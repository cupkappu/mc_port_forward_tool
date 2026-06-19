package dev.kifuko.mctransport.net;

import dev.kifuko.mctransport.protocol.Frame;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FakeTunnelBridgeTest {

    @Test
    void recordsSentFrames() {
        FakeTunnelBridge b = new FakeTunnelBridge();
        Frame f = Frame.create((byte) 1, 1, 1, dev.kifuko.mctransport.protocol.FrameType.PING,
                (byte) 0, new byte[]{1, 2}, 1024);
        b.send(f);
        assertEquals(1, b.sentFrames().size());
        assertSame(f, b.sentFrames().get(0));
    }

    @Test
    void injectedFrameGoesToReceiver() {
        FakeTunnelBridge b = new FakeTunnelBridge();
        Frame[] captured = new Frame[1];
        b.setReceiver(frame -> captured[0] = frame);
        Frame f = Frame.create((byte) 1, 1, 1, dev.kifuko.mctransport.protocol.FrameType.PONG,
                (byte) 0, new byte[0], 1024);
        b.injectInbound(f);
        assertSame(f, captured[0]);
    }

    @Test
    void injectingWithoutReceiverIsDropped() {
        FakeTunnelBridge b = new FakeTunnelBridge();
        Frame f = Frame.create((byte) 1, 1, 1, dev.kifuko.mctransport.protocol.FrameType.PING,
                (byte) 0, new byte[0], 1024);
        // Pre-receiver frames are silently dropped to mirror real Minecraft
        // behaviour; nothing throws.
        b.injectInbound(f);
    }

    @Test
    void closedBridgeRejectsSend() {
        FakeTunnelBridge b = new FakeTunnelBridge();
        b.close();
        Frame f = Frame.create((byte) 1, 1, 1, dev.kifuko.mctransport.protocol.FrameType.PING,
                (byte) 0, new byte[0], 1024);
        assertThrows(IllegalStateException.class, () -> b.send(f));
    }
}