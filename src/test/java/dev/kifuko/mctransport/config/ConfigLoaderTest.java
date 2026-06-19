package dev.kifuko.mctransport.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies {@link ConfigLoader} end-to-end against the temp filesystem.
 */
class ConfigLoaderTest {

    private static final String CLIENT_RESOURCE = "/mctransport.client.toml";
    private static final String SERVER_RESOURCE = "/mctransport.server.toml";

    @Test
    void clientConfigIsSeededWhenMissing(@TempDir Path tmp) throws Exception {
        Path configDir = tmp.resolve("config");
        // Bundled resource must exist on the test classpath.
        try (InputStream in = getClass().getResourceAsStream(CLIENT_RESOURCE)) {
            assertNotNull(in, "bundled client resource missing on test classpath");
        }
        ClientConfig cfg = ConfigLoader.loadClient(configDir, "mctransport.client.toml", CLIENT_RESOURCE);
        assertTrue(cfg.isEnabled());
        assertEquals(25580, cfg.getListenPort());
        assertEquals("mctransport:main", cfg.getChannelName());
        assertEquals("change-me", cfg.getPsk());
        // The seed file must exist after the call.
        assertTrue(Files.isRegularFile(configDir.resolve("mctransport.client.toml")));
    }

    @Test
    void clientConfigIsReusedWhenPresent(@TempDir Path tmp) throws Exception {
        Path configDir = tmp.resolve("config");
        Files.createDirectories(configDir);
        Path file = configDir.resolve("mctransport.client.toml");
        Files.writeString(file,
                "enabled = true\n"
                        + "listen_host = \"127.0.0.1\"\n"
                        + "listen_port = 30000\n"
                        + "channel_name = \"mctransport:main\"\n"
                        + "psk = \"secret\"\n"
                        + "max_streams = 8\n"
                        + "stream_buffer_size = 65536\n"
                        + "global_buffer_size = 262144\n"
                        + "log_level = \"debug\"\n",
                StandardCharsets.UTF_8);
        ClientConfig cfg = ConfigLoader.loadClient(configDir, "mctransport.client.toml", CLIENT_RESOURCE);
        assertEquals(30000, cfg.getListenPort());
        assertEquals("secret", cfg.getPsk());
        assertEquals(8, cfg.getMaxStreams());
    }

