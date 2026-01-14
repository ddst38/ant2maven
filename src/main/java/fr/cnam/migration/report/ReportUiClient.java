package fr.cnam.migration.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import fr.cnam.migration.config.JarNameCleaner;
import fr.cnam.migration.config.MigrationConfig;
import fr.cnam.migration.model.*;
import fr.cnam.migration.analyzer.JarPackageAnalyzer;
import fr.cnam.migration.autofix.model.AutoFixResult;
import fr.cnam.migration.autofix.model.ProvidedDependency;
import fr.cnam.migration.model.CveAnalysisResult;
import fr.cnam.migration.model.CveInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import fr.cnam.migration.autofix.model.MissingDependency;

import java.io.IOException;
import java.io.InputStream;
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

    // Préfixes de packages internes (chargés depuis internal-packages.yaml)
    private static final List<String> INTERNAL_PACKAGE_PREFIXES = Arrays.asList(
        "fr.cnam", "fr.cnamts", "com.cnamts", "com.cnam", "com.rfe", "biblicnam"
    );

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
     * Détermine si un groupId correspond à un package interne.
     */
    private boolean isInternalPackage(String groupId) {
        if (groupId == null) return false;
        return INTERNAL_PACKAGE_PREFIXES.stream().anyMatch(groupId::startsWith);
    }

    /**
     * Détermine la source d'identification d'une bibliothèque.
     */
    private String getIdentificationSource(ResolutionMethod method) {
        return switch (method) {
            case ARTIFACTORY, ARTIFACTORY_CHECKSUM -> "ARTIFACTORY";
            case NEXUS, NEXUS_CHECKSUM -> "NEXUS";
            case CHECKSUM -> "MAVEN_CENTRAL";
            case KNOWN_CONFIG -> "CACHE";
            case INTERNAL_PATTERN, PATTERN -> "PATTERN";
            default -> null;
        };
    }

    /**
     * Soumet un rapport de migration au serveur ReportUI.
     */
    public void submitReport(ProjectStructure project, AnalysisResult analysis) {
        submitReport(project, analysis, null, null);
    }

    /**
     * Soumet un rapport de migration au serveur ReportUI avec les librairies provided.
     */
    public void submitReport(ProjectStructure project, AnalysisResult analysis, AutoFixResult autoFixResult) {
        submitReport(project, analysis, autoFixResult, null);
    }

    /**
     * Soumet un rapport de migration avec analyse CVE.
     */
    public void submitReport(ProjectStructure project, AnalysisResult analysis,
                            AutoFixResult autoFixResult, CveAnalysisResult cveResult) {
        submitReport(project, analysis, autoFixResult, cveResult, null, null);
    }

    /**
     * Soumet un rapport de migration complet avec informations de déploiement.
     *
     * @param project Structure du projet
     * @param analysis Résultat de l'analyse des dépendances
     * @param autoFixResult Résultat de l'auto-fix (peut être null)
     * @param cveResult Résultat de l'analyse CVE (peut être null)
     * @param config Configuration de migration (pour infos de déploiement)
     * @param deployedLibraries Liste des librairies déployées sur le repository distant
     */
    public void submitReport(ProjectStructure project, AnalysisResult analysis,
                            AutoFixResult autoFixResult, CveAnalysisResult cveResult,
                            MigrationConfig config, List<DeployedLibrary> deployedLibraries) {
        try {
            Map<String, Object> request = buildRequest(project, analysis, autoFixResult, cveResult,
                                                       config, deployedLibraries);
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
    private Map<String, Object> buildRequest(ProjectStructure project, AnalysisResult analysis,
                                             AutoFixResult autoFixResult, CveAnalysisResult cveResult,
                                             MigrationConfig config, List<DeployedLibrary> deployedLibraries) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("projectName", project.name());
        request.put("migrationDate", LocalDateTime.now().toString());
        request.put("statistics", buildStatistics(project, analysis, autoFixResult));
        request.put("libraries", buildLibraries(analysis, autoFixResult));
        request.put("detectedJars", buildDetectedJars(project));
        request.put("missingPackages", buildMissingPackages(autoFixResult));
        request.put("compilationSuccess", autoFixResult == null || autoFixResult.isSuccess());

        // Données CVE
        if (cveResult != null && cveResult.analysisPerformed()) {
            request.put("cveVulnerabilities", buildCveVulnerabilities(cveResult));
            request.put("cveSummary", buildCveSummary(cveResult));
        }

        // Informations de déploiement (mode REMOTE uniquement)
        if (config != null && config.isRemoteDeployment() && deployedLibraries != null && !deployedLibraries.isEmpty()) {
            request.put("deploymentInfo", buildDeploymentInfo(config, deployedLibraries));
        }

        return request;
    }

    /**
     * Construit les informations de déploiement vers Artifactory/Nexus.
     */
    private Map<String, Object> buildDeploymentInfo(MigrationConfig config, List<DeployedLibrary> deployedLibraries) {
        Map<String, Object> info = new LinkedHashMap<>();

        // Type de repository (ARTIFACTORY ou NEXUS)
        String repoType = config.isNexusDeployment() ? "NEXUS" : "ARTIFACTORY";
        info.put("repositoryType", repoType);

        // URL du repository
        String repoUrl = config.isNexusDeployment()
            ? config.nexusUrl() + "/repository/" + config.deployRepository()
            : config.artifactoryUrl() + "/" + config.deployRepository();
        info.put("repositoryUrl", repoUrl);

        // Nom du repository
        info.put("repositoryName", config.deployRepository());

        // Liste des librairies déployées
        List<Map<String, Object>> libs = new ArrayList<>();
        for (DeployedLibrary lib : deployedLibraries) {
            Map<String, Object> libData = new LinkedHashMap<>();
            libData.put("groupId", lib.groupId());
            libData.put("artifactId", lib.artifactId());
            libData.put("version", lib.version());
            libData.put("gav", lib.groupId() + ":" + lib.artifactId() + ":" + lib.version());
            libData.put("originalJar", lib.originalJarName());
            libData.put("type", lib.type()); // INTERNAL, EXTERNAL, PROVIDED
            libData.put("size", lib.size());
            libs.add(libData);
        }
        info.put("deployedLibraries", libs);
        info.put("deployedCount", libs.size());

        return info;
    }

    /**
     * Record représentant une librairie déployée sur un repository distant.
     */
    public record DeployedLibrary(
        String groupId,
        String artifactId,
        String version,
        String originalJarName,
        String type,  // INTERNAL, EXTERNAL, PROVIDED
        long size
    ) {}

    /**
     * Construit la liste des vulnérabilités CVE.
     */
    private List<Map<String, Object>> buildCveVulnerabilities(CveAnalysisResult cveResult) {
        List<Map<String, Object>> cves = new ArrayList<>();
        for (CveInfo cve : cveResult.vulnerabilities()) {
            Map<String, Object> cveData = new LinkedHashMap<>();
            cveData.put("cveId", cve.cveId());
            cveData.put("cvssScore", cve.cvssScore());
            cveData.put("severity", cve.severity().name());
            cveData.put("severityColor", cve.severity().getColor());
            cveData.put("description", cve.description());
            cveData.put("libraryName", cve.libraryName());
            cveData.put("gav", cve.gav());
            cveData.put("cweId", cve.cweId());
            cveData.put("reference", cve.primaryReference());
            cves.add(cveData);
        }
        return cves;
    }

    /**
     * Construit le résumé des vulnérabilités CVE.
     */
    private Map<String, Object> buildCveSummary(CveAnalysisResult cveResult) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("totalVulnerabilities", cveResult.totalCount());
        summary.put("criticalCount", cveResult.criticalCount());
        summary.put("highCount", cveResult.highCount());
        summary.put("mediumCount", cveResult.mediumCount());
        summary.put("lowCount", cveResult.lowCount());
        summary.put("maxSeverity", cveResult.maxSeverity().name());
        summary.put("maxSeverityColor", cveResult.maxSeverity().getColor());
        summary.put("riskScore", cveResult.riskScore());
        summary.put("affectedLibraries", cveResult.byLibrary().size());

        // Top librairies vulnérables
        Map<String, Integer> topLibs = new LinkedHashMap<>();
        for (var entry : cveResult.topVulnerableLibraries(5)) {
            topLibs.put(entry.getKey(), entry.getValue().size());
        }
        summary.put("topVulnerableLibraries", topLibs);

        return summary;
    }

    /**
     * Construit la liste des JARs détectés dans le projet source.
     */
    private List<Map<String, Object>> buildDetectedJars(ProjectStructure project) {
        List<Map<String, Object>> jars = new ArrayList<>();
        for (JarInfo jar : project.allJars()) {
            Map<String, Object> jarData = new LinkedHashMap<>();
            jarData.put("name", jar.name());
            jarData.put("size", jar.size());
            jarData.put("source", jar.sourceEar() != null ? "Extrait de " + jar.sourceEar() : "Répertoire lib");
            jarData.put("category", jar.category() != null ? jar.category().name() : "UNKNOWN");
            jars.add(jarData);
        }
        return jars;
    }

    /**
     * Construit la liste des packages manquants (si auto-fix a échoué).
     */
    private List<Map<String, Object>> buildMissingPackages(AutoFixResult autoFixResult) {
        List<Map<String, Object>> missing = new ArrayList<>();
        if (autoFixResult != null && autoFixResult.unresolvedErrors() != null) {
            for (MissingDependency dep : autoFixResult.unresolvedErrors()) {
                Map<String, Object> pkg = new LinkedHashMap<>();
                pkg.put("type", dep.type().name());
                pkg.put("name", dep.name());
                pkg.put("packageName", dep.getPackage());
                pkg.put("sourceFile", dep.sourceFile());
                missing.add(pkg);
            }
        }
        return missing;
    }

    /**
     * Calcule le nombre de packages manquants distincts (smart detection).
     * Regroupe les packages par leurs 3-4 premiers segments.
     */
    private int countDistinctMissingPackages(AutoFixResult autoFixResult) {
        if (autoFixResult == null || autoFixResult.unresolvedErrors() == null) {
            return 0;
        }
        Set<String> groups = new HashSet<>();
        for (MissingDependency dep : autoFixResult.unresolvedErrors()) {
            String pkg = dep.getPackage();
            if (pkg != null) {
                String[] segments = pkg.split("\\.");
                // Smart detection: 4 segments si 5+, sinon 3
                int prefixLength = segments.length >= 5 ? 4 : Math.min(3, segments.length);
                String prefix = String.join(".", Arrays.copyOf(segments, prefixLength));
                groups.add(prefix);
            }
        }
        return groups.size();
    }

    /**
     * Construit les statistiques agrégées.
     */
    private Map<String, Object> buildStatistics(ProjectStructure project, AnalysisResult analysis, AutoFixResult autoFixResult) {
        int totalDetected = project.allJars().size();
        int providedCount = autoFixResult != null && autoFixResult.addedDependencies() != null
            ? autoFixResult.addedDependencies().size() : 0;

        // Calcul cohérent avec ReportGenerator
        int resolvedCount = (int) analysis.resolved().stream()
            .filter(d -> !d.needsLocalInstall())
            .count();

        // Comptage des non résolus internes et externes
        List<DependencyInfo> unresolvedDeps = analysis.resolved().stream()
            .filter(DependencyInfo::needsLocalInstall)
            .collect(Collectors.toList());

        int unresolvedInternal = (int) unresolvedDeps.stream()
            .filter(d -> isInternalPackage(d.groupId()))
            .count();
        unresolvedInternal += (int) analysis.unresolved().stream()
            .filter(u -> {
                JarPackageAnalyzer analyzer = new JarPackageAnalyzer();
                JarPackageAnalyzer.PackageAnalysis pkg = analyzer.analyze(u.jar().path());
                return isInternalPackage(pkg.inferredGroupId());
            })
            .count();

        int unresolvedExternal = (int) unresolvedDeps.stream()
            .filter(d -> !isInternalPackage(d.groupId()))
            .count();
        unresolvedExternal += (int) analysis.unresolved().stream()
            .filter(u -> {
                JarPackageAnalyzer analyzer = new JarPackageAnalyzer();
                JarPackageAnalyzer.PackageAnalysis pkg = analyzer.analyze(u.jar().path());
                return !isInternalPackage(pkg.inferredGroupId());
            })
            .count();

        int unresolvedCount = unresolvedInternal + unresolvedExternal;
        int totalAfterDedup = resolvedCount + unresolvedCount;

        // Calcul du taux de couverture
        int missingCount = countDistinctMissingPackages(autoFixResult);
        int totalWithMissing = totalAfterDedup + providedCount + missingCount;
        int coveredLibraries = resolvedCount + unresolvedCount + providedCount;
        double coverageRate = totalWithMissing > 0 ? (coveredLibraries * 100.0 / totalWithMissing) : 100.0;

        // Ancien taux de succès (pour compatibilité)
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
        stats.put("unresolvedInternal", unresolvedInternal);
        stats.put("unresolvedExternal", unresolvedExternal);
        stats.put("providedAutoFix", providedCount);
        stats.put("autoFixEnabled", autoFixResult != null);
        stats.put("missingCount", missingCount);
        stats.put("successRate", Math.round(successRate * 10.0) / 10.0);
        stats.put("coverageRate", Math.round(coverageRate * 10.0) / 10.0);
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
            boolean needsLocal = dep.needsLocalInstall();
            boolean isInternal = isInternalPackage(dep.groupId());

            lib.put("originalName", originalName);
            lib.put("cleanedName", cleanedName);
            lib.put("groupId", dep.groupId());
            lib.put("artifactId", dep.artifactId());
            lib.put("version", dep.version());
            lib.put("scope", dep.scope().name());
            lib.put("resolutionMethod", dep.method().name());
            lib.put("size", jar != null ? jar.size() : 0);
            lib.put("sha1", jar != null ? jar.sha1() : null);
            lib.put("status", needsLocal ? "UNRESOLVED" : "RESOLVED");
            lib.put("isInternal", isInternal);
            // Nouveaux champs
            lib.put("identificationSource", getIdentificationSource(dep.method()));
            lib.put("libraryType", isInternal ? "internal" : "external");
            lib.put("isAutoFixProvided", false);
            lib.put("isLocalInstall", needsLocal);

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
            boolean isInternal = isInternalPackage(groupId);

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
            // Nouveaux champs
            lib.put("identificationSource", null);
            lib.put("libraryType", isInternal ? "internal" : "external");
            lib.put("isAutoFixProvided", false);
            lib.put("isLocalInstall", true);

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

                boolean isInternal = isInternalPackage(provided.groupId());

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
                lib.put("isInternal", isInternal);
                // Nouveaux champs
                lib.put("identificationSource", null);
                lib.put("libraryType", isInternal ? "internal" : "external");
                lib.put("isAutoFixProvided", true);
                lib.put("isLocalInstall", true);

                libraries.add(lib);
            }
        }

        return libraries;
    }
}
