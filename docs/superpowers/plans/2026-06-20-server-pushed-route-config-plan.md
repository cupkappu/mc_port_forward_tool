# Server-Pushed Route Config Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace client-side manual config with server-pushed single-player single-route configuration, configured through dedicated-server commands and verified on Fabric 1.20.1 and 1.21.1.

**Architecture:** The server owns all route state, stores routes keyed by player UUID, and pushes loopback listener settings to clients after join. The client has no TOML config, does not open a listener at startup, and only opens/closes its listener in response to server control frames. Stream targets are resolved server-side from the active player's route.

**Tech Stack:** Java 17/21, Fabric API command/networking events, Fabric Loom multi-version source sets, JUnit 5, existing E2E scripts.

---

## Ground Rules For The Implementing Agent

- Before coding, read `docs/superpowers/specs/2026-06-20-server-pushed-route-config-design.md`.
- Use your pi-goal feature to create a goal named `server-pushed-route-config`.
- Keep commits small. Commit after each task that passes its verification.
- Do not push to GitHub unless explicitly asked.
- Do not remove existing E2E scripts or version matrix support.
- Do not re-add ignored `run/`, `build/`, `.gradle/`, or `__pycache__/` files.
- If a task fails, stop and debug the root cause before continuing.
- After all tasks pass, notify tmux window `10:2` with a concise completion message:

```bash
tmux send-keys -t 10:2 'Server-pushed route config implementation finished; ready for review. Matrix and real E2E results are in docs/e2e-results/.' C-m
```

---

## Task 1: Add Route Model

**Files:**
- Create: `src/main/java/dev/kifuko/mctransport/config/RouteConfig.java`
- Create: `src/test/java/dev/kifuko/mctransport/config/RouteConfigTest.java`

- [ ] **Step 1: Write tests**

Create tests covering:

Required assertions:

- `routeRejectsBlankPlayerName`: construct with `" "` and assert
  `IllegalArgumentException`.
- `routeRejectsInvalidListenPort`: construct with `0` and `65536` and assert
  `IllegalArgumentException`.
- `routeRejectsInvalidTargetPort`: construct with `0` and `65536` and assert
  `IllegalArgumentException`.
- `routeNormalizesListenHostToLoopback`: construct a route and assert
  `getListenHost().equals("127.0.0.1")`.
- `routeStoresUuidNameAndTarget`: construct with UUID, `" Steve "`,
  listen port `25580`, target host `" 127.0.0.1 "`, target port `10000`, then
  assert UUID, trimmed name, loopback listen host, listen port, trimmed target
  host, and target port.

Expected route constructor shape:

```java
new RouteConfig(UUID playerUuid, String playerName, int listenPort,
        String targetHost, int targetPort)
```

Expected getters:

```java
getPlayerUuid()
getPlayerName()
getListenHost()
getListenPort()
getTargetHost()
getTargetPort()
```

Run:

```bash
./gradlew -PtargetMinecraft=1.21.1 test --tests dev.kifuko.mctransport.config.RouteConfigTest
```

Expected: compile fails because `RouteConfig` does not exist.

- [ ] **Step 2: Implement `RouteConfig`**

Rules:

- `playerUuid` must not be null.
- `playerName` must be nonblank after trim.
- `listenHost` is always `"127.0.0.1"`.
- `listenPort` and `targetPort` must be `1..65535`.
- `targetHost` must be nonblank after trim.
- Store trimmed strings.

- [ ] **Step 3: Verify**

Run:

```bash
./gradlew -PtargetMinecraft=1.21.1 test --tests dev.kifuko.mctransport.config.RouteConfigTest
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/dev/kifuko/mctransport/config/RouteConfig.java src/test/java/dev/kifuko/mctransport/config/RouteConfigTest.java
git commit -m "feat: add route config model"
```

---

## Task 2: Replace Server Config Shape With Route List

**Files:**
- Modify: `src/main/java/dev/kifuko/mctransport/config/ServerConfig.java`
- Modify: `src/main/resources/mctransport.server.toml`
- Modify: `src/test/java/dev/kifuko/mctransport/config/ConfigValidationTest.java`

- [ ] **Step 1: Add failing validation tests**

Update server config tests to expect this constructor shape:

