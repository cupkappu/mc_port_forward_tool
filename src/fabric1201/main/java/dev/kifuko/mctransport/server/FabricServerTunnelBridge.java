package dev.kifuko.mctransport.server;

import dev.kifuko.mctransport.McTransport;
import dev.kifuko.mctransport.config.ServerConfig;
import dev.kifuko.mctransport.crypto.PskCipher;
import dev.kifuko.mctransport.net.TunnelBridge;
import dev.kifuko.mctransport.protocol.Frame;
import dev.kifuko.mctransport.protocol.FrameCodec;
import dev.kifuko.mctransport.protocol.FrameType;
import dev.kifuko.mctransport.protocol.SecureFrameCodec;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-side Fabric 1.20.1 bridge using raw custom payload channels.
 */
public class FabricServerTunnelBridge implements TunnelBridge {

    private final Identifier channel;
    private final FrameCodec codec;
    private final ServerConfig config;
    private final SecureFrameCodec secureCodec;
    private final TunnelExecutorsAdapter executors;
    private final Map<java.util.UUID, PlayerTunnelSession> sessionsByPlayer = new ConcurrentHashMap<>();
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
        ServerPlayNetworking.registerGlobalReceiver(channel,
                (server, player, handler, buf, responseSender) -> {
                    if (closed) return;
                    byte[] bytes = readAll(buf);
                    dispatchToPlayerUuid(player.getUuid(), bytes);
                });

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            Object player = handler.getPlayer();
            java.util.UUID playerUuid = extractUuid(player);
            if (playerUuid == null) {
                McTransport.LOGGER.warn("could not extract player UUID; skipping JOIN");
                return;
            }
            PlayerTunnelSession session = createSession(player);
            sessionsByPlayer.put(playerUuid, session);
            sessionToPlayer.put(session, player);
            McTransport.LOGGER.info("player {} joined; tunnel session ready", playerUuid);
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            Object player = handler.getPlayer();
            java.util.UUID playerUuid = extractUuid(player);
            PlayerTunnelSession session = sessionsByPlayer.remove(playerUuid);
            if (session != null) {
                session.close();
                sessionToPlayer.remove(session);
                McTransport.LOGGER.info("player disconnected; tunnel session torn down");
            }
        });

        started = true;
    }

    /** Extracts a player's UUID across Yarn and Mojang-style method names. */
    private static java.util.UUID extractUuid(Object player) {
        if (player == null) {
            return null;
        }
        for (String methodName : new String[]{"getUuid", "getUUID"}) {
            try {
                java.lang.reflect.Method m = player.getClass().getMethod(methodName);
                Object ret = m.invoke(player);
                if (ret instanceof java.util.UUID id) {
                    return id;
                }
            } catch (ReflectiveOperationException ignored) {
                // Try the next known mapping.
            }
        }
        return null;
    }

    private static byte[] readAll(PacketByteBuf buf) {
        byte[] bytes = new byte[buf.readableBytes()];
        buf.readBytes(bytes);
        return bytes;
    }

    private void dispatchToPlayerUuid(java.util.UUID playerUuid, byte[] bytes) {
        executors.io().execute(() -> {
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
        public void send(Frame frame) {
            if (closed) {
                throw new IllegalStateException("bridge is closed");
            }
            Frame wireFrame = secureCodec.encryptForWire(frame);
            byte[] encoded = codec.encode(wireFrame);
            PacketByteBuf buf = PacketByteBufs.create();
            buf.writeBytes(encoded);
            ServerPlayNetworking.send((ServerPlayerEntity) player, channel, buf);
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
