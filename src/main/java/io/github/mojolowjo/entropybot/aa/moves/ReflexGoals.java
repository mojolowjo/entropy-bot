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
 * {@code getFluidState}. Pure parts (the tests): {@link #breathes} and {@link #wet} over a {@link Cells}.
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
     * goal - the feet under open sky, anywhere; the heuristic pulls up toward {@code upTo} (Baritone's own Y-level cost), so the
     * search digs and pillars up first. Baritone picks the cells, the digs and the placements.
     */
    public static final class UnderSky implements Goal {
        private final SkyCells cells;
        private final int upTo;

        public UnderSky(SkyCells cells, int upTo) {
            this.cells = cells;
            this.upTo = upTo;
        }

        @Override public boolean isInGoal(int x, int y, int z) { return underSky(cells, x, y, z); }

        @Override public double heuristic(int x, int y, int z) { return y >= upTo ? 0 : GoalYLevel.calculate(upTo, y); }

        @Override public String toString() { return "UnderSky (feet under open sky, pull up to y " + upTo + ")"; }
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
