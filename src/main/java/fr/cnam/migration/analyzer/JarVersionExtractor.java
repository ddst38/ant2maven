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
 * Extracteur de version JAR complet qui utilise plusieurs stratégies pour déterminer
 * la version d'un fichier JAR.
 *
 * Stratégies (par ordre de préférence) :
 * 1. Patterns de nom de fichier (ex: artifact-1.2.3.jar)
 * 2. Attributs MANIFEST.MF (Implementation-Version, Bundle-Version, etc.)
 * 3. META-INF/maven/.../pom.properties
 * 4. version.properties ou fichiers similaires dans le JAR
 * 5. Fall back vers versionnement basé sur SHA pour les JARs non versionnés
 */
public class JarVersionExtractor {

    private static final Logger log = LoggerFactory.getLogger(JarVersionExtractor.class);

    // Patterns de nom de fichier pour extraction de version
    private static final List<Pattern> VERSION_PATTERNS = List.of(
        // Pattern Maven standard : artifact-1.2.3.jar ou artifact-1.2.3-classifier.jar
        Pattern.compile("^(.+?)-(\\d+\\.\\d+(?:\\.\\d+)?(?:[.-][A-Za-z0-9]+)*)\\.jar$"),
        // Pattern DEPFAB avec version : DEPFAB.XXX_Y-1.0.16-suffix.jar
        Pattern.compile("^DEPFAB\\.([A-Z0-9_]+)-(\\d+\\.\\d+\\.\\d+)-.+\\.jar$"),
        // Pattern Service : ServiceXXX_1.0.client.jar
        Pattern.compile("^(Service[A-Z]+)_(\\d+\\.\\d+)\\.\\w+\\.jar$"),
        // Pattern jk-socle : jk-socle-xxx-1.2.5.jar
        Pattern.compile("^(jk-socle-[a-z-]+)-(\\d+\\.\\d+\\.\\d+)\\.jar$"),
        // Pattern s8 : s8h-xxx-1.2.0.jar
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
        "Manifest-Version"  // Dernier recours, généralement juste "1.0"
    );

    /**
     * Résultat de l'extraction de version.
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
            // Utiliser les 8 premiers caractères du SHA pour la lisibilité
            String shortSha = sha.length() > 8 ? sha.substring(0, 8) : sha;
            return new VersionInfo("SHA-" + shortSha, artifactName, VersionSource.SHA_HASH, true);
        }

        public static VersionInfo unknown(String artifactName) {
            return new VersionInfo("UNKNOWN", artifactName, VersionSource.UNKNOWN, false);
        }
    }

    /**
     * Source de l'information de version.
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
     * Extrait les informations de version d'un fichier JAR en utilisant toutes les stratégies disponibles.
     */
    public VersionInfo extractVersion(JarInfo jar) {
        return extractVersion(jar.path(), jar.name(), jar.sha1());
    }

    /**
     * Extrait les informations de version d'un fichier JAR en utilisant toutes les stratégies disponibles.
     */
    public VersionInfo extractVersion(Path jarPath, String jarName, String sha1) {
        // Stratégie 1 : Essayer les patterns de nom de fichier
        VersionInfo fromFilename = extractFromFilename(jarName);
        if (fromFilename != null && isValidVersion(fromFilename.version())) {
            log.debug("Version from filename: {} -> {}", jarName, fromFilename.version());
            return fromFilename;
        }

        // Stratégies 2-4 : Essayer l'analyse du contenu JAR
        try (JarFile jarFile = new JarFile(jarPath.toFile())) {
            // Stratégie 2 : MANIFEST.MF
            VersionInfo fromManifest = extractFromManifest(jarFile, jarName);
            if (fromManifest != null && isValidVersion(fromManifest.version())) {
                log.debug("Version from manifest: {} -> {}", jarName, fromManifest.version());
                return fromManifest;
            }

            // Stratégie 3 : pom.properties
            VersionInfo fromPom = extractFromPomProperties(jarFile, jarName);
            if (fromPom != null && isValidVersion(fromPom.version())) {
                log.debug("Version from pom.properties: {} -> {}", jarName, fromPom.version());
                return fromPom;
            }

            // Stratégie 4 : fichiers de version
            VersionInfo fromVersionFile = extractFromVersionFiles(jarFile, jarName);
            if (fromVersionFile != null && isValidVersion(fromVersionFile.version())) {
                log.debug("Version from version file: {} -> {}", jarName, fromVersionFile.version());
                return fromVersionFile;
            }

        } catch (IOException e) {
            log.warn("Could not read JAR file {}: {}", jarPath, e.getMessage());
        }

        // Stratégie 5 : Repli sur le versionnement basé SHA
        if (sha1 != null && !sha1.isBlank()) {
            log.debug("Using SHA-based version for {}: SHA-{}", jarName, sha1.substring(0, 8));
            return VersionInfo.fromSha(extractArtifactName(jarName), sha1);
        }

        // Aucune version trouvée
        log.warn("Could not determine version for: {}", jarName);
        return VersionInfo.unknown(extractArtifactName(jarName));
    }

