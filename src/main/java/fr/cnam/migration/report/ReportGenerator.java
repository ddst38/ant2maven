package fr.cnam.migration.report;

import fr.cnam.migration.analyzer.ArtifactoryClient;
import fr.cnam.migration.analyzer.NexusClient;
import fr.cnam.migration.analyzer.JarPackageAnalyzer;
import fr.cnam.migration.analyzer.JarVersionExtractor;
import fr.cnam.migration.config.InternalArtifactPatterns;
import fr.cnam.migration.config.JarNameCleaner;
import fr.cnam.migration.config.MigrationConfig;
import fr.cnam.migration.generator.TemplateService;
import fr.cnam.migration.model.*;
import fr.cnam.migration.autofix.model.AutoFixResult;
import fr.cnam.migration.model.CveAnalysisResult;
import fr.cnam.migration.model.CveInfo;
import fr.cnam.migration.model.CveSeverity;
import fr.cnam.migration.autofix.model.MissingDependency;
import fr.cnam.migration.autofix.model.ProvidedDependency;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Génère les rapports de migration, scripts d'installation et scripts de déploiement.
 *
 * Fonctionnalités :
 * - Rapport de migration (HTML)
 * - CSV des bibliothèques non résolues (libnotfound.csv)
 * - Script d'installation locale (install-local-jars.sh)
 * - Script de déploiement Artifactory (deploy-to-artifactory.sh)
 * - Versionnement basé sur SHA pour les JARs non versionnés afin d'éviter les collisions
 */
public class ReportGenerator {

    private static final Logger log = LoggerFactory.getLogger(ReportGenerator.class);

    private final TemplateService templateService;
    private final InternalArtifactPatterns internalPatterns;
    private final MigrationConfig config;

    public ReportGenerator(String basePackage) {
        this(MigrationConfig.builder().basePackage(basePackage).build());
    }

    public ReportGenerator(MigrationConfig config) {
        this.config = config;
        this.templateService = new TemplateService();
        this.internalPatterns = new InternalArtifactPatterns(
            config != null ? config.basePackage() : MigrationConfig.DEFAULT_BASE_PACKAGE);
    }

    /**
     * Génère le fichier libnotfound.csv pour les JARs non trouvés sur Maven Central/Artifactory.
     * Inclut les JARs non résolus ET les JARs résolus localement (version LOCAL).
     */
    public void generateLibNotFoundCsv(AnalysisResult analysis, Path outputDir)
            throws IOException {
        Path csvFile = outputDir.resolve("libnotfound.csv");
        int count = 0;

        try (CSVPrinter printer = new CSVPrinter(
                Files.newBufferedWriter(csvFile),
                CSVFormat.DEFAULT.builder()
                    .setHeader("JAR Name", "Size (bytes)", "SHA1", "Resolution Method",
                              "Maven Coordinate", "Status")
                    .build())) {

            // 1. JARs résolus avec version LOCAL (non trouvés sur Maven Central)
            for (DependencyInfo dep : analysis.localDependencies()) {
                JarInfo jar = dep.sourceJar();
                if (jar == null) continue;

                printer.printRecord(
                    jar.name(),
                    jar.size(),
                    jar.sha1() != null ? jar.sha1() : "N/A",
                    dep.method().getDescription(),
                    dep.groupId() + ":" + dep.artifactId() + ":" + dep.version(),
                    "Résolu localement - à installer via install-local-jars.sh"
                );
                count++;
            }

            // 2. JARs non résolus
            for (AnalysisResult.UnresolvedJar unresolved : analysis.unresolved()) {
                JarInfo jar = unresolved.jar();

                String methods = unresolved.attempts().stream()
                    .map(a -> a.method().name())
                    .collect(Collectors.joining(", "));

                String suggestedCoord = internalPatterns.resolve(jar)
                    .map(MavenCoordinate::toGav).orElse("N/A");

                printer.printRecord(
                    jar.name(),
                    jar.size(),
                    jar.sha1() != null ? jar.sha1() : "N/A",
                    methods,
                    suggestedCoord,
                    "Non résolu - recherche manuelle requise"
                );
                count++;
            }
        }

        log.info("Generated libnotfound.csv with {} entries", count);
    }

    /**
     * Génère le script install-local-jars.sh pour le mode de déploiement LOCAL.
     *
     * IMPORTANT: Les coordonnées Maven utilisées dans ce script DOIVENT correspondre
     * exactement à celles déclarées dans le pom.xml du module WAR. Sinon Maven ne
     * pourra pas résoudre les dépendances.
     *
     * Les noms de fichiers sont nettoyés (suppression de DEPFAB. et codes projet)
     * pour correspondre aux fichiers copiés dans liblocale.
     */
    public void generateInstallScript(AnalysisResult analysis, Path outputDir,
                                      JarVersionExtractor versionExtractor) throws IOException {
        List<Map<String, String>> jarsToInstall = new ArrayList<>();
        String basePackage = config != null ? config.basePackage() : MigrationConfig.DEFAULT_BASE_PACKAGE;

        // 1. Collecter les dépendances avec version LOCAL
        // Ces JARs utilisent les MÊMES coordonnées que dans le pom.xml
        for (DependencyInfo dep : analysis.localDependencies()) {
            JarInfo jar = dep.sourceJar();

            // Nettoyer le nom du fichier (supprimer DEPFAB. et code projet)
            String cleanedFileName = JarNameCleaner.clean(jar.name());

            // Utiliser les MÊMES coordonnées que dans le pom.xml
            // C'est crucial pour que Maven puisse résoudre les dépendances
            jarsToInstall.add(Map.of(
                "originalName", jar.name(),
                "fileName", cleanedFileName,
                "groupId", dep.groupId(),
                "artifactId", dep.artifactId(),
                "version", dep.version(),
                "versionSource", dep.method().getDescription(),
                "isInternal", "true"
            ));
        }

        // 2. Collecter les JARs non résolus
        // Ces JARs utilisent les coordonnées générées avec SHA comme version
        // Analyse le package réel du JAR pour déterminer le groupId correct
        JarPackageAnalyzer packageAnalyzer = new JarPackageAnalyzer();
        for (AnalysisResult.UnresolvedJar unresolved : analysis.unresolved()) {
            JarInfo jar = unresolved.jar();

            // Nettoyer le nom du fichier (supprimer DEPFAB. et code projet)
            String cleanedFileName = JarNameCleaner.clean(jar.name());
            String artifactName = cleanedFileName.replace(".jar", "");
            // Utiliser le SHA1 comme version pour garantir l'unicité
            String version = jar.sha1() != null ? "SHA-" + jar.sha1() : "UNKNOWN";

            // Analyser le package réel du JAR pour déterminer le groupId
            String groupId;
            JarPackageAnalyzer.PackageAnalysis pkgAnalysis = packageAnalyzer.analyze(jar.path());
            if (pkgAnalysis.inferredGroupId() != null) {
                groupId = pkgAnalysis.inferredGroupId();
            } else {
                groupId = basePackage;
            }
            boolean isInternal = groupId.startsWith("fr.cnam");

            // Utiliser les MÊMES coordonnées que dans le pom.xml
            jarsToInstall.add(Map.of(
                "originalName", jar.name(),
                "fileName", cleanedFileName,
                "groupId", groupId,
                "artifactId", artifactName,
                "version", version,
                "versionSource", "Non résolu - version basée sur SHA",
                "isInternal", String.valueOf(isInternal)
            ));
        }

        // Générer le script
        Path scriptDir = outputDir.resolve("liblocale");
        Files.createDirectories(scriptDir);

        StringBuilder script = new StringBuilder();
        script.append("#!/bin/bash\n");
        script.append("# Installation des JARs dans le repository Maven local du projet\n");
        script.append("# Généré par ant2maven le ").append(LocalDateTime.now().format(
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))).append("\n");
        script.append("#\n");
        script.append("# Ce script installe les JARs qui ne sont pas disponibles sur Maven Central.\n");
        script.append("# Les coordonnées Maven correspondent EXACTEMENT à celles du pom.xml.\n");
        script.append("#\n");
        script.append("# Note: Les préfixes DEPFAB. et codes projet ont été supprimés des noms de fichiers.\n");
        script.append("# Note: Utilise le repository local du projet (localMvnRepository/)\n");
        script.append("\n");
        script.append("set -e\n");
        script.append("cd \"$(dirname \"$0\")/..\"\n");
        script.append("\n");
        script.append("echo \"Installation des JARs dans le repository Maven local du projet...\"\n");
        script.append("\n");

