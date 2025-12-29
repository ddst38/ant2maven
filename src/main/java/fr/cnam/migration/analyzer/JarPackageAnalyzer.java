package fr.cnam.migration.analyzer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Collectors;

/**
 * Analyse les fichiers JAR pour extraire la structure des packages.
 * Utilisé pour inférer le groupId Maven à partir des packages réels.
 */
public class JarPackageAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(JarPackageAnalyzer.class);

    public record PackageAnalysis(
        String rootPackage,
        String inferredGroupId,
        List<String> allPackages,
        int classCount,
        AnalysisConfidence confidence
    ) {
        public static PackageAnalysis empty() {
            return new PackageAnalysis(null, null, List.of(), 0, AnalysisConfidence.NONE);
        }
    }

    public enum AnalysisConfidence {
        HIGH, MEDIUM, LOW, NONE
    }

    /**
     * Analyse un JAR et extrait les informations de package.
     */
    public PackageAnalysis analyze(Path jarPath) {
        if (jarPath == null || !jarPath.toFile().exists()) {
            return PackageAnalysis.empty();
        }

        List<String> packages = new ArrayList<>();
        int classCount = 0;

        try (JarFile jarFile = new JarFile(jarPath.toFile())) {
            Enumeration<JarEntry> entries = jarFile.entries();

            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String name = entry.getName();

                if (name.endsWith(".class") &&
                    !name.startsWith("META-INF/") &&
                    !name.equals("module-info.class")) {

                    classCount++;
                    String packageName = extractPackageName(name);
                    if (packageName != null && !packageName.isEmpty()) {
                        packages.add(packageName);
                    }
                }
            }
        } catch (IOException e) {
            log.debug("Failed to analyze JAR {}: {}", jarPath, e.getMessage());
            return PackageAnalysis.empty();
        }

        if (packages.isEmpty()) {
            return new PackageAnalysis(null, null, List.of(), classCount, AnalysisConfidence.NONE);
        }

        return analyzePackages(packages, classCount);
    }

    private String extractPackageName(String classFilePath) {
        String path = classFilePath.substring(0, classFilePath.length() - 6);
        int lastSlash = path.lastIndexOf('/');
        if (lastSlash <= 0) {
            return "";
        }
        return path.substring(0, lastSlash).replace('/', '.');
    }

    private PackageAnalysis analyzePackages(List<String> packages, int classCount) {
        Map<String, Long> packageCounts = packages.stream()
            .collect(Collectors.groupingBy(p -> p, Collectors.counting()));

        Map<String, Long> rootPackageCounts = findRootPackages(packages);

        String dominantRoot = rootPackageCounts.entrySet().stream()
            .max(Map.Entry.comparingByValue())
            .map(Map.Entry::getKey)
            .orElse(null);

        if (dominantRoot == null) {
            return new PackageAnalysis(null, null, new ArrayList<>(packageCounts.keySet()),
                classCount, AnalysisConfidence.NONE);
        }

        long dominantCount = rootPackageCounts.getOrDefault(dominantRoot, 0L);
        long totalCount = packages.size();
        double dominanceRatio = (double) dominantCount / totalCount;

        AnalysisConfidence confidence;
        if (rootPackageCounts.size() == 1 || dominanceRatio >= 0.9) {
            confidence = AnalysisConfidence.HIGH;
        } else if (dominanceRatio >= 0.7) {
            confidence = AnalysisConfidence.MEDIUM;
        } else {
            confidence = AnalysisConfidence.LOW;
        }

        return new PackageAnalysis(
            dominantRoot,
            dominantRoot,
            new ArrayList<>(packageCounts.keySet()),
            classCount,
            confidence
        );
    }

    private Map<String, Long> findRootPackages(List<String> packages) {
        Map<String, Long> rootCounts = new HashMap<>();

        for (String pkg : packages) {
            String root = findRootPackage(pkg);
            rootCounts.merge(root, 1L, Long::sum);
        }

        return rootCounts;
    }

    private String findRootPackage(String packageName) {
        String[] parts = packageName.split("\\.");

        if (parts.length <= 2) {
            return packageName;
        }

        // Tous les domaines utilisent 3 niveaux de profondeur pour le groupId
        // Exemples: org.apache.struts, com.cnamts.rfe, fr.cnamts.securite
        if (parts.length >= 3) {
            return parts[0] + "." + parts[1] + "." + parts[2];
        }

        int depth = Math.min(3, parts.length);
        return String.join(".", Arrays.copyOfRange(parts, 0, depth));
    }

    /**
     * Vérifie si un package est un package interne CNAM.
     */
    public boolean isInternalPackage(String packageName) {
        if (packageName == null) return false;
        return packageName.startsWith("fr.cnamts") ||
               packageName.startsWith("fr.cnam.");
    }
}
