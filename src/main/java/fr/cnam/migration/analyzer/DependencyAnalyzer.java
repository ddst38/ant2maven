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
 * Analyzes project dependencies and resolves them to Maven coordinates.
 *
 * Resolution pipeline (in order):
 * 1. Internal artifact patterns (DEPFAB.*, jk-socle-*, etc.)
 * 2. Known artifacts configuration
 * 3. Artifactory lookup by SHA1 (if configured)
 * 4. Maven Central lookup by SHA1
 * 5. Filename pattern matching with verification
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

        // Test Artifactory connection if configured
        if (config.isArtifactoryConfigured()) {
            if (artifactoryClient.testConnection()) {
                log.info("Artifactory connection successful: {}", config.artifactoryUrl());
            } else {
                log.warn("Artifactory connection failed, will skip Artifactory lookups");
            }
        }
    }

    /**
     * Analyzes all JARs in the project structure and resolves them.
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

        // Post-process: deduplicate and normalize
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
     * Attempts to resolve a single JAR using the resolution pipeline.
     */
    private ResolutionContext resolveJar(JarInfo jar) {
        List<AnalysisResult.ResolutionAttempt> attempts = new ArrayList<>();

        // Strategy 1: Check if it's an internal artifact
        if (internalPatterns.isInternal(jar.name())) {
            Optional<MavenCoordinate> coord = internalPatterns.resolve(jar);
            if (coord.isPresent()) {
                log.debug("Resolved as internal: {} -> {}", jar.name(), coord.get().toGav());
                return ResolutionContext.resolved(coord.get(), ResolutionMethod.INTERNAL_PATTERN, attempts);
            }
        }

        // Strategy 2: Check known artifacts configuration
        Optional<MavenCoordinate> known = knownArtifacts.lookup(jar.name());
        if (known.isPresent()) {
            attempts.add(AnalysisResult.ResolutionAttempt.success(
                ResolutionMethod.KNOWN_CONFIG, known.get()));
            log.debug("Resolved from known artifacts: {} -> {}", jar.name(), known.get().toGav());
            return ResolutionContext.resolved(known.get(), ResolutionMethod.KNOWN_CONFIG, attempts);
        }
        attempts.add(AnalysisResult.ResolutionAttempt.failed(
            ResolutionMethod.KNOWN_CONFIG, "Not in known artifacts"));

        // Strategy 3: Artifactory SHA1 checksum lookup (FIRST, before Maven Central)
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

        // Strategy 4: Maven Central SHA1 checksum lookup
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

        // Strategy 5: Filename pattern matching with Artifactory/Maven Central verification
        Optional<MavenCoordinate> fromPattern = patternMatcher.match(jar.name());
        if (fromPattern.isPresent()) {
            MavenCoordinate coord = fromPattern.get();

            // First check Artifactory
            if (config.isArtifactoryConfigured() && artifactoryClient.exists(coord)) {
                attempts.add(AnalysisResult.ResolutionAttempt.success(
                    ResolutionMethod.ARTIFACTORY, coord));
                log.debug("Resolved by pattern, verified on Artifactory: {} -> {}", jar.name(), coord.toGav());
                return ResolutionContext.resolved(coord, ResolutionMethod.ARTIFACTORY, attempts);
            }

            // Then check Maven Central
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
     * Infers the Maven scope for a dependency.
     */
    private Scope inferScope(JarInfo jar, ProjectStructure project) {
        // Based on JAR category
        switch (jar.category()) {
            case TEST:
                return Scope.TEST;
            case PROVIDED:
                return Scope.PROVIDED;
            case RUNTIME:
                return Scope.RUNTIME;
        }

        // Based on artifact name patterns
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
     * Removes duplicates and normalizes versions.
     */
    private List<DependencyInfo> deduplicateAndNormalize(List<DependencyInfo> dependencies) {
        // Group by groupId:artifactId
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
                // Pick the highest version or the one with non-LOCAL/non-SHA version
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
     * Returns the version extractor for use by other components.
     */
    public JarVersionExtractor getVersionExtractor() {
        return versionExtractor;
    }

    /**
     * Returns the Artifactory client for use by other components.
     */
    public ArtifactoryClient getArtifactoryClient() {
        return artifactoryClient;
    }

    /**
     * Internal context for resolution process.
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
