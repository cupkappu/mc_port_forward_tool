package dev.kifuko.mctransport.server;

/** No-op factory used by tests that do not exercise the dial flow. */
public final class NoopServerStreamFactoryForTest implements ServerStreamFactory {
    @Override public void dialAndAttach(PlayerTunnelSession session, int streamId) { }
    @Override public ServerStream find(PlayerTunnelSession session, int streamId) { return null; }
    @Override public void closeAll(PlayerTunnelSession session) { }
}