package dev.frontlineand2engineers.ocradditions.blockentity;

import dev.frontlineand2engineers.ocradditions.Config;
import dev.frontlineand2engineers.ocradditions.OCRAdditions;
import dev.frontlineand2engineers.ocradditions.hud.Anchor;
import dev.frontlineand2engineers.ocradditions.hud.HudWidget;
import dev.frontlineand2engineers.ocradditions.item.SmartGlassesItem;
import dev.frontlineand2engineers.ocradditions.network.GlassesGazePayload;
import dev.frontlineand2engineers.ocradditions.network.GlassesHudClickPayload;
import dev.frontlineand2engineers.ocradditions.network.GlassesScreenSizePayload;
import dev.frontlineand2engineers.ocradditions.network.GlassesSoundPayload;
import dev.frontlineand2engineers.ocradditions.network.SyncHudPayload;
import li.cil.oc.api.Network;
import li.cil.oc.api.UnrecoverablePersistanceException;
import li.cil.oc.api.driver.DeviceInfo;
import li.cil.oc.api.machine.Arguments;
import li.cil.oc.api.machine.Callback;
import li.cil.oc.api.machine.Context;
import li.cil.oc.api.network.Visibility;
import li.cil.oc.api.prefab.BlockEntityEnvironment;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.network.PacketDistributor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * OpenComputers Rebooted peripheral managing 2D HUD, chat commands, clickable UI,
 * 3D AR overlays, gaze raycasts, and wearer telemetry for bound Smart Glasses.
 */
public class GlassesBridgeBlockEntity extends BlockEntityEnvironment implements DeviceInfo {
    private static final Logger LOGGER = LoggerFactory.getLogger(GlassesBridgeBlockEntity.class);
    private static final String OC_NODE_TAG = "oc:node";
    private static final String TAG_BRIDGE_ID = "bridgeId";
    private static final String TAG_CHAT_PREFIX = "chatPrefix";
    private static final int MAX_WIDGETS_PER_LAYER = 512;
    private static final int MAX_QUEUED_EVENTS = 256;

    private static final Map<String, String> DEVICE_INFO = Map.of(
            DeviceAttribute.Class, DeviceClass.Display,
            DeviceAttribute.Description, "Smart Glasses wireless HUD/AR bridge",
            DeviceAttribute.Vendor, "OCR Additions",
            DeviceAttribute.Product, "OpticLink Bridge v1"
    );

    private UUID bridgeId = UUID.randomUUID();
    private String chatPrefix = Config.defaultChatPrefix;

    private final Queue<Map<String, Object>> eventQueue = new ConcurrentLinkedQueue<>();

    private final Map<String, HudWidget> globalWidgets = new LinkedHashMap<>();
    private final Map<UUID, Map<String, HudWidget>> playerWidgets = new ConcurrentHashMap<>();

    private boolean globalClearAll = false;
    private final Set<String> globalDirtyUpserts = new LinkedHashSet<>();
    private final Set<String> globalDirtyRemoves = new LinkedHashSet<>();
    private final Map<UUID, LayerDiff> playerDiffs = new ConcurrentHashMap<>();
    private final Set<UUID> connectedPlayers = new HashSet<>();
    private final Map<UUID, int[]> playerScreenSizes = new ConcurrentHashMap<>();

    private static final class LayerDiff {
        boolean clearAll = false;
        final Set<String> upserts = new LinkedHashSet<>();
        final Set<String> removes = new LinkedHashSet<>();
    }

    public GlassesBridgeBlockEntity(BlockPos pos, BlockState state) {
        super(OCRAdditions.GLASSES_BRIDGE_BE.get(), pos, state);
        this.node = Network.newNode(this, Visibility.Network)
                .withComponent("glasses_bridge", Visibility.Network)
                .withConnector(64)
                .create();
    }

    public UUID getBridgeId() {
        return bridgeId;
    }

    public String getChatPrefix() {
        return (chatPrefix == null || chatPrefix.isBlank()) ? Config.defaultChatPrefix : chatPrefix;
    }

    @Override
    public Map<String, String> getDeviceInfo() {
        return DEVICE_INFO;
    }

    @Override
    public void onLoad() {
        if (level != null && !level.isClientSide) {
            super.onLoad();
            BridgeRegistry.register(bridgeId, this);
        }
    }

    @Override
    public void onChunkUnloaded() {
        if (level != null && !level.isClientSide) {
            BridgeRegistry.unregister(bridgeId, this);
        }
        super.onChunkUnloaded();
    }

    @Override
    public void setRemoved() {
        if (level != null && !level.isClientSide) {
            BridgeRegistry.unregister(bridgeId, this);
            if (level instanceof ServerLevel serverLevel) {
                SyncHudPayload clear = new SyncHudPayload(true, List.of(), List.of());
                for (UUID playerId : connectedPlayers) {
                    ServerPlayer player = serverLevel.getServer().getPlayerList().getPlayer(playerId);
                    if (player != null) {
                        PacketDistributor.sendToPlayer(player, clear);
                    }
                }
            }
            connectedPlayers.clear();
            playerScreenSizes.clear();
        }
        super.setRemoved();
    }

    @Override
    public void saveAdditional(CompoundTag nbt, HolderLookup.Provider provider) {
        super.saveAdditional(nbt, provider);
        nbt.putUUID(TAG_BRIDGE_ID, bridgeId);
        nbt.putString(TAG_CHAT_PREFIX, getChatPrefix());
    }

    @Override
    public void loadAdditional(CompoundTag nbt, HolderLookup.Provider provider) {
        super.loadAdditional(nbt, provider);
        if (node != null && node.host() == this && nbt.contains(OC_NODE_TAG, Tag.TAG_COMPOUND)) {
            try {
                node.loadData(nbt.getCompound(OC_NODE_TAG), provider);
            } catch (UnrecoverablePersistanceException e) {
                LOGGER.warn("Failed to restore glasses_bridge OpenComputers node", e);
            }
        }
        if (nbt.hasUUID(TAG_BRIDGE_ID)) {
            BridgeRegistry.unregister(bridgeId, this);
            bridgeId = nbt.getUUID(TAG_BRIDGE_ID);
            if (level != null && !level.isClientSide) {
                BridgeRegistry.register(bridgeId, this);
            }
        }
        if (nbt.contains(TAG_CHAT_PREFIX, Tag.TAG_STRING)) {
            String loaded = nbt.getString(TAG_CHAT_PREFIX);
            if (!loaded.isBlank() && loaded.length() <= 16) {
                chatPrefix = loaded;
            }
        }
    }

