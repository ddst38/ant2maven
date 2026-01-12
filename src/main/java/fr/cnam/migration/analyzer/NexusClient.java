package fr.cnam.migration.analyzer;

import fr.cnam.migration.config.MigrationConfig;
import fr.cnam.migration.model.MavenCoordinate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.*;
import java.io.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Client pour Sonatype Nexus Repository Manager avec support de certificat SSL.
 *
 * Fonctionnalités :
 * - Recherche d'artefacts par checksum SHA-1
 * - Vérification de l'existence d'un artefact
 * - Déploiement/upload d'artefacts
 * - Support de certificat SSL personnalisé pour environnements enterprise
 */
public class NexusClient {

    private static final Logger log = LoggerFactory.getLogger(NexusClient.class);

    private final MigrationConfig config;
    private final HttpClient httpClient;
    private final Map<String, Optional<MavenCoordinate>> sha1Cache = new ConcurrentHashMap<>();
    private final Map<String, Boolean> existsCache = new ConcurrentHashMap<>();

    public NexusClient(MigrationConfig config) {
        this.config = config;
        this.httpClient = createHttpClient();
    }

    /**
     * Crée un client HTTP avec certificat SSL personnalisé optionnel.
     */
    private HttpClient createHttpClient() {
        HttpClient.Builder builder = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(30));

        // Si un certificat est configuré, configurer le contexte SSL personnalisé
        if (config.nexusCertPath() != null && Files.exists(config.nexusCertPath())) {
            try {
                SSLContext sslContext = createSslContext(config.nexusCertPath());
                builder.sslContext(sslContext);
                log.info("Loaded SSL certificate from: {}", config.nexusCertPath());
            } catch (Exception e) {
                log.warn("Failed to load SSL certificate, using default SSL context: {}", e.getMessage());
            }
        }

