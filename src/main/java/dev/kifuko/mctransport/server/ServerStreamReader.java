package dev.kifuko.mctransport.server;

import java.io.IOException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;

/**
 * Reads target socket bytes and turns them into outbound DATA frames, on a
 * background executor. One instance per {@link ServerStream}.
 */
public final class ServerStreamReader {

    private final ServerStream stream;
    private final byte[] buffer;
    private final ExecutorService io;
    private final CopyOnWriteArrayList<Runnable> shutdownHooks = new CopyOnWriteArrayList<>();

    public ServerStreamReader(ServerStream stream, int chunkBytes, ExecutorService io) {
        this.stream = stream;
        this.buffer = new byte[chunkBytes];
        this.io = io;
    }

    public void start() {
        io.execute(this::loop);
    }

    public void onClose(Runnable hook) {
        if (hook != null) {
            shutdownHooks.add(hook);
        }
    }

    private void loop() {
        try {
            while (!stream.isClosed()) {
                int n;
                try {
                    n = stream.readTargetChunk(buffer);
                } catch (IOException e) {
                    stream.closeReset();
                    return;
                }
                if (n < 0) {
                    stream.sendClose();
                    stream.closeClean();
                    return;
                }
                if (n == 0) {
                    continue;
                }
                stream.sendTargetBytes(buffer, n);
            }
        } finally {
            for (Runnable hook : shutdownHooks) {
                try {
                    hook.run();
                } catch (Throwable t) {
                    // best effort
                }
            }
        }
    }
}