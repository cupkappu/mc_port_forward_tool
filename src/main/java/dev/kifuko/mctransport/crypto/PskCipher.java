package dev.kifuko.mctransport.crypto;

import dev.kifuko.mctransport.protocol.Frame;
import dev.kifuko.mctransport.protocol.FrameCodec;
import dev.kifuko.mctransport.protocol.FrameType;
import dev.kifuko.mctransport.protocol.ProtocolException;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;

/**
 * Pre-shared-key AES-GCM AEAD for the MC Transport Dialer internal protocol.
 *
 * <p>The 32-byte AES key is derived from the PSK via SHA-256. Each encrypted
 * frame carries a fresh 12-byte nonce and the GCM authentication tag. Header
 * metadata (protocol version, session id, stream id, type, flags, length) is
 * bound in as associated data so tampering with any header field invalidates
 * the frame.</p>
 */
public final class PskCipher {

    /** AES-GCM nonce length in bytes. */
    public static final int NONCE_LEN = 12;

    /** AES-GCM tag length in bits. */
    public static final int TAG_BITS = 128;

    /** Length of the derived AES-256 key in bytes. */
    public static final int KEY_LEN = 32;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretKeySpec key;
    private final SecureRandom rng;

    public PskCipher(String psk) {
        this(psk, RANDOM);
    }

    /** Constructor that takes an explicit RNG, used by tests for determinism. */
    public PskCipher(String psk, SecureRandom rng) {
        if (psk == null || psk.isEmpty()) {
            throw new IllegalArgumentException("psk must not be empty");
        }
        this.rng = rng;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(psk.getBytes(StandardCharsets.UTF_8));
            this.key = new SecretKeySpec(digest, "AES");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /**
     * Encrypts the payload of a frame and returns
     * {@code nonce(12) || ciphertext || tag(16)}.
     */
    public byte[] encrypt(Frame frame) {
        try {
            byte[] nonce = new byte[NONCE_LEN];
            rng.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(associatedData(frame));
            byte[] ct = cipher.doFinal(frame.payload());
            ByteBuffer out = ByteBuffer.allocate(NONCE_LEN + ct.length).order(ByteOrder.BIG_ENDIAN);
            out.put(nonce);
            out.put(ct);
            return out.array();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("encryption failure", e);
        }
    }

    /**
     * Decrypts an encrypted frame body and returns the plaintext payload.
     *
     * @throws ProtocolException if the body is too short, the tag fails to
     *                          verify, or the header associated data was
     *                          modified in transit.
     */
    public byte[] decrypt(Frame frame, byte[] encryptedBody) {
        if (encryptedBody == null || encryptedBody.length < NONCE_LEN + 16) {
            throw new ProtocolException("encrypted body too short");
        }
        try {
            byte[] nonce = new byte[NONCE_LEN];
            System.arraycopy(encryptedBody, 0, nonce, 0, NONCE_LEN);
            int ctLen = encryptedBody.length - NONCE_LEN;
            byte[] ct = new byte[ctLen];
            System.arraycopy(encryptedBody, NONCE_LEN, ct, 0, ctLen);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(associatedData(frame));
            return cipher.doFinal(ct);
        } catch (AEADBadTagException e) {
            throw new ProtocolException("authentication failed", e);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("decryption failure", e);
        }
    }

    /**
     * Associated data = the header bytes exactly as produced by
     * {@link dev.kifuko.mctransport.protocol.FrameCodec} up to (but excluding)
     * the payload length.
     */
    static byte[] associatedData(Frame frame) {
        ByteBuffer buf = ByteBuffer.allocate(dev.kifuko.mctransport.protocol.FrameCodec.HEADER_BYTES - 4)
                .order(ByteOrder.BIG_ENDIAN);
        buf.put(frame.protocolVersion());
        buf.putInt(frame.sessionId());
        buf.putInt(frame.streamId());
        buf.put((byte) frame.type().id());
        buf.put(frame.flags());
        return buf.array();
    }
}