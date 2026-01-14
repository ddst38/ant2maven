package fr.cnam.migration.model;

import java.util.List;

/**
 * Représente une vulnérabilité CVE détectée dans une dépendance.
 */
public record CveInfo(
    String cveId,           // Identifiant CVE (ex: CVE-2024-12345)
    double cvssScore,       // Score CVSS v3 (0.0 - 10.0)
    CveSeverity severity,   // Niveau de sévérité calculé
    String description,     // Description de la vulnérabilité
    String libraryName,     // Nom du JAR affecté
    String gav,             // Coordonnées Maven (groupId:artifactId:version)
    String cweId,           // Identifiant CWE (ex: CWE-79)
    List<String> references // URLs de référence (NVD, CVE, etc.)
) {
    /**
     * Construit un CveInfo avec calcul automatique de la sévérité.
     */
    public static CveInfo of(String cveId, double cvssScore, String description,
                             String libraryName, String gav, String cweId, List<String> references) {
        return new CveInfo(
            cveId,
            cvssScore,
            CveSeverity.fromScore(cvssScore),
            description,
            libraryName,
            gav,
            cweId,
            references
        );
    }

    /**
     * URL vers la page NVD pour cette CVE.
     */
    public String nvdUrl() {
        return "https://nvd.nist.gov/vuln/detail/" + cveId;
    }

    /**
     * Retourne la première référence ou l'URL NVD par défaut.
     */
    public String primaryReference() {
        if (references != null && !references.isEmpty()) {
            return references.getFirst();
        }
        return nvdUrl();
    }
}