    @Test
    void invalidClientConfigFailsWithPath(@TempDir Path tmp) throws Exception {
        Path configDir = tmp.resolve("config");
        Files.createDirectories(configDir);
        Files.writeString(configDir.resolve("mctransport.client.toml"),
                "enabled = true\n"
                        + "listen_host = \"127.0.0.1\"\n"
                        + "listen_port = 70000\n"
                        + "channel_name = \"mctransport:main\"\n"
                        + "psk = \"x\"\n"
                        + "max_streams = 1\n"
                        + "stream_buffer_size = 1\n"
                        + "global_buffer_size = 1\n"
                        + "log_level = \"info\"\n",
                StandardCharsets.UTF_8);
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> ConfigLoader.loadClient(configDir, "mctransport.client.toml", CLIENT_RESOURCE));
        assertTrue(ex.getMessage().contains(configDir.resolve("mctransport.client.toml").toString()),
                "message should include config file path: " + ex.getMessage());
    }

    @Test
    void malformedTomlReportsLineNumber(@TempDir Path tmp) throws Exception {
        Path configDir = tmp.resolve("config");
        Files.createDirectories(configDir);
        Files.writeString(configDir.resolve("mctransport.client.toml"),
                "enabled = true\n"
                        + "listen_host = \"127.0.0.1\"\n"
                        + "listen_port = 25580\n"
                        + "channel_name = \"mctransport:main\"\n"
                        + "psk = \"x\"\n"
                        + "max_streams = 1\n"
                        + "stream_buffer_size = 1\n"
                        + "global_buffer_size = 1\n"
                        + "log_level = \"info\"\n"
                        + "this_is_not_valid = bad-bareword\n",
                StandardCharsets.UTF_8);
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> ConfigLoader.loadClient(configDir, "mctransport.client.toml", CLIENT_RESOURCE));
        assertTrue(ex.getMessage().contains(":10:"),
                "expected line number in message, got: " + ex.getMessage());
    }

    @Test
    void malformedTomlRejectsBlankStringOnBadLine(@TempDir Path tmp) throws Exception {
        Path configDir = tmp.resolve("config");
        Files.createDirectories(configDir);
        Files.writeString(configDir.resolve("mctransport.client.toml"),
                "enabled = true\n"
                        + "listen_host = \"127.0.0.1\"\n"
                        + "listen_port = 25580\n"
                        + "channel_name = \"mctransport:main\"\n"
                        + "psk = \"x\"\n"
                        + "max_streams = 1\n"
                        + "stream_buffer_size = 1\n"
                        + "global_buffer_size = 1\n"
                        + "log_level = \"info\"\n"
                        + "this_is_not_valid = bad-keyword-no-quotes\n",
                StandardCharsets.UTF_8);
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> ConfigLoader.loadClient(configDir, "mctransport.client.toml", CLIENT_RESOURCE));
        assertTrue(ex.getMessage().contains(":10:"),
                "expected line number in message, got: " + ex.getMessage());
    }

    @Test
    void serverConfigIsSeededWhenMissing(@TempDir Path tmp) throws Exception {
        Path configDir = tmp.resolve("config");
        try (InputStream in = getClass().getResourceAsStream(SERVER_RESOURCE)) {
            assertNotNull(in, "bundled server resource missing on test classpath");
        }
        ServerConfig cfg = ConfigLoader.loadServer(configDir, "mctransport.server.toml", SERVER_RESOURCE);
        assertTrue(cfg.isEnabled());
        assertEquals(10000, cfg.getTargetPort());
        assertEquals(List.of("player-uuid-here"), cfg.getAllowedPlayers());
    }

    @Test
    void serverConfigIsParsedFromOverride(@TempDir Path tmp) throws IOException {
        Path configDir = tmp.resolve("config");
        Files.createDirectories(configDir);
        Files.writeString(configDir.resolve("mctransport.server.toml"),
                "enabled = true\n"
                        + "target_host = \"10.0.0.1\"\n"
                        + "target_port = 9000\n"
                        + "channel_name = \"mctransport:main\"\n"
                        + "psk = \"secret\"\n"
                        + "allowed_players = [\"alpha\", \"beta\"]\n"
                        + "max_streams_per_player = 16\n"
                        + "stream_buffer_size = 65536\n"
                        + "global_buffer_size_per_player = 262144\n"
                        + "idle_timeout_seconds = 60\n"
                        + "connect_timeout_seconds = 5\n"
                        + "log_level = \"warn\"\n",
                StandardCharsets.UTF_8);
        ServerConfig cfg = ConfigLoader.loadServer(configDir, "mctransport.server.toml", SERVER_RESOURCE);
        assertEquals("10.0.0.1", cfg.getTargetHost());
        assertEquals(9000, cfg.getTargetPort());
        assertEquals(List.of("alpha", "beta"), cfg.getAllowedPlayers());
        assertEquals(60, cfg.getIdleTimeoutSeconds());
        assertEquals(5, cfg.getConnectTimeoutSeconds());
        assertEquals("warn", cfg.getLogLevel());
    }

    @Test
    void serverConfigWithEmptyAllowedPlayersFails(@TempDir Path tmp) throws IOException {
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
        assertTrue(ex.getMessage().contains("allowed_players"));
    }

    @Test
    void seededFileIsNotOverwritten(@TempDir Path tmp) throws Exception {
        Path configDir = tmp.resolve("config");
        Files.createDirectories(configDir);
        Path file = configDir.resolve("mctransport.client.toml");
        // Place a marker; loader should not replace this with bundled defaults.
        Files.writeString(file, "# user-edited\n", StandardCharsets.UTF_8);
        // The file lacks the required keys, so loading must fail — but the
        // file contents must be unchanged.
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> ConfigLoader.loadClient(configDir, "mctransport.client.toml", CLIENT_RESOURCE));
        String contents = Files.readString(file, StandardCharsets.UTF_8);
        assertFalse(contents.contains("enabled"),
                "loader must not overwrite an existing config file: " + ex.getMessage());
    }
}