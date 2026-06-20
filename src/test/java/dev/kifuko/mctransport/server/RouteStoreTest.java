package dev.kifuko.mctransport.server;

import dev.kifuko.mctransport.config.ConfigLoader;
import dev.kifuko.mctransport.config.RouteConfig;
import dev.kifuko.mctransport.config.ServerConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RouteStoreTest {

    private static final UUID UUID_A =
            UUID.fromString("11111111-2222-3333-4444-555555555555");

    private RouteConfig route(String name, int listenPort, int targetPort) {
        return new RouteConfig(UUID_A, name, listenPort, "127.0.0.1", targetPort);
    }

    private ServerConfig config(List<RouteConfig> routes) {
        return new ServerConfig(true, "mctransport:main", routes,
                64, 1024, 8192L, 300, 10, "info");
    }

    @Test
    void setRouteAddsSecondPortForSameUuid(@TempDir Path tmp) {
        RouteStore store = new RouteStore(tmp, "mctransport.server.toml",
                config(List.of(route("Steve", 25580, 10000))));

        store.setRoute(route("Steve", 25581, 10001));

        assertEquals(2, store.routes().size());
        assertEquals(10000, store.routeFor(UUID_A, 25580).getTargetPort());
        assertEquals(10001, store.routeFor(UUID_A, 25581).getTargetPort());
    }

    @Test
    void removeRouteRemovesOnlyRequestedPort(@TempDir Path tmp) {
        RouteStore store = new RouteStore(tmp, "mctransport.server.toml",
                config(List.of(
                        route("Steve", 25580, 10000),
                        route("Steve", 25581, 10001))));

        assertTrue(store.removeRoute(UUID_A, 25580));

        assertNull(store.routeFor(UUID_A, 25580));
        assertEquals(25581, store.routeFor(UUID_A, 25581).getListenPort());
        assertEquals(1, store.routesFor(UUID_A).size());
    }

    @Test
    void removeRouteByUuidReturnsFalseWhenMissing(@TempDir Path tmp) {
        RouteStore store = new RouteStore(tmp, "mctransport.server.toml",
                config(List.of()));
        assertFalse(store.removeRoute(UUID_A));
    }

    @Test
    void removeRouteByPortReturnsFalseWhenMissing(@TempDir Path tmp) {
        RouteStore store = new RouteStore(tmp, "mctransport.server.toml",
                config(List.of()));

        assertFalse(store.removeRoute(UUID_A, 25580));
    }

    @Test
    void routeForByPortReturnsNullWhenMissing(@TempDir Path tmp) {
        RouteStore store = new RouteStore(tmp, "mctransport.server.toml",
                config(List.of()));

        assertNull(store.routeFor(UUID_A, 25580));
        assertNull(store.routeFor(null, 25580));
    }

    @Test
    void routesForReturnsEmptyListForUnknownUuid(@TempDir Path tmp) {
        RouteStore store = new RouteStore(tmp, "mctransport.server.toml",
                config(List.of()));

        assertTrue(store.routesFor(UUID_A).isEmpty());
        assertTrue(store.routesFor(null).isEmpty());
    }

    @Test
    void setRouteWritesConfigFile(@TempDir Path tmp) {
        RouteStore store = new RouteStore(tmp, "mctransport.server.toml",
                config(List.of()));
        RouteConfig route = route("Steve", 25580, 10000);
        store.setRoute(route);

        ServerConfig reloaded = ConfigLoader.loadServer(tmp, "mctransport.server.toml",
                "/mctransport.server.toml");
        assertEquals(route, reloaded.routeFor(UUID_A));
    }

    @Test
    void routesReturnsImmutableSnapshot(@TempDir Path tmp) {
        RouteStore store = new RouteStore(tmp, "mctransport.server.toml",
                config(List.of(route("Steve", 25580, 10000))));
        assertThrows(UnsupportedOperationException.class,
                () -> store.routes().clear());
        assertTrue(store.routeFor(null) == null);
    }
}
