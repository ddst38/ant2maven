package fr.cnam.migration.model;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Une dépendance interne depuis la section [DEPENDANCES_FAB] du properties.conf.
 * Implémentation générique qui extrait le code projet dynamiquement.
 * Exemple : S8071302J;BASE_S8 ou MYPROJ010000W;BASE_MYPROJ
 */
public record InternalDependency(
    String code,           // ex: "S8071302J", "MYPROJ010000W"
    String envVariable,    // ex: "BASE_S8", "BASE_MYPROJ"
    String projectCode,    // ex: "S8", "MYPROJ" (extrait dynamiquement)
    String description     // Description dérivée
) {
    // Pattern pour extraire le code projet : lettres au début, suivies de chiffres, puis suffixe optionnel
    // Exemples : S8071302J -> S8, GMIC021104WS -> GMIC, MYPROJ010000W -> MYPROJ
    private static final Pattern CODE_PATTERN = Pattern.compile("^([A-Z]+\\d?)\\d{6,}.*$");

    // Pattern alternatif pour les codes plus courts
    private static final Pattern SHORT_CODE_PATTERN = Pattern.compile("^([A-Z]{2,})\\d+.*$");

    public static InternalDependency parse(String line) {
        String[] parts = line.split(";");
        if (parts.length != 2) {
            throw new IllegalArgumentException("Invalid dependency line: " + line);
        }
        String code = parts[0].trim();
        String envVar = parts[1].trim();
        String projectCode = extractProjectCode(code, envVar);
        String description = "Internal dependency: " + projectCode;
        return new InternalDependency(code, envVar, projectCode, description);
    }

    /**
     * Extrait le code projet dynamiquement depuis le code de dépendance ou la variable d'environnement.
     * Utilise plusieurs stratégies pour trouver le code projet.
     */
    private static String extractProjectCode(String code, String envVar) {
        // Stratégie 1 : Extraire depuis la variable d'environnement BASE_XXX
        if (envVar != null && envVar.startsWith("BASE_")) {
            return envVar.substring(5); // Supprimer le préfixe "BASE_"
        }

        // Stratégie 2 : Utiliser une regex pour extraire depuis le code
        Matcher matcher = CODE_PATTERN.matcher(code);
        if (matcher.matches()) {
            return matcher.group(1);
        }

        // Stratégie 3 : Essayer un pattern plus court
        matcher = SHORT_CODE_PATTERN.matcher(code);
        if (matcher.matches()) {
            return matcher.group(1);
        }

        // Stratégie 4 : Prendre les premières lettres jusqu'au premier chiffre
        StringBuilder sb = new StringBuilder();
        for (char c : code.toCharArray()) {
            if (Character.isLetter(c)) {
                sb.append(c);
            } else if (Character.isDigit(c) && sb.length() >= 2) {
                break;
            }
        }

        if (sb.length() >= 2) {
            return sb.toString();
        }

        // Fallback : utiliser les 4 premiers caractères ou le code complet si plus court
        return code.substring(0, Math.min(4, code.length()));
    }

    /**
     * Retourne la variable de chemin de bibliothèque attendue utilisée dans depend.xml.
     * La dérive depuis la variable d'environnement ou le code projet.
     */
    public String getLibPathVariable() {
        // Si la variable env est BASE_XXX, le chemin lib est probablement XXXJ.lib ou XXX.lib
        if (envVariable != null && envVariable.startsWith("BASE_")) {
            String base = envVariable.substring(5);
            // Patterns courants : BASE_S8 -> S8J.lib, BASE_WS -> WS.lib
            if (code.endsWith("J") || code.contains("_J")) {
                return base + "J.lib";
            } else if (code.endsWith("WS") || code.contains("WS")) {
                return base + "WS.lib";
            } else if (code.endsWith("H") || code.contains("_H")) {
                return base + "H.lib";
            } else if (code.endsWith("W") || code.contains("_W")) {
                return base + "W.lib";
            }
            return base + ".lib";
        }
        return projectCode + ".lib";
    }

    /**
     * Convertit en coordonnées Maven pour le repository interne.
     * Utilise le package de base par défaut (fr.cnamts).
     */
    public MavenCoordinate toMavenCoordinate() {
        return toMavenCoordinate("fr.cnamts");
    }

    /**
     * Convertit en coordonnées Maven pour le repository interne.
     * @param basePackage Le package de base à utiliser (ex: "fr.cnamts" ou "fr.cnam")
     */
    public MavenCoordinate toMavenCoordinate(String basePackage) {
        String groupId = basePackage + "." + projectCode.toLowerCase();
        String artifactId = projectCode.toLowerCase() + "-lib";
        String version = code; // Utiliser le code complet comme identifiant de version
        return new MavenCoordinate(groupId, artifactId, version);
    }
}
