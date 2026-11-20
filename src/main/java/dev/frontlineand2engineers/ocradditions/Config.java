package dev.frontlineand2engineers.ocradditions;

import dev.frontlineand2engineers.ocradditions.compute.GpuTierSpec;
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

    private static final ModConfigSpec.IntValue REPEATER_RANGE = BUILDER
            .comment("Coverage radius in blocks around each OpenComputers Relay on the bridge network. 0 = use the Relay's Wireless Network Card strength() (requires a wireless card in the Relay); >0 = fixed radius override.")
            .defineInRange("repeaterRange", 0, 0, 100000);

    private static final ModConfigSpec.BooleanValue REPEATER_ALLOW_CROSS_DIMENSION = BUILDER
            .comment("Whether OpenComputers Relays in another dimension (e.g. linked across dimensions) can relay signals back to a Glasses Bridge.")
            .define("repeaterAllowCrossDimension", false);

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
            .defineInRange("radarScanEnergyCost", 500.0, 0.0, 100000.0);

    private static final ModConfigSpec.BooleanValue GPU_COMPUTE_ENABLED = BUILDER
            .comment("Enable parallel CUDA-like compute callbacks on OpenComputers GPUs / APUs.")
            .define("gpuComputeEnabled", true);

    private static final ModConfigSpec.BooleanValue CARD_RACK_ENABLED = BUILDER
            .comment("Enable the Card Rack block (network expansion for up to 8 OC cards).")
            .define("cardRackEnabled", false);

    private static final ModConfigSpec.BooleanValue RAID_V2_ENABLED = BUILDER
            .comment("Enable the RAID v2 block (multi-drive array for up to 18 HDDs/SSDs).")
            .define("raidV2Enabled", false);

    private static final ModConfigSpec.IntValue GPU_COMPUTE_WORKER_THREADS = BUILDER
            .comment("Worker threads for GPU compute across all GPUs (0 = auto: half of CPU cores, capped at 4).")
            .defineInRange("gpuComputeWorkerThreads", 0, 0, 32);

    private static final ModConfigSpec.DoubleValue GPU_COMPUTE_DUTY_CYCLE = BUILDER
            .comment("Max worker CPU duty cycle (0.05 to 1.0). Lower values add short sleeps between chunks to protect host TPS.")
            .defineInRange("gpuComputeDutyCycle", 0.70, 0.05, 1.0);

    private static final ModConfigSpec.IntValue GPU_COMPUTE_MAX_JOBS_PER_CARD = BUILDER
            .comment("Maximum active/queued compute jobs per GPU card.")
            .defineInRange("gpuComputeMaxJobsPerCard", 4, 1, 32);

    private static final ModConfigSpec.IntValue GPU_COMPUTE_MAX_GLOBAL_QUEUE = BUILDER
            .comment("Maximum total active/queued GPU compute jobs server-wide.")
            .defineInRange("gpuComputeMaxGlobalQueue", 128, 4, 1024);

    private static final ModConfigSpec.DoubleValue GPU_COMPUTE_ENERGY_PER_OP = BUILDER
            .comment("OpenComputers network energy consumed per hash or compute operation (0 = free).")
            .defineInRange("gpuComputeEnergyPerOp", 0.01, 0.0, 1000.0);

    private static final ModConfigSpec.IntValue GPU_COMPUTE_MAX_OUTPUT_ROWS = BUILDER
            .comment("Maximum number of result rows returned from a single GPU compute job.")
            .defineInRange("gpuComputeMaxOutputRows", 16384, 64, 1048576);

    private static final GpuTierConfig[] GPU_TIER_CONFIGS = {
            defineGpuTier(1, 4, 1024, 16_384, 40.0),
            defineGpuTier(2, 8, 2048, 65_536, 20.0),
            defineGpuTier(3, 12, 4096, 65_536, 10.0),
            defineGpuTier(4, 16, 8192, 65_536, 5.0)
    };

    public static final ModConfigSpec SPEC = BUILDER.build();

    public static boolean relativeCoordinates = true;
    public static String defaultChatPrefix = "$$";
    public static int maxRange = 256;
    public static boolean allowCrossDimension = false;
    public static int repeaterRange = 0;
    public static boolean repeaterAllowCrossDimension = false;
    public static boolean requireEnergy = false;
    public static int energyCapacity = 100000;
    public static int energyPerTick = 5;
    public static double radarScanEnergyCost = 500.0;
    public static boolean gpuComputeEnabled = true;
    public static boolean cardRackEnabled = true;
    public static boolean raidV2Enabled = true;
    public static int gpuComputeWorkerThreads = 0;
    public static double gpuComputeDutyCycle = 0.50;
    public static int gpuComputeMaxJobsPerCard = 4;
    public static int gpuComputeMaxGlobalQueue = 128;
    public static double gpuComputeEnergyPerOp = 0.01;
    public static int gpuComputeMaxOutputRows = 16384;

    private static volatile GpuTierSpec[] gpuTierSpecs = {
            new GpuTierSpec(1, 16, 16_384, 16_384, 4_000_000L),
            new GpuTierSpec(2, 64, 131_072, 65_536, 1_000_000L),
            new GpuTierSpec(3, 256, 1_048_576, 65_536, 250_000L),
            new GpuTierSpec(4, 512, 4_194_304, 65_536, 0L)
    };

    private Config() {
    }

    private static GpuTierConfig defineGpuTier(
            int tier,
            int defaultCores,
            int defaultMaxBatch,
            int defaultMaxArray,
            double defaultPacingMs
    ) {
        BUILDER.push("gpuTier" + tier);
        ModConfigSpec.IntValue cores = BUILDER
                .comment("Virtual CUDA cores for Tier " + tier + " GPU/APU (controls parallelism partition cap).")
                .defineInRange("cudaCores", defaultCores, 1, 8192);
        ModConfigSpec.IntValue maxBatch = BUILDER
                .comment("Maximum threads per runKernel() / ops per matrixMul() for Tier " + tier + " GPU/APU.")
                .defineInRange("maxBatchSize", defaultMaxBatch, 64, 67_108_864);
        ModConfigSpec.IntValue maxArray = BUILDER
                .comment("Maximum elements in vector/array inputs for Tier " + tier + " GPU/APU.")
                .defineInRange("maxArrayElements", defaultMaxArray, 16, 1_048_576);
        ModConfigSpec.DoubleValue pacingMs = BUILDER
                .comment("Minimum pacing floor in milliseconds per 4096 operations for Tier " + tier + " GPU/APU (0 = unthrottled).")
                .defineInRange("pacingMsPer4kOps", defaultPacingMs, 0.0, 1000.0);
        BUILDER.pop();
        return new GpuTierConfig(cores, maxBatch, maxArray, pacingMs);
    }

    public static GpuTierSpec getGpuTierSpec(int ocTier) {
        return gpuTierSpecs[Math.clamp(ocTier, 0, 3)];
    }

    public static void onLoad(final ModConfigEvent event) {
        if (event.getConfig().getSpec() != SPEC || event instanceof ModConfigEvent.Unloading) {
            return;
        }
        relativeCoordinates = RELATIVE_COORDINATES.get();
        defaultChatPrefix = DEFAULT_CHAT_PREFIX.get().trim();
        maxRange = MAX_RANGE.get();
        allowCrossDimension = ALLOW_CROSS_DIMENSION.get();
        repeaterRange = REPEATER_RANGE.get();
        repeaterAllowCrossDimension = REPEATER_ALLOW_CROSS_DIMENSION.get();
        requireEnergy = REQUIRE_ENERGY.get();
        energyCapacity = ENERGY_CAPACITY.get();
        energyPerTick = ENERGY_PER_TICK.get();
        radarScanEnergyCost = RADAR_SCAN_ENERGY_COST.get();
        gpuComputeEnabled = GPU_COMPUTE_ENABLED.get();
        cardRackEnabled = CARD_RACK_ENABLED.get();
        raidV2Enabled = RAID_V2_ENABLED.get();
        gpuComputeWorkerThreads = GPU_COMPUTE_WORKER_THREADS.get();
        gpuComputeDutyCycle = GPU_COMPUTE_DUTY_CYCLE.get();
        gpuComputeMaxJobsPerCard = GPU_COMPUTE_MAX_JOBS_PER_CARD.get();
        gpuComputeMaxGlobalQueue = GPU_COMPUTE_MAX_GLOBAL_QUEUE.get();
        gpuComputeEnergyPerOp = GPU_COMPUTE_ENERGY_PER_OP.get();
        gpuComputeMaxOutputRows = GPU_COMPUTE_MAX_OUTPUT_ROWS.get();

        GpuTierSpec[] updated = new GpuTierSpec[4];
        for (int i = 0; i < 4; i++) {
            GpuTierConfig cfg = GPU_TIER_CONFIGS[i];
            int maxBatch = cfg.maxBatchSize.get();
            int maxArr = Math.min(maxBatch, cfg.maxArrayElements.get());
            long floorNanos = Math.round(cfg.pacingMsPer4kOps.get() * 1_000_000.0);
            updated[i] = new GpuTierSpec(i + 1, cfg.cudaCores.get(), maxBatch, maxArr, floorNanos);
        }
        gpuTierSpecs = updated;
    }

    private record GpuTierConfig(
            ModConfigSpec.IntValue cudaCores,
            ModConfigSpec.IntValue maxBatchSize,
            ModConfigSpec.IntValue maxArrayElements,
            ModConfigSpec.DoubleValue pacingMsPer4kOps
    ) {
    }
}
