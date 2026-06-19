#!/usr/bin/env bash
set -euo pipefail

if [[ $# -lt 4 ]]; then
  echo "usage: $0 <server-config-dir> <client-config-dir> <player-uuid> <psk> [channel]" >&2
  exit 2
fi

SERVER_CONFIG_DIR="$1"
CLIENT_CONFIG_DIR="$2"
PLAYER_UUID="$3"
PSK="$4"
CHANNEL="${5:-mctransport:main}"

mkdir -p "$SERVER_CONFIG_DIR" "$CLIENT_CONFIG_DIR"

cat >"${SERVER_CONFIG_DIR}/mctransport.server.toml" <<EOF
enabled = true
target_host = "127.0.0.1"
target_port = 10000
channel_name = "${CHANNEL}"
psk = "${PSK}"
allowed_players = ["${PLAYER_UUID}"]
max_streams_per_player = 64
stream_buffer_size = 1048576
global_buffer_size_per_player = 33554432
idle_timeout_seconds = 300
connect_timeout_seconds = 10
log_level = "info"
EOF

cat >"${CLIENT_CONFIG_DIR}/mctransport.client.toml" <<EOF
enabled = true
listen_host = "127.0.0.1"
listen_port = 25580
channel_name = "${CHANNEL}"
psk = "${PSK}"
max_streams = 64
stream_buffer_size = 1048576
global_buffer_size = 33554432
log_level = "info"
EOF

echo "wrote ${SERVER_CONFIG_DIR}/mctransport.server.toml"
echo "wrote ${CLIENT_CONFIG_DIR}/mctransport.client.toml"

