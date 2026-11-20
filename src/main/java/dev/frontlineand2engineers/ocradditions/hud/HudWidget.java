package dev.frontlineand2engineers.ocradditions.hud;

import net.minecraft.network.FriendlyByteBuf;

import java.util.ArrayList;
import java.util.List;

/**
 * Immutable representation of a 2D HUD or 3D AR widget rendered on Smart Glasses.
 */
public record HudWidget(
        String id,
        Type type,
        Anchor anchor,
        float x,
        float y,
        float z,
        float x2,
        float y2,
        float z2,
        float width,
        float height,
        int color,
        int bgColor,
        float scale,
        float value,
        float max,
        String text,
        String targetUuid,
        boolean clickable,
        List<Float> points
) {
    public static final int MAX_GRAPH_POINTS = 256;

    public enum Type {
        RECT(1),
        TEXT(1),
        PROGRESS_BAR(1),
        ITEM_ICON(1),
        GRAPH(2),
        WORLD_MARKER(3),
        WORLD_LINE(3),
        WORLD_BOX(3),
        ENTITY_MARKER(3);

        public final int requiredTier;

        Type(int requiredTier) {
            this.requiredTier = requiredTier;
        }
    }

    public HudWidget withAnchor(Anchor newAnchor) {
        return new HudWidget(id, type, newAnchor, x, y, z, x2, y2, z2, width, height,
                color, bgColor, scale, value, max, text, targetUuid, clickable, points);
    }

    public HudWidget withClickable(boolean newClickable) {
        return new HudWidget(id, type, anchor, x, y, z, x2, y2, z2, width, height,
                color, bgColor, scale, value, max, text, targetUuid, newClickable, points);
    }

    public boolean isWorldWidget() {
        return type == Type.WORLD_MARKER || type == Type.WORLD_LINE || type == Type.WORLD_BOX || type == Type.ENTITY_MARKER;
    }

    public static HudWidget rect(String id, float x, float y, float width, float height, int color) {
        return new HudWidget(id, Type.RECT, Anchor.TOP_LEFT, x, y, 0, 0, 0, 0,
                width, height, color, 0, 1.0f, 0, 0, "", "", false, List.of());
    }

    public static HudWidget text(String id, float x, float y, String text, int color, float scale) {
        return new HudWidget(id, Type.TEXT, Anchor.TOP_LEFT, x, y, 0, 0, 0, 0,
                0, 0, color, 0, scale, 0, 0, text, "", false, List.of());
    }

    public static HudWidget progressBar(String id, float x, float y, float width, float height,
                                          float value, float max, int fgColor, int bgColor) {
        return new HudWidget(id, Type.PROGRESS_BAR, Anchor.TOP_LEFT, x, y, 0, 0, 0, 0,
                width, height, fgColor, bgColor, 1.0f, value, max, "", "", false, List.of());
    }

    public static HudWidget itemIcon(String id, float x, float y, String itemId, float scale) {
        return new HudWidget(id, Type.ITEM_ICON, Anchor.TOP_LEFT, x, y, 0, 0, 0, 0,
                16, 16, 0xFFFFFFFF, 0, scale, 0, 0, itemId, "", false, List.of());
    }

    public static HudWidget graph(String id, float x, float y, float width, float height,
                                  List<Float> points, int fgColor, int bgColor) {
        List<Float> capped = points.size() > MAX_GRAPH_POINTS
                ? List.copyOf(points.subList(points.size() - MAX_GRAPH_POINTS, points.size()))
                : List.copyOf(points);
        return new HudWidget(id, Type.GRAPH, Anchor.TOP_LEFT, x, y, 0, 0, 0, 0,
                width, height, fgColor, bgColor, 1.0f, 0, 0, "", "", false, capped);
    }

    public static HudWidget worldMarker(String id, float x, float y, float z, String label, int color) {
        return new HudWidget(id, Type.WORLD_MARKER, Anchor.TOP_LEFT, x, y, z, 0, 0, 0,
                0, 0, color, 0, 1.0f, 0, 0, label, "", false, List.of());
    }

    public static HudWidget worldLine(String id, float x1, float y1, float z1,
                                      float x2, float y2, float z2, int color) {
        return new HudWidget(id, Type.WORLD_LINE, Anchor.TOP_LEFT, x1, y1, z1, x2, y2, z2,
                0, 0, color, 0, 1.0f, 0, 0, "", "", false, List.of());
    }

    public static HudWidget worldBox(String id, float x1, float y1, float z1,
                                     float x2, float y2, float z2, String label, int color) {
        return new HudWidget(id, Type.WORLD_BOX, Anchor.TOP_LEFT, x1, y1, z1, x2, y2, z2,
                0, 0, color, 0, 1.0f, 0, 0, label, "", false, List.of());
    }

    public static HudWidget entityMarker(String id, String entityUuid, String label, int color) {
        return new HudWidget(id, Type.ENTITY_MARKER, Anchor.TOP_LEFT, 0, 0, 0, 0, 0, 0,
                0, 0, color, 0, 1.0f, 0, 0, label, entityUuid, false, List.of());
    }

    public static void writeToBuf(FriendlyByteBuf buf, HudWidget w) {
        buf.writeUtf(w.id, 128);
        buf.writeEnum(w.type);
        buf.writeEnum(w.anchor);
        buf.writeFloat(w.x);
        buf.writeFloat(w.y);
        buf.writeFloat(w.z);
        buf.writeFloat(w.x2);
        buf.writeFloat(w.y2);
        buf.writeFloat(w.z2);
        buf.writeFloat(w.width);
        buf.writeFloat(w.height);
        buf.writeInt(w.color);
        buf.writeInt(w.bgColor);
        buf.writeFloat(w.scale);
        buf.writeFloat(w.value);
        buf.writeFloat(w.max);
        buf.writeUtf(w.text, 512);
        buf.writeUtf(w.targetUuid, 64);
        buf.writeBoolean(w.clickable);
        int count = Math.min(w.points.size(), MAX_GRAPH_POINTS);
        buf.writeVarInt(count);
        for (int i = 0; i < count; i++) {
            buf.writeFloat(w.points.get(i));
        }
    }

    public static HudWidget readFromBuf(FriendlyByteBuf buf) {
        String id = buf.readUtf(128);
        Type type = buf.readEnum(Type.class);
        Anchor anchor = buf.readEnum(Anchor.class);
        float x = buf.readFloat();
        float y = buf.readFloat();
        float z = buf.readFloat();
        float x2 = buf.readFloat();
        float y2 = buf.readFloat();
        float z2 = buf.readFloat();
        float width = buf.readFloat();
        float height = buf.readFloat();
        int color = buf.readInt();
        int bgColor = buf.readInt();
        float scale = buf.readFloat();
        float value = buf.readFloat();
        float max = buf.readFloat();
        String text = buf.readUtf(512);
        String targetUuid = buf.readUtf(64);
        boolean clickable = buf.readBoolean();
        int count = Math.min(buf.readVarInt(), MAX_GRAPH_POINTS);
        List<Float> points = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            points.add(buf.readFloat());
        }
        return new HudWidget(id, type, anchor, x, y, z, x2, y2, z2, width, height,
                color, bgColor, scale, value, max, text, targetUuid, clickable, List.copyOf(points));
    }
}
