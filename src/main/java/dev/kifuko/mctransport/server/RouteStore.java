package dev.kifuko.mctransport.server;

import dev.kifuko.mctransport.config.ConfigLoader;
import dev.kifuko.mctransport.config.RouteConfig;
import dev.kifuko.mctransport.config.ServerConfig;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

/**
 * In-memory store of player routes, backed by the on-disk server config
 * file. The store owns route persistence and exposes the current config
 * snapshot to other components.
 */
public final class RouteStore {

    private final Path configDir;
    private final String filename;
    private volatile ServerConfig current;

    public RouteStore(Path configDir, String filename, ServerConfig initialConfig) {
        this.configDir = configDir;
        this.filename = filename;
        this.current = initialConfig;
    }

    /** Returns the current snapshot of the server config. */
    public synchronized ServerConfig config() {
        return current;
    }

    /** Returns the route configured for {@code uuid} or {@code null}. */
    public synchronized RouteConfig routeFor(UUID uuid) {
        if (uuid == null) {
            return null;
        }
        return current.routeFor(uuid);
    }

    /**
     * Replaces (or adds) the route for the (playerUuid, listenPort) key
     * and persists the new snapshot.
     */
    public synchronized void setRoute(RouteConfig route) {
        ServerConfig next = current.withRoute(route);
        save(next);
        current = next;
    }

    /**
     * Removes the route for {@code uuid} if present and persists the new
     * snapshot.
     *
     * @return true when at least one route existed and was removed.
     */
    public synchronized boolean removeRoute(UUID uuid) {
        if (uuid == null || current.routesFor(uuid).isEmpty()) {
            return false;
        }
        ServerConfig next = current.withoutRoute(uuid);
        save(next);
        current = next;
        return true;
    }

    /** Returns an immutable snapshot of the configured routes. */
    public synchronized List<RouteConfig> routes() {
        return List.copyOf(current.getRoutes());
    }

    private void save(ServerConfig config) {
        ConfigLoader.writeServer(configDir, filename, config);
    }
}
