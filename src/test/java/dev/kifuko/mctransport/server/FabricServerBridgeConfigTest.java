package dev.kifuko.mctransport.server;

import dev.kifuko.mctransport.config.RouteConfig;
import dev.kifuko.mctransport.config.ServerConfig;
import dev.kifuko.mctransport.protocol.FrameCodec;
import net.minecraft.util.Identifier;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FabricServerBridgeConfigTest {

    @Test
    void createdSessionUsesLoadedServerConfig() {
        UUID uuid = UUID.randomUUID();
        RouteConfig route = new RouteConfig(uuid, "Steve", 25580,
                "10.0.0.25", 19000);
        ServerConfig config = new ServerConfig(true, "mctransport:main",
                List.of(route), 3, 2048, 4096,
                123, 7, "debug");
        TestBridge bridge = new TestBridge(config);

        PlayerTunnelSession session = bridge.newSessionForTest(route, new Object());

        assertEquals(route, session.config().routeFor(uuid));
        assertEquals(3, session.config().getMaxStreamsPerPlayer());
        assertEquals(7, session.config().getConnectTimeoutSeconds());
        assertEquals(route, session.routeStore().routeFor(uuid));
    }

    @Test
    void playerScopedSendIsSerializedForConcurrentServerStreamReaders() throws Exception {
        Class<?> playerBridgeClass = Class.forName(
                "dev.kifuko.mctransport.server.FabricServerTunnelBridge$PlayerBridge");
        Method send = playerBridgeClass.getDeclaredMethod(
                "send", dev.kifuko.mctransport.protocol.Frame.class);

        assertTrue(Modifier.isSynchronized(send.getModifiers()));
    }

    private static final class TestBridge extends FabricServerTunnelBridge {
        private TestBridge(ServerConfig config) {
            super(Identifier.of("mctransport", "main"),
                    new FrameCodec(config.getStreamBufferSize()),
                    config,
                    new RouteStore(Path.of("build/tmp/test-route-store"),
                            "mctransport.server.toml", config),
                    new TunnelExecutorsAdapter() {
                        @Override
                        public java.util.concurrent.ExecutorService io() {
                            return Executors.newSingleThreadExecutor();
                        }
                    });
        }

        PlayerTunnelSession newSessionForTest(RouteConfig route, Object player) {
            return createSession(route, player);
        }
    }
}
