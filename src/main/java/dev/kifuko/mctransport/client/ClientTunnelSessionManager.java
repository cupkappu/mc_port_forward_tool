package dev.kifuko.mctransport.client;

import dev.kifuko.mctransport.net.TunnelBridge;
import dev.kifuko.mctransport.protocol.Frame;
import dev.kifuko.mctransport.protocol.FrameType;
import dev.kifuko.mctransport.protocol.ProtocolException;
import dev.kifuko.mctransport.stream.StreamRegistry;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntFunction;

public final class ClientTunnelSessionManager {

    private static final int DEFAULT_MAX_STREAMS = 64;
    private static final long DEFAULT_PING_INTERVAL_MILLIS = 15_000L;

    private final TunnelBridge bridge;
    private final ClientStreamFactory streamFactory;
    private final IntFunction<ClientListenerController> listenerFactory;
    private final Map<Integer, ClientTunnelSession> sessions = new ConcurrentHashMap<>();

    public ClientTunnelSessionManager(TunnelBridge bridge,
                                      ClientStreamFactory streamFactory,
                                      IntFunction<ClientListenerController> listenerFactory) {
        this.bridge = bridge;
        this.streamFactory = streamFactory;
        this.listenerFactory = listenerFactory;
    }

    public void handleInbound(Frame frame) {
        if (frame == null) throw new IllegalArgumentException("frame must not be null");
        int sessionId = frame.sessionId();
        if (sessionId < 1 || sessionId > 65535) {
            throw new ProtocolException("invalid route session id: " + sessionId);
        }
        if (frame.type() == FrameType.CONFIG_APPLY) {
            ClientTunnelSession session = sessions.computeIfAbsent(sessionId, this::newSession);
            session.handleInbound(frame);
            return;
        }
        ClientTunnelSession session = sessions.get(sessionId);
        if (session == null) {
            throw new ProtocolException("frame for unknown route session: " + sessionId);
        }
        session.handleInbound(frame);
        if (frame.type() == FrameType.CONFIG_CLEAR) {
            sessions.remove(sessionId);
        }
    }

    public void tick(long nowMillis) {
        for (ClientTunnelSession session : sessions.values()) {
            session.tick(nowMillis);
        }
    }

    public void closeAll() {
        for (ClientTunnelSession session : sessions.values()) {
            session.close();
        }
        sessions.clear();
    }

    public Optional<ClientTunnelSession> session(int sessionId) {
        return Optional.ofNullable(sessions.get(sessionId));
    }

    public Collection<ClientTunnelSession> sessions() {
        return new ArrayList<>(sessions.values());
    }

    public int sessionCount() {
        return sessions.size();
    }

    private ClientTunnelSession newSession(int sessionId) {
        ClientTunnelSession session = new ClientTunnelSession(sessionId, bridge,
                new StreamRegistry(DEFAULT_MAX_STREAMS, true),
                streamFactory,
                System.currentTimeMillis(),
                listenerFactory.apply(sessionId));
        session.setPingIntervalMillis(DEFAULT_PING_INTERVAL_MILLIS);
        return session;
    }
}
