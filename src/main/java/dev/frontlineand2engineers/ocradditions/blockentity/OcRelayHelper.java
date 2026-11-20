package dev.frontlineand2engineers.ocradditions.blockentity;

import dev.frontlineand2engineers.ocradditions.Config;
import li.cil.oc.api.network.Component;
import li.cil.oc.api.network.Node;
import li.cil.oc.api.network.WirelessEndpoint;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Queue;
import java.util.Set;

/**
 * Resolves Smart Glasses connectivity through standard OpenComputers {@code Relay}
 * blocks:
 * <ul>
 *   <li>Wired {@code Relay} blocks on the bridge's OC network</li>
 *   <li>Wireless multi-hop {@code Relay} chains (starting from a wired {@code Relay}
 *       or a wired {@code WirelessNetworkCard} modem on the bridge's network)</li>
 *   <li>{@code Relay} blocks linked via OpenComputers Linked Cards ({@code QuantumNetwork})</li>
 * </ul>
 *
 * <p>Never relays across other {@link GlassesBridgeBlockEntity} blocks.</p>
 */
public final class OcRelayHelper {
    private static final String RELAY_COMPONENT_NAME = "relay";
    private static final String MODEM_COMPONENT_NAME = "modem";
    private static final int MAX_VISITED_ENDPOINTS = 128;

    private OcRelayHelper() {
    }

    /**
     * Checks whether {@code player} is within range of any OpenComputers {@code Relay}
     * reachable from {@code bridgeNode}'s network (wired, multi-hop wireless, or linked).
     */
    public static boolean isPlayerInNetworkRelayRange(
            @Nullable Node bridgeNode,
            ServerPlayer player,
            ResourceKey<Level> bridgeDimension
    ) {
        if (bridgeNode == null || bridgeNode.network() == null) {
            return false;
        }
        return isPointInNetworkRelayRange(
                bridgeNode.network().nodes(),
                player.getX(),
                player.getY(),
                player.getZ(),
                player.level().dimension(),
                bridgeDimension
        );
    }

    /**
     * Pure coordinate/dimension BFS over wired OC nodes and wireless/quantum {@code Relay} hops.
     */
    public static boolean isPointInNetworkRelayRange(
            @Nullable Iterable<Node> networkNodes,
            double playerX,
            double playerY,
            double playerZ,
            ResourceKey<Level> playerDimension,
            ResourceKey<Level> bridgeDimension
    ) {
        if (networkNodes == null) {
            return false;
        }

        Set<WirelessEndpoint> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        Queue<WirelessEndpoint> queue = new ArrayDeque<>();

        for (Node netNode : networkNodes) {
            if (!(netNode instanceof Component comp) || !(comp.host() instanceof WirelessEndpoint endpoint)) {
                continue;
            }
            if (RELAY_COMPONENT_NAME.equals(comp.name())) {
                if (visited.add(endpoint)) {
                    if (isEndpointCoveringPlayer(endpoint, playerX, playerY, playerZ, playerDimension, bridgeDimension)) {
                        return true;
                    }
                    queue.add(endpoint);
                }
            } else if (MODEM_COMPONENT_NAME.equals(comp.name())) {
                // A wired WirelessNetworkCard on the bridge network can bridge wirelessly to standalone Relays.
                if (visited.add(endpoint)) {
                    queue.add(endpoint);
                }
            }
        }

        while (!queue.isEmpty() && visited.size() < MAX_VISITED_ENDPOINTS) {
            WirelessEndpoint current = queue.poll();

            double broadcastRange = resolveWirelessBroadcastRange(current);
            if (broadcastRange > 0) {
                for (WirelessEndpoint next : queryWirelessReachable(current, broadcastRange)) {
                    if (!isRelayHost(next) || !isRelayRepeaterActive(next)) {
                        continue;
                    }
                    if (!visited.add(next)) {
                        continue;
                    }
                    if (isEndpointCoveringPlayer(next, playerX, playerY, playerZ, playerDimension, bridgeDimension)) {
                        return true;
                    }
                    queue.add(next);
                    if (visited.size() >= MAX_VISITED_ENDPOINTS) {
                        return false;
                    }
                }
            }

            String tunnel = resolveLinkedTunnel(current);
            if (tunnel != null && !tunnel.isEmpty()) {
                for (WirelessEndpoint next : queryQuantumEndpoints(tunnel)) {
                    if (next == current || !isRelayHost(next)) {
                        continue;
                    }
                    Level nextLevel = next.getWirelessLevel();
                    if (nextLevel == null) {
                        continue;
                    }
                    if (!Config.repeaterAllowCrossDimension && !nextLevel.dimension().equals(bridgeDimension)) {
                        continue;
                    }
                    if (!visited.add(next)) {
                        continue;
                    }
                    if (isEndpointCoveringPlayer(next, playerX, playerY, playerZ, playerDimension, bridgeDimension)) {
                        return true;
                    }
                    queue.add(next);
                    if (visited.size() >= MAX_VISITED_ENDPOINTS) {
                        return false;
                    }
                }
            }
        }

        return false;
    }

