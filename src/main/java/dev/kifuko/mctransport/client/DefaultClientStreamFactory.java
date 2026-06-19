package dev.kifuko.mctransport.client;

import dev.kifuko.mctransport.buffer.BufferBudget;
import dev.kifuko.mctransport.buffer.ReservationState;
import dev.kifuko.mctransport.protocol.Frame;

/**
 * Default {@link ClientStreamFactory} that produces standard
 * {@link ClientStream} instances backed by a shared buffer budget.
 */
public final class DefaultClientStreamFactory implements ClientStreamFactory {

    private final BufferBudget budget;
    private final ReservationState reservations;
    private final int maxPayloadSize;

    public DefaultClientStreamFactory(BufferBudget budget,
                                      ReservationState reservations,
                                      int maxPayloadSize) {
        this.budget = budget;
        this.reservations = reservations;
        this.maxPayloadSize = maxPayloadSize;
    }

    @Override
    public ClientStream create(ClientTunnelSession session, int streamId) {
        return new ClientStream(session, streamId, budget, reservations, maxPayloadSize);
    }

    public BufferBudget budget() {
        return budget;
    }

    public ReservationState reservations() {
        return reservations;
    }
}