package dev.kifuko.mctransport.client;

import dev.kifuko.mctransport.buffer.BufferBudget;
import dev.kifuko.mctransport.buffer.ReservationState;
import dev.kifuko.mctransport.kcp.KcpConfig;
import dev.kifuko.mctransport.net.FakeTunnelBridge;
import dev.kifuko.mctransport.protocol.FrameType;
import dev.kifuko.mctransport.stream.StreamRegistry;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientStreamFlowControlTest {

    @Test
    void reserveOrWaitReturnsFalseWhenBudgetFull() {
        FakeTunnelBridge bridge = new FakeTunnelBridge();
        bridge.setReceiver(frame -> {});
        StreamRegistry registry = new StreamRegistry(16, true);
        ClientTunnelSession session = new ClientTunnelSession(bridge, registry,
                (sess, id, mode) -> null, 0L);

        BufferBudget budget = new BufferBudget(8, 8);
        ReservationState reservations = new ReservationState();
        ClientStream stream = new DirectClientStream(session, 99, budget, reservations, 8);

        // Pre-fill budget
        budget.reserve(99, 8, reservations);

        // With semaphore-based flow control, reserveOrWait returns false
        // immediately when budget is full (doesn't block).
        assertFalse(stream.reserveOrWait(4));
    }

    @Test
    void reserveOrWaitSucceedsWhenBudgetAvailable() {
        FakeTunnelBridge bridge = new FakeTunnelBridge();
        bridge.setReceiver(frame -> {});
        StreamRegistry registry = new StreamRegistry(16, true);
        ClientTunnelSession session = new ClientTunnelSession(bridge, registry,
                (sess, id, mode) -> null, 0L);

        BufferBudget budget = new BufferBudget(1024, 4096);
        ReservationState reservations = new ReservationState();
        ClientStream stream = new DirectClientStream(session, 99, budget, reservations, 1024);

        assertTrue(stream.reserveOrWait(4));
    }

    @Test
    void kcpReadLoopResetsInsteadOfThrowingWhenBudgetFull() throws Exception {
        FakeTunnelBridge bridge = new FakeTunnelBridge();
        bridge.setReceiver(frame -> {});
        StreamRegistry registry = new StreamRegistry(16, true);
        ClientTunnelSession session = new ClientTunnelSession(25580, bridge, registry,
                (sess, id, mode) -> null, 0L);
        BufferBudget budget = new BufferBudget(8, 8);
        ReservationState reservations = new ReservationState();
        budget.reserve(118, 8, reservations);
        KcpClientStream stream = new KcpClientStream(session, 118,
                budget, reservations, new KcpConfig(1024, 32, 32));
        AtomicReference<Throwable> uncaught = new AtomicReference<>();
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            if (thread.getName().equals("mctransport-kcp-read-118")) {
                uncaught.set(error);
            } else if (previous != null) {
                previous.uncaughtException(thread, error);
            }
        });

        try (SocketPair sockets = socketPair()) {
            stream.attach(sockets.streamEnd(), new byte[16]);
            sockets.peer().getOutputStream().write(new byte[1]);
            sockets.peer().getOutputStream().flush();

            waitFor(() -> hasResetFrame(bridge, 118) || uncaught.get() != null);

            assertNull(uncaught.get());
            assertTrue(hasResetFrame(bridge, 118));
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous);
            stream.closeReset();
        }
    }

    private SocketPair socketPair() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            FutureTask<Socket> accepted = new FutureTask<>(server::accept);
            Thread acceptThread = new Thread(accepted, "mctransport-test-accept");
            acceptThread.setDaemon(true);
            acceptThread.start();
            Socket peer = new Socket("127.0.0.1", server.getLocalPort());
            Socket streamEnd = accepted.get();
            return new SocketPair(peer, streamEnd);
        }
    }

    private void waitFor(java.util.function.BooleanSupplier condition) throws Exception {
        long deadline = System.currentTimeMillis() + 2_000L;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(10L);
        }
        throw new AssertionError("condition was not met before timeout");
    }

    private boolean hasResetFrame(FakeTunnelBridge bridge, int streamId) {
        return bridge.sentFrames().stream()
                .anyMatch(frame -> frame.type() == FrameType.RESET
                        && frame.streamId() == streamId);
    }

    private record SocketPair(Socket peer, Socket streamEnd) implements AutoCloseable {
        @Override
        public void close() throws Exception {
            peer.close();
            streamEnd.close();
        }
    }
}
