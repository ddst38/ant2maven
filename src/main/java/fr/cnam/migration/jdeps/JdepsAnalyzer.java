package fr.cnam.migration.jdeps;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.util.stream.Collectors;

/**
 * Analyseur de dependances utilisant jdeps.
 * Execute jdeps sur le projet compile et parse les resultats.
 */
public class JdepsAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(JdepsAnalyzer.class);

    // Pattern pour parser la sortie jdeps verbose:package
    // Format: "   fr.cnam.app.service -> java.util    java.base"
    // ou:     "   fr.cnam.app.service -> org.external    not found"
    private static final Pattern DEPENDENCY_PATTERN = Pattern.compile(
        "^\\s+([\\w.]+)\\s+->\\s+([\\w.]+)(?:\\s+([\\w.]+|not found))?\\s*$"
    );

    // Pattern pour parser la sortie jdeps --jdk-internals
    // Format: "fr.cnam.MyClass -> sun.misc.Unsafe    JDK internal API (jdk.unsupported)"
    private static final Pattern JDK_INTERNAL_PATTERN = Pattern.compile(
        "^\\s*([\\w.$]+)\\s+->\\s+([\\w.$]+)\\s+.*\\(([\\w.]+)\\).*$"
    );

    private final boolean verbose;

    public JdepsAnalyzer() {
        this(false);
    }

    public JdepsAnalyzer(boolean verbose) {
        this.verbose = verbose;
    }

    /**
     * Execute l'analyse jdeps sur un projet Maven compile.
     *
     * @param projectDir Repertoire du projet Maven
     * @return Resultat de l'analyse
     */
    public JdepsResult analyze(Path projectDir) {
        log.info("");
        log.info("=".repeat(60));
        log.info("Phase 6: Analyse structurelle (jdeps)");
        log.info("=".repeat(60));

        // Trouver tous les repertoires target/classes (racine + sous-modules)
        List<Path> allTargetClasses = findAllTargetClasses(projectDir);
        if (allTargetClasses.isEmpty()) {
            log.warn("Aucun repertoire target/classes trouve. Compilation necessaire.");
            return JdepsResult.failed();
        }

        try {
            // Recuperer le classpath Maven
            String classpath = getMavenClasspath(projectDir);
            if (classpath == null || classpath.isBlank()) {
                log.warn("Impossible de recuperer le classpath Maven");
                classpath = "";
            }

            // Executer jdeps sur TOUS les modules
            log.info("Analyse des dependances par package...");
            log.info("  {} module(s) a analyser", allTargetClasses.size());

            List<PackageDependency> dependencies = new ArrayList<>();
            List<JdkInternalUsage> jdkInternals = new ArrayList<>();

            for (Path targetClasses : allTargetClasses) {
                String moduleName = targetClasses.getParent().getParent().getFileName().toString();
                log.info("  Module: {}", moduleName);

                List<PackageDependency> moduleDeps = analyzePackageDependencies(targetClasses, classpath);
                dependencies.addAll(moduleDeps);

                List<JdkInternalUsage> moduleInternals = analyzeJdkInternals(targetClasses, classpath);
                jdkInternals.addAll(moduleInternals);
            }

            log.info("  {} dependances trouvees au total", dependencies.size());
            log.info("  {} usages d'APIs internes detectes", jdkInternals.size());

            // Calculer les metriques par package
            log.info("Calcul des metriques de couplage...");
            Map<String, PackageMetrics> metrics = calculatePackageMetrics(dependencies);
            log.info("  {} packages analyses", metrics.size());

            // Detecter les cycles
            log.info("Detection des cycles de dependances...");
            List<CycleDependency> cycles = detectCycles(dependencies);
            log.info("  {} cycles detectes", cycles.size());

            // Calculer l'instabilite moyenne
            double avgInstability = metrics.values().stream()
                .mapToDouble(PackageMetrics::instability)
                .average()
                .orElse(0.0);

            JdepsResult result = new JdepsResult(
                true,
                dependencies,
                jdkInternals,
                cycles,
                metrics,
                metrics.size(),
                dependencies.size(),
                cycles.size(),
                jdkInternals.size(),
                avgInstability
            );

            printSummary(result);
            return result;

        } catch (Exception e) {
            log.error("Erreur lors de l'analyse jdeps: {}", e.getMessage());
            if (verbose) {
                log.error("Stack trace:", e);
            }
            return JdepsResult.failed();
        }
    }

    /**
     * Cherche TOUS les repertoires target/classes dans le projet (racine + sous-modules).
     */
    private List<Path> findAllTargetClasses(Path projectDir) {
        List<Path> result = new ArrayList<>();

        // Verifier la racine
        Path rootClasses = projectDir.resolve("target/classes");
        if (Files.isDirectory(rootClasses)) {
            result.add(rootClasses);
        }

        // Chercher dans les sous-modules
        try (var walk = Files.walk(projectDir, 3)) {
            walk.filter(p -> p.endsWith("target/classes"))
                .filter(Files::isDirectory)
                .filter(p -> !p.equals(rootClasses)) // Eviter doublon avec racine
                .forEach(result::add);
        } catch (IOException e) {
            // Ignorer
        }

        return result;
    }

    /**
     * Recupere le classpath du projet Maven.
     */
    private String getMavenClasspath(Path projectDir) {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                getMvnCommand(projectDir),
                "-q",
                "-Dexec.executable=echo",
                "-Dexec.args=%classpath",
                "--non-recursive",
                "exec:exec"
            );
            pb.directory(projectDir.toFile());
            pb.redirectErrorStream(true);

            Process process = pb.start();
            String output;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                output = reader.lines().collect(Collectors.joining("\n"));
            }

            int exitCode = process.waitFor();
            if (exitCode != 0) {
                log.debug("Echec recuperation classpath Maven (code {})", exitCode);
                return "";
            }

            return output.trim();

        } catch (Exception e) {
            log.debug("Erreur recuperation classpath: {}", e.getMessage());
            return "";
        }
    }

    /**
     * Determine la commande Maven a utiliser (mvnw ou mvn).
     * Retourne le chemin absolu pour eviter les problemes avec ProcessBuilder.
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
     * Analyse les dependances par package avec jdeps.
     */
    private List<PackageDependency> analyzePackageDependencies(Path targetClasses, String classpath) {
        List<PackageDependency> dependencies = new ArrayList<>();

        try {
            List<String> command = new ArrayList<>();
            command.add("jdeps");
            command.add("-verbose:package");
            command.add("-R");
            command.add("--multi-release");
            command.add("base");
            if (!classpath.isBlank()) {
                command.add("-classpath");
                command.add(classpath);
            }
            command.add(targetClasses.toString());

            if (verbose) {
                log.debug("Commande jdeps: {}", String.join(" ", command));
                log.debug("Target classes: {} (exists: {})", targetClasses, Files.isDirectory(targetClasses));
            }

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);

            Process process = pb.start();
            int lineCount = 0;
            int matchCount = 0;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    lineCount++;
                    if (verbose && lineCount <= 3) {
                        log.debug("jdeps output [{}]: {}", lineCount, line);
                    }
                    Matcher matcher = DEPENDENCY_PATTERN.matcher(line);
                    if (matcher.matches()) {
                        matchCount++;
                        String source = matcher.group(1);
                        String target = matcher.group(2);
                        String module = matcher.group(3);

                        // Ignorer les dependances internes au meme package
                        if (!source.equals(target)) {
                            dependencies.add(new PackageDependency(source, target, module));
                        }
                    }
                }
            }

            int exitCode = process.waitFor();
            if (verbose) {
                log.debug("jdeps exit code: {}, lines: {}, matches: {}", exitCode, lineCount, matchCount);
            }

        } catch (Exception e) {
            log.warn("Erreur analyse dependances: {}", e.getMessage());
            if (verbose) {
                log.warn("Stack trace:", e);
            }
        }

        return dependencies;
    }

    /**
     * Analyse les usages d'APIs internes JDK.
     */
    private List<JdkInternalUsage> analyzeJdkInternals(Path targetClasses, String classpath) {
        List<JdkInternalUsage> usages = new ArrayList<>();

        try {
            List<String> command = new ArrayList<>();
            command.add("jdeps");
            command.add("--jdk-internals");
            command.add("--multi-release");
            command.add("base");
            if (!classpath.isBlank()) {
                command.add("-classpath");
                command.add(classpath);
            }
            command.add(targetClasses.toString());

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);

            Process process = pb.start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    Matcher matcher = JDK_INTERNAL_PATTERN.matcher(line);
                    if (matcher.matches()) {
                        String sourceClass = matcher.group(1);
                        String internalApi = matcher.group(2);
                        String module = matcher.group(3);
                        String suggestion = JdkInternalUsage.getSuggestion(internalApi);

                        usages.add(new JdkInternalUsage(sourceClass, internalApi, module, suggestion));
                    }
                }
            }

            process.waitFor();

        } catch (Exception e) {
            log.warn("Erreur analyse JDK internals: {}", e.getMessage());
        }

        return usages;
    }

    /**
     * Calcule les metriques de couplage pour chaque package.
     */
    private Map<String, PackageMetrics> calculatePackageMetrics(List<PackageDependency> dependencies) {
        // Collecter tous les packages sources (packages du projet)
        Set<String> projectPackages = dependencies.stream()
            .map(PackageDependency::sourcePackage)
            .filter(p -> !p.startsWith("java.") && !p.startsWith("javax.") && !p.startsWith("jdk."))
            .collect(Collectors.toSet());

        Map<String, List<String>> dependsOn = new HashMap<>();
        Map<String, List<String>> usedBy = new HashMap<>();

        // Initialiser les maps
        for (String pkg : projectPackages) {
            dependsOn.put(pkg, new ArrayList<>());
            usedBy.put(pkg, new ArrayList<>());
        }

        // Remplir les relations
        for (PackageDependency dep : dependencies) {
            String source = dep.sourcePackage();
            String target = dep.targetPackage();

            if (projectPackages.contains(source)) {
                dependsOn.get(source).add(target);
            }
            if (projectPackages.contains(target)) {
                usedBy.get(target).add(source);
            }
        }

        // Calculer les metriques
        Map<String, PackageMetrics> metrics = new HashMap<>();
        for (String pkg : projectPackages) {
            List<String> deps = dependsOn.getOrDefault(pkg, List.of());
            List<String> users = usedBy.getOrDefault(pkg, List.of());

            int ce = deps.size();  // Efferent coupling
            int ca = users.size(); // Afferent coupling
            double instability = PackageMetrics.calculateInstability(ca, ce);

            metrics.put(pkg, new PackageMetrics(pkg, ca, ce, instability, deps, users));
        }

        return metrics;
    }

    /**
     * Detecte les cycles de dependances entre packages.
     */
    private List<CycleDependency> detectCycles(List<PackageDependency> dependencies) {
        // Construire le graphe de dependances
        Map<String, Set<String>> graph = new HashMap<>();

        for (PackageDependency dep : dependencies) {
            String source = dep.sourcePackage();
            String target = dep.targetPackage();

            // Ne considerer que les packages du projet (pas java.*, etc.)
            if (!source.startsWith("java.") && !target.startsWith("java.") &&
                !source.startsWith("javax.") && !target.startsWith("javax.") &&
                !source.startsWith("jdk.") && !target.startsWith("jdk.")) {

                graph.computeIfAbsent(source, k -> new HashSet<>()).add(target);
            }
        }

        // Detecter les cycles avec DFS
        List<CycleDependency> cycles = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        Set<String> recursionStack = new HashSet<>();

        for (String node : graph.keySet()) {
            if (!visited.contains(node)) {
                List<String> path = new ArrayList<>();
                detectCyclesDFS(node, graph, visited, recursionStack, path, cycles);
            }
        }

        return cycles;
    }

    private void detectCyclesDFS(String node, Map<String, Set<String>> graph,
                                  Set<String> visited, Set<String> recursionStack,
                                  List<String> path, List<CycleDependency> cycles) {
        visited.add(node);
        recursionStack.add(node);
        path.add(node);

        Set<String> neighbors = graph.getOrDefault(node, Set.of());
        for (String neighbor : neighbors) {
            if (!visited.contains(neighbor)) {
                detectCyclesDFS(neighbor, graph, visited, recursionStack, path, cycles);
            } else if (recursionStack.contains(neighbor)) {
                // Cycle detecte
                int cycleStart = path.indexOf(neighbor);
                if (cycleStart >= 0) {
                    List<String> cyclePath = new ArrayList<>(path.subList(cycleStart, path.size()));
                    // Eviter les doublons
                    boolean isDuplicate = cycles.stream()
                        .anyMatch(c -> new HashSet<>(c.packages()).equals(new HashSet<>(cyclePath)));
                    if (!isDuplicate && cyclePath.size() > 1) {
                        cycles.add(new CycleDependency(cyclePath));
                    }
                }
            }
        }

        path.remove(path.size() - 1);
        recursionStack.remove(node);
    }

    /**
     * Affiche le resume de l'analyse.
     */
    private void printSummary(JdepsResult result) {
        log.info("");
        log.info("Resume de l'analyse jdeps:");
        log.info("  Packages analyses:     {}", result.totalPackages());
        log.info("  Dependances totales:   {}", result.totalDependencies());
        log.info("  Cycles detectes:       {}", result.cycleCount());
        log.info("  APIs internes JDK:     {}", result.jdkInternalCount());
        log.info("  Instabilite moyenne:   {}", String.format("%.2f", result.avgInstability()));

        if (result.cycleCount() > 0) {
            log.warn("");
            log.warn("Cycles de dependances detectes:");
            for (CycleDependency cycle : result.cycles()) {
                log.warn("  - {}", cycle.toCycleString());
            }
        }

        if (result.jdkInternalCount() > 0) {
            log.warn("");
            log.warn("Usages d'APIs internes JDK (a migrer):");
            for (JdkInternalUsage usage : result.jdkInternalUsages()) {
                log.warn("  - {} -> {}", usage.sourceClass(), usage.internalApi());
                if (usage.suggestion() != null) {
                    log.warn("    Suggestion: {}", usage.suggestion());
                }
            }
        }
    }
}
