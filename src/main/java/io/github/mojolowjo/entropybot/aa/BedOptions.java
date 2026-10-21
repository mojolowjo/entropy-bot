package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.brain.Site;
import io.github.mojolowjo.entropybot.aa.brain.Failures;
import io.github.mojolowjo.entropybot.aa.moves.Step;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Round 4 fix 4 (live, 4x: "stopped at step 1 (hunt 3 sheep for white_wool 3): stopped: hunt: 0/3 ..." retried by the
 * bed rung): the ways to a bed as a priced option set, so a failed wool hunt is charged to its option (held
 * {@code Options.RETRY_MS} x failures, the next option runs at once) instead of the rung retrying the same chain:
 * <ul>
 * <li><b>craft</b>: 3 wool in the bag: the planner's bed ({@link Step.Plan} "bed").</li>
 * <li><b>hunt here</b>: sheep in view: a {@link Step.HuntAt} of k sheep for 3 white wool, then the planner's bed (the kills needed, 1 wool each).</li>
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

    /** The planner's bed. */
    public static Step.Plan bedPlan() { return new Step.Plan("bed"); }

    /** The wool hunt then the planner's bed, typed ({@code herd}: {x, z, ...} where they were seen, null in view). */
    public static List<Step> huntSteps(int wool, int[] herd) {
        int k = WOOL - Math.max(0, wool);
        return List.of(new Step.HuntAt("minecraft:sheep", k, herd == null ? null : new int[]{herd[0], herd[1]}, "minecraft:white_wool", WOOL), bedPlan());
    }

    /** The bed's steps without facts: the planner's bed with the wool, else the hunt first. */
    public static List<Step> steps(int wool, int[] herd) { return wool >= WOOL ? List.of(bedPlan()) : huntSteps(wool, herd); }

    private static List<Step> after(BedFacts f, List<Step> way) {
        List<Step> out = new ArrayList<>(AaLadder.climbFirst(f.under()));
        out.addAll(way);
        return out;
    }

    public static Options.Choice choose(BedFacts f, Options.OptionMemory m, long now) {
        List<Options.Option> rows = new ArrayList<>();
        List<String> dropped = new ArrayList<>();
        // AA_ROUND4A O: a known bed is slept in at night (nothing to craft)
        double bedS = Price.tune("danger.bedS");
        if (!f.bedKnown()) dropped.add("sleep in a known bed: none known");
        else if (!f.night()) dropped.add("sleep in a known bed: not night");
        double climb = Price.climb(f.under(), f.depth());
        String climbWhy = f.under() ? ", climb " + f.depth() + " up " + Price.r(climb) : "";
        // review 4A (3): under ground the known bed is reached by the climb first, priced like the hunt rows
        if (f.bedKnown() && f.night()) Price.add(rows, dropped, m, now, Options.typed("sleep in known bed", "sleep", after(f, List.of(new Step.Sleep())), climb + Price.s(bedS),
                "a bed known, walk + lie down " + Price.r(bedS) + " s" + climbWhy, "sleep known bed", m));
        if (f.wool() >= WOOL) {
            RungOptions.add(rows, dropped, m, now, Options.typed("make bed", "craft", List.of(bedPlan()), CRAFT, f.wool() + " wool in the bag", "make bed", true, m));
            return Options.Choice.of(rows, dropped, "no way to a bed");
        }
        int k = WOOL - Math.max(0, f.wool());
        if (f.sheepInView() > 0) {
            RungOptions.add(rows, dropped, m, now, Options.typed("hunt sheep here", "hunt", after(f, huntSteps(f.wool(), null)), climb + KILL * k + CRAFT,
                    f.sheepInView() + " sheep in view, " + k + " to kill" + climbWhy, "hunt sheep here", m));
        } else dropped.add("hunt sheep here: none in view");
        if (f.herd() != null && f.herd().length >= 4) {
            int[] h = f.herd();
            double conf = Math.max(1, h[3]) / 100.0;
            double cost = climb + (2 * h[2] + KILL * k + CRAFT + HERD_FIX) / conf;
            Options.Option o = Options.typed("hunt sheep at " + h[0] + " " + h[1], "hunt", after(f, huntSteps(f.wool(), h)), cost,
                    "a herd I saw " + h[2] + " away (confidence " + h[3] + "%), " + k + " to kill, there and back" + climbWhy, "hunt sheep at " + h[0] + " " + h[1], m);
            if (f.me() != null) o = o.withSite(Site.at(h[0], f.me()[1], h[1]));
            RungOptions.add(rows, dropped, m, now, o);
        } else dropped.add("hunt at a herd: none remembered");
        return Options.Choice.of(rows, dropped, "no wool (" + f.wool() + "/" + WOOL + ") and no sheep seen, no known bed");
    }

    private static final Pattern BED_END = Pattern.compile("hunt:|no sheep|none i may take|nothing to take|no path|no progress|timeout|stuck|couldn't|can't|gave up|refused");

    /** A failed wool hunt or bed craft may be answered with the next option (never the owner's stop, a fight, a death). */
    public static boolean switchable(String end) {
        if (end == null || Options.ownerOrDanger(end)) return false;
        return Failures.classify(end) != null || BED_END.matcher(end.toLowerCase(Locale.ROOT)).find();
    }

    /** The rung's option set. */
    public static final class Set implements Options.OptionSet<BedFacts> {
        @Override public Options.Choice choose(BedFacts f, Options.OptionMemory m, long now, List<Step> planned, double cost, String plannerWhy, String afterFail) {
            return BedOptions.choose(f, m, now);
        }

        @Override public boolean switchable(String end) { return BedOptions.switchable(end); }
    }
}
