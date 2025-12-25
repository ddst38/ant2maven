package fr.cnam.migration.scanner;

import fr.cnam.migration.config.MigrationConfig;
import fr.cnam.migration.model.*;
import org.dom4j.Document;
import org.dom4j.Element;
import org.dom4j.io.SAXReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Main scanner that orchestrates project structure analysis.
 * Generic implementation that detects project structure dynamically.
 */
public class ProjectScanner {

    private static final Logger log = LoggerFactory.getLogger(ProjectScanner.class);

    private final JarScanner jarScanner;
    private final AntBuildParser antParser;
    private final PropertiesConfParser propsParser;

    public ProjectScanner() {
        this.jarScanner = new JarScanner();
        this.antParser = new AntBuildParser();
        this.propsParser = new PropertiesConfParser();
    }

    /**
     * Scans the project and returns its complete structure.
     */
    public ProjectStructure scan(MigrationConfig config) throws IOException {
        Path projectRoot = config.projectRoot();
        log.info("Scanning project at: {}", projectRoot);

        // Detect project type
        ProjectType type = detectProjectType(projectRoot);
        log.info("Detected project type: {}", type);

        // Get project name from directory
        String projectName = projectRoot.getFileName().toString();

        // Find all JARs and categorize them
        List<JarInfo> allJars = jarScanner.findAllJars(projectRoot);
        List<JarInfo> mainLibs = new ArrayList<>();
        List<JarInfo> testLibs = new ArrayList<>();
        List<JarInfo> providedLibs = new ArrayList<>();

        for (JarInfo jar : allJars) {
            if (jar.isSourceJar()) {
                continue; // Skip source JARs
            }

            JarInfo.JarCategory category = jarScanner.categorizeByPath(jar.path(), projectRoot);
            JarInfo categorized = jar.withCategory(category);

            switch (category) {
                case TEST -> testLibs.add(categorized);
                case PROVIDED -> providedLibs.add(categorized);
                default -> mainLibs.add(categorized);
            }
        }

        log.info("Categorized JARs: {} main, {} test, {} provided",
            mainLibs.size(), testLibs.size(), providedLibs.size());

        // Parse Ant build files
        List<AntBuildInfo> builds = antParser.parseAll(projectRoot);
        log.info("Found {} Ant build files", builds.size());

        // Parse properties.conf for internal dependencies
        Path propsConf = projectRoot.resolve("install/properties.conf");
        List<InternalDependency> internalDeps = propsParser.parse(propsConf);

        // Analyze source layout
        ProjectStructure.SourceLayout sourceLayout = analyzeSourceLayout(projectRoot, type);

        // Extract EAR configuration
        EarConfiguration earConfig = extractEarConfig(projectRoot, type);

        return ProjectStructure.builder()
            .name(projectName)
            .projectRoot(projectRoot)
            .type(type)
            .mainLibs(mainLibs)
            .testLibs(testLibs)
            .providedLibs(providedLibs)
            .builds(builds)
            .internalDeps(internalDeps)
            .sourceLayout(sourceLayout)
            .earConfig(earConfig)
            .build();
    }

