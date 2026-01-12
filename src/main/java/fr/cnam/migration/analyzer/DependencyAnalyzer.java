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
 * 1. Configuration des artefacts connus (known-artifacts.yaml par SHA1)
 * 2. Recherche Artifactory par SHA1 (si configuré)
 * 3. Recherche Maven Central par SHA1
 * 4. Patterns d'artefacts internes (DEPFAB.*, jk-socle-*, struts.jar, classes12.jar)
 * 5. Correspondance de pattern sur nom de fichier avec vérification
 * 6. Génération de coordonnées avec version SHA pour les non résolus
 *
 * Les résolutions via Artifactory ou Maven Central sont automatiquement
 * enregistrées dans known-artifacts.yaml pour les prochaines exécutions.
 */
public class DependencyAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(DependencyAnalyzer.class);

    private final KnownArtifactsRegistry knownArtifacts;
    private final InternalArtifactPatterns internalPatterns;
    private final ArtifactoryClient artifactoryClient;
    private final NexusClient nexusClient;
    private final MavenCentralClient mavenCentral;
    private final JarNamePatternMatcher patternMatcher;
    private final JarVersionExtractor versionExtractor;
    private final MigrationConfig config;

    public DependencyAnalyzer(MigrationConfig config) {
        this.config = config;
        this.knownArtifacts = new KnownArtifactsRegistry(config.knownArtifactsFile());
        this.internalPatterns = new InternalArtifactPatterns(config.basePackage());
        this.artifactoryClient = new ArtifactoryClient(config);
        this.nexusClient = new NexusClient(config);
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

        // Tester la connexion Nexus si configuré
        if (config.isNexusConfigured()) {
            if (nexusClient.testConnection()) {
                log.info("Nexus connection successful: {}", config.nexusUrl());
            } else {
                log.warn("Nexus connection failed, will skip Nexus lookups");
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
        if (config.isNexusConfigured()) {
            log.info("Nexus cache stats: {}", nexusClient.getCacheStats());
        }
        log.info("Maven Central cache stats: {}", mavenCentral.getCacheStats());

        // Sauvegarder les nouvelles entrées dans known-artifacts.yaml
        int savedCount = knownArtifacts.saveNewEntries();
        if (savedCount > 0) {
            log.info("Saved {} new entries to known-artifacts.yaml", savedCount);
        }

        return new AnalysisResult(resolved, unresolved);
    }

    /**
     * Tente de résoudre un seul JAR en utilisant le pipeline de résolution.
     *
     * L'ordre de résolution :
     * 1. known-artifacts.yaml (cache des résolutions précédentes par SHA1)
     * 2. Artifactory par SHA1
     * 3. Maven Central par SHA1
     * 4. Patterns internes (DEPFAB.*, struts.jar, classes12.jar, etc.)
     * 5. Correspondance de pattern sur nom de fichier
     * 6. Fallback: génération de coordonnées avec version SHA
     */
    private ResolutionContext resolveJar(JarInfo jar) {
        List<AnalysisResult.ResolutionAttempt> attempts = new ArrayList<>();

        // Stratégie 1 : Vérifier le cache known-artifacts.yaml (par SHA1)
        if (jar.sha1() != null) {
            Optional<MavenCoordinate> known = knownArtifacts.lookupBySha1(jar.sha1());
            if (known.isPresent()) {
                attempts.add(AnalysisResult.ResolutionAttempt.success(
                    ResolutionMethod.KNOWN_CONFIG, known.get()));
                log.debug("Resolved from known artifacts by SHA1: {} -> {}", jar.name(), known.get().toGav());
                return ResolutionContext.resolved(known.get(), ResolutionMethod.KNOWN_CONFIG, attempts);
            }
        }
        attempts.add(AnalysisResult.ResolutionAttempt.failed(
            ResolutionMethod.KNOWN_CONFIG, "Not in known artifacts"));

        // Stratégie 2 : Recherche Artifactory par SHA1
        if (config.isArtifactoryConfigured() && jar.sha1() != null) {
            Optional<MavenCoordinate> fromArtifactory = artifactoryClient.searchBySha1(jar.sha1());
            if (fromArtifactory.isPresent()) {
                MavenCoordinate coord = fromArtifactory.get();
                attempts.add(AnalysisResult.ResolutionAttempt.success(
                    ResolutionMethod.ARTIFACTORY_CHECKSUM, coord));
                log.debug("Resolved from Artifactory by SHA1: {} -> {}", jar.name(), coord.toGav());
                // Enregistrer dans known-artifacts.yaml pour les prochaines exécutions
                knownArtifacts.addEntry(jar.sha1(), coord, jar.originalName());
                return ResolutionContext.resolved(coord, ResolutionMethod.ARTIFACTORY_CHECKSUM, attempts);
            }
            attempts.add(AnalysisResult.ResolutionAttempt.failed(
                ResolutionMethod.ARTIFACTORY_CHECKSUM, "Not found on Artifactory"));
        }

        // Stratégie 2b : Recherche Nexus par SHA1
        if (config.isNexusConfigured() && jar.sha1() != null) {
            Optional<MavenCoordinate> fromNexus = nexusClient.searchBySha1(jar.sha1());
            if (fromNexus.isPresent()) {
                MavenCoordinate coord = fromNexus.get();
                attempts.add(AnalysisResult.ResolutionAttempt.success(
                    ResolutionMethod.NEXUS_CHECKSUM, coord));
                log.debug("Resolved from Nexus by SHA1: {} -> {}", jar.name(), coord.toGav());
                // Enregistrer dans known-artifacts.yaml pour les prochaines exécutions
                knownArtifacts.addEntry(jar.sha1(), coord, jar.originalName());
                return ResolutionContext.resolved(coord, ResolutionMethod.NEXUS_CHECKSUM, attempts);
            }
            attempts.add(AnalysisResult.ResolutionAttempt.failed(
                ResolutionMethod.NEXUS_CHECKSUM, "Not found on Nexus"));
        }

        // Stratégie 3 : Recherche Maven Central par SHA1
        if (!config.skipMavenCentralLookup() && jar.sha1() != null) {
            Optional<MavenCoordinate> bySha1 = mavenCentral.searchBySha1(jar.sha1());
            if (bySha1.isPresent()) {
                MavenCoordinate coord = bySha1.get();
                attempts.add(AnalysisResult.ResolutionAttempt.success(
                    ResolutionMethod.CHECKSUM, coord));
                log.debug("Resolved by SHA1 on Maven Central: {} -> {}", jar.name(), coord.toGav());
                // Enregistrer dans known-artifacts.yaml pour les prochaines exécutions
                knownArtifacts.addEntry(jar.sha1(), coord, jar.originalName());
                return ResolutionContext.resolved(coord, ResolutionMethod.CHECKSUM, attempts);
            }
            attempts.add(AnalysisResult.ResolutionAttempt.failed(
                ResolutionMethod.CHECKSUM, "Not found on Maven Central"));
        }

        // Stratégie 4 : Patterns internes (DEPFAB.*, struts.jar, classes12.jar, etc.)
        if (internalPatterns.isInternal(jar.name())) {
            Optional<MavenCoordinate> coord = internalPatterns.resolve(jar);
            if (coord.isPresent()) {
                log.debug("Resolved as internal: {} -> {}", jar.name(), coord.get().toGav());
                return ResolutionContext.resolved(coord.get(), ResolutionMethod.INTERNAL_PATTERN, attempts);
            }
        }

        // Stratégie 5 : Correspondance de pattern sur nom de fichier avec vérification
        Optional<MavenCoordinate> fromPattern = patternMatcher.match(jar.originalName());
        if (fromPattern.isPresent()) {
            MavenCoordinate coord = fromPattern.get();

            // D'abord vérifier sur Artifactory
            if (config.isArtifactoryConfigured() && artifactoryClient.exists(coord)) {
                attempts.add(AnalysisResult.ResolutionAttempt.success(
                    ResolutionMethod.ARTIFACTORY, coord));
                log.debug("Resolved by pattern, verified on Artifactory: {} -> {}", jar.name(), coord.toGav());
                // Enregistrer dans known-artifacts.yaml
                if (jar.sha1() != null) {
                    knownArtifacts.addEntry(jar.sha1(), coord, jar.originalName());
                }
                return ResolutionContext.resolved(coord, ResolutionMethod.ARTIFACTORY, attempts);
            }

            // Ensuite vérifier sur Nexus
            if (config.isNexusConfigured() && nexusClient.exists(coord)) {
                attempts.add(AnalysisResult.ResolutionAttempt.success(
                    ResolutionMethod.NEXUS, coord));
                log.debug("Resolved by pattern, verified on Nexus: {} -> {}", jar.name(), coord.toGav());
                // Enregistrer dans known-artifacts.yaml
                if (jar.sha1() != null) {
                    knownArtifacts.addEntry(jar.sha1(), coord, jar.originalName());
                }
                return ResolutionContext.resolved(coord, ResolutionMethod.NEXUS, attempts);
            }

            // Ensuite vérifier sur Maven Central
            boolean existsOnCentral = config.skipMavenCentralLookup() || mavenCentral.exists(coord);
            if (existsOnCentral) {
                attempts.add(AnalysisResult.ResolutionAttempt.success(
                    ResolutionMethod.PATTERN, coord));
                log.debug("Resolved by pattern: {} -> {}", jar.name(), coord.toGav());
                // Enregistrer dans known-artifacts.yaml
                if (jar.sha1() != null) {
                    knownArtifacts.addEntry(jar.sha1(), coord, jar.originalName());
                }
                return ResolutionContext.resolved(coord, ResolutionMethod.PATTERN, attempts);
            }

            attempts.add(AnalysisResult.ResolutionAttempt.failed(
                ResolutionMethod.PATTERN, "Pattern matched but not found on Artifactory or Maven Central"));
        } else {
            attempts.add(AnalysisResult.ResolutionAttempt.failed(
                ResolutionMethod.PATTERN, "No pattern matched"));
        }

        // Stratégie 6 : Fallback - génération de coordonnées via pattern générique
        // Cela sera géré par le code appelant qui utilise internalPatterns.resolve()
        // pour les JARs non résolus

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
     * Supprime les doublons exacts (même groupId:artifactId:version).
     * Les JARs avec des versions différentes sont conservés comme dépendances distinctes.
     */
    private List<DependencyInfo> deduplicateAndNormalize(List<DependencyInfo> dependencies) {
        // Grouper par groupId:artifactId:version (coordonnées complètes)
        Map<String, List<DependencyInfo>> grouped = dependencies.stream()
            .collect(Collectors.groupingBy(
                d -> d.groupId() + ":" + d.artifactId() + ":" + d.version(),
                LinkedHashMap::new,
                Collectors.toList()
            ));

        List<DependencyInfo> result = new ArrayList<>();

        for (Map.Entry<String, List<DependencyInfo>> entry : grouped.entrySet()) {
            List<DependencyInfo> duplicates = entry.getValue();
            // Prendre le premier (tous ont les mêmes coordonnées complètes)
            result.add(duplicates.get(0));
            if (duplicates.size() > 1) {
                log.debug("Deduplicated {}: {} duplicates removed", entry.getKey(), duplicates.size() - 1);
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
     * Retourne le client Nexus pour utilisation par d'autres composants.
     */
    public NexusClient getNexusClient() {
        return nexusClient;
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
