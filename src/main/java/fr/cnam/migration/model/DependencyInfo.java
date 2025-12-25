package fr.cnam.migration.model;

/**
 * A resolved dependency with its Maven coordinates, scope, and resolution metadata.
 */
public record DependencyInfo(
    MavenCoordinate coordinate,
    Scope scope,
    ResolutionMethod method,
    JarInfo sourceJar,
    boolean isInternal
) {
    public DependencyInfo(MavenCoordinate coordinate, Scope scope, ResolutionMethod method, JarInfo sourceJar) {
        this(coordinate, scope, method, sourceJar, method == ResolutionMethod.INTERNAL_PATTERN);
    }

    public String groupId() {
        return coordinate.groupId();
    }

    public String artifactId() {
        return coordinate.artifactId();
    }

    public String version() {
        return coordinate.version();
    }

    /**
     * Creates a version property reference like ${spring.version}.
     */
    public String versionProperty() {
        return "${" + coordinate.toPropertyName() + "}";
    }
}
