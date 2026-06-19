package dev.kifuko.mctransport.client;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
