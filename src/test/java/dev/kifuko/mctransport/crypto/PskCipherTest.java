package dev.kifuko.mctransport.crypto;

import dev.kifuko.mctransport.protocol.Frame;
import dev.kifuko.mctransport.protocol.FrameCodec;
import dev.kifuko.mctransport.protocol.FrameType;
import dev.kifuko.mctransport.protocol.ProtocolException;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.SecureRandom;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PskCipherTest {

    private static final FrameCodec CODEC = new FrameCodec(1024);

    /** Deterministic RNG so tests can reason about nonce bytes. */
    private static final SecureRandom FIXED = new SecureRandom() {
        private static final long serialVersionUID = 1L;
        private final byte[] fixed = new byte[]{
                1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12
        };
        private int pos = 0;

        @Override
        public synchronized void nextBytes(byte[] bytes) {
            for (int i = 0; i < bytes.length; i++) {
                bytes[i] = fixed[(pos + i) % fixed.length];
            }
            pos += bytes.length;
        }
    };

    private static Frame sample() {
        return Frame.create((byte) 1, 5, 99, FrameType.DATA, (byte) 0,
                "hello".getBytes(), 1024);
    }

    @Test
    void roundTripRecoversOriginalPayload() {
        PskCipher a = new PskCipher("shared-secret", FIXED);
        PskCipher b = new PskCipher("shared-secret", FIXED);
        Frame f = sample();
        byte[] encrypted = a.encrypt(f);
        byte[] recovered = b.decrypt(f, encrypted);
        assertArrayEquals(f.payload(), recovered);
    }

    @Test
    void encryptedBodyStartsWithNonceThenCiphertext() {
        PskCipher a = new PskCipher("shared-secret", FIXED);
        byte[] enc = a.encrypt(sample());
        // First 12 bytes are the nonce (deterministic here).
        byte[] expectedNonce = new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12};
        byte[] actualNonce = Arrays.copyOfRange(enc, 0, PskCipher.NONCE_LEN);
        assertArrayEquals(expectedNonce, actualNonce);
    }

    @Test
    void encryptionProducesDifferentCiphertextsForSamePlaintext() {
        // Two encrypt calls with different RNG outputs must yield different bodies.
        SecureRandom rngA = new SecureRandom() {
            private static final long serialVersionUID = 1L;
            @Override public void nextBytes(byte[] b) { for (int i = 0; i < b.length; i++) b[i] = (byte) i; }
        };
        SecureRandom rngB = new SecureRandom() {
            private static final long serialVersionUID = 1L;
            @Override public void nextBytes(byte[] b) { for (int i = 0; i < b.length; i++) b[i] = (byte) (b.length - i); }
        };
        PskCipher a = new PskCipher("shared-secret", rngA);
        PskCipher b = new PskCipher("shared-secret", rngB);
        Frame f = sample();
        assertNotEquals(
                Arrays.hashCode(a.encrypt(f)),
                Arrays.hashCode(b.encrypt(f)));
    }

    @Test
    void wrongPskFailsAuthentication() {
        PskCipher a = new PskCipher("one", FIXED);
        PskCipher b = new PskCipher("two", FIXED);
        Frame f = sample();
        byte[] enc = a.encrypt(f);
        ProtocolException ex = assertThrows(ProtocolException.class,
                () -> b.decrypt(f, enc));
        assertEquals("authentication failed", ex.getMessage());
    }

    @Test
    void modifiedCiphertextFailsAuthentication() {
        PskCipher a = new PskCipher("shared-secret", FIXED);
        PskCipher b = new PskCipher("shared-secret", FIXED);
        Frame f = sample();
        byte[] enc = a.encrypt(f);
        enc[PskCipher.NONCE_LEN] ^= 0x01;
        assertThrows(ProtocolException.class, () -> b.decrypt(f, enc));
    }

    @Test
    void modifiedNonceFailsAuthentication() {
        PskCipher a = new PskCipher("shared-secret", FIXED);
        PskCipher b = new PskCipher("shared-secret", FIXED);
        Frame f = sample();
        byte[] enc = a.encrypt(f);
        enc[0] ^= 0x01;
        assertThrows(ProtocolException.class, () -> b.decrypt(f, enc));
    }

    @Test
    void modifiedHeaderFailsAuthentication() {
        PskCipher a = new PskCipher("shared-secret", FIXED);
        PskCipher b = new PskCipher("shared-secret", FIXED);
        Frame original = sample();
        byte[] enc = a.encrypt(original);
        Frame tampered = Frame.create(original.protocolVersion(),
                original.sessionId() ^ 1, original.streamId(), original.type(),
                original.flags(), original.payload(), 1024);
        assertThrows(ProtocolException.class, () -> b.decrypt(tampered, enc));
    }

    @Test
    void truncatedBodyRejected() {
        PskCipher a = new PskCipher("shared-secret", FIXED);
        byte[] enc = new byte[PskCipher.NONCE_LEN + 5];
        assertThrows(ProtocolException.class, () -> a.decrypt(sample(), enc));
    }

    @Test
    void emptyPskRejected() {
        assertThrows(IllegalArgumentException.class, () -> new PskCipher(""));
        assertThrows(IllegalArgumentException.class, () -> new PskCipher(null));
    }

    @Test
    void associatedDataCoversAllHeaderFieldsExceptPayloadLength() {
        Frame f = Frame.create((byte) 7, 0x01020304, 0x05060708,
                FrameType.PING, (byte) 0xAB, new byte[]{1, 2}, 1024);
        ByteBuffer buf = ByteBuffer.allocate(FrameCodec.HEADER_BYTES - 4)
                .order(ByteOrder.BIG_ENDIAN);
        buf.put(f.protocolVersion());
        buf.putInt(f.sessionId());
        buf.putInt(f.streamId());
        buf.put((byte) f.type().id());
        buf.put(f.flags());
        assertArrayEquals(buf.array(), PskCipher.associatedData(f));
    }
}