    public void serverTick() {
        if (!(level instanceof ServerLevel serverLevel)) return;
        if (BridgeRegistry.get(bridgeId) != this) {
            BridgeRegistry.register(bridgeId, this);
        }
        if (node != null && node.network() == null) {
            Network.joinOrCreateNetwork(this);
        }

        List<ServerPlayer> activeRecipients = new ArrayList<>();
        Set<UUID> currentIds = new HashSet<>();

        for (ServerPlayer player : serverLevel.getServer().getPlayerList().getPlayers()) {
            if (isPlayerConnected(player)) {
                currentIds.add(player.getUUID());
                activeRecipients.add(player);
            }
        }

        for (ServerPlayer player : activeRecipients) {
            UUID pid = player.getUUID();
            if (!connectedPlayers.contains(pid)) {
                connectedPlayers.add(pid);
                int tier = getPlayerTier(player);
                sendFullSnapshot(player, tier);
                emitPlayerConnectionEvent("glasses_player_connected", player, tier);
            }
        }

        Iterator<UUID> it = connectedPlayers.iterator();
        while (it.hasNext()) {
            UUID pid = it.next();
            if (!currentIds.contains(pid)) {
                it.remove();
                playerScreenSizes.remove(pid);
                ServerPlayer player = serverLevel.getServer().getPlayerList().getPlayer(pid);
                if (player != null) {
                    PacketDistributor.sendToPlayer(player, new SyncHudPayload(true, List.of(), List.of()));
                    emitPlayerConnectionEvent("glasses_player_disconnected", player, 0);
                }
            }
        }

        for (ServerPlayer player : activeRecipients) {
            ItemStack glasses = SmartGlassesItem.getEquippedGlasses(player);
            if (!glasses.isEmpty()) {
                SmartGlassesItem.consumeEnergy(glasses);
            }
        }

        flushPendingDiffs(activeRecipients);
    }

    private void flushPendingDiffs(List<ServerPlayer> activeRecipients) {
        boolean hasGlobalDiff = globalClearAll || !globalDirtyUpserts.isEmpty() || !globalDirtyRemoves.isEmpty();
        if (!hasGlobalDiff && playerDiffs.isEmpty()) return;

        for (ServerPlayer player : activeRecipients) {
            UUID pid = player.getUUID();
            int tier = getPlayerTier(player);
            LayerDiff pDiff = playerDiffs.remove(pid);
            if (!hasGlobalDiff && pDiff == null) continue;

            boolean clear = globalClearAll || (pDiff != null && pDiff.clearAll);
            if (clear) {
                sendFullSnapshot(player, tier);
                continue;
            }

            List<String> removes = new ArrayList<>(globalDirtyRemoves);
            List<HudWidget> upserts = new ArrayList<>();
            Map<String, HudWidget> pMap = playerWidgets.get(pid);

            for (String id : globalDirtyUpserts) {
                HudWidget chosen = (pMap != null && pMap.containsKey(id)) ? pMap.get(id) : globalWidgets.get(id);
                if (chosen != null) {
                    addFilteredOrRemove(chosen, tier, upserts, removes);
                }
            }

            if (pDiff != null) {
                for (String id : pDiff.removes) {
                    if (!globalWidgets.containsKey(id)) {
                        removes.add(id);
                    } else {
                        addFilteredOrRemove(globalWidgets.get(id), tier, upserts, removes);
                    }
                }
                if (pMap != null) {
                    for (String id : pDiff.upserts) {
                        HudWidget w = pMap.get(id);
                        if (w != null) {
                            addFilteredOrRemove(w, tier, upserts, removes);
                        }
                    }
                }
            }

            if (!removes.isEmpty() || !upserts.isEmpty()) {
                PacketDistributor.sendToPlayer(player, new SyncHudPayload(false, removes, upserts));
            }
        }

        globalClearAll = false;
        globalDirtyUpserts.clear();
        globalDirtyRemoves.clear();
        playerDiffs.clear();
    }

    private void addFilteredOrRemove(HudWidget widget, int tier, List<HudWidget> upserts, List<String> removes) {
        HudWidget filtered = prepareForClient(widget, tier);
        if (filtered != null) {
            upserts.add(filtered);
        } else {
            removes.add(widget.id());
        }
    }

    private void sendFullSnapshot(ServerPlayer player, int tier) {
        Map<String, HudWidget> merged = new LinkedHashMap<>(globalWidgets);
        Map<String, HudWidget> pMap = playerWidgets.get(player.getUUID());
        if (pMap != null) {
            merged.putAll(pMap);
        }
        List<HudWidget> visible = new ArrayList<>(merged.size());
        for (HudWidget w : merged.values()) {
            HudWidget prepared = prepareForClient(w, tier);
            if (prepared != null) {
                visible.add(prepared);
            }
        }
        PacketDistributor.sendToPlayer(player, new SyncHudPayload(true, List.of(), visible));
    }

    @Nullable
    private HudWidget prepareForClient(HudWidget widget, int tier) {
        if (tier < widget.type().requiredTier) {
            return null;
        }
        HudWidget result = widget;
        if (tier < 2 && result.clickable()) {
            result = result.withClickable(false);
        }
        if (Config.relativeCoordinates) {
            if (result.type() == HudWidget.Type.WORLD_MARKER) {
                result = new HudWidget(
                        result.id(), result.type(), result.anchor(),
                        result.x() + worldPosition.getX(),
                        result.y() + worldPosition.getY(),
                        result.z() + worldPosition.getZ(),
                        0, 0, 0, result.width(), result.height(),
                        result.color(), result.bgColor(), result.scale(),
                        result.value(), result.max(), result.text(),
                        result.targetUuid(), result.clickable(), result.points()
                );
            } else if (result.type() == HudWidget.Type.WORLD_LINE || result.type() == HudWidget.Type.WORLD_BOX) {
                result = new HudWidget(
                        result.id(), result.type(), result.anchor(),
                        result.x() + worldPosition.getX(),
                        result.y() + worldPosition.getY(),
                        result.z() + worldPosition.getZ(),
                        result.x2() + worldPosition.getX(),
                        result.y2() + worldPosition.getY(),
                        result.z2() + worldPosition.getZ(),
                        result.width(), result.height(),
                        result.color(), result.bgColor(), result.scale(),
                        result.value(), result.max(), result.text(),
                        result.targetUuid(), result.clickable(), result.points()
                );
            }
        }
        return result;
    }

