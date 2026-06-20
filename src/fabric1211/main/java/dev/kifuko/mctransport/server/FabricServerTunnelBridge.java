package dev.kifuko.mctransport.server;

import dev.kifuko.mctransport.McTransport;
import dev.kifuko.mctransport.client.TransportPayload;
import dev.kifuko.mctransport.config.RouteConfig;
import dev.kifuko.mctransport.config.ServerConfig;
import dev.kifuko.mctransport.net.SerialExecutor;
import dev.kifuko.mctransport.net.TunnelBridge;
import dev.kifuko.mctransport.protocol.Frame;
import dev.kifuko.mctransport.protocol.FrameCodec;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-side Fabric 1.21.1 bridge. Per-player sessions are created for
 * each configured route and dispatched by player UUID and frame session id
 * (the route's listen port).
 */
public class FabricServerTunnelBridge implements TunnelBridge {

    private final Identifier channel;
    private final FrameCodec codec;
    private final ServerConfig config;
    private final RouteStore routeStore;
    private final TunnelExecutorsAdapter executors;
    private final Map<UUID, Map<Integer, PlayerTunnelSession>> sessionsByPlayer = new ConcurrentHashMap<>();
    private final Map<UUID, Object> playersByUuid = new ConcurrentHashMap<>();
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
        ServerPlayNetworking.registerGlobalReceiver(TransportPayload.ID,
                (payload, context) -> {
                    if (closed) return;
                    byte[] bytes = payload.bytes();
                    dispatchToPlayerUuid(context.player().getUuid(), bytes);
        });

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayerEntity player = handler.getPlayer();
            UUID playerUuid = player.getUuid();
            registerOnlinePlayer(playerUuid, player);
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            ServerPlayerEntity player = handler.getPlayer();
            UUID playerUuid = player.getUuid();
            playersByUuid.remove(playerUuid);
            dispatchersByPlayer.remove(playerUuid);
            Map<Integer, PlayerTunnelSession> sessions = sessionsByPlayer.remove(playerUuid);
            if (sessions != null) {
                for (PlayerTunnelSession session : sessions.values()) {
                    session.close();
                    sessionToPlayer.remove(session);
                }
                McTransport.LOGGER.info("player disconnected; tunnel sessions torn down");
            }
        });

        started = true;
    }

    private void dispatchToPlayerUuid(UUID playerUuid, byte[] bytes) {
        SerialExecutor dispatcher = dispatchersByPlayer.get(playerUuid);
        if (dispatcher == null) {
            McTransport.LOGGER.warn("inbound frame for unknown player {}", playerUuid);
            return;
        }
        dispatcher.execute(() -> {
            try {
                Frame wireFrame = codec.decode(bytes);
                Map<Integer, PlayerTunnelSession> sessions = sessionsByPlayer.get(playerUuid);
                PlayerTunnelSession session = sessions == null ? null : sessions.get(wireFrame.sessionId());
                if (session == null) {
                    McTransport.LOGGER.warn("inbound frame for unknown route session {} player {}",
                            wireFrame.sessionId(), playerUuid);
                    return;
                }
                session.handleInbound(wireFrame);
            } catch (RuntimeException e) {
                McTransport.LOGGER.warn("failed to decode inbound frame: {}", e.getMessage());
            }
        });
    }

    /** Pushes the latest route config to an online player for a specific port. */
    public void applyRouteIfOnline(UUID uuid, int listenPort) {
        Object player = playersByUuid.get(uuid);
        if (player == null) {
            return;
        }
        Map<Integer, PlayerTunnelSession> sessions = sessionsByPlayer.computeIfAbsent(
                uuid, ignored -> new ConcurrentHashMap<>());
        // Remove existing session at this port if any
        PlayerTunnelSession existing = sessions.remove(listenPort);
        if (existing != null) {
            existing.sendRouteClear();
            existing.close();
            sessionToPlayer.remove(existing);
        }
        RouteConfig route = routeStore.routeFor(uuid, listenPort);
        if (route == null) {
            return;
        }
        PlayerTunnelSession replacement = createSession(route, player);
        sessions.put(listenPort, replacement);
        sessionToPlayer.put(replacement, player);
        replacement.sendRouteIfConfigured();
    }

    protected void registerOnlinePlayer(UUID playerUuid, Object player) {
        playersByUuid.put(playerUuid, player);
        dispatchersByPlayer.put(playerUuid, new SerialExecutor(executors.io()));

        Map<Integer, PlayerTunnelSession> sessions = new ConcurrentHashMap<>();
        sessionsByPlayer.put(playerUuid, sessions);
        List<RouteConfig> routes = routeStore.routesFor(playerUuid);
        if (routes.isEmpty()) {
            McTransport.LOGGER.info("player {} joined; no route configured", playerUuid);
            return;
        }
        for (RouteConfig route : routes) {
            PlayerTunnelSession session = createSession(route, player);
            sessions.put(session.sessionId(), session);
            sessionToPlayer.put(session, player);
        }
        McTransport.LOGGER.info("player {} joined; {} tunnel sessions ready", playerUuid, sessions.size());
        for (PlayerTunnelSession session : sessions.values()) {
            session.sendRouteIfConfigured();
        }
    }

    /** Clears the route config on an online player for a specific port. */
    public void clearRouteIfOnline(UUID uuid, int listenPort) {
        Map<Integer, PlayerTunnelSession> sessions = sessionsByPlayer.get(uuid);
        if (sessions == null) {
            return;
        }
        PlayerTunnelSession session = sessions.remove(listenPort);
        if (session != null) {
            session.sendRouteClear();
            session.close();
            sessionToPlayer.remove(session);
        }
    }

    protected PlayerTunnelSession createSession(RouteConfig route, Object player) {
        ServerConfig cfg = config;
        TargetTcpConnector connector = new TargetTcpConnector(
                cfg.getConnectTimeoutSeconds(), executors.io());
        DefaultServerStreamFactory factory = new DefaultServerStreamFactory(connector,
                cfg.getStreamBufferSize(), 4096, executors.io());
        UUID uuid = route.getPlayerUuid();
        return new PlayerTunnelSession(uuid, route, new PlayerBridge(player), cfg, routeStore,
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
        playersByUuid.clear();
        dispatchersByPlayer.clear();
    }

    /** Visible for tests. */
    public Optional<PlayerTunnelSession> sessionFor(UUID uuid, int listenPort) {
        Map<Integer, PlayerTunnelSession> sessions = sessionsByPlayer.get(uuid);
        return Optional.ofNullable(sessions == null ? null : sessions.get(listenPort));
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
            ServerPlayNetworking.send((ServerPlayerEntity) player,
                    new TransportPayload(encoded));
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
