package dev.frontlineand2engineers.ocradditions.client;

import dev.frontlineand2engineers.ocradditions.blockentity.CardRackBlockEntity;
import dev.frontlineand2engineers.ocradditions.menu.CardRackMenu;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;

/**
 * Client screen for {@link CardRackMenu}.
 */
public class CardRackScreen extends AbstractContainerScreen<CardRackMenu> {
    public CardRackScreen(CardRackMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
        this.imageWidth = 176;
        this.imageHeight = 166;
        this.inventoryLabelY = 73;
    }

    @Override
    protected void renderBg(GuiGraphics gfx, float partialTick, int mouseX, int mouseY) {
        drawPanel(gfx, leftPos, topPos, imageWidth, imageHeight);

        int maxActive = menu.getMaxActiveCards();
        for (int i = 0; i < menu.slots.size(); i++) {
            Slot slot = menu.slots.get(i);
            boolean locked = i >= 1 && i <= CardRackBlockEntity.CARD_SLOT_COUNT && (i - 1) >= maxActive;
            boolean cpuHighlight = i == 0;
            int borderColor = locked ? 0xFF662828 : (cpuHighlight ? 0xFF3A7CA5 : 0xFF555555);
            int fillColor = locked ? 0xFF231818 : 0xFF1A1A1A;
            drawSlotBox(gfx, leftPos + slot.x - 1, topPos + slot.y - 1, fillColor, borderColor);
        }

        gfx.drawString(font, "CPU", leftPos + 17, topPos + 19, 0xFFAAAAAA, false);
        String status = Component.translatable(
                "gui.ocradditions.card_rack.status",
                menu.getActiveCardCount(),
                maxActive
        ).getString();
        gfx.drawString(font, status, leftPos + 68, topPos + 67, 0xFF55FFAA, false);
    }

    @Override
    public void render(GuiGraphics gfx, int mouseX, int mouseY, float partialTick) {
        super.render(gfx, mouseX, mouseY, partialTick);
        renderTooltip(gfx, mouseX, mouseY);
    }

    static void drawPanel(GuiGraphics gfx, int x, int y, int w, int h) {
        gfx.fill(x, y, x + w, y + h, 0xFFC6C6C6);
        gfx.fill(x + 1, y + 1, x + w - 1, y + h - 1, 0xFF2B2E33);
        gfx.fill(x, y, x + w, y + 1, 0xFFFFFFFF);
        gfx.fill(x, y, x + 1, y + h, 0xFFFFFFFF);
        gfx.fill(x + w - 1, y, x + w, y + h, 0xFF15171A);
        gfx.fill(x, y + h - 1, x + w, y + h, 0xFF15171A);
    }

    static void drawSlotBox(GuiGraphics gfx, int x, int y, int fillColor, int borderColor) {
        gfx.fill(x, y, x + 18, y + 18, borderColor);
        gfx.fill(x + 1, y + 1, x + 17, y + 17, fillColor);
    }
}
