package dev.kifuko.mctransport.client;

import dev.kifuko.mctransport.McTransport;
import dev.kifuko.mctransport.net.TransportExecutors;
import dev.kifuko.mctransport.protocol.FrameCodec;
import dev.kifuko.mctransport.stream.StreamRegistry;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.screen.multiplayer.ConnectScreen;
import net.minecraft.client.gui.screen.multiplayer.MultiplayerScreen;
import net.minecraft.client.network.ServerAddress;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.util.Identifier;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Client-side Fabric entrypoint. Builds the tunnel session and waits for
 * the server to push listener config.
 */
public final class McTransportClient implements ClientModInitializer {

    private static final String DEFAULT_CHANNEL = "mctransport:main";
    private static final int DEFAULT_MAX_STREAMS = 64;
    private static final int DEFAULT_STREAM_BUFFER_SIZE = 1_048_576;
    private static final long DEFAULT_GLOBAL_BUFFER_SIZE = 33_554_432L;
    static final int E2E_QUICK_JOIN_READY_TICKS = 40;

    private final AtomicReference<ClientTunnelSession> session = new AtomicReference<>();
    private final AtomicReference<ClientListenerController> listenerController = new AtomicReference<>();
    private final AtomicBoolean e2eQuickJoinStarted = new AtomicBoolean(false);
    private final AtomicInteger e2eQuickJoinReadyTicks = new AtomicInteger(0);
    private TransportExecutors executors;
    private FabricClientTunnelBridge bridge;

    @Override
    public void onInitializeClient() {
        McTransport.LOGGER.info("MC Transport Dialer client loaded");
        try {
            executors = new TransportExecutors(McTransport.MOD_ID);
            FrameCodec codec = new FrameCodec(DEFAULT_STREAM_BUFFER_SIZE);
            StreamRegistry registry = new StreamRegistry(DEFAULT_MAX_STREAMS, true);

            String[] parts = DEFAULT_CHANNEL.split(":");
            Identifier channelId = Identifier.of(parts[0], parts[1]);
            TransportPayload.ID = TransportPayload.buildId(channelId);
            PayloadTypeRegistry.playC2S().register(TransportPayload.ID,
                    TransportPayload.CODEC);
            PayloadTypeRegistry.playS2C().register(TransportPayload.ID,
                    TransportPayload.CODEC);

            bridge = new FabricClientTunnelBridge(channelId, codec,
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

            ClientListenerController controller = new DynamicLocalTcpListenerController(
                    executors, () -> session.get(), null);
            listenerController.set(controller);
            ClientTunnelSession tunnelSession = new ClientTunnelSession(
                    bridge,
                    registry,
                    (sess, id) -> new ClientStream(sess, id,
                            new dev.kifuko.mctransport.buffer.BufferBudget(
                                    DEFAULT_STREAM_BUFFER_SIZE, DEFAULT_GLOBAL_BUFFER_SIZE),
                            new dev.kifuko.mctransport.buffer.ReservationState(),
                            DEFAULT_STREAM_BUFFER_SIZE),
                    System.currentTimeMillis(),
                    controller);
            session.set(tunnelSession);
            tunnelSession.setPingIntervalMillis(15_000L);

            ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
                McTransport.LOGGER.info("client joined; waiting for server route config");
                if (bridge != null) {
                    bridge.flushPending();
                }
            });
            ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
                McTransport.LOGGER.info("client disconnected; clearing tunnel state");
                ClientListenerController activeController = listenerController.get();
                if (activeController != null) {
                    activeController.clear();
                }
                ClientTunnelSession activeSession = session.get();
                if (activeSession != null) {
                    activeSession.close();
                }
            });
            registerE2eQuickJoinIfRequested();
        } catch (RuntimeException e) {
            McTransport.LOGGER.error("client init failed", e);
        }
    }

    private void registerE2eQuickJoinIfRequested() {
        String target = e2eQuickJoinTarget();
        if (target == null) {
            return;
        }
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            boolean overlayClear = client.getOverlay() == null;
            boolean disconnected = client.world == null && client.player == null;
            int readyTicks = e2eQuickJoinReadyTicks.updateAndGet(current ->
                    nextE2eQuickJoinReadyTicks(current, overlayClear,
                            disconnected));
            if (!shouldAttemptE2eQuickJoin(readyTicks)) {
                return;
            }
            if (!e2eQuickJoinStarted.compareAndSet(false, true)) {
                return;
            }
            McTransport.LOGGER.info("E2E quick join connecting to {}", target);
            ServerInfo info = new ServerInfo("MC Transport E2E", target,
                    ServerInfo.ServerType.OTHER);
            ConnectScreen.connect(new MultiplayerScreen(new TitleScreen()), client,
                    ServerAddress.parse(target), info, true, null);
        });
    }

    static String e2eQuickJoinTarget() {
        String target = System.getProperty("mctransport.e2e.quickJoin");
        if (target == null || target.trim().isEmpty()) {
            return null;
        }
        return target.trim();
    }

    static int nextE2eQuickJoinReadyTicks(int currentTicks, boolean overlayClear,
            boolean disconnected) {
        if (!overlayClear || !disconnected) {
            return 0;
        }
        return currentTicks + 1;
    }

    static boolean shouldAttemptE2eQuickJoin(int readyTicks) {
        return readyTicks >= E2E_QUICK_JOIN_READY_TICKS;
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
