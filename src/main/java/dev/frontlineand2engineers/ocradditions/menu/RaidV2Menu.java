package dev.frontlineand2engineers.ocradditions.menu;

import dev.frontlineand2engineers.ocradditions.OCRAdditions;
import dev.frontlineand2engineers.ocradditions.blockentity.RaidV2BlockEntity;
import dev.frontlineand2engineers.ocradditions.oc.OcHardwareHelper;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.item.ItemStack;

/**
 * Container menu for {@link RaidV2BlockEntity}: CPU + RAM + 18 drive slots.
 */
public class RaidV2Menu extends AbstractContainerMenu {
    private final Container container;
    private final ContainerData data;

    public RaidV2Menu(int containerId, Inventory playerInventory) {
        this(containerId, playerInventory, new SimpleContainer(RaidV2BlockEntity.TOTAL_SLOTS), new SimpleContainerData(6));
    }

    public RaidV2Menu(int containerId, Inventory playerInventory, Container container, ContainerData data) {
        super(OCRAdditions.RAID_V2_MENU.get(), containerId);
        checkContainerSize(container, RaidV2BlockEntity.TOTAL_SLOTS);
        checkContainerDataCount(data, 6);
        this.container = container;
        this.data = data;

        this.addSlot(CardRackMenu.filteredSlot(container, RaidV2BlockEntity.SLOT_CPU, 14, 24, OcHardwareHelper::isCpu));
        this.addSlot(CardRackMenu.filteredSlot(container, RaidV2BlockEntity.SLOT_RAM, 14, 48, OcHardwareHelper::isMemory));

        for (int i = 0; i < RaidV2BlockEntity.DRIVE_SLOT_COUNT; i++) {
            int col = i % 6;
            int row = i / 6;
            this.addSlot(CardRackMenu.filteredSlot(
                    container,
                    RaidV2BlockEntity.DRIVE_SLOT_START + i,
                    48 + col * 20,
                    20 + row * 20,
                    OcHardwareHelper::isStorageDrive
            ));
        }

        CardRackMenu.addPlayerInventorySlots(this::addSlot, playerInventory, 104);
        addDataSlots(data);
    }

    public int getCpuTier() {
        return data.get(0);
    }

    public int getMemoryLevel() {
        return data.get(1);
    }

    public int getMaxDisks() {
        return data.get(2);
    }

    public int getActiveDiskCount() {
        return data.get(3);
    }

    public int getActiveSpeed() {
        return data.get(4);
    }

    public int getTotalCapacityMb() {
        return data.get(5);
    }

    @Override
    public boolean stillValid(Player player) {
        return container.stillValid(player);
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        return CardRackMenu.quickMove(this, player, index, RaidV2BlockEntity.TOTAL_SLOTS);
    }
}
