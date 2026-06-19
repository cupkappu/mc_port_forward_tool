package dev.kifuko.mctransport.server;

import dev.kifuko.mctransport.buffer.BufferBudget;
import dev.kifuko.mctransport.buffer.ReservationState;
import dev.kifuko.mctransport.config.ServerConfig;
import dev.kifuko.mctransport.crypto.PskCipher;
import dev.kifuko.mctransport.net.FakeTunnelBridge;
import dev.kifuko.mctransport.protocol.FrameType;
import dev.kifuko.mctransport.stream.StreamRegistry;
import org.junit.jupiter.api.Test;

import java.net.Socket;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ServerStreamBackpressureTest {

    @Test
    void targetBytesWaitForBudgetInsteadOfBeingDropped() throws Exception {
        FakeTunnelBridge bridge = new FakeTunnelBridge();
        BufferBudget budget = new BufferBudget(8, 8);
        ReservationState reservations = new ReservationState();
        budget.reserve(99, 8, reservations);
        ServerConfig config = new ServerConfig(true, "127.0.0.1", 10000,
                "mctransport:main", "shared",
                List.of(UUID.randomUUID().toString()), 8, 8, 8,
                300, 10, "info");
        PlayerTunnelSession session = new PlayerTunnelSession(config, bridge,
                new PskCipher("shared"), new StreamRegistry(8, false),
                budget, reservations,
                new TargetTcpConnector("127.0.0.1", 10000, 10,
                        Executors.newSingleThreadExecutor()),
                1_700_000_000L, 0L,
                new NoopServerStreamFactoryForTest());
        ServerStream stream = new ServerStream(session, 99, new Socket(), budget,
                reservations, PlayerTunnelSession.PROTOCOL_VERSION, 8);
        byte[] chunk = new byte[]{1, 2, 3, 4};

        Future<?> blockedSend = Executors.newSingleThreadExecutor()
                .submit(() -> stream.sendTargetBytes(chunk, chunk.length));
        Thread.sleep(100);
        assertEquals(0, bridge.sentFrames().size(), "full budget must not drop or send bytes");

        budget.release(99, 8, reservations);
        blockedSend.get(2, TimeUnit.SECONDS);

        assertEquals(1, bridge.sentFrames().size());
        assertEquals(FrameType.DATA, bridge.sentFrames().get(0).type());
        assertArrayEquals(chunk, bridge.sentFrames().get(0).payload());
    }
}
