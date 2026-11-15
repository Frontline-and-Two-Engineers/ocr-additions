package dev.frontlineand2engineers.ocradditions.block;

import com.mojang.serialization.MapCodec;
import dev.frontlineand2engineers.ocradditions.OCRAdditions;
import dev.frontlineand2engineers.ocradditions.blockentity.GlassesBridgeBlockEntity;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

import javax.annotation.Nullable;

/**
 * OpenComputers Rebooted peripheral block that bridges a computer to bound Smart Glasses.
 */
public class GlassesBridgeBlock extends Block implements EntityBlock {
    public static final MapCodec<GlassesBridgeBlock> CODEC = simpleCodec(GlassesBridgeBlock::new);

    public GlassesBridgeBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends Block> codec() {
        return CODEC;
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new GlassesBridgeBlockEntity(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        if (level.isClientSide) return null;
        return type == OCRAdditions.GLASSES_BRIDGE_BE.get()
                ? (lvl, pos, st, be) -> ((GlassesBridgeBlockEntity) be).serverTick()
                : null;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (!level.isClientSide && level.getBlockEntity(pos) instanceof GlassesBridgeBlockEntity bridge) {
            player.displayClientMessage(
                    Component.translatable(
                            "message.ocradditions.bridge_info",
                            bridge.getBridgeId().toString().substring(0, 8),
                            bridge.getChatPrefix()
                    ).withStyle(ChatFormatting.AQUA),
                    true
            );
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }
}
