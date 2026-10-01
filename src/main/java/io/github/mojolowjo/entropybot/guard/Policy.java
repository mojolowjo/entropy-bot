package io.github.mojolowjo.entropybot.guard;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.List;

/**
 * The owner's policy: areas (where the bot may go, break and place) and protect boxes (where it may
 * never break or place). Immutable; the guard swaps whole snapshots so the pathfinder thread can read
 * one without locks. The floor (block entities, built blocks, the Nether and End) is not in here and
 * cannot be edited.
 */
public final class Policy {
    public static final String DEFAULT_DIM = "minecraft:overworld";
    public static final Policy EMPTY = new Policy(List.of(), List.of());

    public final List<Box> areas;
    public final List<Box> protect;

    public Policy(List<Box> areas, List<Box> protect) {
        this.areas = List.copyOf(areas);
        this.protect = List.copyOf(protect);
    }

    public static Policy parse(String json) {
        JsonObject o;
        try {
            JsonElement e = JsonParser.parseString(json);
            if (!e.isJsonObject()) throw new IllegalArgumentException("policy must be a JSON object");
            o = e.getAsJsonObject();
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("bad policy JSON: " + e.getMessage());
        }
        List<Box> areas = new ArrayList<>(), protect = new ArrayList<>();
        if (o.has("areas")) for (JsonElement e : o.getAsJsonArray("areas")) areas.add(Box.fromJson(e.getAsJsonObject(), DEFAULT_DIM));
        if (o.has("protect")) for (JsonElement e : o.getAsJsonArray("protect")) protect.add(Box.fromJson(e.getAsJsonObject(), DEFAULT_DIM));
        for (Box a : areas) if (GuardCore.DENIED_DIMS.contains(a.dim)) throw new IllegalArgumentException("no areas in " + a.dim);
        return new Policy(areas, protect);
    }

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        JsonArray a = new JsonArray(), p = new JsonArray();
        for (Box b : areas) a.add(b.toJson());
        for (Box b : protect) p.add(b.toJson());
        o.add("areas", a);
        o.add("protect", p);
        return o;
    }

    public Box areaAt(String dim, int x, int y, int z) {
        for (Box b : areas) if (b.contains(dim, x, y, z)) return b;
        return null;
    }

    public Box protectAt(String dim, int x, int y, int z) {
        for (Box b : protect) if (b.contains(dim, x, y, z)) return b;
        return null;
    }

    /** True when the box lies entirely inside one area (a lease must). */
    public boolean areaCovers(Box box) {
        for (Box a : areas) if (box.inside(a)) return true;
        return false;
    }
}
