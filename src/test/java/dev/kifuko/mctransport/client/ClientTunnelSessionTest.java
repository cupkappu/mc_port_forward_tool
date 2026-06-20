package dev.kifuko.mctransport.client;

import dev.kifuko.mctransport.net.FakeTunnelBridge;
import dev.kifuko.mctransport.protocol.Frame;
import dev.kifuko.mctransport.protocol.FrameType;
import dev.kifuko.mctransport.protocol.ProtocolException;
import dev.kifuko.mctransport.protocol.RouteControlPayload;
import dev.kifuko.mctransport.stream.StreamRegistry;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientTunnelSessionTest {

    private ClientTunnelSession buildSession(FakeTunnelBridge bridge,
                                             ClientStreamFactory factory,
                                             FakeListenerController listener) {
        StreamRegistry registry = new StreamRegistry(8, true);
        return new ClientTunnelSession(bridge, registry, factory, 0L, listener);
    }

    private FakeTunnelBridge buildBridge() {
        FakeTunnelBridge b = new FakeTunnelBridge();
        b.setReceiver(frame -> { });
        return b;
    }

    private Frame configApply(int port) {
        return Frame.createTrusted(ClientTunnelSession.PROTOCOL_VERSION,
                0, 0, FrameType.CONFIG_APPLY, (byte) 0,
                RouteControlPayload.encodeApply("127.0.0.1", port));
    }

    @Test
    void configApplyStartsListenerAndSendsAck() {
        FakeTunnelBridge b = buildBridge();
        FakeListenerController listener = new FakeListenerController();
        ClientTunnelSession s = buildSession(b, (sess, id) -> null, listener);
        s.handleInbound(configApply(25580));

        assertTrue(s.isAuthenticated());
        assertEquals("127.0.0.1", listener.host);
        assertEquals(25580, listener.port);
        assertEquals(1, b.sentFrames().size());
        Frame ack = b.sentFrames().get(0);
        assertEquals(FrameType.CONFIG_ACK, ack.type());
        assertTrue(RouteControlPayload.decodeAck(ack.payload()).ok());
    }

    @Test
    void failedConfigApplySendsNegativeAck() {
        FakeTunnelBridge b = buildBridge();
        FakeListenerController listener = new FakeListenerController();
        listener.failApply = true;
        ClientTunnelSession s = buildSession(b, (sess, id) -> null, listener);
        s.handleInbound(configApply(25580));

        assertFalse(s.isAuthenticated());
        assertEquals(1, b.sentFrames().size());
        RouteControlPayload.Ack ack = RouteControlPayload.decodeAck(
                b.sentFrames().get(0).payload());
        assertFalse(ack.ok());
        assertTrue(ack.message().contains("failed to bind"));
    }

    @Test
    void openLocalStreamBeforeConfigApplyFails() {
        FakeTunnelBridge b = buildBridge();
        ClientTunnelSession s = buildSession(b, (sess, id) -> null,
                new FakeListenerController());
        assertThrows(IllegalStateException.class, s::openLocalStream);
    }

    @Test
    void openLocalStreamAfterConfigApplyAllocatesAndSendsOpen() {
        FakeTunnelBridge b = buildBridge();
        FakeFactory factory = new FakeFactory();
        ClientTunnelSession s = buildSession(b, factory, new FakeListenerController());
        s.handleInbound(configApply(25580));
        b.clearSent();

        ClientStream stream = s.openLocalStream();
        assertSame(factory.created.get(0), stream);
        assertEquals(1, b.sentFrames().size());
        assertEquals(FrameType.OPEN, b.sentFrames().get(0).type());
        assertEquals(stream.streamId(), b.sentFrames().get(0).streamId());
    }

    @Test
    void configClearStopsListenerClosesStreamsAndSendsAck() {
        FakeTunnelBridge b = buildBridge();
        FakeFactory factory = new FakeFactory();
        FakeListenerController listener = new FakeListenerController();
        ClientTunnelSession s = buildSession(b, factory, listener);
        s.handleInbound(configApply(25580));
        b.clearSent();
        s.openLocalStream();

        s.handleInbound(Frame.createTrusted(ClientTunnelSession.PROTOCOL_VERSION,
                0, 0, FrameType.CONFIG_CLEAR, (byte) 0, new byte[0]));

        assertFalse(s.isAuthenticated());
        assertFalse(listener.isListening());
        assertEquals(0, s.streams().size());
        assertEquals(0, s.registry().size());
        assertEquals(FrameType.CONFIG_ACK,
                b.sentFrames().get(b.sentFrames().size() - 1).type());
    }

    @Test
    void pingBeforeConfigApplyIsRejected() {
        FakeTunnelBridge b = buildBridge();
        ClientTunnelSession s = buildSession(b, (sess, id) -> null,
                new FakeListenerController());
        Frame ping = Frame.createTrusted(ClientTunnelSession.PROTOCOL_VERSION,
                0, 0, FrameType.PING, (byte) 0, new byte[0]);
        assertThrows(ProtocolException.class, () -> s.handleInbound(ping));
    }

    @Test
    void pingAfterConfigApplyTriggersPong() {
        FakeTunnelBridge b = buildBridge();
        ClientTunnelSession s = buildSession(b, (sess, id) -> null,
                new FakeListenerController());
        s.handleInbound(configApply(25580));
        b.clearSent();
        s.handleInbound(Frame.createTrusted(ClientTunnelSession.PROTOCOL_VERSION,
                0, 7, FrameType.PING, (byte) 0, new byte[0]));
        assertEquals(1, b.sentFrames().size());
        assertEquals(FrameType.PONG, b.sentFrames().get(0).type());
        assertEquals(7, b.sentFrames().get(0).streamId());
    }

    @Test
    void dataForUnknownStreamSendsReset() {
        FakeTunnelBridge b = buildBridge();
        ClientTunnelSession s = buildSession(b, (sess, id) -> null,
                new FakeListenerController());
        s.handleInbound(configApply(25580));
        b.clearSent();
        s.handleInbound(Frame.createTrusted(ClientTunnelSession.PROTOCOL_VERSION,
                0, 99, FrameType.DATA, (byte) 0, "hi".getBytes()));
        assertEquals(1, b.sentFrames().size());
        assertEquals(FrameType.RESET, b.sentFrames().get(0).type());
        assertEquals(99, b.sentFrames().get(0).streamId());
    }

    @Test
    void tickBeforeConfigApplyDoesNothing() {
        FakeTunnelBridge b = buildBridge();
        ClientTunnelSession s = buildSession(b, (sess, id) -> null,
                new FakeListenerController());
        s.setPingIntervalMillis(10);
        s.tick(10_000L);
        assertEquals(0, b.sentFrames().size());
    }

    @Test
    void tickAfterConfigApplySendsPingAfterInterval() {
        FakeTunnelBridge b = buildBridge();
        ClientTunnelSession s = buildSession(b, (sess, id) -> null,
                new FakeListenerController());
        s.setPingIntervalMillis(100);
        s.handleInbound(configApply(25580));
        b.clearSent();
        s.tick(50L);
        assertEquals(0, b.sentFrames().size());
        s.tick(200L);
        assertEquals(1, b.sentFrames().size());
        assertEquals(FrameType.PING, b.sentFrames().get(0).type());
    }

    @Test
    void closeClearsStreamsAndRegistry() {
        FakeTunnelBridge b = buildBridge();
        FakeFactory factory = new FakeFactory();
        ClientTunnelSession s = buildSession(b, factory, new FakeListenerController());
        s.handleInbound(configApply(25580));
        s.openLocalStream();
        s.openLocalStream();
        assertEquals(2, s.streams().size());
        assertFalse(s.registry().size() == 0);
        s.close();
        assertEquals(0, s.streams().size());
        assertEquals(0, s.registry().size());
        assertFalse(s.isAuthenticated());
    }

    private static final class FakeListenerController implements ClientListenerController {
        String host;
        int port;
        boolean listening;
        boolean failApply;

        @Override
        public void apply(String listenHost, int listenPort) throws IOException {
            if (failApply) {
                throw new IOException("bind denied");
            }
            this.host = listenHost;
            this.port = listenPort;
            this.listening = true;
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

    private static final class FakeFactory implements ClientStreamFactory {
        final java.util.List<ClientStream> created = new java.util.ArrayList<>();
        @Override public ClientStream create(ClientTunnelSession session, int streamId) {
            ClientStream s = new DirectClientStream(session, streamId,
                    new dev.kifuko.mctransport.buffer.BufferBudget(1024, 8192L),
                    new dev.kifuko.mctransport.buffer.ReservationState(),
                    1024);
            created.add(s);
            return s;
        }
    }
}
