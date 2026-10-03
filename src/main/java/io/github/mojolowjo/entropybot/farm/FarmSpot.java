package io.github.mojolowjo.entropybot.farm;

import com.google.gson.JsonObject;

/**
 * The farm (the bridge's memory.places.farm): where its crops are, its dimension, and what a round compacts the
 * inferium essence into: "block" (9 -> 1 inferium block), "prudentium" (4 essence + an infusion crystal) or "off".
 */
public record FarmSpot(int x, int y, int z, String dim, String compact) {

    public int[] pos() { return new int[]{x, y, z}; }

    public String fmt() { return x + " " + y + " " + z; }

    /** The compact mode, "block" when none is set (the bridge's f.compact || 'block'). */
    public String mode() { return compact == null || compact.isEmpty() ? "block" : compact; }

    public FarmSpot withCompact(String c) { return new FarmSpot(x, y, z, dim, c); }

    /** From the places entry ({x, y, z, dim, compact}); null for null or an entry without coordinates. */
    public static FarmSpot fromJson(JsonObject o) {
        if (o == null || !o.has("x") || !o.has("y") || !o.has("z")) return null;
        return new FarmSpot(o.get("x").getAsInt(), o.get("y").getAsInt(), o.get("z").getAsInt(),
                o.has("dim") && !o.get("dim").isJsonNull() ? o.get("dim").getAsString() : null,
                o.has("compact") && !o.get("compact").isJsonNull() ? o.get("compact").getAsString() : null);
    }

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("x", x);
        o.addProperty("y", y);
        o.addProperty("z", z);
        if (dim != null) o.addProperty("dim", dim);
        if (compact != null) o.addProperty("compact", compact);
        return o;
    }
}
