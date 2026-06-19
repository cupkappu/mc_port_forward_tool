package dev.kifuko.mctransport.client;

/**
 * Strategy the client session uses to materialize {@link ClientStream}
 * objects when a new local TCP socket is accepted.
 */
public interface ClientStreamFactory {

    /**
     * Creates an unattached {@link ClientStream}. The stream will be wired
     * up by the local TCP listener later via
     * {@link ClientStream#attach(java.net.Socket, byte[])}.
     */
    ClientStream create(ClientTunnelSession session, int streamId);
}