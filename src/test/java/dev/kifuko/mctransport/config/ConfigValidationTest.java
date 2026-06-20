package dev.kifuko.mctransport.config;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Validation tests for {@link ServerConfig} in the server-pushed route
 * mode. Global target and PSK fields from the previous design are gone;
 * routes are configured per-player.
 */
class ConfigValidationTest {

    private static final UUID UUID_A =
            UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final UUID UUID_B =
            UUID.fromString("22222222-3333-4444-5555-666666666666");
    private static final RouteConfig ROUTE_A = new RouteConfig(UUID_A,
            "Steve", 25580, "127.0.0.1", 10000);

    @Test
    void serverConfigAcceptsMvpDefaults() {
        ServerConfig cfg = new ServerConfig(true, "mctransport:main",
                List.of(ROUTE_A), "info");
        assertTrue(cfg.isEnabled());
        assertEquals("mctransport:main", cfg.getChannelName());
        assertEquals(1, cfg.getRoutes().size());
        assertSame(ROUTE_A, cfg.routeFor(UUID_A));
    }

    @Test
    void serverConfigAcceptsEmptyRouteList() {
        ServerConfig cfg = new ServerConfig(true, "mctransport:main",
                List.of(), "info");
        assertEquals(0, cfg.getRoutes().size());
        assertNull(cfg.routeFor(UUID_A));
    }

    @Test
    void serverConfigRejectsDuplicateRouteUuids() {
        RouteConfig a1 = new RouteConfig(UUID_A, "Steve", 25580, "127.0.0.1", 10000);
        RouteConfig a2 = new RouteConfig(UUID_A, "Steve2", 25581, "127.0.0.1", 10001);
        assertThrows(IllegalArgumentException.class,
                () -> new ServerConfig(true, "mctransport:main",
                        List.of(a1, a2), "info"));
    }

    @Test
    void serverConfigRejectsBlankChannelWhenEnabled() {
        assertThrows(IllegalArgumentException.class,
                () -> new ServerConfig(true, " ", List.of(), "info"));
    }

    @Test
    void serverConfigRejectsBlankLogLevelWhenEnabled() {
        assertThrows(IllegalArgumentException.class,
                () -> new ServerConfig(true, "mctransport:main", List.of(), ""));
    }

    @Test
    void serverConfigRouteForReturnsConfiguredRoute() {
        RouteConfig b = new RouteConfig(UUID_B, "Alex", 25581, "10.0.0.1", 20000);
        ServerConfig cfg = new ServerConfig(true, "mctransport:main",
                List.of(ROUTE_A, b), "info");
        assertSame(ROUTE_A, cfg.routeFor(UUID_A));
        assertSame(b, cfg.routeFor(UUID_B));
        assertNull(cfg.routeFor(UUID.randomUUID()));
    }

    @Test
    void serverConfigRoutesListIsImmutable() {
        ServerConfig cfg = new ServerConfig(true, "mctransport:main",
                List.of(ROUTE_A), "info");
        assertThrows(UnsupportedOperationException.class,
                () -> cfg.getRoutes().clear());
    }

    @Test
    void serverConfigWithRouteReplacesExisting() {
        RouteConfig replacement = new RouteConfig(UUID_A, "Steve2", 25582,
                "10.0.0.1", 20000);
        ServerConfig updated = new ServerConfig(true, "mctransport:main",
                List.of(ROUTE_A), "info").withRoute(replacement);
        assertSame(replacement, updated.routeFor(UUID_A));
    }

    @Test
    void serverConfigWithoutRouteRemovesByUuid() {
        ServerConfig cfg = new ServerConfig(true, "mctransport:main",
                List.of(ROUTE_A), "info");
        ServerConfig reduced = cfg.withoutRoute(UUID_A);
        assertNull(reduced.routeFor(UUID_A));
        assertEquals(0, reduced.getRoutes().size());
    }

    @Test
    void serverConfigDisabledSkipsStrictValidation() {
        ServerConfig cfg = ServerConfig.disabled();
        assertEquals(false, cfg.isEnabled());
    }

    // The legacy server config tests (target_host / target_port /
    // allowed_players / psk) are intentionally dropped: those fields no
    // longer exist on the route-driven ServerConfig. The new model is
    // covered by the tests above and by the {@link RouteConfig} tests.

}
