package fr.cnam.migration.generator;

import fr.cnam.migration.config.JarNameCleaner;
import fr.cnam.migration.model.AnalysisResult;
import fr.cnam.migration.model.DependencyInfo;
import fr.cnam.migration.model.JarInfo;
import fr.cnam.migration.model.ModuleInfo;
import fr.cnam.migration.model.ProjectStructure;
import fr.cnam.migration.model.ProjectType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Crée la structure de répertoires Maven et copie les fichiers sources.
 */
public class StructureCreator {

    private static final Logger log = LoggerFactory.getLogger(StructureCreator.class);

    /**
     * Crée la structure complète des répertoires Maven.
     */
    public void createStructure(ProjectStructure project, Path outputDir) throws IOException {
        log.info("Creating Maven structure in: {}", outputDir);

        // Créer les répertoires parents
        Files.createDirectories(outputDir);

        // Créer les répertoires de modules basés sur le type de projet
        String moduleName = project.name().toLowerCase().replace("_j", "");

        Path webModule = outputDir.resolve(moduleName + "-web");
        Path earModule = outputDir.resolve(moduleName + "-ear");
        Path liblocale = outputDir.resolve("liblocale");

        // Créer la structure du module web
        createWebModuleStructure(webModule);

        // Créer la structure du module EAR
        createEarModuleStructure(earModule);

        // Créer le répertoire liblocale
        Files.createDirectories(liblocale);

        // Copier les fichiers sources
        copySourceFiles(project, webModule);

        // Copier la configuration EAR
        copyEarConfiguration(project, earModule);

        // Copier les JARs internes vers liblocale
        copyInternalJars(project, liblocale);

        log.info("Directory structure created successfully");
    }

    /**
     * Crée la structure complète des répertoires Maven pour un projet multi-module.
     */
    public void createMultiModuleStructure(ProjectStructure project, Path outputDir) throws IOException {
        log.info("Creating multi-module Maven structure in: {}", outputDir);

        // Créer les répertoires parents
        Files.createDirectories(outputDir);

        // Créer le répertoire liblocale
        Path liblocale = outputDir.resolve("liblocale");
        Files.createDirectories(liblocale);

        // Créer la structure pour chaque module
        for (ModuleInfo module : project.getModulesInBuildOrder()) {
            Path moduleDir = outputDir.resolve(module.artifactId());

            switch (module.type()) {
                case JAR -> createJarModuleStructure(module, moduleDir);
                case WAR -> createWarModuleStructure(module, moduleDir);
                case EAR -> createEarModuleStructure(moduleDir);
                case TEST -> createJarModuleStructure(module, moduleDir);
            }

            // Copier les fichiers sources
            copyModuleSources(module, moduleDir, project);
        }

        // Copier la configuration EAR
        ModuleInfo earModule = project.getModulesInBuildOrder().stream()
            .filter(m -> m.type() == ModuleInfo.ModuleType.EAR)
            .findFirst()
            .orElse(null);
        if (earModule != null) {
            copyEarConfiguration(project, outputDir.resolve(earModule.artifactId()));
        }

        log.info("Multi-module directory structure created successfully");
    }

    /**
     * Crée la structure d'un module JAR.
     */
    private void createJarModuleStructure(ModuleInfo module, Path moduleDir) throws IOException {
        Files.createDirectories(moduleDir.resolve("src/main/java"));
        if (module.resourceDir() != null) {
            Files.createDirectories(moduleDir.resolve("src/main/resources"));
        }
        if (module.type() == ModuleInfo.ModuleType.TEST) {
            Files.createDirectories(moduleDir.resolve("src/test/java"));
            Files.createDirectories(moduleDir.resolve("src/test/resources"));
        }
    }

    /**
     * Crée la structure d'un module WAR.
     */
    private void createWarModuleStructure(ModuleInfo module, Path moduleDir) throws IOException {
        Files.createDirectories(moduleDir.resolve("src/main/java"));
        Files.createDirectories(moduleDir.resolve("src/main/resources"));
        Files.createDirectories(moduleDir.resolve("src/main/webapp/WEB-INF"));
    }

