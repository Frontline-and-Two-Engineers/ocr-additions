package dev.frontlineand2engineers.ocradditions;

import com.mojang.logging.LogUtils;
import com.mojang.serialization.Codec;
import dev.frontlineand2engineers.ocradditions.block.CardRackBlock;
import dev.frontlineand2engineers.ocradditions.block.GlassesBridgeBlock;
import dev.frontlineand2engineers.ocradditions.block.RadarBlock;
import dev.frontlineand2engineers.ocradditions.block.RaidV2Block;
import dev.frontlineand2engineers.ocradditions.blockentity.BridgeRegistry;
import dev.frontlineand2engineers.ocradditions.blockentity.CardRackBlockEntity;
import dev.frontlineand2engineers.ocradditions.blockentity.GlassesBridgeBlockEntity;
import dev.frontlineand2engineers.ocradditions.blockentity.RadarBlockEntity;
import dev.frontlineand2engineers.ocradditions.blockentity.RaidV2BlockEntity;
import dev.frontlineand2engineers.ocradditions.menu.CardRackMenu;
import dev.frontlineand2engineers.ocradditions.menu.RaidV2Menu;
import dev.frontlineand2engineers.ocradditions.client.ClientHudState;
import dev.frontlineand2engineers.ocradditions.client.SmartGlassesClientEvents;
import dev.frontlineand2engineers.ocradditions.compute.GpuComputeEngine;
import dev.frontlineand2engineers.ocradditions.item.SmartGlassesItem;
import dev.frontlineand2engineers.ocradditions.network.GlassesGazePayload;
import dev.frontlineand2engineers.ocradditions.network.GlassesHudClickPayload;
import dev.frontlineand2engineers.ocradditions.network.GlassesScreenSizePayload;
import dev.frontlineand2engineers.ocradditions.network.GlassesSoundPayload;
import dev.frontlineand2engineers.ocradditions.network.SyncHudPayload;
import li.cil.oc.api.network.Environment;
import net.minecraft.ChatFormatting;
import net.minecraft.core.Direction;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.capabilities.BlockCapability;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.ServerChatEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.util.List;
import java.util.UUID;

/**
 * Main entrypoint for OCR Additions, an OpenComputers Rebooted addon providing
 * 3-tier Smart Glasses with a Glasses Bridge peripheral and 3-tier Radar blocks.
 */
@Mod(OCRAdditions.MODID)
public class OCRAdditions {
    public static final String MODID = "ocradditions";
    public static final Logger LOGGER = LogUtils.getLogger();

    public static final BlockCapability<Environment, Direction> OC_ENVIRONMENT_CAPABILITY =
            BlockCapability.createSided(
                    ResourceLocation.fromNamespaceAndPath("opencomputers", "environment"),
                    Environment.class
            );

