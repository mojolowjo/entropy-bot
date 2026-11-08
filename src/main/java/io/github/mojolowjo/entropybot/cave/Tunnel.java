package io.github.mojolowjo.entropybot.cave;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.function.ToDoubleFunction;

/**
 * Job-tunnel (run JAS 2026-11-08 04:45: the band way dug down to -112 16 11 as priced, then its explore ended at once,
 * "no cave cell in the band within cave.exploreTowardM 128 and no target a player would walk to from here", twice, and
 * the order parked; the band price had promised ore by the faces a dig exposes): the band way's tunnel step's own rules,
 * pure (JUnit: JobTunnelTest). The step (Step.Tunnel, StepHands) gives Baritone ONE goal - the tunnel's end cell, a
 * GoalBlock under the ordinary dig lease - and Baritone picks every move and break (rule 2); these rules pick only the
 * heading, by price:
 * <ol>
 * <li>away from the headings already tunnelled this order (each is a fact: its rock is seen);</li>
 * <li>toward the nearest never-seen chunk along the heading within tunnel.unseenReachM (rule 22: a never-seen chunk is a
 * goal through rock);</li>
 * <li>then the seen ore density along the heading (higher first), then Baritone's estimate to the end cell (lower first).</li>
 * </ol>
 * The length is the band price's own model (BandPrice, rule 7): n ore at {@code perBlock} ore per exposed face need n /
 * perBlock faces; a 1 x 2 tunnel breaks 2 blocks a step and each break exposes band.facesPerBreak faces, so the steps are
 * faces / (2 x facesPerBreak), capped at tunnel.maxLength. Loader notes: none.
 */
public final class Tunnel {
    private Tunnel() {}

    /** A heading: its word and its step on x / z. */
    public record Heading(String name, int dx, int dz) {}

    /** The four headings a 1 x 2 tunnel runs (game fact: the horizontal block faces). */
    public static final List<Heading> HEADINGS = List.of(new Heading("north", 0, -1), new Heading("east", 1, 0), new Heading("south", 0, 1), new Heading("west", -1, 0));

    public static Heading heading(String name) {
        for (Heading h : HEADINGS) if (h.name().equals(name)) return h;
        return null;
    }

    /** The pick: the heading and the words that say why (rule 7). Null heading: none (the words say why). */
    public record Pick(Heading heading, String why) {}

    /**
     * Picks the heading from {@code feet} (see the class). {@code seenChunk}: (cx, cz) the chunk is seen; {@code done}: the
     * headings tunnelled this order from near here; {@code density} (null: not known) the seen ore a block along the heading;
     * {@code estimate} (null: not known) Baritone's seconds to the end cell at {@code length}. Every heading done: no pick, said
     * (never a silent pick).
     */
    public static Pick pick(int[] feet, int length, int reach, BiPredicate<Integer, Integer> seenChunk, Set<String> done,
                            ToDoubleFunction<Heading> density, ToDoubleFunction<Heading> estimate) {
        List<Heading> open = new ArrayList<>();
        for (Heading h : HEADINGS) if (done == null || !done.contains(h.name())) open.add(h);
        if (open.isEmpty()) return new Pick(null, "every heading from " + feet[0] + " " + feet[1] + " " + feet[2] + " was tunnelled this order (" + String.join(", ", done) + ")");
        record Scored(Heading h, int unseen, double dens, double est) {}
        List<Scored> sc = new ArrayList<>();
        for (Heading h : open) {
            int u = Integer.MAX_VALUE;
            for (int i = 1; i <= reach; i++) {
                int x = feet[0] + h.dx() * i, z = feet[2] + h.dz() * i;
                if (!seenChunk.test(x >> 4, z >> 4)) { u = i; break; }   // game fact: 16 blocks a chunk
            }
            double d = density == null ? 0 : density.applyAsDouble(h);
            double e = estimate == null ? 0 : estimate.applyAsDouble(h);
            sc.add(new Scored(h, u, Double.isFinite(d) ? d : 0, Double.isFinite(e) ? e : Double.MAX_VALUE));
        }
        sc.sort(Comparator.comparingInt(Scored::unseen).thenComparing(Comparator.comparingDouble(Scored::dens).reversed()).thenComparingDouble(Scored::est));
        Scored b = sc.get(0);
        String why = b.unseen() == Integer.MAX_VALUE
                ? "no never-seen chunk within tunnel.unseenReachM " + reach + " of any open heading; by the seen ore density " + fmt(b.dens()) + (estimate == null ? "" : ", then Baritone's estimate")
                : "the nearest never-seen chunk along it, " + b.unseen() + " blocks (rule 22: a goal through rock)";
        if (done != null && !done.isEmpty()) why += "; tunnelled this order: " + String.join(", ", done);
        return new Pick(b.h(), why);
    }

