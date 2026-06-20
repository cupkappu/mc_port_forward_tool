package dev.kifuko.mctransport.server;

import dev.kifuko.mctransport.buffer.BufferBudget;
import dev.kifuko.mctransport.buffer.ReservationState;
import dev.kifuko.mctransport.config.RouteConfig;
import dev.kifuko.mctransport.config.ServerConfig;
import dev.kifuko.mctransport.net.TunnelBridge;
import dev.kifuko.mctransport.protocol.Frame;
import dev.kifuko.mctransport.protocol.FrameType;
import dev.kifuko.mctransport.protocol.ProtocolException;
import dev.kifuko.mctransport.protocol.RouteControlPayload;
import dev.kifuko.mctransport.stream.StreamRegistry;

import java.util.UUID;

/**
 * Per-player tunnel session for the server-side MC Transport Dialer.
 *
 * <p>The server identifies the player UUID from the Minecraft session
 * itself (Fabric provides it on the join event). There is no
 * client-supplied AUTH handshake in the server-pushed route mode. The
 * session looks up a route for the player's UUID in {@link RouteStore},
 * pushes {@code CONFIG_APPLY} on join, and dials the route's target once
 * the client replies with {@code CONFIG_ACK}.</p>
 */
public final class PlayerTunnelSession {

    /** Wire-protocol version exchanged in control payloads. */
    public static final byte PROTOCOL_VERSION = 1;

    /** Session id used for every control frame in the MVP. */
    public static final int SESSION_ID = 0;

    private final UUID playerUuid;
    private final TunnelBridge bridge;
    private final ServerConfig config;
    private final RouteStore routeStore;
    private final BufferBudget budget;
    private final ReservationState reservations;
    private final StreamRegistry registry;
    private final TargetTcpConnector connector;
    private final long nowMillisSupplier;
    private final ServerStreamFactory streamFactory;

    private volatile long lastInboundMillis;
    private volatile boolean routeActive;
    private volatile RouteConfig activeRoute;

    public PlayerTunnelSession(UUID playerUuid,
                               TunnelBridge bridge,
                               ServerConfig config,
                               RouteStore routeStore,
                               StreamRegistry registry,
                               BufferBudget budget,
                               ReservationState reservations,
                               TargetTcpConnector connector,
                               long nowMillis,
                               ServerStreamFactory streamFactory) {
        this.playerUuid = playerUuid;
        this.bridge = bridge;
        this.config = config;
        this.routeStore = routeStore;
        this.registry = registry;
        this.budget = budget;
        this.reservations = reservations;
        this.connector = connector;
        this.nowMillisSupplier = nowMillis;
        this.lastInboundMillis = nowMillis;
        this.streamFactory = streamFactory;
    }

    public UUID playerUuid() {
        return playerUuid;
    }

    public TunnelBridge bridge() {
        return bridge;
    }

    public ServerConfig config() {
        return config;
    }

    public RouteStore routeStore() {
        return routeStore;
    }

    public StreamRegistry registry() {
        return registry;
    }

    public BufferBudget budget() {
        return budget;
    }

    public ReservationState reservations() {
        return reservations;
    }

    public TargetTcpConnector connector() {
        return connector;
    }

    public long lastInboundMillis() {
        return lastInboundMillis;
    }

    public ServerStreamFactory streamFactory() {
        return streamFactory;
    }

    public boolean isRouteActive() {
        return routeActive;
    }

    public RouteConfig activeRoute() {
        return activeRoute;
    }

    /**
     * Pushes the configured route to the client when one exists.
     * Called by the Fabric bridge right after the player joins.
     */
    public void sendRouteIfConfigured() {
        RouteConfig route = routeStore.routeFor(playerUuid);
        if (route == null) {
            return;
        }
        sendConfigApply(route);
    }

    /** Pushes {@code CONFIG_CLEAR} and tears down any open streams. */
    public void sendRouteClear() {
        routeActive = false;
        activeRoute = null;
        sendConfigClear();
        streamFactory.closeAll(this);
        registry.clear();
    }

