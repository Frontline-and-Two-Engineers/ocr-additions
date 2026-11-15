package dev.frontlineand2engineers.ocradditions;

import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Common configuration for OCR Additions.
 */
public final class Config {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    private static final ModConfigSpec.BooleanValue RELATIVE_COORDINATES = BUILDER
            .comment("If true, Smart Glasses Bridge and Tier 3 Radar report and accept coordinates relative to the block instead of absolute world coordinates.")
            .define("relativeCoordinates", true);

    private static final ModConfigSpec.ConfigValue<String> DEFAULT_CHAT_PREFIX = BUILDER
            .comment("Default chat command prefix for Tier 2+ Smart Glasses (can be overridden per bridge via API).")
            .define("defaultChatPrefix", "$$", obj -> obj instanceof String s && !s.isBlank() && s.length() <= 16);

    private static final ModConfigSpec.IntValue MAX_RANGE = BUILDER
            .comment("Maximum distance in blocks from the Glasses Bridge for Smart Glasses to stay connected (0 = unlimited).")
            .defineInRange("maxRange", 256, 0, 100000);

    private static final ModConfigSpec.BooleanValue ALLOW_CROSS_DIMENSION = BUILDER
            .comment("Whether Smart Glasses remain connected across dimensions (requires maxRange = 0 or same dimension).")
            .define("allowCrossDimension", false);

    private static final ModConfigSpec.BooleanValue REQUIRE_ENERGY = BUILDER
            .comment("If true, Smart Glasses require FE energy to stay active and render HUD/AR overlays.")
            .define("requireEnergy", false);

    private static final ModConfigSpec.IntValue ENERGY_CAPACITY = BUILDER
            .comment("Maximum FE energy capacity of Smart Glasses.")
            .defineInRange("energyCapacity", 100000, 100, 10000000);

    private static final ModConfigSpec.IntValue ENERGY_PER_TICK = BUILDER
            .comment("FE energy consumed per tick while Smart Glasses are active (only when requireEnergy = true).")
            .defineInRange("energyPerTick", 5, 0, 10000);

    private static final ModConfigSpec.DoubleValue RADAR_SCAN_ENERGY_COST = BUILDER
            .comment("OpenComputers network energy consumed per radar.scan() call (0 = free).")
            .defineInRange("radarScanEnergyCost", 0.0, 0.0, 100000.0);

    public static final ModConfigSpec SPEC = BUILDER.build();

    public static boolean relativeCoordinates = true;
    public static String defaultChatPrefix = "$$";
    public static int maxRange = 256;
    public static boolean allowCrossDimension = false;
    public static boolean requireEnergy = false;
    public static int energyCapacity = 100000;
    public static int energyPerTick = 5;
    public static double radarScanEnergyCost = 0.0;

    private Config() {
    }

    public static void onLoad(final ModConfigEvent event) {
        relativeCoordinates = RELATIVE_COORDINATES.get();
        defaultChatPrefix = DEFAULT_CHAT_PREFIX.get().trim();
        maxRange = MAX_RANGE.get();
        allowCrossDimension = ALLOW_CROSS_DIMENSION.get();
        requireEnergy = REQUIRE_ENERGY.get();
        energyCapacity = ENERGY_CAPACITY.get();
        energyPerTick = ENERGY_PER_TICK.get();
        radarScanEnergyCost = RADAR_SCAN_ENERGY_COST.get();
    }
}