    public boolean isPlayerConnected(ServerPlayer player) {
        ItemStack glasses = SmartGlassesItem.getEquippedGlasses(player);
        UUID bound = SmartGlassesItem.getBridgeId(glasses);
        if (!bridgeId.equals(bound)) return false;
        if (!SmartGlassesItem.hasEnoughEnergy(glasses)) return false;
        if (level == null) return false;

        boolean sameDim = player.level().dimension().equals(level.dimension());
        boolean withinBridge;
        if (!sameDim) {
            withinBridge = Config.allowCrossDimension;
        } else if (Config.maxRange <= 0) {
            withinBridge = true;
        } else {
            withinBridge = OcRelayHelper.isWithinRange(
                    player.getX(), player.getY(), player.getZ(),
                    worldPosition.getX() + 0.5, worldPosition.getY() + 0.5, worldPosition.getZ() + 0.5,
                    Config.maxRange
            );
        }
        return withinBridge || OcRelayHelper.isPlayerInNetworkRelayRange(node, player, level.dimension());
    }

    public int getPlayerTier(ServerPlayer player) {
        return SmartGlassesItem.getTier(SmartGlassesItem.getEquippedGlasses(player));
    }

    @Nullable
    private ServerPlayer findConnectedPlayer(String playerUuidStr) {
        if (!(level instanceof ServerLevel serverLevel) || playerUuidStr == null) return null;
        UUID pid;
        try {
            pid = UUID.fromString(playerUuidStr);
        } catch (IllegalArgumentException e) {
            return null;
        }
        ServerPlayer player = serverLevel.getServer().getPlayerList().getPlayer(pid);
        return (player != null && isPlayerConnected(player)) ? player : null;
    }

    // -------------------------------------------------------------------------
    // Incoming Player Interaction Handlers (Chat, HUD Click, Gaze)
    // -------------------------------------------------------------------------

    public boolean handleChatMessage(ServerPlayer player, String rawMessage) {
        if (!isPlayerConnected(player) || getPlayerTier(player) < 2) {
            return false;
        }
        String prefix = getChatPrefix();
        if (!rawMessage.startsWith(prefix)) {
            return false;
        }
        String command = rawMessage.substring(prefix.length()).trim();
        String playerUuid = player.getUUID().toString();
        String playerName = player.getGameProfile().getName();

        Map<String, Object> evt = new LinkedHashMap<>();
        evt.put("event", "glasses_chat_command");
        evt.put("playerUuid", playerUuid);
        evt.put("playerName", playerName);
        evt.put("command", command);
        evt.put("prefix", prefix);
        enqueueEvent(evt);

        emitSignal("glasses_chat_command", playerUuid, playerName, command, prefix);
        return true;
    }

    public void handleHudClick(ServerPlayer player, GlassesHudClickPayload payload) {
        if (!isPlayerConnected(player) || getPlayerTier(player) < 2) {
            return;
        }
        String playerUuid = player.getUUID().toString();
        String playerName = player.getGameProfile().getName();

        Map<String, Object> evt = new LinkedHashMap<>();
        evt.put("event", "glasses_hud_click");
        evt.put("playerUuid", playerUuid);
        evt.put("playerName", playerName);
        evt.put("widgetId", payload.widgetId());
        evt.put("button", payload.button());
        enqueueEvent(evt);

        emitSignal("glasses_hud_click", playerUuid, playerName, payload.widgetId(), payload.button());
    }

    public void handleScreenSize(ServerPlayer player, GlassesScreenSizePayload payload) {
        if (!isPlayerConnected(player)) {
            return;
        }
        int w = Math.clamp(payload.width(), 1, 16384);
        int h = Math.clamp(payload.height(), 1, 16384);
        UUID pid = player.getUUID();
        int[] prev = playerScreenSizes.put(pid, new int[]{w, h});
        if (prev == null || prev[0] != w || prev[1] != h) {
            String playerUuid = pid.toString();
            String playerName = player.getGameProfile().getName();
            Map<String, Object> evt = new LinkedHashMap<>();
            evt.put("event", "glasses_screen_resized");
            evt.put("playerUuid", playerUuid);
            evt.put("playerName", playerName);
            evt.put("screenWidth", w);
            evt.put("screenHeight", h);
            enqueueEvent(evt);
            emitSignal("glasses_screen_resized", playerUuid, playerName, w, h);
        }
    }

    public void handleGaze(ServerPlayer player, GlassesGazePayload payload) {
        if (!isPlayerConnected(player) || getPlayerTier(player) < 3) {
            return;
        }
        double ox = Config.relativeCoordinates ? worldPosition.getX() : 0.0;
        double oy = Config.relativeCoordinates ? worldPosition.getY() : 0.0;
        double oz = Config.relativeCoordinates ? worldPosition.getZ() : 0.0;
        double hitX = payload.hitX() - ox;
        double hitY = payload.hitY() - oy;
        double hitZ = payload.hitZ() - oz;

        String playerUuid = player.getUUID().toString();
        String playerName = player.getGameProfile().getName();

        Map<String, Object> evt = new LinkedHashMap<>();
        evt.put("event", "glasses_gaze");
        evt.put("playerUuid", playerUuid);
        evt.put("playerName", playerName);
        evt.put("hitX", hitX);
        evt.put("hitY", hitY);
        evt.put("hitZ", hitZ);

        switch (payload.targetType()) {
            case BLOCK -> {
                BlockPos bpos = payload.blockPos();
                if (player.distanceToSqr(bpos.getX() + 0.5, bpos.getY() + 0.5, bpos.getZ() + 0.5) > 68.0 * 68.0) {
                    return;
                }
                int bx = Config.relativeCoordinates ? worldPosition.getX() : 0;
                int by = Config.relativeCoordinates ? worldPosition.getY() : 0;
                int bz = Config.relativeCoordinates ? worldPosition.getZ() : 0;
                int relX = bpos.getX() - bx;
                int relY = bpos.getY() - by;
                int relZ = bpos.getZ() - bz;
                String face = payload.face().getSerializedName();
                BlockState state = player.level().getBlockState(bpos);
                String blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();

                evt.put("targetType", "block");
                evt.put("blockX", relX);
                evt.put("blockY", relY);
                evt.put("blockZ", relZ);
                evt.put("face", face);
                evt.put("blockId", blockId);
                enqueueEvent(evt);

                emitSignal("glasses_gaze", playerUuid, playerName, "block",
                        hitX, hitY, hitZ, relX, relY, relZ, face, blockId);
            }
            case ENTITY -> {
                Entity target = player.level().getEntity(payload.entityId());
                if (target == null || target.isRemoved() || target.distanceToSqr(player) > 68.0 * 68.0) {
                    return;
                }
                String entityUuid = target.getUUID().toString();
                String entityName = target.getName().getString();
                String entityType = BuiltInRegistries.ENTITY_TYPE.getKey(target.getType()).toString();

                evt.put("targetType", "entity");
                evt.put("entityUuid", entityUuid);
                evt.put("entityName", entityName);
                evt.put("entityType", entityType);
                enqueueEvent(evt);

                emitSignal("glasses_gaze", playerUuid, playerName, "entity",
                        hitX, hitY, hitZ, entityUuid, entityName, entityType);
            }
            case MISS -> {
                evt.put("targetType", "miss");
                enqueueEvent(evt);
                emitSignal("glasses_gaze", playerUuid, playerName, "miss", hitX, hitY, hitZ);
            }
        }
    }

