package fr.cnam.migration.model;

import java.util.Objects;

/**
 * Maven GAV (GroupId, ArtifactId, Version) coordinate with optional classifier.
 */
public record MavenCoordinate(
    String groupId,
    String artifactId,
    String version,
    String classifier,
    String type
) {
    public MavenCoordinate {
        Objects.requireNonNull(groupId, "groupId cannot be null");
        Objects.requireNonNull(artifactId, "artifactId cannot be null");
        Objects.requireNonNull(version, "version cannot be null");
    }

    public MavenCoordinate(String groupId, String artifactId, String version) {
        this(groupId, artifactId, version, null, "jar");
    }

    public MavenCoordinate(String groupId, String artifactId, String version, String classifier) {
        this(groupId, artifactId, version, classifier, "jar");
    }

    /**
     * Returns the GAV string representation (groupId:artifactId:version).
     */
    public String toGav() {
        StringBuilder sb = new StringBuilder();
        sb.append(groupId).append(":").append(artifactId).append(":").append(version);
        if (classifier != null && !classifier.isEmpty()) {
            sb.append(":").append(classifier);
        }
        return sb.toString();
    }

    /**
     * Returns the path in a Maven repository.
     */
    public String toRepositoryPath() {
        StringBuilder sb = new StringBuilder();
        sb.append(groupId.replace('.', '/'))
          .append("/")
          .append(artifactId)
          .append("/")
          .append(version)
          .append("/")
          .append(artifactId)
          .append("-")
          .append(version);
        if (classifier != null && !classifier.isEmpty()) {
            sb.append("-").append(classifier);
        }
        sb.append(".").append(type != null ? type : "jar");
        return sb.toString();
    }

    /**
     * Returns a property-safe version name for use in pom.xml properties.
     */
    public String toPropertyName() {
        return artifactId.replace("-", ".") + ".version";
    }

    @Override
    public String toString() {
        return toGav();
    }
}
