package fr.cnam.migration.model;

/**
 * How a JAR was resolved to Maven coordinates.
 */
public enum ResolutionMethod {
    /**
     * JAR was identified as an internal/proprietary artifact.
     */
    INTERNAL_PATTERN("Internal artifact pattern"),

    /**
     * JAR was found in the known-artifacts configuration.
     */
    KNOWN_CONFIG("Known artifacts configuration"),

    /**
     * JAR was found on Artifactory by SHA1 checksum.
     */
    ARTIFACTORY_CHECKSUM("Artifactory SHA1 checksum lookup"),

    /**
     * JAR exists on Artifactory (verified by coordinates).
     */
    ARTIFACTORY("Artifactory lookup"),

    /**
     * JAR was found on Maven Central by SHA1 checksum.
     */
    CHECKSUM("Maven Central SHA1 checksum lookup"),

    /**
     * JAR was identified from MANIFEST.MF metadata.
     */
    MANIFEST("MANIFEST.MF analysis"),

    /**
     * JAR was identified by filename pattern matching.
     */
    PATTERN("Filename pattern matching"),

    /**
     * JAR could not be resolved.
     */
    UNRESOLVED("Unresolved");

    private final String description;

    ResolutionMethod(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }
}
