package fr.cnam.migration.model;

/**
 * Une dépendance résolue avec ses coordonnées Maven, son scope et ses métadonnées de résolution.
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
     * Crée une référence de propriété de version comme ${spring.version}.
     */
    public String versionProperty() {
        return "${" + coordinate.toPropertyName() + "}";
    }
}
