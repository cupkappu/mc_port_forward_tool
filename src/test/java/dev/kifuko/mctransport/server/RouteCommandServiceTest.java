package dev.kifuko.mctransport.server;

import dev.kifuko.mctransport.config.RouteConfig;
import dev.kifuko.mctransport.config.ServerConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RouteCommandServiceTest {

    private static final UUID UUID_A =
            UUID.fromString("11111111-2222-3333-4444-555555555555");

    @Test
    void setStoresRouteAndCallsApply(@TempDir Path tmp) {
        RecordingApplier applier = new RecordingApplier();
        RouteStore store = new RouteStore(tmp, "mctransport.server.toml",
                config(List.of()));
        RouteCommandService service = new RouteCommandService(store, applier);

        String message = service.setRoute(UUID_A, " Steve ", 25580,
                "127.0.0.1", 10000);

        RouteConfig route = store.routeFor(UUID_A);
        assertEquals("Steve", route.getPlayerName());
        assertEquals(25580, route.getListenPort());
        assertEquals(10000, route.getTargetPort());
        assertEquals(List.of(UUID_A), applier.applied);
        assertTrue(message.contains("Set route for Steve"));
    }

    @Test
    void unsetRemovesRouteAndCallsClear(@TempDir Path tmp) {
        RecordingApplier applier = new RecordingApplier();
        RouteConfig route = new RouteConfig(UUID_A, "Steve", 25580,
                "127.0.0.1", 10000);
        RouteStore store = new RouteStore(tmp, "mctransport.server.toml",
                config(List.of(route)));
        RouteCommandService service = new RouteCommandService(store, applier);

        String message = service.unsetRoute(UUID_A, "Steve");

        assertTrue(store.routeFor(UUID_A) == null);
        assertEquals(List.of(UUID_A), applier.cleared);
        assertTrue(message.contains("Removed route for Steve"));
    }

    @Test
    void listIncludesRouteEndpoints(@TempDir Path tmp) {
        RouteConfig route = new RouteConfig(UUID_A, "Steve", 25580,
                "127.0.0.1", 10000);
        RouteCommandService service = new RouteCommandService(
                new RouteStore(tmp, "mctransport.server.toml", config(List.of(route))),
                new RecordingApplier());

        List<String> rows = service.listRoutes();

        assertEquals(1, rows.size());
        assertTrue(rows.get(0).contains("Steve"));
        assertTrue(rows.get(0).contains(UUID_A.toString()));
        assertTrue(rows.get(0).contains("127.0.0.1:25580"));
        assertTrue(rows.get(0).contains("127.0.0.1:10000"));
    }

    private ServerConfig config(List<RouteConfig> routes) {
        return new ServerConfig(true, "mctransport:main", routes,
                64, 1024, 8192L, 300, 10, "info");
    }

    private static final class RecordingApplier
            implements RouteCommandService.OnlineRouteApplier {
        final List<UUID> applied = new ArrayList<>();
        final List<UUID> cleared = new ArrayList<>();

        @Override
        public void apply(UUID uuid) {
            applied.add(uuid);
        }

        @Override
        public void clear(UUID uuid) {
            cleared.add(uuid);
        }
    }
}
