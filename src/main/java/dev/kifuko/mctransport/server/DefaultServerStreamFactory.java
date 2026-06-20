package dev.kifuko.mctransport.server;

import dev.kifuko.mctransport.kcp.KcpConfig;
import dev.kifuko.mctransport.protocol.StreamMode;

import java.io.IOException;
import java.net.Socket;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;

/**
 * Default {@link ServerStreamFactory}: keeps an in-memory map of active
 * streams per session and dials the route's target via
 * {@link TargetTcpConnector} on {@code OPEN}.
 *
 * <p>Supports both {@link StreamMode#DIRECT} and {@link StreamMode#KCP}
 * stream creation.</p>
 */
public final class DefaultServerStreamFactory implements ServerStreamFactory {

    private final TargetTcpConnector connector;
    private final int maxPayloadSize;
    private final int readChunkSize;
    private final ExecutorService io;
    private final KcpConfig kcpConfig;
    private final Map<PlayerTunnelSession, Map<Integer, ServerStream>> streams = new HashMap<>();

    /** Full constructor with KCP support. */
    public DefaultServerStreamFactory(TargetTcpConnector connector, int maxPayloadSize,
                                      int readChunkSize, ExecutorService io,
                                      KcpConfig kcpConfig) {
        this.connector = connector;
        this.maxPayloadSize = maxPayloadSize;
        this.readChunkSize = readChunkSize;
        this.io = io;
        this.kcpConfig = kcpConfig != null ? kcpConfig : new KcpConfig();
    }

    /** Backward-compat constructor. KCP mode not available. */
    public DefaultServerStreamFactory(TargetTcpConnector connector, int maxPayloadSize,
                                      int readChunkSize, ExecutorService io) {
        this(connector, maxPayloadSize, readChunkSize, io, new KcpConfig());
    }

    @Override
    public synchronized void dialAndAttach(PlayerTunnelSession session, int streamId,
                                           StreamMode mode) {
        var route = session.route();
        if (route == null) {
            sendReset(session, streamId);
            return;
        }
        Socket socket;
        try {
            socket = connector.connect(route.getTargetHost(), route.getTargetPort());
        } catch (IOException e) {
            sendReset(session, streamId);
            return;
        }
        session.registry().registerServer(streamId);
        ServerStream stream = switch (mode) {
            case KCP -> new KcpServerStream(session, streamId, socket,
                    session.budget(), session.reservations(),
                    kcpConfig, PlayerTunnelSession.PROTOCOL_VERSION);
            case DIRECT -> new DirectServerStream(session, streamId, socket,
                    session.budget(), session.reservations(),
                    PlayerTunnelSession.PROTOCOL_VERSION, maxPayloadSize);
        };
        streams.computeIfAbsent(session, s -> new HashMap<>()).put(streamId, stream);
        if (io != null) {
            ServerStreamReader reader = new ServerStreamReader(stream, readChunkSize, io);
            reader.onClose(() -> forget(session, streamId));
            reader.start();
        }
    }

    @Override
    public synchronized ServerStream find(PlayerTunnelSession session, int streamId) {
        Map<Integer, ServerStream> map = streams.get(session);
        return map == null ? null : map.get(streamId);
    }

    @Override
    public synchronized void closeAll(PlayerTunnelSession session) {
        Map<Integer, ServerStream> map = streams.remove(session);
        if (map == null) {
            return;
        }
        for (ServerStream stream : map.values()) {
            stream.closeReset();
        }
        map.clear();
    }

    public synchronized void forget(PlayerTunnelSession session, int streamId) {
        Map<Integer, ServerStream> map = streams.get(session);
        if (map != null) {
            map.remove(streamId);
        }
    }

    private void sendReset(PlayerTunnelSession session, int streamId) {
        session.bridge().send(dev.kifuko.mctransport.protocol.Frame.createTrusted(
                PlayerTunnelSession.PROTOCOL_VERSION, session.sessionId(), streamId,
                dev.kifuko.mctransport.protocol.FrameType.RESET, (byte) 0, new byte[0]));
    }
}
