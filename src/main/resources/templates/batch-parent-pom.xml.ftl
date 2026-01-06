<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
                             http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <groupId>${groupId}</groupId>
    <artifactId>${artifactId}</artifactId>
    <version>${version}</version>
    <packaging>pom</packaging>

    <name>${projectName}</name>
    <description>Spring Batch Application - Migrated from Ant</description>

    <modules>
<#list modules as module>
        <module>${module}</module>
</#list>
    </modules>

    <properties>
        <project.build.sourceEncoding>ISO-8859-1</project.build.sourceEncoding>
        <maven.compiler.source>8</maven.compiler.source>
        <maven.compiler.target>8</maven.compiler.target>
<#if properties??>
<#list properties as name, value>
        <${name}>${value}</${name}>
</#list>
</#if>
    </properties>

<#if dependencyManagement?? && dependencyManagement?size gt 0>
    <dependencyManagement>
        <dependencies>
<#list dependencyManagement as dep>
<#if dep.comment??>
            <!-- ${dep.comment} -->
</#if>
            <dependency>
                <groupId>${dep.groupId}</groupId>
                <artifactId>${dep.artifactId}</artifactId>
                <version>${dep.version}</version>
<#if dep.scope?? && dep.scope != "compile">
                <scope>${dep.scope}</scope>
</#if>
<#if dep.exclusions??>
                <exclusions>
<#list dep.exclusions as exclusion>
                    <exclusion>
                        <groupId>${exclusion.groupId}</groupId>
                        <artifactId>${exclusion.artifactId}</artifactId>
                    </exclusion>
</#list>
                </exclusions>
</#if>
            </dependency>
</#list>
        </dependencies>
    </dependencyManagement>
</#if>

    <build>
        <pluginManagement>
            <plugins>
                <plugin>
                    <groupId>org.apache.maven.plugins</groupId>
                    <artifactId>maven-compiler-plugin</artifactId>
                    <version>3.11.0</version>
                    <configuration>
                        <source>8</source>
                        <target>8</target>
                        <encoding>${r"${project.build.sourceEncoding}"}</encoding>
                    </configuration>
                </plugin>
                <plugin>
                    <groupId>org.apache.maven.plugins</groupId>
                    <artifactId>maven-jar-plugin</artifactId>
                    <version>3.3.0</version>
                </plugin>
                <plugin>
                    <groupId>org.apache.maven.plugins</groupId>
                    <artifactId>maven-assembly-plugin</artifactId>
                    <version>3.6.0</version>
                </plugin>
                <plugin>
                    <groupId>org.apache.maven.plugins</groupId>
                    <artifactId>maven-dependency-plugin</artifactId>
                    <version>3.6.1</version>
                </plugin>
            </plugins>
        </pluginManagement>
    </build>

    <profiles>
        <profile>
            <id>default</id>
            <activation>
                <activeByDefault>true</activeByDefault>
            </activation>
            <properties>
                <skipTests>true</skipTests>
            </properties>
        </profile>
        <profile>
            <id>pic</id>
            <properties>
                <skipTests>false</skipTests>
            </properties>
        </profile>
    </profiles>

</project>
