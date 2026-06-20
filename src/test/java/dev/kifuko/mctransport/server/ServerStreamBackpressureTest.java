package dev.kifuko.mctransport.server;

import dev.kifuko.mctransport.buffer.BufferBudget;
import dev.kifuko.mctransport.buffer.ReservationState;
import dev.kifuko.mctransport.config.RouteConfig;
import dev.kifuko.mctransport.config.ServerConfig;
import dev.kifuko.mctransport.net.FakeTunnelBridge;
import dev.kifuko.mctransport.protocol.FrameType;
import dev.kifuko.mctransport.stream.StreamRegistry;
import org.junit.jupiter.api.Test;

import java.net.Socket;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ServerStreamBackpressureTest {

    @Test
    void sendTargetBytesSucceedsEvenWhenBudgetIsFull() throws Exception {
        FakeTunnelBridge bridge = new FakeTunnelBridge();
        BufferBudget budget = new BufferBudget(8, 8);
        ReservationState reservations = new ReservationState();
        // Pre-fill budget (8/8 bytes used)
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

        // Semaphore-based flow control: even with budget full, sendTargetBytes
        // proceeds (budget failure is caught). The semaphore limits in-flight
        // frames to prevent OOM.
        stream.sendTargetBytes(chunk, chunk.length);

        assertEquals(1, bridge.sentFrames().size());
        assertEquals(FrameType.DATA, bridge.sentFrames().get(0).type());
        assertArrayEquals(chunk, bridge.sentFrames().get(0).payload());
    }

    @Test
    void reserveOrWaitReturnsFalseWhenBudgetFull() throws Exception {
        FakeTunnelBridge bridge = new FakeTunnelBridge();
        BufferBudget budget = new BufferBudget(8, 8);
        ReservationState reservations = new ReservationState();
        budget.reserve(99, 8, reservations);

        UUID uuid = UUID.randomUUID();
        RouteConfig route = new RouteConfig(uuid, "Steve", 25580, "127.0.0.1", 10000);
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

        // reserveOrWait no longer blocks; returns false immediately when full
        assertEquals(false, stream.reserveOrWait(4));
    }
}
