package dev.kifuko.mctransport.config;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RouteConfigTest {

    private static final UUID UUID_A =
            UUID.fromString("11111111-2222-3333-4444-555555555555");

    @Test
    void routeRejectsBlankPlayerName() {
        assertThrows(IllegalArgumentException.class,
                () -> new RouteConfig(UUID_A, " ", 25580, "127.0.0.1", 10000));
    }

    @Test
    void routeRejectsInvalidListenPort() {
        assertThrows(IllegalArgumentException.class,
                () -> new RouteConfig(UUID_A, "Steve", 0, "127.0.0.1", 10000));
        assertThrows(IllegalArgumentException.class,
                () -> new RouteConfig(UUID_A, "Steve", 65536, "127.0.0.1", 10000));
    }

    @Test
    void routeRejectsInvalidTargetPort() {
        assertThrows(IllegalArgumentException.class,
                () -> new RouteConfig(UUID_A, "Steve", 25580, "127.0.0.1", 0));
        assertThrows(IllegalArgumentException.class,
                () -> new RouteConfig(UUID_A, "Steve", 25580, "127.0.0.1", 65536));
    }

    @Test
    void routeRejectsBlankTargetHost() {
        assertThrows(IllegalArgumentException.class,
                () -> new RouteConfig(UUID_A, "Steve", 25580, " ", 10000));
    }

    @Test
    void routeRejectsNullUuid() {
        assertThrows(IllegalArgumentException.class,
                () -> new RouteConfig(null, "Steve", 25580, "127.0.0.1", 10000));
    }

    @Test
    void routeNormalizesListenHostToLoopback() {
        RouteConfig r = new RouteConfig(UUID_A, "Steve", 25580, "127.0.0.1", 10000);
        assertEquals("127.0.0.1", r.getListenHost());
    }

    @Test
    void routeStoresUuidNameAndTarget() {
        RouteConfig r = new RouteConfig(UUID_A, " Steve ", 25580,
                " 127.0.0.1 ", 10000);
        assertEquals(UUID_A, r.getPlayerUuid());
        assertEquals("Steve", r.getPlayerName());
        assertEquals("127.0.0.1", r.getListenHost());
        assertEquals(25580, r.getListenPort());
        assertEquals("127.0.0.1", r.getTargetHost());
        assertEquals(10000, r.getTargetPort());
    }
}