```java
new ServerConfig(true, "mctransport:main", List.of(route), "info")
```

Expected getters:

```java
isEnabled()
getChannelName()
getRoutes()
getLogLevel()
routeFor(UUID uuid)
```

Add tests:

- empty route list is valid
- duplicate UUID routes are rejected
- `routeFor(uuid)` returns the configured route
- returned route list is immutable

Run:

```bash
./gradlew -PtargetMinecraft=1.21.1 test --tests dev.kifuko.mctransport.config.ConfigValidationTest
```

Expected: compile fails until `ServerConfig` changes.

- [ ] **Step 2: Update `ServerConfig`**

Remove global target/allowed-player fields from the server config API:

- remove `targetHost`
- remove `targetPort`
- remove `allowedPlayers`
- keep `maxStreamsPerPlayer` with default `64`
- keep `streamBufferSize`, `globalBufferSizePerPlayer`, `idleTimeoutSeconds`, and `connectTimeoutSeconds`

If keeping operational limits, use this constructor:

```java
ServerConfig(boolean enabled,
        String channelName,
        List<RouteConfig> routes,
        int maxStreamsPerPlayer,
        int streamBufferSize,
        long globalBufferSizePerPlayer,
        int idleTimeoutSeconds,
        int connectTimeoutSeconds,
        String logLevel)
```

Use defaults in tests:

```java
64, 1048576, 33554432L, 300, 10, "info"
```

- [ ] **Step 3: Update bundled server TOML**

Use:

```toml
enabled = true
channel_name = "mctransport:main"

max_streams_per_player = 64
stream_buffer_size = 1048576
global_buffer_size_per_player = 33554432
idle_timeout_seconds = 300
connect_timeout_seconds = 10
log_level = "info"

[[routes]]
player_uuid = "00000000-0000-0000-0000-000000000000"
player_name = "ExamplePlayer"
listen_port = 25580
target_host = "127.0.0.1"
target_port = 10000
```

- [ ] **Step 4: Verify and commit**

Run:

```bash
./gradlew -PtargetMinecraft=1.21.1 test --tests dev.kifuko.mctransport.config.ConfigValidationTest
```

Expected: `BUILD SUCCESSFUL`.

Commit:

```bash
git add src/main/java/dev/kifuko/mctransport/config/ServerConfig.java src/main/resources/mctransport.server.toml src/test/java/dev/kifuko/mctransport/config/ConfigValidationTest.java
git commit -m "feat: model server routes"
```

---

## Task 3: Parse And Write Route TOML

**Files:**
- Modify: `src/main/java/dev/kifuko/mctransport/config/ConfigLoader.java`
- Modify: `src/test/java/dev/kifuko/mctransport/config/ConfigLoaderTest.java`

- [ ] **Step 1: Write failing parser/writer tests**

Add tests:

- `loadServerReadsRouteEntries`
- `loadServerAllowsNoRoutes`
- `writeServerPersistsRoutesAndReloads`

Use test TOML:

```toml
enabled = true
channel_name = "mctransport:main"
max_streams_per_player = 64
stream_buffer_size = 1048576
global_buffer_size_per_player = 33554432
idle_timeout_seconds = 300
connect_timeout_seconds = 10
log_level = "info"

[[routes]]
player_uuid = "11111111-2222-3333-4444-555555555555"
player_name = "Steve"
listen_port = 25580
target_host = "127.0.0.1"
target_port = 10000
```

Run:

```bash
./gradlew -PtargetMinecraft=1.21.1 test --tests dev.kifuko.mctransport.config.ConfigLoaderTest
```

Expected: failure because `[[routes]]` is unsupported.

- [ ] **Step 2: Extend parser**

Extend the hand-written parser to support repeated `[[routes]]` tables in
server config only. Keep the existing flat key parsing for client tests until
client config tests are removed in a later task.

Required internal behavior:

- collect root keys before route tables
- start a new route map on each `[[routes]]`
- parse route keys with existing scalar parser
- reject keys before malformed table names
- reject duplicate keys inside one route table

- [ ] **Step 3: Add writer**

Add:

```java
public static void writeServer(Path configDir, String filename, ServerConfig config)
```

The writer may rewrite the whole file with canonical formatting.

- [ ] **Step 4: Verify and commit**

Run:

```bash
./gradlew -PtargetMinecraft=1.21.1 test --tests dev.kifuko.mctransport.config.ConfigLoaderTest
```

Expected: `BUILD SUCCESSFUL`.

Commit:

```bash
git add src/main/java/dev/kifuko/mctransport/config/ConfigLoader.java src/test/java/dev/kifuko/mctransport/config/ConfigLoaderTest.java
git commit -m "feat: parse server route config"
```

---

## Task 4: Add Route Store Service

**Files:**
- Create: `src/main/java/dev/kifuko/mctransport/server/RouteStore.java`
- Create: `src/test/java/dev/kifuko/mctransport/server/RouteStoreTest.java`

- [ ] **Step 1: Write tests**

Required API:

```java
public final class RouteStore {
    public RouteStore(Path configDir, String filename, ServerConfig initialConfig)
    public synchronized ServerConfig config()
    public synchronized RouteConfig routeFor(UUID uuid)
    public synchronized void setRoute(RouteConfig route)
    public synchronized boolean removeRoute(UUID uuid)
    public synchronized List<RouteConfig> routes()
}
```

Tests:

- `setRouteReplacesExistingUuid`
- `removeRouteReturnsFalseWhenMissing`
- `setRouteWritesConfigFile`
- `routesReturnsImmutableSnapshot`

Run:

```bash
./gradlew -PtargetMinecraft=1.21.1 test --tests dev.kifuko.mctransport.server.RouteStoreTest
```

Expected: compile fails until service exists.

- [ ] **Step 2: Implement**

Use `ConfigLoader.writeServer(...)` for persistence. Preserve non-route server
settings from the current `ServerConfig`.

- [ ] **Step 3: Verify and commit**

Run:

```bash
./gradlew -PtargetMinecraft=1.21.1 test --tests dev.kifuko.mctransport.server.RouteStoreTest
```

Expected: `BUILD SUCCESSFUL`.

Commit:

```bash
git add src/main/java/dev/kifuko/mctransport/server/RouteStore.java src/test/java/dev/kifuko/mctransport/server/RouteStoreTest.java
git commit -m "feat: add persistent route store"
```

---

## Task 5: Add Control Payload Codec And Frame Types

**Files:**
- Modify: `src/main/java/dev/kifuko/mctransport/protocol/FrameType.java`
- Create: `src/main/java/dev/kifuko/mctransport/protocol/RouteControlPayload.java`
- Create: `src/test/java/dev/kifuko/mctransport/protocol/RouteControlPayloadTest.java`
- Modify: `src/test/java/dev/kifuko/mctransport/protocol/FrameTypeTest.java`

- [ ] **Step 1: Write tests**

Add frame ids:

```java
CONFIG_APPLY(10),
CONFIG_CLEAR(11),
CONFIG_ACK(12)
```

Add payload tests:

- apply payload round trip with `127.0.0.1` and `25580`
- ack success round trip
- ack failure round trip
- reject non-loopback listen host
- reject invalid port

Run:

```bash
./gradlew -PtargetMinecraft=1.21.1 test --tests dev.kifuko.mctransport.protocol.RouteControlPayloadTest --tests dev.kifuko.mctransport.protocol.FrameTypeTest
```

Expected: failure because types/codecs do not exist.

- [ ] **Step 2: Implement**

Use UTF-8 JSON-like strings without adding a dependency. Required public API:

```java
RouteControlPayload.encodeApply(String listenHost, int listenPort)
RouteControlPayload.decodeApply(byte[] payload)
RouteControlPayload.encodeAck(boolean ok, String message)
RouteControlPayload.decodeAck(byte[] payload)
```

Nested values:

```java
record Apply(String listenHost, int listenPort) {}
record Ack(boolean ok, String message) {}
```

- [ ] **Step 3: Verify and commit**

Run the same targeted Gradle command. Expected: `BUILD SUCCESSFUL`.

Commit:

```bash
git add src/main/java/dev/kifuko/mctransport/protocol/FrameType.java src/main/java/dev/kifuko/mctransport/protocol/RouteControlPayload.java src/test/java/dev/kifuko/mctransport/protocol/RouteControlPayloadTest.java src/test/java/dev/kifuko/mctransport/protocol/FrameTypeTest.java
git commit -m "feat: add route control frames"
```

