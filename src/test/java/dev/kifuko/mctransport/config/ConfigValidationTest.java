package dev.kifuko.mctransport.config;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Validation tests for {@link ClientConfig} and {@link ServerConfig}.
 *
 * <p>These tests do not exercise any file IO; they verify that the
 * constructors reject malformed values with {@link IllegalArgumentException}
 * and accept the MVP defaults.</p>
 */
class ConfigValidationTest {

    @Test
    void clientConfigAcceptsMvpDefaults() {
        ClientConfig cfg = new ClientConfig(
                true, "127.0.0.1", 25580, "mctransport:main", "change-me",
                64, 1_048_576, 33_554_432L, "info");
        assertTrue(cfg.isEnabled());
        assertEquals(25580, cfg.getListenPort());
        assertEquals("127.0.0.1", cfg.getListenHost());
    }

    @Test
    void clientConfigAcceptsLocalhost() {
        ClientConfig cfg = new ClientConfig(
                true, "localhost", 25580, "mctransport:main", "change-me",
                64, 1_048_576, 33_554_432L, "info");
        assertEquals("localhost", cfg.getListenHost());
    }

    @Test
    void clientConfigRejectsNonLoopbackHost() {
        assertThrows(IllegalArgumentException.class, () -> new ClientConfig(
                true, "0.0.0.0", 25580, "mctransport:main", "change-me",
                64, 1_048_576, 33_554_432L, "info"));
    }

    @Test
    void clientConfigRejectsLowPort() {
        assertThrows(IllegalArgumentException.class, () -> new ClientConfig(
                true, "127.0.0.1", 0, "mctransport:main", "change-me",
                64, 1_048_576, 33_554_432L, "info"));
    }

    @Test
    void clientConfigRejectsHighPort() {
        assertThrows(IllegalArgumentException.class, () -> new ClientConfig(
                true, "127.0.0.1", 70000, "mctransport:main", "change-me",
                64, 1_048_576, 33_554_432L, "info"));
    }

    @Test
    void clientConfigRejectsZeroMaxStreams() {
        assertThrows(IllegalArgumentException.class, () -> new ClientConfig(
                true, "127.0.0.1", 25580, "mctransport:main", "change-me",
                0, 1_048_576, 33_554_432L, "info"));
    }

    @Test
    void clientConfigRejectsNegativeBuffer() {
        assertThrows(IllegalArgumentException.class, () -> new ClientConfig(
                true, "127.0.0.1", 25580, "mctransport:main", "change-me",
                64, 0, 33_554_432L, "info"));
    }

    @Test
    void clientConfigRejectsBlankPsk() {
        assertThrows(IllegalArgumentException.class, () -> new ClientConfig(
                true, "127.0.0.1", 25580, "mctransport:main", " ",
                64, 1_048_576, 33_554_432L, "info"));
    }

    @Test
    void clientConfigRejectsBlankChannelName() {
        assertThrows(IllegalArgumentException.class, () -> new ClientConfig(
                true, "127.0.0.1", 25580, "", "change-me",
                64, 1_048_576, 33_554_432L, "info"));
    }

    @Test
    void serverConfigAcceptsMvpDefaults() {
        ServerConfig cfg = new ServerConfig(
                true, "127.0.0.1", 10000, "mctransport:main", "change-me",
                List.of("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"),
                64, 1_048_576, 33_554_432L, 300, 10, "info");
        assertTrue(cfg.isEnabled());
        assertEquals(10000, cfg.getTargetPort());
        assertEquals(1, cfg.getAllowedPlayers().size());
    }

    @Test
    void serverConfigRejectsEmptyAllowedPlayersWhenEnabled() {
        assertThrows(IllegalArgumentException.class, () -> new ServerConfig(
                true, "127.0.0.1", 10000, "mctransport:main", "change-me",
                List.of(),
                64, 1_048_576, 33_554_432L, 300, 10, "info"));
    }

    @Test
    void serverConfigRejectsBlankTargetHostWhenEnabled() {
        assertThrows(IllegalArgumentException.class, () -> new ServerConfig(
                true, " ", 10000, "mctransport:main", "change-me",
                List.of("uuid"),
                64, 1_048_576, 33_554_432L, 300, 10, "info"));
    }

    @Test
    void serverConfigRejectsBadTargetPort() {
        assertThrows(IllegalArgumentException.class, () -> new ServerConfig(
                true, "127.0.0.1", 0, "mctransport:main", "change-me",
                List.of("uuid"),
                64, 1_048_576, 33_554_432L, 300, 10, "info"));
    }

    @Test
    void serverConfigRejectsZeroConnectTimeout() {
        assertThrows(IllegalArgumentException.class, () -> new ServerConfig(
                true, "127.0.0.1", 10000, "mctransport:main", "change-me",
                List.of("uuid"),
                64, 1_048_576, 33_554_432L, 300, 0, "info"));
    }

    @Test
    void serverConfigRejectsBlankUuidEntry() {
        assertThrows(IllegalArgumentException.class, () -> new ServerConfig(
                true, "127.0.0.1", 10000, "mctransport:main", "change-me",
                List.of("uuid-1", "  "),
                64, 1_048_576, 33_554_432L, 300, 10, "info"));
    }

    @Test
    void serverConfigDisabledSkipsStrictValidation() {
        // When disabled, allowed_players may be empty and ports may be 0.
        ServerConfig cfg = new ServerConfig(
                false, "", 0, "", "",
                List.of(),
                0, 0, 0L, 0, 0, "");
        assertEquals(false, cfg.isEnabled());
        assertEquals(0, cfg.getAllowedPlayers().size());
    }
}