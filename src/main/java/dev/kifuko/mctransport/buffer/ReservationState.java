package dev.kifuko.mctransport.buffer;

import java.util.HashMap;
import java.util.Map;

/**
 * Holds per-stream reserved-byte counters for {@link BufferBudget}.
 *
 * <p>Separated from {@link BufferBudget} so the budget can be reset (e.g. on
 * disconnect) without losing the per-stream accounting it owns.</p>
 */
public final class ReservationState {

    private final Map<Integer, Long> reserved = new HashMap<>();

    public long reservedFor(int streamId) {
        return reserved.getOrDefault(streamId, 0L);
    }

    /** Visible for {@link BufferBudget}. Adds (or subtracts when negative). */
    public void add(int streamId, long delta) {
        long cur = reserved.getOrDefault(streamId, 0L);
        long next = cur + delta;
        if (next < 0) {
            throw new IllegalStateException("reservation went negative for stream " + streamId);
        }
        if (next == 0) {
            reserved.remove(streamId);
        } else {
            reserved.put(streamId, next);
        }
    }

    public void clearStream(int streamId) {
        reserved.remove(streamId);
    }
}