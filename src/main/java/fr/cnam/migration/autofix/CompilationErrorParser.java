package fr.cnam.migration.autofix;

import fr.cnam.migration.autofix.model.MissingDependency;
import fr.cnam.migration.autofix.model.MissingDependency.Type;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parse les erreurs de compilation Maven pour identifier
 * les classes et packages manquants.
 */
public class CompilationErrorParser {

    private static final Logger log = LoggerFactory.getLogger(CompilationErrorParser.class);

    // Pattern: [ERROR] file.java:[line,col] package X does not exist
    private static final Pattern MAVEN_PACKAGE_NOT_EXIST = Pattern.compile(
        "\\[ERROR\\].*?\\] package ([\\w.]+) does not exist"
    );

    // Pattern simple: package X does not exist
    private static final Pattern PACKAGE_NOT_EXIST = Pattern.compile(
        "package ([\\w.]+) does not exist"
    );

    // Pattern: cannot find symbol ... symbol: class X ... location: package Y
    private static final Pattern CANNOT_FIND_SYMBOL_CLASS = Pattern.compile(
        "cannot find symbol[\\s\\S]*?symbol:\\s*class (\\w+)[\\s\\S]*?location:\\s*package ([\\w.]+)",
        Pattern.MULTILINE
    );

    // Pattern: cannot find symbol ... symbol: class X ... location: class Y
    private static final Pattern CANNOT_FIND_SYMBOL_IN_CLASS = Pattern.compile(
        "cannot find symbol[\\s\\S]*?symbol:\\s*class (\\w+)[\\s\\S]*?location:\\s*class ([\\w.]+)",
        Pattern.MULTILINE
    );

    // Pattern: error: cannot access X
    private static final Pattern CANNOT_ACCESS = Pattern.compile(
        "error: cannot access ([\\w.]+)"
    );

    // Pattern pour les imports (fichier source)
    private static final Pattern SOURCE_FILE = Pattern.compile(
        "\\[ERROR\\] ([^:]+\\.java):\\[(\\d+),\\d+\\]"
    );

    // Pattern: import X not found
    private static final Pattern IMPORT_NOT_FOUND = Pattern.compile(
        "error:.*import ([\\w.]+)"
    );

    // === Patterns pour erreurs de RÉSOLUTION Maven (phase 1) ===

    // Pattern pour erreurs de résolution d'artefacts Maven
    // Format: "artifacts could not be resolved: groupId:artifactId:jar:version, ..."
    private static final Pattern ARTIFACT_NOT_RESOLVED = Pattern.compile(
        "artifacts? could not be resolved:\\s*(.+?)(?:->|$)",
        Pattern.CASE_INSENSITIVE
    );

    // Pattern pour extraire chaque coordonnée Maven
    private static final Pattern ARTIFACT_COORD = Pattern.compile(
        "([\\w.-]+):([\\w.-]+):jar:([\\w.-]+)"
    );

