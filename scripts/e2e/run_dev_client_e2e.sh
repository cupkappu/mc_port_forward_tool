#!/usr/bin/env bash
set -euo pipefail

if [[ $# -lt 1 ]]; then
  echo "usage: $0 <minecraft-version> [username] [psk]" >&2
  exit 2
fi

VERSION="$1"
USERNAME="${2:-E2EPlayer}"
PSK="${3:-mc-transport-e2e-psk}"
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SERVER_DIR="${ROOT_DIR}/run/e2e-server-${VERSION}"
RESULT_FILE="${ROOT_DIR}/docs/e2e-results/${VERSION}.md"
PLAYER_UUID="$("${ROOT_DIR}/scripts/e2e/offline_uuid.py" "$USERNAME")"

case "$VERSION" in
  1.20.1)
    SERVER_JAVA_HOME="${SERVER_JAVA_HOME:-/opt/homebrew/opt/openjdk@17}"
    ;;
  1.21.1)
    SERVER_JAVA_HOME="${SERVER_JAVA_HOME:-/opt/homebrew/opt/openjdk@25}"
    ;;
  *)
    echo "unsupported minecraft version: ${VERSION}" >&2
    exit 2
    ;;
esac

SERVER_JAVA_BIN="${SERVER_JAVA_HOME}/bin/java"
GRADLE_JAVA_HOME="${GRADLE_JAVA_HOME:-/opt/homebrew/opt/openjdk@25}"
export GRADLE_OPTS="${GRADLE_OPTS:-} -Dorg.gradle.native=false"

mkdir -p "${ROOT_DIR}/run/config"

"${ROOT_DIR}/scripts/e2e/prepare_fabric_server.sh" "$VERSION" "$SERVER_DIR" "$PLAYER_UUID" "$PSK"
cp "${ROOT_DIR}/run/e2e-client-${VERSION}/config/mctransport.client.toml" \
  "${ROOT_DIR}/run/config/mctransport.client.toml"

echo "Starting echo target on 127.0.0.1:10000"
"${ROOT_DIR}/scripts/e2e/echo_server.py" --host 127.0.0.1 --port 10000 \
  >"${SERVER_DIR}/echo_server_dev_client.log" 2>&1 &
ECHO_PID="$!"

echo "Starting Fabric ${VERSION} server on 127.0.0.1:25565"
(
  cd "$SERVER_DIR"
  "$SERVER_JAVA_BIN" -jar fabric-server-launch.jar nogui
) >"${SERVER_DIR}/server-dev-client-e2e.log" 2>&1 &
SERVER_PID="$!"
CLIENT_PID=""

cleanup() {
  if [[ -n "${CLIENT_PID}" ]] && kill -0 "$CLIENT_PID" 2>/dev/null; then
    kill "$CLIENT_PID" 2>/dev/null || true
  fi
  if kill -0 "$SERVER_PID" 2>/dev/null; then
    kill "$SERVER_PID" 2>/dev/null || true
  fi
  if kill -0 "$ECHO_PID" 2>/dev/null; then
    kill "$ECHO_PID" 2>/dev/null || true
  fi
}
trap cleanup EXIT

echo "Waiting for server startup"
for _ in $(seq 1 180); do
  if rg -q 'Done \(' "${SERVER_DIR}/server-dev-client-e2e.log" 2>/dev/null; then
    break
  fi
  if ! kill -0 "$SERVER_PID" 2>/dev/null; then
    echo "server exited before startup; see ${SERVER_DIR}/server-dev-client-e2e.log" >&2
    exit 1
  fi
  sleep 1
done
rg -q 'Done \(' "${SERVER_DIR}/server-dev-client-e2e.log"

echo "Starting Loom dev client for ${VERSION} as ${USERNAME} (${PLAYER_UUID})"
(
  cd "$ROOT_DIR"
  JAVA_HOME="$GRADLE_JAVA_HOME" ./gradlew runClient -PtargetMinecraft="$VERSION" \
    -PmctransportE2eQuickJoin=127.0.0.1:25565 \
    --args="--username ${USERNAME}"
) >"${SERVER_DIR}/client-dev-e2e.log" 2>&1 &
CLIENT_PID="$!"

