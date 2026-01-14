package fr.cnam.migration.model;

/**
 * Niveaux de sévérité CVE basés sur les scores CVSS v3.
 * Chaque niveau a un score minimum et une couleur associée pour l'affichage.
 */
public enum CveSeverity {
    CRITICAL(9.0, "#1a1a1a", "Critique"),   // Noir
    HIGH(7.0, "#dc2626", "Haute"),          // Rouge
    MEDIUM(4.0, "#f97316", "Moyenne"),      // Orange
    LOW(0.1, "#eab308", "Basse"),           // Jaune
    NONE(0.0, "#22c55e", "Aucune"),         // Vert
    UNKNOWN(-1.0, "#6b7280", "Inconnue");   // Gris

    private final double minScore;
    private final String color;
    private final String labelFr;

    CveSeverity(double minScore, String color, String labelFr) {
        this.minScore = minScore;
        this.color = color;
        this.labelFr = labelFr;
    }

    public double getMinScore() {
        return minScore;
    }

    public String getColor() {
        return color;
    }

    public String getLabelFr() {
        return labelFr;
    }

    /**
     * Détermine le niveau de sévérité à partir d'un score CVSS.
     */
    public static CveSeverity fromScore(double cvssScore) {
        if (cvssScore < 0) return UNKNOWN;
        if (cvssScore >= 9.0) return CRITICAL;
        if (cvssScore >= 7.0) return HIGH;
        if (cvssScore >= 4.0) return MEDIUM;
        if (cvssScore > 0) return LOW;
        return NONE;
    }

    /**
     * Détermine le niveau de sévérité à partir d'une chaîne (ex: "CRITICAL", "HIGH").
     */
    public static CveSeverity fromString(String severity) {
        if (severity == null || severity.isBlank()) return UNKNOWN;
        return switch (severity.toUpperCase().trim()) {
            case "CRITICAL" -> CRITICAL;
            case "HIGH" -> HIGH;
            case "MEDIUM", "MODERATE" -> MEDIUM;
            case "LOW" -> LOW;
            case "NONE", "INFORMATIONAL" -> NONE;
            default -> UNKNOWN;
        };
    }
}
