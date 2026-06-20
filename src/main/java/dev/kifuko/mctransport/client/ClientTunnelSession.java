package dev.kifuko.mctransport.client;

import dev.kifuko.mctransport.McTransport;
import dev.kifuko.mctransport.net.TunnelBridge;
import dev.kifuko.mctransport.protocol.Frame;
import dev.kifuko.mctransport.protocol.FrameType;
import dev.kifuko.mctransport.protocol.ProtocolException;
import dev.kifuko.mctransport.protocol.RouteControlPayload;
import dev.kifuko.mctransport.protocol.StreamMode;
import dev.kifuko.mctransport.stream.StreamRegistry;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * One client-side tunnel session: the trusted state machine that mediates
 * between the local TCP listener, the stream registry, and the Minecraft
 * channel.
 *
 * <p>Frames may not flow until {@link #handleInbound(Frame)} observes
 * {@code CONFIG_APPLY} and the local listener is active. Once the route is
 * applied, {@link #openLocalStream()} hands out a new client stream and emits
 * an {@code OPEN} frame over the bridge.</p>
 */
public final class ClientTunnelSession {

    public static final byte PROTOCOL_VERSION = 1;
    public static final int SESSION_ID = 0; // MVP uses a single session id per player.

    private final TunnelBridge bridge;
    private final StreamRegistry registry;
    private final ClientStreamFactory streamFactory;
    private final ClientListenerController listenerController;

    private final Map<Integer, ClientStream> streams = new HashMap<>();
    private final Object lock = new Object();
    private boolean routeApplied;
    private volatile StreamMode streamMode = StreamMode.DIRECT;
    private volatile long lastInboundMillis;
    private long pingIntervalMillis;
    private long lastPingMillis;

    public ClientTunnelSession(TunnelBridge bridge,
                               StreamRegistry registry,
                               ClientStreamFactory streamFactory,
                               long nowMillis) {
        this(bridge, registry, streamFactory, nowMillis,
                new NoopListenerController());
    }

    public ClientTunnelSession(TunnelBridge bridge,
                               StreamRegistry registry,
                               ClientStreamFactory streamFactory,
                               long nowMillis,
                               ClientListenerController listenerController) {
        this.bridge = bridge;
        this.registry = registry;
        this.streamFactory = streamFactory;
        this.listenerController = listenerController;
        this.lastInboundMillis = nowMillis;
        this.lastPingMillis = nowMillis;
    }

    public boolean isAuthenticated() {
        return routeApplied;
    }

    public boolean isRouteApplied() {
        return routeApplied;
    }

    public StreamRegistry registry() {
        return registry;
    }

    public TunnelBridge bridge() {
        return bridge;
    }

    public Map<Integer, ClientStream> streams() {
        synchronized (lock) {
            return new HashMap<>(streams);
        }
    }

    public long lastInboundMillis() {
        return lastInboundMillis;
    }

    /** Drives the authenticated state machine on inbound frames. */
    public void handleInbound(Frame frame) {
        if (frame == null) {
            throw new IllegalArgumentException("frame must not be null");
        }
        lastInboundMillis = System.currentTimeMillis();
        McTransport.LOGGER.debug("client handleInbound: type={} streamId={} payload={}B",
                frame.type(), frame.streamId(), frame.payloadLength());
        switch (frame.type()) {
            case CONFIG_APPLY -> handleConfigApply(frame);
            case CONFIG_CLEAR -> handleConfigClear();
            case PING -> {
                requireRoute();
                sendPong(frame.streamId());
            }
            case PONG -> {
                requireRoute();
            }
            case OPEN -> {
                requireRoute();
                // Server should not OPEN; treat as protocol error.
                throw new ProtocolException("client received unexpected OPEN");
            }
            case DATA, CLOSE, RESET, ERROR -> {
                requireRoute();
                dispatchToStream(frame);
            }
            case AUTH -> throw new ProtocolException("client received unexpected AUTH");
            default -> throw new ProtocolException("unhandled frame type: " + frame.type());
        }
    }

    private void handleConfigApply(Frame frame) {
        RouteControlPayload.Apply apply = RouteControlPayload.decodeApply(frame.payload());
        try {
            listenerController.apply(apply.listenHost(), apply.listenPort());
            routeApplied = true;
            this.streamMode = apply.mode();
            sendConfigAck(true, "listening on " + apply.listenHost() + ":" + apply.listenPort()
                    + " (mode=" + apply.mode() + ")");
            McTransport.LOGGER.info("server route applied; listening on {}:{}",
                    apply.listenHost(), apply.listenPort());
        } catch (IOException | RuntimeException e) {
            routeApplied = false;
            sendConfigAck(false, "failed to bind "
                    + apply.listenHost() + ":" + apply.listenPort() + ": " + e.getMessage());
            McTransport.LOGGER.warn("failed to apply server route {}:{}: {}",
                    apply.listenHost(), apply.listenPort(), e.getMessage());
        }
    }

    private void handleConfigClear() {
        listenerController.clear();
        closeStreams();
        registry.clear();
        routeApplied = false;
        sendConfigAck(true, "route cleared");
        McTransport.LOGGER.info("server route cleared");
    }

    /** Allocates a new client stream and sends an OPEN frame. */
    public ClientStream openLocalStream() {
        synchronized (lock) {
            if (!routeApplied || !listenerController.isListening()) {
                throw new IllegalStateException("cannot open local stream before CONFIG_APPLY");
            }
            int id = registry.allocateClient();
            registry.setState(id, dev.kifuko.mctransport.stream.StreamState.OPEN_SENT);
            ClientStream stream = streamFactory.create(this, id, streamMode);
            streams.put(id, stream);
            Frame open = Frame.createTrusted(PROTOCOL_VERSION, SESSION_ID, id,
                    FrameType.OPEN, (byte) 0, new byte[0]);
            bridge.send(open);
            return stream;
        }
    }

    public void closeLocalStream(int streamId) {
        synchronized (lock) {
            ClientStream s = streams.remove(streamId);
            registry.remove(streamId);
            if (s != null) {
                s.closeSocketAndRelease();
            }
        }
    }

    /** Periodically sends PING while authenticated. */
    public void tick(long nowMillis) {
        if (!routeApplied) {
            return;
        }
        if (nowMillis - lastPingMillis >= pingIntervalMillis) {
            Frame ping = Frame.createTrusted(PROTOCOL_VERSION, SESSION_ID, 0,
                    FrameType.PING, (byte) 0, new byte[0]);
            bridge.send(ping);
            lastPingMillis = nowMillis;
        }
    }

    public void setPingIntervalMillis(long millis) {
        if (millis < 0) {
            throw new IllegalArgumentException("ping interval must be non-negative");
        }
        this.pingIntervalMillis = millis;
    }

    private void sendPong(int streamId) {
        Frame pong = Frame.createTrusted(PROTOCOL_VERSION, SESSION_ID, streamId,
                FrameType.PONG, (byte) 0, new byte[0]);
        bridge.send(pong);
    }

    private void dispatchToStream(Frame frame) {
        int streamId = frame.streamId();
        ClientStream stream;
        synchronized (lock) {
            stream = streams.get(streamId);
        }
        if (stream == null) {
            // Unknown stream ID — send RESET so the server cleans up.
            McTransport.LOGGER.debug("client dispatch: unknown stream {}, sending RESET (type={})",
                    streamId, frame.type());
            Frame reset = Frame.createTrusted(PROTOCOL_VERSION, SESSION_ID, streamId,
                    FrameType.RESET, (byte) 0, new byte[0]);
            bridge.send(reset);
            return;
        }
        McTransport.LOGGER.debug("client dispatch: stream {} type={} payload={}B",
                streamId, frame.type(), frame.payloadLength());
        stream.onFrame(frame);
    }

    private void requireRoute() {
        if (!routeApplied) {
            throw new ProtocolException("frame received before CONFIG_APPLY");
        }
    }

    /** Idempotent full close. */
    public void close() {
        routeApplied = false;
        listenerController.clear();
        closeStreams();
        registry.clear();
    }

    private void sendConfigAck(boolean ok, String message) {
        Frame ack = Frame.createTrusted(PROTOCOL_VERSION, SESSION_ID, 0,
                FrameType.CONFIG_ACK, (byte) 0,
                RouteControlPayload.encodeAck(ok, message));
        bridge.send(ack);
    }

    private void closeStreams() {
        synchronized (lock) {
            for (ClientStream s : new java.util.ArrayList<>(streams.values())) {
                s.closeSocketAndRelease();
            }
            streams.clear();
        }
    }

    private static final class NoopListenerController implements ClientListenerController {
        private boolean listening;

        @Override
        public void apply(String listenHost, int listenPort) {
            listening = true;
        }

        @Override
        public void clear() {
            listening = false;
        }

        @Override
        public boolean isListening() {
            return listening;
        }
    }
}
