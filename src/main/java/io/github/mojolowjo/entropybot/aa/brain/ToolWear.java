package io.github.mojolowjo.entropybot.aa.brain;

import io.github.mojolowjo.entropybot.aa.core.Inputs;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Director 2026-10-19 (live aa388: a 114-block staircase dug through stone by hand with a stone pickaxe at 29/131):
 * tool wear is a cost, not a rule. No "keep a worn tool" rule anywhere; at a dig-heavy step (the climb's staircase, a
 * dig to a buried ore, a strip tunnel) the ways are priced in seconds and the cheapest wins:
 * <ul>
 * <li>USE: the blocks with the pickaxe I have (dig time from its real break speed) + wear = blocks / uses left x
 *     replacement cost; blocks past its uses are dug by hand.</li>
 * <li>CRAFT: a new stone pickaxe first ({@link #CRAFT_S} with 3 cobblestone, 2 sticks or planks and a table), then
 *     the blocks with it (its own wear included).</li>
 * <li>HAND: every block by hand.</li>
 * </ul>
 * Replacement cost: the seconds to craft a new one from the bag (with nothing to make one, {@link #GATHER_S} to gather
 * the materials too), at most what the tool's uses
 * left are worth, the hand time they save ({@code left x (hand - tool)}), so USE never loses to HAND while a pickaxe
 * is in the bag: hand only wins with no pickaxe and nothing to make one. Dig time per block is the game's
 * ({@link GameTimes#breakSeconds}: {@code ticks = ceil(hardness x (correct ? 30 : 100) / speed)}; stone (1.5) by hand
 * 7.5 s, stone pickaxe 0.6 s). aa-times: the kit carries the seconds the adapter computed with the game's own terms
 * (Efficiency, Haste, water, off the ground) when it read them ({@link Kit#toolS}, {@link Kit#handS}); the plain formula
 * stands in for tests and a kit built from a speed alone.
 * Pure (JUnit ToolWearTest). Loader notes: none (the adapter reads the speed with vanilla
 * {@code ItemStack.getDestroySpeed}; GameTimesLive the full terms).
 */
public final class ToolWear {
    private ToolWear() {}

    public static final double STONE_HARDNESS = 1.5, CRAFT_S = 10, INF = Double.POSITIVE_INFINITY;
    /** Review: with nothing to make one, a new pickaxe means gathering its materials first (~2 min), capped by its worth. */
    public static final double GATHER_S = 120;
    /** A stone pickaxe's break speed and uses (the one CRAFT makes). */
    public static final float STONE_SPEED = 4f;
    public static final int STONE_USES = 131;
    /** Blocks a staircase digs per level up (the climb's 3-high headroom). */
    public static final int STAIR_BLOCKS_PER_UP = 3;

    public enum Way { USE, CRAFT, HAND }

    /**
     * What the bag holds for digging stone.
     *
     * @param speed  the best pickaxe's break speed on stone (0 = no pickaxe)
     * @param left   its uses left (all pickaxes together)
     * @param craftS seconds to make a new stone pickaxe from the bag, {@link #INF} with no materials or table
     * @param toolS  aa-times: the game's seconds per stone block with that pickaxe (the adapter's terms), NaN = from the speed
     * @param handS  the game's seconds per stone block by hand (the adapter's terms), NaN = the plain formula
     */
    public record Kit(float speed, int left, double craftS, double toolS, double handS) {
        public Kit(float speed, int left, double craftS) { this(speed, left, craftS, Double.NaN, Double.NaN); }

        public boolean pickaxe() { return speed > 1 && left > 0; }

        /** Seconds per stone block with the pickaxe: the adapter's number, else the plain formula on {@link #speed}. */
        public double toolSeconds() { return toolS > 0 && Double.isFinite(toolS) ? toolS : withTool(speed); }

        /** Seconds per stone block by hand: the adapter's number, else the plain formula. */
        public double handSeconds() { return handS > 0 && Double.isFinite(handS) ? handS : hand(); }
    }

    /** One priced way with its terms (aa why / logs). */
    public record Priced(Way way, double seconds, String terms) {
        public String text() { return way.name().toLowerCase(Locale.ROOT) + " " + Math.round(seconds) + "s (" + terms + ")"; }
    }

    /** Seconds to break one block (the game's rule: 20 ticks a second; at least one tick). */
    public static double blockSeconds(double hardness, double speed, boolean correctTool) {
        if (hardness <= 0) return 0.05;
        return GameTimes.breakSeconds(hardness, Math.max(0.01, speed), correctTool);
    }

    public static double hand() { return blockSeconds(STONE_HARDNESS, 1, false); }

    public static double withTool(double speed) { return blockSeconds(STONE_HARDNESS, speed, true); }

    /**
     * The kit from the AA inputs (pickSpeed, pickLeft; materials: 3 cobblestone, 2 sticks or 2 planks; a table). aa-times:
     * the game's seconds per stone block ({@code Inputs.digStoneS}, {@code handStoneS}, read by SenseRuntime through
     * GameTimesLive) ride along when they were read.
     */
    public static Kit kit(Inputs in) {
        int sticks = in.bag == null ? 0 : in.bag.getOrDefault("stick", 0);
        boolean mats = in.cobble >= 3 && (sticks >= 2 || in.planks >= 2);
        boolean table = in.tableInBag || in.tableKnown;
        return new Kit(in.pickaxes > 0 ? in.pickSpeed : 0, in.pickaxes > 0 ? in.pickLeft : 0, mats && table ? CRAFT_S : INF,
                in.pickaxes > 0 ? in.digStoneS : Double.NaN, in.handStoneS);
    }

    /** Every way for {@code blocks} of stone, cheapest first (ties: USE, CRAFT, HAND). */
    public static List<Priced> price(int blocks, Kit k) {
        int n = Math.max(0, blocks);
        double hand = k.handSeconds();
        List<Priced> out = new ArrayList<>();
        if (k.pickaxe()) {
            double tool = k.toolSeconds();
            int uses = Math.min(n, k.left());
            double worth = k.left() * Math.max(0, hand - tool);          // the hand time its uses left save
            double replace = Math.min(k.craftS() < INF ? k.craftS() : GATHER_S, worth);
            double wear = k.left() <= 0 ? 0 : uses / (double) k.left() * replace;
            double s = uses * tool + (n - uses) * hand + wear;
            out.add(new Priced(Way.USE, s, uses + " x " + r2(tool) + "s" + (n > uses ? " + " + (n - uses) + " by hand x " + r2(hand) + "s" : "")
                    + " + wear " + uses + "/" + k.left() + " x " + (replace < worth ? (k.craftS() < INF ? "craft " : "gather and craft ") + Math.round(replace) + "s" : "its worth " + Math.round(worth) + "s")
                    + " = " + Math.round(wear) + "s"));
        }
        if (k.craftS() < INF) {
            double tool = withTool(STONE_SPEED);
            int uses = Math.min(n, STONE_USES);
            double wear = uses / (double) STONE_USES * k.craftS();
            double s = k.craftS() + uses * tool + (n - uses) * hand + wear;
            out.add(new Priced(Way.CRAFT, s, "craft " + Math.round(k.craftS()) + "s + " + uses + " x " + r2(tool) + "s + wear " + Math.round(wear) + "s"));
        }
        out.add(new Priced(Way.HAND, n * hand, n + " x " + r2(hand) + "s by hand"));
        out.sort((a, b) -> Double.compare(a.seconds(), b.seconds()));      // stable: ties keep USE, CRAFT, HAND
        return out;
    }

    /** The cheapest way. */
    public static Priced best(int blocks, Kit k) { return price(blocks, k).get(0); }

    /** "use 44s (...); then craft 54s, hand 555s" for aa why and the logs. */
    public static String text(int blocks, Kit k) {
        List<Priced> p = price(blocks, k);
        StringBuilder b = new StringBuilder(blocks + " blocks: ").append(p.get(0).text());
        for (int i = 1; i < p.size(); i++) b.append(i == 1 ? "; then " : ", ").append(p.get(i).way().name().toLowerCase(Locale.ROOT)).append(' ').append(Math.round(p.get(i).seconds())).append('s');
        return b.toString();
    }

    /** The command prefix for a way: CRAFT makes the pickaxe first. */
    public static String prefix(Way w) { return w == Way.CRAFT ? "craft stone_pickaxe 1 then " : ""; }

    private static String r2(double v) { return String.valueOf(Math.round(v * 100) / 100.0); }
}
