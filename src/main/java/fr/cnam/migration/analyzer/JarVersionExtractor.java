package fr.cnam.migration.analyzer;

import fr.cnam.migration.model.JarInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.*;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Comprehensive JAR version extractor that uses multiple strategies to determine
 * the version of a JAR file.
 *
 * Strategies (in order of preference):
 * 1. Filename patterns (e.g., artifact-1.2.3.jar)
 * 2. MANIFEST.MF attributes (Implementation-Version, Bundle-Version, etc.)
 * 3. META-INF/maven/.../pom.properties
 * 4. version.properties or similar files inside the JAR
 * 5. Fall back to SHA-based versioning for unversioned JARs
 */
public class JarVersionExtractor {

    private static final Logger log = LoggerFactory.getLogger(JarVersionExtractor.class);

    // Filename patterns for version extraction
    private static final List<Pattern> VERSION_PATTERNS = List.of(
        // Standard Maven pattern: artifact-1.2.3.jar or artifact-1.2.3-classifier.jar
        Pattern.compile("^(.+?)-(\\d+\\.\\d+(?:\\.\\d+)?(?:[.-][A-Za-z0-9]+)*)\\.jar$"),
        // DEPFAB pattern with version: DEPFAB.XXX_Y-1.0.16-suffix.jar
        Pattern.compile("^DEPFAB\\.([A-Z0-9_]+)-(\\d+\\.\\d+\\.\\d+)-.+\\.jar$"),
        // Service pattern: ServiceXXX_1.0.client.jar
        Pattern.compile("^(Service[A-Z]+)_(\\d+\\.\\d+)\\.\\w+\\.jar$"),
        // jk-socle pattern: jk-socle-xxx-1.2.5.jar
        Pattern.compile("^(jk-socle-[a-z-]+)-(\\d+\\.\\d+\\.\\d+)\\.jar$"),
        // s8 pattern: s8h-xxx-1.2.0.jar
        Pattern.compile("^(s8[a-z]?-[a-zA-Z-]+)-(\\d+\\.\\d+\\.\\d+)\\.jar$"),
        // CNAM project pattern: SOCA010000J-1.0.0-suffix.jar
        Pattern.compile("^([A-Z]+\\d+[A-Z])-(\\d+\\.\\d+\\.\\d+)-.+\\.jar$")
    );

    // MANIFEST.MF attributes that may contain version information
    private static final List<String> MANIFEST_VERSION_ATTRS = List.of(
        "Implementation-Version",
        "Bundle-Version",
        "Specification-Version",
        "Version",
        "Manifest-Version"  // Last resort, usually just "1.0"
    );

    /**
     * Result of version extraction.
     */
    public record VersionInfo(
        String version,
        String artifactName,
        VersionSource source,
        boolean isShaBasedVersion
    ) {
        public static VersionInfo fromFilename(String artifactName, String version) {
            return new VersionInfo(version, artifactName, VersionSource.FILENAME, false);
        }

        public static VersionInfo fromManifest(String artifactName, String version) {
            return new VersionInfo(version, artifactName, VersionSource.MANIFEST, false);
        }

        public static VersionInfo fromPomProperties(String artifactName, String version) {
            return new VersionInfo(version, artifactName, VersionSource.POM_PROPERTIES, false);
        }

        public static VersionInfo fromVersionFile(String artifactName, String version) {
            return new VersionInfo(version, artifactName, VersionSource.VERSION_FILE, false);
        }

        public static VersionInfo fromSha(String artifactName, String sha) {
            // Use first 8 chars of SHA for readability
            String shortSha = sha.length() > 8 ? sha.substring(0, 8) : sha;
            return new VersionInfo("SHA-" + shortSha, artifactName, VersionSource.SHA_HASH, true);
        }

        public static VersionInfo unknown(String artifactName) {
            return new VersionInfo("UNKNOWN", artifactName, VersionSource.UNKNOWN, false);
        }
    }

    /**
     * Source of version information.
     */
    public enum VersionSource {
        FILENAME("Extracted from filename"),
        MANIFEST("Extracted from MANIFEST.MF"),
        POM_PROPERTIES("Extracted from pom.properties"),
        VERSION_FILE("Extracted from version file"),
        SHA_HASH("Generated from SHA-1 hash"),
        UNKNOWN("Could not determine version");

        private final String description;

        VersionSource(String description) {
            this.description = description;
        }

