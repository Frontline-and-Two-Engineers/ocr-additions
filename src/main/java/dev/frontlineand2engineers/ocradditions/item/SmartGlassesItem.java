package dev.frontlineand2engineers.ocradditions.item;

import dev.frontlineand2engineers.ocradditions.Config;
import dev.frontlineand2engineers.ocradditions.OCRAdditions;
import dev.frontlineand2engineers.ocradditions.blockentity.GlassesBridgeBlockEntity;
import net.minecraft.ChatFormatting;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Equipable;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.energy.IEnergyStorage;

import javax.annotation.Nullable;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Wearable Smart Glasses item in 3 tiers that binds to a Glasses Bridge block.
 */
public class SmartGlassesItem extends Item implements Equipable {
    private final int tier;

    public SmartGlassesItem(int tier, Properties properties) {
        super(properties);
        this.tier = Math.clamp(tier, 1, 3);
    }

    public int getTier() {
        return tier;
    }

    public static int getTier(ItemStack stack) {
        if (stack.isEmpty() || !(stack.getItem() instanceof SmartGlassesItem item)) {
            return 0;
        }
        return item.getTier();
    }

    @Override
    public EquipmentSlot getEquipmentSlot() {
        return EquipmentSlot.HEAD;
    }

    @Override
    public Holder<SoundEvent> getEquipSound() {
        return SoundEvents.ARMOR_EQUIP_IRON;
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        BlockEntity be = level.getBlockEntity(context.getClickedPos());
        if (be instanceof GlassesBridgeBlockEntity bridge) {
            if (!level.isClientSide) {
                UUID bridgeId = bridge.getBridgeId();
                ItemStack stack = context.getItemInHand();
                stack.set(OCRAdditions.BRIDGE_ID.get(), bridgeId);
                Player player = context.getPlayer();
                if (player != null) {
                    player.displayClientMessage(
                            Component.translatable("message.ocradditions.bound", bridgeId.toString().substring(0, 8))
                                    .withStyle(ChatFormatting.AQUA),
                            true
                    );
                }
            }
            return InteractionResult.sidedSuccess(level.isClientSide);
        }
        return super.useOn(context);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        return swapWithEquipmentSlot(this, level, player, hand);
    }

    @Override
    public boolean isBarVisible(ItemStack stack) {
        return Config.requireEnergy || getEnergy(stack) > 0;
    }

    @Override
    public int getBarWidth(ItemStack stack) {
        int cap = Math.max(1, Config.energyCapacity);
        return Math.round(13.0f * getEnergy(stack) / cap);
    }

