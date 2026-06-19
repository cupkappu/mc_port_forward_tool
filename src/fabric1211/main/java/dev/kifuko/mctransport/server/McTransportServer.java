package dev.kifuko.mctransport.server;

import dev.kifuko.mctransport.McTransport;
import dev.kifuko.mctransport.client.TransportPayload;
import dev.kifuko.mctransport.config.ConfigLoader;
import dev.kifuko.mctransport.config.ServerConfig;
import dev.kifuko.mctransport.net.TransportExecutors;
import dev.kifuko.mctransport.protocol.FrameCodec;
import dev.kifuko.mctransport.protocol.SecureFrameCodec;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.util.Identifier;

/**
 * Dedicated-server Fabric entrypoint. Loads the config and starts the
 * server-side Fabric bridge.
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
            FrameCodec codec = new FrameCodec(
                    SecureFrameCodec.encryptedPayloadLimit(config.getStreamBufferSize()));
            String[] parts = config.getChannelName().split(":");
            Identifier channelId = Identifier.of(parts[0], parts[1]);
            TransportPayload.ID = TransportPayload.buildId(channelId);
            PayloadTypeRegistry.playC2S().register(TransportPayload.ID,
                    TransportPayload.CODEC);
            PayloadTypeRegistry.playS2C().register(TransportPayload.ID,
                    TransportPayload.CODEC);
            FabricServerTunnelBridge bridge = new FabricServerTunnelBridge(channelId, codec, config,
                    new FabricServerTunnelBridge.TunnelExecutorsAdapter() {
                        @Override public java.util.concurrent.ExecutorService io() {
                            return executors.io();
                        }
                    });
            bridge.start();
            McTransport.LOGGER.info("server tunnel bridge registered on channel {}; target {}:{}",
                    config.getChannelName(),
                    config.getTargetHost(), config.getTargetPort());
        } catch (RuntimeException e) {
            McTransport.LOGGER.error("server init failed", e);
        }
    }
}
