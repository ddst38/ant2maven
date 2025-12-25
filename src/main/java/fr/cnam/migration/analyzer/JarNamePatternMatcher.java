package fr.cnam.migration.analyzer;

import fr.cnam.migration.model.MavenCoordinate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fait correspondre les noms de fichiers JAR aux coordonnées Maven en utilisant des patterns regex.
 */
public class JarNamePatternMatcher {

    private static final Logger log = LoggerFactory.getLogger(JarNamePatternMatcher.class);

    private static final List<PatternRule> RULES = List.of(
        // Spring Framework: spring-core-3.2.18.RELEASE.jar
        new PatternRule(
            Pattern.compile("^(spring-[a-z-]+)-(\\d+\\.\\d+\\.\\d+(?:\\.RELEASE)?)\\.jar$"),
            (m) -> new MavenCoordinate("org.springframework", m.group(1), m.group(2))
        ),

        // AspectJ: aspectjrt-1.8.9.jar
        new PatternRule(
            Pattern.compile("^(aspectj(?:rt|weaver))-(\\d+\\.\\d+\\.\\d+)\\.jar$"),
            (m) -> new MavenCoordinate("org.aspectj", m.group(1), m.group(2))
        ),

        // Bouncy Castle: bcprov-jdk15on-166.jar -> 1.66
        new PatternRule(
            Pattern.compile("^(bc(?:prov|pkix|mail|pg|test)(?:-ext)?-jdk15on)-(\\d+)\\.jar$"),
            (m) -> new MavenCoordinate(
                "org.bouncycastle",
                m.group(1),
                "1." + m.group(2)
            )
        ),

        // Log4j 2.x: log4j-core-2.17.1.jar
        new PatternRule(
            Pattern.compile("^(log4j-(?:api|core|slf4j-impl|1\\.2-api))-(\\d+\\.\\d+\\.\\d+)\\.jar$"),
            (m) -> new MavenCoordinate("org.apache.logging.log4j", m.group(1), m.group(2))
        ),

        // SLF4J: slf4j-api-1.7.30.jar
        new PatternRule(
            Pattern.compile("^(slf4j-(?:api|simple|log4j12|nop))-(\\d+\\.\\d+\\.\\d+)\\.jar$"),
            (m) -> new MavenCoordinate("org.slf4j", m.group(1), m.group(2))
        ),

        // Commons Lang3: commons-lang3-3.8.1.jar
        new PatternRule(
            Pattern.compile("^(commons-lang3)-(\\d+\\.\\d+(?:\\.\\d+)?)\\.jar$"),
            (m) -> new MavenCoordinate("org.apache.commons", m.group(1), m.group(2))
        ),

        // Commons Lang (old): commons-lang-2.6.jar
        new PatternRule(
            Pattern.compile("^(commons-lang)-(\\d+\\.\\d+)\\.jar$"),
            (m) -> new MavenCoordinate("commons-lang", m.group(1), m.group(2))
        ),

        // Commons Collections4: commons-collections4-4.1.jar
        new PatternRule(
            Pattern.compile("^(commons-collections4)-(\\d+\\.\\d+)\\.jar$"),
            (m) -> new MavenCoordinate("org.apache.commons", m.group(1), m.group(2))
        ),

        // Commons Collections (old): commons-collections-3.2.1.jar
        new PatternRule(
            Pattern.compile("^(commons-collections)-(\\d+\\.\\d+(?:\\.\\d+)?)\\.jar$"),
            (m) -> new MavenCoordinate("commons-collections", m.group(1), m.group(2))
        ),

        // Commons Configuration2: commons-configuration2-2.4.jar
        new PatternRule(
            Pattern.compile("^(commons-configuration2)-(\\d+\\.\\d+)\\.jar$"),
            (m) -> new MavenCoordinate("org.apache.commons", m.group(1), m.group(2))
        ),

        // Commons Text: commons-text-1.6.jar
        new PatternRule(
            Pattern.compile("^(commons-text)-(\\d+\\.\\d+)\\.jar$"),
            (m) -> new MavenCoordinate("org.apache.commons", m.group(1), m.group(2))
        ),

        // Old Commons (codec, logging, beanutils, io, etc.): commons-codec-1.9.jar
        new PatternRule(
            Pattern.compile("^(commons-(?:codec|logging|beanutils|io|pool|dbcp|configuration))-(\\d+\\.\\d+(?:\\.\\d+)?)\\.jar$"),
            (m) -> new MavenCoordinate(m.group(1), m.group(1), m.group(2))
        ),

        // Jackson 2.x: jackson-core-2.9.4.jar
        new PatternRule(
            Pattern.compile("^(jackson-(?:core|databind|annotations))-(\\d+\\.\\d+\\.\\d+)\\.jar$"),
            (m) -> new MavenCoordinate("com.fasterxml.jackson.core", m.group(1), m.group(2))
        ),

        // Jackson 1.x (codehaus): jackson-core-asl-1.9.13.jar
        new PatternRule(
            Pattern.compile("^(jackson-(?:core|mapper)-asl)-(\\d+\\.\\d+\\.\\d+)\\.jar$"),
            (m) -> new MavenCoordinate("org.codehaus.jackson", m.group(1), m.group(2))
        ),

        // PostgreSQL: postgresql-42.2.5.jar
        new PatternRule(
            Pattern.compile("^(postgresql)-(\\d+\\.\\d+\\.\\d+)\\.jar$"),
            (m) -> new MavenCoordinate("org.postgresql", m.group(1), m.group(2))
        ),

        // Joda Time: joda-time-2.3.jar
        new PatternRule(
            Pattern.compile("^(joda-time)-(\\d+\\.\\d+)\\.jar$"),
            (m) -> new MavenCoordinate("joda-time", m.group(1), m.group(2))
        ),

        // Metrics: metrics-core-3.0.2.jar
        new PatternRule(
            Pattern.compile("^(metrics-(?:core|jvm|servlet))-(\\d+\\.\\d+\\.\\d+)\\.jar$"),
            (m) -> new MavenCoordinate("io.dropwizard.metrics", m.group(1), m.group(2))
        ),

        // AOP Alliance: aopalliance-1.0.jar
        new PatternRule(
            Pattern.compile("^(aopalliance)-(\\d+\\.\\d+)\\.jar$"),
            (m) -> new MavenCoordinate("aopalliance", m.group(1), m.group(2))
        ),

        // JAXB API: jaxb-api-2.3.0.jar
        new PatternRule(
            Pattern.compile("^(jaxb-api)-(\\d+\\.\\d+\\.\\d+)\\.jar$"),
            (m) -> new MavenCoordinate("javax.xml.bind", m.group(1), m.group(2))
        ),

        // JAX-WS API: jaxws-api-2.3.0.jar
        new PatternRule(
            Pattern.compile("^(jaxws-api)-(\\d+\\.\\d+\\.\\d+)\\.jar$"),
            (m) -> new MavenCoordinate("javax.xml.ws", m.group(1), m.group(2))
        ),

        // Tomcat: tomcat-jdbc-7.0.54.jar
        new PatternRule(
            Pattern.compile("^(tomcat-(?:jdbc|juli|util))-(\\d+\\.\\d+\\.\\d+)\\.jar$"),
            (m) -> new MavenCoordinate("org.apache.tomcat", m.group(1), m.group(2))
        ),

        // JUnit 4: junit-4.11.jar
        new PatternRule(
            Pattern.compile("^(junit)-(\\d+\\.\\d+(?:\\.\\d+)?)\\.jar$"),
            (m) -> new MavenCoordinate("junit", m.group(1), m.group(2))
        ),

        // Mockito: mockito-all-1.9.0.jar or mockito-core-2.x.jar
        new PatternRule(
            Pattern.compile("^(mockito-(?:all|core))-(\\d+\\.\\d+\\.\\d+)\\.jar$"),
            (m) -> new MavenCoordinate("org.mockito", m.group(1), m.group(2))
        ),

        // Hamcrest: hamcrest-core-1.3.jar
        new PatternRule(
            Pattern.compile("^(hamcrest-(?:core|library|all))-(\\d+\\.\\d+)\\.jar$"),
            (m) -> new MavenCoordinate("org.hamcrest", m.group(1), m.group(2))
        ),

        // H2 Database: h2-1.3.173.jar
        new PatternRule(
            Pattern.compile("^(h2)-(\\d+\\.\\d+\\.\\d+)\\.jar$"),
            (m) -> new MavenCoordinate("com.h2database", m.group(1), m.group(2))
        ),

        // DbUnit: dbunit-2.5.0.jar
        new PatternRule(
            Pattern.compile("^(dbunit)-(\\d+\\.\\d+\\.\\d+)\\.jar$"),
            (m) -> new MavenCoordinate("org.dbunit", m.group(1), m.group(2))
        ),

        // AssertJ: assertj-core-1.5.0.jar
        new PatternRule(
            Pattern.compile("^(assertj-core)-(\\d+\\.\\d+\\.\\d+)\\.jar$"),
            (m) -> new MavenCoordinate("org.assertj", m.group(1), m.group(2))
        ),

        // Spring Test DbUnit: spring-test-dbunit-1.2.1.jar
        new PatternRule(
            Pattern.compile("^(spring-test-dbunit)-(\\d+\\.\\d+\\.\\d+)\\.jar$"),
            (m) -> new MavenCoordinate("com.github.springtestdbunit", m.group(1), m.group(2))
        ),

        // Dom4j: dom4j-2.1.4.jar
        new PatternRule(
            Pattern.compile("^(dom4j)-(\\d+\\.\\d+\\.\\d+)\\.jar$"),
            (m) -> new MavenCoordinate("org.dom4j", m.group(1), m.group(2))
        ),

        // Jetty: jetty-6.1.26.jar
        new PatternRule(
            Pattern.compile("^(jetty(?:-util)?)-(\\d+\\.\\d+\\.\\d+)\\.jar$"),
            (m) -> new MavenCoordinate("org.mortbay.jetty", m.group(1), m.group(2))
        ),

        // FTP Server: ftpserver-core-1.0.6.jar
        new PatternRule(
            Pattern.compile("^(ftpserver-core|ftplet-api)-(\\d+\\.\\d+\\.\\d+)\\.jar$"),
            (m) -> new MavenCoordinate("org.apache.ftpserver", m.group(1), m.group(2))
        ),

        // Mina: mina-core-2.0.4.jar
        new PatternRule(
            Pattern.compile("^(mina-core)-(\\d+\\.\\d+\\.\\d+)\\.jar$"),
            (m) -> new MavenCoordinate("org.apache.mina", m.group(1), m.group(2))
        )
    );

    /**
     * Tente de faire correspondre un nom de fichier JAR à des coordonnées Maven.
     */
    public Optional<MavenCoordinate> match(String jarName) {
        for (PatternRule rule : RULES) {
            Matcher matcher = rule.pattern.matcher(jarName);
            if (matcher.matches()) {
                try {
                    MavenCoordinate coord = rule.resolver.resolve(matcher);
                    log.debug("Pattern matched {} -> {}", jarName, coord.toGav());
                    return Optional.of(coord);
                } catch (Exception e) {
                    log.debug("Pattern matched but resolution failed for {}: {}", jarName, e.getMessage());
                }
            }
        }

        log.debug("No pattern matched for: {}", jarName);
        return Optional.empty();
    }

    private record PatternRule(
        Pattern pattern,
        CoordinateResolver resolver
    ) {}

    @FunctionalInterface
    private interface CoordinateResolver {
        MavenCoordinate resolve(Matcher matcher);
    }
}