        public String getDescription() {
            return description;
        }
    }

    /**
     * Extracts version information from a JAR file using all available strategies.
     */
    public VersionInfo extractVersion(JarInfo jar) {
        return extractVersion(jar.path(), jar.name(), jar.sha1());
    }

    /**
     * Extracts version information from a JAR file using all available strategies.
     */
    public VersionInfo extractVersion(Path jarPath, String jarName, String sha1) {
        // Strategy 1: Try filename patterns
        VersionInfo fromFilename = extractFromFilename(jarName);
        if (fromFilename != null && isValidVersion(fromFilename.version())) {
            log.debug("Version from filename: {} -> {}", jarName, fromFilename.version());
            return fromFilename;
        }

        // Strategy 2-4: Try JAR content analysis
        try (JarFile jarFile = new JarFile(jarPath.toFile())) {
            // Strategy 2: MANIFEST.MF
            VersionInfo fromManifest = extractFromManifest(jarFile, jarName);
            if (fromManifest != null && isValidVersion(fromManifest.version())) {
                log.debug("Version from manifest: {} -> {}", jarName, fromManifest.version());
                return fromManifest;
            }

            // Strategy 3: pom.properties
            VersionInfo fromPom = extractFromPomProperties(jarFile, jarName);
            if (fromPom != null && isValidVersion(fromPom.version())) {
                log.debug("Version from pom.properties: {} -> {}", jarName, fromPom.version());
                return fromPom;
            }

            // Strategy 4: version files
            VersionInfo fromVersionFile = extractFromVersionFiles(jarFile, jarName);
            if (fromVersionFile != null && isValidVersion(fromVersionFile.version())) {
                log.debug("Version from version file: {} -> {}", jarName, fromVersionFile.version());
                return fromVersionFile;
            }

        } catch (IOException e) {
            log.warn("Could not read JAR file {}: {}", jarPath, e.getMessage());
        }

        // Strategy 5: Fall back to SHA-based versioning
        if (sha1 != null && !sha1.isBlank()) {
            log.debug("Using SHA-based version for {}: SHA-{}", jarName, sha1.substring(0, 8));
            return VersionInfo.fromSha(extractArtifactName(jarName), sha1);
        }

        // No version found
        log.warn("Could not determine version for: {}", jarName);
        return VersionInfo.unknown(extractArtifactName(jarName));
    }

    /**
     * Extracts version from filename using regex patterns.
     */
    private VersionInfo extractFromFilename(String jarName) {
        for (Pattern pattern : VERSION_PATTERNS) {
            Matcher matcher = pattern.matcher(jarName);
            if (matcher.matches()) {
                String artifactName = matcher.group(1);
                String version = matcher.group(2);
                return VersionInfo.fromFilename(artifactName, version);
            }
        }
        return null;
    }

    /**
     * Extracts version from MANIFEST.MF attributes.
     */
    private VersionInfo extractFromManifest(JarFile jarFile, String jarName) throws IOException {
        Manifest manifest = jarFile.getManifest();
        if (manifest == null) {
            return null;
        }

        Attributes mainAttrs = manifest.getMainAttributes();
        String artifactName = extractArtifactName(jarName);

        // Try to get artifact name from manifest
        String implTitle = mainAttrs.getValue("Implementation-Title");
        String bundleName = mainAttrs.getValue("Bundle-Name");
        String symbolicName = mainAttrs.getValue("Bundle-SymbolicName");

        if (implTitle != null && !implTitle.isBlank()) {
            artifactName = implTitle;
        } else if (bundleName != null && !bundleName.isBlank()) {
            artifactName = bundleName;
        } else if (symbolicName != null && !symbolicName.isBlank()) {
            artifactName = symbolicName;
        }

        // Try each version attribute
        for (String attr : MANIFEST_VERSION_ATTRS) {
            String version = mainAttrs.getValue(attr);
            if (version != null && !version.isBlank() && isValidVersion(version)) {
                return VersionInfo.fromManifest(artifactName, cleanVersion(version));
            }
        }

        return null;
    }

