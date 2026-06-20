package dev.kifuko.mctransport.server;

import dev.kifuko.mctransport.buffer.BufferBudget;
import dev.kifuko.mctransport.buffer.ReservationState;
import dev.kifuko.mctransport.config.RouteConfig;
import dev.kifuko.mctransport.config.ServerConfig;
import dev.kifuko.mctransport.net.FakeTunnelBridge;
import dev.kifuko.mctransport.protocol.Frame;
import dev.kifuko.mctransport.protocol.FrameType;
import dev.kifuko.mctransport.protocol.ProtocolException;
import dev.kifuko.mctransport.protocol.RouteControlPayload;
import dev.kifuko.mctransport.stream.StreamRegistry;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerTunnelSessionAuthTest {

    private static final UUID PLAYER_UUID =
            UUID.fromString("11111111-2222-3333-4444-555555555555");

    private FakeTunnelBridge bridge;
    private StreamRegistry registry;
    private PlayerTunnelSession session;

    private RouteConfig route() {
        return new RouteConfig(PLAYER_UUID, "Steve", 25580,
                "127.0.0.1", 10000);
    }

    private ServerConfig config(List<RouteConfig> routes) {
        return new ServerConfig(true, "mctransport:main", routes,
                8, 1024, 8192L, 300, 10, "info");
    }

    private RouteStore store(ServerConfig config) {
        return new RouteStore(Path.of("build/tmp/test-route-store"),
                "mctransport.server.toml", config);
    }

    private void setUp(List<RouteConfig> routes, ServerStreamFactory factory) {
        ServerConfig cfg = config(routes);
        bridge = new FakeTunnelBridge();
        bridge.setReceiver(frame -> { });
        registry = new StreamRegistry(8, false);
        TargetTcpConnector connector = new TargetTcpConnector(10,
                Executors.newSingleThreadExecutor());
        session = new PlayerTunnelSession(PLAYER_UUID, bridge, cfg, store(cfg),
                registry, new BufferBudget(1024, 8192L), new ReservationState(),
                connector, 1_700_000_000L, factory);
    }

    private void ack(boolean ok) {
        session.handleInbound(Frame.createTrusted(PlayerTunnelSession.PROTOCOL_VERSION,
                0, 0, FrameType.CONFIG_ACK, (byte) 0,
                RouteControlPayload.encodeAck(ok, ok ? "ok" : "failed")));
    }

    @Test
    void newSessionHasNoActiveRouteUntilAck() {
        setUp(List.of(route()), new NoopServerStreamFactory());
        assertTrue(!session.isRouteActive());
        assertNull(session.activeRoute());
    }

    @Test
    void configuredRouteIsPushedOnJoin() {
        RouteConfig route = route();
        setUp(List.of(route), new NoopServerStreamFactory());
        session.sendRouteIfConfigured();

        assertEquals(1, bridge.sentFrames().size());
        Frame apply = bridge.sentFrames().get(0);
        assertEquals(FrameType.CONFIG_APPLY, apply.type());
        RouteControlPayload.Apply decoded = RouteControlPayload.decodeApply(apply.payload());
        assertEquals("127.0.0.1", decoded.listenHost());
        assertEquals(25580, decoded.listenPort());
        assertSame(route, session.activeRoute());
        assertTrue(!session.isRouteActive());
    }

    @Test
    void successfulAckMarksRouteActive() {
        RouteConfig route = route();
        setUp(List.of(route), new NoopServerStreamFactory());
        session.sendRouteIfConfigured();
        ack(true);
        assertTrue(session.isRouteActive());
        assertSame(route, session.activeRoute());
    }

    @Test
    void failedAckClearsRoute() {
        setUp(List.of(route()), new NoopServerStreamFactory());
        session.sendRouteIfConfigured();
        ack(false);
        assertTrue(!session.isRouteActive());
        assertNull(session.activeRoute());
    }

    @Test
    void framesBeforeRouteAckAreRejected() {
        setUp(List.of(route()), new NoopServerStreamFactory());
        Frame data = Frame.createTrusted(PlayerTunnelSession.PROTOCOL_VERSION,
                0, 1, FrameType.DATA, (byte) 0, "hello".getBytes());
        ProtocolException ex = assertThrows(ProtocolException.class,
                () -> session.handleInbound(data));
        assertTrue(ex.getMessage().contains("before route is active"));
    }

    @Test
    void unexpectedClientControlFramesAreRejected() {
        setUp(List.of(route()), new NoopServerStreamFactory());
        Frame authOk = Frame.createTrusted(PlayerTunnelSession.PROTOCOL_VERSION,
                0, 0, FrameType.AUTH_OK, (byte) 0, new byte[0]);
        ProtocolException ex = assertThrows(ProtocolException.class,
                () -> session.handleInbound(authOk));
        assertTrue(ex.getMessage().contains("unexpected frame"));
    }

    @Test
    void pingAfterRouteAckTriggersPong() {
        setUp(List.of(route()), new NoopServerStreamFactory());
        session.sendRouteIfConfigured();
        ack(true);
        bridge.clearSent();
        Frame ping = Frame.createTrusted(PlayerTunnelSession.PROTOCOL_VERSION,
                0, 7, FrameType.PING, (byte) 0, new byte[0]);
        session.handleInbound(ping);
        assertEquals(1, bridge.sentFrames().size());
        assertEquals(FrameType.PONG, bridge.sentFrames().get(0).type());
        assertEquals(7, bridge.sentFrames().get(0).streamId());
    }

    @Test
    void clearRouteClosesStateAndRegistry() {
        setUp(List.of(route()), new NoopServerStreamFactory());
        session.sendRouteIfConfigured();
        ack(true);
        session.sendRouteClear();
        assertTrue(!session.isRouteActive());
        assertNull(session.activeRoute());
        assertEquals(0, registry.size());
        assertEquals(FrameType.CONFIG_CLEAR,
                bridge.sentFrames().get(bridge.sentFrames().size() - 1).type());
    }

    @Test
    void openAfterRouteAckDelegatesToFactory() {
        RecordingFactory recording = new RecordingFactory();
        setUp(List.of(route()), recording);
        session.sendRouteIfConfigured();
        ack(true);
        Frame open = Frame.createTrusted(PlayerTunnelSession.PROTOCOL_VERSION,
                0, 42, FrameType.OPEN, (byte) 0, new byte[0]);
        session.handleInbound(open);
        assertEquals(1, recording.dialed.size());
        assertEquals(42, recording.dialed.get(0).intValue());
        assertSame(session, recording.dialedSession.get(0));
    }

    private static final class NoopServerStreamFactory implements ServerStreamFactory {
        @Override public void dialAndAttach(PlayerTunnelSession s, int id) { }
        @Override public ServerStream find(PlayerTunnelSession s, int id) { return null; }
        @Override public void closeAll(PlayerTunnelSession s) { }
    }

    private static final class RecordingFactory implements ServerStreamFactory {
        final List<Integer> dialed = new java.util.ArrayList<>();
        final List<PlayerTunnelSession> dialedSession = new java.util.ArrayList<>();
        @Override public void dialAndAttach(PlayerTunnelSession s, int id) {
            dialed.add(id);
            dialedSession.add(s);
            s.registry().registerServer(id);
        }
        @Override public ServerStream find(PlayerTunnelSession s, int id) { return null; }
        @Override public void closeAll(PlayerTunnelSession s) { }
    }
}
