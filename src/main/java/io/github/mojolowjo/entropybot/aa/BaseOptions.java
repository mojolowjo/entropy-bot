package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.brain.Site;

import java.util.ArrayList;
import java.util.List;

/**
 * AA_ROUND4A O: a base (a roof for the night) as a priced option set, the ways:
 * <ul>
 * <li><b>house &lt;area&gt;</b>: an area is marked: BuilderProcess builds the house ({@code base.houseS}, 300 s).</li>
 * <li><b>shelter here</b>: {@code danger.boxBlocks} (12) blocks in the bag: the box ({@code danger.boxS}).</li>
 * <li><b>burrow</b>: dig down two and seal ({@code danger.burrowS}); a night way only (by day it is no base).</li>
 * </ul>
 * Night pricing (the danger desire's numbers, read through Tuning): at night each row adds its build seconds x
 * {@code danger.nightHpS} x {@code danger.hpS} x {@code base.nightW} for the open time while it is set up, so a long
 * build loses to a quick one in the dark. Under ground, the surface ways (house, shelter) climb first. Pure (JUnit:
 * BaseOptionsTest). Loader notes: none (facts from {@code commands.NeedFacts}).
 */
public final class BaseOptions {
    private BaseOptions() {}

    public static final String FACTS = "base.facts";

    /**
     * @param area   the marked area to house (null none); {@code areaAt} its cell for the site (may be null)
     * @param blocks building blocks in the bag (cobblestone + planks + dirt)
     * @param night  it is night; {@code danger} the night danger (0..1+, 1 = full night on normal)
     * @param under  under ground, {@code depth} blocks
     */
    public record BaseFacts(String area, int[] areaAt, int blocks, boolean night, double danger, boolean under, int depth) {}

    /** The open-night price of {@code seconds} spent setting a way up (0 by day). */
    static double nightCost(BaseFacts f, double seconds) {
        if (!f.night()) return 0;
        return Price.s(seconds * Price.tune("danger.nightHpS", 0.05) * Price.tune("danger.hpS", 6) * Math.max(0, f.danger()) * Price.tune("base.nightW", 1));
    }

    public static Options.Choice choose(BaseFacts f, Options.OptionMemory m, long now) {
        List<Options.Option> rows = new ArrayList<>();
        List<String> dropped = new ArrayList<>();
        double climb = Price.climb(f.under(), f.depth());
        String pre = f.under() ? AaLadder.CLIMB + " then " : "", climbWhy = f.under() ? ", climb " + f.depth() + " up " + Price.r(climb) : "";
        // 1. the house in the marked area
        if (f.area() == null) dropped.add("house: no area marked");
        else {
            double s = Price.tune("base.houseS", 300), night = nightCost(f, s);
            Options.Option o = Options.scored("house " + f.area(), "house", pre + "house " + f.area(), climb + Price.s(s) + night,
                    "build " + Price.r(s) + " s" + (f.night() ? ", open night " + Price.r(night) : "") + climbWhy, "house", false, m);
            RungOptions.add(rows, dropped, m, now, f.areaAt() == null ? o : o.withSite(Site.of(f.areaAt())));
        }
        // 2. the box here
        int need = (int) Price.tune("danger.boxBlocks", 12);
        if (f.blocks() < need) dropped.add("shelter here: " + f.blocks() + " blocks in the bag, " + need + " wanted");
        else {
            double s = Price.tune("danger.boxS", 20), night = nightCost(f, s);
            RungOptions.add(rows, dropped, m, now, Options.scored("shelter here", "shelter", pre + "goal shelter", climb + Price.s(s) + night,
                    f.blocks() + " blocks, build " + Price.r(s) + " s" + (f.night() ? ", open night " + Price.r(night) : "") + climbWhy, "shelter here", false, m));
        }
        // 3. the burrow (night only; no climb: it digs down where it is)
        if (!f.night()) dropped.add("burrow: a night way only");
        else {
            double s = Price.tune("danger.burrowS", 6), night = nightCost(f, s);
            RungOptions.add(rows, dropped, m, now, Options.scored("burrow", "burrow", "burrow then wait 120", Price.s(s) + night,
                    "dig 2 and seal " + Price.r(s) + " s, open night " + Price.r(night), "burrow", false, m));
        }
        return Options.Choice.of(rows, dropped, "no way to a base");
    }

    public static boolean switchable(String end) { return end != null && !Options.ownerOrDanger(end); }

    public static final class Set implements Options.OptionSet<BaseFacts> {
        @Override public Options.Choice choose(BaseFacts f, Options.OptionMemory m, long now, String planned, double cost, String plannerWhy, String afterFail) {
            return BaseOptions.choose(f, m, now);
        }

        @Override public boolean switchable(String end) { return BaseOptions.switchable(end); }
    }
}
