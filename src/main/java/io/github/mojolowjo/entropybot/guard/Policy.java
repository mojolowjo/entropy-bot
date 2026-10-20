package io.github.mojolowjo.entropybot.guard;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.List;

/**
 * The owner's policy: labelled places (AREAS_PLAN 1). {@code areas} holds the soft meanings and destroy (base, work,
 * storage, danger, destroy); {@code protect} holds the boxes where the bot never breaks or places (no-break, and
 * keep-out, which it may not even walk into: {@link #keepOut}). Immutable; the guard swaps whole snapshots so the
 * pathfinder thread can read one without locks. The floor (block entities, built blocks, the Nether and End) is not in
 * here and cannot be edited. No area at all = an open world.
 */
public final class Policy {
    public static final String DEFAULT_DIM = "minecraft:overworld";
    public static final Policy EMPTY = new Policy(List.of(), List.of());

    public final List<Box> areas;
    public final List<Box> protect;
    /** AREAS: the keep-out boxes (a subset of {@link #protect}): no walk goal inside, Baritone paths around them. */
    public final List<Box> keepOut;
    /** AREAS: the danger boxes (a subset of {@link #areas}): a cost per move for Baritone, a fact for AA. */
    public final List<Box> danger;

    public Policy(List<Box> areas, List<Box> protect) {
        this.areas = List.copyOf(areas);
        List<Box> pr = new ArrayList<>(), ko = new ArrayList<>(), dg = new ArrayList<>();
        for (Box b : protect) pr.add(AreaTypeRules.protects(b.type) ? b : b.withType(AreaType.NO_BREAK));   // a plain protect box = no-break
        this.protect = List.copyOf(pr);
        for (Box b : this.protect) if (b.type == AreaType.KEEP_OUT) ko.add(b);
        for (Box b : this.areas) if (b.type == AreaType.DANGER) dg.add(b);
        this.keepOut = List.copyOf(ko);
        this.danger = List.copyOf(dg);
    }

