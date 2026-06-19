# Minecraft Transport Dialer Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a Fabric client/server mod pair that transports local TCP byte streams through a real Minecraft client-to-server mod networking channel and restores them to a fixed TCP target on the server side.

**Architecture:** The project is split into common protocol/crypto/config modules plus client-side local TCP intake and server-side fixed target dialing. All Minecraft networking carries encrypted internal frames only; proxy protocols, DNS, routing, and arbitrary remote target selection stay outside this project.

**Tech Stack:** Java, Gradle, Fabric Loader/Fabric API, JUnit 5, Java NIO sockets, AES-GCM from JDK crypto APIs, TOML-style config files loaded at mod startup.

---

## File Structure

- `settings.gradle`: Gradle project name.
- `build.gradle`: Fabric Loom build, dependencies, test setup.
- `gradle.properties`: Minecraft, Fabric, Java, and mod metadata versions pinned by Task 0.
- `src/main/resources/fabric.mod.json`: Client and server entrypoints.
- `src/main/resources/mctransport.client.toml`: Example client config.
- `src/main/resources/mctransport.server.toml`: Example server config.
- `src/main/java/dev/kifuko/mctransport/McTransport.java`: Shared constants and logger.
- `src/main/java/dev/kifuko/mctransport/client/McTransportClient.java`: Fabric client entrypoint.
- `src/main/java/dev/kifuko/mctransport/server/McTransportServer.java`: Fabric server entrypoint.
- `src/main/java/dev/kifuko/mctransport/config/ClientConfig.java`: Client config model and validation.
- `src/main/java/dev/kifuko/mctransport/config/ServerConfig.java`: Server config model and validation.
- `src/main/java/dev/kifuko/mctransport/config/ConfigLoader.java`: Config file loading and default generation.
- `src/main/java/dev/kifuko/mctransport/protocol/FrameType.java`: Internal frame type enum.
- `src/main/java/dev/kifuko/mctransport/protocol/Frame.java`: Immutable internal frame value.
- `src/main/java/dev/kifuko/mctransport/protocol/FrameCodec.java`: Binary frame encode/decode.
- `src/main/java/dev/kifuko/mctransport/crypto/PskCipher.java`: PSK-derived AES-GCM encryption/decryption.
- `src/main/java/dev/kifuko/mctransport/auth/AuthPayload.java`: Auth payload encode/decode.
- `src/main/java/dev/kifuko/mctransport/buffer/BufferBudget.java`: Per-stream and global buffer accounting.
- `src/main/java/dev/kifuko/mctransport/stream/StreamState.java`: Stream lifecycle states.
- `src/main/java/dev/kifuko/mctransport/stream/StreamRegistry.java`: Stream ID allocation and lookup.
- `src/main/java/dev/kifuko/mctransport/net/TunnelBridge.java`: Interface for sending frames across Minecraft networking.
- `src/main/java/dev/kifuko/mctransport/client/LocalTcpListener.java`: Client local TCP listener.
- `src/main/java/dev/kifuko/mctransport/client/ClientStream.java`: One client-side TCP stream.
- `src/main/java/dev/kifuko/mctransport/client/ClientTunnelSession.java`: Authenticated Minecraft tunnel session on client.
- `src/main/java/dev/kifuko/mctransport/server/PlayerTunnelSession.java`: Authenticated Minecraft tunnel session per player on server.
- `src/main/java/dev/kifuko/mctransport/server/TargetTcpConnector.java`: Server fixed target connector.
- `src/main/java/dev/kifuko/mctransport/server/ServerStream.java`: One server-side target TCP stream.
- `src/test/java/dev/kifuko/mctransport/...`: Unit and loopback tests matching the package under test.

## Atomic Tasks

### Task 0: Build Version Pinning

**Files:**
- Create: `docs/build-version-notes.md`
- Create: `gradle.properties`

- [ ] Select one Minecraft Java version supported by current Fabric tooling.
- [ ] Select matching Fabric Loader, Fabric API, Yarn mappings, and Fabric Loom versions.
- [ ] Record the source URL and access date for each selected version in `docs/build-version-notes.md`.
- [ ] Pin the selected values in `gradle.properties`.
- [ ] Pin Java language level compatible with the selected Minecraft version.
- [ ] Do not select snapshot, beta, or release-candidate versions for the MVP baseline.

