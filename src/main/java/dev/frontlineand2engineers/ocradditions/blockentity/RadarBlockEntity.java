package dev.frontlineand2engineers.ocradditions.blockentity;

import dev.frontlineand2engineers.ocradditions.Config;
import dev.frontlineand2engineers.ocradditions.OCRAdditions;
import dev.frontlineand2engineers.ocradditions.block.RadarBlock;
import li.cil.oc.api.Network;
import li.cil.oc.api.UnrecoverablePersistanceException;
import li.cil.oc.api.driver.DeviceInfo;
import li.cil.oc.api.machine.Arguments;
import li.cil.oc.api.machine.Callback;
import li.cil.oc.api.machine.Context;
import li.cil.oc.api.network.Connector;
import li.cil.oc.api.network.Node;
import li.cil.oc.api.network.Visibility;
import li.cil.oc.api.prefab.BlockEntityEnvironment;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenComputers Rebooted Radar peripheral in 3 tiers:
 * Tier 1 (64 blocks): entity type only.
 * Tier 2 (128 blocks): entity type and distance.
 * Tier 3 (256 blocks): entity type, name (player username if player), distance, relative coordinates, uuid.
 */
public class RadarBlockEntity extends BlockEntityEnvironment implements DeviceInfo {
    private static final Logger LOGGER = LoggerFactory.getLogger(RadarBlockEntity.class);
    private static final String OC_NODE_TAG = "oc:node";
    private static final int MAX_RESULTS = 128;

    private final Map<String, String> deviceInfo;

    public RadarBlockEntity(BlockPos pos, BlockState state) {
        super(OCRAdditions.RADAR_BE.get(), pos, state);
        this.node = Network.newNode(this, Visibility.Network)
                .withComponent("radar", Visibility.Network)
                .withConnector(32)
                .create();
        this.deviceInfo = buildDeviceInfo(getTier());
    }

    public int getTier() {
        if (getBlockState().getBlock() instanceof RadarBlock rb) {
            return rb.getTier();
        }
        return 1;
    }

    public double getMaxRangeValue() {
        return maxRangeForTier(getTier());
    }

    public static double maxRangeForTier(int tier) {
        return switch (tier) {
            case 2 -> 128.0;
            case 3 -> 256.0;
            default -> 64.0;
        };
    }

    public static Map<String, String> buildDeviceInfo(int tier) {
        return Map.of(
                DeviceAttribute.Class, DeviceClass.Generic,
                DeviceAttribute.Description, "Entity radar",
                DeviceAttribute.Vendor, "OCR Additions",
                DeviceAttribute.Product, "Entity Radar Mk" + tier,
                DeviceAttribute.Capacity, String.valueOf((int) maxRangeForTier(tier))
        );
    }

    @Override
    public Map<String, String> getDeviceInfo() {
        return deviceInfo;
    }

    @Override
    public void onLoad() {
        if (level != null && !level.isClientSide) {
            super.onLoad();
        }
    }

    @Override
    public void loadAdditional(CompoundTag nbt, HolderLookup.Provider provider) {
        super.loadAdditional(nbt, provider);
        if (node != null && node.host() == this && nbt.contains(OC_NODE_TAG, Tag.TAG_COMPOUND)) {
            try {
                node.loadData(nbt.getCompound(OC_NODE_TAG), provider);
            } catch (UnrecoverablePersistanceException e) {
                LOGGER.warn("Failed to restore radar OpenComputers node", e);
            }
        }
    }

    @Callback(direct = true, doc = "function():number -- Returns the radar tier (1, 2, or 3).")
    public Object[] getTier(Context context, Arguments args) {
        return new Object[]{getTier()};
    }

    @Callback(direct = true, doc = "function():number -- Returns the maximum scan radius in blocks (64, 128, or 256).")
    public Object[] getMaxRange(Context context, Arguments args) {
        return new Object[]{getMaxRangeValue()};
    }

