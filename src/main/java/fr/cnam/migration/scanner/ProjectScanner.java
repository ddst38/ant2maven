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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Scanner principal qui orchestre l'analyse de la structure du projet.
 * Implémentation générique qui détecte la structure du projet dynamiquement.
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
     * Scanne le projet et retourne sa structure complète.
     */
    public ProjectStructure scan(MigrationConfig config) throws IOException {
        Path projectRoot = config.projectRoot();
        log.info("Scanning project at: {}", projectRoot);

        // Détecter le type de projet
        ProjectType type = detectProjectType(projectRoot);
        log.info("Detected project type: {}", type);

        // Obtenir le nom du projet depuis le répertoire
        String projectName = projectRoot.getFileName().toString();

        // Trouver tous les JARs et les catégoriser
        List<JarInfo> allJars = jarScanner.findAllJars(projectRoot);

        // Scanner les fichiers EAR pour extraire les JARs qu'ils contiennent
        // C'est particulièrement important pour les frameworks WebLogic (modules _W)
        List<JarInfo> jarsFromEars = scanEarFiles(projectRoot, config);
        if (!jarsFromEars.isEmpty()) {
            log.info("Ajout de {} JARs extraits depuis des fichiers EAR", jarsFromEars.size());
            allJars.addAll(jarsFromEars);
        }

        List<JarInfo> mainLibs = new ArrayList<>();
        List<JarInfo> testLibs = new ArrayList<>();
        List<JarInfo> providedLibs = new ArrayList<>();

        // Utiliser un Set pour éviter les doublons basés sur le SHA1
        Set<String> seenSha1s = new HashSet<>();

        for (JarInfo jar : allJars) {
            if (jar.isSourceJar()) {
                continue; // Ignorer les JARs sources
            }

            // Éviter les doublons (même SHA1)
            if (jar.sha1() != null && seenSha1s.contains(jar.sha1())) {
                log.debug("JAR doublon ignoré (même SHA1) : {}", jar.name());
                continue;
            }
            if (jar.sha1() != null) {
                seenSha1s.add(jar.sha1());
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

        // Parser les fichiers de build Ant
        List<AntBuildInfo> builds = antParser.parseAll(projectRoot);
        log.info("Found {} Ant build files", builds.size());

        // Parser properties.conf pour les dépendances internes
        Path propsConf = projectRoot.resolve("install/properties.conf");
        List<InternalDependency> internalDeps = propsParser.parse(propsConf);

        // Analyser la structure des sources
        ProjectStructure.SourceLayout sourceLayout = analyzeSourceLayout(projectRoot, type);

        // Extraire la configuration EAR
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
     * Détecte le type de projet basé sur la structure des répertoires.
     * Détection générique sans noms de projets codés en dur.
     */
    private ProjectType detectProjectType(Path projectRoot) {
        try (Stream<Path> dirs = Files.list(projectRoot)) {
            // Chercher style Maven : tout répertoire finissant par -app qui a src/main/java
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
            // Chercher le style Eclipse : répertoire avec src (pas src/main) et WebContent
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

        // Par défaut style Maven
        log.warn("Could not detect project type, defaulting to MAVEN_STYLE");
        return ProjectType.MAVEN_STYLE;
    }

    /**
     * Trouve le répertoire principal de l'application dynamiquement.
     */
    private Path findAppDirectory(Path projectRoot, ProjectType type) {
        try (Stream<Path> dirs = Files.list(projectRoot)) {
            return dirs
                .filter(Files::isDirectory)
                .filter(p -> {
                    String name = p.getFileName().toString();
                    // Ignorer les répertoires install et ear
                    if (name.equals("install") || name.toLowerCase().endsWith("ear")) {
                        return false;
                    }
                    if (name.equals("CVS") || name.equals(".git") || name.equals("target")) {
                        return false;
                    }

                    if (type == ProjectType.MAVEN_STYLE) {
                        // Chercher un répertoire *-app avec structure Maven
                        return name.endsWith("-app") && Files.exists(p.resolve("src/main/java"));
                    } else {
                        // Style Eclipse : a src ou WebContent
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
                // Style Eclipse
                builder.mainJavaDir(appDir.resolve("src"));
                builder.mainResourcesDir(appDir.resolve("conf"));
                builder.testJavaDir(appDir.resolve("test"));
                builder.webappDir(appDir.resolve("WebContent"));
            }
        }

        // Compter les fichiers Java
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
     * Trouve le répertoire EAR dynamiquement.
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
     * Extrait la configuration EAR du projet.
     */
    private EarConfiguration extractEarConfig(Path projectRoot, ProjectType type) {
        EarConfiguration.Builder builder = EarConfiguration.builder();

        // Trouver le répertoire EAR
        Path earDir = findEarDirectory(projectRoot);
        if (earDir == null) {
            log.warn("EAR directory not found");
            return builder.build();
        }

        // Trouver META-INF
        Path metaInf = earDir.resolve("EarContent/META-INF");
        if (!Files.exists(metaInf)) {
            metaInf = earDir.resolve("META-INF");
        }

        // Parser application.xml
        Path applicationXml = metaInf.resolve("application.xml");
        if (Files.exists(applicationXml)) {
            builder.applicationXml(applicationXml);
            parseApplicationXml(applicationXml, builder);
        }

        // Trouver weblogic-application.xml
        Path weblogicXml = metaInf.resolve("weblogic-application.xml");
        if (Files.exists(weblogicXml)) {
            builder.weblogicApplicationXml(weblogicXml);
            parseWeblogicApplicationXml(weblogicXml, builder);
        }

        // Trouver les JARs APP-INF/lib
        Path appInfLib = earDir.resolve("EarContent/APP-INF/lib");
        if (Files.exists(appInfLib)) {
            try (Stream<Path> jars = Files.list(appInfLib)) {
                builder.appInfLibJars(jars.filter(p -> p.toString().endsWith(".jar")).toList());
            } catch (IOException ignored) {
            }
        }

        // Trouver les fichiers APP-INF/conf
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

            // Récupérer le display-name
            Element displayName = root.element("display-name");
            if (displayName != null) {
                builder.displayName(displayName.getTextTrim());
            }

            // Récupérer les infos du module web
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

            // Récupérer library-ref
            Element libraryRef = root.element("library-ref");
            if (libraryRef != null) {
                Element libName = libraryRef.element("library-name");
                if (libName != null) {
                    builder.sharedLibraryName(libName.getTextTrim());
                }
            }

            // Récupérer prefer-application-packages
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

    /**
     * Scanne les fichiers EAR du projet et extrait les JARs qu'ils contiennent.
     *
     * Les fichiers EAR (Enterprise Archive) peuvent contenir des bibliothèques JAR,
     * notamment dans APP-INF/lib/. C'est particulièrement important pour :
     * - Les frameworks WebLogic (modules finissant par _W comme R0_W.ear)
     * - Les répertoires "cadre" ou "framework"
     *
     * Les JARs extraits sont placés dans un répertoire temporaire sous le répertoire
     * de sortie pour permettre leur analyse et leur inclusion dans le projet Maven.
     *
     * @param projectRoot Répertoire racine du projet
     * @param config Configuration de la migration
     * @return Liste des JarInfo extraits des fichiers EAR
     */
    private List<JarInfo> scanEarFiles(Path projectRoot, MigrationConfig config) {
        List<JarInfo> extractedJars = new ArrayList<>();

        try {
            // Trouver tous les fichiers EAR dans le projet
            List<Path> earFiles = jarScanner.findAllEars(projectRoot);

            if (earFiles.isEmpty()) {
                return extractedJars;
            }

            // Créer un répertoire temporaire pour l'extraction
            Path extractDir = config.outputDir().resolve(".ear-extracted");
            Files.createDirectories(extractDir);

            for (Path earPath : earFiles) {
                try {
                    // Extraire les JARs de chaque EAR
                    List<JarInfo> jarsFromEar = jarScanner.extractJarsFromEar(earPath, extractDir);
                    extractedJars.addAll(jarsFromEar);
                } catch (IOException e) {
                    log.warn("Échec de l'extraction des JARs depuis {} : {}",
                        earPath.getFileName(), e.getMessage());
                }
            }

            if (!extractedJars.isEmpty()) {
                log.info("Total de {} JARs extraits depuis {} fichiers EAR",
                    extractedJars.size(), earFiles.size());
            }

        } catch (IOException e) {
            log.warn("Erreur lors du scan des fichiers EAR : {}", e.getMessage());
        }

        return extractedJars;
    }
}
