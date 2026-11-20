package dev.frontlineand2engineers.ocradditions.block;

import dev.frontlineand2engineers.ocradditions.Config;
import dev.frontlineand2engineers.ocradditions.OCRAdditions;
import dev.frontlineand2engineers.ocradditions.blockentity.RaidV2BlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

import javax.annotation.Nullable;
import java.util.List;

/**
 * OpenComputers RAID v2 block: combines up to 18 HDD/SSD drives based on installed CPU and RAM.
 */
public class RaidV2Block extends Block implements EntityBlock {
    public RaidV2Block(Properties properties) {
        super(properties);
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new RaidV2BlockEntity(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        if (level.isClientSide) {
            return null;
        }
        return type == OCRAdditions.RAID_V2_BE.get()
                ? (lvl, p, st, be) -> RaidV2BlockEntity.serverTick(lvl, p, st, (RaidV2BlockEntity) be)
                : null;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (!level.isClientSide && player instanceof ServerPlayer sp) {
            if (!Config.raidV2Enabled) {
                sp.displayClientMessage(Component.translatable("message.ocradditions.raid_v2_disabled"), true);
            } else if (level.getBlockEntity(pos) instanceof RaidV2BlockEntity raid) {
                sp.openMenu(raid, pos);
            }
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean isMoving) {
        if (!state.is(newState.getBlock())) {
            if (level.getBlockEntity(pos) instanceof RaidV2BlockEntity raid) {
                raid.teardownNetwork(true);
                Containers.dropContents(level, pos, raid);
                level.updateNeighbourForOutputSignal(pos, this);
            }
        }
        super.onRemove(state, level, pos, newState, isMoving);
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> tooltipComponents, TooltipFlag tooltipFlag) {
        tooltipComponents.add(Component.translatable("tooltip.ocradditions.raid_v2"));
    }
}
