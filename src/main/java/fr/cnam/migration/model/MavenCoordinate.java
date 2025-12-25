package fr.cnam.migration.model;

import java.util.Objects;

/**
 * Coordonnées Maven GAV (GroupId, ArtifactId, Version) avec classifier optionnel.
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
     * Retourne la représentation GAV sous forme de chaîne (groupId:artifactId:version).
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
     * Retourne le chemin dans un repository Maven.
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
     * Retourne un nom de propriété sécurisé pour utilisation dans les propriétés du pom.xml.
     */
    public String toPropertyName() {
        return artifactId.replace("-", ".") + ".version";
    }

    @Override
    public String toString() {
        return toGav();
    }
}
