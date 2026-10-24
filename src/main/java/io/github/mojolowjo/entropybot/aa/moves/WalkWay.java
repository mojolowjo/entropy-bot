package io.github.mojolowjo.entropybot.aa.moves;

import io.github.mojolowjo.entropybot.aa.brain.Tuning;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 0.39.z38 (owner: walking never places; docs/SPECIAL_FEATURES.md "Placement penalty per run"): a plain walk runs with
 * placing off (the fork's walk lease). When Baritone ends it "no path without placing", the walk's target is noted here
 * with the blocks it would have to place (at least the climb from where it stopped) and the step's path fact re-plans the
 * order (z30); an option that walks there then prices the placing walk as a dearer way: walk seconds + Tuning
 * {@code walk.placeS} per block. Never a silent fallback: a plain walk is never turned into a placing one here. Pure.
 */
public final class WalkWay {
    private WalkWay() {}

    /** The fork's walk end for a plain walk that found no path with placing off (ICustomGoalProcess.lastEnd). */
    public static final String NO_PATH_PLAIN = "no path without placing";

    /** The placement penalty: seconds one placed block of a walk costs (Tuning walk.placeS, the one knob). */
    public static double placeS() { return Tuning.get("walk.placeS"); }

    /** A walk's price: its seconds, plus the penalty for each block it places (0 for a plain walk). */
    public static double priceS(double walkS, int placed) { return walkS + Math.max(0, placed) * placeS(); }

    /** The blocks a placing walk is priced with: the climb from {@code feetY} to {@code targetY}, at least one. */
    public static int blocks(int feetY, int targetY) { return Math.max(1, targetY - feetY); }  // structural: placing at all is one block

    private record Noted(int blocks, long at) {}

    private static final Map<String, Noted> NO_PLAIN = new ConcurrentHashMap<>();

    private static String key(int x, int y, int z) { return x + " " + y + " " + z; }

    /** Baritone ended a plain walk to x y z "no path without placing" with the feet at {@code feetY}. */
    public static void noPlainPath(int x, int y, int z, int feetY, long now) {
        NO_PLAIN.put(key(x, y, z), new Noted(blocks(feetY, y), now));
    }

    /** The blocks a walk to x y z must place (a plain walk there found no path within Tuning walk.noPathMemoryS), else 0. */
    public static int mustPlace(int x, int y, int z, long now) {
        Noted n = NO_PLAIN.get(key(x, y, z));
        if (n == null) return 0;
        if (now - n.at() > (long) (1000 * Tuning.get("walk.noPathMemoryS"))) {
            NO_PLAIN.remove(key(x, y, z));
            return 0;
        }
        return n.blocks();
    }

    public static void clear() { NO_PLAIN.clear(); }

    /** The way to walk to x y z: the plain walk, or (after a plain walk there found no path) the placing walk. */
    public static Step.Walk step(int x, int y, int z, int near, long now) {
        return new Step.Walk(x, y, z, near, mustPlace(x, y, z, now) > 0);
    }

    /** The price of {@link #step}: the walk's seconds, plus the penalty when it must place. */
    public static double stepPriceS(int x, int y, int z, double walkS, long now) {
        return priceS(walkS, mustPlace(x, y, z, now));
    }
}
