package io.github.mojolowjo.entropybot.guard;

import com.google.gson.JsonObject;

/**
 * An axis-aligned box in one dimension, inclusive on all sides. Y defaults to "all of it" when a
 * policy entry gives none, so an area set with four numbers in game covers every height.
 */
public final class Box {
    /** Minecraft's absolute build limits; wider than any world's. */
    public static final int ALL_Y_MIN = -2048, ALL_Y_MAX = 2047;

    public final String name;
    public final String dim;
    public final int x1, y1, z1, x2, y2, z2;

    public Box(String name, String dim, int x1, int y1, int z1, int x2, int y2, int z2) {
        this.name = name;
        this.dim = dim;
        this.x1 = Math.min(x1, x2); this.x2 = Math.max(x1, x2);
        this.y1 = Math.min(y1, y2); this.y2 = Math.max(y1, y2);
        this.z1 = Math.min(z1, z2); this.z2 = Math.max(z1, z2);
    }

    public boolean contains(String d, int x, int y, int z) {
        return dim.equals(d) && x >= x1 && x <= x2 && y >= y1 && y <= y2 && z >= z1 && z <= z2;
    }

    /** True when this box lies entirely inside {@code o}. */
    public boolean inside(Box o) {
        return dim.equals(o.dim) && x1 >= o.x1 && x2 <= o.x2 && y1 >= o.y1 && y2 <= o.y2 && z1 >= o.z1 && z2 <= o.z2;
    }

    public boolean overlaps(Box o) {
        return dim.equals(o.dim) && x1 <= o.x2 && x2 >= o.x1 && y1 <= o.y2 && y2 >= o.y1 && z1 <= o.z2 && z2 >= o.z1;
    }

    public long volume() {
        return (long) (x2 - x1 + 1) * (y2 - y1 + 1) * (z2 - z1 + 1);
    }

    public boolean allY() {
        return y1 <= ALL_Y_MIN && y2 >= ALL_Y_MAX;
    }

    /** Parses {name?, dim?, x1, z1, x2, z2, y1?, y2?}; a missing y means all heights. */
    public static Box fromJson(JsonObject o, String defaultDim) {
        for (String k : new String[]{"x1", "z1", "x2", "z2"}) {
            if (!o.has(k)) throw new IllegalArgumentException("box needs " + k);
        }
        String dim = o.has("dim") ? o.get("dim").getAsString() : defaultDim;
        int y1 = o.has("y1") ? o.get("y1").getAsInt() : ALL_Y_MIN;
        int y2 = o.has("y2") ? o.get("y2").getAsInt() : ALL_Y_MAX;
        String name = o.has("name") && !o.get("name").isJsonNull() ? o.get("name").getAsString() : null;
        return new Box(name, dim, o.get("x1").getAsInt(), y1, o.get("z1").getAsInt(), o.get("x2").getAsInt(), y2, o.get("z2").getAsInt());
    }

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        if (name != null) o.addProperty("name", name);
        o.addProperty("dim", dim);
        o.addProperty("x1", x1); o.addProperty("z1", z1); o.addProperty("x2", x2); o.addProperty("z2", z2);
        if (!allY()) { o.addProperty("y1", y1); o.addProperty("y2", y2); }
        return o;
    }

    public String describe() {
        String base = (name == null ? "box" : name) + " " + x1 + " " + z1 + " to " + x2 + " " + z2;
        return allY() ? base : base + " (y " + y1 + ".." + y2 + ")";
    }

    @Override
    public String toString() {
        return describe() + " in " + dim;
    }
}
