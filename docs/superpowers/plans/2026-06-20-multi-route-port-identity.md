# Multi Route Port Identity Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Support multiple independent logical tunnels for one player by making `(player_uuid, listen_port)` the route identity and using `listen_port` as the frame `sessionId`.

**Architecture:** Route storage changes from one route per UUID to one route per `(UUID, listenPort)`. Server runtime creates one `PlayerTunnelSession` per route and dispatches frames by player UUID plus `sessionId`; client runtime manages one `ClientTunnelSession` per `sessionId`. Streams use an instance session id instead of static `SESSION_ID = 0`.

**Tech Stack:** Java 21, Fabric Loom, JUnit 5, existing `Frame`, `RouteConfig`, `ServerConfig`, `RouteStore`, `PlayerTunnelSession`, `ClientTunnelSession`, `DirectClientStream`, and `KcpClientStream`.

---

## Scope Check

The spec covers one subsystem: route identity and session dispatch for the existing tunnel protocol. The work is sequenced from pure config behavior to command behavior, then server runtime, then client runtime, then integration verification.

## File Structure

- Modify `src/main/java/dev/kifuko/mctransport/config/RouteConfig.java`
  - Add route-key helpers: `routeSessionId()` and `sameRouteKey(...)`.
- Modify `src/main/java/dev/kifuko/mctransport/config/ServerConfig.java`
  - Index by composite key and expose multi-route lookup.
- Modify `src/main/java/dev/kifuko/mctransport/server/RouteStore.java`
  - Forward new multi-route APIs to `ServerConfig`.
- Modify `src/main/java/dev/kifuko/mctransport/server/RouteCommandService.java`
  - Set and unset routes by `(uuid, listenPort)` and tell online appliers which route changed.
- Modify `src/fabric1201/main/java/dev/kifuko/mctransport/server/McTransportCommands.java`
- Modify `src/fabric1211/main/java/dev/kifuko/mctransport/server/McTransportCommands.java`
  - Change `unset` command to require `listenPort`.
- Modify `src/main/java/dev/kifuko/mctransport/server/PlayerTunnelSession.java`
  - Bind each session to a single `RouteConfig` and instance `sessionId`.
- Modify `src/main/java/dev/kifuko/mctransport/server/DefaultServerStreamFactory.java`
  - Use `session.sessionId()` for server-generated resets.
- Modify `src/fabric1201/main/java/dev/kifuko/mctransport/server/FabricServerTunnelBridge.java`
- Modify `src/fabric1211/main/java/dev/kifuko/mctransport/server/FabricServerTunnelBridge.java`
  - Store sessions by player UUID and session id.
- Create `src/main/java/dev/kifuko/mctransport/client/ClientTunnelSessionManager.java`
  - Own client sessions keyed by frame session id.
- Modify `src/main/java/dev/kifuko/mctransport/client/ClientTunnelSession.java`
  - Accept an instance session id and use it for outbound frames.
- Modify `src/main/java/dev/kifuko/mctransport/client/DirectClientStream.java`
- Modify `src/main/java/dev/kifuko/mctransport/client/KcpClientStream.java`
  - Use `session.sessionId()` for outbound stream frames.
- Modify `src/fabric1201/client/java/dev/kifuko/mctransport/client/McTransportClient.java`
- Modify `src/fabric1211/client/java/dev/kifuko/mctransport/client/McTransportClient.java`
  - Replace the single session reference with `ClientTunnelSessionManager`.
- Update unit and integration tests under `src/test/java/dev/kifuko/mctransport`.

---

### Task 1: ServerConfig Composite Route Key

**Files:**
- Modify: `src/main/java/dev/kifuko/mctransport/config/RouteConfig.java`
- Modify: `src/main/java/dev/kifuko/mctransport/config/ServerConfig.java`
- Test: `src/test/java/dev/kifuko/mctransport/config/ConfigValidationTest.java`

- [ ] **Step 1: Write failing tests for same UUID with different listen ports**

Add these tests to `ConfigValidationTest`:

```java
@Test
void serverConfigAcceptsSameUuidWithDifferentListenPorts() {
    RouteConfig routeA = new RouteConfig(UUID_A, "Steve", 25580, "127.0.0.1", 10000);
    RouteConfig routeB = new RouteConfig(UUID_A, "Steve", 25581, "127.0.0.1", 10001);

    ServerConfig cfg = new ServerConfig(true, "mctransport:main",
            List.of(routeA, routeB), "info");

    assertEquals(List.of(routeA, routeB), cfg.routesFor(UUID_A));
    assertSame(routeA, cfg.routeFor(UUID_A, 25580));
    assertSame(routeB, cfg.routeFor(UUID_A, 25581));
}

@Test
void serverConfigRejectsDuplicateUuidAndListenPort() {
    RouteConfig routeA = new RouteConfig(UUID_A, "Steve", 25580, "127.0.0.1", 10000);
    RouteConfig duplicate = new RouteConfig(UUID_A, "Steve2", 25580, "127.0.0.1", 10001);

    IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> new ServerConfig(true, "mctransport:main",
                    List.of(routeA, duplicate), "info"));

    assertTrue(ex.getMessage().contains("duplicate route entry"));
    assertTrue(ex.getMessage().contains(UUID_A.toString()));
    assertTrue(ex.getMessage().contains("25580"));
}

@Test
void serverConfigWithRouteReplacesOnlySameUuidAndListenPort() {
    RouteConfig routeA = new RouteConfig(UUID_A, "Steve", 25580, "127.0.0.1", 10000);
    RouteConfig routeB = new RouteConfig(UUID_A, "Steve", 25581, "127.0.0.1", 10001);
    RouteConfig replacement = new RouteConfig(UUID_A, "Steve2", 25580, "10.0.0.1", 20000);

    ServerConfig updated = new ServerConfig(true, "mctransport:main",
            List.of(routeA, routeB), "info").withRoute(replacement);

    assertEquals(2, updated.getRoutes().size());
    assertSame(replacement, updated.routeFor(UUID_A, 25580));
    assertSame(routeB, updated.routeFor(UUID_A, 25581));
}

@Test
void serverConfigWithoutRouteRemovesOnlySameUuidAndListenPort() {
    RouteConfig routeA = new RouteConfig(UUID_A, "Steve", 25580, "127.0.0.1", 10000);
    RouteConfig routeB = new RouteConfig(UUID_A, "Steve", 25581, "127.0.0.1", 10001);

    ServerConfig reduced = new ServerConfig(true, "mctransport:main",
            List.of(routeA, routeB), "info").withoutRoute(UUID_A, 25580);

    assertNull(reduced.routeFor(UUID_A, 25580));
    assertSame(routeB, reduced.routeFor(UUID_A, 25581));
    assertEquals(List.of(routeB), reduced.routesFor(UUID_A));
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run:

```bash
./gradlew test --tests dev.kifuko.mctransport.config.ConfigValidationTest
```

Expected: FAIL because `routesFor(UUID)`, `routeFor(UUID,int)`, and `withoutRoute(UUID,int)` do not exist, and duplicate UUIDs are still rejected.

- [ ] **Step 3: Implement composite route key**

In `RouteConfig`, add:

```java
public int routeSessionId() {
    return listenPort;
}

