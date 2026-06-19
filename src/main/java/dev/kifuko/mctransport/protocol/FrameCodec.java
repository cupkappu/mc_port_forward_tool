package dev.kifuko.mctransport.protocol;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Binary frame codec for the MC Transport Dialer internal protocol.
 *
 * <p>Wire layout (big-endian, no padding):</p>
 *
 * <pre>
 *   u8    protocol version
 *   u32   session id
 *   u32   stream id
 *   u8    frame type id
 *   u8    flags
 *   u32   payload length
 *   u8[]  payload (length bytes)
 * </pre>
 *
 * <p>The codec does NOT encrypt the payload. Encryption happens in
 * {@link dev.kifuko.mctransport.crypto.PskCipher}; this codec only translates
 * between {@link Frame} values and byte sequences.</p>
 */
public final class FrameCodec {

    /** Number of bytes in the header before the payload. */
    public static final int HEADER_BYTES = 1 + 4 + 4 + 1 + 1 + 4;

    private final int maxPayloadSize;

    public FrameCodec(int maxPayloadSize) {
        if (maxPayloadSize <= 0) {
            throw new IllegalArgumentException(
                    "maxPayloadSize must be positive, got: " + maxPayloadSize);
        }
        this.maxPayloadSize = maxPayloadSize;
    }

    public int maxPayloadSize() {
        return maxPayloadSize;
    }

    /**
     * Encodes a frame into bytes. The returned array is freshly allocated
     * and safe to mutate.
     */
    public byte[] encode(Frame frame) {
        if (frame.payloadLength() > maxPayloadSize) {
            throw new ProtocolException(
                    "payload length " + frame.payloadLength() + " exceeds max " + maxPayloadSize);
        }
        ByteBuffer buf = ByteBuffer.allocate(HEADER_BYTES + frame.payloadLength())
                .order(ByteOrder.BIG_ENDIAN);
        buf.put(frame.protocolVersion());
        buf.putInt(frame.sessionId());
        buf.putInt(frame.streamId());
        buf.put((byte) frame.type().id());
        buf.put(frame.flags());
        buf.putInt(frame.payloadLength());
        buf.put(frame.payloadView());
        return buf.array();
    }

    /**
     * Decodes exactly one frame from {@code bytes}. The entire buffer must
     * contain exactly one frame (no leading or trailing bytes).
     */
    public Frame decode(byte[] bytes) {
        if (bytes == null) {
            throw new ProtocolException("frame bytes must not be null");
        }
        if (bytes.length < HEADER_BYTES) {
            throw new ProtocolException(
                    "truncated frame header: " + bytes.length + " bytes");
        }
        ByteBuffer buf = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
        byte version = buf.get();
        int sessionId = buf.getInt();
        int streamId = buf.getInt();
        FrameType type = FrameType.fromId(buf.get() & 0xFF);
        byte flags = buf.get();
        int payloadLen = buf.getInt();
        if (payloadLen < 0) {
            throw new ProtocolException("negative payload length: " + payloadLen);
        }
        if (payloadLen > maxPayloadSize) {
            throw new ProtocolException(
                    "payload length " + payloadLen + " exceeds max " + maxPayloadSize);
        }
        if (buf.remaining() != payloadLen) {
            throw new ProtocolException(
                    "trailing bytes: expected " + payloadLen + ", got " + buf.remaining());
        }
        byte[] payload = new byte[payloadLen];
        buf.get(payload);
        return Frame.create(version, sessionId, streamId, type, flags, payload, maxPayloadSize);
    }
}