    private static boolean isEndpointCoveringPlayer(
            WirelessEndpoint endpoint,
            double playerX,
            double playerY,
            double playerZ,
            ResourceKey<Level> playerDimension,
            ResourceKey<Level> bridgeDimension
    ) {
        Level endpointLevel = endpoint.getWirelessLevel();
        if (endpointLevel == null || !endpointLevel.dimension().equals(playerDimension)) {
            return false;
        }
        if (!Config.repeaterAllowCrossDimension && !endpointLevel.dimension().equals(bridgeDimension)) {
            return false;
        }
        double range = resolveRelayRange(endpoint);
        return range > 0 && isWithinRange(
                playerX, playerY, playerZ,
                endpoint.x() + 0.5, endpoint.y() + 0.5, endpoint.z() + 0.5,
                range
        );
    }

    /**
     * Returns {@code true} when {@code (px, py, pz)} is within {@code range} blocks of {@code (tx, ty, tz)}.
     */
    public static boolean isWithinRange(
            double px, double py, double pz,
            double tx, double ty, double tz,
            double range
    ) {
        double dx = px - tx;
        double dy = py - ty;
        double dz = pz - tz;
        return dx * dx + dy * dy + dz * dz <= range * range;
    }

    /**
     * Determines a Relay's effective coverage radius for Smart Glasses.
     */
    public static double resolveRelayRange(Object relayHost) {
        if (Config.repeaterRange > 0) {
            return Config.repeaterRange;
        }
        if (relayHost == null) {
            return 0.0;
        }
        return invokeBoolean(relayHost, "isWirelessEnabled")
                ? Math.max(0.0, invokeDouble(relayHost, "strength"))
                : 0.0;
    }

    private static double resolveWirelessBroadcastRange(WirelessEndpoint endpoint) {
        if (isRelayHost(endpoint)) {
            return resolveRelayRange(endpoint);
        }
        return Math.max(0.0, invokeDouble(endpoint, "strength"));
    }

    private static boolean isRelayHost(Object host) {
        return host != null && "Relay".equals(host.getClass().getSimpleName());
    }

    private static boolean isRelayRepeaterActive(Object relayHost) {
        if (Config.repeaterRange > 0) {
            return true;
        }
        return invokeBoolean(relayHost, "isWirelessEnabled") && invokeBoolean(relayHost, "isRepeater");
    }

    @Nullable
    private static String resolveLinkedTunnel(Object host) {
        if (!invokeBoolean(host, "isLinkedEnabled")) {
            return null;
        }
        try {
            Method m = host.getClass().getMethod("tunnel");
            m.trySetAccessible();
            Object res = m.invoke(host);
            return res instanceof String s ? s : null;
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }

    private static boolean invokeBoolean(Object target, String methodName) {
        try {
            Method m = target.getClass().getMethod(methodName);
            m.trySetAccessible();
            return m.invoke(target) instanceof Boolean b && b;
        } catch (ReflectiveOperationException ignored) {
            return false;
        }
    }

    private static double invokeDouble(Object target, String methodName) {
        try {
            Method m = target.getClass().getMethod(methodName);
            m.trySetAccessible();
            return m.invoke(target) instanceof Number n ? n.doubleValue() : 0.0;
        } catch (ReflectiveOperationException ignored) {
            return 0.0;
        }
    }

    private static Iterable<WirelessEndpoint> queryWirelessReachable(WirelessEndpoint source, double range) {
        try {
            Class<?> wnClass = Class.forName("li.cil.oc.server.network.WirelessNetwork$");
            Object wnInstance = wnClass.getField("MODULE$").get(null);
            Method compute = wnClass.getMethod("computeReachableFrom", WirelessEndpoint.class, double.class);
            Object scalaIterable = compute.invoke(wnInstance, source, range);
            return collectScalaIterable(scalaIterable);
        } catch (Throwable ignored) {
            return Collections.emptyList();
        }
    }

    private static Iterable<WirelessEndpoint> queryQuantumEndpoints(String tunnel) {
        try {
            Class<?> qnClass = Class.forName("li.cil.oc.server.network.QuantumNetwork$");
            Object qnInstance = qnClass.getField("MODULE$").get(null);
            Method getEndpoints = qnClass.getMethod("getEndpoints", String.class);
            Object scalaIterable = getEndpoints.invoke(qnInstance, tunnel);
            return collectScalaIterable(scalaIterable);
        } catch (Throwable ignored) {
            return Collections.emptyList();
        }
    }

    private static Iterable<WirelessEndpoint> collectScalaIterable(@Nullable Object scalaIterable) {
        if (scalaIterable == null) {
            return Collections.emptyList();
        }
        try {
            Method iterMethod = scalaIterable.getClass().getMethod("iterator");
            Object scalaIter = iterMethod.invoke(scalaIterable);
            Method hasNext = scalaIter.getClass().getMethod("hasNext");
            Method next = scalaIter.getClass().getMethod("next");
            java.util.List<WirelessEndpoint> out = new java.util.ArrayList<>();
            while (Boolean.TRUE.equals(hasNext.invoke(scalaIter))) {
                Object item = next.invoke(scalaIter);
                if (item instanceof WirelessEndpoint ep) {
                    out.add(ep);
                }
            }
            return out;
        } catch (Throwable ignored) {
            return Collections.emptyList();
        }
    }
}
