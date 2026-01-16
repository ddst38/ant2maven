package fr.cnam.migration.jdeps;

/**
 * Usage d'une API interne JDK (sun.*, jdk.internal.*, com.sun.*).
 */
public record JdkInternalUsage(
    String sourceClass,      // Classe qui utilise l'API interne
    String internalApi,      // API interne utilisee (ex: sun.misc.Unsafe)
    String targetModule,     // Module JDK contenant l'API
    String suggestion        // Suggestion de remplacement
) {
    /**
     * Genere une suggestion de remplacement basee sur l'API interne.
     */
    public static String getSuggestion(String internalApi) {
        if (internalApi == null) return null;

        if (internalApi.contains("sun.misc.Unsafe")) {
            return "Utiliser java.lang.invoke.VarHandle ou MethodHandles";
        }
        if (internalApi.contains("sun.misc.BASE64")) {
            return "Utiliser java.util.Base64";
        }
        if (internalApi.contains("sun.net.www")) {
            return "Utiliser java.net.http.HttpClient";
        }
        if (internalApi.contains("sun.security")) {
            return "Utiliser les APIs java.security.*";
        }
        if (internalApi.contains("com.sun.xml")) {
            return "Utiliser les APIs javax.xml.* standards";
        }
        if (internalApi.contains("jdk.internal")) {
            return "API interne JDK - chercher une alternative publique";
        }

        return "Chercher une API publique equivalente";
    }
}
