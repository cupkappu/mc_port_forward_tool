package dev.kifuko.mctransport.protocol;

import java.util.Arrays;

/**
 * Immutable internal frame value.
 *
 * <p>Header fields are public-final for cheap access. The payload is copied
 * defensively on construction and on read so that callers cannot mutate the
 * frame after it has been created.</p>
 */
public final class Frame {

    public static final int MAX_STREAM_ID = Integer.MAX_VALUE;

    private final byte protocolVersion;
    private final int sessionId;
    private final int streamId;
    private final FrameType type;
    private final byte flags;
    private final byte[] payload;

    private Frame(byte protocolVersion,
                  int sessionId,
                  int streamId,
                  FrameType type,
                  byte flags,
                  byte[] payload) {
        this.protocolVersion = protocolVersion;
        this.sessionId = sessionId;
        this.streamId = streamId;
        this.type = type;
        this.flags = flags;
        this.payload = payload;
    }

    /**
     * Constructs a frame and validates its invariants.
     *
     * @param maxPayloadSize maximum payload length the codec will allow.
     *                       Frames larger than this are rejected.
     */
    public static Frame create(byte protocolVersion,
                               int sessionId,
                               int streamId,
                               FrameType type,
                               byte flags,
                               byte[] payload,
                               int maxPayloadSize) {
        if (type == null) {
            throw new ProtocolException("frame type must not be null");
        }
        if (streamId < 0) {
            throw new ProtocolException("stream id must be non-negative, got: " + streamId);
        }
        byte[] body = payload == null ? new byte[0] : payload;
        if (body.length > maxPayloadSize) {
            throw new ProtocolException(
                    "payload length " + body.length + " exceeds max " + maxPayloadSize);
        }
        return new Frame(protocolVersion, sessionId, streamId, type, flags,
                Arrays.copyOf(body, body.length));
    }

    /**
     * Convenience factory for internal frames (e.g. AUTH_OK, PONG) that
     * are produced by trusted code and have no external payload cap.
     * Skips the {@code maxPayloadSize} check.
     */
    public static Frame createTrusted(byte protocolVersion,
                                      int sessionId,
                                      int streamId,
                                      FrameType type,
                                      byte flags,
                                      byte[] payload) {
        if (type == null) {
            throw new ProtocolException("frame type must not be null");
        }
        if (streamId < 0) {
            throw new ProtocolException("stream id must be non-negative, got: " + streamId);
        }
        byte[] body = payload == null ? new byte[0] : payload;
        return new Frame(protocolVersion, sessionId, streamId, type, flags,
                Arrays.copyOf(body, body.length));
    }

    public byte protocolVersion() {
        return protocolVersion;
    }

    public int sessionId() {
        return sessionId;
    }

    public int streamId() {
        return streamId;
    }

    public FrameType type() {
        return type;
    }

    public byte flags() {
        return flags;
    }

    /** Returns a defensive copy of the payload. */
    public byte[] payload() {
        return Arrays.copyOf(payload, payload.length);
    }

    public int payloadLength() {
        return payload.length;
    }

    /** Returns the payload without copying. Callers must not modify the array. */
    byte[] payloadView() {
        return payload;
    }

    @Override
    public String toString() {
        return "Frame{" +
                "v=" + protocolVersion +
                ", session=" + sessionId +
                ", stream=" + streamId +
                ", type=" + type +
                ", flags=" + flags +
                ", len=" + payload.length +
                '}';
    }
}