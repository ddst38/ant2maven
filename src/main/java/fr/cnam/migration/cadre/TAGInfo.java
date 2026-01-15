package fr.cnam.migration.cadre;

/**
 * Informations extraites d'un tag de dependance cadre.
 * Format du tag : {codeModule}{versionXXYYZZ}{typeLivrable}
 * Exemple : NOOC040000J -> codeModule=NOOC, versionModule=040000, typeLivrable=J
 */
public record TAGInfo(
    String codeModule,      // ex: "NOOC", "WW", "S8"
    String versionModule,   // ex: "040000", "090102"
    String typeLivrable     // ex: "J", "H", "WS"
) {
    /**
     * Retourne le nom de l'artefact pour la recherche.
     * Format : {codeModule}{typeLivrable}-{version}
     * Exemple : noocj-4.0.0
     */
    public String getArtifactSearchName() {
        return (codeModule + typeLivrable).toLowerCase() + "-" + getMavenVersion();
    }

    /**
     * Convertit la version XXYYZZ en format Maven X.Y.Z
     */
    public String getMavenVersion() {
        return transformVersion(versionModule);
    }

    /**
     * Transforme une version au format XXYYZZ en X.Y.Z
     * Exemples :
     *   040000 -> 4.0.0
     *   010106 -> 1.1.6
     *   190208 -> 19.2.8
     */
    public static String transformVersion(String version) {
        if (version == null || version.length() != 6) {
            return version;
        }
        try {
            int major = Integer.parseInt(version.substring(0, 2));
            int minor = Integer.parseInt(version.substring(2, 4));
            int patch = Integer.parseInt(version.substring(4, 6));
            return major + "." + minor + "." + patch;
        } catch (NumberFormatException e) {
            return version;
        }
    }
}
