package dev.frontlineand2engineers.ocradditions.client;

import dev.frontlineand2engineers.ocradditions.blockentity.RaidV2BlockEntity;
import dev.frontlineand2engineers.ocradditions.menu.RaidV2Menu;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;

/**
 * Client screen for {@link RaidV2Menu}.
 */
public class RaidV2Screen extends AbstractContainerScreen<RaidV2Menu> {
    public RaidV2Screen(RaidV2Menu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
        this.imageWidth = 176;
        this.imageHeight = 186;
        this.inventoryLabelY = 93;
    }

    @Override
    protected void renderBg(GuiGraphics gfx, float partialTick, int mouseX, int mouseY) {
        CardRackScreen.drawPanel(gfx, leftPos, topPos, imageWidth, imageHeight);

        int maxDisks = menu.getMaxDisks();
        for (int i = 0; i < menu.slots.size(); i++) {
            Slot slot = menu.slots.get(i);
            boolean isDriveSlot = i >= RaidV2BlockEntity.DRIVE_SLOT_START
                    && i < RaidV2BlockEntity.TOTAL_SLOTS;
            boolean locked = isDriveSlot && (i - RaidV2BlockEntity.DRIVE_SLOT_START) >= maxDisks;
            boolean hwHighlight = i == RaidV2BlockEntity.SLOT_CPU || i == RaidV2BlockEntity.SLOT_RAM;
            int borderColor = locked ? 0xFF662828 : (hwHighlight ? 0xFF3A7CA5 : 0xFF555555);
            int fillColor = locked ? 0xFF231818 : 0xFF1A1A1A;
            CardRackScreen.drawSlotBox(gfx, leftPos + slot.x - 1, topPos + slot.y - 1, fillColor, borderColor);
        }

        gfx.drawString(font, "CPU", leftPos + 13, topPos + 15, 0xFFAAAAAA, false);
        gfx.drawString(font, "RAM", leftPos + 13, topPos + 67, 0xFFAAAAAA, false);

        String status = Component.translatable(
                "gui.ocradditions.raid_v2.status",
                menu.getActiveDiskCount(),
                maxDisks,
                menu.getActiveSpeed(),
                menu.getTotalCapacityMb()
        ).getString();
        gfx.drawString(font, status, leftPos + 48, topPos + 82, 0xFF55FFAA, false);
    }

    @Override
    public void render(GuiGraphics gfx, int mouseX, int mouseY, float partialTick) {
        super.render(gfx, mouseX, mouseY, partialTick);
        renderTooltip(gfx, mouseX, mouseY);
    }
}
