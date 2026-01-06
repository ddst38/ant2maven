package fr.cnam.migration.scanner;

import fr.cnam.migration.model.AntBuildInfo;
import fr.cnam.migration.model.ModuleInfo;
import org.dom4j.Document;
import org.dom4j.DocumentException;
import org.dom4j.Element;
import org.dom4j.io.SAXReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parse les fichiers Ant build.xml pour extraire les informations de structure du projet.
 */
public class AntBuildParser {

    private static final Logger log = LoggerFactory.getLogger(AntBuildParser.class);

    /**
     * Parse tous les fichiers build.xml dans les répertoires install/ et build/.
     */
    public List<AntBuildInfo> parseAll(Path projectRoot) {
        List<AntBuildInfo> builds = new ArrayList<>();

        // Essayer le répertoire install/ (projets WAR/EAR classiques)
        Path installDir = projectRoot.resolve("install");
        if (Files.exists(installDir)) {
            // Parser le build.xml principal
            Path mainBuild = installDir.resolve("build.xml");
            if (Files.exists(mainBuild)) {
                try {
                    builds.add(parse(mainBuild, false));
                } catch (Exception e) {
                    log.error("Failed to parse {}: {}", mainBuild, e.getMessage());
                }
            }

            // Parser build.pic.xml s'il existe
            Path picBuild = installDir.resolve("build.pic.xml");
            if (Files.exists(picBuild)) {
                try {
                    builds.add(parse(picBuild, true));
                } catch (Exception e) {
                    log.error("Failed to parse {}: {}", picBuild, e.getMessage());
                }
            }
        }

        // Essayer le répertoire build/ (projets batch)
        Path buildDir = projectRoot.resolve("build");
        if (Files.exists(buildDir.resolve("build.xml"))) {
            try {
                builds.add(parseBatchBuild(buildDir.resolve("build.xml")));
            } catch (Exception e) {
                log.error("Failed to parse batch build.xml: {}", e.getMessage());
            }
        }

        if (builds.isEmpty()) {
            log.warn("No build.xml found in install/ or build/ directories");
        }

        return builds;
    }

    /**
     * Parse un build.xml de projet batch (dans le répertoire build/).
     * Charge également build.properties et extrait la Main-Class du manifest.
     */
    public AntBuildInfo parseBatchBuild(Path buildFile) throws DocumentException {
        log.info("Parsing batch build file: {}", buildFile);

        SAXReader reader = new SAXReader();
        Document document = reader.read(buildFile.toFile());
        Element root = document.getRootElement();

        // Charger build.properties depuis le même répertoire
        Path propsFile = buildFile.getParent().resolve("build.properties");
        Map<String, String> properties = loadBuildProperties(propsFile);

        // Ajouter les properties du build.xml
        for (Element prop : root.elements("property")) {
            String name = prop.attributeValue("name");
            String value = prop.attributeValue("value");
            if (name != null && value != null) {
                properties.putIfAbsent(name, value);
            }
        }

        // Extraire le nom du projet
        String projectName = root.attributeValue("name");

        // Extraire la Main-Class depuis la target packageJar ou jar
        String mainClass = extractMainClass(root);
        if (mainClass != null) {
            properties.put("batch.mainClass", mainClass);
        }

        // Extraire les informations du manifest
        String specTitle = extractManifestAttribute(root, "Specification-Title");
        if (specTitle != null) {
            properties.put("batch.specificationTitle", specTitle);
        }

        // Marquer comme projet batch
        properties.put("batch.project", "true");

        AntBuildInfo.Builder builder = AntBuildInfo.builder()
            .buildFile(buildFile)
            .isPicBuild(false)
            .projectName(projectName)
            .properties(properties);

        // Extraire app.code depuis build.properties
        String appCode = properties.get("codeAppli");
        if (appCode == null) {
            appCode = properties.get("app.code");
        }
        builder.appCode(appCode);

        // Extraire les répertoires sources
        List<String> sourceDirs = new ArrayList<>();
        String srcPath = properties.get("sourcePath");
        if (srcPath != null) {
            sourceDirs.add(srcPath);
        }
        builder.sourceDirs(sourceDirs);

        // Extraire les répertoires de ressources
        List<String> resourceDirs = new ArrayList<>();
        String resPath = properties.get("resourcePath");
        if (resPath != null) {
            resourceDirs.add(resPath);
        }
        builder.resourceDirs(resourceDirs);

        // Extraire les répertoires de librairies
        List<String> libDirs = new ArrayList<>();
        String libPath = properties.get("libPath");
        if (libPath != null) {
            libDirs.add(libPath);
        }
        builder.libDirs(libDirs);

        return builder.build();
    }

