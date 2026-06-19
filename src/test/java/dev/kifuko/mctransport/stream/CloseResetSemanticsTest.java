package dev.kifuko.mctransport.stream;

import dev.kifuko.mctransport.buffer.BufferBudget;
import dev.kifuko.mctransport.buffer.ReservationState;
import dev.kifuko.mctransport.client.ClientStream;
import dev.kifuko.mctransport.client.ClientTunnelSession;
import dev.kifuko.mctransport.config.ClientConfig;
import dev.kifuko.mctransport.crypto.PskCipher;
import dev.kifuko.mctransport.net.FakeTunnelBridge;
import dev.kifuko.mctransport.protocol.Frame;
import dev.kifuko.mctransport.protocol.FrameType;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CloseResetSemanticsTest {

    private static final SecureRandom FIXED = new SecureRandom() {
        private static final long serialVersionUID = 1L;
        private int c = 0;
        @Override public void nextBytes(byte[] b) {
            for (int i = 0; i < b.length; i++) b[i] = (byte) (c++);
        }
    };

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
        ClientStream s = new ClientStream(session, 1, budget, res, 1024);
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
        dev.kifuko.mctransport.server.PlayerTunnelSession playerSession =
                new dev.kifuko.mctransport.server.PlayerTunnelSession(
                        new dev.kifuko.mctransport.config.ServerConfig(true, "127.0.0.1", 10000,
                                "mctransport:main", "shared",
                                java.util.List.of("11111111-2222-3333-4444-555555555555"),
                                8, 1024, 8192L, 300, 10, "info"),
                        b, new PskCipher("shared", FIXED),
                        new dev.kifuko.mctransport.stream.StreamRegistry(8, false),
                        budget, res,
                        new dev.kifuko.mctransport.server.TargetTcpConnector("127.0.0.1", 10000, 10,
                                java.util.concurrent.Executors.newSingleThreadExecutor()),
                        0L, 0L,
                        new dev.kifuko.mctransport.server.NoopServerStreamFactoryForTest());
        dev.kifuko.mctransport.server.ServerStream ss = new dev.kifuko.mctransport.server.ServerStream(
                playerSession, 1, sock, budget, res, (byte) 1, 1024);
        ss.closeClean();
        assertEquals(1, b.sentFrames().size());
        assertEquals(FrameType.CLOSE, b.sentFrames().get(0).type());
        ss.closeClean(); // idempotent
        assertEquals(1, b.sentFrames().size());
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
        return new ClientStream(session, streamId, budget, res, 1024);
    }

    private ClientTunnelSession newSession(FakeTunnelBridge b,
                                           BufferBudget budget,
                                           ReservationState res) {
        ClientConfig cfg = new ClientConfig(true, "127.0.0.1", 25580,
                "mctransport:main", "shared", 8, 1024, 8192L, "info");
        return new ClientTunnelSession(cfg, b, new PskCipher("shared", FIXED),
                new StreamRegistry(8, true),
                (sess, id) -> new ClientStream(sess, id, budget, res, 1024),
                FIXED, 0L);
    }
}