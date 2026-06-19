package dev.kifuko.mctransport.protocol;

import dev.kifuko.mctransport.crypto.PskCipher;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SecureFrameCodecTest {

    @Test
    void encryptForWireHidesPlaintextAndDecryptsToOriginalFrame() {
        SecureFrameCodec secure = new SecureFrameCodec(new PskCipher("shared"), 1024);
        byte[] plaintext = "secret tcp bytes".getBytes(StandardCharsets.UTF_8);
        Frame frame = Frame.create((byte) 1, 7, 42, FrameType.DATA, (byte) 3,
                plaintext, 1024);

        Frame encrypted = secure.encryptForWire(frame);

        assertEquals(frame.protocolVersion(), encrypted.protocolVersion());
        assertEquals(frame.sessionId(), encrypted.sessionId());
        assertEquals(frame.streamId(), encrypted.streamId());
        assertEquals(frame.type(), encrypted.type());
        assertEquals(frame.flags(), encrypted.flags());
        assertFalse(new String(encrypted.payload(), StandardCharsets.UTF_8)
                .contains("secret tcp bytes"));

        Frame decrypted = secure.decryptFromWire(encrypted);
        assertEquals(frame.type(), decrypted.type());
        assertEquals(frame.streamId(), decrypted.streamId());
        assertArrayEquals(plaintext, decrypted.payload());
    }

    @Test
    void tamperedWirePayloadFailsAuthentication() {
        SecureFrameCodec secure = new SecureFrameCodec(new PskCipher("shared"), 1024);
        Frame frame = Frame.create((byte) 1, 7, 42, FrameType.OPEN, (byte) 0,
                new byte[0], 1024);
        Frame encrypted = secure.encryptForWire(frame);
        byte[] tampered = encrypted.payload();
        tampered[tampered.length - 1] ^= 0x01;
        Frame broken = Frame.createTrusted(encrypted.protocolVersion(), encrypted.sessionId(),
                encrypted.streamId(), encrypted.type(), encrypted.flags(), tampered);

        assertThrows(ProtocolException.class, () -> secure.decryptFromWire(broken));
    }
}
