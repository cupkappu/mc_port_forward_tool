package dev.kifuko.mctransport.config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Parsed and validated server configuration for the MC Transport Dialer.
 *
 * <p>The server owns all route state and pushes a single loopback listener
 * configuration to each configured player. There are no global target or
 * allowed-player fields in this mode; routing is per-player.</p>
 */
public final class ServerConfig {

    public static final String DEFAULT_CHANNEL = "mctransport:main";

    public static final int DEFAULT_MAX_STREAMS_PER_PLAYER = 64;
    public static final int DEFAULT_STREAM_BUFFER_SIZE = 1_048_576;
    public static final long DEFAULT_GLOBAL_BUFFER_SIZE_PER_PLAYER = 33_554_432L;
    public static final int DEFAULT_IDLE_TIMEOUT_SECONDS = 300;
    public static final int DEFAULT_CONNECT_TIMEOUT_SECONDS = 10;
    public static final String DEFAULT_LOG_LEVEL = "info";

    private final boolean enabled;
    private final String channelName;
    private final Map<UUID, RouteConfig> routesByUuid;
    private final List<RouteConfig> routesView;
    private final int maxStreamsPerPlayer;
    private final int streamBufferSize;
    private final long globalBufferSizePerPlayer;
    private final int idleTimeoutSeconds;
    private final int connectTimeoutSeconds;
    private final String logLevel;

    public ServerConfig(boolean enabled,
                        String channelName,
                        List<RouteConfig> routes,
                        int maxStreamsPerPlayer,
                        int streamBufferSize,
                        long globalBufferSizePerPlayer,
                        int idleTimeoutSeconds,
                        int connectTimeoutSeconds,
                        String logLevel) {
        this(enabled, channelName, routes, maxStreamsPerPlayer, streamBufferSize,
                globalBufferSizePerPlayer, idleTimeoutSeconds, connectTimeoutSeconds,
                logLevel, true);
    }

    private ServerConfig(boolean enabled,
                         String channelName,
                         List<RouteConfig> routes,
                         int maxStreamsPerPlayer,
                         int streamBufferSize,
                         long globalBufferSizePerPlayer,
                         int idleTimeoutSeconds,
                         int connectTimeoutSeconds,
                         String logLevel,
                         boolean applyStrictValidation) {
        this.enabled = enabled;
        this.channelName = channelName == null ? "" : channelName.trim();
        this.maxStreamsPerPlayer = maxStreamsPerPlayer;
        this.streamBufferSize = streamBufferSize;
        this.globalBufferSizePerPlayer = globalBufferSizePerPlayer;
        this.idleTimeoutSeconds = idleTimeoutSeconds;
        this.connectTimeoutSeconds = connectTimeoutSeconds;
        this.logLevel = logLevel == null ? "" : logLevel.trim();

        Map<UUID, RouteConfig> map = new LinkedHashMap<>();
        if (routes != null) {
            for (RouteConfig r : routes) {
                if (r == null) {
                    throw new IllegalArgumentException("route entry must not be null");
                }
                if (map.containsKey(r.getPlayerUuid())) {
                    throw new IllegalArgumentException(
                            "duplicate route entry for uuid: " + r.getPlayerUuid());
                }
                map.put(r.getPlayerUuid(), r);
            }
        }
        this.routesByUuid = Collections.unmodifiableMap(map);
        this.routesView = Collections.unmodifiableList(new ArrayList<>(map.values()));

        if (applyStrictValidation && enabled) {
            if (this.channelName.isEmpty()) {
                throw new IllegalArgumentException("channel_name must not be blank");
            }
            if (this.logLevel.isEmpty()) {
                throw new IllegalArgumentException("log_level must not be blank");
            }
            validatePositive(maxStreamsPerPlayer, "max_streams_per_player");
            validatePositive(streamBufferSize, "stream_buffer_size");
            validatePositive(globalBufferSizePerPlayer, "global_buffer_size_per_player");
            if (idleTimeoutSeconds <= 0) {
                throw new IllegalArgumentException(
                        "idle_timeout_seconds must be positive, got: " + idleTimeoutSeconds);
            }
            if (connectTimeoutSeconds <= 0) {
                throw new IllegalArgumentException(
                        "connect_timeout_seconds must be positive, got: " + connectTimeoutSeconds);
            }
        }
    }

    /** Convenience constructor with default operational limits. */
    public ServerConfig(boolean enabled,
                        String channelName,
                        List<RouteConfig> routes,
                        String logLevel) {
        this(enabled, channelName, routes,
                DEFAULT_MAX_STREAMS_PER_PLAYER,
                DEFAULT_STREAM_BUFFER_SIZE,
                DEFAULT_GLOBAL_BUFFER_SIZE_PER_PLAYER,
                DEFAULT_IDLE_TIMEOUT_SECONDS,
                DEFAULT_CONNECT_TIMEOUT_SECONDS,
                logLevel);
    }

    public static ServerConfig disabled() {
        return new ServerConfig(false, "", List.of(),
                DEFAULT_MAX_STREAMS_PER_PLAYER, DEFAULT_STREAM_BUFFER_SIZE,
                DEFAULT_GLOBAL_BUFFER_SIZE_PER_PLAYER,
                DEFAULT_IDLE_TIMEOUT_SECONDS, DEFAULT_CONNECT_TIMEOUT_SECONDS,
                "", false);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String getChannelName() {
        return channelName;
    }

    public List<RouteConfig> getRoutes() {
        return routesView;
    }

    public int getMaxStreamsPerPlayer() {
        return maxStreamsPerPlayer;
    }

    public int getStreamBufferSize() {
        return streamBufferSize;
    }

    public long getGlobalBufferSizePerPlayer() {
        return globalBufferSizePerPlayer;
    }

    public int getIdleTimeoutSeconds() {
        return idleTimeoutSeconds;
    }

    public int getConnectTimeoutSeconds() {
        return connectTimeoutSeconds;
    }

    public String getLogLevel() {
        return logLevel;
    }

    /** Returns the route configured for {@code uuid} or {@code null}. */
    public RouteConfig routeFor(UUID uuid) {
        if (uuid == null) {
            return null;
        }
        return routesByUuid.get(uuid);
    }

    /** Build a config copy with one route replaced or added. */
    public ServerConfig withRoute(RouteConfig route) {
        if (route == null) {
            throw new IllegalArgumentException("route must not be null");
        }
        Map<UUID, RouteConfig> next = new LinkedHashMap<>(routesByUuid);
        next.put(route.getPlayerUuid(), route);
        return new ServerConfig(enabled, channelName, new ArrayList<>(next.values()),
                maxStreamsPerPlayer, streamBufferSize,
                globalBufferSizePerPlayer, idleTimeoutSeconds, connectTimeoutSeconds,
                logLevel, false);
    }

    /** Build a config copy without the route for {@code uuid}, if present. */
    public ServerConfig withoutRoute(UUID uuid) {
        if (uuid == null || !routesByUuid.containsKey(uuid)) {
            return this;
        }
        Map<UUID, RouteConfig> next = new LinkedHashMap<>(routesByUuid);
        next.remove(uuid);
        return new ServerConfig(enabled, channelName, new ArrayList<>(next.values()),
                maxStreamsPerPlayer, streamBufferSize,
                globalBufferSizePerPlayer, idleTimeoutSeconds, connectTimeoutSeconds,
                logLevel, false);
    }

    private static void validatePositive(long value, String field) {
        if (value <= 0) {
            throw new IllegalArgumentException(
                    field + " must be positive, got: " + value);
        }
    }
}