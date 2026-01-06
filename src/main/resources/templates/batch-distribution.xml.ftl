<assembly xmlns="http://maven.apache.org/ASSEMBLY/2.2.0"
          xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
          xsi:schemaLocation="http://maven.apache.org/ASSEMBLY/2.2.0
                              http://maven.apache.org/xsd/assembly-2.2.0.xsd">

    <id>dist</id>

    <formats>
        <format>zip</format>
        <format>tar.gz</format>
    </formats>

    <includeBaseDirectory>true</includeBaseDirectory>

    <fileSets>
        <!-- JAR application principal -->
        <fileSet>
            <directory>${r"${project.build.directory}"}</directory>
            <outputDirectory>lib</outputDirectory>
            <includes>
                <include>${r"${project.artifactId}"}-${r"${project.version}"}.jar</include>
            </includes>
        </fileSet>

        <!-- Dependances copiees par maven-dependency-plugin -->
        <fileSet>
            <directory>${r"${project.build.directory}"}/lib</directory>
            <outputDirectory>lib/dependencies</outputDirectory>
            <includes>
                <include>*.jar</include>
            </includes>
        </fileSet>

        <!-- JARs locaux (non resolus) -->
<#if hasLiblocale?? && hasLiblocale>
        <fileSet>
            <directory>${r"${project.basedir}"}/liblocale</directory>
            <outputDirectory>lib/dependencies</outputDirectory>
            <includes>
                <include>*.jar</include>
            </includes>
        </fileSet>
</#if>

        <!-- Scripts de lancement -->
<#if hasScripts?? && hasScripts>
        <fileSet>
            <directory>${r"${project.basedir}"}/src/main/scripts</directory>
            <outputDirectory>script</outputDirectory>
            <fileMode>0755</fileMode>
            <includes>
                <include>*.sh</include>
            </includes>
        </fileSet>
</#if>

        <!-- Configuration applicative -->
<#if hasConf?? && hasConf>
        <fileSet>
            <directory>${r"${project.basedir}"}/src/main/conf</directory>
            <outputDirectory>conf</outputDirectory>
            <includes>
                <include>**/*.properties</include>
                <include>**/*.xml</include>
                <include>**/*.modele</include>
            </includes>
<#if confExclusions?? && (confExclusions?size > 0)>
            <excludes>
<#list confExclusions as excl>
                <exclude>${excl}</exclude>
</#list>
            </excludes>
</#if>
        </fileSet>
</#if>

        <!-- Ressources Spring Batch -->
        <fileSet>
            <directory>${r"${project.basedir}"}/src/main/resources</directory>
            <outputDirectory>conf</outputDirectory>
            <includes>
                <include>**/*.properties</include>
                <include>**/*.xml</include>
            </includes>
            <excludes>
                <exclude>**/job/**</exclude>
            </excludes>
        </fileSet>
    </fileSets>

</assembly>
