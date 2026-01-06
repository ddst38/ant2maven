<assembly xmlns="http://maven.apache.org/ASSEMBLY/2.1.1"
          xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
          xsi:schemaLocation="http://maven.apache.org/ASSEMBLY/2.1.1
                              http://maven.apache.org/xsd/assembly-2.1.1.xsd">

    <id>dist</id>
    <formats>
        <format>tar.gz</format>
        <format>zip</format>
    </formats>

    <includeBaseDirectory>true</includeBaseDirectory>

    <!-- JAR applicatif dans lib/ -->
    <dependencySets>
        <dependencySet>
            <outputDirectory>lib</outputDirectory>
            <useProjectArtifact>false</useProjectArtifact>
            <includes>
                <include>${r"${project.groupId}"}:${appArtifactId}</include>
            </includes>
        </dependencySet>
    </dependencySets>

    <!-- Librairies dans lib/ -->
    <fileSets>
        <fileSet>
            <directory>${r"${project.build.directory}"}/lib</directory>
            <outputDirectory>lib</outputDirectory>
            <includes>
                <include>*.jar</include>
            </includes>
        </fileSet>

<#if hasLiblocale?? && hasLiblocale>
        <!-- JARs locaux (non resolus) -->
        <fileSet>
            <directory>${r"${project.basedir}"}/../liblocale</directory>
            <outputDirectory>lib</outputDirectory>
            <includes>
                <include>*.jar</include>
            </includes>
        </fileSet>
</#if>

        <!-- Scripts de lancement -->
        <fileSet>
            <directory>${r"${project.basedir}"}/src/main/scripts</directory>
            <outputDirectory>script</outputDirectory>
            <fileMode>0755</fileMode>
            <includes>
                <include>*.sh</include>
            </includes>
        </fileSet>

        <!-- Configuration -->
        <fileSet>
            <directory>${r"${project.basedir}"}/src/main/conf</directory>
            <outputDirectory>conf</outputDirectory>
<#if confExclusions?? && confExclusions?size gt 0>
            <excludes>
<#list confExclusions as exclusion>
                <exclude>${exclusion}</exclude>
</#list>
            </excludes>
</#if>
        </fileSet>

        <!-- Scripts d'installation -->
        <fileSet>
            <directory>${r"${project.basedir}"}/src/main/install-scripts</directory>
            <outputDirectory>.</outputDirectory>
            <fileMode>0755</fileMode>
            <includes>
                <include>*.sh</include>
            </includes>
        </fileSet>
    </fileSets>

</assembly>