---

## Task 6: Refactor Client Session To Apply Server Config

**Files:**
- Modify: `src/main/java/dev/kifuko/mctransport/client/ClientTunnelSession.java`
- Modify: `src/main/java/dev/kifuko/mctransport/client/LocalTcpListener.java`
- Modify: `src/test/java/dev/kifuko/mctransport/client/ClientTunnelSessionTest.java`
- Add: `src/test/java/dev/kifuko/mctransport/client/ClientRouteApplyTest.java`

- [ ] **Step 1: Write failing tests**

Required session behavior:

- before `CONFIG_APPLY`, `openLocalStream()` fails with `IllegalStateException`
- `handleInbound(CONFIG_APPLY)` calls a listener controller and sends `CONFIG_ACK ok=true`
- `handleInbound(CONFIG_CLEAR)` closes listener, closes streams, and sends `CONFIG_ACK ok=true`
- failed bind sends `CONFIG_ACK ok=false`

Introduce this production interface so `ClientTunnelSession` can be tested
without opening real sockets:

```java
public interface ClientListenerController {
    void apply(String listenHost, int listenPort) throws IOException;
    void clear();
    boolean isListening();
}
```

Run:

```bash
./gradlew -PtargetMinecraft=1.21.1 test --tests dev.kifuko.mctransport.client.ClientRouteApplyTest
```

Expected: compile fails until client session supports route control frames.

- [ ] **Step 2: Implement**

Change session auth semantics:

- `AUTH_OK` may be removed or kept as an internal compatibility state, but stream opening must depend on route-applied state.
- `CONFIG_APPLY` is accepted as the server authorization to listen.
- `CONFIG_CLEAR` clears route-applied state.
- `PING`, `PONG`, `DATA`, `CLOSE`, `RESET`, `ERROR` remain stream/control behavior after route apply.

- [ ] **Step 3: Verify and commit**

Run:

```bash
./gradlew -PtargetMinecraft=1.21.1 test --tests dev.kifuko.mctransport.client.ClientRouteApplyTest --tests dev.kifuko.mctransport.client.ClientTunnelSessionTest
```

Expected: `BUILD SUCCESSFUL`.

Commit:

```bash
git add src/main/java/dev/kifuko/mctransport/client src/test/java/dev/kifuko/mctransport/client
git commit -m "feat: apply server pushed route on client"
```

---

## Task 7: Remove Client TOML Dependency From Fabric Client Entrypoints

**Files:**
- Modify: `src/fabric1201/client/java/dev/kifuko/mctransport/client/McTransportClient.java`
- Modify: `src/fabric1211/client/java/dev/kifuko/mctransport/client/McTransportClient.java`
- Modify: `src/test/java/dev/kifuko/mctransport/client/ClientJoinAuthWiringTest.java`
- Delete or stop using: `src/main/resources/mctransport.client.toml`

- [ ] **Step 1: Write/update tests**

Add a test proving quick-join target parsing still works if E2E quick join is
kept. Remove tests that assert client config TOML is loaded.

Run:

```bash
./gradlew -PtargetMinecraft=1.21.1 test --tests dev.kifuko.mctransport.client.ClientJoinAuthWiringTest
```

Expected: existing code still compiles before removal; after updating tests,
production code must be changed.

- [ ] **Step 2: Update client entrypoints**

For both versions:

- do not call `ConfigLoader.loadClient`
- do not create `LocalTcpListener` at startup
- create `TransportExecutors`, `FrameCodec`, `StreamRegistry`, bridge, and session with default channel `mctransport:main`
- inject a real listener controller into `ClientTunnelSession`
- keep disconnect cleanup
- keep E2E quick join support

- [ ] **Step 3: Verify both source sets**

Run:

```bash
./gradlew -PtargetMinecraft=1.20.1 test --tests dev.kifuko.mctransport.client.ClientJoinAuthWiringTest
./gradlew -PtargetMinecraft=1.21.1 test --tests dev.kifuko.mctransport.client.ClientJoinAuthWiringTest
```

Expected: both `BUILD SUCCESSFUL`.

Commit:

