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
        // Ces JARs utilisent les coordonnées générées (basePackage.unresolved:artifactName:LOCAL)
        for (AnalysisResult.UnresolvedJar unresolved : analysis.unresolved()) {
            JarInfo jar = unresolved.jar();

            // Nettoyer le nom du fichier (supprimer DEPFAB. et code projet)
            String cleanedFileName = JarNameCleaner.clean(jar.name());
            String artifactName = cleanedFileName.replace(".jar", "");

            // Utiliser les MÊMES coordonnées que dans le pom.xml
            jarsToInstall.add(Map.of(
                "originalName", jar.name(),
                "fileName", cleanedFileName,
                "groupId", basePackage + ".unresolved",
                "artifactId", artifactName,
                "version", "LOCAL",
                "versionSource", "Non résolu - coordonnées générées",
                "isInternal", "false"
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
        if (config == null || !config.isArtifactoryConfigured()) {
            log.warn("Artifactory non configuré, génération du script de déploiement ignorée");
            return;
        }

        List<Map<String, String>> deployableJars = new ArrayList<>();
        String basePackage = config.basePackage();

        // 1. Collecter les dépendances internes résolues
        // Utiliser les MÊMES coordonnées que dans le pom.xml
        for (DependencyInfo dep : analysis.internalDependencies()) {
            JarInfo jar = dep.sourceJar();

            // Nettoyer le nom du fichier (supprimer DEPFAB. et code projet)
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
                "isInternal", "true",
                "deployCommand", artifactoryClient.generateDeployCommand(jar.path(), coord, false)
            ));
        }

        // 2. Collecter les JARs non résolus
        // Utiliser les MÊMES coordonnées que dans le pom.xml
        for (AnalysisResult.UnresolvedJar unresolved : analysis.unresolved()) {
            JarInfo jar = unresolved.jar();

            // Nettoyer le nom du fichier (supprimer DEPFAB. et code projet)
            String cleanedFileName = JarNameCleaner.clean(jar.name());
            String artifactName = cleanedFileName.replace(".jar", "");

            MavenCoordinate coord = new MavenCoordinate(
                basePackage + ".unresolved",
                artifactName,
                "LOCAL"
            );

            deployableJars.add(Map.of(
                "originalName", jar.name(),
                "fileName", cleanedFileName,
                "path", jar.path().toAbsolutePath().toString(),
                "groupId", coord.groupId(),
                "artifactId", coord.artifactId(),
                "version", coord.version(),
                "versionSource", "Non résolu - coordonnées générées",
                "isInternal", "false",
                "deployCommand", artifactoryClient.generateDeployCommand(jar.path(), coord, false)
            ));
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
        script.append("# Repository Release: ").append(config.artifactoryReleaseRepo()).append("\n");
        script.append("#\n");
        script.append("# Les coordonnées Maven correspondent EXACTEMENT à celles du pom.xml.\n");
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
        script.append("echo \"Déploiement des JARs vers Artifactory...\"\n");
        script.append("echo \"Cible: ").append(config.artifactoryUrl()).append("/")
               .append(config.artifactoryReleaseRepo()).append("\"\n");
        script.append("\n");

        for (Map<String, String> jar : deployableJars) {
            String typeMarker = "true".equals(jar.get("isInternal")) ? "[Interne]" : "[Non résolu]";
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
     * Génère un rapport de migration HTML entièrement en français.
     */
    public void generateMigrationReport(ProjectStructure project, AnalysisResult analysis,
                                        Path outputDir) throws IOException {
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
            html.append("</table>");
        }

        // Statistiques résumées
        html.append("<h2>Résumé</h2>");
        html.append("<div class='stat-box'>");
        html.append("<div class='stat-value'>").append(project.allJars().size()).append("</div>");
        html.append("<div class='stat-label'>JARs détectés</div></div>");

        html.append("<div class='stat-box'>");
        html.append("<div class='stat-value success'>").append(analysis.resolved().size()).append("</div>");
        html.append("<div class='stat-label'>Résolus</div></div>");

        html.append("<div class='stat-box'>");
        html.append("<div class='stat-value warning'>").append(analysis.unresolved().size()).append("</div>");
        html.append("<div class='stat-label'>Non résolus</div></div>");

        html.append("<div class='stat-box'>");
        html.append("<div class='stat-value'>").append(String.format("%.1f%%", analysis.successRate())).append("</div>");
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

        // Section: TOUTES les dépendances résolues (externes + internes)
        html.append("<h2>Dépendances résolues (").append(analysis.resolved().size()).append(")</h2>");
        html.append("<p><em>Ces dépendances seront déclarées dans le pom.xml du module WAR.</em></p>");

        // Dépendances externes (Maven Central)
        List<DependencyInfo> external = analysis.externalDependencies();
        if (!external.isEmpty()) {
            html.append("<h3 class='success'>Dépendances externes - Maven Central (").append(external.size()).append(")</h3>");
            html.append("<p><em>Ces bibliothèques seront téléchargées automatiquement par Maven depuis Maven Central.</em></p>");
            html.append("<table><tr><th>JAR d'origine</th><th>Coordonnées Maven</th><th>Méthode de résolution</th></tr>");
            for (DependencyInfo dep : external) {
                String originalName = dep.sourceJar().name();
                String cleanedName = JarNameCleaner.clean(originalName);
                String displayName = originalName.equals(cleanedName) ? originalName : originalName + " → " + cleanedName;

                html.append("<tr><td><code>").append(displayName).append("</code></td>");
                html.append("<td><code>").append(dep.coordinate().toGav()).append("</code></td>");
                html.append("<td>").append(getMethodDescriptionFr(dep.method())).append("</td></tr>");
            }
            html.append("</table>");
        }

        // Dépendances internes (fr.cnamts.*)
        List<DependencyInfo> internal = analysis.internalDependencies();
        if (!internal.isEmpty()) {
            html.append("<h3 class='warning'>Dépendances internes - Installation locale requise (").append(internal.size()).append(")</h3>");
            html.append("<p><em>Ces bibliothèques doivent être installées via <code>./liblocale/install-local-jars.sh</code></em></p>");
            html.append("<table><tr><th>JAR d'origine</th><th>Coordonnées Maven</th><th>Méthode de résolution</th></tr>");
            for (DependencyInfo dep : internal) {
                String originalName = dep.sourceJar().name();
                String cleanedName = JarNameCleaner.clean(originalName);
                String displayName = originalName.equals(cleanedName) ? originalName : originalName + " → " + cleanedName;

                html.append("<tr><td><code>").append(displayName).append("</code></td>");
                html.append("<td><code>").append(dep.coordinate().toGav()).append("</code></td>");
                html.append("<td>").append(getMethodDescriptionFr(dep.method())).append("</td></tr>");
            }
            html.append("</table>");
        }

        // JARs non résolus
        if (!analysis.unresolved().isEmpty()) {
            html.append("<h3 class='error'>JARs non résolus - Installation locale requise (").append(analysis.unresolved().size()).append(")</h3>");
            html.append("<p><em>Ces bibliothèques n'ont pas pu être identifiées. Elles seront installées avec des coordonnées générées.</em></p>");
            html.append("<table><tr><th>JAR d'origine</th><th>Coordonnées générées</th><th>Taille</th><th>Action suggérée</th></tr>");
            String basePackage = config != null ? config.basePackage() : MigrationConfig.DEFAULT_BASE_PACKAGE;
            for (AnalysisResult.UnresolvedJar unresolved : analysis.unresolved()) {
                JarInfo jar = unresolved.jar();
                String originalName = jar.name();
                String cleanedName = JarNameCleaner.clean(originalName);
                String displayName = originalName.equals(cleanedName) ? originalName : originalName + " → " + cleanedName;
                String artifactName = cleanedName.replace(".jar", "");

                html.append("<tr><td><code>").append(displayName).append("</code></td>");
                html.append("<td><code>").append(basePackage).append(".unresolved:").append(artifactName).append(":LOCAL</code></td>");
                html.append("<td>").append(formatSize(jar.size())).append("</td>");
                html.append("<td>").append(suggestActionFr(jar)).append("</td></tr>");
            }
            html.append("</table>");
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

    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
    }
}
