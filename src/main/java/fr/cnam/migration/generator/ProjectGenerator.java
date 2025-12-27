package fr.cnam.migration.generator;

import fr.cnam.migration.config.MigrationConfig;
import fr.cnam.migration.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Générateur principal qui orchestre la création du projet Maven.
 */
public class ProjectGenerator {

    private static final Logger log = LoggerFactory.getLogger(ProjectGenerator.class);

    private final MigrationConfig config;
    private final TemplateService templateService;
    private final StructureCreator structureCreator;

    public ProjectGenerator(MigrationConfig config) {
        this.config = config;
        this.templateService = new TemplateService();
        this.structureCreator = new StructureCreator();
    }

    /**
     * Génère le projet Maven complet.
     */
    public GenerationResult generate(ProjectStructure project, AnalysisResult analysis)
            throws IOException {
        Path outputDir = config.outputDir();
        log.info("Generating Maven project in: {}", outputDir);

        List<Path> createdFiles = new ArrayList<>();

        // Créer la structure de répertoires et copier les fichiers
        structureCreator.createStructure(project, outputDir);

        // Copier les JARs non résolus vers liblocale
        int unresolvedCopied = structureCreator.copyUnresolvedJars(analysis, outputDir);
        if (unresolvedCopied > 0) {
            log.info("Copié {} JARs non résolus vers liblocale", unresolvedCopied);
        }

        // Copier les JARs internes résolus vers liblocale
        int internalCopied = structureCreator.copyInternalResolvedJars(analysis, outputDir);
        if (internalCopied > 0) {
            log.info("Copié {} JARs internes résolus vers liblocale", internalCopied);
        }

        // Générer le POM parent
        Path parentPom = generateParentPom(project, analysis, outputDir);
        createdFiles.add(parentPom);

        // Générer le POM du module WAR
        String moduleName = project.name().toLowerCase().replace("_j", "");
        Path warPom = generateWarPom(project, analysis, outputDir.resolve(moduleName + "-web"));
        createdFiles.add(warPom);

        // Générer le POM du module EAR
        Path earPom = generateEarPom(project, outputDir.resolve(moduleName + "-ear"));
        createdFiles.add(earPom);

        // Installer le Maven wrapper
        installMavenWrapper(outputDir);
        createdFiles.add(outputDir.resolve("mvnw"));
        createdFiles.add(outputDir.resolve("mvnw.cmd"));

        log.info("Maven project generation complete: {} files created", createdFiles.size());

        return new GenerationResult(outputDir, createdFiles, true);
    }

    /**
     * Génère le POM parent.
     */
    private Path generateParentPom(ProjectStructure project, AnalysisResult analysis,
                                   Path outputDir) {
        String projectName = project.name().toLowerCase();
        String moduleName = projectName.replace("_j", "");

        Map<String, Object> model = new HashMap<>();
        model.put("groupId", config.basePackage() + "." + moduleName);
        model.put("artifactId", projectName);
        model.put("version", "1.0.0-SNAPSHOT");
        model.put("projectName", project.name());

        // Les modules
        model.put("modules", List.of(moduleName + "-web", moduleName + "-ear"));

        // Propriétés de version
        Map<String, String> properties = buildVersionProperties(analysis);
        model.put("properties", properties);

        // Gestion des dépendances
        List<Map<String, String>> depMgmt = buildDependencyManagement(analysis);
        model.put("dependencyManagement", depMgmt);

        // Repository interne si configuré
        if (config.internalRepoUrl() != null) {
            model.put("internalRepository", config.internalRepoUrl());
        }

        // Profil PIC si le projet a un build.pic.xml
        model.put("hasPicProfile", project.picBuild() != null);

        Path pomFile = outputDir.resolve("pom.xml");
        templateService.renderToFile("parent-pom.xml.ftl", model, pomFile);
        return pomFile;
    }

