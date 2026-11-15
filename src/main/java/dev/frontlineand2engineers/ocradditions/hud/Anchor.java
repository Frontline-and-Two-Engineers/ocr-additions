package dev.frontlineand2engineers.ocradditions.hud;

/**
 * Screen anchor points for 2D Smart Glasses HUD widgets.
 */
public enum Anchor {
    TOP_LEFT(0.0f, 0.0f),
    TOP_CENTER(0.5f, 0.0f),
    TOP_RIGHT(1.0f, 0.0f),
    CENTER_LEFT(0.0f, 0.5f),
    CENTER(0.5f, 0.5f),
    CENTER_RIGHT(1.0f, 0.5f),
    BOTTOM_LEFT(0.0f, 1.0f),
    BOTTOM_CENTER(0.5f, 1.0f),
    BOTTOM_RIGHT(1.0f, 1.0f);

    public final float factorX;
    public final float factorY;

    Anchor(float factorX, float factorY) {
        this.factorX = factorX;
        this.factorY = factorY;
    }

    public static Anchor fromName(String name) {
        if (name == null || name.isBlank()) {
            return TOP_LEFT;
        }
        String normalized = name.trim().toUpperCase().replace('-', '_').replace(' ', '_');
        for (Anchor value : values()) {
            if (value.name().equals(normalized)) {
                return value;
            }
        }
        throw new IllegalArgumentException("Unknown anchor: " + name);
    }
}
