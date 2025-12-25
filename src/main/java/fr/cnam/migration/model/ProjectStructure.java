package fr.cnam.migration.model;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Structure complète d'un projet Ant scanné.
 */
public record ProjectStructure(
    String name,
    Path projectRoot,
    ProjectType type,
    List<JarInfo> mainLibs,
    List<JarInfo> testLibs,
    List<JarInfo> providedLibs,
    List<AntBuildInfo> builds,
    List<InternalDependency> internalDeps,
    SourceLayout sourceLayout,
    EarConfiguration earConfig
) {
    /**
     * Retourne tous les JARs de toutes les catégories.
     */
    public List<JarInfo> allJars() {
        List<JarInfo> all = new ArrayList<>();
        all.addAll(mainLibs);
        all.addAll(testLibs);
        all.addAll(providedLibs);
        return all;
    }

    /**
     * Retourne les informations de build principal (non-pic).
     */
    public AntBuildInfo primaryBuild() {
        return builds.stream()
            .filter(b -> !b.isPicBuild())
            .findFirst()
            .orElse(builds.isEmpty() ? null : builds.get(0));
    }

    /**
     * Retourne les informations de build pic si disponibles.
     */
    public AntBuildInfo picBuild() {
        return builds.stream()
            .filter(AntBuildInfo::isPicBuild)
            .findFirst()
            .orElse(null);
    }

    /**
     * Informations sur la structure du code source.
     */
    public record SourceLayout(
        Path mainJavaDir,
        Path mainResourcesDir,
        Path testJavaDir,
        Path testResourcesDir,
        Path webappDir,
        int mainJavaFileCount,
        int testJavaFileCount
    ) {
        public static Builder builder() {
            return new Builder();
        }

        public static class Builder {
            private Path mainJavaDir;
            private Path mainResourcesDir;
            private Path testJavaDir;
            private Path testResourcesDir;
            private Path webappDir;
            private int mainJavaFileCount;
            private int testJavaFileCount;

            public Builder mainJavaDir(Path mainJavaDir) {
                this.mainJavaDir = mainJavaDir;
                return this;
            }

            public Builder mainResourcesDir(Path mainResourcesDir) {
                this.mainResourcesDir = mainResourcesDir;
                return this;
            }

            public Builder testJavaDir(Path testJavaDir) {
                this.testJavaDir = testJavaDir;
                return this;
            }

            public Builder testResourcesDir(Path testResourcesDir) {
                this.testResourcesDir = testResourcesDir;
                return this;
            }

            public Builder webappDir(Path webappDir) {
                this.webappDir = webappDir;
                return this;
            }

            public Builder mainJavaFileCount(int count) {
                this.mainJavaFileCount = count;
                return this;
            }

            public Builder testJavaFileCount(int count) {
                this.testJavaFileCount = count;
                return this;
            }

            public SourceLayout build() {
                return new SourceLayout(
                    mainJavaDir, mainResourcesDir, testJavaDir, testResourcesDir,
                    webappDir, mainJavaFileCount, testJavaFileCount
                );
            }
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String name;
        private Path projectRoot;
        private ProjectType type;
        private List<JarInfo> mainLibs = List.of();
        private List<JarInfo> testLibs = List.of();
        private List<JarInfo> providedLibs = List.of();
        private List<AntBuildInfo> builds = List.of();
        private List<InternalDependency> internalDeps = List.of();
        private SourceLayout sourceLayout;
        private EarConfiguration earConfig;

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder projectRoot(Path projectRoot) {
            this.projectRoot = projectRoot;
            return this;
        }

        public Builder type(ProjectType type) {
            this.type = type;
            return this;
        }

        public Builder mainLibs(List<JarInfo> mainLibs) {
            this.mainLibs = mainLibs;
            return this;
        }

        public Builder testLibs(List<JarInfo> testLibs) {
            this.testLibs = testLibs;
            return this;
        }

        public Builder providedLibs(List<JarInfo> providedLibs) {
            this.providedLibs = providedLibs;
            return this;
        }

        public Builder builds(List<AntBuildInfo> builds) {
            this.builds = builds;
            return this;
        }

        public Builder internalDeps(List<InternalDependency> internalDeps) {
            this.internalDeps = internalDeps;
            return this;
        }

        public Builder sourceLayout(SourceLayout sourceLayout) {
            this.sourceLayout = sourceLayout;
            return this;
        }

        public Builder earConfig(EarConfiguration earConfig) {
            this.earConfig = earConfig;
            return this;
        }

        public ProjectStructure build() {
            return new ProjectStructure(
                name, projectRoot, type, mainLibs, testLibs, providedLibs,
                builds, internalDeps, sourceLayout, earConfig
            );
        }
    }
}
