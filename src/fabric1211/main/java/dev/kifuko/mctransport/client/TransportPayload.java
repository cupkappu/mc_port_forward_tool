package dev.kifuko.mctransport.client;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * Custom payload carrying raw encrypted frame bytes.
 *
 * <p>Both client and server mod share this record type and a single channel
 * identifier. The {@code TYPE} field is filled in once during mod
 * initialization and shared with the server mod via the same channel name.</p>
 */
public record TransportPayload(byte[] bytes) implements CustomPayload {

    /** Set by the client/server mod initializers during startup. */
    public static CustomPayload.Id<TransportPayload> ID;

    public static final PacketCodec<RegistryByteBuf, TransportPayload> CODEC =
            PacketCodec.of(
                    (value, buf) -> buf.writeBytes(value.bytes()),
                    buf -> new TransportPayload(readAll(buf)));

    @Override
    public Id<TransportPayload> getId() {
        return ID;
    }

    /** Convenience: build the payload id with an identifier. */
    public static CustomPayload.Id<TransportPayload> buildId(Identifier id) {
        return new CustomPayload.Id<>(id);
    }

    private static byte[] readAll(RegistryByteBuf buf) {
        byte[] bytes = new byte[buf.readableBytes()];
        buf.readBytes(bytes);
        return bytes;
    }
}
