#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

export GRADLE_OPTS="${GRADLE_OPTS:-} -Dorg.gradle.native=false"

for version in 1.20.1 1.21.1; do
  echo "==> test ${version}"
  ./gradlew test -PtargetMinecraft="${version}"

  echo "==> build ${version}"
  ./gradlew build -PtargetMinecraft="${version}"
done

echo "==> script syntax"
bash -n \
  scripts/test_matrix.sh \
  scripts/e2e/write_configs.sh \
  scripts/e2e/prepare_fabric_server.sh \
  scripts/e2e/prepare_fabric_server_test.sh
python3 -c 'import ast, pathlib; [ast.parse(pathlib.Path(p).read_text(), filename=p) for p in ["scripts/e2e/echo_server.py", "scripts/e2e/tcp_probe.py"]]'

echo "==> prepare_fabric_server path handling"
scripts/e2e/prepare_fabric_server_test.sh