public boolean sameRouteKey(UUID uuid, int listenPort) {
    return playerUuid.equals(uuid) && this.listenPort == listenPort;
}
```

In `ServerConfig`, replace `Map<UUID, RouteConfig> routesByUuid` with:

```java
private final Map<RouteKey, RouteConfig> routesByKey;
private final List<RouteConfig> routesView;
```

Add a private record:

```java
private record RouteKey(UUID playerUuid, int listenPort) {
    private RouteKey {
        if (playerUuid == null) {
            throw new IllegalArgumentException("playerUuid must not be null");
        }
        if (listenPort < 1 || listenPort > 65535) {
            throw new IllegalArgumentException("listenPort must be in 1..65535, got: " + listenPort);
        }
    }

    static RouteKey from(RouteConfig route) {
        return new RouteKey(route.getPlayerUuid(), route.getListenPort());
    }
}
```

Build the map in constructor with:

```java
Map<RouteKey, RouteConfig> map = new LinkedHashMap<>();
if (routes != null) {
    for (RouteConfig r : routes) {
        if (r == null) {
            throw new IllegalArgumentException("route entry must not be null");
        }
        RouteKey key = RouteKey.from(r);
        if (map.containsKey(key)) {
            throw new IllegalArgumentException(
                    "duplicate route entry for uuid " + r.getPlayerUuid()
                            + " listen_port " + r.getListenPort());
        }
        map.put(key, r);
    }
}
this.routesByKey = Collections.unmodifiableMap(map);
this.routesView = Collections.unmodifiableList(new ArrayList<>(map.values()));
```

Add APIs:

```java
public List<RouteConfig> routesFor(UUID uuid) {
    if (uuid == null) {
        return List.of();
    }
    List<RouteConfig> out = new ArrayList<>();
    for (RouteConfig route : routesView) {
        if (uuid.equals(route.getPlayerUuid())) {
            out.add(route);
        }
    }
    return Collections.unmodifiableList(out);
}

public RouteConfig routeFor(UUID uuid, int listenPort) {
    if (uuid == null) {
        return null;
    }
    return routesByKey.get(new RouteKey(uuid, listenPort));
}

public RouteConfig routeFor(UUID uuid) {
    List<RouteConfig> routes = routesFor(uuid);
    return routes.size() == 1 ? routes.get(0) : null;
}

public ServerConfig withRoute(RouteConfig route) {
    if (route == null) {
        throw new IllegalArgumentException("route must not be null");
    }
    Map<RouteKey, RouteConfig> next = new LinkedHashMap<>(routesByKey);
    next.put(RouteKey.from(route), route);
    return new ServerConfig(enabled, channelName, new ArrayList<>(next.values()),
            maxStreamsPerPlayer, streamBufferSize,
            globalBufferSizePerPlayer, idleTimeoutSeconds, connectTimeoutSeconds,
            logLevel, false);
}

public ServerConfig withoutRoute(UUID uuid, int listenPort) {
    if (uuid == null) {
        return this;
    }
    RouteKey key = new RouteKey(uuid, listenPort);
    if (!routesByKey.containsKey(key)) {
        return this;
    }
    Map<RouteKey, RouteConfig> next = new LinkedHashMap<>(routesByKey);
    next.remove(key);
    return new ServerConfig(enabled, channelName, new ArrayList<>(next.values()),
            maxStreamsPerPlayer, streamBufferSize,
            globalBufferSizePerPlayer, idleTimeoutSeconds, connectTimeoutSeconds,
            logLevel, false);
}

public ServerConfig withoutRoute(UUID uuid) {
    if (uuid == null || routesFor(uuid).isEmpty()) {
        return this;
    }
    Map<RouteKey, RouteConfig> next = new LinkedHashMap<>(routesByKey);
    next.entrySet().removeIf(e -> uuid.equals(e.getValue().getPlayerUuid()));
    return new ServerConfig(enabled, channelName, new ArrayList<>(next.values()),
            maxStreamsPerPlayer, streamBufferSize,
            globalBufferSizePerPlayer, idleTimeoutSeconds, connectTimeoutSeconds,
            logLevel, false);
}
```

- [ ] **Step 4: Update old tests whose expectations changed**

In `ConfigValidationTest`, replace `serverConfigRejectsDuplicateRouteUuids` with the duplicate `(uuid, listenPort)` test above. Update any test calling `routeFor(UUID_A)` on a multi-route config to call `routeFor(UUID_A, port)`.

- [ ] **Step 5: Run tests to verify green**

Run:

```bash
./gradlew test --tests dev.kifuko.mctransport.config.ConfigValidationTest
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/dev/kifuko/mctransport/config/RouteConfig.java \
        src/main/java/dev/kifuko/mctransport/config/ServerConfig.java \
        src/test/java/dev/kifuko/mctransport/config/ConfigValidationTest.java
git commit -m "feat: key routes by player and listen port"
```

---

### Task 2: ConfigLoader and RouteStore Multi-Route Persistence

**Files:**
- Modify: `src/main/java/dev/kifuko/mctransport/server/RouteStore.java`
- Test: `src/test/java/dev/kifuko/mctransport/config/ConfigLoaderTest.java`
- Test: `src/test/java/dev/kifuko/mctransport/server/RouteStoreTest.java`

- [ ] **Step 1: Write failing ConfigLoader tests**

Add to `ConfigLoaderTest`:

```java
@Test
void loadServerReadsSamePlayerOnDifferentListenPorts(@TempDir Path tmp) throws Exception {
    Path configDir = tmp.resolve("config");
    Files.createDirectories(configDir);
    Files.writeString(configDir.resolve("mctransport.server.toml"),
            "enabled = true\n"
                    + "channel_name = \"mctransport:main\"\n"
                    + "max_streams_per_player = 64\n"
                    + "stream_buffer_size = 1048576\n"
                    + "global_buffer_size_per_player = 33554432\n"
                    + "idle_timeout_seconds = 300\n"
                    + "connect_timeout_seconds = 10\n"
                    + "log_level = \"info\"\n"
                    + "\n"
                    + "[[routes]]\n"
                    + "player_uuid = \"11111111-2222-3333-4444-555555555555\"\n"
                    + "player_name = \"Steve\"\n"
                    + "listen_port = 25580\n"
                    + "target_host = \"127.0.0.1\"\n"
                    + "target_port = 10000\n"
                    + "\n"
                    + "[[routes]]\n"
                    + "player_uuid = \"11111111-2222-3333-4444-555555555555\"\n"
                    + "player_name = \"Steve\"\n"
                    + "listen_port = 25581\n"
                    + "target_host = \"10.0.0.5\"\n"
                    + "target_port = 25565\n",
            StandardCharsets.UTF_8);

    ServerConfig cfg = ConfigLoader.loadServer(configDir, "mctransport.server.toml", SERVER_RESOURCE);

    UUID uuid = UUID.fromString("11111111-2222-3333-4444-555555555555");
    assertEquals(2, cfg.routesFor(uuid).size());
    assertEquals(10000, cfg.routeFor(uuid, 25580).getTargetPort());
    assertEquals(25565, cfg.routeFor(uuid, 25581).getTargetPort());
}

