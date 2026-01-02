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
    <packaging>pom</packaging>
    <name>${artifactId}</name>
    <description>Module de distribution - packaging du livrable</description>

    <dependencies>
        <dependency>
            <groupId>${r"${project.groupId}"}</groupId>
            <artifactId>${earArtifactId}</artifactId>
            <version>${r"${project.version}"}</version>
            <type>ear</type>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <!-- Renommage des fichiers de configuration en .modele via antrun -->
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-antrun-plugin</artifactId>
                <version>3.1.0</version>
                <executions>
                    <execution>
                        <id>rename-modele</id>
                        <phase>prepare-package</phase>
                        <goals>
                            <goal>run</goal>
                        </goals>
                        <configuration>
                            <target>
                                <mkdir dir="${r"${project.build.directory}"}/conf-modele"/>
                                <copy todir="${r"${project.build.directory}"}/conf-modele">
                                    <fileset dir="${r"${project.basedir}"}/../install/conf"
                                             includes="**/*.properties,**/*.xml"
                                             excludes="**/CVS/**"/>
                                    <globmapper from="*" to="*.modele"/>
                                </copy>
                            </target>
                        </configuration>
                    </execution>
                </executions>
            </plugin>

            <!-- Assemblage de la distribution -->
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-assembly-plugin</artifactId>
                <version>3.6.0</version>
                <configuration>
                    <descriptors>
                        <descriptor>src/assembly/distribution.xml</descriptor>
                    </descriptors>
                    <!-- Sortie dans install/liv/ comme le build ANT -->
                    <outputDirectory>${r"${project.basedir}"}/../install/liv</outputDirectory>
                    <finalName>${r"${project.parent.artifactId}"}-${r"${project.version}"}</finalName>
                </configuration>
                <executions>
                    <execution>
                        <id>make-distribution</id>
                        <phase>package</phase>
                        <goals>
                            <goal>single</goal>
                        </goals>
                    </execution>
                </executions>
            </plugin>
        </plugins>
    </build>

</project>
