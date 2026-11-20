package dev.frontlineand2engineers.ocradditions;

import dev.frontlineand2engineers.ocradditions.blockentity.OcRelayHelper;
import dev.frontlineand2engineers.ocradditions.blockentity.RadarBlockEntity;
import dev.frontlineand2engineers.ocradditions.client.ClientHudState;
import dev.frontlineand2engineers.ocradditions.compute.CryptoKernels;
import dev.frontlineand2engineers.ocradditions.compute.GpuComputeEngine;
import dev.frontlineand2engineers.ocradditions.compute.GpuComputeSession;
import dev.frontlineand2engineers.ocradditions.compute.GpuTierSpec;
import dev.frontlineand2engineers.ocradditions.compute.KernelProgram;
import dev.frontlineand2engineers.ocradditions.hud.Anchor;
import dev.frontlineand2engineers.ocradditions.hud.HudWidget;
import dev.frontlineand2engineers.ocradditions.item.SmartGlassesItem;
import dev.frontlineand2engineers.ocradditions.network.GlassesGazePayload;
import dev.frontlineand2engineers.ocradditions.network.GlassesHudClickPayload;
import dev.frontlineand2engineers.ocradditions.network.GlassesScreenSizePayload;
import dev.frontlineand2engineers.ocradditions.network.GlassesSoundPayload;
import dev.frontlineand2engineers.ocradditions.network.SyncHudPayload;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.ServerChatEvent;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import li.cil.oc.api.machine.Arguments;

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
        assertEquals(3, HudWidget.Type.WORLD_BOX.requiredTier);
        assertEquals(3, HudWidget.Type.ENTITY_MARKER.requiredTier);

        assertFalse(HudWidget.graph("g1", 0, 0, 100, 40, List.of(1f, 2f), 0xFFFFFF, 0).isWorldWidget());
        assertTrue(HudWidget.worldMarker("w1", 1, 2, 3, "Target", 0xFF0000).isWorldWidget());
        assertTrue(HudWidget.worldLine("l1", 0, 0, 0, 5, 5, 5, 0x00FF00).isWorldWidget());
        assertTrue(HudWidget.worldBox("b1", 0, 0, 0, 4, 4, 4, "Zone", 0xFF00E5FF).isWorldWidget());
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

        buf.clear();
        GlassesSoundPayload soundOut = new GlassesSoundPayload("minecraft:entity.experience_orb.pickup", 0.8f, 1.25f);
        GlassesSoundPayload.STREAM_CODEC.encode(buf, soundOut);
        assertEquals(soundOut, GlassesSoundPayload.STREAM_CODEC.decode(buf));

        buf.clear();
        GlassesScreenSizePayload sizeOut = new GlassesScreenSizePayload(640, 360);
        GlassesScreenSizePayload.STREAM_CODEC.encode(buf, sizeOut);
        assertEquals(sizeOut, GlassesScreenSizePayload.STREAM_CODEC.decode(buf));
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

        assertTrue(OcRelayHelper.isWithinRange(10, 0, 0, 0, 0, 0, 10));
        assertFalse(OcRelayHelper.isWithinRange(11, 0, 0, 0, 0, 0, 10));

        var radarDriver = dev.frontlineand2engineers.ocradditions.oc.RadarUpgradeDriver.INSTANCE;
        ItemStack radar1 = new ItemStack(OCRAdditions.RADAR_TIER_1_ITEM.get());
        ItemStack radar2 = new ItemStack(OCRAdditions.RADAR_TIER_2_ITEM.get());
        ItemStack radar3 = new ItemStack(OCRAdditions.RADAR_TIER_3_ITEM.get());
        assertTrue(radarDriver.worksWith(radar1));
        assertTrue(radarDriver.worksWith(radar2));
        assertTrue(radarDriver.worksWith(radar3));
        assertEquals(li.cil.oc.api.driver.item.Slot.Upgrade, radarDriver.slot(radar1));
        assertEquals(0, radarDriver.tier(radar1));
        assertEquals(1, radarDriver.tier(radar2));
        assertEquals(2, radarDriver.tier(radar3));
        assertTrue(radarDriver.worksWith(radar2, li.cil.oc.api.internal.Robot.class));
        assertTrue(radarDriver.worksWith(radar2, li.cil.oc.api.internal.Drone.class));
        assertTrue(radarDriver.worksWith(radar2, li.cil.oc.api.internal.Tablet.class));
        assertFalse(radarDriver.worksWith(radar2, li.cil.oc.api.network.EnvironmentHost.class));

        // Card Rack CPU tier -> max active cards (no CPU=0, T1=2, T2=4, T3=6, T4=8)
        assertEquals(0, dev.frontlineand2engineers.ocradditions.oc.OcHardwareHelper.getCardRackMaxCards(-1));
        assertEquals(2, dev.frontlineand2engineers.ocradditions.oc.OcHardwareHelper.getCardRackMaxCards(0));
        assertEquals(4, dev.frontlineand2engineers.ocradditions.oc.OcHardwareHelper.getCardRackMaxCards(1));
        assertEquals(6, dev.frontlineand2engineers.ocradditions.oc.OcHardwareHelper.getCardRackMaxCards(2));
        assertEquals(8, dev.frontlineand2engineers.ocradditions.oc.OcHardwareHelper.getCardRackMaxCards(3));

        // RAID v2 RAM level -> max active drives (T1=4 .. T4.5=18)
        assertEquals(0, dev.frontlineand2engineers.ocradditions.oc.OcHardwareHelper.getRaidMaxDisks(-1));
        assertEquals(4, dev.frontlineand2engineers.ocradditions.oc.OcHardwareHelper.getRaidMaxDisks(0));
        assertEquals(6, dev.frontlineand2engineers.ocradditions.oc.OcHardwareHelper.getRaidMaxDisks(1));
        assertEquals(8, dev.frontlineand2engineers.ocradditions.oc.OcHardwareHelper.getRaidMaxDisks(2));
        assertEquals(10, dev.frontlineand2engineers.ocradditions.oc.OcHardwareHelper.getRaidMaxDisks(3));
        assertEquals(12, dev.frontlineand2engineers.ocradditions.oc.OcHardwareHelper.getRaidMaxDisks(4));
        assertEquals(14, dev.frontlineand2engineers.ocradditions.oc.OcHardwareHelper.getRaidMaxDisks(5));
        assertEquals(16, dev.frontlineand2engineers.ocradditions.oc.OcHardwareHelper.getRaidMaxDisks(6));
        assertEquals(18, dev.frontlineand2engineers.ocradditions.oc.OcHardwareHelper.getRaidMaxDisks(7));

        // RAID v2 CPU tier -> max speed (T1=3/HDD T2, T2=4/HDD T3, T3+=6)
        assertEquals(0, dev.frontlineand2engineers.ocradditions.oc.OcHardwareHelper.getRaidCpuMaxSpeed(-1));
        assertEquals(3, dev.frontlineand2engineers.ocradditions.oc.OcHardwareHelper.getRaidCpuMaxSpeed(0));
        assertEquals(4, dev.frontlineand2engineers.ocradditions.oc.OcHardwareHelper.getRaidCpuMaxSpeed(1));
        assertEquals(6, dev.frontlineand2engineers.ocradditions.oc.OcHardwareHelper.getRaidCpuMaxSpeed(2));
        assertEquals(6, dev.frontlineand2engineers.ocradditions.oc.OcHardwareHelper.getRaidCpuMaxSpeed(3));

        var cardRack = new dev.frontlineand2engineers.ocradditions.blockentity.CardRackBlockEntity(
                BlockPos.ZERO, OCRAdditions.CARD_RACK.get().defaultBlockState());
        assertEquals(9, cardRack.getContainerSize());

        var raidV2 = new dev.frontlineand2engineers.ocradditions.blockentity.RaidV2BlockEntity(
                BlockPos.ZERO, OCRAdditions.RAID_V2.get().defaultBlockState());
        assertEquals(20, raidV2.getContainerSize());

        assertTrue(Config.cardRackEnabled);
        assertTrue(Config.raidV2Enabled);

        int prevRepeaterRange = Config.repeaterRange;
        try {
            Config.repeaterRange = 0;
            Object disabledRelay = new Object() {
                public boolean isWirelessEnabled() { return false; }
                public double strength() { return 400.0; }
            };
            Object enabledRelay = new Object() {
                public boolean isWirelessEnabled() { return true; }
                public double strength() { return 64.0; }
            };
            assertEquals(0.0, OcRelayHelper.resolveRelayRange(disabledRelay));
            assertEquals(64.0, OcRelayHelper.resolveRelayRange(enabledRelay));

            Config.repeaterRange = 128;
            assertEquals(128.0, OcRelayHelper.resolveRelayRange(disabledRelay));
        } finally {
            Config.repeaterRange = prevRepeaterRange;
        }
    }

    @Test
    void chatHandlerUsesHighestPriorityAndDefaultPrefix() throws NoSuchMethodException {
        assertEquals("$$", Config.defaultChatPrefix);
        assertTrue(Config.relativeCoordinates);

        Method m = OCRAdditions.class.getDeclaredMethod("onServerChat", ServerChatEvent.class);
        SubscribeEvent ann = m.getAnnotation(SubscribeEvent.class);
        assertNotNull(ann);
        assertEquals(EventPriority.HIGHEST, ann.priority());
    }

    @Test
    void gpuComputeTiersMiningAndKernelsWorkInParallel() throws Exception {
        assertEquals(16, GpuTierSpec.fromOcTier(0).cudaCores());
        assertEquals(64, GpuTierSpec.fromOcTier(1).cudaCores());
        assertEquals(256, GpuTierSpec.fromOcTier(2).cudaCores());
        assertEquals(512, GpuTierSpec.fromOcTier(3).cudaCores());

        byte[] minScratch = new byte[24];
        int minStart = CryptoKernels.writeAsciiLong(Long.MIN_VALUE, minScratch);
        assertEquals(Long.toString(Long.MIN_VALUE),
                new String(minScratch, minStart, minScratch.length - minStart));

        GpuComputeSession session = new GpuComputeSession(2, null);
        try {
            Map<String, Object> info = session.getComputeInfo();
            assertEquals(3, info.get("tier"));
            assertEquals(256, info.get("cudaCores"));

            // Player-written double-SHA256 mining kernel using basic primitives
            String powKernel = """
                    nonce = start + id
                    h = sha256(sha256(concat(header, nonce)))
                    ok = leadingZeroBits(h) >= bits
                    """;
            Integer kernelId = (Integer) session.compileKernel(powKernel)[0];
            assertNotNull(kernelId);

            Object[] res = session.runKernel(stubArgs(
                    8192L,
                    kernelId,
                    Map.of("header", "block#1:", "start", 100, "bits", 8),
                    Map.of("where", "ok", "out", Map.of(1, "nonce", 2, "h"), "stopOnFirst", true)
            ));
            assertInstanceOf(Integer.class, res[0]);
            Map<String, Object> snap = waitForJob(session, (Integer) res[0]);
            assertEquals("done", snap.get("status"));
            assertEquals(1L, ((Number) snap.get("matchCount")).longValue());
            List<?> rows = (List<?>) snap.get("result");
            assertEquals(1, rows.size());
            Map<?, ?> hit = (Map<?, ?>) rows.get(0);
            long nonce = ((Number) hit.get("nonce")).longValue();
            String hex = (String) hit.get("h");
            assertTrue(hex.startsWith("00"), "Expected >=8 zero bits, got " + hex);
            String expectedSingle = session.hashDirect("sha256", "block#1:" + nonce);
            assertEquals(session.hashDirect("sha256", expectedSingle), hex);

            // Bitwise + prefix kernel
            String bitwiseKernel = """
                    mix = bxor(id, rotl32(id, 5))
                    h = sha3(concat("tx:", mix))
                    ok = startsWith(h, "00")
                    """;
            Map<String, Object> bwSnap = waitForJob(session, (Integer) session.runKernel(stubArgs(
                    4096L, bitwiseKernel, Map.of(),
                    Map.of("where", "ok", "out", "h", "stopOnFirst", true)
            ))[0]);
            List<?> bwHits = (List<?>) bwSnap.get("result");
            assertFalse(bwHits.isEmpty());
            assertTrue(((String) bwHits.get(0)).startsWith("00"));

            // Array indexing + parallel sum reduction
            Map<Object, Object> vA = Map.of(1, 1.0, 2, 2.0, 3, 3.0);
            Map<Object, Object> vB = Map.of(1, 4.0, 2, 5.0, 3, 6.0);
            Map<String, Object> redSnap = waitForJob(session, (Integer) session.runKernel(stubArgs(
                    3L,
                    "prod = a[id] * b[id]",
                    Map.of("a", vA, "b", vB),
                    Map.of("out", "prod", "reduce", "sum")
            ))[0]);
            assertEquals("done", redSnap.get("status"), () -> "Failed redSnap: " + redSnap);
            assertEquals(32.0, ((Number) redSnap.get("reduced")).doubleValue(), 1e-9);

            // Built-in vector & matrix primitives
            Map<String, Object> dotSnap = waitForJob(session, (Integer) session.launchVectorOp(stubArgs("dot", vA, vB))[0]);
            assertEquals(32.0, ((Number) dotSnap.get("dot")).doubleValue(), 1e-9);

            Map<Object, Object> mA = Map.of(1, 1.0, 2, 2.0, 3, 3.0, 4, 4.0);
            Map<Object, Object> mB = Map.of(1, 5.0, 2, 6.0, 3, 7.0, 4, 8.0);
            Map<String, Object> matSnap = waitForJob(session, (Integer) session.launchMatrixMul(stubArgs(2, 2, 2, mA, mB))[0]);
            assertEquals(List.of(19.0, 22.0, 43.0, 50.0), matSnap.get("result"));

            assertTrue(session.dropKernel(kernelId));
            assertEquals("42", KernelProgram.formatNumber(42.0));
        } finally {
            session.dispose();
            GpuComputeEngine.shutdownAll();
        }
    }

    private static Arguments stubArgs(Object... values) {
        return (Arguments) Proxy.newProxyInstance(
                Arguments.class.getClassLoader(),
                new Class<?>[]{Arguments.class},
                (proxy, method, args) -> {
                    int idx = args != null && args.length > 0 && args[0] instanceof Integer i ? i : -1;
                    Object val = (idx >= 0 && idx < values.length) ? values[idx] : null;
                    return switch (method.getName()) {
                        case "count" -> values.length;
                        case "checkTable" -> (Map<?, ?>) val;
                        case "checkString" -> (String) val;
                        case "optString" -> val != null ? val : args[1];
                        case "checkInteger" -> ((Number) val).intValue();
                        case "checkLong" -> ((Number) val).longValue();
                        case "checkDouble" -> ((Number) val).doubleValue();
                        case "isDouble", "isInteger", "isLong" -> val instanceof Number;
                        case "checkAny" -> val;
                        default -> new HashMap<>();
                    };
                }
        );
    }

    @Test
    void graphicsCardClassReceivesComputeCallbacksViaMixin() throws Exception {
        Class<?> gpuClass = Class.forName("li.cil.oc.server.component.GraphicsCard");
        Set<String> callbackNames = new HashSet<>();
        for (Method method : gpuClass.getDeclaredMethods()) {
            if (method.isAnnotationPresent(li.cil.oc.api.machine.Callback.class)) {
                callbackNames.add(method.getName());
            }
        }
        assertTrue(callbackNames.containsAll(List.of(
                "getComputeInfo",
                "compileKernel",
                "dropKernel",
                "runKernel",
                "getComputeJob",
                "cancelComputeJob",
                "cancelAllCompute",
                "hash",
                "vectorOp",
                "matrixMul"
        )), "Missing CUDA compute callbacks on GraphicsCard: " + callbackNames);
    }

    @Test
    void lootDiskResourcesAndLuaProgramsExist() {
        ClassLoader cl = OCRAdditions.class.getClassLoader();
        List<String> expectedResources = List.of(
                "data/ocradditions/opencomputers/loot_disks/ocr_glasses.json",
                "data/ocradditions/opencomputers/loot_disks/ocr_glasses/install.lua",
                "data/ocradditions/opencomputers/loot_disks/ocr_glasses/README.txt",
                "data/ocradditions/opencomputers/loot_disks/ocr_glasses/usr/bin/glasses_t1.lua",
                "data/ocradditions/opencomputers/loot_disks/ocr_glasses/usr/bin/glasses_t2.lua",
                "data/ocradditions/opencomputers/loot_disks/ocr_glasses/usr/bin/glasses_t3.lua",
                "data/ocradditions/opencomputers/loot_disks/ocr_glasses/usr/bin/ar_radar.lua",
                "data/ocradditions/opencomputers/loot_disks/ocr_radar.json",
                "data/ocradditions/opencomputers/loot_disks/ocr_radar/install.lua",
                "data/ocradditions/opencomputers/loot_disks/ocr_radar/README.txt",
                "data/ocradditions/opencomputers/loot_disks/ocr_radar/usr/bin/radar_test.lua",
                "data/ocradditions/opencomputers/loot_disks/ocr_radar/usr/bin/radar_watch.lua",
                "data/ocradditions/opencomputers/loot_disks/ocr_cuda.json",
                "data/ocradditions/opencomputers/loot_disks/ocr_cuda/install.lua",
                "data/ocradditions/opencomputers/loot_disks/ocr_cuda/README.txt",
                "data/ocradditions/opencomputers/loot_disks/ocr_cuda/usr/bin/cuda_bench.lua",
                "data/ocradditions/opencomputers/loot_disks/ocr_cuda/usr/bin/crypto_miner.lua",
                "data/ocradditions/opencomputers/loot_disks/occoin_wallet.json",
                "data/ocradditions/opencomputers/loot_disks/occoin_wallet/install.lua",
                "data/ocradditions/opencomputers/loot_disks/occoin_wallet/README.txt",
                "data/ocradditions/opencomputers/loot_disks/occoin_wallet/usr/lib/occoin.lua",
                "data/ocradditions/opencomputers/loot_disks/occoin_wallet/usr/bin/occoin_wallet.lua"
        );
        for (String path : expectedResources) {
            assertNotNull(cl.getResource(path), "Missing loot disk resource: " + path);
        }
    }

    private static Map<String, Object> waitForJob(GpuComputeSession session, int jobId) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000L;
        while (System.currentTimeMillis() < deadline) {
            Map<String, Object> snap = session.getJobSnapshot(jobId);
            String st = (String) snap.get("status");
            if ("done".equals(st) || "failed".equals(st) || "canceled".equals(st)) {
                return snap;
            }
            Thread.sleep(5L);
        }
        fail("GPU compute job timed out");
        return Map.of();
    }
}
