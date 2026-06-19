package dev.kifuko.mctransport.server;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.channels.SocketChannel;
import java.util.concurrent.ExecutorService;

/**
 * Dials the fixed {@code target_host:target_port} configured on the server
 * mod. The host and port are NEVER taken from any inbound frame; the server
 * mod cannot be coerced into dialing arbitrary destinations.
 */
public final class TargetTcpConnector {

    private final String host;
    private final int port;
    private final int connectTimeoutMillis;
    private final ExecutorService ioExecutor;

    public TargetTcpConnector(String host, int port, int connectTimeoutSeconds,
                              ExecutorService ioExecutor) {
        this.host = host;
        this.port = port;
        this.connectTimeoutMillis = Math.toIntExact(connectTimeoutSeconds * 1000L);
        this.ioExecutor = ioExecutor;
    }

    public String host() {
        return host;
    }

    public int port() {
        return port;
    }

    public int connectTimeoutSeconds() {
        return connectTimeoutMillis / 1000;
    }

    public ExecutorService ioExecutor() {
        return ioExecutor;
    }

    /**
     * Opens a blocking TCP socket to the fixed target. Callers MUST close
     * the returned socket once finished. This method must not be called from
     * the Minecraft server-tick or Netty event-loop threads.
     */
    public Socket connect() throws IOException {
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

    /**
     * Result wrapper that exposes the connected socket alongside its I/O
     * streams. Streams are obtained lazily because opening an
     * {@link InputStream} immediately can defeat future streaming use.
     */
    public static final class Connected {
        private final Socket socket;

        public Connected(Socket socket) {
            this.socket = socket;
        }

        public Socket socket() {
            return socket;
        }

        public InputStream input() throws IOException {
            return socket.getInputStream();
        }

        public OutputStream output() throws IOException {
            return socket.getOutputStream();
        }

        public void close() throws IOException {
            socket.close();
        }

        public SocketChannel channel() {
            return socket.getChannel();
        }
    }
}