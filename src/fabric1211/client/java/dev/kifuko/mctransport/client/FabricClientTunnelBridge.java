package dev.kifuko.mctransport.client;

import dev.kifuko.mctransport.McTransport;
import dev.kifuko.mctransport.client.TransportPayload;
import dev.kifuko.mctransport.net.SerialExecutor;
import dev.kifuko.mctransport.net.TunnelBridge;
import dev.kifuko.mctransport.protocol.Frame;
import dev.kifuko.mctransport.protocol.FrameCodec;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.util.Identifier;

import java.util.ArrayDeque;
import java.util.Queue;

/**
 * Thin Fabric-side bridge that turns the configured Minecraft networking
 * channel into the {@link TunnelBridge} abstraction.
 */
public final class FabricClientTunnelBridge implements TunnelBridge {

    private final Identifier channel;
    private final FrameCodec codec;
    private final TunnelExecutorsAdapter executors;
    private final SerialExecutor inboundDispatcher;
    private final Queue<Frame> pendingOutbound = new ArrayDeque<>();
    private Receiver receiver;
    private boolean closed;

    public FabricClientTunnelBridge(Identifier channel, FrameCodec codec,
                                    TunnelExecutorsAdapter executors) {
        this.channel = channel;
        this.codec = codec;
        this.executors = executors;
        this.inboundDispatcher = new SerialExecutor(executors.io());
    }

    public synchronized void start() {
        ClientPlayNetworking.registerGlobalReceiver(TransportPayload.ID,
                (payload, context) -> {
                    if (closed) return;
                    byte[] bytes = payload.bytes();
                    inboundDispatcher.execute(() -> {
                        try {
                            Frame frame = codec.decode(bytes);
                            Receiver r;
                            synchronized (FabricClientTunnelBridge.this) {
                                r = receiver;
                            }
                            if (r != null) {
                                r.onFrame(frame);
                            }
                        } catch (RuntimeException e) {
                            McTransport.LOGGER.warn("failed to decode inbound frame: {}",
                                    e.getMessage());
                        }
                    });
                });
    }

    @Override
    public synchronized void send(Frame frame) {
        if (closed) {
            throw new IllegalStateException("bridge is closed");
        }
        if (!ClientPlayNetworking.canSend(channel)) {
            pendingOutbound.add(frame);
            McTransport.LOGGER.info("queued outbound tunnel frame {} until client play channel is ready",
                    frame.type());
            return;
        }
        flushPending();
        sendNow(frame);
    }

    public synchronized void flushPending() {
        if (closed || !ClientPlayNetworking.canSend(channel)) {
            return;
        }
        while (!pendingOutbound.isEmpty()) {
            sendNow(pendingOutbound.remove());
        }
    }

    private void sendNow(Frame frame) {
        byte[] encoded = codec.encode(frame);
        ClientPlayNetworking.send(new TransportPayload(encoded));
    }

    @Override
    public synchronized void setReceiver(Receiver receiver) {
        this.receiver = receiver;
    }

    @Override
    public synchronized void close() {
        closed = true;
        receiver = null;
    }

    /** Adapter so the Fabric bridge can submit work to the project's IO pool. */
    public interface TunnelExecutorsAdapter {
        java.util.concurrent.ExecutorService io();
    }
}