**Acceptance:** `gradle.properties` contains concrete version values and `docs/build-version-notes.md` explains where each value came from.

### Task 1: Project Scaffold

**Files:**
- Create: `settings.gradle`
- Create: `build.gradle`
- Create: `src/main/resources/fabric.mod.json`
- Create: `src/main/java/dev/kifuko/mctransport/McTransport.java`

- [ ] Add a minimal Fabric Java project named `mc-transport-dialer`.
- [ ] Configure Java compilation using the pinned values from `gradle.properties`.
- [ ] Add JUnit 5 test support.
- [ ] Register client entrypoint `dev.kifuko.mctransport.client.McTransportClient`.
- [ ] Register server entrypoint `dev.kifuko.mctransport.server.McTransportServer`.
- [ ] Verify `./gradlew test` or `gradle test` reaches test discovery with zero tests.

**Acceptance:** A clean checkout can compile the empty mod entrypoint classes and run the test task.

### Task 2: Entrypoint Skeletons

**Files:**
- Create: `src/main/java/dev/kifuko/mctransport/client/McTransportClient.java`
- Create: `src/main/java/dev/kifuko/mctransport/server/McTransportServer.java`

- [ ] Implement Fabric client initializer with one startup log line.
- [ ] Implement Fabric server initializer with one startup log line.
- [ ] Do not open sockets yet.
- [ ] Do not register frame handlers yet.

**Acceptance:** Starting either side logs that MC Transport Dialer loaded without changing game behavior.

### Task 3: Example Config Files

**Files:**
- Create: `src/main/resources/mctransport.client.toml`
- Create: `src/main/resources/mctransport.server.toml`

- [ ] Add client defaults: `enabled=true`, `listen_host="127.0.0.1"`, `listen_port=25580`, `channel_name="mctransport:main"`, `psk="change-me"`, `max_streams=64`, `stream_buffer_size=1048576`, `global_buffer_size=33554432`, `log_level="info"`.
- [ ] Add server defaults: `enabled=true`, `target_host="127.0.0.1"`, `target_port=10000`, `channel_name="mctransport:main"`, `psk="change-me"`, `allowed_players=["player-uuid-here"]`, `max_streams_per_player=64`, `stream_buffer_size=1048576`, `global_buffer_size_per_player=33554432`, `idle_timeout_seconds=300`, `connect_timeout_seconds=10`, `log_level="info"`.
- [ ] Keep configs transport-only; do not add SOCKS, HTTP, DNS, route, or arbitrary target fields.

**Acceptance:** Example files exactly represent the PRD MVP defaults.

### Task 4: Config Models and Validation

**Files:**
- Create: `src/main/java/dev/kifuko/mctransport/config/ClientConfig.java`
- Create: `src/main/java/dev/kifuko/mctransport/config/ServerConfig.java`
- Create: `src/test/java/dev/kifuko/mctransport/config/ConfigValidationTest.java`

- [ ] Model all fields from both example config files.
- [ ] Validate ports are `1..65535`.
- [ ] Validate buffer sizes are positive.
- [ ] Validate `max_streams` and `max_streams_per_player` are positive.
- [ ] Validate client `listen_host` defaults to loopback.
- [ ] Validate server `target_host` is present and `allowed_players` is non-empty when enabled.
- [ ] Add tests for valid defaults and one invalid value per validation rule.

**Acceptance:** Config validation fails closed before any listener or tunnel session starts.

### Task 5: Config Loader

**Files:**
- Create: `src/main/java/dev/kifuko/mctransport/config/ConfigLoader.java`
- Create: `src/test/java/dev/kifuko/mctransport/config/ConfigLoaderTest.java`

- [ ] Load client config from Fabric config directory path `mctransport.client.toml`.
- [ ] Load server config from Fabric config directory path `mctransport.server.toml`.
- [ ] Copy bundled example config when the file does not exist.
- [ ] Return validated `ClientConfig` or `ServerConfig`.
- [ ] Make malformed config return a clear exception that includes the config file path.

