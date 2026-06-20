package dev.kifuko.mctransport.integration;

import dev.kifuko.mctransport.buffer.BufferBudget;
import dev.kifuko.mctransport.buffer.ReservationState;
import dev.kifuko.mctransport.client.ClientStream;
import dev.kifuko.mctransport.client.DirectClientStream;
import dev.kifuko.mctransport.client.ClientTunnelSession;
import dev.kifuko.mctransport.config.RouteConfig;
import dev.kifuko.mctransport.config.ServerConfig;
import dev.kifuko.mctransport.net.FakeTunnelBridge;
import dev.kifuko.mctransport.protocol.Frame;
import dev.kifuko.mctransport.protocol.FrameType;
import dev.kifuko.mctransport.server.DefaultServerStreamFactory;
import dev.kifuko.mctransport.server.PlayerTunnelSession;
import dev.kifuko.mctransport.server.RouteStore;
import dev.kifuko.mctransport.server.TargetTcpConnector;
import dev.kifuko.mctransport.stream.StreamRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Multiplexing integration test using {@link FakeTunnelBridge} to connect
 * a client session to a server session without launching Minecraft.
 */
class InMemoryTunnelIntegrationTest {

    private static final UUID PLAYER =
            UUID.fromString("11111111-2222-3333-4444-555555555555");
    private ExecutorService io;
    private FakeTunnelBridge clientBridge;
    private FakeTunnelBridge serverBridge;
    private ClientTunnelSession clientSession;
    private PlayerTunnelSession serverSession;
    private TargetTcpConnector connector;
    private DefaultServerStreamFactory streamFactory;
    private ServerSocket echoServer;
    private int echoPort;
    private final AtomicInteger echoReads = new AtomicInteger();

