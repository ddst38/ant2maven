package fr.cnam.migration.generator;

import fr.cnam.migration.model.ProjectStructure;
import fr.cnam.migration.model.ProjectType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.function.Predicate;

/**
 * Creates Maven directory structure and copies source files.
 */
public class StructureCreator {

    private static final Logger log = LoggerFactory.getLogger(StructureCreator.class);

    /**
     * Creates the complete Maven directory structure.
     */
    public void createStructure(ProjectStructure project, Path outputDir) throws IOException {
        log.info("Creating Maven structure in: {}", outputDir);

        // Create parent directories
        Files.createDirectories(outputDir);

        // Create module directories based on project type
        String moduleName = project.name().toLowerCase().replace("_j", "");

        Path webModule = outputDir.resolve(moduleName + "-web");
        Path earModule = outputDir.resolve(moduleName + "-ear");
        Path liblocale = outputDir.resolve("liblocale");

        // Create web module structure
        createWebModuleStructure(webModule);

        // Create EAR module structure
        createEarModuleStructure(earModule);

        // Create liblocale directory
        Files.createDirectories(liblocale);

        // Copy source files
        copySourceFiles(project, webModule);

        // Copy EAR configuration
        copyEarConfiguration(project, earModule);

        // Copy internal JARs to liblocale
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
     * Copies source files from the original project to Maven structure.
     */
    private void copySourceFiles(ProjectStructure project, Path webModule) throws IOException {
        ProjectStructure.SourceLayout layout = project.sourceLayout();

        // Copy main Java sources
        if (layout.mainJavaDir() != null && Files.exists(layout.mainJavaDir())) {
            copyDirectory(layout.mainJavaDir(), webModule.resolve("src/main/java"),
                path -> true);
            log.info("Copied main Java sources: {} files", layout.mainJavaFileCount());
        }

        // Copy main resources
        if (layout.mainResourcesDir() != null && Files.exists(layout.mainResourcesDir())) {
            copyDirectory(layout.mainResourcesDir(), webModule.resolve("src/main/resources"),
                path -> !isExcludedConfig(path));
            log.info("Copied main resources");
        }

        // Copy webapp (excluding WEB-INF/lib)
        if (layout.webappDir() != null && Files.exists(layout.webappDir())) {
            copyDirectory(layout.webappDir(), webModule.resolve("src/main/webapp"),
                path -> !path.toString().contains("WEB-INF" + FileSystems.getDefault().getSeparator() + "lib"));
            log.info("Copied webapp content");
        }

        // Copy test sources
        if (layout.testJavaDir() != null && Files.exists(layout.testJavaDir())) {
            copyDirectory(layout.testJavaDir(), webModule.resolve("src/test/java"),
                path -> true);
            log.info("Copied test Java sources: {} files", layout.testJavaFileCount());
        }

        // Copy test resources
        if (layout.testResourcesDir() != null && Files.exists(layout.testResourcesDir())) {
            copyDirectory(layout.testResourcesDir(), webModule.resolve("src/test/resources"),
                path -> true);
            log.info("Copied test resources");
        }
    }

    /**
     * Copies EAR configuration files.
     */
    private void copyEarConfiguration(ProjectStructure project, Path earModule) throws IOException {
        if (project.earConfig() == null) {
            return;
        }

        Path metaInf = earModule.resolve("src/main/application/META-INF");

        // Copy application.xml
        if (project.earConfig().applicationXml() != null) {
            Path source = project.earConfig().applicationXml();
            if (Files.exists(source)) {
                Files.copy(source, metaInf.resolve("application.xml"),
                    StandardCopyOption.REPLACE_EXISTING);
                log.info("Copied application.xml");
            }
        }

        // Copy weblogic-application.xml
        if (project.earConfig().weblogicApplicationXml() != null) {
            Path source = project.earConfig().weblogicApplicationXml();
            if (Files.exists(source)) {
                Files.copy(source, metaInf.resolve("weblogic-application.xml"),
                    StandardCopyOption.REPLACE_EXISTING);
                log.info("Copied weblogic-application.xml");
            }
        }

        // Copy APP-INF/conf files if present
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
     * Copies internal JARs to liblocale directory.
     */
    private void copyInternalJars(ProjectStructure project, Path liblocale) throws IOException {
        int count = 0;
        for (var jar : project.allJars()) {
            if (jar.isInternalArtifact() && Files.exists(jar.path())) {
                Files.copy(jar.path(), liblocale.resolve(jar.name()),
                    StandardCopyOption.REPLACE_EXISTING);
                count++;
            }
        }
        log.info("Copied {} internal JARs to liblocale", count);
    }

    /**
     * Copies a directory recursively with a filter.
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
                // Skip CVS directories
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
     * Checks if a file should be excluded from copying (environment-specific configs).
     */
    private boolean isExcludedConfig(Path path) {
        String name = path.getFileName().toString().toLowerCase();
        return name.endsWith(".modele") ||
               name.equals("jdbc.properties") ||
               name.equals("log4j.properties") ||
               name.equals("log4j2.xml");
    }
}
