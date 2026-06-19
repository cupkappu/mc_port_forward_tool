package dev.kifuko.mctransport.net;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Named executor services for transport work. All blocking I/O and crypto
 * runs here; never on the Minecraft render / server-tick / Netty event-loop
 * threads.
 */
public final class TransportExecutors {

    private final ExecutorService accept;
    private final ExecutorService io;
    private boolean shutdown;

    public TransportExecutors(String modId) {
        AtomicLong serial = new AtomicLong();
        this.accept = Executors.newSingleThreadExecutor(named(modId, "accept", serial));
        this.io = Executors.newCachedThreadPool(named(modId, "io", serial));
    }

    /** Visible for tests: build from externally supplied executors. */
    public TransportExecutors(ExecutorService accept, ExecutorService io) {
        this.accept = accept;
        this.io = io;
    }

    public ExecutorService accept() {
        return accept;
    }

    public ExecutorService io() {
        return io;
    }

    public void shutdown() {
        if (shutdown) {
            return;
        }
        shutdown = true;
        accept.shutdownNow();
        io.shutdownNow();
    }

    public boolean isShutdown() {
        return shutdown;
    }

    private static ThreadFactory named(String modId, String role, AtomicLong serial) {
        AtomicLong counter = new AtomicLong();
        return r -> {
            Thread t = new Thread(r, "mctransport-" + modId + "-" + role
                    + "-" + serial.incrementAndGet()
                    + "-" + counter.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
    }
}