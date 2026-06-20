# Multi Route Port Identity — Design Spec

Date: 2026-06-20

## Summary

Allow one Minecraft player to own multiple independent logical tunnels. A
route is no longer unique by player UUID alone. The unique identity is:

```text
(player_uuid, listen_port)
```

Each route can choose its own client listen port, server target host, server
target port, and stream mode. The existing frame `sessionId` field carries the
route identity on the wire by using the route's `listen_port` as the session
id.

## Goals

- Support multiple routes for the same `player_uuid` when their
  `listen_port` values differ.
- Reject duplicate routes with the same `player_uuid` and `listen_port`.
- Keep each logical tunnel isolated: listener state, stream ids, stream
  registry, target connection, and KCP state do not cross route boundaries.
- Preserve the existing external behavior: the client listens on loopback, the
  server dials the route target, and data still flows through the Minecraft
  networking channel.
- Keep `DIRECT` as the default mode; allow `KCP` per route.

## Non-Goals

- Do not add a separate user-visible route id.
- Do not allow non-loopback client listen hosts.
- Do not change the binary frame format.
- Do not merge multiple route configs into one control payload.

## Route Identity

`RouteConfig` remains the immutable route value and adds a route-key concept
based on:

```text
playerUuid + listenPort
```

The listen port is already validated as `1..65535`, so it is safe to use as a
positive frame `sessionId`.

`ServerConfig` changes from indexing routes by `UUID` to indexing routes by
the composite route key. It exposes new route lookup APIs:

- `routesFor(UUID uuid)`: all routes configured for a player.
- `routeFor(UUID uuid, int listenPort)`: one route for a player and port.
- `withRoute(RouteConfig route)`: add or replace only that route key.
- `withoutRoute(UUID uuid, int listenPort)`: remove only that route key.

Any old `routeFor(UUID)` helper must not be used by new runtime logic because
one UUID can now map to multiple routes.

## Config File Behavior

The TOML shape remains a repeated `[[routes]]` table:

```toml
[[routes]]
player_uuid = "11111111-2222-3333-4444-555555555555"
player_name = "Steve"
listen_port = 25580
target_host = "127.0.0.1"
target_port = 10000
stream_mode = "DIRECT"

[[routes]]
player_uuid = "11111111-2222-3333-4444-555555555555"
player_name = "Steve"
listen_port = 25581
target_host = "10.0.0.5"
target_port = 25565
stream_mode = "KCP"
```

Loading accepts the same UUID with different listen ports. Loading rejects the
same UUID with the same listen port twice. Writing preserves every route as a
separate `[[routes]]` entry.

## Command Behavior

`set` keeps the current command shape:

```text
/mctransport set <playerName> <listenPort> <targetHost> <targetPort> [mode]
```

For the same player, setting an existing `listenPort` replaces that one route.
Setting a different `listenPort` adds another route.

`unset` must remove one route by player and listen port:

```text
/mctransport unset <playerName> <listenPort>
```

This avoids accidentally clearing all routes for a player. If a future command
wants to clear all routes for a player, it should be explicit.

`list` prints each route independently, including listen port and mode.

## Wire Protocol

The binary frame format already contains `sessionId`. This feature uses:

```text
frame.sessionId == route.listenPort
```

All control and stream frames for a logical tunnel use the same session id:

- `CONFIG_APPLY`
- `CONFIG_ACK`
- `CONFIG_CLEAR`
- `OPEN`
- `DATA`
- `CLOSE`
- `RESET`
- `ERROR`
- `PING`
- `PONG`

Frames with an unknown session id are ignored or reset according to the
existing frame type semantics. Frames whose session id does not match a server
session's bound route are treated as protocol errors.

## Server Runtime

The Fabric server bridge currently has one `PlayerTunnelSession` per player.
It changes to one session per player route:

```text
player UUID -> listen port/session id -> PlayerTunnelSession
```

On player join:

1. Load all routes for the player's UUID from `RouteStore`.
2. Create one `PlayerTunnelSession` per route.
3. Send one `CONFIG_APPLY` per session, with `sessionId = listenPort`.

On inbound frame:

1. Decode the frame.
2. Look up `sessionsByPlayer[playerUuid][frame.sessionId]`.
3. Dispatch only to that session.
4. Unknown session ids are rejected without affecting other sessions.

`PlayerTunnelSession` is bound to one `RouteConfig` at construction time. It no
longer asks the store for "the player's route" when sending config. Updating a
route for an online player replaces or refreshes only that route's session.

## Client Runtime

The Fabric client entrypoint changes from one `ClientTunnelSession` to a small
session manager keyed by `sessionId`:

```text
session id/listen port -> ClientTunnelSession
```

When a `CONFIG_APPLY` arrives for a new session id:

1. Create a new `ClientTunnelSession` with that session id.
2. Create a dedicated `DynamicLocalTcpListenerController`.
3. Apply the pushed listen port and stream mode.
4. Send `CONFIG_ACK` with the same session id.

When stream/control frames arrive after apply, the client dispatches by
`frame.sessionId()`.

When `CONFIG_CLEAR` arrives, only that session's listener, registry, streams,
and KCP state are closed. Player disconnect still closes all sessions.

`ClientTunnelSession`, `DirectClientStream`, and `KcpClientStream` stop using a
static `SESSION_ID = 0` for outbound frames. They use the instance session id
assigned at construction.

## Stream Modes

The mode remains per route.

`DIRECT` is the default mode. Socket reads are sent directly as `DATA` frames.
It has the least internal state and preserves current behavior.

`KCP` keeps the same external TCP-to-Minecraft-channel-to-TCP shape, but socket
data flows through the KCP stream-mode layer before becoming `DATA` frames. It
can coalesce small writes, split larger writes around MSS, and apply KCP window
backpressure. KCP state is scoped to one stream inside one session, so two
ports never share KCP state.

## Error Handling

- Duplicate `(player_uuid, listen_port)` entries are rejected during config
  construction and config load.
- `unset <player> <listenPort>` reports whether that route existed.
- A failed client bind sends `CONFIG_ACK(ok=false)` for that session only.
- A failed route apply does not clear or deactivate other routes for the same
  player.
- Unknown inbound session ids are logged and ignored or reset without tearing
  down the player's other sessions.

## Testing

Add or update tests before implementation:

- `ServerConfig` accepts same UUID with different listen ports.
- `ServerConfig` rejects same UUID with same listen port.
- `withRoute` replaces only the matching `(uuid, listenPort)` route.
- `withoutRoute` removes only the matching `(uuid, listenPort)` route.
- `RouteStore` persists and reloads multiple routes for one UUID.
- `RouteCommandService.setRoute` adds a second port for a player.
- `RouteCommandService.unsetRoute` removes only the requested listen port.
- `PlayerTunnelSession` sends frames using its bound route listen port as
  `sessionId`.
- Server bridge dispatches inbound frames by player UUID and `sessionId`.
- Client session manager creates or dispatches sessions by `sessionId`.
- `DirectClientStream` and `KcpClientStream` outbound frames preserve the
  parent session id.
- Existing DIRECT and KCP integration tests still pass for one route.
