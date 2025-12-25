package fr.cnam.migration.model;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Result of dependency analysis.
 */
public record AnalysisResult(
    List<DependencyInfo> resolved,
    List<UnresolvedJar> unresolved
) {
    /**
     * Groups resolved dependencies by scope.
     */
    public Map<Scope, List<DependencyInfo>> byScope() {
        return resolved.stream()
            .collect(Collectors.groupingBy(DependencyInfo::scope));
    }

    /**
     * Groups resolved dependencies by resolution method.
     */
    public Map<ResolutionMethod, List<DependencyInfo>> byMethod() {
        return resolved.stream()
            .collect(Collectors.groupingBy(DependencyInfo::method));
    }

    /**
     * Returns all internal (proprietary) dependencies.
     */
    public List<DependencyInfo> internalDependencies() {
        return resolved.stream()
            .filter(DependencyInfo::isInternal)
            .toList();
    }

    /**
     * Returns all external (Maven Central) dependencies.
     */
    public List<DependencyInfo> externalDependencies() {
        return resolved.stream()
            .filter(d -> !d.isInternal())
            .toList();
    }

    /**
     * Resolution success rate as a percentage.
     */
    public double successRate() {
        int total = resolved.size() + unresolved.size();
        return total == 0 ? 100.0 : (resolved.size() * 100.0) / total;
    }

    /**
     * An unresolved JAR with attempted resolution methods.
     */
    public record UnresolvedJar(
        JarInfo jar,
        List<ResolutionAttempt> attempts
    ) {}

    /**
     * A single resolution attempt.
     */
    public record ResolutionAttempt(
        ResolutionMethod method,
        boolean success,
        String message,
        MavenCoordinate coordinate
    ) {
        public static ResolutionAttempt success(ResolutionMethod method, MavenCoordinate coordinate) {
            return new ResolutionAttempt(method, true, "Found", coordinate);
        }

        public static ResolutionAttempt failed(ResolutionMethod method, String reason) {
            return new ResolutionAttempt(method, false, reason, null);
        }
    }
}