    /**
     * Copie les sources d'un module.
     */
    private void copyModuleSources(ModuleInfo module, Path moduleDir, ProjectStructure project) throws IOException {
        // Copier les sources Java
        if (module.sourceDir() != null && Files.exists(module.sourceDir())) {
            Path targetJava = moduleDir.resolve("src/main/java");
            copyDirectory(module.sourceDir(), targetJava, path -> path.toString().endsWith(".java"));
            log.info("Copied Java sources for module: {}", module.artifactId());
        }

        // Copier les ressources (conf/)
        if (module.resourceDir() != null && Files.exists(module.resourceDir())) {
            Path targetResources = moduleDir.resolve("src/main/resources");
            copyDirectory(module.resourceDir(), targetResources, path -> !isExcludedConfig(path));
            log.info("Copied resources for module: {}", module.artifactId());
        }

        // Copier le webapp pour les modules WAR
        if (module.type() == ModuleInfo.ModuleType.WAR && module.webappDir() != null && Files.exists(module.webappDir())) {
            Path targetWebapp = moduleDir.resolve("src/main/webapp");
            copyDirectory(module.webappDir(), targetWebapp,
                path -> !path.toString().contains("WEB-INF" + FileSystems.getDefault().getSeparator() + "lib"));
            log.info("Copied webapp for module: {}", module.artifactId());
        }
    }

    private void createWebModuleStructure(Path webModule) throws IOException {
        Files.createDirectories(webModule.resolve("src/main/java"));
        Files.createDirectories(webModule.resolve("src/main/resources"));
        Files.createDirectories(webModule.resolve("src/main/webapp/WEB-INF"));
        Files.createDirectories(webModule.resolve("src/test/java"));
        Files.createDirectories(webModule.resolve("src/test/resources"));
    }

    private void createEarModuleStructure(Path earModule) throws IOException {
        Files.createDirectories(earModule.resolve("src/main/application/META-INF"));
    }

    /**
     * Copie les fichiers sources du projet original vers la structure Maven.
     */
    private void copySourceFiles(ProjectStructure project, Path webModule) throws IOException {
        ProjectStructure.SourceLayout layout = project.sourceLayout();

        // Copier les sources Java principales
        if (layout.mainJavaDir() != null && Files.exists(layout.mainJavaDir())) {
            copyDirectory(layout.mainJavaDir(), webModule.resolve("src/main/java"),
                path -> true);
            log.info("Copied main Java sources: {} files", layout.mainJavaFileCount());
        }

        // Copier les ressources principales
        if (layout.mainResourcesDir() != null && Files.exists(layout.mainResourcesDir())) {
            copyDirectory(layout.mainResourcesDir(), webModule.resolve("src/main/resources"),
                path -> !isExcludedConfig(path));
            log.info("Copied main resources");
        }

        // Copier webapp (en excluant WEB-INF/lib)
        if (layout.webappDir() != null && Files.exists(layout.webappDir())) {
            copyDirectory(layout.webappDir(), webModule.resolve("src/main/webapp"),
                path -> !path.toString().contains("WEB-INF" + FileSystems.getDefault().getSeparator() + "lib"));
            log.info("Copied webapp content");
        }

        // Copier les sources de test
        if (layout.testJavaDir() != null && Files.exists(layout.testJavaDir())) {
            copyDirectory(layout.testJavaDir(), webModule.resolve("src/test/java"),
                path -> true);
            log.info("Copied test Java sources: {} files", layout.testJavaFileCount());
        }

        // Copier les ressources de test
        if (layout.testResourcesDir() != null && Files.exists(layout.testResourcesDir())) {
            copyDirectory(layout.testResourcesDir(), webModule.resolve("src/test/resources"),
                path -> true);
            log.info("Copied test resources");
        }
    }

