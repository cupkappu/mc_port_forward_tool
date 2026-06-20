package dev.kifuko.mctransport.client;

import dev.kifuko.mctransport.buffer.BufferBudget;
import dev.kifuko.mctransport.buffer.ReservationState;
import dev.kifuko.mctransport.kcp.KcpConfig;
import dev.kifuko.mctransport.protocol.StreamMode;

/**
 * Default {@link ClientStreamFactory} that creates {@link DirectClientStream}
 * or {@link KcpClientStream} based on the stream mode.
 */
public final class DefaultClientStreamFactory implements ClientStreamFactory {

    private final BufferBudget budget;
    private final ReservationState reservations;
    private final int maxPayloadSize;
    private final KcpConfig kcpConfig;

    public DefaultClientStreamFactory(BufferBudget budget,
                                      ReservationState reservations,
                                      int maxPayloadSize,
                                      KcpConfig kcpConfig) {
        this.budget = budget;
        this.reservations = reservations;
        this.maxPayloadSize = maxPayloadSize;
        this.kcpConfig = kcpConfig != null ? kcpConfig : new KcpConfig();
    }

    /** Backward-compat constructor. KCP mode not available. */
    public DefaultClientStreamFactory(BufferBudget budget,
                                      ReservationState reservations,
                                      int maxPayloadSize) {
        this(budget, reservations, maxPayloadSize, new KcpConfig());
    }

    @Override
    public ClientStream create(ClientTunnelSession session, int streamId, StreamMode mode) {
        return switch (mode) {
            case KCP -> new KcpClientStream(session, streamId, budget, reservations, kcpConfig);
            case DIRECT -> new DirectClientStream(session, streamId, budget, reservations, maxPayloadSize);
        };
    }

    public BufferBudget budget() {
        return budget;
    }

    public ReservationState reservations() {
        return reservations;
    }
}
