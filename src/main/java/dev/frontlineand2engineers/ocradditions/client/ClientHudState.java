package dev.frontlineand2engineers.ocradditions.client;

import dev.frontlineand2engineers.ocradditions.hud.HudWidget;
import dev.frontlineand2engineers.ocradditions.network.SyncHudPayload;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Client-side cache of active HUD and 3D AR widgets synced from the connected Glasses Bridge.
 */
public final class ClientHudState {
    private static final Map<String, HudWidget> WIDGETS = new LinkedHashMap<>();

    private ClientHudState() {
    }

    public static synchronized void apply(SyncHudPayload payload) {
        if (payload.clearAll()) {
            WIDGETS.clear();
        }
        for (String id : payload.removes()) {
            WIDGETS.remove(id);
        }
        for (HudWidget w : payload.upserts()) {
            WIDGETS.put(w.id(), w);
        }
    }

    public static synchronized void clear() {
        WIDGETS.clear();
    }

    public static synchronized List<HudWidget> snapshot() {
        return List.copyOf(WIDGETS.values());
    }

    public static synchronized Collection<HudWidget> values() {
        return List.copyOf(WIDGETS.values());
    }
}