    private void emitPlayerConnectionEvent(String eventName, ServerPlayer player, int tier) {
        String playerUuid = player.getUUID().toString();
        String playerName = player.getGameProfile().getName();
        Map<String, Object> evt = new LinkedHashMap<>();
        evt.put("event", eventName);
        evt.put("playerUuid", playerUuid);
        evt.put("playerName", playerName);
        if ("glasses_player_connected".equals(eventName)) {
            evt.put("tier", tier);
            enqueueEvent(evt);
            emitSignal(eventName, playerUuid, playerName, tier);
        } else {
            enqueueEvent(evt);
            emitSignal(eventName, playerUuid, playerName);
        }
    }

    private void enqueueEvent(Map<String, Object> eventData) {
        while (eventQueue.size() >= MAX_QUEUED_EVENTS) {
            eventQueue.poll();
        }
        eventQueue.offer(eventData);
    }

    private void emitSignal(Object... signalArgs) {
        if (node != null && node.network() != null) {
            node.sendToReachable("computer.signal", signalArgs);
        }
    }

    // -------------------------------------------------------------------------
    // Widget Mutation Helpers
    // -------------------------------------------------------------------------

    private void upsertGlobal(HudWidget widget) {
        if (!globalWidgets.containsKey(widget.id()) && globalWidgets.size() >= MAX_WIDGETS_PER_LAYER) {
            throw new IllegalStateException("Global HUD widget limit reached (" + MAX_WIDGETS_PER_LAYER + ")");
        }
        globalWidgets.put(widget.id(), widget);
        globalDirtyRemoves.remove(widget.id());
        globalDirtyUpserts.add(widget.id());
    }

    private void upsertPlayer(UUID playerUuid, HudWidget widget) {
        Map<String, HudWidget> map = playerWidgets.computeIfAbsent(playerUuid, k -> new LinkedHashMap<>());
        if (!map.containsKey(widget.id()) && map.size() >= MAX_WIDGETS_PER_LAYER) {
            throw new IllegalStateException("Player HUD widget limit reached (" + MAX_WIDGETS_PER_LAYER + ")");
        }
        map.put(widget.id(), widget);
        LayerDiff diff = playerDiffs.computeIfAbsent(playerUuid, k -> new LayerDiff());
        diff.removes.remove(widget.id());
        diff.upserts.add(widget.id());
    }

    private void removeGlobalWidget(String id) {
        if (globalWidgets.remove(id) != null) {
            globalDirtyUpserts.remove(id);
            globalDirtyRemoves.add(id);
        }
    }

    private void removePlayerWidget(UUID playerUuid, String id) {
        Map<String, HudWidget> map = playerWidgets.get(playerUuid);
        if (map != null && map.remove(id) != null) {
            LayerDiff diff = playerDiffs.computeIfAbsent(playerUuid, k -> new LayerDiff());
            diff.upserts.remove(id);
            diff.removes.add(id);
        }
    }

    // -------------------------------------------------------------------------
    // Argument Parsing Helpers
    // -------------------------------------------------------------------------

    private static int checkColor(Arguments args, int index, int def) {
        if (args.count() <= index || args.checkAny(index) == null) {
            return def;
        }
        if (args.isString(index)) {
            String raw = args.checkString(index).trim();
            if (raw.startsWith("#")) raw = raw.substring(1);
            else if (raw.startsWith("0x") || raw.startsWith("0X")) raw = raw.substring(2);
            return (int) Long.parseLong(raw, 16);
        }
        return (int) (long) args.checkDouble(index);
    }

    private static List<Float> parsePoints(Map<?, ?> table) {
        List<Float> points = new ArrayList<>();
        if (table == null || table.isEmpty()) return points;

        // OpenComputers converts Lua array tables into Map<Double|Integer, Object> (1-indexed)
        int maxIndex = 0;
        boolean numericKeys = true;
        for (Object key : table.keySet()) {
            if (key instanceof Number n) {
                int idx = n.intValue();
                if (idx >= 1 && idx <= HudWidget.MAX_GRAPH_POINTS * 4) {
                    maxIndex = Math.max(maxIndex, idx);
                }
            } else {
                numericKeys = false;
            }
        }
        if (numericKeys && maxIndex > 0) {
            for (int i = 1; i <= maxIndex && points.size() < HudWidget.MAX_GRAPH_POINTS; i++) {
                Object v = table.get(i);
                if (v == null) v = table.get((double) i);
                if (v instanceof Number n) {
                    points.add(n.floatValue());
                }
            }
            return points;
        }
        for (Object v : table.values()) {
            if (points.size() >= HudWidget.MAX_GRAPH_POINTS) break;
            if (v instanceof Number n) {
                points.add(n.floatValue());
            }
        }
        return points;
    }

    private static UUID parseUuid(String s) {
        return UUID.fromString(s.trim());
    }

    @Nullable
    private Object[] checkPlayerTierForDirectCall(UUID playerUuid, int minTier) {
        if (!(level instanceof ServerLevel serverLevel)) return null;
        ServerPlayer player = serverLevel.getServer().getPlayerList().getPlayer(playerUuid);
        if (player != null && isPlayerConnected(player) && getPlayerTier(player) < minTier) {
            return new Object[]{null, "tier " + minTier + " smart glasses required"};
        }
        return null;
    }

    // -------------------------------------------------------------------------
    // OpenComputers Callbacks: General & Prefix & Telemetry
    // -------------------------------------------------------------------------

    @Callback(direct = true, doc = "function():string -- Returns the unique UUID of this Glasses Bridge.")
    public Object[] getBridgeId(Context context, Arguments args) {
        return new Object[]{bridgeId.toString()};
    }

    @Callback(direct = true, doc = "function():boolean -- Returns true if coordinates are relative to the bridge block.")
    public Object[] isRelativeCoordinates(Context context, Arguments args) {
        return new Object[]{Config.relativeCoordinates};
    }

    @Callback(direct = true, doc = "function():string -- Returns the active Tier 2+ chat command prefix (default '$$').")
    public Object[] getChatPrefix(Context context, Arguments args) {
        return new Object[]{getChatPrefix()};
    }

    @Callback(doc = "function(prefix:string):string -- Sets the Tier 2+ chat command prefix (1-16 non-blank chars). Returns the previous prefix.")
    public Object[] setChatPrefix(Context context, Arguments args) {
        String next = args.checkString(0);
        if (next.isBlank() || next.length() > 16) {
            throw new IllegalArgumentException("Chat prefix must be 1..16 non-blank characters");
        }
        String old = getChatPrefix();
        this.chatPrefix = next;
        setChanged();
        return new Object[]{old};
    }

