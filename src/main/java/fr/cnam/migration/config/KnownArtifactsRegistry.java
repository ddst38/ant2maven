package fr.cnam.migration.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import fr.cnam.migration.model.MavenCoordinate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Registre des mappings JAR vers coordonnées Maven connus.
 * Charge depuis known-artifacts.yaml embarqué et optionnellement depuis un fichier externe.
 * Utilise le SHA1 du JAR comme clé pour garantir une correspondance exacte.
 *
 * Les nouvelles résolutions (via Maven Central ou Artifactory) sont automatiquement
 * enregistrées et sauvegardées pour les prochaines exécutions.
 */
public class KnownArtifactsRegistry {

    private static final Logger log = LoggerFactory.getLogger(KnownArtifactsRegistry.class);
    private static final ObjectMapper YAML_MAPPER = new ObjectMapper(new YAMLFactory());

    // Map: SHA1 -> MavenCoordinate (toutes les entrées)
    private final Map<String, MavenCoordinate> artifactsBySha1 = new HashMap<>();

    // Map: SHA1 -> ArtifactEntry (nouvelles entrées à sauvegarder)
    private final Map<String, ArtifactEntry> newEntries = new LinkedHashMap<>();

    // Chemin du fichier YAML externe (pour sauvegarde)
    private Path yamlFilePath;

    public KnownArtifactsRegistry() {
        loadEmbeddedArtifacts();
    }

    public KnownArtifactsRegistry(Path additionalFile) {
        this();
        this.yamlFilePath = additionalFile;
        if (additionalFile != null && Files.exists(additionalFile)) {
            loadExternalArtifacts(additionalFile);
        }
    }

    private void loadEmbeddedArtifacts() {
        try (InputStream is = getClass().getResourceAsStream("/known-artifacts.yaml")) {
            if (is != null) {
                KnownArtifactsConfig config = YAML_MAPPER.readValue(is, KnownArtifactsConfig.class);
                if (config != null && config.knownArtifacts() != null) {
                    config.knownArtifacts().forEach((sha1, artifact) -> {
                        artifactsBySha1.put(sha1.toLowerCase(), new MavenCoordinate(
                            artifact.groupId(),
                            artifact.artifactId(),
                            artifact.version(),
                            artifact.classifier()
                        ));
                    });
                }
                log.info("Loaded {} known artifacts from embedded configuration", artifactsBySha1.size());
            }
        } catch (IOException e) {
            log.warn("Failed to load embedded known-artifacts.yaml: {}", e.getMessage());
        }
    }

    private void loadExternalArtifacts(Path file) {
        try {
            KnownArtifactsConfig config = YAML_MAPPER.readValue(file.toFile(), KnownArtifactsConfig.class);
            if (config != null && config.knownArtifacts() != null) {
                int count = 0;
                for (Map.Entry<String, ArtifactMapping> entry : config.knownArtifacts().entrySet()) {
                    ArtifactMapping artifact = entry.getValue();
                    artifactsBySha1.put(entry.getKey().toLowerCase(), new MavenCoordinate(
                        artifact.groupId(),
                        artifact.artifactId(),
                        artifact.version(),
                        artifact.classifier()
                    ));
                    count++;
                }
                log.info("Loaded {} additional known artifacts from {}", count, file);
            }
        } catch (IOException e) {
            log.warn("Failed to load external known-artifacts file {}: {}", file, e.getMessage());
        }
    }

    /**
     * Recherche un JAR par son SHA1 dans le registre.
     * @param sha1 Le checksum SHA1 du fichier JAR
     * @return Les coordonnées Maven si trouvées
     */
    public Optional<MavenCoordinate> lookupBySha1(String sha1) {
        if (sha1 == null || sha1.isEmpty()) {
            return Optional.empty();
        }
        return Optional.ofNullable(artifactsBySha1.get(sha1.toLowerCase()));
    }

