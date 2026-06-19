package dev.kifuko.mctransport.stream;

import java.util.HashMap;
import java.util.Map;

/**
 * Allocates and tracks transport stream IDs.
 *
 * <p>The client side is responsible for ID generation; the server side
 * registers streams by client-provided IDs. Each ID must be unique within
 * the registry's scope. The registry does not interpret the payloads that
 * flow through streams; it only tracks metadata.</p>
 */
public final class StreamRegistry {

    /** Callback invoked when a stream entry is removed. */
    @FunctionalInterface
    public interface RemovalListener {
        void onRemoved(int streamId);
    }

    private final int maxStreams;
    private final boolean clientSide;
    private final Map<Integer, StreamEntry> entries = new HashMap<>();
    private int nextClientId = 1;
    private final RemovalListener listener;

    public StreamRegistry(int maxStreams, boolean clientSide) {
        this(maxStreams, clientSide, id -> { });
    }

    public StreamRegistry(int maxStreams, boolean clientSide, RemovalListener listener) {
        if (maxStreams <= 0) {
            throw new IllegalArgumentException("maxStreams must be positive");
        }
        this.maxStreams = maxStreams;
        this.clientSide = clientSide;
        this.listener = listener == null ? id -> { } : listener;
    }

    /**
     * Allocates a new client-side stream ID and registers it. Throws
     * {@link IllegalStateException} when the configured cap is reached.
     */
    public synchronized int allocateClient() {
        if (!clientSide) {
            throw new IllegalStateException("server-side registry does not allocate IDs");
        }
        if (entries.size() >= maxStreams) {
            throw new IllegalStateException("max streams reached: " + maxStreams);
        }
        int id;
        do {
            id = nextClientId++;
            if (nextClientId <= 0) {
                nextClientId = 1;
            }
        } while (entries.containsKey(id));
        entries.put(id, new StreamEntry(id, StreamState.ALLOCATED));
        return id;
    }

    /** Registers a server-side stream with a client-provided ID. */
    public synchronized void registerServer(int streamId) {
        if (clientSide) {
            throw new IllegalStateException("client-side registry does not register server IDs");
        }
        if (streamId < 0) {
            throw new IllegalArgumentException("negative stream id");
        }
        if (entries.size() >= maxStreams) {
            throw new IllegalStateException("max streams reached: " + maxStreams);
        }
        if (entries.containsKey(streamId)) {
            throw new IllegalStateException("duplicate stream id: " + streamId);
        }
        entries.put(streamId, new StreamEntry(streamId, StreamState.OPEN_SENT));
    }

    public synchronized boolean contains(int streamId) {
        return entries.containsKey(streamId);
    }

    public synchronized StreamEntry get(int streamId) {
        return entries.get(streamId);
    }

    public synchronized void setState(int streamId, StreamState state) {
        StreamEntry e = entries.get(streamId);
        if (e == null) {
            throw new IllegalArgumentException("unknown stream id: " + streamId);
        }
        e.state = state;
    }

    public synchronized int size() {
        return entries.size();
    }

    public int maxStreams() {
        return maxStreams;
    }

    /** Removes a stream and fires the removal listener. Idempotent. */
    public synchronized boolean remove(int streamId) {
        StreamEntry removed = entries.remove(streamId);
        if (removed == null) {
            return false;
        }
        listener.onRemoved(streamId);
        return true;
    }

    /** Clears all streams, firing the removal listener for each. */
    public synchronized void clear() {
        int[] ids = entries.keySet().stream().mapToInt(Integer::intValue).toArray();
        entries.clear();
        for (int id : ids) {
            listener.onRemoved(id);
        }
    }

    /** Registry entry: the stream ID plus its lifecycle state. */
    public static final class StreamEntry {
        private final int id;
        private volatile StreamState state;

        StreamEntry(int id, StreamState state) {
            this.id = id;
            this.state = state;
        }

        public int id() {
            return id;
        }

        public StreamState state() {
            return state;
        }
    }
}