package io.github.mojolowjo.entropybot.brain;

import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/**
 * B1: what the brain senses in one loop (docs/BRAIN_LOOP.md step 1). Plain fields with harmless defaults, so a JUnit
 * test builds a fake state in two lines; the game side ({@code commands.BrainRuntime}) fills it every 2 s. Pure.
 */
public final class BrainState {
    public long now;
    /** In a world and alive; a menu of the bot's own open while no job runs; the death policy parked it. */
    public boolean inWorld = true, menuOpen, parked;
    public float health = 20, maxHealth = 20;
    /** The food bar (0..20); edible items in the bag; torches in the bag. */
    public int food = 20, foodItems = 16, torches = 32;
    public int freeSlots = 20;
    public String stage = "stone";
    /** A fight, flight or retreat runs (the threat test counted a mob), a reflex holds the bot, or health is low. */
    public boolean danger;
    public String dangerWhy = "";
    public boolean night, sleepAuto = true, othersSleeping, litHere = true;
    public int[] botPos = {0, 64, 0};
    /** Null when unknown (out of view and no fresh companion position). */
    public int[] ownerPos;
    /** The base place (deposits go there), else null. */
    public int[] basePos;
    public boolean ownerOnline, released;
    /** The owner's running job or chain (anything not started by the brain), else null. */
    public String ownerJob;
    /** Escorting, defending or guarding a spot: passive except for danger. */
    public String passive;
    /** The brain's own running chain, else null. */
    public String brainJob;
    /** Pickaxes in the bag, the best one's durability left in %, all of their durability together; one just broke. */
    public int pickaxes = 1, pickPct = 100, pickDurability = 131;
    /** 0.23.6: the first worn armour piece ("my iron_chestplate is at 6 %"), or null. */
    public String armorWorn;
    public boolean toolBroke;
    public final List<NeedItem> needs = new ArrayList<>();
    public final List<Goal> goals = new ArrayList<>();
    /** The copy rule's verdict; null while brain copy is off. */
    public CopyRules.Activity copy;
    public IdleList.Facts upkeep = new IdleList.Facts();
    /** B3: the planner's hook for a goal with no chain (goal text -> a chain or null); null = no planner. */
    public java.util.function.Function<String, String> planner;

    /** A standing need ("need torch 32"): what the group has, and since when it is noted (ms). */
    public record NeedItem(String id, int want, int have, long since) {}

    /** A queued goal: its text, its chain, when it was set. */
    public record Goal(String text, String chain, long at) {}

    /** True when the brain must stay near the owner (rule 2: not released and the owner online and placed). */
    public boolean bound() { return !released && ownerOnline && ownerPos != null; }

    public static double flat(int[] a, int[] b) {
        if (a == null || b == null) return Double.MAX_VALUE;
        double dx = a[0] - b[0], dz = a[2] - b[2];
        return Math.sqrt(dx * dx + dz * dz);
    }

    /** The decision log's state summary (BRAIN_PLAN 4.5). */
    public JsonObject summary() {
        JsonObject o = new JsonObject();
        o.addProperty("hp", Math.round(health));
        o.addProperty("maxHp", Math.round(maxHealth));
        o.addProperty("food", food);
        o.addProperty("foodItems", foodItems);
        o.addProperty("freeSlots", freeSlots);
        o.addProperty("stage", stage);
        o.addProperty("threats", danger ? 1 : 0);
        o.addProperty("light", litHere);
        o.addProperty("night", night);
        o.addProperty("ownerDist", ownerPos == null ? -1 : (int) Math.round(flat(botPos, ownerPos)));
        o.addProperty("released", released);
        o.addProperty("job", brainJob != null ? "brain: " + brainJob : ownerJob != null ? "owner: " + ownerJob : passive);
        o.addProperty("pickPct", pickaxes == 0 ? -1 : pickPct);
        return o;
    }
}