        for (Map<String, String> jar : jarsToInstall) {
            String originalName = jar.get("originalName");
            String fileName = jar.get("fileName");
            String typeMarker = "true".equals(jar.get("isInternal")) ? "[Interne]" : "[Non résolu]";

            // Afficher le nom original si différent du nom nettoyé
            if (!originalName.equals(fileName)) {
                script.append("# ").append(typeMarker).append(" ").append(originalName).append(" -> ").append(fileName).append("\n");
            } else {
                script.append("# ").append(typeMarker).append(" ").append(fileName).append("\n");
            }
            script.append("# Source: ").append(jar.get("versionSource")).append("\n");
            script.append("./mvnw -s .mvn/wrapper/settings.xml install:install-file \\\n");
            script.append("    -Dfile=\"liblocale/").append(fileName).append("\" \\\n");
            script.append("    -DgroupId=\"").append(jar.get("groupId")).append("\" \\\n");
            script.append("    -DartifactId=\"").append(jar.get("artifactId")).append("\" \\\n");
            script.append("    -Dversion=\"").append(jar.get("version")).append("\" \\\n");
            script.append("    -Dpackaging=jar\n");
            script.append("\n");
        }

        script.append("echo \"Terminé ! ").append(jarsToInstall.size()).append(" JARs installés.\"\n");

        Path scriptFile = scriptDir.resolve("install-local-jars.sh");
        Files.writeString(scriptFile, script.toString());
        scriptFile.toFile().setExecutable(true);

