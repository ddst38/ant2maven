package fr.cnam.migration;

import fr.cnam.migration.analyzer.DependencyAnalyzer;
import fr.cnam.migration.config.MigrationConfig;
import fr.cnam.migration.config.MigrationConfig.DeploymentMode;
import fr.cnam.migration.generator.ProjectGenerator;
import fr.cnam.migration.model.AnalysisResult;
import fr.cnam.migration.model.ProjectStructure;
import fr.cnam.migration.report.ReportGenerator;
import fr.cnam.migration.scanner.ProjectScanner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.util.concurrent.Callable;

/**
 * Outil de migration Ant vers Maven.
 *
 * Transforme les projets CVS basés sur Ant en projets Maven avec migration complète
 * (réorganisation des sources + génération du pom.xml + structure Maven).
 */
@Command(
    name = "ant2maven",
    mixinStandardHelpOptions = true,
    version = "ant2maven 1.1.0",
    description = "Migrates Ant-based CVS projects to Maven projects"
)
public class Ant2MavenApplication implements Callable<Integer> {

    private static final Logger log = LoggerFactory.getLogger(Ant2MavenApplication.class);

    // === Options de base ===

    @Option(
        names = {"-p", "--project"},
        description = "Path to Ant project root (e.g., /path/to/GMIC_J)",
        required = true
    )
    private Path projectRoot;

    @Option(
        names = {"-o", "--output"},
        description = "Output directory for Maven project (default: <project>-maven)"
    )
    private Path outputDir;

    @Option(
        names = {"--known-artifacts"},
        description = "Path to additional known-artifacts.yaml file"
    )
    private Path knownArtifactsFile;

    @Option(
        names = {"--internal-repo"},
        description = "URL of internal Maven repository for proprietary artifacts in generated pom.xml"
    )
    private String internalRepoUrl;

    @Option(
        names = {"--dry-run"},
        description = "Analyze only, do not generate files"
    )
    private boolean dryRun;

    @Option(
        names = {"--profile"},
        description = "Build profile: default or pic",
        defaultValue = "default"
    )
    private String buildVariant;

    @Option(
        names = {"--skip-maven-central"},
        description = "Skip Maven Central lookups (faster, less accurate)"
    )
    private boolean skipMavenCentral;

    @Option(
        names = {"-v", "--verbose"},
        description = "Enable verbose output"
    )
    private boolean verbose;

    @Option(
        names = {"--base-package"},
        description = "Base package for internal artifacts (default: fr.cnamts, can also be fr.cnam)",
        defaultValue = "fr.cnamts"
    )
    private String basePackage;

    // === Options Artifactory ===

    @Option(
        names = {"--artifactory-url"},
        description = "Artifactory server URL (e.g., https://artifactory.company.com/artifactory)"
    )
    private String artifactoryUrl;

    @Option(
        names = {"--artifactory-cert"},
        description = "Path to SSL certificate file (.crt) for Artifactory connection"
    )
    private Path artifactoryCertPath;

    @Option(
        names = {"--artifactory-release-repo"},
        description = "Artifactory repository for release artifacts",
        defaultValue = "libs-release-local"
    )
    private String artifactoryReleaseRepo;

    @Option(
        names = {"--artifactory-snapshot-repo"},
        description = "Artifactory repository for snapshot artifacts",
        defaultValue = "libs-snapshot-local"
    )
    private String artifactorySnapshotRepo;

    @Option(
        names = {"--artifactory-user"},
        description = "Artifactory username for authentication"
    )
    private String artifactoryUsername;

    @Option(
        names = {"--artifactory-password"},
        description = "Artifactory password/token for authentication (can also use ARTIFACTORY_PASSWORD env var)"
    )
    private String artifactoryPassword;

    @Option(
        names = {"--deploy-mode"},
        description = "Deployment mode for unresolved artifacts: LOCAL (install to .m2) or REMOTE (upload to Artifactory)",
        defaultValue = "LOCAL"
    )
    private DeploymentMode deploymentMode;