    /**
     * Detects the project type based on directory structure.
     * Generic detection without hardcoded project names.
     */
    private ProjectType detectProjectType(Path projectRoot) {
        try (Stream<Path> dirs = Files.list(projectRoot)) {
            // Look for Maven-style: any directory ending with -app that has src/main/java
            boolean hasMavenStyle = dirs
                .filter(Files::isDirectory)
                .filter(p -> p.getFileName().toString().endsWith("-app"))
                .anyMatch(p -> Files.exists(p.resolve("src/main/java")));

            if (hasMavenStyle) {
                return ProjectType.MAVEN_STYLE;
            }
        } catch (IOException ignored) {
        }

        try (Stream<Path> dirs = Files.list(projectRoot)) {
            // Look for Eclipse-style: directory with src (not src/main) and WebContent
            boolean hasEclipseStyle = dirs
                .filter(Files::isDirectory)
                .filter(p -> !p.getFileName().toString().equals("install"))
                .filter(p -> !p.getFileName().toString().toLowerCase().endsWith("ear"))
                .anyMatch(p -> {
                    boolean hasSrc = Files.exists(p.resolve("src"));
                    boolean hasWebContent = Files.exists(p.resolve("WebContent"));
                    boolean notMavenStyle = !Files.exists(p.resolve("src/main/java"));
                    return hasSrc && (hasWebContent || notMavenStyle);
                });

            if (hasEclipseStyle) {
                return ProjectType.ECLIPSE_STYLE;
            }
        } catch (IOException ignored) {
        }

        // Default to Maven style
        log.warn("Could not detect project type, defaulting to MAVEN_STYLE");
        return ProjectType.MAVEN_STYLE;
    }

