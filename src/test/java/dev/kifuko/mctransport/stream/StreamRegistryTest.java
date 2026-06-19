package dev.kifuko.mctransport.stream;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StreamRegistryTest {

    @Test
    void clientSideAllocatesMonotonicPositiveIds() {
        StreamRegistry r = new StreamRegistry(8, true);
        int a = r.allocateClient();
        int b = r.allocateClient();
        int c = r.allocateClient();
        assertTrue(a > 0);
        assertTrue(b > a);
        assertTrue(c > b);
    }

    @Test
    void clientSideEnforcesMaxStreams() {
        StreamRegistry r = new StreamRegistry(2, true);
        r.allocateClient();
        r.allocateClient();
        assertThrows(IllegalStateException.class, r::allocateClient);
    }

    @Test
    void serverSideRegistersClientProvidedIds() {
        StreamRegistry r = new StreamRegistry(4, false);
        r.registerServer(11);
        r.registerServer(22);
        assertTrue(r.contains(11));
        assertTrue(r.contains(22));
        assertEquals(StreamState.OPEN_SENT, r.get(11).state());
    }

    @Test
    void serverSideRejectsDuplicateIds() {
        StreamRegistry r = new StreamRegistry(4, false);
        r.registerServer(5);
        assertThrows(IllegalStateException.class, () -> r.registerServer(5));
    }

    @Test
    void serverSideEnforcesMaxStreams() {
        StreamRegistry r = new StreamRegistry(1, false);
        r.registerServer(1);
        assertThrows(IllegalStateException.class, () -> r.registerServer(2));
    }

    @Test
    void serverSideRejectsNegativeIds() {
        StreamRegistry r = new StreamRegistry(2, false);
        assertThrows(IllegalArgumentException.class, () -> r.registerServer(-1));
    }

    @Test
    void clientSideCannotRegisterServerIds() {
        StreamRegistry r = new StreamRegistry(4, true);
        assertThrows(IllegalStateException.class, () -> r.registerServer(1));
    }

    @Test
    void serverSideCannotAllocateClientIds() {
        StreamRegistry r = new StreamRegistry(4, false);
        assertThrows(IllegalStateException.class, r::allocateClient);
    }

    @Test
    void setStateOnUnknownStreamRejected() {
        StreamRegistry r = new StreamRegistry(4, true);
        assertThrows(IllegalArgumentException.class,
                () -> r.setState(99, StreamState.OPEN));
    }

    @Test
    void removeFiresListenerAndIsIdempotent() {
        AtomicInteger calls = new AtomicInteger();
        StreamRegistry r = new StreamRegistry(4, true, id -> calls.incrementAndGet());
        int id = r.allocateClient();
        assertTrue(r.remove(id));
        assertEquals(1, calls.get());
        assertFalse(r.remove(id));
        assertEquals(1, calls.get(), "second remove must not double-fire");
        assertFalse(r.contains(id));
    }

    @Test
    void clearFiresListenerOncePerStream() {
        AtomicInteger calls = new AtomicInteger();
        StreamRegistry r = new StreamRegistry(8, true, id -> calls.incrementAndGet());
        r.allocateClient();
        r.allocateClient();
        r.allocateClient();
        r.clear();
        assertEquals(3, calls.get());
        assertEquals(0, r.size());
    }

    @Test
    void allocatedIdsAreUniqueEvenAfterRemoval() {
        StreamRegistry r = new StreamRegistry(16, true);
        int first = r.allocateClient();
        r.remove(first);
        int second = r.allocateClient();
        assertNotEquals(first, second);
    }
}