**Acceptance:** Tests prove missing files are generated, valid files load, and invalid files fail before startup continues.

### Task 6: Frame Type Enum

**Files:**
- Create: `src/main/java/dev/kifuko/mctransport/protocol/FrameType.java`
- Create: `src/test/java/dev/kifuko/mctransport/protocol/FrameTypeTest.java`

- [ ] Define frame types `AUTH`, `AUTH_OK`, `OPEN`, `DATA`, `CLOSE`, `RESET`, `PING`, `PONG`, `ERROR`.
- [ ] Assign stable numeric IDs starting at `1`.
- [ ] Add lookup from numeric ID to enum.
- [ ] Make unknown numeric IDs fail with a protocol exception.

**Acceptance:** Numeric IDs are stable and unknown frame types cannot be decoded silently.

### Task 7: Frame Value Object

**Files:**
- Create: `src/main/java/dev/kifuko/mctransport/protocol/Frame.java`
- Create: `src/test/java/dev/kifuko/mctransport/protocol/FrameTest.java`

- [ ] Store protocol version, session ID, stream ID, frame type, flags, and payload bytes.
- [ ] Copy payload bytes on construction and read access.
- [ ] Reject negative stream IDs.
- [ ] Reject payloads larger than the configured maximum passed to the factory method.

**Acceptance:** `Frame` is immutable and enforces basic protocol limits.

### Task 8: Binary Frame Codec

**Files:**
- Create: `src/main/java/dev/kifuko/mctransport/protocol/FrameCodec.java`
- Create: `src/test/java/dev/kifuko/mctransport/protocol/FrameCodecTest.java`

- [ ] Encode header fields in a deterministic byte order.
- [ ] Include protocol version, session ID, stream ID, frame type, flags, payload length, and encrypted payload bytes.
- [ ] Decode exactly one frame from bytes.
- [ ] Reject truncated headers.
- [ ] Reject payload length larger than the configured maximum.
- [ ] Round-trip every frame type in tests.

**Acceptance:** Frame bytes are deterministic, bounded, and round-trip through encode/decode.

### Task 9: PSK Cipher

**Files:**
- Create: `src/main/java/dev/kifuko/mctransport/crypto/PskCipher.java`
- Create: `src/test/java/dev/kifuko/mctransport/crypto/PskCipherTest.java`

- [ ] Derive an AES-GCM key from the configured PSK using SHA-256.
- [ ] Generate a unique 12-byte nonce per encrypted frame.
- [ ] Authenticate header metadata as associated data.
- [ ] Return ciphertext with nonce and tag included.
- [ ] Reject wrong PSK, modified ciphertext, modified nonce, and modified associated data.

**Acceptance:** Tests prove payload confidentiality and integrity for successful and tampered frames.

### Task 10: Encrypted Frame Pipeline

**Files:**
- Modify: `src/main/java/dev/kifuko/mctransport/protocol/FrameCodec.java`
- Modify: `src/main/java/dev/kifuko/mctransport/crypto/PskCipher.java`
- Create: `src/test/java/dev/kifuko/mctransport/protocol/EncryptedFramePipelineTest.java`

- [ ] Encrypt payload before frame encode.
- [ ] Decrypt payload after frame decode.
- [ ] Keep header fields visible so receiver can route by stream ID and type.
- [ ] Treat decryption failure as authentication failure.

**Acceptance:** A sender can encrypt, encode, decode, and decrypt a frame; tampering anywhere in the encrypted payload fails.

### Task 11: Auth Payload

**Files:**
- Create: `src/main/java/dev/kifuko/mctransport/auth/AuthPayload.java`
- Create: `src/test/java/dev/kifuko/mctransport/auth/AuthPayloadTest.java`

- [ ] Encode player UUID, client nonce, timestamp seconds, and protocol version.
- [ ] Decode the same fields.
- [ ] Reject mismatched protocol version.
- [ ] Reject timestamps outside a small configurable skew window.

