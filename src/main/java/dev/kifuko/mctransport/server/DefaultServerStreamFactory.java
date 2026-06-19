package dev.kifuko.mctransport.server;

import java.io.IOException;
import java.net.Socket;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;

/**
 * Default {@link ServerStreamFactory}: keeps an in-memory map of active
 * streams per session, and dials the fixed target via
 * {@link TargetTcpConnector} on {@code OPEN}.
 *
 * <p>The factory itself does NOT run network I/O on the caller thread;
 * callers (e.g. a future Fabric bridge) are expected to dispatch
 * {@link #dialAndAttach} to a transport executor. The connector's blocking
 * dial happens synchronously inside this method for MVP simplicity; the
 * caller dispatches off the Minecraft thread before invoking.</p>
 */
public final class DefaultServerStreamFactory implements ServerStreamFactory {

    private final TargetTcpConnector connector;
    private final int maxPayloadSize;
    private final int readChunkSize;
    private final ExecutorService io;
    private final Map<PlayerTunnelSession, Map<Integer, ServerStream>> streams = new HashMap<>();

    public DefaultServerStreamFactory(TargetTcpConnector connector, int maxPayloadSize,
                                      int readChunkSize, ExecutorService io) {
        this.connector = connector;
        this.maxPayloadSize = maxPayloadSize;
        this.readChunkSize = readChunkSize;
        this.io = io;
    }

    @Override
    public synchronized void dialAndAttach(PlayerTunnelSession session, int streamId) {
        Socket socket;
        try {
            socket = connector.connect();
        } catch (IOException e) {
            sendReset(session, streamId);
            return;
        }
        session.registry().registerServer(streamId);
        ServerStream stream = new ServerStream(
                session, streamId, socket,
                session.budget(), session.reservations(),
                PlayerTunnelSession.PROTOCOL_VERSION, maxPayloadSize);
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

    /** Removes a single stream from the registry without touching the socket. */
    public synchronized void forget(PlayerTunnelSession session, int streamId) {
        Map<Integer, ServerStream> map = streams.get(session);
        if (map != null) {
            map.remove(streamId);
        }
    }

    private void sendReset(PlayerTunnelSession session, int streamId) {
        session.bridge().send(dev.kifuko.mctransport.protocol.Frame.createTrusted(
                PlayerTunnelSession.PROTOCOL_VERSION, 0, streamId,
                dev.kifuko.mctransport.protocol.FrameType.RESET, (byte) 0, new byte[0]));
    }
}