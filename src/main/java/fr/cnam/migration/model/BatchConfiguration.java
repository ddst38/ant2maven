package fr.cnam.migration.model;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Configuration specifique aux projets batch (Spring Batch, etc.).
 * Contient les informations necessaires pour generer un JAR executable.
 */
public record BatchConfiguration(
    String mainClass,           // Main-Class pour le manifest JAR
    String jarName,             // Nom du JAR (applicationName)
    String jarVersion,          // Version du JAR (applicationVersion)
    List<Path> launchScripts,   // Scripts de lancement (script/*.sh)
    String specificationTitle,  // Titre pour le manifest (ex: "Starter Kit Spring Batch")
    boolean hasPostgresProfile  // Utilise le profil POSTGRESQL
) {

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String mainClass;
        private String jarName;
        private String jarVersion = "1.0.0-SNAPSHOT";
        private List<Path> launchScripts = new ArrayList<>();
        private String specificationTitle = "Spring Batch Application";
        private boolean hasPostgresProfile = false;

        public Builder mainClass(String mainClass) {
            this.mainClass = mainClass;
            return this;
        }

        public Builder jarName(String jarName) {
            this.jarName = jarName;
            return this;
        }

        public Builder jarVersion(String jarVersion) {
            this.jarVersion = jarVersion;
            return this;
        }

        public Builder launchScripts(List<Path> launchScripts) {
            this.launchScripts = launchScripts != null ? launchScripts : new ArrayList<>();
            return this;
        }

        public Builder addLaunchScript(Path script) {
            this.launchScripts.add(script);
            return this;
        }

        public Builder specificationTitle(String specificationTitle) {
            this.specificationTitle = specificationTitle;
            return this;
        }

        public Builder hasPostgresProfile(boolean hasPostgresProfile) {
            this.hasPostgresProfile = hasPostgresProfile;
            return this;
        }

        public BatchConfiguration build() {
            return new BatchConfiguration(
                mainClass,
                jarName,
                jarVersion,
                launchScripts,
                specificationTitle,
                hasPostgresProfile
            );
        }
    }
}
