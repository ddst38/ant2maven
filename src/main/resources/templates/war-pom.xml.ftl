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
    <packaging>war</packaging>

    <name>${artifactId}</name>

    <dependencies>
        <!-- Servlet API - fourni par le serveur d'applications -->
        <dependency>
            <groupId>javax.servlet</groupId>
            <artifactId>javax.servlet-api</artifactId>
            <version>3.1.0</version>
            <scope>provided</scope>
        </dependency>
<#list dependencies as dep>
        <dependency>
            <groupId>${dep.groupId}</groupId>
            <artifactId>${dep.artifactId}</artifactId>
<#if dep.version??>
            <version>${dep.version}</version>
</#if>
<#if dep.scope?? && dep.scope != "compile">
            <scope>${dep.scope}</scope>
</#if>
<#if dep.classifier??>
            <classifier>${dep.classifier}</classifier>
</#if>
        </dependency>
</#list>
    </dependencies>

    <build>
        <finalName>${warName}</finalName>
        <plugins>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-war-plugin</artifactId>
                <configuration>
                    <warSourceDirectory>src/main/webapp</warSourceDirectory>
                    <failOnMissingWebXml>false</failOnMissingWebXml>
<#if excludedResources?? && excludedResources?size gt 0>
                    <packagingExcludes>
<#list excludedResources as excluded>
                        ${excluded}<#if excluded_has_next>,</#if>
</#list>
                    </packagingExcludes>
</#if>
                </configuration>
            </plugin>
        </plugins>
    </build>

</project>
