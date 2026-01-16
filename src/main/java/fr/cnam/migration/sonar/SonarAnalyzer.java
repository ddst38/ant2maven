package fr.cnam.migration.sonar;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Analyseur SonarQube pour projets Maven.
 * Execute mvn sonar:sonar puis recupere les resultats via l'API.
 */
public class SonarAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(SonarAnalyzer.class);

    private static final int MAX_ISSUES = 500;
    private static final int POLLING_INTERVAL_MS = 5000;
    private static final int MAX_POLLING_ATTEMPTS = 24; // 2 minutes max (24 * 5s)

    private final String sonarUrl;
    private final String sonarToken;
    private final String projectKey;
    private final boolean verbose;
    private final SonarQubeClient client;

    public SonarAnalyzer(String sonarUrl, String sonarToken, String projectKey, boolean verbose) {
        this.sonarUrl = sonarUrl;
        this.sonarToken = sonarToken;
        this.projectKey = projectKey;
        this.verbose = verbose;
        this.client = new SonarQubeClient(sonarUrl, sonarToken, verbose);
    }

    /**
     * Execute l'analyse SonarQube complete sur un projet Maven.
     *
     * @param projectDir Repertoire du projet Maven migre
     * @return Resultat de l'analyse
     */
    public SonarAnalysisResult analyze(Path projectDir) {
        log.info("");
        log.info("=".repeat(60));
        log.info("Phase 7: Analyse SonarQube");
        log.info("=".repeat(60));

        // Verifier la connexion
        log.info("Verification de la connexion a SonarQube...");
        if (!client.testConnection()) {
            log.warn("Impossible de se connecter a SonarQube: {}", sonarUrl);
            return SonarAnalysisResult.failed();
        }
        log.info("  Connexion OK: {}", sonarUrl);

        // Determiner la cle du projet
        String effectiveKey = projectKey != null ? projectKey : projectDir.getFileName().toString();
        log.info("  Cle du projet: {}", effectiveKey);

        // Lancer l'analyse Maven
        log.info("Lancement de l'analyse SonarQube...");
        boolean analysisLaunched = runMavenSonar(projectDir, effectiveKey);
        if (!analysisLaunched) {
            log.warn("Echec du lancement de l'analyse Maven");
            // Continuer quand meme pour recuperer les resultats existants
        }

        // Attendre la fin de l'analyse
        log.info("Attente de la fin de l'analyse...");
        boolean analysisCompleted = waitForAnalysisCompletion(effectiveKey);
        if (!analysisCompleted) {
            log.warn("L'analyse ne s'est pas terminee dans le delai imparti");
            // Continuer pour recuperer les resultats partiels
        }

        // Recuperer les metriques
        log.info("Recuperation des metriques...");
        SonarMetrics metrics = client.getMetrics(effectiveKey);
        log.info("  Dette technique: {}", metrics.formattedDebt());
        log.info("  Maintainability: {}", metrics.maintainabilityRating());
        log.info("  Reliability:     {}", metrics.reliabilityRating());
        log.info("  Security:        {}", metrics.securityRating());

        // Recuperer les issues
        log.info("Recuperation des issues...");
        List<SonarIssue> issues = client.getIssues(effectiveKey, MAX_ISSUES);
        log.info("  {} issues trouvees", issues.size());

        // Recuperer le quality gate
        String qualityGate = client.getQualityGateStatus(effectiveKey);
        log.info("  Quality Gate: {}", qualityGate != null ? qualityGate : "N/A");

        // Recuperer la date d'analyse
        String analysisDate = client.getLastAnalysisDate(effectiveKey);

        // Construire le resultat
        SonarAnalysisResult result = SonarAnalysisResult.from(
            metrics, issues, effectiveKey, analysisDate, qualityGate
        );

        printSummary(result);
        return result;
    }

    /**
     * Execute mvn sonar:sonar sur le projet.
     */
    private boolean runMavenSonar(Path projectDir, String projectKey) {
        try {
            List<String> command = new ArrayList<>();
            command.add(getMvnCommand(projectDir));
            // Utiliser les coordonnees completes du plugin pour eviter l'erreur "No plugin found"
            command.add("org.sonarsource.scanner.maven:sonar-maven-plugin:sonar");
            command.add("-Dsonar.host.url=" + sonarUrl);
            // Utiliser sonar.login (compatible toutes versions) au lieu de sonar.token
            command.add("-Dsonar.login=" + sonarToken);
            command.add("-Dsonar.projectKey=" + projectKey);
            command.add("-Dsonar.projectName=" + projectKey);

            if (verbose) {
                log.debug("Commande: {}", String.join(" ", command));
            }

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.directory(projectDir.toFile());
            pb.redirectErrorStream(true);

            Process process = pb.start();

            // Lire la sortie en arriere-plan
            Thread outputReader = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (verbose) {
                            log.debug("[mvn] {}", line);
                        } else if (line.contains("ANALYSIS SUCCESSFUL") ||
                                   line.contains("ERROR") ||
                                   line.contains("BUILD FAILURE")) {
                            log.info("  {}", line);
                        }
                    }
                } catch (Exception e) {
                    // Ignorer
                }
            });
            outputReader.start();

            // Attendre la fin avec timeout
            boolean finished = process.waitFor(3, TimeUnit.MINUTES);
            if (!finished) {
                process.destroyForcibly();
                log.warn("Timeout lors de l'execution de mvn sonar:sonar");
                return false;
            }

            int exitCode = process.exitValue();
            if (exitCode != 0) {
                log.warn("mvn sonar:sonar termine avec code {}", exitCode);
                return false;
            }

            log.info("  Analyse lancee avec succes");
            return true;

        } catch (Exception e) {
            log.warn("Erreur lors de l'execution de mvn sonar:sonar: {}", e.getMessage());
            if (verbose) {
                log.debug("Stack trace:", e);
            }
            return false;
        }
    }

    /**
     * Attend que l'analyse soit terminee sur le serveur.
     */
    private boolean waitForAnalysisCompletion(String projectKey) {
        for (int i = 0; i < MAX_POLLING_ATTEMPTS; i++) {
            String status = client.getAnalysisStatus(projectKey);

            if (verbose) {
                log.debug("Statut analyse: {}", status);
            }

            if ("SUCCESS".equals(status)) {
                log.info("  Analyse terminee avec succes");
                return true;
            } else if ("FAILED".equals(status)) {
                log.warn("  Analyse echouee sur le serveur");
                return false;
            } else if ("CANCELED".equals(status)) {
                log.warn("  Analyse annulee");
                return false;
            }

            // Attendre avant le prochain polling
            try {
                Thread.sleep(POLLING_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }

            if (i > 0 && i % 6 == 0) {
                log.info("  Analyse en cours... ({}s)", i * POLLING_INTERVAL_MS / 1000);
            }
        }

        log.warn("  Timeout en attente de l'analyse");
        return false;
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
    private void printSummary(SonarAnalysisResult result) {
        log.info("");
        log.info("Resume de l'analyse SonarQube:");
        log.info("  Projet:            {}", result.projectKey());
        log.info("  Quality Gate:      {}", result.qualityGateStatus() != null ? result.qualityGateStatus() : "N/A");

        SonarMetrics m = result.metrics();
        log.info("  Dette technique:   {} (ratio: {}%)", m.formattedDebt(), String.format("%.1f", m.debtRatio()));
        log.info("  Lignes de code:    {}", m.linesOfCode());
        log.info("  Couverture:        {}%", String.format("%.1f", m.coverage()));
        log.info("  Duplications:      {}%", String.format("%.1f", m.duplications()));

        log.info("  Ratings:");
        log.info("    Maintainability: {}", m.maintainabilityRating());
        log.info("    Reliability:     {}", m.reliabilityRating());
        log.info("    Security:        {}", m.securityRating());

        log.info("  Issues:");
        log.info("    Code Smells:     {}", m.codeSmells());
        log.info("    Bugs:            {}", m.bugs());
        log.info("    Vulnerabilities: {}", m.vulnerabilities());
        log.info("    Hotspots:        {}", m.securityHotspots());

        if (result.criticalIssuesCount() > 0) {
            log.warn("  {} issues bloquantes/critiques detectees!", result.criticalIssuesCount());
        }
    }
}
