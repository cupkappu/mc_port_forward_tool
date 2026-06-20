package dev.kifuko.mctransport.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies {@link ConfigLoader} end-to-end against the temp filesystem.
 */
class ConfigLoaderTest {

    private static final String SERVER_RESOURCE = "/mctransport.server.toml";

    private static final String SERVER_ROUTES_TOML = ""
            + "enabled = true\n"
            + "channel_name = \"mctransport:main\"\n"
            + "max_streams_per_player = 64\n"
            + "stream_buffer_size = 1048576\n"
            + "global_buffer_size_per_player = 33554432\n"
            + "idle_timeout_seconds = 300\n"
            + "connect_timeout_seconds = 10\n"
            + "log_level = \"info\"\n"
            + "\n"
            + "[[routes]]\n"
            + "player_uuid = \"11111111-2222-3333-4444-555555555555\"\n"
            + "player_name = \"Steve\"\n"
            + "listen_port = 25580\n"
            + "target_host = \"127.0.0.1\"\n"
            + "target_port = 10000\n";

    @Test
    void serverConfigIsSeededWhenMissing(@TempDir Path tmp) throws Exception {
        Path configDir = tmp.resolve("config");
        try (InputStream in = getClass().getResourceAsStream(SERVER_RESOURCE)) {
            assertNotNull(in, "bundled server resource missing on test classpath");
        }
        ServerConfig cfg = ConfigLoader.loadServer(configDir, "mctransport.server.toml", SERVER_RESOURCE);
        assertTrue(cfg.isEnabled());
        assertEquals(1, cfg.getRoutes().size());
        RouteConfig seeded = cfg.getRoutes().get(0);
        assertEquals("ExamplePlayer", seeded.getPlayerName());
        assertEquals(25580, seeded.getListenPort());
        assertEquals(10000, seeded.getTargetPort());
    }

    @Test
    void loadServerReadsRouteEntries(@TempDir Path tmp) throws Exception {
        Path configDir = tmp.resolve("config");
        Files.createDirectories(configDir);
        Files.writeString(configDir.resolve("mctransport.server.toml"),
                SERVER_ROUTES_TOML, StandardCharsets.UTF_8);
        ServerConfig cfg = ConfigLoader.loadServer(configDir, "mctransport.server.toml", SERVER_RESOURCE);
        assertEquals(1, cfg.getRoutes().size());
        RouteConfig r = cfg.getRoutes().get(0);
        assertEquals(UUID.fromString("11111111-2222-3333-4444-555555555555"),
                r.getPlayerUuid());
        assertEquals("Steve", r.getPlayerName());
        assertEquals(25580, r.getListenPort());
        assertEquals("127.0.0.1", r.getTargetHost());
        assertEquals(10000, r.getTargetPort());
    }

    @Test
    void loadServerAllowsNoRoutes(@TempDir Path tmp) throws Exception {
        Path configDir = tmp.resolve("config");
        Files.createDirectories(configDir);
        Files.writeString(configDir.resolve("mctransport.server.toml"),
                "enabled = true\n"
                        + "channel_name = \"mctransport:main\"\n"
                        + "max_streams_per_player = 64\n"
                        + "stream_buffer_size = 1048576\n"
                        + "global_buffer_size_per_player = 33554432\n"
                        + "idle_timeout_seconds = 300\n"
                        + "connect_timeout_seconds = 10\n"
                        + "log_level = \"info\"\n",
                StandardCharsets.UTF_8);
        ServerConfig cfg = ConfigLoader.loadServer(configDir, "mctransport.server.toml", SERVER_RESOURCE);
        assertEquals(0, cfg.getRoutes().size());
        assertNull(cfg.routeFor(UUID.randomUUID()));
    }

    @Test
    void writeServerPersistsRoutesAndReloads(@TempDir Path tmp) throws Exception {
        Path configDir = tmp.resolve("config");
        Files.createDirectories(configDir);
        Path file = configDir.resolve("mctransport.server.toml");
        Files.writeString(file, SERVER_ROUTES_TOML, StandardCharsets.UTF_8);
        ServerConfig initial = ConfigLoader.loadServer(configDir, "mctransport.server.toml", SERVER_RESOURCE);
        RouteConfig updated = new RouteConfig(
                UUID.fromString("22222222-3333-4444-5555-666666666666"),
                "Alex", 25581, "10.0.0.1", 20000);
        ServerConfig expanded = initial.withRoute(updated);
        ConfigLoader.writeServer(configDir, "mctransport.server.toml", expanded);
        ServerConfig reloaded = ConfigLoader.loadServer(configDir, "mctransport.server.toml", SERVER_RESOURCE);
        assertEquals(2, reloaded.getRoutes().size());
        assertEquals(updated, reloaded.routeFor(updated.getPlayerUuid()));
        assertEquals(25581, reloaded.routeFor(updated.getPlayerUuid()).getListenPort());
    }

