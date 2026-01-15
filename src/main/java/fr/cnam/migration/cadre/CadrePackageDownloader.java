package fr.cnam.migration.cadre;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fr.cnam.migration.config.MigrationConfig;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import java.io.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Telecharge et extrait les packages cadre depuis Artifactory ou Nexus.
 * Les packages sont des tar.gz contenant des JARs qui seront extraits dans libcadre/<tag>/
 */
public class CadrePackageDownloader {

    private static final Logger log = LoggerFactory.getLogger(CadrePackageDownloader.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(120);
    private static final Pattern TAG_PATTERN = Pattern.compile("^([a-zA-Z0-9]{2,4})(\\d{6})([a-zA-Z0-9]{1,2})$");

    private final MigrationConfig config;
    private final HttpClient httpClient;
    private final ObjectMapper mapper;

    public CadrePackageDownloader(MigrationConfig config) {
        this.config = config;
        this.mapper = new ObjectMapper();
        this.httpClient = createHttpClient();
    }

    /**
     * Parse un tag de dependance cadre.
     * Format : {codeModule}{versionXXYYZZ}{typeLivrable}
     * Exemple : NOOC040000J -> TAGInfo(NOOC, 040000, J)
     */
    public static TAGInfo parseTag(String tag) {
        if (tag == null || tag.isBlank()) {
            return null;
        }

        String cleanTag = tag.trim();
        if (cleanTag.length() < 9 || cleanTag.length() > 12) {
            log.debug("Tag invalide (longueur incorrecte) : {}", tag);
            return null;
        }

        Matcher matcher = TAG_PATTERN.matcher(cleanTag);
        if (!matcher.matches()) {
            log.debug("Tag non conforme au pattern : {}", tag);
            return null;
        }

        return new TAGInfo(
            matcher.group(1),  // codeModule
            matcher.group(2),  // versionModule
            matcher.group(3)   // typeLivrable
        );
    }

    /**
     * Telecharge et extrait les JARs d'un package cadre.
     *
     * @param tag Le tag de la dependance (ex: NOOC040000J)
     * @param libcadreDir Le repertoire libcadre/ de destination
     * @return Le nombre de JARs extraits, ou -1 en cas d'erreur
     */
    public int downloadAndExtract(String tag, Path libcadreDir) {
        log.info("[SCAN-CADRE] === Traitement du tag : {} ===", tag);

        TAGInfo tagInfo = parseTag(tag);
        if (tagInfo == null) {
            log.warn("[SCAN-CADRE] Tag invalide (format non reconnu), ignore : {}", tag);
            return -1;
        }

        log.info("[SCAN-CADRE] Tag parse : codeModule={}, version={}, type={}",
            tagInfo.codeModule(), tagInfo.versionModule(), tagInfo.typeLivrable());
        log.info("[SCAN-CADRE] Nom artefact recherche : {}", tagInfo.getArtifactSearchName());

        Path tagDir = libcadreDir.resolve(tag);
        try {
            Files.createDirectories(tagDir);
            log.info("[SCAN-CADRE] Repertoire cible : {}", tagDir);

            // Rechercher le package
            log.info("[SCAN-CADRE] Recherche du package sur {}...", getActiveRepository());
            Optional<URI> downloadUri = searchPackage(tagInfo);
            if (downloadUri.isEmpty()) {
                log.warn("[SCAN-CADRE] Package non trouve pour le tag {} sur {}", tag, getActiveRepository());
                return -1;
            }

            log.info("[SCAN-CADRE] URL de telechargement : {}", downloadUri.get());

            // Telecharger le tar.gz
            Path tarGzFile = downloadFile(downloadUri.get(), tagDir);
            if (tarGzFile == null) {
                log.error("[SCAN-CADRE] Echec du telechargement pour {}", tag);
                return -1;
            }

            log.info("[SCAN-CADRE] Fichier telecharge : {}", tarGzFile);

            // Extraire les JARs
            int jarCount = extractJarsFromTarGz(tarGzFile, tagDir);
            log.info("[SCAN-CADRE] Tag {} : {} JARs extraits dans {}", tag, jarCount, tagDir);
            return jarCount;

        } catch (Exception e) {
            log.error("[SCAN-CADRE] Erreur lors du traitement du tag {} : {}", tag, e.getMessage(), e);
            return -1;
        }
    }

    /**
     * Recherche un package sur Artifactory ou Nexus.
     */
    private Optional<URI> searchPackage(TAGInfo tagInfo) {
        String searchName = tagInfo.getArtifactSearchName();
        log.info("[SCAN-CADRE] Recherche de l'artefact : {}", searchName);

        if (isNexusConfigured()) {
            log.info("[SCAN-CADRE] Nexus configure : {}", config.nexusUrl());
            return searchOnNexus(searchName);
        } else if (isArtifactoryConfigured()) {
            log.info("[SCAN-CADRE] Artifactory configure : {}", config.artifactoryUrl());
            return searchOnArtifactory(searchName);
        }

        log.warn("[SCAN-CADRE] Aucun repository configure (Artifactory ou Nexus)");
        return Optional.empty();
    }

    /**
     * Recherche sur Nexus via l'API /service/rest/v1/search/assets
     */
    private Optional<URI> searchOnNexus(String searchName) {
        String url = config.nexusUrl() + "/service/rest/v1/search/assets?q=" + searchName;
        log.info("[SCAN-CADRE] Requete Nexus : {}", url);

        try {
            HttpRequest request = buildRequest(url, true);
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            log.info("[SCAN-CADRE] Reponse Nexus : HTTP {}", response.statusCode());

            if (response.statusCode() != 200) {
                log.warn("[SCAN-CADRE] Recherche Nexus echouee : HTTP {} - {}", response.statusCode(), response.body());
                return Optional.empty();
            }

            String body = response.body();
            log.info("[SCAN-CADRE] Taille reponse : {} caracteres", body.length());

            JsonNode root = mapper.readTree(body);
            JsonNode items = root.path("items");

            if (!items.isArray() || items.isEmpty()) {
                log.warn("[SCAN-CADRE] Aucun resultat Nexus pour : {}", searchName);
                log.info("[SCAN-CADRE] Reponse complete : {}", body.substring(0, Math.min(500, body.length())));
                return Optional.empty();
            }

            log.info("[SCAN-CADRE] {} resultats trouves sur Nexus", items.size());

            // Chercher un tar.gz
            for (JsonNode item : items) {
                String downloadUrl = item.path("downloadUrl").asText("");
                String path = item.path("path").asText("");
                log.info("[SCAN-CADRE] Item : path={}, downloadUrl={}", path, downloadUrl);

                if (!downloadUrl.isBlank() && downloadUrl.endsWith(".tar.gz")) {
                    log.info("[SCAN-CADRE] Package tar.gz trouve sur Nexus : {}", downloadUrl);
                    return Optional.of(URI.create(downloadUrl));
                }
            }

            log.warn("[SCAN-CADRE] Aucun tar.gz trouve parmi les {} resultats Nexus pour : {}", items.size(), searchName);
            return Optional.empty();

        } catch (Exception e) {
            log.error("[SCAN-CADRE] Erreur recherche Nexus : {}", e.getMessage(), e);
            return Optional.empty();
        }
    }

    /**
     * Recherche sur Artifactory via l'API /api/search/artifact
     */
    private Optional<URI> searchOnArtifactory(String searchName) {
        String url = config.artifactoryUrl() + "/api/search/artifact?name=" + searchName;
        log.info("[SCAN-CADRE] Requete Artifactory : {}", url);

        try {
            HttpRequest request = buildRequest(url, false);
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            log.info("[SCAN-CADRE] Reponse Artifactory : HTTP {}", response.statusCode());

            if (response.statusCode() != 200) {
                log.warn("[SCAN-CADRE] Recherche Artifactory echouee : HTTP {} - {}", response.statusCode(), response.body());
                return Optional.empty();
            }

            String body = response.body();
            log.info("[SCAN-CADRE] Taille reponse : {} caracteres", body.length());

            JsonNode root = mapper.readTree(body);
            JsonNode results = root.path("results");

            if (!results.isArray() || results.isEmpty()) {
                log.warn("[SCAN-CADRE] Aucun resultat Artifactory pour : {}", searchName);
                log.info("[SCAN-CADRE] Reponse complete : {}", body.substring(0, Math.min(500, body.length())));
                return Optional.empty();
            }

            log.info("[SCAN-CADRE] {} resultats trouves sur Artifactory", results.size());

            // Chercher une URI storage pour tar.gz
            for (JsonNode result : results) {
                String uri = result.path("uri").asText("");
                log.info("[SCAN-CADRE] Result : uri={}", uri);

                if (!uri.isBlank() && uri.contains("/api/storage/") && uri.endsWith(".tar.gz")) {
                    log.info("[SCAN-CADRE] URI storage tar.gz trouvee : {}", uri);
                    return fetchDownloadUriFromStorage(URI.create(uri));
                }
            }

            log.warn("[SCAN-CADRE] Aucun tar.gz trouve parmi les {} resultats Artifactory pour : {}", results.size(), searchName);
            return Optional.empty();

        } catch (Exception e) {
            log.error("[SCAN-CADRE] Erreur recherche Artifactory : {}", e.getMessage(), e);
            return Optional.empty();
        }
    }

    /**
     * Recupere le downloadUri depuis l'API Storage d'Artifactory.
     */
    private Optional<URI> fetchDownloadUriFromStorage(URI storageUri) {
        try {
            HttpRequest request = buildRequest(storageUri.toString(), false);
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.warn("Storage API Artifactory echouee : HTTP {}", response.statusCode());
                return Optional.empty();
            }

            JsonNode root = mapper.readTree(response.body());
            String downloadUri = root.path("downloadUri").asText("");

            if (downloadUri.isBlank()) {
                log.warn("downloadUri absent dans la reponse Storage");
                return Optional.empty();
            }

            return Optional.of(URI.create(downloadUri));

        } catch (Exception e) {
            log.error("Erreur Storage API Artifactory : {}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Telecharge un fichier depuis une URI.
     */
    private Path downloadFile(URI downloadUri, Path targetDir) {
        String fileName = Path.of(downloadUri.getPath()).getFileName().toString();
        if (fileName.isBlank()) {
            fileName = "package.tar.gz";
        }
        Path targetFile = targetDir.resolve(fileName);

        log.info("[SCAN-CADRE] Telechargement : {}", downloadUri);

        try {
            // Essayer d'abord sans authentification (repo public)
            HttpRequest request = HttpRequest.newBuilder()
                .uri(downloadUri)
                .timeout(TIMEOUT)
                .header("User-Agent", "ant2maven/1.0")
                .GET()
                .build();

            HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());

            // Si 401/403, réessayer avec authentification
            if (response.statusCode() == 401 || response.statusCode() == 403) {
                log.info("[SCAN-CADRE] Acces refuse sans auth, tentative avec authentification...");
                boolean isNexus = downloadUri.toString().contains(config.nexusUrl() != null ? config.nexusUrl() : "nexus");
                request = buildRequest(downloadUri.toString(), isNexus);
                response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            }

            if (response.statusCode() != 200) {
                log.error("[SCAN-CADRE] Telechargement echoue : HTTP {}", response.statusCode());
                return null;
            }

            try (InputStream in = response.body()) {
                Files.copy(in, targetFile, StandardCopyOption.REPLACE_EXISTING);
            }

            log.info("[SCAN-CADRE] Fichier telecharge : {} ({} octets)", targetFile, Files.size(targetFile));
            return targetFile;

        } catch (Exception e) {
            log.error("[SCAN-CADRE] Erreur telechargement : {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * Extrait les JARs d'un fichier tar.gz.
     */
    private int extractJarsFromTarGz(Path tarGzFile, Path targetDir) throws IOException {
        Path workDir = targetDir.resolve("_work_extract");
        Files.createDirectories(workDir);

        int jarCount = 0;

        try {
            // Decompresser tar.gz -> tar
            String baseName = tarGzFile.getFileName().toString();
            if (baseName.endsWith(".gz")) {
                baseName = baseName.substring(0, baseName.length() - 3);
            }
            Path tarFile = workDir.resolve(baseName);

            try (InputStream fis = Files.newInputStream(tarGzFile);
                 BufferedInputStream bis = new BufferedInputStream(fis);
                 GzipCompressorInputStream gzipIn = new GzipCompressorInputStream(bis);
                 OutputStream outTar = Files.newOutputStream(tarFile,
                     StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                gzipIn.transferTo(outTar);
            }

            // Extraire le tar
            Path extractedDir = workDir.resolve("extracted");
            Files.createDirectories(extractedDir);

            try (InputStream tarFis = Files.newInputStream(tarFile);
                 BufferedInputStream tarBis = new BufferedInputStream(tarFis);
                 TarArchiveInputStream tarIn = new TarArchiveInputStream(tarBis)) {

                TarArchiveEntry entry;
                byte[] buffer = new byte[8192];

                while ((entry = tarIn.getNextTarEntry()) != null) {
                    String entryName = entry.getName();

                    // Protection TarSlip : rejeter uniquement les chemins avec .. ou absolus
                    if (entryName.contains("..") || entryName.startsWith("/")) {
                        log.warn("[SCAN-CADRE] Entree TAR dangereuse ignoree : {}", entryName);
                        continue;
                    }

                    Path outPath = extractedDir.resolve(entryName).normalize();

                    if (entry.isDirectory()) {
                        Files.createDirectories(outPath);
                    } else {
                        Files.createDirectories(outPath.getParent());
                        try (OutputStream out = Files.newOutputStream(outPath)) {
                            int read;
                            while ((read = tarIn.read(buffer)) != -1) {
                                out.write(buffer, 0, read);
                            }
                        }
                    }
                }
            }

            // Copier les JARs vers le repertoire cible
            try (var walk = Files.walk(workDir)) {
                List<Path> jars = walk
                    .filter(p -> p.toString().toLowerCase().endsWith(".jar"))
                    .toList();

                for (Path jar : jars) {
                    Path dest = targetDir.resolve(jar.getFileName());
                    Files.copy(jar, dest, StandardCopyOption.REPLACE_EXISTING);
                    jarCount++;
                }
            }

        } finally {
            // Nettoyage
            deleteRecursively(workDir);
            Files.deleteIfExists(tarGzFile);
        }

        return jarCount;
    }

    /**
     * Supprime recursivement un repertoire.
     */
    private void deleteRecursively(Path dir) {
        if (dir == null || !Files.exists(dir)) {
            return;
        }

        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder())
                .forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException e) {
                        log.debug("Impossible de supprimer : {}", path);
                    }
                });
        } catch (IOException e) {
            log.debug("Erreur nettoyage : {}", e.getMessage());
        }
    }

    /**
     * Construit une requete HTTP avec authentification si configuree.
     */
    private HttpRequest buildRequest(String url, boolean useNexus) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(TIMEOUT)
            .GET();

        // Ajouter authentification Basic si configuree
        String auth = getBasicAuth(useNexus);
        if (auth != null) {
            builder.header("Authorization", auth);
        }

        return builder.build();
    }

