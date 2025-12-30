package fr.cnam.migration.config;

import fr.cnam.migration.analyzer.JarPackageAnalyzer;
import fr.cnam.migration.analyzer.JarVersionExtractor;
import fr.cnam.migration.model.JarInfo;
import fr.cnam.migration.model.MavenCoordinate;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Patterns pour reconnaître et convertir les JARs internes/propriétaires.
 * Utilise des patterns génériques qui fonctionnent pour différents projets CNAM.
 * Le package de base (fr.cnamts ou fr.cnam) est configurable.
 */
public class InternalArtifactPatterns {

    private final String basePackage;
    private final List<InternalPattern> patterns = new ArrayList<>();
    private final JarPackageAnalyzer packageAnalyzer;
    private final JarVersionExtractor versionExtractor;

    public InternalArtifactPatterns() {
        this(MigrationConfig.DEFAULT_BASE_PACKAGE, new JarPackageAnalyzer(), new JarVersionExtractor());
    }

    public InternalArtifactPatterns(String basePackage) {
        this(basePackage, new JarPackageAnalyzer(), new JarVersionExtractor());
    }

    public InternalArtifactPatterns(String basePackage, JarPackageAnalyzer packageAnalyzer) {
        this(basePackage, packageAnalyzer, new JarVersionExtractor());
    }

    public InternalArtifactPatterns(String basePackage, JarPackageAnalyzer packageAnalyzer, JarVersionExtractor versionExtractor) {
        this.basePackage = basePackage != null ? basePackage : MigrationConfig.DEFAULT_BASE_PACKAGE;
        this.packageAnalyzer = packageAnalyzer;
        this.versionExtractor = versionExtractor;
        initializePatterns();
    }

