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
    /** Tool wear as a cost (aa ToolWear): the best pickaxe's break speed on stone (ItemStack.getDestroySpeed), 0 none. */
    public float pickSpeed = 4f;
    /** 0.23.6: the first worn armour piece ("my iron_chestplate is at 6 %"), or null. */
    public String armorWorn;
    public boolean toolBroke;
    /** 0.24.3 night safety: armour and weapon tier (0-3; harmless defaults: diamond), deaths in the last day, the level's
     *  day time (-1 unknown), a bed within reach, the camp place (null: none). */
    public int armourTier = 3, weaponTier = 3, deathsDay;
    public long dayTime = -1;
    public boolean bedNear = true;
    public int[] campPos;
    /** 0.24.3: shell blocks in the bag (planks, cobblestone, dirt; logs count 4). */
    public int shelterBlocks = 64;
    /** 0.25.1: logs and planks in the bag; edible items in bag + storage (the stock view, every 30 s). */
    public int woodItems, foodStock = 16;
    public final List<NeedItem> needs = new ArrayList<>();
    public final List<Goal> goals = new ArrayList<>();
    /** The copy rule's verdict; null while brain copy is off. */
    public CopyRules.Activity copy;
    public IdleList.Facts upkeep = new IdleList.Facts();
    /** B3: the planner's hook for a goal with no chain (goal text -> a chain or null); null = no planner. */
    public java.util.function.Function<String, String> planner;

    /**
     * 0.26.1 "steady brain" inputs, each a bounded read on the game side (no block scan in the loop except the bed
     * search, capped at r 24 and once a minute by day): coal + charcoal and logs (not planks) in the bag; a bed in the
     * bag; the level thunders and the bot's head cell sees the sky; the bot stands inside one of its areas; how long the
     * owner has been offline (ms, 0 online); escort or assist runs (owner-bound passive, R5); the stock view changed
     * (a scan or deposit; R1 clears "none in my chests"); materials for a pickaxe of the stage's tier in the bag (R8);
     * the guard refuses brain copy's target (R9, null = allowed); spawn set near the base and a spawn try is due (R6,
     * filled by the brain from its store).
     */
    public int coal, logs;
    public boolean bedInBag, thundering, skyAbove = true, areasHere = true;
    public long ownerOfflineMs;
    public boolean passiveOwnerBound;
    public long storageSeq;
    public boolean pickMaterials;
    public String copyRefused;
    public boolean spawnNearBase, spawnDue;
    /** 0.26.2: the feasibility facts (known false until the game side reads them: then checks needing them are UNKNOWN). */
    public Feasibility.Facts feas = new Feasibility.Facts();
    /** 0.26.3: the day plan's active goal, offered as the need day_goal (filled by the brain's DayPlanRuntime), else null. */
    public DayGoal dayGoal;
    /** 0.27.3 area.return: the walk back when the bot stands outside every area (null: inside, or no area in this
     *  dimension); a shelter job holds the bot; the walk back is parked (why, else null; filled by the brain). */
    public AreaReturn.Target areaReturn;
    public boolean sheltering;
    public String areaReturnParked;
    /** 0.27.3 (d6): food candidates the last food choice skipped (too much mining), for why; null none. */
    public String foodSkip;

    /** 0.26.5: the learned table and its settings (null: not loaded yet or brain.learn 0: every multiplier is 1). */
    public OutcomeTable learn;
    public OutcomeTable.Params learnParams = OutcomeTable.Params.defaults();

    /** 0.26.5: the learned adjustment of a chain here (m 1 when no table). */
    public OutcomeTable.Adjust learned(String chain) {
        if (learn == null || chain == null || learnParams == null || !learnParams.on()) return new OutcomeTable.Adjust(OutcomeTable.kind(chain), "", 1.0, 0, 0, false);
        return learn.adjust(chain, OutcomeTable.bucket(this), now, learnParams);
    }

    /** The day plan's active goal: its form, its chain (the planner's), the why line. */
    public record DayGoal(String text, String chain, String why) {}

    /** R9: an owner position this far (flat) from the bot is a placeholder, never a real one (maxWalk is 2000). */
    public static final int OWNER_SENTINEL_DIST = 100_000;

    /** R9: the owner's position is known and real (seen, or a fresh companion fix; never the far placeholder). */
    public boolean ownerKnown() {
        return ownerPos != null && Math.abs(ownerPos[0]) < 30_000_000 && Math.abs(ownerPos[2]) < 30_000_000 && flat(botPos, ownerPos) < OWNER_SENTINEL_DIST;
    }

    /** A standing need ("need torch 32"): what the group has, and since when it is noted (ms). */
    public record NeedItem(String id, int want, int have, long since) {}

    /** A queued goal: its text, its chain, when it was set. */
    public record Goal(String text, String chain, long at) {}

    /** True when the brain must stay near the owner (rule 2: not released and the owner online and placed). */
    public boolean bound() { return !released && ownerOnline && ownerKnown(); }

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
        // 0.26.5: the learned table's buckets
        o.addProperty("daypart", OutcomeTable.daypart(night, dayTime));
        o.addProperty("baseDist", basePos == null ? -1 : (int) Math.round(flat(botPos, basePos)));
        // R9: -1 unknown (out of view, no fresh companion fix), -2 a placeholder position the brain ignores (0.25.x logged it as ~14142025)
        o.addProperty("ownerDist", ownerPos == null ? -1 : !ownerKnown() ? -2 : (int) Math.round(flat(botPos, ownerPos)));
        if (thundering) o.addProperty("storm", skyAbove);
        o.addProperty("released", released);
        o.addProperty("job", brainJob != null ? "brain: " + brainJob : ownerJob != null ? "owner: " + ownerJob : passive);
        o.addProperty("pickPct", pickaxes == 0 ? -1 : pickPct);
        return o;
    }
}
