package fr.cnam.migration.autofix;

import fr.cnam.migration.autofix.model.*;
import fr.cnam.migration.autofix.model.MissingDependency.Type;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;

/**
 * Service principal de correction automatique des erreurs de compilation.
 * Orchestre l'indexation, la compilation, le parsing des erreurs et l'injection des dependances.
 */
public class AutoFixService {

    private static final Logger log = LoggerFactory.getLogger(AutoFixService.class);

    private static final int DEFAULT_MAX_ITERATIONS = 5;
    private static final int DEFAULT_TIMEOUT_SECONDS = 300;

    private final LibProvidedIndexer indexer;
    private final CompilationRunner runner;
    private final CompilationErrorParser parser;
    private final PomDependencyInjector injector;

    public AutoFixService() {
        this.indexer = new LibProvidedIndexer();
        this.runner = new CompilationRunner();
        this.parser = new CompilationErrorParser();
        this.injector = new PomDependencyInjector();
    }

    /**
     * Execute le processus de correction automatique.
     *
     * @param projectDir Repertoire du projet Maven genere
     * @param libProvidedDir Repertoire contenant les JARs provided
     * @return Resultat du processus
     */
    public AutoFixResult fix(Path projectDir, Path libProvidedDir) throws IOException {
        return fix(projectDir, libProvidedDir, DEFAULT_MAX_ITERATIONS);
    }

    /**
     * Execute le processus de correction automatique avec un nombre max d'iterations.
     */
    public AutoFixResult fix(Path projectDir, Path libProvidedDir, int maxIterations) throws IOException {
        log.info("============================================================");
        log.info("Correction automatique - Auto-fix");
        log.info("============================================================");
        log.info("Projet : {}", projectDir);
        log.info("Lib-provided : {}", libProvidedDir);
        log.info("Iterations max : {}", maxIterations);
        log.info("");

        // Verifier que Maven est disponible
        if (!runner.isMavenAvailable(projectDir)) {
            log.error("Maven non disponible. Installez Maven ou verifiez le PATH.");
            return AutoFixResult.failure(0, List.of(), Set.of());
        }

        // Etape 1: Indexer lib-provided
        log.info("Phase 1: Indexation de lib-provided...");
        indexer.index(libProvidedDir);

        if (indexer.getJarCount() == 0) {
            log.warn("Aucun JAR trouve dans lib-provided. Correction automatique impossible.");
            return AutoFixResult.failure(0, List.of(), Set.of());
        }

        log.info("Index cree : {} JARs, {} classes", indexer.getJarCount(), indexer.getClassCount());
        log.info("");

        // Installer les JARs locaux d'abord
        installLocalJars(projectDir);

        List<ProvidedDependency> allAdded = new ArrayList<>();
        Set<MissingDependency> lastErrors = new HashSet<>();
        Set<String> alreadyAddedGavs = new HashSet<>();

        for (int iteration = 1; iteration <= maxIterations; iteration++) {
            log.info("============================================================");
            log.info("Iteration {} / {}", iteration, maxIterations);
            log.info("============================================================");

            // Etape 2: Compiler
            log.info("Phase 2: Compilation...");
            CompilationResult result = runner.compile(projectDir, DEFAULT_TIMEOUT_SECONDS);

            if (result.success()) {
                log.info("");
                log.info("Compilation reussie !");
                return AutoFixResult.success(iteration, allAdded);
            }

            // Etape 3: Parser les erreurs
            log.info("Phase 3: Analyse des erreurs...");
            Set<MissingDependency> errors = parser.parse(result.combinedOutput());

            if (errors.isEmpty()) {
                log.warn("Erreurs de compilation detectees mais aucune dependance manquante identifiee.");
                log.warn("Verifiez manuellement les erreurs de compilation.");
                return AutoFixResult.failure(iteration, allAdded, lastErrors);
            }

            log.info("{} dependance(s) manquante(s) detectee(s)", errors.size());

            // Etape 4: Matcher avec lib-provided
            log.info("Phase 4: Recherche dans lib-provided...");
            List<ProvidedDependency> toAdd = new ArrayList<>();

            for (MissingDependency error : errors) {
                Optional<Path> jarPath = findMatchingJar(error);
                if (jarPath.isPresent()) {
                    ProvidedDependency dep = indexer.createDependency(jarPath.get());

                    // Eviter les doublons
                    if (!alreadyAddedGavs.contains(dep.toGav())) {
                        toAdd.add(dep);
                        alreadyAddedGavs.add(dep.toGav());
                        log.info("  Match: {} -> {}", error.name(), dep.toGav());
                    }
                } else {
                    log.debug("  Pas de match pour : {}", error.name());
                }
            }

            if (toAdd.isEmpty()) {
                log.warn("Aucune nouvelle dependance trouvee dans lib-provided.");
                log.warn("Les erreurs restantes necessitent une intervention manuelle.");
                lastErrors = errors;
                return AutoFixResult.failure(iteration, allAdded, errors);
            }

            // Etape 5: Copier les JARs vers liblocale et injecter les dependances
            log.info("Phase 5: Copie des JARs et injection des dependances...");

            // Copier les JARs de lib-provided vers liblocale
            Path liblocale = projectDir.resolve("liblocale");
            Files.createDirectories(liblocale);
            for (ProvidedDependency dep : toAdd) {
                Path target = liblocale.resolve(dep.jarPath().getFileName());
                if (!Files.exists(target)) {
                    Files.copy(dep.jarPath(), target);
                    log.info("  Copie: {} -> liblocale/", dep.jarPath().getFileName());
                }
            }

            // Injecter dans le pom.xml
            Path targetPom = findTargetPom(projectDir);
            int added = injector.addProvidedDependencies(targetPom, toAdd);

            // Mettre a jour le script d'installation
            updateInstallScript(projectDir, toAdd);

            allAdded.addAll(toAdd);
            lastErrors = errors;

            log.info("{} dependance(s) ajoutee(s) a {}", added, targetPom.getFileName());

            // Reinstaller les JARs locaux pour inclure les nouveaux
            log.info("Reinstallation des JARs locaux...");
            installLocalJars(projectDir);
            log.info("");
        }

        log.warn("Nombre maximum d'iterations atteint ({}).", maxIterations);
        return AutoFixResult.maxIterationsReached(maxIterations, allAdded, lastErrors);
    }

