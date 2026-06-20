package dev.kifuko.mctransport.kcp;

import dev.kifuko.mctransport.buffer.BufferBudget;
import dev.kifuko.mctransport.buffer.ReservationState;
import dev.kifuko.mctransport.client.ClientStream;
import dev.kifuko.mctransport.client.ClientTunnelSession;
import dev.kifuko.mctransport.client.DefaultClientStreamFactory;
import dev.kifuko.mctransport.config.RouteConfig;
import dev.kifuko.mctransport.config.ServerConfig;
import dev.kifuko.mctransport.integration.BridgeWire;
import dev.kifuko.mctransport.net.FakeTunnelBridge;
import dev.kifuko.mctransport.protocol.StreamMode;
import dev.kifuko.mctransport.server.DefaultServerStreamFactory;
import dev.kifuko.mctransport.server.PlayerTunnelSession;
import dev.kifuko.mctransport.server.RouteStore;
import dev.kifuko.mctransport.server.TargetTcpConnector;
import dev.kifuko.mctransport.stream.StreamRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KcpStreamIntegrationTest {

    private static final UUID PLAYER =
            UUID.fromString("11111111-2222-3333-4444-555555555555");

    private ExecutorService io;
    private ServerSocket echoServer;
    private ClientTunnelSession clientSession;
    private PlayerTunnelSession serverSession;

    @TempDir
    Path tempDir;

    @AfterEach
    void tearDown() throws Exception {
        if (serverSession != null) serverSession.close();
        if (clientSession != null) clientSession.close();
        if (echoServer != null) echoServer.close();
        if (io != null) {
            io.shutdownNow();
            io.awaitTermination(2, TimeUnit.SECONDS);
        }
    }

    @Test
    void kcpStreamEchoesTcpBytesEndToEnd() throws Exception {
        io = Executors.newCachedThreadPool();
        echoServer = new ServerSocket(0);
        io.execute(this::acceptEchoConnections);

        KcpConfig kcpConfig = new KcpConfig(1024, 32, 32);
        FakeTunnelBridge clientBridge = new FakeTunnelBridge();
        FakeTunnelBridge serverBridge = new FakeTunnelBridge();

        RouteConfig route = new RouteConfig(PLAYER, "Steve", 25580,
                "127.0.0.1", echoServer.getLocalPort(), StreamMode.KCP);
        ServerConfig serverConfig = new ServerConfig(true, "mctransport:main",
                List.of(route), 16, 4096, 65536L, 300, 10, "info");

        serverSession = new PlayerTunnelSession(PLAYER, serverBridge, serverConfig,
                new RouteStore(tempDir, "mctransport.server.toml", serverConfig),
                new StreamRegistry(16, false),
                new BufferBudget(16384, 65536L), new ReservationState(),
                new TargetTcpConnector(10, io), 1_700_000_000L,
                new DefaultServerStreamFactory(new TargetTcpConnector(10, io),
                        4096, 4096, io, kcpConfig));

        clientSession = new ClientTunnelSession(clientBridge,
                new StreamRegistry(16, true),
                new DefaultClientStreamFactory(new BufferBudget(16384, 65536L),
                        new ReservationState(), 4096, kcpConfig),
                0L);

        clientBridge.setReceiver(frame -> clientSession.handleInbound(frame));
        serverBridge.setReceiver(frame -> serverSession.handleInbound(frame));
        new BridgeWire(clientBridge, serverBridge).connect();

        serverSession.sendRouteIfConfigured();
        waitFor(() -> clientSession.isAuthenticated() && serverSession.isRouteActive());
        assertTrue(clientSession.isAuthenticated());
        assertTrue(serverSession.isRouteActive());

        ClientStream stream = clientSession.openLocalStream();
        SocketPair local = socketPair();
        local.peer().setSoTimeout(2000);
        stream.attach(local.streamEnd(), new byte[1024]);

        byte[] payload = new byte[KcpConfig.DEFAULT_MSS];
        byte[] seed = "hello through kcp".getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < payload.length; i++) {
            payload[i] = seed[i % seed.length];
        }
        local.peer().getOutputStream().write(payload);
        local.peer().getOutputStream().flush();

        byte[] echoed = readExactly(local.peer().getInputStream(), payload.length);
        assertArrayEquals(payload, echoed);

        local.close();
    }

    private void acceptEchoConnections() {
        try {
            while (!echoServer.isClosed()) {
                Socket socket = echoServer.accept();
                io.execute(() -> echo(socket));
            }
        } catch (Exception ignored) {
        }
    }

    private void echo(Socket socket) {
        try (socket;
             InputStream in = socket.getInputStream();
             OutputStream out = socket.getOutputStream()) {
            byte[] buffer = new byte[4096];
            int n;
            while ((n = in.read(buffer)) >= 0) {
                if (n == 0) continue;
                out.write(buffer, 0, n);
                out.flush();
            }
        } catch (Exception ignored) {
        }
    }

    private SocketPair socketPair() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            Future<Socket> accepted = io.submit(server::accept);
            Socket peer = new Socket("127.0.0.1", server.getLocalPort());
            Socket streamEnd = accepted.get(2, TimeUnit.SECONDS);
            return new SocketPair(peer, streamEnd);
        }
    }

    private byte[] readExactly(InputStream in, int length) throws Exception {
        byte[] bytes = new byte[length];
        int offset = 0;
        while (offset < length) {
            int n = in.read(bytes, offset, length - offset);
            if (n < 0) {
                throw new AssertionError("socket closed after " + offset + " bytes");
            }
            offset += n;
        }
        return bytes;
    }

    private void waitFor(java.util.function.BooleanSupplier condition) throws Exception {
        long deadline = System.currentTimeMillis() + 2000;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) return;
            Thread.sleep(20);
        }
    }

    private record SocketPair(Socket peer, Socket streamEnd) {
        void close() throws Exception {
            peer.close();
            streamEnd.close();
        }
    }
}
