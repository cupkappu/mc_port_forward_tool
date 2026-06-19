package dev.kifuko.mctransport.protocol;

import dev.kifuko.mctransport.crypto.PskCipher;

/**
 * Encrypts and decrypts frame payloads while leaving routing headers visible.
 *
 * <p>Session and stream code works with plaintext {@link Frame} values. Fabric
 * bridges use this helper at the Minecraft networking boundary so the channel
 * carries encrypted payload bytes only.</p>
 */
public final class SecureFrameCodec {

    private final PskCipher cipher;
    private final int maxPlainPayloadSize;

    public SecureFrameCodec(PskCipher cipher, int maxPlainPayloadSize) {
        if (cipher == null) {
            throw new IllegalArgumentException("cipher must not be null");
        }
        if (maxPlainPayloadSize <= 0) {
            throw new IllegalArgumentException("maxPlainPayloadSize must be positive");
        }
        this.cipher = cipher;
        this.maxPlainPayloadSize = maxPlainPayloadSize;
    }

    public static int encryptedPayloadLimit(int plaintextPayloadLimit) {
        if (plaintextPayloadLimit <= 0) {
            throw new IllegalArgumentException("plaintextPayloadLimit must be positive");
        }
        return plaintextPayloadLimit + PskCipher.NONCE_LEN + (PskCipher.TAG_BITS / 8);
    }

    public Frame encryptForWire(Frame plaintextFrame) {
        byte[] encryptedPayload = cipher.encrypt(plaintextFrame);
        return Frame.createTrusted(
                plaintextFrame.protocolVersion(),
                plaintextFrame.sessionId(),
                plaintextFrame.streamId(),
                plaintextFrame.type(),
                plaintextFrame.flags(),
                encryptedPayload);
    }

    public Frame decryptFromWire(Frame encryptedFrame) {
        byte[] plaintextPayload = cipher.decrypt(encryptedFrame, encryptedFrame.payload());
        return Frame.create(
                encryptedFrame.protocolVersion(),
                encryptedFrame.sessionId(),
                encryptedFrame.streamId(),
                encryptedFrame.type(),
                encryptedFrame.flags(),
                plaintextPayload,
                maxPlainPayloadSize);
    }
}
