package dev.kifuko.mctransport.protocol;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FrameTest {

    private static final int MAX_PAYLOAD = 1024;

    @Test
    void storesAllHeaderFieldsAndPayload() {
        byte[] payload = new byte[]{1, 2, 3};
        Frame f = Frame.create((byte) 1, 7, 42, FrameType.DATA, (byte) 0, payload, MAX_PAYLOAD);
        assertEquals(1, f.protocolVersion());
        assertEquals(7, f.sessionId());
        assertEquals(42, f.streamId());
        assertEquals(FrameType.DATA, f.type());
        assertEquals(0, f.flags());
        assertArrayEquals(new byte[]{1, 2, 3}, f.payload());
    }

    @Test
    void payloadIsCopiedOnConstruction() {
        byte[] payload = new byte[]{1, 2, 3};
        Frame f = Frame.create((byte) 1, 1, 1, FrameType.DATA, (byte) 0, payload, MAX_PAYLOAD);
        payload[0] = 99;
        assertEquals(1, f.payload()[0], "mutation of input must not affect frame");
    }

    @Test
    void payloadIsCopiedOnRead() {
        Frame f = Frame.create((byte) 1, 1, 1, FrameType.DATA, (byte) 0, new byte[]{1}, MAX_PAYLOAD);
        byte[] a = f.payload();
        byte[] b = f.payload();
        assertNotSame(a, b);
        a[0] = 99;
        assertEquals(1, b[0]);
    }

    @Test
    void nullPayloadBecomesEmpty() {
        Frame f = Frame.create((byte) 1, 1, 1, FrameType.CLOSE, (byte) 0, null, MAX_PAYLOAD);
        assertEquals(0, f.payloadLength());
    }

    @Test
    void negativeStreamIdRejected() {
        ProtocolException ex = assertThrows(ProtocolException.class,
                () -> Frame.create((byte) 1, 1, -1, FrameType.DATA, (byte) 0, new byte[0], MAX_PAYLOAD));
        assertEquals("stream id must be non-negative, got: -1", ex.getMessage());
    }

    @Test
    void zeroStreamIdAccepted() {
        Frame f = Frame.create((byte) 1, 1, 0, FrameType.AUTH, (byte) 0, new byte[0], MAX_PAYLOAD);
        assertEquals(0, f.streamId());
    }

    @Test
    void payloadLargerThanMaxRejected() {
        byte[] tooBig = new byte[MAX_PAYLOAD + 1];
        ProtocolException ex = assertThrows(ProtocolException.class,
                () -> Frame.create((byte) 1, 1, 1, FrameType.DATA, (byte) 0, tooBig, MAX_PAYLOAD));
        assertEquals("payload length 1025 exceeds max 1024", ex.getMessage());
    }

    @Test
    void payloadAtMaxAccepted() {
        byte[] exactly = new byte[MAX_PAYLOAD];
        Frame f = Frame.create((byte) 1, 1, 1, FrameType.DATA, (byte) 0, exactly, MAX_PAYLOAD);
        assertEquals(MAX_PAYLOAD, f.payloadLength());
    }

    @Test
    void nullFrameTypeRejected() {
        ProtocolException ex = assertThrows(ProtocolException.class,
                () -> Frame.create((byte) 1, 1, 1, null, (byte) 0, new byte[0], MAX_PAYLOAD));
        assertEquals("frame type must not be null", ex.getMessage());
    }
}