**Acceptance:** `AUTH` payloads are compact, parseable, and reject stale or wrong-version data.

### Task 12: Server Auth and Whitelist

**Files:**
- Create: `src/main/java/dev/kifuko/mctransport/server/PlayerTunnelSession.java`
- Create: `src/test/java/dev/kifuko/mctransport/server/PlayerTunnelSessionAuthTest.java`

- [ ] Start each player session unauthenticated.
- [ ] Accept `AUTH` only when player UUID is in `allowed_players`.
- [ ] Validate PSK by decrypting the `AUTH` frame.
- [ ] Send `AUTH_OK` only after UUID and PSK checks pass.
- [ ] Reject `OPEN`, `DATA`, `CLOSE`, `RESET`, `PING`, and `ERROR` before auth succeeds.

**Acceptance:** Unauthorized players and unauthenticated streams cannot open transport streams.

### Task 13: Stream Registry

**Files:**
- Create: `src/main/java/dev/kifuko/mctransport/stream/StreamState.java`
- Create: `src/main/java/dev/kifuko/mctransport/stream/StreamRegistry.java`
- Create: `src/test/java/dev/kifuko/mctransport/stream/StreamRegistryTest.java`

- [ ] Allocate monotonically increasing positive stream IDs on the client.
- [ ] Register server streams from client-provided stream IDs.
- [ ] Enforce configured max stream count.
- [ ] Remove streams on `CLOSE` or `RESET`.
- [ ] Provide lookup by stream ID.

**Acceptance:** Stream lifecycle is deterministic and max stream count is enforced.

### Task 14: Buffer Budget

**Files:**
- Create: `src/main/java/dev/kifuko/mctransport/buffer/BufferBudget.java`
- Create: `src/test/java/dev/kifuko/mctransport/buffer/BufferBudgetTest.java`

- [ ] Track reserved bytes per stream.
- [ ] Track reserved bytes globally.
- [ ] Reject reservations that exceed per-stream or global limits.
- [ ] Release reservations on send completion and stream cleanup.
- [ ] Make double release impossible or harmless.

**Acceptance:** Queue limits are enforced without leaking reserved bytes after close or reset.

### Task 15: Tunnel Bridge Interface

**Files:**
- Create: `src/main/java/dev/kifuko/mctransport/net/TunnelBridge.java`
- Create: `src/test/java/dev/kifuko/mctransport/net/FakeTunnelBridge.java`

- [ ] Define `send(Frame frame)` for outbound Minecraft-channel delivery.
- [ ] Define receiver callback registration for inbound frames.
- [ ] Provide a fake in-memory bridge for tests.
- [ ] Keep Fabric-specific networking out of protocol and stream tests.

**Acceptance:** Protocol and stream code can be tested without launching Minecraft.

### Task 16: Client Tunnel Session

**Files:**
- Create: `src/main/java/dev/kifuko/mctransport/client/ClientTunnelSession.java`
- Create: `src/test/java/dev/kifuko/mctransport/client/ClientTunnelSessionTest.java`

- [ ] Send encrypted `AUTH` when a Minecraft server connection becomes available.
- [ ] Mark session authenticated after encrypted `AUTH_OK`.
- [ ] Reject local stream opening before `AUTH_OK`.
- [ ] Route inbound frames by stream ID after authentication.
- [ ] Send `RESET` for unknown stream IDs.

**Acceptance:** Client tunnel state prevents local TCP streams from using the channel before auth completes.

### Task 17: Local TCP Listener

**Files:**
- Create: `src/main/java/dev/kifuko/mctransport/client/LocalTcpListener.java`
- Create: `src/test/java/dev/kifuko/mctransport/client/LocalTcpListenerTest.java`

- [ ] Bind only to configured `listen_host` and `listen_port`.
- [ ] Accept multiple local TCP connections until `max_streams` is reached.
- [ ] For each accepted socket, request a new stream from `ClientTunnelSession`.
- [ ] Close new sockets immediately when config is disabled or session is unauthenticated.
- [ ] Run accept loop outside render and Minecraft Netty threads.

**Acceptance:** The listener creates streams only when enabled and authenticated.

