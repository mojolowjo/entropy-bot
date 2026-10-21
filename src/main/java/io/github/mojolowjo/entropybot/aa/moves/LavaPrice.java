package io.github.mojolowjo.entropybot.aa.moves;

import io.github.mojolowjo.entropybot.aa.DangerOptions;
import io.github.mojolowjo.entropybot.aa.brain.Tuning;

/**
 * 0.39.s6 review (RULES row 3, one unit from the game): what one lava cell of a reflex's wade costs, in seconds, then in
 * Baritone ticks for the fork's {@code lavaCellCost}. Seconds in the cell (Baritone's own water crossing time, the
 * nearest game number for a fluid cell: {@code ActionCosts.WALK_ONE_IN_WATER_COST} ticks) x the game's lava damage
 * ({@code game.lavaHpPerS}: 4 hp a hit, a hit every 10 ticks) x the seconds a health point is worth at this health
 * (DangerOptions' hp curve, the danger desire's own price). Pure (JUnit: LavaPriceTest). Loader notes: none.
 */
public final class LavaPrice {
    private LavaPrice() { }

    /** Ticks one cell takes to wade (Baritone's in-fluid walk cost). */
    static final double CELL_TICKS = baritone.api.pathing.movement.ActionCosts.WALK_ONE_IN_WATER_COST;

    /** Seconds of loss one lava cell costs at this health. */
    public static double cellS(double health, double maxHealth) {
        double inCellS = CELL_TICKS / 20.0;
        return inCellS * Tuning.get("game.lavaHpPerS") * DangerOptions.hpS(health, maxHealth);
    }

    /** The same in Baritone ticks (its cost unit), for {@code Settings.lavaCellCost}. */
    public static double cellTicks(double health, double maxHealth) {
        return 20 * cellS(health, maxHealth);
    }
}
