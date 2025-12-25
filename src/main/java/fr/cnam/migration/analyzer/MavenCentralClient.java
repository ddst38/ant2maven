package fr.cnam.migration.analyzer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fr.cnam.migration.model.MavenCoordinate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Client for querying Maven Central repository.
 */
public class MavenCentralClient {

    private static final Logger log = LoggerFactory.getLogger(MavenCentralClient.class);
    private static final String SEARCH_API = "https://search.maven.org/solrsearch/select";
    private static final String REPO_BASE = "https://repo1.maven.org/maven2";

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final Map<String, Optional<MavenCoordinate>> sha1Cache;
    private final Map<String, Boolean> existsCache;

    public MavenCentralClient() {
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
        this.objectMapper = new ObjectMapper();
        this.sha1Cache = new ConcurrentHashMap<>();
        this.existsCache = new ConcurrentHashMap<>();
    }

    /**
     * Searches Maven Central for an artifact by SHA1 checksum.
     */
    public Optional<MavenCoordinate> searchBySha1(String sha1) {
        if (sha1 == null || sha1.isEmpty()) {
            return Optional.empty();
        }

        return sha1Cache.computeIfAbsent(sha1, this::doSha1Search);
    }

    private Optional<MavenCoordinate> doSha1Search(String sha1) {
        try {
            String url = SEARCH_API + "?q=1:" + sha1 + "&rows=1&wt=json";

            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .GET()
                .build();

            HttpResponse<String> response = httpClient.send(request,
                HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                return parseSearchResponse(response.body());
            } else {
                log.debug("Maven Central returned {} for SHA1 {}", response.statusCode(), sha1);
            }
        } catch (Exception e) {
            log.debug("SHA1 search failed for {}: {}", sha1, e.getMessage());
        }

        return Optional.empty();
    }

    private Optional<MavenCoordinate> parseSearchResponse(String json) {
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode response = root.get("response");
            if (response == null) {
                return Optional.empty();
            }

            int numFound = response.get("numFound").asInt();
            if (numFound == 0) {
                return Optional.empty();
            }

            JsonNode docs = response.get("docs");
            if (docs == null || !docs.isArray() || docs.isEmpty()) {
                return Optional.empty();
            }

            JsonNode doc = docs.get(0);
            String groupId = doc.get("g").asText();
            String artifactId = doc.get("a").asText();
            String version = doc.get("v").asText();

            log.debug("Found on Maven Central: {}:{}:{}", groupId, artifactId, version);
            return Optional.of(new MavenCoordinate(groupId, artifactId, version));

        } catch (Exception e) {
            log.debug("Failed to parse Maven Central response: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Checks if an artifact exists on Maven Central.
     */
    public boolean exists(MavenCoordinate coord) {
        if (coord == null) {
            return false;
        }

        String cacheKey = coord.toGav();
        return existsCache.computeIfAbsent(cacheKey, k -> checkExists(coord));
    }

    private boolean checkExists(MavenCoordinate coord) {
        try {
            String pomUrl = String.format("%s/%s/%s/%s/%s-%s.pom",
                REPO_BASE,
                coord.groupId().replace('.', '/'),
                coord.artifactId(),
                coord.version(),
                coord.artifactId(),
                coord.version()
            );

            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(pomUrl))
                .method("HEAD", HttpRequest.BodyPublishers.noBody())
                .timeout(Duration.ofSeconds(10))
                .build();

            HttpResponse<Void> response = httpClient.send(request,
                HttpResponse.BodyHandlers.discarding());

            boolean exists = response.statusCode() == 200;
            log.debug("Maven Central check for {}: {}", coord.toGav(), exists ? "exists" : "not found");
            return exists;

        } catch (Exception e) {
            log.debug("Existence check failed for {}: {}", coord.toGav(), e.getMessage());
            return false;
        }
    }

    /**
     * Searches Maven Central by artifact name (less reliable than SHA1).
     */
    public Optional<MavenCoordinate> searchByName(String artifactId, String version) {
        try {
            String query = String.format("a:%s AND v:%s", artifactId, version);
            String url = SEARCH_API + "?q=" + java.net.URLEncoder.encode(query, "UTF-8") + "&rows=1&wt=json";

            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .GET()
                .build();

            HttpResponse<String> response = httpClient.send(request,
                HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                return parseSearchResponse(response.body());
            }
        } catch (Exception e) {
            log.debug("Name search failed for {}:{}: {}", artifactId, version, e.getMessage());
        }

        return Optional.empty();
    }

    /**
     * Returns cache statistics.
     */
    public String getCacheStats() {
        return String.format("SHA1 cache: %d entries, Exists cache: %d entries",
            sha1Cache.size(), existsCache.size());
    }
}
