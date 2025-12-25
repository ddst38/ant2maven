package fr.cnam.migration.model;

import java.nio.file.Path;
import java.util.regex.Pattern;

/**
 * Information about a JAR file discovered in the project.
 */
public record JarInfo(
    Path path,
    String name,
    long size,
    String sha1,
    JarCategory category
) {
    public enum JarCategory {
        MAIN,      // Main compilation dependency
        TEST,      // Test-only dependency
        PROVIDED,  // Provided by container (servlet-api, etc.)
        RUNTIME,   // Runtime only
        UNKNOWN    // Not yet categorized
    }

    // Patterns for internal artifact detection
    private static final Pattern DEPFAB_PATTERN = Pattern.compile("^DEPFAB\\..*\\.jar$");
    private static final Pattern PROJECT_CODE_PATTERN = Pattern.compile("^[A-Z]+_[A-Z]\\..*\\.jar$");
    private static final Pattern CNAM_PROJECT_PATTERN = Pattern.compile("^[A-Z]+\\d{6,}.*\\.jar$");
    private static final Pattern SERVICE_PATTERN = Pattern.compile("^Service[A-Z]+_.*\\.jar$");
    private static final Pattern JK_SOCLE_PATTERN = Pattern.compile("^jk-socle-.*\\.jar$");
    private static final Pattern S8_PATTERN = Pattern.compile("^s8[a-z]?-.*\\.jar$");

    public static JarInfo of(Path path, long size, String sha1) {
        return new JarInfo(path, path.getFileName().toString(), size, sha1, JarCategory.UNKNOWN);
    }

    public JarInfo withCategory(JarCategory newCategory) {
        return new JarInfo(path, name, size, sha1, newCategory);
    }

    public boolean isSourceJar() {
        return name.contains("-sources");
    }

    /**
     * Checks if this JAR appears to be an internal/proprietary artifact.
     * Uses generic patterns that work across different CNAM projects.
     */
    public boolean isInternalArtifact() {
        // DEPFAB.* pattern (most common internal pattern)
        if (DEPFAB_PATTERN.matcher(name).matches()) {
            return true;
        }

        // XXX_Y.something pattern (e.g., S8_J.secJava.jar, BIMC_H.core.jar)
        if (PROJECT_CODE_PATTERN.matcher(name).matches()) {
            return true;
        }

        // CNAM project codes (e.g., SOCA010000J-1.0.0.jar)
        if (CNAM_PROJECT_PATTERN.matcher(name).matches()) {
            return true;
        }

        // Service stubs (e.g., ServicePS_3.0.client.jar)
        if (SERVICE_PATTERN.matcher(name).matches()) {
            return true;
        }

        // jk-socle libraries
        if (JK_SOCLE_PATTERN.matcher(name).matches()) {
            return true;
        }

        // s8 libraries (e.g., s8sp-2.0.3.jar, s8h-chiffrementUtil-1.2.0.jar)
        if (S8_PATTERN.matcher(name).matches()) {
            return true;
        }

        // Specific well-known internal JARs (keep for backwards compatibility)
        // These are in known-artifacts.yaml but we also check here
        return name.equals("tracesCaster.jar") ||
               name.equals("biblicnam.jar") ||
               name.equals("archirfe.jar") ||
               name.equals("classes12.jar");  // Oracle JDBC not on Maven Central
    }
}