    /**
     * Copie les fichiers de configuration EAR.
     */
    private void copyEarConfiguration(ProjectStructure project, Path earModule) throws IOException {
        if (project.earConfig() == null) {
            return;
        }

        Path metaInf = earModule.resolve("src/main/application/META-INF");

        // Copier application.xml
        if (project.earConfig().applicationXml() != null) {
            Path source = project.earConfig().applicationXml();
            if (Files.exists(source)) {
                Files.copy(source, metaInf.resolve("application.xml"),
                    StandardCopyOption.REPLACE_EXISTING);
                log.info("Copied application.xml");
            }
        }

        // Copier weblogic-application.xml
        if (project.earConfig().weblogicApplicationXml() != null) {
            Path source = project.earConfig().weblogicApplicationXml();
            if (Files.exists(source)) {
                Files.copy(source, metaInf.resolve("weblogic-application.xml"),
                    StandardCopyOption.REPLACE_EXISTING);
                log.info("Copied weblogic-application.xml");
            }
        }

        // Copier les fichiers APP-INF/conf si présents
        if (project.earConfig().appInfConfFiles() != null) {
            Path confDir = earModule.resolve("src/main/conf");
            Files.createDirectories(confDir);
            for (Path confFile : project.earConfig().appInfConfFiles()) {
                if (Files.exists(confFile)) {
                    Files.copy(confFile, confDir.resolve(confFile.getFileName()),
                        StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    /**
     * Copie les JARs internes vers le répertoire liblocale.
     *
     * IMPORTANT: Cette méthode ne copie plus automatiquement les JARs basés sur
     * isInternalArtifact() car cela ne tient pas compte des coordonnées Maven résolues.
     * Par exemple, struts.jar a isInternalArtifact()=true mais ses coordonnées sont
     * org.apache.struts:struts-core qui est disponible sur Maven Central.
     *
     * Les JARs à copier sont maintenant déterminés par l'analyse des dépendances
     * via copyInternalResolvedJars() et copyUnresolvedJars().
     *
     * Les noms de JARs sont nettoyés pour supprimer les préfixes DEPFAB. et les codes projet.
     */
    private void copyInternalJars(ProjectStructure project, Path liblocale) throws IOException {
        // Ne copie plus automatiquement les JARs ici.
        // La copie est maintenant gérée par copyInternalResolvedJars() et copyUnresolvedJars()
        // qui tiennent compte des coordonnées Maven résolues.
        log.debug("copyInternalJars: les JARs seront copiés via copyInternalResolvedJars/copyUnresolvedJars");
    }

    /**
     * Copie les JARs non résolus vers le répertoire liblocale.
     *
     * Ces JARs n'ont pas pu être résolus en coordonnées Maven (ni sur Maven Central,
     * ni sur Artifactory, ni via les patterns connus). Ils doivent quand même être
     * inclus dans le projet pour permettre la compilation.
     *
     * @param analysis Résultat de l'analyse des dépendances
     * @param outputDir Répertoire de sortie du projet Maven
     * @return Nombre de JARs copiés
     */
    public int copyUnresolvedJars(AnalysisResult analysis, Path outputDir) throws IOException {
        Path liblocale = outputDir.resolve("liblocale");
        Files.createDirectories(liblocale);

        // Collecter les noms de fichiers déjà présents pour éviter les doublons
        Set<String> existingFiles = new HashSet<>();
        if (Files.exists(liblocale)) {
            try (var stream = Files.list(liblocale)) {
                stream.filter(Files::isRegularFile)
                      .forEach(p -> existingFiles.add(p.getFileName().toString()));
            }
        }

        int count = 0;
        for (AnalysisResult.UnresolvedJar unresolved : analysis.unresolved()) {
            JarInfo jar = unresolved.jar();

            if (!Files.exists(jar.path())) {
                log.warn("JAR non résolu introuvable : {}", jar.path());
                continue;
            }

            // Nettoyer le nom du JAR
            String cleanedName = JarNameCleaner.clean(jar.name());

            // Éviter les doublons
            if (existingFiles.contains(cleanedName)) {
                log.debug("JAR déjà présent dans liblocale : {}", cleanedName);
                continue;
            }

            Files.copy(jar.path(), liblocale.resolve(cleanedName),
                StandardCopyOption.REPLACE_EXISTING);
            existingFiles.add(cleanedName);
            count++;

            if (!cleanedName.equals(jar.name())) {
                log.info("Copié JAR non résolu : {} → {}", jar.name(), cleanedName);
            } else {
                log.info("Copié JAR non résolu : {}", cleanedName);
            }
        }

        if (count > 0) {
            log.info("Copié {} JARs non résolus vers liblocale", count);
        }

        return count;
    }

    /**
     * Copie les JARs des dépendances internes résolues vers le répertoire liblocale.
     *
     * Seuls les JARs dont les coordonnées Maven commencent par fr.cnamts ou fr.cnam
     * sont copiés. Les JARs avec des coordonnées Maven Central (org.apache.*, com.*, etc.)
     * ne sont PAS copiés car ils seront téléchargés automatiquement par Maven.
     *
     * @param analysis Résultat de l'analyse des dépendances
     * @param outputDir Répertoire de sortie du projet Maven
     * @return Nombre de JARs copiés
     */
    public int copyInternalResolvedJars(AnalysisResult analysis, Path outputDir) throws IOException {
        Path liblocale = outputDir.resolve("liblocale");
        Files.createDirectories(liblocale);

        // Collecter les noms de fichiers déjà présents pour éviter les doublons
        Set<String> existingFiles = new HashSet<>();
        if (Files.exists(liblocale)) {
            try (var stream = Files.list(liblocale)) {
                stream.filter(Files::isRegularFile)
                      .forEach(p -> existingFiles.add(p.getFileName().toString()));
            }
        }

        int count = 0;
        int skippedExternal = 0;

        // Copier les dépendances avec version LOCAL (nécessitent installation locale)
        for (DependencyInfo dep : analysis.localDependencies()) {
            JarInfo jar = dep.sourceJar();

            if (jar == null || !Files.exists(jar.path())) {
                log.warn("JAR interne introuvable : {}", dep.artifactId());
                continue;
            }

            // Nettoyer le nom du JAR
            String cleanedName = JarNameCleaner.clean(jar.name());

            // Éviter les doublons
            if (existingFiles.contains(cleanedName)) {
                log.debug("JAR déjà présent dans liblocale : {}", cleanedName);
                continue;
            }

            Files.copy(jar.path(), liblocale.resolve(cleanedName),
                StandardCopyOption.REPLACE_EXISTING);
            existingFiles.add(cleanedName);
            count++;

            if (!cleanedName.equals(jar.name())) {
                log.info("Copié JAR interne résolu : {} → {} ({}:{}:{})",
                    jar.name(), cleanedName, dep.groupId(), dep.artifactId(), dep.version());
            } else {
                log.info("Copié JAR interne résolu : {} ({}:{}:{})",
                    cleanedName, dep.groupId(), dep.artifactId(), dep.version());
            }
        }

        if (count > 0) {
            log.info("Copié {} JARs internes résolus vers liblocale", count);
        }

        return count;
    }

    /**
     * Copie un répertoire récursivement avec un filtre.
     */
    private void copyDirectory(Path source, Path target, Predicate<Path> filter)
            throws IOException {
        if (!Files.exists(source)) {
            return;
        }

        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
                    throws IOException {
                // Ignorer les répertoires CVS
                if (dir.getFileName().toString().equals("CVS")) {
                    return FileVisitResult.SKIP_SUBTREE;
                }

                Path targetDir = target.resolve(source.relativize(dir));
                if (!Files.exists(targetDir)) {
                    Files.createDirectories(targetDir);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                    throws IOException {
                if (filter.test(file)) {
                    Path targetFile = target.resolve(source.relativize(file));
                    Files.copy(file, targetFile, StandardCopyOption.REPLACE_EXISTING);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                log.warn("Failed to copy {}: {}", file, exc.getMessage());
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /**
     * Vérifie si un fichier doit être exclu de la copie (configurations spécifiques à l'environnement).
     */
    private boolean isExcludedConfig(Path path) {
        String name = path.getFileName().toString().toLowerCase();
        return name.endsWith(".modele") ||
               name.equals("jdbc.properties") ||
               name.equals("log4j.properties") ||
               name.equals("log4j2.xml");
    }

    /**
     * Copie les répertoires install/ vers le projet Maven pour le module de distribution.
     * Copie install/conf et install/script depuis le projet source vers le projet migré.
     *
     * @param project Structure du projet source
     * @param outputDir Répertoire de sortie du projet Maven
     */
    public void copyInstallDirectories(ProjectStructure project, Path outputDir) throws IOException {
        ProjectStructure.DistributionConfig distConfig = project.distributionConfig();
        if (distConfig == null || !distConfig.hasDistribution()) {
            return;
        }

        Path installDir = outputDir.resolve("install");
        Files.createDirectories(installDir);

        // Copier install/conf
        if (distConfig.installConfPath() != null && Files.exists(distConfig.installConfPath())) {
            Path targetConf = installDir.resolve("conf");
            copyDirectory(distConfig.installConfPath(), targetConf, path -> true);
            log.info("Copied install/conf directory");
        }

        // Copier install/script
        if (distConfig.installScriptPath() != null && Files.exists(distConfig.installScriptPath())) {
            Path targetScript = installDir.resolve("script");
            copyDirectory(distConfig.installScriptPath(), targetScript, path -> true);
            log.info("Copied install/script directory");
        }

        // Créer le répertoire install/liv (vide, sera rempli par le build)
        Path livDir = installDir.resolve("liv");
        Files.createDirectories(livDir);
        log.info("Created install/liv directory for distribution output");
    }

    /**
     * Crée la structure du module de distribution.
     *
     * @param outputDir Répertoire de sortie du projet Maven
     * @param moduleName Nom du module dist (ex: r0-dist)
     */
    public void createDistModuleStructure(Path outputDir, String moduleName) throws IOException {
        Path distModule = outputDir.resolve(moduleName);
        Path assemblyDir = distModule.resolve("src/assembly");
        Files.createDirectories(assemblyDir);
        log.info("Created dist module structure: {}", moduleName);
    }
}
