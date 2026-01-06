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
    EarConfiguration earConfig,
    DistributionConfig distributionConfig,
    List<ModuleInfo> modules,
    BatchConfiguration batchConfig
) {

    /**
     * Verifie si le projet est de type batch.
     */
    public boolean isBatch() {
        return type == ProjectType.BATCH;
    }

    /**
     * Verifie si le projet est multi-module.
     */
    public boolean isMultiModule() {
        return type == ProjectType.MULTI_MODULE && modules != null && !modules.isEmpty();
    }

    /**
     * Retourne les modules dans l'ordre de build.
     */
    public List<ModuleInfo> getModulesInBuildOrder() {
        if (modules == null) return List.of();
        return modules.stream()
            .sorted(java.util.Comparator.comparingInt(ModuleInfo::buildOrder))
            .toList();
    }
    /**
     * Configuration pour le packaging de distribution (module dist).
     * Contient les chemins vers install/conf, install/script et les exclusions.
     */
    public record DistributionConfig(
        Path installConfPath,
        Path installScriptPath,
        List<String> confExclusions
    ) {
        public boolean hasDistribution() {
            return installConfPath != null || installScriptPath != null;
        }
    }
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
        private DistributionConfig distributionConfig;
        private List<ModuleInfo> modules = List.of();
        private BatchConfiguration batchConfig;

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

        public Builder distributionConfig(DistributionConfig distributionConfig) {
            this.distributionConfig = distributionConfig;
            return this;
        }

        public Builder modules(List<ModuleInfo> modules) {
            this.modules = modules != null ? modules : List.of();
            return this;
        }

        public Builder batchConfig(BatchConfiguration batchConfig) {
            this.batchConfig = batchConfig;
            return this;
        }

        public ProjectStructure build() {
            return new ProjectStructure(
                name, projectRoot, type, mainLibs, testLibs, providedLibs,
                builds, internalDeps, sourceLayout, earConfig, distributionConfig, modules,
                batchConfig
            );
        }
    }
}
