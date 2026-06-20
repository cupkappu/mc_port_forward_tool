package dev.kifuko.mctransport.server;

import dev.kifuko.mctransport.McTransport;
import dev.kifuko.mctransport.config.ServerConfig;
import dev.kifuko.mctransport.net.SerialExecutor;
import dev.kifuko.mctransport.net.TunnelBridge;
import dev.kifuko.mctransport.protocol.Frame;
import dev.kifuko.mctransport.protocol.FrameCodec;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-side Fabric 1.20.1 bridge. Each connected player owns one
 * {@link PlayerTunnelSession}; control and stream frames are dispatched
 * by player UUID.
 */
public class FabricServerTunnelBridge implements TunnelBridge {

    private final Identifier channel;
    private final FrameCodec codec;
    private final ServerConfig config;
    private final RouteStore routeStore;
    private final TunnelExecutorsAdapter executors;
    private final Map<UUID, PlayerTunnelSession> sessionsByPlayer = new ConcurrentHashMap<>();
    private final Map<UUID, SerialExecutor> dispatchersByPlayer = new ConcurrentHashMap<>();
    private final Map<PlayerTunnelSession, Object> sessionToPlayer = new ConcurrentHashMap<>();
    private boolean started;
    private boolean closed;

    public FabricServerTunnelBridge(Identifier channel, FrameCodec codec,
                                    ServerConfig config,
                                    RouteStore routeStore,
                                    TunnelExecutorsAdapter executors) {
        this.channel = channel;
        this.codec = codec;
        this.config = config;
        this.routeStore = routeStore;
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
            ServerPlayerEntity player = handler.getPlayer();
            UUID playerUuid = player.getUuid();
            PlayerTunnelSession session = createSession(player);
            dispatchersByPlayer.put(playerUuid, new SerialExecutor(executors.io()));
            sessionsByPlayer.put(playerUuid, session);
            sessionToPlayer.put(session, player);
            McTransport.LOGGER.info("player {} joined; tunnel session ready", playerUuid);
            session.sendRouteIfConfigured();
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            ServerPlayerEntity player = handler.getPlayer();
            UUID playerUuid = player.getUuid();
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

    private static byte[] readAll(PacketByteBuf buf) {
        byte[] bytes = new byte[buf.readableBytes()];
        buf.readBytes(bytes);
        return bytes;
    }

    private void dispatchToPlayerUuid(UUID playerUuid, byte[] bytes) {
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
                session.handleInbound(wireFrame);
            } catch (RuntimeException e) {
                McTransport.LOGGER.warn("failed to decode inbound frame: {}", e.getMessage());
            }
        });
    }

    /** Pushes the latest route config to an online player, if any. */
    public void applyRouteIfOnline(UUID uuid, int listenPort) {
        PlayerTunnelSession session = sessionsByPlayer.get(uuid);
        if (session != null) {
            session.sendRouteIfConfigured();
        }
    }

    /** Clears the route config on an online player. */
    public void clearRouteIfOnline(UUID uuid, int listenPort) {
        PlayerTunnelSession session = sessionsByPlayer.get(uuid);
        if (session != null) {
            session.sendRouteClear();
        }
    }

    protected PlayerTunnelSession createSession(Object player) {
        ServerConfig cfg = config;
        TargetTcpConnector connector = new TargetTcpConnector(
                cfg.getConnectTimeoutSeconds(), executors.io());
        DefaultServerStreamFactory factory = new DefaultServerStreamFactory(connector,
                cfg.getStreamBufferSize(), 4096, executors.io());
        UUID uuid = player instanceof ServerPlayerEntity sp
                ? sp.getUuid() : UUID.randomUUID();
        return new PlayerTunnelSession(uuid, new PlayerBridge(player), cfg, routeStore,
                new dev.kifuko.mctransport.stream.StreamRegistry(
                        cfg.getMaxStreamsPerPlayer(), false),
                new dev.kifuko.mctransport.buffer.BufferBudget(
                        cfg.getStreamBufferSize(), cfg.getGlobalBufferSizePerPlayer()),
                new dev.kifuko.mctransport.buffer.ReservationState(),
                connector,
                System.currentTimeMillis(), factory);
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

    /** Visible for tests. */
    public Optional<PlayerTunnelSession> sessionFor(UUID uuid) {
        return Optional.ofNullable(sessionsByPlayer.get(uuid));
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
            byte[] encoded = codec.encode(frame);
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