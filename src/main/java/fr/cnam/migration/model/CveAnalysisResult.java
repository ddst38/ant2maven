package fr.cnam.migration.model;

import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Résultat agrégé de l'analyse CVE pour un projet.
 */
public record CveAnalysisResult(
    List<CveInfo> vulnerabilities,
    int criticalCount,
    int highCount,
    int mediumCount,
    int lowCount,
    int totalCount,
    CveSeverity maxSeverity,
    Map<String, List<CveInfo>> byLibrary,   // GAV -> CVEs
    Map<String, Integer> byCwe,              // CWE-ID -> count
    boolean analysisPerformed
) {
    /**
     * Crée un résultat vide (analyse non effectuée).
     */
    public static CveAnalysisResult empty() {
        return new CveAnalysisResult(
            Collections.emptyList(),
            0, 0, 0, 0, 0,
            CveSeverity.NONE,
            Collections.emptyMap(),
            Collections.emptyMap(),
            false
        );
    }

    /**
     * Construit un CveAnalysisResult à partir d'une liste de vulnérabilités.
     */
    public static CveAnalysisResult from(List<CveInfo> vulnerabilities) {
        if (vulnerabilities == null || vulnerabilities.isEmpty()) {
            return new CveAnalysisResult(
                Collections.emptyList(),
                0, 0, 0, 0, 0,
                CveSeverity.NONE,
                Collections.emptyMap(),
                Collections.emptyMap(),
                true
            );
        }

        int critical = 0, high = 0, medium = 0, low = 0;
        CveSeverity max = CveSeverity.NONE;

        for (CveInfo cve : vulnerabilities) {
            switch (cve.severity()) {
                case CRITICAL -> { critical++; if (max.ordinal() > CveSeverity.CRITICAL.ordinal()) max = CveSeverity.CRITICAL; }
                case HIGH -> { high++; if (max.ordinal() > CveSeverity.HIGH.ordinal()) max = CveSeverity.HIGH; }
                case MEDIUM -> { medium++; if (max.ordinal() > CveSeverity.MEDIUM.ordinal()) max = CveSeverity.MEDIUM; }
                case LOW -> { low++; if (max.ordinal() > CveSeverity.LOW.ordinal()) max = CveSeverity.LOW; }
                default -> {}
            }
        }

        Map<String, List<CveInfo>> byLib = vulnerabilities.stream()
            .collect(Collectors.groupingBy(CveInfo::gav));

        Map<String, Integer> byCwe = vulnerabilities.stream()
            .filter(c -> c.cweId() != null && !c.cweId().isBlank())
            .collect(Collectors.groupingBy(CveInfo::cweId, Collectors.summingInt(c -> 1)));

        return new CveAnalysisResult(
            vulnerabilities,
            critical, high, medium, low,
            vulnerabilities.size(),
            max,
            byLib,
            byCwe,
            true
        );
    }

    /**
     * Retourne les N librairies avec le plus de vulnérabilités.
     */
    public List<Map.Entry<String, List<CveInfo>>> topVulnerableLibraries(int n) {
        return byLibrary.entrySet().stream()
            .sorted((a, b) -> {
                // Trier par sévérité max puis par nombre
                CveSeverity maxA = a.getValue().stream()
                    .map(CveInfo::severity)
                    .min(Comparator.comparingInt(Enum::ordinal))
                    .orElse(CveSeverity.NONE);
                CveSeverity maxB = b.getValue().stream()
                    .map(CveInfo::severity)
                    .min(Comparator.comparingInt(Enum::ordinal))
                    .orElse(CveSeverity.NONE);

                int cmp = Integer.compare(maxA.ordinal(), maxB.ordinal());
                if (cmp != 0) return cmp;
                return Integer.compare(b.getValue().size(), a.getValue().size());
            })
            .limit(n)
            .toList();
    }

    /**
     * Calcule un score de risque global (somme pondérée).
     * Critical=10, High=5, Medium=2, Low=1
     */
    public int riskScore() {
        return criticalCount * 10 + highCount * 5 + mediumCount * 2 + lowCount;
    }

    /**
     * Vérifie si le projet a des vulnérabilités critiques.
     */
    public boolean hasCriticalVulnerabilities() {
        return criticalCount > 0;
    }

    /**
     * Vérifie si le projet a des vulnérabilités (toutes sévérités).
     */
    public boolean hasVulnerabilities() {
        return totalCount > 0;
    }
}
