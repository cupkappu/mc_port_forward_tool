package dev.kifuko.mctransport.server;

import dev.kifuko.mctransport.McTransport;
import dev.kifuko.mctransport.client.TransportPayload;
import dev.kifuko.mctransport.config.ConfigLoader;
import dev.kifuko.mctransport.config.ServerConfig;
import dev.kifuko.mctransport.net.TransportExecutors;
import dev.kifuko.mctransport.protocol.FrameCodec;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.util.Identifier;

/**
 * Dedicated-server Fabric entrypoint for Minecraft 1.21.1.
 *
 * <p>The server owns the {@link RouteStore} and {@link RouteCommandService}.
 * Routes are configured at runtime via the {@code /mctransport} command;
 * the bundled config file persists routes across restarts.</p>
 */
public final class McTransportServer implements DedicatedServerModInitializer {

    private static final String CONFIG_FILE = "mctransport.server.toml";
    private static final String CONFIG_RESOURCE = "/mctransport.server.toml";

    @Override
    public void onInitializeServer() {
        McTransport.LOGGER.info("MC Transport Dialer server loaded");
        try {
            ServerConfig config = ConfigLoader.loadServer(
                    FabricLoader.getInstance().getConfigDir(), CONFIG_FILE, CONFIG_RESOURCE);
            if (!config.isEnabled()) {
                McTransport.LOGGER.info("server transport is disabled in config");
                return;
            }
            TransportExecutors executors = new TransportExecutors(McTransport.MOD_ID);
            FrameCodec codec = new FrameCodec(config.getStreamBufferSize());
            String[] parts = config.getChannelName().split(":");
            Identifier channelId = Identifier.of(parts[0], parts[1]);
            TransportPayload.ID = TransportPayload.buildId(channelId);
            PayloadTypeRegistry.playC2S().register(TransportPayload.ID,
                    TransportPayload.CODEC);
            PayloadTypeRegistry.playS2C().register(TransportPayload.ID,
                    TransportPayload.CODEC);

            RouteStore routeStore = new RouteStore(
                    FabricLoader.getInstance().getConfigDir(), CONFIG_FILE, config);

            FabricServerTunnelBridge bridge = new FabricServerTunnelBridge(channelId, codec, config,
                    routeStore,
                    new FabricServerTunnelBridge.TunnelExecutorsAdapter() {
                        @Override public java.util.concurrent.ExecutorService io() {
                            return executors.io();
                        }
                    });
            bridge.start();

            RouteCommandService commandService = new RouteCommandService(routeStore,
                    new RouteCommandService.OnlineRouteApplier() {
                        @Override public void apply(java.util.UUID uuid, int listenPort) {
                            bridge.applyRouteIfOnline(uuid, listenPort);
                        }
                        @Override public void clear(java.util.UUID uuid, int listenPort) {
                            bridge.clearRouteIfOnline(uuid, listenPort);
                        }
                    });

            McTransportCommands.register(commandService);
            McTransport.LOGGER.info("server tunnel bridge registered on channel {}; {} routes configured",
                    config.getChannelName(), routeStore.routes().size());
        } catch (RuntimeException e) {
            McTransport.LOGGER.error("server init failed", e);
        }
    }
}
