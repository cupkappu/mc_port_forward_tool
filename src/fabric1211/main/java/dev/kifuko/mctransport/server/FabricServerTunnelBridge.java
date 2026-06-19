package dev.kifuko.mctransport.server;

import dev.kifuko.mctransport.McTransport;
import dev.kifuko.mctransport.client.TransportPayload;
import dev.kifuko.mctransport.config.ServerConfig;
import dev.kifuko.mctransport.crypto.PskCipher;
import dev.kifuko.mctransport.net.SerialExecutor;
import dev.kifuko.mctransport.net.TunnelBridge;
import dev.kifuko.mctransport.protocol.Frame;
import dev.kifuko.mctransport.protocol.FrameCodec;
import dev.kifuko.mctransport.protocol.FrameType;
import dev.kifuko.mctransport.protocol.SecureFrameCodec;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-side Fabric bridge. Each connected player owns one
 * {@link PlayerTunnelSession}, dispatched by player UUID.
 *
 * <p>To avoid a hard compile-time dependency on
 * {@code net.minecraft.server.network.ServerPlayerEntity} (which is not
 * available on the main sourceset under Loom 1.15.5), the bridge keys on
 * {@code net.minecraft.server.level.ServerPlayer} via the connection-events
 * API. That class is in the common Minecraft jar and so is always available.</p>
 */
public class FabricServerTunnelBridge implements TunnelBridge {

    private final Identifier channel;
    private final FrameCodec codec;
    private final ServerConfig config;
    private final SecureFrameCodec secureCodec;
    private final TunnelExecutorsAdapter executors;
    private final Map<java.util.UUID, PlayerTunnelSession> sessionsByPlayer = new ConcurrentHashMap<>();
    private final Map<java.util.UUID, SerialExecutor> dispatchersByPlayer = new ConcurrentHashMap<>();
    private final Map<PlayerTunnelSession, Object> sessionToPlayer = new ConcurrentHashMap<>();
    private boolean started;
    private boolean closed;

    public FabricServerTunnelBridge(Identifier channel, FrameCodec codec,
                                    ServerConfig config,
                                    TunnelExecutorsAdapter executors) {
        this.channel = channel;
        this.codec = codec;
        this.config = config;
        this.secureCodec = new SecureFrameCodec(new PskCipher(config.getPsk()),
                config.getStreamBufferSize());
        this.executors = executors;
    }

    public synchronized void start() {
        if (started) {
            return;
        }
        ServerPlayNetworking.registerGlobalReceiver(TransportPayload.ID,
                (payload, context) -> {
                    if (closed) return;
                    byte[] bytes = payload.bytes();
                    dispatchToPlayerUuid(context.player().getUuid(), bytes);
        });

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayerEntity player = handler.getPlayer();
            java.util.UUID playerUuid = player.getUuid();
            PlayerTunnelSession session = createSession(player);
            dispatchersByPlayer.put(playerUuid, new SerialExecutor(executors.io()));
            sessionsByPlayer.put(playerUuid, session);
            sessionToPlayer.put(session, player);
            McTransport.LOGGER.info("player {} joined; tunnel session ready", playerUuid);
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            ServerPlayerEntity player = handler.getPlayer();
            java.util.UUID playerUuid = player.getUuid();
            PlayerTunnelSession session = sessionsByPlayer.remove(playerUuid);
            dispatchersByPlayer.remove(playerUuid);
            if (session != null) {
                session.close();
                sessionToPlayer.remove(session);
                McTransport.LOGGER.info("player disconnected; tunnel session torn down");
            }
        });

        started = true;
    }

    private void dispatchToPlayerUuid(java.util.UUID playerUuid, byte[] bytes) {
        SerialExecutor dispatcher = dispatchersByPlayer.get(playerUuid);
        if (dispatcher == null) {
            McTransport.LOGGER.warn("inbound frame for unknown player {}", playerUuid);
            return;
        }
        dispatcher.execute(() -> {
            PlayerTunnelSession session = sessionsByPlayer.get(playerUuid);
            if (session == null) {
                McTransport.LOGGER.warn("inbound frame for unknown player {}", playerUuid);
                return;
            }
            try {
                Frame wireFrame = codec.decode(bytes);
                Frame frame = wireFrame.type() == FrameType.AUTH
                        ? wireFrame
                        : secureCodec.decryptFromWire(wireFrame);
                session.handleInbound(frame);
            } catch (RuntimeException e) {
                McTransport.LOGGER.warn("failed to decode inbound frame: {}", e.getMessage());
            }
        });
    }

    protected PlayerTunnelSession createSession(Object player) {
        ServerConfig cfg = config;
        TargetTcpConnector connector = new TargetTcpConnector(cfg.getTargetHost(),
                cfg.getTargetPort(), cfg.getConnectTimeoutSeconds(), executors.io());
        DefaultServerStreamFactory factory = new DefaultServerStreamFactory(connector,
                cfg.getStreamBufferSize(), 4096, executors.io());
        return new PlayerTunnelSession(cfg, new PlayerBridge(player),
                new PskCipher(cfg.getPsk()),
                new dev.kifuko.mctransport.stream.StreamRegistry(cfg.getMaxStreamsPerPlayer(), false),
                new dev.kifuko.mctransport.buffer.BufferBudget(cfg.getStreamBufferSize(),
                        cfg.getGlobalBufferSizePerPlayer()),
                new dev.kifuko.mctransport.buffer.ReservationState(),
                connector,
                System.currentTimeMillis() / 1000L, System.currentTimeMillis(),
                factory);
    }

    @Override
    public synchronized void send(Frame frame) {
        throw new UnsupportedOperationException(
                "server bridge send requires a player-scoped PlayerBridge");
    }

    @Override
    public void setReceiver(Receiver receiver) {
        // Server side has no global receiver; sessions are dispatched by player UUID.
    }

    @Override
    public synchronized void close() {
        closed = true;
        for (PlayerTunnelSession s : sessionToPlayer.keySet()) {
            s.close();
        }
        sessionToPlayer.clear();
        sessionsByPlayer.clear();
        dispatchersByPlayer.clear();
    }

    public interface TunnelExecutorsAdapter {
        java.util.concurrent.ExecutorService io();
    }

    private final class PlayerBridge implements TunnelBridge {
        private final Object player;

        private PlayerBridge(Object player) {
            this.player = player;
        }

        @Override
        public synchronized void send(Frame frame) {
            if (closed) {
                throw new IllegalStateException("bridge is closed");
            }
            Frame wireFrame = secureCodec.encryptForWire(frame);
            byte[] encoded = codec.encode(wireFrame);
            ServerPlayNetworking.send((ServerPlayerEntity) player, new TransportPayload(encoded));
        }

        @Override
        public void setReceiver(Receiver receiver) {
            // Inbound dispatch is owned by the outer FabricServerTunnelBridge.
        }

        @Override
        public void close() {
            // The outer bridge owns lifecycle.
        }
    }
}
