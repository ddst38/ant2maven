package fr.cnam.migration.autofix.model;

import java.nio.file.Path;

/**
 * Represente une dependance provided a ajouter au pom.xml.
 */
public record ProvidedDependency(
    String groupId,
    String artifactId,
    String version,
    Path jarPath
) {
    /**
     * Retourne les coordonnees Maven au format GAV.
     */
    public String toGav() {
        return groupId + ":" + artifactId + ":" + version;
    }

    /**
     * Cree une dependance a partir d'un JAR avec des coordonnees inferees.
     */
    public static ProvidedDependency fromJar(Path jarPath, String groupId, String version) {
        String fileName = jarPath.getFileName().toString();
        String artifactId = fileName.endsWith(".jar")
            ? fileName.substring(0, fileName.length() - 4)
            : fileName;
        return new ProvidedDependency(groupId, artifactId, version, jarPath);
    }
}
