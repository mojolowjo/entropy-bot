package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.brain.Site;
import io.github.mojolowjo.entropybot.sense.FailMemory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * aa-trees: the wood option set. Logs are a priced prerequisite with the ways the bot has seen:
 * <ul>
 * <li><b>cut</b>: nothing seen yet - the chopper's own search round the bot and its remembered logs, one priced try.</li>
 * <li><b>chop at</b>: walk to the nearest seen tree, then {@code cut N}; priced with the walk there and back, up to
 *     {@value #FAR_MAX} away.</li>
 * </ul>
 * Trees come from {@code sense/Sightings} (kind TREE: a log column on dirt with leaves, seen twice; fair play: a tree not
 * seen is not an option). The world is open: a tree is refused only by the policy (keep-out / no-break) or a protected
 * build near it, dropped with its reason; a failed option is held per tree (Options.RETRY_MS, 10 min), so a
 * prerequisite that fails never loops: the next tree, or "no option" with every reason. Unit: walk-blocks (1 s of
 * work = 1 block, Options.MINUTE = 60). Pure (JUnit: WoodOptionsTest). Loader notes: none (the facts come from
 * {@code AaRuntime.woodFacts}).
 */
public final class WoodOptions {
    private WoodOptions() {}

    public static final String FACTS = "wood.facts";
    public static final double CUT_LOG = 6, SEARCH = 60;
    public static final int FAR_MAX = 256;

    /** @param deny the policy's or a protect box's reason against cutting there (null: allowed) */
    public record Tree(int x, int y, int z, double dist, String deny) {
        public String key() { return x + " " + y + " " + z; }
    }

    /**
     * @param need  logs wanted
     * @param trees the seen trees, nearest first (exhausted ones already left out)
     */
    public record WoodFacts(int need, List<Tree> trees) {}

    public static Options.Choice choose(WoodFacts f, Options.OptionMemory m, long now) {
        List<Options.Option> rows = new ArrayList<>();
        List<String> dropped = new ArrayList<>();
        int need = Math.max(1, f.need());
        boolean chop = false;
        for (Tree t : f.trees()) {
            String chopKey = "chop at " + t.key();
            if (t.deny() != null) { dropped.add("tree " + t.key() + ": " + t.deny()); continue; }
            if (t.dist() > FAR_MAX) continue;
            if (m.blocked(chopKey, now)) { dropped.add(chopKey + ": " + m.reason(chopKey)); continue; }
            rows.add(Options.scored("chop at " + t.key(), "wood", "goto " + t.key() + " then cut " + need,
                    2 * t.dist() + CUT_LOG * need, "the seen tree " + Math.round(t.dist()) + " away, the walk there and back counted", chopKey, false, m)
                    .withSite(Site.at(t.x(), t.y(), t.z())));
            chop = true;
            break;
        }
        if (!chop) {
            // nothing seen (or every seen tree refused/held): the plain cut (the chopper's own search round me and its remembered logs) is one priced try
            String key = "cut here";
            if (m.blocked(key, now)) dropped.add("cut: " + m.reason(key) + " (no tree seen yet; the sightings fill as it walks)");
            else rows.add(Options.scored("cut", "wood", "cut " + need, SEARCH + CUT_LOG * need, "no tree seen yet: the chopper searches round me and its remembered logs", key, false, m));
        }
        return Options.Choice.of(rows, dropped, "no way to wood");
    }

    private static final Pattern WOOD_END = Pattern.compile("no trees|chopped nothing|couldn't get at its trunk|no path|no progress|timeout|stuck|couldn't|can't");

    /** A failed wood prerequisite may be answered with the next option (never the owner's stop or danger). */
    public static boolean switchable(String end) {
        if (end == null || Options.ownerOrDanger(end)) return false;
        return FailMemory.classify(end) != null || WOOD_END.matcher(end.toLowerCase(Locale.ROOT)).find();
    }

    /** True for a prerequisite command this set made ("goto x y z then cut N"). */
    public static boolean isWoodCommand(String c) { return c != null && c.startsWith("goto ") && c.contains(" then cut "); }
}
