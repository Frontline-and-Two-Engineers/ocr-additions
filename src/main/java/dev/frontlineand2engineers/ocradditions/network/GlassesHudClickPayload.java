package dev.frontlineand2engineers.ocradditions.network;

import dev.frontlineand2engineers.ocradditions.OCRAdditions;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Client-to-server click event when clicking an interactive HUD widget on Tier 2+ Smart Glasses.
 */
public record GlassesHudClickPayload(String widgetId, int button) implements CustomPacketPayload {

    public static final Type<GlassesHudClickPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(OCRAdditions.MODID, "glasses_hud_click"));

    public static final StreamCodec<RegistryFriendlyByteBuf, GlassesHudClickPayload> STREAM_CODEC =
            StreamCodec.of(GlassesHudClickPayload::write, GlassesHudClickPayload::read);

    private static void write(RegistryFriendlyByteBuf buf, GlassesHudClickPayload payload) {
        buf.writeUtf(payload.widgetId, 128);
        buf.writeVarInt(payload.button);
    }

    private static GlassesHudClickPayload read(RegistryFriendlyByteBuf buf) {
        String widgetId = buf.readUtf(128);
        int button = buf.readVarInt();
        return new GlassesHudClickPayload(widgetId, button);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
