package dev.frontlineand2engineers.ocradditions.menu;

import dev.frontlineand2engineers.ocradditions.OCRAdditions;
import dev.frontlineand2engineers.ocradditions.blockentity.CardRackBlockEntity;
import dev.frontlineand2engineers.ocradditions.oc.OcHardwareHelper;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.function.Predicate;

/**
 * Container menu for {@link CardRackBlockEntity}: 1 CPU slot + 8 expansion card slots.
 */
public class CardRackMenu extends AbstractContainerMenu {
    private final Container container;
    private final ContainerData data;

    public CardRackMenu(int containerId, Inventory playerInventory) {
        this(containerId, playerInventory, new SimpleContainer(CardRackBlockEntity.TOTAL_SLOTS), new SimpleContainerData(3));
    }

    public CardRackMenu(int containerId, Inventory playerInventory, Container container, ContainerData data) {
        super(OCRAdditions.CARD_RACK_MENU.get(), containerId);
        checkContainerSize(container, CardRackBlockEntity.TOTAL_SLOTS);
        checkContainerDataCount(data, 3);
        this.container = container;
        this.data = data;

        this.addSlot(filteredSlot(container, CardRackBlockEntity.SLOT_CPU, 18, 30, OcHardwareHelper::isCpu));

        for (int i = 0; i < CardRackBlockEntity.CARD_SLOT_COUNT; i++) {
            int col = i % 4;
            int row = i / 4;
            this.addSlot(filteredSlot(container, 1 + i, 68 + col * 22, 20 + row * 22, OcHardwareHelper::isCard));
        }

        addPlayerInventorySlots(this::addSlot, playerInventory, 84);
        addDataSlots(data);
    }

    public int getCpuTier() {
        return data.get(0);
    }

    public int getMaxActiveCards() {
        return data.get(1);
    }

    public int getActiveCardCount() {
        return data.get(2);
    }

    @Override
    public boolean stillValid(Player player) {
        return container.stillValid(player);
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        return quickMove(this, player, index, CardRackBlockEntity.TOTAL_SLOTS);
    }

    static Slot filteredSlot(Container container, int slot, int x, int y, Predicate<ItemStack> filter) {
        return new Slot(container, slot, x, y) {
            @Override
            public boolean mayPlace(ItemStack stack) {
                return filter.test(stack);
            }

            @Override
            public int getMaxStackSize() {
                return 1;
            }
        };
    }

    static void addPlayerInventorySlots(java.util.function.Function<Slot, Slot> adder, Inventory playerInv, int startY) {
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                adder.apply(new Slot(playerInv, col + row * 9 + 9, 8 + col * 18, startY + row * 18));
            }
        }
        for (int col = 0; col < 9; col++) {
            adder.apply(new Slot(playerInv, col, 8 + col * 18, startY + 58));
        }
    }

    static ItemStack quickMove(AbstractContainerMenu menu, Player player, int index, int containerSize) {
        Slot slot = menu.slots.get(index);
        if (!slot.hasItem()) {
            return ItemStack.EMPTY;
        }
        ItemStack stack = slot.getItem();
        ItemStack copy = stack.copy();

        if (index < containerSize) {
            if (!moveItemStackToRange(menu, stack, containerSize, menu.slots.size(), true)) {
                return ItemStack.EMPTY;
            }
        } else {
            if (!moveItemStackToRange(menu, stack, 0, containerSize, false)) {
                return ItemStack.EMPTY;
            }
        }

        if (stack.isEmpty()) {
            slot.setByPlayer(ItemStack.EMPTY);
        } else {
            slot.setChanged();
        }
        return copy;
    }

    private static boolean moveItemStackToRange(AbstractContainerMenu menu, ItemStack stack, int start, int end, boolean reverse) {
        boolean moved = false;
        int i = reverse ? end - 1 : start;
        while (!stack.isEmpty() && (reverse ? i >= start : i < end)) {
            Slot target = menu.slots.get(i);
            ItemStack existing = target.getItem();
            if (existing.isEmpty() && target.mayPlace(stack)) {
                int max = Math.min(stack.getCount(), target.getMaxStackSize(stack));
                target.setByPlayer(stack.split(max));
                target.setChanged();
                moved = true;
            } else if (!existing.isEmpty() && ItemStack.isSameItemSameComponents(stack, existing) && existing.getCount() < target.getMaxStackSize(existing)) {
                int transfer = Math.min(stack.getCount(), target.getMaxStackSize(existing) - existing.getCount());
                if (transfer > 0) {
                    existing.grow(transfer);
                    stack.shrink(transfer);
                    target.setChanged();
                    moved = true;
                }
            }
            i += reverse ? -1 : 1;
        }
        return moved;
    }
}
