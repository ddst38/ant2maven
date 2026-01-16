package fr.cnam.migration.jdeps;

import java.util.List;

/**
 * Represente un cycle de dependances entre packages.
 */
public record CycleDependency(
    List<String> packages,   // Liste des packages formant le cycle
    int length               // Longueur du cycle
) {
    public CycleDependency(List<String> packages) {
        this(packages, packages.size());
    }

    /**
     * Retourne une representation textuelle du cycle.
     * Ex: "fr.cnam.a -> fr.cnam.b -> fr.cnam.c -> fr.cnam.a"
     */
    public String toCycleString() {
        if (packages == null || packages.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < packages.size(); i++) {
            if (i > 0) sb.append(" -> ");
            sb.append(packages.get(i));
        }
        // Ajouter le retour au premier package pour montrer le cycle
        if (packages.size() > 1) {
            sb.append(" -> ").append(packages.get(0));
        }
        return sb.toString();
    }
}
