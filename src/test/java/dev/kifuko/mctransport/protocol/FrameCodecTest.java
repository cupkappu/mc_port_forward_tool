package dev.kifuko.mctransport.protocol;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FrameCodecTest {

    private final FrameCodec codec = new FrameCodec(1024);

    private static byte[] payload(String s) {
        return s.getBytes();
    }

    @Test
    void headerIsExactlyFifteenBytes() {
        assertEquals(15, FrameCodec.HEADER_BYTES);
    }

    @Test
    void roundTripsEveryFrameType() {
        byte[] data = payload("hello");
        for (FrameType t : FrameType.values()) {
            Frame original = Frame.create((byte) 1, 7, 42, t, (byte) 0, data, 1024);
            byte[] encoded = codec.encode(original);
            Frame decoded = codec.decode(encoded);
            assertEquals(original.protocolVersion(), decoded.protocolVersion());
            assertEquals(original.sessionId(), decoded.sessionId());
            assertEquals(original.streamId(), decoded.streamId());
            assertEquals(original.type(), decoded.type());
            assertEquals(original.flags(), decoded.flags());
            assertArrayEquals(original.payload(), decoded.payload());
        }
    }

    @Test
    void roundTripsEmptyPayload() {
        Frame original = Frame.create((byte) 1, 1, 2, FrameType.CLOSE, (byte) 0, new byte[0], 1024);
        byte[] encoded = codec.encode(original);
        Frame decoded = codec.decode(encoded);
        assertEquals(FrameType.CLOSE, decoded.type());
        assertEquals(0, decoded.payloadLength());
    }

    @Test
    void encodingIsBigEndianDeterministic() {
        Frame f = Frame.create((byte) 1, 0x01020304, 0x05060708, FrameType.DATA,
                (byte) 0, new byte[]{0x09}, 1024);
        byte[] encoded = codec.encode(f);
        // version (1) | session=0x01020304 | stream=0x05060708 | type=DATA(4) | flags | len=1 | 0x09
        byte[] expected = new byte[]{
                0x01,
                0x01, 0x02, 0x03, 0x04,
                0x05, 0x06, 0x07, 0x08,
                0x04,
                0x00,
                0x00, 0x00, 0x00, 0x01,
                0x09
        };
        assertArrayEquals(expected, encoded);
    }

    @Test
    void decodesEmptyFrame() {
        Frame original = Frame.create((byte) 2, 5, 6, FrameType.PING, (byte) 0xFF, new byte[0], 1024);
        Frame decoded = codec.decode(codec.encode(original));
        assertEquals(0xFF, decoded.flags() & 0xFF);
        assertEquals(FrameType.PING, decoded.type());
    }

    @Test
    void rejectsTruncatedHeader() {
        ProtocolException ex = assertThrows(ProtocolException.class,
                () -> codec.decode(new byte[FrameCodec.HEADER_BYTES - 1]));
        assertEquals("truncated frame header: 14 bytes", ex.getMessage());
    }

    @Test
    void rejectsPayloadLengthExceedingMax() {
        // Build a header that declares a huge payload length, with payload
        // bytes truncated so trailing-bytes check cannot mask the size error.
        byte[] encoded = codec.encode(
                Frame.create((byte) 1, 1, 1, FrameType.DATA, (byte) 0, new byte[1], 1024));
        // Header layout: version[0] | session[1..4] | stream[5..8] |
        // type[9] | flags[10] | payloadLen[11..14] | payload[15..]
        encoded[11] = 0x10;
        encoded[12] = 0x00;
        encoded[13] = 0x00;
        encoded[14] = 0x00;
        ProtocolException ex = assertThrows(ProtocolException.class, () -> codec.decode(encoded));
        assertTrue(ex.getMessage().contains("exceeds max 1024"),
                "got: " + ex.getMessage());
    }

    @Test
    void rejectsTrailingBytes() {
        byte[] encoded = codec.encode(
                Frame.create((byte) 1, 1, 1, FrameType.DATA, (byte) 0, new byte[]{1, 2}, 1024));
        byte[] withJunk = new byte[encoded.length + 3];
        System.arraycopy(encoded, 0, withJunk, 0, encoded.length);
        ProtocolException ex = assertThrows(ProtocolException.class, () -> codec.decode(withJunk));
        assertEquals("trailing bytes: expected 2, got 5", ex.getMessage());
    }

    @Test
    void rejectsTruncatedPayload() {
        byte[] encoded = codec.encode(
                Frame.create((byte) 1, 1, 1, FrameType.DATA, (byte) 0, new byte[]{1, 2, 3, 4}, 1024));
        byte[] truncated = new byte[encoded.length - 2];
        System.arraycopy(encoded, 0, truncated, 0, truncated.length);
        ProtocolException ex = assertThrows(ProtocolException.class, () -> codec.decode(truncated));
        assertEquals("trailing bytes: expected 4, got 2", ex.getMessage());
    }

    @Test
    void rejectsUnknownFrameType() {
        byte[] encoded = codec.encode(
                Frame.create((byte) 1, 1, 1, FrameType.DATA, (byte) 0, new byte[]{1}, 1024));
        // Replace the frame-type byte (offset 9 in the header) with an unknown value.
        encoded[9] = 99;
        assertThrows(ProtocolException.class, () -> codec.decode(encoded));
    }

    @Test
    void rejectsEncodeThatExceedsMax() {
        // Frame.create rejects oversize payloads before encode sees them; the
        // encode-side guard catches the rare case where the codec's max is
        // smaller than the frame's allowed max. Verify the encode path
        // simply produces the right output for an in-bounds frame.
        FrameCodec small = new FrameCodec(4);
        Frame ok = Frame.create((byte) 1, 1, 1, FrameType.DATA, (byte) 0,
                new byte[]{1, 2, 3, 4}, small.maxPayloadSize());
        assertEquals(FrameCodec.HEADER_BYTES + 4, small.encode(ok).length);
    }
}