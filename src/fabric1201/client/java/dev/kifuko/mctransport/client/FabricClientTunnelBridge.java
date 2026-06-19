package dev.kifuko.mctransport.client;

import dev.kifuko.mctransport.McTransport;
import dev.kifuko.mctransport.crypto.PskCipher;
import dev.kifuko.mctransport.net.TunnelBridge;
import dev.kifuko.mctransport.protocol.Frame;
import dev.kifuko.mctransport.protocol.FrameCodec;
import dev.kifuko.mctransport.protocol.FrameType;
import dev.kifuko.mctransport.protocol.SecureFrameCodec;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.util.Identifier;

/**
 * Client-side Fabric 1.20.1 bridge using raw custom payload channels.
 */
public final class FabricClientTunnelBridge implements TunnelBridge {

    private final Identifier channel;
    private final FrameCodec codec;
    private final SecureFrameCodec secureCodec;
    private final TunnelExecutorsAdapter executors;
    private Receiver receiver;
    private boolean closed;

    public FabricClientTunnelBridge(Identifier channel, FrameCodec codec,
                                    PskCipher cipher, int maxPlainPayloadSize,
                                    TunnelExecutorsAdapter executors) {
        this.channel = channel;
        this.codec = codec;
        this.secureCodec = new SecureFrameCodec(cipher, maxPlainPayloadSize);
        this.executors = executors;
    }

    public synchronized void start() {
        ClientPlayNetworking.registerGlobalReceiver(channel,
                (client, handler, buf, responseSender) -> {
                    if (closed) return;
                    byte[] bytes = readAll(buf);
                    executors.io().execute(() -> {
                        try {
                            Frame wireFrame = codec.decode(bytes);
                            Frame frame = secureCodec.decryptFromWire(wireFrame);
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
        Frame wireFrame = frame.type() == FrameType.AUTH ? frame : secureCodec.encryptForWire(frame);
        byte[] encoded = codec.encode(wireFrame);
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
