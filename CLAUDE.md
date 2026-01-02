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
| `-p, --project` | Chemin projet ANT source |
| `-o, --output` | Répertoire sortie Maven |
| `--profile` | Profil (default/pic) |
| `--artifactory-url` | URL Artifactory (optionnel) |
| `--deploy-mode` | LOCAL ou REMOTE |
| `--auto-fix` | Compile et ajoute automatiquement les dépendances provided manquantes |
| `--lib-provided` | Répertoire des librairies provided (défaut: lib-provided) |

## Mode Auto-Fix

**Classe:** `autofix/AutoFixService.java`

Le mode `--auto-fix` effectue une correction automatique après la migration :

### Fonctionnement
1. **Indexation lib-provided** : Scanne le répertoire `lib-provided/` et indexe toutes les classes Java par JAR
2. **Compilation initiale** : Compile le projet Maven généré
3. **Analyse des erreurs** : Parse les erreurs de compilation pour identifier les packages/classes manquants
4. **Résolution automatique** : Trouve les JARs dans `lib-provided/` qui contiennent les classes manquantes
5. **Ajout des dépendances** : Ajoute les JARs trouvés comme dépendances `provided` dans le POM
6. **Itérations** : Répète jusqu'à compilation réussie ou max 5 itérations

### Classes clés
| Classe | Rôle |
|--------|------|
| `AutoFixService.java` | Orchestration du cycle compile/fix |
| `LibProvidedIndexer.java` | Indexation classes → JAR |
| `CompilationErrorParser.java` | Parse erreurs javac |
| `ProvidedDependencyResolver.java` | Résout packages → JARs |

### Répertoire lib-provided
Contient les JARs serveur (WebLogic, J2EE, etc.) qui seront ajoutés en scope `provided` :
```
lib-provided/
├── weblogic.jar
├── javax.servlet-api.jar
└── ...
```

## Rapports générés

- `migration-report.html` - Rapport détaillé
- `libnotfound.csv` - JARs non résolus
- `install-local-jars.sh` - Script installation locale
- `deploy-to-artifactory.sh` - Script déploiement Artifactory
