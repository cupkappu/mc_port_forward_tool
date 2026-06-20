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
    void setRouteReplacesExistingUuid(@TempDir Path tmp) {
        RouteStore store = new RouteStore(tmp, "mctransport.server.toml",
                config(List.of(route("Steve", 25580, 10000))));
        store.setRoute(route("Steve2", 25581, 10001));

        assertEquals(1, store.routes().size());
        assertEquals("Steve2", store.routeFor(UUID_A).getPlayerName());
        assertEquals(25581, store.routeFor(UUID_A).getListenPort());
    }

    @Test
    void removeRouteReturnsFalseWhenMissing(@TempDir Path tmp) {
        RouteStore store = new RouteStore(tmp, "mctransport.server.toml",
                config(List.of()));
        assertFalse(store.removeRoute(UUID_A));
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
