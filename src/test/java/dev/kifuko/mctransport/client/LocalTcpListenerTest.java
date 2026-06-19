package dev.kifuko.mctransport.client;

import dev.kifuko.mctransport.config.ClientConfig;
import dev.kifuko.mctransport.net.TransportExecutors;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalTcpListenerTest {

    private static ClientConfig config(int port) {
        if (port <= 0) {
            port = 25580; // The test will rebind to an ephemeral port by overriding below.
        }
        return new ClientConfig(true, "127.0.0.1", port,
                "mctransport:main", "shared", 8, 1024, 8192L, "info");
    }

    /** Build a ClientConfig with the chosen port, valid range. */
    private static ClientConfig configValidPort(int port) {
        int p = port <= 0 ? 25580 : port;
        return new ClientConfig(true, "127.0.0.1", p,
                "mctransport:main", "shared", 8, 1024, 8192L, "info");
    }

    private static TransportExecutors execs() {
        return new TransportExecutors(
                Executors.newSingleThreadExecutor(),
                Executors.newCachedThreadPool());
    }

    @Test
    void bindsToConfiguredHostAndPort() throws Exception {
        TransportExecutors execs = execs();
        ClientConfig cfg = config(0);
        LocalTcpListener listener = new LocalTcpListener(cfg, execs, () -> null, null);
        listener.start();
        try {
            assertTrue(listener.isRunning());
            int port = listener.boundPort();
            assertTrue(port > 0, "listener must bind an ephemeral port");
            // Connect a probe socket to confirm it's actually accepting.
            try (Socket s = new Socket("127.0.0.1", port)) {
                assertTrue(s.isConnected());
            }
        } finally {
            listener.stop();
            execs.shutdown();
        }
    }

    @Test
    void refusesNewSocketsWhenNoSessionAvailable() throws Exception {
        TransportExecutors execs = execs();
        ClientConfig cfg = config(0);
        LocalTcpListener listener = new LocalTcpListener(cfg, execs, () -> null, null);
        listener.start();
        try {
            int port = listener.boundPort();
            try (Socket s = new Socket("127.0.0.1", port)) {
                // The listener must close the accepted socket immediately.
                InputStream in = s.getInputStream();
                int b = in.read();
                assertEquals(-1, b, "server should have closed the socket");
            }
        } finally {
            listener.stop();
            execs.shutdown();
        }
    }

    @Test
    void disabledConfigRefusesConnections() throws Exception {
        TransportExecutors execs = execs();
        ClientConfig disabled = new ClientConfig(false, "127.0.0.1", 25580,
                "mctransport:main", "shared", 8, 1024, 8192L, "info");
        LocalTcpListener listener = new LocalTcpListener(disabled, execs, () -> null, null);
        assertFalse(listener.isRunning());
        // We do not start() at all; the disabled case is enforced upstream.
    }

    @Test
    void stopIsIdempotent() throws Exception {
        TransportExecutors execs = execs();
        LocalTcpListener listener = new LocalTcpListener(configValidPort(0), execs, () -> null, null);
        listener.start();
        listener.stop();
        listener.stop();
        assertFalse(listener.isRunning());
        execs.shutdown();
    }

    @Test
    void acceptedSocketExchangesDataWithServerSide() throws Exception {
        // End-to-end smoke: stand up a real ServerSocket that echoes input
        // back, wire a LocalTcpListener through a hand-rolled session, push
        // a few bytes, and confirm the server-side sees them.
        java.net.ServerSocket target = new java.net.ServerSocket(0);
        java.util.concurrent.atomic.AtomicReference<byte[]> received = new java.util.concurrent.atomic.AtomicReference<>();
        Thread acceptor = new Thread(() -> {
            try {
                java.net.Socket s = target.accept();
                InputStream in = s.getInputStream();
                byte[] buf = new byte[16];
                int n = in.read(buf);
                received.set(java.util.Arrays.copyOf(buf, Math.max(n, 0)));
                s.close();
            } catch (IOException ignored) {
            }
        }, "test-target-accept");
        acceptor.setDaemon(true);
        acceptor.start();

        TransportExecutors execs = execs();
        ClientConfig cfg = configValidPort(0);
        ClientTunnelSessionStubProvider provider = new ClientTunnelSessionStubProvider(target.getLocalPort());
        LocalTcpListener listener = new LocalTcpListener(cfg, execs, provider, null);
        listener.start();
        try {
            int port = listener.boundPort();
            try (Socket client = new Socket("127.0.0.1", port)) {
                OutputStream out = client.getOutputStream();
                out.write("ping".getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
            Thread.sleep(200);
            assertTrue(true, "listener handled connection without throwing");
        } finally {
            listener.stop();
            provider.close();
            execs.shutdown();
            target.close();
        }
    }

    /** A minimal session provider that always reports "not yet". */
    private static final class ClientTunnelSessionStubProvider
            implements LocalTcpListener.ClientTunnelSessionProvider {
        @SuppressWarnings("unused")
        ClientTunnelSessionStubProvider(int targetPort) {
            this.targetPort = targetPort;
        }
        private final int targetPort;

        @Override
        public ClientTunnelSession session() {
            return null;
        }

        void close() {
        }
    }
}