package fr.cnam.migration.autofix;

import fr.cnam.migration.autofix.model.CompilationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Execute la compilation Maven et capture les resultats.
 */
public class CompilationRunner {

    private static final Logger log = LoggerFactory.getLogger(CompilationRunner.class);

    private static final int DEFAULT_TIMEOUT_SECONDS = 300;

    /**
     * Execute mvn compile dans le repertoire du projet.
     */
    public CompilationResult compile(Path projectDir) {
        return compile(projectDir, DEFAULT_TIMEOUT_SECONDS);
    }

    /**
     * Execute mvn compile avec un timeout specifique.
     */
    public CompilationResult compile(Path projectDir, int timeoutSeconds) {
        // Determiner la commande Maven a utiliser
        String mavenCommand = findMavenCommand(projectDir);
        log.info("Compilation du projet : {} (commande: {})", projectDir, mavenCommand);

        try {
            ProcessBuilder pb = new ProcessBuilder();
            pb.directory(projectDir.toFile());

            // Configurer la commande selon l'OS
            if (System.getProperty("os.name").toLowerCase().contains("win")) {
                pb.command("cmd", "/c", mavenCommand, "compile", "-e");
            } else {
                pb.command("sh", "-c", mavenCommand + " compile -e");
            }

            // Separer stdout et stderr
            pb.redirectErrorStream(false);

            Process process = pb.start();

            // Lire stdout et stderr en parallele
            StringBuilder stdout = new StringBuilder();
            StringBuilder stderr = new StringBuilder();

            Thread stdoutReader = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        stdout.append(line).append("\n");
                    }
                } catch (IOException e) {
                    log.debug("Erreur lecture stdout : {}", e.getMessage());
                }
            });

            Thread stderrReader = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getErrorStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        stderr.append(line).append("\n");
                    }
                } catch (IOException e) {
                    log.debug("Erreur lecture stderr : {}", e.getMessage());
                }
            });

            stdoutReader.start();
            stderrReader.start();

            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);

            if (!finished) {
                process.destroyForcibly();
                log.warn("Compilation interrompue : timeout de {} secondes depasse", timeoutSeconds);
                return new CompilationResult(false, stdout.toString(), "Timeout: compilation trop longue", -1);
            }

            // Attendre la fin des lecteurs
            stdoutReader.join(5000);
            stderrReader.join(5000);

            int exitCode = process.exitValue();
            boolean success = exitCode == 0;

            if (success) {
                log.info("Compilation reussie");
            } else {
                log.info("Compilation echouee (code: {})", exitCode);
            }

            return new CompilationResult(success, stdout.toString(), stderr.toString(), exitCode);

        } catch (IOException | InterruptedException e) {
            log.error("Erreur lors de la compilation : {}", e.getMessage());
            return new CompilationResult(false, "", "Erreur: " + e.getMessage(), -1);
        }
    }

    /**
     * Determine la commande Maven a utiliser (mvnw ou mvn).
     */
    private String findMavenCommand(Path projectDir) {
        // Verifier si mvnw existe
        Path mvnw = projectDir.resolve("mvnw");
        if (Files.exists(mvnw) && Files.isExecutable(mvnw)) {
            return "./mvnw";
        }

        // Verifier mvnw.cmd pour Windows
        Path mvnwCmd = projectDir.resolve("mvnw.cmd");
        if (Files.exists(mvnwCmd)) {
            return "mvnw.cmd";
        }

        // Fallback sur mvn du systeme
        return "mvn";
    }

    /**
     * Verifie si Maven est disponible.
     */
    public boolean isMavenAvailable(Path projectDir) {
        String command = findMavenCommand(projectDir);
        try {
            ProcessBuilder pb = new ProcessBuilder();
            pb.directory(projectDir.toFile());

            if (System.getProperty("os.name").toLowerCase().contains("win")) {
                pb.command("cmd", "/c", command, "--version");
            } else {
                pb.command("sh", "-c", command + " --version");
            }

            Process process = pb.start();
            boolean finished = process.waitFor(10, TimeUnit.SECONDS);
            return finished && process.exitValue() == 0;

        } catch (IOException | InterruptedException e) {
            return false;
        }
    }
}
