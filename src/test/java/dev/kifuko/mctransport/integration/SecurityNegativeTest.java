package dev.kifuko.mctransport.integration;

import dev.kifuko.mctransport.buffer.BufferBudget;
import dev.kifuko.mctransport.buffer.ReservationState;
import dev.kifuko.mctransport.config.RouteConfig;
import dev.kifuko.mctransport.config.ServerConfig;
import dev.kifuko.mctransport.net.FakeTunnelBridge;
import dev.kifuko.mctransport.protocol.Frame;
import dev.kifuko.mctransport.protocol.FrameType;
import dev.kifuko.mctransport.protocol.ProtocolException;
import dev.kifuko.mctransport.protocol.RouteControlPayload;
import dev.kifuko.mctransport.server.NoopServerStreamFactoryForTest;
import dev.kifuko.mctransport.server.PlayerTunnelSession;
import dev.kifuko.mctransport.server.RouteStore;
import dev.kifuko.mctransport.server.TargetTcpConnector;
import dev.kifuko.mctransport.stream.StreamRegistry;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SecurityNegativeTest {

    private static final UUID PLAYER =
            UUID.fromString("11111111-2222-3333-4444-555555555555");

    private PlayerTunnelSession buildSession(List<RouteConfig> routes) {
        ServerConfig cfg = new ServerConfig(true, "mctransport:main", routes,
                8, 1024, 8192L, 300, 10, "info");
        FakeTunnelBridge bridge = new FakeTunnelBridge();
        bridge.setReceiver(frame -> { });
        return new PlayerTunnelSession(PLAYER, bridge, cfg,
                new RouteStore(Path.of("build/tmp/test-route-store"),
                        "mctransport.server.toml", cfg),
                new StreamRegistry(8, false),
                new BufferBudget(1024, 8192L), new ReservationState(),
                new TargetTcpConnector(10, Executors.newSingleThreadExecutor()),
                1_700_000_000L, new NoopServerStreamFactoryForTest());
    }

    private RouteConfig route() {
        return new RouteConfig(PLAYER, "Steve", 25580, "127.0.0.1", 10000);
    }

    @Test
    void openWithoutConfiguredRouteIsRejected() {
        PlayerTunnelSession session = buildSession(List.of());
        Frame open = Frame.createTrusted(PlayerTunnelSession.PROTOCOL_VERSION,
                0, 1, FrameType.OPEN, (byte) 0, new byte[0]);
        ProtocolException ex = assertThrows(ProtocolException.class,
                () -> session.handleInbound(open));
        assertTrue(ex.getMessage().contains("before route is active"));
    }

    @Test
    void openBeforeClientAckIsRejected() {
        PlayerTunnelSession session = buildSession(List.of(route()));
        session.sendRouteIfConfigured();
        Frame open = Frame.createTrusted(PlayerTunnelSession.PROTOCOL_VERSION,
                0, 1, FrameType.OPEN, (byte) 0, new byte[0]);
        ProtocolException ex = assertThrows(ProtocolException.class,
                () -> session.handleInbound(open));
        assertTrue(ex.getMessage().contains("before route is active"));
    }

    @Test
    void openAfterFailedAckIsRejected() {
        PlayerTunnelSession session = buildSession(List.of(route()));
        session.sendRouteIfConfigured();
        session.handleInbound(Frame.createTrusted(PlayerTunnelSession.PROTOCOL_VERSION,
                0, 0, FrameType.CONFIG_ACK, (byte) 0,
                RouteControlPayload.encodeAck(false, "bind failed")));
        Frame open = Frame.createTrusted(PlayerTunnelSession.PROTOCOL_VERSION,
                0, 1, FrameType.OPEN, (byte) 0, new byte[0]);
        assertThrows(ProtocolException.class, () -> session.handleInbound(open));
    }

    @Test
    void clientCannotForgeServerConfigFrames() {
        PlayerTunnelSession session = buildSession(List.of(route()));
        Frame forged = Frame.createTrusted(PlayerTunnelSession.PROTOCOL_VERSION,
                0, 0, FrameType.CONFIG_APPLY, (byte) 0,
                RouteControlPayload.encodeApply("127.0.0.1", 25580));
        ProtocolException ex = assertThrows(ProtocolException.class,
                () -> session.handleInbound(forged));
        assertTrue(ex.getMessage().contains("unexpected frame"));
    }
}
