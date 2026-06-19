package dev.kifuko.mctransport.server;

import dev.kifuko.mctransport.auth.AuthPayload;
import dev.kifuko.mctransport.buffer.BufferBudget;
import dev.kifuko.mctransport.buffer.ReservationState;
import dev.kifuko.mctransport.config.ServerConfig;
import dev.kifuko.mctransport.crypto.PskCipher;
import dev.kifuko.mctransport.net.TunnelBridge;
import dev.kifuko.mctransport.protocol.Frame;
import dev.kifuko.mctransport.protocol.FrameType;
import dev.kifuko.mctransport.protocol.ProtocolException;
import dev.kifuko.mctransport.stream.StreamRegistry;

import java.util.List;
import java.util.UUID;

/**
 * Per-player authentication state for the server-side MC Transport Dialer.
 *
 * <p>A session starts unauthenticated. The server accepts {@code AUTH} only
 * when the player UUID is in {@code allowed_players} and the embedded
 * plaintext payload decrypts cleanly with the configured PSK. Only after
 * {@code AUTH_OK} is sent do further frame types become valid.</p>
 */
public final class PlayerTunnelSession {

    /** Wire-protocol version exchanged in the AUTH payload. */
    public static final byte PROTOCOL_VERSION = 1;

    /** Maximum allowed clock skew between client and server in seconds. */
    public static final long DEFAULT_MAX_CLOCK_SKEW_SECONDS = 60;

    private final ServerConfig config;
    private final TunnelBridge bridge;
    private final PskCipher cipher;
    private final BufferBudget budget;
    private final ReservationState reservations;
    private final StreamRegistry registry;
    private final TargetTcpConnector connector;
    private final long maxClockSkewSeconds;
    private final long nowSecondsSupplier;
    private final long nowMillisSupplier;

    private boolean authenticated;
    private UUID authenticatedUuid;
    private volatile long lastInboundMillis;
    private final ServerStreamFactory streamFactory;

    public PlayerTunnelSession(ServerConfig config,
                               TunnelBridge bridge,
                               PskCipher cipher,
                               StreamRegistry registry,
                               BufferBudget budget,
                               ReservationState reservations,
                               TargetTcpConnector connector,
                               long nowSeconds,
                               long nowMillis,
                               ServerStreamFactory streamFactory) {
        this.config = config;
        this.bridge = bridge;
        this.cipher = cipher;
        this.registry = registry;
        this.budget = budget;
        this.reservations = reservations;
        this.connector = connector;
        this.maxClockSkewSeconds = DEFAULT_MAX_CLOCK_SKEW_SECONDS;
        this.nowSecondsSupplier = nowSeconds;
        this.nowMillisSupplier = nowMillis;
        this.lastInboundMillis = nowMillis;
        this.streamFactory = streamFactory;
    }

    public BufferBudget budget() {
        return budget;
    }

    public ReservationState reservations() {
        return reservations;
    }

    public boolean isAuthenticated() {
        return authenticated;
    }

    public UUID authenticatedUuid() {
        return authenticatedUuid;
    }

    public StreamRegistry registry() {
        return registry;
    }

    public TunnelBridge bridge() {
        return bridge;
    }

    public ServerConfig config() {
        return config;
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

    /** Handles an inbound frame from the Minecraft channel. */
    public void handleInbound(Frame frame) {
        if (frame == null) {
            throw new IllegalArgumentException("frame must not be null");
        }
        lastInboundMillis = nowMillisSupplier;
        FrameType type = frame.type();
        switch (type) {
            case AUTH -> handleAuth(frame);
            case PING -> {
                requireAuth();
                sendPong(frame.streamId());
            }
            case PONG -> {
                requireAuth();
            }
            case OPEN -> {
                requireAuth();
                handleOpen(frame);
            }
            case DATA, CLOSE, RESET, ERROR -> {
                requireAuth();
                dispatchToStream(frame);
            }
            case AUTH_OK -> {
                // Server must never receive AUTH_OK.
                throw new ProtocolException("server received unexpected AUTH_OK");
            }
            default -> throw new ProtocolException("unhandled frame type: " + type);
        }
    }

    private void handleAuth(Frame frame) {
        if (authenticated) {
            // Re-auth attempts are ignored.
            return;
        }
        byte[] plaintext;
        try {
            plaintext = cipher.decrypt(frame, frame.payload());
        } catch (ProtocolException e) {
            throw new ProtocolException("AUTH decryption failed", e);
        }
        AuthPayload.Decoded decoded;
        try {
            decoded = AuthPayload.decode(plaintext, PROTOCOL_VERSION,
                    maxClockSkewSeconds, nowSecondsSupplier);
        } catch (IllegalArgumentException e) {
            throw new ProtocolException("invalid AUTH payload: " + e.getMessage(), e);
        }
        if (!isAllowed(decoded.playerUuid())) {
            throw new ProtocolException("player not allowed: " + decoded.playerUuid());
        }
        authenticated = true;
        authenticatedUuid = decoded.playerUuid();
        sendAuthOk();
    }

    private boolean isAllowed(UUID uuid) {
        List<String> allowed = config.getAllowedPlayers();
        for (String entry : allowed) {
            try {
                UUID candidate = UUID.fromString(entry);
                if (candidate.equals(uuid)) {
                    return true;
                }
            } catch (IllegalArgumentException ignored) {
                // Compare against the raw string form too (dashes optional in some tooling).
                if (entry.replace("-", "").equalsIgnoreCase(
                        uuid.toString().replace("-", ""))) {
                    return true;
                }
            }
        }
        return false;
    }

    private void sendAuthOk() {
        Frame reply = Frame.createTrusted(PROTOCOL_VERSION, 0, 0,
                FrameType.AUTH_OK, (byte) 0, new byte[0]);
        bridge.send(reply);
    }

    private void sendPong(int streamId) {
        Frame reply = Frame.createTrusted(PROTOCOL_VERSION, 0, streamId,
                FrameType.PONG, (byte) 0, new byte[0]);
        bridge.send(reply);
    }

    private void requireAuth() {
        if (!authenticated) {
            throw new ProtocolException("frame received before AUTH_OK");
        }
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
        // ServerStreamFactory.dialAndAttach is expected to synchronously dial
        // the fixed target, register the stream in the registry on success,
        // and (on failure) send RESET itself.
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
        Frame f = Frame.createTrusted(PROTOCOL_VERSION, 0, streamId,
                FrameType.RESET, (byte) 0, new byte[0]);
        bridge.send(f);
    }

    private void sendError(int streamId, String message) {
        byte[] body = message == null ? new byte[0] : message.getBytes();
        Frame f = Frame.createTrusted(PROTOCOL_VERSION, 0, streamId,
                FrameType.ERROR, (byte) 0, body);
        bridge.send(f);
    }

    /** Called when the underlying player disconnects. Idempotent. */
    public void close() {
        authenticated = false;
        authenticatedUuid = null;
        streamFactory.closeAll(this);
        registry.clear();
    }
}
