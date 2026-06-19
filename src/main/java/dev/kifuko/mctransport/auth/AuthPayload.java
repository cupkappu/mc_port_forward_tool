package dev.kifuko.mctransport.auth;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Wire format for the {@code AUTH} frame payload.
 *
 * <pre>
 *   u8    protocol version
 *   u16   client nonce length (N)
 *   u8[N] client nonce bytes
 *   u64   timestamp seconds since epoch
 *   u16   player uuid length (M, 32 or 36 typically)
 *   u8[M] player uuid bytes (canonical ASCII)
 * </pre>
 *
 * <p>The payload is encrypted as part of an {@code AUTH} frame; this class
 * only handles encoding and decoding the plaintext bytes.</p>
 */
public final class AuthPayload {

    /** Maximum length of the client nonce in bytes. */
    public static final int MAX_NONCE_LEN = 64;

    /** Maximum length of the player UUID string in bytes. */
    public static final int MAX_UUID_LEN = 64;

    private static final int SHORT_MAX = 0xFFFF;
    private static final long LONG_MAX = Long.MAX_VALUE;

    private AuthPayload() {
    }

    public static byte[] encode(byte protocolVersion,
                                UUID playerUuid,
                                byte[] clientNonce,
                                long timestampSeconds) {
        if (playerUuid == null) {
            throw new IllegalArgumentException("playerUuid must not be null");
        }
        if (clientNonce == null || clientNonce.length == 0 || clientNonce.length > MAX_NONCE_LEN) {
            throw new IllegalArgumentException(
                    "clientNonce length must be 1.." + MAX_NONCE_LEN);
        }
        if (timestampSeconds < 0 || timestampSeconds > LONG_MAX) {
            throw new IllegalArgumentException(
                    "timestampSeconds out of range: " + timestampSeconds);
        }
        byte[] uuidBytes = playerUuid.toString().getBytes(StandardCharsets.US_ASCII);
        if (uuidBytes.length > MAX_UUID_LEN) {
            throw new IllegalArgumentException("uuid string too long");
        }

        ByteBuffer buf = ByteBuffer.allocate(1 + 2 + clientNonce.length + 8 + 2 + uuidBytes.length)
                .order(ByteOrder.BIG_ENDIAN);
        buf.put(protocolVersion);
        buf.putShort((short) clientNonce.length);
        buf.put(clientNonce);
        buf.putLong(timestampSeconds);
        buf.putShort((short) uuidBytes.length);
        buf.put(uuidBytes);
        return buf.array();
    }

    public static Decoded decode(byte[] bytes, byte expectedProtocolVersion,
                                  long maxClockSkewSeconds, long nowSeconds) {
        if (bytes == null) {
            throw new IllegalArgumentException("bytes must not be null");
        }
        ByteBuffer buf = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
        if (buf.remaining() < 1 + 2 + 8 + 2) {
            throw new IllegalArgumentException("auth payload too short");
        }
        byte version = buf.get();
        if (version != expectedProtocolVersion) {
            throw new IllegalArgumentException(
                    "protocol version mismatch: expected " + expectedProtocolVersion
                            + " got " + version);
        }
        int nonceLen = buf.getShort() & SHORT_MAX;
        if (nonceLen <= 0 || nonceLen > MAX_NONCE_LEN) {
            throw new IllegalArgumentException("bad nonce length: " + nonceLen);
        }
        if (buf.remaining() < nonceLen + 8 + 2) {
            throw new IllegalArgumentException("auth payload truncated after nonce");
        }
        byte[] nonce = new byte[nonceLen];
        buf.get(nonce);
        long timestamp = buf.getLong();
        if (timestamp < 0) {
            throw new IllegalArgumentException("negative timestamp");
        }
        long skew = Math.abs(nowSeconds - timestamp);
        if (skew > maxClockSkewSeconds) {
            throw new IllegalArgumentException(
                    "timestamp skew too large: " + skew + " > " + maxClockSkewSeconds);
        }
        int uuidLen = buf.getShort() & SHORT_MAX;
        if (uuidLen <= 0 || uuidLen > MAX_UUID_LEN) {
            throw new IllegalArgumentException("bad uuid length: " + uuidLen);
        }
        if (buf.remaining() != uuidLen) {
            throw new IllegalArgumentException("trailing bytes in auth payload");
        }
        byte[] uuidBytes = new byte[uuidLen];
        buf.get(uuidBytes);
        String uuidString = new String(uuidBytes, StandardCharsets.US_ASCII);
        UUID uuid;
        try {
            uuid = UUID.fromString(uuidString);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("invalid uuid: " + uuidString, e);
        }
        return new Decoded(version, uuid, nonce, timestamp);
    }

    public record Decoded(byte protocolVersion, UUID playerUuid, byte[] clientNonce,
                          long timestampSeconds) {
    }
}