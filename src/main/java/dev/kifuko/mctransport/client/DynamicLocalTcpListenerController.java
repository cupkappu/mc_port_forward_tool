package dev.kifuko.mctransport.client;

import dev.kifuko.mctransport.net.TransportExecutors;

import java.io.IOException;

/**
 * Creates and replaces the loopback listener from server-pushed config.
 */
public final class DynamicLocalTcpListenerController implements ClientListenerController {

    private final TransportExecutors executors;
    private final LocalTcpListener.ClientTunnelSessionProvider sessionProvider;
    private final Runnable onStreamAttached;
    private LocalTcpListener listener;

    public DynamicLocalTcpListenerController(TransportExecutors executors,
                                             LocalTcpListener.ClientTunnelSessionProvider sessionProvider,
                                             Runnable onStreamAttached) {
        this.executors = executors;
        this.sessionProvider = sessionProvider;
        this.onStreamAttached = onStreamAttached;
    }

    @Override
    public synchronized void apply(String listenHost, int listenPort) throws IOException {
        clear();
        LocalTcpListener next = new LocalTcpListener(listenHost, listenPort, executors,
                sessionProvider, onStreamAttached);
        next.start();
        listener = next;
    }

    @Override
    public synchronized void clear() {
        if (listener != null) {
            listener.stop();
            listener = null;
        }
    }

    @Override
    public synchronized boolean isListening() {
        return listener != null && listener.isRunning();
    }
}