    /** AREAS: the danger box holding the spot, or null. Lock-free (the pathfinder thread reads it). */
    public Box dangerAt(String dim, int x, int y, int z) {
        for (Box b : danger) if (b.contains(dim, x, y, z)) return b;
        return null;
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
        // V1a: a no-break (old: safe) or keep-out area is a protect box (one list for the owner, two here for the lock-free checks)
        if (o.has("areas")) for (JsonElement e : o.getAsJsonArray("areas")) {
            Box b = Box.fromJson(e.getAsJsonObject(), DEFAULT_DIM);
            if (AreaTypeRules.protects(b.type)) protect.add(b);
            else areas.add(b);
        }
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

    /** This policy plus one more area; this one itself when {@code extra} is null. */
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

    /** 0.33.0: the smallest area holding the spot (a house inside the camp names the house), or null. For HERE. */
    public Box innermostAreaAt(String dim, int x, int y, int z) {
        Box best = null;
        for (Box b : areas) {
            if (b.contains(dim, x, y, z) && (best == null || b.volume() < best.volume())) best = b;
        }
        return best;
    }

    /** AREAS: every box (areas and protect) holding the spot, hardest meaning first; empty when none. */
    public List<Box> boxesAt(String dim, int x, int y, int z) {
        List<Box> out = new ArrayList<>();
        for (Box b : protect) if (b.contains(dim, x, y, z)) out.add(b);
        for (Box b : areas) if (b.contains(dim, x, y, z)) out.add(b);
        out.sort((p, q) -> Integer.compare(p.type.rank(), q.type.rank()));
        return out;
    }

    /**
     * 0.30.6 (live, 0.30.4: the stuck dig-out opened a house's cobblestone wall): the cell is part of a house in a base
     * area: on the area box's shell, or (ownBuilt) any cell in the area's columns the bot built itself (floor, pillars
     * under it, stations). Such a cell is never a dig-out's way; the house's door is the way out.
     */
    public boolean houseCell(String dim, int x, int y, int z, boolean ownBuilt) {
        for (Box b : areas) {
            if (b.type != AreaType.BASE || !b.dim.equals(dim) || !b.columnIn(x, z)) continue;
            if (ownBuilt || b.onShell(dim, x, y, z)) return true;
        }
        return false;
    }

    /**
     * 0.30.7 (owner's rule 2026-10-11; live: twice stuck in the respawn pit at 0 136 9 inside the neutral area "arena",
     * the surface dig-out refused): a dig-out (the stuck escape, the surface dig-out, the stair side) may break any
     * block in this cell, placed ones and persistent leaves included: it lies in a work, storage, danger or destroy
     * area, in no protect box, and is not a base area's house cell (houseCell). The caller still refuses containers,
     * blocks by water or lava, unbreakable and unloaded cells.
     */
    public boolean digOutAny(String dim, int x, int y, int z, boolean ownBuilt) {
        if (protectAt(dim, x, y, z) != null || houseCell(dim, x, y, z, ownBuilt)) return false;
        for (Box b : areas) {
            if (b.type != AreaType.BASE && b.contains(dim, x, y, z)) return true;
        }
        return false;
    }

    public Box protectAt(String dim, int x, int y, int z) {
        for (Box b : protect) if (b.contains(dim, x, y, z)) return b;
        return null;
    }

    /** AREAS: the keep-out box holding the spot, or null. Lock-free (the pathfinder thread reads it). */
    public Box keepOutAt(String dim, int x, int y, int z) {
        for (Box b : keepOut) if (b.contains(dim, x, y, z)) return b;
        return null;
    }

    /** AREAS: the protect box (keep-out or no-break) whose footprint holds the column (heights aside), keep-out first; or null. */
    public Box protectColumn(String dim, int x, int z) {
        Box best = null;
        for (Box b : protect) if (b.dim.equals(dim) && b.columnIn(x, z) && (best == null || b.type.rank() < best.type.rank())) best = b;
        return best;
    }

    /** V1a: the meaning at a spot: the hardest of every box holding it (AREAS_PLAN 2), or null (no area). */
    public AreaType typeAt(String dim, int x, int y, int z) {
        AreaType t = null;
        for (Box b : protect) if (b.contains(dim, x, y, z)) t = AreaType.harder(t, b.type);
        for (Box b : areas) if (b.contains(dim, x, y, z)) t = AreaType.harder(t, b.type);
        return t;
    }

    /** V1a: the destroy area (by name, any case) the box lies wholly inside, or null. */
    public Box destroyCovering(Box box, String areaName) {
        for (Box a : areas) {
            if (a.type != AreaType.DESTROY || a.name == null || !a.name.equalsIgnoreCase(areaName)) continue;
            if (box.inside(a)) return a;
        }
        return null;
    }

    /**
     * Returns the first protect box (no-break or keep-out) in list order whose box overlaps the given box, or
     * {@code null} if none overlap. Two boxes overlap when they share at least one cell, including the Y range (see
     * {@link Box#overlaps}). The order of the {@code protect} list decides which one is reported when several overlap.
     *
     * <p>Called by the dig verb to refuse a dig box up front when the result is not {@code null}, and by
     * {@link GuardCore#lease} to refuse a break lease that touches one.
     */
    public Box safeTouching(Box box) {
        for (Box b : protect) if (b.overlaps(box)) return b;
        return null;
    }

    /** True when the box lies entirely inside one area (a soft or destroy one). */
    public boolean areaCovers(Box box) {
        for (Box a : areas) if (box.inside(a)) return true;
        return false;
    }

    /** AREAS: the nearest base area's centre column in this dimension ({x, z}), or null when none is set. */
    public int[] nearestBase(String dim, int x, int z) {
        int[] best = null;
        double bd = Double.MAX_VALUE;
        for (Box b : areas) {
            if (b.type != AreaType.BASE || !b.dim.equals(dim)) continue;
            int cx = b.round ? b.cx : (b.x1 + b.x2) / 2, cz = b.round ? b.cz : (b.z1 + b.z2) / 2;
            double d = Math.hypot(cx - x, cz - z);
            if (d < bd) { bd = d; best = new int[]{cx, cz}; }
        }
        return best;
    }
}
