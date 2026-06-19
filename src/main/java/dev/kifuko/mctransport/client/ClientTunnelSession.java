package dev.kifuko.mctransport.client;

import dev.kifuko.mctransport.auth.AuthPayload;
import dev.kifuko.mctransport.config.ClientConfig;
import dev.kifuko.mctransport.crypto.PskCipher;
import dev.kifuko.mctransport.net.TunnelBridge;
import dev.kifuko.mctransport.protocol.Frame;
import dev.kifuko.mctransport.protocol.FrameType;
import dev.kifuko.mctransport.protocol.ProtocolException;
import dev.kifuko.mctransport.stream.StreamRegistry;

import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * One client-side tunnel session: the trusted state machine that mediates
 * between the local TCP listener, the stream registry, and the Minecraft
 * channel.
 *
 * <p>Frames may not flow until {@link #handleInbound(Frame)} observes
 * {@code AUTH_OK}. Once authenticated, {@link #openLocalStream()} hands out
 * a new client stream and emits an {@code OPEN} frame over the bridge.</p>
 */
public final class ClientTunnelSession {

    public static final byte PROTOCOL_VERSION = 1;
    public static final int SESSION_ID = 0; // MVP uses a single session id per player.

    private final ClientConfig config;
    private final TunnelBridge bridge;
    private final PskCipher cipher;
    private final StreamRegistry registry;
    private final ClientStreamFactory streamFactory;
    private final SecureRandom rng;

    private final Map<Integer, ClientStream> streams = new HashMap<>();
    private final Object lock = new Object();
    private boolean authenticated;
    private volatile long lastInboundMillis;
    private long pingIntervalMillis;
    private long lastPingMillis;

    public ClientTunnelSession(ClientConfig config,
                               TunnelBridge bridge,
                               PskCipher cipher,
                               StreamRegistry registry,
                               ClientStreamFactory streamFactory,
                               SecureRandom rng,
                               long nowMillis) {
        this.config = config;
        this.bridge = bridge;
        this.cipher = cipher;
        this.registry = registry;
        this.streamFactory = streamFactory;
        this.rng = rng;
        this.lastInboundMillis = nowMillis;
        this.lastPingMillis = nowMillis;
    }

    public boolean isAuthenticated() {
        return authenticated;
    }

    public StreamRegistry registry() {
        return registry;
    }

    public ClientConfig config() {
        return config;
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

    /** Sends the encrypted {@code AUTH} frame. Must be called before auth. */
    public void sendAuth(UUID playerUuid, long nowSeconds) {
        byte[] nonce = new byte[16];
        rng.nextBytes(nonce);
        byte[] body = AuthPayload.encode(PROTOCOL_VERSION, playerUuid, nonce, nowSeconds);
        Frame inner = Frame.createTrusted(PROTOCOL_VERSION, SESSION_ID, 0,
                FrameType.AUTH, (byte) 0, body);
        Frame outer = Frame.createTrusted(PROTOCOL_VERSION, SESSION_ID, 0,
                FrameType.AUTH, (byte) 0, cipher.encrypt(inner));
        bridge.send(outer);
    }

    /** Drives the authenticated state machine on inbound frames. */
    public void handleInbound(Frame frame) {
        if (frame == null) {
            throw new IllegalArgumentException("frame must not be null");
        }
        lastInboundMillis = System.currentTimeMillis();
        switch (frame.type()) {
            case AUTH_OK -> handleAuthOk(frame);
            case PING -> {
                requireAuth();
                sendPong(frame.streamId());
            }
            case PONG -> {
                requireAuth();
            }
            case OPEN -> {
                requireAuth();
                // Server should not OPEN; treat as protocol error.
                throw new ProtocolException("client received unexpected OPEN");
            }
            case DATA, CLOSE, RESET, ERROR -> {
                requireAuth();
                dispatchToStream(frame);
            }
            case AUTH -> throw new ProtocolException("client received unexpected AUTH");
            default -> throw new ProtocolException("unhandled frame type: " + frame.type());
        }
    }

    private void handleAuthOk(Frame frame) {
        if (authenticated) {
            return;
        }
        authenticated = true;
        // Wake any pending stream opens.
    }

    /** Allocates a new client stream and sends an OPEN frame. */
    public ClientStream openLocalStream() {
        synchronized (lock) {
            if (!authenticated) {
                throw new IllegalStateException("cannot open local stream before AUTH_OK");
            }
            int id = registry.allocateClient();
            registry.setState(id, dev.kifuko.mctransport.stream.StreamState.OPEN_SENT);
            ClientStream stream = streamFactory.create(this, id);
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
        if (!authenticated) {
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
            Frame reset = Frame.createTrusted(PROTOCOL_VERSION, SESSION_ID, streamId,
                    FrameType.RESET, (byte) 0, new byte[0]);
            bridge.send(reset);
            return;
        }
        stream.onFrame(frame);
    }

    private void requireAuth() {
        if (!authenticated) {
            throw new ProtocolException("frame received before AUTH_OK");
        }
    }

    /** Idempotent full close. */
    public void close() {
        authenticated = false;
        synchronized (lock) {
            for (ClientStream s : new java.util.ArrayList<>(streams.values())) {
                s.closeSocketAndRelease();
            }
            streams.clear();
        }
        registry.clear();
    }
}