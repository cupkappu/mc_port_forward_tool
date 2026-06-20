package dev.kifuko.mctransport.client;

import dev.kifuko.mctransport.buffer.BufferBudget;
import dev.kifuko.mctransport.buffer.ReservationState;
import dev.kifuko.mctransport.net.FakeTunnelBridge;
import dev.kifuko.mctransport.stream.StreamRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientStreamFlowControlTest {

    @AfterEach
    void restoreFlowControlDefaults() {
        ClientStream.DRAIN_INTERVAL_MS = 150L;
    }

    @Test
    void partialDrainPreventsDeadlockOnFullBudget() throws Exception {
        ClientStream.DRAIN_INTERVAL_MS = 50L;

        FakeTunnelBridge bridge = new FakeTunnelBridge();
        bridge.setReceiver(frame -> {});
        StreamRegistry registry = new StreamRegistry(16, true);
        ClientTunnelSession session = new ClientTunnelSession(bridge, registry,
                (sess, id) -> null, 0L);

        // Large per-stream limit, small global limit. Pre-fill same stream.
        BufferBudget budget = new BufferBudget(1024, 16);
        ReservationState reservations = new ReservationState();
        ClientStream stream = new ClientStream(session, 99, budget, reservations, 1024);

        // Pre-fill global budget for the SAME stream
        budget.reserve(99, 16, reservations);

        // reserveOrWait should eventually succeed after partial drains
        long start = System.currentTimeMillis();
        boolean ok = stream.reserveOrWait(4);
        long elapsed = System.currentTimeMillis() - start;

        assertTrue(ok, "reserveOrWait should succeed after partial drain");
        assertTrue(elapsed >= 40, "should have waited at least ~50ms for drain, got " + elapsed + "ms");
    }

    @Test
    void partialDrainWorksRepeatedly() throws Exception {
        ClientStream.DRAIN_INTERVAL_MS = 50L;

        FakeTunnelBridge bridge = new FakeTunnelBridge();
        bridge.setReceiver(frame -> {});
        StreamRegistry registry = new StreamRegistry(16, true);
        ClientTunnelSession session = new ClientTunnelSession(bridge, registry,
                (sess, id) -> null, 0L);

        BufferBudget budget = new BufferBudget(1024, 32);
        ReservationState reservations = new ReservationState();
        ClientStream stream = new ClientStream(session, 99, budget, reservations, 1024);

        // Round 1: fill and drain (same stream)
        budget.reserve(99, 32, reservations);
        assertTrue(stream.reserveOrWait(8), "round 1 should succeed after drain");

        // Round 2: remaining budget might still be reserved; drain should work again
        long start = System.currentTimeMillis();
        assertTrue(stream.reserveOrWait(8), "round 2 should also succeed");
        long elapsed = System.currentTimeMillis() - start;
        // Second call might succeed immediately if enough budget was freed
    }
}
