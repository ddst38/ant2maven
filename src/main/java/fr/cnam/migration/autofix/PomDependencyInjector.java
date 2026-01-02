package fr.cnam.migration.autofix;

import fr.cnam.migration.autofix.model.ProvidedDependency;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.*;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Injecte des dependances provided dans un fichier pom.xml.
 */
public class PomDependencyInjector {

    private static final Logger log = LoggerFactory.getLogger(PomDependencyInjector.class);

    private static final String MAVEN_NAMESPACE = "http://maven.apache.org/POM/4.0.0";

    /**
     * Ajoute une liste de dependances provided au pom.xml.
     */
    public int addProvidedDependencies(Path pomFile, List<ProvidedDependency> dependencies) throws IOException {
        if (!Files.exists(pomFile)) {
            log.error("Fichier pom.xml introuvable : {}", pomFile);
            return 0;
        }

        try {
            // Parser le pom.xml
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(pomFile.toFile());

            // Trouver ou creer la section <dependencies>
            Element root = doc.getDocumentElement();
            Element dependenciesElement = findOrCreateDependencies(doc, root);

            // Collecter les dependances existantes pour eviter les doublons
            Set<String> existingDeps = getExistingDependencies(dependenciesElement);

            int addedCount = 0;
            for (ProvidedDependency dep : dependencies) {
                String key = dep.groupId() + ":" + dep.artifactId();
                if (existingDeps.contains(key)) {
                    log.debug("Dependance deja presente : {}", key);
                    continue;
                }

                // Creer l'element <dependency>
                // Inserer au DEBUT pour priorité de classpath (eviter conflits avec axis-saaj, etc.)
                Element depElement = createDependencyElement(doc, dep);
                Node firstChild = dependenciesElement.getFirstChild();
                if (firstChild != null) {
                    dependenciesElement.insertBefore(depElement, firstChild);
                } else {
                    dependenciesElement.appendChild(depElement);
                }
                existingDeps.add(key);
                addedCount++;

                log.info("Dependance provided ajoutee : {}:{}:{}",
                    dep.groupId(), dep.artifactId(), dep.version());
            }

            if (addedCount > 0) {
                // Sauvegarder le fichier
                saveDocument(doc, pomFile);
                log.info("{} dependance(s) ajoutee(s) a {}", addedCount, pomFile);
            }

            return addedCount;

        } catch (ParserConfigurationException | SAXException e) {
            throw new IOException("Erreur lors du parsing du pom.xml : " + e.getMessage(), e);
        } catch (Exception e) {
            throw new IOException("Erreur lors de la modification du pom.xml : " + e.getMessage(), e);
        }
    }

    /**
     * Trouve ou cree la section <dependencies>.
     */
    private Element findOrCreateDependencies(Document doc, Element root) {
        NodeList depsList = root.getElementsByTagNameNS(MAVEN_NAMESPACE, "dependencies");
        if (depsList.getLength() == 0) {
            // Essayer sans namespace
            depsList = root.getElementsByTagName("dependencies");
        }

        if (depsList.getLength() > 0) {
            // Trouver la section dependencies directe (pas dependencyManagement)
            for (int i = 0; i < depsList.getLength(); i++) {
                Element deps = (Element) depsList.item(i);
                if (deps.getParentNode() == root) {
                    return deps;
                }
            }
        }

        // Creer la section <dependencies>
        Element dependencies = doc.createElement("dependencies");

        // Inserer avant <build> si present, sinon a la fin
        NodeList buildList = root.getElementsByTagName("build");
        if (buildList.getLength() > 0) {
            root.insertBefore(dependencies, buildList.item(0));
        } else {
            root.appendChild(dependencies);
        }

        return dependencies;
    }

    /**
     * Collecte les dependances existantes (groupId:artifactId).
     */
    private Set<String> getExistingDependencies(Element dependenciesElement) {
        Set<String> existing = new HashSet<>();
        NodeList deps = dependenciesElement.getElementsByTagName("dependency");

        for (int i = 0; i < deps.getLength(); i++) {
            Element dep = (Element) deps.item(i);
            String groupId = getElementText(dep, "groupId");
            String artifactId = getElementText(dep, "artifactId");
            if (groupId != null && artifactId != null) {
                existing.add(groupId + ":" + artifactId);
            }
        }

        return existing;
    }

    /**
     * Cree un element <dependency> avec scope provided.
     */
    private Element createDependencyElement(Document doc, ProvidedDependency dep) {
        Element dependency = doc.createElement("dependency");

        Element groupId = doc.createElement("groupId");
        groupId.setTextContent(dep.groupId());
        dependency.appendChild(groupId);

        Element artifactId = doc.createElement("artifactId");
        artifactId.setTextContent(dep.artifactId());
        dependency.appendChild(artifactId);

        Element version = doc.createElement("version");
        version.setTextContent(dep.version());
        dependency.appendChild(version);

        Element scope = doc.createElement("scope");
        scope.setTextContent("provided");
        dependency.appendChild(scope);

        return dependency;
    }

    /**
     * Recupere le texte d'un element enfant.
     */
    private String getElementText(Element parent, String tagName) {
        NodeList list = parent.getElementsByTagName(tagName);
        if (list.getLength() > 0) {
            return list.item(0).getTextContent().trim();
        }
        return null;
    }

    /**
     * Sauvegarde le document XML.
     */
    private void saveDocument(Document doc, Path file) throws Exception {
        TransformerFactory transformerFactory = TransformerFactory.newInstance();
        Transformer transformer = transformerFactory.newTransformer();
        transformer.setOutputProperty(OutputKeys.INDENT, "yes");
        transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "4");
        transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no");
        transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");

        // Nettoyer les espaces blancs excessifs
        doc.normalize();

        DOMSource source = new DOMSource(doc);
        StreamResult result = new StreamResult(file.toFile());
        transformer.transform(source, result);
    }

    /**
     * Verifie si une dependance existe deja dans le pom.xml.
     */
    public boolean dependencyExists(Path pomFile, String groupId, String artifactId) throws IOException {
        if (!Files.exists(pomFile)) {
            return false;
        }

        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(pomFile.toFile());

            NodeList deps = doc.getElementsByTagName("dependency");
            for (int i = 0; i < deps.getLength(); i++) {
                Element dep = (Element) deps.item(i);
                String gid = getElementText(dep, "groupId");
                String aid = getElementText(dep, "artifactId");
                if (groupId.equals(gid) && artifactId.equals(aid)) {
                    return true;
                }
            }
            return false;

        } catch (ParserConfigurationException | SAXException e) {
            throw new IOException("Erreur lors de la lecture du pom.xml", e);
        }
    }
}
