<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
                             http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>${parent.groupId}</groupId>
        <artifactId>${parent.artifactId}</artifactId>
        <version>${parent.version}</version>
    </parent>

    <artifactId>${artifactId}</artifactId>
    <packaging>jar</packaging>

    <name>${projectName}</name>

<#if dependencies?? && dependencies?size gt 0>
    <dependencies>
<#list dependencies as dep>
        <dependency>
            <groupId>${dep.groupId}</groupId>
            <artifactId>${dep.artifactId}</artifactId>
<#if dep.scope?? && dep.scope != "compile">
            <scope>${dep.scope}</scope>
</#if>
        </dependency>
</#list>
    </dependencies>
</#if>

    <build>
        <plugins>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-compiler-plugin</artifactId>
            </plugin>

<#if mainClass??>
            <!-- JAR executable avec manifest -->
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-jar-plugin</artifactId>
                <configuration>
                    <archive>
                        <manifest>
                            <mainClass>${mainClass}</mainClass>
                            <addClasspath>true</addClasspath>
                            <classpathPrefix>lib/</classpathPrefix>
                        </manifest>
                        <manifestEntries>
                            <Specification-Title>${specificationTitle!("Spring Batch Application")}</Specification-Title>
                            <Specification-Vendor>CNAMTS</Specification-Vendor>
                            <Implementation-Title>${artifactId}</Implementation-Title>
                            <Implementation-Version>${r"${project.version}"}</Implementation-Version>
                            <Build-Time>${r"${maven.build.timestamp}"}</Build-Time>
                        </manifestEntries>
                    </archive>
                </configuration>
            </plugin>
</#if>
        </plugins>
    </build>

</project>
