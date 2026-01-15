package fr.cnam.migration.model;

/**
 * Résultat d'une résolution de dépendance incluant les coordonnées Maven
 * et le repository source.
 */
public record ResolutionResult(
    MavenCoordinate coordinate,
    String sourceRepository
) {
    /**
     * Crée un résultat sans information de repository.
     */
    public static ResolutionResult of(MavenCoordinate coordinate) {
        return new ResolutionResult(coordinate, null);
    }

    /**
     * Crée un résultat avec information de repository.
     */
    public static ResolutionResult of(MavenCoordinate coordinate, String repository) {
        return new ResolutionResult(coordinate, repository);
    }

    /**
     * Vérifie si ce résultat provient du repository de dette technique.
     */
    public boolean isFromDebtRepository() {
        return sourceRepository != null &&
               sourceRepository.toLowerCase().contains("migration-java-dette");
    }
}
