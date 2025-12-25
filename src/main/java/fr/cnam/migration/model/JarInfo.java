package fr.cnam.migration.model;

import java.nio.file.Path;
import java.util.regex.Pattern;

/**
 * Informations sur un fichier JAR découvert dans le projet.
 */
public record JarInfo(
    Path path,
    String name,
    long size,
    String sha1,
    JarCategory category
) {
    public enum JarCategory {
        MAIN,      // Dépendance de compilation principale
        TEST,      // Dépendance de test uniquement
        PROVIDED,  // Fourni par le conteneur (servlet-api, etc.)
        RUNTIME,   // Exécution uniquement
        UNKNOWN    // Pas encore catégorisé
    }

    // Patterns pour la détection des artefacts internes
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
     * Vérifie si ce JAR semble être un artefact interne/propriétaire.
     * Utilise des patterns génériques qui fonctionnent pour différents projets CNAM.
     */
    public boolean isInternalArtifact() {
        // Pattern DEPFAB.* (pattern interne le plus courant)
        if (DEPFAB_PATTERN.matcher(name).matches()) {
            return true;
        }

        // Pattern XXX_Y.something (ex: S8_J.secJava.jar, BIMC_H.core.jar)
        if (PROJECT_CODE_PATTERN.matcher(name).matches()) {
            return true;
        }

        // Codes projets CNAM (ex: SOCA010000J-1.0.0.jar)
        if (CNAM_PROJECT_PATTERN.matcher(name).matches()) {
            return true;
        }

        // Stubs de services (ex: ServicePS_3.0.client.jar)
        if (SERVICE_PATTERN.matcher(name).matches()) {
            return true;
        }

        // Bibliothèques jk-socle
        if (JK_SOCLE_PATTERN.matcher(name).matches()) {
            return true;
        }

        // Bibliothèques s8 (ex: s8sp-2.0.3.jar, s8h-chiffrementUtil-1.2.0.jar)
        if (S8_PATTERN.matcher(name).matches()) {
            return true;
        }

        // JARs internes spécifiques bien connus (conservés pour compatibilité)
        // Ceux-ci sont dans known-artifacts.yaml mais on les vérifie aussi ici
        return name.equals("tracesCaster.jar") ||
               name.equals("biblicnam.jar") ||
               name.equals("archirfe.jar") ||
               name.equals("classes12.jar");  // Oracle JDBC non disponible sur Maven Central
    }
}
