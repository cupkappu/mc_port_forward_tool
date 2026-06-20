package dev.kifuko.mctransport.server;

import dev.kifuko.mctransport.config.RouteConfig;
import dev.kifuko.mctransport.config.ServerConfig;

import java.util.List;
import java.util.UUID;

/**
 * Shared command surface for the per-version Fabric adapters. Adapters
 * wire this to a literal-command registration that resolves the player
 * name to a UUID via the server's player cache.
 */
public final class RouteCommandService {

    private final RouteStore store;
    private final OnlineRouteApplier applier;

    public RouteCommandService(RouteStore store, OnlineRouteApplier applier) {
        this.store = store;
        this.applier = applier;
    }

    public String setRoute(UUID uuid, String playerName, int listenPort,
                           String targetHost, int targetPort) {
        RouteConfig route = new RouteConfig(uuid, playerName,
                listenPort, targetHost, targetPort);
        store.setRoute(route);
        applier.apply(uuid);
        return "Set route for " + route.getPlayerName() + " (" + uuid + "): "
                + route.getListenHost() + ":" + route.getListenPort()
                + " -> " + route.getTargetHost() + ":" + route.getTargetPort();
    }

    public String unsetRoute(UUID uuid, String playerName) {
        store.removeRoute(uuid);
        applier.clear(uuid);
        return "Removed route for " + playerName + " (" + uuid + ")";
    }

    public List<String> listRoutes() {
        List<RouteConfig> routes = store.routes();
        if (routes.isEmpty()) {
            return List.of("No routes configured");
        }
        return routes.stream()
                .map(r -> r.getPlayerName()
                        + " (" + r.getPlayerUuid() + ") "
                        + r.getListenHost() + ":" + r.getListenPort()
                        + " -> " + r.getTargetHost() + ":" + r.getTargetPort())
                .toList();
    }

    public RouteStore store() {
        return store;
    }

    /** Hooks the bridge uses to push config frames to online players. */
    public interface OnlineRouteApplier {
        void apply(UUID uuid);

        void clear(UUID uuid);
    }
}