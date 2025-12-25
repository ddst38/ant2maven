package fr.cnam.migration.analyzer;

import fr.cnam.migration.config.MigrationConfig;
import fr.cnam.migration.model.MavenCoordinate;
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
 * Client for JFrog Artifactory with SSL certificate support.
 *
 * Features:
 * - Search artifacts by SHA-1 checksum
 * - Check if artifact exists
 * - Deploy/upload artifacts
 * - Custom SSL certificate support for enterprise environments
 */
public class ArtifactoryClient {

    private static final Logger log = LoggerFactory.getLogger(ArtifactoryClient.class);

    private final MigrationConfig config;
    private final HttpClient httpClient;
    private final Map<String, Optional<MavenCoordinate>> sha1Cache = new ConcurrentHashMap<>();
    private final Map<String, Boolean> existsCache = new ConcurrentHashMap<>();

    public ArtifactoryClient(MigrationConfig config) {
        this.config = config;
        this.httpClient = createHttpClient();
    }

    /**
     * Creates an HTTP client with optional custom SSL certificate.
     */
    private HttpClient createHttpClient() {
        HttpClient.Builder builder = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(30));

        // If a certificate is configured, set up custom SSL context
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
     * Creates an SSL context with the specified certificate.
     */
    private SSLContext createSslContext(Path certPath) throws Exception {
        // Load the certificate
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        Certificate cert;
        try (InputStream is = Files.newInputStream(certPath)) {
            cert = cf.generateCertificate(is);
        }

        // Create a KeyStore containing our trusted certificate
        KeyStore keyStore = KeyStore.getInstance(KeyStore.getDefaultType());
        keyStore.load(null, null);
        keyStore.setCertificateEntry("artifactory", cert);

        // Create TrustManager that trusts our certificate
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(keyStore);

        // Also include the default trusted certificates
        TrustManagerFactory defaultTmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        defaultTmf.init((KeyStore) null);

        // Combine our trust managers with the default ones
        TrustManager[] trustManagers = createCombinedTrustManagers(tmf.getTrustManagers(), defaultTmf.getTrustManagers());

        // Create SSL context
        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(null, trustManagers, null);

        return sslContext;
    }

    /**
     * Combines custom and default trust managers.
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

        // Create a composite trust manager
        return new TrustManager[]{new CompositeX509TrustManager(x509Managers)};
    }

    /**
     * Searches for an artifact by SHA-1 checksum in Artifactory.
     */
    public Optional<MavenCoordinate> searchBySha1(String sha1) {
        if (!config.isArtifactoryConfigured() || sha1 == null || sha1.isBlank()) {
            return Optional.empty();
        }

        return sha1Cache.computeIfAbsent(sha1, this::doSearchBySha1);
    }

    private Optional<MavenCoordinate> doSearchBySha1(String sha1) {
        try {
            // Artifactory AQL search by checksum
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
     * Checks if an artifact exists in Artifactory.
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
     * Deploys/uploads an artifact to Artifactory.
     *
     * @param jarPath Path to the JAR file
     * @param coord Maven coordinates for the artifact
     * @param isSnapshot Whether this is a snapshot version
     * @return true if deployment succeeded
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
     * Generates a deployment script for artifacts that should be uploaded.
     * This is useful when direct upload is not possible (e.g., network restrictions).
     */
    public String generateDeployCommand(Path jarPath, MavenCoordinate coord, boolean isSnapshot) {
        String repo = isSnapshot ? config.artifactorySnapshotRepo() : config.artifactoryReleaseRepo();

        // Generate curl command
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
     * Adds authentication headers to the request.
     */
    private void addAuthHeaders(HttpRequest.Builder builder) {
        if (config.artifactoryUsername() != null && config.artifactoryPassword() != null) {
            String auth = config.artifactoryUsername() + ":" + config.artifactoryPassword();
            String encodedAuth = Base64.getEncoder().encodeToString(auth.getBytes(StandardCharsets.UTF_8));
            builder.header("Authorization", "Basic " + encodedAuth);
        }
    }

    /**
     * Builds the artifact path in the repository.
     */
    private String buildArtifactPath(MavenCoordinate coord, String repo) {
        String groupPath = coord.groupId().replace('.', '/');
        String fileName = coord.artifactId() + "-" + coord.version() + ".jar";
        return repo + "/" + groupPath + "/" + coord.artifactId() + "/" + coord.version() + "/" + fileName;
    }

    /**
     * Parses the Artifactory search response to extract Maven coordinates.
     */
    private Optional<MavenCoordinate> parseSearchResponse(String json) {
        // Simple JSON parsing for Artifactory response
        // Response format: {"results":[{"uri":"...","downloadUri":"..."}]}

        Pattern uriPattern = Pattern.compile("\"downloadUri\"\\s*:\\s*\"([^\"]+)\"");
        Matcher matcher = uriPattern.matcher(json);

        if (matcher.find()) {
            String downloadUri = matcher.group(1);
            return parseCoordinatesFromPath(downloadUri);
        }

        return Optional.empty();
    }

    /**
     * Parses Maven coordinates from an artifact path.
     */
    private Optional<MavenCoordinate> parseCoordinatesFromPath(String path) {
        // Path format: .../groupId/artifactId/version/artifactId-version.jar
        Pattern pathPattern = Pattern.compile(".*/([^/]+)/([^/]+)/([^/]+)/([^/]+)\\.jar$");
        Matcher matcher = pathPattern.matcher(path);

        if (matcher.find()) {
            // This gives us the last 3 path segments before the filename
            // Need to reconstruct the full path to get groupId
            String[] parts = path.split("/");
            if (parts.length >= 4) {
                String version = parts[parts.length - 2];
                String artifactId = parts[parts.length - 3];

                // Find where the groupId starts (after repository name)
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
     * Returns cache statistics for debugging.
     */
    public String getCacheStats() {
        return String.format("SHA1 cache: %d entries, Exists cache: %d entries",
            sha1Cache.size(), existsCache.size());
    }

    /**
     * Tests the connection to Artifactory.
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
     * Composite X509TrustManager that combines multiple trust managers.
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
                    // Try next trust manager
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
                    // Try next trust manager
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
