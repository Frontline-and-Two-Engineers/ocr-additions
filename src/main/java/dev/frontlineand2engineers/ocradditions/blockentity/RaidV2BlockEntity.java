package dev.frontlineand2engineers.ocradditions.blockentity;

import dev.frontlineand2engineers.ocradditions.Config;
import dev.frontlineand2engineers.ocradditions.OCRAdditions;
import dev.frontlineand2engineers.ocradditions.menu.RaidV2Menu;
import dev.frontlineand2engineers.ocradditions.oc.OcHardwareHelper;
import li.cil.oc.api.FileSystem;
import li.cil.oc.api.Network;
import li.cil.oc.api.UnrecoverablePersistanceException;
import li.cil.oc.api.fs.Label;
import li.cil.oc.api.network.Environment;
import li.cil.oc.api.network.EnvironmentHost;
import li.cil.oc.api.network.ManagedEnvironment;
import li.cil.oc.api.network.Message;
import li.cil.oc.api.network.Node;
import li.cil.oc.api.network.Visibility;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
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
import java.util.UUID;

/**
 * BlockEntity for RAID v2: combines up to 18 HDD/SSD drives into a single OC filesystem.
 *
 * <p>Requires both CPU (Slot 0) and RAM (Slot 1):
 * <ul>
 *   <li>RAM tier controls active drive count: T1=4, T1.5=6, T2=8, T2.5=10, T3=12, T3.5=14, T4=16, T4.5=18.</li>
 *   <li>CPU tier caps max speed: T1=3 (HDD T2), T2=4 (HDD T3), T3/T4=6 (limited only by slowest drive).</li>
 *   <li>Effective speed is always {@code min(cpuMaxSpeed, slowestActiveDriveSpeed)}.</li>
 * </ul>
 */
public class RaidV2BlockEntity extends BlockEntity implements Environment, EnvironmentHost, Container, MenuProvider {
    private static final Logger LOGGER = LoggerFactory.getLogger(RaidV2BlockEntity.class);
    public static final int SLOT_CPU = 0;
    public static final int SLOT_RAM = 1;
    public static final int DRIVE_SLOT_START = 2;
    public static final int DRIVE_SLOT_COUNT = OcHardwareHelper.MAX_RAID_SLOTS;
    public static final int TOTAL_SLOTS = 2 + DRIVE_SLOT_COUNT;

    private static final String OC_NODE_TAG = "oc:node";
    private static final String OC_FS_TAG = "oc:fs";
    private static final String RAID_ID_TAG = "raidId";
    private static final String DISK_SIG_TAG = "diskSig";
    private static final String LABEL_TAG = "raidLabel";

    private final Node node;
    private final NonNullList<ItemStack> items = NonNullList.withSize(TOTAL_SLOTS, ItemStack.EMPTY);
    private final RaidLabel label = new RaidLabel();

    @Nullable
    private ManagedEnvironment filesystem;
    private CompoundTag savedFsTag = new CompoundTag();
    private String raidId = "";
    private String diskSignature = "";
    private int activeSpeed;
    private int activeDiskCount;
    private long totalCapacityBytes;
    private boolean initialized;

    private final ContainerData containerData = new ContainerData() {
        @Override
        public int get(int index) {
            return switch (index) {
                case 0 -> getCpuTier();
                case 1 -> getMemoryLevel();
                case 2 -> getMaxDisks();
                case 3 -> activeDiskCount;
                case 4 -> activeSpeed;
                case 5 -> (int) Math.min(Integer.MAX_VALUE, totalCapacityBytes / (1024L * 1024L));
                default -> 0;
            };
        }

        @Override
        public void set(int index, int value) {
        }

        @Override
        public int getCount() {
            return 6;
        }
    };

    public RaidV2BlockEntity(BlockPos pos, BlockState state) {
        super(OCRAdditions.RAID_V2_BE.get(), pos, state);
        this.node = Network.newNode(this, Visibility.Network).create();
    }

    public int getCpuTier() {
        return OcHardwareHelper.getCpuTier(items.get(SLOT_CPU));
    }

    public int getMemoryLevel() {
        return OcHardwareHelper.getMemoryLevel(items.get(SLOT_RAM));
    }

