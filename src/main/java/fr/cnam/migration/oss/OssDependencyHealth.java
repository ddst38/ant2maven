package fr.cnam.migration.oss;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * Sante d'une dependance selon l'analyse OSS Index.
 * Evalue la viabilite long terme, pas seulement les vulnerabilites.
 */
public record OssDependencyHealth(
    String groupId,
    String artifactId,
    String currentVersion,
    String latestVersion,
    LocalDate currentReleaseDate,
    LocalDate latestReleaseDate,
    int majorVersionsBehind,
    int minorVersionsBehind,
    int patchVersionsBehind,
    int criticalVulns,
    int highVulns,
    int mediumVulns,
    int lowVulns,
    long mavenPopularityRank,
    double healthScore
) {
    /**
     * Calcule le score de sante (0-10) selon les standards industriels.
     */
    public static double calculateHealthScore(
            LocalDate currentReleaseDate,
            int majorVersionsBehind,
            int minorVersionsBehind,
            int criticalVulns,
            int highVulns,
            int mediumVulns,
            int lowVulns,
            long mavenPopularityRank) {

        double score = 10.0;

        // Penalites Maintenance (max 3.0)
        if (currentReleaseDate != null) {
            long monthsOld = ChronoUnit.MONTHS.between(currentReleaseDate, LocalDate.now());
            if (monthsOld > 36) {
                score -= 3.0;
            } else if (monthsOld > 24) {
                score -= 2.0;
            } else if (monthsOld > 12) {
                score -= 1.0;
            }
        }

        // Penalites Version currency (max 3.0)
        if (majorVersionsBehind >= 2) {
            score -= 3.0;
        } else if (majorVersionsBehind == 1) {
            score -= 1.5;
        } else if (minorVersionsBehind >= 3) {
            score -= 1.0;
        }

        // Penalites Vulnerabilites (max 2.5)
        double vulnPenalty = (criticalVulns * 1.0) + (highVulns * 0.5) +
                           ((mediumVulns + lowVulns) * 0.2);
        score -= Math.min(vulnPenalty, 2.5);

        // Bonus Popularite (max +1.5)
        if (mavenPopularityRank > 0) {
            if (mavenPopularityRank <= 1000) {
                score += 1.5;
            } else if (mavenPopularityRank <= 5000) {
                score += 1.0;
            } else if (mavenPopularityRank <= 20000) {
                score += 0.5;
            }
        }

        return Math.max(0, Math.min(10, score));
    }

    /**
     * Retourne le statut de sante.
     */
    public String healthStatus() {
        if (healthScore >= 8) return "HEALTHY";
        if (healthScore >= 6) return "MONITOR";
        if (healthScore >= 4) return "RISKY";
        return "CRITICAL";
    }

    /**
     * Retourne l'age de la release en mois.
     */
    public long releaseAgeMonths() {
        if (currentReleaseDate == null) return -1;
        return ChronoUnit.MONTHS.between(currentReleaseDate, LocalDate.now());
    }

    /**
     * Retourne le nombre total de vulnerabilites.
     */
    public int totalVulnerabilities() {
        return criticalVulns + highVulns + mediumVulns + lowVulns;
    }

    /**
     * Coordonnees Maven completes.
     */
    public String coordinates() {
        return groupId + ":" + artifactId + ":" + currentVersion;
    }

    /**
     * Indique si une mise a jour est disponible.
     */
    public boolean hasUpdate() {
        return latestVersion != null && !latestVersion.equals(currentVersion);
    }

    /**
     * Retourne le nombre total de versions en retard.
     */
    public int totalVersionsBehind() {
        return majorVersionsBehind + minorVersionsBehind + patchVersionsBehind;
    }
}