    /**
     * Ajoute une nouvelle entrée au registre.
     * L'entrée sera sauvegardée lors de l'appel à saveNewEntries().
     *
     * @param sha1 Le checksum SHA1 du JAR
     * @param coord Les coordonnées Maven résolues
     * @param jarName Le nom original du fichier JAR (pour documentation)
     */
    public void addEntry(String sha1, MavenCoordinate coord, String jarName) {
        if (sha1 == null || coord == null) {
            return;
        }
        String sha1Lower = sha1.toLowerCase();

        // Ne pas ajouter si déjà présent
        if (artifactsBySha1.containsKey(sha1Lower)) {
            return;
        }

        artifactsBySha1.put(sha1Lower, coord);
        newEntries.put(sha1Lower, new ArtifactEntry(sha1Lower, coord, jarName));
        log.debug("Added new entry: {} ({}) -> {}", jarName, sha1Lower.substring(0, 8), coord.toGav());
    }

    /**
     * Sauvegarde les nouvelles entrées dans le fichier known-artifacts.yaml.
     * @return Le nombre d'entrées sauvegardées
     */
    public int saveNewEntries() {
        if (newEntries.isEmpty()) {
            return 0;
        }

        // Déterminer le chemin de sauvegarde
        Path savePath = yamlFilePath;
        if (savePath == null) {
            // Utiliser le fichier embarqué dans src/main/resources
            savePath = Paths.get("src/main/resources/known-artifacts.yaml");
        }

        try {
            // Lire le contenu existant ou créer un nouveau fichier
            StringBuilder content = new StringBuilder();
            if (Files.exists(savePath)) {
                content.append(Files.readString(savePath));
            } else {
                content.append(getYamlHeader());
            }

            // S'assurer que le fichier contient la section knownArtifacts
            if (!content.toString().contains("knownArtifacts:")) {
                content.append("\nknownArtifacts:\n");
            }

            // Ajouter les nouvelles entrées
            StringBuilder newContent = new StringBuilder();
            for (ArtifactEntry entry : newEntries.values()) {
                newContent.append(formatEntry(entry));
            }

            // Écrire le fichier
            try (PrintWriter writer = new PrintWriter(new FileWriter(savePath.toFile(), true))) {
                writer.print(newContent);
            }

            int count = newEntries.size();
            newEntries.clear();
            log.info("Saved {} new entries to {}", count, savePath);
            return count;

        } catch (IOException e) {
            log.error("Failed to save known-artifacts.yaml: {}", e.getMessage());
            return 0;
        }
    }

    private String getYamlHeader() {
        return """
            # Known artifacts mapping for Ant to Maven migration
            # Format: SHA1 -> Maven coordinates
            # Generated automatically by ant2maven

            knownArtifacts:
            """;
    }

    private String formatEntry(ArtifactEntry entry) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n  # ").append(entry.jarName()).append("\n");
        sb.append("  ").append(entry.sha1()).append(":\n");
        sb.append("    groupId: ").append(entry.coord().groupId()).append("\n");
        sb.append("    artifactId: ").append(entry.coord().artifactId()).append("\n");
        sb.append("    version: \"").append(entry.coord().version()).append("\"\n");
        sb.append("    jarName: ").append(entry.jarName()).append("\n");
        return sb.toString();
    }

    /**
     * Retourne le nombre d'artefacts connus.
     */
    public int size() {
        return artifactsBySha1.size();
    }

    /**
     * Retourne le nombre de nouvelles entrées en attente de sauvegarde.
     */
    public int pendingEntriesCount() {
        return newEntries.size();
    }

    // Entrée interne pour les nouvelles résolutions
    private record ArtifactEntry(String sha1, MavenCoordinate coord, String jarName) {}

    // DTOs internes pour le parsing YAML
    record KnownArtifactsConfig(Map<String, ArtifactMapping> knownArtifacts) {}

    record ArtifactMapping(
        String groupId,
        String artifactId,
        String version,
        String classifier,
        String scope,
        String jarName,
        String note
    ) {}
}