    /**
     * Extracts version from META-INF/maven/.../pom.properties.
     */
    private VersionInfo extractFromPomProperties(JarFile jarFile, String jarName) throws IOException {
        Enumeration<JarEntry> entries = jarFile.entries();

        while (entries.hasMoreElements()) {
            JarEntry entry = entries.nextElement();
            String entryName = entry.getName();

            if (entryName.startsWith("META-INF/maven/") && entryName.endsWith("/pom.properties")) {
                try (InputStream is = jarFile.getInputStream(entry)) {
                    Properties props = new Properties();
                    props.load(is);

                    String version = props.getProperty("version");
                    String artifactId = props.getProperty("artifactId");

                    if (version != null && !version.isBlank()) {
                        String name = artifactId != null ? artifactId : extractArtifactName(jarName);
                        return VersionInfo.fromPomProperties(name, version);
                    }
                }
            }
        }

        return null;
    }

    /**
     * Extracts version from common version files inside the JAR.
     */
    private VersionInfo extractFromVersionFiles(JarFile jarFile, String jarName) throws IOException {
        // Common version file patterns
        List<String> versionFilePatterns = List.of(
            "version.properties",
            "version.txt",
            "VERSION",
            "build.properties",
            "build-info.properties"
        );

        Enumeration<JarEntry> entries = jarFile.entries();

        while (entries.hasMoreElements()) {
            JarEntry entry = entries.nextElement();
            String entryName = entry.getName();

            // Check if this is a version file
            for (String pattern : versionFilePatterns) {
                if (entryName.endsWith(pattern) || entryName.equals(pattern)) {
                    try (InputStream is = jarFile.getInputStream(entry)) {
                        String version = extractVersionFromStream(is, entryName);
                        if (version != null && isValidVersion(version)) {
                            return VersionInfo.fromVersionFile(extractArtifactName(jarName), version);
                        }
                    }
                }
            }
        }

        return null;
    }

    /**
     * Extracts version from a properties or text file stream.
     */
    private String extractVersionFromStream(InputStream is, String fileName) throws IOException {
        if (fileName.endsWith(".properties")) {
            Properties props = new Properties();
            props.load(is);

            // Try common property names
            for (String key : List.of("version", "Version", "VERSION",
                                       "build.version", "project.version",
                                       "implementation.version")) {
                String value = props.getProperty(key);
                if (value != null && !value.isBlank()) {
                    return cleanVersion(value);
                }
            }
        } else {
            // Plain text file - read first line
            String content = new String(is.readAllBytes()).trim();
            if (!content.isEmpty()) {
                // Try to extract version pattern from content
                Matcher versionMatcher = Pattern.compile("(\\d+\\.\\d+(?:\\.\\d+)?(?:[.-][A-Za-z0-9]+)?)").matcher(content);
                if (versionMatcher.find()) {
                    return versionMatcher.group(1);
                }
            }
        }

        return null;
    }

    /**
     * Extracts artifact name from JAR filename.
     */
    private String extractArtifactName(String jarName) {
        // Remove .jar extension
        String name = jarName.replaceAll("\\.jar$", "");

        // Try to remove version suffix
        for (Pattern pattern : VERSION_PATTERNS) {
            Matcher matcher = pattern.matcher(jarName);
            if (matcher.matches()) {
                return matcher.group(1);
            }
        }

        // Remove common suffixes
        name = name.replaceAll("-\\d+\\.\\d+.*$", "");

        return name;
    }

    /**
     * Checks if a version string is valid (not just "1.0" or similar generic values).
     */
    private boolean isValidVersion(String version) {
        if (version == null || version.isBlank()) {
            return false;
        }

        // Filter out generic/meaningless versions
        Set<String> genericVersions = Set.of("1.0", "1.0.0", "0.0.0", "0.0.1");
        if (genericVersions.contains(version.trim())) {
            return false;
        }

        // Must contain at least one digit
        return version.matches(".*\\d.*");
    }

    /**
     * Cleans up a version string.
     */
    private String cleanVersion(String version) {
        if (version == null) {
            return null;
        }

        // Remove common prefixes
        version = version.replaceAll("^v", "");
        version = version.replaceAll("^version[=:]?\\s*", "");

        // Trim whitespace
        return version.trim();
    }

    /**
     * Generates a unique artifact identifier using SHA for unversioned JARs.
     * This prevents version collisions when different projects have
     * different versions of the same-named JAR.
     */
    public String generateShaBasedArtifactId(String originalName, String sha1) {
        String baseName = extractArtifactName(originalName);
        String shortSha = sha1.length() > 8 ? sha1.substring(0, 8) : sha1;
        return baseName + "-" + shortSha;
    }
}
