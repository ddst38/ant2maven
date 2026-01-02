<assembly xmlns="http://maven.apache.org/ASSEMBLY/2.2.0"
          xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
          xsi:schemaLocation="http://maven.apache.org/ASSEMBLY/2.2.0
                              http://maven.apache.org/xsd/assembly-2.2.0.xsd">

    <id>dist</id>

    <formats>
        <format>tar.gz</format>
    </formats>

    <includeBaseDirectory>true</includeBaseDirectory>

    <!-- EAR dans web/ -->
    <dependencySets>
        <dependencySet>
            <includes>
                <include>*:${earArtifactId}:ear</include>
            </includes>
            <outputDirectory>web</outputDirectory>
            <useProjectArtifact>false</useProjectArtifact>
        </dependencySet>
    </dependencySets>

    <fileSets>
        <!-- Configuration environnement (.modele) - générée par antrun -->
        <fileSet>
            <directory>${r"${project.build.directory}"}/conf-modele</directory>
            <outputDirectory>conf</outputDirectory>
            <includes>
                <include>**/*</include>
            </includes>
        </fileSet>

        <!-- Configuration applicative (depuis le module web) -->
        <fileSet>
            <directory>${r"${project.basedir}"}/../${webModule}/src/main/resources/conf</directory>
            <outputDirectory>conf</outputDirectory>
            <includes>
                <include>**/*.properties</include>
                <include>**/*.xml</include>
            </includes>
            <excludes>
<#if confExclusions?? && (confExclusions?size > 0)>
<#list confExclusions as excl>
                <exclude>${excl}</exclude>
</#list>
</#if>
                <!-- Exclure les fichiers déjà présents en .modele -->
                <exclude>**/CVS/**</exclude>
            </excludes>
        </fileSet>

        <!-- Scripts d'installation -->
<#if hasInstallScript>
        <fileSet>
            <directory>${r"${project.basedir}"}/../install/script</directory>
            <outputDirectory></outputDirectory>
            <includes>
                <include>*.sh</include>
            </includes>
            <fileMode>0755</fileMode>
        </fileSet>
</#if>
    </fileSets>

</assembly>
