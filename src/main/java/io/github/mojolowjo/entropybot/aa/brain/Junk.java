package io.github.mojolowjo.entropybot.aa.brain;

/**
 * One junk definition (rm-names, RULES row 4): no id list. An item is junk when it is not kept (gear, food, light, bench),
 * and it is either a stone-like block (the game's base-stone tags, or a soft pickaxe/shovel block that is no ore and has no
 * block entity: {@link ItemWorth.Source#stoneLike}) or worth under {@code junk.maxWorth} (dirt, gravel, sand, leaves).
 * An unknown worth is never junk. Pure; the data comes through {@link ItemWorth}. JUnit JunkTest.
 */
public final class Junk {
    private Junk() {}

    public static boolean is(String id) {
        if (id == null) return false;
        if (ItemWorth.junkFood(id)) return true;           // harmful or nutrition <= 1: junk by the FOOD data
        if (Kit.kept(id)) return false;
        if (ItemWorth.stoneLike(id)) return true;
        var w = ItemWorth.of(id);
        return w.known() && w.value() < Tuning.get("junk.maxWorth");
    }

    /** A block the bot can place as filler (hardness from the data): what a build or shelter keeps in the bag. */
    public static boolean placeable(String id) {
        if (id == null || !ItemWorth.block(id) || Kit.kept(id)) return false;
        // run HW (2026-11-02): a stone-like block (the base-stone tags, or a pickaxe/shovel block up to junk.hardBand with no ore,
        // storage or block entity: cobbled deepslate at 3.5) is filler whatever its hardness says of its worth
        if (ItemWorth.stoneLike(id)) return true;
        var w = ItemWorth.of(id);
        return w.known() && w.value() <= Tuning.get("junk.placeWorth") + 1e-9;
    }
}

