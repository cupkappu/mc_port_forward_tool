package dev.kifuko.mctransport.kcp;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class KcpCoreTest {

    private KcpCore clientKcp;
    private KcpCore serverKcp;
    private final List<ByteBuffer> clientToServer = new ArrayList<>();
    private final List<ByteBuffer> serverToClient = new ArrayList<>();
    private long now;

    @BeforeEach
    void setUp() {
        now = System.currentTimeMillis();
        clientToServer.clear();
        serverToClient.clear();

        clientKcp = new KcpCore(1, (data, kcp) -> {
            byte[] copy = new byte[data.remaining()];
            data.get(copy);
            clientToServer.add(ByteBuffer.wrap(copy));
        });

        serverKcp = new KcpCore(1, (data, kcp) -> {
            byte[] copy = new byte[data.remaining()];
            data.get(copy);
            serverToClient.add(ByteBuffer.wrap(copy));
        });

        KcpConfig cfg = new KcpConfig(8192, 128, 128);
        clientKcp.applyConfig(cfg);
        serverKcp.applyConfig(cfg);
    }

    /** Pumps frames through one full round-trip: update→drain→input→update→drain. */
    private void pump() {
        // Round 1: flush pending sends, deliver to peer, peer processes
        clientKcp.update(now);
        serverKcp.update(now);
        drain();
        now += 20;
        // Round 2: flush acks triggered by received data
        clientKcp.update(now);
        serverKcp.update(now);
        drain();
        now += 20;
    }

    private void drain() {
        for (ByteBuffer buf : clientToServer) {
            serverKcp.input(buf, true, now);
        }
        clientToServer.clear();
        for (ByteBuffer buf : serverToClient) {
            clientKcp.input(buf, true, now);
        }
        serverToClient.clear();
    }

    @Test
    void sendRecvSingleSmallMessage() {
        byte[] payload = "hello kcp".getBytes();
        assertEquals(0, clientKcp.send(ByteBuffer.wrap(payload)));
        pump();

        ByteBuffer received = serverKcp.recv();
        assertNotNull(received);
        byte[] result = new byte[received.remaining()];
        received.get(result);
        assertArrayEquals(payload, result);
    }

    @Test
    void sendRecvMultipleMessages() {
        // In stream mode, KCP coalesces sequential sends.
        // Send each message and pump to preserve message boundaries.
        for (int i = 0; i < 10; i++) {
            byte[] payload = ("msg-" + i).getBytes();
            assertEquals(0, clientKcp.send(ByteBuffer.wrap(payload)));
            pump();
        }

        for (int i = 0; i < 10; i++) {
            ByteBuffer buf = serverKcp.recv();
            assertNotNull(buf, "message " + i);
            byte[] result = new byte[buf.remaining()];
            buf.get(result);
            assertArrayEquals(("msg-" + i).getBytes(), result);
        }
        assertNull(serverKcp.recv());
    }

    @Test
    void sendLargePayloadSplitsIntoSegments() {
        byte[] large = new byte[20000];
        for (int i = 0; i < large.length; i++) large[i] = (byte) (i % 256);

        assertEquals(0, clientKcp.send(ByteBuffer.wrap(large)));
        for (int p = 0; p < 10; p++) {
            pump();
        }

        // Stream mode: each MTU-sized segment is a separate recv() result.
        // 20000 bytes with MSS=8192 → 3 segments of 8192, 8192, 3616 bytes
        java.io.ByteArrayOutputStream reassembled = new java.io.ByteArrayOutputStream();
        for (int i = 0; i < 10; i++) {
            ByteBuffer received = serverKcp.recv();
            if (received == null) break;
            byte[] chunk = new byte[received.remaining()];
            received.get(chunk);
            reassembled.writeBytes(chunk);
        }
        byte[] result = reassembled.toByteArray();
        assertArrayEquals(large, result);
    }

    @Test
    void waitSndReflectsPendingData() {
        assertEquals(0, clientKcp.waitSnd());
        clientKcp.send(ByteBuffer.wrap(new byte[100]));
        assertTrue(clientKcp.waitSnd() > 0);
    }

    @Test
    void streamModeCoalescesSmallWrites() {
        // Send 100 x 1-byte writes — stream mode should coalesce them
        for (int i = 0; i < 100; i++) {
            clientKcp.send(ByteBuffer.wrap(new byte[]{1}));
        }
        clientKcp.update(now);
        // Output should be far fewer than 100 segments
        assertTrue(clientToServer.size() < 50,
                "Expected coalescing, got " + clientToServer.size() + " output chunks for 100 writes");
    }

    @Test
    void sendTooManyFragmentsReturnsMinusTwo() {
        // Each fragment is at most mss bytes, so 256 * (mss+1) bytes → 257 fragments
        int tooMany = 256 * (8193);
        byte[] huge = new byte[tooMany];
        int result = clientKcp.send(ByteBuffer.wrap(huge));
        assertEquals(-2, result);
    }

    @Test
    void emptySendReturnsMinusOne() {
        assertEquals(-1, clientKcp.send(ByteBuffer.allocate(0)));
    }

    @Test
    void recvReturnsNullWhenNothingAvailable() {
        assertNull(serverKcp.recv());
    }

    @Test
    void releaseClearsState() {
        clientKcp.release();
        assertEquals(-1, clientKcp.getState());
        assertEquals(0, clientKcp.waitSnd());
    }
}
