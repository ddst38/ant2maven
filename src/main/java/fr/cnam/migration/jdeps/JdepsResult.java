package fr.cnam.migration.jdeps;

import java.util.List;
import java.util.Map;

/**
 * Resultat complet de l'analyse jdeps.
 */
public record JdepsResult(
    boolean analysisPerformed,
    List<PackageDependency> packageDependencies,
    List<JdkInternalUsage> jdkInternalUsages,
    List<CycleDependency> cycles,
    Map<String, PackageMetrics> packageMetrics,
    int totalPackages,
    int totalDependencies,
    int cycleCount,
    int jdkInternalCount,
    double avgInstability
) {
    /**
     * Retourne un resultat vide (analyse non effectuee).
     */
    public static JdepsResult empty() {
        return new JdepsResult(
            false,
            List.of(),
            List.of(),
            List.of(),
            Map.of(),
            0, 0, 0, 0, 0.0
        );
    }

    /**
     * Retourne un resultat avec erreur (analyse echouee).
     */
    public static JdepsResult failed() {
        return new JdepsResult(
            true,
            List.of(),
            List.of(),
            List.of(),
            Map.of(),
            0, 0, 0, 0, 0.0
        );
    }
}
