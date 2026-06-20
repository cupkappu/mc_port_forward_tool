package dev.kifuko.mctransport.server;

import dev.kifuko.mctransport.config.RouteConfig;
import dev.kifuko.mctransport.config.ServerConfig;
import dev.kifuko.mctransport.protocol.StreamMode;
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

        RouteConfig route = store.routeFor(UUID_A, 25580);
        assertEquals("Steve", route.getPlayerName());
        assertEquals(25580, route.getListenPort());
        assertEquals(10000, route.getTargetPort());
        assertEquals(List.of(new RouteEvent(UUID_A, 25580)), applier.applied);
        assertTrue(message.contains("Set route for Steve"));
    }

    @Test
    void unsetRemovesOnlyRequestedPortAndCallsClear(@TempDir Path tmp) {
        RecordingApplier applier = new RecordingApplier();
        RouteConfig routeA = new RouteConfig(UUID_A, "Steve", 25580,
                "127.0.0.1", 10000);
        RouteConfig routeB = new RouteConfig(UUID_A, "Steve", 25581,
                "127.0.0.1", 10001);
        RouteStore store = new RouteStore(tmp, "mctransport.server.toml",
                config(List.of(routeA, routeB)));
        RouteCommandService service = new RouteCommandService(store, applier);

        String message = service.unsetRoute(UUID_A, "Steve", 25580);

        assertTrue(store.routeFor(UUID_A, 25580) == null);
        assertEquals(routeB, store.routeFor(UUID_A, 25581));
        assertEquals(List.of(new RouteEvent(UUID_A, 25580)), applier.cleared);
        assertTrue(message.contains("Removed route for Steve"));
        assertTrue(message.contains("127.0.0.1:25580"));
    }

    @Test
    void setAddsSecondPortForSamePlayer(@TempDir Path tmp) {
        RecordingApplier applier = new RecordingApplier();
        RouteConfig routeA = new RouteConfig(UUID_A, "Steve", 25580,
                "127.0.0.1", 10000);
        RouteStore store = new RouteStore(tmp, "mctransport.server.toml",
                config(List.of(routeA)));
        RouteCommandService service = new RouteCommandService(store, applier);

        service.setRoute(UUID_A, "Steve", 25581, "10.0.0.5", 25565, StreamMode.KCP);

        assertEquals(2, store.routesFor(UUID_A).size());
        assertEquals(StreamMode.KCP, store.routeFor(UUID_A, 25581).getMode());
        assertEquals(List.of(new RouteEvent(UUID_A, 25581)), applier.applied);
    }

    @Test
    void listIncludesModeAndEndpoints(@TempDir Path tmp) {
        RouteConfig route = new RouteConfig(UUID_A, "Steve", 25580,
                "127.0.0.1", 10000, StreamMode.KCP);
        RouteCommandService service = new RouteCommandService(
                new RouteStore(tmp, "mctransport.server.toml", config(List.of(route))),
                new RecordingApplier());

        List<String> rows = service.listRoutes();

        assertEquals(1, rows.size());
        assertTrue(rows.get(0).contains("Steve"));
        assertTrue(rows.get(0).contains(UUID_A.toString()));
        assertTrue(rows.get(0).contains("127.0.0.1:25580"));
        assertTrue(rows.get(0).contains("127.0.0.1:10000"));
        assertTrue(rows.get(0).contains("(mode=KCP)"));
    }

    private ServerConfig config(List<RouteConfig> routes) {
        return new ServerConfig(true, "mctransport:main", routes,
                64, 1024, 8192L, 300, 10, "info");
    }

    private record RouteEvent(UUID uuid, int listenPort) {}

    private static final class RecordingApplier
            implements RouteCommandService.OnlineRouteApplier {
        final List<RouteEvent> applied = new ArrayList<>();
        final List<RouteEvent> cleared = new ArrayList<>();

        @Override
        public void apply(UUID uuid, int listenPort) {
            applied.add(new RouteEvent(uuid, listenPort));
        }

        @Override
        public void clear(UUID uuid, int listenPort) {
            cleared.add(new RouteEvent(uuid, listenPort));
        }
    }
}
