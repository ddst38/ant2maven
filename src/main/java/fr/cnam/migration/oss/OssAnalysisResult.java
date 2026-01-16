package fr.cnam.migration.oss;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Resultat complet de l'analyse OSS Index.
 * Focus sur la viabilite long terme des dependances.
 */
public record OssAnalysisResult(
    boolean analysisPerformed,
    LocalDateTime analysisDate,
    int totalDependencies,
    double overallHealthScore,
    Map<String, Integer> healthDistribution,
    List<OssDependencyHealth> dependencies,
    List<OssDependencyHealth> criticalAlerts,
    int outdatedCount,
    int vulnerableCount,
    int staleCount
) {
    /**
     * Resultat vide quand l'analyse n'est pas activee.
     */
    public static OssAnalysisResult empty() {
        return new OssAnalysisResult(
            false,
            null,
            0,
            0.0,
            Map.of(),
            List.of(),
            List.of(),
            0, 0, 0
        );
    }

    /**
     * Resultat en cas d'echec de l'analyse.
     */
    public static OssAnalysisResult failed() {
        return new OssAnalysisResult(
            true,
            LocalDateTime.now(),
            0,
            0.0,
            Map.of("UNKNOWN", 0),
            List.of(),
            List.of(),
            0, 0, 0
        );
    }

    /**
     * Construit le resultat a partir de la liste des dependances analysees.
     */
    public static OssAnalysisResult from(List<OssDependencyHealth> dependencies) {
        if (dependencies == null || dependencies.isEmpty()) {
            return new OssAnalysisResult(
                true,
                LocalDateTime.now(),
                0,
                10.0,
                Map.of("HEALTHY", 0, "MONITOR", 0, "RISKY", 0, "CRITICAL", 0),
                List.of(),
                List.of(),
                0, 0, 0
            );
        }

        // Calcul du score global (moyenne ponderee)
        double overallScore = dependencies.stream()
            .mapToDouble(OssDependencyHealth::healthScore)
            .average()
            .orElse(10.0);

        // Distribution par statut
        Map<String, Integer> distribution = dependencies.stream()
            .collect(Collectors.groupingBy(
                OssDependencyHealth::healthStatus,
                Collectors.collectingAndThen(Collectors.counting(), Long::intValue)
            ));
        // S'assurer que toutes les categories sont presentes
        distribution.putIfAbsent("HEALTHY", 0);
        distribution.putIfAbsent("MONITOR", 0);
        distribution.putIfAbsent("RISKY", 0);
        distribution.putIfAbsent("CRITICAL", 0);

        // Alertes critiques (score < 4 ou vulnerabilites critiques)
        List<OssDependencyHealth> alerts = dependencies.stream()
            .filter(d -> d.healthScore() < 4 || d.criticalVulns() > 0)
            .sorted((a, b) -> Double.compare(a.healthScore(), b.healthScore()))
            .limit(20)
            .toList();

        // Compteurs
        int outdated = (int) dependencies.stream()
            .filter(d -> d.majorVersionsBehind() > 0 || d.minorVersionsBehind() >= 3)
            .count();

        int vulnerable = (int) dependencies.stream()
            .filter(d -> d.totalVulnerabilities() > 0)
            .count();

        int stale = (int) dependencies.stream()
            .filter(d -> d.releaseAgeMonths() > 24)
            .count();

        return new OssAnalysisResult(
            true,
            LocalDateTime.now(),
            dependencies.size(),
            Math.round(overallScore * 10.0) / 10.0,
            distribution,
            dependencies,
            alerts,
            outdated,
            vulnerable,
            stale
        );
    }

    /**
     * Retourne le statut global de sante.
     */
    public String overallStatus() {
        if (overallHealthScore >= 8) return "HEALTHY";
        if (overallHealthScore >= 6) return "MONITOR";
        if (overallHealthScore >= 4) return "RISKY";
        return "CRITICAL";
    }

    /**
     * Pourcentage de dependances saines.
     */
    public double healthyPercentage() {
        if (totalDependencies == 0) return 100.0;
        int healthy = healthDistribution.getOrDefault("HEALTHY", 0);
        return Math.round((healthy * 100.0 / totalDependencies) * 10.0) / 10.0;
    }

    /**
     * Indique s'il y a des alertes critiques.
     */
    public boolean hasCriticalAlerts() {
        return !criticalAlerts.isEmpty();
    }
}
