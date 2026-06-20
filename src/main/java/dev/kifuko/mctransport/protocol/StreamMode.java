package dev.kifuko.mctransport.protocol;

/**
 * Stream transport mode selectable per-player route.
 *
 * <p>{@link #DIRECT} maps TCP socket reads 1:1 to DATA frames (current).
 * {@link #KCP} feeds socket reads through KCP stream-mode coalescing before
 * framing, reducing frame count and protocol-header overhead.</p>
 */
public enum StreamMode {
    DIRECT,
    KCP;

    public static StreamMode fromString(String s) {
        if (s == null || s.isBlank()) {
            return DIRECT;
        }
        return switch (s.trim().toUpperCase()) {
            case "KCP" -> KCP;
            case "DIRECT", "DEFAULT" -> DIRECT;
            default -> throw new IllegalArgumentException(
                    "unknown stream mode: " + s + ". Valid: DIRECT, KCP");
        };
    }
}