    /**
     * Génère le POM du module WAR.
     *
     * IMPORTANT: Toutes les dépendances (résolues et non résolues) doivent être
     * déclarées ici pour que le projet compile. Les dépendances internes seront
     * installées via le script install-local-jars.sh.
     */
    private Path generateWarPom(ProjectStructure project, AnalysisResult analysis,
                                Path webModuleDir) {
        String projectName = project.name().toLowerCase();
        String moduleName = projectName.replace("_j", "");

        Map<String, Object> model = new HashMap<>();
        model.put("parent", Map.of(
            "groupId", config.basePackage() + "." + moduleName,
            "artifactId", projectName,
            "version", "1.0.0-SNAPSHOT"
        ));
        model.put("artifactId", moduleName + "-web");
        model.put("warName", project.primaryBuild() != null ?
            project.primaryBuild().warName().replace(".war", "") : moduleName);

        // Collecter TOUTES les dépendances (résolues + non résolues)
        List<Map<String, String>> dependencies = new ArrayList<>();
        String basePackage = config.basePackage();

        log.info("Génération des dépendances pour le pom.xml du module WAR");
        log.info("  - {} dépendances résolues", analysis.resolved().size());
        log.info("  - {} dépendances non résolues", analysis.unresolved().size());

        // 1. Dépendances résolues (internes ET externes)
        for (DependencyInfo dep : analysis.resolved()) {
            if (dep.scope() != Scope.TEST || config.isPicBuild()) {
                Map<String, String> depMap = new LinkedHashMap<>();
                depMap.put("groupId", dep.groupId());
                depMap.put("artifactId", dep.artifactId());
                depMap.put("version", dep.version());

                if (dep.scope() != Scope.COMPILE) {
                    depMap.put("scope", dep.scope().getValue());
                }

                // Marquer les dépendances internes qui nécessitent installation locale
                if (dep.isInternal()) {
                    depMap.put("comment", "Interne - installer via ./liblocale/install-local-jars.sh");
                    log.debug("  Dépendance interne: {}:{}:{}", dep.groupId(), dep.artifactId(), dep.version());
                } else {
                    log.debug("  Dépendance externe: {}:{}:{}", dep.groupId(), dep.artifactId(), dep.version());
                }

                dependencies.add(depMap);
            }
        }

        // 2. Dépendances non résolues (seront installées via install-local-jars.sh)
        for (AnalysisResult.UnresolvedJar unresolved : analysis.unresolved()) {
            JarInfo jar = unresolved.jar();
            Map<String, String> depMap = new LinkedHashMap<>();

            // Générer des coordonnées Maven pour le JAR non résolu
            String cleanedName = fr.cnam.migration.config.JarNameCleaner.clean(jar.name());
            String artifactName = cleanedName.replace(".jar", "");

            depMap.put("groupId", basePackage + ".unresolved");
            depMap.put("artifactId", artifactName);
            depMap.put("version", "LOCAL");
            depMap.put("comment", "Non résolu - installer via ./liblocale/install-local-jars.sh");

            log.debug("  Dépendance non résolue: {}.unresolved:{}:LOCAL", basePackage, artifactName);

            dependencies.add(depMap);
        }

        log.info("Total: {} dépendances à déclarer dans le pom.xml", dependencies.size());

        model.put("dependencies", dependencies);

        // Ressources exclues
        if (project.primaryBuild() != null) {
            model.put("excludedResources", project.primaryBuild().excludedFiles());
        }

        Path pomFile = webModuleDir.resolve("pom.xml");
        templateService.renderToFile("war-pom.xml.ftl", model, pomFile);
        return pomFile;
    }

    /**
     * Génère le POM du module EAR.
     */
    private Path generateEarPom(ProjectStructure project, Path earModuleDir) {
        String projectName = project.name().toLowerCase();
        String moduleName = projectName.replace("_j", "");

        Map<String, Object> model = new HashMap<>();
        model.put("parent", Map.of(
            "groupId", config.basePackage() + "." + moduleName,
            "artifactId", projectName,
            "version", "1.0.0-SNAPSHOT"
        ));
        model.put("artifactId", moduleName + "-ear");
        model.put("warArtifactId", moduleName + "-web");

        // Configuration EAR
        EarConfiguration earConfig = project.earConfig();
        if (earConfig != null) {
            model.put("contextRoot", earConfig.contextRoot() != null ?
                earConfig.contextRoot() : moduleName);
            model.put("warFileName", earConfig.warFileName() != null ?
                earConfig.warFileName() : moduleName + ".war");
            model.put("hasAppInfLib", earConfig.hasAppInfLib());
            model.put("hasAppInfConf", earConfig.appInfConfFiles() != null &&
                !earConfig.appInfConfFiles().isEmpty());

            // Dépendances APP-INF/lib
            if (earConfig.hasAppInfLib()) {
                List<Map<String, String>> appInfLibs = new ArrayList<>();
                for (Path jarPath : earConfig.appInfLibJars()) {
                    String jarName = jarPath.getFileName().toString();
                    // Ce sont typiquement des artefacts internes
                    appInfLibs.add(Map.of(
                        "groupId", config.basePackage() + ".internal",
                        "artifactId", jarName.replace(".jar", ""),
                        "version", "LOCAL"
                    ));
                }
                model.put("appInfLibs", appInfLibs);
            }
        } else {
            model.put("contextRoot", moduleName);
            model.put("warFileName", moduleName + ".war");
            model.put("hasAppInfLib", false);
            model.put("hasAppInfConf", false);
        }

        Path pomFile = earModuleDir.resolve("pom.xml");
        templateService.renderToFile("ear-pom.xml.ftl", model, pomFile);
        return pomFile;
    }

