#!/usr/bin/env bash
set -euo pipefail

if [[ $# -lt 6 ]]; then
  echo "usage: $0 <server-config-dir> <player-uuid> <player-name> <listen-port> <target-host> <target-port> [channel]" >&2
  exit 2
fi

SERVER_CONFIG_DIR="$1"
PLAYER_UUID="$2"
PLAYER_NAME="$3"
LISTEN_PORT="$4"
TARGET_HOST="$5"
TARGET_PORT="$6"
CHANNEL="${7:-mctransport:main}"

mkdir -p "$SERVER_CONFIG_DIR"

cat >"${SERVER_CONFIG_DIR}/mctransport.server.toml" <<EOF
enabled = true
channel_name = "${CHANNEL}"
max_streams_per_player = 64
stream_buffer_size = 1048576
global_buffer_size_per_player = 33554432
idle_timeout_seconds = 300
connect_timeout_seconds = 10
log_level = "info"

[[routes]]
player_uuid = "${PLAYER_UUID}"
player_name = "${PLAYER_NAME}"
listen_port = ${LISTEN_PORT}
target_host = "${TARGET_HOST}"
target_port = ${TARGET_PORT}
EOF

echo "wrote ${SERVER_CONFIG_DIR}/mctransport.server.toml"
