package dev.frontlineand2engineers.ocradditions;

import dev.frontlineand2engineers.ocradditions.blockentity.RadarBlockEntity;
import dev.frontlineand2engineers.ocradditions.client.ClientHudState;
import dev.frontlineand2engineers.ocradditions.hud.Anchor;
import dev.frontlineand2engineers.ocradditions.hud.HudWidget;
import dev.frontlineand2engineers.ocradditions.item.SmartGlassesItem;
import dev.frontlineand2engineers.ocradditions.network.GlassesGazePayload;
import dev.frontlineand2engineers.ocradditions.network.GlassesHudClickPayload;
import dev.frontlineand2engineers.ocradditions.network.SyncHudPayload;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.item.ItemStack;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests verifying HUD widget tier rules, anchors, client HUD sync state, and packet codecs.
 */
class OCRAdditionsUnitTest {

    @Test
    void anchorParsingAndFactors() {
        assertEquals(Anchor.TOP_LEFT, Anchor.fromName(null));
        assertEquals(Anchor.TOP_LEFT, Anchor.fromName("  "));
        assertEquals(Anchor.BOTTOM_RIGHT, Anchor.fromName("bottom-right"));
        assertEquals(Anchor.CENTER_LEFT, Anchor.fromName("Center Left"));
        assertEquals(0.5f, Anchor.CENTER.factorX);
        assertEquals(0.5f, Anchor.CENTER.factorY);
        assertThrows(IllegalArgumentException.class, () -> Anchor.fromName("invalid_anchor"));
    }

    @Test
    void widgetTierRequirementsMatchSpecification() {
        assertEquals(1, HudWidget.Type.RECT.requiredTier);
        assertEquals(1, HudWidget.Type.TEXT.requiredTier);
        assertEquals(1, HudWidget.Type.PROGRESS_BAR.requiredTier);
        assertEquals(1, HudWidget.Type.ITEM_ICON.requiredTier);
        assertEquals(2, HudWidget.Type.GRAPH.requiredTier);
        assertEquals(3, HudWidget.Type.WORLD_MARKER.requiredTier);
        assertEquals(3, HudWidget.Type.WORLD_LINE.requiredTier);
        assertEquals(3, HudWidget.Type.ENTITY_MARKER.requiredTier);

        assertFalse(HudWidget.graph("g1", 0, 0, 100, 40, List.of(1f, 2f), 0xFFFFFF, 0).isWorldWidget());
        assertTrue(HudWidget.worldMarker("w1", 1, 2, 3, "Target", 0xFF0000).isWorldWidget());
        assertTrue(HudWidget.worldLine("l1", 0, 0, 0, 5, 5, 5, 0x00FF00).isWorldWidget());
        assertTrue(HudWidget.entityMarker("e1", "00000000-0000-0000-0000-000000000001", "Boss", 0xFFFF00).isWorldWidget());
    }

    @Test
    void graphPointsAreCappedToMaxHistory() {
        List<Float> many = new ArrayList<>();
        for (int i = 0; i < 400; i++) {
            many.add((float) i);
        }
        HudWidget graph = HudWidget.graph("cpu", 10, 10, 120, 50, many, 0xFFFFFFFF, 0x80000000);
        assertEquals(HudWidget.MAX_GRAPH_POINTS, graph.points().size());
        assertEquals(400f - HudWidget.MAX_GRAPH_POINTS, graph.points().get(0));
        assertEquals(399f, graph.points().get(graph.points().size() - 1));
    }

    @Test
    void hudWidgetBufferRoundTrip() {
        HudWidget original = HudWidget.graph("g1", 12, 24, 100, 48, List.of(1.5f, 3.5f, 2.0f), 0xFF00FF00, 0x40000000)
                .withAnchor(Anchor.TOP_RIGHT)
                .withClickable(true);

        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        HudWidget.writeToBuf(buf, original);
        HudWidget decoded = HudWidget.readFromBuf(buf);

        assertEquals(original, decoded);
    }

    @Test
    void networkPayloadsRoundTrip() {
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);

