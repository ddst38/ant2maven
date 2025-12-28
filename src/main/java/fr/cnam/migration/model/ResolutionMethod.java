package fr.cnam.migration.model;

/**
 * Comment un JAR a été résolu en coordonnées Maven.
 */
public enum ResolutionMethod {
    /**
     * JAR identifié comme un artefact interne/propriétaire.
     */
    INTERNAL_PATTERN("Pattern d'artefact interne"),

    /**
     * JAR trouvé dans la configuration des artefacts connus.
     */
    KNOWN_CONFIG("Configuration des artefacts connus"),

    /**
     * JAR trouvé sur Artifactory par checksum SHA1.
     */
    ARTIFACTORY_CHECKSUM("Recherche SHA1 sur Artifactory"),

    /**
     * JAR existe sur Artifactory (vérifié par coordonnées).
     */
    ARTIFACTORY("Recherche Artifactory"),

    /**
     * JAR trouvé sur Maven Central par checksum SHA1.
     */
    CHECKSUM("Recherche SHA1 sur Maven Central"),

    /**
     * JAR identifié depuis les métadonnées MANIFEST.MF.
     */
    MANIFEST("Analyse MANIFEST.MF"),

    /**
     * JAR identifié par correspondance de pattern sur le nom de fichier.
     */
    PATTERN("Correspondance de pattern sur nom de fichier"),

    /**
     * JAR identifié par analyse des packages contenus.
     */
    PACKAGE_ANALYSIS("Analyse des packages du JAR"),

    /**
     * JAR non résolu.
     */
    UNRESOLVED("Non résolu");

    private final String description;

    ResolutionMethod(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }
}
