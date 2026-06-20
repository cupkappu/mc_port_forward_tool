package dev.kifuko.mctransport.client;

import dev.kifuko.mctransport.buffer.BufferBudget;
import dev.kifuko.mctransport.buffer.ReservationState;
import dev.kifuko.mctransport.net.FakeTunnelBridge;
import dev.kifuko.mctransport.protocol.Frame;
import dev.kifuko.mctransport.protocol.FrameType;
import dev.kifuko.mctransport.protocol.RouteControlPayload;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ClientTunnelSessionManagerTest {

    @Test
    void configApplyCreatesSeparateSessionsBySessionId() {
        FakeTunnelBridge bridge = new FakeTunnelBridge();
        ClientTunnelSessionManager manager = new ClientTunnelSessionManager(
                bridge, new RecordingFactory(), sessionId -> new RecordingListenerController());

        manager.handleInbound(apply(25580));
        manager.handleInbound(apply(25581));

        assertTrue(manager.session(25580).isPresent());
        assertTrue(manager.session(25581).isPresent());
        assertEquals(2, manager.sessionCount());
    }

    @Test
    void configClearRemovesOnlyOneSession() {
        FakeTunnelBridge bridge = new FakeTunnelBridge();
        ClientTunnelSessionManager manager = new ClientTunnelSessionManager(
                bridge, new RecordingFactory(), sessionId -> new RecordingListenerController());
        manager.handleInbound(apply(25580));
        manager.handleInbound(apply(25581));

        manager.handleInbound(Frame.createTrusted(ClientTunnelSession.PROTOCOL_VERSION,
                25580, 0, FrameType.CONFIG_CLEAR, (byte) 0, new byte[0]));

        assertFalse(manager.session(25580).isPresent());
        assertTrue(manager.session(25581).isPresent());
    }

    @Test
    void tickTicksEverySession() {
        FakeTunnelBridge bridge = new FakeTunnelBridge();
        ClientTunnelSessionManager manager = new ClientTunnelSessionManager(
                bridge, new RecordingFactory(), sessionId -> new RecordingListenerController());
        manager.handleInbound(apply(25580));
        manager.handleInbound(apply(25581));
        manager.sessions().forEach(s -> s.setPingIntervalMillis(0));
        bridge.clearSent();

        manager.tick(System.currentTimeMillis());

        assertEquals(List.of(25580, 25581), bridge.sentFrames().stream()
                .map(Frame::sessionId).sorted().toList());
    }

    @Test
    void defaultPingIntervalDoesNotPingEveryTick() {
        FakeTunnelBridge bridge = new FakeTunnelBridge();
        ClientTunnelSessionManager manager = new ClientTunnelSessionManager(
                bridge, new RecordingFactory(), sessionId -> new RecordingListenerController());
        manager.handleInbound(apply(25580));
        bridge.clearSent();

        manager.tick(System.currentTimeMillis());

        assertTrue(bridge.sentFrames().isEmpty());
    }

    @Test
    void unknownSessionIdBeforeConfigApplyThrows() {
        FakeTunnelBridge bridge = new FakeTunnelBridge();
        ClientTunnelSessionManager manager = new ClientTunnelSessionManager(
                bridge, new RecordingFactory(), sessionId -> new RecordingListenerController());

        assertThrows(dev.kifuko.mctransport.protocol.ProtocolException.class, () ->
                manager.handleInbound(Frame.createTrusted(ClientTunnelSession.PROTOCOL_VERSION,
                        25580, 0, FrameType.PING, (byte) 0, new byte[0])));
    }

    private static Frame apply(int port) {
        return Frame.createTrusted(ClientTunnelSession.PROTOCOL_VERSION,
                port, 0, FrameType.CONFIG_APPLY, (byte) 0,
                RouteControlPayload.encodeApply("127.0.0.1", port));
    }

    private static final class RecordingFactory implements ClientStreamFactory {
        @Override
        public ClientStream create(ClientTunnelSession session, int streamId,
                                   dev.kifuko.mctransport.protocol.StreamMode mode) {
            return new DirectClientStream(session, streamId,
                    new BufferBudget(1024, 8192L), new ReservationState(), 1024);
        }
    }

    private static final class RecordingListenerController
            implements ClientListenerController {
        private boolean listening;

        @Override public void apply(String listenHost, int listenPort) { listening = true; }
        @Override public void clear() { listening = false; }
        @Override public boolean isListening() { return listening; }
    }
}
