package dev.frontlineand2engineers.ocradditions.network;

import dev.frontlineand2engineers.ocradditions.OCRAdditions;
import dev.frontlineand2engineers.ocradditions.hud.HudWidget;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

/**
 * Server-to-client HUD and 3D AR widget sync packet.
 */
public record SyncHudPayload(boolean clearAll, List<String> removes, List<HudWidget> upserts)
        implements CustomPacketPayload {

    public static final Type<SyncHudPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(OCRAdditions.MODID, "sync_hud"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SyncHudPayload> STREAM_CODEC =
            StreamCodec.of(SyncHudPayload::write, SyncHudPayload::read);

    private static void write(RegistryFriendlyByteBuf buf, SyncHudPayload payload) {
        buf.writeBoolean(payload.clearAll);
        buf.writeVarInt(payload.removes.size());
        for (String id : payload.removes) {
            buf.writeUtf(id, 128);
        }
        buf.writeVarInt(payload.upserts.size());
        for (HudWidget widget : payload.upserts) {
            HudWidget.writeToBuf(buf, widget);
        }
    }

    private static SyncHudPayload read(RegistryFriendlyByteBuf buf) {
        boolean clearAll = buf.readBoolean();
        int removeCount = buf.readVarInt();
        List<String> removes = new ArrayList<>(removeCount);
        for (int i = 0; i < removeCount; i++) {
            removes.add(buf.readUtf(128));
        }
        int upsertCount = buf.readVarInt();
        List<HudWidget> upserts = new ArrayList<>(upsertCount);
        for (int i = 0; i < upsertCount; i++) {
            upserts.add(HudWidget.readFromBuf(buf));
        }
        return new SyncHudPayload(clearAll, List.copyOf(removes), List.copyOf(upserts));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
