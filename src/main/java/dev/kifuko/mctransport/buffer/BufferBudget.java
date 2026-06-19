package dev.kifuko.mctransport.buffer;

/**
 * Tracks per-stream and global buffer reservations to enforce hard queue
 * limits.
 *
 * <p>Reservations are accounted in bytes. Each stream gets a per-stream
 * limit; all streams together are bounded by a global limit. Reservations
 * fail closed when either limit would be exceeded.</p>
 */
public final class BufferBudget {

    private final int perStreamLimit;
    private final long globalLimit;

    private long globalReserved;

    public BufferBudget(int perStreamLimit, long globalLimit) {
        if (perStreamLimit <= 0) {
            throw new IllegalArgumentException("perStreamLimit must be positive");
        }
        if (globalLimit <= 0) {
            throw new IllegalArgumentException("globalLimit must be positive");
        }
        this.perStreamLimit = perStreamLimit;
        this.globalLimit = globalLimit;
    }

    public int perStreamLimit() {
        return perStreamLimit;
    }

    public long globalLimit() {
        return globalLimit;
    }

    public synchronized long globalReserved() {
        return globalReserved;
    }

    /**
     * Reserves {@code bytes} against both the per-stream and global limits.
     *
     * @throws IllegalStateException when the reservation would exceed either
     *                               limit. On failure, no bytes are reserved.
     */
    public synchronized void reserve(int streamId, int bytes, ReservationState state) {
        if (bytes < 0) {
            throw new IllegalArgumentException("bytes must be non-negative");
        }
        if (state == null) {
            throw new IllegalArgumentException("state must not be null");
        }
        long streamReserved = state.reservedFor(streamId);
        if (streamReserved + bytes > perStreamLimit) {
            throw new IllegalStateException(
                    "per-stream limit exceeded for stream " + streamId);
        }
        if (globalReserved + bytes > globalLimit) {
            throw new IllegalStateException("global buffer limit exceeded");
        }
        state.add(streamId, bytes);
        globalReserved += bytes;
    }

    /**
     * Releases {@code bytes} previously reserved for {@code streamId}.
     * Double-release is harmless: the second call sees no reservation and
     * returns false without mutating the global counter.
     */
    public synchronized boolean release(int streamId, int bytes, ReservationState state) {
        if (bytes < 0) {
            throw new IllegalArgumentException("bytes must be non-negative");
        }
        long current = state.reservedFor(streamId);
        long take = Math.min(current, bytes);
        if (take == 0) {
            return false;
        }
        state.add(streamId, -take);
        globalReserved -= take;
        return true;
    }

    /**
     * Releases all bytes reserved for {@code streamId}. Idempotent.
     */
    public synchronized void releaseAll(int streamId, ReservationState state) {
        long current = state.reservedFor(streamId);
        if (current == 0) {
            return;
        }
        state.add(streamId, -current);
        globalReserved -= current;
    }
}