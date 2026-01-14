package fr.cnam.migration.cve;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fr.cnam.migration.model.CveInfo;
import fr.cnam.migration.model.CveSeverity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Parse le rapport JSON généré par OWASP Dependency-Check.
 * Extrait les vulnérabilités CVE et les mappe vers le modèle interne.
 */
public class DependencyCheckReportParser {

    private static final Logger log = LoggerFactory.getLogger(DependencyCheckReportParser.class);
    private final ObjectMapper objectMapper;

    public DependencyCheckReportParser() {
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Parse le fichier JSON de rapport dependency-check.
     *
     * @param reportPath Chemin vers le fichier dependency-check-report.json
     * @return Liste des vulnérabilités trouvées
     */
    public List<CveInfo> parse(Path reportPath) throws IOException {
        if (!Files.exists(reportPath)) {
            log.warn("Rapport dependency-check non trouvé : {}", reportPath);
            return List.of();
        }

        log.debug("Parsing rapport OWASP : {}", reportPath);

        JsonNode root = objectMapper.readTree(reportPath.toFile());
        List<CveInfo> vulnerabilities = new ArrayList<>();

        JsonNode dependencies = root.path("dependencies");
        if (dependencies.isMissingNode() || !dependencies.isArray()) {
            log.debug("Aucune dépendance dans le rapport");
            return vulnerabilities;
        }

        for (JsonNode dependency : dependencies) {
            String fileName = dependency.path("fileName").asText("");
            String gav = extractGav(dependency);

            JsonNode vulns = dependency.path("vulnerabilities");
            if (vulns.isMissingNode() || !vulns.isArray()) {
                continue;
            }

            for (JsonNode vuln : vulns) {
                CveInfo cve = parseVulnerability(vuln, fileName, gav);
                if (cve != null) {
                    vulnerabilities.add(cve);
                }
            }
        }

        log.info("Vulnérabilités CVE trouvées : {}", vulnerabilities.size());
        return vulnerabilities;
    }

    /**
     * Extrait les coordonnées GAV d'une dépendance.
     */
    private String extractGav(JsonNode dependency) {
        // Essayer d'abord les identifiers Maven
        JsonNode identifiers = dependency.path("packages");
        if (!identifiers.isMissingNode() && identifiers.isArray()) {
            for (JsonNode pkg : identifiers) {
                String id = pkg.path("id").asText("");
                if (id.startsWith("pkg:maven/")) {
                    // Format: pkg:maven/groupId/artifactId@version
                    String maven = id.substring("pkg:maven/".length());
                    return maven.replace("/", ":").replace("@", ":");
                }
            }
        }

        // Fallback sur le nom de fichier
        String fileName = dependency.path("fileName").asText("");
        return fileName.isEmpty() ? "unknown" : fileName;
    }

    /**
     * Parse une vulnérabilité individuelle.
     */
    private CveInfo parseVulnerability(JsonNode vuln, String libraryName, String gav) {
        String cveId = vuln.path("name").asText("");
        if (cveId.isEmpty()) {
            return null;
        }

        // Extraire le score CVSS (v3 prioritaire, sinon v2)
        double cvssScore = extractCvssScore(vuln);

        // Sévérité depuis le score ou le champ severity
        CveSeverity severity;
        if (cvssScore > 0) {
            severity = CveSeverity.fromScore(cvssScore);
        } else {
            String severityStr = vuln.path("severity").asText("");
            severity = CveSeverity.fromString(severityStr);
        }

        String description = vuln.path("description").asText("");

        // Extraire CWE
        String cweId = extractCweId(vuln);

        // Extraire les références
        List<String> references = extractReferences(vuln);

        return new CveInfo(
            cveId,
            cvssScore,
            severity,
            truncateDescription(description),
            libraryName,
            gav,
            cweId,
            references
        );
    }

    /**
     * Extrait le score CVSS (préférence v3 > v2).
     */
    private double extractCvssScore(JsonNode vuln) {
        // CVSS v3
        JsonNode cvssv3 = vuln.path("cvssv3");
        if (!cvssv3.isMissingNode()) {
            double score = cvssv3.path("baseScore").asDouble(-1);
            if (score >= 0) return score;
        }

        // CVSS v2
        JsonNode cvssv2 = vuln.path("cvssv2");
        if (!cvssv2.isMissingNode()) {
            double score = cvssv2.path("score").asDouble(-1);
            if (score >= 0) return score;
        }

        // Fallback sur cvssScore direct
        return vuln.path("cvssScore").asDouble(0);
    }

    /**
     * Extrait l'identifiant CWE.
     */
    private String extractCweId(JsonNode vuln) {
        JsonNode cwes = vuln.path("cwes");
        if (!cwes.isMissingNode() && cwes.isArray() && !cwes.isEmpty()) {
            return cwes.get(0).asText("");
        }
        return vuln.path("cwe").asText(null);
    }

    /**
     * Extrait les URLs de référence.
     */
    private List<String> extractReferences(JsonNode vuln) {
        List<String> refs = new ArrayList<>();
        JsonNode references = vuln.path("references");
        if (!references.isMissingNode() && references.isArray()) {
            for (JsonNode ref : references) {
                String url = ref.path("url").asText("");
                if (!url.isEmpty()) {
                    refs.add(url);
                }
            }
        }
        return refs;
    }

    /**
     * Tronque la description si trop longue.
     */
    private String truncateDescription(String description) {
        if (description == null) return "";
        if (description.length() <= 500) return description;
        return description.substring(0, 497) + "...";
    }
}
