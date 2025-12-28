package fr.cnam.migration.model;

/**
 * Une dépendance résolue avec ses coordonnées Maven, son scope et ses métadonnées de résolution.
 *
 * Une dépendance est considérée comme "interne" si ses coordonnées Maven commencent par
 * le package de base CNAM (fr.cnamts ou fr.cnam). Les bibliothèques avec des coordonnées
 * Maven Central (org.apache.*, com.*, etc.) ne sont pas internes, même si elles ont été
 * résolues via un pattern interne.
 */
public record DependencyInfo(
    MavenCoordinate coordinate,
    Scope scope,
    ResolutionMethod method,
    JarInfo sourceJar
) {
    /**
     * Vérifie si cette dépendance est interne (artefact propriétaire CNAM).
     * Une dépendance est interne si son groupId commence par fr.cnamts ou fr.cnam.
     * Les bibliothèques avec des coordonnées Maven Central ne sont pas internes.
     */
    public boolean isInternal() {
        String groupId = coordinate.groupId();
        return groupId.startsWith("fr.cnamts") || groupId.startsWith("fr.cnam");
    }

    /**
     * Vérifie si cette dépendance nécessite une installation locale.
     * Une dépendance nécessite une installation locale si sa version est "LOCAL".
     */
    public boolean needsLocalInstall() {
        return "LOCAL".equals(coordinate.version());
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
