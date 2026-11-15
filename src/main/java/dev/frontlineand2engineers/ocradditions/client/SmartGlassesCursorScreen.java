package dev.frontlineand2engineers.ocradditions.client;

import dev.frontlineand2engineers.ocradditions.hud.HudWidget;
import dev.frontlineand2engineers.ocradditions.item.SmartGlassesItem;
import dev.frontlineand2engineers.ocradditions.network.GlassesHudClickPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Transparent overlay screen allowing players wearing Tier 2+ Smart Glasses to click HUD widgets.
 */
public class SmartGlassesCursorScreen extends Screen {

    public SmartGlassesCursorScreen() {
        super(Component.translatable("screen.ocradditions.cursor"));
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void renderBackground(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        // Keep world and HUD completely clear without darkening.
    }

    private int currentTier() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return 0;
        return SmartGlassesItem.getTier(SmartGlassesItem.getEquippedGlasses(mc.player));
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);

        HudWidget hovered = SmartGlassesHudRenderer.hitTest(mouseX, mouseY, width, height, font, currentTier());
        if (hovered != null) {
            SmartGlassesHudRenderer.Bounds b = SmartGlassesHudRenderer.getWidgetBounds(hovered, width, height, font);
            if (b != null) {
                graphics.renderOutline(b.x1() - 1, b.y1() - 1, (b.x2() - b.x1()) + 2, (b.y2() - b.y1()) + 2, 0xFF00E5FF);
            }
            graphics.renderTooltip(font, Component.literal(hovered.id()), mouseX, mouseY);
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        HudWidget clicked = SmartGlassesHudRenderer.hitTest(mouseX, mouseY, width, height, font, currentTier());
        if (clicked != null) {
            PacketDistributor.sendToServer(new GlassesHudClickPayload(clicked.id(), button));
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (SmartGlassesClientEvents.CURSOR_KEY.matches(keyCode, scanCode)) {
            onClose();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }
}
