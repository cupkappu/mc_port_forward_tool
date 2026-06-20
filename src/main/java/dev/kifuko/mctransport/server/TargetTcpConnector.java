package dev.kifuko.mctransport.server;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.ExecutorService;

/**
 * Dials a TCP target resolved from the player's route. The connector
 * accepts no host/port from network frames; the caller (the
 * {@link DefaultServerStreamFactory}) supplies the resolved host and
 * port per OPEN frame.
 */
public final class TargetTcpConnector {

    private final int connectTimeoutMillis;
    private final ExecutorService ioExecutor;

    public TargetTcpConnector(int connectTimeoutSeconds, ExecutorService ioExecutor) {
        if (connectTimeoutSeconds <= 0) {
            throw new IllegalArgumentException(
                    "connectTimeoutSeconds must be positive, got: " + connectTimeoutSeconds);
        }
        this.connectTimeoutMillis = Math.toIntExact(connectTimeoutSeconds * 1000L);
        this.ioExecutor = ioExecutor;
    }

    public int connectTimeoutSeconds() {
        return connectTimeoutMillis / 1000;
    }

    public ExecutorService ioExecutor() {
        return ioExecutor;
    }

    public Socket connect(String host, int port) throws IOException {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(host, port), connectTimeoutMillis);
            socket.setTcpNoDelay(true);
            return socket;
        } catch (IOException e) {
            socket.close();
            throw e;
        }
    }
}