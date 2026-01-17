package fr.cnam.migration.oss;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Analyseur OSS Index pour evaluer la viabilite long terme des dependances.
 * Combine les informations de versions et de vulnerabilites.
 */
public class OssIndexAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(OssIndexAnalyzer.class);

    private static final int COMMAND_TIMEOUT_MINUTES = 5;

    // Patterns pour parser les sorties Maven
    private static final Pattern VERSION_UPDATE_PATTERN = Pattern.compile(
        "\\s+([\\w.-]+):([\\w.-]+)\\s+\\.+\\s+([\\w.-]+)\\s+->\\s+([\\w.-]+)"
    );
    private static final Pattern DEPENDENCY_PATTERN = Pattern.compile(
        "\\s+([\\w.-]+):([\\w.-]+):jar:([\\w.-]+)"
    );
    private static final Pattern VULN_COMPONENT_PATTERN = Pattern.compile(
        "pkg:maven/([^/]+)/([^@]+)@([^?\\s]+)"
    );
    private static final Pattern VULN_SEVERITY_PATTERN = Pattern.compile(
        "\\[(\\d+)\\]\\s+(\\w+)");

    // Patterns de modules a exclure (distribution, assemblage)
    private static final List<String> EXCLUDED_MODULE_PATTERNS = List.of(
        "-dist", "-distribution", "-assembly", "-package"
    );

    private final boolean verbose;
    private final String ossindexUser;
    private final String ossindexToken;

    public OssIndexAnalyzer(boolean verbose) {
        this(verbose, null, null);
    }

    public OssIndexAnalyzer(boolean verbose, String ossindexUser, String ossindexToken) {
        this.verbose = verbose;
        this.ossindexUser = ossindexUser;
        this.ossindexToken = ossindexToken;
    }

    /**
     * Detecte les modules a exclure (distribution, assemblage).
     */
    private List<String> findExcludedModules(Path projectDir) {
        List<String> excluded = new ArrayList<>();
        Path pomFile = projectDir.resolve("pom.xml");

        if (!Files.exists(pomFile)) {
            return excluded;
        }

        try {
            String pomContent = Files.readString(pomFile);
            // Chercher les modules declares
            Pattern modulePattern = Pattern.compile("<module>([^<]+)</module>");
            Matcher matcher = modulePattern.matcher(pomContent);

            while (matcher.find()) {
                String moduleName = matcher.group(1);
                for (String pattern : EXCLUDED_MODULE_PATTERNS) {
                    if (moduleName.contains(pattern)) {
                        excluded.add(moduleName);
                        log.debug("Module exclu de l'analyse OSS: {}", moduleName);
                        break;
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Impossible de lire le pom.xml: {}", e.getMessage());
        }

        return excluded;
    }

    /**
     * Execute l'analyse OSS Index complete sur un projet Maven.
     */
    public OssAnalysisResult analyze(Path projectDir) {
        log.info("");
        log.info("=".repeat(60));
        log.info("Phase 8: Analyse OSS Index (Viabilite)");
        log.info("=".repeat(60));

        try {
            // Detecter les modules a exclure
            List<String> excludedModules = findExcludedModules(projectDir);
            if (!excludedModules.isEmpty()) {
                log.info("Modules exclus: {}", String.join(", ", excludedModules));
            }

            // Etape 1: Recuperer les dependances et mises a jour disponibles
            log.info("Analyse des versions des dependances...");
            Map<String, DependencyInfo> dependencies = analyzeDependencyVersions(projectDir, excludedModules);
            log.info("  {} dependances analysees", dependencies.size());

            // Etape 2: Analyser les vulnerabilites OSS Index
            log.info("Analyse des vulnerabilites OSS Index...");
            Map<String, VulnerabilityInfo> vulnerabilities = analyzeOssVulnerabilities(projectDir, excludedModules);
            log.info("  {} composants avec vulnerabilites", vulnerabilities.size());

            // Etape 3: Combiner et calculer les scores
            log.info("Calcul des scores de viabilite...");
            List<OssDependencyHealth> healthList = buildHealthList(dependencies, vulnerabilities);

            // Etape 4: Construire le resultat
            OssAnalysisResult result = OssAnalysisResult.from(healthList);
            printSummary(result);

            return result;

        } catch (Exception e) {
            log.error("Erreur lors de l'analyse OSS Index: {}", e.getMessage());
            if (verbose) {
                log.debug("Stack trace:", e);
            }
            return OssAnalysisResult.failed();
        }
    }

    /**
     * Analyse les versions des dependances via Maven.
     */
    private Map<String, DependencyInfo> analyzeDependencyVersions(Path projectDir, List<String> excludedModules) {
        Map<String, DependencyInfo> dependencies = new HashMap<>();

        // Construire les arguments d'exclusion de modules
        List<String> baseArgs = new ArrayList<>();
        if (!excludedModules.isEmpty()) {
            baseArgs.add("-pl");
            baseArgs.add("!" + String.join(",!", excludedModules));
        }

        // D'abord lister toutes les dependances
        List<String> depListArgs = new ArrayList<>(baseArgs);
        depListArgs.addAll(List.of("dependency:list", "-DoutputAbsoluteArtifactFilename=false", "-DincludeScope=compile"));
        String depTreeOutput = runMavenCommand(projectDir, depListArgs.toArray(new String[0]));

        if (depTreeOutput != null) {
            for (String line : depTreeOutput.split("\n")) {
                Matcher m = DEPENDENCY_PATTERN.matcher(line);
                if (m.find()) {
                    String groupId = m.group(1);
                    String artifactId = m.group(2);
                    String version = m.group(3);
                    String key = groupId + ":" + artifactId;
                    dependencies.put(key, new DependencyInfo(groupId, artifactId, version, null));
                }
            }
        }

        // Ensuite recuperer les mises a jour disponibles
        List<String> updateArgs = new ArrayList<>(baseArgs);
        updateArgs.addAll(List.of("versions:display-dependency-updates", "-DprocessDependencyManagement=false"));
        String updatesOutput = runMavenCommand(projectDir, updateArgs.toArray(new String[0]));

        if (updatesOutput != null) {
            for (String line : updatesOutput.split("\n")) {
                Matcher m = VERSION_UPDATE_PATTERN.matcher(line);
                if (m.find()) {
                    String groupId = m.group(1);
                    String artifactId = m.group(2);
                    String currentVersion = m.group(3);
                    String latestVersion = m.group(4);
                    String key = groupId + ":" + artifactId;

                    DependencyInfo existing = dependencies.get(key);
                    if (existing != null) {
                        dependencies.put(key, new DependencyInfo(
                            groupId, artifactId, currentVersion, latestVersion));
                    } else {
                        dependencies.put(key, new DependencyInfo(
                            groupId, artifactId, currentVersion, latestVersion));
                    }
                }
            }
        }

        return dependencies;
    }

    /**
     * Analyse les vulnerabilites via OSS Index.
     */
    private Map<String, VulnerabilityInfo> analyzeOssVulnerabilities(Path projectDir, List<String> excludedModules) {
        Map<String, VulnerabilityInfo> vulnerabilities = new HashMap<>();

        // Construire les arguments avec exclusions
        List<String> args = new ArrayList<>();
        if (!excludedModules.isEmpty()) {
            args.add("-pl");
            args.add("!" + String.join(",!", excludedModules));
        }

        // Creer un settings.xml temporaire si credentials disponibles
        Path tempSettings = null;
        if (ossindexUser != null && !ossindexUser.isBlank() &&
            ossindexToken != null && !ossindexToken.isBlank()) {
            try {
                tempSettings = createOssIndexSettings(projectDir, ossindexUser, ossindexToken);
                args.add("-s");
                args.add(tempSettings.toAbsolutePath().toString());
            } catch (Exception e) {
                log.warn("Impossible de creer le settings.xml temporaire: {}", e.getMessage());
            }
        }

        args.add("org.sonatype.ossindex.maven:ossindex-maven-plugin:audit");
        args.add("-Dossindex.fail=false");

        // Authentification OSS Index via authId (reference au server dans settings.xml)
        if (ossindexUser != null && !ossindexUser.isBlank() &&
            ossindexToken != null && !ossindexToken.isBlank()) {
            args.add("-DossIndex.authId=ossindex");
        }

        String output = runMavenCommand(projectDir, args.toArray(new String[0]));

        // Supprimer le fichier temporaire
        if (tempSettings != null) {
            try {
                Files.deleteIfExists(tempSettings);
            } catch (Exception ignored) {}
        }

        if (output == null) {
            return vulnerabilities;
        }

        // Verifier si l'execution a echoue (dependances non resolvables)
        if (output.contains("Could not resolve dependencies") ||
            output.contains("BUILD FAILURE")) {
            log.warn("Analyse vulnerabilites OSS ignoree: dependances non resolvables");
            return vulnerabilities;
        }

        // Parser la sortie pour extraire les vulnerabilites
        String currentComponent = null;
        int critical = 0, high = 0, medium = 0, low = 0;

        for (String line : output.split("\n")) {
            // Detecter un nouveau composant
            Matcher compMatcher = VULN_COMPONENT_PATTERN.matcher(line);
            if (compMatcher.find()) {
                // Sauvegarder le composant precedent
                if (currentComponent != null && (critical + high + medium + low) > 0) {
                    vulnerabilities.put(currentComponent,
                        new VulnerabilityInfo(critical, high, medium, low));
                }
                // Nouveau composant
                currentComponent = compMatcher.group(1) + ":" + compMatcher.group(2);
                critical = 0; high = 0; medium = 0; low = 0;
                continue;
            }

            // Detecter les severites (format: [cvss] SEVERITY)
            if (currentComponent != null) {
                String lineLower = line.toLowerCase();
                if (lineLower.contains("critical")) critical++;
                else if (lineLower.contains("high")) high++;
                else if (lineLower.contains("medium")) medium++;
                else if (lineLower.contains("low")) low++;
            }
        }

        // Sauvegarder le dernier composant
        if (currentComponent != null && (critical + high + medium + low) > 0) {
            vulnerabilities.put(currentComponent,
                new VulnerabilityInfo(critical, high, medium, low));
        }

        return vulnerabilities;
    }

    /**
     * Construit la liste des OssDependencyHealth.
     */
    private List<OssDependencyHealth> buildHealthList(
            Map<String, DependencyInfo> dependencies,
            Map<String, VulnerabilityInfo> vulnerabilities) {

        List<OssDependencyHealth> healthList = new ArrayList<>();

        for (Map.Entry<String, DependencyInfo> entry : dependencies.entrySet()) {
            DependencyInfo dep = entry.getValue();
            VulnerabilityInfo vuln = vulnerabilities.getOrDefault(entry.getKey(),
                new VulnerabilityInfo(0, 0, 0, 0));

            // Calculer les versions en retard
            int[] versionDiff = calculateVersionDiff(dep.currentVersion, dep.latestVersion);

            // Estimer la date de release (heuristique basee sur la version)
            LocalDate releaseDate = estimateReleaseDate(dep.currentVersion);

            // Calculer le score de sante
            double score = OssDependencyHealth.calculateHealthScore(
                releaseDate,
                versionDiff[0], versionDiff[1],
                vuln.critical, vuln.high, vuln.medium, vuln.low,
                0 // Popularite non disponible sans API externe
            );

            healthList.add(new OssDependencyHealth(
                dep.groupId,
                dep.artifactId,
                dep.currentVersion,
                dep.latestVersion,
                releaseDate,
                null, // Date derniere version non disponible
                versionDiff[0],
                versionDiff[1],
                versionDiff[2],
                vuln.critical,
                vuln.high,
                vuln.medium,
                vuln.low,
                0, // Popularite non disponible
                score
            ));
        }

        // Trier par score croissant (les plus critiques en premier)
        healthList.sort(Comparator.comparingDouble(OssDependencyHealth::healthScore));

        return healthList;
    }

    /**
     * Calcule la difference de versions [majeur, mineur, patch].
     */
    private int[] calculateVersionDiff(String current, String latest) {
        if (current == null || latest == null || current.equals(latest)) {
            return new int[]{0, 0, 0};
        }

        try {
            int[] currentParts = parseVersion(current);
            int[] latestParts = parseVersion(latest);

            int majorDiff = Math.max(0, latestParts[0] - currentParts[0]);
            int minorDiff = majorDiff > 0 ? 0 : Math.max(0, latestParts[1] - currentParts[1]);
            int patchDiff = (majorDiff > 0 || minorDiff > 0) ? 0 :
                Math.max(0, latestParts[2] - currentParts[2]);

            return new int[]{majorDiff, minorDiff, patchDiff};
        } catch (Exception e) {
            return new int[]{0, 0, 0};
        }
    }

    /**
     * Parse une version en [majeur, mineur, patch].
     */
    private int[] parseVersion(String version) {
        // Nettoyer la version (enlever suffixes comme -RELEASE, .Final, etc.)
        String cleaned = version.replaceAll("[^0-9.].*", "");
        String[] parts = cleaned.split("\\.");
        int[] result = new int[3];
        for (int i = 0; i < Math.min(parts.length, 3); i++) {
            try {
                result[i] = Integer.parseInt(parts[i]);
            } catch (NumberFormatException e) {
                result[i] = 0;
            }
        }
        return result;
    }

    /**
     * Estime la date de release basee sur des heuristiques.
     * Sans acces a Maven Central API, on utilise une estimation.
     */
    private LocalDate estimateReleaseDate(String version) {
        // Heuristique: versions anciennes = dates anciennes
        // Cette estimation sera amelioree si on ajoute l'API Maven Central
        return null; // Retourne null si non disponible
    }

    /**
     * Execute une commande Maven et retourne la sortie.
     */
    private String runMavenCommand(Path projectDir, String... goals) {
        return runMavenCommand(projectDir, null, goals);
    }

    /**
     * Execute une commande Maven avec des variables d'environnement supplementaires.
     */
    private String runMavenCommand(Path projectDir, Map<String, String> extraEnv, String... goals) {
        try {
            List<String> command = new ArrayList<>();
            command.add(getMvnCommand(projectDir));
            command.addAll(Arrays.asList(goals));
            command.add("-B"); // Mode batch
            // Pas de -q pour pouvoir parser la sortie

            if (verbose) {
                log.debug("Commande: {}", String.join(" ", command));
            }

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.directory(projectDir.toFile());
            pb.redirectErrorStream(true);

            // Ajouter les variables d'environnement supplementaires
            if (extraEnv != null && !extraEnv.isEmpty()) {
                pb.environment().putAll(extraEnv);
            }

            Process process = pb.start();
            StringBuilder output = new StringBuilder();

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                    if (verbose) {
                        log.debug("[mvn] {}", line);
                    }
                }
            }

            boolean finished = process.waitFor(COMMAND_TIMEOUT_MINUTES, TimeUnit.MINUTES);
            if (!finished) {
                process.destroyForcibly();
                log.warn("Timeout commande Maven");
                return null;
            }

            return output.toString();

        } catch (Exception e) {
            log.warn("Erreur commande Maven: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Cree un fichier settings.xml temporaire avec les credentials OSS Index,
     * en fusionnant avec le settings.xml existant du projet ou de l'utilisateur.
     */
    private Path createOssIndexSettings(Path projectDir, String username, String token) throws Exception {
        // Chercher le settings.xml existant (priorite au .mvn/wrapper du projet migre)
        Path mvnWrapperSettings = projectDir.resolve(".mvn/wrapper/settings.xml");
        Path projectSettings = projectDir.resolve("settings.xml");
        Path userSettings = Path.of(System.getProperty("user.home"), ".m2", "settings.xml");

        String baseContent = null;
        if (Files.exists(mvnWrapperSettings)) {
            baseContent = Files.readString(mvnWrapperSettings);
            log.debug("Utilisation du settings.xml de .mvn/wrapper/");
        } else if (Files.exists(projectSettings)) {
            baseContent = Files.readString(projectSettings);
        } else if (Files.exists(userSettings)) {
            baseContent = Files.readString(userSettings);
        }

        String ossIndexServer = """
                <server>
                  <id>ossindex</id>
                  <username>%s</username>
                  <password>%s</password>
                </server>""".formatted(username, token);

        String settingsContent;
        if (baseContent != null && baseContent.contains("<servers>")) {
            // Ajouter le serveur ossindex dans la section servers existante
            settingsContent = baseContent.replace("</servers>", ossIndexServer + "\n    </servers>");
        } else if (baseContent != null && baseContent.contains("</settings>")) {
            // Creer la section servers
            String serversSection = """
              <servers>
            %s
              </servers>
            </settings>""".formatted(ossIndexServer);
            settingsContent = baseContent.replace("</settings>", serversSection);
        } else {
            // Creer un settings.xml complet
            settingsContent = """
                <?xml version="1.0" encoding="UTF-8"?>
                <settings xmlns="http://maven.apache.org/SETTINGS/1.0.0"
                          xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                          xsi:schemaLocation="http://maven.apache.org/SETTINGS/1.0.0
                                              http://maven.apache.org/xsd/settings-1.0.0.xsd">
                  <servers>
                %s
                  </servers>
                </settings>
                """.formatted(ossIndexServer);
        }

        Path tempFile = Files.createTempFile("ossindex-settings", ".xml");
        Files.writeString(tempFile, settingsContent);
        log.debug("Settings OSS Index temporaire: {}", tempFile);
        if (verbose) {
            log.debug("Contenu settings:\n{}", settingsContent);
        }
        return tempFile;
    }

    /**
     * Determine la commande Maven a utiliser.
     */
    private String getMvnCommand(Path projectDir) {
        Path mvnw = projectDir.resolve("mvnw");
        if (Files.isExecutable(mvnw)) {
            return mvnw.toAbsolutePath().toString();
        }
        Path mvnwCmd = projectDir.resolve("mvnw.cmd");
        if (Files.exists(mvnwCmd)) {
            return mvnwCmd.toAbsolutePath().toString();
        }
        return "mvn";
    }

    /**
     * Affiche le resume de l'analyse.
     */
    private void printSummary(OssAnalysisResult result) {
        log.info("");
        log.info("Resume de l'analyse OSS Index (Viabilite):");
        log.info("  Score global:        {}/10 ({})",
            String.format("%.1f", result.overallHealthScore()),
            result.overallStatus());
        log.info("  Dependances:         {}", result.totalDependencies());
        log.info("  Distribution:");
        log.info("    Saines:            {}", result.healthDistribution().getOrDefault("HEALTHY", 0));
        log.info("    A surveiller:      {}", result.healthDistribution().getOrDefault("MONITOR", 0));
        log.info("    Risquees:          {}", result.healthDistribution().getOrDefault("RISKY", 0));
        log.info("    Critiques:         {}", result.healthDistribution().getOrDefault("CRITICAL", 0));
        log.info("  Obsoletes:           {} (version majeure en retard)", result.outdatedCount());
        log.info("  Vulnerables:         {} (avec CVE OSS)", result.vulnerableCount());
        log.info("  Inactives:           {} (release > 2 ans)", result.staleCount());

        if (result.hasCriticalAlerts()) {
            log.warn("  {} alertes critiques!", result.criticalAlerts().size());
            for (OssDependencyHealth alert : result.criticalAlerts().stream().limit(5).toList()) {
                log.warn("    - {} (score: {})", alert.coordinates(),
                    String.format("%.1f", alert.healthScore()));
            }
        }
    }

    // Classes internes pour le parsing
    private record DependencyInfo(String groupId, String artifactId,
                                  String currentVersion, String latestVersion) {}

    private record VulnerabilityInfo(int critical, int high, int medium, int low) {}
}
