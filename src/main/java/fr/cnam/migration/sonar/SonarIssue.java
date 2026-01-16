package fr.cnam.migration.sonar;

/**
 * Represente une issue SonarQube (bug, vulnerability, code smell).
 */
public record SonarIssue(
    String key,           // Identifiant unique de l'issue
    String rule,          // Regle SonarQube (ex: java:S1234)
    String severity,      // BLOCKER, CRITICAL, MAJOR, MINOR, INFO
    String type,          // BUG, VULNERABILITY, CODE_SMELL, SECURITY_HOTSPOT
    String message,       // Description de l'issue
    String component,     // Chemin du fichier
    int line,             // Numero de ligne
    String effort         // Temps de remediation (ex: "15min", "1h")
) {
    /**
     * Retourne le nom du fichier sans le chemin complet.
     */
    public String fileName() {
        if (component == null) return "";
        int lastSlash = component.lastIndexOf('/');
        int lastColon = component.lastIndexOf(':');
        int start = Math.max(lastSlash, lastColon) + 1;
        return component.substring(start);
    }

    /**
     * Retourne le chemin relatif du fichier (sans la cle du projet).
     */
    public String relativePath() {
        if (component == null) return "";
        int colonIdx = component.indexOf(':');
        return colonIdx >= 0 ? component.substring(colonIdx + 1) : component;
    }

    /**
     * Determine la couleur CSS associee a la severite.
     */
    public String severityColor() {
        return switch (severity) {
            case "BLOCKER" -> "#1a1a1a";
            case "CRITICAL" -> "#dc2626";
            case "MAJOR" -> "#ea580c";
            case "MINOR" -> "#eab308";
            case "INFO" -> "#3b82f6";
            default -> "#6b7280";
        };
    }

    /**
     * Determine la classe CSS associee au type.
     */
    public String typeIcon() {
        return switch (type) {
            case "BUG" -> "bug";
            case "VULNERABILITY" -> "shield";
            case "CODE_SMELL" -> "code";
            case "SECURITY_HOTSPOT" -> "fire";
            default -> "question";
        };
    }
}
