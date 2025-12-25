package fr.cnam.migration.scanner;

import fr.cnam.migration.model.InternalDependency;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Parses the properties.conf file to extract internal dependencies.
 */
public class PropertiesConfParser {

    private static final Logger log = LoggerFactory.getLogger(PropertiesConfParser.class);
    private static final String DEPENDANCES_FAB_SECTION = "[DEPENDANCES_FAB]";

    /**
     * Parses the properties.conf file and extracts DEPENDANCES_FAB section.
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

                // Check for section headers
                if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                    inDependancesSection = DEPENDANCES_FAB_SECTION.equals(trimmed);
                    continue;
                }

                // Parse dependency lines
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
     * Extracts other configuration sections from properties.conf.
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
