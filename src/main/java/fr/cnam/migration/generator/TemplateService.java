package fr.cnam.migration.generator;

import freemarker.template.Configuration;
import freemarker.template.Template;
import freemarker.template.TemplateExceptionHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Service de rendu de templates FreeMarker.
 */
public class TemplateService {

    private static final Logger log = LoggerFactory.getLogger(TemplateService.class);

    private final Configuration configuration;

    public TemplateService() {
        configuration = new Configuration(Configuration.VERSION_2_3_32);
        configuration.setClassLoaderForTemplateLoading(
            getClass().getClassLoader(), "templates");
        configuration.setDefaultEncoding("UTF-8");
        configuration.setTemplateExceptionHandler(TemplateExceptionHandler.RETHROW_HANDLER);
        configuration.setLogTemplateExceptions(false);
        configuration.setWrapUncheckedExceptions(true);
    }

    /**
     * Effectue le rendu d'un template avec le modèle de données donné.
     */
    public String render(String templateName, Map<String, Object> model) {
        try {
            Template template = configuration.getTemplate(templateName);
            StringWriter writer = new StringWriter();
            template.process(model, writer);
            return writer.toString();
        } catch (Exception e) {
            throw new RuntimeException("Failed to render template " + templateName, e);
        }
    }

    /**
     * Effectue le rendu d'un template et l'écrit dans un fichier.
     */
    public void renderToFile(String templateName, Map<String, Object> model, Path outputFile) {
        try {
            String content = render(templateName, model);
            Files.createDirectories(outputFile.getParent());
            Files.writeString(outputFile, content);
            log.info("Generated: {}", outputFile);
        } catch (IOException e) {
            throw new RuntimeException("Failed to write " + outputFile, e);
        }
    }
}
