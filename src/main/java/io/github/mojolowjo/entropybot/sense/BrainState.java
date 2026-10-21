package io.github.mojolowjo.entropybot.sense;

/**
 * B1: what the bot senses in one read (docs/BRAIN_LOOP.md step 1). Plain fields with harmless defaults, so a JUnit
 * test builds a fake state in two lines; the game side ({@code commands.SenseRuntime}) fills it, AA reads it every
 * 10 ticks. AA_CLEANUP 3: moved from the old brain with the brain-only fields (copy, upkeep, day plan, learned table,
 * needs and goals) dropped. Pure.
 */
public final class BrainState {
    public long now;
    /** In a world and alive; the death policy parked it. */
    public boolean inWorld = true, parked;
    public float health = 20, maxHealth = 20;
    /** The food bar (0..20); edible items in the bag; torches in the bag. */
    public int food = 20, foodItems = 16, torches = 32;
    public int freeSlots = 20;
    public String stage = "stone";
    /** A fight, flight or retreat runs (the threat test counted a mob), or a reflex holds the bot. */
    public boolean danger;
    public String dangerWhy = "";
    public boolean night, sleepAuto = true, othersSleeping, litHere = true;
    public int[] botPos = {0, 64, 0};
    /** Null when unknown (out of view and no fresh companion position). */
    public int[] ownerPos;
    /** The base place (deposits go there), else null. */
    public int[] basePos;
    public boolean ownerOnline, released;
    /** Pickaxes in the bag, the best one's durability left in %, all of their durability together. */
    public int pickaxes = 1, pickPct = 100, pickDurability = 131;
    /** Tool wear as a cost (aa ToolWear): the best pickaxe's break speed on stone (ItemStack.getDestroySpeed), 0 none. */
    public float pickSpeed = 4f;
    /** aa-times: the game's seconds per stone block with the best pickaxe / by hand (GameTimesLive), -1 not read. */
    public double digStoneS = -1, handStoneS = -1;
    /** 0.23.6: the first worn armour piece ("my iron_chestplate is at 6 %"), or null. */
    public String armorWorn;
    /** 0.24.3: armour and weapon tier (0-3; harmless defaults: diamond), deaths in the last day, the level's day time
     *  (-1 unknown), a bed within reach, the camp place (null: none). */
    public int armourTier = 3, weaponTier = 3, deathsDay;
    public long dayTime = -1;
    public boolean bedNear = true;
    public int[] campPos;
    /** 0.24.3: shell blocks in the bag (planks, cobblestone, dirt; logs count 4). */
    public int shelterBlocks = 64;
    /** 0.25.1: logs and planks in the bag; edible items in bag + storage (the stock view, every 30 s). */
    public int woodItems, foodStock = 16;

    /**
     * 0.26.1 inputs, each a bounded read on the game side: coal + charcoal and logs (not planks) in the bag; a bed in the
     * bag; the level thunders and the bot's head cell sees the sky; the bot stands inside one of its areas; how long the
     * owner has been offline (ms, 0 online); the stock view changed (a scan or deposit); materials for a pickaxe of the
     * stage's tier in the bag.
     */
    public int coal, logs;
    public boolean bedInBag, thundering, skyAbove = true, areasHere = true;
    public long ownerOfflineMs;
    public long storageSeq;
    public boolean pickMaterials;
    /** 0.26.2: the feasibility facts (known false until the game side reads them: then checks needing them are UNKNOWN). */
    public Feasibility.Facts feas = new Feasibility.Facts();

    /** R9: an owner position this far (flat) from the bot is a placeholder, never a real one (maxWalk is 2000). */
    public static final int OWNER_SENTINEL_DIST = 100_000;

    /** R9: the owner's position is known and real (seen, or a fresh companion fix; never the far placeholder). */
    public boolean ownerKnown() {
        return ownerPos != null && Math.abs(ownerPos[0]) < 30_000_000 && Math.abs(ownerPos[2]) < 30_000_000 && flat(botPos, ownerPos) < OWNER_SENTINEL_DIST;
    }

    public static double flat(int[] a, int[] b) {
        if (a == null || b == null) return Double.MAX_VALUE;
        double dx = a[0] - b[0], dz = a[2] - b[2];
        return Math.sqrt(dx * dx + dz * dz);
    }
}
