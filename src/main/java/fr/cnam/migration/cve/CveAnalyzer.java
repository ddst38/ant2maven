package fr.cnam.migration.cve;

import fr.cnam.migration.model.CveAnalysisResult;
import fr.cnam.migration.model.CveInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Service d'analyse des vulnérabilités CVE via OWASP Dependency-Check.
 * Exécute le plugin Maven et parse le rapport JSON généré.
 */
public class CveAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(CveAnalyzer.class);
    private static final String REPORT_FILE = "target/dependency-check-report.json";
    private static final int TIMEOUT_MINUTES = 30;

    private final String nvdApiKey;
    private final boolean verbose;

    public CveAnalyzer(String nvdApiKey, boolean verbose) {
        this.nvdApiKey = nvdApiKey;
        this.verbose = verbose;
    }

    /**
     * Analyse les dépendances du projet Maven pour détecter les CVE.
     *
     * @param projectDir Répertoire du projet Maven à analyser
     * @return Résultat de l'analyse CVE
     */
    public CveAnalysisResult analyze(Path projectDir) {
        log.info("");
        log.info("============================================================");
        log.info("Analyse des vulnérabilités CVE");
        log.info("============================================================");
        log.info("Projet : {}", projectDir);
        log.info("Cette analyse peut prendre plusieurs minutes...");

        try {
            // Étape 1 : Exécuter dependency-check via Maven
            boolean success = runDependencyCheck(projectDir);
            if (!success) {
                log.error("Échec de l'exécution de dependency-check");
                return CveAnalysisResult.empty();
            }

            // Étape 2 : Parser le rapport JSON
            Path reportPath = projectDir.resolve(REPORT_FILE);
            if (!Files.exists(reportPath)) {
                log.warn("Rapport dependency-check non généré : {}", reportPath);
                return CveAnalysisResult.empty();
            }

            DependencyCheckReportParser parser = new DependencyCheckReportParser();
            List<CveInfo> vulnerabilities = parser.parse(reportPath);

            // Étape 3 : Construire le résultat
            CveAnalysisResult result = CveAnalysisResult.from(vulnerabilities);
            printSummary(result);

            return result;

        } catch (Exception e) {
            log.error("Erreur lors de l'analyse CVE : {}", e.getMessage());
            if (verbose) {
                log.error("Stack trace :", e);
            }
            return CveAnalysisResult.empty();
        }
    }

    /**
     * Exécute le plugin Maven dependency-check.
     */
    private boolean runDependencyCheck(Path projectDir) throws IOException, InterruptedException {
        // Construire la commande Maven
        String mvnCmd = detectMavenCommand(projectDir);

        // Détecter les modules -dist à exclure
        String excludeModules = detectDistModules(projectDir);

        ProcessBuilder pb = new ProcessBuilder();
        pb.directory(projectDir.toFile());

        // Construire la ligne de commande
        String fullCommand = buildMavenCommand(mvnCmd, excludeModules);
        if (System.getProperty("os.name").toLowerCase().contains("win")) {
            pb.command("cmd", "/c", fullCommand);
        } else {
            pb.command("/bin/bash", "-c", fullCommand);
        }

        // Rediriger stderr vers stdout
        pb.redirectErrorStream(true);

        log.info("Exécution : {}", fullCommand);

        Process process = pb.start();

        // Lire la sortie en temps réel si verbose
        if (verbose) {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    log.debug("[dependency-check] {}", line);
                }
            }
        } else {
            // Consommer la sortie pour éviter le blocage
            process.getInputStream().transferTo(java.io.OutputStream.nullOutputStream());
        }

        boolean finished = process.waitFor(TIMEOUT_MINUTES, TimeUnit.MINUTES);
        if (!finished) {
            log.error("Timeout après {} minutes", TIMEOUT_MINUTES);
            process.destroyForcibly();
            return false;
        }

        int exitCode = process.exitValue();
        if (exitCode != 0) {
            log.warn("dependency-check terminé avec code {}", exitCode);
            // On continue quand même car le rapport peut avoir été généré
        }

        return true;
    }

    /**
     * Construit la commande Maven complète.
     */
    private String buildMavenCommand(String mvnCmd, String excludeModules) {
        StringBuilder cmd = new StringBuilder();
        cmd.append(mvnCmd);
        cmd.append(" org.owasp:dependency-check-maven:12.1.0:aggregate");
        cmd.append(" -DfailBuildOnCVSS=11"); // Ne jamais faire échouer
        cmd.append(" -Dformats=JSON");
        cmd.append(" -DprettyPrint=true");
        // Désactiver OSS Index (nécessite authentification Sonatype)
        cmd.append(" -DossindexAnalyzerEnabled=false");

        // Exclure les modules de distribution (dépendent du JAR compilé)
        if (excludeModules != null && !excludeModules.isEmpty()) {
            cmd.append(" -pl ").append(excludeModules);
        }

        if (nvdApiKey != null && !nvdApiKey.isBlank()) {
            cmd.append(" -DnvdApiKey=").append(nvdApiKey);
        }

        if (!verbose) {
            cmd.append(" -q"); // Mode quiet
        }

        return cmd.toString();
    }

    /**
     * Détecte les modules -dist à exclure de l'analyse.
     */
    private String detectDistModules(Path projectDir) {
        try {
            List<String> distModules = new java.util.ArrayList<>();
            try (var dirs = java.nio.file.Files.list(projectDir)) {
                dirs.filter(java.nio.file.Files::isDirectory)
                    .filter(d -> d.getFileName().toString().endsWith("-dist"))
                    .forEach(d -> distModules.add("!" + d.getFileName().toString()));
            }
            if (!distModules.isEmpty()) {
                log.debug("Modules dist exclus de l'analyse CVE : {}", distModules);
                return String.join(",", distModules);
            }
        } catch (Exception e) {
            log.debug("Impossible de détecter les modules dist : {}", e.getMessage());
        }
        return null;
    }

    /**
     * Détecte si on utilise mvnw ou mvn.
     */
    private String detectMavenCommand(Path projectDir) {
        Path mvnw = projectDir.resolve("mvnw");
        if (Files.exists(mvnw) && Files.isExecutable(mvnw)) {
            return "./mvnw";
        }
        return "mvn";
    }

    /**
     * Affiche un résumé de l'analyse.
     */
    private void printSummary(CveAnalysisResult result) {
        log.info("");
        log.info("Résumé de l'analyse CVE");
        log.info("-".repeat(40));
        log.info("Total vulnérabilités : {}", result.totalCount());

        if (result.criticalCount() > 0) {
            log.error("  - Critiques : {}", result.criticalCount());
        }
        if (result.highCount() > 0) {
            log.warn("  - Hautes    : {}", result.highCount());
        }
        if (result.mediumCount() > 0) {
            log.info("  - Moyennes  : {}", result.mediumCount());
        }
        if (result.lowCount() > 0) {
            log.info("  - Basses    : {}", result.lowCount());
        }

        log.info("Score de risque global : {}", result.riskScore());
        log.info("Librairies affectées : {}", result.byLibrary().size());

        if (result.hasCriticalVulnerabilities()) {
            log.error("");
            log.error("ATTENTION : Des vulnérabilités CRITIQUES ont été détectées !");
        }
    }
}
