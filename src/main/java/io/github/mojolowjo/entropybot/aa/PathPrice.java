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
    record Walk(double seconds, double lavaS, int pickBreaks, java.util.Map<String, Integer> dug) {
        public Walk {
            dug = dug == null ? java.util.Map.of() : java.util.Map.copyOf(dug);
        }
        public Walk(double seconds) { this(seconds, 0); }
        public Walk(double seconds, double lavaS) { this(seconds, lavaS, 0); }
        public Walk(double seconds, double lavaS, int pickBreaks) { this(seconds, lavaS, pickBreaks, null); }
        /*
         * 0.39.z69 item 1 (run AA 11:39:48: "to y 5..31" dug stone by hand from y 62 to 31 for 5 min); review (d): dug - the
         * pickBreaks blocks by kind (block id -> count), what the chain prices their breaks by hand and with a pickaxe from
         * (the game's break formula with each kind's hardness and harvest tier); empty: not counted.
         */
        /**
         * 0.39.z62 item 2 (run S: the stone pickaxe broke after the dug shaft from y 64 to 16 plus 46 strip blocks): the
         * blocks on the path's cells (feet and head height) that the game mines with a pickaxe, at the landing - the walk's
         * wear on the pickaxe (rough, rule 14: one use a block); 0: none or not counted.
         */
        public int pickBreaks() { return Math.max(0, pickBreaks); }
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

    /**
     * 0.39.z76 J3-mod item 2 (rule 18 amended: rank rough, walk exact): Baritone's own estimate of the walk from the feet to
     * stand by the block at x y z - its goal heuristic (the fork's IPathEstimate), NO search, in seconds - for ranking ways
     * before they are priced; never a price (no wall, cliff, break or place is in it). NaN: not known (no estimate here).
     */
    default double estimate(int x, int y, int z) { return Double.NaN; }

    /**
     * 0.39.z76 J3-mod item 2: the chain ranked the walk to x y z out of the ways it prices (pathCost.pricedTop) - it was not
     * asked; the live provider withdraws a pending ask of it that nobody asked again (IPricedPath.cancel). Game thread.
     */
    default void rankedOut(int x, int y, int z) {}

    /**
     * 0.39.z76 send-back 2 (run AN 23:16:18 "to y 56..56: Baritone's goal ended at y 57"; 13x in AK, 32x in AL): the walk
     * whose feet end IN the cell x y z (a band walk: the cell is inside the band), not by it - the stand-by goal is reached
     * from the cell above (and two below), outside a one-high band. The live provider prices and walks it with Baritone's
     * goal of that exact cell; a fake without it answers as {@link #price}.
     */
    default Answer priceInto(int x, int y, int z, double dist) { return price(x, y, z, dist); }

    /** A complete walk now, else null (callers that only compare known walks: the deposit). */
    default Walk toBlock(int x, int y, int z) { return price(x, y, z, Double.NaN).walk(); }

    /** A computation is in flight (an order whose chain waits on it is held, not parked). */
    default boolean pending() { return false; }

    /** Bumped each time an answer lands (the running order re-prices at its next step). */
    default long landed() { return 0; }

    /**
     * 0.39.z60 review 2: first answers (walks not answered yet in this order) computing or queued now; -1: not known (no
     * fork count). The chain charges the first-answer wait only while some are pending (NeedChain.pathsExplore).
     */
    default int firstAnswersPending() { return -1; }

    /** Milliseconds one background search may run (Baritone's executor, not the game thread). */
    static long fullMaxMs() { return (long) Tuning.get("pathCost.fullMaxMs"); }

    /** A* nodes one background search may open. */
    static int maxNodes() { return Tuning.i("pathCost.maxNodes"); }

    /** The walk past which A* stops (its cost ceiling), in seconds. */
    static double budgetS() { return Tuning.get("pathCost.budgetS"); }

    /** 0.39.z76 J3-mod item 2: the known sources of one need that get a full priced path (Tuning pathCost.pricedTop). */
    static int pricedTop() { return Math.max(1, Tuning.i("pathCost.pricedTop")); }

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

    /** 0.39.z58 (run Q 02:29:32): search slots only the running step's walk may use. */
    static int reserveForRunning() { return Tuning.i("pathCost.reserveForRunning"); }

    /** 0.39.z58 (run Q 02:28-02:35): seconds before a re-search when the bot moved off the goal's answer. */
    static double researchMinS() { return Tuning.get("pathCost.researchMinS"); }

    /** 0.39.z58: seconds a queued ask stays in the fork's queue without being asked again. */
    static double queueKeepS() { return Tuning.get("pathCost.queueKeepS"); }
}