### Task 18: Client Stream Reads

**Files:**
- Create: `src/main/java/dev/kifuko/mctransport/client/ClientStream.java`
- Create: `src/test/java/dev/kifuko/mctransport/client/ClientStreamReadTest.java`

- [ ] Send `OPEN` once for a new local socket.
- [ ] Read local socket bytes into bounded chunks.
- [ ] Convert chunks to `DATA` frames.
- [ ] Pause reading when `BufferBudget` rejects a reservation.
- [ ] Send `CLOSE` on normal local EOF.
- [ ] Send `RESET` on socket read error.

**Acceptance:** One local socket maps to one stream ID and produces bounded `OPEN`, `DATA`, `CLOSE`, and `RESET` frames.

### Task 19: Client Stream Writes

**Files:**
- Modify: `src/main/java/dev/kifuko/mctransport/client/ClientStream.java`
- Create: `src/test/java/dev/kifuko/mctransport/client/ClientStreamWriteTest.java`

- [ ] Write inbound `DATA` payloads to the local socket.
- [ ] Close the local socket on inbound `CLOSE`.
- [ ] Close the local socket on inbound `RESET`.
- [ ] Release buffer reservations after writes complete.
- [ ] Ignore duplicate close frames after cleanup.

**Acceptance:** Server-returned bytes are delivered to the original local TCP connection and cleanup is idempotent.

### Task 20: Server Target Connector

**Files:**
- Create: `src/main/java/dev/kifuko/mctransport/server/TargetTcpConnector.java`
- Create: `src/test/java/dev/kifuko/mctransport/server/TargetTcpConnectorTest.java`

- [ ] Connect only to configured `target_host` and `target_port`.
- [ ] Apply configured connect timeout.
- [ ] Do not accept target host or target port from any client frame.
- [ ] Return a connected socket on success.
- [ ] Return a typed failure on timeout or connection refusal.

**Acceptance:** Server-side dialing is fixed by config and cannot become an open proxy.

### Task 21: Server Stream Open

**Files:**
- Create: `src/main/java/dev/kifuko/mctransport/server/ServerStream.java`
- Modify: `src/main/java/dev/kifuko/mctransport/server/PlayerTunnelSession.java`
- Create: `src/test/java/dev/kifuko/mctransport/server/ServerStreamOpenTest.java`

- [ ] On authenticated `OPEN`, create one `ServerStream`.
- [ ] Dial the fixed target through `TargetTcpConnector`.
- [ ] Register the stream only after target connection succeeds.
- [ ] Send `ERROR` or `RESET` when target connection fails.
- [ ] Enforce `max_streams_per_player`.

**Acceptance:** Server opens fixed target TCP sockets only for authenticated players within stream limits.

### Task 22: Server Stream Forwarding

**Files:**
- Modify: `src/main/java/dev/kifuko/mctransport/server/ServerStream.java`
- Create: `src/test/java/dev/kifuko/mctransport/server/ServerStreamForwardingTest.java`

- [ ] Write inbound Minecraft `DATA` payloads to the target TCP socket.
- [ ] Read target TCP socket bytes into bounded chunks.
- [ ] Send target bytes back as encrypted `DATA` frames.
- [ ] Send `CLOSE` on target EOF.
- [ ] Send `RESET` on target socket error.
- [ ] Run target socket I/O outside server tick and Minecraft Netty threads.

**Acceptance:** Bytes flow in both directions between Minecraft frames and the fixed target socket.

### Task 23: Close and Reset Semantics

**Files:**
- Modify: `src/main/java/dev/kifuko/mctransport/client/ClientStream.java`
- Modify: `src/main/java/dev/kifuko/mctransport/server/ServerStream.java`
- Modify: `src/main/java/dev/kifuko/mctransport/stream/StreamRegistry.java`
- Create: `src/test/java/dev/kifuko/mctransport/stream/CloseResetSemanticsTest.java`

- [ ] Treat `CLOSE` as full stream close for MVP.
- [ ] Treat `RESET` as immediate close with error status.
- [ ] Remove stream registry entries on close and reset.
- [ ] Release all buffer reservations on close and reset.
- [ ] Make repeated close and reset calls safe.

