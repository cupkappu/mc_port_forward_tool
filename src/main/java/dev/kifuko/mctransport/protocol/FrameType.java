package dev.kifuko.mctransport.protocol;

/**
 * Frame types used by the MC Transport Dialer internal protocol.
 *
 * <p>Numeric IDs are stable and start at 1. Wire encoding is big-endian.</p>
 */
public enum FrameType {
    AUTH(1),
    AUTH_OK(2),
    OPEN(3),
    DATA(4),
    CLOSE(5),
    RESET(6),
    PING(7),
    PONG(8),
    ERROR(9);

    private final int id;

    FrameType(int id) {
        this.id = id;
    }

    public int id() {
        return id;
    }

    /**
     * Resolves a {@link FrameType} from its numeric wire id.
     *
     * @throws ProtocolException when {@code id} does not match a known type.
     */
    public static FrameType fromId(int id) {
        for (FrameType t : values()) {
            if (t.id == id) {
                return t;
            }
        }
        throw new ProtocolException("unknown frame type id: " + id);
    }
}