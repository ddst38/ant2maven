# Ant2Maven - Outil de Migration Ant vers Maven

Outil de migration automatique pour transformer des projets Java Ant/CVS en projets Maven multi-modules.

## Table des matières

- [Fonctionnalités](#fonctionnalités)
- [Prérequis](#prérequis)
- [Installation](#installation)
- [Référence rapide](#référence-rapide)
- [Utilisation](#utilisation)
  - [Commandes de base](#commandes-de-base)
  - [Options complètes](#options-complètes)
  - [Exemples](#exemples)
- [Mode Auto-Fix](#mode-auto-fix)
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
- **Cache automatique** : les résolutions sont sauvegardées pour accélérer les exécutions suivantes
- **Support multi-modules** : génération de projets WAR/EAR
- **Gestion des JARs internes** avec versionnement SHA pour éviter les collisions
- **Intégration Artifactory** avec support SSL personnalisé
- **Modes de déploiement** : LOCAL (repository .m2) ou REMOTE (upload Artifactory)
- **Mode Auto-Fix** : correction automatique des erreurs de compilation via `--auto-fix`
- **Inference groupId** : analyse du contenu des JARs pour inférer le groupId depuis les packages Java
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

## Référence rapide

```bash
# Synopsis
java -jar ant2maven-1.0.0-SNAPSHOT.jar [OPTIONS] -p <projet-source>

# Migration basique
java -jar ant2maven-1.0.0-SNAPSHOT.jar -p ./MonProjet

# Migration avec sortie personnalisée
java -jar ant2maven-1.0.0-SNAPSHOT.jar -p ./MonProjet -o ./MonProjet-maven

# Migration avec correction automatique (recommandé)
java -jar ant2maven-1.0.0-SNAPSHOT.jar -p ./MonProjet --auto-fix -v

# Analyse seule (sans génération)
java -jar ant2maven-1.0.0-SNAPSHOT.jar -p ./MonProjet --dry-run

# Avec Artifactory
java -jar ant2maven-1.0.0-SNAPSHOT.jar -p ./MonProjet \
  --artifactory-url https://artifactory.example.com/artifactory \
  --artifactory-cert ./cert.crt
```

| Option courte | Option longue | Description |
|---------------|---------------|-------------|
| `-p` | `--project` | **Requis.** Chemin du projet Ant source |
| `-o` | `--output` | Répertoire de sortie (défaut: `<projet>-maven`) |
| `-v` | `--verbose` | Mode verbeux |
| | `--auto-fix` | Corrige automatiquement les erreurs de compilation |
| | `--lib-provided` | Répertoire des JARs serveur (défaut: `lib-provided`) |
| | `--dry-run` | Analyse sans générer de fichiers |
| `-k` | `--known-artifacts` | Fichier YAML de cache des artefacts |
| | `--base-package` | Package de base (défaut: `fr.cnamts`) |

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
| `--auto-fix` | Compile et ajoute automatiquement les dépendances provided | `false` |
| `--lib-provided` | Répertoire des librairies provided | `lib-provided` |
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

#### Exemple 6 : Migration avec auto-fix (correction automatique)

```bash
java -jar ant2maven-1.0.0-SNAPSHOT.jar \
  -p /projects/FANO_J \
  -o /projects/FANO_J-maven \
  --auto-fix \
  -v
```

#### Exemple 7 : Auto-fix avec répertoire lib-provided personnalisé

```bash
java -jar ant2maven-1.0.0-SNAPSHOT.jar \
  -p /projects/GMIC_J \
  --auto-fix \
  --lib-provided /chemin/vers/weblogic-libs \
  -v
```

---

## Mode Auto-Fix

Le mode `--auto-fix` permet de résoudre automatiquement les erreurs de compilation dues aux dépendances serveur manquantes (WebLogic, J2EE, etc.).

### Principe

1. **Migration standard** : Le projet est d'abord migré normalement
2. **Compilation** : Le projet Maven généré est compilé
3. **Analyse des erreurs** : Les erreurs de compilation sont analysées pour identifier les classes/packages manquants
4. **Résolution** : Les JARs contenant ces classes sont recherchés dans `lib-provided/`
5. **Correction** : Les JARs trouvés sont ajoutés comme dépendances `provided` dans le POM
6. **Itération** : Le cycle compile/fix se répète jusqu'à succès (max 5 itérations)

### Répertoire lib-provided

Le répertoire `lib-provided/` (configurable via `--lib-provided`) doit contenir les JARs serveur :

```
lib-provided/
├── weblogic.jar              # Classes WebLogic
├── javax.servlet-api-3.1.0.jar
├── javax.ejb-api-3.2.jar
└── ...
```

Ces JARs sont indexés au démarrage : toutes les classes Java sont mappées vers leur JAR source.

### Fonctionnement détaillé

```
Migration terminée
       ↓
[Indexation lib-provided]
  31096 classes, 3 JARs indexés
       ↓
[Compilation Maven]
       ↓
  Erreurs ?
    ├─ Non → Succès !
    └─ Oui → Analyse des erreurs
              ↓
         [Résolution packages manquants]
           javax.servlet.* → javax.servlet-api.jar
           weblogic.* → weblogic.jar
              ↓
         [Ajout dépendances provided au POM]
              ↓
         [Recompilation] → Retour à "Erreurs ?"
```

### Rapport auto-fix

Le rapport `migration-report.html` inclut une section dédiée listant :
- Les dépendances provided ajoutées automatiquement
- Les packages qui n'ont pas pu être résolus (si échec)

### Exemple de sortie

```
============================================================
Correction automatique - Auto-fix
============================================================
Projet : ../FANO_J-maven
Lib-provided : lib-provided
Iterations max : 5

Phase 1: Indexation de lib-provided...
Indexation terminee : 31096 classes, 1442 packages, 3 JARs

Installation des JARs locaux...
[...]

Iteration 1/5:
Compilation du projet...
BUILD SUCCESS

Auto-fix terminé avec succès !
```

---

## Configuration

### Fichier known-artifacts.yaml

Ce fichier sert de **cache** pour les résolutions de dépendances. Il utilise le **SHA1 du JAR comme clé** pour garantir une correspondance exacte, quel que soit le nom du fichier.

#### Fonctionnement du cache

1. **Première exécution** : Les JARs sont recherchés sur Maven Central/Artifactory (lent)
2. **Résolution réussie** : L'entrée SHA1 → coordonnées Maven est automatiquement ajoutée au fichier
3. **Exécutions suivantes** : Les JARs déjà résolus sont trouvés instantanément via leur SHA1

#### Emplacement

- **Par défaut** : `src/main/resources/known-artifacts.yaml` (embarqué et mis à jour automatiquement)
- **Personnalisé** : Spécifier via l'option `-k`

#### Format

```yaml
knownArtifacts:
  # Clé = SHA1 du fichier JAR (40 caractères hexadécimaux)
  f0a0d2e29ed910808c33135a3a5a51bba6358f7b:
    groupId: log4j
    artifactId: log4j
    version: "1.2.15"
    jarName: log4j.jar  # Pour référence/documentation

  # Autre exemple
  dc6a73fdbd1fa3f0944e8497c6c872fa21dca37e:
    groupId: commons-digester
    artifactId: commons-digester
    version: "1.8"
    jarName: commons-digester.jar
```

#### Obtenir le SHA1 d'un JAR

```bash
# Linux
sha1sum mon-fichier.jar

# macOS
shasum -a 1 mon-fichier.jar

# Windows (PowerShell)
Get-FileHash -Algorithm SHA1 mon-fichier.jar
```

#### Avantages du cache SHA1

- **Précision** : Le même nom de fichier (ex: `struts.jar`) peut avoir des versions différentes selon les projets. Le SHA1 identifie exactement le binaire.
- **Performance** : Après la première résolution, les recherches réseau sont évitées.
- **Partage** : Le fichier peut être partagé entre développeurs/projets pour mutualiser les résolutions.

### Configuration Artifactory

#### Ordre de résolution des dépendances

1. **Cache known-artifacts.yaml** : Recherche par SHA1 (instantané)
2. **Artifactory** (si configuré) : Recherche par SHA1, puis ajout au cache
3. **Maven Central** : Recherche par SHA1, puis ajout au cache
4. **Patterns internes** : DEPFAB.*, jk-socle-*, struts.jar, classes12.jar
5. **Pattern matching** : Extraction version depuis le nom de fichier
6. **Fallback** : Génération de coordonnées avec version SHA

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

1. Recherche dans le cache known-artifacts.yaml (par SHA1)
2. Lookup SHA1 sur Artifactory (si configuré) → ajout au cache si trouvé
3. Lookup SHA1 sur Maven Central → ajout au cache si trouvé
4. Application des patterns internes (DEPFAB.*, struts.jar, classes12.jar)
5. Pattern matching sur le nom de fichier
6. Génération de versions SHA pour les JARs non résolus
7. Sauvegarde des nouvelles entrées dans known-artifacts.yaml

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

### Pipeline de résolution

Le système utilise un pipeline en 6 étapes pour résoudre chaque JAR :

1. **Cache SHA1** (known-artifacts.yaml) - Instantané, pas de réseau
2. **Artifactory SHA1** - Si configuré, résultat ajouté au cache
3. **Maven Central SHA1** - Résultat ajouté au cache
4. **Patterns internes** - Pour les JARs propriétaires (DEPFAB.*, struts.jar, etc.)
5. **Pattern nom de fichier** - Extraction version depuis le nom
6. **Fallback SHA** - Génération de coordonnées avec version SHA-xxx

### Patterns internes reconnus

Les JARs correspondant à ces patterns sont traités comme internes :

| Pattern | GroupId généré | Exemple |
|---------|---------------|---------|
| `DEPFAB.*` | `fr.cnamts.internal.*` | DEPFAB.S8_J.audit.jar |
| `jk-socle-*` | `fr.cnamts.jk.socle` | jk-socle-util-1.2.5.jar |
| `Service*_client.jar` | `fr.cnamts.services` | ServicePS_3.0.client.jar |
| `s8*-*.jar` | `fr.cnamts.s8` | s8sp-2.0.3.jar |
| `struts.jar` | `org.apache.struts` | Version extraite du MANIFEST |
| `classes12.jar` | `com.oracle.database.jdbc` | ojdbc8:12.2.0.1 |

### Lookup SHA1

La recherche par checksum SHA1 permet d'identifier précisément un JAR :

1. **Maven Central** : `https://search.maven.org/solrsearch/select?q=1:<sha1>`
2. **Artifactory** : `<url>/api/search/checksum?sha1=<sha1>`

### Cache automatique

Lorsqu'un JAR est résolu via Maven Central ou Artifactory, l'entrée est automatiquement ajoutée au fichier `known-artifacts.yaml` :

```
Première exécution (projet A):
  log4j.jar (SHA1: f0a0d...) → recherche Maven Central → trouvé → ajouté au cache

Exécutions suivantes (projet A, B, C...):
  log4j.jar (SHA1: f0a0d...) → trouvé dans cache → pas de recherche réseau
```

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

### Version 1.3.0
- **Mode Auto-Fix** : Option `--auto-fix` pour corriger automatiquement les erreurs de compilation
- **Lib-provided** : Support du répertoire `lib-provided/` pour les dépendances serveur
- **Indexation des classes** : Mapping automatique classes Java → JARs pour résolution rapide
- **Inference groupId** : Le pattern DEPFAB générique analyse le contenu du JAR pour inférer le groupId depuis les packages Java réels (ex: `fr.cnamts.trgu` au lieu de `fr.cnamts.internal`)
- **Correction JarNameCleaner** : Fix du bug où `DEPFAB.XXX_Y.jar` était renommé en `jar` au lieu de `XXX_Y.jar`
- **Rapport amélioré** : Section bibliothèques provided dans le rapport HTML

### Version 1.2.0
- **Cache automatique SHA1** : Les résolutions sont sauvegardées dans known-artifacts.yaml
- **Nouveau format known-artifacts.yaml** : Utilise le SHA1 comme clé (plus précis que le nom de fichier)
- **Pipeline réordonné** : Cache → Artifactory → Maven Central → Patterns internes
- Amélioration du rapport HTML avec statistiques cohérentes
- Correction de l'artifactId pour les JARs extraits des EAR
- Support des JARs en camelCase (jAuthApp.jar, etc.)

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
