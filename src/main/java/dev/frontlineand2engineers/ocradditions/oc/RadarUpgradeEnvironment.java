package dev.frontlineand2engineers.ocradditions.oc;

import dev.frontlineand2engineers.ocradditions.Config;
import dev.frontlineand2engineers.ocradditions.blockentity.RadarBlockEntity;
import li.cil.oc.api.Network;
import li.cil.oc.api.driver.DeviceInfo;
import li.cil.oc.api.machine.Arguments;
import li.cil.oc.api.machine.Callback;
import li.cil.oc.api.machine.Context;
import li.cil.oc.api.network.EnvironmentHost;
import li.cil.oc.api.network.Visibility;
import li.cil.oc.api.prefab.AbstractManagedEnvironment;
import net.minecraft.world.phys.Vec3;

import java.util.Map;

/**
 * Mobile OpenComputers {@code radar} component environment for Robots, Drones, and Tablets
 * when a Radar block (Tier 1..3) is installed into an Upgrade slot.
 *
 * <p>Tracks {@link EnvironmentHost}'s live coordinates so scans follow the moving host.</p>
 */
public class RadarUpgradeEnvironment extends AbstractManagedEnvironment implements DeviceInfo {
    private final EnvironmentHost host;
    private final int tier;
    private final Map<String, String> deviceInfo;

    public RadarUpgradeEnvironment(EnvironmentHost host, int tier) {
        this.host = host;
        this.tier = Math.max(1, Math.min(3, tier));
        this.deviceInfo = RadarBlockEntity.buildDeviceInfo(this.tier);
        setNode(Network.newNode(this, Visibility.Neighbors)
                .withComponent("radar", Visibility.Neighbors)
                .withConnector()
                .create());
    }

    @Override
    public Map<String, String> getDeviceInfo() {
        return deviceInfo;
    }

    @Callback(direct = true, doc = "function():number -- Returns the radar tier (1, 2, or 3).")
    public Object[] getTier(Context context, Arguments args) {
        return new Object[]{tier};
    }

    @Callback(direct = true, doc = "function():number -- Returns the maximum scan radius in blocks (64, 128, or 256).")
    public Object[] getMaxRange(Context context, Arguments args) {
        return new Object[]{RadarBlockEntity.maxRangeForTier(tier)};
    }

    @Callback(direct = true, doc = "function():boolean -- Returns true if Tier 3 coordinates are relative to the host's current position.")
    public Object[] isRelativeCoordinates(Context context, Arguments args) {
        return new Object[]{Config.relativeCoordinates};
    }

    @Callback(doc = "function([radius:number]):table -- Scans entities around the moving host (Tablet, Robot, or Drone).")
    public Object[] scan(Context context, Arguments args) {
        double x = host.xPosition();
        double y = host.yPosition();
        double z = host.zPosition();
        return RadarBlockEntity.scanEntities(
                host.getEnvironmentLevel(),
                node(),
                tier,
                new Vec3(x, y, z),
                x,
                y,
                z,
                args
        );
    }

    @Callback(doc = "function([radius:number]):table -- Alias for scan([radius]).")
    public Object[] getEntities(Context context, Arguments args) {
        return scan(context, args);
    }
}
