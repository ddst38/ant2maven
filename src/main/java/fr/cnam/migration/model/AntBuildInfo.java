package fr.cnam.migration.model;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Information extracted from an Ant build.xml file.
 */
public record AntBuildInfo(
    Path buildFile,
    String projectName,
    String defaultTarget,
    String appCode,
    String warName,
    Map<String, String> properties,
    List<String> sourceDirs,
    List<String> resourceDirs,
    List<String> libDirs,
    List<String> classpathEntries,
    List<String> excludedFiles,
    String webappDir,
    String earConfDir,
    boolean isPicBuild  // true if this is a build.pic.xml
) {
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private Path buildFile;
        private String projectName;
        private String defaultTarget = "package";
        private String appCode;
        private String warName;
        private Map<String, String> properties = Map.of();
        private List<String> sourceDirs = List.of();
        private List<String> resourceDirs = List.of();
        private List<String> libDirs = List.of();
        private List<String> classpathEntries = List.of();
        private List<String> excludedFiles = List.of();
        private String webappDir;
        private String earConfDir;
        private boolean isPicBuild;

        public Builder buildFile(Path buildFile) {
            this.buildFile = buildFile;
            return this;
        }

        public Builder projectName(String projectName) {
            this.projectName = projectName;
            return this;
        }

        public Builder defaultTarget(String defaultTarget) {
            this.defaultTarget = defaultTarget;
            return this;
        }

        public Builder appCode(String appCode) {
            this.appCode = appCode;
            return this;
        }

        public Builder warName(String warName) {
            this.warName = warName;
            return this;
        }

        public Builder properties(Map<String, String> properties) {
            this.properties = properties;
            return this;
        }

        public Builder sourceDirs(List<String> sourceDirs) {
            this.sourceDirs = sourceDirs;
            return this;
        }

        public Builder resourceDirs(List<String> resourceDirs) {
            this.resourceDirs = resourceDirs;
            return this;
        }

        public Builder libDirs(List<String> libDirs) {
            this.libDirs = libDirs;
            return this;
        }

        public Builder classpathEntries(List<String> classpathEntries) {
            this.classpathEntries = classpathEntries;
            return this;
        }

        public Builder excludedFiles(List<String> excludedFiles) {
            this.excludedFiles = excludedFiles;
            return this;
        }

        public Builder webappDir(String webappDir) {
            this.webappDir = webappDir;
            return this;
        }

        public Builder earConfDir(String earConfDir) {
            this.earConfDir = earConfDir;
            return this;
        }

        public Builder isPicBuild(boolean isPicBuild) {
            this.isPicBuild = isPicBuild;
            return this;
        }

        public AntBuildInfo build() {
            return new AntBuildInfo(
                buildFile, projectName, defaultTarget, appCode, warName,
                properties, sourceDirs, resourceDirs, libDirs, classpathEntries,
                excludedFiles, webappDir, earConfDir, isPicBuild
            );
        }
    }
}
