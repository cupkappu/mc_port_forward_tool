package dev.kifuko.mctransport.net;

import dev.kifuko.mctransport.protocol.Frame;

/**
 * Abstraction over the Minecraft networking channel used to ferry internal
 * frames. The protocol and stream layers depend on this interface; the
 * Fabric-specific bridge is implemented separately.
 */
public interface TunnelBridge {

    /** Sends a frame over the configured Minecraft channel. */
    void send(Frame frame);

    /**
     * Registers a receiver callback for inbound frames. Implementations must
     * call the receiver on a transport thread, NOT on the Minecraft render,
     * server tick, or Netty event-loop threads.
     */
    void setReceiver(Receiver receiver);

    /** Closes the bridge and stops delivering inbound frames. */
    void close();

    @FunctionalInterface
    interface Receiver {
        void onFrame(Frame frame);
    }
}