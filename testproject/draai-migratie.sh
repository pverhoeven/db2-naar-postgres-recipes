#!/usr/bin/env bash
# Draait migreer-naar-postgres.sh op een kopie van klant-service en test het resultaat.
# Het origineel blijft onaangeroerd; het resultaat staat in werk/klant-service (git diff HEAD).
#
# Gebruik: testproject/draai-migratie.sh [--met-handwerk]
#   --met-handwerk  past daarna handwerk.patch toe: het handwerk uit de bevindingen, zodat de
#                   tests op Postgres groen worden
# JAVA_HOME moet naar JDK 21 of 25 wijzen (OpenRewrite heeft geen parser voor 26+).
set -euo pipefail

HIER="$(cd "$(dirname "$0")" && pwd)"
RECIPES_ROOT="$(dirname "$HIER")"
WERK="$HIER/werk"
HANDWERK=false
[[ "${1:-}" == "--met-handwerk" ]] && HANDWERK=true

echo "==> Recipes installeren in ~/.m2"
mvn -B -q -f "$RECIPES_ROOT/pom.xml" install -DskipTests

echo "==> Kopie maken in $WERK"
rm -rf "$WERK"
mkdir -p "$WERK"
cp -R "$HIER/klant-service" "$WERK/"
rm -rf "$WERK/klant-service/target"
cd "$WERK/klant-service"
git init -q -b main
git add -A
git -c user.name=test -c user.email=test@example.nl commit -q -m "DB2-uitgangssituatie"

echo "==> Migreren"
"$RECIPES_ROOT/migreer-naar-postgres.sh" "$WERK/klant-service" "$HIER/pg-review"

if $HANDWERK; then
  echo "==> Handwerk toepassen (handwerk.patch)"
  git apply --index "$HIER/handwerk.patch"
fi

# Testcontainers met podman: socket van de podman-machine, en Ryuk mount de socket in de VM
if [[ -z "${DOCKER_HOST:-}" ]] && command -v podman >/dev/null && podman machine inspect >/dev/null 2>&1; then
  export DOCKER_HOST="unix://$(podman machine inspect --format '{{.ConnectionInfo.PodmanSocket.Path}}')"
  export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE="${TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE:-$(podman info --format '{{.Host.RemoteSocket.Path}}' | sed 's|^unix://||')}"
fi
if docker info >/dev/null 2>&1 || podman info >/dev/null 2>&1; then
  echo "==> Testen na de migratie (Postgres via Testcontainers)"
  mvn -B -q verify
else
  echo "==> Geen docker/podman gevonden: alleen compileren (tests overgeslagen)"
  mvn -B -q test-compile
fi

echo
echo "Klaar. Bekijk de wijzigingen met:  git -C $WERK/klant-service diff --cached"
echo "Bevindingen:                        $WERK/klant-service-postgres-overstap/"
