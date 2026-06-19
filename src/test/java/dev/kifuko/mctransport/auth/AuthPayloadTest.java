package dev.kifuko.mctransport.auth;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AuthPayloadTest {

    private static final UUID UUID_A = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final byte[] NONCE = "nonce-1234".getBytes(StandardCharsets.UTF_8);

    @Test
    void roundTripsAllFields() {
        byte[] body = AuthPayload.encode((byte) 1, UUID_A, NONCE, 1_700_000_000L);
        AuthPayload.Decoded d = AuthPayload.decode(body, (byte) 1, 60, 1_700_000_000L);
        assertEquals(1, d.protocolVersion());
        assertEquals(UUID_A, d.playerUuid());
        assertArrayEquals(NONCE, d.clientNonce());
        assertEquals(1_700_000_000L, d.timestampSeconds());
    }

    @Test
    void rejectsMismatchedProtocolVersion() {
        byte[] body = AuthPayload.encode((byte) 1, UUID_A, NONCE, 1_700_000_000L);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> AuthPayload.decode(body, (byte) 2, 60, 1_700_000_000L));
        assertEquals("protocol version mismatch: expected 2 got 1", ex.getMessage());
    }

    @Test
    void rejectsTimestampTooFarInPast() {
        byte[] body = AuthPayload.encode((byte) 1, UUID_A, NONCE, 1_600_000_000L);
        assertThrows(IllegalArgumentException.class,
                () -> AuthPayload.decode(body, (byte) 1, 60, 1_700_000_000L));
    }

    @Test
    void rejectsTimestampTooFarInFuture() {
        byte[] body = AuthPayload.encode((byte) 1, UUID_A, NONCE, 2_000_000_000L);
        assertThrows(IllegalArgumentException.class,
                () -> AuthPayload.decode(body, (byte) 1, 60, 1_700_000_000L));
    }

    @Test
    void acceptsTimestampWithinSkew() {
        byte[] body = AuthPayload.encode((byte) 1, UUID_A, NONCE, 1_700_000_030L);
        AuthPayload.Decoded d = AuthPayload.decode(body, (byte) 1, 60, 1_700_000_000L);
        assertEquals(1_700_000_030L, d.timestampSeconds());
    }

    @Test
    void rejectsEmptyNonce() {
        assertThrows(IllegalArgumentException.class,
                () -> AuthPayload.encode((byte) 1, UUID_A, new byte[0], 1_700_000_000L));
    }

    @Test
    void rejectsTooLongNonce() {
        byte[] tooLong = new byte[AuthPayload.MAX_NONCE_LEN + 1];
        assertThrows(IllegalArgumentException.class,
                () -> AuthPayload.encode((byte) 1, UUID_A, tooLong, 1_700_000_000L));
    }

    @Test
    void rejectsNullUuid() {
        assertThrows(IllegalArgumentException.class,
                () -> AuthPayload.encode((byte) 1, null, NONCE, 1_700_000_000L));
    }

    @Test
    void rejectsNegativeTimestamp() {
        assertThrows(IllegalArgumentException.class,
                () -> AuthPayload.encode((byte) 1, UUID_A, NONCE, -1));
    }

    @Test
    void rejectsMalformedUuidString() {
        byte[] body = AuthPayload.encode((byte) 1, UUID_A, NONCE, 1_700_000_000L);
        // Find the UUID portion and overwrite it with garbage.
        byte[] bad = new byte[body.length];
        System.arraycopy(body, 0, bad, 0, body.length);
        for (int i = body.length - 36; i < body.length; i++) {
            bad[i] = 'X';
        }
        assertThrows(IllegalArgumentException.class,
                () -> AuthPayload.decode(bad, (byte) 1, 60, 1_700_000_000L));
    }
}