    @Callback(doc = "function():table -- Returns a list of connected players wearing Smart Glasses.")
    public Object[] getConnectedPlayers(Context context, Arguments args) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return new Object[]{List.of()};
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (ServerPlayer player : serverLevel.getServer().getPlayerList().getPlayers()) {
            if (!isPlayerConnected(player)) continue;
            int tier = getPlayerTier(player);
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("uuid", player.getUUID().toString());
            info.put("name", player.getGameProfile().getName());
            info.put("tier", tier);
            info.put("dimension", player.level().dimension().location().toString());
            int[] screen = playerScreenSizes.get(player.getUUID());
            info.put("screenWidth", screen != null ? screen[0] : 0);
            info.put("screenHeight", screen != null ? screen[1] : 0);
            if (tier >= 3) {
                double ox = Config.relativeCoordinates ? worldPosition.getX() : 0.0;
                double oy = Config.relativeCoordinates ? worldPosition.getY() : 0.0;
                double oz = Config.relativeCoordinates ? worldPosition.getZ() : 0.0;
                info.put("x", player.getX() - ox);
                info.put("y", player.getY() - oy);
                info.put("z", player.getZ() - oz);
            }
            result.add(info);
        }
        return new Object[]{result};
    }

    @Callback(doc = "function(playerUuid:string):table -- Returns wearer telemetry (Tier 3 glasses required): hp, food, relative pos, armor, xp, energy, etc.")
    public Object[] getPlayerStats(Context context, Arguments args) {
        String uuidStr = args.checkString(0);
        ServerPlayer player = findConnectedPlayer(uuidStr);
        if (player == null) {
            return new Object[]{null, "player not connected"};
        }
        int tier = getPlayerTier(player);
        if (tier < 3) {
            return new Object[]{null, "tier 3 smart glasses required"};
        }
        ItemStack glasses = SmartGlassesItem.getEquippedGlasses(player);
        double ox = Config.relativeCoordinates ? worldPosition.getX() : 0.0;
        double oy = Config.relativeCoordinates ? worldPosition.getY() : 0.0;
        double oz = Config.relativeCoordinates ? worldPosition.getZ() : 0.0;

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("uuid", player.getUUID().toString());
        stats.put("name", player.getGameProfile().getName());
        stats.put("tier", tier);
        stats.put("hp", player.getHealth());
        stats.put("maxHp", player.getMaxHealth());
        stats.put("food", player.getFoodData().getFoodLevel());
        stats.put("saturation", player.getFoodData().getSaturationLevel());
        stats.put("armor", player.getArmorValue());
        stats.put("air", player.getAirSupply());
        stats.put("maxAir", player.getMaxAirSupply());
        stats.put("xpLevel", player.experienceLevel);
        stats.put("xpProgress", player.experienceProgress);
        stats.put("yaw", player.getYRot());
        stats.put("pitch", player.getXRot());
        stats.put("x", player.getX() - ox);
        stats.put("y", player.getY() - oy);
        stats.put("z", player.getZ() - oz);
        stats.put("dimension", player.level().dimension().location().toString());
        stats.put("biome", player.level().getBiome(player.blockPosition()).unwrapKey()
                .map(k -> k.location().toString()).orElse("unknown"));
        stats.put("dayTime", player.level().getDayTime() % 24000L);
        stats.put("isSprinting", player.isSprinting());
        stats.put("isSneaking", player.isShiftKeyDown());
        stats.put("isInWater", player.isInWater());
        stats.put("isOnGround", player.onGround());
        stats.put("energy", SmartGlassesItem.getEnergy(glasses));
        stats.put("maxEnergy", Config.energyCapacity);
        int[] screen = playerScreenSizes.get(player.getUUID());
        stats.put("screenWidth", screen != null ? screen[0] : 0);
        stats.put("screenHeight", screen != null ? screen[1] : 0);
        return new Object[]{stats};
    }

    @Callback(direct = true, doc = "function(playerUuid:string):number, number -- Returns the wearer's current scaled GUI screenWidth and screenHeight.")
    public Object[] getScreenSize(Context context, Arguments args) {
        ServerPlayer player = findConnectedPlayer(args.checkString(0));
        if (player == null) {
            return new Object[]{null, "player not connected"};
        }
        int[] screen = playerScreenSizes.get(player.getUUID());
        if (screen == null) {
            return new Object[]{null, "screen size not reported yet"};
        }
        return new Object[]{screen[0], screen[1]};
    }

    @Callback(doc = "function(playerUuid:string, soundId:string[, volume:number, pitch:number]):boolean -- Plays a private sound notification on a connected wearer's glasses.")
    public Object[] playSound(Context context, Arguments args) {
        ServerPlayer player = findConnectedPlayer(args.checkString(0));
        if (player == null) {
            return new Object[]{false, "player not connected"};
        }
        String soundId = args.checkString(1).trim();
        if (ResourceLocation.tryParse(soundId) == null) {
            return new Object[]{false, "invalid soundId: " + soundId};
        }
        float volume = Mth.clamp((float) args.optDouble(2, 1.0), 0.0f, 2.0f);
        float pitch = Mth.clamp((float) args.optDouble(3, 1.0), 0.5f, 2.0f);
        PacketDistributor.sendToPlayer(player, new GlassesSoundPayload(soundId, volume, pitch));
        return new Object[]{true};
    }

    @Callback(doc = "function(soundId:string[, volume:number, pitch:number]):number -- Plays a sound notification on all connected glasses.")
    public Object[] broadcastSound(Context context, Arguments args) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return new Object[]{0};
        }
        String soundId = args.checkString(0).trim();
        if (ResourceLocation.tryParse(soundId) == null) {
            throw new IllegalArgumentException("Invalid soundId: " + soundId);
        }
        float volume = Mth.clamp((float) args.optDouble(1, 1.0), 0.0f, 2.0f);
        float pitch = Mth.clamp((float) args.optDouble(2, 1.0), 0.5f, 2.0f);
        GlassesSoundPayload payload = new GlassesSoundPayload(soundId, volume, pitch);
        int count = 0;
        for (ServerPlayer player : serverLevel.getServer().getPlayerList().getPlayers()) {
            if (isPlayerConnected(player)) {
                PacketDistributor.sendToPlayer(player, payload);
                count++;
            }
        }
        return new Object[]{count};
    }

    @Callback(doc = "function(playerUuid:string, message:string):boolean -- Sends a chat message to a connected Tier 2+ wearer.")
    public Object[] sendMessage(Context context, Arguments args) {
        ServerPlayer player = findConnectedPlayer(args.checkString(0));
        if (player == null) {
            return new Object[]{false, "player not connected"};
        }
        if (getPlayerTier(player) < 2) {
            return new Object[]{false, "tier 2 smart glasses required"};
        }
        String message = args.checkString(1);
        player.sendSystemMessage(Component.literal("[Glasses] ").withStyle(ChatFormatting.AQUA)
                .append(Component.literal(message).withStyle(ChatFormatting.WHITE)));
        return new Object[]{true};
    }

    @Callback(doc = "function(message:string):number -- Broadcasts a chat message to all connected Tier 2+ wearers.")
    public Object[] broadcastMessage(Context context, Arguments args) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return new Object[]{0};
        }
        String message = args.checkString(0);
        Component comp = Component.literal("[Glasses] ").withStyle(ChatFormatting.AQUA)
                .append(Component.literal(message).withStyle(ChatFormatting.WHITE));
        int count = 0;
        for (ServerPlayer player : serverLevel.getServer().getPlayerList().getPlayers()) {
            if (isPlayerConnected(player) && getPlayerTier(player) >= 2) {
                player.sendSystemMessage(comp);
                count++;
            }
        }
        return new Object[]{count};
    }

    @Callback(direct = true, doc = "function():table|nil -- Pops and returns the oldest queued glasses event, or nil.")
    public Object[] pollEvent(Context context, Arguments args) {
        return new Object[]{eventQueue.poll()};
    }

    @Callback(direct = true, doc = "function():table -- Returns and clears all queued glasses events.")
    public Object[] getEvents(Context context, Arguments args) {
        List<Map<String, Object>> drained = new ArrayList<>();
        Map<String, Object> item;
        while ((item = eventQueue.poll()) != null) {
            drained.add(item);
        }
        return new Object[]{drained};
    }

    // -------------------------------------------------------------------------
    // OpenComputers Callbacks: Global Layer (Tier 1+ 2D, Tier 2+ Graph/Click, Tier 3 3D)
    // -------------------------------------------------------------------------

    @Callback(doc = "function():boolean -- Clears all global HUD/AR widgets.")
    public Object[] clear(Context context, Arguments args) {
        globalWidgets.clear();
        globalDirtyUpserts.clear();
        globalDirtyRemoves.clear();
        globalClearAll = true;
        return new Object[]{true};
    }

    @Callback(doc = "function(id:string):boolean -- Removes a global widget by ID.")
    public Object[] removeWidget(Context context, Arguments args) {
        removeGlobalWidget(args.checkString(0));
        return new Object[]{true};
    }

    @Callback(doc = "function(id:string, x:number, y:number, width:number, height:number[, color:number]):boolean -- Adds or updates a 2D rectangle (Tier 1+).")
    public Object[] addRect(Context context, Arguments args) {
        String id = args.checkString(0);
        float x = (float) args.checkDouble(1);
        float y = (float) args.checkDouble(2);
        float w = (float) args.checkDouble(3);
        float h = (float) args.checkDouble(4);
        int color = checkColor(args, 5, 0x80000000);
        upsertGlobal(HudWidget.rect(id, x, y, w, h, color));
        return new Object[]{true};
    }

    @Callback(doc = "function(id:string, x:number, y:number, text:string[, color:number, scale:number]):boolean -- Adds or updates 2D text (Tier 1+).")
    public Object[] addText(Context context, Arguments args) {
        String id = args.checkString(0);
        float x = (float) args.checkDouble(1);
        float y = (float) args.checkDouble(2);
        String text = args.checkString(3);
        int color = checkColor(args, 4, 0xFFFFFFFF);
        float scale = (float) args.optDouble(5, 1.0);
        upsertGlobal(HudWidget.text(id, x, y, text, color, scale));
        return new Object[]{true};
    }

    @Callback(doc = "function(id:string, x:number, y:number, width:number, height:number, value:number, max:number[, fgColor:number, bgColor:number]):boolean -- Adds or updates a progress bar (Tier 1+).")
    public Object[] addProgressBar(Context context, Arguments args) {
        String id = args.checkString(0);
        float x = (float) args.checkDouble(1);
        float y = (float) args.checkDouble(2);
        float w = (float) args.checkDouble(3);
        float h = (float) args.checkDouble(4);
        float value = (float) args.checkDouble(5);
        float max = (float) args.checkDouble(6);
        int fgColor = checkColor(args, 7, 0xFF00E5FF);
        int bgColor = checkColor(args, 8, 0x80202020);
        upsertGlobal(HudWidget.progressBar(id, x, y, w, h, value, max, fgColor, bgColor));
        return new Object[]{true};
    }

    @Callback(doc = "function(id:string, x:number, y:number, itemId:string[, scale:number]):boolean -- Adds or updates an item icon (Tier 1+).")
    public Object[] addItemIcon(Context context, Arguments args) {
        String id = args.checkString(0);
        float x = (float) args.checkDouble(1);
        float y = (float) args.checkDouble(2);
        String itemId = args.checkString(3);
        float scale = (float) args.optDouble(4, 1.0);
        upsertGlobal(HudWidget.itemIcon(id, x, y, itemId, scale));
        return new Object[]{true};
    }

    @Callback(doc = "function(id:string, x:number, y:number, width:number, height:number, values:table[, fgColor:number, bgColor:number]):boolean -- Adds or updates a live data graph (Tier 2+).")
    public Object[] addGraph(Context context, Arguments args) {
        String id = args.checkString(0);
        float x = (float) args.checkDouble(1);
        float y = (float) args.checkDouble(2);
        float w = (float) args.checkDouble(3);
        float h = (float) args.checkDouble(4);
        List<Float> points = parsePoints(args.checkTable(5));
        int fgColor = checkColor(args, 6, 0xFF00E5FF);
        int bgColor = checkColor(args, 7, 0x80101820);
        upsertGlobal(HudWidget.graph(id, x, y, w, h, points, fgColor, bgColor));
        return new Object[]{true};
    }

    @Callback(doc = "function(id:string, anchor:string):boolean -- Sets the screen anchor (TOP_LEFT, TOP_CENTER, TOP_RIGHT, CENTER_LEFT, CENTER, CENTER_RIGHT, BOTTOM_LEFT, BOTTOM_CENTER, BOTTOM_RIGHT).")
    public Object[] setAnchor(Context context, Arguments args) {
        String id = args.checkString(0);
        Anchor anchor = Anchor.fromName(args.checkString(1));
        HudWidget existing = globalWidgets.get(id);
        if (existing == null) {
            return new Object[]{false, "widget not found"};
        }
        upsertGlobal(existing.withAnchor(anchor));
        return new Object[]{true};
    }

    @Callback(doc = "function(id:string, clickable:boolean):boolean -- Enables or disables cursor clicking on a 2D widget (Tier 2+).")
    public Object[] setClickable(Context context, Arguments args) {
        String id = args.checkString(0);
        boolean clickable = args.checkBoolean(1);
        HudWidget existing = globalWidgets.get(id);
        if (existing == null) {
            return new Object[]{false, "widget not found"};
        }
        upsertGlobal(existing.withClickable(clickable));
        return new Object[]{true};
    }

    @Callback(doc = "function(id:string, x:number, y:number, z:number, label:string[, color:number]):boolean -- Adds a 3D world marker relative to the bridge (Tier 3).")
    public Object[] addWorldMarker(Context context, Arguments args) {
        String id = args.checkString(0);
        float x = (float) args.checkDouble(1);
        float y = (float) args.checkDouble(2);
        float z = (float) args.checkDouble(3);
        String label = args.optString(4, "");
        int color = checkColor(args, 5, 0xFF00E5FF);
        upsertGlobal(HudWidget.worldMarker(id, x, y, z, label, color));
        return new Object[]{true};
    }

    @Callback(doc = "function(id:string, x1:number, y1:number, z1:number, x2:number, y2:number, z2:number[, color:number]):boolean -- Adds a 3D world line relative to the bridge (Tier 3).")
    public Object[] addWorldLine(Context context, Arguments args) {
        String id = args.checkString(0);
        float x1 = (float) args.checkDouble(1);
        float y1 = (float) args.checkDouble(2);
        float z1 = (float) args.checkDouble(3);
        float x2 = (float) args.checkDouble(4);
        float y2 = (float) args.checkDouble(5);
        float z2 = (float) args.checkDouble(6);
        int color = checkColor(args, 7, 0xFF00E5FF);
        upsertGlobal(HudWidget.worldLine(id, x1, y1, z1, x2, y2, z2, color));
        return new Object[]{true};
    }

    @Callback(doc = "function(id:string, x1:number, y1:number, z1:number, x2:number, y2:number, z2:number[, label:string, color:number]):boolean -- Adds a 3D wireframe bounding box relative to the bridge (Tier 3).")
    public Object[] addWorldBox(Context context, Arguments args) {
        String id = args.checkString(0);
        float x1 = (float) args.checkDouble(1);
        float y1 = (float) args.checkDouble(2);
        float z1 = (float) args.checkDouble(3);
        float x2 = (float) args.checkDouble(4);
        float y2 = (float) args.checkDouble(5);
        float z2 = (float) args.checkDouble(6);
        String label = args.optString(7, "");
        int color = checkColor(args, 8, 0xFF00E5FF);
        upsertGlobal(HudWidget.worldBox(id, x1, y1, z1, x2, y2, z2, label, color));
        return new Object[]{true};
    }

    @Callback(doc = "function(id:string, entityUuid:string, label:string[, color:number]):boolean -- Adds a 3D entity tracking box (Tier 3).")
    public Object[] addEntityMarker(Context context, Arguments args) {
        String id = args.checkString(0);
        String entityUuid = args.checkString(1);
        String label = args.optString(2, "");
        int color = checkColor(args, 3, 0xFFFF5555);
        upsertGlobal(HudWidget.entityMarker(id, entityUuid, label, color));
        return new Object[]{true};
    }

    // -------------------------------------------------------------------------
    // OpenComputers Callbacks: Per-Player Layer
    // -------------------------------------------------------------------------

    @Callback(doc = "function(playerUuid:string):boolean -- Clears all per-player widgets for a wearer.")
    public Object[] clearPlayer(Context context, Arguments args) {
        UUID pid = parseUuid(args.checkString(0));
        playerWidgets.remove(pid);
        LayerDiff diff = playerDiffs.computeIfAbsent(pid, k -> new LayerDiff());
        diff.upserts.clear();
        diff.removes.clear();
        diff.clearAll = true;
        return new Object[]{true};
    }

    @Callback(doc = "function(playerUuid:string, id:string):boolean -- Removes a per-player widget by ID.")
    public Object[] removePlayerWidget(Context context, Arguments args) {
        removePlayerWidget(parseUuid(args.checkString(0)), args.checkString(1));
        return new Object[]{true};
    }

    @Callback(doc = "function(playerUuid:string, id:string, x:number, y:number, width:number, height:number[, color:number]):boolean -- Adds a per-player 2D rectangle (Tier 1+).")
    public Object[] addPlayerRect(Context context, Arguments args) {
        UUID pid = parseUuid(args.checkString(0));
        String id = args.checkString(1);
        float x = (float) args.checkDouble(2);
        float y = (float) args.checkDouble(3);
        float w = (float) args.checkDouble(4);
        float h = (float) args.checkDouble(5);
        int color = checkColor(args, 6, 0x80000000);
        upsertPlayer(pid, HudWidget.rect(id, x, y, w, h, color));
        return new Object[]{true};
    }

    @Callback(doc = "function(playerUuid:string, id:string, x:number, y:number, text:string[, color:number, scale:number]):boolean -- Adds per-player 2D text (Tier 1+).")
    public Object[] addPlayerText(Context context, Arguments args) {
        UUID pid = parseUuid(args.checkString(0));
        String id = args.checkString(1);
        float x = (float) args.checkDouble(2);
        float y = (float) args.checkDouble(3);
        String text = args.checkString(4);
        int color = checkColor(args, 5, 0xFFFFFFFF);
        float scale = (float) args.optDouble(6, 1.0);
        upsertPlayer(pid, HudWidget.text(id, x, y, text, color, scale));
        return new Object[]{true};
    }

    @Callback(doc = "function(playerUuid:string, id:string, x:number, y:number, width:number, height:number, value:number, max:number[, fgColor:number, bgColor:number]):boolean -- Adds a per-player progress bar (Tier 1+).")
    public Object[] addPlayerProgressBar(Context context, Arguments args) {
        UUID pid = parseUuid(args.checkString(0));
        String id = args.checkString(1);
        float x = (float) args.checkDouble(2);
        float y = (float) args.checkDouble(3);
        float w = (float) args.checkDouble(4);
        float h = (float) args.checkDouble(5);
        float value = (float) args.checkDouble(6);
        float max = (float) args.checkDouble(7);
        int fgColor = checkColor(args, 8, 0xFF00E5FF);
        int bgColor = checkColor(args, 9, 0x80202020);
        upsertPlayer(pid, HudWidget.progressBar(id, x, y, w, h, value, max, fgColor, bgColor));
        return new Object[]{true};
    }

    @Callback(doc = "function(playerUuid:string, id:string, x:number, y:number, itemId:string[, scale:number]):boolean -- Adds a per-player item icon (Tier 1+).")
    public Object[] addPlayerItemIcon(Context context, Arguments args) {
        UUID pid = parseUuid(args.checkString(0));
        String id = args.checkString(1);
        float x = (float) args.checkDouble(2);
        float y = (float) args.checkDouble(3);
        String itemId = args.checkString(4);
        float scale = (float) args.optDouble(5, 1.0);
        upsertPlayer(pid, HudWidget.itemIcon(id, x, y, itemId, scale));
        return new Object[]{true};
    }

    @Callback(doc = "function(playerUuid:string, id:string, x:number, y:number, width:number, height:number, values:table[, fgColor:number, bgColor:number]):boolean -- Adds a per-player live graph (Tier 2+).")
    public Object[] addPlayerGraph(Context context, Arguments args) {
        UUID pid = parseUuid(args.checkString(0));
        Object[] err = checkPlayerTierForDirectCall(pid, 2);
        if (err != null) return err;

        String id = args.checkString(1);
        float x = (float) args.checkDouble(2);
        float y = (float) args.checkDouble(3);
        float w = (float) args.checkDouble(4);
        float h = (float) args.checkDouble(5);
        List<Float> points = parsePoints(args.checkTable(6));
        int fgColor = checkColor(args, 7, 0xFF00E5FF);
        int bgColor = checkColor(args, 8, 0x80101820);
        upsertPlayer(pid, HudWidget.graph(id, x, y, w, h, points, fgColor, bgColor));
        return new Object[]{true};
    }

    @Callback(doc = "function(playerUuid:string, id:string, anchor:string):boolean -- Sets the anchor on a per-player widget.")
    public Object[] setPlayerAnchor(Context context, Arguments args) {
        UUID pid = parseUuid(args.checkString(0));
        String id = args.checkString(1);
        Anchor anchor = Anchor.fromName(args.checkString(2));
        Map<String, HudWidget> map = playerWidgets.get(pid);
        HudWidget existing = map != null ? map.get(id) : null;
        if (existing == null) {
            return new Object[]{false, "widget not found"};
        }
        upsertPlayer(pid, existing.withAnchor(anchor));
        return new Object[]{true};
    }

    @Callback(doc = "function(playerUuid:string, id:string, clickable:boolean):boolean -- Enables or disables cursor clicking on a per-player widget (Tier 2+).")
    public Object[] setPlayerClickable(Context context, Arguments args) {
        UUID pid = parseUuid(args.checkString(0));
        Object[] err = checkPlayerTierForDirectCall(pid, 2);
        if (err != null) return err;

        String id = args.checkString(1);
        boolean clickable = args.checkBoolean(2);
        Map<String, HudWidget> map = playerWidgets.get(pid);
        HudWidget existing = map != null ? map.get(id) : null;
        if (existing == null) {
            return new Object[]{false, "widget not found"};
        }
        upsertPlayer(pid, existing.withClickable(clickable));
        return new Object[]{true};
    }

    @Callback(doc = "function(playerUuid:string, id:string, x:number, y:number, z:number, label:string[, color:number]):boolean -- Adds a per-player 3D world marker (Tier 3).")
    public Object[] addPlayerWorldMarker(Context context, Arguments args) {
        UUID pid = parseUuid(args.checkString(0));
        Object[] err = checkPlayerTierForDirectCall(pid, 3);
        if (err != null) return err;

        String id = args.checkString(1);
        float x = (float) args.checkDouble(2);
        float y = (float) args.checkDouble(3);
        float z = (float) args.checkDouble(4);
        String label = args.optString(5, "");
        int color = checkColor(args, 6, 0xFF00E5FF);
        upsertPlayer(pid, HudWidget.worldMarker(id, x, y, z, label, color));
        return new Object[]{true};
    }

    @Callback(doc = "function(playerUuid:string, id:string, x1:number, y1:number, z1:number, x2:number, y2:number, z2:number[, color:number]):boolean -- Adds a per-player 3D world line (Tier 3).")
    public Object[] addPlayerWorldLine(Context context, Arguments args) {
        UUID pid = parseUuid(args.checkString(0));
        Object[] err = checkPlayerTierForDirectCall(pid, 3);
        if (err != null) return err;

        String id = args.checkString(1);
        float x1 = (float) args.checkDouble(2);
        float y1 = (float) args.checkDouble(3);
        float z1 = (float) args.checkDouble(4);
        float x2 = (float) args.checkDouble(5);
        float y2 = (float) args.checkDouble(6);
        float z2 = (float) args.checkDouble(7);
        int color = checkColor(args, 8, 0xFF00E5FF);
        upsertPlayer(pid, HudWidget.worldLine(id, x1, y1, z1, x2, y2, z2, color));
        return new Object[]{true};
    }

    @Callback(doc = "function(playerUuid:string, id:string, x1:number, y1:number, z1:number, x2:number, y2:number, z2:number[, label:string, color:number]):boolean -- Adds a per-player 3D wireframe bounding box (Tier 3).")
    public Object[] addPlayerWorldBox(Context context, Arguments args) {
        UUID pid = parseUuid(args.checkString(0));
        Object[] err = checkPlayerTierForDirectCall(pid, 3);
        if (err != null) return err;

        String id = args.checkString(1);
        float x1 = (float) args.checkDouble(2);
        float y1 = (float) args.checkDouble(3);
        float z1 = (float) args.checkDouble(4);
        float x2 = (float) args.checkDouble(5);
        float y2 = (float) args.checkDouble(6);
        float z2 = (float) args.checkDouble(7);
        String label = args.optString(8, "");
        int color = checkColor(args, 9, 0xFF00E5FF);
        upsertPlayer(pid, HudWidget.worldBox(id, x1, y1, z1, x2, y2, z2, label, color));
        return new Object[]{true};
    }

    @Callback(doc = "function(playerUuid:string, id:string, entityUuid:string, label:string[, color:number]):boolean -- Adds a per-player 3D entity marker (Tier 3).")
    public Object[] addEntityPlayerMarker(Context context, Arguments args) {
        return addPlayerEntityMarker(context, args);
    }

    @Callback(doc = "function(playerUuid:string, id:string, entityUuid:string, label:string[, color:number]):boolean -- Adds a per-player 3D entity marker (Tier 3).")
    public Object[] addPlayerEntityMarker(Context context, Arguments args) {
        UUID pid = parseUuid(args.checkString(0));
        Object[] err = checkPlayerTierForDirectCall(pid, 3);
        if (err != null) return err;

        String id = args.checkString(1);
        String entityUuid = args.checkString(2);
        String label = args.optString(3, "");
        int color = checkColor(args, 4, 0xFFFF5555);
        upsertPlayer(pid, HudWidget.entityMarker(id, entityUuid, label, color));
        return new Object[]{true};
    }
}
