#!/usr/bin/env bash
set -euo pipefail

if [[ $# -lt 3 ]]; then
  echo "usage: $0 <minecraft-version> <player-uuid> <player-name>" >&2
  exit 2
fi

VERSION="$1"
PLAYER_UUID="$2"
PLAYER_NAME="$3"
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SERVER_DIR="${ROOT_DIR}/run/e2e-server-${VERSION}"
RESULT_DIR="${ROOT_DIR}/docs/e2e-results"
RESULT_FILE="${RESULT_DIR}/${VERSION}.md"

case "$VERSION" in
  1.20.1)
    JAVA_BIN="${JAVA_BIN:-/opt/homebrew/opt/openjdk@17/bin/java}"
    ;;
  1.21.1)
    JAVA_BIN="${JAVA_BIN:-/opt/homebrew/opt/openjdk@25/bin/java}"
    ;;
  *)
    echo "unsupported minecraft version: ${VERSION}" >&2
    exit 2
    ;;
esac

mkdir -p "$RESULT_DIR"

"${ROOT_DIR}/scripts/e2e/prepare_fabric_server.sh" "$VERSION" "$SERVER_DIR" "$PLAYER_UUID" "$PLAYER_NAME"

echo "Starting echo target on 127.0.0.1:10000"
"${ROOT_DIR}/scripts/e2e/echo_server.py" --host 127.0.0.1 --port 10000 \
  >"${SERVER_DIR}/echo_server.log" 2>&1 &
ECHO_PID="$!"

echo "Starting Fabric ${VERSION} server on 127.0.0.1:25565"
(
  cd "$SERVER_DIR"
  "$JAVA_BIN" -jar fabric-server-launch.jar nogui
) >"${SERVER_DIR}/server-e2e.log" 2>&1 &
SERVER_PID="$!"

cleanup() {
  if kill -0 "$SERVER_PID" 2>/dev/null; then
    kill "$SERVER_PID" 2>/dev/null || true
  fi
  if kill -0 "$ECHO_PID" 2>/dev/null; then
    kill "$ECHO_PID" 2>/dev/null || true
  fi
}
trap cleanup EXIT

echo "Waiting for server startup"
for _ in $(seq 1 120); do
  if rg -q 'Done \(' "${SERVER_DIR}/server-e2e.log" 2>/dev/null; then
    break
  fi
  if ! kill -0 "$SERVER_PID" 2>/dev/null; then
    echo "server exited before startup; see ${SERVER_DIR}/server-e2e.log" >&2
    exit 1
  fi
  sleep 1
done
rg -q 'Done \(' "${SERVER_DIR}/server-e2e.log"

cat <<EOF
Server is ready.

Now launch a real Fabric ${VERSION} client with:
- Fabric Loader 0.19.3
- matching Fabric API
- build/libs/mc-transport-dialer-${VERSION}-0.1.0.jar

Join 127.0.0.1:25565. This script will wait for the client local listener
127.0.0.1:25580 after the server pushes the route, then run TCP probes.
EOF

python3 - <<'PY'
import socket
import time

deadline = time.time() + 300
while time.time() < deadline:
    try:
        with socket.create_connection(("127.0.0.1", 25580), timeout=1):
            raise SystemExit(0)
    except OSError:
        time.sleep(1)
raise SystemExit("timed out waiting for 127.0.0.1:25580")
PY

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
  echo "Real client E2E evidence captured at \`$(date '+%Y-%m-%d %H:%M:%S %Z')\`:"
  echo
  echo "- Server command: \`${JAVA_BIN} -jar fabric-server-launch.jar nogui\`"
  echo "- Client joined: inspect \`${SERVER_DIR}/server-e2e.log\` and client latest.log"
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
