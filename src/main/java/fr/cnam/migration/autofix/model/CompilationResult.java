package fr.cnam.migration.autofix.model;

/**
 * Resultat d'une execution de compilation Maven.
 */
public record CompilationResult(
    boolean success,
    String stdout,
    String stderr,
    int exitCode
) {
    /**
     * Retourne la sortie combinee (stdout + stderr).
     */
    public String combinedOutput() {
        return stdout + "\n" + stderr;
    }
}
