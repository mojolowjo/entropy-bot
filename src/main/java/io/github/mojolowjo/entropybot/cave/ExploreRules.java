package io.github.mojolowjo.entropybot.cave;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * "explore [minutes]" and the "new land" walks of "mine" and "mine cave" (B4; the bridge's startExplore, exploredKey,
 * exploreMark, exploreTarget and exploreStep's report), game-free. A chunk counts as explored once the bot was within
 * {@link #SEEN} chunks of it; the target is the nearest chunk centre (rings outward, 24 at most) that isn't explored,
 * lies inside the owner's areas and the guard lets the bot walk to.
 */
public final class ExploreRules {
    private ExploreRules() {}

    public static final int SEEN = 2, RINGS = 24, DEFAULT_MINUTES = 5, MAX_MINUTES = 30;
    /** A walk to a chunk: until Baritone is done or this many ticks (90 s); further than ARRIVED off = gave up on it. */
    public static final long WALK_TICKS = 20 * 90;
    public static final int ARRIVED = 24;

    /** aa385: explore's leash, blocks from the nearest area cell. */
    public static final int LEASH = 64;

    /** An area cell within {@code r} blocks (grid of 8, then the exact distance; areas are boxes far wider than 8). */
    public static boolean nearAreas(java.util.function.BiPredicate<Integer, Integer> inAreas, int x, int z, int r) {
        for (int dx = -r; dx <= r; dx += 8)
            for (int dz = -r; dz <= r; dz += 8)
                if (dx * dx + dz * dz <= r * r && inAreas.test(x + dx, z + dz)) return true;
        return false;
    }

    public static String key(String dim, int cx, int cz) {
        return dim + " " + cx + " " + cz;
    }

    /** "explore 10" -> 10 (1-30, 5 when no number). */
    public static int minutes(String text) {
        String t = text == null ? "" : text.trim().toLowerCase();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^(\\d+)").matcher(t);
        int n = m.find() ? Integer.parseInt(m.group(1)) : 0;
        if (n == 0) n = DEFAULT_MINUTES;
        return Math.min(Math.max(n, 1), MAX_MINUTES);
    }

    /** The chunk keys within SEEN chunks of block x z (the bridge's exploreMark's set). */
    public static List<String> around(String dim, int x, int z) {
        List<String> out = new ArrayList<>();
        int cx = x >> 4, cz = z >> 4;
        for (int dx = -SEEN; dx <= SEEN; dx++) for (int dz = -SEEN; dz <= SEEN; dz++) out.add(key(dim, cx + dx, cz + dz));
        return out;
    }

    /** What the target search needs from the world. */
    public interface Land {
        boolean explored(String key);
        /** Inside the owner's areas (x and z only). */
        boolean inside(int x, int z);
        /** The guard refuses walking to x y z (a portal nearby, outside the fence...). */
        boolean refused(int x, int y, int z);
        /** A chunk the guard refused: never again (memory.explored). */
        void markRefused(String key);
    }

    /** An x z to walk to. */
    public record Target(int x, int z) {}

    /**
     * The nearest unexplored chunk centre inside the areas from block x y z (the bridge's exploreTarget); skip: a job's
     * own chunks to pass over too (never written to the explored notes); null when there is none.
     */
    public static Target target(String dim, int x, int y, int z, Land land, Set<String> skip) {
        int cx = x >> 4, cz = z >> 4;
        for (int r = 1; r <= RINGS; r++) {
            for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) {
                if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                String k = key(dim, cx + dx, cz + dz);
                if (land.explored(k) || (skip != null && skip.contains(k))) continue;
                int tx = ((cx + dx) << 4) + 8, tz = ((cz + dz) << 4) + 8;
                if (!land.inside(tx, tz)) continue;
                if (land.refused(tx, y, tz)) {
                    land.markRefused(k);
                    continue;
                }
                return new Target(tx, tz);
            }
        }
        return null;
    }

    /** Arrived near a chunk target (within ARRIVED blocks, x + z)? */
    public static boolean arrived(int x, int z, Target t) {
        return Math.abs(x - t.x()) + Math.abs(z - t.z()) <= ARRIVED;
    }

    /** 0.33.1: new chunks walked between two gathers; the share of the run gathering may take; no gather this near the end. */
    public static final int GATHER_GAP_CHUNKS = 2, GATHER_SHARE = 3;
    public static final long GATHER_END_TICKS = 600;

    /**
     * 0.33.1: may explore stop for a gather now? Gathering is a side effect of the walk, never the walk itself: only
     * after GATHER_GAP_CHUNKS new chunks since the last gather (or since the start), while gathering has taken at most
     * 1/GATHER_SHARE of the run so far, and not in the run's last 30 s.
     */
    public static boolean gatherAllowed(long elapsed, long remaining, long gatherTicks, int chunks, int chunksAtLastGather) {
        if (remaining < GATHER_END_TICKS) return false;
        if (chunks - chunksAtLastGather < GATHER_GAP_CHUNKS) return false;
        return gatherTicks * GATHER_SHARE <= elapsed;
    }

    /** A point of interest for the report. */
    public record Poi(int id, String kind, int x, int y, int z) {}

    /**
     * The report: "explored 12 chunks in 5 min (the time is up); new: dungeon at x y z, ... (+N more, "poi")" or
     * "; nothing new to note". fresh: the points first seen during the walk, oldest first.
     */
    public static String note(int chunks, long ticks, String why, List<Poi> fresh) {
        return note(chunks, ticks, why, fresh, null, null);
    }

    /**
     * 0.33.0 (Haiku run 2026-10-16: the operator had to work out "about 225 blocks west"): with the start and end spots,
     * "; went 225 blocks west, ended at -268 63 117" right after the time, before the finds (a cut reply keeps it).
     */
    public static String note(int chunks, long ticks, String why, List<Poi> fresh, int[] from, int[] to) {
        StringBuilder sb = new StringBuilder("explored " + chunks + " chunks in " + Math.round(ticks / 1200.0) + " min (" + why + ")");
        if (from != null && to != null) sb.append("; ").append(travel(from, to));
        if (fresh.isEmpty()) return sb.append("; nothing new to note").toString();
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < Math.min(6, fresh.size()); i++) {
            Poi p = fresh.get(i);
            parts.add(p.kind() + " at " + p.x() + " " + p.y() + " " + p.z());
        }
        sb.append("; new: ").append(String.join(", ", parts));
        if (fresh.size() > 6) sb.append(" (+").append(fresh.size() - 6).append(" more, \"poi\")");
        return sb.toString();
    }

    /** "went N blocks <direction>, ended at x y z" (straight-line, flat), or "ended where I started, at x y z". Pure. */
    public static String travel(int[] from, int[] to) {
        int dx = to[0] - from[0], dz = to[2] - from[2];
        long d = Math.round(Math.sqrt((double) dx * dx + (double) dz * dz));
        String at = "ended at " + to[0] + " " + to[1] + " " + to[2];
        if (d < 4) return "ended where I started, at " + to[0] + " " + to[1] + " " + to[2];
        String[] names = {"east", "southeast", "south", "southwest", "west", "northwest", "north", "northeast"};
        double deg = Math.toDegrees(Math.atan2(dz, dx));                 // +z is south in Minecraft
        int i = (int) Math.floorMod(Math.round(deg / 45.0), 8L);
        return "went " + d + " blocks " + names[i] + ", " + at;
    }

    public static final String NO_AREAS = "error: I explore only inside my areas, and there are none - ";
}
