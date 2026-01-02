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

    <name>${moduleName}</name>

<#-- Dependances internes (autres modules du projet) - les externes sont heritees du parent -->
<#if internalDependencies?? && internalDependencies?size gt 0>
    <dependencies>
<#list internalDependencies as dep>
        <!-- Module interne: ${dep.name} -->
        <dependency>
            <groupId>${r"${project.groupId}"}</groupId>
            <artifactId>${dep.artifactId}</artifactId>
            <version>${r"${project.version}"}</version>
        </dependency>
</#list>
    </dependencies>
</#if>

<#if hasResources?? && hasResources>
    <build>
        <resources>
            <resource>
                <directory>src/main/resources</directory>
                <includes>
                    <include>**/*.xml</include>
                    <include>**/*.properties</include>
                </includes>
            </resource>
        </resources>
    </build>
</#if>

</project>