    private void initializePatterns() {
        // IMPORTANT: Les patterns spécifiques avec coordonnées Maven Central doivent être
        // définis EN PREMIER pour avoir priorité sur les patterns génériques.

        // struts.jar historique -> coordonnées Maven Central avec version extraite du manifest
        patterns.add(new InternalPattern(
            Pattern.compile("^struts\\.jar$"),
            (m, jar) -> {
                // Extraire la version du manifest ou du nom de fichier
                JarVersionExtractor.VersionInfo versionInfo = versionExtractor.extractVersion(jar);
                String version = versionInfo != null && versionInfo.version() != null
                    ? versionInfo.version()
                    : (jar.sha1() != null ? "SHA-" + jar.sha1() : "UNKNOWN");
                return new MavenCoordinate("org.apache.struts", "struts-core", version);
            }
        ));

        // Oracle JDBC classes12.jar -> coordonnées Maven Central
        patterns.add(new InternalPattern(
            Pattern.compile("^classes12\\.jar$"),
            (m, jar) -> new MavenCoordinate(
                "com.oracle.database.jdbc",
                "ojdbc8",
                "12.2.0.1"
            )
        ));

        // DEPFAB.XXX_Y.module.jar -> {basePackage}.internal.xxx.y:module:SHA-{sha1}
        // Exemples : DEPFAB.S8_J.secJava.jar, DEPFAB.BIMC_H.core.jar
        // Utilise le SHA1 du JAR comme version pour garantir l'unicité
        patterns.add(new InternalPattern(
            Pattern.compile("^DEPFAB\\.([A-Z0-9]+_[A-Z])\\.(.+)\\.jar$"),
            (m, jar) -> new MavenCoordinate(
                basePackage + ".internal." + m.group(1).toLowerCase().replace("_", "."),
                m.group(2),
                jar.sha1() != null ? "SHA-" + jar.sha1() : "UNKNOWN"
            )
        ));

        // DEPFAB.XXX_Y-version-suffix.jar -> {basePackage}.internal:xxx_y:version
        // Exemple : DEPFAB.BIMC_H-1.0.16-st3.0-rhel7-wls12cr2-pub.jar
        patterns.add(new InternalPattern(
            Pattern.compile("^DEPFAB\\.([A-Z0-9]+_[A-Z])-(\\d+\\.\\d+\\.\\d+)-.+\\.jar$"),
            (m, jar) -> new MavenCoordinate(
                basePackage + ".internal",
                m.group(1).toLowerCase(),
                m.group(2)
            )
        ));

        // DEPFAB.XXX_ServiceName_version_type.jar -> {basePackage}.internal:xxx-servicename:version-type
        // Exemple : DEPFAB.W1_ServiceImageDecompte_v1.0_client.jar
        patterns.add(new InternalPattern(
            Pattern.compile("^DEPFAB\\.([A-Z0-9]+)_([A-Za-z]+)_v?(\\d+\\.\\d+)_([a-z]+)\\.jar$"),
            (m, jar) -> new MavenCoordinate(
                basePackage + ".internal",
                m.group(1).toLowerCase() + "-" + m.group(2).toLowerCase(),
                m.group(3) + "-" + m.group(4)
            )
        ));

        // DEPFAB.XXX_WS.XXX_ServiceName.type.jar -> {basePackage}.internal:xxx-servicename:type
        // Exemple : DEPFAB.GMIC_WS.GMIC_ServiceGMIC.serveur.jar
        patterns.add(new InternalPattern(
            Pattern.compile("^DEPFAB\\.([A-Z0-9]+)_WS\\.\\1_([A-Za-z]+)\\.([a-z]+)\\.jar$"),
            (m, jar) -> new MavenCoordinate(
                basePackage + ".internal",
                m.group(1).toLowerCase() + "-" + m.group(2).toLowerCase(),
                m.group(3)
            )
        ));

        // Pattern DEPFAB générique : DEPFAB.anything.jar -> SHA-{sha1}
        // Utilise le SHA1 du JAR comme version pour garantir l'unicité
        patterns.add(new InternalPattern(
            Pattern.compile("^DEPFAB\\.(.+)\\.jar$"),
            (m, jar) -> new MavenCoordinate(
                basePackage + ".internal",
                m.group(1).replaceAll("[^a-zA-Z0-9-]", "-").toLowerCase(),
                jar.sha1() != null ? "SHA-" + jar.sha1() : "UNKNOWN"
            )
        ));

        // jk-socle-XXX-version.jar -> {basePackage}.jk.socle:jk-socle-xxx:version
        patterns.add(new InternalPattern(
            Pattern.compile("^(jk-socle-[a-z-]+)-(\\d+\\.\\d+\\.\\d+)\\.jar$"),
            (m, jar) -> new MavenCoordinate(
                basePackage + ".jk.socle",
                m.group(1),
                m.group(2)
            )
        ));

        // XXX_Y.library-version.jar -> {basePackage}.xxx:library:version
        // Exemple : S8_J.commons-collections4-4.1.jar
        patterns.add(new InternalPattern(
            Pattern.compile("^([A-Z0-9]+_[A-Z])\\.(.+)-(\\d+\\.\\d+(?:\\.\\d+)?)\\.jar$"),
            (m, jar) -> new MavenCoordinate(
                basePackage + "." + m.group(1).toLowerCase().replace("_", "."),
                m.group(2),
                m.group(3)
            )
        ));

        // s8XXX-version.jar or s8h-XXX-version.jar -> {basePackage}.s8:s8xxx:version
        patterns.add(new InternalPattern(
            Pattern.compile("^(s8[a-z]?-?[a-zA-Z]*)-(\\d+\\.\\d+\\.\\d+)\\.jar$"),
            (m, jar) -> new MavenCoordinate(
                basePackage + ".s8",
                m.group(1),
                m.group(2)
            )
        ));

        // ServiceXXX_version.type.jar -> {basePackage}.services:servicexxx:version-type
        // Exemple : ServicePS_3.0.client.jar
        patterns.add(new InternalPattern(
            Pattern.compile("^(Service[A-Z]+)_(\\d+\\.\\d+)\\.([a-z]+)\\.jar$"),
            (m, jar) -> new MavenCoordinate(
                basePackage + ".services",
                m.group(1).toLowerCase(),
                m.group(2) + "-" + m.group(3)
            )
        ));

        // Codes projet SOCA ou similaires : XXXNNNNNNNY-version-suffix.jar
        // Exemple : SOCA010000J-1.0.0-multipub-pub.jar
        patterns.add(new InternalPattern(
            Pattern.compile("^([A-Z]+\\d+[A-Z])-(\\d+\\.\\d+\\.\\d+)-.+\\.jar$"),
            (m, jar) -> new MavenCoordinate(
                basePackage + ".internal",
                m.group(1).toLowerCase(),
                m.group(2)
            )
        ));

        // tracesCaster.jar et JARs autonomes similaires -> SHA-{sha1}
        // Utilise le SHA1 du JAR comme version pour garantir l'unicité
        patterns.add(new InternalPattern(
            Pattern.compile("^(tracesCaster)\\.jar$"),
            (m, jar) -> new MavenCoordinate(
                basePackage + ".internal",
                m.group(1),
                jar.sha1() != null ? "SHA-" + jar.sha1() : "UNKNOWN"
            )
        ));

        // JARs génériques sans version - utilise l'analyse des packages pour déterminer le groupId
        // Accepte les noms en minuscules ou camelCase (ex: biblicnam.jar, jAuthApp.jar)
        // Utilise le SHA1 du JAR comme version pour garantir l'unicité
        patterns.add(new InternalPattern(
            Pattern.compile("^([a-zA-Z][a-zA-Z0-9]*)\\.jar$"),
            (m, jar) -> {
                String name = m.group(1);
                // Ne matcher que si ça ressemble à un artefact (nom court, pas de version)
                if (name.length() <= 20 && !name.contains("-")) {
                    String version = jar.sha1() != null ? "SHA-" + jar.sha1() : "UNKNOWN";
                    // Analyser le contenu du JAR pour trouver le package réel
                    if (jar.path() != null) {
                        JarPackageAnalyzer.PackageAnalysis analysis = packageAnalyzer.analyze(jar.path());
                        if (analysis.inferredGroupId() != null) {
                            return new MavenCoordinate(
                                analysis.inferredGroupId(),
                                name,
                                version
                            );
                        }
                    }
                    // Fallback si l'analyse échoue
                    return new MavenCoordinate(
                        basePackage + ".internal",
                        name,
                        version
                    );
                }
                return null;
            }
        ));

        // Note: struts.jar et classes12.jar sont définis au début pour avoir priorité
    }