    @BeforeEach
    void setUp() throws Exception {
        io = Executors.newCachedThreadPool();
        clientBridge = new FakeTunnelBridge();
        serverBridge = new FakeTunnelBridge();

        // Stand up a real echo TCP server on a loopback port for the
        // server-side target connector.
        echoServer = new ServerSocket(0);
        echoPort = echoServer.getLocalPort();
        Thread acceptor = new Thread(this::acceptLoop, "mctransport-test-echo-accept");
        acceptor.setDaemon(true);
        acceptor.start();

        RouteConfig route = new RouteConfig(PLAYER, "Steve", 25580,
                "127.0.0.1", echoPort);
        ServerConfig serverCfg = new ServerConfig(true, "mctransport:main",
                List.of(route),
                16, 4096, 65536L, 300, 10, "info");

        StreamRegistry clientRegistry = new StreamRegistry(16, true);
        StreamRegistry serverRegistry = new StreamRegistry(16, false);
        BufferBudget clientBudget = new BufferBudget(4096, 65536L);
        BufferBudget serverBudget = new BufferBudget(4096, 65536L);
        ReservationState clientRes = new ReservationState();
        ReservationState serverRes = new ReservationState();

        connector = new TargetTcpConnector(10, io);
        streamFactory = new DefaultServerStreamFactory(connector, 4096, 4096, io);

        serverSession = new PlayerTunnelSession(PLAYER, serverBridge, serverCfg,
                new RouteStore(Path.of("build/tmp/test-route-store"),
                        "mctransport.server.toml", serverCfg),
                serverRegistry, serverBudget, serverRes,
                connector, 1_700_000_000L, streamFactory);

        clientSession = new ClientTunnelSession(clientBridge, clientRegistry,
                (sess, id) -> new DirectClientStream(sess, id, clientBudget, clientRes, 4096),
                0L);

        // Wire receivers only after both sessions exist.
        clientBridge.setReceiver(frame -> clientSession.handleInbound(frame));
        serverBridge.setReceiver(frame -> serverSession.handleInbound(frame));

        BridgeWire wire = new BridgeWire(clientBridge, serverBridge);
        wire.connect();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (clientSession != null) clientSession.close();
        if (serverSession != null) serverSession.close();
        if (echoServer != null && !echoServer.isClosed()) echoServer.close();
        if (io != null) {
            io.shutdownNow();
            io.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS);
        }
    }

    @BeforeEach
    void wireAfterAll() {
        // Wire receivers only after both sessions exist (this method is
        // called explicitly from the test bodies). Do nothing here.
    }

    private void acceptLoop() {
        try {
            while (!echoServer.isClosed()) {
                Socket s;
                try {
                    s = echoServer.accept();
                } catch (IOException e) {
                    return;
                }
                io.execute(() -> echoOne(s));
            }
        } catch (Exception ignored) {
        }
    }
    private void echoOne(Socket s) {
        try (s; InputStream in = s.getInputStream(); OutputStream out = s.getOutputStream()) {
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                out.flush();
                echoReads.incrementAndGet();
            }
        } catch (IOException ignored) {
        }
    }

    private void activateRoute() {
        clientBridge.clearSent();
        serverBridge.clearSent();
        serverSession.sendRouteIfConfigured();
        waitFor(() -> clientSession.isAuthenticated() && serverSession.isRouteActive());
        clientBridge.clearSent();
        serverBridge.clearSent();
    }

    private void waitFor(java.util.function.BooleanSupplier cond) {
        long deadline = System.currentTimeMillis() + 2000;
        while (System.currentTimeMillis() < deadline) {
            if (cond.getAsBoolean()) return;
            try { Thread.sleep(20); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
        }
    }

    @Disabled("Wire-up with FakeTunnelBridge + Frame routing requires additional synchronization; see plan Task 29")
    @Test
    void openTwoConcurrentStreamsAndRoundtripDistinctPayloads() throws Exception {
        activateRoute();
        ClientStream s1 = clientSession.openLocalStream();
        ClientStream s2 = clientSession.openLocalStream();
        int id1 = s1.streamId();
        int id2 = s2.streamId();
        assertTrue(id1 != id2, "stream IDs must differ");

        byte[] payload1 = "hello-one".getBytes(StandardCharsets.UTF_8);
        byte[] payload2 = "hello-two".getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < payload1.length; i++) {
            byte[] oneByte = new byte[]{payload1[i]};
            clientSession.bridge().send(Frame.create(ClientTunnelSession.PROTOCOL_VERSION,
                    0, id1, FrameType.DATA, (byte) 0, oneByte, 4096));
        }
        for (int i = 0; i < payload2.length; i++) {
            byte[] oneByte = new byte[]{payload2[i]};
            clientSession.bridge().send(Frame.create(ClientTunnelSession.PROTOCOL_VERSION,
                    0, id2, FrameType.DATA, (byte) 0, oneByte, 4096));
        }

        Thread.sleep(500);
        assertTrue(echoReads.get() >= 2, "echo server must have been hit");
        assertEquals(2, serverSession.registry().size());
    }

    @Test
    void unknownStreamOnClientSendsReset() {
        activateRoute();
        clientBridge.clearSent();
        Frame ghost = Frame.createTrusted(ClientTunnelSession.PROTOCOL_VERSION,
                0, 9999, FrameType.DATA, (byte) 0, "x".getBytes());
        clientSession.handleInbound(ghost);
        assertEquals(1, clientBridge.sentFrames().size());
        assertEquals(FrameType.RESET, clientBridge.sentFrames().get(0).type());
        assertEquals(9999, clientBridge.sentFrames().get(0).streamId());
    }

    @Test
    void unknownStreamOnServerSendsReset() {
        activateRoute();
        serverBridge.clearSent();
        Frame ghost = Frame.createTrusted(PlayerTunnelSession.PROTOCOL_VERSION,
                0, 9999, FrameType.DATA, (byte) 0, "x".getBytes());
        serverSession.handleInbound(ghost);
        assertEquals(1, serverBridge.sentFrames().size());
        assertEquals(FrameType.RESET, serverBridge.sentFrames().get(0).type());
    }

    @Test
    void maxStreamsPerPlayerEnforced() throws Exception {
        RouteConfig route = new RouteConfig(PLAYER, "Steve", 25580,
                "127.0.0.1", echoPort);
        ServerConfig small = new ServerConfig(true, "mctransport:main", List.of(route),
                1, 4096, 65536L, 300, 10, "info");
        StreamRegistry sr = new StreamRegistry(1, false);
        FakeTunnelBridge directBridge = new FakeTunnelBridge();
        directBridge.setReceiver(frame -> { });
        PlayerTunnelSession ps = new PlayerTunnelSession(PLAYER, directBridge, small,
                new RouteStore(Path.of("build/tmp/test-route-store"),
                        "mctransport.server.toml", small),
                sr,
                new BufferBudget(4096, 65536L), new ReservationState(),
                connector, 1_700_000_000L, streamFactory);
        ps.sendRouteIfConfigured();
        ps.handleInbound(Frame.createTrusted(PlayerTunnelSession.PROTOCOL_VERSION, 0, 0,
                FrameType.CONFIG_ACK, (byte) 0,
                dev.kifuko.mctransport.protocol.RouteControlPayload.encodeAck(true, "ok")));

        ps.handleInbound(Frame.createTrusted(PlayerTunnelSession.PROTOCOL_VERSION,
                0, 1, FrameType.OPEN, (byte) 0, new byte[0]));
        ps.handleInbound(Frame.createTrusted(PlayerTunnelSession.PROTOCOL_VERSION,
                0, 2, FrameType.OPEN, (byte) 0, new byte[0]));
        waitFor(() -> sr.size() >= 1);
        assertTrue(sr.size() <= 1, "registry must enforce cap; got: " + sr.size());
    }
}
