package fr.cnam.migration.sonar;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Client HTTP pour l'API SonarQube.
 */
public class SonarQubeClient {

    private static final Logger log = LoggerFactory.getLogger(SonarQubeClient.class);

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    // Patterns pour parser le JSON sans bibliotheque externe
    private static final Pattern VALUE_PATTERN = Pattern.compile("\"value\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern METRIC_PATTERN = Pattern.compile(
        "\\{[^}]*\"metric\"\\s*:\\s*\"([^\"]+)\"[^}]*\"value\"\\s*:\\s*\"([^\"]+)\"[^}]*\\}"
    );
    private static final Pattern ISSUE_PATTERN = Pattern.compile(
        "\\{\"key\"\\s*:\\s*\"([^\"]+)\"[^}]+\\}"
    );

    private final String baseUrl;
    private final String token;
    private final HttpClient httpClient;
    private final boolean verbose;

    public SonarQubeClient(String baseUrl, String token, boolean verbose) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.token = token;
        this.verbose = verbose;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(TIMEOUT)
            .build();
    }

    /**
     * Teste la connexion au serveur SonarQube.
     */
    public boolean testConnection() {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/system/status"))
                .header("Authorization", "Bearer " + token)
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200 && response.body().contains("\"status\"")) {
                log.debug("Connexion SonarQube OK");
                return true;
            }
            log.warn("SonarQube status inattendu: {}", response.statusCode());
            return false;

        } catch (Exception e) {
            log.warn("Erreur connexion SonarQube: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Recupere le statut de la derniere analyse pour un projet.
     * @return "SUCCESS", "IN_PROGRESS", "FAILED", "PENDING" ou null
     */
    public String getAnalysisStatus(String projectKey) {
        try {
            String url = baseUrl + "/api/ce/component?component=" + encode(projectKey);
            String json = doGet(url);

            if (json == null) return null;

            // Chercher le statut de la tache courante
            if (json.contains("\"current\"")) {
                Pattern statusPattern = Pattern.compile("\"status\"\\s*:\\s*\"([^\"]+)\"");
                Matcher matcher = statusPattern.matcher(json);
                if (matcher.find()) {
                    return matcher.group(1);
                }
            }

            // Si pas de tache courante, verifier la queue
            if (json.contains("\"queue\":[]") || !json.contains("\"queue\"")) {
                return "SUCCESS"; // Pas de tache en cours = derniere analyse terminee
            }

            return "PENDING";

        } catch (Exception e) {
            log.debug("Erreur recuperation statut analyse: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Recupere les metriques d'un projet.
     */
    public SonarMetrics getMetrics(String projectKey) {
        try {
            String metrics = "sqale_index,sqale_debt_ratio,sqale_rating," +
                           "reliability_rating,security_rating," +
                           "new_technical_debt,code_smells,bugs,vulnerabilities," +
                           "security_hotspots,security_hotspots_reviewed," +
                           "coverage,duplicated_lines_density,ncloc";

            String url = baseUrl + "/api/measures/component?component=" + encode(projectKey) +
                        "&metricKeys=" + metrics;

            String json = doGet(url);
            if (json == null) return SonarMetrics.empty();

            return parseMetrics(json);

        } catch (Exception e) {
            log.warn("Erreur recuperation metriques: {}", e.getMessage());
            return SonarMetrics.empty();
        }
    }

    /**
     * Recupere les issues d'un projet.
     */
    public List<SonarIssue> getIssues(String projectKey, int maxResults) {
        List<SonarIssue> allIssues = new ArrayList<>();

        try {
            int page = 1;
            int pageSize = Math.min(maxResults, 500);

            while (allIssues.size() < maxResults) {
                String url = baseUrl + "/api/issues/search?" +
                            "componentKeys=" + encode(projectKey) +
                            "&statuses=OPEN,CONFIRMED,REOPENED" +
                            "&ps=" + pageSize +
                            "&p=" + page;

                String json = doGet(url);
                if (json == null) break;

                List<SonarIssue> pageIssues = parseIssues(json);
                if (pageIssues.isEmpty()) break;

                allIssues.addAll(pageIssues);

                // Verifier s'il y a plus de pages
                int total = extractTotal(json);
                if (allIssues.size() >= total || allIssues.size() >= maxResults) break;

                page++;
            }

        } catch (Exception e) {
            log.warn("Erreur recuperation issues: {}", e.getMessage());
        }

        return allIssues.size() > maxResults ? allIssues.subList(0, maxResults) : allIssues;
    }

    /**
     * Recupere le statut du quality gate.
     */
    public String getQualityGateStatus(String projectKey) {
        try {
            String url = baseUrl + "/api/qualitygates/project_status?projectKey=" + encode(projectKey);
            String json = doGet(url);

            if (json == null) return null;

            Pattern pattern = Pattern.compile("\"status\"\\s*:\\s*\"([^\"]+)\"");
            Matcher matcher = pattern.matcher(json);
            if (matcher.find()) {
                return matcher.group(1);
            }

        } catch (Exception e) {
            log.debug("Erreur recuperation quality gate: {}", e.getMessage());
        }

        return null;
    }

    /**
     * Recupere la date de la derniere analyse.
     */
    public String getLastAnalysisDate(String projectKey) {
        try {
            String url = baseUrl + "/api/project_analyses/search?project=" + encode(projectKey) + "&ps=1";
            String json = doGet(url);

            if (json == null) return null;

            Pattern pattern = Pattern.compile("\"date\"\\s*:\\s*\"([^\"]+)\"");
            Matcher matcher = pattern.matcher(json);
            if (matcher.find()) {
                return matcher.group(1);
            }

        } catch (Exception e) {
            log.debug("Erreur recuperation date analyse: {}", e.getMessage());
        }

        return null;
    }

    /**
     * Effectue une requete GET.
     * Essaie d'abord sans authentification, puis avec token si 401.
     */
    private String doGet(String url) throws IOException, InterruptedException {
        if (verbose) {
            log.debug("GET {}", url);
        }

        // Essayer d'abord sans authentification (acces anonyme)
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(TIMEOUT)
            .GET()
            .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        // Si 401, reessayer avec authentification
        if (response.statusCode() == 401 && token != null && !token.isBlank()) {
            // SonarQube accepte Basic auth avec token:empty ou Bearer token selon version
            String basicAuth = Base64.getEncoder().encodeToString((token + ":").getBytes());
            request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Basic " + basicAuth)
                .timeout(TIMEOUT)
                .GET()
                .build();

            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        }

        if (response.statusCode() == 200) {
            return response.body();
        } else if (response.statusCode() == 404) {
            log.debug("Ressource non trouvee: {}", url);
            return null;
        } else {
            log.warn("Erreur API SonarQube: HTTP {} - {}", response.statusCode(), response.body());
            return null;
        }
    }

    /**
     * Parse les metriques depuis le JSON.
     */
    private SonarMetrics parseMetrics(String json) {
        Map<String, String> values = new HashMap<>();

        Matcher matcher = METRIC_PATTERN.matcher(json);
        while (matcher.find()) {
            values.put(matcher.group(1), matcher.group(2));
        }

        return new SonarMetrics(
            parseLong(values.get("sqale_index")),
            parseDouble(values.get("sqale_debt_ratio")),
            SonarMetrics.ratingToLetter(parseDouble(values.get("sqale_rating"))),
            SonarMetrics.ratingToLetter(parseDouble(values.get("reliability_rating"))),
            SonarMetrics.ratingToLetter(parseDouble(values.get("security_rating"))),
            parseLong(values.get("new_technical_debt")),
            parseInt(values.get("code_smells")),
            parseInt(values.get("bugs")),
            parseInt(values.get("vulnerabilities")),
            parseInt(values.get("security_hotspots")),
            parseInt(values.get("security_hotspots_reviewed")),
            parseDouble(values.get("coverage")),
            parseDouble(values.get("duplicated_lines_density")),
            parseInt(values.get("ncloc"))
        );
    }

    /**
     * Parse les issues depuis le JSON.
     * Utilise un parsing manuel pour gerer les objets JSON imbriques.
     */
    private List<SonarIssue> parseIssues(String json) {
        List<SonarIssue> issues = new ArrayList<>();

        // Trouver la section "issues"
        int issuesStart = json.indexOf("\"issues\"");
        if (issuesStart < 0) return issues;

        // Trouver le debut du tableau
        int arrayStart = json.indexOf('[', issuesStart);
        if (arrayStart < 0) return issues;

        // Parser chaque issue en comptant les accolades pour gerer les objets imbriques
        int depth = 0;
        int issueStart = -1;

        for (int i = arrayStart; i < json.length(); i++) {
            char c = json.charAt(i);

            if (c == '{') {
                if (depth == 0) {
                    issueStart = i;
                }
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0 && issueStart >= 0) {
                    // On a trouve un objet issue complet
                    String block = json.substring(issueStart, i + 1);
                    SonarIssue issue = parseIssueBlock(block);
                    if (issue != null) {
                        issues.add(issue);
                    }
                    issueStart = -1;
                }
            } else if (c == ']' && depth == 0) {
                // Fin du tableau issues
                break;
            }
        }

        return issues;
    }

    /**
     * Parse un bloc JSON representant une issue.
     */
    private SonarIssue parseIssueBlock(String block) {
        String key = extractField(block, "key");
        String rule = extractField(block, "rule");
        String severity = extractField(block, "severity");
        String type = extractField(block, "type");
        String message = extractField(block, "message");
        String component = extractField(block, "component");
        int line = parseInt(extractField(block, "line"));
        String effort = extractField(block, "effort");

        if (key != null && severity != null) {
            return new SonarIssue(key, rule, severity, type, message, component, line, effort);
        }
        return null;
    }

    /**
     * Extrait un champ JSON simple.
     */
    private String extractField(String json, String field) {
        Pattern pattern = Pattern.compile("\"" + field + "\"\\s*:\\s*\"([^\"]*)\"");
        Matcher matcher = pattern.matcher(json);
        return matcher.find() ? matcher.group(1) : null;
    }

    /**
     * Extrait le total d'issues depuis la reponse.
     */
    private int extractTotal(String json) {
        Pattern pattern = Pattern.compile("\"total\"\\s*:\\s*(\\d+)");
        Matcher matcher = pattern.matcher(json);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : 0;
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private int parseInt(String value) {
        if (value == null || value.isBlank()) return 0;
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private long parseLong(String value) {
        if (value == null || value.isBlank()) return 0;
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private double parseDouble(String value) {
        if (value == null || value.isBlank()) return 0.0;
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }
}