    @Callback(direct = true, doc = "function():boolean -- Returns true if Tier 3 coordinates are relative to the radar block.")
    public Object[] isRelativeCoordinates(Context context, Arguments args) {
        return new Object[]{Config.relativeCoordinates};
    }

    @Callback(doc = "function([radius:number]):table -- Scans entities within radius (clamped to tier max). Tier 1: type; Tier 2: type, distance; Tier 3: type, name, isPlayer, distance, x, y, z, uuid.")
    public Object[] scan(Context context, Arguments args) {
        if (level != null && !level.isClientSide && node != null && node.network() == null) {
            Network.joinOrCreateNetwork(this);
        }
        Vec3 center = Vec3.atCenterOf(worldPosition);
        return scanEntities(
                level,
                node,
                getTier(),
                center,
                worldPosition.getX(),
                worldPosition.getY(),
                worldPosition.getZ(),
                args
        );
    }

    /**
     * Shared entity scanning logic used by both the {@link RadarBlockEntity} block
     * and the mobile {@link dev.frontlineand2engineers.ocradditions.oc.RadarUpgradeEnvironment}.
     */
    public static Object[] scanEntities(
            Level level,
            Node node,
            int tier,
            Vec3 center,
            double originX,
            double originY,
            double originZ,
            Arguments args
    ) {
        if (level == null || level.isClientSide) {
            return new Object[]{List.of()};
        }

        if (Config.radarScanEnergyCost > 0.0 && node instanceof Connector connector) {
            if (!connector.tryChangeBuffer(-Config.radarScanEnergyCost)) {
                return new Object[]{null, "not enough energy"};
            }
        }

        double maxRange = maxRangeForTier(tier);
        double requested = args.count() > 0 && args.checkAny(0) != null ? args.checkDouble(0) : maxRange;
        double r = Double.isFinite(requested) && requested > 0 ? Math.min(requested, maxRange) : maxRange;
        double rSq = r * r;

        AABB box = new AABB(
                center.x - r, center.y - r, center.z - r,
                center.x + r, center.y + r, center.z + r
        );
        List<Entity> found = level.getEntitiesOfClass(Entity.class, box, e -> !e.isRemoved());

        List<EntityDist> sorted = new ArrayList<>();
        for (Entity e : found) {
            double distSq = e.position().distanceToSqr(center);
            if (distSq <= rSq) {
                sorted.add(new EntityDist(e, Math.sqrt(distSq)));
            }
        }
        sorted.sort(Comparator.comparingDouble(ed -> ed.dist));

        int limit = Math.min(sorted.size(), MAX_RESULTS);
        List<Map<String, Object>> results = new ArrayList<>(limit);
        for (int i = 0; i < limit; i++) {
            Entity e = sorted.get(i).entity;
            double dist = sorted.get(i).dist;

            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("type", BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString());

            if (tier >= 2) {
                entry.put("distance", dist);
            }

            if (tier >= 3) {
                if (e instanceof Player player) {
                    entry.put("isPlayer", true);
                    entry.put("name", player.getGameProfile().getName());
                } else {
                    entry.put("isPlayer", false);
                    entry.put("name", e.getName().getString());
                }
                double ox = Config.relativeCoordinates ? e.getX() - originX : e.getX();
                double oy = Config.relativeCoordinates ? e.getY() - originY : e.getY();
                double oz = Config.relativeCoordinates ? e.getZ() - originZ : e.getZ();
                entry.put("x", ox);
                entry.put("y", oy);
                entry.put("z", oz);
                entry.put("uuid", e.getUUID().toString());
            }

            results.add(entry);
        }
        return new Object[]{results};
    }

    @Callback(doc = "function([radius:number]):table -- Alias for scan([radius]).")
    public Object[] getEntities(Context context, Arguments args) {
        return scan(context, args);
    }

    private record EntityDist(Entity entity, double dist) {
    }
}
