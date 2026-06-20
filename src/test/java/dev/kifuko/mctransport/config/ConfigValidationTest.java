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
    void serverConfigAcceptsSameUuidWithDifferentListenPorts() {
        RouteConfig routeA = new RouteConfig(UUID_A, "Steve", 25580, "127.0.0.1", 10000);
        RouteConfig routeB = new RouteConfig(UUID_A, "Steve", 25581, "127.0.0.1", 10001);

        ServerConfig cfg = new ServerConfig(true, "mctransport:main",
                List.of(routeA, routeB), "info");

        assertEquals(List.of(routeA, routeB), cfg.routesFor(UUID_A));
        assertSame(routeA, cfg.routeFor(UUID_A, 25580));
        assertSame(routeB, cfg.routeFor(UUID_A, 25581));
    }

    @Test
    void serverConfigRejectsDuplicateUuidAndListenPort() {
        RouteConfig routeA = new RouteConfig(UUID_A, "Steve", 25580, "127.0.0.1", 10000);
        RouteConfig duplicate = new RouteConfig(UUID_A, "Steve2", 25580, "127.0.0.1", 10001);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> new ServerConfig(true, "mctransport:main",
                        List.of(routeA, duplicate), "info"));

        assertTrue(ex.getMessage().contains("duplicate route entry"));
        assertTrue(ex.getMessage().contains(UUID_A.toString()));
        assertTrue(ex.getMessage().contains("25580"));
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
    void serverConfigRouteForByUuidReturnsSingleRoute() {
        RouteConfig b = new RouteConfig(UUID_B, "Alex", 25581, "10.0.0.1", 20000);
        ServerConfig cfg = new ServerConfig(true, "mctransport:main",
                List.of(ROUTE_A, b), "info");
        assertSame(ROUTE_A, cfg.routeFor(UUID_A));
        assertSame(b, cfg.routeFor(UUID_B));
        assertNull(cfg.routeFor(UUID.randomUUID()));
    }

    @Test
    void serverConfigRouteForByUuidReturnsNullWhenMultipleRoutes() {
        RouteConfig routeA = new RouteConfig(UUID_A, "Steve", 25580, "127.0.0.1", 10000);
        RouteConfig routeB = new RouteConfig(UUID_A, "Steve", 25581, "127.0.0.1", 10001);
        ServerConfig cfg = new ServerConfig(true, "mctransport:main",
                List.of(routeA, routeB), "info");
        assertNull(cfg.routeFor(UUID_A));
        assertSame(routeA, cfg.routeFor(UUID_A, 25580));
        assertSame(routeB, cfg.routeFor(UUID_A, 25581));
    }

    @Test
    void serverConfigRoutesListIsImmutable() {
        ServerConfig cfg = new ServerConfig(true, "mctransport:main",
                List.of(ROUTE_A), "info");
        assertThrows(UnsupportedOperationException.class,
                () -> cfg.getRoutes().clear());
    }

    @Test
    void serverConfigWithRouteReplacesByUuidAndPort() {
        RouteConfig replacement = new RouteConfig(UUID_A, "Steve2", 25580,
                "10.0.0.1", 20000);
        ServerConfig updated = new ServerConfig(true, "mctransport:main",
                List.of(ROUTE_A), "info").withRoute(replacement);
        assertSame(replacement, updated.routeFor(UUID_A, 25580));
    }

    @Test
    void serverConfigWithRouteAddsNewPortForSameUuid() {
        RouteConfig routeB = new RouteConfig(UUID_A, "Steve", 25581,
                "127.0.0.1", 10001);
        ServerConfig updated = new ServerConfig(true, "mctransport:main",
                List.of(ROUTE_A), "info").withRoute(routeB);
        assertEquals(2, updated.getRoutes().size());
        assertSame(ROUTE_A, updated.routeFor(UUID_A, 25580));
        assertSame(routeB, updated.routeFor(UUID_A, 25581));
    }

    @Test
    void serverConfigWithRouteReplacesOnlySameUuidAndListenPort() {
        RouteConfig routeA = new RouteConfig(UUID_A, "Steve", 25580, "127.0.0.1", 10000);
        RouteConfig routeB = new RouteConfig(UUID_A, "Steve", 25581, "127.0.0.1", 10001);
        RouteConfig replacement = new RouteConfig(UUID_A, "Steve2", 25580, "10.0.0.1", 20000);

        ServerConfig updated = new ServerConfig(true, "mctransport:main",
                List.of(routeA, routeB), "info").withRoute(replacement);

        assertEquals(2, updated.getRoutes().size());
        assertSame(replacement, updated.routeFor(UUID_A, 25580));
        assertSame(routeB, updated.routeFor(UUID_A, 25581));
    }

    @Test
    void serverConfigWithoutRouteByUuidRemovesAllForPlayer() {
        ServerConfig cfg = new ServerConfig(true, "mctransport:main",
                List.of(ROUTE_A), "info");
        ServerConfig reduced = cfg.withoutRoute(UUID_A);
        assertNull(reduced.routeFor(UUID_A));
        assertEquals(0, reduced.getRoutes().size());
    }

    @Test
    void serverConfigWithoutRouteByUuidAndPortRemovesOnlyOne() {
        RouteConfig routeA = new RouteConfig(UUID_A, "Steve", 25580, "127.0.0.1", 10000);
        RouteConfig routeB = new RouteConfig(UUID_A, "Steve", 25581, "127.0.0.1", 10001);

        ServerConfig reduced = new ServerConfig(true, "mctransport:main",
                List.of(routeA, routeB), "info").withoutRoute(UUID_A, 25580);

        assertNull(reduced.routeFor(UUID_A, 25580));
        assertSame(routeB, reduced.routeFor(UUID_A, 25581));
        assertEquals(List.of(routeB), reduced.routesFor(UUID_A));
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
