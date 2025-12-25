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
 * Main generator that orchestrates Maven project creation.
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
     * Generates the complete Maven project.
     */
    public GenerationResult generate(ProjectStructure project, AnalysisResult analysis)
            throws IOException {
        Path outputDir = config.outputDir();
        log.info("Generating Maven project in: {}", outputDir);

        List<Path> createdFiles = new ArrayList<>();

        // Create directory structure and copy files
        structureCreator.createStructure(project, outputDir);

        // Generate parent POM
        Path parentPom = generateParentPom(project, analysis, outputDir);
        createdFiles.add(parentPom);

        // Generate WAR module POM
        String moduleName = project.name().toLowerCase().replace("_j", "");
        Path warPom = generateWarPom(project, analysis, outputDir.resolve(moduleName + "-web"));
        createdFiles.add(warPom);

        // Generate EAR module POM
        Path earPom = generateEarPom(project, outputDir.resolve(moduleName + "-ear"));
        createdFiles.add(earPom);

        // Install Maven wrapper
        installMavenWrapper(outputDir);
        createdFiles.add(outputDir.resolve("mvnw"));
        createdFiles.add(outputDir.resolve("mvnw.cmd"));

        log.info("Maven project generation complete: {} files created", createdFiles.size());

        return new GenerationResult(outputDir, createdFiles, true);
    }

    /**
     * Generates the parent POM.
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

        // Modules
        model.put("modules", List.of(moduleName + "-web", moduleName + "-ear"));

        // Version properties
        Map<String, String> properties = buildVersionProperties(analysis);
        model.put("properties", properties);

        // Dependency management
        List<Map<String, String>> depMgmt = buildDependencyManagement(analysis);
        model.put("dependencyManagement", depMgmt);

        // Internal repository if configured
        if (config.internalRepoUrl() != null) {
            model.put("internalRepository", config.internalRepoUrl());
        }

        // PIC profile if project has build.pic.xml
        model.put("hasPicProfile", project.picBuild() != null);

        Path pomFile = outputDir.resolve("pom.xml");
        templateService.renderToFile("parent-pom.xml.ftl", model, pomFile);
        return pomFile;
    }

    /**
     * Generates the WAR module POM.
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

        // Dependencies
        List<Map<String, String>> dependencies = new ArrayList<>();
        for (DependencyInfo dep : analysis.resolved()) {
            if (dep.scope() != Scope.TEST || config.isPicBuild()) {
                Map<String, String> depMap = new LinkedHashMap<>();
                depMap.put("groupId", dep.groupId());
                depMap.put("artifactId", dep.artifactId());
                // Use property reference or direct version
                if (shouldUsePropertyVersion(dep)) {
                    depMap.put("version", null); // Will be managed by parent
                } else {
                    depMap.put("version", dep.version());
                }
                if (dep.scope() != Scope.COMPILE) {
                    depMap.put("scope", dep.scope().getValue());
                }
                dependencies.add(depMap);
            }
        }
        model.put("dependencies", dependencies);

        // Excluded resources
        if (project.primaryBuild() != null) {
            model.put("excludedResources", project.primaryBuild().excludedFiles());
        }

        Path pomFile = webModuleDir.resolve("pom.xml");
        templateService.renderToFile("war-pom.xml.ftl", model, pomFile);
        return pomFile;
    }

    /**
     * Generates the EAR module POM.
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

        // EAR configuration
        EarConfiguration earConfig = project.earConfig();
        if (earConfig != null) {
            model.put("contextRoot", earConfig.contextRoot() != null ?
                earConfig.contextRoot() : moduleName);
            model.put("warFileName", earConfig.warFileName() != null ?
                earConfig.warFileName() : moduleName + ".war");
            model.put("hasAppInfLib", earConfig.hasAppInfLib());
            model.put("hasAppInfConf", earConfig.appInfConfFiles() != null &&
                !earConfig.appInfConfFiles().isEmpty());

            // APP-INF/lib dependencies
            if (earConfig.hasAppInfLib()) {
                List<Map<String, String>> appInfLibs = new ArrayList<>();
                for (Path jarPath : earConfig.appInfLibJars()) {
                    String jarName = jarPath.getFileName().toString();
                    // These are typically internal artifacts
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
     * Builds version properties from resolved dependencies.
     */
    private Map<String, String> buildVersionProperties(AnalysisResult analysis) {
        Map<String, String> properties = new LinkedHashMap<>();

        // Group dependencies by framework/family
        Map<String, String> familyVersions = new HashMap<>();

        for (DependencyInfo dep : analysis.resolved()) {
            String family = extractFamily(dep.groupId(), dep.artifactId());
            if (family != null && !familyVersions.containsKey(family)) {
                familyVersions.put(family, dep.version());
            }
        }

        // Convert to property names
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
     * Builds dependency management section.
     */
    private List<Map<String, String>> buildDependencyManagement(AnalysisResult analysis) {
        return analysis.resolved().stream()
            .filter(dep -> !dep.isInternal())
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
     * Installs Maven wrapper.
     */
    private void installMavenWrapper(Path outputDir) throws IOException {
        // Create .mvn/wrapper directory
        Path wrapperDir = outputDir.resolve(".mvn/wrapper");
        Files.createDirectories(wrapperDir);

        // Create maven-wrapper.properties
        String wrapperProps = """
            distributionUrl=https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.9.6/apache-maven-3.9.6-bin.zip
            wrapperUrl=https://repo.maven.apache.org/maven2/org/apache/maven/wrapper/maven-wrapper/3.2.0/maven-wrapper-3.2.0.jar
            """;
        Files.writeString(wrapperDir.resolve("maven-wrapper.properties"), wrapperProps);

        // Create mvnw script
        String mvnw = """
            #!/bin/sh
            # Maven Wrapper script
            # Download and run Maven wrapper

            MAVEN_PROJECTBASEDIR="${MAVEN_BASEDIR:-$(cd "$(dirname "$0")" && pwd)}"
            WRAPPER_JAR="$MAVEN_PROJECTBASEDIR/.mvn/wrapper/maven-wrapper.jar"

            if [ ! -f "$WRAPPER_JAR" ]; then
                echo "Downloading Maven wrapper..."
                mkdir -p "$(dirname "$WRAPPER_JAR")"
                curl -sLo "$WRAPPER_JAR" "https://repo.maven.apache.org/maven2/org/apache/maven/wrapper/maven-wrapper/3.2.0/maven-wrapper-3.2.0.jar"
            fi

            exec java -jar "$WRAPPER_JAR" "$@"
            """;
        Path mvnwPath = outputDir.resolve("mvnw");
        Files.writeString(mvnwPath, mvnw);
        mvnwPath.toFile().setExecutable(true);

        // Create mvnw.cmd for Windows
        String mvnwCmd = """
            @echo off
            setlocal

            set MAVEN_PROJECTBASEDIR=%~dp0
            set WRAPPER_JAR=%MAVEN_PROJECTBASEDIR%.mvn\\wrapper\\maven-wrapper.jar

            if not exist "%WRAPPER_JAR%" (
                echo Downloading Maven wrapper...
                powershell -Command "Invoke-WebRequest -Uri 'https://repo.maven.apache.org/maven2/org/apache/maven/wrapper/maven-wrapper/3.2.0/maven-wrapper-3.2.0.jar' -OutFile '%WRAPPER_JAR%'"
            )

            java -jar "%WRAPPER_JAR%" %*
            """;
        Files.writeString(outputDir.resolve("mvnw.cmd"), mvnwCmd);

        log.info("Maven wrapper installed");
    }

    /**
     * Result of project generation.
     */
    public record GenerationResult(
        Path outputDir,
        List<Path> createdFiles,
        boolean success
    ) {}
}
