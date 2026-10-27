package io.github.mojolowjo.entropybot.aa.moves;

import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalYLevel;

/**
 * Reflex choosers: the reflexes' goals say only what to achieve; Baritone's search picks the cell. Breathe = the head in
 * open air (the surface or an air pocket, whichever Baritone reaches); Water = the feet in water (the fire reflex's
 * "to water"). No mod-picked cell, no scan for one.
 *
 * <p>Loader notes: Baritone API {@link Goal} only. {@link Cells} reads the client level; Baritone calls a goal from its
 * path thread (it reads the client chunks from there itself too), so the reader only calls {@code getBlockState} /
 * {@code getFluidState}. Pure parts (the tests): {@link #breathes} and {@link #wet} over a {@link Cells}; the climb's
 * {@link #digLevelTicks} over Baritone's API {@code ActionCosts} (JUnit: Z77ClimbDigUpTest).
 */
public final class ReflexGoals {
    /** The world as the goals see it. */
    public interface Cells {
        /** No collision box (air, water, plants). */
        boolean open(int x, int y, int z);

        /** Water in the cell (a source, a flow or waterlogged). */
        boolean water(int x, int y, int z);
    }

    /** The feet at y can breathe: feet and head cells open, the head out of water. */
    public static boolean breathes(Cells c, int x, int y, int z) {
        return c.open(x, y, z) && c.open(x, y + 1, z) && !c.water(x, y + 1, z);
    }

    /** The feet are in water. */
    public static boolean wet(Cells c, int x, int y, int z) {
        return c.water(x, y, z) && c.open(x, y, z);
    }

    /** Head in open air. The heuristic pulls up toward {@code upTo} (Baritone's own Y-level cost), so the search tries up first. */
    public static final class Breathe implements Goal {
        private final Cells cells;
        private final int upTo;

        public Breathe(Cells cells, int upTo) {
            this.cells = cells;
            this.upTo = upTo;
        }

        @Override public boolean isInGoal(int x, int y, int z) { return breathes(cells, x, y, z); }

        @Override public double heuristic(int x, int y, int z) { return y >= upTo ? 0 : GoalYLevel.calculate(upTo, y); }

        @Override public String toString() { return "Breathe (head in open air, pull up to y " + upTo + ")"; }
    }

    /** Feet in water: Baritone finds the water near itself. Heuristic 0 (a plain cost search). */
    public static final class Water implements Goal {
        private final Cells cells;

        public Water(Cells cells) { this.cells = cells; }

        @Override public boolean isInGoal(int x, int y, int z) { return wet(cells, x, y, z); }

        @Override public double heuristic(int x, int y, int z) { return 0; }

        @Override public String toString() { return "Water (feet in water)"; }
    }

    /** The world as the climb's goal sees it: a cell with no collision and no fluid, and the sky seen from a cell. */
    public interface SkyCells {
        boolean open(int x, int y, int z);

        boolean sky(int x, int y, int z);
    }

    /** 0.39.z77 round fix (run BI): the feet at y are out: feet and head open, the head under open sky. */
    public static boolean underSky(SkyCells c, int x, int y, int z) {
        return c.open(x, y, z) && c.open(x, y + 1, z) && c.sky(x, y + 1, z);
    }

    /**
     * 0.39.z77 round fix (run BI 15:51 / 22:41: the climb ran deleted owner verbs, "goto" and "dig"; rule 2): the climb's one
     * goal - the feet under open sky, anywhere; the heuristic pulls up toward {@code upTo}, so the search digs and pillars up
     * first. Baritone picks the cells, the digs and the placements.
     *
     * <p>0.39.z77 climb (run CM 17:36-17:43, "Baritone's climb goal ended at -45 -9 -46 with 59 blocks over the feet" 0.5 s
     * after the goal was set, five times): the heuristic was Baritone's Y-level cost, 3.17 ticks a level, while a dug level
     * costs ~300-450 here (deepslate 24.5 ticks a cell x the policy's travel 6, two or three cells a stair level). A* keeps a
     * partial path only for a node whose heuristic + cost / coefficient (1.5..10) beats the start's, so no node ever did and
     * the search ended with no path. Now each level is priced at {@code levelTicks} ({@link #digLevelTicks}: the column's own
     * dearest dug level), so a level dug up is progress and Baritone's partial paths go up segment by segment.
     */
    public static final class UnderSky implements Goal {
        private final SkyCells cells;
        private final int upTo;
        private final double levelTicks;

        /** {@code levelTicks}: the ticks of one dug level up here ({@link #digLevelTicks}); never below Baritone's own jump. */
        public UnderSky(SkyCells cells, int upTo, double levelTicks) {
            this.cells = cells;
            this.upTo = upTo;
            this.levelTicks = Math.max(GoalYLevel.calculate(1, 0), levelTicks);
        }

        @Override public boolean isInGoal(int x, int y, int z) { return underSky(cells, x, y, z); }

        @Override public double heuristic(int x, int y, int z) { return y >= upTo ? 0 : (upTo - y) * levelTicks; }

        public double levelTicks() { return levelTicks; }

        @Override public String toString() { return "UnderSky (feet under open sky, pull up to y " + upTo + ", " + Math.round(levelTicks) + " ticks a level)"; }
    }

    /**
     * structural: a staircase level (Baritone's MovementAscend) breaks at most three cells - the step's feet and head cells
     * and the cell over the bot's own head; a pillar level breaks one and places one.
     */
    static final int STAIR_CELLS = 3; // structural: Baritone's ascend geometry

    /**
     * 0.39.z77 climb (pure): the ticks of one level dug up through cells that cost at most {@code dearestCellTicks} each as
     * Baritone prices a break (its mining ticks + blockBreakAdditionalPenalty, times the cost hook's multiplier): a staircase
     * level's walk and jump plus {@link #STAIR_CELLS} such cells - at least what any one level up costs Baritone there, so a
     * node a level higher always beats the start at Baritone's first coefficient (1.5). 0 or less: no solid cell over the
     * feet (Baritone's walk and jump only).
     */
    public static double digLevelTicks(double dearestCellTicks) {
        return baritone.api.pathing.movement.ActionCosts.JUMP_ONE_BLOCK_COST + baritone.api.pathing.movement.ActionCosts.WALK_ONE_BLOCK_COST
                + STAIR_CELLS * Math.max(0, dearestCellTicks);
    }

    /**
     * 0.39.z77 shape review 1 (rule 15): the climb's end from the feet's {@code depth} under ground (-1: the surface over the
     * column not known) and whether Baritone's goal still runs: null - climbing on; "ok: ..." - out (a KNOWN surface, 0
     * over the feet); else the failure (Baritone's goal ended under ground, or where the surface is not known - never "out").
     */
    public static String climbEnd(int depth, boolean active, String at) {
        if (depth == 0) return "ok: on the surface at " + at + " (under open sky)";
        if (active) return null;
        return depth < 0 ? "Baritone's climb goal ended at " + at + " where the surface is not known (rule 15: not known is never out)"
                : "Baritone's climb goal ended at " + at + " with " + depth + " blocks over the feet";
    }

    private ReflexGoals() { }
}