    @Test
    void loadServerRejectsLegacyGlobalTargetFields(@TempDir Path tmp) throws Exception {
        // Legacy server config used global target_host / target_port /
        // allowed_players / psk. The new server schema must reject them
        // because the new operators configure routes through commands.
        Path configDir = tmp.resolve("config");
        Files.createDirectories(configDir);
        Files.writeString(configDir.resolve("mctransport.server.toml"),
                "enabled = true\n"
                        + "target_host = \"127.0.0.1\"\n"
                        + "target_port = 10000\n"
                        + "channel_name = \"mctransport:main\"\n"
                        + "psk = \"x\"\n"
                        + "allowed_players = []\n"
                        + "max_streams_per_player = 1\n"
                        + "stream_buffer_size = 1\n"
                        + "global_buffer_size_per_player = 1\n"
                        + "idle_timeout_seconds = 60\n"
                        + "connect_timeout_seconds = 5\n"
                        + "log_level = \"info\"\n",
                StandardCharsets.UTF_8);
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> ConfigLoader.loadServer(configDir, "mctransport.server.toml", SERVER_RESOURCE));
        assertTrue(ex.getMessage().contains("unsupported server config key"),
                "legacy config must be rejected: " + ex.getMessage());
    }

    @Test
    void loadServerReadsSamePlayerOnDifferentListenPorts(@TempDir Path tmp) throws Exception {
        Path configDir = tmp.resolve("config");
        Files.createDirectories(configDir);
        Files.writeString(configDir.resolve("mctransport.server.toml"),
                "enabled = true\n"
                + "channel_name = \"mctransport:main\"\n"
                + "max_streams_per_player = 64\n"
                + "stream_buffer_size = 1048576\n"
                + "global_buffer_size_per_player = 33554432\n"
                + "idle_timeout_seconds = 300\n"
                + "connect_timeout_seconds = 10\n"
                + "log_level = \"info\"\n"
                + "\n"
                + "[[routes]]\n"
                + "player_uuid = \"11111111-2222-3333-4444-555555555555\"\n"
                + "player_name = \"Steve\"\n"
                + "listen_port = 25580\n"
                + "target_host = \"127.0.0.1\"\n"
                + "target_port = 10000\n"
                + "\n"
                + "[[routes]]\n"
                + "player_uuid = \"11111111-2222-3333-4444-555555555555\"\n"
                + "player_name = \"Steve\"\n"
                + "listen_port = 25581\n"
                + "target_host = \"10.0.0.5\"\n"
                + "target_port = 25565\n",
                StandardCharsets.UTF_8);

        ServerConfig cfg = ConfigLoader.loadServer(configDir, "mctransport.server.toml", SERVER_RESOURCE);
        UUID uuid = UUID.fromString("11111111-2222-3333-4444-555555555555");

        assertEquals(2, cfg.routesFor(uuid).size());
        assertEquals(10000, cfg.routeFor(uuid, 25580).getTargetPort());
        assertEquals(25565, cfg.routeFor(uuid, 25581).getTargetPort());
    }

    @Test
    void writeServerPersistsSamePlayerOnDifferentListenPorts(@TempDir Path tmp) throws Exception {
        UUID uuid = UUID.fromString("11111111-2222-3333-4444-555555555555");
        RouteConfig routeA = new RouteConfig(uuid, "Steve", 25580, "127.0.0.1", 10000);
        RouteConfig routeB = new RouteConfig(uuid, "Steve", 25581, "10.0.0.5", 25565);
        ServerConfig config = new ServerConfig(true, "mctransport:main",
                List.of(routeA, routeB),
                64, 1024, 8192L, 300, 10, "info");

        ConfigLoader.writeServer(tmp, "mctransport.server.toml", config);
        ServerConfig reloaded = ConfigLoader.loadServer(tmp, "mctransport.server.toml", SERVER_RESOURCE);

        assertEquals(2, reloaded.routesFor(uuid).size());
        assertEquals("127.0.0.1", reloaded.routeFor(uuid, 25580).getTargetHost());
        assertEquals("10.0.0.5", reloaded.routeFor(uuid, 25581).getTargetHost());
    }

}
