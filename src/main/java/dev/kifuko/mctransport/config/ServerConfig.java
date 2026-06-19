package dev.kifuko.mctransport.config;

import java.util.List;

/**
 * Parsed and validated server configuration for the MC Transport Dialer.
 *
 * <p>Validation rejects configurations that would let the server mod become
 * an open proxy: when enabled, the allowed_players list MUST be non-empty.</p>
 */
public final class ServerConfig {

    public static final String DEFAULT_CHANNEL = "mctransport:main";

    private final boolean enabled;
    private final String targetHost;
    private final int targetPort;
    private final String channelName;
    private final String psk;
    private final List<String> allowedPlayers;
    private final int maxStreamsPerPlayer;
    private final int streamBufferSize;
    private final long globalBufferSizePerPlayer;
    private final int idleTimeoutSeconds;
    private final int connectTimeoutSeconds;
    private final String logLevel;

    public ServerConfig(
            boolean enabled,
            String targetHost,
            int targetPort,
            String channelName,
            String psk,
            List<String> allowedPlayers,
            int maxStreamsPerPlayer,
            int streamBufferSize,
            long globalBufferSizePerPlayer,
            int idleTimeoutSeconds,
            int connectTimeoutSeconds,
            String logLevel
    ) {
        if (enabled) {
            ClientConfig.validatePort(targetPort, "target_port");
            ClientConfig.validatePositive(maxStreamsPerPlayer, "max_streams_per_player");
            ClientConfig.validatePositive(streamBufferSize, "stream_buffer_size");
            ClientConfig.validatePositive(globalBufferSizePerPlayer, "global_buffer_size_per_player");
            if (idleTimeoutSeconds <= 0) {
                throw new IllegalArgumentException(
                        "idle_timeout_seconds must be positive, got: " + idleTimeoutSeconds);
            }
            if (connectTimeoutSeconds <= 0) {
                throw new IllegalArgumentException(
                        "connect_timeout_seconds must be positive, got: " + connectTimeoutSeconds);
            }
            ClientConfig.requireNonBlank(channelName, "channel_name");
            ClientConfig.requireNonBlank(psk, "psk");
            ClientConfig.requireNonBlank(logLevel, "log_level");

            String host = targetHost == null ? "" : targetHost.trim();
            ClientConfig.requireNonBlank(host, "target_host");

            if (allowedPlayers == null || allowedPlayers.isEmpty()) {
                throw new IllegalArgumentException(
                        "allowed_players must be non-empty when the server mod is enabled");
            }
            for (int i = 0; i < allowedPlayers.size(); i++) {
                String p = allowedPlayers.get(i);
                if (p == null || p.isBlank()) {
                    throw new IllegalArgumentException(
                            "allowed_players[" + i + "] must not be blank");
                }
            }

            this.targetHost = host;
            this.allowedPlayers = List.copyOf(allowedPlayers);
        } else {
            this.targetHost = targetHost == null ? "" : targetHost.trim();
            this.allowedPlayers = allowedPlayers == null ? List.of() : List.copyOf(allowedPlayers);
        }

        this.enabled = enabled;
        this.targetPort = targetPort;
        this.channelName = channelName == null ? "" : channelName.trim();
        this.psk = psk == null ? "" : psk;
        this.maxStreamsPerPlayer = maxStreamsPerPlayer;
        this.streamBufferSize = streamBufferSize;
        this.globalBufferSizePerPlayer = globalBufferSizePerPlayer;
        this.idleTimeoutSeconds = idleTimeoutSeconds;
        this.connectTimeoutSeconds = connectTimeoutSeconds;
        this.logLevel = logLevel == null ? "" : logLevel.trim();
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String getTargetHost() {
        return targetHost;
    }

    public int getTargetPort() {
        return targetPort;
    }

    public String getChannelName() {
        return channelName;
    }

    public String getPsk() {
        return psk;
    }

    public List<String> getAllowedPlayers() {
        return allowedPlayers;
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
}
