package fr.cnam.migration.scanner;

import fr.cnam.migration.model.InternalDependency;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Parse le fichier properties.conf ou pic.properties pour extraire les dépendances internes.
 */
public class PropertiesConfParser {

    private static final Logger log = LoggerFactory.getLogger(PropertiesConfParser.class);
    private static final String DEPENDANCES_FAB_SECTION = "[DEPENDANCES_FAB]";

    /**
     * Localise le fichier CI a analyser.
     * Priorite : pic.properties (racine) > Install/properties.conf
     *
     * @param projectRoot Racine du projet a migrer
     * @return Le chemin du fichier CI ou Optional.empty() si aucun trouve
     */
    public Optional<Path> findCiFile(Path projectRoot) {
        log.info("[SCAN-CADRE] Recherche fichier CI dans : {}", projectRoot);

        // Priorite 1 : pic.properties a la racine
        Path picProperties = projectRoot.resolve("pic.properties");
        log.info("[SCAN-CADRE] Test pic.properties : {} -> existe={}", picProperties, Files.exists(picProperties));
        if (Files.exists(picProperties)) {
            log.info("[SCAN-CADRE] Fichier CI trouve : {} (prioritaire)", picProperties);
            return Optional.of(picProperties);
        }

        // Priorite 2 : Install/properties.conf
        Path propertiesConf = projectRoot.resolve("Install").resolve("properties.conf");
        log.info("[SCAN-CADRE] Test Install/properties.conf : {} -> existe={}", propertiesConf, Files.exists(propertiesConf));
        if (Files.exists(propertiesConf)) {
            log.info("[SCAN-CADRE] Fichier CI trouve : {}", propertiesConf);
            return Optional.of(propertiesConf);
        }

        // Priorite 3 : install/properties.conf (minuscule)
        Path propertiesConfLower = projectRoot.resolve("install").resolve("properties.conf");
        log.info("[SCAN-CADRE] Test install/properties.conf : {} -> existe={}", propertiesConfLower, Files.exists(propertiesConfLower));
        if (Files.exists(propertiesConfLower)) {
            log.info("[SCAN-CADRE] Fichier CI trouve : {}", propertiesConfLower);
            return Optional.of(propertiesConfLower);
        }

        log.warn("[SCAN-CADRE] Aucun fichier CI trouve dans {}", projectRoot);
        return Optional.empty();
    }

    /**
     * Extrait les tags bruts de la section [DEPENDANCES_FAB].
     * Retourne uniquement la partie gauche du separateur ';' (le tag).
     *
     * @param projectRoot Racine du projet
     * @return Liste des tags (ex: ["NOOC040000J", "WW090102J"])
     */
    public List<String> extractTags(Path projectRoot) {
        Optional<Path> ciFile = findCiFile(projectRoot);
        if (ciFile.isEmpty()) {
            return List.of();
        }
        return extractTagsFromFile(ciFile.get());
    }

    /**
     * Extrait les tags depuis un fichier CI specifique.
     */
    public List<String> extractTagsFromFile(Path ciFile) {
        List<String> tags = new ArrayList<>();

        log.info("[SCAN-CADRE] Lecture du fichier : {}", ciFile);
        List<String> lines = readFileWithEncoding(ciFile);
        log.info("[SCAN-CADRE] Nombre de lignes lues : {}", lines.size());

        if (lines.isEmpty()) {
            log.warn("[SCAN-CADRE] Fichier vide ou illisible : {}", ciFile);
            return tags;
        }

        boolean inDependancesSection = false;

        for (String line : lines) {
            String trimmed = line.trim();

            // Detecter les sections
            if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                boolean wasInSection = inDependancesSection;
                inDependancesSection = DEPENDANCES_FAB_SECTION.equals(trimmed);
                if (inDependancesSection) {
                    log.info("[SCAN-CADRE] Section [DEPENDANCES_FAB] trouvee");
                } else if (wasInSection) {
                    log.info("[SCAN-CADRE] Fin de section [DEPENDANCES_FAB], nouvelle section : {}", trimmed);
                }
                continue;
            }

            // Parser les lignes de la section DEPENDANCES_FAB
            if (inDependancesSection && !trimmed.isEmpty() && !trimmed.startsWith("#")) {
                log.info("[SCAN-CADRE] Parsing ligne : '{}'", trimmed);
                String tag = extractTagFromLine(trimmed);
                if (tag != null && !tag.isEmpty()) {
                    tags.add(tag);
                    log.info("[SCAN-CADRE] Tag extrait : {}", tag);
                } else {
                    log.warn("[SCAN-CADRE] Impossible d'extraire le tag de : '{}'", trimmed);
                }
            }
        }

