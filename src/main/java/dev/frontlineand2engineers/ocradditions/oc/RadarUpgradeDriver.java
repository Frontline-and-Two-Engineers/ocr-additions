package dev.frontlineand2engineers.ocradditions.oc;

import dev.frontlineand2engineers.ocradditions.OCRAdditions;
import dev.frontlineand2engineers.ocradditions.block.RadarBlock;
import li.cil.oc.api.driver.EnvironmentProvider;
import li.cil.oc.api.driver.item.HostAware;
import li.cil.oc.api.driver.item.Slot;
import li.cil.oc.api.internal.Drone;
import li.cil.oc.api.internal.Robot;
import li.cil.oc.api.internal.Tablet;
import li.cil.oc.api.network.EnvironmentHost;
import li.cil.oc.api.network.ManagedEnvironment;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;

/**
 * Allows installing any {@link RadarBlock} item (Tier 1..3) directly into an
 * OpenComputers Upgrade slot on a Tablet, Robot, or Drone.
 *
 * <p>The required upgrade slot tier matches the radar tier (zero-indexed: 0, 1, or 2).</p>
 */
public final class RadarUpgradeDriver implements HostAware, EnvironmentProvider {
    public static final RadarUpgradeDriver INSTANCE = new RadarUpgradeDriver();

    private RadarUpgradeDriver() {
    }

    /**
     * Returns 1..3 if {@code stack} is a Radar block item, or 0 otherwise.
     */
    public static int getRadarTier(ItemStack stack) {
        if (stack.isEmpty()) {
            return 0;
        }
        if (stack.is(OCRAdditions.RADAR_TIER_1_ITEM.get())) {
            return 1;
        }
        if (stack.is(OCRAdditions.RADAR_TIER_2_ITEM.get())) {
            return 2;
        }
        if (stack.is(OCRAdditions.RADAR_TIER_3_ITEM.get())) {
            return 3;
        }
        if (stack.getItem() instanceof BlockItem bi && bi.getBlock() instanceof RadarBlock rb) {
            return rb.getTier();
        }
        return 0;
    }

    @Override
    public boolean worksWith(ItemStack stack) {
        return getRadarTier(stack) > 0;
    }

    @Override
    public boolean worksWith(ItemStack stack, Class<? extends EnvironmentHost> host) {
        return worksWith(stack)
                && host != null
                && (Robot.class.isAssignableFrom(host)
                || Drone.class.isAssignableFrom(host)
                || Tablet.class.isAssignableFrom(host));
    }

    @Override
    public ManagedEnvironment createEnvironment(ItemStack stack, EnvironmentHost host) {
        if (host == null || (host.getEnvironmentLevel() != null && host.getEnvironmentLevel().isClientSide())) {
            return null;
        }
        int tier = getRadarTier(stack);
        return tier > 0 ? new RadarUpgradeEnvironment(host, tier) : null;
    }

    @Override
    public String slot(ItemStack stack) {
        return Slot.Upgrade;
    }

    @Override
    public int tier(ItemStack stack) {
        int radarTier = getRadarTier(stack);
        return radarTier > 0 ? radarTier - 1 : 0;
    }

    @Override
    public Class<?> getEnvironment(ItemStack stack) {
        return worksWith(stack) ? RadarUpgradeEnvironment.class : null;
    }
}
