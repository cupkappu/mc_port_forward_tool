package dev.kifuko.mctransport.client;

import dev.kifuko.mctransport.McTransport;
import dev.kifuko.mctransport.net.SerialExecutor;
import dev.kifuko.mctransport.net.TunnelBridge;
import dev.kifuko.mctransport.protocol.Frame;
import dev.kifuko.mctransport.protocol.FrameCodec;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.util.Identifier;

import java.util.ArrayDeque;
import java.util.Queue;

/**
 * Client-side Fabric 1.20.1 bridge using raw custom payload channels.
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
        ClientPlayNetworking.registerGlobalReceiver(channel,
                (client, handler, buf, responseSender) -> {
                    if (closed) return;
                    byte[] bytes = readAll(buf);
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
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeBytes(encoded);
        ClientPlayNetworking.send(channel, buf);
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

    private static byte[] readAll(PacketByteBuf buf) {
        byte[] bytes = new byte[buf.readableBytes()];
        buf.readBytes(bytes);
        return bytes;
    }

    /** Adapter so the Fabric bridge can submit work to the project's IO pool. */
    public interface TunnelExecutorsAdapter {
        java.util.concurrent.ExecutorService io();
    }
}
