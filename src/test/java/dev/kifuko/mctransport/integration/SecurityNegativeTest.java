package dev.kifuko.mctransport.integration;

import dev.kifuko.mctransport.auth.AuthPayload;
import dev.kifuko.mctransport.crypto.PskCipher;
import dev.kifuko.mctransport.protocol.Frame;
import dev.kifuko.mctransport.protocol.ProtocolException;
import dev.kifuko.mctransport.server.PlayerTunnelSession;
import dev.kifuko.mctransport.stream.StreamRegistry;
import dev.kifuko.mctransport.config.ServerConfig;
import dev.kifuko.mctransport.buffer.BufferBudget;
import dev.kifuko.mctransport.buffer.ReservationState;
import dev.kifuko.mctransport.net.FakeTunnelBridge;
import dev.kifuko.mctransport.server.NoopServerStreamFactoryForTest;
import dev.kifuko.mctransport.server.TargetTcpConnector;
import dev.kifuko.mctransport.protocol.FrameType;

import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Negative-path security tests for the server-side tunnel session.
 */
class SecurityNegativeTest {

    private static final UUID ALLOWED =
            UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final UUID OTHER =
            UUID.fromString("99999999-9999-9999-9999-999999999999");
    private static final String PSK = "shared-secret";
    private static final long NOW = 1_700_000_000L;

    private static final SecureRandom FIXED = new SecureRandom() {
        private static final long serialVersionUID = 1L;
        private int c = 0;
        @Override public void nextBytes(byte[] b) {
            for (int i = 0; i < b.length; i++) b[i] = (byte) (c++);
        }
    };

    private PlayerTunnelSession buildSession(String serverPsk) {
        ServerConfig cfg = new ServerConfig(true, "127.0.0.1", 10000,
                "mctransport:main", serverPsk,
                List.of(ALLOWED.toString()),
                8, 1024, 8192L, 300, 10, "info");
        FakeTunnelBridge bridge = new FakeTunnelBridge();
        bridge.setReceiver(frame -> { });
        return new PlayerTunnelSession(cfg, bridge,
                new PskCipher(serverPsk, FIXED),
                new StreamRegistry(8, false),
                new BufferBudget(1024, 8192L), new ReservationState(),
                new TargetTcpConnector("127.0.0.1", 10000, 10,
                        Executors.newSingleThreadExecutor()),
                NOW, 0L, new NoopServerStreamFactoryForTest());
    }

    private Frame makeAuthFrame(UUID playerUuid, String clientPsk) {
        PskCipher clientCipher = new PskCipher(clientPsk, FIXED);
        byte[] body = AuthPayload.encode(PlayerTunnelSession.PROTOCOL_VERSION,
                playerUuid, "nonce".getBytes(), NOW);
        return Frame.createTrusted(PlayerTunnelSession.PROTOCOL_VERSION, 0, 0,
                FrameType.AUTH, (byte) 0, clientCipher.encrypt(Frame.createTrusted(
                        PlayerTunnelSession.PROTOCOL_VERSION, 0, 0,
                        FrameType.AUTH, (byte) 0, body)));
    }

    @Test
    void wrongPskBlocksAuthOk() {
        PlayerTunnelSession session = buildSession(PSK);
        Frame bad = makeAuthFrame(ALLOWED, "wrong-psk");
        ProtocolException ex = assertThrows(ProtocolException.class,
                () -> session.handleInbound(bad));
        assertTrue(ex.getMessage().contains("AUTH decryption failed"));
    }

    @Test
    void nonWhitelistedUuidBlocksAuthOk() {
        PlayerTunnelSession session = buildSession(PSK);
        Frame bad = makeAuthFrame(OTHER, PSK);
        ProtocolException ex = assertThrows(ProtocolException.class,
                () -> session.handleInbound(bad));
        assertTrue(ex.getMessage().contains("not allowed"));
    }

    @Test
    void openBeforeAuthIsRejected() {
        PlayerTunnelSession session = buildSession(PSK);
        Frame open = Frame.createTrusted(PlayerTunnelSession.PROTOCOL_VERSION,
                0, 1, FrameType.OPEN, (byte) 0, new byte[0]);
        ProtocolException ex = assertThrows(ProtocolException.class,
                () -> session.handleInbound(open));
        assertTrue(ex.getMessage().contains("before AUTH_OK"));
    }

    @Test
    void dataBeforeAuthIsRejected() {
        PlayerTunnelSession session = buildSession(PSK);
        Frame data = Frame.createTrusted(PlayerTunnelSession.PROTOCOL_VERSION,
                0, 1, FrameType.DATA, (byte) 0, "hi".getBytes());
        ProtocolException ex = assertThrows(ProtocolException.class,
                () -> session.handleInbound(data));
        assertTrue(ex.getMessage().contains("before AUTH_OK"));
    }

    @Test
    void tamperedEncryptedBodyFailsAuthentication() {
        PlayerTunnelSession session = buildSession(PSK);
        Frame good = makeAuthFrame(ALLOWED, PSK);
        byte[] payload = good.payload().clone();
        // Flip a byte in the ciphertext (after the 12-byte nonce).
        payload[PskCipher.NONCE_LEN] ^= 0x01;
        Frame tampered = Frame.createTrusted(PlayerTunnelSession.PROTOCOL_VERSION,
                0, 0, FrameType.AUTH, (byte) 0, payload);
        ProtocolException ex = assertThrows(ProtocolException.class,
                () -> session.handleInbound(tampered));
        assertTrue(ex.getMessage().contains("AUTH decryption failed"));
    }
}