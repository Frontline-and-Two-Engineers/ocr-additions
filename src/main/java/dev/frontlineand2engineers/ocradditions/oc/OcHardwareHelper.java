package dev.frontlineand2engineers.ocradditions.oc;

import li.cil.oc.api.Driver;
import li.cil.oc.api.Network;
import li.cil.oc.api.driver.DriverItem;
import li.cil.oc.api.driver.item.Slot;
import li.cil.oc.api.internal.Robot;
import li.cil.oc.api.machine.Arguments;
import li.cil.oc.api.machine.Context;
import li.cil.oc.api.network.Component;
import li.cil.oc.api.network.Environment;
import li.cil.oc.api.network.EnvironmentHost;
import li.cil.oc.api.network.ManagedEnvironment;
import li.cil.oc.api.network.ManagedPeripheral;
import li.cil.oc.api.network.Message;
import li.cil.oc.api.network.Node;
import li.cil.oc.api.network.Visibility;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.lang.reflect.Method;
import java.util.Map;

/**
 * Shared helper for inspecting OpenComputers components (CPU, RAM, Expansion Cards,
 * HDD/SSD drives) using the public OC driver API with minimal safe reflection.
 */
public final class OcHardwareHelper {
    public static final int MAX_CARD_RACK_SLOTS = 8;
    public static final int MAX_RAID_SLOTS = 18;

    private static final Map<String, Integer> DRIVE_SPEEDS = Map.of(
            "hdd1", 2,
            "hdd2", 3,
            "hdd3", 4,
            "hdd4", 5,
            "ssd1", 4,
            "ssd2", 5,
            "ssd3", 6
    );

    private static final Map<String, Long> FALLBACK_DRIVE_BYTES = Map.of(
            "hdd1", 1024L * 1024L,
            "hdd2", 2048L * 1024L,
            "hdd3", 4096L * 1024L,
            "hdd4", 8192L * 1024L,
            "ssd1", 1024L * 1024L,
            "ssd2", 2048L * 1024L,
            "ssd3", 4096L * 1024L
    );

    private static final Map<String, Integer> RAM_LEVELS = Map.of(
            "ram1", 0,
            "ram2", 1,
            "ram3", 2,
            "ram4", 3,
            "ram5", 4,
            "ram6", 5,
            "ram7", 6,
            "ram8", 7
    );

    private OcHardwareHelper() {
    }

    @Nullable
    public static DriverItem findDriver(ItemStack stack) {
        if (stack.isEmpty()) {
            return null;
        }
        DriverItem driver = Driver.driverFor(stack, Robot.class);
        return driver != null ? driver : Driver.driverFor(stack);
    }

    public static boolean isCpu(ItemStack stack) {
        return getCpuTier(stack) >= 0;
    }

    /**
     * Returns 0..3 (Tier 1..4) for an OpenComputers CPU, or -1 if not a CPU.
     */
    public static int getCpuTier(ItemStack stack) {
        DriverItem driver = findDriver(stack);
        if (driver == null || !Slot.CPU.equals(driver.slot(stack))) {
            return -1;
        }
        return Math.max(0, driver.tier(stack));
    }

    /**
     * Card Rack capacity by CPU tier: T1=2, T2=4, T3=6, T4=8; no CPU = 0.
     */
    public static int getCardRackMaxCards(int cpuTier) {
        if (cpuTier < 0) {
            return 0;
        }
        return Math.min(MAX_CARD_RACK_SLOTS, (cpuTier + 1) * 2);
    }

    public static boolean isMemory(ItemStack stack) {
        return getMemoryLevel(stack) >= 0;
    }

    /**
     * Returns 0..7 for RAM T1, T1.5, T2, T2.5, T3, T3.5, T4, T4.5, or -1 if not RAM.
     */
    public static int getMemoryLevel(ItemStack stack) {
        DriverItem driver = findDriver(stack);
        if (driver == null || !Slot.Memory.equals(driver.slot(stack))) {
            return -1;
        }
        ResourceLocation key = BuiltInRegistries.ITEM.getKey(stack.getItem());
        if (key != null && RAM_LEVELS.containsKey(key.getPath())) {
            return RAM_LEVELS.get(key.getPath());
        }
        try {
            Method tierField = stack.getItem().getClass().getMethod("tier");
            tierField.trySetAccessible();
            if (tierField.invoke(stack.getItem()) instanceof Number n) {
                return Math.max(0, Math.min(7, n.intValue()));
            }
        } catch (ReflectiveOperationException ignored) {
        }
        return Math.max(0, Math.min(7, driver.tier(stack) * 2));
    }

    /**
     * RAIDv2 active drive limit by RAM level:
     * T1(0)->4, T1.5(1)->6, T2(2)->8, T2.5(3)->10, T3(4)->12, T3.5(5)->14, T4(6)->16, T4.5(7)->18.
     */
    public static int getRaidMaxDisks(int ramLevel) {
        if (ramLevel < 0) {
            return 0;
        }
        return Math.min(MAX_RAID_SLOTS, 4 + ramLevel * 2);
    }

