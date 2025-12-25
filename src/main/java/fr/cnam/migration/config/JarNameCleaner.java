package fr.cnam.migration.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Utilitaire pour nettoyer les noms de JARs internes.
 *
 * Les bibliothèques internes CNAM peuvent avoir des préfixes spéciaux qui doivent
 * être supprimés pour obtenir un nom de fichier utilisable :
 *
 * 1. Le préfixe "DEPFAB." indique une dépendance de fabrication (compilation).
 *    Historiquement, ces bibliothèques étaient fournies par l'intégration continue ANT
 *    en lisant la section DEPENDANCES_FAB. Ce préfixe doit être supprimé.
 *
 * 2. Certains noms contiennent un code projet supplémentaire après "DEPFAB."
 *    (ex: "DEPFAB.S8_J.nimbus-jose-jwt-4.23-jdk16.jar"). Ce code projet correspond
 *    à une bibliothèque bundle qui contient d'autres bibliothèques. Il doit
 *    également être supprimé pour obtenir le vrai nom de la bibliothèque.
 *
 * Exemples :
 * - DEPFAB.W1_ServiceImageDecompte_v1.0_client.jar → W1_ServiceImageDecompte_v1.0_client.jar
 * - DEPFAB.S8_J.nimbus-jose-jwt-4.23-jdk16.jar → nimbus-jose-jwt-4.23-jdk16.jar
 * - DEPFAB.BIMC_H.commons-lang3-3.8.1.jar → commons-lang3-3.8.1.jar
 */
public class JarNameCleaner {

    private static final Logger log = LoggerFactory.getLogger(JarNameCleaner.class);

    /**
     * Préfixe DEPFAB indiquant une dépendance de fabrication.
     */
    private static final String DEPFAB_PREFIX = "DEPFAB.";

    /**
     * Pattern pour détecter un code projet après DEPFAB.
     * Format : CODE_LETTRE. (ex: S8_J., BIMC_H., GMIC_J., W1_WS.)
     * Le code projet est composé de lettres/chiffres, underscore, lettre(s), puis un point.
     */
    private static final Pattern PROJECT_CODE_PATTERN = Pattern.compile(
        "^([A-Z0-9]+_[A-Z]+)\\.(.+)$"
    );

    /**
     * Nettoie un nom de JAR en supprimant les préfixes DEPFAB et code projet.
     *
     * @param jarName Le nom original du JAR
     * @return Le nom nettoyé, ou le nom original s'il n'y a rien à nettoyer
     */
    public static String clean(String jarName) {
        if (jarName == null || jarName.isBlank()) {
            return jarName;
        }

        String cleanedName = jarName;

        // Étape 1 : Supprimer le préfixe DEPFAB.
        if (cleanedName.startsWith(DEPFAB_PREFIX)) {
            String afterDepfab = cleanedName.substring(DEPFAB_PREFIX.length());
            log.debug("Suppression du préfixe DEPFAB. de {}", jarName);

            // Étape 2 : Vérifier s'il y a un code projet à supprimer
            Matcher matcher = PROJECT_CODE_PATTERN.matcher(afterDepfab);
            if (matcher.matches()) {
                String projectCode = matcher.group(1);
                cleanedName = matcher.group(2);
                log.debug("Suppression du code projet {} -> {}", projectCode, cleanedName);
            } else {
                cleanedName = afterDepfab;
            }

            log.info("Nom de JAR nettoyé : {} → {}", jarName, cleanedName);
        }

        return cleanedName;
    }

    /**
     * Vérifie si un nom de JAR nécessite un nettoyage.
     *
     * @param jarName Le nom du JAR à vérifier
     * @return true si le nom contient des préfixes à supprimer
     */
    public static boolean needsCleaning(String jarName) {
        return jarName != null && jarName.startsWith(DEPFAB_PREFIX);
    }

    /**
     * Retourne le nom original et le nom nettoyé sous forme de paire descriptive.
     * Utile pour les logs et rapports.
     *
     * @param jarName Le nom original du JAR
     * @return Description du nettoyage effectué, ou null si aucun nettoyage nécessaire
     */
    public static String getCleaningDescription(String jarName) {
        if (!needsCleaning(jarName)) {
            return null;
        }
        String cleaned = clean(jarName);
        return jarName + " → " + cleaned;
    }
}