        return builder.build();
    }

    /**
     * Crée un contexte SSL avec le certificat spécifié.
     */
    private SSLContext createSslContext(Path certPath) throws Exception {
        // Charger le certificat
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        Certificate cert;
        try (InputStream is = Files.newInputStream(certPath)) {
            cert = cf.generateCertificate(is);
        }

        // Créer un KeyStore contenant notre certificat de confiance
        KeyStore keyStore = KeyStore.getInstance(KeyStore.getDefaultType());
        keyStore.load(null, null);
        keyStore.setCertificateEntry("nexus", cert);

        // Créer un TrustManager qui fait confiance à notre certificat
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(keyStore);

        // Inclure également les certificats de confiance par défaut
        TrustManagerFactory defaultTmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        defaultTmf.init((KeyStore) null);

        // Combiner nos trust managers avec ceux par défaut
        TrustManager[] trustManagers = createCombinedTrustManagers(tmf.getTrustManagers(), defaultTmf.getTrustManagers());

        // Créer le contexte SSL
        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(null, trustManagers, null);

        return sslContext;
    }

    /**
     * Combine les trust managers personnalisés et par défaut.
     */
    private TrustManager[] createCombinedTrustManagers(TrustManager[] custom, TrustManager[] defaults) {
        List<X509TrustManager> x509Managers = new ArrayList<>();

        for (TrustManager tm : custom) {
            if (tm instanceof X509TrustManager) {
                x509Managers.add((X509TrustManager) tm);
            }
        }
        for (TrustManager tm : defaults) {
            if (tm instanceof X509TrustManager) {
                x509Managers.add((X509TrustManager) tm);
            }
        }

        // Créer un trust manager composite
        return new TrustManager[]{new CompositeX509TrustManager(x509Managers)};
    }

    /**
     * Recherche un artefact par checksum SHA-1 dans Nexus.
     * Utilise l'API REST Nexus 3 : /service/rest/v1/search/assets
     */
    public Optional<MavenCoordinate> searchBySha1(String sha1) {
        if (!config.isNexusConfigured() || sha1 == null || sha1.isBlank()) {
            log.debug("[Nexus] Recherche ignorée : Nexus non configuré ou SHA1 invalide");
            return Optional.empty();
        }

        // Vérifier si déjà en cache
        if (sha1Cache.containsKey(sha1)) {
            Optional<MavenCoordinate> cached = sha1Cache.get(sha1);
            log.debug("[Nexus] Résultat en cache pour SHA1 {} : {}", sha1,
                cached.map(MavenCoordinate::toGav).orElse("non trouvé"));
            return cached;
        }

        log.debug("[Nexus] Recherche par SHA1 : {}", sha1);
        return sha1Cache.computeIfAbsent(sha1, this::doSearchBySha1);
    }

    private Optional<MavenCoordinate> doSearchBySha1(String sha1) {
        try {
            // API Nexus 3 pour recherche par checksum
            String url = config.nexusUrl() + "/service/rest/v1/search/assets?sha1=" + sha1;
            log.debug("[Nexus] Appel API : {}", url);

            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .GET();

            addAuthHeaders(requestBuilder);

            HttpRequest request = requestBuilder.build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            log.debug("[Nexus] Réponse HTTP : {} pour SHA1 {}", response.statusCode(), sha1);

            if (response.statusCode() == 200) {
                Optional<MavenCoordinate> result = parseSearchResponse(response.body());
                if (result.isPresent()) {
                    log.debug("[Nexus] Artefact trouvé pour SHA1 {} : {}", sha1, result.get().toGav());
                } else {
                    log.debug("[Nexus] Réponse 200 mais aucun artefact trouvé pour SHA1 {} (réponse vide ou format inattendu)", sha1);
                    log.trace("[Nexus] Corps de la réponse : {}", response.body());
                }
                return result;
            } else if (response.statusCode() == 404) {
                log.debug("[Nexus] Artefact non trouvé (404) pour SHA1 {}", sha1);
                return Optional.empty();
            } else {
                log.warn("[Nexus] Échec de la recherche avec statut {} pour SHA1 {} : {}",
                    response.statusCode(), sha1, response.body());
            }

        } catch (Exception e) {
            log.debug("[Nexus] Erreur lors de la recherche pour SHA1 {} : {}", sha1, e.getMessage());
            log.trace("[Nexus] Stack trace :", e);
        }

        return Optional.empty();
    }

    /**
     * Vérifie si un artefact existe dans Nexus.
     */
    public boolean exists(MavenCoordinate coord) {
        if (!config.isNexusConfigured()) {
            log.debug("[Nexus] Vérification ignorée : Nexus non configuré");
            return false;
        }

        String key = coord.toGav();

        // Vérifier si déjà en cache
        if (existsCache.containsKey(key)) {
            boolean cached = existsCache.get(key);
            log.debug("[Nexus] Existence en cache pour {} : {}", key, cached ? "existe" : "n'existe pas");
            return cached;
        }

        log.debug("[Nexus] Vérification d'existence pour : {}", key);
        return existsCache.computeIfAbsent(key, k -> doExists(coord));
    }

    private boolean doExists(MavenCoordinate coord) {
        try {
            String path = buildArtifactPath(coord, config.nexusRepository());
            String url = config.nexusUrl() + "/repository/" + path;
            log.debug("[Nexus] Vérification HEAD : {}", url);

            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .method("HEAD", HttpRequest.BodyPublishers.noBody());

            addAuthHeaders(requestBuilder);

            HttpRequest request = requestBuilder.build();
            HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());

            boolean exists = response.statusCode() == 200;
            log.debug("[Nexus] Résultat pour {} : {} (HTTP {})",
                coord.toGav(), exists ? "existe" : "n'existe pas", response.statusCode());
            return exists;

        } catch (Exception e) {
            log.debug("[Nexus] Erreur lors de la vérification d'existence pour {} : {}",
                coord.toGav(), e.getMessage());
            return false;
        }
    }

    /**
     * Déploie/uploade un artefact vers Nexus.
     *
     * @param jarPath Chemin vers le fichier JAR
     * @param coord Coordonnées Maven pour l'artefact
     * @param isSnapshot Si c'est une version snapshot
     * @return true si le déploiement a réussi
     */
    public boolean deploy(Path jarPath, MavenCoordinate coord, boolean isSnapshot) {
        if (!config.isNexusConfigured()) {
            log.error("Nexus is not configured, cannot deploy artifact");
            return false;
        }

        try {
            String repo = config.nexusRepository();
            String path = buildArtifactPath(coord, repo);
            String url = config.nexusUrl() + "/repository/" + path;

            log.info("Deploying {} to Nexus: {}", jarPath.getFileName(), url);

            byte[] content = Files.readAllBytes(jarPath);

            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofMinutes(5))
                .PUT(HttpRequest.BodyPublishers.ofByteArray(content))
                .header("Content-Type", "application/java-archive");

            addAuthHeaders(requestBuilder);

            HttpRequest request = requestBuilder.build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 201 || response.statusCode() == 200) {
                log.info("Successfully deployed {} as {}", jarPath.getFileName(), coord.toGav());
                return true;
            } else {
                log.error("Deployment to Nexus failed with status {}: {}", response.statusCode(), response.body());
                return false;
            }

        } catch (Exception e) {
            log.error("Error deploying artifact to Nexus: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Génère un script de déploiement pour les artefacts à uploader vers Nexus.
     * Utile quand l'upload direct n'est pas possible (ex: restrictions réseau).
     */
    public String generateDeployCommand(Path jarPath, MavenCoordinate coord, boolean isSnapshot) {
        return generateDeployCommand(jarPath, coord, isSnapshot, config.nexusRepository());
    }

    /**
     * Génère un script de déploiement avec un repository spécifique.
     */
    public String generateDeployCommand(Path jarPath, MavenCoordinate coord, boolean isSnapshot, String deployRepo) {
        String repo = deployRepo;

        // Générer la commande curl
        StringBuilder cmd = new StringBuilder();
        cmd.append("curl -X PUT ");

        if (config.nexusUsername() != null && config.nexusPassword() != null) {
            cmd.append("-u \"").append(config.nexusUsername())
               .append(":${NEXUS_PASSWORD}\" ");
        }

        if (config.nexusCertPath() != null) {
            cmd.append("--cacert \"").append(config.nexusCertPath()).append("\" ");
        }

        cmd.append("-T \"").append(jarPath.toAbsolutePath()).append("\" ");
        cmd.append("\"").append(config.nexusUrl())
           .append("/repository/").append(buildArtifactPath(coord, repo)).append("\"");

        return cmd.toString();
    }

    /**
     * Ajoute les en-têtes d'authentification à la requête.
     */
    private void addAuthHeaders(HttpRequest.Builder builder) {
        if (config.nexusUsername() != null && config.nexusPassword() != null) {
            String auth = config.nexusUsername() + ":" + config.nexusPassword();
            String encodedAuth = Base64.getEncoder().encodeToString(auth.getBytes(StandardCharsets.UTF_8));
            builder.header("Authorization", "Basic " + encodedAuth);
        }
    }

    /**
     * Construit le chemin de l'artefact dans le repository.
     */
    private String buildArtifactPath(MavenCoordinate coord, String repo) {
        String groupPath = coord.groupId().replace('.', '/');
        String fileName = coord.artifactId() + "-" + coord.version() + ".jar";
        return repo + "/" + groupPath + "/" + coord.artifactId() + "/" + coord.version() + "/" + fileName;
    }

    /**
     * Parse la réponse de recherche Nexus pour extraire les coordonnées Maven.
     * Format Nexus 3 : {"items":[{"path":"/...", "maven2":{"groupId":"...", "artifactId":"...", "version":"..."}}]}
     */
    private Optional<MavenCoordinate> parseSearchResponse(String json) {
        // Parsing JSON simple pour la réponse Nexus 3
        // On cherche les champs maven2.groupId, maven2.artifactId, maven2.version

        // Pattern pour extraire groupId
        Pattern groupIdPattern = Pattern.compile("\"groupId\"\\s*:\\s*\"([^\"]+)\"");
        Pattern artifactIdPattern = Pattern.compile("\"artifactId\"\\s*:\\s*\"([^\"]+)\"");
        Pattern versionPattern = Pattern.compile("\"version\"\\s*:\\s*\"([^\"]+)\"");

        Matcher groupIdMatcher = groupIdPattern.matcher(json);
        Matcher artifactIdMatcher = artifactIdPattern.matcher(json);
        Matcher versionMatcher = versionPattern.matcher(json);

        if (groupIdMatcher.find() && artifactIdMatcher.find() && versionMatcher.find()) {
            String groupId = groupIdMatcher.group(1);
            String artifactId = artifactIdMatcher.group(1);
            String version = versionMatcher.group(1);

            return Optional.of(new MavenCoordinate(groupId, artifactId, version));
        }

        // Fallback: essayer de parser depuis le path
        Pattern pathPattern = Pattern.compile("\"path\"\\s*:\\s*\"([^\"]+)\"");
        Matcher pathMatcher = pathPattern.matcher(json);

        if (pathMatcher.find()) {
            String path = pathMatcher.group(1);
            return parseCoordinatesFromPath(path);
        }

        return Optional.empty();
    }

    /**
     * Parse les coordonnées Maven depuis un chemin d'artefact.
     */
    private Optional<MavenCoordinate> parseCoordinatesFromPath(String path) {
        // Format du chemin : /groupId/artifactId/version/artifactId-version.jar
        Pattern pathPattern = Pattern.compile(".*/([^/]+)/([^/]+)/([^/]+)/([^/]+)\\.jar$");
        Matcher matcher = pathPattern.matcher(path);

        if (matcher.find()) {
            String[] parts = path.split("/");
            if (parts.length >= 4) {
                String version = parts[parts.length - 2];
                String artifactId = parts[parts.length - 3];

                // Reconstruire le groupId à partir des segments restants
                StringBuilder groupId = new StringBuilder();
                // Ignorer les segments vides au début et le repository
                int startIdx = 1; // Ignorer le premier segment vide

                for (int i = startIdx; i < parts.length - 3; i++) {
                    if (!parts[i].isEmpty()) {
                        if (groupId.length() > 0) {
                            groupId.append(".");
                        }
                        groupId.append(parts[i]);
                    }
                }

                if (groupId.length() > 0) {
                    return Optional.of(new MavenCoordinate(groupId.toString(), artifactId, version));
                }
            }
        }

        return Optional.empty();
    }

    /**
     * Recherche des artefacts par groupId dans Nexus.
     * Utilise l'API REST Nexus 3 : /service/rest/v1/search?group=...
     *
     * @param groupId Le groupId à rechercher (ex: fr.cnamts.prf1)
     * @return Liste des coordonnées Maven trouvées
     */
    public List<MavenCoordinate> searchByGroupId(String groupId) {
        if (!config.isNexusConfigured() || groupId == null || groupId.isBlank()) {
            return List.of();
        }

        try {
            String url = config.nexusUrl() + "/service/rest/v1/search?group=" + groupId;
            log.debug("[Nexus] Recherche par groupId : {}", groupId);

            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .GET();

            addAuthHeaders(requestBuilder);

            HttpRequest request = requestBuilder.build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                List<MavenCoordinate> results = parseSearchResponseMultiple(response.body());
                log.debug("[Nexus] {} artefact(s) trouvé(s) pour groupId {}", results.size(), groupId);
                return results;
            }

        } catch (Exception e) {
            log.debug("[Nexus] Erreur lors de la recherche par groupId {} : {}", groupId, e.getMessage());
        }

        return List.of();
    }

    /**
     * Recherche un artefact correspondant à un package Java dans Nexus.
     * Tente différentes variantes de groupId basées sur le package.
     *
     * @param packageName Le package Java manquant (ex: fr.cnamts.prf1.batch.service)
     * @return Les coordonnées Maven si trouvées
     */
    public Optional<MavenCoordinate> searchByPackage(String packageName) {
        if (!config.isNexusConfigured() || packageName == null || packageName.isBlank()) {
            return Optional.empty();
        }

        // Essayer différents préfixes du package comme groupId
        String[] parts = packageName.split("\\.");
        for (int len = parts.length; len >= 2; len--) {
            String candidateGroupId = String.join(".", Arrays.copyOf(parts, len));
            List<MavenCoordinate> results = searchByGroupId(candidateGroupId);

            if (!results.isEmpty()) {
                // Retourner le premier résultat (le plus récent généralement)
                log.debug("[Nexus] Package {} résolu via groupId {} -> {}",
                    packageName, candidateGroupId, results.get(0).toGav());
                return Optional.of(results.get(0));
            }
        }

        return Optional.empty();
    }

    /**
     * Parse la réponse de recherche Nexus pour extraire plusieurs coordonnées Maven.
     */
    private List<MavenCoordinate> parseSearchResponseMultiple(String json) {
        List<MavenCoordinate> results = new ArrayList<>();

        // Pattern pour extraire les items
        Pattern itemPattern = Pattern.compile("\\{[^{}]*\"maven2\"[^{}]*\\{[^{}]*\\}[^{}]*\\}");
        Matcher itemMatcher = itemPattern.matcher(json);

        Pattern groupIdPattern = Pattern.compile("\"groupId\"\\s*:\\s*\"([^\"]+)\"");
        Pattern artifactIdPattern = Pattern.compile("\"artifactId\"\\s*:\\s*\"([^\"]+)\"");
        Pattern versionPattern = Pattern.compile("\"version\"\\s*:\\s*\"([^\"]+)\"");

        // Trouver tous les blocs "maven2"
        int searchStart = 0;
        while (true) {
            int maven2Idx = json.indexOf("\"maven2\"", searchStart);
            if (maven2Idx < 0) break;

            // Extraire le bloc JSON autour de maven2
            int blockEnd = json.indexOf("}", maven2Idx);
            if (blockEnd < 0) break;

            String block = json.substring(maven2Idx, blockEnd + 1);

            Matcher gm = groupIdPattern.matcher(block);
            Matcher am = artifactIdPattern.matcher(block);
            Matcher vm = versionPattern.matcher(block);

            if (gm.find() && am.find() && vm.find()) {
                results.add(new MavenCoordinate(gm.group(1), am.group(1), vm.group(1)));
            }

            searchStart = blockEnd + 1;
        }

        return results;
    }

    /**
     * Retourne les statistiques du cache pour débogage.
     */
    public String getCacheStats() {
        return String.format("SHA1 cache: %d entries, Exists cache: %d entries",
            sha1Cache.size(), existsCache.size());
    }

    /**
     * Teste la connexion à Nexus.
     */
    public boolean testConnection() {
        if (!config.isNexusConfigured()) {
            log.debug("[Nexus] Test de connexion ignoré : Nexus non configuré");
            return false;
        }

        try {
            // API Nexus 3 pour vérifier le statut
            String url = config.nexusUrl() + "/service/rest/v1/status";
            log.debug("[Nexus] Test de connexion vers : {}", url);

            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .GET();

            addAuthHeaders(requestBuilder);

            HttpRequest request = requestBuilder.build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            boolean success = response.statusCode() == 200;
            if (success) {
                log.debug("[Nexus] Connexion réussie (HTTP 200)");
            } else {
                log.debug("[Nexus] Connexion échouée (HTTP {})", response.statusCode());
            }
            return success;

        } catch (Exception e) {
            log.warn("[Nexus] Échec du test de connexion : {}", e.getMessage());
            log.debug("[Nexus] Détail de l'erreur :", e);
            return false;
        }
    }

    /**
     * X509TrustManager composite qui combine plusieurs trust managers.
     */
    private static class CompositeX509TrustManager implements X509TrustManager {
        private final List<X509TrustManager> trustManagers;

        CompositeX509TrustManager(List<X509TrustManager> trustManagers) {
            this.trustManagers = trustManagers;
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) throws java.security.cert.CertificateException {
            for (X509TrustManager tm : trustManagers) {
                try {
                    tm.checkClientTrusted(chain, authType);
                    return;
                } catch (java.security.cert.CertificateException e) {
                    // Essayer le trust manager suivant
                }
            }
            throw new java.security.cert.CertificateException("None of the trust managers trust this certificate chain");
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) throws java.security.cert.CertificateException {
            for (X509TrustManager tm : trustManagers) {
                try {
                    tm.checkServerTrusted(chain, authType);
                    return;
                } catch (java.security.cert.CertificateException e) {
                    // Essayer le trust manager suivant
                }
            }
            throw new java.security.cert.CertificateException("None of the trust managers trust this certificate chain");
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            List<X509Certificate> certs = new ArrayList<>();
            for (X509TrustManager tm : trustManagers) {
                certs.addAll(Arrays.asList(tm.getAcceptedIssuers()));
            }
            return certs.toArray(new X509Certificate[0]);
        }
    }
}