echo "Waiting for client local listener on 127.0.0.1:25580"
for _ in $(seq 1 300); do
  if python3 - <<'PY'
import socket
try:
    with socket.create_connection(("127.0.0.1", 25580), timeout=1):
        raise SystemExit(0)
except OSError:
    raise SystemExit(1)
PY
  then
    break
  fi
  if ! kill -0 "$CLIENT_PID" 2>/dev/null; then
    echo "client exited before opening 127.0.0.1:25580; see ${SERVER_DIR}/client-dev-e2e.log" >&2
    exit 1
  fi
  sleep 1
done
python3 - <<'PY'
import socket
with socket.create_connection(("127.0.0.1", 25580), timeout=1):
    pass
PY

echo "Waiting for client join/auth handshake log"
for _ in $(seq 1 300); do
  if rg -q 'player .* joined; tunnel session ready' \
      "${SERVER_DIR}/server-dev-client-e2e.log" 2>/dev/null && \
      rg -q 'client joined; sending AUTH' \
      "${SERVER_DIR}/client-dev-e2e.log" 2>/dev/null && \
      rg -q 'client tunnel authenticated' \
      "${SERVER_DIR}/client-dev-e2e.log" 2>/dev/null; then
    break
  fi
  if ! kill -0 "$CLIENT_PID" 2>/dev/null; then
    echo "client exited before join/auth; see ${SERVER_DIR}/client-dev-e2e.log" >&2
    exit 1
  fi
  sleep 1
done
rg -q 'player .* joined; tunnel session ready' \
  "${SERVER_DIR}/server-dev-client-e2e.log"
rg -q 'client joined; sending AUTH' \
  "${SERVER_DIR}/client-dev-e2e.log"
rg -q 'client tunnel authenticated' \
  "${SERVER_DIR}/client-dev-e2e.log"

echo "Running TCP probes"
SINGLE_LOG="${SERVER_DIR}/probe-single.log"
CONCURRENT_LOG="${SERVER_DIR}/probe-concurrent.log"
if ! "${ROOT_DIR}/scripts/e2e/tcp_probe.py" --host 127.0.0.1 --port 25580 --bytes 4096 \
    >"$SINGLE_LOG" 2>&1; then
  cat "$SINGLE_LOG" >&2
  echo "single TCP probe failed; see ${SINGLE_LOG}" >&2
  exit 1
fi
if ! "${ROOT_DIR}/scripts/e2e/tcp_probe.py" --host 127.0.0.1 --port 25580 --connections 4 --bytes 16384 \
    >"$CONCURRENT_LOG" 2>&1; then
  cat "$CONCURRENT_LOG" >&2
  echo "concurrent TCP probe failed; see ${CONCURRENT_LOG}" >&2
  exit 1
fi
SINGLE_OUTPUT="$(cat "$SINGLE_LOG")"
CONCURRENT_OUTPUT="$(cat "$CONCURRENT_LOG")"

{
  echo
  echo "Loom dev-client E2E evidence captured at \`$(date '+%Y-%m-%d %H:%M:%S %Z')\`:"
  echo
  echo "- Username: \`${USERNAME}\`"
  echo "- Offline UUID: \`${PLAYER_UUID}\`"
  echo "- Server Java: \`${SERVER_JAVA_BIN}\`"
  echo "- Gradle Java home: \`${GRADLE_JAVA_HOME}\`"
  echo "- Server log: \`${SERVER_DIR}/server-dev-client-e2e.log\`"
  echo "- Client log: \`${SERVER_DIR}/client-dev-e2e.log\`"
  echo "- Single probe:"
  echo '```'
  echo "$SINGLE_OUTPUT"
  echo '```'
  echo "- Concurrent probe:"
  echo '```'
  echo "$CONCURRENT_OUTPUT"
  echo '```'
} >> "$RESULT_FILE"

echo "$SINGLE_OUTPUT"
echo "$CONCURRENT_OUTPUT"
echo "Wrote ${RESULT_FILE}"
