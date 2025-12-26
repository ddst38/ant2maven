package fr.cnam.migration.report;

import fr.cnam.migration.analyzer.ArtifactoryClient;
import fr.cnam.migration.analyzer.JarVersionExtractor;
import fr.cnam.migration.config.InternalArtifactPatterns;
import fr.cnam.migration.config.JarNameCleaner;
import fr.cnam.migration.config.MigrationConfig;
import fr.cnam.migration.generator.TemplateService;
import fr.cnam.migration.model.*;
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
     * Génère le fichier libnotfound.csv pour les JARs non résolus.
     */
    public void generateLibNotFoundCsv(AnalysisResult analysis, Path outputDir)
            throws IOException {
        Path csvFile = outputDir.resolve("libnotfound.csv");

        try (CSVPrinter printer = new CSVPrinter(
                Files.newBufferedWriter(csvFile),
                CSVFormat.DEFAULT.builder()
                    .setHeader("JAR Name", "Size (bytes)", "SHA1", "Attempted Methods",
                              "Suggested Action", "Suggested Coordinate")
                    .build())) {

            for (AnalysisResult.UnresolvedJar unresolved : analysis.unresolved()) {
                JarInfo jar = unresolved.jar();

                String methods = unresolved.attempts().stream()
                    .map(a -> a.method().name())
                    .collect(Collectors.joining(", "));

                String suggestion = suggestAction(jar);
                String suggestedCoord = suggestCoordinate(jar);

                printer.printRecord(
                    jar.name(),
                    jar.size(),
                    jar.sha1() != null ? jar.sha1() : "N/A",
                    methods,
                    suggestion,
                    suggestedCoord
                );
            }
        }

        log.info("Generated libnotfound.csv with {} entries", analysis.unresolved().size());
    }

    /**
     * Génère le script install-local-jars.sh pour le mode de déploiement LOCAL.
     * Utilise le versionnement basé sur SHA pour les JARs sans version détectable.
     *
     * Les noms de fichiers sont nettoyés (suppression de DEPFAB. et codes projet)
     * pour correspondre aux fichiers copiés dans liblocale.
     */
    public void generateInstallScript(AnalysisResult analysis, Path outputDir,
                                      JarVersionExtractor versionExtractor) throws IOException {
        List<Map<String, String>> internalJars = new ArrayList<>();
        String basePackage = config != null ? config.basePackage() : MigrationConfig.DEFAULT_BASE_PACKAGE;

        // Collecter les dépendances internes
        for (DependencyInfo dep : analysis.internalDependencies()) {
            JarInfo jar = dep.sourceJar();

            // Extraire les infos de version, en utilisant SHA si nécessaire
            JarVersionExtractor.VersionInfo versionInfo = versionExtractor.extractVersion(jar);

            String version = versionInfo.version();
            String artifactId = versionInfo.artifactName();

            // Si basé sur SHA, utiliser l'ID d'artefact basé sur SHA pour éviter les collisions
            if (versionInfo.isShaBasedVersion() && jar.sha1() != null) {
                artifactId = versionExtractor.generateShaBasedArtifactId(jar.name(), jar.sha1());
            }

            // Nettoyer le nom du fichier (supprimer DEPFAB. et code projet)
            String cleanedFileName = JarNameCleaner.clean(jar.name());

            internalJars.add(Map.of(
                "originalName", jar.name(),
                "fileName", cleanedFileName,
                "groupId", dep.groupId(),
                "artifactId", artifactId,
                "version", version,
                "versionSource", versionInfo.source().getDescription(),
                "isShaBasedVersion", String.valueOf(versionInfo.isShaBasedVersion())
            ));
        }

        // Inclure TOUS les JARs non résolus (pas seulement les internes)
        // Ces JARs doivent être installés pour permettre la compilation
        for (AnalysisResult.UnresolvedJar unresolved : analysis.unresolved()) {
            JarInfo jar = unresolved.jar();

            // Nettoyer le nom du fichier (supprimer DEPFAB. et code projet)
            String cleanedFileName = JarNameCleaner.clean(jar.name());
            String artifactName = cleanedFileName.replace(".jar", "");

            // Utiliser les mêmes coordonnées que dans le pom.xml généré
            String groupId;
            String artifactId;
            String version;
            String versionSource;
            boolean isShaBasedVersion = false;

            if (jar.isInternalArtifact()) {
                // Pour les JARs internes, utiliser les patterns internes
                JarVersionExtractor.VersionInfo versionInfo = versionExtractor.extractVersion(jar);
                version = versionInfo.version();
                artifactId = versionInfo.artifactName();
                versionSource = versionInfo.source().getDescription();
                isShaBasedVersion = versionInfo.isShaBasedVersion();

                if (isShaBasedVersion && jar.sha1() != null) {
                    artifactId = versionExtractor.generateShaBasedArtifactId(jar.name(), jar.sha1());
                }

                var coord = internalPatterns.resolve(jar);
                groupId = coord.map(MavenCoordinate::groupId).orElse(basePackage + ".internal");
            } else {
                // Pour les autres JARs non résolus, utiliser le groupe "unresolved"
                groupId = basePackage + ".unresolved";
                artifactId = artifactName;
                version = "LOCAL";
                versionSource = "Non résolu - coordonnées générées";
            }

            internalJars.add(Map.of(
                "originalName", jar.name(),
                "fileName", cleanedFileName,
                "groupId", groupId,
                "artifactId", artifactId,
                "version", version,
                "versionSource", versionSource,
                "isShaBasedVersion", String.valueOf(isShaBasedVersion)
            ));
        }

        // Générer le script
        Path scriptDir = outputDir.resolve("liblocale");
        Files.createDirectories(scriptDir);

        StringBuilder script = new StringBuilder();
        script.append("#!/bin/bash\n");
        script.append("# Installation des JARs internes dans le repository Maven local (.m2)\n");
        script.append("# Généré par ant2maven le ").append(LocalDateTime.now().format(
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))).append("\n");
        script.append("#\n");
        script.append("# Les JARs avec versions basées sur SHA sont marqués [SHA]\n");
        script.append("# Cela évite les collisions de version quand différents projets ont\n");
        script.append("# différentes versions du même JAR non versionné.\n");
        script.append("#\n");
        script.append("# Note: Les préfixes DEPFAB. et codes projet ont été supprimés des noms de fichiers.\n");
        script.append("\n");
        script.append("set -e\n");
        script.append("cd \"$(dirname \"$0\")\"\n");
        script.append("\n");
        script.append("echo \"Installation des JARs internes dans le repository Maven local...\"\n");
        script.append("\n");

        for (Map<String, String> jar : internalJars) {
            String shaMarker = "true".equals(jar.get("isShaBasedVersion")) ? " [SHA]" : "";
            String originalName = jar.get("originalName");
            String fileName = jar.get("fileName");

            // Afficher le nom original si différent du nom nettoyé
            if (!originalName.equals(fileName)) {
                script.append("# ").append(originalName).append(" -> ").append(fileName).append(shaMarker).append("\n");
            } else {
                script.append("# ").append(fileName).append(shaMarker).append("\n");
            }
            script.append("# Source version: ").append(jar.get("versionSource")).append("\n");
            script.append("mvn install:install-file \\\n");
            script.append("    -Dfile=\"").append(fileName).append("\" \\\n");
            script.append("    -DgroupId=\"").append(jar.get("groupId")).append("\" \\\n");
            script.append("    -DartifactId=\"").append(jar.get("artifactId")).append("\" \\\n");
            script.append("    -Dversion=\"").append(jar.get("version")).append("\" \\\n");
            script.append("    -Dpackaging=jar\n");
            script.append("\n");
        }

        script.append("echo \"Terminé ! ").append(internalJars.size()).append(" JARs installés.\"\n");

        Path scriptFile = scriptDir.resolve("install-local-jars.sh");
        Files.writeString(scriptFile, script.toString());
        scriptFile.toFile().setExecutable(true);

        log.info("Script install-local-jars.sh généré pour {} JARs", internalJars.size());
    }

    /**
     * Génère le script d'installation sans extracteur de version (compatibilité ascendante).
     */
    public void generateInstallScript(AnalysisResult analysis, Path outputDir) throws IOException {
        generateInstallScript(analysis, outputDir, new JarVersionExtractor());
    }

    /**
     * Génère le script deploy-to-artifactory.sh pour le mode de déploiement REMOTE.
     * Utilise le versionnement basé sur SHA pour les JARs sans version détectable.
     *
     * Les noms de fichiers sont nettoyés (suppression de DEPFAB. et codes projet)
     * pour correspondre aux fichiers copiés dans liblocale.
     */
    public void generateDeployScript(AnalysisResult analysis, Path outputDir,
                                     JarVersionExtractor versionExtractor,
                                     ArtifactoryClient artifactoryClient) throws IOException {
        if (config == null || !config.isArtifactoryConfigured()) {
            log.warn("Artifactory non configuré, génération du script de déploiement ignorée");
            return;
        }

        List<Map<String, String>> deployableJars = new ArrayList<>();
        String basePackage = config.basePackage();

        // Collecter les dépendances internes à déployer
        for (DependencyInfo dep : analysis.internalDependencies()) {
            JarInfo jar = dep.sourceJar();
            JarVersionExtractor.VersionInfo versionInfo = versionExtractor.extractVersion(jar);

            String version = versionInfo.version();
            String artifactId = versionInfo.artifactName();

            // Utiliser l'ID d'artefact basé sur SHA pour les JARs non versionnés
            if (versionInfo.isShaBasedVersion() && jar.sha1() != null) {
                artifactId = versionExtractor.generateShaBasedArtifactId(jar.name(), jar.sha1());
            }

            MavenCoordinate coord = new MavenCoordinate(dep.groupId(), artifactId, version);

            // Nettoyer le nom du fichier (supprimer DEPFAB. et code projet)
            String cleanedFileName = JarNameCleaner.clean(jar.name());

            deployableJars.add(Map.of(
                "originalName", jar.name(),
                "fileName", cleanedFileName,
                "path", jar.path().toAbsolutePath().toString(),
                "groupId", coord.groupId(),
                "artifactId", coord.artifactId(),
                "version", coord.version(),
                "versionSource", versionInfo.source().getDescription(),
                "isShaBasedVersion", String.valueOf(versionInfo.isShaBasedVersion()),
                "deployCommand", artifactoryClient.generateDeployCommand(jar.path(), coord, false)
            ));
        }

        // Inclure TOUS les JARs non résolus (pas seulement les internes)
        for (AnalysisResult.UnresolvedJar unresolved : analysis.unresolved()) {
            JarInfo jar = unresolved.jar();

            // Nettoyer le nom du fichier (supprimer DEPFAB. et code projet)
            String cleanedFileName = JarNameCleaner.clean(jar.name());
            String artifactName = cleanedFileName.replace(".jar", "");

            String groupId;
            String artifactId;
            String version;
            String versionSource;
            boolean isShaBasedVersion = false;

            if (jar.isInternalArtifact()) {
                // Pour les JARs internes, utiliser les patterns internes
                JarVersionExtractor.VersionInfo versionInfo = versionExtractor.extractVersion(jar);
                version = versionInfo.version();
                artifactId = versionInfo.artifactName();
                versionSource = versionInfo.source().getDescription();
                isShaBasedVersion = versionInfo.isShaBasedVersion();

                if (isShaBasedVersion && jar.sha1() != null) {
                    artifactId = versionExtractor.generateShaBasedArtifactId(jar.name(), jar.sha1());
                }

                var patternCoord = internalPatterns.resolve(jar);
                groupId = patternCoord.map(MavenCoordinate::groupId).orElse(basePackage + ".internal");
            } else {
                // Pour les autres JARs non résolus, utiliser le groupe "unresolved"
                groupId = basePackage + ".unresolved";
                artifactId = artifactName;
                version = "LOCAL";
                versionSource = "Non résolu - coordonnées générées";
            }

            MavenCoordinate coord = new MavenCoordinate(groupId, artifactId, version);

            deployableJars.add(Map.of(
                "originalName", jar.name(),
                "fileName", cleanedFileName,
                "path", jar.path().toAbsolutePath().toString(),
                "groupId", coord.groupId(),
                "artifactId", coord.artifactId(),
                "version", coord.version(),
                "versionSource", versionSource,
                "isShaBasedVersion", String.valueOf(isShaBasedVersion),
                "deployCommand", artifactoryClient.generateDeployCommand(jar.path(), coord, false)
            ));
        }

        // Générer le script de déploiement
        Path scriptDir = outputDir.resolve("liblocale");
        Files.createDirectories(scriptDir);

        StringBuilder script = new StringBuilder();
        script.append("#!/bin/bash\n");
        script.append("# Déploiement des JARs internes vers Artifactory\n");
        script.append("# Généré par ant2maven le ").append(LocalDateTime.now().format(
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))).append("\n");
        script.append("#\n");
        script.append("# Artifactory: ").append(config.artifactoryUrl()).append("\n");
        script.append("# Repository Release: ").append(config.artifactoryReleaseRepo()).append("\n");
        script.append("#\n");
        script.append("# Les JARs avec versions basées sur SHA sont marqués [SHA]\n");
        script.append("# Cela évite les collisions de version quand différents projets ont\n");
        script.append("# différentes versions du même JAR non versionné.\n");
        script.append("#\n");
        script.append("# Note: Les préfixes DEPFAB. et codes projet ont été supprimés des noms de fichiers.\n");
        script.append("#\n");
        script.append("# IMPORTANT: Définir la variable d'environnement ARTIFACTORY_PASSWORD avant exécution.\n");
        script.append("\n");
        script.append("set -e\n");
        script.append("cd \"$(dirname \"$0\")\"\n");
        script.append("\n");
        script.append("if [ -z \"$ARTIFACTORY_PASSWORD\" ]; then\n");
        script.append("    echo \"ERREUR: La variable d'environnement ARTIFACTORY_PASSWORD n'est pas définie.\"\n");
        script.append("    echo \"Définissez-la avec: export ARTIFACTORY_PASSWORD=votre_mot_de_passe\"\n");
        script.append("    exit 1\n");
        script.append("fi\n");
        script.append("\n");
        script.append("echo \"Déploiement des JARs internes vers Artifactory...\"\n");
        script.append("echo \"Cible: ").append(config.artifactoryUrl()).append("/")
               .append(config.artifactoryReleaseRepo()).append("\"\n");
        script.append("\n");

        for (Map<String, String> jar : deployableJars) {
            String shaMarker = "true".equals(jar.get("isShaBasedVersion")) ? " [SHA]" : "";
            String originalName = jar.get("originalName");
            String fileName = jar.get("fileName");

            // Afficher le nom original si différent du nom nettoyé
            if (!originalName.equals(fileName)) {
                script.append("# ").append(originalName).append(" -> ").append(fileName).append(shaMarker).append("\n");
            } else {
                script.append("# ").append(fileName).append(shaMarker).append("\n");
            }
            script.append("# Source version: ").append(jar.get("versionSource")).append("\n");
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
     * Génère un rapport de migration HTML.
     */
    public void generateMigrationReport(ProjectStructure project, AnalysisResult analysis,
                                        Path outputDir) throws IOException {
        Files.createDirectories(outputDir);
        Path reportFile = outputDir.resolve("migration-report.html");

        StringBuilder html = new StringBuilder();
        html.append("""
            <!DOCTYPE html>
            <html>
            <head>
                <meta charset="UTF-8">
                <title>Migration Report - %s</title>
                <style>
                    body { font-family: Arial, sans-serif; margin: 20px; }
                    h1 { color: #333; }
                    h2 { color: #666; border-bottom: 1px solid #ccc; padding-bottom: 5px; }
                    table { border-collapse: collapse; width: 100%%; margin: 10px 0; }
                    th, td { border: 1px solid #ddd; padding: 8px; text-align: left; }
                    th { background-color: #f4f4f4; }
                    tr:nth-child(even) { background-color: #f9f9f9; }
                    .success { color: green; }
                    .warning { color: orange; }
                    .error { color: red; }
                    .sha-version { background-color: #fff3cd; }
                    .stat-box { display: inline-block; padding: 15px; margin: 10px; background: #f0f0f0; border-radius: 5px; }
                    .stat-value { font-size: 24px; font-weight: bold; }
                    .stat-label { color: #666; }
                    .config-table { width: auto; }
                    .config-table td { padding: 4px 12px; }
                </style>
            </head>
            <body>
            """.formatted(project.name()));

        // En-tête
        html.append("<h1>Migration Report: ").append(project.name()).append("</h1>");
        html.append("<p>Generated: ")
            .append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")))
            .append("</p>");

        // Configuration section
        if (config != null) {
            html.append("<h2>Configuration</h2>");
            html.append("<table class='config-table'>");
            html.append("<tr><td><strong>Base Package:</strong></td><td>").append(config.basePackage()).append("</td></tr>");
            html.append("<tr><td><strong>Deployment Mode:</strong></td><td>").append(config.deploymentMode()).append("</td></tr>");
            if (config.isArtifactoryConfigured()) {
                html.append("<tr><td><strong>Artifactory:</strong></td><td>").append(config.artifactoryUrl()).append("</td></tr>");
                html.append("<tr><td><strong>Release Repo:</strong></td><td>").append(config.artifactoryReleaseRepo()).append("</td></tr>");
            }
            html.append("</table>");
        }

        // Summary statistics
        html.append("<h2>Summary</h2>");
        html.append("<div class='stat-box'>");
        html.append("<div class='stat-value'>").append(project.allJars().size()).append("</div>");
        html.append("<div class='stat-label'>Total JARs</div></div>");

        html.append("<div class='stat-box'>");
        html.append("<div class='stat-value success'>").append(analysis.resolved().size()).append("</div>");
        html.append("<div class='stat-label'>Resolved</div></div>");

        html.append("<div class='stat-box'>");
        html.append("<div class='stat-value warning'>").append(analysis.unresolved().size()).append("</div>");
        html.append("<div class='stat-label'>Unresolved</div></div>");

        html.append("<div class='stat-box'>");
        html.append("<div class='stat-value'>").append(String.format("%.1f%%", analysis.successRate())).append("</div>");
        html.append("<div class='stat-label'>Success Rate</div></div>");

        // Résolution par méthode
        html.append("<h2>Resolution Methods</h2>");
        html.append("<table><tr><th>Method</th><th>Count</th></tr>");
        Map<ResolutionMethod, List<DependencyInfo>> byMethod = analysis.byMethod();
        for (ResolutionMethod method : ResolutionMethod.values()) {
            if (method != ResolutionMethod.UNRESOLVED) {
                int count = byMethod.getOrDefault(method, List.of()).size();
                if (count > 0) {
                    html.append("<tr><td>").append(method.getDescription()).append("</td>");
                    html.append("<td>").append(count).append("</td></tr>");
                }
            }
        }
        html.append("</table>");

        // Dépendances par scope
        html.append("<h2>Dependencies by Scope</h2>");
        html.append("<table><tr><th>Scope</th><th>Count</th></tr>");
        Map<Scope, List<DependencyInfo>> byScope = analysis.byScope();
        for (Scope scope : Scope.values()) {
            int count = byScope.getOrDefault(scope, List.of()).size();
            if (count > 0) {
                html.append("<tr><td>").append(scope.getValue()).append("</td>");
                html.append("<td>").append(count).append("</td></tr>");
            }
        }
        html.append("</table>");

        // Dépendances internes
        List<DependencyInfo> internal = analysis.internalDependencies();
        if (!internal.isEmpty()) {
            html.append("<h2>Internal Dependencies (").append(internal.size()).append(")</h2>");
            html.append("<p><em>Note: Rows highlighted in yellow use SHA-based versioning to prevent collisions.</em></p>");
            html.append("<table><tr><th>JAR</th><th>Maven Coordinate</th><th>Version Source</th></tr>");
            for (DependencyInfo dep : internal) {
                String rowClass = dep.version().startsWith("SHA-") ? " class='sha-version'" : "";
                html.append("<tr").append(rowClass).append("><td>").append(dep.sourceJar().name()).append("</td>");
                html.append("<td>").append(dep.coordinate().toGav()).append("</td>");
                html.append("<td>").append(dep.method().getDescription()).append("</td></tr>");
            }
            html.append("</table>");
        }

        // JARs non résolus
        if (!analysis.unresolved().isEmpty()) {
            html.append("<h2 class='warning'>Unresolved JARs (").append(analysis.unresolved().size()).append(")</h2>");
            html.append("<table><tr><th>JAR</th><th>Size</th><th>SHA1</th><th>Suggested Action</th></tr>");
            for (AnalysisResult.UnresolvedJar unresolved : analysis.unresolved()) {
                JarInfo jar = unresolved.jar();
                html.append("<tr><td>").append(jar.name()).append("</td>");
                html.append("<td>").append(formatSize(jar.size())).append("</td>");
                html.append("<td><code>").append(jar.sha1() != null ? jar.sha1().substring(0, 12) + "..." : "N/A").append("</code></td>");
                html.append("<td>").append(suggestAction(jar)).append("</td></tr>");
            }
            html.append("</table>");
        }

        // Informations du projet
        html.append("<h2>Project Information</h2>");
        html.append("<table>");
        html.append("<tr><th>Property</th><th>Value</th></tr>");
        html.append("<tr><td>Project Type</td><td>").append(project.type()).append("</td></tr>");
        html.append("<tr><td>Main Java Files</td><td>")
            .append(project.sourceLayout().mainJavaFileCount()).append("</td></tr>");
        html.append("<tr><td>Test Java Files</td><td>")
            .append(project.sourceLayout().testJavaFileCount()).append("</td></tr>");
        html.append("<tr><td>Internal Dependencies</td><td>")
            .append(project.internalDeps().size()).append("</td></tr>");
        html.append("</table>");

        html.append("</body></html>");

        Files.writeString(reportFile, html.toString());
        log.info("Generated migration-report.html");
    }

    private String suggestAction(JarInfo jar) {
        String name = jar.name();

        if (name.startsWith("DEPFAB.") || name.startsWith("jk-socle-")) {
            return "Internal artifact - will be installed/deployed automatically";
        }
        if (name.contains("oracle") || name.equals("classes12.jar")) {
            return "Oracle JDBC - download from Oracle and install manually";
        }
        if (name.contains("-sources")) {
            return "Source JAR - can be excluded from dependencies";
        }
        if (name.startsWith("Service") || name.startsWith("SOCA")) {
            return "CNAM service - will be installed/deployed automatically";
        }
        if (name.equals("struts.jar")) {
            return "Legacy Struts - map to org.apache.struts:struts-core:1.3.10";
        }
        return "Search manually on Maven Central or vendor website";
    }

    private String suggestCoordinate(JarInfo jar) {
        var coord = internalPatterns.resolve(jar);
        return coord.map(MavenCoordinate::toGav).orElse("N/A");
    }

    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
    }
}