    /**
     * Trouve le JAR correspondant a une dependance manquante.
     */
    private Optional<Path> findMatchingJar(MissingDependency error) {
        if (error.type() == Type.CLASS) {
            // Essayer de trouver par classe complete
            Optional<Path> byClass = indexer.findJarForClass(error.name());
            if (byClass.isPresent()) {
                return byClass;
            }
            // Sinon chercher par package
            return indexer.findBestJarForPackage(error.getPackage());
        } else {
            // Type PACKAGE
            return indexer.findBestJarForPackage(error.name());
        }
    }

    /**
     * Trouve le pom.xml cible pour l'injection des dependances.
     * Pour un projet multi-module, on injecte dans le parent.
     */
    private Path findTargetPom(Path projectDir) {
        Path parentPom = projectDir.resolve("pom.xml");
        if (Files.exists(parentPom)) {
            return parentPom;
        }
        // Fallback: chercher dans les sous-repertoires
        try (Stream<Path> paths = Files.walk(projectDir, 2)) {
            return paths.filter(p -> p.getFileName().toString().equals("pom.xml"))
                       .findFirst()
                       .orElse(parentPom);
        } catch (IOException e) {
            return parentPom;
        }
    }

    /**
     * Installe les JARs locaux via le script install-local-jars.sh.
     */
    private void installLocalJars(Path projectDir) {
        Path script = projectDir.resolve("liblocale/install-local-jars.sh");
        if (!Files.exists(script)) {
            log.debug("Pas de script install-local-jars.sh");
            return;
        }

        log.info("Installation des JARs locaux...");
        try {
            ProcessBuilder pb = new ProcessBuilder("sh", script.toAbsolutePath().toString());
            pb.directory(projectDir.toAbsolutePath().toFile());
            pb.inheritIO();
            Process process = pb.start();
            int exitCode = process.waitFor();
            if (exitCode == 0) {
                log.info("JARs locaux installes avec succes");
            } else {
                log.warn("Installation des JARs locaux terminee avec code : {}", exitCode);
            }
        } catch (IOException | InterruptedException e) {
            log.warn("Erreur lors de l'installation des JARs locaux : {}", e.getMessage());
        }
    }

    /**
     * Met a jour le script d'installation pour inclure les nouveaux JARs provided.
     */
    private void updateInstallScript(Path projectDir, List<ProvidedDependency> newDeps) {
        Path script = projectDir.resolve("liblocale/install-local-jars.sh");
        if (!Files.exists(script)) {
            return;
        }

        try {
            String content = Files.readString(script);
            StringBuilder additions = new StringBuilder();

            for (ProvidedDependency dep : newDeps) {
                String jarName = dep.jarPath().getFileName().toString();
                // Verifier si deja present
                if (content.contains(jarName)) {
                    continue;
                }

                // Ajouter la commande d'installation (meme format que le reste du script)
                additions.append("\n# [Provided] ").append(dep.toGav()).append("\n");
                additions.append("./mvnw -s .mvn/wrapper/settings.xml install:install-file \\\n");
                additions.append("    -Dfile=\"liblocale/").append(jarName).append("\" \\\n");
                additions.append("    -DgroupId=\"").append(dep.groupId()).append("\" \\\n");
                additions.append("    -DartifactId=\"").append(dep.artifactId()).append("\" \\\n");
                additions.append("    -Dversion=\"").append(dep.version()).append("\" \\\n");
                additions.append("    -Dpackaging=jar\n");
            }

            if (additions.length() > 0) {
                // Inserer avant la ligne "echo" finale
                int echoPos = content.lastIndexOf("\necho");
                if (echoPos > 0) {
                    content = content.substring(0, echoPos) + additions.toString() + content.substring(echoPos);
                } else {
                    content += additions.toString();
                }
                Files.writeString(script, content);
                log.info("Script install-local-jars.sh mis a jour");
            }
        } catch (IOException e) {
            log.warn("Impossible de mettre a jour le script : {}", e.getMessage());
        }
    }

    /**
     * Affiche un resume du resultat.
     */
    public void printSummary(AutoFixResult result) {
        log.info("");
        log.info("============================================================");
        log.info("Resume - Auto-fix");
        log.info("============================================================");
        log.info("Statut : {}", result.status());
        log.info("Iterations : {}", result.iterations());
        log.info("Dependances ajoutees : {}", result.addedDependencies().size());

        if (!result.addedDependencies().isEmpty()) {
            log.info("");
            log.info("Dependances provided ajoutees :");
            for (ProvidedDependency dep : result.addedDependencies()) {
                log.info("  - {}", dep.toGav());
            }
        }

        if (!result.unresolvedErrors().isEmpty()) {
            log.info("");
            log.warn("Erreurs non resolues ({}) :", result.unresolvedErrors().size());
            result.unresolvedErrors().stream()
                .limit(10)
                .forEach(e -> log.warn("  - {} : {}", e.type(), e.name()));
            if (result.unresolvedErrors().size() > 10) {
                log.warn("  ... et {} autres", result.unresolvedErrors().size() - 10);
            }
        }
    }
}
