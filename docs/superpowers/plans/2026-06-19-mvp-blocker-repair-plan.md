# MVP Blocker Repair Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:test-driven-development to implement each behavior. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Repair the implementation so the Fabric mod can authenticate, encrypt all frame payloads, route server responses to the correct player, preserve TCP bytes under backpressure, and provide runnable regression coverage for those behaviors.

**Architecture:** Keep the existing `TunnelBridge` abstraction for unit tests, but add focused helpers for encrypted frame wrapping/unwrapping and a server-side session-aware bridge path. The client entrypoint owns client config and auth-on-join; the server entrypoint passes loaded server config into the bridge, and each `PlayerTunnelSession` sends responses through a per-player bridge wrapper.

**Tech Stack:** Java 25, Fabric API CustomPayload networking, JUnit 5 tests, JDK AES-GCM.

---

## Task 1: Encrypted Frame Transport

**Files:**
- Create: `src/main/java/dev/kifuko/mctransport/protocol/SecureFrameCodec.java`
- Create: `src/test/java/dev/kifuko/mctransport/protocol/SecureFrameCodecTest.java`
- Modify: `ClientTunnelSession`, `ClientStream`, `PlayerTunnelSession`, `ServerStream`, `DefaultServerStreamFactory`

- [ ] Add a failing test that `OPEN`, `DATA`, and `AUTH_OK` wire frames do not expose plaintext payload bytes and decrypt back to the original frame.
- [ ] Implement `SecureFrameCodec.encryptForSend(Frame)` and `decryptReceived(Frame)`.
- [ ] Replace direct `bridge.send(frame)` from sessions/streams with encrypted send helpers.
- [ ] Decrypt every inbound non-plain frame before state-machine dispatch.

## Task 2: Client Auth on Real Join

**Files:**
- Modify: `src/client/java/dev/kifuko/mctransport/client/McTransportClient.java`
- Create: `src/test/java/dev/kifuko/mctransport/client/ClientJoinAuthWiringTest.java`

- [ ] Add a failing test around a small join-auth helper that calls `sendAuth(playerUuid, nowSeconds)`.
- [ ] Call the helper from `ClientPlayConnectionEvents.JOIN`.
- [ ] Stop listener/session/bridge/executors on disconnect.

## Task 3: Server Config Propagation and Targeted Sends

**Files:**
- Modify: `McTransportServer`
- Modify: `FabricServerTunnelBridge`
- Create: `src/test/java/dev/kifuko/mctransport/server/FabricServerBridgeConfigTest.java`

- [ ] Add a failing test that `FabricServerTunnelBridge` session creation uses the loaded `ServerConfig`, not `ServerConfig.defaultFor`.
- [ ] Store the player associated with each session.
- [ ] Send outbound frames only to the player for that session.

## Task 4: Backpressure Without Byte Loss

**Files:**
- Modify: `ClientStream`
- Modify: `ServerStream`
- Create: `src/test/java/dev/kifuko/mctransport/client/ClientStreamBackpressureTest.java`
- Create: `src/test/java/dev/kifuko/mctransport/server/ServerStreamBackpressureTest.java`

- [ ] Add failing tests showing bytes are retried after a failed reservation.
- [ ] Reserve before reading where possible, or retry sending the current chunk until reservation succeeds or stream closes.
- [ ] Never drop bytes silently on reservation failure.

## Task 5: Verification

**Files:**
- Modify disabled integration tests or replace them with enabled equivalents.
- Add Gradle wrapper if a trusted local Gradle installation becomes available.

- [ ] Run targeted tests for each repair.
- [ ] Run full test suite if a Gradle runner is available.
- [ ] Report any blocked verification explicitly.
