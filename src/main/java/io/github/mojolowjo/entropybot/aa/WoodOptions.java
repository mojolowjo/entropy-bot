package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.brain.Site;
import io.github.mojolowjo.entropybot.aa.moves.Step;
import io.github.mojolowjo.entropybot.aa.brain.Failures;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * aa-trees: the wood option set. Logs are a priced prerequisite with the ways the bot has seen:
 * <ul>
 * <li><b>cut</b>: nothing seen yet - Baritone's own tree mining (its MineProcess on the log blocks it knows), one priced try.</li>
 * <li><b>chop at</b>: walk to the nearest seen tree, then Baritone's tree mining there; priced with the walk there and
 *     back, up to {@value #FAR_MAX} away.</li>
 * </ul>
 * rm-execution: each option is typed steps ({@link Step.Walk}, {@link Step.ChopTree}), run by {@code aa/moves/Steps};
 * no chain text. Trees come from {@code sense/Sightings} (kind TREE: a log column on dirt with leaves, seen twice; fair
 * play: a tree not seen is not an option). The world is open: a tree is refused only by the policy (keep-out / no-break)
 * or a protected build near it, dropped with its reason; a failed option is held per tree (Options.RETRY_MS, 10 min), so
 * a prerequisite that fails never loops: the next tree, or "no option" with every reason. Unit: walk-blocks (1 s of
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

    /** The steps of "chop at" a seen tree: the walk there, then Baritone's tree mining. */
    public static List<Step> chopSteps(Tree t, int need) {
        return List.of(new Step.Walk(t.x(), t.y(), t.z(), (int) io.github.mojolowjo.entropybot.aa.brain.Tuning.get("steps.walkNear")),
                new Step.ChopTree(new int[]{t.x(), t.y(), t.z()}, need, null));
    }

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
            rows.add(Options.typed("chop at " + t.key(), "wood", chopSteps(t, need),
                    2 * t.dist() + CUT_LOG * need, "the seen tree " + Math.round(t.dist()) + " away, the walk there and back counted", chopKey, m)
                    .withSite(Site.at(t.x(), t.y(), t.z())));
            chop = true;
            break;
        }
        if (!chop) {
            // nothing seen (or every seen tree refused/held): Baritone's tree mining on the logs it knows is one priced try
            String key = "cut here";
            if (m.blocked(key, now)) dropped.add("cut: " + m.reason(key) + " (no tree seen yet; the sightings fill as it walks)");
            else rows.add(Options.typed("cut", "wood", List.of(new Step.ChopTree(null, need, null)), SEARCH + CUT_LOG * need,
                    "no tree seen yet: Baritone mines the logs it knows", key, m));
        }
        return Options.Choice.of(rows, dropped, "no way to wood");
    }

    private static final Pattern WOOD_END = Pattern.compile("no trees|no log|chopped nothing|couldn't get at its trunk|no path|no progress|timed out|timeout|stuck|couldn't|can't");

    /** A failed wood prerequisite may be answered with the next option (never the owner's stop or danger). */
    public static boolean switchable(String end) {
        if (end == null || Options.ownerOrDanger(end)) return false;
        return Failures.classify(end) != null || WOOD_END.matcher(end.toLowerCase(Locale.ROOT)).find();
    }
}
