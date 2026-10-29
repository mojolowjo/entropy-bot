package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.brain.Tuning;


import java.util.List;

/**
 * aa-trees, one-planner: the trees the bot has seen (sense/Sightings kind TREE: a log column on dirt with leaves, seen
 * twice), the fallback behind Baritone's scan for the chain's "a tree known" (AaRuntime.treeSighting), and the tuned cut
 * of one log when the block's hardness is not known. The wood option set's rows (chop at, cut) went: logs are an item
 * like any other in the one chain (NeedChain: a known tree, the walk and the breaks; none known: explore).
 * Pure. Loader notes: none (the facts come from {@code AaRuntime.woodFacts}).
 */
public final class WoodOptions {
    private WoodOptions() {}

    public static double cutLog() { return Tuning.get("woodOptions.cutLog"); }
    /** The farthest seen tree that counts as known (the sightings' reach). */
    public static int farMax() { return Tuning.i("woodOptions.farMax"); }

    /**
     * @param deny the policy's or a protect box's reason against cutting there (null: allowed)
     * @param log  FV fix: the wood the tree was seen with (its lowest log's block id; Sightings.treeWood), null: not read -
     *             the tree then serves any log kind until its chunk loads (rule 15)
     */
    public record Tree(int x, int y, int z, double dist, String deny, String log) {
        public Tree(int x, int y, int z, double dist, String deny) { this(x, y, z, dist, deny, null); }
        public String key() { return x + " " + y + " " + z; }
    }

    /**
     * @param need  logs wanted
     * @param trees the seen trees, nearest first (exhausted ones already left out)
     */
    public record WoodFacts(int need, List<Tree> trees) {}
}