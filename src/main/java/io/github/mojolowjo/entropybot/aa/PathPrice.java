package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.brain.Tuning;

/**
 * path-cost (BARITONE_HANDOFF channel 3): the walk price of a chain's trip, asked of Baritone's own A* before walking.
 * 0.39.z41 (owner 2026-10-23: "math the actual path it will take ... A path that is not known, or whose chunks are not
 * loaded, is 'path not known', not a number"): a walk's price is Baritone's COMPLETE path cost or it is not a price. The
 * straight line and the partial "about" estimate are gone; a walk whose path is not known yet ({@link Kind#COMPUTING}),
 * whose search met unloaded chunks ({@link Kind#CHUNKS}), that Baritone found unreachable ({@link Kind#UNREACHABLE}) or
 * that has no answer ({@link Kind#NONE}) cannot be picked. The live one is {@code commands/LivePathPrice} (the full search
 * in the background); tests use a fake. Pure. Loader notes: none.
 */
public interface PathPrice {

    /** A complete walk: seconds of Baritone's path (through {@link TickPrice}), its lava part in seconds. */
    record Walk(double seconds, double lavaS) {
        public Walk(double seconds) { this(seconds, 0); }
    }

    /** What an answer is: a price, or why there is none (rule 15: loaded / empty / not loaded). */
    enum Kind { PRICED, COMPUTING, CHUNKS, UNREACHABLE, NONE }

    /** One ask's answer: the walk (PRICED only) or the kind and Baritone's / the provider's words. */
    record Answer(Walk walk, Kind kind, String reason) {
        public static Answer priced(Walk w) { return new Answer(w, Kind.PRICED, null); }
        public static Answer computing(String why) { return new Answer(null, Kind.COMPUTING, why); }
        public static Answer chunks(String why) { return new Answer(null, Kind.CHUNKS, why); }
        public static Answer unreachable(String why) { return new Answer(null, Kind.UNREACHABLE, why); }
        public static Answer none(String why) { return new Answer(null, Kind.NONE, why); }
    }

    /** The start of every not-a-price text a way fails with (aa why / aa debug; {@link NeedChain.Chain#waiting}). */
    String NOT_KNOWN = "walk: path not known";

    /**
     * The walk from the bot's feet to stand by the block at x y z. {@code dist}: the straight metres the chain knows (said
     * in aa debug; the live provider does not use it, a test fake may). Game thread for the live one.
     */
    Answer price(int x, int y, int z, double dist);

    /** A complete walk now, else null (callers that only compare known walks: the deposit). */
    default Walk toBlock(int x, int y, int z) { return price(x, y, z, Double.NaN).walk(); }

    /** A computation is in flight (an order whose chain waits on it is held, not parked). */
    default boolean pending() { return false; }

    /** Bumped each time an answer lands (the running order re-prices at its next step). */
    default long landed() { return 0; }

    /** Milliseconds one background search may run (Baritone's executor, not the game thread). */
    static long fullMaxMs() { return (long) Tuning.get("pathCost.fullMaxMs"); }

    /** A* nodes one background search may open. */
    static int maxNodes() { return Tuning.i("pathCost.maxNodes"); }

    /** The walk past which A* stops (its cost ceiling), in seconds. */
    static double budgetS() { return Tuning.get("pathCost.budgetS"); }

    /** Searches in flight at once; more asks wait for the next pass. */
    static int maxPending() { return Tuning.i("pathCost.maxPending"); }

    /** A cached answer is kept while the bot stands within this many blocks of where it was asked from. */
    static double reuseBlocks() { return Tuning.get("pathCost.reuseBlocks"); }

    /** A cached answer is kept at most this many seconds (z57: the z41 route, pricedPath.on 0, only). */
    static double keepS() { return Tuning.get("pathCost.keepS"); }

    /**
     * 0.39.z57 (owner 2026-10-25: "while doing stuff it should not forget the path that was already planned"): a complete
     * priced path's safety age in seconds; the fork's path memory drops it earlier on the facts it depends on.
     */
    static double maxKeepS() { return Tuning.get("pathCost.maxKeepS"); }
    /** 0.39.z46: the fork's priced path prices and walks (one search); off: z41's route (search once, walk fresh). */
    static boolean pricedPathOn() { return Tuning.i("pricedPath.on") != 0; }

    /** 0.39.z46: game ticks the fork keeps a priced path for the walk. */
    static int pricedKeepTicks() { return Tuning.i("pricedPath.keepTicks"); }

    /** 0.39.z46: Baritone path segments one price may chain at most. */
    static int pricedMaxSegments() { return Tuning.i("pricedPath.maxSegments"); }
}