    /** Handles an inbound frame from the Minecraft channel. */
    public void handleInbound(Frame frame) {
        if (frame == null) {
            throw new IllegalArgumentException("frame must not be null");
        }
        lastInboundMillis = nowMillisSupplier;
        FrameType type = frame.type();
        switch (type) {
            case CONFIG_ACK -> handleConfigAck(frame);
            case PING -> sendPong(frame.streamId());
            case PONG -> { /* keep-alive */ }
            case OPEN -> {
                requireRoute();
                handleOpen(frame);
            }
            case DATA, CLOSE, RESET, ERROR -> {
                requireRoute();
                dispatchToStream(frame);
            }
            case AUTH, AUTH_OK, CONFIG_APPLY, CONFIG_CLEAR -> {
                throw new ProtocolException(
                        "server received unexpected frame: " + type);
            }
            default -> throw new ProtocolException("unhandled frame type: " + type);
        }
    }

    private void handleConfigAck(Frame frame) {
        RouteControlPayload.Ack ack = RouteControlPayload.decodeAck(frame.payload());
        if (!ack.ok()) {
            dev.kifuko.mctransport.McTransport.LOGGER.warn(
                    "client reported route apply failure: {}",
                    ack.message());
            routeActive = false;
            activeRoute = null;
            streamFactory.closeAll(this);
            registry.clear();
            return;
        }
        if (activeRoute == null) {
            // Ack for CONFIG_CLEAR or before any apply: no-op.
            return;
        }
        routeActive = true;
        dev.kifuko.mctransport.McTransport.LOGGER.info(
                "route active for player {} -> {}:{}",
                playerUuid, activeRoute.getTargetHost(), activeRoute.getTargetPort());
    }

    private void requireRoute() {
        if (!routeActive || activeRoute == null) {
            throw new ProtocolException(
                    "frame received before route is active for " + playerUuid);
        }
    }

    private void sendConfigApply(RouteConfig route) {
        activeRoute = route;
        Frame f = Frame.createTrusted(PROTOCOL_VERSION, SESSION_ID, 0,
                FrameType.CONFIG_APPLY, (byte) 0,
                RouteControlPayload.encodeApply(route.getListenHost(), route.getListenPort()));
        bridge.send(f);
    }

    private void sendConfigClear() {
        Frame f = Frame.createTrusted(PROTOCOL_VERSION, SESSION_ID, 0,
                FrameType.CONFIG_CLEAR, (byte) 0, new byte[0]);
        bridge.send(f);
    }

    private void sendPong(int streamId) {
        Frame f = Frame.createTrusted(PROTOCOL_VERSION, SESSION_ID, streamId,
                FrameType.PONG, (byte) 0, new byte[0]);
        bridge.send(f);
    }

    private void handleOpen(Frame frame) {
        int streamId = frame.streamId();
        if (registry.contains(streamId)) {
            sendReset(streamId);
            return;
        }
        if (registry.size() >= config.getMaxStreamsPerPlayer()) {
            sendError(streamId, "max streams reached");
            return;
        }
        streamFactory.dialAndAttach(this, streamId);
    }

    private void dispatchToStream(Frame frame) {
        int streamId = frame.streamId();
        ServerStream stream = streamFactory.find(this, streamId);
        if (stream == null) {
            if (frame.type() == FrameType.RESET) {
                return;
            }
            sendReset(streamId);
            return;
        }
        stream.onFrame(frame);
    }

    private void sendReset(int streamId) {
        Frame f = Frame.createTrusted(PROTOCOL_VERSION, SESSION_ID, streamId,
                FrameType.RESET, (byte) 0, new byte[0]);
        bridge.send(f);
    }

    private void sendError(int streamId, String message) {
        byte[] body = message == null ? new byte[0] : message.getBytes();
        Frame f = Frame.createTrusted(PROTOCOL_VERSION, SESSION_ID, streamId,
                FrameType.ERROR, (byte) 0, body);
        bridge.send(f);
    }

    /** Called when the underlying player disconnects. Idempotent. */
    public void close() {
        routeActive = false;
        activeRoute = null;
        streamFactory.closeAll(this);
        registry.clear();
    }
}
