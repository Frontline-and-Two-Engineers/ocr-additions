package dev.frontlineand2engineers.ocradditions.blockentity;

import dev.frontlineand2engineers.ocradditions.Config;
import dev.frontlineand2engineers.ocradditions.OCRAdditions;
import dev.frontlineand2engineers.ocradditions.menu.CardRackMenu;
import dev.frontlineand2engineers.ocradditions.oc.OcHardwareHelper;
import li.cil.oc.api.Network;
import li.cil.oc.api.UnrecoverablePersistanceException;
import li.cil.oc.api.driver.DriverItem;
import li.cil.oc.api.network.Environment;
import li.cil.oc.api.network.EnvironmentHost;
import li.cil.oc.api.network.ManagedEnvironment;
import li.cil.oc.api.network.Message;
import li.cil.oc.api.network.Node;
import li.cil.oc.api.network.Visibility;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Container;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;

/**
 * BlockEntity for the Card Rack: hosts 1 CPU (slot 0) and up to 8 OC expansion cards (slots 1..8).
 * Active slot capacity depends on CPU tier (T1=2, T2=4, T3=6, T4=8).
 * Connected cards share the OC network and consume energy just like inside a computer.
 */
public class CardRackBlockEntity extends BlockEntity implements Environment, EnvironmentHost, Container, MenuProvider {
    private static final Logger LOGGER = LoggerFactory.getLogger(CardRackBlockEntity.class);
    public static final int SLOT_CPU = 0;
    public static final int CARD_SLOT_COUNT = OcHardwareHelper.MAX_CARD_RACK_SLOTS;
    public static final int TOTAL_SLOTS = 1 + CARD_SLOT_COUNT;

    private static final String OC_NODE_TAG = "oc:node";
    private static final String CARD_ENV_TAGS = "oc:cardEnvs";

    private final Node node;
    private final NonNullList<ItemStack> items = NonNullList.withSize(TOTAL_SLOTS, ItemStack.EMPTY);
    private final ManagedEnvironment[] environments = new ManagedEnvironment[CARD_SLOT_COUNT];
    private final Node[] proxyNodes = new Node[CARD_SLOT_COUNT];
    private final CompoundTag[] savedCardTags = new CompoundTag[CARD_SLOT_COUNT];
    private boolean initialized;

    private final ContainerData containerData = new ContainerData() {
        @Override
        public int get(int index) {
            return switch (index) {
                case 0 -> getCpuTier();
                case 1 -> getMaxActiveCards();
                case 2 -> getActiveCardCount();
                default -> 0;
            };
        }

        @Override
        public void set(int index, int value) {
        }

        @Override
        public int getCount() {
            return 3;
        }
    };

    public CardRackBlockEntity(BlockPos pos, BlockState state) {
        super(OCRAdditions.CARD_RACK_BE.get(), pos, state);
        this.node = Network.newNode(this, Visibility.Network)
                .withConnector(1024)
                .create();
        for (int i = 0; i < CARD_SLOT_COUNT; i++) {
            savedCardTags[i] = new CompoundTag();
        }
    }

    public int getCpuTier() {
        return OcHardwareHelper.getCpuTier(items.get(SLOT_CPU));
    }

    public int getMaxActiveCards() {
        return Config.cardRackEnabled ? OcHardwareHelper.getCardRackMaxCards(getCpuTier()) : 0;
    }

    public int getActiveCardCount() {
        int max = getMaxActiveCards();
        int count = 0;
        for (int i = 0; i < max; i++) {
            if (!items.get(1 + i).isEmpty() && environments[i] != null) {
                count++;
            }
        }
        return count;
    }

    public ContainerData getContainerData() {
        return containerData;
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, CardRackBlockEntity be) {
        if (level.isClientSide) {
            return;
        }
        if (!be.initialized) {
            be.initialized = true;
            if (be.node != null && be.node.network() == null) {
                Network.joinOrCreateNetwork(be);
            }
            be.syncCardEnvironments();
        }
        if (!Config.cardRackEnabled) {
            for (int i = 0; i < CARD_SLOT_COUNT; i++) {
                be.detachCardEnvironment(i);
            }
            return;
        }
        for (ManagedEnvironment env : be.environments) {
            if (env != null && env.canUpdate()) {
                env.update();
            }
        }
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (level != null && !level.isClientSide) {
            Network.joinOrCreateNetwork(this);
            initialized = true;
            syncCardEnvironments();
        }
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
        teardownNetwork();
    }

    @Override
    public void onChunkUnloaded() {
        super.onChunkUnloaded();
        teardownNetwork();
    }

    public void teardownNetwork() {
        for (int i = 0; i < CARD_SLOT_COUNT; i++) {
            detachCardEnvironment(i);
        }
        if (node != null) {
            node.remove();
        }
    }

    private void syncCardEnvironments() {
        if (level == null || level.isClientSide) {
            return;
        }
        if (node != null && node.network() == null) {
            Network.joinOrCreateNetwork(this);
        }
        int maxActive = getMaxActiveCards();
        for (int i = 0; i < CARD_SLOT_COUNT; i++) {
            ItemStack stack = items.get(1 + i);
            boolean shouldBeActive = i < maxActive && !stack.isEmpty() && OcHardwareHelper.isCard(stack);
            if (!shouldBeActive) {
                detachCardEnvironment(i);
            } else if (environments[i] == null) {
                attachCardEnvironment(i, stack);
            }
        }
    }