    public int getMaxDisks() {
        return OcHardwareHelper.getRaidMaxDisks(getMemoryLevel());
    }

    public int getActiveSpeed() {
        return activeSpeed;
    }

    public int getActiveDiskCount() {
        return activeDiskCount;
    }

    public long getTotalCapacityBytes() {
        return totalCapacityBytes;
    }

    public ContainerData getContainerData() {
        return containerData;
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, RaidV2BlockEntity be) {
        if (level.isClientSide) {
            return;
        }
        if (!be.initialized) {
            be.initialized = true;
            if (be.node != null && be.node.network() == null) {
                Network.joinOrCreateNetwork(be);
            }
            be.rebuildRaid(false);
        }
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (level != null && !level.isClientSide) {
            Network.joinOrCreateNetwork(this);
            initialized = true;
            rebuildRaid(false);
        }
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
        teardownNetwork(false);
    }

    @Override
    public void onChunkUnloaded() {
        super.onChunkUnloaded();
        teardownNetwork(false);
    }

    public void teardownNetwork(boolean wipeContents) {
        detachFilesystem(wipeContents);
        if (node != null) {
            node.remove();
        }
    }

    private void detachFilesystem(boolean wipeContents) {
        if (filesystem == null) {
            return;
        }
        if (level != null && !wipeContents) {
            CompoundTag tag = new CompoundTag();
            filesystem.saveData(tag, level.registryAccess());
            savedFsTag = tag;
        } else if (wipeContents) {
            savedFsTag = new CompoundTag();
        }
        if (filesystem.node() != null) {
            filesystem.node().remove();
        }
        filesystem = null;
    }