    /**
     * Finds the main application directory dynamically.
     */
    private Path findAppDirectory(Path projectRoot, ProjectType type) {
        try (Stream<Path> dirs = Files.list(projectRoot)) {
            return dirs
                .filter(Files::isDirectory)
                .filter(p -> {
                    String name = p.getFileName().toString();
                    // Skip install and ear directories
                    if (name.equals("install") || name.toLowerCase().endsWith("ear")) {
                        return false;
                    }
                    if (name.equals("CVS") || name.equals(".git") || name.equals("target")) {
                        return false;
                    }

                    if (type == ProjectType.MAVEN_STYLE) {
                        // Look for *-app directory with Maven structure
                        return name.endsWith("-app") && Files.exists(p.resolve("src/main/java"));
                    } else {
                        // Eclipse style: has src or WebContent
                        return Files.exists(p.resolve("src")) || Files.exists(p.resolve("WebContent"));
                    }
                })
                .findFirst()
                .orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Analyzes the source code layout.
     */
    private ProjectStructure.SourceLayout analyzeSourceLayout(Path projectRoot, ProjectType type) {
        ProjectStructure.SourceLayout.Builder builder = ProjectStructure.SourceLayout.builder();

        Path appDir = findAppDirectory(projectRoot, type);
        if (appDir != null) {
            if (type == ProjectType.MAVEN_STYLE) {
                builder.mainJavaDir(appDir.resolve("src/main/java"));
                builder.mainResourcesDir(appDir.resolve("src/main/resources"));
                builder.testJavaDir(appDir.resolve("src/test/java"));
                builder.testResourcesDir(appDir.resolve("src/test/resources"));
                builder.webappDir(appDir.resolve("src/main/webapp"));
            } else {
                // Eclipse style
                builder.mainJavaDir(appDir.resolve("src"));
                builder.mainResourcesDir(appDir.resolve("conf"));
                builder.testJavaDir(appDir.resolve("test"));
                builder.webappDir(appDir.resolve("WebContent"));
            }
        }

        // Count Java files
        ProjectStructure.SourceLayout layout = builder.build();
        if (layout.mainJavaDir() != null && Files.exists(layout.mainJavaDir())) {
            builder.mainJavaFileCount(countJavaFiles(layout.mainJavaDir()));
        }

        if (layout.testJavaDir() != null && Files.exists(layout.testJavaDir())) {
            builder.testJavaFileCount(countJavaFiles(layout.testJavaDir()));
        }

        return builder.build();
    }

    private int countJavaFiles(Path directory) {
        if (!Files.exists(directory)) {
            return 0;
        }
        try (Stream<Path> files = Files.walk(directory)) {
            return (int) files
                .filter(Files::isRegularFile)
                .filter(p -> p.toString().endsWith(".java"))
                .count();
        } catch (IOException e) {
            return 0;
        }
    }

    /**
     * Finds the EAR directory dynamically.
     */
    private Path findEarDirectory(Path projectRoot) {
        try (Stream<Path> dirs = Files.list(projectRoot)) {
            return dirs
                .filter(Files::isDirectory)
                .filter(p -> p.getFileName().toString().toLowerCase().endsWith("ear"))
                .findFirst()
                .orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Extracts EAR configuration from the project.
     */
    private EarConfiguration extractEarConfig(Path projectRoot, ProjectType type) {
        EarConfiguration.Builder builder = EarConfiguration.builder();

        // Find EAR directory
        Path earDir = findEarDirectory(projectRoot);
        if (earDir == null) {
            log.warn("EAR directory not found");
            return builder.build();
        }

        // Find META-INF
        Path metaInf = earDir.resolve("EarContent/META-INF");
        if (!Files.exists(metaInf)) {
            metaInf = earDir.resolve("META-INF");
        }

        // Parse application.xml
        Path applicationXml = metaInf.resolve("application.xml");
        if (Files.exists(applicationXml)) {
            builder.applicationXml(applicationXml);
            parseApplicationXml(applicationXml, builder);
        }

        // Find weblogic-application.xml
        Path weblogicXml = metaInf.resolve("weblogic-application.xml");
        if (Files.exists(weblogicXml)) {
            builder.weblogicApplicationXml(weblogicXml);
            parseWeblogicApplicationXml(weblogicXml, builder);
        }

        // Find APP-INF/lib JARs
        Path appInfLib = earDir.resolve("EarContent/APP-INF/lib");
        if (Files.exists(appInfLib)) {
            try (Stream<Path> jars = Files.list(appInfLib)) {
                builder.appInfLibJars(jars.filter(p -> p.toString().endsWith(".jar")).toList());
            } catch (IOException ignored) {
            }
        }

        // Find APP-INF/conf files
        Path appInfConf = earDir.resolve("EarContent/APP-INF/conf");
        if (Files.exists(appInfConf)) {
            try (Stream<Path> files = Files.list(appInfConf)) {
                builder.appInfConfFiles(files.toList());
            } catch (IOException ignored) {
            }
        }

        return builder.build();
    }

    private void parseApplicationXml(Path file, EarConfiguration.Builder builder) {
        try {
            SAXReader reader = new SAXReader();
            Document doc = reader.read(file.toFile());
            Element root = doc.getRootElement();

            // Get display-name
            Element displayName = root.element("display-name");
            if (displayName != null) {
                builder.displayName(displayName.getTextTrim());
            }

            // Get web module info
            for (Element module : root.elements("module")) {
                Element web = module.element("web");
                if (web != null) {
                    Element webUri = web.element("web-uri");
                    Element contextRoot = web.element("context-root");
                    if (webUri != null) {
                        builder.warFileName(webUri.getTextTrim());
                    }
                    if (contextRoot != null) {
                        builder.contextRoot(contextRoot.getTextTrim());
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Failed to parse application.xml: {}", e.getMessage());
        }
    }

    private void parseWeblogicApplicationXml(Path file, EarConfiguration.Builder builder) {
        try {
            SAXReader reader = new SAXReader();
            Document doc = reader.read(file.toFile());
            Element root = doc.getRootElement();

            // Get library-ref
            Element libraryRef = root.element("library-ref");
            if (libraryRef != null) {
                Element libName = libraryRef.element("library-name");
                if (libName != null) {
                    builder.sharedLibraryName(libName.getTextTrim());
                }
            }

            // Get prefer-application-packages
            Element preferPkgs = root.element("prefer-application-packages");
            if (preferPkgs != null) {
                List<String> packages = new ArrayList<>();
                for (Element pkg : preferPkgs.elements("package-name")) {
                    packages.add(pkg.getTextTrim());
                }
                builder.preferApplicationPackages(packages);
            }
        } catch (Exception e) {
            log.warn("Failed to parse weblogic-application.xml: {}", e.getMessage());
        }
    }
}
