package dev.frontlineand2engineers.ocradditions.network;

import dev.frontlineand2engineers.ocradditions.OCRAdditions;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Client-to-server report of the wearer's current scaled GUI screen resolution.
 */
public record GlassesScreenSizePayload(int width, int height)
        implements CustomPacketPayload {

    public static final Type<GlassesScreenSizePayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(OCRAdditions.MODID, "glasses_screen_size"));

    public static final StreamCodec<RegistryFriendlyByteBuf, GlassesScreenSizePayload> STREAM_CODEC =
            StreamCodec.of(GlassesScreenSizePayload::write, GlassesScreenSizePayload::read);

    private static void write(RegistryFriendlyByteBuf buf, GlassesScreenSizePayload payload) {
        buf.writeVarInt(payload.width);
        buf.writeVarInt(payload.height);
    }

    private static GlassesScreenSizePayload read(RegistryFriendlyByteBuf buf) {
        int width = buf.readVarInt();
        int height = buf.readVarInt();
        return new GlassesScreenSizePayload(width, height);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
