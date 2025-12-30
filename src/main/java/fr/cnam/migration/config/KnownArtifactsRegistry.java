package fr.cnam.migration.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import fr.cnam.migration.model.MavenCoordinate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Registre des mappings JAR vers coordonnées Maven connus.
 * Charge depuis known-artifacts.yaml embarqué et optionnellement depuis un fichier externe.
 * Utilise le SHA1 du JAR comme clé pour garantir une correspondance exacte.
 */
public class KnownArtifactsRegistry {

    private static final Logger log = LoggerFactory.getLogger(KnownArtifactsRegistry.class);
    private static final ObjectMapper YAML_MAPPER = new ObjectMapper(new YAMLFactory());

    // Map: SHA1 -> MavenCoordinate (clé principale)
    private final Map<String, MavenCoordinate> artifactsBySha1 = new HashMap<>();

    public KnownArtifactsRegistry() {
        loadEmbeddedArtifacts();
    }

    public KnownArtifactsRegistry(Path additionalFile) {
        this();
        if (additionalFile != null && Files.exists(additionalFile)) {
            loadExternalArtifacts(additionalFile);
        }
    }

    private void loadEmbeddedArtifacts() {
        try (InputStream is = getClass().getResourceAsStream("/known-artifacts.yaml")) {
            if (is != null) {
                KnownArtifactsConfig config = YAML_MAPPER.readValue(is, KnownArtifactsConfig.class);
                if (config.knownArtifacts() != null) {
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
            if (config.knownArtifacts() != null) {
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
     * Retourne le nombre d'artefacts connus.
     */
    public int size() {
        return artifactsBySha1.size();
    }

    // DTOs internes pour le parsing YAML
    record KnownArtifactsConfig(Map<String, ArtifactMapping> knownArtifacts) {}

    record ArtifactMapping(
        String groupId,
        String artifactId,
        String version,
        String classifier,
        String scope,
        String jarName,  // Optionnel, pour référence/documentation
        String note
    ) {}
}
