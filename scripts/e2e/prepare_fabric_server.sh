#!/usr/bin/env bash
set -euo pipefail

if [[ $# -lt 2 ]]; then
  echo "usage: $0 <minecraft-version> <server-dir> [player-uuid] [psk]" >&2
  exit 2
fi

VERSION="$1"
SERVER_DIR="$2"
PLAYER_UUID="${3:-}"
PSK="${4:-mc-transport-e2e-psk}"
LOADER_VERSION="${LOADER_VERSION:-0.19.3}"
INSTALLER_VERSION="${INSTALLER_VERSION:-1.1.0}"

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
mkdir -p "$SERVER_DIR"
SERVER_DIR="$(cd "$SERVER_DIR" && pwd)"
VERSION_FILE="${ROOT_DIR}/versions/${VERSION}.properties"
if [[ ! -f "$VERSION_FILE" ]]; then
  echo "unsupported minecraft version: ${VERSION}" >&2
  exit 2
fi

FABRIC_API_VERSION="$(
  awk -F= '$1 == "fabric_api_version" { print $2 }' "$VERSION_FILE"
)"
MOD_JAR="${ROOT_DIR}/build/libs/mc-transport-dialer-${VERSION}-0.1.0.jar"

if [[ ! -f "$MOD_JAR" ]]; then
  echo "missing ${MOD_JAR}; run ./gradlew build -PtargetMinecraft=${VERSION} first" >&2
  exit 1
fi

mkdir -p "$SERVER_DIR/mods" "$SERVER_DIR/config"

INSTALLER_JAR="${SERVER_DIR}/fabric-installer-${INSTALLER_VERSION}.jar"
if [[ ! -f "$INSTALLER_JAR" ]]; then
  curl -fL \
    "https://maven.fabricmc.net/net/fabricmc/fabric-installer/${INSTALLER_VERSION}/fabric-installer-${INSTALLER_VERSION}.jar" \
    -o "$INSTALLER_JAR"
fi

(
  cd "$SERVER_DIR"
  java -jar "$INSTALLER_JAR" server \
    -mcversion "$VERSION" \
    -loader "$LOADER_VERSION" \
    -downloadMinecraft
  echo "eula=true" > eula.txt
)

curl -fL \
  "https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/${FABRIC_API_VERSION}/fabric-api-${FABRIC_API_VERSION}.jar" \
  -o "${SERVER_DIR}/mods/fabric-api-${FABRIC_API_VERSION}.jar"
cp "$MOD_JAR" "${SERVER_DIR}/mods/"

if [[ -n "$PLAYER_UUID" ]]; then
  "${ROOT_DIR}/scripts/e2e/write_configs.sh" \
    "${SERVER_DIR}/config" \
    "${ROOT_DIR}/run/e2e-client-${VERSION}/config" \
    "$PLAYER_UUID" \
    "$PSK"
fi

cat <<EOF
Prepared Fabric ${VERSION} server in ${SERVER_DIR}

Start it with:
  cd ${SERVER_DIR}
  java -jar fabric-server-launch.jar nogui

Run the target echo server on the server host:
  ${ROOT_DIR}/scripts/e2e/echo_server.py --host 127.0.0.1 --port 10000
EOF
