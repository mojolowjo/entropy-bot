package io.github.mojolowjo.entropybot.aa.core;

import io.github.mojolowjo.entropybot.guard.AreaType;
import io.github.mojolowjo.entropybot.guard.Box;
import io.github.mojolowjo.entropybot.guard.Policy;

import java.util.List;

/**
 * AREAS (0.39.2, AREAS_PLAN 5): the labelled places as facts for AA. Nothing here is a permission (the guard holds the
 * hard meanings); these are the soft facts the scorer prices: the meanings at the bot's cell, the nearest base, the
 * owner's near-me circle and the owner's note. Pure Java (JUnit: AreaFactsTest). Loader notes: none. Errors: a null
 * policy = no facts (empty, no base), never a throw.
 */
public final class AreaFacts {
    private AreaFacts() {}

    /** Blackboard keys (strings and ints, read by the operator model and the status line). */
    public static final String HERE = "area.here", NEAREST_BASE = "area.nearestBase", NEAR_OWNER = "area.nearOwner", NOTE = "area.note";

    /**
     * Ending a walk farther from the nearest base than it started costs this share of the way back (seconds per
     * second of walking away): the old WayBack's x2 return leg, priced softly into every option instead of scripted.
     */
    public static final double AWAY_WEIGHT = 0.5;
    /** Blocks per second of a walk (the sequencer's speed). */
    static final double SPEED = 4.3;

    /** What the bot stands in: the meanings at its cell, hardest first ("base,work"), "" with no area. */
    public static String here(Policy p, String dim, int x, int y, int z) {
        if (p == null) return "";
        StringBuilder b = new StringBuilder();
        for (Box bx : p.boxesAt(dim, x, y, z)) {
            if (b.length() > 0) b.append(',');
            b.append(bx.type.word());
        }
        return b.toString();
    }

    /** The owner's notes on the boxes at the cell, joined by "; ", or null. */
    public static String note(Policy p, String dim, int x, int y, int z) {
        if (p == null) return null;
        StringBuilder b = new StringBuilder();
        for (Box bx : p.boxesAt(dim, x, y, z)) {
            if (bx.note == null) continue;
            if (b.length() > 0) b.append("; ");
            b.append(bx.name == null ? "" : bx.name + ": ").append(bx.note);
        }
        return b.length() == 0 ? null : b.toString();
    }

    /** The nearest base area's centre column {x, z} in this dimension, or null (none set). */
    public static int[] nearestBase(Policy p, String dim, int x, int z) {
        return p == null ? null : p.nearestBase(dim, x, z);
    }

    /** Flat blocks from (x, z) to the base column; -1 with no base. */
    public static double baseDist(int[] base, int x, int z) {
        return base == null ? -1 : Math.hypot(base[0] - x, base[1] - z);
    }

    /**
     * The soft cost (seconds) of ending at {@code dest} instead of {@code me}, given the nearest base: the extra way
     * back later, weighted. 0 with no base, when dest is nearer the base, or when either spot is unknown.
     */
    public static double awayS(int[] me, int[] dest, int[] base) {
        if (me == null || dest == null || base == null || me.length < 3 || dest.length < 3) return 0;
        double now = baseDist(base, me[0], me[2]), then = baseDist(base, dest[0], dest[2]);
        return Math.max(0, then - now) / SPEED * AWAY_WEIGHT;
    }

    /** The meanings at the cell contain this one ("base" in "base,work"). */
    public static boolean in(String here, AreaType t) {
        if (here == null || here.isEmpty() || t == null) return false;
        for (String w : here.split(",")) if (w.equals(t.word())) return true;
        return false;
    }

    /**
     * AREAS_PLAN 5 (owner's default 10.4): a candidate base explore/find noted is treated as no-break until the owner
     * rejects it, when it is not plainly the bot's own: a named mob there, or {@code manyEntities} block entities or
     * more. {@code built} alone (a few placed blocks) is not enough: the bot's own paths and torches look like that.
     */
    public static final int MANY_ENTITIES = 2;

    public static boolean autoNoBreak(boolean named, int blockEntities) {
        return named || blockEntities >= MANY_ENTITIES;
    }

    /** The no-break box for a candidate at (x, y, z): R each way, DOWN below, UP above (the accept command's shape). */
    public static Box candidateBox(String name, String dim, int x, int y, int z, int r, int down, int up) {
        return new Box(name, dim, x - r, y - down, z - r, x + r, y + up, z + r).withType(AreaType.NO_BREAK).withNote("candidate: someone's base? (area candidates)");
    }

    /** A short status line: "in base,work; base 12 blocks; near the owner". */
    public static String status(String here, double baseDist, boolean nearOwner) {
        StringBuilder b = new StringBuilder(here == null || here.isEmpty() ? "no area here" : "in " + here);
        b.append(baseDist < 0 ? "; no base area" : "; base " + Math.round(baseDist) + " blocks");
        if (nearOwner) b.append("; near the owner");
        return b.toString();
    }

    /** The list of meanings as words, for a log line. */
    public static String words(List<Box> boxes) {
        StringBuilder b = new StringBuilder();
        for (Box bx : boxes) {
            if (b.length() > 0) b.append(", ");
            b.append(bx.name == null ? "box" : bx.name).append(" (").append(bx.type.word()).append(')');
        }
        return b.toString();
    }
}