    /**
     * Charge les propriétés depuis un fichier build.properties.
     */
    private Map<String, String> loadBuildProperties(Path propsFile) {
        Map<String, String> props = new LinkedHashMap<>();
        if (Files.exists(propsFile)) {
            try {
                java.util.Properties p = new java.util.Properties();
                try (var is = Files.newInputStream(propsFile)) {
                    p.load(is);
                }
                p.forEach((k, v) -> props.put(k.toString(), v.toString()));
                log.info("Loaded {} properties from {}", props.size(), propsFile.getFileName());
            } catch (Exception e) {
                log.warn("Failed to load build.properties: {}", e.getMessage());
            }
        }
        return props;
    }

    /**
     * Extrait la Main-Class depuis le manifest d'une target jar.
     */
    private String extractMainClass(Element root) {
        for (Element target : root.elements("target")) {
            Element jar = target.element("jar");
            if (jar != null) {
                Element manifest = jar.element("manifest");
                if (manifest != null) {
                    for (Element attr : manifest.elements("attribute")) {
                        if ("Main-Class".equals(attr.attributeValue("name"))) {
                            return attr.attributeValue("value");
                        }
                    }
                }
            }
        }
        return null;
    }

    /**
     * Extrait un attribut du manifest.
     */
    private String extractManifestAttribute(Element root, String attributeName) {
        for (Element target : root.elements("target")) {
            Element jar = target.element("jar");
            if (jar != null) {
                Element manifest = jar.element("manifest");
                if (manifest != null) {
                    for (Element attr : manifest.elements("attribute")) {
                        if (attributeName.equals(attr.attributeValue("name"))) {
                            return attr.attributeValue("value");
                        }
                    }
                }
            }
        }
        return null;
    }

