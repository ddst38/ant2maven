# Ant2Maven - Outil de Migration Ant vers Maven

Outil de migration automatique pour transformer des projets Java Ant/CVS en projets Maven multi-modules.

## Table des matières

- [Fonctionnalités](#fonctionnalités)
- [Prérequis](#prérequis)
- [Installation](#installation)
- [Utilisation](#utilisation)
  - [Commandes de base](#commandes-de-base)
  - [Options complètes](#options-complètes)
  - [Exemples](#exemples)
- [Configuration](#configuration)
  - [Fichier known-artifacts.yaml](#fichier-known-artifactsyaml)
  - [Configuration Artifactory](#configuration-artifactory)
  - [Modes de déploiement](#modes-de-déploiement)
- [Processus de migration](#processus-de-migration)
- [Fichiers générés](#fichiers-générés)
- [Architecture du projet](#architecture-du-projet)
- [Résolution des dépendances](#résolution-des-dépendances)
- [Gestion des versions](#gestion-des-versions)
- [Dépannage](#dépannage)

---

## Fonctionnalités

- **Analyse automatique** des projets Ant/CVS
- **Détection des dépendances** via lookup SHA1 sur Maven Central et/ou Artifactory
- **Support multi-modules** : génération de projets WAR/EAR
- **Gestion des JARs internes** avec versionnement SHA pour éviter les collisions
- **Intégration Artifactory** avec support SSL personnalisé
- **Modes de déploiement** : LOCAL (repository .m2) ou REMOTE (upload Artifactory)
- **Rapports HTML** détaillés sur la migration
- **Scripts d'installation** pour les JARs internes
- **Configuration flexible** via fichier YAML ou ligne de commande

---

## Prérequis

- **Java 21** ou supérieur
- **Maven 3.8+** (ou utilisation du wrapper Maven inclus)
- Accès réseau à Maven Central (ou Artifactory configuré)

---

## Installation

### Compilation depuis les sources

```bash
git clone https://github.com/ddst38/ant2maven.git
cd ant2maven
mvn clean package -DskipTests
```

Le JAR exécutable sera généré dans `target/ant2maven-1.0.0-SNAPSHOT.jar`.

### Utilisation directe avec Maven

```bash
mvn exec:java -Dexec.mainClass="fr.cnam.migration.Ant2MavenApplication" \
  -Dexec.args="<options>"
```

---

## Utilisation

### Commandes de base

#### Mode analyse (dry-run)

Analyse le projet sans générer de fichiers :

```bash
java -jar ant2maven-1.0.0-SNAPSHOT.jar \
  -p /chemin/vers/projet-ant \
  --dry-run
```

#### Migration complète

Génère le projet Maven complet :

```bash
java -jar ant2maven-1.0.0-SNAPSHOT.jar \
  -p /chemin/vers/projet-ant \
  -o /chemin/vers/projet-maven
```

### Options complètes

| Option | Description | Valeur par défaut |
|--------|-------------|-------------------|
| `-p, --project` | **Requis.** Chemin vers le projet Ant source | - |
| `-o, --output` | Répertoire de sortie pour le projet Maven | `<project>-maven` |
| `-k, --known-artifacts` | Fichier YAML de mapping des artefacts connus | Fichier embarqué |
| `-b, --build-variant` | Variante de build (default, pic, etc.) | `default` |
| `--base-package` | Package de base pour les artefacts internes | `fr.cnamts` |
| `--dry-run` | Mode analyse sans génération de fichiers | `false` |
| `--skip-maven-central` | Désactiver la recherche sur Maven Central | `false` |
| `-v, --verbose` | Activer les logs détaillés | `false` |

#### Options Artifactory

| Option | Description | Valeur par défaut |
|--------|-------------|-------------------|
| `--artifactory-url` | URL de base Artifactory | - |
| `--artifactory-cert` | Chemin vers le certificat SSL (.crt) | - |
| `--artifactory-release-repo` | Nom du repository releases | `libs-release-local` |
| `--artifactory-snapshot-repo` | Nom du repository snapshots | `libs-snapshot-local` |
| `--artifactory-user` | Nom d'utilisateur Artifactory | - |
| `--artifactory-password` | Mot de passe Artifactory | - |
| `--deploy-mode` | Mode de déploiement : LOCAL ou REMOTE | `LOCAL` |

### Exemples

#### Exemple 1 : Migration simple

```bash
java -jar ant2maven-1.0.0-SNAPSHOT.jar \
  -p /projects/GMIC_J \
  -o /projects/GMIC_J-maven \
  -v
```

#### Exemple 2 : Migration avec package fr.cnam

```bash
java -jar ant2maven-1.0.0-SNAPSHOT.jar \
  -p /projects/R0_J \
  -o /projects/R0_J-maven \
  --base-package fr.cnam \
  -v
```

#### Exemple 3 : Migration avec Artifactory

```bash
java -jar ant2maven-1.0.0-SNAPSHOT.jar \
  -p /projects/GMIC_J \
  -o /projects/GMIC_J-maven \
  --artifactory-url https://artifactory.exemple.fr/artifactory \
  --artifactory-cert /chemin/vers/certificat.crt \
  --artifactory-user monuser \
  --artifactory-password monpassword \
  --deploy-mode REMOTE \
  -v
```

#### Exemple 4 : Analyse seule (dry-run)

```bash
java -jar ant2maven-1.0.0-SNAPSHOT.jar \
  -p /projects/GMIC_J \
  --dry-run \
  -v
```

#### Exemple 5 : Avec fichier de configuration personnalisé

```bash
java -jar ant2maven-1.0.0-SNAPSHOT.jar \
  -p /projects/GMIC_J \
  -k /config/my-known-artifacts.yaml \
  -v
```

---

## Configuration

### Fichier known-artifacts.yaml

Ce fichier définit le mapping entre les noms de fichiers JAR et leurs coordonnées Maven. Il est utilisé pour les JARs dont le nom ne permet pas de déterminer automatiquement les coordonnées.

#### Emplacement

- **Par défaut** : `src/main/resources/known-artifacts.yaml` (embarqué dans le JAR)
- **Personnalisé** : Spécifier via l'option `-k`

#### Format

```yaml
knownArtifacts:
  # Format simple
  nom-du-jar.jar:
    groupId: com.exemple
    artifactId: mon-artefact
    version: "1.0.0"

  # Format complet avec scope et note
  autre-jar.jar:
    groupId: com.exemple
    artifactId: autre-artefact
    version: "2.0.0"
    scope: test        # compile, provided, runtime, test
    classifier: jdk8   # optionnel
    note: "Description ou remarque"
```

#### Exemples de configuration

```yaml
knownArtifacts:
  # Bouncy Castle (version dans le nom diffère de Maven)
  bcprov-jdk15on-166.jar:
    groupId: org.bouncycastle
    artifactId: bcprov-jdk15on
    version: "1.66"

  # Spring Framework
  spring-core-3.2.18.RELEASE.jar:
    groupId: org.springframework
    artifactId: spring-core
    version: 3.2.18.RELEASE

  # Dépendance de test
  junit-4.11.jar:
    groupId: junit
    artifactId: junit
    version: 4.11
    scope: test

  # Driver Oracle (nécessite téléchargement manuel)
  classes12.jar:
    groupId: com.oracle.database.jdbc
    artifactId: ojdbc8
    version: 12.2.0.1
    scope: provided
    note: "Oracle JDBC - requires manual download from Oracle"
```

### Configuration Artifactory

#### Ordre de résolution des dépendances

1. **Patterns internes** : DEPFAB.*, jk-socle-*, Service*_client.jar, etc.
2. **Configuration known-artifacts.yaml**
3. **Artifactory** (si configuré) : Recherche par SHA1
4. **Maven Central** : Recherche par SHA1
5. **Pattern matching** : Extraction depuis le nom de fichier

#### Certificat SSL

Pour les instances Artifactory avec certificat auto-signé :

```bash
# Exporter le certificat depuis le navigateur ou via openssl
openssl s_client -connect artifactory.exemple.fr:443 \
  -showcerts </dev/null 2>/dev/null | \
  openssl x509 -outform PEM > artifactory.crt

# Utiliser dans la commande
java -jar ant2maven-1.0.0-SNAPSHOT.jar \
  -p /projects/GMIC_J \
  --artifactory-url https://artifactory.exemple.fr/artifactory \
  --artifactory-cert /chemin/vers/artifactory.crt
```

### Modes de déploiement

#### Mode LOCAL (par défaut)

Les JARs internes sont installés dans le repository Maven local (`~/.m2/repository`).

```bash
# Après migration, exécuter le script généré
cd projet-maven
./liblocale/install-local-jars.sh
```

#### Mode REMOTE

Les JARs internes sont déployés sur Artifactory. Un script de déploiement est généré.

```bash
# Après migration, exécuter le script généré
cd projet-maven
./liblocale/deploy-to-artifactory.sh
```

---

## Processus de migration

### Phase 1 : Analyse du projet source

1. Détection du type de projet (MAVEN_STYLE, STANDARD, FLAT)
2. Scan des répertoires de JARs (lib/, libtest/, libprovided/)
3. Parsing des fichiers build.xml Ant
4. Extraction des dépendances internes depuis properties.conf
5. Calcul des checksums SHA1 pour chaque JAR

### Phase 2 : Résolution des dépendances

1. Application des patterns internes (DEPFAB.*, jk-socle-*, etc.)
2. Recherche dans known-artifacts.yaml
3. Lookup SHA1 sur Artifactory (si configuré)
4. Lookup SHA1 sur Maven Central
5. Pattern matching sur le nom de fichier
6. Génération de versions SHA pour les JARs non résolus

### Phase 3 : Génération du projet Maven

1. Création de la structure de répertoires
2. Copie des sources Java (main et test)
3. Copie des ressources et fichiers webapp
4. Génération des pom.xml (parent, web, ear)
5. Copie des JARs internes dans liblocale/
6. Génération des scripts d'installation

---

## Fichiers générés

### Structure du projet généré

```
projet-maven/
├── pom.xml                      # POM parent
├── mvnw                         # Maven wrapper (Unix)
├── mvnw.cmd                     # Maven wrapper (Windows)
├── .mvn/
│   └── wrapper/
│       └── maven-wrapper.properties
├── projet-web/
│   ├── pom.xml                  # POM module WAR
│   └── src/
│       ├── main/
│       │   ├── java/            # Sources Java
│       │   ├── resources/       # Ressources
│       │   └── webapp/          # Contenu web
│       └── test/
│           ├── java/            # Tests Java
│           └── resources/       # Ressources de test
├── projet-ear/
│   ├── pom.xml                  # POM module EAR
│   └── src/main/application/
│       ├── application.xml
│       └── weblogic-application.xml
├── liblocale/
│   ├── install-local-jars.sh    # Script d'installation locale
│   ├── deploy-to-artifactory.sh # Script de déploiement (si REMOTE)
│   └── *.jar                    # JARs internes copiés
├── migration-report.html        # Rapport de migration
└── libnotfound.csv              # Liste des JARs non résolus
```

### Rapport de migration (migration-report.html)

Le rapport HTML contient :

- **Résumé** : Statistiques globales (total JARs, résolus, non résolus)
- **Méthodes de résolution** : Répartition par méthode utilisée
- **Dépendances par scope** : compile, provided, test, runtime
- **Dépendances internes** : Liste avec coordonnées Maven générées
- **Dépendances externes** : Liste avec coordonnées Maven trouvées
- **Informations projet** : Type, nombre de fichiers Java, etc.

### Script install-local-jars.sh

Script bash pour installer les JARs internes dans le repository Maven local :

```bash
#!/bin/bash
# Install internal JARs to local Maven repository (.m2)

set -e
cd "$(dirname "$0")"

# Exemple d'installation
mvn install:install-file \
    -Dfile="jk-socle-util-1.2.5.jar" \
    -DgroupId="fr.cnamts.jk.socle" \
    -DartifactId="jk-socle-util" \
    -Dversion="1.2.5" \
    -Dpackaging=jar

# JAR avec version SHA (pas de version détectable)
mvn install:install-file \
    -Dfile="tracesCaster.jar" \
    -DgroupId="fr.cnamts.internal" \
    -DartifactId="tracesCaster-eed09ca1" \
    -Dversion="SHA-eed09ca1" \
    -Dpackaging=jar
```

---

## Architecture du projet

### Packages Java

```
fr.cnam.migration
├── Ant2MavenApplication.java    # Point d'entrée CLI (Picocli)
├── config/
│   ├── MigrationConfig.java     # Configuration globale (record)
│   ├── KnownArtifactsRegistry.java  # Chargement known-artifacts.yaml
│   └── InternalArtifactPatterns.java # Patterns d'artefacts internes
├── scanner/
│   ├── ProjectScanner.java      # Orchestration du scan
│   ├── JarScanner.java          # Scan et checksum des JARs
│   ├── AntBuildParser.java      # Parsing des build.xml
│   └── PropertiesConfParser.java # Parsing properties.conf
├── analyzer/
│   ├── DependencyAnalyzer.java  # Orchestration résolution
│   ├── MavenCentralClient.java  # Client REST Maven Central
│   ├── ArtifactoryClient.java   # Client REST Artifactory
│   ├── JarNamePatternMatcher.java # Extraction version depuis nom
│   └── JarVersionExtractor.java # Extraction version multi-sources
├── generator/
│   ├── ProjectGenerator.java    # Orchestration génération
│   ├── StructureCreator.java    # Création répertoires et copies
│   └── TemplateService.java     # Génération via FreeMarker
├── model/
│   ├── ProjectStructure.java    # Structure projet scannée
│   ├── JarInfo.java             # Informations JAR (record)
│   ├── DependencyInfo.java      # Dépendance résolue (record)
│   ├── MavenCoordinate.java     # Coordonnées Maven (record)
│   ├── AnalysisResult.java      # Résultat d'analyse (record)
│   └── ...
└── report/
    └── ReportGenerator.java     # Génération rapports HTML/CSV
```

### Technologies utilisées

- **Java 21** : Records, sealed classes, pattern matching
- **Picocli** : Framework CLI
- **FreeMarker** : Moteur de templates
- **Jackson** : Parsing YAML/JSON
- **DOM4J** : Parsing XML (build.xml)
- **SLF4J + Logback** : Logging

---

## Résolution des dépendances

### Patterns internes reconnus

Les JARs correspondant à ces patterns sont considérés comme internes :

| Pattern | GroupId généré | Exemple |
|---------|---------------|---------|
| `DEPFAB.*` | `fr.cnamts.internal.*` | DEPFAB.S8_J.audit.jar |
| `jk-socle-*` | `fr.cnamts.jk.socle` | jk-socle-util-1.2.5.jar |
| `Service*_client.jar` | `fr.cnamts.services` | ServicePS_3.0.client.jar |
| `s8*-*.jar` | `fr.cnamts.s8` | s8sp-2.0.3.jar |
| `S8_J.*` | `fr.cnamts.s8.j` | S8_J.commons-collections4-4.1.jar |
| `SOCA*` | `fr.cnamts.internal` | SOCA010000J-1.0.0-multipub-pub.jar |

### Lookup SHA1

La recherche par checksum SHA1 permet d'identifier précisément un JAR :

1. **Maven Central** : `https://search.maven.org/solrsearch/select?q=1:<sha1>`
2. **Artifactory** : `<url>/api/search/checksum?sha1=<sha1>`

---

## Gestion des versions

### Sources de version (par ordre de priorité)

1. **Nom de fichier** : `artifact-1.2.3.jar` → version `1.2.3`
2. **MANIFEST.MF** : `Implementation-Version` ou `Bundle-Version`
3. **pom.properties** : `META-INF/maven/.../pom.properties`
4. **version.properties** : Fichier `version.properties` dans le JAR
5. **SHA-1** : Version générée à partir du hash (fallback)

### Versionnement SHA

Pour les JARs sans version détectable, une version unique est générée :

```
JAR: tracesCaster.jar
SHA1: eed09ca1b2c3d4e5f6...

Coordonnées générées:
- groupId: fr.cnamts.internal
- artifactId: tracesCaster-eed09ca1
- version: SHA-eed09ca1
```

Cela évite les collisions si différents projets ont des versions différentes du même JAR non versionné.

---

## Dépannage

### Erreur : "Could not resolve dependency"

1. Vérifier la connectivité réseau vers Maven Central
2. Ajouter le JAR dans `known-artifacts.yaml`
3. Utiliser `--verbose` pour voir les détails de résolution

### Erreur : "SSL certificate problem"

Pour Artifactory avec certificat auto-signé :

```bash
# Exporter et utiliser le certificat
--artifactory-cert /chemin/vers/certificat.crt
```

### JARs marqués comme "unresolved"

1. Consulter `libnotfound.csv` pour la liste
2. Ajouter les mappings dans `known-artifacts.yaml`
3. Relancer la migration

### Compilation Maven échoue après migration

1. Exécuter le script d'installation des JARs internes :
   ```bash
   ./liblocale/install-local-jars.sh
   ```
2. Vérifier que toutes les dépendances sont dans le repository local

### Logs détaillés

Activer le mode verbose pour diagnostiquer :

```bash
java -jar ant2maven-1.0.0-SNAPSHOT.jar \
  -p /projects/GMIC_J \
  --dry-run \
  -v
```

---

## Contribution

1. Fork le projet
2. Créer une branche feature (`git checkout -b feature/ma-feature`)
3. Commiter les changements (`git commit -am 'Ajout de ma feature'`)
4. Pusher la branche (`git push origin feature/ma-feature`)
5. Créer une Pull Request

---

## Licence

Ce projet est sous licence interne CNAM.

---

## Auteurs

- **CNAM** - Développement initial
- **Claude Code** - Assistance au développement

---

## Changelog

### Version 1.1.0
- Ajout de l'intégration Artifactory
- Support SSL avec certificats personnalisés
- Versionnement SHA pour les JARs non versionnés
- Modes de déploiement LOCAL/REMOTE
- Extraction de version depuis MANIFEST.MF et pom.properties

### Version 1.0.0
- Version initiale
- Scan des projets Ant
- Résolution des dépendances via Maven Central
- Génération de projets Maven multi-modules
