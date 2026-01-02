package fr.cnam.migration.model;

/**
 * Type de structure de projet Ant détecté.
 * Détection générique basée sur les patterns de répertoires, pas sur les noms de projets spécifiques.
 */
public enum ProjectType {
    /**
     * Structure style Maven : *-app/src/main/java, *-app/src/main/webapp
     * Modules typiques : XXX-app, XXXEar
     */
    MAVEN_STYLE,

    /**
     * Structure style Eclipse : XXX/src, XXX/WebContent
     * Modules typiques : XXX, XXXEar
     */
    ECLIPSE_STYLE,

    /**
     * Structure multi-module : plusieurs sous-projets avec chaine de dependances.
     * Ex: MetierXXXClient -> MetierXXX -> MetierXXXjms -> MetierXXXws
     */
    MULTI_MODULE;

    /**
     * Retourne une description de ce type de projet.
     */
    public String getDescription() {
        return switch (this) {
            case MAVEN_STYLE -> "Structure style Maven (src/main/java)";
            case ECLIPSE_STYLE -> "Structure style Eclipse (src, WebContent)";
            case MULTI_MODULE -> "Structure multi-module (plusieurs JAR/WAR avec dependances)";
        };
    }
}
