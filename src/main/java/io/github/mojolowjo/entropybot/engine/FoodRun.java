package io.github.mojolowjo.entropybot.engine;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Where the food run looks (B3a, docs/BOT_PLAN.md 5.7, the owner's answer of 2026-10-01): the chest the
 * owner marked "food" first, then trusted chests whose notes hold food, nearest first. Plain Java.
 */
public final class FoodRun {
    private FoodRun() {}

    /** At most this many chests per run. */
    public static final int MAX_CHESTS = 2;
    /** Never further than this from the bot (a /home first covers the base from anywhere near home). */
    public static final double MAX_DIST = 256;
    /** Food items it takes along per run. */
    public static final int TAKE = 16;

    public record Target(String key, int x, int y, int z, String why) {}

    public static List<Target> targets(Map<String, JsonObject> places, Map<String, JsonObject> chests, Predicate<String> isFood,
                                       String dim, double bx, double by, double bz) {
        List<Target> out = new ArrayList<>();
        JsonObject food = places.get("food");
        String foodKey = null;
        if (food != null && dimOf(food).equals(dim)) {
            try {
                int x = food.get("x").getAsInt(), y = food.get("y").getAsInt(), z = food.get("z").getAsInt();
                foodKey = x + " " + y + " " + z;
                out.add(new Target(foodKey, x, y, z, "the food chest"));
            } catch (RuntimeException ignored) {}
        }
        List<Target> more = new ArrayList<>();
        for (Map.Entry<String, JsonObject> e : chests.entrySet()) {
            JsonObject c = e.getValue();
            if (e.getKey().equals(foodKey) || !dimOf(c).equals(dim)) continue;
            if (c.has("trusted") && !c.get("trusted").getAsBoolean()) continue;
            if (!hasFood(c, isFood)) continue;
            String[] p = e.getKey().split(" ");
            if (p.length != 3) continue;
            try {
                int x = Integer.parseInt(p[0]), y = Integer.parseInt(p[1]), z = Integer.parseInt(p[2]);
                if (dist(bx, by, bz, x, y, z) > MAX_DIST) continue;
                more.add(new Target(e.getKey(), x, y, z, "a chest with food"));
            } catch (NumberFormatException ignored) {}
        }
        more.sort(Comparator.comparingDouble(t -> dist(bx, by, bz, t.x(), t.y(), t.z())));
        for (Target t : more) {
            if (out.size() >= MAX_CHESTS) break;
            out.add(t);
        }
        return out;
    }

    static boolean hasFood(JsonObject chest, Predicate<String> isFood) {
        if (!chest.has("items") || !chest.get("items").isJsonObject()) return false;
        for (Map.Entry<String, JsonElement> it : chest.getAsJsonObject("items").entrySet()) {
            try {
                if (it.getValue().getAsInt() > 0 && isFood.test(it.getKey())) return true;
            } catch (RuntimeException ignored) {}
        }
        return false;
    }

    static String dimOf(JsonObject o) {
        return o.has("dim") ? o.get("dim").getAsString() : "minecraft:overworld";
    }

    static double dist(double bx, double by, double bz, int x, int y, int z) {
        double dx = bx - x - 0.5, dy = by - y, dz = bz - z - 0.5;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}
