package fr.cnam.migration.config;

import java.nio.file.Path;

/**
 * Configuration globale pour le processus de migration.
 */
public record MigrationConfig(
    Path projectRoot,
    Path outputDir,
    Path knownArtifactsFile,
    String internalRepoUrl,
    boolean dryRun,
    String buildVariant,  // "default" ou "pic"
    boolean skipMavenCentralLookup,
    boolean verbose,
    String basePackage,    // Package de base pour les artefacts internes (fr.cnamts ou fr.cnam)
    // Configuration Artifactory
    String artifactoryUrl,           // URL de base du serveur Artifactory
    Path artifactoryCertPath,        // Chemin vers le fichier certificat .crt pour SSL
    String artifactoryReleaseRepo,   // Repository pour les artefacts release
    String artifactorySnapshotRepo,  // Repository pour les artefacts snapshot
    String artifactoryUsername,      // Nom d'utilisateur pour l'authentification Artifactory
    String artifactoryPassword,      // Mot de passe/token pour l'authentification Artifactory
    DeploymentMode deploymentMode    // Déploiement LOCAL ou REMOTE
) {
    /**
     * Package de base par défaut pour les artefacts internes.
     */
    public static final String DEFAULT_BASE_PACKAGE = "fr.cnamts";

    /**
     * Mode de déploiement pour les artefacts non résolus.
     */
    public enum DeploymentMode {
        /** Installe les artefacts dans le repository local .m2 */
        LOCAL,
        /** Upload les artefacts vers Artifactory */
        REMOTE
    }

    public static Builder builder() {
        return new Builder();
    }

    public boolean isPicBuild() {
        return "pic".equalsIgnoreCase(buildVariant);
    }

    /**
     * Retourne true si Artifactory est configuré et peut être utilisé pour les recherches.
     */
    public boolean isArtifactoryConfigured() {
        return artifactoryUrl != null && !artifactoryUrl.isBlank();
    }

    /**
     * Retourne true si le déploiement vers Artifactory est activé.
     */
    public boolean isRemoteDeployment() {
        return deploymentMode == DeploymentMode.REMOTE && isArtifactoryConfigured();
    }

    public static class Builder {
        private Path projectRoot;
        private Path outputDir;
        private Path knownArtifactsFile;
        private String internalRepoUrl;
        private boolean dryRun;
        private String buildVariant = "default";
        private boolean skipMavenCentralLookup;
        private boolean verbose;
        private String basePackage = DEFAULT_BASE_PACKAGE;
        // Paramètres Artifactory
        private String artifactoryUrl;
        private Path artifactoryCertPath;
        private String artifactoryReleaseRepo = "libs-release-local";
        private String artifactorySnapshotRepo = "libs-snapshot-local";
        private String artifactoryUsername;
        private String artifactoryPassword;
        private DeploymentMode deploymentMode = DeploymentMode.LOCAL;

        public Builder projectRoot(Path projectRoot) {
            this.projectRoot = projectRoot;
            return this;
        }

        public Builder outputDir(Path outputDir) {
            this.outputDir = outputDir;
            return this;
        }

        public Builder knownArtifactsFile(Path knownArtifactsFile) {
            this.knownArtifactsFile = knownArtifactsFile;
            return this;
        }

        public Builder internalRepoUrl(String internalRepoUrl) {
            this.internalRepoUrl = internalRepoUrl;
            return this;
        }

        public Builder dryRun(boolean dryRun) {
            this.dryRun = dryRun;
            return this;
        }

        public Builder buildVariant(String buildVariant) {
            this.buildVariant = buildVariant;
            return this;
        }

        public Builder skipMavenCentralLookup(boolean skip) {
            this.skipMavenCentralLookup = skip;
            return this;
        }

        public Builder verbose(boolean verbose) {
            this.verbose = verbose;
            return this;
        }

        public Builder basePackage(String basePackage) {
            this.basePackage = basePackage != null ? basePackage : DEFAULT_BASE_PACKAGE;
            return this;
        }

        public Builder artifactoryUrl(String artifactoryUrl) {
            this.artifactoryUrl = artifactoryUrl;
            return this;
        }

        public Builder artifactoryCertPath(Path artifactoryCertPath) {
            this.artifactoryCertPath = artifactoryCertPath;
            return this;
        }

        public Builder artifactoryReleaseRepo(String artifactoryReleaseRepo) {
            this.artifactoryReleaseRepo = artifactoryReleaseRepo;
            return this;
        }

        public Builder artifactorySnapshotRepo(String artifactorySnapshotRepo) {
            this.artifactorySnapshotRepo = artifactorySnapshotRepo;
            return this;
        }

        public Builder artifactoryUsername(String artifactoryUsername) {
            this.artifactoryUsername = artifactoryUsername;
            return this;
        }

        public Builder artifactoryPassword(String artifactoryPassword) {
            this.artifactoryPassword = artifactoryPassword;
            return this;
        }

        public Builder deploymentMode(DeploymentMode deploymentMode) {
            this.deploymentMode = deploymentMode != null ? deploymentMode : DeploymentMode.LOCAL;
            return this;
        }

        public MigrationConfig build() {
            if (outputDir == null && projectRoot != null) {
                outputDir = projectRoot.resolveSibling(projectRoot.getFileName() + "-maven");
            }
            return new MigrationConfig(
                projectRoot, outputDir, knownArtifactsFile, internalRepoUrl,
                dryRun, buildVariant, skipMavenCentralLookup, verbose, basePackage,
                artifactoryUrl, artifactoryCertPath, artifactoryReleaseRepo,
                artifactorySnapshotRepo, artifactoryUsername, artifactoryPassword,
                deploymentMode
            );
        }
    }
}
