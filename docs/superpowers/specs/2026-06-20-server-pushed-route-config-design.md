# Server-Pushed Route Config Design

## Goal

Remove client-side manual configuration. Operators configure one route per
player from the dedicated server with commands. When that player joins, the
server pushes the route to the client, the client opens a local listener, and
traffic through that listener is tunneled over the Minecraft connection to the
server-side target.

## Scope

This MVP supports exactly one active route per player UUID. It targets the
existing Fabric 1.20.1 and 1.21.1 adapters and must preserve the current real
end-to-end testing flow for both versions.

In scope:

- One server-side config file.
- No client-side TOML file or client-side operator configuration.
- Server commands using player names.
- Persistent route storage keyed by UUID.
- Push route config on login/auth.
- Apply route immediately when a configured player is online.
- Clear route immediately when unset for an online player.
- Close client listener on disconnect.
- Route all stream opens for a player to that player's configured server-side
  target.

Out of scope for this MVP:

- Multiple routes per player.
- Multiple local ports per player.
- Client UI.
- Hot-reload from external file edits.
- Arbitrary client-selected targets.
- Cross-server trust prompts.
- Encryption with a manually shared PSK.

## Trust And Security Model

Client zero configuration means the client cannot pre-share a secret with the
server. The trust model changes from "client and server both know a PSK" to
"the user trusts the Minecraft server they joined while this mod is installed".

Consequences:

- The server must never send `target_host` or `target_port` to the client.
- The client only learns `listen_host` and `listen_port`.
- Client listeners are bound to loopback only in this MVP.
- The server decides the real target for each stream based on the player's UUID.
- Only server operators with permission level 4 may create, replace, list, or
  remove routes.
- When the player disconnects, the client closes the listener and all streams.

The current PSK-based encrypted AUTH is removed from the operational control
path. The Minecraft connection and Fabric networking context identify the
player on the server. Client-originated stream frames are accepted only after
the server has an online player session and an active route for that UUID.

## Server Commands

Commands are registered by each Fabric version adapter.

```text
/mctransport set <playerName> <listenPort> <targetHost> <targetPort>
/mctransport unset <playerName>
/mctransport list
```

Rules:

- `set` resolves `<playerName>` to a UUID.
- If the player is online, use the online player's UUID.
- If the player is offline, resolve through the server user cache when
  available. If no UUID can be resolved, fail with a clear error.
- `listenPort` must be `1..65535`.
- `targetPort` must be `1..65535`.
- `targetHost` must be nonblank.
- `listenHost` is fixed to `127.0.0.1` for this MVP.
- `set` replaces any existing route for the UUID.
- `unset` removes the route for the UUID.
- `list` shows UUID, last known player name, local listen endpoint, and
  server-side target endpoint.

When `set` is run for an online player, the server immediately pushes
`CONFIG_APPLY`. When `unset` is run for an online player, the server
immediately pushes `CONFIG_CLEAR`.

## Server Configuration

Only `config/mctransport.server.toml` remains required.

```toml
enabled = true
channel_name = "mctransport:main"

[[routes]]
player_uuid = "xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx"
player_name = "Steve"
listen_port = 25580
target_host = "127.0.0.1"
target_port = 10000
```

The parser/writer must preserve valid route data, but it does not need to
preserve comments or formatting after command writes. The command path is the
source of truth for updates in this MVP.

## Client Configuration

The client no longer reads or creates `config/mctransport.client.toml`.

Client runtime behavior:

- Register Fabric networking channel using default `mctransport:main`.
- Create the tunnel session and bridge.
- Do not open a TCP listener at startup.
- After joining a server, wait for server control frames.
- On `CONFIG_APPLY`, close any current listener, bind
  `127.0.0.1:<listenPort>`, and send `CONFIG_ACK`.
- On `CONFIG_CLEAR`, close listener and active streams, then send `CONFIG_ACK`.
- On disconnect, close listener, close active streams, clear session state, and
  shut down executors as today.

## Protocol

Add frame types:

```java
CONFIG_APPLY
CONFIG_CLEAR
CONFIG_ACK
```

Control frames use session id `0`, stream id `0`, and protocol version `1`.

`CONFIG_APPLY` payload is UTF-8 JSON:

```json
{
  "listenHost": "127.0.0.1",
  "listenPort": 25580
}
```

`CONFIG_CLEAR` payload is empty.

`CONFIG_ACK` payload is UTF-8 JSON:

```json
{
  "ok": true,
  "message": "listening on 127.0.0.1:25580"
}
```

or:

```json
{
  "ok": false,
  "message": "failed to bind 127.0.0.1:25580"
}
```

The payload codec should be a small internal encoder/decoder, not a new JSON
dependency.

## Session And Stream Flow

Join flow:

```text
client joins server
server creates player session from Fabric player context
server looks up route by UUID
if route exists: server sends CONFIG_APPLY
client starts local listener and replies CONFIG_ACK
server marks route active after successful ACK
```

Stream flow:

```text
local app connects to client listener
client allocates stream and sends OPEN
server receives OPEN from player session
server looks up active route by UUID
server dials route.target_host:route.target_port
DATA/CLOSE/RESET proceed as current implementation
```

If no active route exists when `OPEN` arrives, the server sends `ERROR` and
`RESET` for that stream.

## Version Adapter Requirements

Both Fabric versions need command registration and networking changes:

- Fabric 1.20.1 adapter registers `/mctransport`.
- Fabric 1.21.1 adapter registers `/mctransport`.
- Both adapters keep the same channel name and payload registration behavior
  required by their Minecraft/Fabric version.
- Build output remains one jar per Minecraft version, each containing both
  client and server entrypoints.

## Testing Requirements

Unit tests:

- Route model validation.
- Route config parsing.
- Route config writing and re-reading.
- Command input validation where pure code can cover it.
- `CONFIG_APPLY` / `CONFIG_ACK` payload round trip.
- Client session applies config before accepting local streams.
- Client session clears config and closes listener.
- Server session sends config for configured UUID.
- Server stream target selection uses the player's route.

Matrix tests:

- `scripts/test_matrix.sh` must pass for 1.20.1 and 1.21.1.

Real E2E:

- Fabric 1.20.1 real server + Loom client.
- Fabric 1.21.1 real server + Loom client.
- The E2E setup must create server-side route config only.
- The client must not receive or copy `mctransport.client.toml`.
- The client local listener must open only after server `CONFIG_APPLY`.
- Single and 4-way concurrent TCP probes must pass.

## Migration Notes

The existing client config file may remain on disk for users who tested older
versions, but the new client code ignores it. Existing server config with the
old global `target_host`, `target_port`, `allowed_players`, and `psk` keys is
not considered valid for the new command-driven route mode. Operators should
run `/mctransport set ...` to create route entries.
