package fr.cnam.migration.jdeps;

import java.util.List;

/**
 * Metriques de couplage pour un package.
 */
public record PackageMetrics(
    String packageName,
    int afferentCoupling,    // Ca : nombre de packages qui dependent de celui-ci
    int efferentCoupling,    // Ce : nombre de packages dont celui-ci depend
    double instability,      // I = Ce / (Ca + Ce), 0=stable, 1=instable
    List<String> dependsOn,  // Packages dont ce package depend
    List<String> usedBy      // Packages qui utilisent ce package
) {
    /**
     * Calcule l'instabilite a partir de Ca et Ce.
     */
    public static double calculateInstability(int ca, int ce) {
        if (ca + ce == 0) return 0.0;
        return (double) ce / (ca + ce);
    }
}
