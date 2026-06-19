package dev.kifuko.mctransport.config;

/**
 * Parsed and validated client configuration for the MC Transport Dialer.
 *
 * <p>This is a pure value object. All validation happens in the constructor;
 * malformed input throws {@link IllegalArgumentException} with a message
 * suitable for surfacing to operators.</p>
 */
public final class ClientConfig {

    public static final String DEFAULT_CHANNEL = "mctransport:main";

    private final boolean enabled;
    private final String listenHost;
    private final int listenPort;
    private final String channelName;
    private final String psk;
    private final int maxStreams;
    private final int streamBufferSize;
    private final long globalBufferSize;
    private final String logLevel;

    public ClientConfig(
            boolean enabled,
            String listenHost,
            int listenPort,
            String channelName,
            String psk,
            int maxStreams,
            int streamBufferSize,
            long globalBufferSize,
            String logLevel
    ) {
        validatePort(listenPort, "listen_port");
        validatePositive(maxStreams, "max_streams");
        validatePositive(streamBufferSize, "stream_buffer_size");
        validatePositive(globalBufferSize, "global_buffer_size");
        requireNonBlank(channelName, "channel_name");
        requireNonBlank(psk, "psk");
        requireNonBlank(logLevel, "log_level");

        String host = listenHost == null ? "" : listenHost.trim();
        requireNonBlank(host, "listen_host");
        if (!"127.0.0.1".equals(host) && !"localhost".equals(host)) {
            throw new IllegalArgumentException(
                    "client listen_host must be 127.0.0.1 or localhost for MVP, got: " + listenHost);
        }

        this.enabled = enabled;
        this.listenHost = host;
        this.listenPort = listenPort;
        this.channelName = channelName.trim();
        this.psk = psk;
        this.maxStreams = maxStreams;
        this.streamBufferSize = streamBufferSize;
        this.globalBufferSize = globalBufferSize;
        this.logLevel = logLevel.trim();
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String getListenHost() {
        return listenHost;
    }

    public int getListenPort() {
        return listenPort;
    }

    public String getChannelName() {
        return channelName;
    }

    public String getPsk() {
        return psk;
    }

    public int getMaxStreams() {
        return maxStreams;
    }

    public int getStreamBufferSize() {
        return streamBufferSize;
    }

    public long getGlobalBufferSize() {
        return globalBufferSize;
    }

    public String getLogLevel() {
        return logLevel;
    }

    static void validatePort(int port, String field) {
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException(
                    field + " must be in 1..65535, got: " + port);
        }
    }

    static void validatePositive(long value, String field) {
        if (value <= 0) {
            throw new IllegalArgumentException(
                    field + " must be positive, got: " + value);
        }
    }

    static void requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}