    @Override
    public int getBarColor(ItemStack stack) {
        return Mth.hsvToRgb(0.52f, 0.9f, 0.95f);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("tooltip.ocradditions.glasses_tier_" + tier).withStyle(ChatFormatting.YELLOW));
        UUID bridgeId = getBridgeId(stack);
        if (bridgeId != null) {
            tooltip.add(Component.translatable("tooltip.ocradditions.bound", bridgeId.toString().substring(0, 8))
                    .withStyle(ChatFormatting.AQUA));
        } else {
            tooltip.add(Component.translatable("tooltip.ocradditions.unbound").withStyle(ChatFormatting.GRAY));
        }
        if (Config.requireEnergy || getEnergy(stack) > 0) {
            tooltip.add(Component.translatable("tooltip.ocradditions.energy", getEnergy(stack), Config.energyCapacity)
                    .withStyle(ChatFormatting.DARK_AQUA));
        }
        super.appendHoverText(stack, context, tooltip, flag);
    }

    @Nullable
    public static UUID getBridgeId(ItemStack stack) {
        if (stack.isEmpty() || !(stack.getItem() instanceof SmartGlassesItem)) {
            return null;
        }
        return stack.get(OCRAdditions.BRIDGE_ID.get());
    }

    public static int getEnergy(ItemStack stack) {
        Integer stored = stack.get(OCRAdditions.GLASSES_ENERGY.get());
        return stored != null ? Mth.clamp(stored, 0, Config.energyCapacity) : 0;
    }

    public static void setEnergy(ItemStack stack, int energy) {
        stack.set(OCRAdditions.GLASSES_ENERGY.get(), Mth.clamp(energy, 0, Config.energyCapacity));
    }

    public static boolean hasEnoughEnergy(ItemStack stack) {
        if (!Config.requireEnergy) return true;
        return getEnergy(stack) >= Config.energyPerTick;
    }

    public static void consumeEnergy(ItemStack stack) {
        if (!Config.requireEnergy || Config.energyPerTick <= 0) return;
        setEnergy(stack, getEnergy(stack) - Config.energyPerTick);
    }

    /**
     * Finds equipped Smart Glasses in the head armor slot or (if installed) Curios head slot.
     */
    public static ItemStack getEquippedGlasses(Player player) {
        ItemStack head = player.getItemBySlot(EquipmentSlot.HEAD);
        if (head.getItem() instanceof SmartGlassesItem) {
            return head;
        }
        if (ModList.get().isLoaded("curios")) {
            ItemStack curio = findInCurios(player);
            if (!curio.isEmpty()) {
                return curio;
            }
        }
        return ItemStack.EMPTY;
    }

    private static ItemStack findInCurios(Player player) {
        try {
            Class<?> curiosApi = Class.forName("top.theillusivec4.curios.api.CuriosApi");
            Class<?> curiosHandlerClass = Class.forName("top.theillusivec4.curios.api.type.capability.ICuriosItemHandler");
            Class<?> slotResultClass = Class.forName("top.theillusivec4.curios.api.SlotResult");
            Method getInventory = curiosApi.getMethod("getCuriosInventory", LivingEntity.class);
            Optional<?> invOpt = (Optional<?>) getInventory.invoke(null, player);
            if (invOpt.isPresent()) {
                Object handler = invOpt.get();
                Method findFirst = curiosHandlerClass.getMethod("findFirstCurio", Item.class);
                Method stackMethod = slotResultClass.getMethod("stack");
                for (Item item : List.of(
                        OCRAdditions.SMART_GLASSES_TIER_3.get(),
                        OCRAdditions.SMART_GLASSES_TIER_2.get(),
                        OCRAdditions.SMART_GLASSES_TIER_1.get()
                )) {
                    Optional<?> resultOpt = (Optional<?>) findFirst.invoke(handler, item);
                    if (resultOpt.isPresent()) {
                        return (ItemStack) stackMethod.invoke(resultOpt.get());
                    }
                }
            }
        } catch (Throwable ignored) {
            // Curios absent or incompatible API version.
        }
        return ItemStack.EMPTY;
    }

    public static class EnergyStorage implements IEnergyStorage {
        private final ItemStack stack;

        public EnergyStorage(ItemStack stack) {
            this.stack = stack;
        }

        @Override
        public int receiveEnergy(int toReceive, boolean simulate) {
            int current = getEnergy(stack);
            int received = Math.min(Config.energyCapacity - current, Math.max(0, toReceive));
            if (!simulate && received > 0) {
                setEnergy(stack, current + received);
            }
            return received;
        }

        @Override
        public int extractEnergy(int toExtract, boolean simulate) {
            int current = getEnergy(stack);
            int extracted = Math.min(current, Math.max(0, toExtract));
            if (!simulate && extracted > 0) {
                setEnergy(stack, current - extracted);
            }
            return extracted;
        }

        @Override
        public int getEnergyStored() {
            return getEnergy(stack);
        }

        @Override
        public int getMaxEnergyStored() {
            return Config.energyCapacity;
        }

        @Override
        public boolean canExtract() {
            return true;
        }

        @Override
        public boolean canReceive() {
            return true;
        }
    }
}
