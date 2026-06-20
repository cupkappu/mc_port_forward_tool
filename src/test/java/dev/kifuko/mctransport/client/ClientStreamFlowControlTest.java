package dev.kifuko.mctransport.client;

import dev.kifuko.mctransport.buffer.BufferBudget;
import dev.kifuko.mctransport.buffer.ReservationState;
import dev.kifuko.mctransport.net.FakeTunnelBridge;
import dev.kifuko.mctransport.stream.StreamRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientStreamFlowControlTest {

    @Test
    void reserveOrWaitReturnsFalseWhenBudgetFull() {
        FakeTunnelBridge bridge = new FakeTunnelBridge();
        bridge.setReceiver(frame -> {});
        StreamRegistry registry = new StreamRegistry(16, true);
        ClientTunnelSession session = new ClientTunnelSession(bridge, registry,
                (sess, id) -> null, 0L);

        BufferBudget budget = new BufferBudget(8, 8);
        ReservationState reservations = new ReservationState();
        ClientStream stream = new ClientStream(session, 99, budget, reservations, 8);

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
                (sess, id) -> null, 0L);

        BufferBudget budget = new BufferBudget(1024, 4096);
        ReservationState reservations = new ReservationState();
        ClientStream stream = new ClientStream(session, 99, budget, reservations, 1024);

        assertTrue(stream.reserveOrWait(4));
    }
}
