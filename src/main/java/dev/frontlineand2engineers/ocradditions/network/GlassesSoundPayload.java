package dev.frontlineand2engineers.ocradditions.network;

import dev.frontlineand2engineers.ocradditions.OCRAdditions;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server-to-client private audio notification played on equipped Smart Glasses.
 */
public record GlassesSoundPayload(String soundId, float volume, float pitch)
        implements CustomPacketPayload {

    public static final Type<GlassesSoundPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(OCRAdditions.MODID, "glasses_sound"));

    public static final StreamCodec<RegistryFriendlyByteBuf, GlassesSoundPayload> STREAM_CODEC =
            StreamCodec.of(GlassesSoundPayload::write, GlassesSoundPayload::read);

    private static void write(RegistryFriendlyByteBuf buf, GlassesSoundPayload payload) {
        buf.writeUtf(payload.soundId, 256);
        buf.writeFloat(payload.volume);
        buf.writeFloat(payload.pitch);
    }

    private static GlassesSoundPayload read(RegistryFriendlyByteBuf buf) {
        String soundId = buf.readUtf(256);
        float volume = buf.readFloat();
        float pitch = buf.readFloat();
        return new GlassesSoundPayload(soundId, volume, pitch);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