        log.info("Script install-local-jars.sh généré pour {} JARs", jarsToInstall.size());
    }

    /**
     * Génère le script d'installation sans extracteur de version (compatibilité ascendante).
     */
    public void generateInstallScript(AnalysisResult analysis, Path outputDir) throws IOException {
        generateInstallScript(analysis, outputDir, new JarVersionExtractor());
    }

    /**
     * Génère le script deploy-to-artifactory.sh pour le mode de déploiement REMOTE.
     *
     * IMPORTANT: Les coordonnées Maven utilisées dans ce script DOIVENT correspondre
     * exactement à celles déclarées dans le pom.xml du module WAR.
     *
     * Les noms de fichiers sont nettoyés (suppression de DEPFAB. et codes projet)
     * pour correspondre aux fichiers copiés dans liblocale.
     */
    public void generateDeployScript(AnalysisResult analysis, Path outputDir,
                                     JarVersionExtractor versionExtractor,
                                     ArtifactoryClient artifactoryClient) throws IOException {
        generateDeployScript(analysis, outputDir, versionExtractor, artifactoryClient, null);
    }

    /**
     * Génère le script deploy-to-artifactory.sh avec support des JARs provided (auto-fix).
     */
    public void generateDeployScript(AnalysisResult analysis, Path outputDir,
                                     JarVersionExtractor versionExtractor,
                                     ArtifactoryClient artifactoryClient,
                                     AutoFixResult autoFixResult) throws IOException {
        if (config == null || !config.isArtifactoryConfigured()) {
            log.warn("Artifactory non configuré, génération du script de déploiement ignorée");
            return;
        }

        // Repository de déploiement (configurable via --deploy-repo)
        final String DEPLOY_REPO = config.deployRepository();

        List<Map<String, String>> deployableJars = new ArrayList<>();
        String basePackage = config.basePackage();

        // 1. Collecter les dépendances nécessitant une installation locale
        // (uniquement celles qui ne sont pas déjà présentes sur un repo distant)
        for (DependencyInfo dep : analysis.localDependencies()) {
            JarInfo jar = dep.sourceJar();
            String cleanedFileName = JarNameCleaner.clean(jar.name());
            MavenCoordinate coord = dep.coordinate();

            deployableJars.add(Map.of(
                "originalName", jar.name(),
                "fileName", cleanedFileName,
                "path", jar.path().toAbsolutePath().toString(),
                "groupId", coord.groupId(),
                "artifactId", coord.artifactId(),
                "version", coord.version(),
                "versionSource", dep.method().getDescription(),
                "type", dep.isInternal() ? "Interne" : "Externe",
                "deployCommand", artifactoryClient.generateDeployCommand(jar.path(), coord, DEPLOY_REPO)
            ));
        }

        // 2. Collecter les JARs non résolus
        JarPackageAnalyzer deployPackageAnalyzer = new JarPackageAnalyzer();
        for (AnalysisResult.UnresolvedJar unresolved : analysis.unresolved()) {
            JarInfo jar = unresolved.jar();
            String cleanedFileName = JarNameCleaner.clean(jar.name());
            String artifactName = cleanedFileName.replace(".jar", "");
            String version = jar.sha1() != null ? "SHA-" + jar.sha1() : "UNKNOWN";

            String groupId;
            JarPackageAnalyzer.PackageAnalysis pkgAnalysis = deployPackageAnalyzer.analyze(jar.path());
            if (pkgAnalysis.inferredGroupId() != null) {
                groupId = pkgAnalysis.inferredGroupId();
            } else {
                groupId = basePackage;
            }

            MavenCoordinate coord = new MavenCoordinate(groupId, artifactName, version);

            deployableJars.add(Map.of(
                "originalName", jar.name(),
                "fileName", cleanedFileName,
                "path", jar.path().toAbsolutePath().toString(),
                "groupId", coord.groupId(),
                "artifactId", coord.artifactId(),
                "version", coord.version(),
                "versionSource", "Non résolu - version basée sur SHA",
                "type", "Non résolu",
                "deployCommand", artifactoryClient.generateDeployCommand(jar.path(), coord, DEPLOY_REPO)
            ));
        }

        // 3. Collecter les JARs provided (auto-fix)
        if (autoFixResult != null && autoFixResult.addedDependencies() != null) {
            for (ProvidedDependency provided : autoFixResult.addedDependencies()) {
                MavenCoordinate coord = new MavenCoordinate(
                    provided.groupId(), provided.artifactId(), provided.version());

                deployableJars.add(Map.of(
                    "originalName", provided.jarPath().getFileName().toString(),
                    "fileName", provided.jarPath().getFileName().toString(),
                    "path", provided.jarPath().toAbsolutePath().toString(),
                    "groupId", coord.groupId(),
                    "artifactId", coord.artifactId(),
                    "version", coord.version(),
                    "versionSource", "Auto-fix (provided)",
                    "type", "Provided",
                    "deployCommand", artifactoryClient.generateDeployCommand(provided.jarPath(), coord, DEPLOY_REPO)
                ));
            }
        }

        // Générer le script de déploiement
        Path scriptDir = outputDir.resolve("liblocale");
        Files.createDirectories(scriptDir);

        StringBuilder script = new StringBuilder();
        script.append("#!/bin/bash\n");
        script.append("# Déploiement des JARs vers Artifactory\n");
        script.append("# Généré par ant2maven le ").append(LocalDateTime.now().format(
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))).append("\n");
        script.append("#\n");
        script.append("# Artifactory: ").append(config.artifactoryUrl()).append("\n");
        script.append("# Repository: ").append(DEPLOY_REPO).append("\n");
        script.append("#\n");
        script.append("# Les coordonnées Maven correspondent EXACTEMENT à celles du pom.xml.\n");
        script.append("#\n");
        script.append("# Note: Les préfixes DEPFAB. et codes projet ont été supprimés des noms de fichiers.\n");
        script.append("#\n");
        script.append("# IMPORTANT: Le mot de passe peut être passé via:\n");
        script.append("#   - Variable d'environnement ARTIFACTORY_PASSWORD\n");
        script.append("#   - Paramètre du script: ./deploy-to-artifactory.sh mon_password\n");
        script.append("\n");
        script.append("set -e\n");
        script.append("cd \"$(dirname \"$0\")\"\n");
        script.append("\n");
        script.append("# Récupérer le password: paramètre > variable d'environnement\n");
        script.append("if [ -n \"$1\" ]; then\n");
        script.append("    ARTIFACTORY_PASSWORD=\"$1\"\n");
        script.append("fi\n");
        script.append("\n");
        script.append("if [ -z \"$ARTIFACTORY_PASSWORD\" ]; then\n");
        script.append("    echo \"ERREUR: Mot de passe Artifactory non défini.\"\n");
        script.append("    echo \"Utilisez: export ARTIFACTORY_PASSWORD=xxx ou ./deploy-to-artifactory.sh xxx\"\n");
        script.append("    exit 1\n");
        script.append("fi\n");
        script.append("\n");
        script.append("echo \"Déploiement des JARs vers Artifactory...\"\n");
        script.append("echo \"Cible: ").append(config.artifactoryUrl()).append("/")
               .append(DEPLOY_REPO).append("\"\n");
        script.append("\n");

        for (Map<String, String> jar : deployableJars) {
            String typeMarker = "[" + jar.get("type") + "]";
            String originalName = jar.get("originalName");
            String fileName = jar.get("fileName");

            // Afficher le nom original si différent du nom nettoyé
            if (!originalName.equals(fileName)) {
                script.append("# ").append(typeMarker).append(" ").append(originalName).append(" -> ").append(fileName).append("\n");
            } else {
                script.append("# ").append(typeMarker).append(" ").append(fileName).append("\n");
            }
            script.append("# Source: ").append(jar.get("versionSource")).append("\n");
            script.append("# Coordonnée: ").append(jar.get("groupId")).append(":")
                   .append(jar.get("artifactId")).append(":").append(jar.get("version")).append("\n");
            script.append("echo \"Déploiement de ").append(fileName).append("...\"\n");
            script.append(jar.get("deployCommand")).append("\n");
            script.append("\n");
        }

        script.append("echo \"Terminé ! ").append(deployableJars.size()).append(" JARs déployés.\"\n");

        Path scriptFile = scriptDir.resolve("deploy-to-artifactory.sh");
        Files.writeString(scriptFile, script.toString());
        scriptFile.toFile().setExecutable(true);

        log.info("Script deploy-to-artifactory.sh généré pour {} JARs", deployableJars.size());
    }

    /**
     * Génère le script deploy-to-nexus.sh pour le mode de déploiement REMOTE vers Nexus.
     *
     * IMPORTANT: Les coordonnées Maven utilisées dans ce script DOIVENT correspondre
     * exactement à celles déclarées dans le pom.xml du module WAR.
     *
     * Les noms de fichiers sont nettoyés (suppression de DEPFAB. et codes projet)
     * pour correspondre aux fichiers copiés dans liblocale.
     */
    public void generateNexusDeployScript(AnalysisResult analysis, Path outputDir,
                                          JarVersionExtractor versionExtractor,
                                          NexusClient nexusClient) throws IOException {
        generateNexusDeployScript(analysis, outputDir, versionExtractor, nexusClient, null);
    }

    /**
     * Génère le script deploy-to-nexus.sh avec support des JARs provided (auto-fix).
     */
    public void generateNexusDeployScript(AnalysisResult analysis, Path outputDir,
                                          JarVersionExtractor versionExtractor,
                                          NexusClient nexusClient,
                                          AutoFixResult autoFixResult) throws IOException {
        if (config == null || !config.isNexusConfigured()) {
            log.warn("Nexus non configuré, génération du script de déploiement ignorée");
            return;
        }

        // Repository de déploiement (configurable via --deploy-repo)
        final String DEPLOY_REPO = config.deployRepository();

        List<Map<String, String>> deployableJars = new ArrayList<>();
        String basePackage = config.basePackage();

        // 1. Collecter les dépendances nécessitant une installation locale
        // (uniquement celles qui ne sont pas déjà présentes sur un repo distant)
        for (DependencyInfo dep : analysis.localDependencies()) {
            JarInfo jar = dep.sourceJar();

            String cleanedFileName = JarNameCleaner.clean(jar.name());
            MavenCoordinate coord = dep.coordinate();

            deployableJars.add(Map.of(
                "originalName", jar.name(),
                "fileName", cleanedFileName,
                "path", jar.path().toAbsolutePath().toString(),
                "groupId", coord.groupId(),
                "artifactId", coord.artifactId(),
                "version", coord.version(),
                "versionSource", dep.method().getDescription(),
                "type", dep.isInternal() ? "Interne" : "Externe",
                "deployCommand", nexusClient.generateDeployCommand(jar.path(), coord, false, DEPLOY_REPO)
            ));
        }

        // 2. Collecter les JARs non résolus
        JarPackageAnalyzer deployPackageAnalyzer = new JarPackageAnalyzer();
        for (AnalysisResult.UnresolvedJar unresolved : analysis.unresolved()) {
            JarInfo jar = unresolved.jar();

            String cleanedFileName = JarNameCleaner.clean(jar.name());
            String artifactName = cleanedFileName.replace(".jar", "");
            String version = jar.sha1() != null ? "SHA-" + jar.sha1() : "UNKNOWN";

            String groupId;
            JarPackageAnalyzer.PackageAnalysis pkgAnalysis = deployPackageAnalyzer.analyze(jar.path());
            if (pkgAnalysis.inferredGroupId() != null) {
                groupId = pkgAnalysis.inferredGroupId();
            } else {
                groupId = basePackage;
            }

            MavenCoordinate coord = new MavenCoordinate(groupId, artifactName, version);

            deployableJars.add(Map.of(
                "originalName", jar.name(),
                "fileName", cleanedFileName,
                "path", jar.path().toAbsolutePath().toString(),
                "groupId", coord.groupId(),
                "artifactId", coord.artifactId(),
                "version", coord.version(),
                "versionSource", "Non résolu - version basée sur SHA",
                "type", "Non résolu",
                "deployCommand", nexusClient.generateDeployCommand(jar.path(), coord, false, DEPLOY_REPO)
            ));
        }

        // 3. Collecter les JARs provided (auto-fix)
        if (autoFixResult != null && autoFixResult.addedDependencies() != null) {
            for (ProvidedDependency provided : autoFixResult.addedDependencies()) {
                MavenCoordinate coord = new MavenCoordinate(
                    provided.groupId(), provided.artifactId(), provided.version());

                deployableJars.add(Map.of(
                    "originalName", provided.jarPath().getFileName().toString(),
                    "fileName", provided.jarPath().getFileName().toString(),
                    "path", provided.jarPath().toAbsolutePath().toString(),
                    "groupId", coord.groupId(),
                    "artifactId", coord.artifactId(),
                    "version", coord.version(),
                    "versionSource", "Auto-fix (provided)",
                    "type", "Provided",
                    "deployCommand", nexusClient.generateDeployCommand(provided.jarPath(), coord, false, DEPLOY_REPO)
                ));
            }
        }

        // Générer le script de déploiement
        Path scriptDir = outputDir.resolve("liblocale");
        Files.createDirectories(scriptDir);

        StringBuilder script = new StringBuilder();
        script.append("#!/bin/bash\n");
        script.append("# Déploiement des JARs vers Nexus\n");
        script.append("# Généré par ant2maven le ").append(LocalDateTime.now().format(
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))).append("\n");
        script.append("#\n");
        script.append("# Nexus: ").append(config.nexusUrl()).append("\n");
        script.append("# Repository: ").append(DEPLOY_REPO).append("\n");
        script.append("#\n");
        script.append("# Les coordonnées Maven correspondent EXACTEMENT à celles du pom.xml.\n");
        script.append("#\n");
        script.append("# Note: Les préfixes DEPFAB. et codes projet ont été supprimés des noms de fichiers.\n");
        script.append("#\n");
        script.append("# IMPORTANT: Le mot de passe peut être passé via:\n");
        script.append("#   - Variable d'environnement NEXUS_PASSWORD\n");
        script.append("#   - Paramètre du script: ./deploy-to-nexus.sh mon_password\n");
        script.append("\n");
        script.append("set -e\n");
        script.append("cd \"$(dirname \"$0\")\"\n");
        script.append("\n");
        script.append("# Récupérer le password: paramètre > variable d'environnement\n");
        script.append("if [ -n \"$1\" ]; then\n");
        script.append("    NEXUS_PASSWORD=\"$1\"\n");
        script.append("fi\n");
        script.append("\n");
        script.append("if [ -z \"$NEXUS_PASSWORD\" ]; then\n");
        script.append("    echo \"ERREUR: Mot de passe Nexus non défini.\"\n");
        script.append("    echo \"Utilisez: export NEXUS_PASSWORD=xxx ou ./deploy-to-nexus.sh xxx\"\n");
        script.append("    exit 1\n");
        script.append("fi\n");
        script.append("\n");
        script.append("echo \"Déploiement des JARs vers Nexus...\"\n");
        script.append("echo \"Cible: ").append(config.nexusUrl()).append("/repository/")
               .append(DEPLOY_REPO).append("\"\n");
        script.append("\n");

        for (Map<String, String> jar : deployableJars) {
            String typeMarker = "[" + jar.get("type") + "]";
            String originalName = jar.get("originalName");
            String fileName = jar.get("fileName");

            if (!originalName.equals(fileName)) {
                script.append("# ").append(typeMarker).append(" ").append(originalName).append(" -> ").append(fileName).append("\n");
            } else {
                script.append("# ").append(typeMarker).append(" ").append(fileName).append("\n");
            }
            script.append("# Source: ").append(jar.get("versionSource")).append("\n");
            script.append("# Coordonnée: ").append(jar.get("groupId")).append(":")
                   .append(jar.get("artifactId")).append(":").append(jar.get("version")).append("\n");
            script.append("echo \"Déploiement de ").append(fileName).append("...\"\n");
            script.append(jar.get("deployCommand")).append("\n");
            script.append("\n");
        }

        script.append("echo \"Terminé ! ").append(deployableJars.size()).append(" JARs déployés.\"\n");

        Path scriptFile = scriptDir.resolve("deploy-to-nexus.sh");
        Files.writeString(scriptFile, script.toString());
        scriptFile.toFile().setExecutable(true);

        log.info("Script deploy-to-nexus.sh généré pour {} JARs", deployableJars.size());
    }

    /**
     * Génère un rapport de migration HTML (sans résultat auto-fix).
     */
    public void generateMigrationReport(ProjectStructure project, AnalysisResult analysis,
                                        Path outputDir) throws IOException {
        generateMigrationReport(project, analysis, outputDir, null, null);
    }

    /**
     * Génère un rapport de migration HTML entièrement en français.
     * @param autoFixResult Résultat de l'auto-fix (peut être null si --auto-fix non utilisé)
     */
    public void generateMigrationReport(ProjectStructure project, AnalysisResult analysis,
                                        Path outputDir, AutoFixResult autoFixResult) throws IOException {
        generateMigrationReport(project, analysis, outputDir, autoFixResult, null);
    }

    /**
     * Génère un rapport de migration HTML avec analyse CVE.
     * @param autoFixResult Résultat de l'auto-fix (peut être null)
     * @param cveResult Résultat de l'analyse CVE (peut être null)
     */
    public void generateMigrationReport(ProjectStructure project, AnalysisResult analysis,
                                        Path outputDir, AutoFixResult autoFixResult,
                                        CveAnalysisResult cveResult) throws IOException {
        Files.createDirectories(outputDir);
        Path reportFile = outputDir.resolve("migration-report.html");

        StringBuilder html = new StringBuilder();
        html.append("""
            <!DOCTYPE html>
            <html lang="fr">
            <head>
                <meta charset="UTF-8">
                <title>Rapport de migration - %s</title>
                <style>
                    body { font-family: Arial, sans-serif; margin: 20px; }
                    h1 { color: #333; }
                    h2 { color: #666; border-bottom: 1px solid #ccc; padding-bottom: 5px; }
                    h3 { color: #555; margin-top: 20px; }
                    table { border-collapse: collapse; width: 100%%; margin: 10px 0; }
                    th, td { border: 1px solid #ddd; padding: 8px; text-align: left; }
                    th { background-color: #f4f4f4; }
                    tr:nth-child(even) { background-color: #f9f9f9; }
                    .success { color: green; }
                    .warning { color: orange; }
                    .error { color: red; }
                    .info { color: #0066cc; }
                    .stat-box { display: inline-block; padding: 15px; margin: 10px; background: #f0f0f0; border-radius: 5px; }
                    .stat-value { font-size: 24px; font-weight: bold; }
                    .stat-label { color: #666; }
                    .config-table { width: auto; }
                    .config-table td { padding: 4px 12px; }
                    .source-ear { font-style: italic; color: #666; font-size: 0.9em; }
                    .loaded { background-color: #d4edda; }
                    .not-loaded { background-color: #f8d7da; }
                    .conflict-warning { color: #856404; background-color: #fff3cd; padding: 10px; border-radius: 5px; margin: 10px 0; }
                </style>
            </head>
            <body>
            """.formatted(project.name()));

        // En-tête
        html.append("<h1>Rapport de migration : ").append(project.name()).append("</h1>");
        html.append("<p>Généré le : ")
            .append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd/MM/yyyy à HH:mm:ss")))
            .append("</p>");

        // Section Configuration
        if (config != null) {
            html.append("<h2>Configuration</h2>");
            html.append("<table class='config-table'>");
            html.append("<tr><td><strong>Package de base :</strong></td><td>").append(config.basePackage()).append("</td></tr>");
            html.append("<tr><td><strong>Mode de déploiement :</strong></td><td>").append(config.deploymentMode()).append("</td></tr>");
            if (config.isArtifactoryConfigured()) {
                html.append("<tr><td><strong>Artifactory :</strong></td><td>").append(config.artifactoryUrl()).append("</td></tr>");
                html.append("<tr><td><strong>Dépôt Release :</strong></td><td>").append(config.artifactoryReleaseRepo()).append("</td></tr>");
            }
            if (config.isNexusConfigured()) {
                html.append("<tr><td><strong>Nexus :</strong></td><td>").append(config.nexusUrl()).append("</td></tr>");
                html.append("<tr><td><strong>Dépôt Nexus :</strong></td><td>").append(config.nexusRepository()).append("</td></tr>");
            }
            if (config.isRemoteDeployment() && config.remoteTarget() != null) {
                html.append("<tr><td><strong>Cible Remote :</strong></td><td>").append(config.remoteTarget()).append("</td></tr>");
            }
            html.append("</table>");
        }

        // Calcul des statistiques cohérentes avec les sections détaillées
        int totalDetected = project.allJars().size();
        int resolvedCount = (int) analysis.resolved().stream()
            .filter(d -> !d.needsLocalInstall())
            .count();
        int unresolvedCount = (int) analysis.resolved().stream()
            .filter(DependencyInfo::needsLocalInstall)
            .count() + analysis.unresolved().size();
        int totalAfterDedup = resolvedCount + unresolvedCount;
        double successRate = totalAfterDedup > 0 ? (resolvedCount * 100.0 / totalAfterDedup) : 0;

        // Statistiques résumées
        html.append("<h2>Résumé</h2>");
        html.append("<div class='stat-box'>");
        html.append("<div class='stat-value'>").append(totalDetected).append("</div>");
        html.append("<div class='stat-label'>JARs détectés</div></div>");

        html.append("<div class='stat-box'>");
        html.append("<div class='stat-value success'>").append(resolvedCount).append("</div>");
        html.append("<div class='stat-label'>Résolus</div></div>");

        html.append("<div class='stat-box'>");
        html.append("<div class='stat-value warning'>").append(unresolvedCount).append("</div>");
        html.append("<div class='stat-label'>Non résolus</div></div>");

        html.append("<div class='stat-box'>");
        html.append("<div class='stat-value'>").append(String.format("%.1f%%", successRate)).append("</div>");
        html.append("<div class='stat-label'>Taux de succès</div></div>");

        // =====================================================================
        // NOUVELLE SECTION : Bibliothèques détectées initialement
        // =====================================================================
        html.append("<h2>Bibliothèques détectées dans le projet (").append(project.allJars().size()).append(")</h2>");
        html.append("<p><em>Liste complète des fichiers JAR trouvés dans le projet source, avant tout traitement.</em></p>");
        html.append("<table><tr><th>#</th><th>Nom du fichier</th><th>Taille</th><th>Source</th><th>Catégorie</th></tr>");

        int index = 1;
        for (JarInfo jar : project.allJars()) {
            String source = jar.sourceEar() != null ?
                "<span class='source-ear'>Extrait de " + jar.sourceEar() + "</span>" :
                "Répertoire lib";
            String categoryClass = jar.category() == JarInfo.JarCategory.MAIN ? "success" :
                                   jar.category() == JarInfo.JarCategory.TEST ? "warning" : "";
            String categoryName = switch (jar.category()) {
                case MAIN -> "Principal";
                case TEST -> "Test";
                case PROVIDED -> "Fourni";
                case RUNTIME -> "Exécution";
                case UNKNOWN -> "Inconnu";
            };

            html.append("<tr>");
            html.append("<td>").append(index++).append("</td>");
            html.append("<td><code>").append(jar.name()).append("</code></td>");
            html.append("<td>").append(formatSize(jar.size())).append("</td>");
            html.append("<td>").append(source).append("</td>");
            html.append("<td class='").append(categoryClass).append("'>").append(categoryName).append("</td>");
            html.append("</tr>");
        }
        html.append("</table>");

        // Méthodes de résolution
        html.append("<h2>Méthodes de résolution utilisées</h2>");
        html.append("<table><tr><th>Méthode</th><th>Nombre</th></tr>");
        Map<ResolutionMethod, List<DependencyInfo>> byMethod = analysis.byMethod();
        for (ResolutionMethod method : ResolutionMethod.values()) {
            if (method != ResolutionMethod.UNRESOLVED) {
                int count = byMethod.getOrDefault(method, List.of()).size();
                if (count > 0) {
                    html.append("<tr><td>").append(getMethodDescriptionFr(method)).append("</td>");
                    html.append("<td>").append(count).append("</td></tr>");
                }
            }
        }
        html.append("</table>");

        // Dépendances par scope
        html.append("<h2>Dépendances par portée (scope)</h2>");
        html.append("<table><tr><th>Portée</th><th>Nombre</th></tr>");
        Map<Scope, List<DependencyInfo>> byScope = analysis.byScope();
        for (Scope scope : Scope.values()) {
            int count = byScope.getOrDefault(scope, List.of()).size();
            if (count > 0) {
                html.append("<tr><td>").append(getScopeDescriptionFr(scope)).append("</td>");
                html.append("<td>").append(count).append("</td></tr>");
            }
        }
        html.append("</table>");

        // =====================================================================
        // SECTION : Conflits de versions
        // =====================================================================
        // Grouper par groupId:artifactId (sans version) pour détecter les doublons
        Map<String, List<DependencyInfo>> byGaWithoutVersion = analysis.resolved().stream()
            .collect(Collectors.groupingBy(
                d -> d.groupId() + ":" + d.artifactId(),
                LinkedHashMap::new,
                Collectors.toList()
            ));

        // Filtrer ceux qui ont plusieurs versions différentes
        Map<String, List<DependencyInfo>> conflicts = byGaWithoutVersion.entrySet().stream()
            .filter(e -> e.getValue().stream()
                .map(DependencyInfo::version)
                .distinct()
                .count() > 1)
            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (a,b)->a, LinkedHashMap::new));

        if (!conflicts.isEmpty()) {
            html.append("<h2 class='warning'>⚠️ Conflits de versions (").append(conflicts.size()).append(")</h2>");
            html.append("<p class='conflict-warning'>Ces bibliothèques sont déclarées plusieurs fois avec des versions différentes. ");
            html.append("Dans Maven, la <strong>dernière version déclarée</strong> dans le POM est celle qui est chargée dans le classpath.</p>");
            html.append("<table><tr><th>Bibliothèque</th><th>Versions</th><th>Statut classpath</th></tr>");

            for (Map.Entry<String, List<DependencyInfo>> entry : conflicts.entrySet()) {
                String ga = entry.getKey();
                List<DependencyInfo> deps = entry.getValue();

                // Trouver l'index max dans la liste resolved() pour déterminer la version chargée
                List<DependencyInfo> resolvedList = analysis.resolved();
                int maxIndex = -1;
                DependencyInfo loadedDep = null;
                for (DependencyInfo dep : deps) {
                    int idx = resolvedList.indexOf(dep);
                    if (idx > maxIndex) {
                        maxIndex = idx;
                        loadedDep = dep;
                    }
                }

                html.append("<tr><td><code>").append(ga).append("</code></td>");
                html.append("<td>");

                // Collecter les versions uniques avec leur statut
                Set<String> seenVersions = new LinkedHashSet<>();
                for (DependencyInfo dep : deps) {
                    String version = dep.version();
                    if (!seenVersions.contains(version)) {
                        seenVersions.add(version);
                        boolean isLoaded = loadedDep != null && version.equals(loadedDep.version());
                        String cssClass = isLoaded ? "loaded" : "not-loaded";
                        String marker = isLoaded ? " ✓" : "";
                        html.append("<span class='").append(cssClass).append("'>").append(version).append(marker).append("</span><br>");
                    }
                }

                html.append("</td>");
                html.append("<td>Version <code>").append(loadedDep != null ? loadedDep.version() : "?").append("</code> chargée (dernière déclarée)</td>");
                html.append("</tr>");
            }
            html.append("</table>");
        }

        // =====================================================================
        // SECTION : Bibliothèques résolues (Maven Central ou Artifactory uniquement)
        // =====================================================================
        // Seules les bibliothèques trouvées sur Maven Central, Artifactory ou Nexus sont "résolues"
        // Inclut INTERNAL_PATTERN si les coordonnées ne nécessitent pas d'installation locale
        List<DependencyInfo> trulyResolved = analysis.resolved().stream()
            .filter(d -> d.method() == ResolutionMethod.CHECKSUM ||
                        d.method() == ResolutionMethod.ARTIFACTORY ||
                        d.method() == ResolutionMethod.ARTIFACTORY_CHECKSUM ||
                        d.method() == ResolutionMethod.NEXUS ||
                        d.method() == ResolutionMethod.NEXUS_CHECKSUM ||
                        (d.method() == ResolutionMethod.KNOWN_CONFIG && !d.needsLocalInstall()) ||
                        (d.method() == ResolutionMethod.INTERNAL_PATTERN && !d.needsLocalInstall()))
            .toList();

        html.append("<h2>Bibliothèques résolues (").append(trulyResolved.size()).append(")</h2>");
        html.append("<p><em>Ces bibliothèques seront téléchargées automatiquement par Maven depuis un dépôt distant.</em></p>");

        // Sous-section : Résolues depuis Maven Central (inclut patterns internes avec coordonnées Maven valides)
        List<DependencyInfo> resolvedMavenCentral = trulyResolved.stream()
            .filter(d -> d.method() == ResolutionMethod.CHECKSUM ||
                        (d.method() == ResolutionMethod.KNOWN_CONFIG && !d.needsLocalInstall()) ||
                        (d.method() == ResolutionMethod.INTERNAL_PATTERN && !d.needsLocalInstall()))
            .toList();

        if (!resolvedMavenCentral.isEmpty()) {
            List<DependencyInfo> mcExternal = resolvedMavenCentral.stream().filter(d -> !d.isInternal()).toList();
            List<DependencyInfo> mcInternal = resolvedMavenCentral.stream().filter(DependencyInfo::isInternal).toList();

            html.append("<h3 class='success'>Depuis Maven Central (").append(resolvedMavenCentral.size()).append(")</h3>");

            if (!mcExternal.isEmpty()) {
                html.append("<h4>Externes (").append(mcExternal.size()).append(")</h4>");
                html.append("<table><tr><th>JAR d'origine</th><th>Coordonnées Maven</th><th>Méthode</th></tr>");
                for (DependencyInfo dep : mcExternal) {
                    appendResolvedDependencyRow(html, dep);
                }
                html.append("</table>");
            }

            if (!mcInternal.isEmpty()) {
                html.append("<h4>Internes fr.cnam* (").append(mcInternal.size()).append(")</h4>");
                html.append("<table><tr><th>JAR d'origine</th><th>Coordonnées Maven</th><th>Méthode</th></tr>");
                for (DependencyInfo dep : mcInternal) {
                    appendResolvedDependencyRow(html, dep);
                }
                html.append("</table>");
            }
        }

        // Sous-section : Résolues depuis Artifactory
        List<DependencyInfo> resolvedArtifactory = trulyResolved.stream()
            .filter(d -> d.method() == ResolutionMethod.ARTIFACTORY ||
                        d.method() == ResolutionMethod.ARTIFACTORY_CHECKSUM)
            .toList();

        if (!resolvedArtifactory.isEmpty()) {
            List<DependencyInfo> artExternal = resolvedArtifactory.stream().filter(d -> !d.isInternal()).toList();
            List<DependencyInfo> artInternal = resolvedArtifactory.stream().filter(DependencyInfo::isInternal).toList();

            html.append("<h3 class='success'>Depuis Artifactory (").append(resolvedArtifactory.size()).append(")</h3>");

            if (!artExternal.isEmpty()) {
                html.append("<h4>Externes (").append(artExternal.size()).append(")</h4>");
                html.append("<table><tr><th>JAR d'origine</th><th>Coordonnées Maven</th><th>Méthode</th></tr>");
                for (DependencyInfo dep : artExternal) {
                    appendResolvedDependencyRow(html, dep);
                }
                html.append("</table>");
            }

            if (!artInternal.isEmpty()) {
                html.append("<h4>Internes fr.cnam* (").append(artInternal.size()).append(")</h4>");
                html.append("<table><tr><th>JAR d'origine</th><th>Coordonnées Maven</th><th>Méthode</th></tr>");
                for (DependencyInfo dep : artInternal) {
                    appendResolvedDependencyRow(html, dep);
                }
                html.append("</table>");
            }
        }

        // Sous-section : Résolues depuis Nexus
        List<DependencyInfo> resolvedNexus = trulyResolved.stream()
            .filter(d -> d.method() == ResolutionMethod.NEXUS ||
                        d.method() == ResolutionMethod.NEXUS_CHECKSUM)
            .toList();

        if (!resolvedNexus.isEmpty()) {
            List<DependencyInfo> nexusExternal = resolvedNexus.stream().filter(d -> !d.isInternal()).toList();
            List<DependencyInfo> nexusInternal = resolvedNexus.stream().filter(DependencyInfo::isInternal).toList();

            html.append("<h3 class='success'>Depuis Nexus (").append(resolvedNexus.size()).append(")</h3>");

            if (!nexusExternal.isEmpty()) {
                html.append("<h4>Externes (").append(nexusExternal.size()).append(")</h4>");
                html.append("<table><tr><th>JAR d'origine</th><th>Coordonnées Maven</th><th>Méthode</th></tr>");
                for (DependencyInfo dep : nexusExternal) {
                    appendResolvedDependencyRow(html, dep);
                }
                html.append("</table>");
            }

            if (!nexusInternal.isEmpty()) {
                html.append("<h4>Internes fr.cnam* (").append(nexusInternal.size()).append(")</h4>");
                html.append("<table><tr><th>JAR d'origine</th><th>Coordonnées Maven</th><th>Méthode</th></tr>");
                for (DependencyInfo dep : nexusInternal) {
                    appendResolvedDependencyRow(html, dep);
                }
                html.append("</table>");
            }
        }

        // =====================================================================
        // SECTION : Bibliothèques non résolues (installation locale requise)
        // =====================================================================
        // Un seul tableau simple avec toutes les bibliothèques nécessitant une installation locale
        List<DependencyInfo> needsLocalInstall = analysis.resolved().stream()
            .filter(DependencyInfo::needsLocalInstall)
            .toList();

        int totalUnresolved = needsLocalInstall.size() + analysis.unresolved().size();

        if (totalUnresolved > 0) {
            html.append("<h2 class='warning'>Bibliothèques non résolues (").append(totalUnresolved).append(")</h2>");
            html.append("<p><em>Ces bibliothèques ne sont pas disponibles sur Maven Central ou Artifactory. ");
            html.append("Elles doivent être installées via <code>./liblocale/install-local-jars.sh</code></em></p>");

            html.append("<table><tr><th>JAR d'origine</th><th>Coordonnées Maven</th><th>Taille</th></tr>");

            // 1. Dépendances identifiées par pattern mais nécessitant installation locale
            for (DependencyInfo dep : needsLocalInstall) {
                String originalName = dep.sourceJar().name();
                String cleanedName = JarNameCleaner.clean(originalName);
                String displayName = originalName.equals(cleanedName) ? originalName : originalName + " → " + cleanedName;

                html.append("<tr><td><code>").append(displayName).append("</code></td>");
                html.append("<td><code>").append(dep.coordinate().toGav()).append("</code></td>");
                html.append("<td>").append(formatSize(dep.sourceJar().size())).append("</td></tr>");
            }

            // 2. Dépendances vraiment non résolues
            JarPackageAnalyzer reportPackageAnalyzer = new JarPackageAnalyzer();
            String basePackage = config != null ? config.basePackage() : MigrationConfig.DEFAULT_BASE_PACKAGE;

            for (AnalysisResult.UnresolvedJar unresolved : analysis.unresolved()) {
                JarInfo jar = unresolved.jar();
                String originalName = jar.name();
                String cleanedName = JarNameCleaner.clean(originalName);
                String displayName = originalName.equals(cleanedName) ? originalName : originalName + " → " + cleanedName;
                String artifactName = cleanedName.replace(".jar", "");
                String version = jar.sha1() != null ? "SHA-" + jar.sha1() : "UNKNOWN";

                // Analyser le package réel du JAR pour déterminer le groupId
                String groupId;
                JarPackageAnalyzer.PackageAnalysis pkgAnalysis = reportPackageAnalyzer.analyze(jar.path());
                if (pkgAnalysis.inferredGroupId() != null) {
                    groupId = pkgAnalysis.inferredGroupId();
                } else {
                    groupId = basePackage;
                }

                html.append("<tr><td><code>").append(displayName).append("</code></td>");
                html.append("<td><code>").append(groupId).append(":").append(artifactName).append(":").append(version).append("</code></td>");
                html.append("<td>").append(formatSize(jar.size())).append("</td></tr>");
            }

            html.append("</table>");
        }

        // =====================================================================
        // SECTION : Bibliothèques provided (ajoutées par auto-fix)
        // =====================================================================
        if (autoFixResult != null && !autoFixResult.addedDependencies().isEmpty()) {
            html.append("<h2 class='success'>✅ Bibliothèques provided (").append(autoFixResult.addedDependencies().size()).append(")</h2>");
            html.append("<p><em>Ces bibliothèques ont été ajoutées automatiquement par le mode <code>--auto-fix</code> ");
            html.append("depuis le répertoire <code>lib-provided/</code>.</em></p>");
            html.append("<table><tr><th>Coordonnées Maven</th><th>Fichier JAR</th><th>Scope</th></tr>");

            for (ProvidedDependency dep : autoFixResult.addedDependencies()) {
                html.append("<tr>");
                html.append("<td><code>").append(dep.toGav()).append("</code></td>");
                html.append("<td><code>").append(dep.jarPath().getFileName()).append("</code></td>");
                html.append("<td>provided</td>");
                html.append("</tr>");
            }

            html.append("</table>");
        }

        // =====================================================================
        // SECTION : Packages manquants (uniquement si auto-fix activé et échec)
        // =====================================================================
        if (autoFixResult != null && !autoFixResult.isSuccess() && !autoFixResult.unresolvedErrors().isEmpty()) {
            html.append("<h2 class='error'>❌ Packages manquants (").append(autoFixResult.unresolvedErrors().size()).append(")</h2>");
            html.append("<p><em>Ces packages/classes n'ont pas pu être résolus malgré le mode <code>--auto-fix</code>. ");
            html.append("Le projet ne compile pas.</em></p>");
            html.append("<table><tr><th>Type</th><th>Nom complet</th><th>Package</th><th>Fichier source</th></tr>");

            for (MissingDependency missing : autoFixResult.unresolvedErrors()) {
                html.append("<tr>");
                html.append("<td>").append(missing.type()).append("</td>");
                html.append("<td><code>").append(missing.name()).append("</code></td>");
                html.append("<td><code>").append(missing.getPackage()).append("</code></td>");
                html.append("<td>").append(missing.sourceFile() != null ? missing.sourceFile() : "-").append("</td>");
                html.append("</tr>");
            }

            html.append("</table>");
            html.append("<p class='info'><strong>Actions suggérées :</strong> Ajouter les JARs manquants dans <code>lib-provided/</code> puis relancer avec <code>--auto-fix</code></p>");
        }

        // =====================================================================
        // SECTION : Analyse des vulnérabilités CVE
        // =====================================================================
        if (cveResult != null && cveResult.analysisPerformed()) {
            appendCveSection(html, cveResult);
        }

        // Informations du projet
        html.append("<h2>Informations sur le projet</h2>");
        html.append("<table>");
        html.append("<tr><th>Propriété</th><th>Valeur</th></tr>");
        html.append("<tr><td>Type de projet</td><td>").append(project.type()).append("</td></tr>");
        html.append("<tr><td>Fichiers Java principaux</td><td>")
            .append(project.sourceLayout().mainJavaFileCount()).append("</td></tr>");
        html.append("<tr><td>Fichiers Java de test</td><td>")
            .append(project.sourceLayout().testJavaFileCount()).append("</td></tr>");
        html.append("<tr><td>Dépendances internes (properties.conf)</td><td>")
            .append(project.internalDeps().size()).append("</td></tr>");
        html.append("</table>");

        html.append("</body></html>");

        Files.writeString(reportFile, html.toString());
        log.info("Rapport migration-report.html généré");
    }

    /**
     * Retourne la description française d'une méthode de résolution.
     */
    private String getMethodDescriptionFr(ResolutionMethod method) {
        return switch (method) {
            case KNOWN_CONFIG -> "Configuration connue (known-artifacts.yaml)";
            case INTERNAL_PATTERN -> "Pattern d'artefact interne";
            case ARTIFACTORY_CHECKSUM -> "Checksum Artifactory";
            case ARTIFACTORY -> "Recherche Artifactory";
            case NEXUS_CHECKSUM -> "Checksum Nexus";
            case NEXUS -> "Recherche Nexus";
            case CHECKSUM -> "Checksum Maven Central";
            case MANIFEST -> "Analyse MANIFEST.MF";
            case PATTERN -> "Pattern de nom de fichier";
            case PACKAGE_ANALYSIS -> "Analyse des packages du JAR";
            case UNRESOLVED -> "Non résolu";
        };
    }

    /**
     * Retourne la description française d'une portée Maven.
     */
    private String getScopeDescriptionFr(Scope scope) {
        return switch (scope) {
            case COMPILE -> "Compilation (compile)";
            case PROVIDED -> "Fourni (provided)";
            case RUNTIME -> "Exécution (runtime)";
            case TEST -> "Test (test)";
            case SYSTEM -> "Système (system)";
        };
    }

    /**
     * Suggère une action en français pour un JAR non résolu.
     */
    private String suggestActionFr(JarInfo jar) {
        String name = jar.name();

        if (name.startsWith("DEPFAB.") || name.startsWith("jk-socle-")) {
            return "Artefact interne - sera installé automatiquement";
        }
        if (name.contains("oracle") || name.equals("classes12.jar")) {
            return "Oracle JDBC - télécharger depuis Oracle et installer manuellement";
        }
        if (name.contains("-sources")) {
            return "JAR source - peut être exclu des dépendances";
        }
        if (name.startsWith("Service") || name.startsWith("SOCA")) {
            return "Service CNAM - sera installé automatiquement";
        }
        if (name.equals("struts.jar")) {
            return "Struts legacy - mapper vers org.apache.struts:struts-core:1.3.10";
        }
        return "Rechercher manuellement sur Maven Central ou le site du fournisseur";
    }

    /**
     * Ajoute une ligne de tableau pour une dépendance résolue.
     */
    private void appendResolvedDependencyRow(StringBuilder html, DependencyInfo dep) {
        String originalName = dep.sourceJar().name();
        String cleanedName = JarNameCleaner.clean(originalName);
        String displayName = originalName.equals(cleanedName) ? originalName : originalName + " → " + cleanedName;

        html.append("<tr><td><code>").append(displayName).append("</code></td>");
        html.append("<td><code>").append(dep.coordinate().toGav()).append("</code></td>");
        html.append("<td>").append(getMethodDescriptionFr(dep.method())).append("</td></tr>");
    }

    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
    }

    /**
     * Génère la section CVE du rapport HTML.
     */
    private void appendCveSection(StringBuilder html, CveAnalysisResult cveResult) {
        // Styles CSS additionnels pour CVE
        html.append("""
            <style>
                .cve-critical { background-color: #1a1a1a; color: white; }
                .cve-high { background-color: #dc2626; color: white; }
                .cve-medium { background-color: #f97316; color: white; }
                .cve-low { background-color: #eab308; color: black; }
                .cve-none { background-color: #22c55e; color: white; }
                .cve-badge { padding: 2px 8px; border-radius: 4px; font-weight: bold; font-size: 0.85em; }
                .cve-stats { display: flex; gap: 15px; flex-wrap: wrap; margin: 15px 0; }
                .cve-stat-box { padding: 10px 15px; border-radius: 5px; text-align: center; min-width: 80px; }
                .cve-score { font-size: 1.2em; font-weight: bold; }
            </style>
            """);

        // Titre section
        String titleClass = cveResult.hasCriticalVulnerabilities() ? "error" :
                           (cveResult.hasVulnerabilities() ? "warning" : "success");
        String titleIcon = cveResult.hasCriticalVulnerabilities() ? "🛑" :
                          (cveResult.hasVulnerabilities() ? "⚠️" : "✅");

        html.append("<h2 class='").append(titleClass).append("'>")
            .append(titleIcon).append(" Analyse des vulnérabilités CVE (")
            .append(cveResult.totalCount()).append(")</h2>");

        if (!cveResult.hasVulnerabilities()) {
            html.append("<p class='success'><strong>Aucune vulnérabilité CVE détectée !</strong> ");
            html.append("Le projet n'utilise pas de bibliothèques avec des failles de sécurité connues.</p>");
            return;
        }

        // Résumé statistique
        html.append("<div class='cve-stats'>");

        if (cveResult.criticalCount() > 0) {
            html.append("<div class='cve-stat-box cve-critical'>");
            html.append("<div class='cve-score'>").append(cveResult.criticalCount()).append("</div>");
            html.append("<div>Critiques</div></div>");
        }
        if (cveResult.highCount() > 0) {
            html.append("<div class='cve-stat-box cve-high'>");
            html.append("<div class='cve-score'>").append(cveResult.highCount()).append("</div>");
            html.append("<div>Hautes</div></div>");
        }
        if (cveResult.mediumCount() > 0) {
            html.append("<div class='cve-stat-box cve-medium'>");
            html.append("<div class='cve-score'>").append(cveResult.mediumCount()).append("</div>");
            html.append("<div>Moyennes</div></div>");
        }
        if (cveResult.lowCount() > 0) {
            html.append("<div class='cve-stat-box cve-low'>");
            html.append("<div class='cve-score'>").append(cveResult.lowCount()).append("</div>");
            html.append("<div>Basses</div></div>");
        }

        html.append("<div class='stat-box'>");
        html.append("<div class='stat-value'>").append(cveResult.riskScore()).append("</div>");
        html.append("<div class='stat-label'>Score de risque</div></div>");

        html.append("<div class='stat-box'>");
        html.append("<div class='stat-value'>").append(cveResult.byLibrary().size()).append("</div>");
        html.append("<div class='stat-label'>Librairies affectées</div></div>");

        html.append("</div>");

        // Top 10 librairies vulnérables
        var topLibs = cveResult.topVulnerableLibraries(10);
        if (!topLibs.isEmpty()) {
            html.append("<h3>Librairies les plus vulnérables</h3>");
            html.append("<table><tr><th>Librairie</th><th>CVE</th><th>Sévérité max</th></tr>");

            for (var entry : topLibs) {
                String gav = entry.getKey();
                List<CveInfo> cves = entry.getValue();
                CveSeverity maxSev = cves.stream()
                    .map(CveInfo::severity)
                    .min(Comparator.comparingInt(Enum::ordinal))
                    .orElse(CveSeverity.NONE);

                html.append("<tr>");
                html.append("<td><code>").append(gav).append("</code></td>");
                html.append("<td>").append(cves.size()).append("</td>");
                html.append("<td><span class='cve-badge cve-").append(maxSev.name().toLowerCase())
                    .append("'>").append(maxSev.getLabelFr()).append("</span></td>");
                html.append("</tr>");
            }
            html.append("</table>");
        }

        // Tableau détaillé des CVE
        html.append("<h3>Détail des vulnérabilités</h3>");
        html.append("<table><tr><th>CVE</th><th>Score</th><th>Sévérité</th><th>Librairie</th><th>Description</th></tr>");

        // Trier par score décroissant
        List<CveInfo> sortedCves = cveResult.vulnerabilities().stream()
            .sorted((a, b) -> Double.compare(b.cvssScore(), a.cvssScore()))
            .toList();

        for (CveInfo cve : sortedCves) {
            String sevClass = "cve-" + cve.severity().name().toLowerCase();

            html.append("<tr>");
            html.append("<td><a href='").append(cve.nvdUrl()).append("' target='_blank'>")
                .append(cve.cveId()).append("</a></td>");
            html.append("<td><span class='cve-badge ").append(sevClass).append("'>")
                .append(String.format("%.1f", cve.cvssScore())).append("</span></td>");
            html.append("<td><span class='cve-badge ").append(sevClass).append("'>")
                .append(cve.severity().getLabelFr()).append("</span></td>");
            html.append("<td><code>").append(cve.libraryName()).append("</code></td>");

            // Tronquer la description
            String desc = cve.description();
            if (desc.length() > 150) {
                desc = desc.substring(0, 147) + "...";
            }
            html.append("<td>").append(desc).append("</td>");
            html.append("</tr>");
        }

        html.append("</table>");

        // Message d'avertissement si critiques
        if (cveResult.hasCriticalVulnerabilities()) {
            html.append("<div class='conflict-warning' style='background-color: #fef2f2; border-left: 4px solid #dc2626;'>");
            html.append("<strong>🛑 ATTENTION :</strong> Ce projet contient des vulnérabilités CRITIQUES. ");
            html.append("Il est fortement recommandé de mettre à jour les bibliothèques concernées avant la mise en production.");
            html.append("</div>");
        }
    }
}
