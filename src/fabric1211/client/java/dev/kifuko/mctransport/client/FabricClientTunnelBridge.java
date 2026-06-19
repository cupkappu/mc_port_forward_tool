package dev.kifuko.mctransport.client;

import dev.kifuko.mctransport.McTransport;
import dev.kifuko.mctransport.client.TransportPayload;
import dev.kifuko.mctransport.crypto.PskCipher;
import dev.kifuko.mctransport.net.TunnelBridge;
import dev.kifuko.mctransport.protocol.Frame;
import dev.kifuko.mctransport.protocol.FrameCodec;
import dev.kifuko.mctransport.protocol.FrameType;
import dev.kifuko.mctransport.protocol.SecureFrameCodec;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.util.Identifier;

/**
 * Thin Fabric-side bridge that turns the configured Minecraft networking
 * channel into the {@link TunnelBridge} abstraction.
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
        ClientPlayNetworking.registerGlobalReceiver(TransportPayload.ID,
                (payload, context) -> {
                    if (closed) return;
                    byte[] bytes = payload.bytes();
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
        // AUTH is already encrypted by ClientTunnelSession because the server
        // must validate PSK before it can accept other frame types.
        Frame wireFrame = frame.type() == FrameType.AUTH ? frame : secureCodec.encryptForWire(frame);
        byte[] encoded = codec.encode(wireFrame);
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
