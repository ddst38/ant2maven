package fr.cnam.migration.scanner;

import fr.cnam.migration.model.JarInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HexFormat;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Scanne les fichiers JAR et EAR dans un répertoire de projet.
 *
 * Les fichiers EAR (Enterprise Archive) peuvent contenir des bibliothèques JAR,
 * notamment dans APP-INF/lib/. Ces JARs sont extraits et inclus dans l'analyse.
 * C'est particulièrement important pour les frameworks WebLogic (modules _W).
 *
 * Note: Le fichier empty.jar est ignoré car c'est un fichier placeholder sans contenu utile.
 */
public class JarScanner {

    private static final Logger log = LoggerFactory.getLogger(JarScanner.class);

    /**
     * Liste des JARs à ignorer complètement lors du scan.
     * Ces fichiers ne sont ni analysés, ni copiés, ni déclarés dans le pom.xml.
     */
    private static final List<String> IGNORED_JARS = List.of(
        "empty.jar"  // Fichier placeholder sans contenu utile
    );

    /**
     * Vérifie si un JAR doit être ignoré.
     */
    private boolean shouldIgnore(String jarName) {
        return IGNORED_JARS.contains(jarName.toLowerCase());
    }

    /**
     * Trouve tous les fichiers JAR dans le répertoire donné et ses sous-répertoires.
     */
    public List<JarInfo> findAllJars(Path directory) throws IOException {
        List<JarInfo> jars = new ArrayList<>();

        Files.walkFileTree(directory, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                String fileName = file.getFileName().toString();
                if (fileName.toLowerCase().endsWith(".jar")) {
                    // Ignorer les JARs de la liste d'exclusion
                    if (shouldIgnore(fileName)) {
                        log.debug("JAR ignoré : {}", fileName);
                        return FileVisitResult.CONTINUE;
                    }
                    try {
                        String sha1 = computeSha1(file);
                        jars.add(JarInfo.of(file, attrs.size(), sha1));
                    } catch (Exception e) {
                        log.warn("Failed to compute SHA1 for {}: {}", file, e.getMessage());
                        jars.add(JarInfo.of(file, attrs.size(), null));
                    }
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                String dirName = dir.getFileName().toString();
                // Ignorer les répertoires CVS, .git, target, build
                if (dirName.equals("CVS") || dirName.equals(".git") ||
                    dirName.equals("target") || dirName.equals("build")) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                log.warn("Failed to access {}: {}", file, exc.getMessage());
                return FileVisitResult.CONTINUE;
            }
        });

