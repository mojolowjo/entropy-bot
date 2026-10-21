package io.github.mojolowjo.entropybot.aa.brain;

import io.github.mojolowjo.entropybot.threat.ThreatFacts;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * AA_ROUND4 B3: the hand numbers of the brain, the night and the danger desire in one place, each with its name, its
 * default, the run that set it and why. {@code aa tune <name> <value>} changes one for this world (the adapter saves the
 * changed ones as {@code aa-tuning.json} in the world's folder) so a three-game round can try a number without a build;
 * {@code aa tune} lists them. Only the numbers listed here are read through it (the code reads {@link #get} where the
 * number is used). Pure; loader notes: none (the file is the adapter's).
 */
public final class Tuning {
    private Tuning() {}

    /** One number: name, default, value now, the run that set it, why. */
    public record Entry(String name, double def, double value, String run, String why) {}

    private static final Map<String, Entry> ALL = new LinkedHashMap<>();

    static {
        // AA_ROUND4 C (ItemWorth): the scales the item data is turned into worth with (iron pickaxe ~30, stone 15, wooden 4)
        def("worth.toolK", 1.15, "AA_ROUND4 C", "a digging tool's worth = this x speed^pow x (1 + level) x ln(1 + durability) x role");
        def("worth.speedPow", 0.25, "AA_ROUND4 C review", "the power dig speed enters the tool formula with (low: level and durability dominate, so a fast gold pickaxe stays under stone)");
        def("worth.cap", 135, "AA_ROUND4 C review", "no derived worth passes this (about 3x a diamond pickaxe): a modded attack 50 cannot swamp the scale");
        def("worth.floor", 0.02, "AA_ROUND4 C review", "a recipe-derived worth is at least this (a plain block)");
        def("worth.weaponK", 0.09, "AA_ROUND4 C", "a weapon's worth = this x attack^2 x ln(1 + durability)");
        def("worth.armourK", 0.8, "AA_ROUND4 C", "an armour piece's worth = this x (defence + 2 x toughness)");
        def("worth.foodK", 0.25, "AA_ROUND4 C", "a food item's worth per energy point (nutrition + saturation), as the kit values it");
        def("worth.hardK", 0.01, "AA_ROUND4 C", "a block's worth per point of hardness");
        def("worth.lightK", 0.007, "AA_ROUND4 C", "a block's worth per light level it gives");
        def("worth.margin", 0.1, "AA_ROUND4 C", "a recipe-only item is worth its ingredients plus this share (the work of making it)");
        def("worth.depth", 4, "AA_ROUND4 C", "how many recipe steps deep an item's worth is followed before it is unknown");
        def("worth.role.pickaxe", 1, "AA_ROUND4 C", "a pickaxe's weight against the tool formula");
        def("worth.role.axe", 0.4, "AA_ROUND4 C", "an axe's weight (the kit's old axe 0.4)");
        def("worth.role.shovel", 0.3, "AA_ROUND4 C", "a shovel's weight (the kit's old shovel 0.3)");
        def("worth.role.hoe", 0.15, "AA_ROUND4 C", "a hoe's weight");
        def("worth.role.other", 0.3, "AA_ROUND4 C", "weight of a digging tool whose role the data does not name (shears, a modded tool)");
        def("worth.oreK", 100, "rm-names", "rarity is in no item component: an ore block (the ores tag) is worth this x its hardness worth; its smelted ingot and gem follow through the recipe");
        def("kit.unknown", 1.0, "rm-names", "prior: an item the game gives no data for (no tool/food/block facts, no recipe) is worth this in a kit; logged once per item");
        def("kit.lightMin", 7, "rm-names", "a held item that gives this much light and breaks at once is a torch-like light source the deposit keeps");
        def("junk.hardBand", 3.5, "rm-names", "a pickaxe/shovel block with no ore tag, no storage-block tag and no block entity up to this hardness is stone-like filler (cobbled deepslate 3.5)");
        def("junk.placeWorth", 0.02, "rm-names", "a block item worth no more than this (hardness 2: cobblestone, dirt, planks, logs) is a filler the danger moves may place and a build keeps; an ore is worth more");
        def("junk.neverWorth", 2, "rm-names", "an item worth more than this is never put on the owner's junk list (ores, gems, ingots)");
        def("tech.keyWeight", 3, "rm-names", "a recipe result that is gear (tool, armour, shield, bow: damageable) or a workstation (opens a menu) weighs this in the tech tree, any other result 1");
        def("tools.unknownNeedTier", 3, "rm-names", "the pickaxe tier asked for a mining need the block data cannot read (iron: what the old default 4 on the clear scale meant)");
        def("junk.maxWorth", 0.015, "rm-names", "an item worth less than this (soft blocks: dirt, gravel, sand, leaves) is junk; stone-like blocks are junk by tag; an unknown worth is never junk");
        def("urgent", 75, "aa-brain 1", "the score from which an upkeep may interrupt the running desire (food near empty)");
        def("margin", 15, "aa-brain 1", "the commitment bonus: a challenger must beat the running desire by this much");
        def("capExempt", 90, "director 2026-10-19", "upkeep at this score (starving) is not capped by the night");
        def("trumpFood", 4, "aa-night2 P0 1", "food under this stops a trumping desire from cutting the food need");
        def("shelterMax", 99, "aa-costs", "the most any shelter or hide scores");
        def("stashMax", 74, "aa-kit camp", "the most the stash desire scores");
        def("diedS", 60, "aa-kit camp", "seconds a death at the spot is worth in the recovery cost");
        def("dawnClimb", 45, "aa-night3", "the dawn climb to the surface scores this");
        def("respawnS", 5, "AA_STEP3 A", "seconds a respawn takes in the death cost");
        def("healTo", 16, "aa-night2 P0 1", "health the hide keeps going until");
        def("hideDayUnder", 10, "aa-night2 P0 1", "health under which a day hide starts with hostiles near");
        def("pointS", 6, "aa-costs 4", "seconds of work one food point is worth when food is fully scarce");
        def("sourceFactor", 0.2, "aa-costs 4", "with a food source known, scarcity counts this much");
        def("stationBase", 40, "AA_ROUND2 4", "a station (table, furnace) is worth this much before its share of what it unlocks");
        def("stationShare", 40, "AA_ROUND2 4", "a station adds this x the tech tree share of what it unlocks");
        def("flow", 0.9, "AA_ROUND2 4", "a prerequisite is worth at least this share of what it unlocks");
        def("shelterAt1", 85, "aa-costs", "the full-night shelter's score at night danger 1 (the one anchor)");
        def("torchCut", 0.05, "AA_ROUND4 B2", "each torch within 16 cuts the night danger by this share");
        def("lightFloor", 0.7, "AA_ROUND4 B2", "the most the torches may cut the night danger to");
        def("swordValue", 25, "aa-night2 P0 2", "a weapon's worth once a zombie was seen, added to the tools need");
        def("blockedTop", 8, "AA_ROUND4 C", "how many of the tech tree's top ideas the pickaxe tier is checked against (Costs.blocked)");
        def("chestMin", 35, "aa-kit camp", "the camp chest's least worth");
        def("hidePerHp", 7, "aa-night2 P0 1", "the hide's score per missing health point x the night danger");
        def("danger.hpS", 6, "AA_CLEANUP 4", "seconds one health point is worth at full health (regen + food), the cost_of_hp curve's low end");
        def("danger.deathS", 300, "AA_CLEANUP 4", "seconds a death costs when the facts (kit, walk back, respawn, night) are not at hand (the foes-only pricing)");
        def("danger.horizonS", 10, "AA_CLEANUP 4", "the seconds of staying put the danger desire looks at");
        def("danger.fleeBack", 0.6, "AA_CLEANUP 4", "the share of the staying damage a flee still takes later (they follow)");
        def("danger.minS", 30, "AA_CLEANUP 4", "seconds a way must save over staying before the danger desire wants it");
        def("danger.holdS", 30, "AA_CLEANUP 4", "seconds a dug-in or pillared bot waits before it works again");
        def("danger.perS", 0.25, "AA_CLEANUP 4", "the danger desire's score per second the best way saves (300 s = urgent 75)");
        // AA_STEP2 1: the night ways (round 5: night.hide and night.shelter cancelled each other; one danger desire now)
        def("danger.fightHit", ThreatFacts.ARMED, "AA_STEP2 1", "the least weapon hit the fight way needs (a wooden sword 4)");
        def("danger.boxBlocks", 12, "AA_STEP2 1", "blocks the built shell (box) needs in the bag (ShelterPlan: 12 cells)");
        def("danger.boxS", 20, "AA_STEP2 1", "seconds to build the box (12 blocks, the torch inside)");
        def("danger.burrowS", 6, "AA_STEP2 1", "seconds to dig down two and seal over the head");
        def("danger.bedS", 20, "AA_STEP2 1", "seconds to reach a known bed and lie down (no bed distance in the facts)");
        def("danger.nightHpS", 0.05, "AA_STEP2 1", "health per second the open night costs at night danger 1 (fresh spawns, while a way is set up)");
        def("slowPauseS", 20, "AA_STEP3 B", "seconds slow mode waits for go (PM or the dashboard) before it goes on by itself");
        def("danger.openS", 60, "AA_STEP2 1", "seconds a pillar top stays open to arrows and phantoms at night");
        // AA_STEP3 A (TIME_MODEL loss terms): only the curve's shape is a hand number; its ends are the facts (hpS, the death cost)
        def("danger.hpCurve", 3, "AA_STEP3 A1", "the cost_of_hp curve's power: hpS at full health rising to the death cost at 1 hp");
        def("pausedRepriceS", 10, "AA_STEP3 A5", "seconds a job may sit paused by a reflex within a minute before it ends and the brain picks again");
        // round 4A N: the needs' ways (Need, Ways): the planner as a priced row, the facts wait after a prerequisite
        def("planUnit", 5, "MINING_PLAN 3.6", "walk-blocks per planner step-cost unit (a planner chain's price = its cost x this)");
        def("searchPenalty", 60, "AA_ROUND4 A2", "seconds added to the planner row while it still searches (a known way wins while it thinks)");
        def("plannerHandS", 60, "AA_ROUND4 A2", "seconds the planner row is priced at while its own cost is unknown (searching, or after a prerequisite)");
        def("plannerWaitS", 45, "aa371", "seconds the planner may search before its row is dropped");
        def("plannerHoldS", 120, "AA_ROUND4 A2", "seconds a planner row dropped for searching too long stays held (key planner <goal>)");
        def("settleMaxS", 75, "review 2 (2b)", "the most seconds a need waits for the facts after a prerequisite ended (bag or station facts change ends it sooner)");
        // AA_ROUND4A O: the station, base and bag option sets (TIME_MODEL craft 0.5 s, place 0.25 s)
        def("station.craftS", 0.5, "AA_ROUND4A O", "seconds one craft takes (TIME_MODEL)");
        def("station.placeS", 0.25, "AA_ROUND4A O", "seconds one place takes (TIME_MODEL)");
        def("station.near", 48, "AA_ROUND4A O", "blocks within which a known station is walked to instead of made");
        def("base.distGuess", 16, "AA_ROUND4A O", "blocks a base is priced at when its distance is not sensed");
        def("climbPerBlockS", 1.5, "AA_ROUND4A O", "seconds per block of a climb out from under ground (dig + step)");
        def("base.houseS", 300, "AA_ROUND4A O", "seconds BuilderProcess takes to build the house in an area");
        def("base.nightW", 1, "AA_ROUND4A O", "weight of the open-night cost while a base way is set up");
        def("bag.depositS", 5, "AA_ROUND4A O", "seconds to put the bag into the base chests once there");
        def("bag.dropS", 2, "AA_ROUND4A O", "seconds to throw the junk out");
        // aa-cleanup C (RULES 1): hand numbers that sat in code, moved here with their old values
        def("bag.enter", 3, "aa-brain 1", "the bag need switches on under this many free slots");
        def("bag.leave", 10, "aa-brain 1", "the bag need switches off at this many free slots (the slots the deposit keeps free)");
        def("band.torchOn", 8, "aa-brain 1", "the torches need switches on under this many torches");
        def("band.torchOff", 16, "aa-brain 1", "the torches need switches off at this many torches");
        def("band.foodOn", 10, "aa-brain 1", "the food need switches on under this much food energy");
        def("band.foodOff", 18, "aa-brain 1", "the food need switches off at this much food energy");
        def("tools.base", 55, "aa-costs", "the tools need's score when the pickaxe is missing or wooden (before the blocked share)");
        def("tools.blockedW0", 30, "aa-costs", "added to tools.base x the share of next steps blocked when there is no pickaxe");
        def("tools.blockedW1", 20, "aa-costs", "added to tools.base x the share of next steps blocked when the pickaxe is wooden");
        def("tools.blockedOf", 4, "aa-costs", "how many next steps (furnace, iron, coal, owner ore) the blocked share is counted out of");
        def("tools.keepBase", 40, "aa-costs", "the tools need's score floor with a stone or better pickaxe, before its wear curve");
        def("tools.wearSpan", 30, "aa-costs", "the wear curve's span for a stone or better pickaxe (worn out adds this)");
        def("torch.target", 16, "aa-night2 P0 1", "torches the coal and craft rows stock up to");
        def("tools.stoneTier", 2, "aa-costs", "the pickaxe tier (kit scale: wood 1, stone 2) from which iron, furnace and coal work is not blocked");
        def("tools.wearPct", 15, "aa-night2 P0 1", "pickaxe durability percent under which a single pickaxe counts as worn out");
        def("tools.spares", 2, "aa-night2 P0 1", "pickaxes in the bag from which a worn one needs no new tools need");
        def("torch.base", 30, "aa-costs", "the torches need's score floor before its upkeep curve");
        def("torch.span", 30, "aa-costs", "the torches need's upkeep curve span (empty bag adds this)");
        def("bag.base", 20, "aa-costs", "the bag need's score floor before its upkeep curve");
        def("bag.span", 60, "aa-costs", "the bag need's upkeep curve span (no free slot adds this)");
        def("cost.station", 60, "AA_ROUND4 A", "seconds the station need (table, furnace) is priced at before its own facts");
        def("cost.spareTable", 10, "AA_ROUND4 A", "seconds the spare table need is priced at");
        def("cost.base", 240, "AA_ROUND4 A", "seconds the base need is priced at before its own facts");
        def("cost.bed", 120, "AA_ROUND4 A", "seconds the bed need is priced at before its own facts");
        def("cost.iron", 600, "AA_ROUND4 A", "seconds the iron need is priced at before its own facts");
        def("need.defaultScore", 30, "AA_ROUND4 A", "the score of a need that has no formula of its own");
        def("sprint.drainFloor", 0.25, "aa-costs 4", "the least measured-drain scale the sprint price trusts (a low reading cannot make sprinting free)");
        def("quarry.radius", 48, "AA_ROUND4 C", "blocks around the bot the quarry scan looks at");
        def("quarry.cacheMs", 10000, "AA_ROUND4 C", "milliseconds the quarry scan is reused while the bot stays near");
        def("mine.maxMin", 20, "0.25 bridge port (rm-mining moved it)", "minutes one mine job may run before it stops with \"time is up\"");
        def("mine.stallS", 60, "0.25 bridge port (rm-mining moved it)", "seconds without a move or a pickup while Baritone mines before the job stops (stage stall)");
        def("quarry.digK", 1.5, "AA_ROUND4 C","walk-blocks one planned dig cell costs in a quarry site's price");
        def("run.weight", 2, "aa-danger 2", "heuristic units per block along the preferred run heading (Baritone walks ~4.6 per block)");
        def("planner.gapMs", 5000, "aa371", "milliseconds between two planner asks after which the earlier search wait is over (AA ticks at 4 Hz)");
        // builder placements (0.39.q1): the movers' time limits and retries, out of aa/moves
        def("moves.calcFails", 20, "backbone step 2", "Baritone ticks of failed path calculation before a move leg fails");
        def("moves.walkS", 30, "backbone step 2", "seconds a walk / cover leg may take");
        def("steps.stepMaxS", 600, "rm-execution (was the hunt's 10 min cap)", "seconds one typed step of an option (walk, cut, hunt, mine, craft...) may run before the option fails 'timed out'");
        def("steps.burrowWaitS", 120, "rm-execution2 (was the chain text 'burrow then wait 120')", "seconds the bot waits sealed in after a night burrow (base and danger ways) before the need picks again");
        def("steps.calmWaitS", 60, "rm-execution2 (was the chain text 'wait 60')", "seconds the danger desire's wait and pillar-out ways hold still");
        def("steps.recoverWaitS", 3, "rm-execution2 (was Kit.recoverChain's 'wait 3')", "seconds the kit recovery waits on its death spot for the drops to come in");
        def("steps.fishMin", 5, "rm-execution2 (was the food way's 'fish 4')", "minutes the food set's fishing way fishes for");
        def("steps.kelpN", 8, "rm-execution2 (was the food way's 'gather kelp 8 cook')", "kelp the food set's kelp way cuts and smelts");
        def("steps.exploreMin", 5, "rm-execution2 (was the want's 'explore 5')", "minutes the explore want walks new land");
        def("steps.walkNear", 2, "rm-execution", "blocks from a seen tree or herd at which the walk before the work ends (Baritone GoalNear)");
        def("steps.huntReach", 20, "rm-execution (was the hunt job's 20-block walk rule; the strike's fight loop reaches 24)", "blocks from an animal within which the hunt step hands it to the strike input instead of walking (Baritone GoalNear) to it");
        def("steps.huntAtR", 16, "rm-execution (was HuntRules.AT_R)", "blocks round a remembered herd's place within which the hunt step takes animals");
        def("moves.pillarS", 15, "backbone step 2", "seconds a pillar leg may take");
        def("moves.digS", 40, "backbone step 2", "seconds a burrow / dig-in leg may take");
        def("moves.reflexS", 15, "backbone step 4", "seconds a reflex leg (swim-up, flee, lava escape) may take");
        def("reflex.triesPerWay", 3, "reflex choosers", "failed tries of one reflex way (charged in Failures as reflex.<kind> <cell>) before the next way runs");
        def("reflex.creeper.run", 6, "0.39.s3 (was ReflexRules.CREEPER_RUN)", "blocks: a creeper this close is run from");
        def("reflex.creeper.runTo", 12, "0.39.s3 (was ReflexRules.CREEPER_RUN_TO)", "blocks the creeper flee's GoalRunAway runs from the hostiles");
        def("reflex.creeper.seeMore", 6, "0.39.s3 (was FLEE_SEE = runTo + 6)", "blocks past runTo the flee still counts a hostile to run from");
        def("reflex.creeper.refleeTicks", 60, "0.39.s3 (was a hand 60)", "ticks one creeper run is kept before the next one is asked (Baritone re-plans less)");
        def("danger.fleeRun", 16, "0.39.s3 (was DangerMoves.FLEE_RUN)", "blocks the danger desire's flee way runs from the hostiles");
        def("danger.burrowDepth", 3, "rm-threat (was CornerEscape.burrowDepth's 2 or 3)", "cells the danger burrow asks the fork to dig down: at 3 the seal is the cell under the old feet, ground on its sides even on open flat land (at 2 the seal is the feet cell, nothing beside it in a field: the watch-s7 'no ground' night)");
        def("danger.pillarUp", 3, "rm-threat (was CornerEscape.UP / DangerOptions.PILLAR_UP)", "blocks the danger pillar climbs: 3 puts the feet out of a zombie's reach");
        def("danger.pillarMinUp", 2, "rm-threat (was CornerEscape.MIN_UP)", "the least head room / blocks for which the pillar verb still pillars (lower: no pillar)");
        def("danger.waitS", 60, "rm-threat (was DangerOptions.OPEN_WAIT_S)", "seconds of the open-air fallback wait; its exposure (night danger x these seconds) is priced against a shelter's set-up over the same span");
        def("reflex.fire.escapeStep", 3, "0.39.s3 (was FireReflex.ESCAPE_STEP)", "blocks the step out of lava / fire runs from the hazard cells");
        def("reflex.fire.escapeAway", 6, "0.39.s3 (was FireReflex.ESCAPE_AWAY)", "blocks a burning bot runs from the fire / lava near");
        def("reflex.fire.waterR", 8, "0.39.s3 (was SurvivalRules.WATER_R in nearestWater)", "blocks around the fire reflex looks for water (the fact 'water near')");
        def("reflex.fire.waterGraceTicks", 10, "0.39.s3 review", "ticks in water before a still-burning bot counts the to-water try as failed (the burn goes out a tick or two late)");
        def("reflex.retryTicks", 10, "reflex choosers", "ticks between two tries of a reflex way (a failed run is not re-asked every tick)");
        def("reflex.air.pullUp", 24, "reflex choosers", "blocks over the feet the swim-up goal's heuristic pulls toward (Baritone's search tries up first; the goal itself is any cell with the head in air)");
        def("reflex.air.burrowUp", 3, "reflex choosers", "cells over the head the air reflex's burrow-up asks the fork to break when no swim-up path is found");
        def("reflex.creeper.pillar", 3, "0.39.s8", "blocks the creeper ladder's third way pillars up (Baritone places the bag's blocks; out of a creeper's reach of the ground blast)");
        def("reflex.creeper.burrow", 2, "reflex choosers", "cells under the feet the creeper reflex's burrow asks the fork to dig (then sealed over the head)");
        def("reflex.failedS", 30, "reflex choosers", "seconds a reflex whose every way failed keeps the danger at full for the danger desire (its line is the reason)");
        def("game.lavaHpPerS", 8, "0.39.s6 review (vanilla Entity.lavaHurt)", "health a player loses in lava per second: 4 a hit, a hit every 10 ticks (the invulnerability time); LavaPrice prices the reflex's lava wade from it");
        def("moves.ownerStaleS", 2, "0.39.s6 review", "seconds after its last status tick a reflex car stops owning Baritone's breaking/placing (a car dropped without stop: the SafetyNet then puts them back)");
        def("reflex.fire.aimTicks", 10, "reflex choosers", "ticks the water bucket waits for Baritone's look to land on the block under the feet before that try fails");
        def("hands.retries", 2, "backbone step 3", "times a job's hand request restarts after another process took control away");
        def("moves.climbS", 30, "builder placements", "seconds a climb back up onto the floor fill's walkway may take (Baritone's own way up)");
        def("hands.waitS", 120, "builder placements", "seconds a caller waits for a running build / click before it counts a try (twice a leg, a reflex pause in between)");
        def("hands.reflexResume", 2, "builder placements", "a reflex pause resumes a request while its age is under this many times its limit");
        def("hands.clickS", 5, "builder placements", "seconds one right-click stage (altar use) may take");
        def("hands.buildS", 60, "builder placements", "seconds one builder placement leg (walk, face, click) may take");
        def("walk.stuckS", 30, "rm-routes (was Jobs.TRAVEL_STUCK_TICKS)", "seconds a walk may go without a block of progress toward its goal before it fails");
        def("walk.farXZ", 128, "rm-routes", "blocks across beyond which a walk asks Baritone for GoalXZ first, then GoalBlock on arrival");
    }

    private static void def(String name, double v, String run, String why) { ALL.put(name, new Entry(name, v, v, run, why)); }

    /** True under JUnit: an unknown name throws there; in the game it is logged once (and reads 0, loudly wrong rather than silent). */
    static volatile boolean strict = junit();
    private static final java.util.Set<String> REPORTED = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private static boolean junit() {
        try { Class.forName("org.junit.jupiter.api.Test"); return true; } catch (ClassNotFoundException e) { return false; }
    }

    /** The value now (the default unless tuned). An unknown name is a bug (rule 7): it throws under test, and in the game logs an error once. */
    public static double get(String name) {
        Entry e = ALL.get(name);
        if (e != null) return e.value();
        String msg = "no tuning \"" + name + "\" is registered in Tuning";
        if (strict) throw new IllegalArgumentException(msg);
        if (REPORTED.add(name)) org.slf4j.LoggerFactory.getLogger(Tuning.class).error("[entropybot] aa: {} (the number reads 0)", msg);
        return 0;
    }

    /** The value of a name code must have: same as get (an unknown name is loud there). */
    public static double need(String name) {
        double v = get(name);
        if (Double.isNaN(v)) throw new IllegalStateException("no tuning \"" + name + "\"");
        return v;
    }

    /** Sets one; returns the reply line ("ok: ..." or "error: ..."). */
    public static String set(String name, double v) {
        Entry e = ALL.get(name);
        if (e == null) return "error: no tuning \"" + name + "\" - aa tune lists them";
        if (Double.isNaN(v) || Double.isInfinite(v) || v < 0) return "error: " + name + " must be a number 0 or more";
        ALL.put(name, new Entry(e.name(), e.def(), v, e.run(), e.why()));
        return "ok: " + name + " " + fmt(v) + (v == e.def() ? " (the default)" : " (default " + fmt(e.def()) + ")");
    }

    /** All back to the defaults (a new world). */
    public static void reset() {
        for (Entry e : List.copyOf(ALL.values())) ALL.put(e.name(), new Entry(e.name(), e.def(), e.def(), e.run(), e.why()));
    }

    /** The tuned (not default) values, for the world's file. */
    public static Map<String, Double> changed() {
        Map<String, Double> m = new LinkedHashMap<>();
        for (Entry e : ALL.values()) if (e.value() != e.def()) m.put(e.name(), e.value());
        return m;
    }

    /** Loads saved values; unknown names and bad numbers are returned as errors (the rest load). */
    public static List<String> load(Map<String, Double> saved) {
        reset();
        List<String> errors = new ArrayList<>();
        if (saved == null) return errors;
        for (var e : saved.entrySet()) {
            String r = set(e.getKey(), e.getValue() == null ? Double.NaN : e.getValue());
            if (r.startsWith("error")) errors.add(r.substring(7));
        }
        return errors;
    }

    /** "flow 0.9 (AA_ROUND2 4: why)" per number; a tuned one shows its default. */
    public static List<String> lines() {
        List<String> out = new ArrayList<>();
        for (Entry e : ALL.values())
            out.add(e.name() + " " + fmt(e.value()) + (e.value() != e.def() ? " (tuned, default " + fmt(e.def()) + ")" : "") + " - " + e.run() + ": " + e.why());
        return out;
    }

    /** The default of one number (for the legacy constants and the tests). */
    public static double defOf(String name) { Entry e = ALL.get(name); return e == null ? Double.NaN : e.def(); }

    public static java.util.Set<String> names() { return ALL.keySet(); }

    static String fmt(double v) { return v == Math.rint(v) ? String.valueOf((long) v) : String.format(Locale.ROOT, "%.3f", v).replaceAll("0+$", ""); }
}



