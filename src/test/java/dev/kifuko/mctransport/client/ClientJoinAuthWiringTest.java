package dev.kifuko.mctransport.client;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientJoinAuthWiringTest {

    @Test
    void extractClientPlayerUuidReadsPlayerGetUUIDForJoinAuth() {
        UUID uuid = UUID.fromString("11111111-2222-3333-4444-555555555555");
        FakeClient client = new FakeClient(uuid);

        assertEquals(uuid, McTransportClient.extractClientPlayerUuid(client));
    }

    @Test
    void extractClientPlayerUuidReadsYarnGetUuidForJoinAuth() {
        UUID uuid = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
        YarnClient client = new YarnClient(uuid);

        assertEquals(uuid, McTransportClient.extractClientPlayerUuid(client));
    }

    @Test
    void e2eQuickJoinTargetIgnoresBlankProperty() {
        String old = System.getProperty("mctransport.e2e.quickJoin");
        try {
            System.setProperty("mctransport.e2e.quickJoin", "  ");

            assertNull(McTransportClient.e2eQuickJoinTarget());
        } finally {
            restoreQuickJoinProperty(old);
        }
    }

    @Test
    void e2eQuickJoinTargetTrimsConfiguredAddress() {
        String old = System.getProperty("mctransport.e2e.quickJoin");
        try {
            System.setProperty("mctransport.e2e.quickJoin", " 127.0.0.1:25565 ");

            assertEquals("127.0.0.1:25565", McTransportClient.e2eQuickJoinTarget());
        } finally {
            restoreQuickJoinProperty(old);
        }
    }

    @Test
    void e2eQuickJoinReadinessRequiresStableClientWithoutOverlay() {
        int readyTicks = 0;

        readyTicks = McTransportClient.nextE2eQuickJoinReadyTicks(
                readyTicks, false, true);
        assertEquals(0, readyTicks);

        readyTicks = McTransportClient.nextE2eQuickJoinReadyTicks(
                readyTicks, true, true);
        assertEquals(1, readyTicks);
        assertFalse(McTransportClient.shouldAttemptE2eQuickJoin(readyTicks));

        for (int i = 1; i < McTransportClient.E2E_QUICK_JOIN_READY_TICKS; i++) {
            readyTicks = McTransportClient.nextE2eQuickJoinReadyTicks(
                    readyTicks, true, true);
        }
        assertTrue(McTransportClient.shouldAttemptE2eQuickJoin(readyTicks));

        readyTicks = McTransportClient.nextE2eQuickJoinReadyTicks(
                readyTicks, true, false);
        assertEquals(0, readyTicks);
    }

    private static void restoreQuickJoinProperty(String old) {
        if (old == null) {
            System.clearProperty("mctransport.e2e.quickJoin");
        } else {
            System.setProperty("mctransport.e2e.quickJoin", old);
        }
    }

    public static final class FakeClient {
        public final FakePlayer player;

        FakeClient(UUID uuid) {
            this.player = new FakePlayer(uuid);
        }
    }

    public static final class FakePlayer {
        private final UUID uuid;

        FakePlayer(UUID uuid) {
            this.uuid = uuid;
        }

        public UUID getUUID() {
            return uuid;
        }
    }

    public static final class YarnClient {
        public final YarnPlayer player;

        YarnClient(UUID uuid) {
            this.player = new YarnPlayer(uuid);
        }
    }

    public static final class YarnPlayer {
        private final UUID uuid;

        YarnPlayer(UUID uuid) {
            this.uuid = uuid;
        }

        public UUID getUuid() {
            return uuid;
        }
    }
}
