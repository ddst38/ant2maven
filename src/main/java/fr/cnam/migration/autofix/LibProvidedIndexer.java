package fr.cnam.migration.autofix;

import fr.cnam.migration.analyzer.JarPackageAnalyzer;
import fr.cnam.migration.autofix.model.ProvidedDependency;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Indexe les JARs du repertoire lib-provided pour permettre
 * la resolution des classes et packages manquants.
 */
public class LibProvidedIndexer {

    private static final Logger log = LoggerFactory.getLogger(LibProvidedIndexer.class);

    // Index: nom de classe complet -> chemin du JAR
    private final Map<String, Path> classToJar = new HashMap<>();

    // Index: package -> liste des JARs contenant ce package
    private final Map<String, List<Path>> packageToJars = new HashMap<>();

    // Cache des infos de version extraites des JARs
    private final Map<Path, JarMetadata> jarMetadata = new HashMap<>();

    private final JarPackageAnalyzer packageAnalyzer = new JarPackageAnalyzer();

    // Pattern pour extraire la version du nom de fichier
    private static final Pattern VERSION_PATTERN = Pattern.compile(
        "^(.+?)[-_](\\d+\\.\\d+(?:\\.\\d+)?(?:[.-][A-Za-z0-9]+)?)\\.jar$"
    );

    /**
     * Indexe tous les JARs d'un repertoire (recursif).
     */
    public void index(Path libProvidedDir) throws IOException {
        if (!Files.exists(libProvidedDir)) {
            log.warn("Repertoire lib-provided inexistant : {}", libProvidedDir);
            return;
        }

        log.info("Indexation du repertoire lib-provided : {}", libProvidedDir);

        try (Stream<Path> paths = Files.walk(libProvidedDir)) {
            paths.filter(p -> p.toString().toLowerCase().endsWith(".jar"))
                 .filter(Files::isRegularFile)
                 .forEach(this::indexJar);
        }

        log.info("Indexation terminee : {} classes, {} packages, {} JARs",
            classToJar.size(), packageToJars.size(), jarMetadata.size());
    }

    /**
     * Indexe un JAR unique.
     */
    private void indexJar(Path jarPath) {
        try (JarFile jarFile = new JarFile(jarPath.toFile())) {
            // Extraire les metadonnees
            JarMetadata metadata = extractMetadata(jarPath, jarFile);
            jarMetadata.put(jarPath, metadata);

            // Indexer les classes
            Enumeration<JarEntry> entries = jarFile.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String name = entry.getName();

                if (name.endsWith(".class") && !name.startsWith("META-INF/") && !name.equals("module-info.class")) {
                    // Convertir chemin en nom de classe: com/example/Foo.class -> com.example.Foo
                    String className = name.substring(0, name.length() - 6).replace('/', '.');
                    classToJar.put(className, jarPath);

                    // Extraire et indexer le package
                    int lastDot = className.lastIndexOf('.');
                    if (lastDot > 0) {
                        String packageName = className.substring(0, lastDot);
                        packageToJars.computeIfAbsent(packageName, k -> new ArrayList<>());
                        if (!packageToJars.get(packageName).contains(jarPath)) {
                            packageToJars.get(packageName).add(jarPath);
                        }
                    }
                }
            }

            log.debug("JAR indexe : {} ({} classes)", jarPath.getFileName(),
                classToJar.entrySet().stream().filter(e -> e.getValue().equals(jarPath)).count());

        } catch (IOException e) {
            log.warn("Impossible d'indexer le JAR : {} - {}", jarPath, e.getMessage());
        }
    }

    /**
     * Extrait les metadonnees d'un JAR (groupId, version).
     */
    private JarMetadata extractMetadata(Path jarPath, JarFile jarFile) {
        String fileName = jarPath.getFileName().toString();
        String artifactId = fileName.substring(0, fileName.length() - 4);
        String version = "provided";
        String groupId = null;

        // Tenter d'extraire la version du nom de fichier
        Matcher matcher = VERSION_PATTERN.matcher(fileName);
        if (matcher.matches()) {
            artifactId = matcher.group(1);
            version = matcher.group(2);
        }

        // Tenter d'extraire depuis le manifest
        try {
            Manifest manifest = jarFile.getManifest();
            if (manifest != null) {
                var attrs = manifest.getMainAttributes();
                if (attrs.getValue("Implementation-Version") != null) {
                    version = attrs.getValue("Implementation-Version");
                } else if (attrs.getValue("Bundle-Version") != null) {
                    version = attrs.getValue("Bundle-Version");
                }
                if (attrs.getValue("Implementation-Vendor-Id") != null) {
                    groupId = attrs.getValue("Implementation-Vendor-Id");
                }
            }
        } catch (IOException ignored) {
        }

        // Si pas de groupId, analyser les packages
        if (groupId == null) {
            JarPackageAnalyzer.PackageAnalysis analysis = packageAnalyzer.analyze(jarPath);
            groupId = analysis.inferredGroupId();
            if (groupId == null) {
                groupId = "provided";
            }
        }

        return new JarMetadata(artifactId, version, groupId);
    }

    /**
     * Trouve le JAR contenant une classe donnee.
     */
    public Optional<Path> findJarForClass(String className) {
        return Optional.ofNullable(classToJar.get(className));
    }

    /**
     * Trouve les JARs contenant un package donne.
     */
    public List<Path> findJarsForPackage(String packageName) {
        return packageToJars.getOrDefault(packageName, List.of());
    }

    /**
     * Trouve le JAR le plus probable pour un package (celui avec le plus de classes).
     */
    public Optional<Path> findBestJarForPackage(String packageName) {
        List<Path> jars = findJarsForPackage(packageName);
        if (jars.isEmpty()) {
            // Essayer avec les packages parents
            int lastDot = packageName.lastIndexOf('.');
            while (lastDot > 0) {
                packageName = packageName.substring(0, lastDot);
                jars = findJarsForPackage(packageName);
                if (!jars.isEmpty()) break;
                lastDot = packageName.lastIndexOf('.');
            }
        }
        // Retourner le premier (le plus probable)
        return jars.isEmpty() ? Optional.empty() : Optional.of(jars.get(0));
    }

    /**
     * Cree une dependance provided a partir d'un chemin JAR.
     */
    public ProvidedDependency createDependency(Path jarPath) {
        JarMetadata metadata = jarMetadata.get(jarPath);
        if (metadata == null) {
            String fileName = jarPath.getFileName().toString();
            String artifactId = fileName.substring(0, fileName.length() - 4);
            return new ProvidedDependency("provided", artifactId, "provided", jarPath);
        }
        return new ProvidedDependency(metadata.groupId, metadata.artifactId, metadata.version, jarPath);
    }

    /**
     * Retourne le nombre de classes indexees.
     */
    public int getClassCount() {
        return classToJar.size();
    }

    /**
     * Retourne le nombre de JARs indexes.
     */
    public int getJarCount() {
        return jarMetadata.size();
    }

    /**
     * Metadonnees extraites d'un JAR.
     */
    private record JarMetadata(String artifactId, String version, String groupId) {}
}
