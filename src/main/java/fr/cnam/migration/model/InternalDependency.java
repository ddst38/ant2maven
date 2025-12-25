package fr.cnam.migration.model;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * An internal dependency from properties.conf [DEPENDANCES_FAB] section.
 * Generic implementation that extracts project code dynamically.
 * Example: S8071302J;BASE_S8 or MYPROJ010000W;BASE_MYPROJ
 */
public record InternalDependency(
    String code,           // e.g., "S8071302J", "MYPROJ010000W"
    String envVariable,    // e.g., "BASE_S8", "BASE_MYPROJ"
    String projectCode,    // e.g., "S8", "MYPROJ" (extracted dynamically)
    String description     // Derived description
) {
    // Pattern to extract project code: letters at the start, followed by digits, then optional suffix
    // Examples: S8071302J -> S8, GMIC021104WS -> GMIC, MYPROJ010000W -> MYPROJ
    private static final Pattern CODE_PATTERN = Pattern.compile("^([A-Z]+\\d?)\\d{6,}.*$");

    // Alternative pattern for shorter codes
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
     * Extracts project code dynamically from the dependency code or environment variable.
     * Uses multiple strategies to find the project code.
     */
    private static String extractProjectCode(String code, String envVar) {
        // Strategy 1: Extract from BASE_XXX environment variable
        if (envVar != null && envVar.startsWith("BASE_")) {
            return envVar.substring(5); // Remove "BASE_" prefix
        }

        // Strategy 2: Use regex to extract from code
        Matcher matcher = CODE_PATTERN.matcher(code);
        if (matcher.matches()) {
            return matcher.group(1);
        }

        // Strategy 3: Try shorter pattern
        matcher = SHORT_CODE_PATTERN.matcher(code);
        if (matcher.matches()) {
            return matcher.group(1);
        }

        // Strategy 4: Take first letters until we hit a digit
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

        // Fallback: use first 4 characters or full code if shorter
        return code.substring(0, Math.min(4, code.length()));
    }

    /**
     * Returns the expected library path variable used in depend.xml.
     * Derives it from the environment variable or project code.
     */
    public String getLibPathVariable() {
        // If env variable is BASE_XXX, the lib path is likely XXXJ.lib or XXX.lib
        if (envVariable != null && envVariable.startsWith("BASE_")) {
            String base = envVariable.substring(5);
            // Common patterns: BASE_S8 -> S8J.lib, BASE_WS -> WS.lib
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
     * Convert to a Maven coordinate for the internal repository.
     * Uses the default base package (fr.cnamts).
     */
    public MavenCoordinate toMavenCoordinate() {
        return toMavenCoordinate("fr.cnamts");
    }

    /**
     * Convert to a Maven coordinate for the internal repository.
     * @param basePackage The base package to use (e.g., "fr.cnamts" or "fr.cnam")
     */
    public MavenCoordinate toMavenCoordinate(String basePackage) {
        String groupId = basePackage + "." + projectCode.toLowerCase();
        String artifactId = projectCode.toLowerCase() + "-lib";
        String version = code; // Use the full code as version identifier
        return new MavenCoordinate(groupId, artifactId, version);
    }
}