```bash
git add src/fabric1201/client/java/dev/kifuko/mctransport/client/McTransportClient.java src/fabric1211/client/java/dev/kifuko/mctransport/client/McTransportClient.java src/test/java/dev/kifuko/mctransport/client/ClientJoinAuthWiringTest.java src/main/resources
git commit -m "feat: remove client side config file"
```

---

## Task 8: Refactor Server Session To Use Player Route

**Files:**
- Modify: `src/main/java/dev/kifuko/mctransport/server/PlayerTunnelSession.java`
- Modify: `src/main/java/dev/kifuko/mctransport/server/DefaultServerStreamFactory.java`
- Modify: `src/main/java/dev/kifuko/mctransport/server/TargetTcpConnector.java`
- Modify: `src/test/java/dev/kifuko/mctransport/server/PlayerTunnelSessionAuthTest.java`
- Add: `src/test/java/dev/kifuko/mctransport/server/PlayerRouteSessionTest.java`

- [ ] **Step 1: Write failing tests**

Required server behavior:

- session created for a player UUID with a matching route sends `CONFIG_APPLY`
- session created with no route does not allow `OPEN`
- `CONFIG_ACK ok=true` marks route active
- `OPEN` dials route target
- after route removal/clear, `OPEN` fails

Run:

```bash
./gradlew -PtargetMinecraft=1.21.1 test --tests dev.kifuko.mctransport.server.PlayerRouteSessionTest
```

Expected: compile fails until server route session behavior exists.

- [ ] **Step 2: Implement**

Remove PSK AUTH as the source of authorization. The server session should be
constructed with:

```java
UUID playerUuid
String playerName
RouteStore routeStore
```

Server session should send `CONFIG_APPLY` when `routeStore.routeFor(playerUuid)`
returns a route. It should store the active route only after successful
`CONFIG_ACK`.

Stream factory must dial `activeRoute.targetHost:activeRoute.targetPort`.

- [ ] **Step 3: Verify and commit**

Run:

```bash
./gradlew -PtargetMinecraft=1.21.1 test --tests dev.kifuko.mctransport.server.PlayerRouteSessionTest --tests dev.kifuko.mctransport.server.PlayerTunnelSessionAuthTest
```

Expected: updated tests pass. Rename old auth tests if the class name no
longer matches behavior.

Commit:

```bash
git add src/main/java/dev/kifuko/mctransport/server src/test/java/dev/kifuko/mctransport/server
git commit -m "feat: route server streams by player"
```

---

## Task 9: Update Fabric Server Bridges For Player Sessions

**Files:**
- Modify: `src/fabric1201/main/java/dev/kifuko/mctransport/server/FabricServerTunnelBridge.java`
- Modify: `src/fabric1211/main/java/dev/kifuko/mctransport/server/FabricServerTunnelBridge.java`
- Modify: `src/test/java/dev/kifuko/mctransport/server/FabricServerBridgeConfigTest.java`

- [ ] **Step 1: Update tests**

Bridge tests must assert:

- bridge can receive `RouteStore`
- bridge creates per-player sessions keyed by player UUID
- bridge tears down sessions on disconnect
- bridge has a method to apply or clear route for an online UUID after command changes

Run:

```bash
./gradlew -PtargetMinecraft=1.21.1 test --tests dev.kifuko.mctransport.server.FabricServerBridgeConfigTest
```

Expected: failure until bridge constructors and helpers change.

- [ ] **Step 2: Implement in both version adapters**

For both Fabric versions:

- construct `PlayerTunnelSession` using Fabric's server player UUID and name
- pass `RouteStore`
- expose:

```java
applyRouteIfOnline(UUID uuid)
clearRouteIfOnline(UUID uuid)
```

These methods are called by command handlers.

- [ ] **Step 3: Verify both versions and commit**

Run:

```bash
./gradlew -PtargetMinecraft=1.20.1 test --tests dev.kifuko.mctransport.server.FabricServerBridgeConfigTest
./gradlew -PtargetMinecraft=1.21.1 test --tests dev.kifuko.mctransport.server.FabricServerBridgeConfigTest
```

Expected: both `BUILD SUCCESSFUL`.

Commit:

```bash
git add src/fabric1201/main/java/dev/kifuko/mctransport/server/FabricServerTunnelBridge.java src/fabric1211/main/java/dev/kifuko/mctransport/server/FabricServerTunnelBridge.java src/test/java/dev/kifuko/mctransport/server/FabricServerBridgeConfigTest.java
git commit -m "feat: wire player route sessions"
```

