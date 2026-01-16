package fr.cnam.migration.sonar;

/**
 * Metriques de qualite recuperees depuis SonarQube.
 */
public record SonarMetrics(
    long technicalDebt,           // sqale_index en minutes
    double debtRatio,             // sqale_debt_ratio en %
    String maintainabilityRating, // A, B, C, D, E
    String reliabilityRating,     // A, B, C, D, E
    String securityRating,        // A, B, C, D, E
    long newTechnicalDebt,        // new_technical_debt en minutes
    int codeSmells,
    int bugs,
    int vulnerabilities,
    int securityHotspots,
    int securityHotspotsReviewed,
    double coverage,              // en %
    double duplications,          // duplicated_lines_density en %
    int linesOfCode               // ncloc
) {
    /**
     * Cree des metriques vides.
     */
    public static SonarMetrics empty() {
        return new SonarMetrics(0, 0.0, "A", "A", "A", 0, 0, 0, 0, 0, 0, 0.0, 0.0, 0);
    }

    /**
     * Convertit le rating numerique (1-5) en lettre (A-E).
     */
    public static String ratingToLetter(double rating) {
        int r = (int) Math.round(rating);
        return switch (r) {
            case 1 -> "A";
            case 2 -> "B";
            case 3 -> "C";
            case 4 -> "D";
            default -> "E";
        };
    }

    /**
     * Formate la dette technique en format lisible.
     * Ex: 480 minutes -> "1j 0h" ou "8h 0min"
     */
    public String formattedDebt() {
        if (technicalDebt <= 0) return "0min";

        long minutes = technicalDebt;
        long hours = minutes / 60;
        long days = hours / 8; // 8h par jour de travail

        if (days > 0) {
            hours = hours % 8;
            return days + "j " + hours + "h";
        } else if (hours > 0) {
            minutes = minutes % 60;
            return hours + "h " + minutes + "min";
        } else {
            return minutes + "min";
        }
    }

    /**
     * Verifie si les metriques indiquent des problemes critiques.
     */
    public boolean hasCriticalIssues() {
        return bugs > 0 || vulnerabilities > 0 ||
               "D".equals(maintainabilityRating) || "E".equals(maintainabilityRating) ||
               "D".equals(reliabilityRating) || "E".equals(reliabilityRating) ||
               "D".equals(securityRating) || "E".equals(securityRating);
    }
}
