package fr.cnam.migration.analyzer;

import fr.cnam.migration.config.InternalArtifactPatterns;
import fr.cnam.migration.config.KnownArtifactsRegistry;
import fr.cnam.migration.config.MigrationConfig;
import fr.cnam.migration.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Analyse les dépendances du projet et les résout en coordonnées Maven.
 *
 * Pipeline de résolution (dans l'ordre) :
 * 1. Patterns d'artefacts internes (DEPFAB.*, jk-socle-*, etc.)
 * 2. Configuration des artefacts connus
 * 3. Recherche Artifactory par SHA1 (si configuré)
 * 4. Recherche Maven Central par SHA1
 * 5. Correspondance de pattern sur nom de fichier avec vérification
 */
public class DependencyAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(DependencyAnalyzer.class);

    private final KnownArtifactsRegistry knownArtifacts;
    private final InternalArtifactPatterns internalPatterns;
    private final ArtifactoryClient artifactoryClient;
    private final MavenCentralClient mavenCentral;
    private final JarNamePatternMatcher patternMatcher;
    private final JarVersionExtractor versionExtractor;
    private final MigrationConfig config;

    public DependencyAnalyzer(MigrationConfig config) {
        this.config = config;
        this.knownArtifacts = new KnownArtifactsRegistry(config.knownArtifactsFile());
        this.internalPatterns = new InternalArtifactPatterns(config.basePackage());
        this.artifactoryClient = new ArtifactoryClient(config);
        this.mavenCentral = new MavenCentralClient();
        this.patternMatcher = new JarNamePatternMatcher();
        this.versionExtractor = new JarVersionExtractor();

        // Tester la connexion Artifactory si configuré
        if (config.isArtifactoryConfigured()) {
            if (artifactoryClient.testConnection()) {
                log.info("Artifactory connection successful: {}", config.artifactoryUrl());
            } else {
                log.warn("Artifactory connection failed, will skip Artifactory lookups");
            }
        }
    }

    /**
     * Analyse tous les JARs dans la structure du projet et les résout.
     */
    public AnalysisResult analyze(ProjectStructure project) {
        log.info("Analyzing {} JARs for project {}", project.allJars().size(), project.name());

        List<DependencyInfo> resolved = new ArrayList<>();
        List<AnalysisResult.UnresolvedJar> unresolved = new ArrayList<>();

        for (JarInfo jar : project.allJars()) {
            if (jar.isSourceJar()) {
                log.debug("Skipping source JAR: {}", jar.name());
                continue;
            }

            ResolutionContext ctx = resolveJar(jar);

            if (ctx.isResolved()) {
                Scope scope = inferScope(jar, project);
                resolved.add(new DependencyInfo(
                    ctx.coordinate(),
                    scope,
                    ctx.method(),
                    jar
                ));
            } else {
                unresolved.add(new AnalysisResult.UnresolvedJar(jar, ctx.attempts()));
            }
        }

        // Post-traitement : dédupliquer et normaliser
        resolved = deduplicateAndNormalize(resolved);

        log.info("Resolution complete: {} resolved, {} unresolved",
            resolved.size(), unresolved.size());

        if (config.isArtifactoryConfigured()) {
            log.info("Artifactory cache stats: {}", artifactoryClient.getCacheStats());
        }
        log.info("Maven Central cache stats: {}", mavenCentral.getCacheStats());

        return new AnalysisResult(resolved, unresolved);
    }

    /**
     * Tente de résoudre un seul JAR en utilisant le pipeline de résolution.
     */
    private ResolutionContext resolveJar(JarInfo jar) {
        List<AnalysisResult.ResolutionAttempt> attempts = new ArrayList<>();

        // Stratégie 1 : Vérifier si c'est un artefact interne
        if (internalPatterns.isInternal(jar.name())) {
            Optional<MavenCoordinate> coord = internalPatterns.resolve(jar);
            if (coord.isPresent()) {
                log.debug("Resolved as internal: {} -> {}", jar.name(), coord.get().toGav());
                return ResolutionContext.resolved(coord.get(), ResolutionMethod.INTERNAL_PATTERN, attempts);
            }
        }

        // Stratégie 2 : Vérifier la configuration des artefacts connus
        Optional<MavenCoordinate> known = knownArtifacts.lookup(jar.name());
        if (known.isPresent()) {
            attempts.add(AnalysisResult.ResolutionAttempt.success(
                ResolutionMethod.KNOWN_CONFIG, known.get()));
            log.debug("Resolved from known artifacts: {} -> {}", jar.name(), known.get().toGav());
            return ResolutionContext.resolved(known.get(), ResolutionMethod.KNOWN_CONFIG, attempts);
        }
        attempts.add(AnalysisResult.ResolutionAttempt.failed(
            ResolutionMethod.KNOWN_CONFIG, "Not in known artifacts"));

        // Stratégie 3 : Recherche Artifactory par SHA1 (EN PREMIER, avant Maven Central)
        if (config.isArtifactoryConfigured() && jar.sha1() != null) {
            Optional<MavenCoordinate> fromArtifactory = artifactoryClient.searchBySha1(jar.sha1());
            if (fromArtifactory.isPresent()) {
                attempts.add(AnalysisResult.ResolutionAttempt.success(
                    ResolutionMethod.ARTIFACTORY_CHECKSUM, fromArtifactory.get()));
                log.debug("Resolved from Artifactory by SHA1: {} -> {}", jar.name(), fromArtifactory.get().toGav());
                return ResolutionContext.resolved(fromArtifactory.get(), ResolutionMethod.ARTIFACTORY_CHECKSUM, attempts);
            }
            attempts.add(AnalysisResult.ResolutionAttempt.failed(
                ResolutionMethod.ARTIFACTORY_CHECKSUM, "Not found on Artifactory"));
        }

        // Stratégie 4 : Recherche Maven Central par SHA1
        if (!config.skipMavenCentralLookup() && jar.sha1() != null) {
            Optional<MavenCoordinate> bySha1 = mavenCentral.searchBySha1(jar.sha1());
            if (bySha1.isPresent()) {
                attempts.add(AnalysisResult.ResolutionAttempt.success(
                    ResolutionMethod.CHECKSUM, bySha1.get()));
                log.debug("Resolved by SHA1 on Maven Central: {} -> {}", jar.name(), bySha1.get().toGav());
                return ResolutionContext.resolved(bySha1.get(), ResolutionMethod.CHECKSUM, attempts);
            }
            attempts.add(AnalysisResult.ResolutionAttempt.failed(
                ResolutionMethod.CHECKSUM, "Not found on Maven Central"));
        }

        // Stratégie 5 : Correspondance de pattern sur nom de fichier avec vérification Artifactory/Maven Central
        Optional<MavenCoordinate> fromPattern = patternMatcher.match(jar.name());
        if (fromPattern.isPresent()) {
            MavenCoordinate coord = fromPattern.get();

            // D'abord vérifier sur Artifactory
            if (config.isArtifactoryConfigured() && artifactoryClient.exists(coord)) {
                attempts.add(AnalysisResult.ResolutionAttempt.success(
                    ResolutionMethod.ARTIFACTORY, coord));
                log.debug("Resolved by pattern, verified on Artifactory: {} -> {}", jar.name(), coord.toGav());
                return ResolutionContext.resolved(coord, ResolutionMethod.ARTIFACTORY, attempts);
            }

            // Ensuite vérifier sur Maven Central
            boolean existsOnCentral = config.skipMavenCentralLookup() || mavenCentral.exists(coord);
            if (existsOnCentral) {
                attempts.add(AnalysisResult.ResolutionAttempt.success(
                    ResolutionMethod.PATTERN, coord));
                log.debug("Resolved by pattern: {} -> {}", jar.name(), coord.toGav());
                return ResolutionContext.resolved(coord, ResolutionMethod.PATTERN, attempts);
            }

            attempts.add(AnalysisResult.ResolutionAttempt.failed(
                ResolutionMethod.PATTERN, "Pattern matched but not found on Artifactory or Maven Central"));
        } else {
            attempts.add(AnalysisResult.ResolutionAttempt.failed(
                ResolutionMethod.PATTERN, "No pattern matched"));
        }

        log.debug("Failed to resolve: {}", jar.name());
        return ResolutionContext.unresolved(attempts);
    }

    /**
     * Infère le scope Maven pour une dépendance.
     */
    private Scope inferScope(JarInfo jar, ProjectStructure project) {
        // Basé sur la catégorie du JAR
        switch (jar.category()) {
            case TEST:
                return Scope.TEST;
            case PROVIDED:
                return Scope.PROVIDED;
            case RUNTIME:
                return Scope.RUNTIME;
        }

        // Basé sur les patterns de nom d'artefact
        String name = jar.name().toLowerCase();
        if (name.contains("-test") || name.contains("junit") ||
            name.contains("mockito") || name.contains("dbunit") ||
            name.contains("assertj") || name.contains("hamcrest")) {
            return Scope.TEST;
        }

        if (name.contains("servlet-api") || name.contains("jsp-api") ||
            name.contains("weblogic")) {
            return Scope.PROVIDED;
        }

        return Scope.COMPILE;
    }

    /**
     * Supprime les doublons et normalise les versions.
     */
    private List<DependencyInfo> deduplicateAndNormalize(List<DependencyInfo> dependencies) {
        // Grouper par groupId:artifactId
        Map<String, List<DependencyInfo>> grouped = dependencies.stream()
            .collect(Collectors.groupingBy(
                d -> d.groupId() + ":" + d.artifactId(),
                LinkedHashMap::new,
                Collectors.toList()
            ));

        List<DependencyInfo> result = new ArrayList<>();

        for (Map.Entry<String, List<DependencyInfo>> entry : grouped.entrySet()) {
            List<DependencyInfo> versions = entry.getValue();
            if (versions.size() == 1) {
                result.add(versions.get(0));
            } else {
                // Prendre la version la plus haute ou celle avec une version non-LOCAL/non-SHA
                DependencyInfo best = versions.stream()
                    .filter(d -> !"LOCAL".equals(d.version()) && !d.version().startsWith("SHA-"))
                    .max(Comparator.comparing(d -> d.version()))
                    .orElse(versions.get(0));

                log.debug("Deduplicated {}: {} versions -> {}", entry.getKey(),
                    versions.size(), best.version());
                result.add(best);
            }
        }

        return result;
    }

    /**
     * Retourne l'extracteur de version pour utilisation par d'autres composants.
     */
    public JarVersionExtractor getVersionExtractor() {
        return versionExtractor;
    }

    /**
     * Retourne le client Artifactory pour utilisation par d'autres composants.
     */
    public ArtifactoryClient getArtifactoryClient() {
        return artifactoryClient;
    }

    /**
     * Contexte interne pour le processus de résolution.
     */
    private record ResolutionContext(
        boolean isResolved,
        MavenCoordinate coordinate,
        ResolutionMethod method,
        List<AnalysisResult.ResolutionAttempt> attempts
    ) {
        static ResolutionContext resolved(MavenCoordinate coord, ResolutionMethod method,
                                         List<AnalysisResult.ResolutionAttempt> attempts) {
            return new ResolutionContext(true, coord, method, attempts);
        }

        static ResolutionContext unresolved(List<AnalysisResult.ResolutionAttempt> attempts) {
            return new ResolutionContext(false, null, ResolutionMethod.UNRESOLVED, attempts);
        }
    }
}
