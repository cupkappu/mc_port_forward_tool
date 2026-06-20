#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
TMP_DIR="$(mktemp -d)"
TEST_REL="run/prepare-server-path-test-$$"
trap 'rm -rf "$TMP_DIR" "$ROOT_DIR/$TEST_REL"' EXIT

FAKE_BIN="${TMP_DIR}/bin"
mkdir -p "$FAKE_BIN"

cat >"${FAKE_BIN}/curl" <<'FAKE_CURL'
#!/usr/bin/env bash
set -euo pipefail
out=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    -o)
      out="$2"
      shift 2
      ;;
    *)
      shift
      ;;
  esac
done
if [[ -z "$out" ]]; then
  echo "fake curl expected -o" >&2
  exit 2
fi
mkdir -p "$(dirname "$out")"
printf 'fake jar\n' > "$out"
FAKE_CURL

cat >"${FAKE_BIN}/java" <<'FAKE_JAVA'
#!/usr/bin/env bash
set -euo pipefail
if [[ "${1:-}" != "-jar" || ! -f "${2:-}" ]]; then
  echo "fake java could not access jar: ${2:-}" >&2
  exit 1
fi
touch fabric-server-launch.jar
FAKE_JAVA

chmod +x "${FAKE_BIN}/curl" "${FAKE_BIN}/java"

(
  cd "$ROOT_DIR"
  PATH="${FAKE_BIN}:$PATH" \
    scripts/e2e/prepare_fabric_server.sh \
      1.20.1 \
      "$TEST_REL" \
      00000000-0000-0000-0000-000000000001 \
      TestPlayer >/dev/null
)

test -f "${ROOT_DIR}/${TEST_REL}/fabric-server-launch.jar"
grep -qx 'server-ip=127.0.0.1' "${ROOT_DIR}/${TEST_REL}/server.properties"
grep -qx 'server-port=25565' "${ROOT_DIR}/${TEST_REL}/server.properties"
grep -qx 'online-mode=false' "${ROOT_DIR}/${TEST_REL}/server.properties"
grep -qx '\[\[routes\]\]' "${ROOT_DIR}/${TEST_REL}/config/mctransport.server.toml"
grep -qx 'player_uuid = "00000000-0000-0000-0000-000000000001"' \
  "${ROOT_DIR}/${TEST_REL}/config/mctransport.server.toml"
grep -qx 'player_name = "TestPlayer"' \
  "${ROOT_DIR}/${TEST_REL}/config/mctransport.server.toml"
if find "${ROOT_DIR}/${TEST_REL}" -name mctransport.client.toml | rg .; then
  echo "prepare_fabric_server.sh must not create a client config" >&2
  exit 1
fi
