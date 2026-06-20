package dev.kifuko.mctransport.client;

import dev.kifuko.mctransport.McTransport;
import dev.kifuko.mctransport.net.TransportExecutors;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * TCP listener that accepts local sockets on {@code listen_host:listen_port}.
 *
 * <p>The accept loop runs on a dedicated executor (never on a Minecraft
 * thread). For each accepted socket the listener asks the
 * {@link ClientTunnelSession} to open a new stream and attaches the socket
 * to it. New sockets are closed immediately when the config is disabled or
 * the session is not authenticated.</p>
 */
public final class LocalTcpListener {

    private final String listenHost;
    private final int listenPort;
    private final TransportExecutors executors;
    private final ClientTunnelSessionProvider sessionProvider;
    private final Runnable onStreamAttached;

    private ServerSocket serverSocket;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread acceptThread;

    public LocalTcpListener(String listenHost,
                            int listenPort,
                            TransportExecutors executors,
                            ClientTunnelSessionProvider sessionProvider,
                            Runnable onStreamAttached) {
        this.listenHost = listenHost;
        this.listenPort = listenPort;
        this.executors = executors;
        this.sessionProvider = sessionProvider;
        this.onStreamAttached = onStreamAttached == null ? () -> { } : onStreamAttached;
    }

    /** Binds the server socket and starts the accept loop. Idempotent. */
    public synchronized void start() throws IOException {
        if (running.get()) {
            return;
        }
        ServerSocket ss = new ServerSocket();
        ss.bind(new InetSocketAddress(listenHost, listenPort));
        ss.setSoTimeout(500);
        this.serverSocket = ss;
        running.set(true);
        McTransport.LOGGER.info("listening on {}:{}",
                listenHost, boundPort());
        Thread t = new Thread(this::runAcceptLoop, "mctransport-client-accept");
        t.setDaemon(true);
        this.acceptThread = t;
        t.start();
    }

    private void runAcceptLoop() {
        while (running.get()) {
            ServerSocket ss = serverSocket;
            if (ss == null) {
                return;
            }
            Socket socket;
            try {
                socket = ss.accept();
            } catch (java.net.SocketTimeoutException ste) {
                continue;
            } catch (IOException e) {
                if (running.get()) {
                    McTransport.LOGGER.error("accept failed", e);
                }
                return;
            }
            handleAccept(socket);
        }
    }

    private void handleAccept(Socket socket) {
        ClientTunnelSession session = sessionProvider.session();
        if (session == null || !session.isAuthenticated()) {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
            return;
        }
        ClientStream stream;
        try {
            stream = session.openLocalStream();
        } catch (IllegalStateException e) {
            McTransport.LOGGER.warn("refusing local connection: {}", e.getMessage());
            try {
                socket.close();
            } catch (IOException ignored) {
            }
            return;
        }
        executors.io().execute(() -> {
            try {
                stream.attach(socket, new byte[Math.min(stream.maxPayload(), 16 * 1024)]);
            } catch (Throwable t) {
                McTransport.LOGGER.error("stream attach failed", t);
                stream.closeReset();
            }
            onStreamAttached.run();
        });
    }

    public synchronized void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        ServerSocket ss = serverSocket;
        serverSocket = null;
        if (ss != null) {
            try {
                ss.close();
            } catch (IOException ignored) {
            }
        }
        Thread t = acceptThread;
        acceptThread = null;
        if (t != null) {
            t.interrupt();
        }
    }

    public boolean isRunning() {
        return running.get();
    }

    public int boundPort() {
        ServerSocket ss = serverSocket;
        return ss == null ? -1 : ss.getLocalPort();
    }

    /** Indirection used so the listener can pick up the live session. */
    @FunctionalInterface
    public interface ClientTunnelSessionProvider {
        ClientTunnelSession session();
    }
}
