#!/usr/bin/env bash
# Overstap van één microservice van DB2 naar Postgres (één ticket = één run).
#
# Gebruik:
#   migreer-naar-postgres.sh <service-map> <gereviewde-postgres-map> [opties]
#
# <gereviewde-postgres-map> is de output van de voorbereidende stap, door het team gereviewd:
#   000-baseline.yaml      uit jullie schema-generator
#   001-basisdata.yaml     uit: liquibase generate-changelog --diff-types=data (leeg opgestarte DB2)
#
# Opties:
#   --oude-dbms <waarde>   dbms voor bestaande changesets (default db2)
#   --postgres-image <img> image voor Testcontainers (default postgres:17)
#   --schema <naam>        Postgres-schema voor preconditions (default: search_path)
#   --master <pad>         master-changelog (default src/main/resources/db/changelog/db.changelog-master.yaml)
#   --branch <naam>        default feature/postgres-overstap
#
# Omgevingsvariabele RECIPES: Maven-coördinaten van het recipe-artifact
#   (default nl.example.migratie:db2-naar-postgres-recipes:1.0.0-SNAPSHOT)
#
# Het script commit niets: alles staat klaar met git add. Bevindingen komen naast de repo in
# <service>-postgres-overstap/ (CSV) voor de PR-beschrijving.
set -euo pipefail

usage() { sed -n '2,23p' "$0" | sed 's/^# \{0,1\}//'; exit 1; }
[[ $# -lt 2 ]] && usage

SVC="$(cd "$1" && pwd)"; REVIEW="$(cd "$2" && pwd)"; shift 2
OUDE_DBMS="db2"; PG_IMAGE="postgres:17"; SCHEMA=""; MASTER="src/main/resources/db/changelog/db.changelog-master.yaml"
BRANCH="feature/postgres-overstap"
RECIPES="${RECIPES:-nl.example.migratie:db2-naar-postgres-recipes:1.0.0-SNAPSHOT}"
while [[ $# -gt 0 ]]; do
  case "$1" in
    --oude-dbms) OUDE_DBMS="$2"; shift 2 ;;
    --postgres-image) PG_IMAGE="$2"; shift 2 ;;
    --schema) SCHEMA="$2"; shift 2 ;;
    --master) MASTER="$2"; shift 2 ;;
    --branch) BRANCH="$2"; shift 2 ;;
    *) usage ;;
  esac
done

NAAM="$(basename "$SVC")"
ROOT="$(dirname "$MASTER")"
RAPPORT="$(dirname "$SVC")/$NAAM-postgres-overstap"
stap() { printf '\n\033[1m==> %s\033[0m\n' "$*"; }

stap "1/4 Controles"
cd "$SVC"
git diff --quiet && git diff --cached --quiet || { echo "FOUT: $NAAM heeft niet-gecommitte wijzigingen"; exit 1; }
[[ -f "$MASTER" ]] || { echo "FOUT: $MASTER niet gevonden (gebruik --master)"; exit 1; }
[[ -e "$ROOT/db2" ]] && { echo "FOUT: $ROOT/db2 bestaat al; is deze service al over?"; exit 1; }
[[ -e "$ROOT/postgres" ]] && { echo "FOUT: $ROOT/postgres bestaat al"; exit 1; }
ls "$REVIEW"/*.yaml >/dev/null 2>&1 || { echo "FOUT: geen YAML-changelogs in $REVIEW"; exit 1; }
git switch -c "$BRANCH" 2>/dev/null || git switch "$BRANCH"

stap "2/4 Gereviewde Postgres-changelogs overnemen"
mkdir -p "$ROOT/postgres"
cp "$REVIEW"/*.yaml "$ROOT/postgres/"
ls "$ROOT/postgres"

stap "3/4 OpenRewrite"
CONFIG="$(mktemp -d)/rewrite.yml"
cat > "$CONFIG" <<EOF
type: specs.openrewrite.org/v1beta/recipe
name: nl.example.migratie.Db2NaarPostgresDezeService
displayName: DB2 naar Postgres voor $NAAM
recipeList:
  - nl.example.migratie.Db2NaarPostgresChangelogs:
      masterChangelog: $MASTER
      oudeDbms: "$OUDE_DBMS"
      postgresSchema: "$SCHEMA"
  - nl.example.migratie.StemEntitiesAfOpPostgres:
      postgresChangelogMap: $ROOT/postgres
  - nl.example.migratie.Db2NaarPostgresPom
  - nl.example.migratie.Db2NaarPostgresSpring
  # H2NaarTestcontainers, met het image als optie
  - org.openrewrite.maven.ChangeDependencyGroupIdAndArtifactId:
      oldGroupId: com.h2database
      oldArtifactId: h2
      newGroupId: org.testcontainers
      newArtifactId: testcontainers-postgresql
      newVersion: 2.x
      overrideManagedVersion: false
  - org.openrewrite.maven.ChangeDependencyScope:
      groupId: org.testcontainers
      artifactId: testcontainers-postgresql
      newScope: test
  - org.openrewrite.maven.AddDependency:
      groupId: org.springframework.boot
      artifactId: spring-boot-testcontainers
      version: 4.x
      scope: test
      onlyIfUsing: org.springframework.boot.test.context.SpringBootTest
  - nl.example.migratie.VoegTestcontainersToe:
      postgresImage: "$PG_IMAGE"
  - nl.example.migratie.TestconfiguratieZonderDatasource
  - nl.example.migratie.ZoekDb2Restanten
EOF
# plainTextMasks: formatted-SQL-changelogs en data-bestanden als tekst inlezen, zodat ze mee verhuizen
# Pluginversie vast: de recipes zijn getest tegen deze OpenRewrite-versie (zie pom.xml)
mvn -B -U org.openrewrite.maven:rewrite-maven-plugin:6.46.1:run \
  -Drewrite.configLocation="$CONFIG" \
  -Drewrite.activeRecipes=nl.example.migratie.Db2NaarPostgresDezeService \
  -Drewrite.recipeArtifactCoordinates="$RECIPES" \
  -Drewrite.exportDatatables=true \
  -Drewrite.plainTextMasks='**/*.sql,**/*.csv,**/*.txt,**/*.md'

stap "4/4 Bevindingen en klaarzetten"
mkdir -p "$RAPPORT"
LAATSTE="$(ls -td target/rewrite/datatables/*/ 2>/dev/null | head -1 || true)"
if [[ -n "$LAATSTE" ]]; then
  cp "$LAATSTE"*.csv "$RAPPORT/" 2>/dev/null || true
  for f in "$RAPPORT"/*MigratieBevindingen*.csv; do
    [[ -f "$f" ]] && echo "Bevindingen: $f"
  done
else
  echo "LET OP: geen datatabellen gevonden in target/rewrite/datatables"
fi
[[ -d "$ROOT/db2" ]] || { echo "FOUT: $ROOT/db2 is niet aangemaakt; zie de output van OpenRewrite"; exit 1; }
git add -A
git status --short | sed 's/^/  /'
cat <<EOF

Klaar. Wijzigingen staan op branch $BRANCH (niet gecommit).
Nog te doen:
  - Bevindingen in $RAPPORT nalopen
  - mvn verify (tests draaien op Postgres via Testcontainers: docker/podman nodig)
  - Lokaal draaien: Local<App> in src/test/java
  - Lokaal draaien tegen Postgres: schema en basisdata goed?
  - Controle DB2: liquibase status --verbose tegen een DB2-omgeving moet 0 changesets tonen
EOF
