package fr.cnam.migration.model;

/**
 * Type of Ant project structure detected.
 * Generic detection based on directory patterns, not specific project names.
 */
public enum ProjectType {
    /**
     * Maven-like structure: *-app/src/main/java, *-app/src/main/webapp
     * Typical modules: XXX-app, XXXEar
     */
    MAVEN_STYLE,

    /**
     * Eclipse-like structure: XXX/src, XXX/WebContent
     * Typical modules: XXX, XXXEar
     */
    ECLIPSE_STYLE;

    /**
     * Returns a description of this project type.
     */
    public String getDescription() {
        return switch (this) {
            case MAVEN_STYLE -> "Maven-like structure (src/main/java)";
            case ECLIPSE_STYLE -> "Eclipse-like structure (src, WebContent)";
        };
    }
}
