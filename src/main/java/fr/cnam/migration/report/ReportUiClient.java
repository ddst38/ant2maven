package fr.cnam.migration.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import fr.cnam.migration.config.JarNameCleaner;
import fr.cnam.migration.model.*;
import fr.cnam.migration.analyzer.JarPackageAnalyzer;
import fr.cnam.migration.autofix.model.AutoFixResult;
import fr.cnam.migration.autofix.model.ProvidedDependency;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Client HTTP pour soumettre les rapports de migration à l'application ReportUI.
 *
 * Permet d'envoyer les résultats de migration vers un serveur centralisé
 * pour visualisation et suivi.
 */
public class ReportUiClient {

    private static final Logger log = LoggerFactory.getLogger(ReportUiClient.class);

    private final String baseUrl;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String basePackage;

    public ReportUiClient(String baseUrl, String basePackage) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.basePackage = basePackage;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
        this.objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule());
    }

    /**
     * Soumet un rapport de migration au serveur ReportUI.
     */
    public void submitReport(ProjectStructure project, AnalysisResult analysis) {
        submitReport(project, analysis, null);
    }

    /**
     * Soumet un rapport de migration au serveur ReportUI avec les librairies provided.
     */
    public void submitReport(ProjectStructure project, AnalysisResult analysis, AutoFixResult autoFixResult) {
        try {
            Map<String, Object> request = buildRequest(project, analysis, autoFixResult);
            String jsonBody = objectMapper.writeValueAsString(request);

            HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/reports"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .timeout(Duration.ofSeconds(30))
                .build();

            HttpResponse<String> response = httpClient.send(httpRequest,
                HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 201 || response.statusCode() == 200) {
                log.info("Rapport soumis avec succès à ReportUI");
            } else {
                log.warn("Erreur lors de la soumission du rapport: HTTP {} - {}",
                    response.statusCode(), response.body());
            }

        } catch (IOException | InterruptedException e) {
            log.warn("Impossible de soumettre le rapport à ReportUI: {}", e.getMessage());
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Construit la requête de création de rapport à partir des modèles ant2maven.
     */
    private Map<String, Object> buildRequest(ProjectStructure project, AnalysisResult analysis, AutoFixResult autoFixResult) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("projectName", project.name());
        request.put("migrationDate", LocalDateTime.now().toString());
        request.put("statistics", buildStatistics(project, analysis, autoFixResult));
        request.put("libraries", buildLibraries(analysis, autoFixResult));
        return request;
    }

    /**
     * Construit les statistiques agrégées.
     */
    private Map<String, Object> buildStatistics(ProjectStructure project, AnalysisResult analysis, AutoFixResult autoFixResult) {
        int totalDetected = project.allJars().size();
        int providedCount = autoFixResult != null ? autoFixResult.addedDependencies().size() : 0;

        // Calcul cohérent avec ReportGenerator
        int resolvedCount = (int) analysis.resolved().stream()
            .filter(d -> !d.needsLocalInstall())
            .count();
        int unresolvedCount = (int) analysis.resolved().stream()
            .filter(DependencyInfo::needsLocalInstall)
            .count() + analysis.unresolved().size();
        int totalAfterDedup = resolvedCount + unresolvedCount;
        double successRate = totalAfterDedup > 0 ? (resolvedCount * 100.0 / totalAfterDedup) : 0;

        // Par scope
        Map<String, Integer> byScope = new LinkedHashMap<>(analysis.byScope().entrySet().stream()
            .collect(Collectors.toMap(
                e -> e.getKey().name(),
                e -> e.getValue().size(),
                (a, b) -> a,
                LinkedHashMap::new
            )));

        // Ajouter les provided au byScope
        if (providedCount > 0) {
            byScope.put("PROVIDED", providedCount);
        }

        // Par méthode
        Map<String, Integer> byMethod = new LinkedHashMap<>(analysis.byMethod().entrySet().stream()
            .collect(Collectors.toMap(
                e -> e.getKey().name(),
                e -> e.getValue().size(),
                (a, b) -> a,
                LinkedHashMap::new
            )));

        // Ajouter les provided au byMethod
        if (providedCount > 0) {
            byMethod.put("AUTO_FIX", providedCount);
        }

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("totalJars", totalDetected + providedCount);
        stats.put("resolved", resolvedCount + providedCount);
        stats.put("unresolved", unresolvedCount);
        stats.put("successRate", Math.round(successRate * 10.0) / 10.0);
        stats.put("byScope", byScope);
        stats.put("byMethod", byMethod);
        return stats;
    }

    /**
     * Construit la liste des bibliothèques.
     */
    private List<Map<String, Object>> buildLibraries(AnalysisResult analysis, AutoFixResult autoFixResult) {
        List<Map<String, Object>> libraries = new ArrayList<>();
        JarPackageAnalyzer packageAnalyzer = new JarPackageAnalyzer();

        // Bibliothèques résolues
        for (DependencyInfo dep : analysis.resolved()) {
            Map<String, Object> lib = new LinkedHashMap<>();
            JarInfo jar = dep.sourceJar();

            String originalName = jar != null ? jar.name() : dep.artifactId() + ".jar";
            String cleanedName = JarNameCleaner.clean(originalName);

            lib.put("originalName", originalName);
            lib.put("cleanedName", cleanedName);
            lib.put("groupId", dep.groupId());
            lib.put("artifactId", dep.artifactId());
            lib.put("version", dep.version());
            lib.put("scope", dep.scope().name());
            lib.put("resolutionMethod", dep.method().name());
            lib.put("size", jar != null ? jar.size() : 0);
            lib.put("sha1", jar != null ? jar.sha1() : null);
            lib.put("status", dep.needsLocalInstall() ? "LOCAL" : "RESOLVED");
            lib.put("isInternal", dep.isInternal());

            libraries.add(lib);
        }

        // Bibliothèques non résolues
        for (AnalysisResult.UnresolvedJar unresolved : analysis.unresolved()) {
            Map<String, Object> lib = new LinkedHashMap<>();
            JarInfo jar = unresolved.jar();

            String originalName = jar.name();
            String cleanedName = JarNameCleaner.clean(originalName);
            String artifactName = cleanedName.replace(".jar", "");
            String version = jar.sha1() != null ? "SHA-" + jar.sha1() : "UNKNOWN";

            // Analyser le package réel du JAR pour déterminer le groupId
            String groupId;
            JarPackageAnalyzer.PackageAnalysis pkgAnalysis = packageAnalyzer.analyze(jar.path());
            if (pkgAnalysis.inferredGroupId() != null) {
                groupId = pkgAnalysis.inferredGroupId();
            } else {
                groupId = basePackage;
            }
            boolean isInternal = groupId.startsWith("fr.cnam");

            lib.put("originalName", originalName);
            lib.put("cleanedName", cleanedName);
            lib.put("groupId", groupId);
            lib.put("artifactId", artifactName);
            lib.put("version", version);
            lib.put("scope", "COMPILE");
            lib.put("resolutionMethod", "UNRESOLVED");
            lib.put("size", jar.size());
            lib.put("sha1", jar.sha1());
            lib.put("status", "UNRESOLVED");
            lib.put("isInternal", isInternal);

            libraries.add(lib);
        }

        // Bibliothèques provided (ajoutées par auto-fix)
        if (autoFixResult != null && autoFixResult.addedDependencies() != null) {
            for (ProvidedDependency provided : autoFixResult.addedDependencies()) {
                Map<String, Object> lib = new LinkedHashMap<>();

                String originalName = provided.jarPath().getFileName().toString();
                long size = 0;
                try {
                    size = java.nio.file.Files.size(provided.jarPath());
                } catch (Exception ignored) {}

                lib.put("originalName", originalName);
                lib.put("cleanedName", originalName);
                lib.put("groupId", provided.groupId());
                lib.put("artifactId", provided.artifactId());
                lib.put("version", provided.version());
                lib.put("scope", "PROVIDED");
                lib.put("resolutionMethod", "AUTO_FIX");
                lib.put("size", size);
                lib.put("sha1", null);
                lib.put("status", "RESOLVED");
                lib.put("isInternal", false);

                libraries.add(lib);
            }
        }

        return libraries;
    }
}
