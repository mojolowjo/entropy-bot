package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.brain.Site;
import io.github.mojolowjo.entropybot.sense.FailMemory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * aa-trees (director P2, "no trees in my areas" with trees 50 blocks out): the wood option set. The planner says "missing N
 * logs - no trees in my areas"; that is no longer a skip but a priced prerequisite with the ways the bot has seen:
 * <ul>
 * <li><b>extend</b>: a new area ("treesN", work, neutral) round the nearest seen tree, then {@code cut N}. Walk there once;
 *     the area stays, so later cuts nearby are free. In reach = within {@value #REACH}; at most {@value #AREA_CAP} such areas.</li>
 * <li><b>chop at</b>: the same cut through one tight area ("treeat", R {@value #CHOP_R}) that each use replaces (it grows no
 *     area list); priced with the way back to the areas (logs are resources: allowed outside my areas in singleplayer,
 *     {@code outsideLogs} in the facts), up to {@value #FAR_MAX} away.</li>
 * </ul>
 * Trees come from {@code sense/Sightings} (kind TREE: a log column on dirt with leaves, seen twice; fair play: a tree not
 * seen is not an option). A tree whose protect-box or policy verdict says no is dropped with its reason; a failed option
 * is held per tree (Options.RETRY_MS, 10 min), so a prerequisite that fails never loops: the next tree, or "no option"
 * with every reason. Unit: walk-blocks (1 s of work = 1 block, Options.MINUTE = 60). Pure (JUnit: WoodOptionsTest).
 * Loader notes: none (the facts come from {@code AaRuntime.woodFacts}).
 */
public final class WoodOptions {
    private WoodOptions() {}

    public static final String FACTS = "wood.facts";
    public static final double CUT_LOG = 6, AREA_FIX = 10, OUTSIDE_FIX = 10, SEARCH = 60;
    public static final int TREE_AREA_R = 20, CHOP_R = 8, AREA_CAP = 3, REACH = 96, FAR_MAX = 256;
    public static final String CHOP_AREA = "treeat";

    /** @param deny the policy's or a protect box's reason against cutting there (null: allowed) */
    public record Tree(int x, int y, int z, double dist, String deny) {
        public String key() { return x + " " + y + " " + z; }
    }

    /**
     * @param need         logs wanted
     * @param trees        the seen trees, nearest first (exhausted ones already left out)
     * @param areasMade    tree areas AA made already (named trees1..)
     * @param outsideLogs  logs may be cut outside my areas (singleplayer: the owner's rule on resources)
     */
    public record WoodFacts(int need, List<Tree> trees, int areasMade, boolean outsideLogs) {}

    public static String areaCmd(String name, int x, int z, int r) {
        return "area " + (x - r) + " " + (z - r) + " " + (x + r) + " " + (z + r) + " " + name + " neutral";
    }

    public static Options.Choice choose(WoodFacts f, Options.OptionMemory m, long now) {
        List<Options.Option> rows = new ArrayList<>();
        List<String> dropped = new ArrayList<>();
        int need = Math.max(1, f.need());
        boolean plainTry = f.trees().isEmpty();
        for (int pass = 0; pass < 2; pass++) {
        if (plainTry) {
            // nothing seen: the plain cut (the chopper's own search of my areas and its remembered logs) is one priced try, held 10 min on a failure
            String key = "cut here";
            if (m.blocked(key, now)) dropped.add("cut in my areas: " + m.reason(key) + " (no tree seen yet; the sightings fill as it walks)");
            else rows.add(Options.scored("cut in my areas", "wood", "cut " + need, SEARCH + CUT_LOG * need, "no tree seen yet: the chopper searches my areas and its remembered logs", key, false, m));
        }
        if (pass == 1) break;
        boolean extend = false, chop = false;
        for (Tree t : f.trees()) {
            String extendKey = "extend " + t.key(), chopKey = "chop at " + t.key();
            if (t.deny() != null) { dropped.add("tree " + t.key() + ": " + t.deny()); continue; }
            if (!extend && t.dist() <= REACH) {
                if (f.areasMade() >= AREA_CAP) { dropped.add("extend area: " + AREA_CAP + " tree areas made already"); extend = true; }
                else if (m.blocked(extendKey, now)) dropped.add(extendKey + ": " + m.reason(extendKey));
                else {
                    extend = true;
                    rows.add(Options.scored("extend wood area " + t.key(), "wood", areaCmd("trees" + (f.areasMade() + 1), t.x(), t.z(), TREE_AREA_R) + " then cut " + need,
                            t.dist() + CUT_LOG * need + AREA_FIX, "a new tree area round the seen tree " + Math.round(t.dist()) + " away, it stays for later cuts", extendKey, false, m).withSite(Site.at(t.x(), t.y(), t.z())));
                }
            }
            if (!chop && t.dist() <= FAR_MAX) {
                if (!f.outsideLogs()) { dropped.add("chop at " + t.key() + ": logs outside my areas are not allowed here"); chop = true; }
                else if (m.blocked(chopKey, now)) dropped.add(chopKey + ": " + m.reason(chopKey));
                else {
                    chop = true;
                    rows.add(Options.scored("chop at " + t.key(), "wood", areaCmd(CHOP_AREA, t.x(), t.z(), CHOP_R) + " then cut " + need,
                            2 * t.dist() + CUT_LOG * need + OUTSIDE_FIX, "the seen tree " + Math.round(t.dist()) + " away, outside my areas (logs are resources), the way back counted", chopKey, false, m)
                            .withSite(Site.at(t.x(), t.y(), t.z())));
                }
            }
            if (extend && chop) break;
        }
        if (!rows.isEmpty() || plainTry) break;
        plainTry = true;      // trees seen but no row: the plain cut is still one try
        }
        return Options.Choice.of(rows, dropped, "no way to wood");
    }

    private static final Pattern WOOD_END = Pattern.compile("no trees|chopped nothing|couldn't get at its trunk|no path|no progress|timeout|stuck|couldn't|can't|outside my areas|area");

    /** A failed wood prerequisite may be answered with the next option (never the owner's stop or danger). */
    public static boolean switchable(String end) {
        if (end == null || Options.ownerOrDanger(end)) return false;
        return FailMemory.classify(end) != null || WOOD_END.matcher(end.toLowerCase(Locale.ROOT)).find();
    }

    /** True for a prerequisite command this set made ("area ... then cut N"). */
    public static boolean isWoodCommand(String c) { return c != null && c.startsWith("area ") && c.contains(" then cut "); }
}