---

## Task 10: Add Shared Command Handler Core

**Files:**
- Create: `src/main/java/dev/kifuko/mctransport/server/RouteCommandService.java`
- Create: `src/test/java/dev/kifuko/mctransport/server/RouteCommandServiceTest.java`

- [ ] **Step 1: Write tests**

Required API:

```java
public final class RouteCommandService {
    public RouteCommandService(RouteStore store, OnlineRouteApplier applier)
    public String setRoute(UUID uuid, String playerName, int listenPort, String targetHost, int targetPort)
    public String unsetRoute(UUID uuid, String playerName)
    public List<String> listRoutes()
}
```

`OnlineRouteApplier`:

```java
public interface OnlineRouteApplier {
    void apply(UUID uuid);
    void clear(UUID uuid);
}
```

Tests:

- set stores route and calls apply
- unset removes route and calls clear
- list includes name, UUID, listen port, target host, target port

Run:

```bash
./gradlew -PtargetMinecraft=1.21.1 test --tests dev.kifuko.mctransport.server.RouteCommandServiceTest
```

Expected: compile fails until service exists.

- [ ] **Step 2: Implement**

Return short operator-facing messages such as:

```text
Set route for Steve (uuid): 127.0.0.1:25580 -> 127.0.0.1:10000
Removed route for Steve (uuid)
No routes configured
```

- [ ] **Step 3: Verify and commit**

Run targeted test. Expected: `BUILD SUCCESSFUL`.

Commit:

```bash
git add src/main/java/dev/kifuko/mctransport/server/RouteCommandService.java src/test/java/dev/kifuko/mctransport/server/RouteCommandServiceTest.java
git commit -m "feat: add route command service"
```

---

## Task 11: Register `/mctransport` Commands In Both Fabric Versions

**Files:**
- Create: `src/fabric1201/main/java/dev/kifuko/mctransport/server/McTransportCommands.java`
- Create: `src/fabric1211/main/java/dev/kifuko/mctransport/server/McTransportCommands.java`
- Modify: `src/fabric1201/main/java/dev/kifuko/mctransport/server/McTransportServer.java`
- Modify: `src/fabric1211/main/java/dev/kifuko/mctransport/server/McTransportServer.java`

- [ ] **Step 1: Implement command registration for 1.20.1**

Use Fabric command registration for 1.20.1. Commands:

```text
/mctransport set <playerName> <listenPort> <targetHost> <targetPort>
/mctransport unset <playerName>
/mctransport list
```

Permission requirement:

```java
source.hasPermissionLevel(4)
```

Player name resolution:

- first check online players by exact name
- then use server user cache if available
- fail clearly if no UUID can be found

- [ ] **Step 2: Implement command registration for 1.21.1**

Mirror the 1.20.1 behavior with version-correct imports and APIs.

- [ ] **Step 3: Wire commands from server entrypoints**

Each `McTransportServer` must:

- load `ServerConfig`
- create `RouteStore`
- create bridge
- create `RouteCommandService`
- register commands with service and bridge route applier hooks

- [ ] **Step 4: Verify both builds**

Run:

```bash
./gradlew -PtargetMinecraft=1.20.1 build
./gradlew -PtargetMinecraft=1.21.1 build
```

Expected: both `BUILD SUCCESSFUL`.

Commit:

```bash
git add src/fabric1201/main/java/dev/kifuko/mctransport/server src/fabric1211/main/java/dev/kifuko/mctransport/server
git commit -m "feat: add server route commands"
```

---

## Task 12: Update E2E Scripts For Server-Only Route Config

**Files:**
- Modify: `scripts/e2e/write_configs.sh`
- Modify: `scripts/e2e/prepare_fabric_server.sh`
- Modify: `scripts/e2e/run_dev_client_e2e.sh`
- Modify: `scripts/e2e/run_real_e2e.sh`
- Modify: `scripts/e2e/prepare_fabric_server_test.sh`
- Modify: `scripts/test_matrix.sh`
- Modify: `docs/manual-smoke-test.md`
- Modify: `README.md`

- [ ] **Step 1: Update script tests first**

`prepare_fabric_server_test.sh` must assert:

