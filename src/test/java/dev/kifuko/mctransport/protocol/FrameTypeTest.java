package dev.kifuko.mctransport.protocol;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FrameTypeTest {

    @Test
    void allNineTypesAreDefined() {
        assertEquals(9, FrameType.values().length);
    }

    @Test
    void idsStartAtOneAndAreStable() {
        assertEquals(1, FrameType.AUTH.id());
        assertEquals(2, FrameType.AUTH_OK.id());
        assertEquals(3, FrameType.OPEN.id());
        assertEquals(4, FrameType.DATA.id());
        assertEquals(5, FrameType.CLOSE.id());
        assertEquals(6, FrameType.RESET.id());
        assertEquals(7, FrameType.PING.id());
        assertEquals(8, FrameType.PONG.id());
        assertEquals(9, FrameType.ERROR.id());
    }

    @Test
    void fromIdRoundTrip() {
        for (FrameType t : FrameType.values()) {
            assertSame(t, FrameType.fromId(t.id()));
        }
    }

    @Test
    void unknownIdThrows() {
        ProtocolException ex = assertThrows(ProtocolException.class,
                () -> FrameType.fromId(42));
        assertEquals("unknown frame type id: 42", ex.getMessage());
    }

    @Test
    void unknownIdIncludesZero() {
        assertThrows(ProtocolException.class, () -> FrameType.fromId(0));
    }
}