**Acceptance:** MVP full-close behavior is consistent and does not leak streams or buffers.

### Task 24: Ping, Pong, and Idle Timeout

**Files:**
- Modify: `src/main/java/dev/kifuko/mctransport/client/ClientTunnelSession.java`
- Modify: `src/main/java/dev/kifuko/mctransport/server/PlayerTunnelSession.java`
- Create: `src/test/java/dev/kifuko/mctransport/stream/PingPongIdleTimeoutTest.java`

- [ ] Reply to `PING` with `PONG`.
- [ ] Track last inbound frame time per session.
- [ ] Send periodic `PING` only while authenticated.
- [ ] Close all streams on idle timeout.
- [ ] Do not rely on Minecraft keepalive for transport liveness.

**Acceptance:** Tunnel sessions can detect stalled transport state and clean up streams.

### Task 25: Fabric Client Networking

**Files:**
- Modify: `src/main/java/dev/kifuko/mctransport/client/McTransportClient.java`
- Create: `src/main/java/dev/kifuko/mctransport/client/FabricClientTunnelBridge.java`

- [ ] Register the configured Fabric networking channel on client startup.
- [ ] Send encoded encrypted frame bytes over the channel.
- [ ] Decode inbound bytes and hand frames to `ClientTunnelSession`.
- [ ] Start auth when the client joins a server.
- [ ] Stop local listener and streams when the client disconnects.

**Acceptance:** Client Fabric integration is a thin bridge over already-tested tunnel/session code.

### Task 26: Fabric Server Networking

**Files:**
- Modify: `src/main/java/dev/kifuko/mctransport/server/McTransportServer.java`
- Create: `src/main/java/dev/kifuko/mctransport/server/FabricServerTunnelBridge.java`

- [ ] Register the configured Fabric networking channel on server startup.
- [ ] Create one `PlayerTunnelSession` per player after channel traffic arrives.
- [ ] Decode inbound bytes and hand frames to the player session.
- [ ] Send encoded encrypted frame bytes back to the same player.
- [ ] Clean all streams when the player disconnects.

**Acceptance:** Server Fabric integration keeps all blocking I/O outside Minecraft server tick and Netty event loop threads.

### Task 27: Thread Pools and Shutdown

**Files:**
- Create: `src/main/java/dev/kifuko/mctransport/net/TransportExecutors.java`
- Modify: client and server stream classes that perform socket I/O.
- Create: `src/test/java/dev/kifuko/mctransport/net/TransportExecutorsTest.java`

- [ ] Provide named executor services for listener accept loops, socket reads, socket writes, and crypto work if needed.
- [ ] Ensure shutdown closes listeners, sockets, streams, and executor services.
- [ ] Verify shutdown can be called twice.
- [ ] Avoid blocking render, server tick, and Minecraft Netty event-loop threads.

**Acceptance:** All blocking transport work is isolated and can be stopped cleanly on disconnect or mod shutdown.

### Task 28: Logging

**Files:**
- Modify: `src/main/java/dev/kifuko/mctransport/McTransport.java`
- Modify: session, listener, connector, and stream classes.

- [ ] Log startup config summary without printing PSK.
- [ ] Log auth success and auth failure with player UUID.
- [ ] Log listener bind failures.
- [ ] Log target connection failures.
- [ ] Log stream close/reset at debug level.

**Acceptance:** Operators can diagnose startup, auth, and connection failures without leaking the shared secret.

### Task 29: In-Memory Multiplexing Integration Test

**Files:**
- Create: `src/test/java/dev/kifuko/mctransport/integration/InMemoryTunnelIntegrationTest.java`

- [ ] Use `FakeTunnelBridge` to connect one client session to one server session.
- [ ] Authenticate with a whitelisted UUID and matching PSK.
- [ ] Open two concurrent streams.
- [ ] Send distinct byte sequences on both streams.
- [ ] Verify frames are routed to the correct stream IDs.
- [ ] Verify both streams close independently.

**Acceptance:** Multiplexing works for concurrent streams without launching Minecraft.

