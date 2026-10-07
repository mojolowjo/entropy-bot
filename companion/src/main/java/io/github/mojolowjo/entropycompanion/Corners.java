package io.github.mojolowjo.entropycompanion;

/**
 * C3: the two corners the owner marks in the game (the crosshair block within 64, else the feet) and the bot
 * commands made from them: the bot's {@code area x z x2 z2 <name> [type] [y1 y2]} (0.22.0; one verb for every
 * type, the old protect form is gone). Plain Java; thread-safe.
 */
public final class Corners {
    public static final String NAME_RE = "[a-z0-9_-]{1,24}";      // the bot's PolicyCommands.NAME_RE
    /** A protect box made from two ground corners reaches this far below and above them (like "protect ... here"). */
    public static final int PROTECT_DOWN = 8, PROTECT_UP = 16;

    public record Pos(int x, int y, int z, String dim) {
        public String text() { return x + " " + y + " " + z; }
    }

    private Pos c1, c2;

    public synchronized void set(int which, Pos p) {
        if (which == 1) c1 = p;
        else c2 = p;
    }

    public synchronized Pos get(int which) { return which == 1 ? c1 : c2; }

    public synchronized void clear() { c1 = c2 = null; }

    /** Null when both corners are set in one dimension, else what is missing. */
    public synchronized String missing() {
        if (c1 == null) return "set corner 1 first (the corner 1 key, or /bot corner1)";
        if (c2 == null) return "set corner 2 first (the corner 2 key, or /bot corner2)";
        if (!c1.dim().equals(c2.dim())) return "the corners are in different dimensions - set them again";
        return null;
    }

    /**
     * 0.2.1 (bot 0.22.0): "area x z x2 z2 <name> [type] [y1 y2]" from the corners. type: neutral (all heights; with
     * withY the corners' heights), destroy (the corners' heights: "dig <area>" needs them), main (all heights), safe
     * ({@link #PROTECT_DOWN} below to {@link #PROTECT_UP} above the corners). An error text starts "error:".
     */
    public synchronized String areaCommand(String name, String type, boolean withY) {
        String m = check(name);
        if (m != null) return m;
        String t = type == null || type.isEmpty() ? "neutral" : type;
        if (!t.matches("neutral|destroy|main|safe")) return "error: type must be neutral, destroy, main or safe";
        String s = "area " + Math.min(c1.x, c2.x) + " " + Math.min(c1.z, c2.z) + " " + Math.max(c1.x, c2.x) + " " + Math.max(c1.z, c2.z) + " " + name;
        if (!t.equals("neutral")) s += " " + t;
        if (t.equals("safe")) return s + " " + (Math.min(c1.y, c2.y) - PROTECT_DOWN) + " " + (Math.max(c1.y, c2.y) + PROTECT_UP);
        if (withY || t.equals("destroy")) return s + " " + Math.min(c1.y, c2.y) + " " + Math.max(c1.y, c2.y);
        return s;
    }

    /** The neutral form (all heights, or the corners' heights). */
    public synchronized String areaCommand(String name, boolean withY) {
        return areaCommand(name, "neutral", withY);
    }
    private String check(String name) {
        String m = missing();
        if (m != null) return "error: " + m;
        if (name == null || !name.matches(NAME_RE)) return "error: a name is lower-case letters, digits, _ and - (24 at most)";
        return null;
    }
}
