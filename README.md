# MC Transport Dialer

A minimal Minecraft transport layer that ferries local TCP byte streams
through a real Minecraft client-server connection to a fixed server-side
target.

## What this is

The MC Transport Dialer is **not** a proxy. It is a Minecraft-side transport
adapter. It does not parse SOCKS5, HTTP CONNECT, TUN, DNS, or routing.
It does not let the server mod dial arbitrary internet destinations.

What it does:

1. Listen on a local TCP port on the Minecraft client side.
2. Map each accepted TCP connection to a Minecraft channel stream ID.
3. Encrypt and frame the bytes; carry them across the real Minecraft
   client-server connection using Fabric's CustomPayload API.
4. On the server side, dial one fixed TCP target (host and port from the
   server config) for each stream; do nothing else.
5. Reverse the mapping so bytes round-trip end-to-end.

## What this is not

- Not a SOCKS5 server.
- Not an HTTP CONNECT proxy.
- Not a TUN-based transparent proxy.
- Not a DNS resolver or router.
- Not a Minecraft protocol simulator. Real Minecraft client + server +
  Fabric Loader are required.
- Not a way to dial arbitrary remote hosts from the server mod. The server
  side connects only to the configured target.

## External Chain

The intended end-to-end chain:

```
client app
  → external client proxy tool (Xray / sing-box / gost / frp / socat)
  → 127.0.0.1:25580 (client mod local intake)
  → Minecraft client ↔ Minecraft server (real connection, modded channel)
  → 127.0.0.1:10000 (server mod fixed exit)
  → external server proxy tool
  → final destination
```

The proxy protocol (SOCKS5, HTTP, TUN, ...) is the responsibility of the
external tools. The Minecraft mod only carries bytes.

## MVP Features

- Fabric client mod + server mod in a single jar
- Local TCP listener on the client side
- Fixed-target TCP dial on the server side
- AES-256-GCM encryption with PSK-derived key
- Player UUID whitelist + PSK auth
- Multiplexed stream channels (default 64 concurrent per player)
- Per-stream and global buffer limits
- Ping/pong liveness check with idle timeout
- Clean close, hard reset, and stream lifecycle
- Thread-isolated blocking I/O via dedicated executors
- Thread-isolated crypto work; never on Minecraft render / server-tick /
  Netty event-loop threads

## Non-Goals

- SOCKS5
- HTTP CONNECT
- UDP / datagram transport
- TUN
- DNS resolution or routing
- Server-side arbitrary target dialing
- GUI
- Multi-server fallback
- Session resumption after disconnect
- Advanced obfuscation
- Replacing a real Minecraft client
- Simulating the Minecraft protocol

## Configuration

The client has no TOML configuration. It waits until the server pushes a route,
then opens the local loopback listener requested by that server.

The dedicated server owns `config/mctransport.server.toml`. Operators configure
one route per player with commands:

```
/mctransport set <playerName> <listenPort> <targetHost> <targetPort>
/mctransport unset <playerName>
/mctransport list
```

Routes are persisted by player UUID. `set` and `unset` apply immediately for an
online player. Direct edits to `mctransport.server.toml` are not hot-loaded in
this MVP; restart the server or use commands.

## Build

```
./gradlew build
```

Output: `build/libs/mc-transport-dialer-0.1.0.jar`

## Test

```
./gradlew test
```

## Manual Smoke Test

See [docs/manual-smoke-test.md](docs/manual-smoke-test.md) for a step-by-step
guide to installing into real Minecraft.

## Acceptance Checklist

See [docs/mvp-acceptance-checklist.md](docs/mvp-acceptance-checklist.md) for
the PRD Section 13 pass/fail evidence rows.