    private void rebuildRaid(boolean allowWipeOnDiskChange) {
        if (level == null || level.isClientSide) {
            return;
        }
        if (!Config.raidV2Enabled) {
            this.activeSpeed = 0;
            detachFilesystem(false);
            return;
        }

        int cpuMaxSpeed = OcHardwareHelper.getRaidCpuMaxSpeed(getCpuTier());
        int maxDisks = getMaxDisks();

        StringBuilder sig = new StringBuilder();
        long sumBytes = 0L;
        int count = 0;
        int minSpeed = Integer.MAX_VALUE;

        for (int i = 0; i < maxDisks; i++) {
            ItemStack drive = items.get(DRIVE_SLOT_START + i);
            if (!drive.isEmpty() && OcHardwareHelper.isStorageDrive(drive)) {
                long cap = OcHardwareHelper.getDriveCapacityBytes(drive, this);
                int spd = OcHardwareHelper.getDriveSpeed(drive);
                if (cap > 0 && spd > 0) {
                    count++;
                    sumBytes += cap;
                    if (spd < minSpeed) {
                        minSpeed = spd;
                    }
                    sig.append(i).append(':')
                            .append(BuiltInRegistries.ITEM.getKey(drive.getItem()))
                            .append(':').append(cap).append(';');
                }
            }
        }

        this.activeDiskCount = count;
        this.totalCapacityBytes = sumBytes;
        String newSignature = sig.toString();

        if (allowWipeOnDiskChange && !newSignature.equals(this.diskSignature)) {
            detachFilesystem(true);
            this.diskSignature = newSignature;
            this.raidId = count > 0 ? UUID.randomUUID().toString() : "";
        } else if (this.diskSignature.isEmpty() && !newSignature.isEmpty()) {
            this.diskSignature = newSignature;
            if (this.raidId.isEmpty()) {
                this.raidId = UUID.randomUUID().toString();
            }
        }

        if (cpuMaxSpeed <= 0 || maxDisks <= 0 || count == 0 || sumBytes <= 0 || raidId.isEmpty()) {
            this.activeSpeed = 0;
            detachFilesystem(false);
            return;
        }

        int newSpeed = Math.min(cpuMaxSpeed, minSpeed);
        if (filesystem != null && this.activeSpeed == newSpeed) {
            return;
        }

        detachFilesystem(false);
        this.activeSpeed = newSpeed;

        li.cil.oc.api.fs.FileSystem ocFs = FileSystem.fromSaveDirectory(raidId, sumBytes, true);
        ManagedEnvironment env = FileSystem.asManagedEnvironment(
                ocFs, label, this, "opencomputers:hdd_access", newSpeed
        );
        if (env == null) {
            return;
        }
        if (!savedFsTag.isEmpty()) {
            try {
                env.loadData(savedFsTag, level.registryAccess());
            } catch (Exception e) {
                LOGGER.warn("Failed to restore RAIDv2 filesystem state", e);
            }
        }
        this.filesystem = env;
        OcHardwareHelper.connectNetworkVisibleComponent(node, this, env.node());
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider provider) {
        super.saveAdditional(tag, provider);
        ContainerHelper.saveAllItems(tag, items, provider);
        tag.putString(RAID_ID_TAG, raidId);
        tag.putString(DISK_SIG_TAG, diskSignature);
        if (label.getLabel(provider) != null) {
            tag.putString(LABEL_TAG, label.getLabel(provider));
        }
        if (node != null) {
            CompoundTag nodeTag = new CompoundTag();
            node.saveData(nodeTag, provider);
            tag.put(OC_NODE_TAG, nodeTag);
        }
        if (filesystem != null) {
            CompoundTag fsTag = new CompoundTag();
            filesystem.saveData(fsTag, provider);
            savedFsTag = fsTag;
            tag.put(OC_FS_TAG, fsTag);
        } else if (!savedFsTag.isEmpty()) {
            tag.put(OC_FS_TAG, savedFsTag.copy());
        }
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider provider) {
        super.loadAdditional(tag, provider);
        items.clear();
        ContainerHelper.loadAllItems(tag, items, provider);
        raidId = tag.getString(RAID_ID_TAG);
        diskSignature = tag.getString(DISK_SIG_TAG);
        if (tag.contains(LABEL_TAG, Tag.TAG_STRING)) {
            label.setLabel(tag.getString(LABEL_TAG));
        }
        if (node != null && tag.contains(OC_NODE_TAG, Tag.TAG_COMPOUND)) {
            try {
                node.loadData(tag.getCompound(OC_NODE_TAG), provider);
            } catch (UnrecoverablePersistanceException e) {
                LOGGER.warn("Failed to restore RAIDv2 OC node", e);
            }
        }
        if (tag.contains(OC_FS_TAG, Tag.TAG_COMPOUND)) {
            savedFsTag = tag.getCompound(OC_FS_TAG);
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
            onSlotChanged();
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
        onSlotChanged();
    }

    private void onSlotChanged() {
        setChanged();
        rebuildRaid(true);
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
        if (slot == SLOT_RAM) {
            return OcHardwareHelper.isMemory(stack);
        }
        return slot >= DRIVE_SLOT_START && slot < TOTAL_SLOTS && OcHardwareHelper.isStorageDrive(stack);
    }

    @Override
    public boolean stillValid(Player player) {
        return Container.stillValidBlockEntity(this, player);
    }

    @Override
    public void clearContent() {
        items.clear();
        onSlotChanged();
    }

    // --- MenuProvider ---

    @Override
    public Component getDisplayName() {
        return Component.translatable("block.ocradditions.raid_v2");
    }

    @Nullable
    @Override
    public AbstractContainerMenu createMenu(int containerId, Inventory playerInventory, Player player) {
        return new RaidV2Menu(containerId, playerInventory, this, containerData);
    }

    private class RaidLabel implements Label {
        private String value = "raidv2";

        @Override
        public String getLabel(HolderLookup.Provider provider) {
            return value;
        }

        @Override
        public void setLabel(String value) {
            this.value = value != null && value.length() > 16 ? value.substring(0, 16) : value;
            setChanged();
        }

        @Override
        public void loadData(CompoundTag nbt, HolderLookup.Provider provider) {
            if (nbt.contains("label", Tag.TAG_STRING)) {
                value = nbt.getString("label");
            }
        }

        @Override
        public void saveData(CompoundTag nbt, HolderLookup.Provider provider) {
            if (value != null) {
                nbt.putString("label", value);
            }
        }

        @Override
        public void loadData(net.minecraft.core.component.DataComponentHolder holder) {
        }

        @Override
        public void saveData(net.neoforged.neoforge.common.MutableDataComponentHolder holder) {
        }
    }
}
