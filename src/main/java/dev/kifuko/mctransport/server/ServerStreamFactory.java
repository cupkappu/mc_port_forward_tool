package dev.kifuko.mctransport.server;

import dev.kifuko.mctransport.protocol.StreamMode;

/**
 * Strategy the server uses to create and look up per-stream objects.
 *
 * <p>Splitting this out keeps {@link PlayerTunnelSession} free of socket
 * dependencies and lets tests substitute an in-memory factory.</p>
 */
public interface ServerStreamFactory {

    /**
     * Dials the fixed target for {@code streamId} and, on success, attaches
     * a {@link ServerStream} that will receive subsequent inbound frames for
     * that ID. On failure, the factory must send {@code RESET} (or
     * {@code ERROR}) and remove the registry entry itself.
     */
    void dialAndAttach(PlayerTunnelSession session, int streamId, StreamMode mode);

    /** Returns the active stream for {@code streamId}, or null if none. */
    ServerStream find(PlayerTunnelSession session, int streamId);

    /** Closes every stream owned by {@code session}. */
    void closeAll(PlayerTunnelSession session);
}