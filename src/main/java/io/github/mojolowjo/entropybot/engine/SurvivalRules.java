package io.github.mojolowjo.entropybot.engine;

import java.util.Set;

/**
 * 0.23.6 survival reflexes, the decision tables (pure, JUnit): fire, the bad effects (poison, wither, hunger) and the
 * fight potion. The game side is {@link Survival}.
 *
 * <p>Fire: out of a burning cell or lava first, then a carried water bucket at the feet (only where placing is allowed),
 * then a fire resistance potion, then the nearest water within 8, else keep moving away. Effects: a milk bucket clears
 * poison, wither and hunger; without one it is noted (check suggests {@code need milk_bucket 1}); weakness and slowness
 * are only logged. In a fight under 6 health a healing or regeneration potion is drunk, one per 30 s.
 *
 * <p>Loader notes: none.
 */
public final class SurvivalRules {
    private SurvivalRules() {}

    public enum Fire { NONE, STEP_OUT, PLACE_WATER, DRINK_FIRE_RES, WALK_WATER, MOVE_AWAY }

    public enum Effect { NONE, DRINK_MILK, NOTE_NO_MILK, LOG, DRINK_HEAL }

    public static final Set<String> MILK_CURES = Set.of("poison", "wither", "hunger");
    public static final Set<String> LOG_ONLY = Set.of("weakness", "slowness", "mining_fatigue");
    public static final float HEAL_BELOW = 6;
    public static final long HEAL_EVERY_TICKS = 600;
    public static final int WATER_R = 8;

    /**
     * onFire: fire ticks above 0; resistant: fire resistance active; inHazard: the feet or head cell is fire or lava;
     * bucket: a water bucket carried; mayPlace: the guard allows water at the feet (no safe area, no container near, not
     * the Nether); water: water within {@link #WATER_R} on the grid; potion: a fire resistance potion carried.
     */
    public static Fire fire(boolean onFire, boolean resistant, boolean inHazard, boolean bucket, boolean mayPlace, boolean water, boolean potion) {
        if (inHazard && !resistant) return Fire.STEP_OUT;
        if (!onFire || resistant) return Fire.NONE;
        if (bucket && mayPlace) return Fire.PLACE_WATER;
        if (potion) return Fire.DRINK_FIRE_RES;
        if (water) return Fire.WALK_WATER;
        return Fire.MOVE_AWAY;
    }

    /**
     * effects: the active effect paths ("poison"); milk: a milk bucket carried; health, fighting; healPotion: a healing or
     * regeneration potion carried; sinceHeal: ticks since the last one was drunk.
     */
    public static Effect effects(Set<String> effects, boolean milk, float health, boolean fighting, boolean healPotion, long sinceHeal) {
        if (fighting && health < HEAL_BELOW && healPotion && sinceHeal >= HEAL_EVERY_TICKS && !effects.contains("regeneration")) return Effect.DRINK_HEAL;
        for (String e : effects) {
            if (MILK_CURES.contains(e)) return milk ? Effect.DRINK_MILK : Effect.NOTE_NO_MILK;
        }
        for (String e : effects) if (LOG_ONLY.contains(e)) return Effect.LOG;
        return Effect.NONE;
    }

    /** Placing water at the feet: never in the Nether, never next to a container (r 2), never where the guard says no. */
    public static boolean mayPlaceWater(boolean guardAllows, boolean nether, boolean containerNear, boolean feetFree) {
        return guardAllows && !nether && !containerNear && feetFree;
    }
}
