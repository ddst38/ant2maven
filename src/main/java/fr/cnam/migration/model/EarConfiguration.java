package fr.cnam.migration.model;

import java.nio.file.Path;
import java.util.List;

/**
 * EAR packaging configuration extracted from the source project.
 */
public record EarConfiguration(
    String displayName,
    String contextRoot,
    String warFileName,
    Path applicationXml,
    Path weblogicApplicationXml,
    List<Path> appInfLibJars,
    List<Path> appInfConfFiles,
    String sharedLibraryName,  // e.g., "GMIC_W" or "R0_W"
    List<String> preferApplicationPackages
) {
    public static Builder builder() {
        return new Builder();
    }

    public boolean hasSharedLibrary() {
        return sharedLibraryName != null && !sharedLibraryName.isEmpty();
    }

    public boolean hasAppInfLib() {
        return appInfLibJars != null && !appInfLibJars.isEmpty();
    }

    public static class Builder {
        private String displayName;
        private String contextRoot;
        private String warFileName;
        private Path applicationXml;
        private Path weblogicApplicationXml;
        private List<Path> appInfLibJars = List.of();
        private List<Path> appInfConfFiles = List.of();
        private String sharedLibraryName;
        private List<String> preferApplicationPackages = List.of();

        public Builder displayName(String displayName) {
            this.displayName = displayName;
            return this;
        }

        public Builder contextRoot(String contextRoot) {
            this.contextRoot = contextRoot;
            return this;
        }

        public Builder warFileName(String warFileName) {
            this.warFileName = warFileName;
            return this;
        }

        public Builder applicationXml(Path applicationXml) {
            this.applicationXml = applicationXml;
            return this;
        }

        public Builder weblogicApplicationXml(Path weblogicApplicationXml) {
            this.weblogicApplicationXml = weblogicApplicationXml;
            return this;
        }

        public Builder appInfLibJars(List<Path> appInfLibJars) {
            this.appInfLibJars = appInfLibJars;
            return this;
        }

        public Builder appInfConfFiles(List<Path> appInfConfFiles) {
            this.appInfConfFiles = appInfConfFiles;
            return this;
        }

        public Builder sharedLibraryName(String sharedLibraryName) {
            this.sharedLibraryName = sharedLibraryName;
            return this;
        }

        public Builder preferApplicationPackages(List<String> preferApplicationPackages) {
            this.preferApplicationPackages = preferApplicationPackages;
            return this;
        }

        public EarConfiguration build() {
            return new EarConfiguration(
                displayName, contextRoot, warFileName,
                applicationXml, weblogicApplicationXml,
                appInfLibJars, appInfConfFiles,
                sharedLibraryName, preferApplicationPackages
            );
        }
    }
}
