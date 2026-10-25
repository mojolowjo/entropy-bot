package io.github.mojolowjo.entropybot.cave;

import io.github.mojolowjo.entropybot.aa.brain.Tuning;

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

    /** live-facts: the pickaxe tier I carry ({@code have}) meets the block's ({@code need}, MiningTiers; an unknown tier, below 0, is not met). */
    public static boolean toolMeets(int need, int have) { return need >= 0 && need <= have; }

    /**
     * live-facts: a chain's explore for a missing source ends the moment the source is known: the one Nearby fact
     * ({@code source}: AaRuntime.knownSource: a block by Baritone's scan or a sighting, or an animal whose loot drops it) answers for the item. The end text, or null
     * while nothing is known (or no word was given). The chain is then priced again by the need that runs it.
     * explore-until (0.39.z16): {@code word} may be the whole source set of the need the explore was started for, its words
     * joined by "|" ("#coal_ores|#logs_that_burn" for torch's coals): any one of them known ends it.
     */
    public static String untilFound(String word, java.util.function.Function<String, io.github.mojolowjo.entropybot.aa.NeedChain.Spot> source) {
        return untilFound(word, source, (part, s) -> null);
    }

    /**
     * 0.39.z47 (run H: 374 explore restarts in 3 min): as {@link #untilFound(String, java.util.function.Function)}, but a
     * source the order dropped ({@code dropped}: its reason, null when not dropped; z33's Goal.sources) does not end the
     * explore - the chain prices without it, so ending on it would start the same explore again at once.
     */
    public static String untilFound(String word, java.util.function.Function<String, io.github.mojolowjo.entropybot.aa.NeedChain.Spot> source,
                                    java.util.function.BiFunction<String, io.github.mojolowjo.entropybot.aa.NeedChain.Spot, String> dropped) {
        if (word == null || source == null) return null;
        for (String part : word.split("\\|")) {
            if (part.isEmpty()) continue;
            var s = source.apply(part);
            if (s == null) continue;
            if (dropped != null && dropped.apply(part, s) != null) continue;
            return "a source of " + part + " is known: " + (s.block() == null ? "" : s.block().replaceFirst("^minecraft:", "") + " ")
                    + "at " + s.x() + " " + s.y() + " " + s.z() + " (" + Math.round(s.dist()) + " away); " + io.github.mojolowjo.entropybot.aa.brain.FastEnd.FACT_ARRIVED;
        }
        return null;
    }

    /** 0.39.z51: the source a {@link #untilFound} line names, without its distance ("a source of X is known: stone at 1 2 3"); null for null. */
    public static String sourceOf(String known) {
        if (known == null) return null;
        int k = known.indexOf(" (");
        return k < 0 ? known : known.substring(0, k);
    }

    /**
     * 0.39.z51 item 3 (run L 16:30:00-16:30:21: "explore until #stone_tool_materials" started 66 times, each ending at once on
     * "stone at -11 48 -61 is known" while the chain's dig way waited on its path): the explore ends on a known source when
     * that source is new since the explore started; one already known at its start (nothing changed) is a re-plan fact
     * that holds {@code replan.unchangedHoldS} seconds, not an immediate end. Null known: no end.
     */
    public static boolean untilEnds(String known, String knownAtStart, double secondsSinceStart) {
        if (known == null) return false;
        if (knownAtStart == null || !sourceOf(known).equals(sourceOf(knownAtStart))) return true;
        return secondsSinceStart >= Tuning.get("replan.unchangedHoldS");
    }

    public static int seen() { return Tuning.i("exploreRules.seen"); }
    public static int rings() { return Tuning.i("exploreRules.rings"); }
    public static int defaultMinutes() { return Tuning.i("exploreRules.defaultMinutes"); }
    public static int maxMinutes() { return Tuning.i("exploreRules.maxMinutes"); }
    /** A walk to a chunk: until Baritone is done or this many ticks (90 s); further than ARRIVED off = gave up on it. */
    public static long walkTicks() { return Tuning.l("exploreRules.walkTicks"); }
    public static int arrivedV() { return Tuning.i("exploreRules.arrived"); }

    public static String key(String dim, int cx, int cz) {
        return dim + " " + cx + " " + cz;
    }

    /** "explore 10" -> 10 (1-30, 5 when no number). */
    public static int minutes(String text) {
        String t = text == null ? "" : text.trim().toLowerCase();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^(\\d+)").matcher(t);
        int n = m.find() ? Integer.parseInt(m.group(1)) : 0;
        if (n == 0) n = defaultMinutes();
        return Math.min(Math.max(n, 1), maxMinutes());
    }

    /** The chunk keys within SEEN chunks of block x z (the bridge's exploreMark's set). */
    public static List<String> around(String dim, int x, int z) {
        List<String> out = new ArrayList<>();
        int cx = x >> 4, cz = z >> 4;
        for (int dx = -seen(); dx <= seen(); dx++) for (int dz = -seen(); dz <= seen(); dz++) out.add(key(dim, cx + dx, cz + dz));
        return out;
    }

    /** What the target search needs from the world. */
    public interface Land {
        boolean explored(String key);
        /** The policy allows work in the column x z (no keep-out / no-break area there). */
        boolean allowed(int x, int z);
        /** The guard refuses walking to x y z (a portal nearby, a keep-out area...). */
        boolean refused(int x, int y, int z);
        /** A chunk the guard refused: never again (memory.explored). */
        void markRefused(String key);
    }

    /** An x z to walk to. */
    public record Target(int x, int z) {}

    /**
     * The nearest unexplored chunk centre the policy allows from block x y z (the bridge's exploreTarget); skip: a job's
     * own chunks to pass over too (never written to the explored notes); null when there is none.
     */
    public static Target target(String dim, int x, int y, int z, Land land, Set<String> skip) {
        int cx = x >> 4, cz = z >> 4;
        for (int r = 1; r <= rings(); r++) {
            for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) {
                if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                String k = key(dim, cx + dx, cz + dz);
                if (land.explored(k) || (skip != null && skip.contains(k))) continue;
                int tx = ((cx + dx) << 4) + 8, tz = ((cz + dz) << 4) + 8;  // game fact: the centre of a chunk is 8 blocks in (a chunk is 16 wide)
                if (!land.allowed(tx, tz)) continue;
                if (land.refused(tx, y, tz)) {
                    land.markRefused(k);
                    continue;
                }
                return new Target(tx, tz);
            }
        }
        return null;
    }

    /**
     * 0.39.z48 (owner 2026-10-24: "explore TOWARD the possible cave positions", S65): the explore's next aim for an ore need:
     * the first of {@code nearestFirst} (the cave candidates within cave.exploreTowardM, nearest first, the dropped ones
     * already out) that {@code visited} does not name; null: none (the next unexplored chunk, as before).
     */
    public static int[] toward(List<int[]> nearestFirst, Predicate<int[]> visited) {
        if (nearestFirst == null) return null;
        for (int[] c : nearestFirst) if (visited == null || !visited.test(c)) return c;
        return null;
    }

    /**
     * 0.39.z68 (owner 2026-10-25: "if it's ore, explore for caves"): the cave candidates of an explore for a band target
     * (GenWhere BAND) with the ones whose cell lies in the band {@code lo..hi} first, each part nearest first as given (a
     * mouth or pocket in the band opens onto it; the others still lead under ground).
     */
    public static List<int[]> bandFirst(List<int[]> nearestFirst, int lo, int hi) {
        List<int[]> in = new ArrayList<>(), out = new ArrayList<>();
        if (nearestFirst == null) return in;
        for (int[] c : nearestFirst) (c[1] >= lo && c[1] <= hi ? in : out).add(c);
        in.addAll(out);
        return in;
    }

    /**
     * 0.39.z69 item 2 (owner 2026-10-25: "does the data tell where the ore is denser?"): the cave candidates of an explore
     * for a band target ranked by the target's density curve at each cell's Y ({@code density}: GenWhere.Fact#curve with
     * the caves' air-exposure discard). z69 review (b): the walk there counts too - each cave scores its density over
     * (its straight metres from {@code me} + {@code scale}), {@code scale} the explore's own reach (cave.exploreTowardM), so a
     * near cave at 90% of the peak beats a far one at the peak, and a far cave needs a clearly denser Y to win (rough, rule
     * 14). Equal scores keep the given order (nearest first). A cell outside every band (density 0) still leads under
     * ground: last, nearest first.
     */
    public static List<int[]> byCurve(List<int[]> nearestFirst, java.util.function.IntToDoubleFunction density, int[] me, double scale) {
        List<int[]> out = new ArrayList<>();
        if (nearestFirst == null) return out;
        out.addAll(nearestFirst);
        if (density == null) return out;
        double s = Math.max(1, scale);       // structural: at least one metre (no division by zero)
        out.sort(java.util.Comparator.comparingDouble((int[] c) -> {
            double d = me == null ? 0 : Math.sqrt(Math.pow(c[0] - me[0], 2) + Math.pow(c[1] - me[1], 2) + Math.pow(c[2] - me[2], 2));
            return density.applyAsDouble(c[1]) / (d + s);
        }).reversed());                      // a stable sort: ties stay nearest first
        return out;
    }

    /** Arrived near a chunk target (within ARRIVED blocks, x + z)? */
    public static boolean arrived(int x, int z, Target t) {
        return Math.abs(x - t.x()) + Math.abs(z - t.z()) <= arrivedV();
    }

    /** 0.33.1: new chunks walked between two gathers; the share of the run gathering may take; no gather this near the end. */
    public static int gatherGapChunks() { return Tuning.i("exploreRules.gatherGapChunks"); }
    public static int gatherShare() { return Tuning.i("exploreRules.gatherShare"); }
    public static long gatherEndTicks() { return Tuning.l("exploreRules.gatherEndTicks"); }

    /**
     * 0.33.1: may explore stop for a gather now? Gathering is a side effect of the walk, never the walk itself: only
     * after GATHER_GAP_CHUNKS new chunks since the last gather (or since the start), while gathering has taken at most
     * 1/GATHER_SHARE of the run so far, and not in the run's last 30 s.
     */
    public static boolean gatherAllowed(long elapsed, long remaining, long gatherTicks, int chunks, int chunksAtLastGather) {
        if (remaining < gatherEndTicks()) return false;
        if (chunks - chunksAtLastGather < gatherGapChunks()) return false;
        return gatherTicks * gatherShare() <= elapsed;
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
        StringBuilder sb = new StringBuilder("explored " + chunks + " chunks in " + Math.round(ticks / 1200.0) + " min (" + why + ")");  // game fact: 1200 ticks in a minute
        if (from != null && to != null) sb.append("; ").append(travel(from, to));
        if (fresh.isEmpty()) return sb.append("; nothing new to note").toString();
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < Math.min(6, fresh.size()); i++) {
            Poi p = fresh.get(i);
            parts.add(p.kind() + " at " + p.x() + " " + p.y() + " " + p.z());
        }
        sb.append("; new: ").append(String.join(", ", parts));
        if (fresh.size() > 6) sb.append(" (+").append(fresh.size() - 6).append(" more, \"poi\")");  // structural: display: how many items a text lists
        return sb.toString();
    }

    /** "went N blocks <direction>, ended at x y z" (straight-line, flat), or "ended where I started, at x y z". Pure. */
    public static String travel(int[] from, int[] to) {
        int dx = to[0] - from[0], dz = to[2] - from[2];
        long d = Math.round(Math.sqrt((double) dx * dx + (double) dz * dz));
        String at = "ended at " + to[0] + " " + to[1] + " " + to[2];
        if (d < Tuning.i("exploreRules.endedNear")) return "ended where I started, at " + to[0] + " " + to[1] + " " + to[2];
        String[] names = {"east", "southeast", "south", "southwest", "west", "northwest", "north", "northeast"};
        double deg = Math.toDegrees(Math.atan2(dz, dx));                 // +z is south in Minecraft
        int i = (int) Math.floorMod(Math.round(deg / 45.0), 8L);  // structural: compass: 8 points of 45 degrees
        return "went " + d + " blocks " + names[i] + ", " + at;
    }
}
