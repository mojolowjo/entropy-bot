package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.brain.Site;
import io.github.mojolowjo.entropybot.sense.FailMemory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Round 4 fix 4 (live, 4x: "stopped at step 1 (hunt 3 sheep for white_wool 3): stopped: hunt: 0/3 ..." retried by the
 * bed rung): the ways to a bed as a priced option set, so a failed wool hunt is charged to its option (held
 * {@code Options.RETRY_MS} x failures, the next option runs at once) instead of the rung retrying the same chain:
 * <ul>
 * <li><b>craft</b>: 3 wool in the bag: {@code goal bed} (the planner crafts it).</li>
 * <li><b>hunt here</b>: sheep in view: {@code hunt k sheep for white_wool 3 then goal bed} (the kills needed, 1 wool each).</li>
 * <li><b>hunt at</b>: a herd of sheep remembered and not in view: the same hunt {@code at x z}, priced with the walk
 *     there and back and the sighting's confidence.</li>
 * </ul>
 * No way = "no option" with every reason (the rung skips, and says why). Unit: walk-blocks (Options.MINUTE = 60). Pure
 * (JUnit: BedOptionsTest). Loader notes: none (the facts come from {@code AaRuntime.bedFacts}).
 */
public final class BedOptions {
    private BedOptions() {}

    public static final String FACTS = "bed.facts";
    /** Wool a bed takes; blocks of work per kill (walk up, hit, pick up); the craft itself; a fixed cost for leaving for a herd. */
    public static final int WOOL = 3;
    public static final double KILL = 25, CRAFT = 20, HERD_FIX = 20;

    /**
     * @param wool        white wool in the bag
     * @param sheepInView sheep within the hunt's range now
     * @param herd        the best remembered sheep herd not in view ({x, z, distance, confidence x 100}), or null
     * @param me          the bot's cell (for the herd's site), may be null
     */
    public record BedFacts(int wool, int sheepInView, int[] herd, int[] me, boolean bedKnown, boolean night, boolean under, int depth) {
        /** 0.39.7's facts: no known bed, day, on the surface. */
        public BedFacts(int wool, int sheepInView, int[] herd, int[] me) { this(wool, sheepInView, herd, me, false, false, false, 0); }
    }

    /** AA_ROUND4A O: the climb out, prepended to a surface way when under ground ("" on the surface). */
    static String climbFirst(BedFacts f) { return f.under() ? AaLadder.CLIMB + " then " : ""; }

    /** The hunt chain the ladder used before (kept: the planner-free fallback without facts). */
    public static String huntChain(int wool, int[] herd) {
        int k = WOOL - Math.max(0, wool);
        return "hunt " + k + " sheep for white_wool " + WOOL + (herd == null ? "" : " at " + herd[0] + " " + herd[1]) + " then goal bed";
    }

    public static Options.Choice choose(BedFacts f, Options.OptionMemory m, long now) {
        List<Options.Option> rows = new ArrayList<>();
        List<String> dropped = new ArrayList<>();
        // AA_ROUND4A O: a known bed is slept in at night (nothing to craft)
        double bedS = Price.tune("danger.bedS", 20);
        if (!f.bedKnown()) dropped.add("sleep in a known bed: none known");
        else if (!f.night()) dropped.add("sleep in a known bed: not night");
        else RungOptions.add(rows, dropped, m, now, Options.scored("sleep in known bed", "sleep", "sleep", Price.s(bedS),
                "a bed known, walk + lie down " + Price.r(bedS) + " s", "sleep known bed", false, m));
        double climb = Price.climb(f.under(), f.depth());
        String climbWhy = f.under() ? ", climb " + f.depth() + " up " + Price.r(climb) : "";
        if (f.wool() >= WOOL) {
            RungOptions.add(rows, dropped, m, now, Options.scored("craft bed", "craft", "goal bed", CRAFT, f.wool() + " wool in the bag", "craft bed", true, m));
            return Options.Choice.of(rows, dropped, "no way to a bed");
        }
        int k = WOOL - Math.max(0, f.wool());
        if (f.sheepInView() > 0) {
            RungOptions.add(rows, dropped, m, now, Options.scored("hunt sheep here", "hunt", climbFirst(f) + huntChain(f.wool(), null), climb + KILL * k + CRAFT,
                    f.sheepInView() + " sheep in view, " + k + " to kill" + climbWhy, "hunt sheep here", false, m));
        } else dropped.add("hunt sheep here: none in view");
        if (f.herd() != null && f.herd().length >= 4) {
            int[] h = f.herd();
            double conf = Math.max(1, h[3]) / 100.0;
            double cost = climb + (2 * h[2] + KILL * k + CRAFT + HERD_FIX) / conf;
            Options.Option o = Options.scored("hunt sheep at " + h[0] + " " + h[1], "hunt", climbFirst(f) + huntChain(f.wool(), h), cost,
                    "a herd I saw " + h[2] + " away (confidence " + h[3] + "%), " + k + " to kill, there and back" + climbWhy, "hunt sheep at " + h[0] + " " + h[1], false, m);
            if (f.me() != null) o = o.withSite(Site.at(h[0], f.me()[1], h[1]));
            RungOptions.add(rows, dropped, m, now, o);
        } else dropped.add("hunt at a herd: none remembered");
        return Options.Choice.of(rows, dropped, "no wool (" + f.wool() + "/" + WOOL + ") and no sheep seen, no known bed");
    }

    private static final Pattern BED_END = Pattern.compile("hunt:|no sheep|none i may take|nothing to take|no path|no progress|timeout|stuck|couldn't|can't|gave up|refused");

    /** A failed wool hunt or bed craft may be answered with the next option (never the owner's stop, a fight, a death). */
    public static boolean switchable(String end) {
        if (end == null || Options.ownerOrDanger(end)) return false;
        return FailMemory.classify(end) != null || BED_END.matcher(end.toLowerCase(Locale.ROOT)).find();
    }

    /** The rung's option set. */
    public static final class Set implements Options.OptionSet<BedFacts> {
        @Override public Options.Choice choose(BedFacts f, Options.OptionMemory m, long now, String planned, double cost, String plannerWhy, String afterFail) {
            return BedOptions.choose(f, m, now);
        }

        @Override public boolean switchable(String end) { return BedOptions.switchable(end); }
    }
}