        log.info("[SCAN-CADRE] Total : {} tags extraits depuis {}", tags.size(), ciFile);
        return tags;
    }

    /**
     * Extrait le tag d'une ligne de dependance.
     * Gere les commentaires inline et les caracteres speciaux.
     * Format : TAG;BASE_NAME ou TAG;BASE_NAME #commentaire
     */
    private String extractTagFromLine(String line) {
        // Supprimer les commentaires inline (apres # ou apres ;...#)
        String cleaned = line;

        // Chercher le separateur ;
        int semicolonIdx = cleaned.indexOf(';');
        if (semicolonIdx == -1) {
            return null;
        }

        // Extraire la partie gauche (le tag)
        String tag = cleaned.substring(0, semicolonIdx).trim();

        // Nettoyer les caracteres speciaux (BOM, espaces invisibles)
        tag = cleanString(tag);

        return tag.isEmpty() ? null : tag;
    }

    /**
     * Nettoie une chaine des caracteres speciaux (BOM UTF-8, espaces invisibles).
     */
    private String cleanString(String s) {
        if (s == null) return null;
        // Supprimer BOM UTF-8 (EF BB BF) et autres caracteres invisibles
        return s.replaceAll("^\\uFEFF", "")
                .replaceAll("[\\p{Cntrl}&&[^\r\n\t]]", "")
                .trim();
    }

    /**
     * Lit un fichier en detectant automatiquement l'encodage.
     * Essaie UTF-8, puis ISO-8859-1 en fallback.
     */
    private List<String> readFileWithEncoding(Path file) {
        // Essayer UTF-8 d'abord
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            log.debug("Fichier lu en UTF-8 : {}", file);
            return lines;
        } catch (IOException e) {
            log.debug("Echec lecture UTF-8, essai ISO-8859-1 : {}", file);
        }

        // Fallback ISO-8859-1
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.ISO_8859_1);
            log.debug("Fichier lu en ISO-8859-1 : {}", file);
            return lines;
        } catch (IOException e) {
            log.error("Impossible de lire le fichier {} : {}", file, e.getMessage());
        }

        return List.of();
    }

    /**
     * Parse le fichier properties.conf et extrait la section DEPENDANCES_FAB.
     */
    public List<InternalDependency> parse(Path propertiesConf) {
        List<InternalDependency> dependencies = new ArrayList<>();

        if (!Files.exists(propertiesConf)) {
            log.warn("properties.conf not found: {}", propertiesConf);
            return dependencies;
        }

        try {
            List<String> lines = Files.readAllLines(propertiesConf);
            boolean inDependancesSection = false;

            for (String line : lines) {
                String trimmed = line.trim();

                // Vérifier les en-têtes de section
                if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                    inDependancesSection = DEPENDANCES_FAB_SECTION.equals(trimmed);
                    continue;
                }

                // Parser les lignes de dépendance
                if (inDependancesSection && !trimmed.isEmpty() && !trimmed.startsWith("#")) {
                    try {
                        InternalDependency dep = InternalDependency.parse(trimmed);
                        dependencies.add(dep);
                        log.debug("Found internal dependency: {} -> {}", dep.code(), dep.envVariable());
                    } catch (IllegalArgumentException e) {
                        log.warn("Invalid dependency line in {}: {}", propertiesConf, trimmed);
                    }
                }
            }

            log.info("Parsed {} internal dependencies from {}", dependencies.size(), propertiesConf);

        } catch (IOException e) {
            log.error("Failed to read {}: {}", propertiesConf, e.getMessage());
        }

        return dependencies;
    }

    /**
     * Extrait les autres sections de configuration depuis properties.conf.
     */
    public record PropertiesConfInfo(
        String osRefab,
        List<InternalDependency> dependencies,
        String typeDestinataire,
        boolean ksEnabled
    ) {}

    public PropertiesConfInfo parseAll(Path propertiesConf) {
        String osRefab = null;
        List<InternalDependency> dependencies = new ArrayList<>();
        String typeDestinataire = null;
        boolean ksEnabled = false;

        if (!Files.exists(propertiesConf)) {
            return new PropertiesConfInfo(osRefab, dependencies, typeDestinataire, ksEnabled);
        }

        try {
            List<String> lines = Files.readAllLines(propertiesConf);
            String currentSection = null;

            for (String line : lines) {
                String trimmed = line.trim();

                if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                    currentSection = trimmed;
                    continue;
                }

                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }

                switch (currentSection) {
                    case "[REFAB]":
                        if (trimmed.startsWith("OSREFAB=")) {
                            osRefab = trimmed.substring("OSREFAB=".length());
                        }
                        break;

                    case "[DEPENDANCES_FAB]":
                        try {
                            dependencies.add(InternalDependency.parse(trimmed));
                        } catch (IllegalArgumentException ignored) {
                        }
                        break;

                    case "[DIFFUSION]":
                        if (trimmed.startsWith("TYPE_DESTINATAIRE=")) {
                            typeDestinataire = trimmed.substring("TYPE_DESTINATAIRE=".length());
                        }
                        break;

                    case "[INSTALLATION]":
                        if (trimmed.startsWith("KS=")) {
                            ksEnabled = "oui".equalsIgnoreCase(trimmed.substring("KS=".length()));
                        }
                        break;
                }
            }

        } catch (IOException e) {
            log.error("Failed to parse {}: {}", propertiesConf, e.getMessage());
        }

        return new PropertiesConfInfo(osRefab, dependencies, typeDestinataire, ksEnabled);
    }
}
