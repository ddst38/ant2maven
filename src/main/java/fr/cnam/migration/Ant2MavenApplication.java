package fr.cnam.migration;

import fr.cnam.migration.analyzer.DependencyAnalyzer;
import fr.cnam.migration.autofix.AutoFixService;
import fr.cnam.migration.autofix.model.AutoFixResult;
import fr.cnam.migration.config.MigrationConfig;
import fr.cnam.migration.config.MigrationConfig.DeploymentMode;
import fr.cnam.migration.config.MigrationConfig.RemoteTarget;
import fr.cnam.migration.cve.CveAnalyzer;
import fr.cnam.migration.generator.ProjectGenerator;
import fr.cnam.migration.model.AnalysisResult;
import fr.cnam.migration.model.CveAnalysisResult;
import fr.cnam.migration.model.DependencyInfo;
import fr.cnam.migration.model.JarInfo;
import fr.cnam.migration.model.ProjectStructure;
import fr.cnam.migration.report.ReportGenerator;
import fr.cnam.migration.report.ReportUiClient;
import fr.cnam.migration.scanner.ProjectScanner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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

    // === Options Auto-fix ===

    @Option(
        names = {"--auto-fix"},
        description = "Compile le projet et ajoute automatiquement les dependances provided manquantes"
    )
    private boolean autoFix;

    @Option(
        names = {"--lib-provided"},
        description = "Repertoire des librairies provided (defaut: lib-provided dans ant2maven)"
    )
    private Path libProvidedDir;

    // === Option ReportUI ===

    @Option(
        names = {"--report-ui-url"},
        description = "URL du serveur ReportUI pour soumettre le rapport (ex: http://localhost:8090)"
    )
    private String reportUiUrl;

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
        description = "Deployment mode for unresolved artifacts: LOCAL (install to .m2) or REMOTE (upload to Artifactory/Nexus)",
        defaultValue = "LOCAL"
    )
    private DeploymentMode deploymentMode;

    // === Options Nexus ===

    @Option(
        names = {"--nexus-url"},
        description = "Nexus Repository Manager URL (e.g., https://nexus.company.com)"
    )
    private String nexusUrl;

    @Option(
        names = {"--nexus-cert"},
        description = "Path to SSL certificate file (.crt) for Nexus connection"
    )
    private Path nexusCertPath;

    @Option(
        names = {"--nexus-repo"},
        description = "Nexus repository for artifacts",
        defaultValue = "maven-releases"
    )
    private String nexusRepository;

    @Option(
        names = {"--nexus-user"},
        description = "Nexus username for authentication"
    )
    private String nexusUsername;

    @Option(
        names = {"--nexus-password"},
        description = "Nexus password/token for authentication (can also use NEXUS_PASSWORD env var)"
    )
    private String nexusPassword;

    @Option(
        names = {"--remote-target"},
        description = "Target for REMOTE deployment: ARTIFACTORY or NEXUS",
        defaultValue = "ARTIFACTORY"
    )
    private RemoteTarget remoteTarget;

    @Option(
        names = {"--deploy-repo"},
        description = "Repository for deploying migrated libraries (used in settings.xml and deploy scripts)",
        defaultValue = "java-dette"
    )
    private String deployRepository;

    // === Options Analyse CVE ===

    @Option(
        names = {"--cve-check"},
        description = "Execute l'analyse CVE via OWASP Dependency-Check apres compilation"
    )
    private boolean cveCheck;

    @Option(
        names = {"--nvd-api-key"},
        description = "Cle API NVD pour accelerer les analyses CVE (ou variable NVD_API_KEY)"
    )
    private String nvdApiKey;

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

            // Récupérer le mot de passe Nexus depuis l'environnement si non fourni
            String effectiveNexusPassword = nexusPassword;
            if (effectiveNexusPassword == null || effectiveNexusPassword.isBlank()) {
                effectiveNexusPassword = System.getenv("NEXUS_PASSWORD");
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
                // Paramètres Nexus
                .nexusUrl(nexusUrl)
                .nexusCertPath(nexusCertPath)
                .nexusRepository(nexusRepository)
                .nexusUsername(nexusUsername)
                .nexusPassword(effectiveNexusPassword)
                .remoteTarget(remoteTarget)
                .deployRepository(deployRepository)
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
                    log.info("Artifactory SSL Certificate: {}", config.artifactoryCertPath());
                }
            } else {
                log.info("Artifactory: not configured");
            }

            if (config.isNexusConfigured()) {
                log.info("Nexus: {} (repository: {})",
                    config.nexusUrl(),
                    config.nexusRepository());
                if (config.nexusCertPath() != null) {
                    log.info("Nexus SSL Certificate: {}", config.nexusCertPath());
                }
            } else {
                log.info("Nexus: not configured");
            }

            if (!config.isArtifactoryConfigured() && !config.isNexusConfigured()) {
                log.info("Using Maven Central only for resolution");
            }

            if (config.isRemoteDeployment()) {
                log.info("Remote target: {}", config.remoteTarget());
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
                if (config.isNexusDeployment()) {
                    reportGenerator.generateNexusDeployScript(analysis, config.outputDir(),
                        analyzer.getVersionExtractor(), analyzer.getNexusClient());
                } else {
                    reportGenerator.generateDeployScript(analysis, config.outputDir(),
                        analyzer.getVersionExtractor(), analyzer.getArtifactoryClient());
                }
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
                if (config.isNexusDeployment()) {
                    log.info("2. ./liblocale/deploy-to-nexus.sh       # Upload internal JARs to Nexus");
                } else {
                    log.info("2. ./liblocale/deploy-to-artifactory.sh  # Upload internal JARs to Artifactory");
                }
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

            // Phase 4 : Auto-fix (si active)
            AutoFixResult fixResult = null;
            if (autoFix) {
                log.info("");
                Path effectiveLibProvided = libProvidedDir;
                if (effectiveLibProvided == null) {
                    // Chercher lib-provided dans le repertoire de l'executable
                    effectiveLibProvided = findLibProvidedDir();
                }

                if (effectiveLibProvided != null) {
                    // Passer le NexusClient pour permettre la recherche sur le repository distant
                    AutoFixService autoFixService = new AutoFixService(analyzer.getNexusClient());
                    fixResult = autoFixService.fix(result.outputDir(), effectiveLibProvided);
                    autoFixService.printSummary(fixResult);

                    // Régénérer le rapport HTML avec le résultat auto-fix
                    log.info("Mise à jour du rapport HTML avec les résultats auto-fix...");
                    reportGenerator.generateMigrationReport(project, analysis, config.outputDir(), fixResult);

                    if (!fixResult.isSuccess()) {
                        log.warn("Correction automatique incomplete. Verifiez les erreurs restantes.");
                    } else if (config.isRemoteDeployment()) {
                        // Regénérer le script de déploiement avec les JARs provided
                        log.info("Mise à jour du script de déploiement avec les dépendances provided...");
                        if (config.isNexusDeployment()) {
                            reportGenerator.generateNexusDeployScript(analysis, config.outputDir(),
                                analyzer.getVersionExtractor(), analyzer.getNexusClient(), fixResult);
                        } else {
                            reportGenerator.generateDeployScript(analysis, config.outputDir(),
                                analyzer.getVersionExtractor(), analyzer.getArtifactoryClient(), fixResult);
                        }
                        // Compilation réussie + mode REMOTE = exécuter le déploiement automatique
                        executeRemoteDeployment(result.outputDir(), config);
                    }
                } else {
                    log.warn("Repertoire lib-provided non trouve. Utilisez --lib-provided pour specifier le chemin.");
                }
            } else if (config.isRemoteDeployment()) {
                // Pas d'auto-fix mais mode REMOTE = exécuter le déploiement
                // Note: sans auto-fix, on ne sait pas si la compilation est OK
                log.info("");
                log.info("Mode REMOTE sans auto-fix : le script de déploiement a été généré.");
                log.info("Exécutez-le manuellement après avoir vérifié la compilation.");
            }

            // Phase 5 : Analyse CVE (si activée)
            CveAnalysisResult cveResult = CveAnalysisResult.empty();
            if (cveCheck || "true".equalsIgnoreCase(System.getenv("CVE_CHECK"))) {
                // Récupérer la clé API NVD
                String effectiveNvdApiKey = nvdApiKey;
                if (effectiveNvdApiKey == null || effectiveNvdApiKey.isBlank()) {
                    effectiveNvdApiKey = System.getenv("NVD_API_KEY");
                }

                CveAnalyzer cveAnalyzer = new CveAnalyzer(effectiveNvdApiKey, verbose);
                cveResult = cveAnalyzer.analyze(result.outputDir());

                // Regénérer le rapport HTML avec les CVE
                if (cveResult.analysisPerformed()) {
                    log.info("Mise à jour du rapport HTML avec les résultats CVE...");
                    reportGenerator.generateMigrationReport(project, analysis, config.outputDir(), fixResult, cveResult);
                }
            }

            // Soumettre le rapport à ReportUI si configuré (après auto-fix et CVE pour tout inclure)
            if (reportUiUrl != null && !reportUiUrl.isBlank()) {
                log.info("");
                log.info("Submitting report to ReportUI at {}...", reportUiUrl);
                try {
                    ReportUiClient reportUiClient = new ReportUiClient(reportUiUrl, config.basePackage());
                    // Collecter les librairies déployées si mode REMOTE
                    List<ReportUiClient.DeployedLibrary> deployedLibraries = null;
                    if (config.isRemoteDeployment()) {
                        deployedLibraries = collectDeployedLibraries(analysis, fixResult, config);
                    }
                    reportUiClient.submitReport(project, analysis, fixResult, cveResult, config, deployedLibraries);
                } catch (Exception e) {
                    log.warn("Failed to submit report to ReportUI: {}", e.getMessage());
                }
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

    /**
     * Exécute le script de déploiement vers Nexus ou Artifactory.
     * Appelé uniquement si la compilation a réussi (auto-fix OK) et mode REMOTE actif.
     */
    private void executeRemoteDeployment(Path projectDir, MigrationConfig config) {
        String scriptName;
        String targetName;

        if (config.isNexusDeployment()) {
            scriptName = "deploy-to-nexus.sh";
            targetName = "Nexus";
        } else {
            scriptName = "deploy-to-artifactory.sh";
            targetName = "Artifactory";
        }

        Path scriptPath = projectDir.resolve("liblocale").resolve(scriptName).toAbsolutePath();

        if (!java.nio.file.Files.exists(scriptPath)) {
            log.warn("Script de déploiement non trouvé : {}", scriptPath);
            return;
        }

        log.info("");
        log.info("============================================================");
        log.info("Déploiement automatique vers {}", targetName);
        log.info("============================================================");
        log.info("Exécution de : {}", scriptPath);

        try {
            // Construire la commande avec le password en paramètre si disponible
            ProcessBuilder pb;
            String password = null;

            if (config.isNexusDeployment() && config.nexusPassword() != null) {
                password = config.nexusPassword();
            } else if (config.isArtifactoryDeployment() && config.artifactoryPassword() != null) {
                password = config.artifactoryPassword();
            }

            if (password != null) {
                pb = new ProcessBuilder("/bin/bash", scriptPath.toString(), password);
            } else {
                pb = new ProcessBuilder("/bin/bash", scriptPath.toString());
            }

            pb.directory(projectDir.toAbsolutePath().toFile());
            pb.inheritIO();

            // Passer aussi les variables d'environnement (au cas où)
            Map<String, String> env = pb.environment();
            if (config.isNexusDeployment() && config.nexusPassword() != null) {
                env.put("NEXUS_PASSWORD", config.nexusPassword());
            }
            if (config.isArtifactoryDeployment() && config.artifactoryPassword() != null) {
                env.put("ARTIFACTORY_PASSWORD", config.artifactoryPassword());
            }

            Process process = pb.start();
            int exitCode = process.waitFor();

            if (exitCode == 0) {
                log.info("");
                log.info("Déploiement vers {} terminé avec succès !", targetName);
            } else {
                log.error("Déploiement vers {} échoué (code: {})", targetName, exitCode);
            }

        } catch (IOException e) {
            log.error("Erreur lors de l'exécution du script de déploiement : {}", e.getMessage());
        } catch (InterruptedException e) {
            log.error("Déploiement interrompu");
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Collecte les librairies qui ont été déployées sur le repository distant.
     * Inclut les dépendances locales, non résolues et provided (auto-fix).
     */
    private List<ReportUiClient.DeployedLibrary> collectDeployedLibraries(
            AnalysisResult analysis, AutoFixResult autoFixResult, MigrationConfig config) {

        List<ReportUiClient.DeployedLibrary> deployed = new ArrayList<>();
        String basePackage = config.basePackage();

        // 1. Dépendances nécessitant installation locale (version SHA)
        for (DependencyInfo dep : analysis.localDependencies()) {
            JarInfo jar = dep.sourceJar();
            String type = dep.isInternal() ? "INTERNAL" : "EXTERNAL";
            deployed.add(new ReportUiClient.DeployedLibrary(
                dep.groupId(),
                dep.artifactId(),
                dep.version(),
                jar != null ? jar.name() : dep.artifactId() + ".jar",
                type,
                jar != null ? jar.size() : 0
            ));
        }

        // 2. JARs non résolus
        fr.cnam.migration.analyzer.JarPackageAnalyzer packageAnalyzer =
            new fr.cnam.migration.analyzer.JarPackageAnalyzer();
        for (AnalysisResult.UnresolvedJar unresolved : analysis.unresolved()) {
            JarInfo jar = unresolved.jar();
            String cleanedName = fr.cnam.migration.config.JarNameCleaner.clean(jar.name());
            String artifactName = cleanedName.replace(".jar", "");
            String version = jar.sha1() != null ? "SHA-" + jar.sha1() : "UNKNOWN";

            // Analyser le package pour déterminer le groupId
            String groupId;
            var pkgAnalysis = packageAnalyzer.analyze(jar.path());
            if (pkgAnalysis.inferredGroupId() != null) {
                groupId = pkgAnalysis.inferredGroupId();
            } else {
                groupId = basePackage;
            }
            String type = groupId.startsWith("fr.cnam") ? "INTERNAL" : "EXTERNAL";

            deployed.add(new ReportUiClient.DeployedLibrary(
                groupId,
                artifactName,
                version,
                jar.name(),
                type,
                jar.size()
            ));
        }

        // 3. Dépendances provided (ajoutées par auto-fix)
        if (autoFixResult != null && autoFixResult.addedDependencies() != null) {
            for (var provided : autoFixResult.addedDependencies()) {
                long size = 0;
                try {
                    size = java.nio.file.Files.size(provided.jarPath());
                } catch (Exception ignored) {}

                deployed.add(new ReportUiClient.DeployedLibrary(
                    provided.groupId(),
                    provided.artifactId(),
                    provided.version(),
                    provided.jarPath().getFileName().toString(),
                    "PROVIDED",
                    size
                ));
            }
        }

        return deployed;
    }

    /**
     * Cherche le repertoire lib-provided dans les emplacements standards.
     */
    private Path findLibProvidedDir() {
        // 1. Chercher dans le repertoire courant
        Path current = Path.of("lib-provided");
        if (java.nio.file.Files.isDirectory(current)) {
            return current;
        }

        // 2. Chercher relativement au JAR
        try {
            Path jarPath = Path.of(getClass().getProtectionDomain().getCodeSource().getLocation().toURI());
            Path jarDir = jarPath.getParent();
            if (jarDir != null) {
                // Si on est dans target/, remonter au parent
                if (jarDir.getFileName().toString().equals("target")) {
                    jarDir = jarDir.getParent();
                }
                Path libProvided = jarDir.resolve("lib-provided");
                if (java.nio.file.Files.isDirectory(libProvided)) {
                    return libProvided;
                }
            }
        } catch (Exception e) {
            log.debug("Impossible de determiner l'emplacement du JAR : {}", e.getMessage());
        }

        // 3. Chercher dans ant2maven/lib-provided
        Path ant2maven = Path.of("ant2maven/lib-provided");
        if (java.nio.file.Files.isDirectory(ant2maven)) {
            return ant2maven;
        }

        return null;
    }

    public static void main(String[] args) {
        int exitCode = new CommandLine(new Ant2MavenApplication()).execute(args);
        System.exit(exitCode);
    }
}