        HudWidget text = HudWidget.text("t1", 5, 8, "Hello OCR", 0xFFFFFFFF, 1.5f);
        SyncHudPayload syncOut = new SyncHudPayload(true, List.of("old_1"), List.of(text));
        SyncHudPayload.STREAM_CODEC.encode(buf, syncOut);
        SyncHudPayload syncIn = SyncHudPayload.STREAM_CODEC.decode(buf);
        assertEquals(syncOut, syncIn);

        buf.clear();
        GlassesGazePayload gazeOut = new GlassesGazePayload(
                GlassesGazePayload.TargetType.BLOCK,
                new BlockPos(10, 64, -12),
                Direction.UP,
                42,
                10.5, 65.0, -11.25
        );
        GlassesGazePayload.STREAM_CODEC.encode(buf, gazeOut);
        GlassesGazePayload gazeIn = GlassesGazePayload.STREAM_CODEC.decode(buf);
        assertEquals(gazeOut, gazeIn);

        buf.clear();
        GlassesHudClickPayload clickOut = new GlassesHudClickPayload("btn_reboot", 1);
        GlassesHudClickPayload.STREAM_CODEC.encode(buf, clickOut);
        GlassesHudClickPayload clickIn = GlassesHudClickPayload.STREAM_CODEC.decode(buf);
        assertEquals(clickOut, clickIn);
    }

    @Test
    void clientHudStateAppliesUpsertsRemovesAndClearAll() {
        ClientHudState.clear();
        HudWidget w1 = HudWidget.rect("r1", 0, 0, 20, 20, 0xFFFFFFFF);
        HudWidget w2 = HudWidget.text("t1", 10, 10, "A", 0xFFFFFFFF, 1f);

        ClientHudState.apply(new SyncHudPayload(false, List.of(), List.of(w1, w2)));
        assertEquals(2, ClientHudState.snapshot().size());

        ClientHudState.apply(new SyncHudPayload(false, List.of("r1"), List.of()));
        assertEquals(1, ClientHudState.snapshot().size());
        assertEquals("t1", ClientHudState.snapshot().get(0).id());

        ClientHudState.apply(new SyncHudPayload(true, List.of(), List.of()));
        assertTrue(ClientHudState.snapshot().isEmpty());
    }

    @Test
    void registeredBlocksItemsAndTiersWorkInServerEnvironment() {
        assertEquals(1, OCRAdditions.RADAR_TIER_1.get().getTier());
        assertEquals(2, OCRAdditions.RADAR_TIER_2.get().getTier());
        assertEquals(3, OCRAdditions.RADAR_TIER_3.get().getTier());

        RadarBlockEntity r1 = new RadarBlockEntity(BlockPos.ZERO, OCRAdditions.RADAR_TIER_1.get().defaultBlockState());
        RadarBlockEntity r2 = new RadarBlockEntity(BlockPos.ZERO, OCRAdditions.RADAR_TIER_2.get().defaultBlockState());
        RadarBlockEntity r3 = new RadarBlockEntity(BlockPos.ZERO, OCRAdditions.RADAR_TIER_3.get().defaultBlockState());
        assertEquals(64.0, r1.getMaxRangeValue());
        assertEquals(128.0, r2.getMaxRangeValue());
        assertEquals(256.0, r3.getMaxRangeValue());
        assertNotNull(r1.node());

        ItemStack g1 = new ItemStack(OCRAdditions.SMART_GLASSES_TIER_1.get());
        ItemStack g2 = new ItemStack(OCRAdditions.SMART_GLASSES_TIER_2.get());
        ItemStack g3 = new ItemStack(OCRAdditions.SMART_GLASSES_TIER_3.get());
        assertEquals(1, SmartGlassesItem.getTier(g1));
        assertEquals(2, SmartGlassesItem.getTier(g2));
        assertEquals(3, SmartGlassesItem.getTier(g3));

        UUID id = UUID.randomUUID();
        g2.set(OCRAdditions.BRIDGE_ID.get(), id);
        assertEquals(id, SmartGlassesItem.getBridgeId(g2));

        SmartGlassesItem.EnergyStorage energy = new SmartGlassesItem.EnergyStorage(g2);
        int added = energy.receiveEnergy(500, false);
        assertEquals(500, added);
        assertEquals(500, energy.getEnergyStored());
        assertEquals(200, energy.extractEnergy(200, false));
        assertEquals(300, SmartGlassesItem.getEnergy(g2));
    }
}
