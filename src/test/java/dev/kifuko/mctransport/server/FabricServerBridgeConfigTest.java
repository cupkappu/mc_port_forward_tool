package dev.kifuko.mctransport.server;

import dev.kifuko.mctransport.config.ServerConfig;
import dev.kifuko.mctransport.protocol.FrameCodec;
import dev.kifuko.mctransport.protocol.SecureFrameCodec;
import net.minecraft.util.Identifier;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FabricServerBridgeConfigTest {

    @Test
    void createdSessionUsesLoadedServerConfig() {
        ServerConfig config = new ServerConfig(true, "10.0.0.25", 19000,
                "mctransport:main", "not-default",
                List.of(UUID.randomUUID().toString()), 3, 2048, 4096,
                123, 7, "debug");
        TestBridge bridge = new TestBridge(config);

        PlayerTunnelSession session = bridge.newSessionForTest(new Object());

        assertEquals("10.0.0.25", session.config().getTargetHost());
        assertEquals(19000, session.config().getTargetPort());
        assertEquals("not-default", session.config().getPsk());
        assertEquals(3, session.config().getMaxStreamsPerPlayer());
        assertEquals(7, session.config().getConnectTimeoutSeconds());
    }

    private static final class TestBridge extends FabricServerTunnelBridge {
        private TestBridge(ServerConfig config) {
            super(Identifier.of("mctransport", "main"),
                    new FrameCodec(SecureFrameCodec.encryptedPayloadLimit(
                            config.getStreamBufferSize())),
                    config,
                    new TunnelExecutorsAdapter() {
                        @Override
                        public java.util.concurrent.ExecutorService io() {
                            return Executors.newSingleThreadExecutor();
                        }
                    });
        }

        PlayerTunnelSession newSessionForTest(Object player) {
            return createSession(player);
        }
    }
}
