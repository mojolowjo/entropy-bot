package io.github.mojolowjo.entropybot.brain;

import io.github.mojolowjo.entropybot.camp.DayNight;

/**
 * 0.24.3 (the owner: the pack's mobs are far worse than vanilla, and the bot starts with nothing): how safe a night out is.
 * score = armour tier x3 ({@link #armourTier}: none 0, leather 1, iron 2,
 * diamond+ 3) + weapon tier x2 (0-3) + food (8+ items: 2, 4+: 1, else 0) + torches (1 with any) + health (16+: 1)
 * - deaths in the last day (at most 3). Full iron (6) + iron sword (4) + 8 food (2) = {@link #FULL_IRON}.
 * Bands: below night.shelterBelow = SHELTER (shelter at dusk until day), below night.workBelow = UNDERGROUND (night
 * work only underground or in the lit camp), else NORMAL (light the spot, sleep auto). Pure; loader notes: none.
 */
public final class NightSafety {
    private NightSafety() {}

    public static final int FULL_IRON = 12;

    public enum Band { SHELTER, UNDERGROUND, NORMAL }

    public static int score(int armour, int weapon, int foodItems, int torches, float health, int deathsDay) {
        int s = clamp(armour) * 3 + clamp(weapon) * 2 + (foodItems >= 8 ? 2 : foodItems >= 4 ? 1 : 0) + (torches > 0 ? 1 : 0)
                + (health >= 16 ? 1 : 0) - Math.min(Math.max(deathsDay, 0), 3);
        return Math.max(0, s);
    }

    private static int clamp(int t) { return Math.max(0, Math.min(3, t)); }

    public static int score(BrainState s) {
        return score(s.armourTier, s.weaponTier, s.foodItems, s.torches, s.health, s.deathsDay);
    }

    public static Band band(int score, BrainConfig c) {
        if (score < c.i("night.shelterBelow")) return Band.SHELTER;
        if (score < c.i("night.workBelow")) return Band.UNDERGROUND;
        return Band.NORMAL;
    }

    /** Armour tier from a material word in the worn pieces' ids (the weakest piece counts; a missing piece = 0). */
    public static int armourTier(java.util.List<String> worn) {
        if (worn == null || worn.size() < 4) return 0;
        int min = 3;
        for (String id : worn) min = Math.min(min, tierOf(id));
        return min;
    }

    /** Material tier of an item id: wood 0; leather, stone, chainmail, copper, gold 1; iron 2; diamond, netherite 3. */
    public static int tierOf(String id) {
        String s = id == null ? "" : id.toLowerCase();
        if (s.contains("netherite") || s.contains("diamond")) return 3;
        if (s.contains("iron")) return 2;
        if (s.contains("leather") || s.contains("chainmail") || s.contains("copper") || s.contains("golden") || s.contains("stone")) return 1;
        return 0;
    }

    /** Ticks until dusk (night start); 0 at night. */
    public static long toDusk(long dayTime) {
        if (dayTime < 0) return -1;
        if (DayNight.night(dayTime)) return 0;
        long t = dayTime % 24000;
        return t < DayNight.NIGHT_FROM ? DayNight.NIGHT_FROM - t : 24000 - t + DayNight.NIGHT_FROM;
    }

    /** Within the last h in-game hours before dusk (1 hour = 1000 ticks). */
    public static boolean nearDusk(long dayTime, int hours) {
        long d = toDusk(dayTime);
        return d > 0 && d <= hours * 1000L;
    }

    /** A chain that stays off the surface at night: underground mining, eating, crafting, sheltering, sleeping, lighting. */
    public static boolean underground(String chain) {
        String c = chain == null ? "" : chain.trim().toLowerCase();
        return c.startsWith("mine strip") || c.startsWith("mine cave") || c.startsWith("eat") || c.startsWith("craft")
                || c.contains("shelter") || c.startsWith("sleep") || c.startsWith("light");
    }

    /** "night status" / why: the score, the band and time to dusk. */
    public static String status(BrainState s, BrainConfig c) {
        int sc = score(s);
        Band b = band(sc, c);
        long d = toDusk(s.dayTime);
        return "night safety " + sc + " (" + b.name().toLowerCase() + ": shelter below " + c.i("night.shelterBelow") + ", underground only below "
                + c.i("night.workBelow") + "; armour " + s.armourTier + ", weapon " + s.weaponTier + ", food " + s.foodItems + ", torches " + s.torches
                + ", health " + Math.round(s.health) + ", deaths today " + s.deathsDay + ")"
                + (d < 0 ? "" : d == 0 ? "; it is night" : "; dusk in " + (d / 1000) + "h" + String.format("%02d", (d % 1000) * 60 / 1000) + "m");
    }
}
