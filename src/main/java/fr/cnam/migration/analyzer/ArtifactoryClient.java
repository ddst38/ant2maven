package fr.cnam.migration.analyzer;

import fr.cnam.migration.config.MigrationConfig;
import fr.cnam.migration.model.MavenCoordinate;
import fr.cnam.migration.model.ResolutionResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.*;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
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
 * Client pour JFrog Artifactory avec support de certificat SSL.
 *
 * Fonctionnalités :
 * - Recherche d'artefacts par checksum SHA-1
 * - Vérification de l'existence d'un artefact
 * - Déploiement/upload d'artefacts
 * - Support de certificat SSL personnalisé pour environnements enterprise
 */
public class ArtifactoryClient {

    private static final Logger log = LoggerFactory.getLogger(ArtifactoryClient.class);

    private final MigrationConfig config;
    private final HttpClient httpClient;
    private final Map<String, Optional<MavenCoordinate>> sha1Cache = new ConcurrentHashMap<>();
    private final Map<String, Optional<ResolutionResult>> sha1ResultCache = new ConcurrentHashMap<>();
    private final Map<String, Boolean> existsCache = new ConcurrentHashMap<>();

    public ArtifactoryClient(MigrationConfig config) {
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
        if (config.artifactoryCertPath() != null && Files.exists(config.artifactoryCertPath())) {
            try {
                SSLContext sslContext = createSslContext(config.artifactoryCertPath());
                builder.sslContext(sslContext);
                log.info("Loaded SSL certificate from: {}", config.artifactoryCertPath());
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
        keyStore.setCertificateEntry("artifactory", cert);

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
     * Recherche un artefact par checksum SHA-1 dans Artifactory.
     */
    public Optional<MavenCoordinate> searchBySha1(String sha1) {
        if (!config.isArtifactoryConfigured() || sha1 == null || sha1.isBlank()) {
            return Optional.empty();
        }

        return sha1Cache.computeIfAbsent(sha1, this::doSearchBySha1);
    }

    /**
     * Recherche un artefact par checksum SHA-1 et retourne aussi le repository source.
     */
    public Optional<ResolutionResult> searchBySha1WithResult(String sha1) {
        if (!config.isArtifactoryConfigured() || sha1 == null || sha1.isBlank()) {
            return Optional.empty();
        }

        return sha1ResultCache.computeIfAbsent(sha1, this::doSearchBySha1WithResult);
    }

    private Optional<ResolutionResult> doSearchBySha1WithResult(String sha1) {
        try {
            String url = config.artifactoryUrl() + "/api/search/checksum?sha1=" + sha1;

            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .GET();

            addAuthHeaders(requestBuilder);

            HttpRequest request = requestBuilder.build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                return parseSearchResponseWithRepo(response.body());
            } else if (response.statusCode() == 404) {
                return Optional.empty();
            } else {
                log.warn("Artifactory search failed with status {}: {}", response.statusCode(), response.body());
            }

        } catch (Exception e) {
            log.debug("Artifactory search error for SHA1 {}: {}", sha1, e.getMessage());
        }

        return Optional.empty();
    }

    private Optional<MavenCoordinate> doSearchBySha1(String sha1) {
        try {
            // Recherche AQL Artifactory par checksum
            String url = config.artifactoryUrl() + "/api/search/checksum?sha1=" + sha1;

            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .GET();

            addAuthHeaders(requestBuilder);

            HttpRequest request = requestBuilder.build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                return parseSearchResponse(response.body());
            } else if (response.statusCode() == 404) {
                return Optional.empty();
            } else {
                log.warn("Artifactory search failed with status {}: {}", response.statusCode(), response.body());
            }

        } catch (Exception e) {
            log.debug("Artifactory search error for SHA1 {}: {}", sha1, e.getMessage());
        }

        return Optional.empty();
    }

    /**
     * Vérifie si un artefact existe dans Artifactory.
     */
    public boolean exists(MavenCoordinate coord) {
        if (!config.isArtifactoryConfigured()) {
            return false;
        }

        String key = coord.toGav();
        return existsCache.computeIfAbsent(key, k -> doExists(coord));
    }

    private boolean doExists(MavenCoordinate coord) {
        try {
            String path = buildArtifactPath(coord, config.artifactoryReleaseRepo());
            String url = config.artifactoryUrl() + "/" + path;

            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .method("HEAD", HttpRequest.BodyPublishers.noBody());

            addAuthHeaders(requestBuilder);

            HttpRequest request = requestBuilder.build();
            HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());

            return response.statusCode() == 200;

        } catch (Exception e) {
            log.debug("Error checking artifact existence: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Déploie/uploade un artefact vers Artifactory.
     *
     * @param jarPath Chemin vers le fichier JAR
     * @param coord Coordonnées Maven pour l'artefact
     * @param isSnapshot Si c'est une version snapshot
     * @return true si le déploiement a réussi
     */
    public boolean deploy(Path jarPath, MavenCoordinate coord, boolean isSnapshot) {
        if (!config.isArtifactoryConfigured()) {
            log.error("Artifactory is not configured, cannot deploy artifact");
            return false;
        }

        try {
            String repo = isSnapshot ? config.artifactorySnapshotRepo() : config.artifactoryReleaseRepo();
            String path = buildArtifactPath(coord, repo);
            String url = config.artifactoryUrl() + "/" + path;

            log.info("Deploying {} to Artifactory: {}", jarPath.getFileName(), url);

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
                log.error("Deployment failed with status {}: {}", response.statusCode(), response.body());
                return false;
            }

        } catch (Exception e) {
            log.error("Error deploying artifact: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Génère un script de déploiement pour les artefacts à uploader.
     * Utile quand l'upload direct n'est pas possible (ex: restrictions réseau).
     */
    public String generateDeployCommand(Path jarPath, MavenCoordinate coord, boolean isSnapshot) {
        String repo = isSnapshot ? config.artifactorySnapshotRepo() : config.artifactoryReleaseRepo();
        return generateDeployCommand(jarPath, coord, repo);
    }

    /**
     * Génère un script de déploiement avec un repository spécifique.
     */
    public String generateDeployCommand(Path jarPath, MavenCoordinate coord, String deployRepo) {
        String repo = deployRepo;

        // Générer la commande curl
        StringBuilder cmd = new StringBuilder();
        cmd.append("curl -X PUT ");

        if (config.artifactoryUsername() != null && config.artifactoryPassword() != null) {
            cmd.append("-u \"").append(config.artifactoryUsername())
               .append(":${ARTIFACTORY_PASSWORD}\" ");
        }

        if (config.artifactoryCertPath() != null) {
            cmd.append("--cacert \"").append(config.artifactoryCertPath()).append("\" ");
        }

        cmd.append("-T \"").append(jarPath.toAbsolutePath()).append("\" ");
        cmd.append("\"").append(config.artifactoryUrl())
           .append("/").append(buildArtifactPath(coord, repo)).append("\"");

        return cmd.toString();
    }

    /**
     * Ajoute les en-têtes d'authentification à la requête.
     */
    private void addAuthHeaders(HttpRequest.Builder builder) {
        if (config.artifactoryUsername() != null && config.artifactoryPassword() != null) {
            String auth = config.artifactoryUsername() + ":" + config.artifactoryPassword();
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
     * Parse la réponse de recherche Artifactory pour extraire les coordonnées Maven.
     */
    private Optional<MavenCoordinate> parseSearchResponse(String json) {
        // Parsing JSON simple pour la réponse Artifactory
        // Format de réponse : {"results":[{"uri":"...","downloadUri":"..."}]}

        Pattern uriPattern = Pattern.compile("\"downloadUri\"\\s*:\\s*\"([^\"]+)\"");
        Matcher matcher = uriPattern.matcher(json);

        if (matcher.find()) {
            String downloadUri = matcher.group(1);
            return parseCoordinatesFromPath(downloadUri);
        }

        return Optional.empty();
    }

    /**
     * Parse la réponse de recherche et extrait les coordonnées Maven ainsi que le repository.
     */
    private Optional<ResolutionResult> parseSearchResponseWithRepo(String json) {
        // Format de réponse : {"results":[{"uri":"...","downloadUri":"...","repo":"..."}]}
        Pattern uriPattern = Pattern.compile("\"downloadUri\"\\s*:\\s*\"([^\"]+)\"");
        Pattern repoPattern = Pattern.compile("\"repo\"\\s*:\\s*\"([^\"]+)\"");

        Matcher uriMatcher = uriPattern.matcher(json);
        Matcher repoMatcher = repoPattern.matcher(json);

        if (uriMatcher.find()) {
            String downloadUri = uriMatcher.group(1);
            String repository = repoMatcher.find() ? repoMatcher.group(1) : extractRepoFromPath(downloadUri);

            Optional<MavenCoordinate> coord = parseCoordinatesFromPath(downloadUri);
            if (coord.isPresent()) {
                return Optional.of(ResolutionResult.of(coord.get(), repository));
            }
        }

        return Optional.empty();
    }

    /**
     * Extrait le nom du repository depuis le path de téléchargement.
     */
    private String extractRepoFromPath(String downloadUri) {
        // Format: http://artifactory/artifactory/repo-name/group/artifact/version/file.jar
        String[] parts = downloadUri.split("/");
        for (int i = 0; i < parts.length; i++) {
            if (parts[i].equals("artifactory") && i + 1 < parts.length) {
                return parts[i + 1];
            }
        }
        return null;
    }

    /**
     * Parse les coordonnées Maven depuis un chemin d'artefact.
     */
    private Optional<MavenCoordinate> parseCoordinatesFromPath(String path) {
        // Format du chemin : .../groupId/artifactId/version/artifactId-version.jar
        Pattern pathPattern = Pattern.compile(".*/([^/]+)/([^/]+)/([^/]+)/([^/]+)\\.jar$");
        Matcher matcher = pathPattern.matcher(path);

        if (matcher.find()) {
            // Cela nous donne les 3 derniers segments de chemin avant le nom de fichier
            // Besoin de reconstruire le chemin complet pour obtenir le groupId
            String[] parts = path.split("/");
            if (parts.length >= 4) {
                String version = parts[parts.length - 2];
                String artifactId = parts[parts.length - 3];

                // Trouver où le groupId commence (après le nom du repository)
                StringBuilder groupId = new StringBuilder();
                boolean foundRepo = false;
                for (int i = 0; i < parts.length - 3; i++) {
                    String part = parts[i];
                    if (part.contains("-local") || part.contains("-remote") ||
                        part.equals("libs-release") || part.equals("libs-snapshot")) {
                        foundRepo = true;
                        continue;
                    }
                    if (foundRepo && !part.isEmpty()) {
                        if (groupId.length() > 0) {
                            groupId.append(".");
                        }
                        groupId.append(part);
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
     * Retourne les statistiques du cache pour débogage.
     */
    public String getCacheStats() {
        return String.format("SHA1 cache: %d entries, Exists cache: %d entries",
            sha1Cache.size(), existsCache.size());
    }

    /**
     * Teste la connexion à Artifactory.
     */
    public boolean testConnection() {
        if (!config.isArtifactoryConfigured()) {
            return false;
        }

        try {
            String url = config.artifactoryUrl() + "/api/system/ping";

            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .GET();

            addAuthHeaders(requestBuilder);

            HttpRequest request = requestBuilder.build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            return response.statusCode() == 200;

        } catch (Exception e) {
            log.warn("Artifactory connection test failed: {}", e.getMessage());
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