    /**
     * Maximum RAIDv2 speed permitted by CPU tier:
     * no CPU -> 0, T1 -> 3 (HDD T2 speed), T2 -> 4 (HDD T3 speed), T3/T4 -> 6 (no CPU bottleneck).
     */
    public static int getRaidCpuMaxSpeed(int cpuTier) {
        if (cpuTier < 0) {
            return 0;
        }
        return switch (cpuTier) {
            case 0 -> 3;
            case 1 -> 4;
            default -> 6;
        };
    }

    public static boolean isCard(ItemStack stack) {
        DriverItem driver = findDriver(stack);
        return driver != null && Slot.Card.equals(driver.slot(stack));
    }

    public static boolean isStorageDrive(ItemStack stack) {
        DriverItem driver = findDriver(stack);
        return driver != null && Slot.HDD.equals(driver.slot(stack));
    }

    /**
     * Returns the OpenComputers filesystem speed tier for an HDD (2..5) or SSD (4..6).
     */
    public static int getDriveSpeed(ItemStack stack) {
        DriverItem driver = findDriver(stack);
        if (driver == null || !Slot.HDD.equals(driver.slot(stack))) {
            return 0;
        }
        ResourceLocation key = BuiltInRegistries.ITEM.getKey(stack.getItem());
        if (key != null && DRIVE_SPEEDS.containsKey(key.getPath())) {
            return DRIVE_SPEEDS.get(key.getPath());
        }
        return Math.max(1, Math.min(6, driver.tier(stack) + 2));
    }

    /**
     * Computes storage capacity in bytes for an HDD/SSD item stack.
     */
    public static long getDriveCapacityBytes(ItemStack stack, EnvironmentHost host) {
        DriverItem driver = findDriver(stack);
        if (driver == null || !Slot.HDD.equals(driver.slot(stack))) {
            return 0L;
        }
        if (host != null) {
            ManagedEnvironment env = driver.createEnvironment(stack, host);
            if (env != null) {
                try {
                    Method fsMethod = env.getClass().getMethod("fileSystem");
                    fsMethod.trySetAccessible();
                    Object fsObj = fsMethod.invoke(env);
                    if (fsObj instanceof li.cil.oc.api.fs.FileSystem fs) {
                        return Math.max(0L, fs.spaceTotal());
                    }
                } catch (ReflectiveOperationException ignored) {
                }
            }
        }
        try {
            Method kbMethod = stack.getItem().getClass().getMethod("kiloBytes");
            kbMethod.trySetAccessible();
            if (kbMethod.invoke(stack.getItem()) instanceof Number n) {
                return Math.max(0L, n.longValue() * 1024L);
            }
        } catch (ReflectiveOperationException ignored) {
        }
        ResourceLocation key = BuiltInRegistries.ITEM.getKey(stack.getItem());
        if (key != null && FALLBACK_DRIVE_BYTES.containsKey(key.getPath())) {
            return FALLBACK_DRIVE_BYTES.get(key.getPath());
        }
        return 1024L * 1024L;
    }

    /**
     * Exposes a child component node (card or RAID filesystem) to the entire wired OC network.
     *
     * <p>If the child node has {@code reachability == Visibility.Network}, sets its component
     * visibility to {@code Network} directly. If its reachability is {@code Neighbors}
     * (such as GPU, Data Card, etc.), attaches a {@link CardComponentProxy} with
     * {@code Visibility.Network} and returns the proxy node so the caller can clean it up.</p>
     */
    @Nullable
    public static Node connectNetworkVisibleComponent(Node parentNode, EnvironmentHost host, Node childNode) {
        if (parentNode == null || childNode == null) {
            return null;
        }
        if (parentNode.network() == null && host instanceof net.minecraft.world.level.block.entity.BlockEntity be) {
            Network.joinOrCreateNetwork(be);
        }
        if (childNode instanceof Component comp) {
            if (childNode.reachability() == Visibility.Network) {
                comp.setVisibility(Visibility.Network);
                parentNode.connect(childNode);
                return null;
            }
            parentNode.connect(childNode);
            CardComponentProxy proxy = new CardComponentProxy(comp);
            parentNode.connect(proxy.node());
            return proxy.node();
        }
        parentNode.connect(childNode);
        return null;
    }

    private static final class CardComponentProxy implements Environment, ManagedPeripheral {
        private final Component target;
        private final Node node;

        private CardComponentProxy(Component target) {
            this.target = target;
            this.node = Network.newNode(this, Visibility.Network)
                    .withComponent(target.name(), Visibility.Network)
                    .create();
        }

        @Override
        public Node node() {
            return node;
        }

        @Override
        public void onConnect(Node other) {
        }

        @Override
        public void onDisconnect(Node other) {
        }

        @Override
        public void onMessage(Message message) {
        }

        @Override
        public String[] methods() {
            return target.methods().toArray(new String[0]);
        }

        @Override
        public Object[] invoke(String method, Context context, Arguments args) throws Exception {
            return target.invoke(method, context, args.toArray());
        }
    }
}