        log.info("Found {} JAR files in {}", jars.size(), directory);
        return jars;
    }

    /**
     * Trouve tous les fichiers EAR dans le répertoire donné et ses sous-répertoires.
     */
    public List<Path> findAllEars(Path directory) throws IOException {
        List<Path> ears = new ArrayList<>();

        Files.walkFileTree(directory, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (file.getFileName().toString().toLowerCase().endsWith(".ear")) {
                    ears.add(file);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                String dirName = dir.getFileName().toString();
                if (dirName.equals("CVS") || dirName.equals(".git") ||
                    dirName.equals("target") || dirName.equals("build")) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }
        });

        log.info("Found {} EAR files in {}", ears.size(), directory);
        return ears;
    }

    /**
     * Calcule le checksum SHA1 d'un fichier.
     */
    public String computeSha1(Path file) throws IOException, NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-1");
        try (InputStream is = Files.newInputStream(file)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = is.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /**
     * Catégorise les JARs en fonction de leur emplacement dans le projet.
     *
     * Priorité de catégorisation :
     * 1. APP-INF/lib ou WEB-INF/lib → MAIN (dépendances runtime d'entreprise)
     * 2. Module test (nom contenant "test") → TEST
     * 3. src/test/ ou lib/test/ → TEST (tests unitaires/intégration)
     * 4. provided/ → PROVIDED
     * 5. runtime/ → RUNTIME
     * 6. Défaut → MAIN
     */
    public JarInfo.JarCategory categorizeByPath(Path jarPath, Path projectRoot) {
        String pathStr = projectRoot.relativize(jarPath).toString().toLowerCase();

        // Priorité 1 : Les JARs dans APP-INF/lib ou WEB-INF/lib sont toujours MAIN
        // Ces répertoires contiennent les dépendances runtime de l'application
        if (pathStr.contains("app-inf/lib") || pathStr.contains("app-inf\\lib") ||
            pathStr.contains("web-inf/lib") || pathStr.contains("web-inf\\lib")) {
            return JarInfo.JarCategory.MAIN;
        }

        // Priorité 2 : Module de test (nom du répertoire parent contient "test")
        // Ex: MetierFANOtest/lib/junit-4.8.2.jar → TEST
        if (isInTestModule(pathStr)) {
            return JarInfo.JarCategory.TEST;
        }

        // Priorité 3 : Les répertoires de test explicites
        // src/test/ = code de test Maven/Gradle
        // lib/test/ = bibliothèques de test
        if (pathStr.contains("src/test/") || pathStr.contains("src\\test\\") ||
            pathStr.contains("/lib/test/") || pathStr.contains("\\lib\\test\\") ||
            pathStr.contains("/lib/test") || pathStr.contains("\\lib\\test")) {
            return JarInfo.JarCategory.TEST;
        }

        if (pathStr.contains("/provided/") || pathStr.contains("\\provided\\")) {
            return JarInfo.JarCategory.PROVIDED;
        }

        if (pathStr.contains("/runtime/") || pathStr.contains("\\runtime\\")) {
            return JarInfo.JarCategory.RUNTIME;
        }

        return JarInfo.JarCategory.MAIN;
    }

    /**
     * Vérifie si le JAR est dans un module de test.
     * Détecte les patterns: *test/lib/, *Test/lib/, *tests/lib/
     */
    private boolean isInTestModule(String pathStr) {
        // Patterns pour détecter un module de test
        // Le chemin doit avoir un segment contenant "test" suivi de /lib/
        String normalized = pathStr.replace("\\", "/");
        String[] segments = normalized.split("/");

        for (int i = 0; i < segments.length - 1; i++) {
            String segment = segments[i].toLowerCase();
            // Le segment contient "test" (ex: MetierFANOtest, fano-test, tests)
            // et le segment suivant est "lib"
            if (segment.contains("test") && i + 1 < segments.length) {
                if (segments[i + 1].equals("lib")) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Extrait les fichiers JAR contenus dans un fichier EAR.
     *
     * Les fichiers EAR (Enterprise Archive) peuvent contenir des bibliothèques
     * dans différents emplacements :
     * - APP-INF/lib/ : bibliothèques partagées de l'application
     * - lib/ : bibliothèques au niveau racine
     * - Autres emplacements selon la configuration
     *
     * Cette méthode est particulièrement importante pour les frameworks WebLogic
     * (modules finissant par _W) qui contiennent souvent des dépendances.
     *
     * @param earPath Chemin vers le fichier EAR
     * @param extractDir Répertoire où extraire les JARs
     * @return Liste des JarInfo extraits
     */
    public List<JarInfo> extractJarsFromEar(Path earPath, Path extractDir) throws IOException {
        List<JarInfo> extractedJars = new ArrayList<>();
        String earName = earPath.getFileName().toString();

        log.info("Extraction des JARs depuis l'archive EAR : {}", earName);

        try (ZipFile zipFile = new ZipFile(earPath.toFile())) {
            Enumeration<? extends ZipEntry> entries = zipFile.entries();

            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String entryName = entry.getName();

                // Chercher les fichiers JAR dans l'EAR
                if (!entry.isDirectory() && entryName.toLowerCase().endsWith(".jar")) {
                    // Ignorer les JARs de la liste d'exclusion
                    String jarFileName = Path.of(entryName).getFileName().toString();
                    if (shouldIgnore(jarFileName)) {
                        log.debug("JAR ignoré dans EAR : {}", jarFileName);
                        continue;
                    }

                    // Extraire le JAR
                    Path targetPath = extractDir.resolve(Path.of(entryName).getFileName());

                    // Éviter les doublons (même nom de fichier)
                    if (Files.exists(targetPath)) {
                        // Ajouter le chemin dans l'EAR pour différencier
                        String uniqueName = entryName.replace("/", "_").replace("\\", "_");
                        targetPath = extractDir.resolve(uniqueName);
                    }

                    try (InputStream is = zipFile.getInputStream(entry)) {
                        Files.createDirectories(targetPath.getParent());
                        Files.copy(is, targetPath, StandardCopyOption.REPLACE_EXISTING);

                        // Calculer le SHA1 et créer le JarInfo
                        String sha1 = computeSha1(targetPath);
                        long size = Files.size(targetPath);

                        JarInfo jarInfo = JarInfo.builder()
                            .path(targetPath)
                            .name(targetPath.getFileName().toString())
                            .size(size)
                            .sha1(sha1)
                            .category(JarInfo.JarCategory.MAIN)
                            .sourceEar(earName)
                            .build();

                        extractedJars.add(jarInfo);

                        log.debug("Extrait de {} : {} ({})", earName, entryName,
                            formatSize(size));

                    } catch (Exception e) {
                        log.warn("Échec de l'extraction de {} depuis {} : {}",
                            entryName, earName, e.getMessage());
                    }
                }
            }
        }

        if (!extractedJars.isEmpty()) {
            log.info("Extrait {} JARs depuis {}", extractedJars.size(), earName);
        }

        return extractedJars;
    }

    /**
     * Formate une taille de fichier en format lisible.
     */
    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
    }
}
