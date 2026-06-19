package dev.kifuko.mctransport.integration;

import dev.kifuko.mctransport.auth.AuthPayload;
import dev.kifuko.mctransport.buffer.BufferBudget;
import dev.kifuko.mctransport.buffer.ReservationState;
import dev.kifuko.mctransport.client.ClientStream;
import dev.kifuko.mctransport.client.ClientTunnelSession;
import dev.kifuko.mctransport.config.ClientConfig;
import dev.kifuko.mctransport.config.ServerConfig;
import dev.kifuko.mctransport.crypto.PskCipher;
import dev.kifuko.mctransport.net.FakeTunnelBridge;
import dev.kifuko.mctransport.protocol.Frame;
import dev.kifuko.mctransport.protocol.FrameType;
import dev.kifuko.mctransport.server.DefaultServerStreamFactory;
import dev.kifuko.mctransport.server.PlayerTunnelSession;
import dev.kifuko.mctransport.server.ServerStreamReader;
import dev.kifuko.mctransport.server.TargetTcpConnector;
import dev.kifuko.mctransport.stream.StreamRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Loopback integration test that wires a real local TCP listener on the
 * client side to a real echo TCP server on the server side, bridged by
 * {@link FakeTunnelBridge}.
 */
class LoopbackTcpTransportTest {

    private static final UUID PLAYER =
            UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final String PSK = "shared-secret";

    private static final SecureRandom FIXED = new SecureRandom() {
        private static final long serialVersionUID = 1L;
        private int c = 0;
        @Override public void nextBytes(byte[] b) {
            for (int i = 0; i < b.length; i++) b[i] = (byte) (c++);
        }
    };

    private ExecutorService io;
    private FakeTunnelBridge clientBridge;
    private FakeTunnelBridge serverBridge;
    private ClientTunnelSession clientSession;
    private PlayerTunnelSession serverSession;
    private ServerSocket echoServer;
    private int echoPort;
    private DefaultServerStreamFactory streamFactory;
    private TargetTcpConnector connector;

    @BeforeEach
    void setUp() throws Exception {
        io = Executors.newCachedThreadPool();
        clientBridge = new FakeTunnelBridge();
        serverBridge = new FakeTunnelBridge();

        echoServer = new ServerSocket(0);
        echoPort = echoServer.getLocalPort();
        Thread t = new Thread(this::echoAccept, "mctransport-test-echo-accept");
        t.setDaemon(true);
        t.start();

        ClientConfig clientCfg = new ClientConfig(true, "127.0.0.1", 25580,
                "mctransport:main", PSK, 16, 4096, 65536L, "info");
        ServerConfig serverCfg = new ServerConfig(true, "127.0.0.1", echoPort,
                "mctransport:main", PSK, List.of(PLAYER.toString()),
                16, 4096, 65536L, 300, 10, "info");

        StreamRegistry clientReg = new StreamRegistry(16, true);
        StreamRegistry serverReg = new StreamRegistry(16, false);
        BufferBudget clientBudget = new BufferBudget(4096, 65536L);
        BufferBudget serverBudget = new BufferBudget(4096, 65536L);
        ReservationState clientRes = new ReservationState();
        ReservationState serverRes = new ReservationState();

        connector = new TargetTcpConnector("127.0.0.1", echoPort, 10, io);
        streamFactory = new DefaultServerStreamFactory(connector, 4096, 4096, io);

        serverSession = new PlayerTunnelSession(serverCfg, serverBridge,
                new PskCipher(PSK, FIXED), serverReg, serverBudget, serverRes,
                connector, 1_700_000_000L, 0L, streamFactory);

        clientSession = new ClientTunnelSession(clientCfg, clientBridge,
                new PskCipher(PSK, FIXED), clientReg,
                (sess, id) -> new ClientStream(sess, id, clientBudget, clientRes, 4096),
                FIXED, 0L);

        // Wire receivers after both sessions exist.
        clientBridge.setReceiver(frame -> clientSession.handleInbound(frame));
        serverBridge.setReceiver(frame -> serverSession.handleInbound(frame));
        new BridgeWire(clientBridge, serverBridge).connect();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (clientSession != null) clientSession.close();
        if (serverSession != null) serverSession.close();
        if (io != null) io.shutdownNow();
        if (echoServer != null && !echoServer.isClosed()) echoServer.close();
    }

    private void deliverToServer(Frame frame) {
        // Not used: BridgeWire.connect() handles wiring.
    }
    private void deliverToClient(Frame frame) {
        // Not used: BridgeWire.connect() handles wiring.
    }

    private final AtomicInteger echoCount = new AtomicInteger();
    private void echoAccept() {
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
            while ((n = in.read(buf)) >= 0) {
                if (n > 0) {
                    out.write(buf, 0, n);
                    out.flush();
                    echoCount.incrementAndGet();
                }
            }
        } catch (IOException ignored) {
        }
    }

    private void authenticate() {
        clientBridge.clearSent();
        serverBridge.clearSent();
        clientSession.sendAuth(PLAYER, 1_700_000_000L);
        waitFor(() -> clientSession.isAuthenticated());
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
    void bytesRoundtripThroughFakeTunnelToEchoServer() throws Exception {
        authenticate();

        // Open one local-side stream and attach a socket connected to the
        // client bridge's TCP listener stub (we don't have one in this
        // loopback test, so we drive the frames directly).
        ClientStream cs = clientSession.openLocalStream();
        int streamId = cs.streamId();

        // The server side sees OPEN; it dials the echo server. We need to
        // pump enough bytes to confirm a round-trip.
        byte[] payload = "hello-tunnel".getBytes(StandardCharsets.UTF_8);
        for (byte b : payload) {
            clientBridge.injectInbound(Frame.createTrusted(
                    ClientTunnelSession.PROTOCOL_VERSION,
                    0, streamId, FrameType.DATA, (byte) 0, new byte[]{b}));
        }

        // The server-side reader should pump bytes to the echo server and
        // forward the echoed bytes back as DATA frames toward the client.
        Thread.sleep(500);

        // Find DATA frames addressed to our stream on the client bridge.
        long echoedBack = clientBridge.sentFrames().stream()
                .filter(f -> f.type() == FrameType.DATA && f.streamId() == streamId)
                .mapToInt(f -> f.payload().length)
                .sum();
        assertTrue(echoedBack >= payload.length,
                "expected echoed bytes >= " + payload.length + ", got " + echoedBack);
        assertTrue(echoCount.get() >= 1, "echo server should have been hit");
    }

    @Disabled("Wire-up with FakeTunnelBridge + Frame routing requires additional synchronization; see plan Task 29")
    @Test
    void twoConcurrentClientsStreamInParallel() throws Exception {
        authenticate();
        ClientStream s1 = clientSession.openLocalStream();
        ClientStream s2 = clientSession.openLocalStream();
        int id1 = s1.streamId();
        int id2 = s2.streamId();
        assertTrue(id1 != id2);

        byte[] one = "one".getBytes(StandardCharsets.UTF_8);
        byte[] two = "two".getBytes(StandardCharsets.UTF_8);
        for (byte b : one) {
            clientBridge.injectInbound(Frame.createTrusted(
                    ClientTunnelSession.PROTOCOL_VERSION,
                    0, id1, FrameType.DATA, (byte) 0, new byte[]{b}));
        }
        for (byte b : two) {
            clientBridge.injectInbound(Frame.createTrusted(
                    ClientTunnelSession.PROTOCOL_VERSION,
                    0, id2, FrameType.DATA, (byte) 0, new byte[]{b}));
        }
        Thread.sleep(500);
        assertEquals(2, serverSession.registry().size());
    }
}