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
 * Per-route tunnel session for the server-side MC Transport Dialer.
 *
 * <p>Each session is bound to a single {@link RouteConfig} and uses
 * {@code route.listenPort} as its frame {@code sessionId}. The server
 * creates one session per (player UUID, listenPort) pair.</p>
 */
public final class PlayerTunnelSession {

    /** Wire-protocol version exchanged in control payloads. */
    public static final byte PROTOCOL_VERSION = 1;

    private final UUID playerUuid;
    private final RouteConfig route;
    private final int sessionId;
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

    public PlayerTunnelSession(UUID playerUuid,
                               RouteConfig route,
                               TunnelBridge bridge,
                               ServerConfig config,
                               RouteStore routeStore,
                               StreamRegistry registry,
                               BufferBudget budget,
                               ReservationState reservations,
                               TargetTcpConnector connector,
                               long nowMillis,
                               ServerStreamFactory streamFactory) {
        if (route == null) {
            throw new IllegalArgumentException("route must not be null");
        }
        if (!route.getPlayerUuid().equals(playerUuid)) {
            throw new IllegalArgumentException(
                    "route playerUuid must match session playerUuid");
        }
        this.playerUuid = playerUuid;
        this.route = route;
        this.sessionId = route.routeSessionId();
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

    /** The session id used in outbound frames — equal to {@code route.listenPort}. */
    public int sessionId() {
        return sessionId;
    }

    /** The route this session is bound to. */
    public RouteConfig route() {
        return route;
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

    /**
     * Pushes the bound route config to the client.
     * Called by the Fabric bridge right after session creation.
     */
    public void sendRouteIfConfigured() {
        sendConfigApply(route);
    }

    /** Pushes {@code CONFIG_CLEAR} and tears down any open streams. */
    public void sendRouteClear() {
        routeActive = false;
        sendConfigClear();
        streamFactory.closeAll(this);
        registry.clear();
    }

    /** Handles an inbound frame from the Minecraft channel. */
    public void handleInbound(Frame frame) {
        if (frame == null) {
            throw new IllegalArgumentException("frame must not be null");
        }
        if (frame.sessionId() != sessionId) {
            throw new ProtocolException("unexpected session id " + frame.sessionId()
                    + " for route " + sessionId);
        }
        lastInboundMillis = nowMillisSupplier;
        FrameType type = frame.type();
        dev.kifuko.mctransport.McTransport.LOGGER.debug(
                "server handleInbound: type={} streamId={} payload={}B",
                type, frame.streamId(), frame.payloadLength());
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
            streamFactory.closeAll(this);
            registry.clear();
            return;
        }
        if (routeActive) {
            // Already active: duplicate ack, no-op.
            return;
        }
        routeActive = true;
        dev.kifuko.mctransport.McTransport.LOGGER.info(
                "route active for player {} -> {}:{}",
                playerUuid, route.getTargetHost(), route.getTargetPort());
    }

    private void requireRoute() {
        if (!routeActive) {
            throw new ProtocolException(
                    "frame received before route is active for " + playerUuid);
        }
    }

    private void sendConfigApply(RouteConfig configRoute) {
        routeActive = false;
        Frame f = Frame.createTrusted(PROTOCOL_VERSION, sessionId, 0,
                FrameType.CONFIG_APPLY, (byte) 0,
                RouteControlPayload.encodeApply(configRoute.getListenHost(),
                        configRoute.getListenPort(), configRoute.getMode()));
        bridge.send(f);
    }

    private void sendConfigClear() {
        Frame f = Frame.createTrusted(PROTOCOL_VERSION, sessionId, 0,
                FrameType.CONFIG_CLEAR, (byte) 0, new byte[0]);
        bridge.send(f);
    }

    private void sendPong(int streamId) {
        Frame f = Frame.createTrusted(PROTOCOL_VERSION, sessionId, streamId,
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
        streamFactory.dialAndAttach(this, streamId, route.getMode());
    }

    private void dispatchToStream(Frame frame) {
        int streamId = frame.streamId();
        ServerStream stream = streamFactory.find(this, streamId);
        if (stream == null) {
            if (frame.type() == FrameType.RESET) {
                return;
            }
            dev.kifuko.mctransport.McTransport.LOGGER.debug(
                    "server dispatch: unknown stream {}, sending RESET (type={})",
                    streamId, frame.type());
            sendReset(streamId);
            return;
        }
        dev.kifuko.mctransport.McTransport.LOGGER.debug(
                "server dispatch: stream {} type={} payload={}B",
                streamId, frame.type(), frame.payloadLength());
        stream.onFrame(frame);
    }

    private void sendReset(int streamId) {
        Frame f = Frame.createTrusted(PROTOCOL_VERSION, sessionId, streamId,
                FrameType.RESET, (byte) 0, new byte[0]);
        bridge.send(f);
    }

    private void sendError(int streamId, String message) {
        byte[] body = message == null ? new byte[0] : message.getBytes();
        Frame f = Frame.createTrusted(PROTOCOL_VERSION, sessionId, streamId,
                FrameType.ERROR, (byte) 0, body);
        bridge.send(f);
    }

    /** Called when the underlying player disconnects. Idempotent. */
    public void close() {
        routeActive = false;
        streamFactory.closeAll(this);
        registry.clear();
    }
}
