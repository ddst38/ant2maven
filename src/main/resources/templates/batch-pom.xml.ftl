<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
                             http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <groupId>${groupId}</groupId>
    <artifactId>${artifactId}</artifactId>
    <version>${version}</version>
    <packaging>jar</packaging>

    <name>${projectName}</name>
    <description>Spring Batch Application - Migrated from Ant</description>

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

<#if dependencies?? && dependencies?size gt 0>
    <dependencies>
<#list dependencies as dep>
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
        </dependency>
</#list>
    </dependencies>
</#if>

    <build>
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

<#if mainClass??>
            <!-- JAR executable avec manifest -->
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-jar-plugin</artifactId>
                <version>3.3.0</version>
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

            <!-- Copie des dependances dans lib/ -->
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-dependency-plugin</artifactId>
                <version>3.6.1</version>
                <executions>
                    <execution>
                        <id>copy-dependencies</id>
                        <phase>package</phase>
                        <goals>
                            <goal>copy-dependencies</goal>
                        </goals>
                        <configuration>
                            <outputDirectory>${r"${project.build.directory}"}/lib</outputDirectory>
                            <includeScope>runtime</includeScope>
                        </configuration>
                    </execution>
                </executions>
            </plugin>

            <!-- Execution du batch -->
            <plugin>
                <groupId>org.codehaus.mojo</groupId>
                <artifactId>exec-maven-plugin</artifactId>
                <version>3.1.1</version>
                <configuration>
                    <mainClass>${mainClass}</mainClass>
<#if hasPostgresProfile?? && hasPostgresProfile>
                    <systemProperties>
                        <systemProperty>
                            <key>spring.profiles.active</key>
                            <value>POSTGRESQL</value>
                        </systemProperty>
                    </systemProperties>
</#if>
                </configuration>
            </plugin>
</#if>

            <!-- Assembly pour la distribution -->
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-assembly-plugin</artifactId>
                <version>3.6.0</version>
                <configuration>
                    <descriptors>
                        <descriptor>src/assembly/distribution.xml</descriptor>
                    </descriptors>
                </configuration>
                <executions>
                    <execution>
                        <id>make-assembly</id>
                        <phase>package</phase>
                        <goals>
                            <goal>single</goal>
                        </goals>
                    </execution>
                </executions>
            </plugin>
        </plugins>
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
