package dev.kifuko.mctransport.server;

import dev.kifuko.mctransport.auth.AuthPayload;
import dev.kifuko.mctransport.buffer.BufferBudget;
import dev.kifuko.mctransport.buffer.ReservationState;
import dev.kifuko.mctransport.config.ServerConfig;
import dev.kifuko.mctransport.crypto.PskCipher;
import dev.kifuko.mctransport.net.FakeTunnelBridge;
import dev.kifuko.mctransport.net.TunnelBridge;
import dev.kifuko.mctransport.protocol.Frame;
import dev.kifuko.mctransport.protocol.FrameType;
import dev.kifuko.mctransport.protocol.ProtocolException;
import dev.kifuko.mctransport.stream.StreamRegistry;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerTunnelSessionAuthTest {

    private static final UUID PLAYER_UUID =
            UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final String PSK = "shared-secret";
    private static final long NOW_SECONDS = 1_700_000_000L;

    private static final SecureRandom FIXED = new SecureRandom() {
        private static final long serialVersionUID = 1L;
        private int c = 0;
        @Override public void nextBytes(byte[] b) {
            for (int i = 0; i < b.length; i++) b[i] = (byte) (c++);
        }
    };

    private FakeTunnelBridge bridge;
    private StreamRegistry registry;
    private PlayerTunnelSession session;
    private ServerStreamFactory factory;

    private void setUp() {
        setUp(PSK, PSK);
    }

    private void setUp(String clientPsk, String serverPsk) {
        ServerConfig cfg = new ServerConfig(
                true, "127.0.0.1", 10000, "mctransport:main", serverPsk,
                List.of(PLAYER_UUID.toString()),
                8, 1024, 8192L, 300, 10, "info");
        bridge = new FakeTunnelBridge();
        bridge.setReceiver(this::onFrame);
        registry = new StreamRegistry(8, false);
        BufferBudget budget = new BufferBudget(1024, 8192L);
        ReservationState res = new ReservationState();
        TargetTcpConnector connector = new TargetTcpConnector("127.0.0.1", 10000, 10,
                Executors.newSingleThreadExecutor());
        factory = new NoopServerStreamFactory();
        PskCipher serverCipher = new PskCipher(serverPsk, FIXED);
        session = new PlayerTunnelSession(cfg, bridge, serverCipher,
                registry, budget, res, connector,
                NOW_SECONDS, 0L, factory);
    }

    private void onFrame(Frame frame) {
        // No-op: tests inject frames explicitly via session.handleInbound.
    }

    private Frame makeAuthFrame(String psk) {
        PskCipher clientCipher = new PskCipher(psk, FIXED);
        byte[] body = AuthPayload.encode(PlayerTunnelSession.PROTOCOL_VERSION,
                PLAYER_UUID, "nonce".getBytes(), NOW_SECONDS);
        return Frame.createTrusted(PlayerTunnelSession.PROTOCOL_VERSION, 0, 0,
                FrameType.AUTH, (byte) 0, clientCipher.encrypt(Frame.createTrusted(
                        PlayerTunnelSession.PROTOCOL_VERSION, 0, 0,
                        FrameType.AUTH, (byte) 0, body)));
    }

    @Test
    void newSessionIsNotAuthenticated() {
        setUp();
        assertTrue(!session.isAuthenticated());
    }

    @Test
    void validAuthProducesAuthOk() {
        setUp();
        bridge.clearSent();
        session.handleInbound(makeAuthFrame(PSK));
        assertTrue(session.isAuthenticated());
        assertEquals(PLAYER_UUID, session.authenticatedUuid());
        assertEquals(1, bridge.sentFrames().size());
        Frame reply = bridge.sentFrames().get(0);
        assertEquals(FrameType.AUTH_OK, reply.type());
    }

    @Test
    void wrongPskFailsAuthentication() {
        setUp(PSK, "different");
        ProtocolException ex = assertThrows(ProtocolException.class,
                () -> session.handleInbound(makeAuthFrame(PSK)));
        assertTrue(ex.getMessage().contains("AUTH decryption failed"));
        assertTrue(!session.isAuthenticated());
    }

    @Test
    void nonWhitelistedUuidFailsAuthentication() {
        ServerConfig cfg = new ServerConfig(
                true, "127.0.0.1", 10000, "mctransport:main", PSK,
                List.of("ffffffff-ffff-ffff-ffff-ffffffffffff"),
                8, 1024, 8192L, 300, 10, "info");
        bridge = new FakeTunnelBridge();
        bridge.setReceiver(this::onFrame);
        registry = new StreamRegistry(8, false);
        BufferBudget budget = new BufferBudget(1024, 8192L);
        ReservationState res = new ReservationState();
        TargetTcpConnector connector = new TargetTcpConnector("127.0.0.1", 10000, 10,
                Executors.newSingleThreadExecutor());
        factory = new NoopServerStreamFactory();
        PskCipher cipher = new PskCipher(PSK, FIXED);
        session = new PlayerTunnelSession(cfg, bridge, cipher, registry, budget, res,
                connector, NOW_SECONDS, 0L, factory);

        ProtocolException ex = assertThrows(ProtocolException.class,
                () -> session.handleInbound(makeAuthFrame(PSK)));
        assertTrue(ex.getMessage().contains("not allowed"));
    }

    @Test
    void framesBeforeAuthAreRejected() {
        setUp();
        Frame data = Frame.createTrusted(PlayerTunnelSession.PROTOCOL_VERSION,
                0, 1, FrameType.DATA, (byte) 0, "hello".getBytes());
        ProtocolException ex = assertThrows(ProtocolException.class,
                () -> session.handleInbound(data));
        assertTrue(ex.getMessage().contains("before AUTH_OK"));
    }

    @Test
    void openBeforeAuthIsRejected() {
        setUp();
        Frame open = Frame.createTrusted(PlayerTunnelSession.PROTOCOL_VERSION,
                0, 1, FrameType.OPEN, (byte) 0, new byte[0]);
        assertThrows(ProtocolException.class, () -> session.handleInbound(open));
    }

    @Test
    void closeBeforeAuthIsRejected() {
        setUp();
        Frame close = Frame.createTrusted(PlayerTunnelSession.PROTOCOL_VERSION,
                0, 1, FrameType.CLOSE, (byte) 0, new byte[0]);
        assertThrows(ProtocolException.class, () -> session.handleInbound(close));
    }

    @Test
    void resetBeforeAuthIsRejected() {
        setUp();
        Frame reset = Frame.createTrusted(PlayerTunnelSession.PROTOCOL_VERSION,
                0, 1, FrameType.RESET, (byte) 0, new byte[0]);
        assertThrows(ProtocolException.class, () -> session.handleInbound(reset));
    }

    @Test
    void errorBeforeAuthIsRejected() {
        setUp();
        Frame err = Frame.createTrusted(PlayerTunnelSession.PROTOCOL_VERSION,
                0, 0, FrameType.ERROR, (byte) 0, "boom".getBytes());
        assertThrows(ProtocolException.class, () -> session.handleInbound(err));
    }

    @Test
    void pingBeforeAuthIsRejected() {
        setUp();
        Frame ping = Frame.createTrusted(PlayerTunnelSession.PROTOCOL_VERSION,
                0, 0, FrameType.PING, (byte) 0, new byte[0]);
        assertThrows(ProtocolException.class, () -> session.handleInbound(ping));
    }

    @Test
    void authOkFromClientIsRejected() {
        setUp();
        Frame authOk = Frame.createTrusted(PlayerTunnelSession.PROTOCOL_VERSION,
                0, 0, FrameType.AUTH_OK, (byte) 0, new byte[0]);
        ProtocolException ex = assertThrows(ProtocolException.class,
                () -> session.handleInbound(authOk));
        assertTrue(ex.getMessage().contains("unexpected AUTH_OK"));
    }

    @Test
    void pingAfterAuthTriggersPong() {
        setUp();
        session.handleInbound(makeAuthFrame(PSK));
        bridge.clearSent();
        Frame ping = Frame.createTrusted(PlayerTunnelSession.PROTOCOL_VERSION,
                0, 7, FrameType.PING, (byte) 0, new byte[0]);
        session.handleInbound(ping);
        assertEquals(1, bridge.sentFrames().size());
        assertEquals(FrameType.PONG, bridge.sentFrames().get(0).type());
        assertEquals(7, bridge.sentFrames().get(0).streamId());
    }

    @Test
    void reAuthIsSilentlyIgnored() {
        setUp();
        session.handleInbound(makeAuthFrame(PSK));
        bridge.clearSent();
        // Second AUTH must not produce a second AUTH_OK and must not flip state.
        session.handleInbound(makeAuthFrame(PSK));
        assertEquals(0, bridge.sentFrames().size());
        assertEquals(PLAYER_UUID, session.authenticatedUuid());
    }

    @Test
    void closeClearsStateAndRegistry() {
        setUp();
        session.handleInbound(makeAuthFrame(PSK));
        session.close();
        assertTrue(!session.isAuthenticated());
        assertEquals(0, registry.size());
    }

    @Test
    void openAfterAuthDelegatesToFactory() {
        setUp();
        session.handleInbound(makeAuthFrame(PSK));
        RecordingFactory recording = new RecordingFactory();
        PlayerTunnelSession customSession = new PlayerTunnelSession(
                session.config(), bridge, new PskCipher(PSK, FIXED),
                new dev.kifuko.mctransport.stream.StreamRegistry(8, false),
                new dev.kifuko.mctransport.buffer.BufferBudget(1024, 8192L),
                new dev.kifuko.mctransport.buffer.ReservationState(),
                new dev.kifuko.mctransport.server.TargetTcpConnector("127.0.0.1", 10000, 10,
                        java.util.concurrent.Executors.newSingleThreadExecutor()),
                NOW_SECONDS, 0L, recording);
        customSession.handleInbound(makeAuthFrame(PSK));
        Frame open = Frame.createTrusted(PlayerTunnelSession.PROTOCOL_VERSION,
                0, 42, FrameType.OPEN, (byte) 0, new byte[0]);
        customSession.handleInbound(open);
        assertEquals(1, recording.dialed.size());
        assertEquals(42, recording.dialed.get(0).intValue());
        assertSame(customSession, recording.dialedSession.get(0));
    }

    /** Empty factory used for tests that do not exercise OPEN. */
    private static final class NoopServerStreamFactory implements ServerStreamFactory {
        @Override public void dialAndAttach(PlayerTunnelSession s, int id) { }
        @Override public ServerStream find(PlayerTunnelSession s, int id) { return null; }
        @Override public void closeAll(PlayerTunnelSession s) { }
    }

    /** Factory that records which stream IDs were dialed. */
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