    /**
     * Parse la sortie de compilation et extrait les dependances manquantes.
     * Detecte les erreurs de RESOLUTION Maven (phase 1) et de COMPILATION javac (phase 2).
     */
    public Set<MissingDependency> parse(String compilerOutput) {
        Set<MissingDependency> missing = new HashSet<>();

        // 1. Parser les erreurs de RÉSOLUTION Maven (phase 1)
        //    Ces erreurs empêchent la compilation de démarrer
        missing.addAll(parseArtifactResolutionErrors(compilerOutput));

        // 2. Parser les erreurs de COMPILATION javac (phase 2)
        String currentSourceFile = null;

        // Traiter ligne par ligne pour capturer le fichier source
        String[] lines = compilerOutput.split("\n");
        StringBuilder context = new StringBuilder();

        for (String line : lines) {
            // Detecter le fichier source
            Matcher sourceMatcher = SOURCE_FILE.matcher(line);
            if (sourceMatcher.find()) {
                currentSourceFile = sourceMatcher.group(1);
            }

            // Accumuler le contexte pour les patterns multi-lignes
            context.append(line).append("\n");

            // Pattern Maven: [ERROR] ... package X does not exist
            Matcher mavenPackageMatcher = MAVEN_PACKAGE_NOT_EXIST.matcher(line);
            if (mavenPackageMatcher.find()) {
                String packageName = mavenPackageMatcher.group(1);
                missing.add(new MissingDependency(Type.PACKAGE, packageName, currentSourceFile));
                log.debug("Package manquant detecte (Maven) : {}", packageName);
            }

            // Pattern simple: package does not exist
            Matcher packageMatcher = PACKAGE_NOT_EXIST.matcher(line);
            if (packageMatcher.find() && !mavenPackageMatcher.find()) {
                String packageName = packageMatcher.group(1);
                missing.add(new MissingDependency(Type.PACKAGE, packageName, currentSourceFile));
                log.debug("Package manquant detecte : {}", packageName);
            }

            // Pattern: cannot access
            Matcher accessMatcher = CANNOT_ACCESS.matcher(line);
            if (accessMatcher.find()) {
                String className = accessMatcher.group(1);
                missing.add(new MissingDependency(Type.CLASS, className, currentSourceFile));
                log.debug("Classe inaccessible detectee : {}", className);
            }
        }

        // Patterns multi-lignes sur le contexte complet
        String fullOutput = context.toString();

        // Pattern: cannot find symbol - class in package
        Matcher classMatcher = CANNOT_FIND_SYMBOL_CLASS.matcher(fullOutput);
        while (classMatcher.find()) {
            String className = classMatcher.group(1);
            String packageName = classMatcher.group(2);
            String fullClassName = packageName + "." + className;
            missing.add(new MissingDependency(Type.CLASS, fullClassName, currentSourceFile));
            log.debug("Classe manquante detectee : {}", fullClassName);
        }

        // Pattern: cannot find symbol - class in class (import)
        Matcher classInClassMatcher = CANNOT_FIND_SYMBOL_IN_CLASS.matcher(fullOutput);
        while (classInClassMatcher.find()) {
            String className = classInClassMatcher.group(1);
            String locationClass = classInClassMatcher.group(2);
            // On ne connait pas le package exact, on ajoute juste la classe
            missing.add(new MissingDependency(Type.CLASS, className, currentSourceFile));
            log.debug("Classe manquante (sans package) detectee : {}", className);
        }

        // Deduplication des packages a partir des classes
        Set<String> packages = new HashSet<>();
        for (MissingDependency dep : missing) {
            if (dep.type() == Type.CLASS) {
                String pkg = dep.getPackage();
                if (!pkg.isEmpty()) {
                    packages.add(pkg);
                }
            }
        }

        // Ajouter les packages comme dependances supplementaires
        for (String pkg : packages) {
            boolean alreadyExists = missing.stream()
                .anyMatch(d -> d.type() == Type.PACKAGE && d.name().equals(pkg));
            if (!alreadyExists) {
                missing.add(new MissingDependency(Type.PACKAGE, pkg, null));
            }
        }

        log.info("Analyse terminee : {} dependances manquantes detectees", missing.size());
        return missing;
    }

    /**
     * Extrait le nombre total d'erreurs de compilation.
     */
    public int countErrors(String compilerOutput) {
        // Pattern: [ERROR] ... error(s)
        Pattern errorCount = Pattern.compile("\\[(\\d+) errors?\\]");
        Matcher matcher = errorCount.matcher(compilerOutput);
        if (matcher.find()) {
            return Integer.parseInt(matcher.group(1));
        }

        // Compter les lignes [ERROR]
        int count = 0;
        for (String line : compilerOutput.split("\n")) {
            if (line.contains("[ERROR]") && line.contains(".java:")) {
                count++;
            }
        }
        return count;
    }

    /**
     * Parse les erreurs de résolution d'artefacts Maven (phase 1).
     * Ces erreurs surviennent AVANT la compilation javac, quand Maven
     * ne peut pas télécharger/trouver les dépendances déclarées dans le POM.
     *
     * Format d'erreur:
     * [ERROR] Could not resolve dependencies... The following artifacts could not be resolved:
     * fr.cnamts.internal:bimc_h:jar:1.0.16, fr.cnamts.jk.socle:jk-socle-util:jar:1.2.5, ...
     *
     * @param output La sortie complète de Maven
     * @return Les dépendances manquantes détectées (groupId utilisé comme package)
     */
    private Set<MissingDependency> parseArtifactResolutionErrors(String output) {
        Set<MissingDependency> errors = new HashSet<>();

        Matcher matcher = ARTIFACT_NOT_RESOLVED.matcher(output);
        while (matcher.find()) {
            String artifactsList = matcher.group(1);
            Matcher coordMatcher = ARTIFACT_COORD.matcher(artifactsList);
            while (coordMatcher.find()) {
                String groupId = coordMatcher.group(1);
                String artifactId = coordMatcher.group(2);
                // Utiliser groupId comme package pour compatibilité avec résolution lib-provided
                errors.add(new MissingDependency(Type.PACKAGE, groupId, null));
                log.debug("Artefact Maven non résolu: {}:{}", groupId, artifactId);
            }
        }

        if (!errors.isEmpty()) {
            log.info("Erreurs résolution Maven détectées: {} artefacts manquants", errors.size());
        }

        return errors;
    }
}
