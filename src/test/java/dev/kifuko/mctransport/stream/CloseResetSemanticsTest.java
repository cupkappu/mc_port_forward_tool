package dev.kifuko.mctransport.stream;

import dev.kifuko.mctransport.buffer.BufferBudget;
import dev.kifuko.mctransport.buffer.ReservationState;
import dev.kifuko.mctransport.client.ClientStream;
import dev.kifuko.mctransport.client.DirectClientStream;
import dev.kifuko.mctransport.client.ClientTunnelSession;
import dev.kifuko.mctransport.net.FakeTunnelBridge;
import dev.kifuko.mctransport.protocol.Frame;
import dev.kifuko.mctransport.protocol.FrameType;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CloseResetSemanticsTest {

    @Test
    void clientStreamCloseCleanSendsCloseFrame() {
        FakeTunnelBridge b = new FakeTunnelBridge();
        ClientStream s = newStream(b, 7);
        s.closeClean();
        assertEquals(1, b.sentFrames().size());
        assertEquals(FrameType.CLOSE, b.sentFrames().get(0).type());
        assertEquals(7, b.sentFrames().get(0).streamId());
        s.closeClean(); // idempotent
        assertEquals(1, b.sentFrames().size());
    }

    @Test
    void clientStreamCloseResetSendsResetFrame() {
        FakeTunnelBridge b = new FakeTunnelBridge();
        ClientStream s = newStream(b, 8);
        s.closeReset();
        assertEquals(1, b.sentFrames().size());
        assertEquals(FrameType.RESET, b.sentFrames().get(0).type());
        s.closeReset(); // idempotent
        assertEquals(1, b.sentFrames().size());
    }

    @Test
    void clientStreamReleaseReservationsOnClose() {
        FakeTunnelBridge b = new FakeTunnelBridge();
        BufferBudget budget = new BufferBudget(1024, 8192L);
        ReservationState res = new ReservationState();
        budget.reserve(1, 500, res);
        ClientTunnelSession session = newSession(b, budget, res);
        ClientStream s = new DirectClientStream(session, 1, budget, res, 1024);
        s.closeClean();
        assertEquals(0L, budget.globalReserved());
    }

    @Test
    void serverStreamCloseCleanSendsCloseFrame() throws Exception {
        FakeTunnelBridge b = new FakeTunnelBridge();
        java.net.ServerSocket server = new java.net.ServerSocket(0);
        java.net.Socket sock = new java.net.Socket();
        sock.connect(new java.net.InetSocketAddress("127.0.0.1", server.getLocalPort()));
        java.net.Socket peer = server.accept();
        BufferBudget budget = new BufferBudget(1024, 8192L);
        ReservationState res = new ReservationState();
        UUID playerUuid = UUID.fromString("11111111-2222-3333-4444-555555555555");
        dev.kifuko.mctransport.config.RouteConfig route =
                new dev.kifuko.mctransport.config.RouteConfig(playerUuid, "Steve",
                        25580, "127.0.0.1", 10000);
        dev.kifuko.mctransport.config.ServerConfig serverConfig =
                new dev.kifuko.mctransport.config.ServerConfig(true, "mctransport:main",
                        java.util.List.of(route), 8, 1024, 8192L, 300, 10, "info");
        dev.kifuko.mctransport.server.PlayerTunnelSession playerSession =
                new dev.kifuko.mctransport.server.PlayerTunnelSession(
                        playerUuid, route, b, serverConfig,
                        new dev.kifuko.mctransport.server.RouteStore(
                                Path.of("build/tmp/test-route-store"),
                                "mctransport.server.toml", serverConfig),
                        new dev.kifuko.mctransport.stream.StreamRegistry(8, false),
                        budget, res,
                        new dev.kifuko.mctransport.server.TargetTcpConnector(10,
                                java.util.concurrent.Executors.newSingleThreadExecutor()),
                        0L,
                        new dev.kifuko.mctransport.server.NoopServerStreamFactoryForTest());
        dev.kifuko.mctransport.server.DirectServerStream ss = new dev.kifuko.mctransport.server.DirectServerStream(
                playerSession, 1, sock, budget, res, (byte) 1, 1024);
        ss.closeClean();
        assertEquals(1, b.sentFrames().size());
        assertEquals(FrameType.CLOSE, b.sentFrames().get(0).type());
        assertEquals(25580, b.sentFrames().get(0).sessionId());
        ss.closeClean(); // idempotent
        assertEquals(1, b.sentFrames().size());
        sock.close();
        peer.close();
        server.close();
    }

    @Test
    void kcpServerStreamCloseResetSendsRouteSessionId() throws Exception {
        FakeTunnelBridge b = new FakeTunnelBridge();
        java.net.ServerSocket server = new java.net.ServerSocket(0);
        java.net.Socket sock = new java.net.Socket();
        sock.connect(new java.net.InetSocketAddress("127.0.0.1", server.getLocalPort()));
        java.net.Socket peer = server.accept();
        BufferBudget budget = new BufferBudget(1024, 8192L);
        ReservationState res = new ReservationState();
        UUID playerUuid = UUID.fromString("11111111-2222-3333-4444-555555555555");
        dev.kifuko.mctransport.config.RouteConfig route =
                new dev.kifuko.mctransport.config.RouteConfig(playerUuid, "Steve",
                        25580, "127.0.0.1", 10000);
        dev.kifuko.mctransport.config.ServerConfig serverConfig =
                new dev.kifuko.mctransport.config.ServerConfig(true, "mctransport:main",
                        java.util.List.of(route), 8, 1024, 8192L, 300, 10, "info");
        dev.kifuko.mctransport.server.PlayerTunnelSession playerSession =
                new dev.kifuko.mctransport.server.PlayerTunnelSession(
                        playerUuid, route, b, serverConfig,
                        new dev.kifuko.mctransport.server.RouteStore(
                                Path.of("build/tmp/test-route-store"),
                                "mctransport.server.toml", serverConfig),
                        new dev.kifuko.mctransport.stream.StreamRegistry(8, false),
                        budget, res,
                        new dev.kifuko.mctransport.server.TargetTcpConnector(10,
                                java.util.concurrent.Executors.newSingleThreadExecutor()),
                        0L,
                        new dev.kifuko.mctransport.server.NoopServerStreamFactoryForTest());
        dev.kifuko.mctransport.server.KcpServerStream ss =
                new dev.kifuko.mctransport.server.KcpServerStream(
                        playerSession, 1, sock, budget, res,
                        new dev.kifuko.mctransport.kcp.KcpConfig(), (byte) 1);

        ss.closeReset();

        assertEquals(1, b.sentFrames().size());
        assertEquals(FrameType.RESET, b.sentFrames().get(0).type());
        assertEquals(25580, b.sentFrames().get(0).sessionId());
        sock.close();
        peer.close();
        server.close();
    }

    @Test
    void registryClearsOnClose() {
        BufferBudget budget = new BufferBudget(1024, 8192L);
        ReservationState res = new ReservationState();
        StreamRegistry registry = new StreamRegistry(4, true, id -> budget.releaseAll(id, res));
        int id = registry.allocateClient();
        registry.remove(id);
        assertEquals(0, registry.size());
        assertEquals(0L, budget.globalReserved());
    }

    private ClientStream newStream(FakeTunnelBridge b, int streamId) {
        BufferBudget budget = new BufferBudget(1024, 8192L);
        ReservationState res = new ReservationState();
        ClientTunnelSession session = newSession(b, budget, res);
        return new DirectClientStream(session, streamId, budget, res, 1024);
    }

    private ClientTunnelSession newSession(FakeTunnelBridge b,
                                           BufferBudget budget,
                                           ReservationState res) {
        return new ClientTunnelSession(b,
                new StreamRegistry(8, true),
                (sess, id, mode) -> new DirectClientStream(sess, id, budget, res, 1024),
                0L);
    }
}
