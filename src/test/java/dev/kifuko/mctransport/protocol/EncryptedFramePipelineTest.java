package dev.kifuko.mctransport.protocol;

import dev.kifuko.mctransport.crypto.PskCipher;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * End-to-end test: encrypt payload, encode frame, decode frame, decrypt.
 */
class EncryptedFramePipelineTest {

    private static final int MAX_PAYLOAD = 1024;
    private final FrameCodec codec = new FrameCodec(MAX_PAYLOAD);

    private static final SecureRandom FIXED = new SecureRandom() {
        private static final long serialVersionUID = 1L;
        private int counter = 0;
        @Override public void nextBytes(byte[] b) {
            for (int i = 0; i < b.length; i++) b[i] = (byte) (counter++);
        }
    };

    @Test
    void roundTripsEveryFrameTypeWithEncryption() {
        PskCipher cipher = new PskCipher("shared", FIXED);
        byte[] payload = "secret-payload".getBytes();
        for (FrameType t : FrameType.values()) {
            Frame plaintext = Frame.create((byte) 1, 11, 22, t, (byte) 0, payload, MAX_PAYLOAD);
            Frame encrypted = Frame.create(
                    plaintext.protocolVersion(), plaintext.sessionId(), plaintext.streamId(),
                    plaintext.type(), plaintext.flags(),
                    cipher.encrypt(plaintext), MAX_PAYLOAD);
            byte[] encoded = codec.encode(encrypted);
            Frame decoded = codec.decode(encoded);
            byte[] decrypted = cipher.decrypt(decoded, decoded.payload());
            assertArrayEquals(payload, decrypted);
        }
    }

    @Test
    void tamperingAnywhereInPayloadFails() {
        PskCipher cipher = new PskCipher("shared", FIXED);
        Frame plaintext = Frame.create((byte) 1, 1, 1, FrameType.DATA, (byte) 0,
                "hello".getBytes(), MAX_PAYLOAD);
        byte[] encPayload = cipher.encrypt(plaintext);
        Frame encrypted = Frame.create(plaintext.protocolVersion(),
                plaintext.sessionId(), plaintext.streamId(), plaintext.type(),
                plaintext.flags(), encPayload, MAX_PAYLOAD);
        byte[] encoded = codec.encode(encrypted);
        Frame decoded = codec.decode(encoded);
        byte[] tampered = decoded.payload().clone();
        tampered[PskCipher.NONCE_LEN + 2] ^= 0x55;
        assertThrows(ProtocolException.class, () -> cipher.decrypt(decoded, tampered));
    }

    @Test
    void plaintextVsEncryptedFramesAreNotByteEqual() {
        PskCipher cipher = new PskCipher("shared", FIXED);
        byte[] payload = "ping".getBytes();
        Frame plaintext = Frame.create((byte) 1, 1, 1, FrameType.PING, (byte) 0, payload, MAX_PAYLOAD);
        byte[] encBody = cipher.encrypt(plaintext);
        assertNotEquals(plaintext.payloadLength(), encBody.length);
    }
}