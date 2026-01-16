package fr.cnam.migration.sonar;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Resultat complet de l'analyse SonarQube.
 */
public record SonarAnalysisResult(
    boolean analysisPerformed,
    SonarMetrics metrics,
    List<SonarIssue> issues,
    Map<String, List<SonarIssue>> issuesByType,
    Map<String, Integer> issuesBySeverity,
    String projectKey,
    String analysisDate,
    String qualityGateStatus  // OK, WARN, ERROR
) {
    /**
     * Cree un resultat vide (analyse non effectuee).
     */
    public static SonarAnalysisResult empty() {
        return new SonarAnalysisResult(
            false,
            SonarMetrics.empty(),
            List.of(),
            Map.of(),
            Map.of(),
            null,
            null,
            null
        );
    }

    /**
     * Cree un resultat d'echec (analyse tentee mais echouee).
     */
    public static SonarAnalysisResult failed() {
        return new SonarAnalysisResult(
            true,
            SonarMetrics.empty(),
            List.of(),
            Map.of(),
            Map.of(),
            null,
            null,
            "ERROR"
        );
    }

    /**
     * Cree un resultat a partir des metriques et issues.
     */
    public static SonarAnalysisResult from(SonarMetrics metrics, List<SonarIssue> issues,
                                           String projectKey, String analysisDate,
                                           String qualityGateStatus) {
        // Grouper par type
        Map<String, List<SonarIssue>> byType = issues.stream()
            .collect(Collectors.groupingBy(SonarIssue::type));

        // Compter par severite
        Map<String, Integer> bySeverity = new LinkedHashMap<>();
        bySeverity.put("BLOCKER", 0);
        bySeverity.put("CRITICAL", 0);
        bySeverity.put("MAJOR", 0);
        bySeverity.put("MINOR", 0);
        bySeverity.put("INFO", 0);

        for (SonarIssue issue : issues) {
            bySeverity.merge(issue.severity(), 1, Integer::sum);
        }

        return new SonarAnalysisResult(
            true,
            metrics,
            issues,
            byType,
            bySeverity,
            projectKey,
            analysisDate,
            qualityGateStatus
        );
    }

    /**
     * Retourne le nombre total d'issues.
     */
    public int totalIssues() {
        return issues.size();
    }

    /**
     * Retourne le nombre d'issues bloquantes ou critiques.
     */
    public int criticalIssuesCount() {
        return issuesBySeverity.getOrDefault("BLOCKER", 0) +
               issuesBySeverity.getOrDefault("CRITICAL", 0);
    }

    /**
     * Verifie si le quality gate est passe.
     */
    public boolean qualityGatePassed() {
        return "OK".equals(qualityGateStatus);
    }

    /**
     * Retourne les issues triees par severite (plus graves en premier).
     */
    public List<SonarIssue> issuesBySeverityOrder() {
        List<String> order = List.of("BLOCKER", "CRITICAL", "MAJOR", "MINOR", "INFO");
        return issues.stream()
            .sorted((a, b) -> {
                int idxA = order.indexOf(a.severity());
                int idxB = order.indexOf(b.severity());
                return Integer.compare(idxA >= 0 ? idxA : 99, idxB >= 0 ? idxB : 99);
            })
            .collect(Collectors.toList());
    }

    /**
     * Retourne les N premieres issues les plus graves.
     */
    public List<SonarIssue> topIssues(int limit) {
        return issuesBySeverityOrder().stream()
            .limit(limit)
            .collect(Collectors.toList());
    }
}