- server config contains `[[routes]]`
- server config contains player UUID and name
- no client config file is written or copied

Run:

```bash
scripts/e2e/prepare_fabric_server_test.sh
```

Expected: failure until scripts change.

- [ ] **Step 2: Update scripts**

Change `write_configs.sh` to write only server config. Inputs should be:

```text
<server-config-dir> <player-uuid> <player-name> <listen-port> <target-host> <target-port>
```

`prepare_fabric_server.sh` should create only:

```text
run/e2e-server-<version>/config/mctransport.server.toml
```

`run_dev_client_e2e.sh` must stop copying `mctransport.client.toml` into
`run/config`.

- [ ] **Step 3: Update docs**

README must state:

- client has no config file
- server commands configure routes
- server config persists UUID-keyed routes
- config changes through commands apply immediately for online players
- external file edits require server restart because this MVP has no reload command

If no reload command exists, do not document one.

- [ ] **Step 4: Verify and commit**

Run:

```bash
scripts/e2e/prepare_fabric_server_test.sh
scripts/test_matrix.sh
```

Expected: both pass.

Commit:

```bash
git add scripts README.md docs/manual-smoke-test.md
git commit -m "test: update e2e for server pushed routes"
```

---

## Task 13: Run Full Local Matrix

**Files:**
- No source edits expected unless failures reveal bugs.

- [ ] **Step 1: Run matrix**

```bash
scripts/test_matrix.sh
```

Expected:

- `test 1.20.1` succeeds
- `build 1.20.1` succeeds
- `test 1.21.1` succeeds
- `build 1.21.1` succeeds
- script checks succeed

- [ ] **Step 2: Fix failures**

Use systematic debugging. Do not guess. Add tests for any bug fixed.

- [ ] **Step 3: Commit fixes**

If code changed while fixing matrix failures, commit the changed files with a
specific message naming the fixed failure. If no code changed, record the clean
matrix result in the final handoff note.

---

## Task 14: Run Real Fabric E2E For Both Versions

**Files:**
- Modify: `docs/e2e-results/1.20.1.md`
- Modify: `docs/e2e-results/1.21.1.md`

- [ ] **Step 1: Run 1.20.1 real E2E**

```bash
scripts/e2e/run_dev_client_e2e.sh 1.20.1 E2EPlayer
```

Expected:

- server starts
- Loom client joins
- server sends `CONFIG_APPLY`
- client logs local listener opened after config apply
- single TCP probe passes
- 4-way concurrent TCP probe passes

- [ ] **Step 2: Run 1.21.1 real E2E**

```bash
scripts/e2e/run_dev_client_e2e.sh 1.21.1 E2EPlayer
```

Expected: same as 1.20.1.

- [ ] **Step 3: Verify logs**

Use:

```bash
rg -n "CONFIG_APPLY|CONFIG_ACK|local listener|client joined|joined; tunnel session ready|probe .* ok|Exception|ERROR|failed" run/e2e-server-1.20.1 run/e2e-server-1.21.1
```

Expected:

- config apply/ack appears before probe success
- no crash or protocol error

- [ ] **Step 4: Commit evidence docs**

```bash
git add docs/e2e-results/1.20.1.md docs/e2e-results/1.21.1.md
git commit -m "test: verify server pushed route e2e"
```

---

## Task 15: Final Audit

**Files:**
- Whole repository.

- [ ] **Step 1: Check status**

```bash
git status --short --branch
```

Expected: clean or only ignored runtime files.

- [ ] **Step 2: Confirm no client config dependency**

```bash
rg -n "mctransport.client.toml|loadClient|ClientConfig" src scripts README.md docs/manual-smoke-test.md
```

Expected: no operational dependency remains. Test-only references are allowed
for legacy parser coverage when they do not affect runtime.

- [ ] **Step 3: Confirm release workflow still builds both jars**

```bash
ruby -e 'require "yaml"; YAML.load_file(".github/workflows/release.yml"); puts "yaml ok"'
scripts/test_matrix.sh
```

Expected: YAML parse succeeds and matrix passes.

- [ ] **Step 4: Notify review window**

```bash
tmux send-keys -t 10:2 'Server-pushed route config implementation finished; ready for review. Matrix and real E2E results are in docs/e2e-results/.' C-m
```
