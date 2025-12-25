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
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * Scans for JAR and EAR files in a project directory.
 */
public class JarScanner {

    private static final Logger log = LoggerFactory.getLogger(JarScanner.class);

    /**
     * Finds all JAR files in the given directory and subdirectories.
     */
    public List<JarInfo> findAllJars(Path directory) throws IOException {
        List<JarInfo> jars = new ArrayList<>();

        Files.walkFileTree(directory, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                String fileName = file.getFileName().toString().toLowerCase();
                if (fileName.endsWith(".jar")) {
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
                // Skip CVS, .git, target, build directories
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
     * Finds all EAR files in the given directory and subdirectories.
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
     * Computes SHA1 checksum for a file.
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
     * Categorizes JARs based on their location in the project.
     */
    public JarInfo.JarCategory categorizeByPath(Path jarPath, Path projectRoot) {
        String pathStr = projectRoot.relativize(jarPath).toString().toLowerCase();

        if (pathStr.contains("/test/") || pathStr.contains("\\test\\") ||
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
}