    /**
     * Extrait la version depuis le nom de fichier en utilisant des patterns regex.
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
     * Extrait la version depuis les attributs MANIFEST.MF.
     */
    private VersionInfo extractFromManifest(JarFile jarFile, String jarName) throws IOException {
        Manifest manifest = jarFile.getManifest();
        if (manifest == null) {
            return null;
        }

        Attributes mainAttrs = manifest.getMainAttributes();
        String artifactName = extractArtifactName(jarName);

        // Essayer de récupérer le nom de l'artefact depuis le manifest
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

        // Essayer chaque attribut de version
        for (String attr : MANIFEST_VERSION_ATTRS) {
            String version = mainAttrs.getValue(attr);
            if (version != null && !version.isBlank() && isValidVersion(version)) {
                return VersionInfo.fromManifest(artifactName, cleanVersion(version));
            }
        }

        return null;
    }

    /**
     * Extrait la version depuis META-INF/maven/.../pom.properties.
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
     * Extrait la version depuis les fichiers de version courants à l'intérieur du JAR.
     */
    private VersionInfo extractFromVersionFiles(JarFile jarFile, String jarName) throws IOException {
        // Patterns de fichiers de version courants
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

            // Vérifier si c'est un fichier de version
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
     * Extrait la version depuis un flux de fichier properties ou texte.
     */
    private String extractVersionFromStream(InputStream is, String fileName) throws IOException {
        if (fileName.endsWith(".properties")) {
            Properties props = new Properties();
            props.load(is);

            // Essayer les noms de propriétés courants
            for (String key : List.of("version", "Version", "VERSION",
                                       "build.version", "project.version",
                                       "implementation.version")) {
                String value = props.getProperty(key);
                if (value != null && !value.isBlank()) {
                    return cleanVersion(value);
                }
            }
        } else {
            // Fichier texte brut - lire la première ligne
            String content = new String(is.readAllBytes()).trim();
            if (!content.isEmpty()) {
                // Essayer d'extraire le pattern de version du contenu
                Matcher versionMatcher = Pattern.compile("(\\d+\\.\\d+(?:\\.\\d+)?(?:[.-][A-Za-z0-9]+)?)").matcher(content);
                if (versionMatcher.find()) {
                    return versionMatcher.group(1);
                }
            }
        }

        return null;
    }

    /**
     * Extrait le nom de l'artefact depuis le nom de fichier JAR.
     */
    private String extractArtifactName(String jarName) {
        // Supprimer l'extension .jar
        String name = jarName.replaceAll("\\.jar$", "");

        // Essayer de supprimer le suffixe de version
        for (Pattern pattern : VERSION_PATTERNS) {
            Matcher matcher = pattern.matcher(jarName);
            if (matcher.matches()) {
                return matcher.group(1);
            }
        }

        // Supprimer les suffixes courants
        name = name.replaceAll("-\\d+\\.\\d+.*$", "");

        return name;
    }

    /**
     * Vérifie si une chaîne de version est valide (pas juste "1.0" ou des valeurs génériques similaires).
     */
    private boolean isValidVersion(String version) {
        if (version == null || version.isBlank()) {
            return false;
        }

        // Filtrer les versions génériques/sans signification
        Set<String> genericVersions = Set.of("1.0", "1.0.0", "0.0.0", "0.0.1");
        if (genericVersions.contains(version.trim())) {
            return false;
        }

        // Doit contenir au moins un chiffre
        return version.matches(".*\\d.*");
    }

    /**
     * Nettoie une chaîne de version.
     */
    private String cleanVersion(String version) {
        if (version == null) {
            return null;
        }

        // Supprimer les préfixes courants
        version = version.replaceAll("^v", "");
        version = version.replaceAll("^version[=:]?\\s*", "");

        // Supprimer les espaces
        return version.trim();
    }

    /**
     * Génère un identifiant d'artefact unique en utilisant le SHA pour les JARs sans version.
     * Cela évite les collisions de version quand différents projets ont
     * différentes versions du même JAR.
     */
    public String generateShaBasedArtifactId(String originalName, String sha1) {
        String baseName = extractArtifactName(originalName);
        String shortSha = sha1.length() > 8 ? sha1.substring(0, 8) : sha1;
        return baseName + "-" + shortSha;
    }
}
