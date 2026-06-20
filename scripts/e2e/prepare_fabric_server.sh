#!/usr/bin/env bash
set -euo pipefail

if [[ $# -lt 2 ]]; then
  echo "usage: $0 <minecraft-version> <server-dir> [player-uuid] [player-name]" >&2
  exit 2
fi

VERSION="$1"
SERVER_DIR="$2"
PLAYER_UUID="${3:-}"
PLAYER_NAME="${4:-E2EPlayer}"
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
MOD_VERSION="$(
  awk -F= '$1 == "mod_version" { print $2 }' "${ROOT_DIR}/gradle.properties"
)"
MOD_JAR="${ROOT_DIR}/build/libs/mc-transport-dialer-${VERSION}-${MOD_VERSION}.jar"

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
  cat > server.properties <<'EOF'
server-ip=127.0.0.1
server-port=25565
online-mode=false
motd=MC Transport Dialer E2E
enable-command-block=false
spawn-protection=0
view-distance=6
simulation-distance=4
EOF
)

curl -fL \
  "https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/${FABRIC_API_VERSION}/fabric-api-${FABRIC_API_VERSION}.jar" \
  -o "${SERVER_DIR}/mods/fabric-api-${FABRIC_API_VERSION}.jar"
cp "$MOD_JAR" "${SERVER_DIR}/mods/"

if [[ -n "$PLAYER_UUID" ]]; then
  "${ROOT_DIR}/scripts/e2e/write_configs.sh" \
    "${SERVER_DIR}/config" \
    "$PLAYER_UUID" \
    "$PLAYER_NAME" \
    25580 \
    127.0.0.1 \
    10000
fi

cat <<EOF
Prepared Fabric ${VERSION} server in ${SERVER_DIR}

Start it with:
  cd ${SERVER_DIR}
  java -jar fabric-server-launch.jar nogui

Run the target echo server on the server host:
  ${ROOT_DIR}/scripts/e2e/echo_server.py --host 127.0.0.1 --port 10000
EOF
