package fr.cnam.migration.model;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Résultat de l'analyse des dépendances.
 */
public record AnalysisResult(
    List<DependencyInfo> resolved,
    List<UnresolvedJar> unresolved
) {
    /**
     * Groupe les dépendances résolues par scope.
     */
    public Map<Scope, List<DependencyInfo>> byScope() {
        return resolved.stream()
            .collect(Collectors.groupingBy(DependencyInfo::scope));
    }

    /**
     * Groupe les dépendances résolues par méthode de résolution.
     */
    public Map<ResolutionMethod, List<DependencyInfo>> byMethod() {
        return resolved.stream()
            .collect(Collectors.groupingBy(DependencyInfo::method));
    }

    /**
     * Retourne toutes les dépendances internes (propriétaires).
     */
    public List<DependencyInfo> internalDependencies() {
        return resolved.stream()
            .filter(DependencyInfo::isInternal)
            .toList();
    }

    /**
     * Retourne toutes les dépendances externes (Maven Central).
     */
    public List<DependencyInfo> externalDependencies() {
        return resolved.stream()
            .filter(d -> !d.isInternal())
            .toList();
    }

    /**
     * Taux de succès de résolution en pourcentage.
     */
    public double successRate() {
        int total = resolved.size() + unresolved.size();
        return total == 0 ? 100.0 : (resolved.size() * 100.0) / total;
    }

    /**
     * Un JAR non résolu avec les méthodes de résolution tentées.
     */
    public record UnresolvedJar(
        JarInfo jar,
        List<ResolutionAttempt> attempts
    ) {}

    /**
     * Une tentative de résolution unique.
     */
    public record ResolutionAttempt(
        ResolutionMethod method,
        boolean success,
        String message,
        MavenCoordinate coordinate
    ) {
        public static ResolutionAttempt success(ResolutionMethod method, MavenCoordinate coordinate) {
            return new ResolutionAttempt(method, true, "Trouvé", coordinate);
        }

        public static ResolutionAttempt failed(ResolutionMethod method, String reason) {
            return new ResolutionAttempt(method, false, reason, null);
        }
    }
}
