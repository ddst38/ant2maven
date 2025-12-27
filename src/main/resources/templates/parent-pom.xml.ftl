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
    <description>Migrated from Ant/CVS project</description>

    <properties>
        <project.build.sourceEncoding>ISO-8859-1</project.build.sourceEncoding>
        <maven.compiler.source>21</maven.compiler.source>
        <maven.compiler.target>21</maven.compiler.target>
<#list properties as name, value>
        <${name}>${value}</${name}>
</#list>
    </properties>

    <modules>
<#list modules as module>
        <module>${module}</module>
</#list>
    </modules>

<#if internalRepository??>
    <repositories>
        <repository>
            <id>internal</id>
            <name>Internal Repository</name>
            <url>${internalRepository}</url>
        </repository>
    </repositories>
</#if>

    <build>
        <pluginManagement>
            <plugins>
                <plugin>
                    <groupId>org.apache.maven.plugins</groupId>
                    <artifactId>maven-compiler-plugin</artifactId>
                    <version>3.11.0</version>
                    <configuration>
                        <release>21</release>
                        <encoding>${r"${project.build.sourceEncoding}"}</encoding>
                    </configuration>
                </plugin>
                <plugin>
                    <groupId>org.apache.maven.plugins</groupId>
                    <artifactId>maven-war-plugin</artifactId>
                    <version>3.4.0</version>
                </plugin>
                <plugin>
                    <groupId>org.apache.maven.plugins</groupId>
                    <artifactId>maven-ear-plugin</artifactId>
                    <version>3.3.0</version>
                </plugin>
                <plugin>
                    <groupId>org.apache.maven.plugins</groupId>
                    <artifactId>maven-surefire-plugin</artifactId>
                    <version>3.2.2</version>
                </plugin>
            </plugins>
        </pluginManagement>
    </build>

<#if hasPicProfile>
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
</#if>

</project>
