package dev.frontlineand2engineers.ocradditions.blockentity;

import javax.annotation.Nullable;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-side registry mapping bridge UUIDs to active GlassesBridgeBlockEntity instances.
 */
public final class BridgeRegistry {
    private static final Map<UUID, GlassesBridgeBlockEntity> BRIDGES = new ConcurrentHashMap<>();

    private BridgeRegistry() {
    }

    public static void register(UUID id, GlassesBridgeBlockEntity bridge) {
        if (id != null && bridge != null) {
            BRIDGES.put(id, bridge);
        }
    }

    public static void unregister(UUID id, GlassesBridgeBlockEntity bridge) {
        if (id != null && bridge != null) {
            BRIDGES.remove(id, bridge);
        }
    }

    @Nullable
    public static GlassesBridgeBlockEntity get(UUID id) {
        if (id == null) return null;
        GlassesBridgeBlockEntity bridge = BRIDGES.get(id);
        if (bridge != null && bridge.isRemoved()) {
            BRIDGES.remove(id, bridge);
            return null;
        }
        return bridge;
    }

    public static Collection<GlassesBridgeBlockEntity> all() {
        return Collections.unmodifiableCollection(BRIDGES.values());
    }
}
