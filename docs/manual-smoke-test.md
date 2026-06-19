# Minecraft Transport Dialer - Fabric E2E Smoke Test

This smoke test verifies the real transport path:

`tcp_probe.py -> client 127.0.0.1:25580 -> Minecraft client -> Fabric server -> server 127.0.0.1:10000 -> echo_server.py`

Run it separately for Minecraft `1.20.1` and `1.21.1`.

## Prerequisites

- Real Minecraft Java Edition client for the target version.
- Fabric Loader `0.19.3` on both client and server.
- Fabric API:
  - `0.92.9+1.20.1` for Minecraft `1.20.1`
  - `0.116.12+1.21.1` for Minecraft `1.21.1`
- Java 17 for `1.20.1`; Java 21 for `1.21.1`.
- The test player's UUID.

## Build Matrix

```
scripts/test_matrix.sh
```

The mod jars are:

- `build/libs/mc-transport-dialer-1.20.1-0.1.0.jar`
- `build/libs/mc-transport-dialer-1.21.1-0.1.0.jar`

## Server Setup

```
scripts/e2e/prepare_fabric_server.sh 1.20.1 run/e2e-server-1.20.1 <player-uuid> <shared-psk>
scripts/e2e/prepare_fabric_server.sh 1.21.1 run/e2e-server-1.21.1 <player-uuid> <shared-psk>
```

For a guided real E2E run, use:

```
scripts/e2e/run_real_e2e.sh 1.20.1 <player-uuid> <shared-psk>
scripts/e2e/run_real_e2e.sh 1.21.1 <player-uuid> <shared-psk>
```

The script starts the server and echo target, waits for the real client local
listener on `127.0.0.1:25580`, runs single and concurrent probes, and appends
the evidence to `docs/e2e-results/<version>.md`.

Start the echo target:

```
scripts/e2e/echo_server.py --host 127.0.0.1 --port 10000
```

Start the selected server:

```
cd run/e2e-server-1.20.1
/opt/homebrew/opt/openjdk@17/bin/java -jar fabric-server-launch.jar nogui
```

Use Java 17 for Minecraft `1.20.1` and Java 21+ for Minecraft `1.21.1`.
On macOS, `/usr/bin/java` may resolve to an old Java 8 plugin runtime, so
prefer an explicit JDK path.

For `1.21.1` on this machine:

```
cd run/e2e-server-1.21.1
/opt/homebrew/opt/openjdk/bin/java -jar fabric-server-launch.jar nogui
```

## Client Setup

Install the matching Minecraft version, Fabric Loader, Fabric API, and matching
`mc-transport-dialer-<version>-0.1.0.jar` into the client `mods/` directory.

Write the client config with the same PSK:

```
scripts/e2e/write_configs.sh run/e2e-server-1.20.1/config <client-config-dir> <player-uuid> <shared-psk>
```

Then launch the real Fabric client and join the real Fabric server.

## Probe

After the client joins and the log shows `local listener bound to 127.0.0.1:25580`,
run:

```
scripts/e2e/tcp_probe.py --host 127.0.0.1 --port 25580 --bytes 4096
scripts/e2e/tcp_probe.py --host 127.0.0.1 --port 25580 --connections 4 --bytes 16384
```

## Pass Criteria

- Server log contains `player <uuid> joined; tunnel session ready`.
- Client log contains `client joined; sending AUTH for <uuid>`.
- `tcp_probe.py` exits 0 for single and concurrent probes.
- Disconnecting the client logs `player disconnected; tunnel session torn down`.
