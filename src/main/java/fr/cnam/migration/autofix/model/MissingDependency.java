package fr.cnam.migration.autofix.model;

/**
 * Represente une dependance manquante detectee lors de la compilation.
 */
public record MissingDependency(
    Type type,
    String name,
    String sourceFile
) {
    /**
     * Type de dependance manquante.
     */
    public enum Type {
        /** Classe introuvable (ex: WebLogicDataSource) */
        CLASS,
        /** Package inexistant (ex: weblogic.jdbc.extensions) */
        PACKAGE
    }

    /**
     * Retourne le package de la classe ou le package lui-meme.
     */
    public String getPackage() {
        if (type == Type.PACKAGE) {
            return name;
        }
        // Pour une classe, extraire le package
        int lastDot = name.lastIndexOf('.');
        return lastDot > 0 ? name.substring(0, lastDot) : "";
    }

    /**
     * Retourne le nom simple de la classe (sans package).
     */
    public String getSimpleName() {
        if (type == Type.PACKAGE) {
            return name;
        }
        int lastDot = name.lastIndexOf('.');
        return lastDot > 0 ? name.substring(lastDot + 1) : name;
    }
}
