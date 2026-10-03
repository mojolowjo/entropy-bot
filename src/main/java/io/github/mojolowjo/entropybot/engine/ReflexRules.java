package io.github.mojolowjo.entropybot.engine;

import java.util.Set;

/**
 * The reflexes' rules without Minecraft types, so JUnit can pin them (docs/BOT_PLAN.md 5.8, the owner's
 * answers of 2026-10-01): when to eat, which food, how far to look for monsters, when to run.
 */
public final class ReflexRules {
    private ReflexRules() {}

    /** Never eaten: they poison, cause hunger, or teleport. */
    public static final Set<String> FOOD_BLACKLIST = Set.of(
            "minecraft:rotten_flesh", "minecraft:spider_eye", "minecraft:chorus_fruit", "minecraft:pufferfish",
            "minecraft:poisonous_potato", "minecraft:suspicious_stew");
    /** Only eaten below 6 hearts. */
    public static final Set<String> GOLDEN = Set.of("minecraft:golden_apple", "minecraft:enchanted_golden_apple");

    public static final int EAT_FOOD = 14;          // eat at this food level or below
    public static final int EAT_FOOD_HURT = 18;     // ... or below this while hurt (natural healing needs 18)
    public static final float GOLDEN_BELOW = 12f;   // health under 6 hearts
    public static final float RETREAT_AT = 6f;      // health at or below: get away
    public static final int LOOK = 8;               // monsters within this many blocks count
    public static final int LOOK_HURT = 24;         // ... or this many after a hit
    public static final int HURT_TICKS = 100;       // "after a hit" lasts this long
    public static final double REACH = 2.8;         // attack from this close
    public static final double CREEPER_RUN = 6;     // run from a creeper this close
    public static final int CREEPER_RUN_TO = 12;    // ... to a spot this far away
    public static final double URGENT = 5;          // a monster this close stops a bridge job (as the bridge did)

    /** Worth eating now: food 14 or less, or below 18 while hurt. */
    public static boolean wantsMeal(int food, float health) {
        return food <= EAT_FOOD || (food < EAT_FOOD_HURT && health < EAT_FOOD_HURT);
    }

    /**
     * How good a food is to eat now; -1 = never. harmful: it has a harmful effect (poison, hunger,
     * nausea...), which also catches modded foods the blacklist doesn't name.
     */
    public static int foodScore(String id, int nutrition, float saturation, boolean harmful, float health) {
        if (id == null || FOOD_BLACKLIST.contains(id) || harmful) return -1;
        if (GOLDEN.contains(id) && health >= GOLDEN_BELOW) return -1;
        if (nutrition <= 0) return -1;
        return nutrition * 10 + Math.round(saturation * 10);
    }

    /** How far monsters count: 8 blocks, 24 right after a hit. */
    public static int lookRadius(boolean recentlyHurt) {
        return recentlyHurt ? LOOK_HURT : LOOK;
    }

    /**
     * A monster counts when it is within the radius and, unless the bot was just hurt, the bot can see it or
     * it is right here: one behind rock can't be fought and would pause the bot's work for ever.
     */
    public static boolean counts(double dist, boolean recentlyHurt, boolean seen) {
        if (dist > lookRadius(recentlyHurt)) return false;
        return recentlyHurt || dist <= 2.5 || seen;
    }

    /** A spot dist blocks straight away from the threat: {x, z}. */
    public static int[] awayFrom(double px, double pz, double tx, double tz, double dist) {
        double dx = px - tx, dz = pz - tz, len = Math.max(Math.sqrt(dx * dx + dz * dz), 0.1);
        return new int[] { (int) Math.floor(px + dx / len * dist), (int) Math.floor(pz + dz / len * dist) };
    }
}