    @Override
    public Integer call() {
        try {
            log.info("=".repeat(60));
            log.info("Ant to Maven Migration Tool v1.1.0");
            log.info("=".repeat(60));

            // Récupérer le mot de passe Artifactory depuis l'environnement si non fourni
            String effectiveArtifactoryPassword = artifactoryPassword;
            if (effectiveArtifactoryPassword == null || effectiveArtifactoryPassword.isBlank()) {
                effectiveArtifactoryPassword = System.getenv("ARTIFACTORY_PASSWORD");
            }

            // Construire la configuration
            MigrationConfig config = MigrationConfig.builder()
                .projectRoot(projectRoot)
                .outputDir(outputDir)
                .knownArtifactsFile(knownArtifactsFile)
                .internalRepoUrl(internalRepoUrl)
                .dryRun(dryRun)
                .buildVariant(buildVariant)
                .skipMavenCentralLookup(skipMavenCentral)
                .verbose(verbose)
                .basePackage(basePackage)
                // Paramètres Artifactory
                .artifactoryUrl(artifactoryUrl)
                .artifactoryCertPath(artifactoryCertPath)
                .artifactoryReleaseRepo(artifactoryReleaseRepo)
                .artifactorySnapshotRepo(artifactorySnapshotRepo)
                .artifactoryUsername(artifactoryUsername)
                .artifactoryPassword(effectiveArtifactoryPassword)
                .deploymentMode(deploymentMode)
                .build();

            // Afficher la configuration
            log.info("Project: {}", config.projectRoot());
            log.info("Output: {}", config.outputDir());
            log.info("Profile: {}", config.buildVariant());
            log.info("Base package: {}", config.basePackage());
            log.info("Deployment mode: {}", config.deploymentMode());

            if (config.isArtifactoryConfigured()) {
                log.info("Artifactory: {} (release: {}, snapshot: {})",
                    config.artifactoryUrl(),
                    config.artifactoryReleaseRepo(),
                    config.artifactorySnapshotRepo());
                if (config.artifactoryCertPath() != null) {
                    log.info("SSL Certificate: {}", config.artifactoryCertPath());
                }
            } else {
                log.info("Artifactory: not configured (using Maven Central only)");
            }

            // Phase 1 : Scanner la structure du projet
            log.info("");
            log.info("Phase 1: Scanning project structure...");
            ProjectScanner scanner = new ProjectScanner();
            ProjectStructure project = scanner.scan(config);

            log.info("Project type: {}", project.type());
            log.info("Found {} JARs ({} main, {} test)",
                project.allJars().size(),
                project.mainLibs().size(),
                project.testLibs().size());
            log.info("Found {} Java source files", project.sourceLayout().mainJavaFileCount());
            log.info("Found {} internal dependencies", project.internalDeps().size());

            // Phase 2 : Analyser les dépendances
            log.info("");
            log.info("Phase 2: Analyzing dependencies...");
            DependencyAnalyzer analyzer = new DependencyAnalyzer(config);
            AnalysisResult analysis = analyzer.analyze(project);

            log.info("Resolved: {} dependencies", analysis.resolved().size());
            log.info("Unresolved: {} dependencies", analysis.unresolved().size());
            log.info("Success rate: {}%", String.format("%.1f", analysis.successRate()));

            // Générer les rapports (même en mode dry-run)
            log.info("");
            log.info("Generating reports...");
            ReportGenerator reportGenerator = new ReportGenerator(config);
            reportGenerator.generateMigrationReport(project, analysis, config.outputDir());

            if (config.dryRun()) {
                log.info("");
                log.info("Dry run complete. No files were generated.");
                log.info("Review migration-report.html for analysis results.");
                return 0;
            }

            // Phase 3 : Générer le projet Maven
            log.info("");
            log.info("Phase 3: Generating Maven project...");
            ProjectGenerator generator = new ProjectGenerator(config);
            ProjectGenerator.GenerationResult result = generator.generate(project, analysis);

            // Générer les rapports et scripts additionnels
            reportGenerator.generateLibNotFoundCsv(analysis, config.outputDir());
            reportGenerator.generateInstallScript(analysis, config.outputDir(), analyzer.getVersionExtractor());

            if (config.isRemoteDeployment()) {
                reportGenerator.generateDeployScript(analysis, config.outputDir(),
                    analyzer.getVersionExtractor(), analyzer.getArtifactoryClient());
            }

            // Résumé
            log.info("");
            log.info("=".repeat(60));
            log.info("Migration complete!");
            log.info("=".repeat(60));
            log.info("Output directory: {}", result.outputDir());
            log.info("Files created: {}", result.createdFiles().size());
            log.info("");
            log.info("Next steps:");
            log.info("1. cd {}", result.outputDir());

            if (config.isRemoteDeployment()) {
                log.info("2. ./liblocale/deploy-to-artifactory.sh  # Upload internal JARs to Artifactory");
            } else {
                log.info("2. ./liblocale/install-local-jars.sh  # Install internal JARs locally");
            }

            log.info("3. ./mvnw compile                     # Verify compilation");
            log.info("4. ./mvnw dependency:analyze          # Check unused dependencies");
            log.info("5. ./mvnw package                     # Build WAR/EAR");

            if (!analysis.unresolved().isEmpty()) {
                log.info("");
                log.warn("Note: {} unresolved dependencies need manual attention.",
                    analysis.unresolved().size());
                log.warn("See libnotfound.csv and migration-report.html for details.");
            }

            return 0;

        } catch (Exception e) {
            log.error("Migration failed: {}", e.getMessage());
            if (verbose) {
                log.error("Stack trace:", e);
            }
            return 1;
        }
    }

    public static void main(String[] args) {
        int exitCode = new CommandLine(new Ant2MavenApplication()).execute(args);
        System.exit(exitCode);
    }
}
