package io.github.mojolowjo.entropybot.aa.core;

/**
 * AA v0 (AA_PLAN 7): the fixed inputs of the decision step, filled once per AA tick from the brain's sense snapshot
 * (the adapter) or by hand (tests). Harmless defaults: a healthy bot with nothing. {@link #vector()} is the same
 * numbers in a fixed order, scaled 0..1, so a network can read them later without a new format.
 */
public final class Inputs {
    public float health = 20, maxHealth = 20;
    public int food = 20;
    /** aa-food: the food saturation (synced to the client). Not in the vector. */
    public float saturation = 5;
    public boolean hurtRecently;
    /** 0 calm, 1 a reflex holds, 2 a fight/flee/retreat runs (urgent). */
    public int danger;
    public String dangerWhy = "";
    public boolean creeperClose;
    /** Air left in ticks (300 full). */
    public int air = 300;
    public boolean inLavaOrFire;
    public int freeSlots = 36, foodItems, torches, wood, cobble, coal, ironIngots, wool, string;
    /**
     * B4: the bag by item path ("raw_iron" -> 3) and the bot's dimension id. Not in the vector. AA_ROUND2 6: the source of
     * truth for progress and failure sweeps (Brain.progressSig, OptionMemory.items); the named counts stay for the curves.
     */
    public java.util.Map<String, Integer> bag = java.util.Map.of();
    public String dimension = "minecraft:overworld";
    /** A sheep has been seen (latched by the adapter): a wool source. */
    public boolean sheepSeen;
    /** The best pickaxe: tier 0 none, 1 wood/gold, 2 stone, 3 iron, 4 diamond+; its durability left in %; count. */
    public int pickTier, pickPct, pickaxes, planks;
    public boolean bedKnown, bedInBag, tableKnown, furnaceKnown, tableInBag, furnaceInBag, baseChests, baseKnown, shelterBuilt, areaKnown;
    /** The level's day time (ticks), -1 unknown. */
    public long dayTime = -1;
    public boolean night, thunder, underGround, inArea = true, ownerOnline;
    /** 0.36.3: blocks of ground over the bot (Jobs.underDepth, leaves not counted; 0 = at the surface). Not in the vector. */
    public int underDepth;
    /** Night fix: the bot's armour points (0 = none; early game). Not in the vector. */
    public int armorPoints;
    /** S0: the bot's block position (null unknown; OptionMemory's NO_PATH sweep). Not in the vector. */
    public int[] pos;
    /** S1: the spot was lit this night (AaTree keeps it; the idle LIGHT option scores -1 then). Not in the vector. */
    public boolean litTonight;
    /** nightwork: inside a shell of its own that is built and waiting (ShelterJob WAIT, at its cell). Not in the vector. */
    public boolean sheltered;
    /** aa-costs: an open coal goal's score and size (0 none; Goals.coalGoal). Not in the vector. */
    public double coalGoalScore;
    public int coalGoalN;
    /** aa-costs (night): difficulty 0..3, block light at the feet, hostiles within 24. Not in the vector. */
    public int difficulty = 2, light, hostilesNear;

    /** Torches may burn coal: the torches' gain per coal over the coal goal's loss per coal (Costs.torchCoal). */
    public boolean torchCoal() { return io.github.mojolowjo.entropybot.aa.brain.Costs.torchCoal(coal, torches, coalGoalScore, coalGoalN); }

    public String torchCoalTerms() { return io.github.mojolowjo.entropybot.aa.brain.Costs.coalTerms(coal, torches, coalGoalScore, coalGoalN); }
    public boolean ownerOrderPending, aiRequestPending, menuOpen, parked, inWorld = true;
    /** Something not AA's runs (the owner's chain, a job, a gather...): its name, else null. */
    public String otherJob;
    /** The first buildable (not safe) area's name, else null; the food bar drains (not Peaceful). */
    public String areaName;
    public boolean hungerDrains;

    public static final String[] NAMES = {
            "health", "food", "hurt", "danger", "creeper", "air", "lavaFire", "freeSlots", "foodItems", "torches", "wood",
            "cobble", "coal", "iron", "wool", "pickTier", "pickPct", "bed", "table", "furnace", "baseChests", "base",
            "shelter", "area", "night", "thunder", "underGround", "inArea", "ownerOnline", "ownerOrder", "aiRequest",
            "menu", "parked", "otherJob"};

    /** The inputs as a fixed vector, 0..1 (counts capped at 64, iron at 32). */
    public float[] vector() {
        return new float[]{
                health / Math.max(1, maxHealth), food / 20f, b(hurtRecently), danger / 2f, b(creeperClose), air / 300f,
                b(inLavaOrFire), freeSlots / 36f, cap(foodItems), cap(torches), cap(wood), cap(cobble), cap(coal),
                Math.min(32, ironIngots) / 32f, cap(wool), pickTier / 4f, pickPct / 100f, b(bedKnown || bedInBag),
                b(tableKnown), b(furnaceKnown), b(baseChests), b(baseKnown), b(shelterBuilt), b(areaKnown), b(night),
                b(thunder), b(underGround), b(inArea), b(ownerOnline), b(ownerOrderPending), b(aiRequestPending),
                b(menuOpen), b(parked), b(otherJob != null)};
    }

    private static float b(boolean v) { return v ? 1 : 0; }

    private static float cap(int n) { return Math.min(64, Math.max(0, n)) / 64f; }
}
