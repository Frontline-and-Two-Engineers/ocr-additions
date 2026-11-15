package dev.frontlineand2engineers.ocradditions.network;

import dev.frontlineand2engineers.ocradditions.OCRAdditions;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Client-to-server gaze raycast report when pressing the Tier 3 Smart Glasses gaze key.
 */
public record GlassesGazePayload(
        TargetType targetType,
        BlockPos blockPos,
        Direction face,
        int entityId,
        double hitX,
        double hitY,
        double hitZ
) implements CustomPacketPayload {

    public enum TargetType {
        MISS,
        BLOCK,
        ENTITY
    }

    public static final Type<GlassesGazePayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(OCRAdditions.MODID, "glasses_gaze"));

    public static final StreamCodec<RegistryFriendlyByteBuf, GlassesGazePayload> STREAM_CODEC =
            StreamCodec.of(GlassesGazePayload::write, GlassesGazePayload::read);

    private static void write(RegistryFriendlyByteBuf buf, GlassesGazePayload payload) {
        buf.writeEnum(payload.targetType);
        buf.writeBlockPos(payload.blockPos);
        buf.writeEnum(payload.face);
        buf.writeVarInt(payload.entityId);
        buf.writeDouble(payload.hitX);
        buf.writeDouble(payload.hitY);
        buf.writeDouble(payload.hitZ);
    }

    private static GlassesGazePayload read(RegistryFriendlyByteBuf buf) {
        TargetType targetType = buf.readEnum(TargetType.class);
        BlockPos blockPos = buf.readBlockPos();
        Direction face = buf.readEnum(Direction.class);
        int entityId = buf.readVarInt();
        double hitX = buf.readDouble();
        double hitY = buf.readDouble();
        double hitZ = buf.readDouble();
        return new GlassesGazePayload(targetType, blockPos, face, entityId, hitX, hitY, hitZ);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
