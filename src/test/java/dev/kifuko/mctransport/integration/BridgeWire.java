package dev.kifuko.mctransport.integration;

import dev.kifuko.mctransport.net.FakeTunnelBridge;
import dev.kifuko.mctransport.protocol.Frame;

/**
 * Helper that wires two {@link FakeTunnelBridge} instances so each bridge's
 * outbound frames are presented to the other bridge as inbound frames,
 * without recursion.
 *
 * <p>The wire is implemented by registering a single outbound listener that
 * dispatches the frame directly to the opposite bridge's
 * {@link FakeTunnelBridge#injectInbound(Frame)} method. Each bridge's own
 * receiver is registered to a no-op so that frames do not loop back into
 * the originator.</p>
 */
public final class BridgeWire {

    private final FakeTunnelBridge a;
    private final FakeTunnelBridge b;

    public BridgeWire(FakeTunnelBridge a, FakeTunnelBridge b) {
        this.a = a;
        this.b = b;
    }

    public FakeTunnelBridge a() { return a; }
    public FakeTunnelBridge b() { return b; }

    /** Connects A → B and B → A through a dispatcher. */
    public void connect() {
        a.setOutboundListener(frame -> b.injectInbound(frame));
        b.setOutboundListener(frame -> a.injectInbound(frame));
    }
}