    private void attachCardEnvironment(int cardIndex, ItemStack stack) {
        DriverItem driver = OcHardwareHelper.findDriver(stack);
        if (driver == null) {
            return;
        }
        ManagedEnvironment env = driver.createEnvironment(stack, this);
        if (env == null) {
            return;
        }
        if (level != null && !savedCardTags[cardIndex].isEmpty()) {
            try {
                env.loadData(savedCardTags[cardIndex], level.registryAccess());
            } catch (Exception e) {
                LOGGER.warn("Failed to restore card environment data in slot {}", cardIndex, e);
            }
        }
        environments[cardIndex] = env;
        proxyNodes[cardIndex] = OcHardwareHelper.connectNetworkVisibleComponent(node, this, env.node());
    }

    private void detachCardEnvironment(int cardIndex) {
        if (proxyNodes[cardIndex] != null) {
            proxyNodes[cardIndex].remove();
            proxyNodes[cardIndex] = null;
        }
        ManagedEnvironment env = environments[cardIndex];
        if (env == null) {
            return;
        }
        if (level != null) {
            CompoundTag tag = new CompoundTag();
            try {
                env.saveData(tag, level.registryAccess());
                savedCardTags[cardIndex] = tag;
            } catch (Exception e) {
                LOGGER.warn("Failed to save card environment data in slot {}", cardIndex, e);
            }
        }
        if (env.node() != null) {
            env.node().remove();
        }
        environments[cardIndex] = null;
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider provider) {
        super.saveAdditional(tag, provider);
        ContainerHelper.saveAllItems(tag, items, provider);
        if (node != null) {
            CompoundTag nodeTag = new CompoundTag();
            node.saveData(nodeTag, provider);
            tag.put(OC_NODE_TAG, nodeTag);
        }
        ListTag cardList = new ListTag();
        for (int i = 0; i < CARD_SLOT_COUNT; i++) {
            CompoundTag cardTag = new CompoundTag();
            if (environments[i] != null) {
                environments[i].saveData(cardTag, provider);
                savedCardTags[i] = cardTag;
            } else if (!savedCardTags[i].isEmpty()) {
                cardTag = savedCardTags[i].copy();
            }
            cardList.add(cardTag);
        }
        tag.put(CARD_ENV_TAGS, cardList);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider provider) {
        super.loadAdditional(tag, provider);
        items.clear();
        ContainerHelper.loadAllItems(tag, items, provider);
        if (node != null && tag.contains(OC_NODE_TAG, Tag.TAG_COMPOUND)) {
            try {
                node.loadData(tag.getCompound(OC_NODE_TAG), provider);
            } catch (UnrecoverablePersistanceException e) {
                LOGGER.warn("Failed to restore Card Rack OC node", e);
            }
        }
        if (tag.contains(CARD_ENV_TAGS, Tag.TAG_LIST)) {
            ListTag cardList = tag.getList(CARD_ENV_TAGS, Tag.TAG_COMPOUND);
            for (int i = 0; i < Math.min(CARD_SLOT_COUNT, cardList.size()); i++) {
                savedCardTags[i] = cardList.getCompound(i);
            }
        }
    }

    // --- OpenComputers Environment / EnvironmentHost ---

    @Override
    public Node node() {
        return node;
    }

    @Override
    public void onConnect(Node other) {
    }

    @Override
    public void onDisconnect(Node other) {
    }

    @Override
    public void onMessage(Message message) {
    }

    @Override
    public Level getEnvironmentLevel() {
        return level;
    }

    @Override
    public double xPosition() {
        return worldPosition.getX() + 0.5;
    }

    @Override
    public double yPosition() {
        return worldPosition.getY() + 0.5;
    }

    @Override
    public double zPosition() {
        return worldPosition.getZ() + 0.5;
    }

    @Override
    public void markChanged() {
        setChanged();
    }

    // --- Container ---

    @Override
    public int getContainerSize() {
        return TOTAL_SLOTS;
    }

    @Override
    public boolean isEmpty() {
        for (ItemStack stack : items) {
            if (!stack.isEmpty()) {
                return false;
            }
        }
        return true;
    }

    @Override
    public ItemStack getItem(int slot) {
        return items.get(slot);
    }

    @Override
    public ItemStack removeItem(int slot, int amount) {
        ItemStack removed = ContainerHelper.removeItem(items, slot, amount);
        if (!removed.isEmpty()) {
            onSlotChanged(slot);
        }
        return removed;
    }

    @Override
    public ItemStack removeItemNoUpdate(int slot) {
        return ContainerHelper.takeItem(items, slot);
    }

    @Override
    public void setItem(int slot, ItemStack stack) {
        items.set(slot, stack);
        if (stack.getCount() > getMaxStackSize()) {
            stack.setCount(getMaxStackSize());
        }
        onSlotChanged(slot);
    }

    private void onSlotChanged(int slot) {
        if (slot > 0 && slot <= CARD_SLOT_COUNT) {
            // Reset saved card environment if item was swapped/removed in that card slot.
            detachCardEnvironment(slot - 1);
            savedCardTags[slot - 1] = new CompoundTag();
        }
        setChanged();
        syncCardEnvironments();
    }

    @Override
    public int getMaxStackSize() {
        return 1;
    }

    @Override
    public boolean canPlaceItem(int slot, ItemStack stack) {
        if (slot == SLOT_CPU) {
            return OcHardwareHelper.isCpu(stack);
        }
        return slot >= 1 && slot < TOTAL_SLOTS && OcHardwareHelper.isCard(stack);
    }

    @Override
    public boolean stillValid(Player player) {
        return Container.stillValidBlockEntity(this, player);
    }

    @Override
    public void clearContent() {
        items.clear();
        syncCardEnvironments();
    }

    // --- MenuProvider ---

    @Override
    public Component getDisplayName() {
        return Component.translatable("block.ocradditions.card_rack");
    }

    @Nullable
    @Override
    public AbstractContainerMenu createMenu(int containerId, Inventory playerInventory, Player player) {
        return new CardRackMenu(containerId, playerInventory, this, containerData);
    }
}