    /**
     * Parse un seul fichier build.xml.
     */
    public AntBuildInfo parse(Path buildFile, boolean isPic) throws DocumentException {
        log.info("Parsing Ant build file: {}", buildFile);

        SAXReader reader = new SAXReader();
        Document document = reader.read(buildFile.toFile());
        Element root = document.getRootElement();

        AntBuildInfo.Builder builder = AntBuildInfo.builder()
            .buildFile(buildFile)
            .isPicBuild(isPic);

        // Extraire le nom du projet
        String projectName = root.attributeValue("name");
        builder.projectName(projectName);

        // Extraire la cible par défaut
        String defaultTarget = root.attributeValue("default");
        builder.defaultTarget(defaultTarget != null ? defaultTarget : "package");

        // Extraire les propriétés
        Map<String, String> properties = new LinkedHashMap<>();
        for (Element prop : root.elements("property")) {
            String name = prop.attributeValue("name");
            String value = prop.attributeValue("value");
            if (name != null && value != null) {
                properties.put(name, value);
            }
        }
        builder.properties(properties);

        // Extraire app.code
        String appCode = properties.get("app.code");
        builder.appCode(appCode);

        // Extraire le nom du WAR
        String warName = properties.get("app.code.war");
        if (warName == null && appCode != null) {
            warName = appCode + "_J";
        }
        builder.warName(warName != null ? warName + "-app.war" : null);

        // Extraire les répertoires sources
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

        // Extraire les répertoires de ressources
        List<String> resourceDirs = new ArrayList<>();
        String srcResources = properties.get("src.resources");
        if (srcResources != null) {
            resourceDirs.add(srcResources);
        }
        builder.resourceDirs(resourceDirs);

        // Extraire les répertoires de librairies
        List<String> libDirs = new ArrayList<>();
        String lib = properties.get("lib");
        if (lib != null) {
            libDirs.add(lib);
        }
        builder.libDirs(libDirs);

        // Extraire le répertoire webapp
        String webapp = properties.get("webapp");
        if (webapp == null) {
            webapp = properties.get("webdir");
        }
        builder.webappDir(webapp);

        // Extraire le répertoire de configuration EAR
        String earConf = properties.get("earConf");
        builder.earConfDir(earConf);

        // Extraire les entrées classpath depuis l'élément path compile.classpath
        List<String> classpathEntries = extractClasspathEntries(root);
        builder.classpathEntries(classpathEntries);

        // Extraire les fichiers exclus
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

        // Chercher les tâches copy avec des excludes
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

        // Chercher les excludes de la tâche war
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

    // ========== Parsing multi-module ==========

    private static final Pattern PROPERTY_REF = Pattern.compile("\\$\\{([^}]+)}");

    /**
     * Parse les targets JAR et WAR du build.xml pour detecter les modules.
     * Analyse les dependances entre targets pour calculer l'ordre de build.
     */
    public List<ModuleInfo> parseModules(Path projectRoot) {
        Path buildFile = projectRoot.resolve("install/build.xml");
        if (!Files.exists(buildFile)) {
            return List.of();
        }

        try {
            SAXReader reader = new SAXReader();
            Document document = reader.read(buildFile.toFile());
            Element root = document.getRootElement();

            // Extraire les proprietes pour resoudre les references
            Map<String, String> properties = extractProperties(root);
            String projectName = root.attributeValue("name");
            String codeAppli = properties.getOrDefault("CODE_APPLI",
                projectName != null ? projectName.replace("_J", "") : "APP");

            // Trouver les targets avec <jar> ou <war>
            Map<String, TargetInfo> targets = new LinkedHashMap<>();
            for (Element target : root.elements("target")) {
                String name = target.attributeValue("name");
                String depends = target.attributeValue("depends");

                // Chercher <jar> dans la target
                Element jarElement = target.element("jar");
                Element warElement = target.element("war");

                if (jarElement != null || warElement != null) {
                    ModuleInfo.ModuleType type = warElement != null ?
                        ModuleInfo.ModuleType.WAR : ModuleInfo.ModuleType.JAR;

                    // Trouver le srcdir depuis javac
                    Element javac = target.element("javac");
                    String srcdir = null;
                    if (javac != null) {
                        srcdir = javac.attributeValue("srcdir");
                    }

                    targets.put(name, new TargetInfo(name, type, depends, srcdir, properties));
                }
            }

            // Calculer l'ordre topologique
            List<String> orderedTargets = topologicalSort(targets);

            // Construire les ModuleInfo
            List<ModuleInfo> modules = new ArrayList<>();
            int order = 0;
            for (String targetName : orderedTargets) {
                TargetInfo info = targets.get(targetName);
                if (info == null) continue;

                ModuleInfo module = buildModuleInfo(info, projectRoot, properties, codeAppli, order++);
                if (module != null) {
                    modules.add(module);
                }
            }

            // Ajouter module EAR
            addEarModule(modules, projectRoot, codeAppli, order++);

            // Pour les modules TEST, ajouter tous les modules JAR comme dépendances
            List<String> jarModuleNames = modules.stream()
                .filter(m -> m.type() == ModuleInfo.ModuleType.JAR)
                .map(ModuleInfo::name)
                .toList();

            if (!jarModuleNames.isEmpty()) {
                modules = modules.stream()
                    .map(m -> {
                        if (m.type() == ModuleInfo.ModuleType.TEST) {
                            log.info("Module TEST {} : ajout des dépendances vers {} modules JAR",
                                m.name(), jarModuleNames.size());
                            return ModuleInfo.builder()
                                .name(m.name())
                                .artifactId(m.artifactId())
                                .type(m.type())
                                .sourceDir(m.sourceDir())
                                .resourceDir(m.resourceDir())
                                .webappDir(m.webappDir())
                                .dependsOn(jarModuleNames)
                                .antTargetName(m.antTargetName())
                                .buildOrder(m.buildOrder())
                                .build();
                        }
                        return m;
                    })
                    .toList();
            }

            log.info("Detected {} modules in multi-module project", modules.size());
            return new ArrayList<>(modules);

        } catch (Exception e) {
            log.error("Failed to parse modules from {}: {}", buildFile, e.getMessage());
            return List.of();
        }
    }

    /**
     * Verifie si le projet est multi-module (plusieurs targets JAR/WAR).
     */
    public boolean isMultiModuleProject(Path projectRoot) {
        Path buildFile = projectRoot.resolve("install/build.xml");
        if (!Files.exists(buildFile)) {
            return false;
        }

        try {
            SAXReader reader = new SAXReader();
            Document document = reader.read(buildFile.toFile());
            Element root = document.getRootElement();

            int jarWarCount = 0;
            for (Element target : root.elements("target")) {
                if (target.element("jar") != null || target.element("war") != null) {
                    jarWarCount++;
                }
            }
            // Multi-module si plus de 2 targets JAR/WAR (ex: client + metier + jms + war)
            return jarWarCount >= 3;
        } catch (Exception e) {
            return false;
        }
    }

    private Map<String, String> extractProperties(Element root) {
        Map<String, String> properties = new LinkedHashMap<>();
        for (Element prop : root.elements("property")) {
            String name = prop.attributeValue("name");
            String value = prop.attributeValue("value");
            if (name != null && value != null) {
                properties.put(name, value);
            }
        }
        return properties;
    }

    private String resolveProperty(String value, Map<String, String> properties) {
        if (value == null) return null;

        Matcher matcher = PROPERTY_REF.matcher(value);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            String propName = matcher.group(1);
            String propValue = properties.get(propName);
            if (propValue != null) {
                // Resolution recursive
                propValue = resolveProperty(propValue, properties);
                matcher.appendReplacement(result, Matcher.quoteReplacement(propValue));
            }
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private List<String> topologicalSort(Map<String, TargetInfo> targets) {
        List<String> result = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        Set<String> visiting = new HashSet<>();

        for (String name : targets.keySet()) {
            if (!visited.contains(name)) {
                topologicalVisit(name, targets, visited, visiting, result);
            }
        }
        return result;
    }

    private void topologicalVisit(String name, Map<String, TargetInfo> targets,
            Set<String> visited, Set<String> visiting, List<String> result) {
        if (visited.contains(name)) return;
        if (visiting.contains(name)) return; // Cycle

        visiting.add(name);
        TargetInfo info = targets.get(name);
        if (info != null && info.depends != null) {
            for (String dep : info.depends.split(",")) {
                String depName = dep.trim();
                if (targets.containsKey(depName)) {
                    topologicalVisit(depName, targets, visited, visiting, result);
                }
            }
        }
        visiting.remove(name);
        visited.add(name);
        result.add(name);
    }

    private ModuleInfo buildModuleInfo(TargetInfo target, Path projectRoot,
            Map<String, String> properties, String codeAppli, int order) {

        String srcdir = resolveProperty(target.srcdir, properties);
        if (srcdir == null) return null;

        // Resoudre le chemin source relatif a install/
        Path installDir = projectRoot.resolve("install");
        Path sourceDir = installDir.resolve(srcdir).normalize();

        // Extraire le nom du module depuis le chemin source
        // Ex: ../MetierFANOClient/src -> MetierFANOClient
        String moduleName = extractModuleName(srcdir);
        if (moduleName == null) return null;

        // Generer artifactId: MetierFANOClient -> fano-client
        String artifactId = generateArtifactId(moduleName, codeAppli);

        // Trouver le repertoire de ressources (conf/)
        Path resourceDir = findResourceDir(projectRoot, moduleName);

        // Trouver le repertoire webapp pour WAR
        Path webappDir = null;
        if (target.type == ModuleInfo.ModuleType.WAR) {
            webappDir = findWebappDir(projectRoot, moduleName);
        }

        // Extraire les dependances depuis "depends"
        List<String> dependsOn = new ArrayList<>();
        if (target.depends != null) {
            for (String dep : target.depends.split(",")) {
                String depName = dep.trim();
                // Convertir target name en module name
                // ex: jarMetierClient -> MetierFANOClient
                String depModuleName = targetToModuleName(depName, codeAppli);
                if (depModuleName != null) {
                    dependsOn.add(depModuleName);
                }
            }
        }

        // Détecter si c'est un module de test par son nom
        ModuleInfo.ModuleType moduleType = target.type;
        if (moduleName.toLowerCase().contains("test")) {
            moduleType = ModuleInfo.ModuleType.TEST;
            log.info("Module {} détecté comme TEST (nom contient 'test')", moduleName);
        }

        return ModuleInfo.builder()
            .name(moduleName)
            .artifactId(artifactId)
            .type(moduleType)
            .sourceDir(sourceDir)
            .resourceDir(resourceDir)
            .webappDir(webappDir)
            .dependsOn(dependsOn)
            .antTargetName(target.name)
            .buildOrder(order)
            .build();
    }

    private String extractModuleName(String srcdir) {
        // ../MetierFANOClient/src/ -> MetierFANOClient
        if (srcdir == null) return null;
        String[] parts = srcdir.replace("\\", "/").split("/");
        for (String part : parts) {
            if (part.startsWith("Metier") || part.startsWith("Socle")) {
                return part;
            }
        }
        // Fallback: prendre le premier repertoire significatif
        for (String part : parts) {
            if (!part.isEmpty() && !part.equals("..") && !part.equals("src")) {
                return part;
            }
        }
        return null;
    }

    private String generateArtifactId(String moduleName, String codeAppli) {
        // MetierFANOClient -> fano-client
        // MetierFANO -> fano-metier
        // MetierFANOjms -> fano-jms
        // MetierFANOws -> fano-web
        String lower = codeAppli.toLowerCase();
        String suffix = moduleName.toLowerCase()
            .replace("metier" + lower, "")
            .replace("socle" + lower, "");

        if (suffix.isEmpty()) {
            return lower + "-metier";
        } else if (suffix.equals("client")) {
            return lower + "-client";
        } else if (suffix.equals("jms")) {
            return lower + "-jms";
        } else if (suffix.equals("ws") || suffix.equals("rest")) {
            return lower + "-web";
        } else if (suffix.equals("ejb")) {
            return lower + "-ejb";
        } else {
            return lower + "-" + suffix;
        }
    }

    private Path findResourceDir(Path projectRoot, String moduleName) {
        Path confDir = projectRoot.resolve(moduleName).resolve("conf");
        if (Files.exists(confDir)) {
            return confDir;
        }
        return null;
    }

    private Path findWebappDir(Path projectRoot, String moduleName) {
        Path webContent = projectRoot.resolve(moduleName).resolve("WebContent");
        if (Files.exists(webContent)) {
            return webContent;
        }
        return null;
    }

    private String targetToModuleName(String targetName, String codeAppli) {
        // jarMetierClient -> MetierFANOClient (si codeAppli=FANO)
        // jarMetier -> MetierFANO
        // jarMetierJms -> MetierFANOjms
        // warMetierWs -> MetierFANOws
        if (targetName.startsWith("jar") || targetName.startsWith("war")) {
            String suffix = targetName.substring(3); // MetierClient, Metier, MetierJms, MetierWs
            if (suffix.startsWith("Metier")) {
                String rest = suffix.substring(6); // Client, "", Jms, Ws
                if (rest.isEmpty()) {
                    return "Metier" + codeAppli;
                } else if (rest.equalsIgnoreCase("Client")) {
                    return "Metier" + codeAppli + "Client";
                } else {
                    // Jms, Ws, Ejb, etc. en minuscules
                    return "Metier" + codeAppli + rest.toLowerCase();
                }
            }
        }
        return null;
    }

    private void addEarModule(List<ModuleInfo> modules, Path projectRoot, String codeAppli, int order) {
        // Chercher le repertoire *Ear
        Path earDir = null;
        try (var stream = Files.list(projectRoot)) {
            earDir = stream
                .filter(Files::isDirectory)
                .filter(p -> p.getFileName().toString().toLowerCase().endsWith("ear"))
                .findFirst()
                .orElse(null);
        } catch (Exception e) {
            // ignore
        }

        if (earDir != null) {
            String moduleName = earDir.getFileName().toString();
            modules.add(ModuleInfo.builder()
                .name(moduleName)
                .artifactId(codeAppli.toLowerCase() + "-ear")
                .type(ModuleInfo.ModuleType.EAR)
                .sourceDir(earDir)
                .buildOrder(order)
                .build());
        }
    }

    /**
     * Info temporaire pour une target ANT.
     */
    private record TargetInfo(
        String name,
        ModuleInfo.ModuleType type,
        String depends,
        String srcdir,
        Map<String, String> properties
    ) {}
}
