package dev.frontlineand2engineers.ocradditions.compute;

import dev.frontlineand2engineers.ocradditions.Config;

/**
 * Hardware compute capabilities for each OpenComputers Graphics Card tier.
 * OpenComputers tiers are 0-indexed internally (0 = Tier 1 .. 3 = Tier 4/Creative).
 */
public record GpuTierSpec(
        int displayTier,
        int cudaCores,
        int maxBatchSize,
        int maxArrayElements,
        long pacingFloorNanosPer4kOps
) {
    public static GpuTierSpec fromOcTier(int ocTier) {
        return Config.getGpuTierSpec(ocTier);
    }
}
