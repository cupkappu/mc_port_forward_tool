package dev.kifuko.mctransport.server;

import dev.kifuko.mctransport.buffer.BufferBudget;
import dev.kifuko.mctransport.buffer.ReservationState;
import dev.kifuko.mctransport.config.RouteConfig;
import dev.kifuko.mctransport.config.ServerConfig;
import dev.kifuko.mctransport.net.FakeTunnelBridge;
import dev.kifuko.mctransport.protocol.FrameType;
import dev.kifuko.mctransport.stream.StreamRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.Socket;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerStreamBackpressureTest {

    @AfterEach
    void restoreFlowControlDefaults() {
        ServerStream.DRAIN_INTERVAL_MS = 150L;
    }

    @Test
    void targetBytesWaitForBudgetInsteadOfBeingDropped() throws Exception {
        FakeTunnelBridge bridge = new FakeTunnelBridge();
        BufferBudget budget = new BufferBudget(8, 8);
        ReservationState reservations = new ReservationState();
        budget.reserve(99, 8, reservations);
        UUID uuid = UUID.randomUUID();
        RouteConfig route = new RouteConfig(uuid, "Steve", 25580,
                "127.0.0.1", 10000);
        ServerConfig config = new ServerConfig(true, "mctransport:main",
                List.of(route), 8, 8, 8L, 300, 10, "info");
        PlayerTunnelSession session = new PlayerTunnelSession(uuid, bridge, config,
                new RouteStore(Path.of("build/tmp/test-route-store"),
                        "mctransport.server.toml", config),
                new StreamRegistry(8, false), budget, reservations,
                new TargetTcpConnector(10, Executors.newSingleThreadExecutor()),
                1_700_000_000L,
                new NoopServerStreamFactoryForTest());
        ServerStream stream = new ServerStream(session, 99, new Socket(), budget,
                reservations, PlayerTunnelSession.PROTOCOL_VERSION, 8);
        byte[] chunk = new byte[]{1, 2, 3, 4};

        Future<?> blockedSend = Executors.newSingleThreadExecutor()
                .submit(() -> stream.sendTargetBytes(chunk, chunk.length));
        Thread.sleep(100);
        assertEquals(0, bridge.sentFrames().size(), "full budget must not drop or send bytes");

        budget.release(99, 8, reservations);
        blockedSend.get(2, TimeUnit.SECONDS);

        assertEquals(1, bridge.sentFrames().size());
        assertEquals(FrameType.DATA, bridge.sentFrames().get(0).type());
        assertArrayEquals(chunk, bridge.sentFrames().get(0).payload());
    }

    @Test
    void partialDrainPreventsDeadlockForUnidirectionalFlow() throws Exception {
        ServerStream.DRAIN_INTERVAL_MS = 50L;

        FakeTunnelBridge bridge = new FakeTunnelBridge();
        // Large per-stream limit so the current stream can reserve a lot,
        // but small global limit to trigger drain from current stream.
        BufferBudget budget = new BufferBudget(1024, 16);
        ReservationState reservations = new ReservationState();
        // Pre-fill: reserve 16 bytes for the SAME stream (99) so partial
        // drain releases from the correct stream.
        budget.reserve(99, 16, reservations);

        UUID uuid = UUID.randomUUID();
        RouteConfig route = new RouteConfig(uuid, "Steve", 25580, "127.0.0.1", 10000);
        ServerConfig config = new ServerConfig(true, "mctransport:main",
                List.of(route), 8, 1024, 32L, 300, 10, "info");
        PlayerTunnelSession session = new PlayerTunnelSession(uuid, bridge, config,
                new RouteStore(Path.of("build/tmp/test-route-store"),
                        "mctransport.server.toml", config),
                new StreamRegistry(8, false), budget, reservations,
                new TargetTcpConnector(10, Executors.newSingleThreadExecutor()),
                1_700_000_000L,
                new NoopServerStreamFactoryForTest());
        ServerStream stream = new ServerStream(session, 99, new Socket(), budget,
                reservations, PlayerTunnelSession.PROTOCOL_VERSION, 1024);
        byte[] chunk = new byte[]{9, 8, 7, 6};

        Future<?> blockedSend = Executors.newSingleThreadExecutor()
                .submit(() -> stream.sendTargetBytes(chunk, chunk.length));

        // Partial drain (25% every 50ms) should free enough budget
        // within a few drain cycles; 5 s timeout is generous.
        blockedSend.get(5, TimeUnit.SECONDS);

        assertEquals(1, bridge.sentFrames().size(),
                "partial drain should have freed budget, frame count");
        assertEquals(FrameType.DATA, bridge.sentFrames().get(0).type());
        assertArrayEquals(chunk, bridge.sentFrames().get(0).payload());
    }
}