    /** The tunnel's length in steps by the band price's model (see the class); at least 1. */
    public static int length(int n, double perBlock, double facesPerBreak, int max) {
        if (!(perBlock > 0) || !(facesPerBreak > 0)) return Math.max(1, max);
        double faces = n / perBlock;
        int steps = (int) Math.ceil(faces / (2 * facesPerBreak));   // structural: a 1 x 2 tunnel breaks 2 blocks a step
        return Math.max(1, Math.min(max, steps));
    }

    /** The tunnel's end cell from {@code from} along {@code h} for {@code length} steps. */
    public static int[] end(int[] from, Heading h, int length) {
        return new int[]{from[0] + h.dx() * length, from[1], from[2] + h.dz() * length};
    }

    /**
     * Cave air: the first air cell of {@code air} (cells read now round the feet) that is not in {@code known} (the air at
     * the start and the cells the tunnel itself opened) - air beyond a wall the tunnel did not make. Null: none.
     */
    public static int[] caveAir(List<int[]> air, Set<Long> known) {
        for (int[] c : air) if (!known.contains(key(c[0], c[1], c[2]))) return c;
        return null;
    }

    /** What the running tunnel does this tick. */
    public enum Act { DIG, MINE, END_FACT, DONE }

    public record Next(Act act, String why) {}

    /**
     * The tunnel's tick, in this order: a seen ore of the family in the mine's sight ({@code ore}: its cell, null none)
     * pauses the dig and the vein takes it; water or lava ahead ({@code fluid}: its words) ends it, the heading held; cave
     * air ({@code cave}) ends it for the cave step; the end cell reached is done; Baritone stopped short is a fact; else Baritone digs on.
     */
    public static Next next(int[] ore, String fluid, int[] cave, boolean atEnd, boolean stopped, Heading h, int[] end, int[] feet) {
        if (ore != null) return new Next(Act.MINE, "a seen ore at " + ore[0] + " " + ore[1] + " " + ore[2] + " is in the mine's sight - the tunnel pauses, the vein mines it");
        if (fluid != null) return new Next(Act.END_FACT, fluid + ": the heading " + h.name() + " is held");
        if (cave != null) return new Next(Act.END_FACT, "cave air at " + cave[0] + " " + cave[1] + " " + cave[2] + ": the cave step takes over");
        if (atEnd) return new Next(Act.DONE, "at the tunnel's end " + end[0] + " " + end[1] + " " + end[2]);
        if (stopped) return new Next(Act.END_FACT, "Baritone stopped short of the tunnel's end " + end[0] + " " + end[1] + " " + end[2] + " at " + feet[0] + " " + feet[1] + " " + feet[2]);
        return new Next(Act.DIG, "digging " + h.name());
    }

    /** The tunnel's end line: blocks broken, faces exposed, ore seen and mined, and the reason (rule 7). */
    public static String endLine(String why, int broken, int faces, int seen, int mined, String family) {
        return String.format(Locale.ROOT, "%s: %d block(s) broken, %d face(s) exposed, %d %s ore seen, %d mined", why, broken, faces, seen, family, mined);
    }

    /**
     * Job-tunnel2 (run JAT 05:14:13: a 51-block GoalBlock through rock ended in 2 s with nothing broken): the goal is capped at
     * the loaded edge - the most steps {@code i <= length} with every step 1..i loaded ({@code loadedAt}: step i's cells are
     * loaded). 0: the first step is not loaded.
     */
    public static int loadedSteps(int length, java.util.function.IntPredicate loadedAt) {
        int n = 0;
        for (int i = 1; i <= length; i++) {
            if (!loadedAt.test(i)) break;
            n = i;
        }
        return n;
    }

