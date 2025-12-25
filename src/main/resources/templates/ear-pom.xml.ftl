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
    <packaging>ear</packaging>

    <name>${artifactId}</name>

    <dependencies>
        <!-- WAR module -->
        <dependency>
            <groupId>${parent.groupId}</groupId>
            <artifactId>${warArtifactId}</artifactId>
            <version>${parent.version}</version>
            <type>war</type>
        </dependency>
<#if appInfLibs?? && appInfLibs?size gt 0>

        <!-- APP-INF/lib dependencies -->
<#list appInfLibs as lib>
        <dependency>
            <groupId>${lib.groupId}</groupId>
            <artifactId>${lib.artifactId}</artifactId>
            <version>${lib.version}</version>
        </dependency>
</#list>
</#if>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-ear-plugin</artifactId>
                <configuration>
                    <version>5</version>
<#if hasAppInfLib>
                    <defaultLibBundleDir>APP-INF/lib</defaultLibBundleDir>
<#else>
                    <defaultLibBundleDir>lib</defaultLibBundleDir>
</#if>

                    <modules>
                        <webModule>
                            <groupId>${parent.groupId}</groupId>
                            <artifactId>${warArtifactId}</artifactId>
                            <contextRoot>${contextRoot}</contextRoot>
                            <bundleFileName>${warFileName}</bundleFileName>
                        </webModule>
                    </modules>

                    <!-- Include WebLogic-specific descriptors -->
                    <earSourceDirectory>src/main/application</earSourceDirectory>
                    <earSourceIncludes>META-INF/**</earSourceIncludes>
<#if hasAppInfConf>

                    <!-- APP-INF/conf configuration files -->
                    <fileMapping>
                        <directory>APP-INF/conf</directory>
                        <source>src/main/conf</source>
                    </fileMapping>
</#if>
                </configuration>
            </plugin>
        </plugins>
    </build>

</project>
