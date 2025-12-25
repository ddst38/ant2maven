package fr.cnam.migration.scanner;

import fr.cnam.migration.model.AntBuildInfo;
import org.dom4j.Document;
import org.dom4j.DocumentException;
import org.dom4j.Element;
import org.dom4j.io.SAXReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Parses Ant build.xml files to extract project structure information.
 */
public class AntBuildParser {

    private static final Logger log = LoggerFactory.getLogger(AntBuildParser.class);

    /**
     * Parses all build.xml files in the install directory.
     */
    public List<AntBuildInfo> parseAll(Path projectRoot) {
        List<AntBuildInfo> builds = new ArrayList<>();
        Path installDir = projectRoot.resolve("install");

        if (!Files.exists(installDir)) {
            log.warn("Install directory not found: {}", installDir);
            return builds;
        }

        // Parse main build.xml
        Path mainBuild = installDir.resolve("build.xml");
        if (Files.exists(mainBuild)) {
            try {
                builds.add(parse(mainBuild, false));
            } catch (Exception e) {
                log.error("Failed to parse {}: {}", mainBuild, e.getMessage());
            }
        }

        // Parse build.pic.xml if exists
        Path picBuild = installDir.resolve("build.pic.xml");
        if (Files.exists(picBuild)) {
            try {
                builds.add(parse(picBuild, true));
            } catch (Exception e) {
                log.error("Failed to parse {}: {}", picBuild, e.getMessage());
            }
        }

        return builds;
    }

    /**
     * Parses a single build.xml file.
     */
    public AntBuildInfo parse(Path buildFile, boolean isPic) throws DocumentException {
        log.info("Parsing Ant build file: {}", buildFile);

        SAXReader reader = new SAXReader();
        Document document = reader.read(buildFile.toFile());
        Element root = document.getRootElement();

        AntBuildInfo.Builder builder = AntBuildInfo.builder()
            .buildFile(buildFile)
            .isPicBuild(isPic);

        // Extract project name
        String projectName = root.attributeValue("name");
        builder.projectName(projectName);

        // Extract default target
        String defaultTarget = root.attributeValue("default");
        builder.defaultTarget(defaultTarget != null ? defaultTarget : "package");

        // Extract properties
        Map<String, String> properties = new LinkedHashMap<>();
        for (Element prop : root.elements("property")) {
            String name = prop.attributeValue("name");
            String value = prop.attributeValue("value");
            if (name != null && value != null) {
                properties.put(name, value);
            }
        }
        builder.properties(properties);

        // Extract app.code
        String appCode = properties.get("app.code");
        builder.appCode(appCode);

        // Extract WAR name
        String warName = properties.get("app.code.war");
        if (warName == null && appCode != null) {
            warName = appCode + "_J";
        }
        builder.warName(warName != null ? warName + "-app.war" : null);

        // Extract source directories
        List<String> sourceDirs = new ArrayList<>();
        String srcJava = properties.get("src.java");
        if (srcJava != null) {
            sourceDirs.add(srcJava);
        }
        String srcMetier = properties.get("srcMetier");
        if (srcMetier != null) {
            sourceDirs.add(srcMetier);
        }
        builder.sourceDirs(sourceDirs);

        // Extract resource directories
        List<String> resourceDirs = new ArrayList<>();
        String srcResources = properties.get("src.resources");
        if (srcResources != null) {
            resourceDirs.add(srcResources);
        }
        builder.resourceDirs(resourceDirs);

        // Extract lib directories
        List<String> libDirs = new ArrayList<>();
        String lib = properties.get("lib");
        if (lib != null) {
            libDirs.add(lib);
        }
        builder.libDirs(libDirs);

        // Extract webapp directory
        String webapp = properties.get("webapp");
        if (webapp == null) {
            webapp = properties.get("webdir");
        }
        builder.webappDir(webapp);

        // Extract EAR config directory
        String earConf = properties.get("earConf");
        builder.earConfDir(earConf);

        // Extract classpath entries from compile.classpath path element
        List<String> classpathEntries = extractClasspathEntries(root);
        builder.classpathEntries(classpathEntries);

        // Extract excluded files
        List<String> excludedFiles = extractExcludedFiles(root);
        builder.excludedFiles(excludedFiles);

        return builder.build();
    }

    private List<String> extractClasspathEntries(Element root) {
        List<String> entries = new ArrayList<>();

        for (Element path : root.elements("path")) {
            String id = path.attributeValue("id");
            if (id != null && id.contains("classpath")) {
                for (Element fileset : path.elements("fileset")) {
                    String dir = fileset.attributeValue("dir");
                    if (dir != null) {
                        String includes = fileset.attributeValue("includes");
                        if (includes != null) {
                            entries.add(dir + "/" + includes);
                        } else {
                            entries.add(dir);
                        }
                    }
                }
            }
        }

        return entries;
    }

    private List<String> extractExcludedFiles(Element root) {
        Set<String> excluded = new LinkedHashSet<>();

        // Look for copy tasks with excludes
        for (Element target : root.elements("target")) {
            for (Element copy : target.elements("copy")) {
                for (Element fileset : copy.elements("fileset")) {
                    String excludes = fileset.attributeValue("excludes");
                    if (excludes != null) {
                        Arrays.stream(excludes.split(","))
                            .map(String::trim)
                            .filter(s -> !s.isEmpty())
                            .forEach(excluded::add);
                    }
                }
            }
        }

        // Look for war task excludes
        for (Element target : root.elements("target")) {
            for (Element war : target.elements("war")) {
                for (Element fileset : war.elements("fileset")) {
                    String excludes = fileset.attributeValue("excludes");
                    if (excludes != null) {
                        Arrays.stream(excludes.split(","))
                            .map(String::trim)
                            .filter(s -> !s.isEmpty())
                            .forEach(excluded::add);
                    }
                }
            }
        }

        return new ArrayList<>(excluded);
    }
}
