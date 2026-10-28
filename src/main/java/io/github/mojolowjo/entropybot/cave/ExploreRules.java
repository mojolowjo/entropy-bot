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
        return untilFound(word, source, dropped, null);
    }

    /**
     * 0.39.z77 cave (run BW 14:24:10: the cave at 20 21 -10 was confirmed during "explore 5 until raw_iron", the explore ran on
     * 25 s and failed): as {@link #untilFound(String, java.util.function.Function, java.util.function.BiFunction)}, and a part
     * with no source known (or only a dropped one) ends the explore on a confirmed cave the planner offers for it ({@code cave}:
     * NeedChain.caveSource on the order's world, so a cave the order dropped is none there; null: no cave facts). The order is
     * then priced again on the new facts, as for a seen source.
     */
    public static String untilFound(String word, java.util.function.Function<String, io.github.mojolowjo.entropybot.aa.NeedChain.Spot> source,
                                    java.util.function.BiFunction<String, io.github.mojolowjo.entropybot.aa.NeedChain.Spot, String> dropped,
                                    java.util.function.Function<String, io.github.mojolowjo.entropybot.aa.NeedChain.Spot> cave) {
        if (word == null || source == null) return null;
        for (String part : word.split("\\|")) {
            if (part.isEmpty()) continue;
            var s = source.apply(part);
            if (s != null && (dropped == null || dropped.apply(part, s) == null))
                return "a source of " + part + " is known: " + (s.block() == null ? "" : s.block().replaceFirst("^minecraft:", "") + " ")
                        + "at " + s.x() + " " + s.y() + " " + s.z() + " (" + Math.round(s.dist()) + " away); " + io.github.mojolowjo.entropybot.aa.brain.FastEnd.FACT_ARRIVED;
            var k = cave == null ? null : cave.apply(part);
            if (k != null)
                return "a source of " + part + " is known: a cave" + (k.block() == null ? "" : " with " + k.block().replaceFirst("^minecraft:", ""))
                        + " at " + k.x() + " " + k.y() + " " + k.z() + " (" + Math.round(k.dist()) + " away); " + io.github.mojolowjo.entropybot.aa.brain.FastEnd.FACT_ARRIVED;
        }
        return null;
    }

    /**
     * 0.39.z77 seen (run CC 16:42:08: "explore until iron_ingot" asked knownSource("iron_ingot"), "no block gives it", so the
     * iron ore it passed never ended it): the until word read through the furnace (pure) - each part, then what smelts into
     * it ({@code smeltInputs}: the recipe data's furnace inputs of a part, one level), joined by "|": "minecraft:iron_ingot" ->
     * "minecraft:iron_ingot|minecraft:raw_iron|...". The source of an input (raw_iron: iron ore, by the loot data) ends the
     * explore as the part's own would. Null word: null; no inputs: the word as it is.
     */
    public static String throughSmelts(String word, java.util.function.Function<String, List<String>> smeltInputs) {
        if (word == null || smeltInputs == null) return word;
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        for (String part : word.split("\\|")) if (!part.isEmpty()) out.add(part);
        for (String part : new ArrayList<>(out)) {
            List<String> in = smeltInputs.apply(part);
            if (in != null) for (String i : in) if (i != null && !i.isEmpty()) out.add(i);
        }
        return String.join("|", out);
    }

    /** 0.39.z77 seen: the furnace inputs of one item (every smelt recipe's ingredients, the recipe data's order); a tag or list word: none. */
    public static List<String> smeltInputs(String word) {
        List<String> out = new ArrayList<>();
        if (word == null || !io.github.mojolowjo.entropybot.aa.NeedChain.plain(word)) return out;
        for (var r : io.github.mojolowjo.entropybot.aa.brain.ItemWorth.recipes(word, io.github.mojolowjo.entropybot.aa.brain.ItemWorth.SMELT)) {
            for (String i : r.inputs()) if (!out.contains(i)) out.add(i);
            if (r.options() != null) for (List<String> o : r.options()) if (o != null) for (String i : o) if (!out.contains(i)) out.add(i);
        }
        return out;
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
    /** 0.39.z75 metric fix B: a found source the order was not priced again on yet (another source than the last asked). */
    public static boolean askAgain(String known, String lastAsked) {
        return known != null && (lastAsked == null || !sourceOf(known).equals(sourceOf(lastAsked)));
    }

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

    /**
     * 0.39.z77 J2 E1 (run AP 00:02:40: the fresh world's one explore for a JOINED word "#logs|..|raw_iron|#coals" read UNKNOWN
     * by GenWhere - placed both ways - so the land-first water term was off and the nearest unseen chunk lay over the sea):
     * the water term is on when ANY part of the until word generates on the surface ({@code of}: a part -> its fact; null
     * or not loaded: not that part). Pure.
     */
    public static boolean anySurfacePart(String until, java.util.function.Function<String, io.github.mojolowjo.entropybot.aa.brain.GenWhere.Fact> of) {
        if (until == null || of == null) return false;
        for (String part : until.split("\\|")) {
            if (part.isEmpty()) continue;
            var f = of.apply(part);
            if (f != null && f.surface()) return true;
        }
        return false;
    }

    /**
     * 0.39.z77 J2 E2 (runs AX 14:23, BC 06:24:02: "no path 3 times from -181 16 27" toward cave candidates at y 60-98 by a
     * GoalXZ through the rock over the bot): under ground an explore's only targets are cave cells IN the target's band
     * {@code lo..hi} (within the reach the caller gave): a cell goal Baritone may dig to, as a player tunnels to a cave at its
     * own level. A cell out of the band - or any never-seen chunk - is no target from down there (rule 22: never a target a
     * player would not walk to from where it stands). The kept cells, in the given order. Pure.
     */
    public static List<int[]> inBand(List<int[]> cells, int lo, int hi) {
        List<int[]> out = new ArrayList<>();
        if (cells == null) return out;
        for (int[] c : cells) if (c[1] >= lo && c[1] <= hi) out.add(c);
        return out;
    }

    /**
     * Run ED (21:40:11: "toward cave opening -4 69 14 (5 m)", 72 ms later "ended 4 m from it" - the chunk target's
     * exploreRules.arrived 24 blocks, x + z, read as arrival at an opening): the feet stand within reach of the opening
     * cell - the eyes (feet + 1.62) to the cell's centre within {@link #OPENING_REACH}. Pure.
     */
    public static boolean withinReach(int[] feet, int[] cell) {
        if (feet == null || cell == null || feet.length < 3 || cell.length < 3) return false;     // structural: x y z
        double dx = cell[0] - feet[0], dy = cell[1] + 0.5 - (feet[1] + 1.62), dz = cell[2] - feet[2];   // game fact: eye height 1.62
        return Math.sqrt(dx * dx + dy * dy + dz * dz) <= OPENING_REACH;
    }

    /**
     * 0.39.z78 J6b item 5 (a): a chunk target's surface cell - the column's leaf-free surface (SurfaceHeight.noLeaves: the
     * first cell over its top non-leaf block, the cave index's ground), so the explore's walk ends on the surface and
     * never digs down for it; null: not known (the column not loaded). Pure.
     */
    public static int[] surfaceCell(int x, int z, int surfaceY) {
        return io.github.mojolowjo.entropybot.world.SurfaceHeight.known(surfaceY) ? new int[]{x, surfaceY, z} : null;
    }

    /**
     * 0.39.z78 J6b item 5 (b): a walk to a chunk target ({@code holdingChunk}) yields to the first credited opening that
     * {@code skip} does not name (visited, failed or frozen from near here); null: it walks on. A walk toward an opening
     * never yields (it is already one). Pure.
     */
    public static int[] yieldTo(boolean holdingChunk, List<int[]> credited, Predicate<int[]> skip) {
        if (!holdingChunk || credited == null) return null;
        for (int[] c : credited) if (skip == null || !skip.test(c)) return c;
        return null;
    }

    /**
     * J6b item 5: the fork's no-break walk ended without a path ({@code ICustomGoalProcess.lastEnd()} "no path (no-break
     * walk)": nothing reachable without breaking or placing). Pure.
     */
    public static boolean noBreakNoPath(String lastEnd) { return lastEnd != null && lastEnd.startsWith("no path") && lastEnd.contains("no-break"); }

    /**
     * Run EH (2): the no-break walks in a row from the same feet that found no path, at which the bot counts as boxed in:
     * three targets, each its own chunk - a hill hides one or two of them, never every way out.
     */
    public static final int BOXED_TRIES = 3; // structural: three separate targets from the same feet

    /** Run EH (2): that many no-break no-path ends in a row from the same feet - boxed in. Pure. */
    public static boolean boxedIn(int failsHere) { return failsHere >= BOXED_TRIES; }

    /** A world fact about one cell (the lip test's stand and sight, from the level; tests give their own). */
    @FunctionalInterface
    public interface CellTest { boolean test(int x, int y, int z); }

    /**
     * Run EL: the lip goal as the lip step reads it - in the game the fork's GoalReachBlock on the entry (a standable cell
     * from which the entry is within reach of the eye and a face of it in sight; its cells built on the game thread).
     */
    public interface LipGoal {
        boolean isEmpty();

        boolean isInGoal(int x, int y, int z);
    }

    /** Run EL: where the lip step stands. */
    public enum Lip { APPROACH, WALKING, ARRIVED, NO_CELL }

    /**
     * Run EL (rules 2, 23 (2); run EL 03:22:57-03:23:58: a GoalNear 3 stand-in accepted the cell 3 over the entry while
     * the lip test said no - two "arrived" readers): the lip step's ONE arrived reader is its goal. {@code goal} null (the
     * entry's chunk not loaded): APPROACH - walking toward it, never done. Empty: NO_CELL - the step's named fact
     * ({@link #noLipCell}). Else ARRIVED exactly when the goal holds the feet, WALKING otherwise. Pure.
     */
    public static Lip lip(LipGoal goal, int[] feet) {
        if (goal == null) return Lip.APPROACH;
        if (goal.isEmpty()) return Lip.NO_CELL;
        return feet != null && feet.length >= 3 && goal.isInGoal(feet[0], feet[1], feet[2]) ? Lip.ARRIVED : Lip.WALKING;     // structural: x y z
    }

    /** Run EL: the lip step's named fact when its goal has no cell. */
    public static String noLipCell(int[] entry) {
        return "no standable cell within reach and sight of the entry " + entry[0] + " " + entry[1] + " " + entry[2];
    }

    /**
     * Run EI (2): the "probe nobreak:" line of one failed no-break walk (debug.walkProbe): the feet, the goal, the fork's
     * lastEnd, and its calc detail from lastNoBreakCalc ({@code calc} null: the fork kept none; {@code nodes} -1: not
     * Baritone's A*; {@code best} null: not known - each said so).
     * Pure.
     */
    public static String noBreakLine(int[] feet, String goal, String lastEnd, String calc, Integer nodes, String best) {
        String none = "none (the fork kept no no-break calc)";
        return "probe nobreak: feet " + (feet == null ? "-" : feet[0] + " " + feet[1] + " " + feet[2]) + " | goal " + goal + " | lastEnd " + lastEnd
                + " | calc " + (calc == null ? none : calc)
                + " | nodes opened " + (nodes == null ? "-" : nodes < 0 ? "unknown (not Baritone's A*)" : nodes)
                + " | best node " + (best == null ? "unknown" : best);
    }

    /** game fact: the survival block interaction range - a player at the opening could touch it. */
    public static final double OPENING_REACH = 4.5;

    /**
     * Run EN (rule 23 (2)): the cell a {@link TargetHold} is given when its walk's own reach goal decides arrival - no cell,
     * so the hold never says REACHED (no eye-reach "reached"); it only holds or fails (no path, no new best).
     */
    public static final int[] NEVER_REACHED = new int[0];

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

    /**
     * 0.39.z72 (design D2 3.3, Job C; run P 00:04:15-54: a new explore target -264 24, -248 24, -232 24 ... one per
     * CALC_FAILED while the feet stood at -148 -33 -83, each unreached chunk marked explored): the chosen explore target as
     * a held fact. It is held until REACHED ({@link #arrived}) or FAILED - Baritone found no path {@code monitor.noPathFails}
     * times since the last new best, or no new best for {@code monitor.stallS}. A new best = the feet's horizontal distance
     * to the target 1 block below its best. Baritone going idle is not a failure by itself: the adapter sets the same goal
     * again for a HELD target (rule 2: Baritone walks). A FAILED target is not marked explored (rule 15: an unreached chunk
     * was never seen); the run keeps it with the cell it failed from and skips it until the bot moved {@code failures.moved}
     * blocks from there ({@link #skip}). The hold never picks a target (rule 1: the chooser's pick is unchanged), it only
     * says how long the pick lives and what its end marks. Pure (JUnit: Z72ExploreHoldTest).
     */
    public static final class TargetHold {
        private TargetHold() {}

        public enum State { HELD, REACHED, FAILED }

        /**
         * The held target {@code x z}: held since {@code since} (ticks), its best distance and when, Baritone's failed-search
         * count at that best, the failed searches since it, the state and why.
         */
        public record Held(int x, int z, long since, double best, long bestAt, long calcAtBest, int noPathFails, State state, String why) {
            public Target target() { return new Target(x, z); }

            /**
             * A REACHED chunk target marks its chunk explored; a FAILED one never does; a cave candidate ({@code aimed}) never
             * does (0.39.z72 review 1: it ends as visited instead, as before).
             */
            public boolean marksExplored(boolean aimed) { return state == State.REACHED && !aimed; }
        }

        /** A failed target, the cell it failed from and why (the run's failed set). */
        public record Failed(int x, int z, int[] from, String why) {
            public Failed {
                from = from == null ? null : from.clone();
            }
        }

        /** Baritone's failed searches since a target's last new best before it fails (Tuning monitor.noPathFails). */
        public static int noPathFailsV() { return Math.max(1, Tuning.i("monitor.noPathFails")); }

        /** Ticks with no new best before a target fails (Tuning monitor.stallS x 20 ticks a second, a game fact). */
        public static long stallTicks() { return Math.round(20 * Tuning.get("monitor.stallS")); }

        /** J6b item 5: the held target fails now ({@code why}: the no-break walk found no path); a REACHED or FAILED hold stays. */
        public static Held failNow(Held h, String why) {
            return h == null || h.state() != State.HELD ? h : with(h, h.best(), h.bestAt(), h.calcAtBest(), h.noPathFails(), State.FAILED, why);
        }

        /** The target chosen at {@code now} from {@code feet} (null: not known), Baritone's failed-search count then. */
        public static Held start(Target t, long now, int[] feet, long calcFailed) {
            return new Held(t.x(), t.z(), now, dist(feet, t.x(), t.z()), now, calcFailed, 0, State.HELD, "held");
        }

        /**
         * One look at {@code now} from {@code feet}: the hold's next state. A REACHED or FAILED hold stays as it is.
         * 0.39.z72 review 2: as the step monitor, the failed searches fail the target only while Baritone executes nothing
         * ({@code executing}: it follows a path - a failure of the global count on another goal is not this target's).
         */
        public static Held look(Held h, long now, int[] feet, long calcFailed, boolean executing) {
            return look(h, now, feet, calcFailed, executing, null);
        }

        /**
         * Run ED: as {@link #look(Held, long, int[], long, boolean)}; a cave opening's target ({@code cell} not null) is
         * REACHED only within reach of that cell ({@link #withinReach}), never by the chunk target's 24 blocks; it ends
         * otherwise only FAILED, on Baritone's no progress (no path, or no new best for monitor.stallS).
         */
        public static Held look(Held h, long now, int[] feet, long calcFailed, boolean executing, int[] cell) {
            // (run EN: cell == NEVER_REACHED - withinReach reads no cell - the hold only fails or holds; its walk's goal decides arrival)
            if (h == null || h.state() != State.HELD) return h;
            if (feet != null && (cell != null ? withinReach(feet, cell) : arrived(feet[0], feet[2], h.target()))) return with(h, h.best(), h.bestAt(), h.calcAtBest(), 0, State.REACHED, "reached");
            double d = dist(feet, h.x(), h.z());
            double best = h.best();
            long bestAt = h.bestAt(), calcAt = h.calcAtBest();
            if (!Double.isNaN(d) && (Double.isNaN(best) || d <= best - 1)) {        // structural: one block nearer
                best = d;
                bestAt = now;
                calcAt = calcFailed;
            }
            int fails = (int) Math.max(0, calcFailed - calcAt);
            String from = feet == null ? "an unknown cell" : feet[0] + " " + feet[1] + " " + feet[2];
            if (fails >= noPathFailsV() && !executing) return with(h, best, bestAt, calcAt, fails, State.FAILED, "Baritone found no path " + fails + " times from " + from);
            if (now - bestAt >= stallTicks())
                return with(h, best, bestAt, calcAt, fails, State.FAILED, "no new best for " + (now - bestAt) / 20 + " s from " + from    // game fact: 20 ticks a second
                        + (Double.isNaN(best) ? "" : " (best " + Math.round(best) + " m)"));
            return with(h, best, bestAt, calcAt, fails, State.HELD, "held");
        }

        private static Held with(Held h, double best, long bestAt, long calcAt, int fails, State s, String why) {
            return new Held(h.x(), h.z(), h.since(), best, bestAt, calcAt, fails, s, why);
        }

        /** The feet's horizontal distance to x z, NaN: no feet. */
        static double dist(int[] feet, int x, int z) {
            if (feet == null || feet.length < 3) return Double.NaN;     // structural: x y z
            double dx = feet[0] - x, dz = feet[2] - z;
            return Math.sqrt(dx * dx + dz * dz);
        }

        /**
         * The chunk {@code cx cz} holds a target that failed from a cell the bot ({@code me}) is still within {@code moved}
         * blocks of: the next-target pick passes over it (a fact about where it failed, never "explored"). Unknown feet:
         * still skipped (rule 15).
         */
        /** 0.39.z75 review 2: a held target no longer applies - the bot stands {@code moved} or more from where it failed. */
        public static boolean released(Failed f, int[] me, double moved) {
            if (f == null || f.from() == null || me == null) return false;
            double dx = me[0] - f.from()[0], dy = me[1] - f.from()[1], dz = me[2] - f.from()[2];
            return Math.sqrt(dx * dx + dy * dy + dz * dz) >= moved;
        }

        /**
         * Run ED: a cave opening x z whose walk failed (or froze) from a cell the bot is still within {@code moved} of: the
         * pick passes over it - it was not visited (a visited opening is one the bot stood at).
         */
        public static boolean skipCell(List<Failed> failed, int x, int z, int[] me, double moved) {
            if (failed == null) return false;
            for (Failed f : failed) if (f.x() == x && f.z() == z && !released(f, me, moved)) return true;     // unknown feet: still skipped (rule 15)
            return false;
        }

        public static boolean skip(List<Failed> failed, int cx, int cz, int[] me, double moved) {
            if (failed == null) return false;
            for (Failed f : failed) {
                if (f.x() >> 4 != cx || f.z() >> 4 != cz) continue;     // structural: a chunk is 16 blocks (>> 4)
                if (me == null || f.from() == null) return true;
                double dx = me[0] - f.from()[0], dy = me[1] - f.from()[1], dz = me[2] - f.from()[2];
                if (Math.sqrt(dx * dx + dy * dy + dz * dz) < moved) return true;
            }
            return false;
        }

        /** "explore: target -264 24 failed: Baritone found no path 3 times from -148 -33 -83" (said once per failure). */
        public static String failedLine(Held h) { return "explore: target " + h.x() + " " + h.z() + " failed: " + h.why(); }
    }
}
