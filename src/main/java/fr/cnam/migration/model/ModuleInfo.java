package fr.cnam.migration.model;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Information sur un module dans un projet multi-module.
 * Chaque module correspond a une target JAR/WAR dans le build.xml ANT.
 */
public record ModuleInfo(
    String name,                    // Nom original (ex: "MetierFANOClient")
    String artifactId,              // Nom Maven (ex: "fano-client")
    ModuleType type,                // JAR, WAR, EAR, TEST
    Path sourceDir,                 // Repertoire source Java
    Path resourceDir,               // Repertoire ressources (conf/)
    Path webappDir,                 // Repertoire webapp (pour WAR)
    List<String> dependsOn,         // Noms des modules dont il depend
    String antTargetName,           // Nom de la target ANT (ex: "jarMetierClient")
    int buildOrder                  // Ordre de compilation (0 = premier)
) {

    /**
     * Type de module.
     */
    public enum ModuleType {
        JAR,    // Bibliotheque Java
        WAR,    // Application Web
        EAR,    // Enterprise Archive
        TEST    // Module de tests
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Verifie si ce module a des dependances internes.
     */
    public boolean hasInternalDependencies() {
        return dependsOn != null && !dependsOn.isEmpty();
    }

    /**
     * Builder pour ModuleInfo.
     */
    public static class Builder {
        private String name;
        private String artifactId;
        private ModuleType type = ModuleType.JAR;
        private Path sourceDir;
        private Path resourceDir;
        private Path webappDir;
        private List<String> dependsOn = new ArrayList<>();
        private String antTargetName;
        private int buildOrder;

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder artifactId(String artifactId) {
            this.artifactId = artifactId;
            return this;
        }

        public Builder type(ModuleType type) {
            this.type = type;
            return this;
        }

        public Builder sourceDir(Path sourceDir) {
            this.sourceDir = sourceDir;
            return this;
        }

        public Builder resourceDir(Path resourceDir) {
            this.resourceDir = resourceDir;
            return this;
        }

        public Builder webappDir(Path webappDir) {
            this.webappDir = webappDir;
            return this;
        }

        public Builder dependsOn(List<String> dependsOn) {
            this.dependsOn = dependsOn != null ? new ArrayList<>(dependsOn) : new ArrayList<>();
            return this;
        }

        public Builder addDependency(String moduleName) {
            this.dependsOn.add(moduleName);
            return this;
        }

        public Builder antTargetName(String antTargetName) {
            this.antTargetName = antTargetName;
            return this;
        }

        public Builder buildOrder(int buildOrder) {
            this.buildOrder = buildOrder;
            return this;
        }

        public ModuleInfo build() {
            return new ModuleInfo(
                name,
                artifactId,
                type,
                sourceDir,
                resourceDir,
                webappDir,
                List.copyOf(dependsOn),
                antTargetName,
                buildOrder
            );
        }
    }
}
