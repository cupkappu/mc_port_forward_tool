package dev.kifuko.mctransport.buffer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BufferBudgetTest {

    @Test
    void reservesBytesWithinLimits() {
        BufferBudget b = new BufferBudget(1024, 4096);
        ReservationState s = new ReservationState();
        b.reserve(1, 100, s);
        b.reserve(1, 200, s);
        assertEquals(300L, s.reservedFor(1));
        assertEquals(300L, b.globalReserved());
    }

    @Test
    void rejectsReservationExceedingPerStreamLimit() {
        BufferBudget b = new BufferBudget(100, 1000);
        ReservationState s = new ReservationState();
        b.reserve(1, 60, s);
        assertThrows(IllegalStateException.class, () -> b.reserve(1, 50, s));
        assertEquals(60L, s.reservedFor(1), "failed reserve must not be applied");
    }

    @Test
    void rejectsReservationExceedingGlobalLimit() {
        BufferBudget b = new BufferBudget(1024, 200);
        ReservationState s = new ReservationState();
        b.reserve(1, 150, s);
        assertThrows(IllegalStateException.class, () -> b.reserve(2, 60, s));
    }

    @Test
    void releaseDecreasesCounters() {
        BufferBudget b = new BufferBudget(1024, 4096);
        ReservationState s = new ReservationState();
        b.reserve(1, 100, s);
        assertTrue(b.release(1, 40, s));
        assertEquals(60L, s.reservedFor(1));
        assertEquals(60L, b.globalReserved());
    }

    @Test
    void releaseIsIdempotent() {
        BufferBudget b = new BufferBudget(1024, 4096);
        ReservationState s = new ReservationState();
        b.reserve(1, 100, s);
        assertTrue(b.release(1, 100, s));
        assertFalse(b.release(1, 100, s));
        assertFalse(b.release(1, 100, s));
        assertEquals(0L, s.reservedFor(1));
        assertEquals(0L, b.globalReserved());
    }

    @Test
    void releaseClampedToReservation() {
        BufferBudget b = new BufferBudget(1024, 4096);
        ReservationState s = new ReservationState();
        b.reserve(1, 50, s);
        // Releasing more than was reserved is silently capped.
        assertTrue(b.release(1, 999, s));
        assertEquals(0L, s.reservedFor(1));
        assertEquals(0L, b.globalReserved());
    }

    @Test
    void releaseAllClearsStream() {
        BufferBudget b = new BufferBudget(1024, 4096);
        ReservationState s = new ReservationState();
        b.reserve(1, 100, s);
        b.reserve(1, 50, s);
        b.releaseAll(1, s);
        assertEquals(0L, s.reservedFor(1));
        assertEquals(0L, b.globalReserved());
    }

    @Test
    void releaseAllIsIdempotent() {
        BufferBudget b = new BufferBudget(1024, 4096);
        ReservationState s = new ReservationState();
        b.releaseAll(1, s);
        b.reserve(1, 100, s);
        b.releaseAll(1, s);
        b.releaseAll(1, s);
        assertEquals(0L, b.globalReserved());
    }

    @Test
    void multipleStreamsShareGlobalLimit() {
        BufferBudget b = new BufferBudget(1024, 500);
        ReservationState s = new ReservationState();
        b.reserve(1, 200, s);
        b.reserve(2, 200, s);
        assertThrows(IllegalStateException.class, () -> b.reserve(3, 200, s));
        b.release(1, 200, s);
        b.reserve(3, 200, s);
    }

    @Test
    void rejectsNegativeBytes() {
        BufferBudget b = new BufferBudget(1024, 4096);
        ReservationState s = new ReservationState();
        assertThrows(IllegalArgumentException.class, () -> b.reserve(1, -1, s));
    }
}