@Test
void writeServerPersistsSamePlayerOnDifferentListenPorts(@TempDir Path tmp) throws Exception {
    UUID uuid = UUID.fromString("11111111-2222-3333-4444-555555555555");
    ServerConfig config = new ServerConfig(true, "mctransport:main",
            List.of(
                    new RouteConfig(uuid, "Steve", 25580, "127.0.0.1", 10000),
                    new RouteConfig(uuid, "Steve", 25581, "10.0.0.5", 25565)),
            64, 1024, 8192L, 300, 10, "info");

    ConfigLoader.writeServer(tmp, "mctransport.server.toml", config);
    ServerConfig reloaded = ConfigLoader.loadServer(tmp, "mctransport.server.toml", SERVER_RESOURCE);

    assertEquals(2, reloaded.routesFor(uuid).size());
    assertEquals("127.0.0.1", reloaded.routeFor(uuid, 25580).getTargetHost());
    assertEquals("10.0.0.5", reloaded.routeFor(uuid, 25581).getTargetHost());
}
```

- [ ] **Step 2: Write failing RouteStore tests**

In `RouteStoreTest`, replace `setRouteReplacesExistingUuid` with:

```java
@Test
void setRouteAddsSecondPortForSameUuid(@TempDir Path tmp) {
    RouteStore store = new RouteStore(tmp, "mctransport.server.toml",
            config(List.of(route("Steve", 25580, 10000))));

    store.setRoute(route("Steve", 25581, 10001));

    assertEquals(2, store.routes().size());
    assertEquals(10000, store.routeFor(UUID_A, 25580).getTargetPort());
    assertEquals(10001, store.routeFor(UUID_A, 25581).getTargetPort());
}

@Test
void removeRouteRemovesOnlyRequestedPort(@TempDir Path tmp) {
    RouteStore store = new RouteStore(tmp, "mctransport.server.toml",
            config(List.of(
                    route("Steve", 25580, 10000),
                    route("Steve", 25581, 10001))));

    assertTrue(store.removeRoute(UUID_A, 25580));

    assertTrue(store.routeFor(UUID_A, 25580) == null);
    assertEquals(25581, store.routeFor(UUID_A, 25581).getListenPort());
    assertEquals(1, store.routesFor(UUID_A).size());
}
```

Update the existing persistence assertion:

```java
assertEquals(route, reloaded.routeFor(UUID_A, 25580));
```

- [ ] **Step 3: Run tests to verify they fail**

Run:

```bash
./gradlew test --tests dev.kifuko.mctransport.config.ConfigLoaderTest \
               --tests dev.kifuko.mctransport.server.RouteStoreTest
```

Expected: FAIL because `RouteStore.routeFor(UUID,int)`, `routesFor(UUID)`, and `removeRoute(UUID,int)` do not exist.

- [ ] **Step 4: Implement RouteStore APIs**

Update `RouteStore`:

```java
public synchronized RouteConfig routeFor(UUID uuid, int listenPort) {
    if (uuid == null) {
        return null;
    }
    return current.routeFor(uuid, listenPort);
}

public synchronized List<RouteConfig> routesFor(UUID uuid) {
    if (uuid == null) {
        return List.of();
    }
    return List.copyOf(current.routesFor(uuid));
}

public synchronized boolean removeRoute(UUID uuid, int listenPort) {
    if (current.routeFor(uuid, listenPort) == null) {
        return false;
    }
    ServerConfig next = current.withoutRoute(uuid, listenPort);
    save(next);
    current = next;
    return true;
}
```

Keep `routeFor(UUID)` and `removeRoute(UUID)` for any old call sites during migration, but new runtime code must use port-specific APIs.

- [ ] **Step 5: Run tests to verify green**

Run:

```bash
./gradlew test --tests dev.kifuko.mctransport.config.ConfigLoaderTest \
               --tests dev.kifuko.mctransport.server.RouteStoreTest
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/dev/kifuko/mctransport/server/RouteStore.java \
        src/test/java/dev/kifuko/mctransport/config/ConfigLoaderTest.java \
        src/test/java/dev/kifuko/mctransport/server/RouteStoreTest.java
git commit -m "feat: persist multiple player routes by port"
```

---

### Task 3: RouteCommandService Uses Listen Port for Apply and Clear

**Files:**
- Modify: `src/main/java/dev/kifuko/mctransport/server/RouteCommandService.java`
- Test: `src/test/java/dev/kifuko/mctransport/server/RouteCommandServiceTest.java`

- [ ] **Step 1: Write failing service tests**

Update `RecordingApplier` in `RouteCommandServiceTest`:

```java
private static final class RouteEvent {
    final UUID uuid;
    final int listenPort;

    RouteEvent(UUID uuid, int listenPort) {
        this.uuid = uuid;
        this.listenPort = listenPort;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof RouteEvent other)) return false;
        return listenPort == other.listenPort && uuid.equals(other.uuid);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(uuid, listenPort);
    }
}

