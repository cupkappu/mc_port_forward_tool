package dev.kifuko.mctransport.config;

import dev.kifuko.mctransport.protocol.StreamMode;

import java.util.Objects;
import java.util.UUID;

/**
 * Immutable route configuration for a single player.
 *
 * <p>The MVP supports exactly one route per player UUID. The listen host
 * is always loopback on the client side; the server chooses the actual
 * {@code target_host:target_port}. Client listeners never learn the
 * target.</p>
 */
public final class RouteConfig {

    public static final String LOOPBACK_HOST = "127.0.0.1";

    private final UUID playerUuid;
    private final String playerName;
    private final String listenHost;
    private final int listenPort;
    private final String targetHost;
    private final int targetPort;
    private final StreamMode mode;

    public RouteConfig(UUID playerUuid,
                       String playerName,
                       int listenPort,
                       String targetHost,
                       int targetPort) {
        this(playerUuid, playerName, listenPort, targetHost, targetPort, StreamMode.DIRECT);
    }

    public RouteConfig(UUID playerUuid,
                       String playerName,
                       int listenPort,
                       String targetHost,
                       int targetPort,
                       StreamMode mode) {
        if (playerUuid == null) {
            throw new IllegalArgumentException("playerUuid must not be null");
        }
        if (playerName == null || playerName.trim().isEmpty()) {
            throw new IllegalArgumentException("playerName must not be blank");
        }
        validatePort(listenPort, "listenPort");
        validatePort(targetPort, "targetPort");
        if (targetHost == null || targetHost.trim().isEmpty()) {
            throw new IllegalArgumentException("targetHost must not be blank");
        }
        this.playerUuid = playerUuid;
        this.playerName = playerName.trim();
        this.listenHost = LOOPBACK_HOST;
        this.listenPort = listenPort;
        this.targetHost = targetHost.trim();
        this.targetPort = targetPort;
        this.mode = mode == null ? StreamMode.DIRECT : mode;
    }

    public UUID getPlayerUuid() {
        return playerUuid;
    }

    public String getPlayerName() {
        return playerName;
    }

    public String getListenHost() {
        return listenHost;
    }

    public int getListenPort() {
        return listenPort;
    }

    public String getTargetHost() {
        return targetHost;
    }

    public int getTargetPort() {
        return targetPort;
    }

    public StreamMode getMode() {
        return mode;
    }

    private static void validatePort(int port, String field) {
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException(
                    field + " must be in 1..65535, got: " + port);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof RouteConfig other)) return false;
        return listenPort == other.listenPort
                && targetPort == other.targetPort
                && mode == other.mode
                && Objects.equals(playerUuid, other.playerUuid)
                && Objects.equals(playerName, other.playerName)
                && Objects.equals(listenHost, other.listenHost)
                && Objects.equals(targetHost, other.targetHost);
    }

    @Override
    public int hashCode() {
        return Objects.hash(playerUuid, playerName, listenHost, listenPort,
                targetHost, targetPort, mode);
    }

    @Override
    public String toString() {
        return "RouteConfig{"
                + "playerUuid=" + playerUuid
                + ", playerName='" + playerName + '\''
                + ", listenHost='" + listenHost + '\''
                + ", listenPort=" + listenPort
                + ", targetHost='" + targetHost + '\''
                + ", targetPort=" + targetPort
                + ", mode=" + mode
                + '}';
    }
}