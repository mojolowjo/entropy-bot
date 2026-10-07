package io.github.mojolowjo.entropycompanion;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.List;

/**
 * C3's box view data: the bot's areas, coloured by type (0.2.1: neutral white, destroy red, main blue, safe green), from the dashboard's {@code /api/map/boxes}
 * ({areas:[{name,dim,x1,z1,x2,z2,y1?,y2?,type?}], protect:[... the safe ones]}), and which of them to draw near the owner. Plain Java.
 */
public final class BoxSet {
    public static final int RANGE = 128;
    public static final int WORLD_MIN_Y = -64, WORLD_MAX_Y = 320;

    public enum Kind { AREA, PROTECT, CORNER }

    /** Inclusive block box; an area without heights spans the whole world height. */
    public record Box(Kind kind, String name, String dim, int x1, int y1, int z1, int x2, int y2, int z2, String type) {
        public Box(Kind kind, String name, String dim, int x1, int y1, int z1, int x2, int y2, int z2) {
            this(kind, name, dim, x1, y1, z1, x2, y2, z2, kind == Kind.PROTECT ? "safe" : "neutral");
        }
    }

    /** 0.2.1: the line colour {r, g, b} of an area type (the bot's AreaType): neutral white, destroy red, main blue, safe green. */
    public static float[] colour(String type) {
        return switch (type == null ? "neutral" : type) {
            case "destroy" -> new float[]{1f, 0.2f, 0.2f};
            case "main" -> new float[]{0.2f, 0.45f, 1f};
            case "safe" -> new float[]{0.2f, 1f, 0.25f};
            default -> new float[]{1f, 1f, 1f};
        };
    }

    /** The boxes in a /api/map/boxes answer; bad entries are skipped. Throws on a body that is not JSON. */
    public static List<Box> parse(String body) {
        List<Box> out = new ArrayList<>();
        JsonObject o = JsonParser.parseString(body).getAsJsonObject();
        add(out, o.get("areas"), Kind.AREA);
        add(out, o.get("protect"), Kind.PROTECT);
        return out;
    }

    private static void add(List<Box> out, JsonElement arr, Kind kind) {
        if (arr == null || !arr.isJsonArray()) return;
        for (JsonElement e : (JsonArray) arr) {
            try {
                JsonObject b = e.getAsJsonObject();
                int x1 = b.get("x1").getAsInt(), z1 = b.get("z1").getAsInt(), x2 = b.get("x2").getAsInt(), z2 = b.get("z2").getAsInt();
                int y1 = b.has("y1") && !b.get("y1").isJsonNull() ? b.get("y1").getAsInt() : WORLD_MIN_Y;
                int y2 = b.has("y2") && !b.get("y2").isJsonNull() ? b.get("y2").getAsInt() : WORLD_MAX_Y;
                String dim = b.has("dim") ? b.get("dim").getAsString() : "minecraft:overworld";
                String name = b.has("name") ? b.get("name").getAsString() : "?";
                String type = kind == Kind.PROTECT ? "safe" : b.has("type") && b.get("type").isJsonPrimitive() ? b.get("type").getAsString() : "neutral";
                out.add(new Box(type.equals("safe") ? Kind.PROTECT : kind, name, dim, Math.min(x1, x2), Math.min(y1, y2), Math.min(z1, z2),
                        Math.max(x1, x2), Math.max(y1, y2), Math.max(z1, z2), type));
            } catch (RuntimeException ignored) {
                // one broken entry never hides the rest
            }
        }
    }

    /** The boxes in this dimension whose nearest horizontal point is within range of (px, pz). */
    public static List<Box> near(List<Box> boxes, String dim, double px, double pz, int range) {
        List<Box> out = new ArrayList<>();
        for (Box b : boxes) {
            if (!b.dim().equals(dim)) continue;
            double dx = px < b.x1() ? b.x1() - px : px > b.x2() + 1 ? px - b.x2() - 1 : 0;
            double dz = pz < b.z1() ? b.z1() - pz : pz > b.z2() + 1 ? pz - b.z2() - 1 : 0;
            if (dx * dx + dz * dz <= (double) range * range) out.add(b);
        }
        return out;
    }
}