private static final class RecordingApplier
        implements RouteCommandService.OnlineRouteApplier {
    final List<RouteEvent> applied = new ArrayList<>();
    final List<RouteEvent> cleared = new ArrayList<>();

    @Override
    public void apply(UUID uuid, int listenPort) {
        applied.add(new RouteEvent(uuid, listenPort));
    }

    @Override
    public void clear(UUID uuid, int listenPort) {
        cleared.add(new RouteEvent(uuid, listenPort));
    }
}
```

Replace assertions in `setStoresRouteAndCallsApply`:

```java
RouteConfig route = store.routeFor(UUID_A, 25580);
assertEquals("Steve", route.getPlayerName());
assertEquals(25580, route.getListenPort());
assertEquals(10000, route.getTargetPort());
assertEquals(List.of(new RouteEvent(UUID_A, 25580)), applier.applied);
```

Replace `unsetRemovesRouteAndCallsClear` with:

```java
@Test
void unsetRemovesOnlyRequestedPortAndCallsClear(@TempDir Path tmp) {
    RecordingApplier applier = new RecordingApplier();
    RouteConfig routeA = new RouteConfig(UUID_A, "Steve", 25580,
            "127.0.0.1", 10000);
    RouteConfig routeB = new RouteConfig(UUID_A, "Steve", 25581,
            "127.0.0.1", 10001);
    RouteStore store = new RouteStore(tmp, "mctransport.server.toml",
            config(List.of(routeA, routeB)));
    RouteCommandService service = new RouteCommandService(store, applier);

    String message = service.unsetRoute(UUID_A, "Steve", 25580);

    assertTrue(store.routeFor(UUID_A, 25580) == null);
    assertEquals(routeB, store.routeFor(UUID_A, 25581));
    assertEquals(List.of(new RouteEvent(UUID_A, 25580)), applier.cleared);
    assertTrue(message.contains("Removed route for Steve"));
    assertTrue(message.contains("127.0.0.1:25580"));
}
```

Add:

```java
@Test
void setAddsSecondPortForSamePlayer(@TempDir Path tmp) {
    RecordingApplier applier = new RecordingApplier();
    RouteStore store = new RouteStore(tmp, "mctransport.server.toml",
            config(List.of(new RouteConfig(UUID_A, "Steve", 25580,
                    "127.0.0.1", 10000))));
    RouteCommandService service = new RouteCommandService(store, applier);

    service.setRoute(UUID_A, "Steve", 25581, "10.0.0.5", 25565, StreamMode.KCP);

    assertEquals(2, store.routesFor(UUID_A).size());
    assertEquals(StreamMode.KCP, store.routeFor(UUID_A, 25581).getMode());
    assertEquals(List.of(new RouteEvent(UUID_A, 25581)), applier.applied);
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run:

```bash
./gradlew test --tests dev.kifuko.mctransport.server.RouteCommandServiceTest
```

Expected: FAIL because `OnlineRouteApplier` still takes only UUID and `unsetRoute` has no listen port parameter.

- [ ] **Step 3: Implement service changes**

Update `RouteCommandService`:

```java
public String setRoute(UUID uuid, String playerName, int listenPort,
                       String targetHost, int targetPort, StreamMode mode) {
    RouteConfig route = new RouteConfig(uuid, playerName,
            listenPort, targetHost, targetPort, mode);
    store.setRoute(route);
    applier.apply(uuid, listenPort);
    return "Set route for " + route.getPlayerName() + " (" + uuid + "): "
            + route.getListenHost() + ":" + route.getListenPort()
            + " -> " + route.getTargetHost() + ":" + route.getTargetPort()
            + " (mode=" + route.getMode() + ")";
}

public String unsetRoute(UUID uuid, String playerName, int listenPort) {
    boolean removed = store.removeRoute(uuid, listenPort);
    applier.clear(uuid, listenPort);
    return (removed ? "Removed route for " : "No route found for ")
            + playerName + " (" + uuid + ") "
            + RouteConfig.LOOPBACK_HOST + ":" + listenPort;
}

public interface OnlineRouteApplier {
    void apply(UUID uuid, int listenPort);

    void clear(UUID uuid, int listenPort);
}
```

Update `listRoutes()` row mapping to include mode:

```java
+ " -> " + r.getTargetHost() + ":" + r.getTargetPort()
+ " (mode=" + r.getMode() + ")"
```

- [ ] **Step 4: Run tests to verify green**

Run:

```bash
./gradlew test --tests dev.kifuko.mctransport.server.RouteCommandServiceTest
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/dev/kifuko/mctransport/server/RouteCommandService.java \
        src/test/java/dev/kifuko/mctransport/server/RouteCommandServiceTest.java
git commit -m "feat: apply route commands by listen port"
```

---

### Task 4: PlayerTunnelSession Owns One Route and Session Id

**Files:**
- Modify: `src/main/java/dev/kifuko/mctransport/server/PlayerTunnelSession.java`
- Modify: `src/main/java/dev/kifuko/mctransport/server/DefaultServerStreamFactory.java`
- Test: `src/test/java/dev/kifuko/mctransport/server/PlayerTunnelSessionAuthTest.java`

- [ ] **Step 1: Write failing tests for server session id**

In `PlayerTunnelSessionAuthTest`, change `setUp` to bind a route:

```java
private void setUp(RouteConfig route, List<RouteConfig> routes, ServerStreamFactory factory) {
    ServerConfig cfg = config(routes);
    bridge = new FakeTunnelBridge();
    bridge.setReceiver(frame -> { });
    registry = new StreamRegistry(8, false);
    TargetTcpConnector connector = new TargetTcpConnector(10,
            Executors.newSingleThreadExecutor());
    session = new PlayerTunnelSession(PLAYER_UUID, route, bridge, cfg, store(cfg),
            registry, new BufferBudget(1024, 8192L), new ReservationState(),
            connector, 1_700_000_000L, factory);
}
```

Add:

```java
@Test
void configuredRouteUsesListenPortAsSessionId() {
    RouteConfig route = route();
    setUp(route, List.of(route), new NoopServerStreamFactory());

    session.sendRouteIfConfigured();

    Frame apply = bridge.sentFrames().get(0);
    assertEquals(25580, apply.sessionId());
    assertEquals(25580, session.sessionId());
}

@Test
void pongUsesBoundSessionId() {
    RouteConfig route = route();
    setUp(route, List.of(route), new NoopServerStreamFactory());
    session.sendRouteIfConfigured();
    session.handleInbound(Frame.createTrusted(PlayerTunnelSession.PROTOCOL_VERSION,
            25580, 0, FrameType.CONFIG_ACK, (byte) 0,
            RouteControlPayload.encodeAck(true, "ok")));
    bridge.clearSent();

    session.handleInbound(Frame.createTrusted(PlayerTunnelSession.PROTOCOL_VERSION,
            25580, 7, FrameType.PING, (byte) 0, new byte[0]));

    assertEquals(FrameType.PONG, bridge.sentFrames().get(0).type());
    assertEquals(25580, bridge.sentFrames().get(0).sessionId());
}

@Test
void mismatchedSessionIdIsRejected() {
    RouteConfig route = route();
    setUp(route, List.of(route), new NoopServerStreamFactory());

    ProtocolException ex = assertThrows(ProtocolException.class,
            () -> session.handleInbound(Frame.createTrusted(PlayerTunnelSession.PROTOCOL_VERSION,
                    25581, 0, FrameType.CONFIG_ACK, (byte) 0,
                    RouteControlPayload.encodeAck(true, "wrong"))));

    assertTrue(ex.getMessage().contains("unexpected session id"));
}
```

Update helper calls from `setUp(List.of(route()), ...)` to `setUp(route(), List.of(route()), ...)`.

- [ ] **Step 2: Run tests to verify they fail**

Run:

```bash
./gradlew test --tests dev.kifuko.mctransport.server.PlayerTunnelSessionAuthTest
```

Expected: FAIL because the constructor does not accept `RouteConfig`, outbound frames use session id `0`, and mismatched session ids are accepted.

- [ ] **Step 3: Implement bound route and session id**

Update fields in `PlayerTunnelSession`:

```java
private final RouteConfig route;
private final int sessionId;
```

Update constructor signature:

```java
public PlayerTunnelSession(UUID playerUuid,
                           RouteConfig route,
                           TunnelBridge bridge,
                           ServerConfig config,
                           RouteStore routeStore,
                           StreamRegistry registry,
                           BufferBudget budget,
                           ReservationState reservations,
                           TargetTcpConnector connector,
                           long nowMillis,
                           ServerStreamFactory streamFactory) {
    if (route == null) {
        throw new IllegalArgumentException("route must not be null");
    }
    if (!route.getPlayerUuid().equals(playerUuid)) {
        throw new IllegalArgumentException("route playerUuid must match session playerUuid");
    }
    this.playerUuid = playerUuid;
    this.route = route;
    this.sessionId = route.routeSessionId();
    this.bridge = bridge;
    this.config = config;
    this.routeStore = routeStore;
    this.registry = registry;
    this.budget = budget;
    this.reservations = reservations;
    this.connector = connector;
    this.nowMillisSupplier = nowMillis;
    this.lastInboundMillis = nowMillis;
    this.streamFactory = streamFactory;
}
```

Add accessors:

```java
public int sessionId() {
    return sessionId;
}

public RouteConfig route() {
    return route;
}
```

At the top of `handleInbound` after null check:

```java
if (frame.sessionId() != sessionId) {
    throw new ProtocolException("unexpected session id " + frame.sessionId()
            + " for route " + sessionId);
}
```

Change `sendRouteIfConfigured()`:

```java
public void sendRouteIfConfigured() {
    sendConfigApply(route);
}
```

Replace every `SESSION_ID` use in frame creation with `sessionId`.

In `DefaultServerStreamFactory.sendReset`, use:

```java
session.sessionId()
```

- [ ] **Step 4: Run tests to verify green**

Run:

```bash
./gradlew test --tests dev.kifuko.mctransport.server.PlayerTunnelSessionAuthTest \
               --tests dev.kifuko.mctransport.server.ServerStreamBackpressureTest
```

Expected: PASS after updating constructor call sites in affected tests to pass a `RouteConfig`.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/dev/kifuko/mctransport/server/PlayerTunnelSession.java \
        src/main/java/dev/kifuko/mctransport/server/DefaultServerStreamFactory.java \
        src/test/java/dev/kifuko/mctransport/server/PlayerTunnelSessionAuthTest.java \
        src/test/java/dev/kifuko/mctransport/server/ServerStreamBackpressureTest.java \
        src/test/java/dev/kifuko/mctransport/integration \
        src/test/java/dev/kifuko/mctransport/kcp \
        src/test/java/dev/kifuko/mctransport/stream
git commit -m "feat: bind server tunnel sessions to route ports"
```

---

### Task 5: Fabric Server Bridge Stores Sessions by Player and Port

**Files:**
- Modify: `src/fabric1201/main/java/dev/kifuko/mctransport/server/FabricServerTunnelBridge.java`
- Modify: `src/fabric1211/main/java/dev/kifuko/mctransport/server/FabricServerTunnelBridge.java`
- Modify: `src/test/java/dev/kifuko/mctransport/server/FabricServerBridgeConfigTest.java`

- [ ] **Step 1: Write failing bridge tests**

Update `FabricServerBridgeConfigTest` to inspect route-specific sessions:

```java
@Test
void createdSessionsUseLoadedRoutesForSamePlayer() {
    UUID uuid = UUID.randomUUID();
    RouteConfig routeA = new RouteConfig(uuid, "Steve", 25580,
            "10.0.0.25", 19000);
    RouteConfig routeB = new RouteConfig(uuid, "Steve", 25581,
            "10.0.0.26", 19001);
    ServerConfig config = new ServerConfig(true, "mctransport:main",
            List.of(routeA, routeB), 3, 2048, 4096,
            123, 7, "debug");
    TestBridge bridge = new TestBridge(config);

    List<PlayerTunnelSession> sessions = bridge.newSessionsForTest(uuid, new Object());

    assertEquals(2, sessions.size());
    assertEquals(25580, sessions.get(0).sessionId());
    assertEquals(25581, sessions.get(1).sessionId());
    assertEquals(routeA, sessions.get(0).route());
    assertEquals(routeB, sessions.get(1).route());
}
```

Replace the test helper with:

```java
List<PlayerTunnelSession> newSessionsForTest(UUID uuid, Object player) {
    return createSessions(uuid, player);
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run:

```bash
./gradlew test --tests dev.kifuko.mctransport.server.FabricServerBridgeConfigTest
```

Expected: FAIL because `createSessions(UUID,Object)` does not exist and the bridge still creates one session.

- [ ] **Step 3: Implement server bridge maps in both Fabric versions**

In both server bridge files, replace:

```java
private final Map<UUID, PlayerTunnelSession> sessionsByPlayer = new ConcurrentHashMap<>();
```

with:

```java
private final Map<UUID, Map<Integer, PlayerTunnelSession>> sessionsByPlayer = new ConcurrentHashMap<>();
```

On join, replace single-session creation with:

```java
Map<Integer, PlayerTunnelSession> sessions = new ConcurrentHashMap<>();
for (PlayerTunnelSession session : createSessions(playerUuid, player)) {
    sessions.put(session.sessionId(), session);
    sessionToPlayer.put(session, player);
}
playersByUuid.put(playerUuid, player);
dispatchersByPlayer.put(playerUuid, new SerialExecutor(executors.io()));
sessionsByPlayer.put(playerUuid, sessions);
McTransport.LOGGER.info("player {} joined; {} tunnel sessions ready", playerUuid, sessions.size());
for (PlayerTunnelSession session : sessions.values()) {
    session.sendRouteIfConfigured();
}
```

On disconnect:

```java
Map<Integer, PlayerTunnelSession> sessions = sessionsByPlayer.remove(playerUuid);
playersByUuid.remove(playerUuid);
dispatchersByPlayer.remove(playerUuid);
if (sessions != null) {
    for (PlayerTunnelSession session : sessions.values()) {
        session.close();
        sessionToPlayer.remove(session);
    }
    McTransport.LOGGER.info("player disconnected; tunnel sessions torn down");
}
```

Update inbound dispatch:

```java
Frame wireFrame = codec.decode(bytes);
Map<Integer, PlayerTunnelSession> sessions = sessionsByPlayer.get(playerUuid);
PlayerTunnelSession session = sessions == null ? null : sessions.get(wireFrame.sessionId());
if (session == null) {
    McTransport.LOGGER.warn("inbound frame for unknown route session {} player {}",
            wireFrame.sessionId(), playerUuid);
    return;
}
session.handleInbound(wireFrame);
```

Add route-specific applier methods:

```java
public void applyRouteIfOnline(UUID uuid, int listenPort) {
    Map<Integer, PlayerTunnelSession> sessions = sessionsByPlayer.get(uuid);
    Object player = playersByUuid.get(uuid);
    if (sessions == null) {
        return;
    }
    PlayerTunnelSession existing = sessions.get(listenPort);
    if (existing != null) {
        existing.sendRouteClear();
        existing.close();
        sessionToPlayer.remove(existing);
    }
    RouteConfig route = routeStore.routeFor(uuid, listenPort);
    if (route == null || player == null) {
        return;
    }
    PlayerTunnelSession replacement = createSession(uuid, route, player);
    sessions.put(listenPort, replacement);
    sessionToPlayer.put(replacement, player);
    replacement.sendRouteIfConfigured();
}

public void clearRouteIfOnline(UUID uuid, int listenPort) {
    Map<Integer, PlayerTunnelSession> sessions = sessionsByPlayer.get(uuid);
    if (sessions == null) {
        return;
    }
    PlayerTunnelSession session = sessions.remove(listenPort);
    if (session != null) {
        session.sendRouteClear();
        session.close();
        sessionToPlayer.remove(session);
    }
}
```

Because the snippet above needs the player object when creating a brand-new online route, store players by UUID:

```java
private final Map<UUID, Object> playersByUuid = new ConcurrentHashMap<>();
```

Set it on join, remove it on disconnect, and use `playersByUuid.get(uuid)` in `applyRouteIfOnline`.

Add helpers:

```java
protected List<PlayerTunnelSession> createSessions(UUID uuid, Object player) {
    List<PlayerTunnelSession> out = new java.util.ArrayList<>();
    for (RouteConfig route : routeStore.routesFor(uuid)) {
        out.add(createSession(uuid, route, player));
    }
    return out;
}

protected PlayerTunnelSession createSession(UUID uuid, RouteConfig route, Object player) {
    ServerConfig cfg = config;
    TargetTcpConnector connector = new TargetTcpConnector(
            cfg.getConnectTimeoutSeconds(), executors.io());
    DefaultServerStreamFactory factory = new DefaultServerStreamFactory(connector,
            cfg.getStreamBufferSize(), 4096, executors.io());
    return new PlayerTunnelSession(uuid, route, new PlayerBridge(player), cfg, routeStore,
            new dev.kifuko.mctransport.stream.StreamRegistry(
                    cfg.getMaxStreamsPerPlayer(), false),
            new dev.kifuko.mctransport.buffer.BufferBudget(
                    cfg.getStreamBufferSize(), cfg.getGlobalBufferSizePerPlayer()),
            new dev.kifuko.mctransport.buffer.ReservationState(),
            connector,
            System.currentTimeMillis(), factory);
}
```

Update `sessionFor` to include listen port:

```java
public Optional<PlayerTunnelSession> sessionFor(UUID uuid, int listenPort) {
    Map<Integer, PlayerTunnelSession> sessions = sessionsByPlayer.get(uuid);
    return Optional.ofNullable(sessions == null ? null : sessions.get(listenPort));
}
```

- [ ] **Step 4: Wire RouteCommandService applier calls in server initializers**

Where the server bridge is passed as `OnlineRouteApplier`, update anonymous implementations or method references to call:

```java
bridge.applyRouteIfOnline(uuid, listenPort);
bridge.clearRouteIfOnline(uuid, listenPort);
```

Use `rg -n "applyRouteIfOnline|clearRouteIfOnline|OnlineRouteApplier" src/fabric1201 src/fabric1211 src/main src/test` to find every call site.

- [ ] **Step 5: Run tests to verify green**

Run:

```bash
./gradlew test --tests dev.kifuko.mctransport.server.FabricServerBridgeConfigTest
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/fabric1201/main/java/dev/kifuko/mctransport/server/FabricServerTunnelBridge.java \
        src/fabric1211/main/java/dev/kifuko/mctransport/server/FabricServerTunnelBridge.java \
        src/test/java/dev/kifuko/mctransport/server/FabricServerBridgeConfigTest.java
git commit -m "feat: dispatch server sessions by player and port"
```

---

### Task 6: ClientTunnelSession Uses Instance Session Id

**Files:**
- Modify: `src/main/java/dev/kifuko/mctransport/client/ClientTunnelSession.java`
- Modify: `src/main/java/dev/kifuko/mctransport/client/DirectClientStream.java`
- Modify: `src/main/java/dev/kifuko/mctransport/client/KcpClientStream.java`
- Test: `src/test/java/dev/kifuko/mctransport/client/ClientTunnelSessionTest.java`
- Test: `src/test/java/dev/kifuko/mctransport/client/ClientStreamFlowControlTest.java`

- [ ] **Step 1: Write failing tests for client session id**

In `ClientTunnelSessionTest`, change `buildSession`:

```java
private ClientTunnelSession buildSession(FakeTunnelBridge bridge,
                                         ClientStreamFactory factory,
                                         FakeListenerController listener) {
    StreamRegistry registry = new StreamRegistry(8, true);
    return new ClientTunnelSession(25580, bridge, registry, factory, 0L, listener);
}
```

Change `configApply`:

```java
private Frame configApply(int port) {
    return Frame.createTrusted(ClientTunnelSession.PROTOCOL_VERSION,
            port, 0, FrameType.CONFIG_APPLY, (byte) 0,
            RouteControlPayload.encodeApply("127.0.0.1", port));
}
```

Add:

```java
@Test
void outboundControlFramesUseInstanceSessionId() {
    FakeTunnelBridge b = buildBridge();
    ClientTunnelSession s = buildSession(b, (sess, id, mode) -> null,
            new FakeListenerController());

    s.handleInbound(configApply(25580));

    assertEquals(FrameType.CONFIG_ACK, b.sentFrames().get(0).type());
    assertEquals(25580, b.sentFrames().get(0).sessionId());
}

@Test
void openPingAndResetUseInstanceSessionId() {
    FakeTunnelBridge b = buildBridge();
    FakeFactory factory = new FakeFactory();
    ClientTunnelSession s = buildSession(b, factory, new FakeListenerController());
    s.setPingIntervalMillis(100);
    s.handleInbound(configApply(25580));
    b.clearSent();

    s.openLocalStream();
    s.tick(200L);
    s.handleInbound(Frame.createTrusted(ClientTunnelSession.PROTOCOL_VERSION,
            25580, 99, FrameType.DATA, (byte) 0, "hi".getBytes()));

    assertTrue(b.sentFrames().stream().allMatch(f -> f.sessionId() == 25580));
}

@Test
void mismatchedInboundSessionIdIsRejected() {
    FakeTunnelBridge b = buildBridge();
    ClientTunnelSession s = buildSession(b, (sess, id, mode) -> null,
            new FakeListenerController());

    ProtocolException ex = assertThrows(ProtocolException.class,
            () -> s.handleInbound(Frame.createTrusted(ClientTunnelSession.PROTOCOL_VERSION,
                    25581, 0, FrameType.CONFIG_APPLY, (byte) 0,
                    RouteControlPayload.encodeApply("127.0.0.1", 25581))));

    assertTrue(ex.getMessage().contains("unexpected session id"));
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run:

```bash
./gradlew test --tests dev.kifuko.mctransport.client.ClientTunnelSessionTest
```

Expected: FAIL because the constructor has no session id and outbound frames still use `SESSION_ID = 0`.

- [ ] **Step 3: Implement instance session id**

Update `ClientTunnelSession`:

```java
private final int sessionId;

public ClientTunnelSession(int sessionId,
                           TunnelBridge bridge,
                           StreamRegistry registry,
                           ClientStreamFactory streamFactory,
                           long nowMillis,
                           ClientListenerController listenerController) {
    if (sessionId < 1 || sessionId > 65535) {
        throw new IllegalArgumentException("sessionId must be in 1..65535, got: " + sessionId);
    }
    this.sessionId = sessionId;
    this.bridge = bridge;
    this.registry = registry;
    this.streamFactory = streamFactory;
    this.listenerController = listenerController;
    this.lastInboundMillis = nowMillis;
    this.lastPingMillis = nowMillis;
}

public int sessionId() {
    return sessionId;
}
```

Keep backward-compatible constructors:

```java
public ClientTunnelSession(TunnelBridge bridge,
                           StreamRegistry registry,
                           ClientStreamFactory streamFactory,
                           long nowMillis) {
    this(1, bridge, registry, streamFactory, nowMillis,
            new NoopListenerController());
}

public ClientTunnelSession(TunnelBridge bridge,
                           StreamRegistry registry,
                           ClientStreamFactory streamFactory,
                           long nowMillis,
                           ClientListenerController listenerController) {
    this(1, bridge, registry, streamFactory, nowMillis, listenerController);
}
```

At the top of `handleInbound` after null check:

```java
if (frame.sessionId() != sessionId) {
    throw new ProtocolException("unexpected session id " + frame.sessionId()
            + " for route " + sessionId);
}
```

Replace every outbound `SESSION_ID` in `ClientTunnelSession` with `sessionId`.

In `DirectClientStream` and `KcpClientStream`, replace:

```java
ClientTunnelSession.SESSION_ID
```

with:

```java
session.sessionId()
```

- [ ] **Step 4: Update tests with explicit session id where needed**

Search:

```bash
rg -n "new ClientTunnelSession\\(|ClientTunnelSession\\.SESSION_ID" src/test src/main
```

For tests expecting historical `0`, either pass an explicit valid session id and assert it, or leave backward-compatible constructors when the test is unrelated to session id.

- [ ] **Step 5: Run tests to verify green**

Run:

```bash
./gradlew test --tests dev.kifuko.mctransport.client.ClientTunnelSessionTest \
               --tests dev.kifuko.mctransport.client.ClientStreamFlowControlTest
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/dev/kifuko/mctransport/client/ClientTunnelSession.java \
        src/main/java/dev/kifuko/mctransport/client/DirectClientStream.java \
        src/main/java/dev/kifuko/mctransport/client/KcpClientStream.java \
        src/test/java/dev/kifuko/mctransport/client/ClientTunnelSessionTest.java \
        src/test/java/dev/kifuko/mctransport/client/ClientStreamFlowControlTest.java
git commit -m "feat: send client frames with route session id"
```

---

### Task 7: ClientTunnelSessionManager Dispatches by Session Id

**Files:**
- Create: `src/main/java/dev/kifuko/mctransport/client/ClientTunnelSessionManager.java`
- Test: `src/test/java/dev/kifuko/mctransport/client/ClientTunnelSessionManagerTest.java`

- [ ] **Step 1: Write failing manager tests**

Create `ClientTunnelSessionManagerTest`:

```java
package dev.kifuko.mctransport.client;

import dev.kifuko.mctransport.buffer.BufferBudget;
import dev.kifuko.mctransport.buffer.ReservationState;
import dev.kifuko.mctransport.net.FakeTunnelBridge;
import dev.kifuko.mctransport.protocol.Frame;
import dev.kifuko.mctransport.protocol.FrameType;
import dev.kifuko.mctransport.protocol.RouteControlPayload;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientTunnelSessionManagerTest {

    @Test
    void configApplyCreatesSeparateSessionsBySessionId() {
        FakeTunnelBridge bridge = new FakeTunnelBridge();
        RecordingFactory factory = new RecordingFactory();
        ClientTunnelSessionManager manager = new ClientTunnelSessionManager(
                bridge, factory, sessionId -> new RecordingListenerController());

        manager.handleInbound(apply(25580));
        manager.handleInbound(apply(25581));

        assertTrue(manager.session(25580).isPresent());
        assertTrue(manager.session(25581).isPresent());
        assertEquals(2, manager.sessionCount());
        assertEquals(List.of(25580, 25581), bridge.sentFrames().stream()
                .map(Frame::sessionId).toList());
    }

    @Test
    void configClearRemovesOnlyOneSession() {
        FakeTunnelBridge bridge = new FakeTunnelBridge();
        ClientTunnelSessionManager manager = new ClientTunnelSessionManager(
                bridge, new RecordingFactory(), sessionId -> new RecordingListenerController());
        manager.handleInbound(apply(25580));
        manager.handleInbound(apply(25581));

        manager.handleInbound(Frame.createTrusted(ClientTunnelSession.PROTOCOL_VERSION,
                25580, 0, FrameType.CONFIG_CLEAR, (byte) 0, new byte[0]));

        assertFalse(manager.session(25580).isPresent());
        assertTrue(manager.session(25581).isPresent());
    }

    @Test
    void tickTicksEverySession() {
        FakeTunnelBridge bridge = new FakeTunnelBridge();
        ClientTunnelSessionManager manager = new ClientTunnelSessionManager(
                bridge, new RecordingFactory(), sessionId -> new RecordingListenerController());
        manager.handleInbound(apply(25580));
        manager.handleInbound(apply(25581));
        manager.sessions().forEach(s -> s.setPingIntervalMillis(100));
        bridge.clearSent();

        manager.tick(200L);

        assertEquals(List.of(25580, 25581), bridge.sentFrames().stream()
                .map(Frame::sessionId).sorted().toList());
    }

    private static Frame apply(int port) {
        return Frame.createTrusted(ClientTunnelSession.PROTOCOL_VERSION,
                port, 0, FrameType.CONFIG_APPLY, (byte) 0,
                RouteControlPayload.encodeApply("127.0.0.1", port));
    }

    private static final class RecordingFactory implements ClientStreamFactory {
        @Override
        public ClientStream create(ClientTunnelSession session, int streamId,
                                   dev.kifuko.mctransport.protocol.StreamMode mode) {
            return new DirectClientStream(session, streamId,
                    new BufferBudget(1024, 8192L),
                    new ReservationState(), 1024);
        }
    }

    private static final class RecordingListenerController implements ClientListenerController {
        private boolean listening;

        @Override
        public void apply(String listenHost, int listenPort) {
            listening = true;
        }

        @Override
        public void clear() {
            listening = false;
        }

        @Override
        public boolean isListening() {
            return listening;
        }
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run:

```bash
./gradlew test --tests dev.kifuko.mctransport.client.ClientTunnelSessionManagerTest
```

Expected: FAIL because `ClientTunnelSessionManager` does not exist.

- [ ] **Step 3: Implement the manager**

Create `ClientTunnelSessionManager`:

```java
package dev.kifuko.mctransport.client;

import dev.kifuko.mctransport.net.TunnelBridge;
import dev.kifuko.mctransport.protocol.Frame;
import dev.kifuko.mctransport.protocol.FrameType;
import dev.kifuko.mctransport.protocol.ProtocolException;
import dev.kifuko.mctransport.stream.StreamRegistry;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntFunction;

public final class ClientTunnelSessionManager {

    private static final int DEFAULT_MAX_STREAMS = 64;

    private final TunnelBridge bridge;
    private final ClientStreamFactory streamFactory;
    private final IntFunction<ClientListenerController> listenerFactory;
    private final Map<Integer, ClientTunnelSession> sessions = new ConcurrentHashMap<>();

    public ClientTunnelSessionManager(TunnelBridge bridge,
                                      ClientStreamFactory streamFactory,
                                      IntFunction<ClientListenerController> listenerFactory) {
        this.bridge = bridge;
        this.streamFactory = streamFactory;
        this.listenerFactory = listenerFactory;
    }

    public void handleInbound(Frame frame) {
        if (frame == null) {
            throw new IllegalArgumentException("frame must not be null");
        }
        int sessionId = frame.sessionId();
        if (sessionId < 1 || sessionId > 65535) {
            throw new ProtocolException("invalid route session id: " + sessionId);
        }
        if (frame.type() == FrameType.CONFIG_APPLY) {
            ClientTunnelSession session = sessions.computeIfAbsent(sessionId, this::newSession);
            session.handleInbound(frame);
            return;
        }
        ClientTunnelSession session = sessions.get(sessionId);
        if (session == null) {
            throw new ProtocolException("frame for unknown route session: " + sessionId);
        }
        session.handleInbound(frame);
        if (frame.type() == FrameType.CONFIG_CLEAR) {
            sessions.remove(sessionId);
        }
    }

    public void tick(long nowMillis) {
        for (ClientTunnelSession session : sessions.values()) {
            session.tick(nowMillis);
        }
    }

    public void closeAll() {
        for (ClientTunnelSession session : sessions.values()) {
            session.close();
        }
        sessions.clear();
    }

    public Optional<ClientTunnelSession> session(int sessionId) {
        return Optional.ofNullable(sessions.get(sessionId));
    }

    public Collection<ClientTunnelSession> sessions() {
        return new ArrayList<>(sessions.values());
    }

    public int sessionCount() {
        return sessions.size();
    }

    private ClientTunnelSession newSession(int sessionId) {
        return new ClientTunnelSession(sessionId, bridge,
                new StreamRegistry(DEFAULT_MAX_STREAMS, true),
                streamFactory,
                System.currentTimeMillis(),
                listenerFactory.apply(sessionId));
    }
}
```

- [ ] **Step 4: Run tests to verify green**

Run:

```bash
./gradlew test --tests dev.kifuko.mctransport.client.ClientTunnelSessionManagerTest
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/dev/kifuko/mctransport/client/ClientTunnelSessionManager.java \
        src/test/java/dev/kifuko/mctransport/client/ClientTunnelSessionManagerTest.java
git commit -m "feat: manage client tunnel sessions by port"
```

---

### Task 8: Fabric Client Uses ClientTunnelSessionManager

**Files:**
- Modify: `src/fabric1201/client/java/dev/kifuko/mctransport/client/McTransportClient.java`
- Modify: `src/fabric1211/client/java/dev/kifuko/mctransport/client/McTransportClient.java`

- [ ] **Step 1: Update client entrypoints to compile against manager**

In both client files, replace:

```java
private final AtomicReference<ClientTunnelSession> session = new AtomicReference<>();
private final AtomicReference<ClientListenerController> listenerController = new AtomicReference<>();
```

with:

```java
private final AtomicReference<ClientTunnelSessionManager> sessionManager = new AtomicReference<>();
```

Replace `StreamRegistry registry = ...` setup with no shared registry. Keep `FrameCodec`, `TransportExecutors`, bridge, and stream factory setup.

Create one shared stream factory:

```java
ClientStreamFactory streamFactory = new DefaultClientStreamFactory(
        new dev.kifuko.mctransport.buffer.BufferBudget(
                DEFAULT_STREAM_BUFFER_SIZE, DEFAULT_GLOBAL_BUFFER_SIZE),
        new dev.kifuko.mctransport.buffer.ReservationState(),
        DEFAULT_STREAM_BUFFER_SIZE,
        new KcpConfig());
```

Create manager:

```java
ClientTunnelSessionManager manager = new ClientTunnelSessionManager(
        bridge,
        streamFactory,
        sessionId -> new DynamicLocalTcpListenerController(
                executors,
                () -> sessionManager.get().session(sessionId)
                        .orElseThrow(() -> new IllegalStateException(
                                "route session missing: " + sessionId)),
                null));
sessionManager.set(manager);
```

Update receiver:

```java
bridge.setReceiver(frame -> {
    ClientTunnelSessionManager manager = sessionManager.get();
    if (manager != null) {
        manager.handleInbound(frame);
    }
});
```

Update disconnect:

```java
ClientTunnelSessionManager active = sessionManager.get();
if (active != null) {
    active.closeAll();
}
```

Update tick:

```java
ClientTunnelSessionManager manager = sessionManager.get();
if (manager != null) {
    manager.tick(System.currentTimeMillis());
}
```

- [ ] **Step 2: Compile both Minecraft targets**

Run:

```bash
./gradlew compileJava compileClientJava -PtargetMinecraft=1.21.1
./gradlew compileJava compileClientJava -PtargetMinecraft=1.20.1
```

Expected: PASS. If imports fail, remove unused `StreamRegistry`, `ClientTunnelSession`, and `ClientListenerController` imports and add `ClientStreamFactory`.

- [ ] **Step 3: Commit**

```bash
git add src/fabric1201/client/java/dev/kifuko/mctransport/client/McTransportClient.java \
        src/fabric1211/client/java/dev/kifuko/mctransport/client/McTransportClient.java
git commit -m "feat: route client frames to sessions by port"
```

---

### Task 9: Fabric Commands Require Listen Port for Unset

**Files:**
- Modify: `src/fabric1201/main/java/dev/kifuko/mctransport/server/McTransportCommands.java`
- Modify: `src/fabric1211/main/java/dev/kifuko/mctransport/server/McTransportCommands.java`

- [ ] **Step 1: Update unset command in both Fabric versions**

Replace `unsetCommand` in both files with:

```java
private static com.mojang.brigadier.builder.LiteralArgumentBuilder<ServerCommandSource> unsetCommand(
        RouteCommandService service) {
    return CommandManager.literal("unset")
            .then(CommandManager.argument("playerName", StringArgumentType.word())
                    .then(CommandManager.argument("listenPort",
                                    IntegerArgumentType.integer(1, 65535))
                            .executes(ctx -> unset(service, ctx.getSource(),
                                    StringArgumentType.getString(ctx, "playerName"),
                                    IntegerArgumentType.getInteger(ctx, "listenPort")))));
}
```

Replace private `unset` method with:

```java
private static int unset(RouteCommandService service, ServerCommandSource source,
                         String playerName, int listenPort) {
    ResolvedPlayer player = resolvePlayer(source.getServer(), playerName);
    String message = service.unsetRoute(player.uuid(), player.name(), listenPort);
    source.sendFeedback(() -> Text.literal(message), true);
    return 1;
}
```

- [ ] **Step 2: Compile both Minecraft targets**

Run:

```bash
./gradlew compileJava -PtargetMinecraft=1.21.1
./gradlew compileJava -PtargetMinecraft=1.20.1
```

Expected: PASS.

- [ ] **Step 3: Commit**

```bash
git add src/fabric1201/main/java/dev/kifuko/mctransport/server/McTransportCommands.java \
        src/fabric1211/main/java/dev/kifuko/mctransport/server/McTransportCommands.java
git commit -m "feat: unset routes by player and port"
```

---

### Task 10: Full Test and E2E Compatibility Sweep

**Files:**
- Modify tests only if compile-time API migration requires it.

- [ ] **Step 1: Run all unit tests for default target**

Run:

```bash
./gradlew test -PtargetMinecraft=1.21.1
```

Expected: PASS.

- [ ] **Step 2: Run all unit tests for 1.20.1 target**

Run:

```bash
./gradlew test -PtargetMinecraft=1.20.1
```

Expected: PASS.

- [ ] **Step 3: Run build matrix script if available**

Run:

```bash
scripts/test_matrix.sh
```

Expected: PASS for supported Minecraft targets.

- [ ] **Step 4: Inspect remaining static single-session assumptions**

Run:

```bash
rg -n "SESSION_ID|routeFor\\(UUID|removeRoute\\(UUID|Map<UUID, PlayerTunnelSession>|AtomicReference<ClientTunnelSession>|unsetRoute\\(" src/main src/fabric1201 src/fabric1211 src/test
```

Expected remaining matches:

- `Frame` and protocol tests may mention `sessionId`.
- Backward-compatible helpers may remain in `ServerConfig`, `RouteStore`, and `ClientTunnelSession`.
- No runtime path should create route frames with hard-coded `SESSION_ID = 0`.

- [ ] **Step 5: Commit any migration fixes**

If Step 4 required code changes:

```bash
git add src
git commit -m "fix: remove remaining single-route assumptions"
```

If Step 4 required no code changes, do not create an empty commit.

---

### Task 11: Manual Smoke Test Notes

**Files:**
- Modify: `docs/manual-smoke-test.md`

- [ ] **Step 1: Document multi-route smoke commands**

Add a section:

````markdown
## Multi-Route Player Smoke Test

Configure two routes for the same player:

```text
/mctransport set Steve 25580 127.0.0.1 10000 DIRECT
/mctransport set Steve 25581 127.0.0.1 10001 KCP
/mctransport list
```

Expected:

- `list` shows two rows for Steve.
- One row contains `127.0.0.1:25580 -> 127.0.0.1:10000 (mode=DIRECT)`.
- One row contains `127.0.0.1:25581 -> 127.0.0.1:10001 (mode=KCP)`.
- The client binds both loopback ports after joining.
- Traffic sent to local port `25580` reaches target port `10000`.
- Traffic sent to local port `25581` reaches target port `10001`.

Remove one route:

```text
/mctransport unset Steve 25580
/mctransport list
```

Expected:

- The `25580` route is gone.
- The `25581` route remains active.
````

- [ ] **Step 2: Commit docs**

```bash
git add docs/manual-smoke-test.md
git commit -m "docs: add multi-route smoke test"
```

---

### Task 12: Final Verification

**Files:**
- No file changes expected.

- [ ] **Step 1: Run verification before completion**

Run:

```bash
./gradlew test -PtargetMinecraft=1.21.1
./gradlew test -PtargetMinecraft=1.20.1
scripts/test_matrix.sh
git status --short
```

Expected:

- Both Gradle test commands pass.
- Matrix script passes.
- `git status --short` is clean.

- [ ] **Step 2: Prepare completion summary**

Include:

- Config route identity changed to `(player_uuid, listen_port)`.
- Server sessions dispatch by player UUID plus frame `sessionId`.
- Client sessions dispatch by frame `sessionId`.
- `DIRECT` and `KCP` remain per-route.
- `unset` now requires a listen port.
- Verification commands and outcomes.