    /**
     * Job-tunnel3 (run JAW 06:34:55-06:35:30: four 51-block goals through rock, each ended at its start - north "CHUNKS",
     * the rest "TIME" - the fork's price ask cannot land a 51-block dig path in its 1-2 s budget): the tunnel runs in LEGS.
     * A leg's steps from the feet: at most {@code legBlocks} and the {@code left} to the end, and only steps whose cells AND
     * the cell one step past them are loaded (the search reads the goal's neighbours; run JAW's north goal ended CHUNKS on the
     * loaded edge). 0: the next leg has no loaded goal.
     */
    public static int legSteps(int left, int legBlocks, java.util.function.IntPredicate loadedAt) {
        int want = Math.max(0, Math.min(left, legBlocks));
        if (want == 0) return 0;
        int loaded = loadedSteps(want + 1, loadedAt);                      // structural: one cell past the goal
        return Math.max(0, Math.min(want, loaded - 1));
    }

    /** Job-tunnel3: what a leg that ended short (timed out, stopped, no path) does: retry once shorter, then hold. */
    public enum ShortLeg { RETRY, HOLD }

    public static ShortLeg shortLeg(boolean retried) { return retried ? ShortLeg.HOLD : ShortLeg.RETRY; }

    /** Job-tunnel3: the end line with the legs walked ("...; 9 leg(s)"). */
    public static String endLine(String why, int broken, int faces, int seen, int mined, String family, int legs) {
        return endLine(why, broken, faces, seen, mined, family) + ", " + legs + " leg(s)";
    }

    /** Job-tunnel2: the words of a capped goal ("to the loaded edge at x y z first, N of the L blocks"). */
    public static String edgeLine(int[] goal, int steps, int length) {
        return "to the loaded edge at " + goal[0] + " " + goal[1] + " " + goal[2] + " first, " + steps + " of the " + length + " blocks";
    }

    /** Job-tunnel2: the key prefix of a held tunnel heading ("tunnel heading x y z north"), a source the order holds. */
    public static final String HOLD = "tunnel heading ";

    /** Job-tunnel2: the source key of the heading {@code heading} from {@code start}. */
    public static String holdKey(int[] start, String heading) {
        return HOLD + start[0] + " " + start[1] + " " + start[2] + " " + heading;
    }

    /** Job-tunnel2 (rule 7): the held heading's words - "tunnel north from x y z held: Baritone stopped short at x y z (the fork: ...)". */
    public static String heldText(String heading, int[] start, String why) {
        return "tunnel " + heading + " from " + start[0] + " " + start[1] + " " + start[2] + " held: " + why;
    }

    /**
     * Job-tunnel2: the headings held near {@code at} (within {@code nearM} blocks of a held start, the same Y) among the
     * order's held lines ("key: why"); the planner's band way drops a peak whose every heading is held.
     */
    public static Set<String> heldHeadings(java.util.Collection<String> held, int[] at, double nearM) {
        Set<String> out = new java.util.TreeSet<>();
        if (held == null || at == null) return out;
        for (String h : held) {
            if (h == null || !h.startsWith(HOLD)) continue;
            int colon = h.indexOf(": ");
            String[] p = (colon < 0 ? h.substring(HOLD.length()) : h.substring(HOLD.length(), colon)).trim().split("\\s+");
            if (p.length != 4) continue;                                     // structural: x y z heading
            try {
                int x = Integer.parseInt(p[0]), y = Integer.parseInt(p[1]), z = Integer.parseInt(p[2]);
                if (y == at[1] && Math.hypot(x - at[0], z - at[2]) <= nearM && heading(p[3]) != null) out.add(p[3]);
            } catch (NumberFormatException e) {
                continue;   // silent: not a cell, not a tunnel hold; the held line itself is said where it was written
            }
        }
        return out;
    }

    public static long key(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);   // structural: the packed cell
    }

    static String fmt(double v) { return String.format(Locale.ROOT, "%.4f", v); }
}
