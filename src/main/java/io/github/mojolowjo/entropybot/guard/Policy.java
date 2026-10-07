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
        // V1a: a safe area is a protect box (one list for the owner, two here for the lock-free checks)
        if (o.has("areas")) for (JsonElement e : o.getAsJsonArray("areas")) {
            Box b = Box.fromJson(e.getAsJsonObject(), DEFAULT_DIM);
            if (b.type == AreaType.SAFE) protect.add(b);
            else areas.add(b);
        }
        if (o.has("protect")) for (JsonElement e : o.getAsJsonArray("protect")) protect.add(Box.fromJson(e.getAsJsonObject(), DEFAULT_DIM).withType(AreaType.SAFE));
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

    /** 0.21.2: this policy plus one more area (the near-me zone); this one itself when {@code extra} is null. */
    public Policy withArea(Box extra) {
        if (extra == null) return this;
        List<Box> a = new ArrayList<>(areas);
        a.add(extra);
        return new Policy(a, protect);
    }

    public Box areaAt(String dim, int x, int y, int z) {
        for (Box b : areas) if (b.contains(dim, x, y, z)) return b;
        return null;
    }

    public Box protectAt(String dim, int x, int y, int z) {
        for (Box b : protect) if (b.contains(dim, x, y, z)) return b;
        return null;
    }

    /** V1a: the type at a spot: safe in a protect box, else the first area's type, else null (outside every area). */
    public AreaType typeAt(String dim, int x, int y, int z) {
        if (protectAt(dim, x, y, z) != null) return AreaType.SAFE;
        Box a = areaAt(dim, x, y, z);
        return a == null ? null : a.type;
    }

    /** V1a: the destroy area (by name, any case) the box lies wholly inside, or null. */
    public Box destroyCovering(Box box, String areaName) {
        for (Box a : areas) {
            if (a.type != AreaType.DESTROY || a.name == null || !a.name.equalsIgnoreCase(areaName)) continue;
            if (box.inside(a)) return a;
        }
        return null;
    }

    /** True when the box lies entirely inside one area (a lease must). */
    public boolean areaCovers(Box box) {
        for (Box a : areas) if (box.inside(a)) return true;
        return false;
    }
}