    public static final DeferredRegister.DataComponents DATA_COMPONENTS =
            DeferredRegister.createDataComponents(Registries.DATA_COMPONENT_TYPE, MODID);
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(MODID);
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MODID);
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, MODID);
    public static final DeferredRegister<net.minecraft.world.inventory.MenuType<?>> MENUS =
            DeferredRegister.create(Registries.MENU, MODID);
    public static final DeferredRegister<CreativeModeTab> CREATIVE_MODE_TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MODID);

    public static final DeferredHolder<DataComponentType<?>, DataComponentType<UUID>> BRIDGE_ID =
            DATA_COMPONENTS.registerComponentType("bridge_id",
                    builder -> builder.persistent(UUIDUtil.CODEC).networkSynchronized(UUIDUtil.STREAM_CODEC));

    public static final DeferredHolder<DataComponentType<?>, DataComponentType<Integer>> GLASSES_ENERGY =
            DATA_COMPONENTS.registerComponentType("glasses_energy",
                    builder -> builder.persistent(Codec.INT).networkSynchronized(ByteBufCodecs.VAR_INT));

    public static final DeferredBlock<GlassesBridgeBlock> GLASSES_BRIDGE = BLOCKS.register("glasses_bridge",
            () -> new GlassesBridgeBlock(metalBlockProperties()));
    public static final DeferredItem<BlockItem> GLASSES_BRIDGE_ITEM =
            ITEMS.registerSimpleBlockItem("glasses_bridge", GLASSES_BRIDGE);

    public static final DeferredBlock<RadarBlock> RADAR_TIER_1 = BLOCKS.register("radar_tier_1",
            () -> new RadarBlock(1, metalBlockProperties()));
    public static final DeferredItem<BlockItem> RADAR_TIER_1_ITEM =
            ITEMS.registerSimpleBlockItem("radar_tier_1", RADAR_TIER_1);

    public static final DeferredBlock<RadarBlock> RADAR_TIER_2 = BLOCKS.register("radar_tier_2",
            () -> new RadarBlock(2, metalBlockProperties()));
    public static final DeferredItem<BlockItem> RADAR_TIER_2_ITEM =
            ITEMS.registerSimpleBlockItem("radar_tier_2", RADAR_TIER_2);

    public static final DeferredBlock<RadarBlock> RADAR_TIER_3 = BLOCKS.register("radar_tier_3",
            () -> new RadarBlock(3, metalBlockProperties()));
    public static final DeferredItem<BlockItem> RADAR_TIER_3_ITEM =
            ITEMS.registerSimpleBlockItem("radar_tier_3", RADAR_TIER_3);

    public static final DeferredBlock<CardRackBlock> CARD_RACK = BLOCKS.register("card_rack",
            () -> new CardRackBlock(metalBlockProperties()));
    public static final DeferredItem<BlockItem> CARD_RACK_ITEM =
            ITEMS.registerSimpleBlockItem("card_rack", CARD_RACK);

    public static final DeferredBlock<RaidV2Block> RAID_V2 = BLOCKS.register("raid_v2",
            () -> new RaidV2Block(metalBlockProperties()));
    public static final DeferredItem<BlockItem> RAID_V2_ITEM =
            ITEMS.registerSimpleBlockItem("raid_v2", RAID_V2);

    public static final DeferredItem<SmartGlassesItem> SMART_GLASSES_TIER_1 =
            ITEMS.register("smart_glasses_tier_1", () -> new SmartGlassesItem(1, new Item.Properties().stacksTo(1)));
    public static final DeferredItem<SmartGlassesItem> SMART_GLASSES_TIER_2 =
            ITEMS.register("smart_glasses_tier_2", () -> new SmartGlassesItem(2, new Item.Properties().stacksTo(1)));
    public static final DeferredItem<SmartGlassesItem> SMART_GLASSES_TIER_3 =
            ITEMS.register("smart_glasses_tier_3", () -> new SmartGlassesItem(3, new Item.Properties().stacksTo(1)));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<GlassesBridgeBlockEntity>> GLASSES_BRIDGE_BE =
            BLOCK_ENTITIES.register("glasses_bridge",
                    () -> BlockEntityType.Builder.of(GlassesBridgeBlockEntity::new, GLASSES_BRIDGE.get()).build(null));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<RadarBlockEntity>> RADAR_BE =
            BLOCK_ENTITIES.register("radar",
                    () -> BlockEntityType.Builder.of(
                            RadarBlockEntity::new,
                            RADAR_TIER_1.get(),
                            RADAR_TIER_2.get(),
                            RADAR_TIER_3.get()
                    ).build(null));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<CardRackBlockEntity>> CARD_RACK_BE =
            BLOCK_ENTITIES.register("card_rack",
                    () -> BlockEntityType.Builder.of(CardRackBlockEntity::new, CARD_RACK.get()).build(null));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<RaidV2BlockEntity>> RAID_V2_BE =
            BLOCK_ENTITIES.register("raid_v2",
                    () -> BlockEntityType.Builder.of(RaidV2BlockEntity::new, RAID_V2.get()).build(null));

    public static final DeferredHolder<net.minecraft.world.inventory.MenuType<?>, net.minecraft.world.inventory.MenuType<CardRackMenu>> CARD_RACK_MENU =
            MENUS.register("card_rack",
                    () -> net.neoforged.neoforge.common.extensions.IMenuTypeExtension.create((id, inv, buf) -> new CardRackMenu(id, inv)));

    public static final DeferredHolder<net.minecraft.world.inventory.MenuType<?>, net.minecraft.world.inventory.MenuType<RaidV2Menu>> RAID_V2_MENU =
            MENUS.register("raid_v2",
                    () -> net.neoforged.neoforge.common.extensions.IMenuTypeExtension.create((id, inv, buf) -> new RaidV2Menu(id, inv)));

    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> OCR_ADDITIONS_TAB =
            CREATIVE_MODE_TABS.register("ocr_additions", () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.ocradditions"))
                    .icon(() -> SMART_GLASSES_TIER_3.get().getDefaultInstance())
                    .displayItems((parameters, output) -> {
                        output.accept(GLASSES_BRIDGE_ITEM.get());
                        output.accept(SMART_GLASSES_TIER_1.get());
                        output.accept(SMART_GLASSES_TIER_2.get());
                        output.accept(SMART_GLASSES_TIER_3.get());
                        output.accept(RADAR_TIER_1_ITEM.get());
                        output.accept(RADAR_TIER_2_ITEM.get());
                        output.accept(RADAR_TIER_3_ITEM.get());
                        if (Config.cardRackEnabled) {
                            output.accept(CARD_RACK_ITEM.get());
                        }
                        if (Config.raidV2Enabled) {
                            output.accept(RAID_V2_ITEM.get());
                        }
                    })
                    .build());

    public OCRAdditions(IEventBus modEventBus, ModContainer modContainer) {
        DATA_COMPONENTS.register(modEventBus);
        BLOCKS.register(modEventBus);
        ITEMS.register(modEventBus);
        BLOCK_ENTITIES.register(modEventBus);
        MENUS.register(modEventBus);
        CREATIVE_MODE_TABS.register(modEventBus);

        modEventBus.addListener(this::commonSetup);
        modEventBus.addListener(this::registerCapabilities);
        modEventBus.addListener(this::registerPayloads);
        modEventBus.addListener(Config::onLoad);

        NeoForge.EVENT_BUS.register(this);

        if (FMLEnvironment.dist == Dist.CLIENT) {
            SmartGlassesClientEvents.register(modEventBus);
        }

        modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC);
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(() -> {
            String[] archs = {"Lua 5.2", "Lua 5.3", "LuaJ"};
            for (String prog : List.of("glasses_t1", "glasses_t2", "glasses_t3", "ar_radar")) {
                li.cil.oc.api.IMC.registerProgramDiskLabel(prog, "OCR Glasses Tests", archs);
            }
            for (String prog : List.of("radar_test", "radar_watch")) {
                li.cil.oc.api.IMC.registerProgramDiskLabel(prog, "OCR Radar Tests", archs);
            }
            for (String prog : List.of("cuda_bench", "crypto_miner")) {
                li.cil.oc.api.IMC.registerProgramDiskLabel(prog, "OCR CUDA Tests", archs);
            }
            li.cil.oc.api.IMC.registerProgramDiskLabel("occoin_wallet", "OCCoin Wallet", archs);

            li.cil.oc.api.Driver.add((li.cil.oc.api.driver.DriverItem) dev.frontlineand2engineers.ocradditions.oc.RadarUpgradeDriver.INSTANCE);
            li.cil.oc.api.Driver.add((li.cil.oc.api.driver.EnvironmentProvider) dev.frontlineand2engineers.ocradditions.oc.RadarUpgradeDriver.INSTANCE);
        });
    }

    private static BlockBehaviour.Properties metalBlockProperties() {
        return BlockBehaviour.Properties.of()
                .mapColor(MapColor.METAL)
                .strength(2.5F)
                .sound(SoundType.METAL)
                .requiresCorrectToolForDrops();
    }

    private void registerCapabilities(RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(OC_ENVIRONMENT_CAPABILITY, GLASSES_BRIDGE_BE.get(), (be, side) -> be);
        event.registerBlockEntity(OC_ENVIRONMENT_CAPABILITY, RADAR_BE.get(), (be, side) -> be);
        event.registerBlockEntity(OC_ENVIRONMENT_CAPABILITY, CARD_RACK_BE.get(), (be, side) -> be);
        event.registerBlockEntity(OC_ENVIRONMENT_CAPABILITY, RAID_V2_BE.get(), (be, side) -> be);

        for (DeferredItem<SmartGlassesItem> glasses : List.of(
                SMART_GLASSES_TIER_1, SMART_GLASSES_TIER_2, SMART_GLASSES_TIER_3)) {
            event.registerItem(
                    Capabilities.EnergyStorage.ITEM,
                    (stack, ctx) -> new SmartGlassesItem.EnergyStorage(stack),
                    glasses.get()
            );
        }
    }

    private void registerPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");

        registrar.playToClient(
                SyncHudPayload.TYPE,
                SyncHudPayload.STREAM_CODEC,
                (payload, ctx) -> ctx.enqueueWork(() -> ClientHudState.apply(payload))
        );

        registrar.playToServer(
                GlassesGazePayload.TYPE,
                GlassesGazePayload.STREAM_CODEC,
                (payload, ctx) -> ctx.enqueueWork(() -> {
                    if (ctx.player() instanceof ServerPlayer player) {
                        GlassesBridgeBlockEntity bridge = resolveBridge(player);
                        if (bridge != null) {
                            bridge.handleGaze(player, payload);
                        }
                    }
                })
        );

        registrar.playToClient(
                GlassesSoundPayload.TYPE,
                GlassesSoundPayload.STREAM_CODEC,
                (payload, ctx) -> ctx.enqueueWork(() -> SmartGlassesClientEvents.playGlassesSound(payload))
        );

        registrar.playToServer(
                GlassesHudClickPayload.TYPE,
                GlassesHudClickPayload.STREAM_CODEC,
                (payload, ctx) -> ctx.enqueueWork(() -> {
                    if (ctx.player() instanceof ServerPlayer player) {
                        GlassesBridgeBlockEntity bridge = resolveBridge(player);
                        if (bridge != null) {
                            bridge.handleHudClick(player, payload);
                        }
                    }
                })
        );

        registrar.playToServer(
                GlassesScreenSizePayload.TYPE,
                GlassesScreenSizePayload.STREAM_CODEC,
                (payload, ctx) -> ctx.enqueueWork(() -> {
                    if (ctx.player() instanceof ServerPlayer player) {
                        GlassesBridgeBlockEntity bridge = resolveBridge(player);
                        if (bridge != null) {
                            bridge.handleScreenSize(player, payload);
                        }
                    }
                })
        );
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onServerChat(ServerChatEvent event) {
        ServerPlayer player = event.getPlayer();
        GlassesBridgeBlockEntity bridge = resolveBridge(player);
        if (bridge == null) {
            return;
        }
        String rawText = event.getRawText();
        if (rawText == null || rawText.isEmpty()) {
            rawText = event.getMessage().getString();
        }
        boolean handled = bridge.handleChatMessage(player, rawText);
        if (!handled) {
            String stripped = ChatFormatting.stripFormatting(rawText);
            if (stripped != null && !stripped.equals(rawText)) {
                handled = bridge.handleChatMessage(player, stripped);
            }
        }
        if (handled) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        GpuComputeEngine.shutdownAll();
    }

    @Nullable
    private static GlassesBridgeBlockEntity resolveBridge(ServerPlayer player) {
        ItemStack stack = SmartGlassesItem.getEquippedGlasses(player);
        if (stack.isEmpty() || !SmartGlassesItem.hasEnoughEnergy(stack)) {
            return null;
        }
        UUID bridgeId = SmartGlassesItem.getBridgeId(stack);
        return bridgeId != null ? BridgeRegistry.get(bridgeId) : null;
    }
}
