package fr.cnam.migration.generator;

import fr.cnam.migration.config.JarNameCleaner;
import fr.cnam.migration.model.AnalysisResult;
import fr.cnam.migration.model.DependencyInfo;
import fr.cnam.migration.model.JarInfo;
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
     * Les noms de JARs sont nettoyés pour supprimer les préfixes DEPFAB. et les codes projet.
     * Le préfixe DEPFAB. indique une dépendance de fabrication qui était fournie par l'IC ANT.
     *
     * Exemples de nettoyage :
     * - DEPFAB.W1_ServiceImageDecompte_v1.0_client.jar → W1_ServiceImageDecompte_v1.0_client.jar
     * - DEPFAB.S8_J.nimbus-jose-jwt-4.23-jdk16.jar → nimbus-jose-jwt-4.23-jdk16.jar
     */
    private void copyInternalJars(ProjectStructure project, Path liblocale) throws IOException {
        int count = 0;
        int cleaned = 0;
        for (var jar : project.allJars()) {
            if (jar.isInternalArtifact() && Files.exists(jar.path())) {
                // Nettoyer le nom du JAR (supprimer DEPFAB. et code projet si présents)
                String cleanedName = JarNameCleaner.clean(jar.name());

                if (!cleanedName.equals(jar.name())) {
                    cleaned++;
                }

                Files.copy(jar.path(), liblocale.resolve(cleanedName),
                    StandardCopyOption.REPLACE_EXISTING);
                count++;
            }
        }
        log.info("Copié {} JARs internes vers liblocale ({} noms nettoyés)", count, cleaned);
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
     * Ces JARs ont été résolus via known-artifacts.yaml ou les patterns internes,
     * mais ils doivent quand même être installés localement car ils ne sont pas
     * disponibles sur Maven Central.
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
        for (DependencyInfo dep : analysis.internalDependencies()) {
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
                log.info("Copié JAR interne résolu : {} → {}", jar.name(), cleanedName);
            } else {
                log.info("Copié JAR interne résolu : {}", cleanedName);
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
}