### Task 30: Loopback TCP Integration Test

**Files:**
- Create: `src/test/java/dev/kifuko/mctransport/integration/LoopbackTcpTransportTest.java`

- [ ] Start a local echo TCP server as the fixed server target.
- [ ] Start a client-side local TCP listener on a random loopback port.
- [ ] Connect client and server sessions through `FakeTunnelBridge`.
- [ ] Write bytes into the client local listener.
- [ ] Verify the same bytes return from the echo server through the transport path.
- [ ] Repeat with at least two concurrent local TCP connections.

**Acceptance:** The MVP transport path works as TCP byte-stream relay in-process.

### Task 31: Negative Security Integration Test

**Files:**
- Create: `src/test/java/dev/kifuko/mctransport/integration/SecurityNegativeTest.java`

- [ ] Verify wrong PSK prevents `AUTH_OK`.
- [ ] Verify non-whitelisted UUID prevents `AUTH_OK`.
- [ ] Verify `OPEN` before auth is rejected.
- [ ] Verify client-provided target data is ignored if included in an `OPEN` payload.
- [ ] Verify tampered encrypted frame payload is rejected.

**Acceptance:** The implementation fails closed for the MVP security requirements.

### Task 32: Manual Minecraft Smoke Test Document

**Files:**
- Create: `docs/manual-smoke-test.md`

- [ ] Document how to install the client mod into a real Minecraft Java client.
- [ ] Document how to install the server mod into a real Fabric server.
- [ ] Document client config path and server config path.
- [ ] Document starting a server-side echo listener on `127.0.0.1:10000`.
- [ ] Document connecting to `127.0.0.1:25580` on the client machine.
- [ ] Document expected result: bytes sent to client local port return from server echo target.

**Acceptance:** A tester can validate the real Minecraft path without knowing the PRD.

### Task 33: README Scope and Non-Goals

**Files:**
- Create: `README.md`

- [ ] State that this project is a Minecraft transport layer, not a full proxy.
- [ ] Show the intended external chain: external client proxy tool, local TCP listener, Minecraft client, Minecraft server, fixed server target, external server proxy tool.
- [ ] List supported MVP features.
- [ ] List non-goals: SOCKS5, HTTP CONNECT, UDP, TUN, DNS, routing, arbitrary target dialing, GUI, session resume, Minecraft protocol simulation.
- [ ] Warn that default PSK `change-me` must be replaced.

**Acceptance:** README prevents users and future agents from expanding scope beyond the PRD.

### Task 34: Release Acceptance Checklist

**Files:**
- Create: `docs/mvp-acceptance-checklist.md`

- [ ] Convert PRD Section 13 into a checklist with exact pass/fail evidence.
- [ ] Include evidence rows for local listener bind, fixed server target dial, multiple concurrent TCP streams, client responsiveness, server TPS, player disconnect cleanup, and unauthorized player rejection.
- [ ] Include the command or manual action used to gather each evidence item.

**Acceptance:** MVP completion can be verified against the original PRD acceptance criteria.

## Sequencing Guidance

- Phase 1 foundation: Tasks 1-8.
- Phase 2 security and auth: Tasks 9-12 and 31.
- Phase 3 streams and buffers: Tasks 13-24.
- Phase 4 Fabric integration: Tasks 25-27.
- Phase 5 docs and acceptance: Tasks 28-34.

## Self-Review

- PRD client responsibilities map to Tasks 16-19, 25, and 27.
- PRD server responsibilities map to Tasks 12, 20-22, 26, and 27.
- PRD frame protocol maps to Tasks 6-11 and 24.
- PRD encryption and authentication maps to Tasks 9-12 and 31.
- PRD flow-control MVP maps to Task 14 plus read-pausing requirements in Tasks 18 and 22.
- PRD Minecraft threading requirements map to Tasks 17, 22, 25, 26, and 27.
- PRD config requirements map to Tasks 3-5.
- PRD MVP acceptance maps to Tasks 29, 30, 31, 32, and 34.
- Non-goals are explicitly guarded in Tasks 3, 20, 31, and 33.
