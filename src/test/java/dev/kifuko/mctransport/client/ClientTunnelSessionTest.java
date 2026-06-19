package dev.kifuko.mctransport.client;

import dev.kifuko.mctransport.auth.AuthPayload;
import dev.kifuko.mctransport.config.ClientConfig;
import dev.kifuko.mctransport.crypto.PskCipher;
import dev.kifuko.mctransport.net.FakeTunnelBridge;
import dev.kifuko.mctransport.protocol.Frame;
import dev.kifuko.mctransport.protocol.FrameType;
import dev.kifuko.mctransport.protocol.ProtocolException;
import dev.kifuko.mctransport.stream.StreamRegistry;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientTunnelSessionTest {

    private static final SecureRandom FIXED = new SecureRandom() {
        private static final long serialVersionUID = 1L;
        private int c = 0;
        @Override public void nextBytes(byte[] b) {
            for (int i = 0; i < b.length; i++) b[i] = (byte) (c++);
        }
    };

    private static final UUID PLAYER_UUID =
            UUID.fromString("11111111-2222-3333-4444-555555555555");

    private ClientConfig newConfig() {
        return new ClientConfig(true, "127.0.0.1", 25580,
                "mctransport:main", "shared", 8, 1024, 8192L, "info");
    }

    private ClientTunnelSession buildSession(FakeTunnelBridge bridge, ClientStreamFactory factory) {
        StreamRegistry registry = new StreamRegistry(8, true);
        PskCipher cipher = new PskCipher("shared", FIXED);
        return new ClientTunnelSession(newConfig(), bridge, cipher, registry,
                factory, FIXED, 0L);
    }

    private FakeTunnelBridge buildBridge() {
        FakeTunnelBridge b = new FakeTunnelBridge();
        b.setReceiver(frame -> { /* unused in these tests */ });
        return b;
    }

    @Test
    void sendAuthEmitsEncryptedAuthFrame() {
        FakeTunnelBridge b = buildBridge();
        ClientTunnelSession s = buildSession(b, (sess, id) -> null);
        s.sendAuth(PLAYER_UUID, 1_700_000_000L);
        assertEquals(1, b.sentFrames().size());
        Frame f = b.sentFrames().get(0);
        assertEquals(FrameType.AUTH, f.type());
        assertTrue(f.payload().length > 0, "payload must be encrypted (non-empty)");

        // Decrypt to confirm wire format.
        PskCipher cipher = new PskCipher("shared", FIXED);
        byte[] plaintext = cipher.decrypt(f, f.payload());
        // The plaintext should be the AuthPayload encoding itself.
        AuthPayload.Decoded decoded = AuthPayload.decode(plaintext,
                ClientTunnelSession.PROTOCOL_VERSION, 3600, 1_700_000_000L);
        assertEquals(PLAYER_UUID, decoded.playerUuid());
    }

    @Test
    void authOkMarksSessionAuthenticated() {
        FakeTunnelBridge b = buildBridge();
        ClientTunnelSession s = buildSession(b, (sess, id) -> null);
        Frame authOk = Frame.createTrusted(ClientTunnelSession.PROTOCOL_VERSION,
                0, 0, FrameType.AUTH_OK, (byte) 0, new byte[0]);
        s.handleInbound(authOk);
        assertTrue(s.isAuthenticated());
    }

    @Test
    void openLocalStreamBeforeAuthFails() {
        FakeTunnelBridge b = buildBridge();
        ClientTunnelSession s = buildSession(b, (sess, id) -> null);
        assertThrows(IllegalStateException.class, s::openLocalStream);
    }

    @Test
    void openLocalStreamAfterAuthAllocatesAndSendsOpen() {
        FakeTunnelBridge b = buildBridge();
        FakeFactory factory = new FakeFactory();
        ClientTunnelSession s = buildSession(b, factory);
        s.handleInbound(Frame.createTrusted(ClientTunnelSession.PROTOCOL_VERSION,
                0, 0, FrameType.AUTH_OK, (byte) 0, new byte[0]));

        ClientStream stream = s.openLocalStream();
        assertSame(factory.created.get(0), stream);
        assertEquals(1, b.sentFrames().size());
        assertEquals(FrameType.OPEN, b.sentFrames().get(0).type());
        assertEquals(stream.streamId(), b.sentFrames().get(0).streamId());
    }

    @Test
    void pingBeforeAuthIsRejected() {
        FakeTunnelBridge b = buildBridge();
        ClientTunnelSession s = buildSession(b, (sess, id) -> null);
        Frame ping = Frame.createTrusted(ClientTunnelSession.PROTOCOL_VERSION,
                0, 0, FrameType.PING, (byte) 0, new byte[0]);
        assertThrows(ProtocolException.class, () -> s.handleInbound(ping));
    }

    @Test
    void pingAfterAuthTriggersPong() {
        FakeTunnelBridge b = buildBridge();
        ClientTunnelSession s = buildSession(b, (sess, id) -> null);
        s.handleInbound(Frame.createTrusted(ClientTunnelSession.PROTOCOL_VERSION,
                0, 0, FrameType.AUTH_OK, (byte) 0, new byte[0]));
        b.clearSent();
        s.handleInbound(Frame.createTrusted(ClientTunnelSession.PROTOCOL_VERSION,
                0, 7, FrameType.PING, (byte) 0, new byte[0]));
        assertEquals(1, b.sentFrames().size());
        assertEquals(FrameType.PONG, b.sentFrames().get(0).type());
        assertEquals(7, b.sentFrames().get(0).streamId());
    }

    @Test
    void authFromServerIsRejected() {
        FakeTunnelBridge b = buildBridge();
        ClientTunnelSession s = buildSession(b, (sess, id) -> null);
        Frame auth = Frame.createTrusted(ClientTunnelSession.PROTOCOL_VERSION,
                0, 0, FrameType.AUTH, (byte) 0, new byte[0]);
        ProtocolException ex = assertThrows(ProtocolException.class, () -> s.handleInbound(auth));
        assertTrue(ex.getMessage().contains("unexpected AUTH"));
    }

    @Test
    void dataForUnknownStreamSendsReset() {
        FakeTunnelBridge b = buildBridge();
        ClientTunnelSession s = buildSession(b, (sess, id) -> null);
        s.handleInbound(Frame.createTrusted(ClientTunnelSession.PROTOCOL_VERSION,
                0, 0, FrameType.AUTH_OK, (byte) 0, new byte[0]));
        b.clearSent();
        s.handleInbound(Frame.createTrusted(ClientTunnelSession.PROTOCOL_VERSION,
                0, 99, FrameType.DATA, (byte) 0, "hi".getBytes()));
        assertEquals(1, b.sentFrames().size());
        assertEquals(FrameType.RESET, b.sentFrames().get(0).type());
        assertEquals(99, b.sentFrames().get(0).streamId());
    }

    @Test
    void tickBeforeAuthDoesNothing() {
        FakeTunnelBridge b = buildBridge();
        ClientTunnelSession s = buildSession(b, (sess, id) -> null);
        s.setPingIntervalMillis(10);
        s.tick(10_000L);
        assertEquals(0, b.sentFrames().size());
    }

    @Test
    void tickAfterAuthSendsPingAfterInterval() {
        FakeTunnelBridge b = buildBridge();
        ClientTunnelSession s = buildSession(b, (sess, id) -> null);
        s.setPingIntervalMillis(100);
        s.handleInbound(Frame.createTrusted(ClientTunnelSession.PROTOCOL_VERSION,
                0, 0, FrameType.AUTH_OK, (byte) 0, new byte[0]));
        b.clearSent();
        s.tick(50L);
        assertEquals(0, b.sentFrames().size());
        s.tick(200L);
        assertEquals(1, b.sentFrames().size());
        assertEquals(FrameType.PING, b.sentFrames().get(0).type());
    }

    @Test
    void closeClearsStreamsAndRegistry() {
        FakeTunnelBridge b = buildBridge();
        FakeFactory factory = new FakeFactory();
        ClientTunnelSession s = buildSession(b, factory);
        s.handleInbound(Frame.createTrusted(ClientTunnelSession.PROTOCOL_VERSION,
                0, 0, FrameType.AUTH_OK, (byte) 0, new byte[0]));
        s.openLocalStream();
        s.openLocalStream();
        assertEquals(2, s.streams().size());
        assertFalse(s.registry().size() == 0);
        s.close();
        assertEquals(0, s.streams().size());
        assertEquals(0, s.registry().size());
        assertFalse(s.isAuthenticated());
    }

    private static final class FakeFactory implements ClientStreamFactory {
        final java.util.List<ClientStream> created = new java.util.ArrayList<>();
        @Override public ClientStream create(ClientTunnelSession session, int streamId) {
            ClientStream s = new ClientStream(session, streamId,
                    new dev.kifuko.mctransport.buffer.BufferBudget(1024, 8192L),
                    new dev.kifuko.mctransport.buffer.ReservationState(),
                    1024);
            created.add(s);
            return s;
        }
    }
}