    private String getBasicAuth(boolean useNexus) {
        String user, password;
        if (useNexus) {
            user = config.nexusUsername();
            password = config.nexusPassword();
        } else {
            user = config.artifactoryUsername();
            password = config.artifactoryPassword();
        }

        if (user != null && !user.isBlank() && password != null && !password.isBlank()) {
            String credentials = user + ":" + password;
            return "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
        }
        return null;
    }

    /**
     * Cree un HttpClient avec support SSL personnalise si necessaire.
     */
    private HttpClient createHttpClient() {
        try {
            Path certPath = isNexusConfigured() ? config.nexusCertPath() : config.artifactoryCertPath();

            if (certPath != null && Files.exists(certPath)) {
                SSLContext sslContext = createSSLContext(certPath);
                return HttpClient.newBuilder()
                    .connectTimeout(TIMEOUT)
                    .sslContext(sslContext)
                    .build();
            }
        } catch (Exception e) {
            log.warn("Erreur configuration SSL, utilisation du client par defaut : {}", e.getMessage());
        }

        return HttpClient.newBuilder()
            .connectTimeout(TIMEOUT)
            .build();
    }

    private SSLContext createSSLContext(Path certPath) throws Exception {
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        X509Certificate cert;
        try (InputStream certIs = Files.newInputStream(certPath)) {
            cert = (X509Certificate) cf.generateCertificate(certIs);
        }

        KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
        ks.load(null, null);
        ks.setCertificateEntry("custom-cert", cert);

        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(ks);

        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(null, tmf.getTrustManagers(), null);

        return sslContext;
    }

    private boolean isNexusConfigured() {
        return config.nexusUrl() != null && !config.nexusUrl().isBlank();
    }

    private boolean isArtifactoryConfigured() {
        return config.artifactoryUrl() != null && !config.artifactoryUrl().isBlank();
    }

    private String getActiveRepository() {
        if (isNexusConfigured()) return "Nexus";
        if (isArtifactoryConfigured()) return "Artifactory";
        return "aucun";
    }
}
