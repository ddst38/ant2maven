package fr.cnam.migration.autofix.model;

import java.util.List;
import java.util.Set;

/**
 * Resultat du processus de correction automatique.
 */
public record AutoFixResult(
    Status status,
    int iterations,
    List<ProvidedDependency> addedDependencies,
    Set<MissingDependency> unresolvedErrors
) {
    /**
     * Statut du processus de correction.
     */
    public enum Status {
        /** Compilation reussie */
        SUCCESS,
        /** Echec - erreurs non resolues */
        FAILURE,
        /** Nombre maximum d'iterations atteint */
        MAX_ITERATIONS_REACHED
    }

    public boolean isSuccess() {
        return status == Status.SUCCESS;
    }

    public static AutoFixResult success(int iterations, List<ProvidedDependency> added) {
        return new AutoFixResult(Status.SUCCESS, iterations, added, Set.of());
    }

    public static AutoFixResult failure(int iterations, List<ProvidedDependency> added, Set<MissingDependency> errors) {
        return new AutoFixResult(Status.FAILURE, iterations, added, errors);
    }

    public static AutoFixResult maxIterationsReached(int iterations, List<ProvidedDependency> added, Set<MissingDependency> errors) {
        return new AutoFixResult(Status.MAX_ITERATIONS_REACHED, iterations, added, errors);
    }
}