    /**
     * Construit les propriétés de version à partir des dépendances résolues.
     */
    private Map<String, String> buildVersionProperties(AnalysisResult analysis) {
        Map<String, String> properties = new LinkedHashMap<>();

        // Grouper les dépendances par framework/famille
        Map<String, String> familyVersions = new HashMap<>();

        for (DependencyInfo dep : analysis.resolved()) {
            String family = extractFamily(dep.groupId(), dep.artifactId());
            if (family != null && !familyVersions.containsKey(family)) {
                familyVersions.put(family, dep.version());
            }
        }

        // Convertir en noms de propriétés
        if (familyVersions.containsKey("springframework")) {
            properties.put("spring.version", familyVersions.get("springframework"));
        }
        if (familyVersions.containsKey("aspectj")) {
            properties.put("aspectj.version", familyVersions.get("aspectj"));
        }
        if (familyVersions.containsKey("jackson")) {
            properties.put("jackson.version", familyVersions.get("jackson"));
        }
        if (familyVersions.containsKey("log4j")) {
            properties.put("log4j2.version", familyVersions.get("log4j"));
        }
        if (familyVersions.containsKey("bouncycastle")) {
            properties.put("bouncycastle.version", familyVersions.get("bouncycastle"));
        }
        if (familyVersions.containsKey("slf4j")) {
            properties.put("slf4j.version", familyVersions.get("slf4j"));
        }

        return properties;
    }

    private String extractFamily(String groupId, String artifactId) {
        if (groupId.contains("springframework")) return "springframework";
        if (groupId.contains("aspectj")) return "aspectj";
        if (groupId.contains("jackson")) return "jackson";
        if (groupId.contains("log4j")) return "log4j";
        if (groupId.contains("bouncycastle")) return "bouncycastle";
        if (groupId.contains("slf4j")) return "slf4j";
        return null;
    }

    /**
     * Construit la section de gestion des dépendances.
     *
     * Le dependencyManagement du parent ne contient que les dépendances internes (fr.cnamts.*)
     * pour centraliser leurs versions. Les dépendances externes (Maven Central) sont déclarées
     * directement dans le module enfant avec leurs versions complètes.
     */
    private List<Map<String, String>> buildDependencyManagement(AnalysisResult analysis) {
        return analysis.resolved().stream()
            .filter(DependencyInfo::isInternal)  // Seulement les dépendances internes
            .map(dep -> {
                Map<String, String> m = new LinkedHashMap<>();
                m.put("groupId", dep.groupId());
                m.put("artifactId", dep.artifactId());
                m.put("version", dep.version());
                if (dep.scope() != Scope.COMPILE) {
                    m.put("scope", dep.scope().getValue());
                }
                return m;
            })
            .collect(Collectors.toList());
    }

    private boolean shouldUsePropertyVersion(DependencyInfo dep) {
        String family = extractFamily(dep.groupId(), dep.artifactId());
        return family != null;
    }

    /**
     * Installe le Maven wrapper avec un repository local autonome.
     *
     * Crée la structure suivante :
     * - .mvn/wrapper/maven-wrapper.properties (URLs Maven)
     * - .mvn/wrapper/settings.xml (localRepository vers localMvnRepository)
     * - localMvnRepository/ (repository Maven local au projet)
     * - mvnw, mvnw.cmd (scripts wrapper)
     */
    private void installMavenWrapper(Path outputDir) throws IOException {
        // Créer le répertoire .mvn/wrapper/
        Path mvnWrapperDir = outputDir.resolve(".mvn/wrapper");
        Files.createDirectories(mvnWrapperDir);

        // Copier maven-wrapper.properties
        try (var propsStream = getClass().getResourceAsStream("/maven-wrapper/maven-wrapper.properties")) {
            if (propsStream != null) {
                Files.copy(propsStream, mvnWrapperDir.resolve("maven-wrapper.properties"),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } else {
                log.warn("Ressource maven-wrapper.properties introuvable");
            }
        }

        // Copier settings.xml (avec localRepository pointant vers localMvnRepository)
        try (var settingsStream = getClass().getResourceAsStream("/maven-wrapper/settings.xml")) {
            if (settingsStream != null) {
                Files.copy(settingsStream, mvnWrapperDir.resolve("settings.xml"),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } else {
                log.warn("Ressource settings.xml introuvable");
            }
        }

        // Créer le répertoire localMvnRepository pour le repository local autonome
        Path localRepoDir = outputDir.resolve("localMvnRepository");
        Files.createDirectories(localRepoDir);

        // Copier mvnw depuis les ressources
        try (var mvnwStream = getClass().getResourceAsStream("/maven-wrapper/mvnw")) {
            if (mvnwStream != null) {
                Path mvnwPath = outputDir.resolve("mvnw");
                Files.copy(mvnwStream, mvnwPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                mvnwPath.toFile().setExecutable(true);
            } else {
                log.warn("Ressource mvnw introuvable");
            }
        }

        // Copier mvnw.cmd depuis les ressources
        try (var mvnwCmdStream = getClass().getResourceAsStream("/maven-wrapper/mvnw.cmd")) {
            if (mvnwCmdStream != null) {
                Files.copy(mvnwCmdStream, outputDir.resolve("mvnw.cmd"),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } else {
                log.warn("Ressource mvnw.cmd introuvable");
            }
        }

        log.info("Maven wrapper installé avec repository local autonome");
    }

    /**
     * Résultat de la génération du projet.
     */
    public record GenerationResult(
        Path outputDir,
        List<Path> createdFiles,
        boolean success
    ) {}
}
