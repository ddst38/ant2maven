# ant2maven - Architecture et Algorithme

## Vue d'ensemble
Outil Java 21 de migration de projets ANT/CVS vers Maven. Utilise picocli pour CLI et Freemarker pour génération de templates.

## Architecture en 3 phases

```
Projet ANT → [Scanner] → [Analyzer] → [Generator] → Projet Maven
```

### Phase 1: Scanner
**Classe:** `scanner/ProjectScanner.java`
- Détecte le type de projet:
  - **MAVEN_STYLE**: `*-app/src/main/java` (ex: GMIC_J)
  - **ECLIPSE_STYLE**: `src/` + `WebContent/` (ex: R0_J)
- Scanne les JARs via `JarScanner.java`
- Parse `build.xml` via `AntBuildParser.java`
- Extrait configuration EAR (application.xml, weblogic-application.xml)

### Phase 2: Analyzer
**Classe:** `analyzer/DependencyAnalyzer.java`

Pipeline de résolution (5 stratégies en ordre):
1. `KnownArtifactsRegistry` → known-artifacts.yaml
2. `InternalArtifactPatterns` → DEPFAB.*, jk-socle-*
3. `ArtifactoryClient` → Recherche SHA1
4. `MavenCentralClient` → Recherche SHA1
5. `JarNamePatternMatcher` → Regex sur nom fichier

**Déduplication:** Par SHA1, priorité MAIN > PROVIDED > TEST

### Phase 3: Generator
**Classe:** `generator/ProjectGenerator.java`
- Crée structure Maven standard
- Génère POMs via templates Freemarker
- Copie JARs non résolus vers `liblocale/`
- Génère scripts d'installation

## Fichiers clés

| Fichier | Rôle |
|---------|------|
| `Ant2MavenApplication.java` | Point d'entrée CLI |
| `model/ProjectStructure.java` | Modèle de données projet |
| `model/JarInfo.java` | Métadonnées JAR (SHA1, catégorie) |
| `model/DependencyInfo.java` | Dépendance Maven résolue |
| `config/known-artifacts.yaml` | Mappings JAR → Maven connus |

## Templates Freemarker

- `templates/parent-pom.xml.ftl` - POM parent multi-module
- `templates/war-pom.xml.ftl` - Module WAR avec dépendances
- `templates/ear-pom.xml.ftl` - Module EAR packaging
- `templates/install-local-jars.sh.ftl` - Script installation locale

## Utilisation

```bash
./mvnw package -DskipTests
java -jar target/ant2maven-1.0-SNAPSHOT.jar \
  --project-path ../R0_J \
  --output-dir ./output \
  --build-profile default
```

## Options CLI

| Option | Description |
|--------|-------------|
| `--project-path` | Chemin projet ANT source |
| `--output-dir` | Répertoire sortie Maven |
| `--build-profile` | Profil (default/pic) |
| `--artifactory-url` | URL Artifactory (optionnel) |
| `--deployment-mode` | LOCAL ou REMOTE |

## Rapports générés

- `migration-report.html` - Rapport détaillé
- `libnotfound.csv` - JARs non résolus
- `install-local-jars.sh` - Script installation locale
- `deploy-to-artifactory.sh` - Script déploiement Artifactory