    /**
     * Retourne le package de base configuré.
     */
    public String getBasePackage() {
        return basePackage;
    }

    /**
     * Vérifie si un nom de JAR correspond à un pattern interne SPÉCIFIQUE.
     * Ne retourne true que pour les artefacts internes bien identifiés.
     * Les JARs génériques (log4j.jar, antlr.jar, etc.) retournent false
     * pour permettre la recherche sur Maven Central.
     */
    public boolean isInternal(String jarName) {
        // Nettoyer les préfixes EAR si présents
        String cleanName = jarName;
        if (cleanName.startsWith("APP-INF_lib_")) {
            cleanName = cleanName.substring("APP-INF_lib_".length());
        } else if (cleanName.startsWith("WEB-INF_lib_")) {
            cleanName = cleanName.substring("WEB-INF_lib_".length());
        } else if (cleanName.startsWith("lib_")) {
            cleanName = cleanName.substring("lib_".length());
        }

        // Préfixes internes courants (patterns spécifiques CNAM)
        if (cleanName.startsWith("DEPFAB.") ||
            cleanName.startsWith("jk-socle-") ||
            cleanName.matches("^[A-Z]+_[A-Z]\\..*") ||  // XXX_Y.something
            cleanName.matches("^s8[a-z]?-.*") ||         // s8xxx- or s8h-xxx
            cleanName.matches("^Service[A-Z]+_.*") ||    // ServiceXXX_
            cleanName.matches("^[A-Z]+\\d{6,}.*\\.jar$")) {  // Codes projet comme SOCA010000J
            return true;
        }

        // Cas spécifiques bien connus qui ont un mapping Maven
        if (cleanName.equals("struts.jar") || cleanName.equals("classes12.jar")) {
            return true;
        }

        // NE PAS matcher le pattern générique ici - cela empêcherait
        // la recherche Maven Central pour les JARs standards
        return false;
    }

    /**
     * Tente de résoudre un JAR en coordonnées Maven en utilisant les patterns internes.
     * Utilise le nom original du JAR (sans préfixes EAR) pour le matching.
     */
    public Optional<MavenCoordinate> resolve(JarInfo jar) {
        // Utiliser le nom original sans les préfixes de chemin EAR
        String jarName = jar.originalName();
        for (InternalPattern pattern : patterns) {
            Matcher matcher = pattern.pattern.matcher(jarName);
            if (matcher.matches()) {
                try {
                    MavenCoordinate coord = pattern.resolver.resolve(matcher, jar);
                    if (coord != null) {
                        return Optional.of(coord);
                    }
                } catch (Exception e) {
                    // Continuer vers le pattern suivant
                }
            }
        }
        return Optional.empty();
    }

    private record InternalPattern(
        Pattern pattern,
        CoordinateResolver resolver
    ) {}

    @FunctionalInterface
    private interface CoordinateResolver {
        MavenCoordinate resolve(Matcher matcher, JarInfo jar);
    }
}
