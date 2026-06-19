package dev.kifuko.mctransport.client;

import dev.kifuko.mctransport.McTransport;
import dev.kifuko.mctransport.config.ClientConfig;
import dev.kifuko.mctransport.config.ConfigLoader;
import dev.kifuko.mctransport.crypto.PskCipher;
import dev.kifuko.mctransport.net.TransportExecutors;
import dev.kifuko.mctransport.protocol.FrameCodec;
import dev.kifuko.mctransport.protocol.SecureFrameCodec;
import dev.kifuko.mctransport.stream.StreamRegistry;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.util.Identifier;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Client-side Fabric entrypoint. Loads the config, builds the tunnel session,
 * starts the local TCP listener, and hooks the Fabric bridge.
 */
public final class McTransportClient implements ClientModInitializer {

    private static final String CONFIG_FILE = "mctransport.client.toml";
    private static final String CONFIG_RESOURCE = "/mctransport.client.toml";

    private final AtomicReference<LocalTcpListener> listener = new AtomicReference<>();
    private final AtomicReference<ClientTunnelSession> session = new AtomicReference<>();
    private TransportExecutors executors;
    private FabricClientTunnelBridge bridge;

    @Override
    public void onInitializeClient() {
        McTransport.LOGGER.info("MC Transport Dialer client loaded");
        try {
            ClientConfig config = ConfigLoader.loadClient(
                    net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir(),
                    CONFIG_FILE, CONFIG_RESOURCE);
            if (!config.isEnabled()) {
                McTransport.LOGGER.info("client transport is disabled in config");
                return;
            }
            executors = new TransportExecutors(McTransport.MOD_ID);
            FrameCodec codec = new FrameCodec(
                    SecureFrameCodec.encryptedPayloadLimit(config.getStreamBufferSize()));
            StreamRegistry registry = new StreamRegistry(config.getMaxStreams(), true);

            String[] parts = config.getChannelName().split(":");
            Identifier channelId = Identifier.of(parts[0], parts[1]);
            TransportPayload.ID = TransportPayload.buildId(channelId);
            PayloadTypeRegistry.playS2C().register(TransportPayload.ID,
                    TransportPayload.CODEC);

            bridge = new FabricClientTunnelBridge(channelId, codec,
                    new PskCipher(config.getPsk()), config.getStreamBufferSize(),
                    new FabricClientTunnelBridge.TunnelExecutorsAdapter() {
                        @Override public java.util.concurrent.ExecutorService io() {
                            return executors.io();
                        }
                    });
            bridge.start();
            bridge.setReceiver(frame -> {
                ClientTunnelSession s = session.get();
                if (s != null) {
                    s.handleInbound(frame);
                }
            });

            ClientTunnelSession tunnelSession = new ClientTunnelSession(
                    config, bridge, new PskCipher(config.getPsk()),
                    registry,
                    (sess, id) -> new ClientStream(sess, id,
                            new dev.kifuko.mctransport.buffer.BufferBudget(
                                    config.getStreamBufferSize(), config.getGlobalBufferSize()),
                            new dev.kifuko.mctransport.buffer.ReservationState(),
                            config.getStreamBufferSize()),
                    new java.security.SecureRandom(),
                    System.currentTimeMillis());
            session.set(tunnelSession);
            tunnelSession.setPingIntervalMillis(15_000L);

            LocalTcpListener l = new LocalTcpListener(config, executors,
                    () -> session.get(), null);
            try {
                l.start();
            } catch (java.io.IOException e) {
                McTransport.LOGGER.error("failed to bind local listener on {}:{}",
                        config.getListenHost(), config.getListenPort(), e);
                return;
            }
            listener.set(l);
            McTransport.LOGGER.info("local listener bound to {}:{}",
                    config.getListenHost(), config.getListenPort());

            ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
                UUID playerUuid = extractClientPlayerUuid(client);
                if (playerUuid == null) {
                    McTransport.LOGGER.warn("client joined but player UUID was unavailable; AUTH not sent");
                    return;
                }
                ClientTunnelSession s = session.get();
                if (s != null) {
                    McTransport.LOGGER.info("client joined; sending AUTH for {}", playerUuid);
                    s.sendAuth(playerUuid, System.currentTimeMillis() / 1000L);
                }
            });
            ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
                McTransport.LOGGER.info("client disconnected; clearing tunnel state");
                LocalTcpListener activeListener = listener.getAndSet(null);
                if (activeListener != null) {
                    activeListener.stop();
                }
                ClientTunnelSession activeSession = session.getAndSet(null);
                if (activeSession != null) {
                    activeSession.close();
                }
                if (bridge != null) {
                    bridge.close();
                }
                if (executors != null) {
                    executors.shutdown();
                }
            });
        } catch (RuntimeException e) {
            McTransport.LOGGER.error("client init failed", e);
        }
    }

    static UUID extractClientPlayerUuid(Object client) {
        if (client == null) {
            return null;
        }
        try {
            Field playerField = client.getClass().getField("player");
            Object player = playerField.get(client);
            if (player == null) {
                return null;
            }
            for (String methodName : new String[]{"getUuid", "getUUID"}) {
                try {
                    Method getUuid = player.getClass().getMethod(methodName);
                    Object value = getUuid.invoke(player);
                    if (value instanceof UUID uuid) {
                        return uuid;
                    }
                } catch (NoSuchMethodException ignored) {
                    // Try the next known mapping.
                }
            }
            return null;
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }
}
