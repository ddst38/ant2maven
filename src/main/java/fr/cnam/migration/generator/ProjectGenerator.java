package fr.cnam.migration.generator;

import fr.cnam.migration.analyzer.JarPackageAnalyzer;
import fr.cnam.migration.config.MigrationConfig;
import fr.cnam.migration.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;
import java.util.HexFormat;
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

        // Detecter si c'est un projet multi-module
        if (project.isMultiModule()) {
            return generateMultiModule(project, analysis);
        }

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

        // Générer le module de distribution si install/conf ou install/script existe
        if (project.distributionConfig() != null && project.distributionConfig().hasDistribution()) {
            // Copier les répertoires install/
            structureCreator.copyInstallDirectories(project, outputDir);

            // Créer la structure du module dist
            String distModuleName = moduleName + "-dist";
            structureCreator.createDistModuleStructure(outputDir, distModuleName);

            // Générer le POM et le descripteur assembly du module dist
            Path distPom = generateDistPom(project, outputDir.resolve(distModuleName), moduleName);
            createdFiles.add(distPom);

            Path assemblyXml = generateDistributionXml(project, outputDir.resolve(distModuleName), moduleName);
            createdFiles.add(assemblyXml);

            log.info("Generated distribution module: {}", distModuleName);
        }

        // Installer le Maven wrapper
        installMavenWrapper(outputDir);
        createdFiles.add(outputDir.resolve("mvnw"));
        createdFiles.add(outputDir.resolve("mvnw.cmd"));

        log.info("Maven project generation complete: {} files created", createdFiles.size());

        return new GenerationResult(outputDir, createdFiles, true);
    }

    /**
     * Génère un projet Maven multi-module.
     */
    private GenerationResult generateMultiModule(ProjectStructure project, AnalysisResult analysis)
            throws IOException {
        Path outputDir = config.outputDir();
        log.info("Generating multi-module Maven project in: {}", outputDir);

        List<Path> createdFiles = new ArrayList<>();
        String projectName = project.name().toLowerCase();
        String baseName = projectName.replace("_j", "");

        // Créer la structure de répertoires pour chaque module
        structureCreator.createMultiModuleStructure(project, outputDir);

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

        // Construire la liste des modules dans l'ordre de build (sans doublons)
        List<String> moduleNames = new ArrayList<>();
        Set<String> seenModules = new HashSet<>();
        Map<String, ModuleInfo> moduleByArtifactId = new HashMap<>();
        for (ModuleInfo module : project.getModulesInBuildOrder()) {
            if (!seenModules.contains(module.artifactId())) {
                moduleNames.add(module.artifactId());
                seenModules.add(module.artifactId());
            }
            moduleByArtifactId.put(module.artifactId(), module);
        }

        // Ajouter le module dist si configuration de distribution presente
        if (project.distributionConfig() != null && project.distributionConfig().hasDistribution()) {
            moduleNames.add(baseName + "-dist");
        }

        // Générer le POM parent avec la liste des modules
        Path parentPom = generateMultiModuleParentPom(project, analysis, outputDir, moduleNames);
        createdFiles.add(parentPom);

        // Générer le POM de chaque module (eviter les doublons)
        Set<String> generatedModules = new HashSet<>();
        for (ModuleInfo module : project.getModulesInBuildOrder()) {
            if (generatedModules.contains(module.artifactId())) {
                continue; // Skip duplicates
            }
            generatedModules.add(module.artifactId());

            Path moduleDir = outputDir.resolve(module.artifactId());
            Path modulePom;

            switch (module.type()) {
                case JAR -> {
                    modulePom = generateJarModulePom(project, module, analysis, moduleDir, moduleByArtifactId);
                    createdFiles.add(modulePom);
                    log.info("Generated JAR module: {}", module.artifactId());
                }
                case WAR -> {
                    modulePom = generateWarModulePom(project, module, analysis, moduleDir, moduleByArtifactId);
                    createdFiles.add(modulePom);
                    log.info("Generated WAR module: {}", module.artifactId());
                }
                case EAR -> {
                    modulePom = generateEarModulePom(project, module, moduleDir, baseName);
                    createdFiles.add(modulePom);
                    log.info("Generated EAR module: {}", module.artifactId());
                }
                case TEST -> {
                    modulePom = generateTestModulePom(project, module, analysis, moduleDir, moduleByArtifactId);
                    createdFiles.add(modulePom);
                    log.info("Generated TEST module: {}", module.artifactId());
                }
            }
        }

        // Générer le module de distribution si necessaire
        if (project.distributionConfig() != null && project.distributionConfig().hasDistribution()) {
            structureCreator.copyInstallDirectories(project, outputDir);
            String distModuleName = baseName + "-dist";
            structureCreator.createDistModuleStructure(outputDir, distModuleName);
            Path distPom = generateDistPom(project, outputDir.resolve(distModuleName), baseName);
            createdFiles.add(distPom);
            Path assemblyXml = generateDistributionXml(project, outputDir.resolve(distModuleName), baseName);
            createdFiles.add(assemblyXml);
            log.info("Generated distribution module: {}", distModuleName);
        }

        // Installer le Maven wrapper
        installMavenWrapper(outputDir);
        createdFiles.add(outputDir.resolve("mvnw"));
        createdFiles.add(outputDir.resolve("mvnw.cmd"));

        log.info("Multi-module Maven project generation complete: {} files created", createdFiles.size());

        return new GenerationResult(outputDir, createdFiles, true);
    }

    /**
     * Génère le POM parent pour un projet multi-module.
     */
    private Path generateMultiModuleParentPom(ProjectStructure project, AnalysisResult analysis,
                                               Path outputDir, List<String> moduleNames) {
        String projectName = project.name().toLowerCase();
        String moduleName = projectName.replace("_j", "");

        Map<String, Object> model = new HashMap<>();
        model.put("groupId", config.basePackage() + "." + moduleName);
        model.put("artifactId", projectName);
        model.put("version", "1.0.0-SNAPSHOT");
        model.put("projectName", project.name());
        model.put("modules", moduleNames);

        // Propriétés de version
        Map<String, String> properties = buildVersionProperties(analysis);
        model.put("properties", properties);

        // Toutes les dépendances centralisées dans le parent
        List<Map<String, String>> dependencies = new ArrayList<>();
        String basePackage = config.basePackage();

        log.info("Génération des dépendances centralisées dans le pom.xml parent (multi-module)");
        log.info("  - {} dépendances résolues", analysis.resolved().size());
        log.info("  - {} dépendances non résolues", analysis.unresolved().size());

        // 1. Dépendances résolues (inclut les deps TEST sans scope car code test dans src/main)
        for (DependencyInfo dep : analysis.resolved()) {
            Map<String, String> depMap = new LinkedHashMap<>();
            depMap.put("groupId", dep.groupId());
            depMap.put("artifactId", dep.artifactId());
            depMap.put("version", dep.version());

            // Scope compile ou test → pas de scope (défaut = compile)
            // Les autres scopes (provided, runtime) sont conservés
            if (dep.scope() != Scope.COMPILE && dep.scope() != Scope.TEST) {
                depMap.put("scope", dep.scope().getValue());
            }

            if (dep.isInternal()) {
                depMap.put("comment", "Interne - liblocale");
            }

            dependencies.add(depMap);
        }

        // 2. Dépendances non résolues
        JarPackageAnalyzer packageAnalyzer = new JarPackageAnalyzer();
        for (AnalysisResult.UnresolvedJar unresolved : analysis.unresolved()) {
            JarInfo jar = unresolved.jar();
            Map<String, String> depMap = new LinkedHashMap<>();

            String cleanedName = fr.cnam.migration.config.JarNameCleaner.clean(jar.name());
            String artifactName = cleanedName.replace(".jar", "");
            String version = jar.sha1() != null ? "SHA-" + jar.sha1() : "UNKNOWN";

            String groupId;
            JarPackageAnalyzer.PackageAnalysis pkgAnalysis = packageAnalyzer.analyze(jar.path());
            if (pkgAnalysis.inferredGroupId() != null) {
                groupId = pkgAnalysis.inferredGroupId();
            } else {
                groupId = basePackage;
            }

            depMap.put("groupId", groupId);
            depMap.put("artifactId", artifactName);
            depMap.put("version", version);
            depMap.put("comment", "Non résolu - liblocale");

            dependencies.add(depMap);
        }

        // 3. Servlet API
        Map<String, String> servletApi = new LinkedHashMap<>();
        servletApi.put("groupId", "javax.servlet");
        servletApi.put("artifactId", "javax.servlet-api");
        servletApi.put("version", "3.1.0");
        servletApi.put("scope", "provided");
        dependencies.add(servletApi);

        log.info("Total: {} dépendances centralisées", dependencies.size());
        model.put("dependencies", dependencies);

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
     * Génère le POM pour un module JAR.
     */
    private Path generateJarModulePom(ProjectStructure project, ModuleInfo module,
                                       AnalysisResult analysis, Path moduleDir,
                                       Map<String, ModuleInfo> moduleByArtifactId) {
        String projectName = project.name().toLowerCase();
        String baseName = projectName.replace("_j", "");

        Map<String, Object> model = new HashMap<>();
        model.put("parent", Map.of(
            "groupId", config.basePackage() + "." + baseName,
            "artifactId", projectName,
            "version", "1.0.0-SNAPSHOT"
        ));
        model.put("artifactId", module.artifactId());
        model.put("moduleName", module.name());

        // Dependances internes (autres modules du projet)
        List<Map<String, String>> internalDeps = new ArrayList<>();
        if (module.hasInternalDependencies()) {
            for (String depName : module.dependsOn()) {
                // Trouver le module correspondant
                for (ModuleInfo m : project.getModulesInBuildOrder()) {
                    if (m.name().equals(depName)) {
                        internalDeps.add(Map.of(
                            "name", m.name(),
                            "artifactId", m.artifactId()
                        ));
                        break;
                    }
                }
            }
        }
        model.put("internalDependencies", internalDeps);

        // Dependances externes (toutes les dependances resolues)
        List<Map<String, String>> externalDeps = new ArrayList<>();
        for (DependencyInfo dep : analysis.resolved()) {
            if (dep.scope() != Scope.TEST || config.isPicBuild()) {
                Map<String, String> depMap = new LinkedHashMap<>();
                depMap.put("groupId", dep.groupId());
                depMap.put("artifactId", dep.artifactId());
                depMap.put("version", dep.version());
                if (dep.scope() != Scope.COMPILE) {
                    depMap.put("scope", dep.scope().getValue());
                }
                externalDeps.add(depMap);
            }
        }
        model.put("dependencies", externalDeps);
        model.put("hasResources", module.resourceDir() != null);

        Path pomFile = moduleDir.resolve("pom.xml");
        templateService.renderToFile("jar-pom.xml.ftl", model, pomFile);
        return pomFile;
    }

    /**
     * Génère le POM pour un module WAR dans un projet multi-module.
     */
    private Path generateWarModulePom(ProjectStructure project, ModuleInfo module,
                                       AnalysisResult analysis, Path moduleDir,
                                       Map<String, ModuleInfo> moduleByArtifactId) {
        String projectName = project.name().toLowerCase();
        String baseName = projectName.replace("_j", "");

        Map<String, Object> model = new HashMap<>();
        model.put("parent", Map.of(
            "groupId", config.basePackage() + "." + baseName,
            "artifactId", projectName,
            "version", "1.0.0-SNAPSHOT"
        ));
        model.put("artifactId", module.artifactId());
        model.put("warName", baseName);

        // Dependances internes (autres modules JAR du projet)
        List<Map<String, String>> allDependencies = new ArrayList<>();

        // D'abord les modules internes
        if (module.hasInternalDependencies()) {
            for (String depName : module.dependsOn()) {
                for (ModuleInfo m : project.getModulesInBuildOrder()) {
                    if (m.name().equals(depName)) {
                        allDependencies.add(Map.of(
                            "groupId", "${project.groupId}",
                            "artifactId", m.artifactId(),
                            "version", "${project.version}"
                        ));
                        break;
                    }
                }
            }
        }

        // Puis les dependances externes
        for (DependencyInfo dep : analysis.resolved()) {
            if (dep.scope() != Scope.TEST || config.isPicBuild()) {
                Map<String, String> depMap = new LinkedHashMap<>();
                depMap.put("groupId", dep.groupId());
                depMap.put("artifactId", dep.artifactId());
                depMap.put("version", dep.version());
                if (dep.scope() != Scope.COMPILE) {
                    depMap.put("scope", dep.scope().getValue());
                }
                allDependencies.add(depMap);
            }
        }
        model.put("dependencies", allDependencies);

        // Ressources exclues
        if (project.primaryBuild() != null) {
            model.put("excludedResources", project.primaryBuild().excludedFiles());
        }

        Path pomFile = moduleDir.resolve("pom.xml");
        templateService.renderToFile("war-pom.xml.ftl", model, pomFile);
        return pomFile;
    }

    /**
     * Génère le POM pour un module EAR dans un projet multi-module.
     */
    private Path generateEarModulePom(ProjectStructure project, ModuleInfo module,
                                       Path moduleDir, String baseName) {
        String projectName = project.name().toLowerCase();

        Map<String, Object> model = new HashMap<>();
        model.put("parent", Map.of(
            "groupId", config.basePackage() + "." + baseName,
            "artifactId", projectName,
            "version", "1.0.0-SNAPSHOT"
        ));
        model.put("artifactId", module.artifactId());

        // Trouver le module WAR
        String warArtifactId = baseName + "-web";
        for (ModuleInfo m : project.getModulesInBuildOrder()) {
            if (m.type() == ModuleInfo.ModuleType.WAR) {
                warArtifactId = m.artifactId();
                break;
            }
        }
        model.put("warArtifactId", warArtifactId);

        // Configuration EAR
        EarConfiguration earConfig = project.earConfig();
        if (earConfig != null) {
            model.put("contextRoot", earConfig.contextRoot() != null ?
                earConfig.contextRoot() : baseName);
            model.put("warFileName", earConfig.warFileName() != null ?
                earConfig.warFileName() : baseName + ".war");
            model.put("hasAppInfLib", earConfig.hasAppInfLib());
            model.put("hasAppInfConf", earConfig.appInfConfFiles() != null &&
                !earConfig.appInfConfFiles().isEmpty());
        } else {
            model.put("contextRoot", baseName);
            model.put("warFileName", baseName + ".war");
            model.put("hasAppInfLib", false);
            model.put("hasAppInfConf", false);
        }

        Path pomFile = moduleDir.resolve("pom.xml");
        templateService.renderToFile("ear-pom.xml.ftl", model, pomFile);
        return pomFile;
    }

    /**
     * Génère le POM pour un module de test dans un projet multi-module.
     */
    private Path generateTestModulePom(ProjectStructure project, ModuleInfo module,
                                        AnalysisResult analysis, Path moduleDir,
                                        Map<String, ModuleInfo> moduleByArtifactId) {
        String projectName = project.name().toLowerCase();
        String baseName = projectName.replace("_j", "");

        Map<String, Object> model = new HashMap<>();
        model.put("parent", Map.of(
            "groupId", config.basePackage() + "." + baseName,
            "artifactId", projectName,
            "version", "1.0.0-SNAPSHOT"
        ));
        model.put("artifactId", module.artifactId());
        model.put("moduleName", module.name());

        // Dependances internes (tous les modules JAR du projet)
        List<Map<String, String>> internalDeps = new ArrayList<>();
        for (ModuleInfo m : project.getModulesInBuildOrder()) {
            if (m.type() == ModuleInfo.ModuleType.JAR) {
                internalDeps.add(Map.of(
                    "name", m.name(),
                    "artifactId", m.artifactId()
                ));
            }
        }
        model.put("internalDependencies", internalDeps);
        model.put("hasResources", module.resourceDir() != null);

        Path pomFile = moduleDir.resolve("pom.xml");
        templateService.renderToFile("jar-pom.xml.ftl", model, pomFile);
        return pomFile;
    }

    /**
     * Génère le POM du module de distribution.
     */
    private Path generateDistPom(ProjectStructure project, Path distModuleDir, String moduleName) {
        String projectName = project.name().toLowerCase();

        Map<String, Object> model = new HashMap<>();
        model.put("parent", Map.of(
            "groupId", config.basePackage() + "." + moduleName,
            "artifactId", projectName,
            "version", "1.0.0-SNAPSHOT"
        ));
        model.put("artifactId", moduleName + "-dist");
        model.put("earArtifactId", moduleName + "-ear");

        Path pomFile = distModuleDir.resolve("pom.xml");
        templateService.renderToFile("dist-pom.xml.ftl", model, pomFile);
        return pomFile;
    }

    /**
     * Génère le descripteur assembly pour le module de distribution.
     */
    private Path generateDistributionXml(ProjectStructure project, Path distModuleDir, String moduleName) {
        ProjectStructure.DistributionConfig distConfig = project.distributionConfig();

        Map<String, Object> model = new HashMap<>();
        model.put("earArtifactId", moduleName + "-ear");
        model.put("webModule", moduleName + "-web");
        model.put("confExclusions", distConfig != null ? distConfig.confExclusions() : List.of());
        model.put("hasInstallScript", distConfig != null && distConfig.installScriptPath() != null);

        Path assemblyFile = distModuleDir.resolve("src/assembly/distribution.xml");
        templateService.renderToFile("distribution.xml.ftl", model, assemblyFile);
        return assemblyFile;
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

        // Les modules (inclure dist si configuration de distribution présente)
        List<String> modules = new ArrayList<>();
        modules.add(moduleName + "-web");
        modules.add(moduleName + "-ear");
        if (project.distributionConfig() != null && project.distributionConfig().hasDistribution()) {
            modules.add(moduleName + "-dist");
        }
        model.put("modules", modules);

        // Propriétés de version
        Map<String, String> properties = buildVersionProperties(analysis);
        model.put("properties", properties);

        // Gestion des dépendances
        List<Map<String, String>> depMgmt = buildDependencyManagement(analysis);
        model.put("dependencyManagement", depMgmt);

        // Toutes les dépendances (résolues + non résolues) centralisées dans le parent
        List<Map<String, String>> dependencies = new ArrayList<>();
        String basePackage = config.basePackage();

        log.info("Génération des dépendances centralisées dans le pom.xml parent");
        log.info("  - {} dépendances résolues", analysis.resolved().size());
        log.info("  - {} dépendances non résolues", analysis.unresolved().size());

        // 1. Dépendances résolues
        for (DependencyInfo dep : analysis.resolved()) {
            if (dep.scope() != Scope.TEST || config.isPicBuild()) {
                Map<String, String> depMap = new LinkedHashMap<>();
                depMap.put("groupId", dep.groupId());
                depMap.put("artifactId", dep.artifactId());
                depMap.put("version", dep.version());

                if (dep.scope() != Scope.COMPILE) {
                    depMap.put("scope", dep.scope().getValue());
                }

                if (dep.isInternal()) {
                    depMap.put("comment", "Interne - liblocale");
                }

                dependencies.add(depMap);
            }
        }

        // 2. Dépendances non résolues (installées via liblocale)
        JarPackageAnalyzer packageAnalyzer = new JarPackageAnalyzer();
        for (AnalysisResult.UnresolvedJar unresolved : analysis.unresolved()) {
            JarInfo jar = unresolved.jar();
            Map<String, String> depMap = new LinkedHashMap<>();

            String cleanedName = fr.cnam.migration.config.JarNameCleaner.clean(jar.name());
            String artifactName = cleanedName.replace(".jar", "");
            String version = jar.sha1() != null ? "SHA-" + jar.sha1() : "UNKNOWN";

            String groupId;
            JarPackageAnalyzer.PackageAnalysis pkgAnalysis = packageAnalyzer.analyze(jar.path());
            if (pkgAnalysis.inferredGroupId() != null) {
                groupId = pkgAnalysis.inferredGroupId();
            } else {
                groupId = basePackage;
            }

            depMap.put("groupId", groupId);
            depMap.put("artifactId", artifactName);
            depMap.put("version", version);
            depMap.put("comment", "Non résolu - liblocale");

            dependencies.add(depMap);
        }

        // 3. Servlet API (provided par le serveur d'applications)
        Map<String, String> servletApi = new LinkedHashMap<>();
        servletApi.put("groupId", "javax.servlet");
        servletApi.put("artifactId", "javax.servlet-api");
        servletApi.put("version", "3.1.0");
        servletApi.put("scope", "provided");
        dependencies.add(servletApi);

        log.info("Total: {} dépendances centralisées dans le pom.xml parent", dependencies.size());
        model.put("dependencies", dependencies);

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
        // Utilise le SHA1 du JAR comme version pour garantir l'unicité
        // Analyse le package réel du JAR pour déterminer le groupId correct
        JarPackageAnalyzer packageAnalyzer = new JarPackageAnalyzer();
        for (AnalysisResult.UnresolvedJar unresolved : analysis.unresolved()) {
            JarInfo jar = unresolved.jar();
            Map<String, String> depMap = new LinkedHashMap<>();

            // Générer des coordonnées Maven pour le JAR non résolu
            String cleanedName = fr.cnam.migration.config.JarNameCleaner.clean(jar.name());
            String artifactName = cleanedName.replace(".jar", "");
            String version = jar.sha1() != null ? "SHA-" + jar.sha1() : "UNKNOWN";

            // Analyser le package réel du JAR pour déterminer le groupId
            String groupId;
            JarPackageAnalyzer.PackageAnalysis pkgAnalysis = packageAnalyzer.analyze(jar.path());
            if (pkgAnalysis.inferredGroupId() != null) {
                groupId = pkgAnalysis.inferredGroupId();
            } else {
                groupId = basePackage;
            }

            depMap.put("groupId", groupId);
            depMap.put("artifactId", artifactName);
            depMap.put("version", version);
            depMap.put("comment", "Non résolu - installer via ./liblocale/install-local-jars.sh");

            log.debug("  Dépendance non résolue: {}:{}:{}", groupId, artifactName, version);

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

            // Dépendances APP-INF/lib - utilise le SHA1 comme version pour garantir l'unicité
            if (earConfig.hasAppInfLib()) {
                List<Map<String, String>> appInfLibs = new ArrayList<>();
                for (Path jarPath : earConfig.appInfLibJars()) {
                    String jarName = jarPath.getFileName().toString();
                    String sha1 = computeSha1(jarPath);
                    String version = sha1 != null ? "SHA-" + sha1 : "UNKNOWN";
                    // Ce sont typiquement des artefacts internes
                    appInfLibs.add(Map.of(
                        "groupId", config.basePackage() + ".internal",
                        "artifactId", jarName.replace(".jar", ""),
                        "version", version
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

        // Créer maven.config pour utiliser automatiquement le settings.xml local
        Path mavenConfigPath = outputDir.resolve(".mvn/maven.config");
        Files.writeString(mavenConfigPath, "-s .mvn/wrapper/settings.xml\n");

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
     * Calcule le SHA1 d'un fichier JAR.
     * Retourne null en cas d'erreur.
     */
    private String computeSha1(Path jarPath) {
        try (InputStream is = Files.newInputStream(jarPath)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = is.read(buffer)) != -1) {
                digest.update(buffer, 0, bytesRead);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception e) {
            log.warn("Impossible de calculer le SHA1 pour {}: {}", jarPath, e.getMessage());
            return null;
        }
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
