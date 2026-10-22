package io.github.mojolowjo.entropybot.aa.brain;

/**
 * rm-constants (0.39.u2): the hand numbers that were {@code static final} literals in the code, one entry each with its default,
 * the constant it replaced and why. Grouped by the class they came from (the name prefix); {@code aa tune} lists them by prefix.
 * Read through {@link Tuning#get} (or {@link Tuning#i}, {@link Tuning#l}) where the number is used. Generated once, then edited by hand.
 */
final class TuningDefs {
    private TuningDefs() {}

    static void register() {
        // aaDesires
        Tuning.def("aaDesires.idleScale", 0.25, "rm-constants 0.39.u2 (was AaDesires.IDLE_SCALE)", "the idle scale of AaDesires");
        Tuning.def("aaDesires.rungMax", 99, "rm-constants 0.39.u2 (was AaDesires.RUNG_MAX)", "AA_ROUND4 B2: the most a need scores by itself");
        Tuning.def("aaDesires.rescoreMs", 10_000, "rm-constants 0.39.u2 (was AaDesires.RESCORE_MS)", "AA_ROUND2 1+2: a rung desire's probe (may it start?) and, with a picker, its live cost");
        Tuning.def("aaDesires.shelterPatienceMs", 12 * 60_000, "rm-constants 0.39.u2 (was AaDesires.SHELTER_PATIENCE_MS)", "the shelter patience ms of AaDesires");
        Tuning.def("aaDesires.shortHoldMs", 60_000, "rm-constants 0.39.u2 (was AaDesires.SHORT_HOLD_MS)", "Review 2: a failed way's hold when no other way is open (else Failures' 10 min x fails)");
        Tuning.def("aaDesires.repriceMs", 5_000, "rm-constants 0.39.u2 (was AaDesires.REPRICE_MS)", "AA_STEP3 A6: how often a running danger way is priced again (the Replan cadence)");
        Tuning.def("aaDesires.helpTimeoutMs", 10 * 60_000, "rm-constants 0.39.u2 (was AaDesires.HELP_TIMEOUT_MS)", "A help task is short (a walk, a fight, a hand-over)");
        // aaEscape
        Tuning.def("aaEscape.maxPerTask", 2, "rm-constants 0.39.u2 (was AaEscape.MAX_PER_TASK)", "At most this many escapes per task (a way out that keeps ending stuck fails the task as before)");
        Tuning.def("aaEscape.holeMaxUp", 48, "rm-constants 0.39.u2 (was AaEscape.HOLE_MAX_UP)", "How far up the hole scan looks for a way out sideways");
        Tuning.def("aaEscape.plankReserve", 6, "rm-constants 0.39.u2 (was AaEscape.PLANK_RESERVE)", "Review 0.39.a4 4: planks kept back from placing (a crafting table 4 + sticks 2), so a pillar or a box never eats the next craft");
        // aaLadder
        Tuning.def("aaLadder.surfaceDepth", 3, "rm-constants 0.39.u2 (was AaLadder.SURFACE_DEPTH)", "Under this much ground a need whose work wants open ground prices a climb into its row (a tree's leaves never count)");
        Tuning.def("aaLadder.campR", 24, "rm-constants 0.39.u2 (was AaLadder.CAMP_R)", "Seen within this many blocks of the camp or base mark, a table/furnace counts as the camp's (latched)");
        // aaLive
        Tuning.def("aaLive.switches", 12, "rm-constants 0.39.u2 (was AaLive.SWITCHES)", "the switches of AaLive");
        Tuning.def("aaLive.depthMax", 40, "rm-constants 0.39.u2 (was AaLive.DEPTH_MAX)", "the depth max of AaLive");
        // aaManual
        Tuning.def("aaManual.askMs", 60_000, "rm-constants 0.39.u2 (was AaManual.ASK_MS)", "How long an ask waits for aa pick before AA takes its own first choice");
        Tuning.def("aaManual.doMaxMs", 10 * 60_000, "rm-constants 0.39.u2 (was AaManual.DO_MAX_MS)", "the do max ms of AaManual");
        // aaRuntime
        Tuning.def("aaRuntime.holdMs", 2 * 60_000, "rm-constants 0.39.u2 (was AaRuntime.HOLD_MS)", "the hold ms of AaRuntime");
        Tuning.def("aaRuntime.errorsPerMinuteOff", 10, "rm-constants 0.39.u2 (was AaRuntime.ERRORS_PER_MINUTE_OFF)", "the errors per minute off of AaRuntime");
        Tuning.def("aaRuntime.foodFactsMs", 10_000, "rm-constants 0.39.u2 (was AaRuntime.FOOD_FACTS_MS)", "The food facts are read at most this often (the desire's park check asks every tick)");
        Tuning.def("aaRuntime.foodR", 24, "rm-constants 0.39.u2 (was AaRuntime.FOOD_R)", "the food r of AaRuntime");
        Tuning.def("aaRuntime.foodDy", 6, "rm-constants 0.39.u2 (was AaRuntime.FOOD_DY)", "the food dy of AaRuntime");
        Tuning.def("aaRuntime.drainWindowMs", 5 * 60_000, "rm-constants 0.39.u2 (was AaRuntime.DRAIN_WINDOW_MS)", "A sample closes after this long at most (a long task gives several)");
        Tuning.def("aaRuntime.stairRead", 16, "rm-constants 0.39.u2 (was AaRuntime.STAIR_READ)", "AA_ROUND3 4: the staircase steps down whose cells are read per heading (the pick-time budget)");
        Tuning.def("failures.fastEndMs", 1000, "rm-bootstrap 0.39.v1", "a job that ends done within this many ms changing nothing is a failure (rule 7)");
        Tuning.def("aaRuntime.skyR", 24, "rm-constants 0.39.u2 (was AaRuntime.SKY_R)", "The open-sky cell search radius (columns), S2");
        Tuning.def("aaRuntime.ironOres", 8, "rm-constants 0.39.u2 (was AaRuntime.IRON_ORES)", "The nearest known iron positions the scorer weighs");
        Tuning.def("aaRuntime.lookTicksDefault", 10, "rm-constants 0.39.u2 (was AaRuntime.LOOK_TICKS_DEFAULT)", "Baritone's own default is 5");
        // aaScorer
        Tuning.def("aaScorer.stockTarget", 64, "rm-constants 0.39.u2 (was AaScorer.STOCK_TARGET)", "the stock target of AaScorer");
        Tuning.def("aaScorer.woodLo", 32, "rm-constants 0.39.u2 (was AaScorer.WOOD_LO)", "the wood lo of AaScorer");
        Tuning.def("aaScorer.woodHi", 64, "rm-constants 0.39.u2 (was AaScorer.WOOD_HI)", "the wood hi of AaScorer");
        Tuning.def("aaScorer.stoneLo", 32, "rm-constants 0.39.u2 (was AaScorer.STONE_LO)", "the stone lo of AaScorer");
        Tuning.def("aaScorer.stoneHi", 64, "rm-constants 0.39.u2 (was AaScorer.STONE_HI)", "the stone hi of AaScorer");
        Tuning.def("aaScorer.coalLo", 8, "rm-constants 0.39.u2 (was AaScorer.COAL_LO)", "the coal lo of AaScorer");
        Tuning.def("aaScorer.coalHi", 32, "rm-constants 0.39.u2 (was AaScorer.COAL_HI)", "the coal hi of AaScorer");
        Tuning.def("aaScorer.ironLo", 4, "rm-constants 0.39.u2 (was AaScorer.IRON_LO)", "the iron lo of AaScorer");
        Tuning.def("aaScorer.ironHi", 16, "rm-constants 0.39.u2 (was AaScorer.IRON_HI)", "the iron hi of AaScorer");
        Tuning.def("aaScorer.torchLo", 32, "rm-constants 0.39.u2 (was AaScorer.TORCH_LO)", "the torch lo of AaScorer");
        Tuning.def("aaScorer.torchHi", 64, "rm-constants 0.39.u2 (was AaScorer.TORCH_HI)", "the torch hi of AaScorer");
        Tuning.def("aaScorer.depositBelow", 10, "rm-constants 0.39.u2 (was AaScorer.DEPOSIT_BELOW)", "the deposit below of AaScorer");
        Tuning.def("aaScorer.lightTorches", 8, "rm-constants 0.39.u2 (was AaScorer.LIGHT_TORCHES)", "the light torches of AaScorer");
        Tuning.def("aaScorer.lightScore", 40, "rm-constants 0.39.u2 (was AaScorer.LIGHT_SCORE)", "the light score of AaScorer");
        Tuning.def("aaScorer.waitScore", 10, "rm-constants 0.39.u2 (was AaScorer.WAIT_SCORE)", "the wait score of AaScorer");
        Tuning.def("aaScorer.onFloor", 12, "rm-constants 0.39.u2 (was AaScorer.ON_FLOOR)", "the on floor of AaScorer");
        // aaTree
        Tuning.def("aaTree.calmMs", 3000, "rm-constants 0.39.u2 (was AaTree.CALM_MS)", "the calm ms of AaTree");
        Tuning.def("aaTree.airHoldMs", 30_000, "rm-constants 0.39.u2 (was AaTree.AIR_HOLD_MS)", "the air hold ms of AaTree");
        // actionTable
        Tuning.def("actionTable.budget", 30_000, "rm-constants 0.39.u2 (was ActionTable.BUDGET)", "The document's size budget without the state");
        // areaCandidates
        Tuning.def("areaCandidates.r", 16, "rm-constants 0.39.u2 (was AreaCandidates.R)", "How far round a candidate the safe area reaches (the 16 blocks explore keeps off), and down/up");
        Tuning.def("areaCandidates.down", 8, "rm-constants 0.39.u2 (was AreaCandidates.DOWN)", "How far round a candidate the safe area reaches (the 16 blocks explore keeps off), and down/up");
        Tuning.def("areaCandidates.up", 16, "rm-constants 0.39.u2 (was AreaCandidates.UP)", "How far round a candidate the safe area reaches (the 16 blocks explore keeps off), and down/up");
        // areaFacts
        Tuning.def("areaFacts.awayWeight", 0.5, "rm-constants 0.39.u2 (was AreaFacts.AWAY_WEIGHT)", "the away weight of AreaFacts");
        Tuning.def("areaFacts.manyEntities", 2, "rm-constants 0.39.u2 (was AreaFacts.MANY_ENTITIES)", "the many entities of AreaFacts");
        Tuning.def("areaFacts.manyBuilt", 6, "rm-constants 0.39.u2 (was AreaFacts.MANY_BUILT)", "the many built of AreaFacts");
        // assistRules
        Tuning.def("assistRules.freshMs", 10_000, "rm-constants 0.39.u2 (was AssistRules.FRESH_MS)", "an older report is no report");
        Tuning.def("assistRules.recentMs", 10_000, "rm-constants 0.39.u2 (was AssistRules.RECENT_MS)", "a broke/placed counts this long");
        Tuning.def("assistRules.switchMs", 5_000, "rm-constants 0.39.u2 (was AssistRules.SWITCH_MS)", "a new activity must hold this long");
        Tuning.def("assistRules.idleStopMs", 60_000, "rm-constants 0.39.u2 (was AssistRules.IDLE_STOP_MS)", "idle this long: no mirroring, just follow");
        Tuning.def("assistRules.giveGapMs", 30_000, "rm-constants 0.39.u2 (was AssistRules.GIVE_GAP_MS)", "at most one hand-over in this time");
        Tuning.def("assistRules.retryMs", 60_000, "rm-constants 0.39.u2 (was AssistRules.RETRY_MS)", "the same chain is not started again within this");
        Tuning.def("assistRules.chopR", 16, "rm-constants 0.39.u2 (was AssistRules.CHOP_R)", "the same chain is not started again within this");
        Tuning.def("assistRules.mineR", 12, "rm-constants 0.39.u2 (was AssistRules.MINE_R)", "the same chain is not started again within this");
        Tuning.def("assistRules.treeR", 4, "rm-constants 0.39.u2 (was AssistRules.TREE_R)", "the same chain is not started again within this");
        Tuning.def("assistRules.treeUp", 12, "rm-constants 0.39.u2 (was AssistRules.TREE_UP)", "the same chain is not started again within this");
        Tuning.def("assistRules.giveLow", 16, "rm-constants 0.39.u2 (was AssistRules.GIVE_LOW)", "the give low of AssistRules");
        Tuning.def("assistRules.giveMax", 32, "rm-constants 0.39.u2 (was AssistRules.GIVE_MAX)", "the give max of AssistRules");
        Tuning.def("assistRules.mineMax", 8, "rm-constants 0.39.u2 (was AssistRules.MINE_MAX)", "the mine max of AssistRules");
        Tuning.def("assistRules.treeMax", 4, "rm-constants 0.39.u2 (was AssistRules.TREE_MAX)", "the tree max of AssistRules");
        Tuning.def("assistRules.farmR", 16, "rm-constants 0.39.u2 (was AssistRules.FARM_R)", "the farm r of AssistRules");
        // bedOptions
        Tuning.def("bedOptions.kill", 25, "rm-constants 0.39.u2 (was BedOptions.KILL)", "the kill of BedOptions");
        Tuning.def("bedOptions.craft", 20, "rm-constants 0.39.u2 (was BedOptions.CRAFT)", "the craft of BedOptions");
        Tuning.def("bedOptions.herdFix", 20, "rm-constants 0.39.u2 (was BedOptions.HERD_FIX)", "the herd fix of BedOptions");
        // bedSpot
        Tuning.def("bedSpot.offCamp", 1.5, "rm-constants 0.39.u2 (was BedSpot.OFF_CAMP)", "Blocks of cost per block the bed lies away from being next to the table or chest");
        Tuning.def("bedSpot.blockedHead", 0.5, "rm-constants 0.39.u2 (was BedSpot.BLOCKED_HEAD)", "Cost of each head direction that is blocked");
        Tuning.def("bedSpot.near", 4, "rm-constants 0.39.u2 (was BedSpot.NEAR)", "How far from the anchor a bed may stand");
        Tuning.def("bedSpot.far", 10, "rm-constants 0.39.u2 (was BedSpot.FAR)", "How far from the anchor a bed may stand");
        // blueprint
        Tuning.def("blueprint.maxFails", 3, "rm-constants 0.39.u2 (was Blueprint.MAX_FAILS)", "Largest schematic AA takes (cells, air included)");
        Tuning.def("needChain.exploreReturn", 0.5, "rm-bootstrap 0.39.v1", "share of the explore time the walk back costs again (the explore way's return leg)");
        Tuning.def("nearby.scanR", 32, "simple-tools 0.39.w1", "blocks round the bot Baritone's world scan looks for the nearest tree log and stone (the radius its own mine process uses)");
        Tuning.def("nearby.scanMax", 64, "simple-tools 0.39.w1", "most blocks one Baritone world scan returns for the nearby fact");
        Tuning.def("nearby.scanY", 10, "simple-tools 0.39.w1", "the y-level threshold passed to Baritone's world scan (the one its mine process uses)");
        Tuning.def("nearby.maxChecks", 24, "simple-tools 0.39.w1", "scanned blocks checked (nearest first) for leaves, the policy and the tier before the nearby fact falls back to the sightings");
        Tuning.def("nearby.leafReach", 2, "simple-tools 0.39.w1", "blocks above or beside a log a leaf block must be within for the log to count as a tree");
        Tuning.def("nearby.cacheMs", 3000, "simple-tools 0.39.w1", "milliseconds a nearby answer is reused while the bot stays near where it was asked");
        Tuning.def("nearby.cacheMove", 4, "simple-tools 0.39.w1", "blocks the bot may move before a cached nearby answer is asked again");
        Tuning.def("only.otherWeight", 0, "simple-tools 0.39.w1", "factor on every other need's and want's score while 'aa only <need>' is on (0: they are silent; reflexes and danger stay)");
        // campCommands (the station search radius)
        Tuning.def("campCommands.near", 8, "rm-bootstrap 0.39.v1 (was bootstrapPlan.near)", "blocks round the bot a crafting table, furnace or chest counts as near");
        // brain
        Tuning.def("brain.minRunMs", 60_000, "rm-constants 0.39.u2 (was Brain.MIN_RUN_MS)", "the min run ms of Brain");
        Tuning.def("brain.stuckMs", 180_000, "rm-constants 0.39.u2 (was Brain.STUCK_MS)", "the stuck ms of Brain");
        Tuning.def("brain.fastStuckMs", 40_000, "rm-constants 0.39.u2 (was Brain.FAST_STUCK_MS)", "the fast stuck ms of Brain");
        Tuning.def("brain.waitMaxS", 60, "rm-constants 0.39.u2 (was Brain.WAIT_MAX_S)", "the wait max s of Brain");
        Tuning.def("brain.waitMinS", 3, "rm-constants 0.39.u2 (was Brain.WAIT_MIN_S)", "the wait min s of Brain");
        Tuning.def("brain.cdBaseMs", 120_000, "rm-constants 0.39.u2 (was Brain.CD_BASE_MS)", "the cd base ms of Brain");
        Tuning.def("brain.cdMaxMs", 16 * 60_000, "rm-constants 0.39.u2 (was Brain.CD_MAX_MS)", "the cd max ms of Brain");
        Tuning.def("brain.gapMs", 5_000, "rm-constants 0.39.u2 (was Brain.GAP_MS)", "the gap ms of Brain");
        Tuning.def("brain.probeMs", 60_000, "rm-constants 0.39.u2 (was Brain.PROBE_MS)", "the probe ms of Brain");
        Tuning.def("brain.picksKept", 10, "rm-constants 0.39.u2 (was Brain.PICKS_KEPT)", "the picks kept of Brain");
        Tuning.def("brain.surfaceDepth", 3, "rm-constants 0.39.u2 (was Brain.SURFACE_DEPTH)", "Under this much ground over the bot it is not at the surface (AaLadder.SURFACE_DEPTH)");
        Tuning.def("brain.zeroEndMs", 5_000, "rm-constants 0.39.u2 (was Brain.ZERO_END_MS)", "aa-switch2: how long a running desire's score must stay at or below 0 before the day-check ends it");
        Tuning.def("brain.probeHoldMs", 5_000, "rm-constants 0.39.u2 (was Brain.PROBE_HOLD_MS)", "AA_ROUND2 2: a challenger's 'cannot start' is re-asked at most this often");
        Tuning.def("brain.maxInterruptsPerMin", 2, "rm-constants 0.39.u2 (was Brain.MAX_INTERRUPTS_PER_MIN)", "the max interrupts per min of Brain");
        Tuning.def("brain.planEveryMs", 30_000, "rm-constants 0.39.u2 (was Brain.PLAN_EVERY_MS)", "the plan every ms of Brain");
        // brainStats
        Tuning.def("brainStats.minRuns", 3, "rm-constants 0.39.u2 (was BrainStats.MIN_RUNS)", "the min runs of BrainStats");
        Tuning.def("brainStats.alpha", 0.3, "rm-constants 0.39.u2 (was BrainStats.ALPHA)", "the alpha of BrainStats");
        Tuning.def("brainStats.drainMinN", 2, "rm-constants 0.39.u2 (was BrainStats.DRAIN_MIN_N)", "Samples before a kind's measured drain replaces the hand rate");
        Tuning.def("brainStats.drainMinMinutes", 1, "rm-constants 0.39.u2 (was BrainStats.DRAIN_MIN_MINUTES)", "the drain min minutes of BrainStats");
        // breakPolicy
        Tuning.def("breakPolicy.travel", 6, "rm-constants 0.39.u2 (was BreakPolicy.TRAVEL)", "Default travel multiplier (about 15 blocks of walking per broken stone block)");
        Tuning.def("breakPolicy.travelExpensive", 10, "rm-constants 0.39.u2 (was BreakPolicy.TRAVEL_EXPENSIVE)", "The camp and a multiplayer world's outside");
        Tuning.def("breakPolicy.travelWaterSide", 2, "rm-constants 0.39.u2 (was BreakPolicy.TRAVEL_WATER_SIDE)", "TRAVEL: a break next to water on top (flow risk)");
        Tuning.def("breakPolicy.mineOre", 0.5, "rm-constants 0.39.u2 (was BreakPolicy.MINE_ORE)", "MINE: an ore is cheaper than the stone around it (go through the ore)");
        Tuning.def("breakPolicy.travelPlace", 3, "rm-constants 0.39.u2 (was BreakPolicy.TRAVEL_PLACE)", "TRAVEL placing: x3 (place cost 60 + walk)");
        Tuning.def("breakPolicy.refill", 0.05, "rm-constants 0.39.u2 (was BreakPolicy.REFILL)", "the refill of BreakPolicy");
        Tuning.def("breakPolicy.travelCap", 24, "rm-constants 0.39.u2 (was BreakPolicy.TRAVEL_CAP)", "Travel breaks per walk, all restored by the ledger (owner's default)");
        // buildSpotter
        Tuning.def("buildSpotter.radius", 8, "rm-constants 0.39.u2 (was BuildSpotter.RADIUS)", "the radius of BuildSpotter");
        Tuning.def("buildSpotter.minBlocks", 6, "rm-constants 0.39.u2 (was BuildSpotter.MIN_BLOCKS)", "the min blocks of BuildSpotter");
        Tuning.def("buildSpotter.grow", 2, "rm-constants 0.39.u2 (was BuildSpotter.GROW)", "the grow of BuildSpotter");
        Tuning.def("buildSpotter.link", 3, "rm-constants 0.39.u2 (was BuildSpotter.LINK)", "the link of BuildSpotter");
        Tuning.def("buildSpotter.homeRadius", 24, "rm-constants 0.39.u2 (was BuildSpotter.HOME_RADIUS)", "0.24.3: how far from the bot's spawn point or a bed it used a build hint never fires");
        // builderDig
        Tuning.def("builderDig.maxRestarts", 5, "rm-constants 0.39.u2 (was BuilderDig.MAX_RESTARTS)", "Restarts after the builder went idle while blocks were left and it had broken some since its last start (a hold)");
        Tuning.def("builderDig.outsideNear", 2, "rm-constants 0.39.u2 (was BuilderDig.OUTSIDE_NEAR)", "How far outside the box a break still counts as the way in");
        Tuning.def("builderDig.outsideCap", 20000, "rm-constants 0.39.u2 (was BuilderDig.OUTSIDE_CAP)", "the outside cap of BuilderDig");
        // builderReport
        Tuning.def("builderReport.stallSeconds", 60, "rm-constants 0.39.u2 (was BuilderReport.STALL_SECONDS)", "the stall seconds of BuilderReport");
        // campCommands
        // caveRules
        Tuning.def("caveRules.minutes", 30, "rm-constants 0.39.u2 (was CaveRules.MINUTES)", "the minutes of CaveRules");
        Tuning.def("caveRules.fromEntrance", 160, "rm-constants 0.39.u2 (was CaveRules.FROM_ENTRANCE)", "the from entrance of CaveRules");
        Tuning.def("caveRules.free", 4, "rm-constants 0.39.u2 (was CaveRules.FREE)", "the free of CaveRules");
        Tuning.def("caveRules.moves", 3, "rm-constants 0.39.u2 (was CaveRules.MOVES)", "the moves of CaveRules");
        Tuning.def("caveRules.misses", 4, "rm-constants 0.39.u2 (was CaveRules.MISSES)", "the misses of CaveRules");
        Tuning.def("caveRules.veinCells", 8, "rm-constants 0.39.u2 (was CaveRules.VEIN_CELLS)", "the vein cells of CaveRules");
        Tuning.def("caveRules.veinReach", 16, "rm-constants 0.39.u2 (was CaveRules.VEIN_REACH)", "the vein reach of CaveRules");
        Tuning.def("caveRules.veinSpread", 4, "rm-constants 0.39.u2 (was CaveRules.VEIN_SPREAD)", "the vein spread of CaveRules");
        Tuning.def("caveRules.walkTicks", 1200, "rm-constants 0.39.u2 (was CaveRules.WALK_TICKS)", "The walk to a frontier");
        // caveSearch
        Tuning.def("caveSearch.maxNodes", 12000, "rm-constants 0.39.u2 (was CaveSearch.MAX_NODES)", "the max nodes of CaveSearch");
        Tuning.def("caveSearch.maxReach", 48, "rm-constants 0.39.u2 (was CaveSearch.MAX_REACH)", "the max reach of CaveSearch");
        Tuning.def("caveSearch.maxOres", 24, "rm-constants 0.39.u2 (was CaveSearch.MAX_ORES)", "the max ores of CaveSearch");
        Tuning.def("caveSearch.minFrontier", 6, "rm-constants 0.39.u2 (was CaveSearch.MIN_FRONTIER)", "the min frontier of CaveSearch");
        // caves
        Tuning.def("caves.pickWithin", 64, "rm-constants 0.39.u2 (was Caves.PICK_WITHIN)", "Package H: the cells of one cave, of all caves together, and the number of caves (memory/Limits)");
        // chains
        Tuning.def("chains.runResumeMs", 12L * 3600 * 1000, "rm-constants 0.39.u2 (was Chains.RUN_RESUME_MS)", "the run resume ms of Chains");
        // chunkSummary
        Tuning.def("chunkSummary.lavaLake", 12, "rm-constants 0.39.u2 (was ChunkSummary.LAVA_LAKE)", "Lava sources in one section that make a 'lava lake' (as PoiScanner had it)");
        // clearEngine
        Tuning.def("clearEngine.maxConsecFails", 8, "rm-constants 0.39.u2 (was ClearEngine.MAX_CONSEC_FAILS)", "clearPlan gives up after this many failed tries in a row");
        // clearGrid
        Tuning.def("clearGrid.lockBudget", 600, "rm-constants 0.39.u2 (was ClearGrid.LOCK_BUDGET)", "How many sight checks waterLock may spend");
        Tuning.def("clearGrid.sightBudget", 400, "rm-constants 0.39.u2 (was ClearGrid.SIGHT_BUDGET)", "planWalk's sight budget per plan");
        Tuning.def("clearGrid.entryBudget", 600, "rm-constants 0.39.u2 (was ClearGrid.ENTRY_BUDGET)", "How many sight checks entryProblem may spend");
        // clearing
        Tuning.def("clearing.buildFall", 4, "rm-constants 0.39.u2 (was Clearing.BUILD_FALL)", "0.30.4 (live: the bot left on a wall top 4 above the floor, Baritone's limit 3)");
        // climbFallback
        Tuning.def("climbFallback.near", 8, "rm-constants 0.39.u2 (was ClimbFallback.NEAR)", "the near of ClimbFallback");
        Tuning.def("climbFallback.after", 2, "rm-constants 0.39.u2 (was ClimbFallback.AFTER)", "the after of ClimbFallback");
        // climbPlan
        Tuning.def("climbPlan.hole", 3, "rm-constants 0.39.u2 (was ClimbPlan.HOLE)", "A hole this deep or more under the surface counts as 'in a hole' (check, the walk's hint)");
        Tuning.def("climbPlan.hintMax", 24, "rm-constants 0.39.u2 (was ClimbPlan.HINT_MAX)", "Deeper than this a failed walk gets no climb hint (a mine, not a pit)");
        Tuning.def("climbPlan.startR", 4, "rm-constants 0.39.u2 (was ClimbPlan.START_R)", "the start r of ClimbPlan");
        // coalOptions
        Tuning.def("coalOptions.mineOre", 3, "rm-constants 0.39.u2 (was CoalOptions.MINE_ORE)", "the mine ore of CoalOptions");
        Tuning.def("coalOptions.smeltItem", 10, "rm-constants 0.39.u2 (was CoalOptions.SMELT_ITEM)", "the smelt item of CoalOptions");
        Tuning.def("coalOptions.furnaceTrip", 2, "rm-constants 0.39.u2 (was CoalOptions.FURNACE_TRIP)", "the furnace trip of CoalOptions");
        Tuning.def("coalOptions.furnaceMake", 15, "rm-constants 0.39.u2 (was CoalOptions.FURNACE_MAKE)", "the furnace make of CoalOptions");
        Tuning.def("coalOptions.digMax", 16, "rm-constants 0.39.u2 (was CoalOptions.DIG_MAX)", "the dig max of CoalOptions");
        // commands
        Tuning.def("commands.memoryBlockMs", 20_000, "rm-constants 0.39.u2 (was Commands.MEMORY_BLOCK_MS)", "the memory block ms of Commands");
        Tuning.def("commands.mobsRadius", 32, "rm-constants 0.39.u2 (was Commands.MOBS_RADIUS)", "state.json 'mobs': radius, cap");
        Tuning.def("commands.mobsCap", 48, "rm-constants 0.39.u2 (was Commands.MOBS_CAP)", "the mobs cap of Commands");
        // compact
        Tuning.def("compact.compactR", 6, "rm-constants 0.39.u2 (was Compact.COMPACT_R)", "the compact r of Compact");
        Tuning.def("compact.compactSpare", 2, "rm-constants 0.39.u2 (was Compact.COMPACT_SPARE)", "Free slots kept for the crafted blocks");
        // confirmGate
        Tuning.def("confirmGate.windowMs", 30_000, "rm-constants 0.39.u2 (was ConfirmGate.WINDOW_MS)", "the window ms of ConfirmGate");
        Tuning.def("confirmGate.bigDig", 1000, "rm-constants 0.39.u2 (was ConfirmGate.BIG_DIG)", "A dig box bigger than this asks first");
        Tuning.def("confirmGate.maxDig", 20000, "rm-constants 0.39.u2 (was ConfirmGate.MAX_DIG)", "A dig box bigger than this is an error (DigCommands' own cap), said before any question");
        // cooldown
        Tuning.def("cooldown.baseMs", 120_000, "rm-constants 0.39.u2 (was Cooldown.BASE_MS)", "the base ms of Cooldown");
        Tuning.def("cooldown.maxMs", 16 * 60_000, "rm-constants 0.39.u2 (was Cooldown.MAX_MS)", "the max ms of Cooldown");
        Tuning.def("cooldown.noRunMs", 30_000, "rm-constants 0.39.u2 (was Cooldown.NO_RUN_MS)", "S1 (MINING_PLAN 12): the idle starvation fix");
        // costs
        Tuning.def("costs.holdMs", 4_000, "rm-constants 0.39.u2 (was Costs.HOLD_MS)", "the hold ms of Costs");
        Tuning.def("costs.litAt", 8, "rm-constants 0.39.u2 (was Costs.LIT_AT)", "Block light this high keeps a point lit (spawning is far rarer there)");
        // craftJob
        Tuning.def("craftJob.craftsPerFill", 1, "rm-constants 0.39.u2 (was CraftJob.CRAFTS_PER_FILL)", "The bridge's crafts per grid fill");
        Tuning.def("craftJob.resultWaitTicks", 30, "rm-constants 0.39.u2 (was CraftJob.RESULT_WAIT_TICKS)", "Ticks to wait for the result slot after a fill before giving up ('the grid did not make ...')");
        Tuning.def("craftJob.tableWalkMinTicks", 40, "rm-constants 0.39.u2 (was CraftJob.TABLE_WALK_MIN_TICKS)", "Ticks the walk to a table runs at least before the bot checks Baritone is idle");
        Tuning.def("craftJob.tableOpenTicks", 60, "rm-constants 0.39.u2 (was CraftJob.TABLE_OPEN_TICKS)", "Ticks to wait for the table's screen to open");
        Tuning.def("craftJob.smeltTicks", 200, "rm-constants 0.39.u2 (was CraftJob.SMELT_TICKS)", "A vanilla furnace: 10 s an item");
        Tuning.def("craftJob.smeltCheckTries", 4, "rm-constants 0.39.u2 (was CraftJob.SMELT_CHECK_TRIES)", "Collect rounds at a furnace before 'made only N of M'");
        // craftPlanner
        Tuning.def("craftPlanner.maxDepth", 8, "rm-constants 0.39.u2 (was CraftPlanner.MAX_DEPTH)", "How deep missing ingredients are crafted or smelted first (16k disk <- 16k part <- 4k part <- 1k part <- processor <- raw processor <- silicon)");
        Tuning.def("craftPlanner.wideDepth", 2, "rm-constants 0.39.u2 (was CraftPlanner.WIDE_DEPTH)", "Down to this depth every alternative (up to CRAFT_ALTS) of a missing ingredient is tried");
        Tuning.def("craftPlanner.deepAlts", 4, "rm-constants 0.39.u2 (was CraftPlanner.DEEP_ALTS)", "Alternatives of a missing ingredient tried below WIDE_DEPTH (keeps a deep search small)");
        Tuning.def("craftPlanner.budget", 1500, "rm-constants 0.39.u2 (was CraftPlanner.BUDGET)", "the budget of CraftPlanner");
        Tuning.def("craftPlanner.craftAlts", 40, "rm-constants 0.39.u2 (was CraftPlanner.CRAFT_ALTS)", "How many alternatives of a missing crafting ingredient are tried");
        Tuning.def("craftPlanner.smeltAlts", 10, "rm-constants 0.39.u2 (was CraftPlanner.SMELT_ALTS)", "How many alternatives of a missing furnace input are tried");
        // crafting
        Tuning.def("crafting.hereR", 6, "rm-constants 0.39.u2 (was Crafting.HERE_R)", "How near a furnace must be to count as 'here'");
        // creeperSprint
        Tuning.def("creeperSprint.start", 6, "rm-constants 0.39.u2 (was CreeperSprint.START)", "the start of CreeperSprint");
        Tuning.def("creeperSprint.maxTicks", 100, "rm-constants 0.39.u2 (was CreeperSprint.MAX_TICKS)", "the max ticks of CreeperSprint");
        Tuning.def("creeperSprint.moving", 0.15, "rm-constants 0.39.u2 (was CreeperSprint.MOVING)", "Blocks a tick that count as moving (a sprint is about 0.28)");
        Tuning.def("creeperSprint.awayDot", 0.5, "rm-constants 0.39.u2 (was CreeperSprint.AWAY_DOT)", "the away dot of CreeperSprint");
        Tuning.def("creeperSprint.watch", 10, "rm-constants 0.39.u2 (was CreeperSprint.WATCH)", "After a sprint the bot holds still (no job walking it back) while the creeper is this close, for WATCH_TICKS");
        Tuning.def("creeperSprint.watchTicks", 100, "rm-constants 0.39.u2 (was CreeperSprint.WATCH_TICKS)", "the watch ticks of CreeperSprint");
        Tuning.def("creeperSprint.chaseTicks", 60, "rm-constants 0.39.u2 (was CreeperSprint.CHASE_TICKS)", "A creeper that keeps coming within CHASE_TICKS of the last sprint");
        // curves
        Tuning.def("curves.costC", 600, "rm-constants 0.39.u2 (was Curves.COST_C)", "Seconds at which a progress desire's value halves");
        Tuning.def("curves.foodW", 175, "rm-constants 0.39.u2 (was Curves.FOOD_W)", "the food w of Curves");
        // dangerMoves
        Tuning.def("dangerMoves.calmTicks", 100, "rm-constants 0.39.u2 (was DangerMoves.CALM_TICKS)", "the calm ticks of DangerMoves");
        Tuning.def("dangerMoves.maxTicks", 1800, "rm-constants 0.39.u2 (was DangerMoves.MAX_TICKS)", "the max ticks of DangerMoves");
        Tuning.def("dangerMoves.waterFloorScan", 24, "rm-constants 0.39.u2 (was DangerMoves.WATER_FLOOR_SCAN)", "How far down the water facts look for a floor");
        Tuning.def("dangerMoves.shoreCacheMs", 2_000, "rm-constants 0.39.u2 (was DangerMoves.SHORE_CACHE_MS)", "Review 0.39.a4 3: the shore scan is kept this long (and for the same cell)");
        // dangerOptions
        Tuning.def("dangerOptions.digS", 3, "rm-constants 0.39.u2 (was DangerOptions.DIG_S)", "Seconds to dig two cells down (or into a wall) and seal");
        Tuning.def("dangerOptions.pillarS", 1.5, "rm-constants 0.39.u2 (was DangerOptions.PILLAR_S)", "Seconds to dig two cells down (or into a wall) and seal");
        Tuning.def("dangerOptions.pathFactor", 0.6, "rm-constants 0.39.u2 (was DangerOptions.PATH_FACTOR)", "the path factor of DangerOptions");
        Tuning.def("dangerOptions.pursuitRange", 16, "rm-constants 0.39.u2 (was DangerOptions.PURSUIT_RANGE)", "A walker this far behind at the start loses the bot on the way");
        Tuning.def("dangerOptions.pathX", 1.3, "rm-constants 0.39.u2 (was DangerOptions.PATH_X)", "AA_STEP3 (TIME_MODEL)");
        Tuning.def("dangerOptions.edgeS", 2, "rm-constants 0.39.u2 (was DangerOptions.EDGE_S)", "AA_STEP3 (TIME_MODEL)");
        // debugRules
        Tuning.def("debugRules.maxCells", 4096, "rm-constants 0.39.u2 (was DebugRules.MAX_CELLS)", "the max cells of DebugRules");
        Tuning.def("debugRules.eventsDefault", 20, "rm-constants 0.39.u2 (was DebugRules.EVENTS_DEFAULT)", "the events default of DebugRules");
        Tuning.def("debugRules.eventsMax", 300, "rm-constants 0.39.u2 (was DebugRules.EVENTS_MAX)", "the events max of DebugRules");
        Tuning.def("debugRules.changesRDefault", 8, "rm-constants 0.39.u2 (was DebugRules.CHANGES_R_DEFAULT)", "the changes r default of DebugRules");
        Tuning.def("debugRules.changesRMax", 64, "rm-constants 0.39.u2 (was DebugRules.CHANGES_R_MAX)", "the changes r max of DebugRules");
        Tuning.def("debugRules.changesMax", 50, "rm-constants 0.39.u2 (was DebugRules.CHANGES_MAX)", "the changes max of DebugRules");
        Tuning.def("debugRules.trailMinutesDefault", 10, "rm-constants 0.39.u2 (was DebugRules.TRAIL_MINUTES_DEFAULT)", "the trail minutes default of DebugRules");
        Tuning.def("debugRules.trailMinutesMax", 24 * 60, "rm-constants 0.39.u2 (was DebugRules.TRAIL_MINUTES_MAX)", "the trail minutes max of DebugRules");
        Tuning.def("debugRules.trailMax", 200, "rm-constants 0.39.u2 (was DebugRules.TRAIL_MAX)", "the trail max of DebugRules");
        Tuning.def("debugRules.incidentsList", 10, "rm-constants 0.39.u2 (was DebugRules.INCIDENTS_LIST)", "the incidents list of DebugRules");
        // discovery
        Tuning.def("discovery.caveBlocksMin", 16, "rm-constants 0.39.u2 (was Discovery.CAVE_BLOCKS_MIN)", "the cave blocks min of Discovery");
        Tuning.def("discovery.exposed", 8, "rm-constants 0.39.u2 (was Discovery.EXPOSED)", "the exposed of Discovery");
        Tuning.def("discovery.defaultTunnelPerOre", 60, "rm-constants 0.39.u2 (was Discovery.DEFAULT_TUNNEL_PER_ORE)", "the default tunnel per ore of Discovery");
        Tuning.def("discovery.minTunnel", 4, "rm-constants 0.39.u2 (was Discovery.MIN_TUNNEL)", "the min tunnel of Discovery");
        Tuning.def("discovery.maxTunnel", 2000, "rm-constants 0.39.u2 (was Discovery.MAX_TUNNEL)", "the max tunnel of Discovery");
        Tuning.def("discovery.seenCaveWithin", 64, "rm-constants 0.39.u2 (was Discovery.SEEN_CAVE_WITHIN)", "AA_ROUND3 5: a seen opening this close is the cheap cave option");
        Tuning.def("discovery.maxWalkPerCave", 3000, "rm-constants 0.39.u2 (was Discovery.MAX_WALK_PER_CAVE)", "AA_ROUND3 5: a seen opening this close is the cheap cave option");
        Tuning.def("discovery.exploreMinutes", 5, "rm-constants 0.39.u2 (was Discovery.EXPLORE_MINUTES)", "AA_ROUND3 5: a seen opening this close is the cheap cave option");
        Tuning.def("discovery.slab", 4, "rm-constants 0.39.u2 (was Discovery.SLAB)", "the slab of Discovery");
        Tuning.def("discovery.stairLeg", 30, "rm-constants 0.39.u2 (was Discovery.STAIR_LEG)", "aa-switch2: staircase levels per chain");
        // door
        Tuning.def("door.repeatMs", 30_000, "rm-constants 0.39.u2 (was Door.REPEAT_MS)", "The same driver and action are counted and logged again only after this long");
        // dropSweep
        Tuning.def("dropSweep.maxWalks", 20, "rm-constants 0.39.u2 (was DropSweep.MAX_WALKS)", "the max walks of DropSweep");
        Tuning.def("dropSweep.maxTicks", 1200, "rm-constants 0.39.u2 (was DropSweep.MAX_TICKS)", "60 s");
        Tuning.def("dropSweep.walkTicks", 80, "rm-constants 0.39.u2 (was DropSweep.WALK_TICKS)", "4 s per drop");
        Tuning.def("dropSweep.idleAfter", 15, "rm-constants 0.39.u2 (was DropSweep.IDLE_AFTER)", "a walk that went idle this early without the drop gone has given up */");
        // drownRisk
        Tuning.def("drownRisk.swimUp", 2.0, "rm-constants 0.39.u2 (was DrownRisk.SWIM_UP)", "the swim up of DrownRisk");
        Tuning.def("drownRisk.margin", 1.5, "rm-constants 0.39.u2 (was DrownRisk.MARGIN)", "the margin of DrownRisk");
        Tuning.def("drownRisk.k", 60, "rm-constants 0.39.u2 (was DrownRisk.K)", "the k of DrownRisk");
        Tuning.def("drownRisk.waterSlow", 5, "rm-constants 0.39.u2 (was DrownRisk.WATER_SLOW)", "Vanilla: breaking is 5x slower submerged (no Aqua Affinity) and 5x again off the ground");
        Tuning.def("drownRisk.dropS", 0.4, "rm-constants 0.39.u2 (was DrownRisk.DROP_S)", "the drop s of DrownRisk");
        Tuning.def("drownRisk.defaultTier", 2, "rm-constants 0.39.u2 (was DrownRisk.DEFAULT_TIER)", "The tier assumed when unknown (a stone pickaxe)");
        Tuning.def("drownRisk.placeS", 0.5, "rm-constants 0.39.u2 (was DrownRisk.PLACE_S)", "Seconds to place one block (aim, click)");
        Tuning.def("drownRisk.surfaceScan", 32, "rm-constants 0.39.u2 (was DrownRisk.SURFACE_SCAN)", "The deepest water column counted per cell");
        // exploreRules
        Tuning.def("exploreRules.seen", 2, "rm-constants 0.39.u2 (was ExploreRules.SEEN)", "the seen of ExploreRules");
        Tuning.def("exploreRules.rings", 24, "rm-constants 0.39.u2 (was ExploreRules.RINGS)", "the rings of ExploreRules");
        Tuning.def("exploreRules.defaultMinutes", 5, "rm-constants 0.39.u2 (was ExploreRules.DEFAULT_MINUTES)", "the default minutes of ExploreRules");
        Tuning.def("exploreRules.maxMinutes", 30, "rm-constants 0.39.u2 (was ExploreRules.MAX_MINUTES)", "the max minutes of ExploreRules");
        Tuning.def("exploreRules.walkTicks", 20 * 90, "rm-constants 0.39.u2 (was ExploreRules.WALK_TICKS)", "A walk to a chunk: until Baritone is done or this many ticks (90 s)");
        Tuning.def("exploreRules.arrived", 24, "rm-constants 0.39.u2 (was ExploreRules.ARRIVED)", "the arrived of ExploreRules");
        Tuning.def("exploreRules.gatherGapChunks", 2, "rm-constants 0.39.u2 (was ExploreRules.GATHER_GAP_CHUNKS)", "0.33.1: new chunks walked between two gathers");
        Tuning.def("exploreRules.gatherShare", 3, "rm-constants 0.39.u2 (was ExploreRules.GATHER_SHARE)", "0.33.1: new chunks walked between two gathers");
        Tuning.def("exploreRules.gatherEndTicks", 600, "rm-constants 0.39.u2 (was ExploreRules.GATHER_END_TICKS)", "the gather end ticks of ExploreRules");
        // exploreWords
        Tuning.def("exploreWords.defaultMinutes", 5, "rm-constants 0.39.u2 (was ExploreWords.DEFAULT_MINUTES)", "the default minutes of ExploreWords");
        Tuning.def("exploreWords.findMinutes", 10, "rm-constants 0.39.u2 (was ExploreWords.FIND_MINUTES)", "the find minutes of ExploreWords");
        Tuning.def("exploreWords.maxMinutes", 30, "rm-constants 0.39.u2 (was ExploreWords.MAX_MINUTES)", "the max minutes of ExploreWords");
        Tuning.def("exploreWords.rings", 24, "rm-constants 0.39.u2 (was ExploreWords.RINGS)", "the rings of ExploreWords");
        Tuning.def("exploreWords.maxFromStart", 16, "rm-constants 0.39.u2 (was ExploreWords.MAX_FROM_START)", "How far (chunks, either axis) from where it started it goes, so the walk home stays short (256 blocks)");
        Tuning.def("exploreWords.baseBuilt", 6, "rm-constants 0.39.u2 (was ExploreWords.BASE_BUILT)", "the base built of ExploreWords");
        Tuning.def("exploreWords.baseR", 16, "rm-constants 0.39.u2 (was ExploreWords.BASE_R)", "the base r of ExploreWords");
        Tuning.def("exploreWords.caveAir", 40, "rm-constants 0.39.u2 (was ExploreWords.CAVE_AIR)", "A cave under the bot");
        // failures
        Tuning.def("failures.retryMs", 10 * 60_000, "rm-constants 0.39.u2 (was Failures.RETRY_MS)", "the retry ms of Failures");
        Tuning.def("failures.retryMaxMs", 60 * 60_000, "rm-constants 0.39.u2 (was Failures.RETRY_MAX_MS)", "the retry max ms of Failures");
        Tuning.def("failures.cdBaseMs", 120_000, "rm-constants 0.39.u2 (was Failures.CD_BASE_MS)", "the cd base ms of Failures");
        Tuning.def("failures.cdMaxMs", 16 * 60_000, "rm-constants 0.39.u2 (was Failures.CD_MAX_MS)", "the cd max ms of Failures");
        Tuning.def("failures.moved", 16, "rm-constants 0.39.u2 (was Failures.MOVED)", "the moved of Failures");
        // farmPlant
        Tuning.def("farmPlant.waterR", 4, "rm-constants 0.39.u2 (was FarmPlant.WATER_R)", "Farmland within this many blocks (each way) of water stays wet");
        Tuning.def("farmPlant.maxSide", 33, "rm-constants 0.39.u2 (was FarmPlant.MAX_SIDE)", "The largest box side");
        // farmPlanting
        Tuning.def("farmPlanting.maxTicks", 20L * 60 * 5, "rm-constants 0.39.u2 (was FarmPlanting.MAX_TICKS)", "The longest planting job, in ticks (5 minutes)");
        // farmRules
        Tuning.def("farmRules.farmR", 6, "rm-constants 0.39.u2 (was FarmRules.FARM_R)", "Crops within this many blocks of the farm spot");
        Tuning.def("farmRules.farmFree", 4, "rm-constants 0.39.u2 (was FarmRules.FARM_FREE)", "A round that ends with this many free bag slots or fewer puts things away at the base");
        // fishRules
        Tuning.def("fishRules.defaultN", 4, "rm-constants 0.39.u2 (was FishRules.DEFAULT_N)", "the default n of FishRules");
        Tuning.def("fishRules.maxMinutes", 30, "rm-constants 0.39.u2 (was FishRules.MAX_MINUTES)", "the max minutes of FishRules");
        Tuning.def("fishRules.range", 16, "rm-constants 0.39.u2 (was FishRules.RANGE)", "the range of FishRules");
        Tuning.def("fishRules.minCast", 3, "rm-constants 0.39.u2 (was FishRules.MIN_CAST)", "the min cast of FishRules");
        Tuning.def("fishRules.maxCast", 9, "rm-constants 0.39.u2 (was FishRules.MAX_CAST)", "the max cast of FishRules");
        Tuning.def("fishRules.biteWait", 40 * 20L, "rm-constants 0.39.u2 (was FishRules.BITE_WAIT)", "Ticks: no bite for this long after a cast = reel in and cast again (the bobber may sit in a bad spot)");
        Tuning.def("fishRules.recastDelay", 15, "rm-constants 0.39.u2 (was FishRules.RECAST_DELAY)", "Ticks between a reel and the next cast (the bobber is gone)");
        Tuning.def("fishRules.countMax", 15 * 60 * 20L, "rm-constants 0.39.u2 (was FishRules.COUNT_MAX)", "A count run stops after this long (ticks), like hunt's 10 minutes, but fishing is slow");
        // fishing
        Tuning.def("fishing.idleEnd", 60 * 20, "rm-constants 0.39.u2 (was Fishing.IDLE_END)", "the idle end of Fishing");
        Tuning.def("fishing.maxWalks", 4, "rm-constants 0.39.u2 (was Fishing.MAX_WALKS)", "the max walks of Fishing");
        Tuning.def("fishing.walkTicks", 60, "rm-final 0.39.u3 (was an inline literal in Fishing)", "ticks the fish step gives Baritone to walk to the water before it looks again");
        Tuning.def("fishing.idleRecheck", 100, "rm-final 0.39.u3 (was an inline literal in Fishing)", "ticks between two looks for water when none is in reach");
        Tuning.def("fishing.handRetry", 20, "rm-final 0.39.u3 (was an inline literal in Fishing)", "ticks before the rod is tried to the hand again");
        Tuning.def("fishing.aimTicks", 5, "rm-final 0.39.u3 (was an inline literal in Fishing)", "ticks the camera gets to turn before a cast, and between a reel and the next step");
        Tuning.def("fishing.castRest", 10, "rm-final 0.39.u3 (was an inline literal in Fishing)", "ticks after a cast before the bobber is read");
        Tuning.def("fishing.scanDown", 4, "rm-final 0.39.u3 (was an inline literal in Fishing)", "blocks below the bot the water-surface scan reaches");
        Tuning.def("fishing.scanUp", 2, "rm-final 0.39.u3 (was an inline literal in Fishing)", "blocks above the bot the water-surface scan reaches");
        // fleeHeading
        Tuning.def("fleeHeading.clear", 2.5, "rm-constants 0.39.u2 (was FleeHeading.CLEAR)", "the clear of FleeHeading");
        // foodModel
        Tuning.def("foodModel.horizonMin", 60, "rm-constants 0.39.u2 (was FoodModel.HORIZON_MIN)", "the horizon min of FoodModel");
        Tuning.def("foodModel.minStock", 8, "rm-constants 0.39.u2 (was FoodModel.MIN_STOCK)", "the min stock of FoodModel");
        Tuning.def("foodModel.maxStock", 64, "rm-constants 0.39.u2 (was FoodModel.MAX_STOCK)", "the max stock of FoodModel");
        Tuning.def("foodModel.urgentFood", 6, "rm-constants 0.39.u2 (was FoodModel.URGENT_FOOD)", "At or below this the bar is urgent");
        Tuning.def("foodModel.stockBase", 25, "rm-constants 0.39.u2 (was FoodModel.STOCK_BASE)", "the stock base of FoodModel");
        Tuning.def("foodModel.stockW", 35, "rm-constants 0.39.u2 (was FoodModel.STOCK_W)", "the stock w of FoodModel");
        Tuning.def("foodModel.handMean", 0.4, "rm-constants 0.39.u2 (was FoodModel.HAND_MEAN)", "Energy points per minute when nothing is measured for a kind yet (hand values, replaced after 2 samples)");
        // foodOptions
        Tuning.def("foodOptions.herdMax", 400, "rm-constants 0.39.u2 (was FoodOptions.HERD_MAX)", "Herds this far or more are not offered (a round trip past the horizon)");
        Tuning.def("foodOptions.herdMinConf", 0.05, "rm-constants 0.39.u2 (was FoodOptions.HERD_MIN_CONF)", "Herds this far or more are not offered (a round trip past the horizon)");
        Tuning.def("foodOptions.eatCost", 2, "rm-constants 0.39.u2 (was FoodOptions.EAT_COST)", "the eat cost of FoodOptions");
        // foodPlan
        Tuning.def("foodPlan.low", 10, "rm-constants 0.39.u2 (was FoodPlan.LOW)", "Below this food level (of 20), check lists food first");
        // furnaceJobs
        Tuning.def("furnaceJobs.smeltMs", 10_000, "rm-constants 0.39.u2 (was FurnaceJobs.SMELT_MS)", "A vanilla furnace: 10 s an item");
        Tuning.def("furnaceJobs.slackMs", 2_000, "rm-constants 0.39.u2 (was FurnaceJobs.SLACK_MS)", "Added to a job's due time (the first item lights the furnace, the server's tick rate)");
        Tuning.def("furnaceJobs.expireMs", 30 * 60_000L, "rm-constants 0.39.u2 (was FurnaceJobs.EXPIRE_MS)", "A job is forgotten this long after it was due (package H's proposal), counted from this session's start at the earliest");
        Tuning.def("furnaceJobs.retryMs", 5 * 60_000L, "rm-constants 0.39.u2 (was FurnaceJobs.RETRY_MS)", "A pickup that couldn't run (busy, failed) is tried again after this");
        Tuning.def("furnaceJobs.stallMs", 60_000, "rm-constants 0.39.u2 (was FurnaceJobs.STALL_MS)", "Past its due time, no new output for this long while input is left");
        Tuning.def("furnaceJobs.busyWaitTicks", 1200, "rm-constants 0.39.u2 (was FurnaceJobs.BUSY_WAIT_TICKS)", "Every furnace busy: look again after this many ticks (a minute), at most BUSY_ROUNDS times");
        Tuning.def("furnaceJobs.busyRounds", 3, "rm-constants 0.39.u2 (was FurnaceJobs.BUSY_ROUNDS)", "the busy rounds of FurnaceJobs");
        Tuning.def("furnaceJobs.foreignFuelSmall", 16, "rm-constants 0.39.u2 (was FurnaceJobs.FOREIGN_FUEL_SMALL)", "Most a foreign fuel may smelt before the bot tops up with its own (16 items = 160 s of burning to wait out)");
        // goals
        Tuning.def("goals.high", 20, "rm-constants 0.39.u2 (was Goals.HIGH)", "the high of Goals");
        Tuning.def("goals.oreDefault", 8, "rm-constants 0.39.u2 (was Goals.ORE_DEFAULT)", "the ore default of Goals");
        Tuning.def("goals.freshMs", 5 * 60_000, "rm-constants 0.39.u2 (was Goals.FRESH_MS)", "A fresh goal may interrupt the running task (once) within this time");
        // gridLoop
        Tuning.def("gridLoop.maxPerCell", 64, "rm-constants 0.39.u2 (was GridLoop.MAX_PER_CELL)", "A stack per cell at most (and never more than the item's own stack size)");
        Tuning.def("gridLoop.clearTicks", 4, "rm-constants 0.39.u2 (was GridLoop.CLEAR_TICKS)", "Ticks after a clear before the next fill (the cleared items settle in the bag)");
        Tuning.def("gridLoop.settleMin", 2, "rm-constants 0.39.u2 (was GridLoop.SETTLE_MIN)", "Ticks after the shift-click before the batch is counted when it all arrived");
        Tuning.def("gridLoop.settleMax", 40, "rm-constants 0.39.u2 (was GridLoop.SETTLE_MAX)", "Ticks after the shift-click before the batch is counted when it all arrived");
        // guardCore
        Tuning.def("guardCore.maxLeaseVolume", 4096, "rm-constants 0.39.u2 (was GuardCore.MAX_LEASE_VOLUME)", "The biggest box a lease may cover (the bridge's dig limit)");
        Tuning.def("guardCore.maxForceVolume", 64, "rm-constants 0.39.u2 (was GuardCore.MAX_FORCE_VOLUME)", "A force lease");
        Tuning.def("guardCore.leaseTimeoutTicks", 100, "rm-constants 0.39.u2 (was GuardCore.LEASE_TIMEOUT_TICKS)", "Ticks without a heartbeat after which a lease dies");
        // help
        Tuning.def("help.far", 48, "rm-constants 0.39.u2 (was Help.FAR)", "the far of Help");
        Tuning.def("help.comeFrom", 16, "rm-constants 0.39.u2 (was Help.COME_FROM)", "the come from of Help");
        Tuning.def("help.giveMin", 8, "rm-constants 0.39.u2 (was Help.GIVE_MIN)", "the give min of Help");
        Tuning.def("help.giveN", 32, "rm-constants 0.39.u2 (was Help.GIVE_N)", "the give n of Help");
        Tuning.def("help.torchesMin", 4, "rm-constants 0.39.u2 (was Help.TORCHES_MIN)", "the torches min of Help");
        Tuning.def("help.lightR", 8, "rm-constants 0.39.u2 (was Help.LIGHT_R)", "the light r of Help");
        Tuning.def("help.mobsMax", 3, "rm-constants 0.39.u2 (was Help.MOBS_MAX)", "the mobs max of Help");
        Tuning.def("help.cap", 74, "rm-constants 0.39.u2 (was Help.CAP)", "the cap of Help");
        // hitLog
        Tuning.def("hitLog.deathHits", 20, "rm-constants 0.39.u2 (was HitLog.DEATH_HITS)", "the death hits of HitLog");
        // holdRules
        Tuning.def("holdRules.defendR", 4, "rm-constants 0.39.u2 (was HoldRules.DEFEND_R)", "the defend r of HoldRules");
        Tuning.def("holdRules.guardR", 8, "rm-constants 0.39.u2 (was HoldRules.GUARD_R)", "the guard r of HoldRules");
        // hostileRules
        Tuning.def("hostileRules.cap", 100, "rm-constants 0.39.u2 (was HostileRules.CAP)", "At most this many ids on the list");
        Tuning.def("hostileRules.maxAttackers", 16, "rm-constants 0.39.u2 (was HostileRules.MAX_ATTACKERS)", "At most this many attackers are remembered at once (oldest dropped)");
        Tuning.def("hostileRules.strongHp", 100, "rm-constants 0.39.u2 (was HostileRules.STRONG_HP)", "A stranger with this much max health or more is fled from, not fought (the combat plan's boss bar, 2.1)");
        Tuning.def("hostileRules.strongHits", 10, "rm-constants 0.39.u2 (was HostileRules.STRONG_HITS)", "... or with this many times the bot's own weapon hit");
        // hotbarKeeper
        Tuning.def("hotbarKeeper.idleTicks", 40, "rm-constants 0.39.u2 (was HotbarKeeper.IDLE_TICKS)", "Ticks of quiet before an idle swap");
        Tuning.def("hotbarKeeper.gap", 10, "rm-constants 0.39.u2 (was HotbarKeeper.GAP)", "Ticks of quiet before an idle swap");
        Tuning.def("hotbarKeeper.startTicks", 200, "rm-constants 0.39.u2 (was HotbarKeeper.START_TICKS)", "Ticks of quiet before an idle swap");
        // houseGround
        Tuning.def("houseGround.maxDepth", 4, "rm-constants 0.39.u2 (was HouseGround.MAX_DEPTH)", "The deepest pillar under a floor cell");
        Tuning.def("houseGround.height", 5, "rm-constants 0.39.u2 (was HouseGround.HEIGHT)", "A house chosen from the terrain is this many blocks high (floor to roof)");
        // housePlan
        Tuning.def("housePlan.minSide", 7, "rm-constants 0.39.u2 (was HousePlan.MIN_SIDE)", "The smallest area: a 5x5 inside with 1-thick walls, floor and roof");
        Tuning.def("housePlan.minHeight", 5, "rm-constants 0.39.u2 (was HousePlan.MIN_HEIGHT)", "The smallest area: a 5x5 inside with 1-thick walls, floor and roof");
        Tuning.def("housePlan.digMax", 1000, "rm-constants 0.39.u2 (was HousePlan.DIG_MAX)", "A box dig over this many cells asks for confirm");
        Tuning.def("housePlan.scanRadius", 4, "rm-constants 0.39.u2 (was HousePlan.SCAN_RADIUS)", "The house's own scan");
        // hungerConserve
        Tuning.def("hungerConserve.lapseMs", 3_000, "rm-constants 0.39.u2 (was HungerConserve.LAPSE_MS)", "The flag lapses this long after the last renewal (the AA tick runs every 250 ms)");
        // ironOptions
        Tuning.def("ironOptions.clear", 10, "rm-constants 0.39.u2 (was IronOptions.CLEAR)", "the clear of IronOptions");
        Tuning.def("ironOptions.digBlock", 3, "rm-constants 0.39.u2 (was IronOptions.DIG_BLOCK)", "the dig block of IronOptions");
        Tuning.def("ironOptions.placeBlock", 5, "rm-constants 0.39.u2 (was IronOptions.PLACE_BLOCK)", "the place block of IronOptions");
        Tuning.def("ironOptions.caveTime", 120, "rm-constants 0.39.u2 (was IronOptions.CAVE_TIME)", "the cave time of IronOptions");
        Tuning.def("ironOptions.stripTime", 200, "rm-constants 0.39.u2 (was IronOptions.STRIP_TIME)", "the strip time of IronOptions");
        Tuning.def("ironOptions.stale", 1.5, "rm-constants 0.39.u2 (was IronOptions.STALE)", "the stale of IronOptions");
        Tuning.def("ironOptions.caveFace", 2, "rm-constants 0.39.u2 (was IronOptions.CAVE_FACE)", "the cave face of IronOptions");
        Tuning.def("ironOptions.digDeepBlock", 6, "rm-constants 0.39.u2 (was IronOptions.DIG_DEEP_BLOCK)", "the dig deep block of IronOptions");
        Tuning.def("ironOptions.digHard", 40, "rm-constants 0.39.u2 (was IronOptions.DIG_HARD)", "the dig hard of IronOptions");
        Tuning.def("ironOptions.stripBlocks", 60, "rm-constants 0.39.u2 (was IronOptions.STRIP_BLOCKS)", "Blocks a strip tunnel digs for one find (its wear term)");
        Tuning.def("ironOptions.digMax", 16, "rm-constants 0.39.u2 (was IronOptions.DIG_MAX)", "the dig max of IronOptions");
        Tuning.def("ironOptions.pillarMinUp", 3, "rm-constants 0.39.u2 (was IronOptions.PILLAR_MIN_UP)", "the pillar min up of IronOptions");
        Tuning.def("ironOptions.smeltWalk", 24, "rm-constants 0.39.u2 (was IronOptions.SMELT_WALK)", "the smelt walk of IronOptions");
        Tuning.def("ironOptions.furnaceCobble", 8, "rm-constants 0.39.u2 (was IronOptions.FURNACE_COBBLE)", "the furnace cobble of IronOptions");
        Tuning.def("ironOptions.smeltHereCost", 10, "rm-constants 0.39.u2 (was IronOptions.SMELT_HERE_COST)", "the smelt here cost of IronOptions");
        Tuning.def("ironOptions.defaultN", 3, "rm-constants 0.39.u2 (was IronOptions.DEFAULT_N)", "the default n of IronOptions");
        // itemWorthSource
        Tuning.def("itemWorthSource.harmP", 0.5f, "rm-constants 0.39.u2 (was ItemWorthSource.HARM_P)", "A harmful effect that happens at least this often makes a food poison");
        // jobPort
        Tuning.def("jobPort.timeoutMs", 20 * 60_000, "rm-constants 0.39.u2 (was JobPort.TIMEOUT_MS)", "the timeout ms of JobPort");
        Tuning.def("jobPort.noProgressMs", 180_000, "rm-constants 0.39.u2 (was JobPort.NO_PROGRESS_MS)", "the no progress ms of JobPort");
        Tuning.def("jobPort.maxMs", 60 * 60_000, "rm-constants 0.39.u2 (was JobPort.MAX_MS)", "the max ms of JobPort");
        // jobs
        Tuning.def("jobs.travelFails", 3, "rm-constants 0.39.u2 (was Jobs.TRAVEL_FAILS)", "the travel fails of Jobs");
        // kelpRules
        Tuning.def("kelpRules.defaultN", 8, "rm-constants 0.39.u2 (was KelpRules.DEFAULT_N)", "the default n of KelpRules");
        // kelping
        // kit
        Tuning.def("kit.minRecover", 2, "rm-constants 0.39.u2 (was Kit.MIN_RECOVER)", "Under this missing worth there is nothing to go back for");
        Tuning.def("kit.minStash", 3, "rm-constants 0.39.u2 (was Kit.MIN_STASH)", "Under this loot worth there is nothing to stash");
        Tuning.def("kit.stashLead", 1_500, "rm-constants 0.39.u2 (was Kit.STASH_LEAD)", "The stash rises over this many ticks before dusk (75 s)");
        Tuning.def("kit.farTrip", 64, "rm-constants 0.39.u2 (was Kit.FAR_TRIP)", "A trip this far from the base is a far trip (blocks)");
        // ledger
        Tuning.def("ledger.confirmAfter", 5, "rm-constants 0.39.u2 (was Ledger.CONFIRM_AFTER)", "A pending break is looked at after this many ticks, and dropped when the cell is still full after the max");
        Tuning.def("ledger.confirmMax", 40, "rm-constants 0.39.u2 (was Ledger.CONFIRM_MAX)", "A pending break is looked at after this many ticks, and dropped when the cell is still full after the max");
        // lightGrid
        Tuning.def("lightGrid.step", 6, "rm-constants 0.39.u2 (was LightGrid.STEP)", "the step of LightGrid");
        Tuning.def("lightGrid.maxR", 32, "rm-constants 0.39.u2 (was LightGrid.MAX_R)", "the max r of LightGrid");
        Tuning.def("lightGrid.maxSpots", 64, "rm-constants 0.39.u2 (was LightGrid.MAX_SPOTS)", "the max spots of LightGrid");
        // litter
        Tuning.def("litter.surfaceS", 30, "rm-constants 0.39.u2 (was Litter.SURFACE_S)", "Seconds of litter per block left standing, by context");
        Tuning.def("litter.caveS", 5, "rm-constants 0.39.u2 (was Litter.CAVE_S)", "Seconds of litter per block left standing, by context");
        Tuning.def("litter.sealedS", 2, "rm-constants 0.39.u2 (was Litter.SEALED_S)", "Seconds of litter per block left standing, by context");
        Tuning.def("litter.nearBuild", 16, "rm-constants 0.39.u2 (was Litter.NEAR_BUILD)", "A block this close to a known place, area edge or player build is on show");
        Tuning.def("litter.far", 9999, "rm-constants 0.39.u2 (was Litter.FAR)", "A block this close to a known place, area edge or player build is on show");
        Tuning.def("litter.cleanupExtraS", 0.5, "rm-constants 0.39.u2 (was Litter.CLEANUP_EXTRA_S)", "Extra seconds per block to take it down on top of the break time (aim, drop, step)");
        Tuning.def("litter.unknownSavedS", 60, "rm-constants 0.39.u2 (was Litter.UNKNOWN_SAVED_S)", "Time saved by a crossing when the way around is unknown");
        Tuning.def("litter.placeS", 0.6, "rm-constants 0.39.u2 (was Litter.PLACE_S)", "Seconds to place one scaffold block (jump, look, place)");
        Tuning.def("litter.reachUp", 5, "rm-constants 0.39.u2 (was Litter.REACH_UP)", "Standing on the ground the bot reaches this many blocks above its feet's level with the trunk in sight");
        Tuning.def("litter.logValueS", 4, "rm-constants 0.39.u2 (was Litter.LOG_VALUE_S)", "What a log is worth when it has to be found elsewhere (seconds)");
        // mineGeom
        Tuning.def("mineGeom.legFar", 48, "rm-constants 0.39.u2 (was MineGeom.LEG_FAR)", "A run that starts further than this from its first box (or LEG_LEVELS levels off) walks there in legs");
        Tuning.def("mineGeom.legLevels", 10, "rm-constants 0.39.u2 (was MineGeom.LEG_LEVELS)", "A run that starts further than this from its first box (or LEG_LEVELS levels off) walks there in legs");
        Tuning.def("mineGeom.legLen", 40, "rm-constants 0.39.u2 (was MineGeom.LEG_LEN)", "A run that starts further than this from its first box (or LEG_LEVELS levels off) walks there in legs");
        // mineNotes
        Tuning.def("mineNotes.retryMax", 6000, "rm-constants 0.39.u2 (was MineNotes.RETRY_MAX)", "S1: after a failed write the next try waits FLUSH_AFTER, doubling per failure, at most RETRY_MAX ticks (5 min)");
        // mineRules
        Tuning.def("mineRules.protectMargin", 16, "rm-constants 0.39.u2 (was MineRules.PROTECT_MARGIN)", "Policy: the distance Baritone's free mine keeps from protect boxes (the guard's no-break)");
        Tuning.def("mineRules.leaseMax", 4096, "rm-constants 0.39.u2 (was MineRules.LEASE_MAX)", "A lease covers at most this many blocks (GuardCore.MAX_LEASE_VOLUME)");
        Tuning.def("mineRules.atVein", 8, "rm-constants 0.39.u2 (was MineRules.AT_VEIN)", "The at form's vein: at most this many cells, the target included");
        // mining
        Tuning.def("mining.gatherMax", 8, "rm-constants 0.39.u2 (was Mining.GATHER_MAX)", "0.23.1: at most this many gathers (a tree, a surface vein) per explore/find run");
        Tuning.def("mining.gatherEvery", 40, "rm-constants 0.39.u2 (was Mining.GATHER_EVERY)", "0.23.1: at most this many gathers (a tree, a surface vein) per explore/find run");
        // mobDamage
        Tuning.def("mobDamage.alpha", 0.3, "rm-constants 0.39.u2 (was MobDamage.ALPHA)", "the alpha of MobDamage");
        Tuning.def("mobDamage.minSamples", 3, "rm-constants 0.39.u2 (was MobDamage.MIN_SAMPLES)", "the min samples of MobDamage");
        // moddedSolids
        Tuning.def("moddedSolids.low", 0.2, "rm-constants 0.39.u2 (was ModdedSolids.LOW)", "Collision shapes up to this high (carpets, plates, modded farmland edges) stay walk-through");
        // moveRules
        Tuning.def("moveRules.defaultDanger", 4, "rm-constants 0.39.u2 (was MoveRules.DEFAULT_DANGER)", "The owner's default (AREAS_PLAN 10.7)");
        Tuning.def("moveRules.outSlack", 2, "rm-constants 0.39.u2 (was MoveRules.OUT_SLACK)", "Slack beyond the shortest way out a cell may lie and still be walked (round obstacles)");
        // nearZone
        Tuning.def("nearZone.defaultR", 16, "rm-constants 0.39.u2 (was NearZone.DEFAULT_R)", "the default r of NearZone");
        Tuning.def("nearZone.minR", 4, "rm-constants 0.39.u2 (was NearZone.MIN_R)", "the min r of NearZone");
        Tuning.def("nearZone.maxR", 64, "rm-constants 0.39.u2 (was NearZone.MAX_R)", "the max r of NearZone");
        Tuning.def("nearZone.yHalf", 16, "rm-constants 0.39.u2 (was NearZone.Y_HALF)", "Blocks below and above the owner's feet the zone reaches");
        Tuning.def("nearZone.unknownNoteMs", 10 * 60_000L, "rm-constants 0.39.u2 (was NearZone.UNKNOWN_NOTE_MS)", "Owner's spot unknown this long while the zone is on (and the owner online)");
        // news
        Tuning.def("news.death", 4, "rm-constants 0.39.u2 (was News.DEATH)", "the death of News");
        Tuning.def("news.damage", 3, "rm-constants 0.39.u2 (was News.DAMAGE)", "the damage of News");
        Tuning.def("news.tool", 3, "rm-constants 0.39.u2 (was News.TOOL)", "the tool of News");
        Tuning.def("news.detour", 2, "rm-constants 0.39.u2 (was News.DETOUR)", "the detour of News");
        Tuning.def("news.hostile", 2, "rm-constants 0.39.u2 (was News.HOSTILE)", "the hostile of News");
        Tuning.def("news.time", 1, "rm-constants 0.39.u2 (was News.TIME)", "the time of News");
        // nightWork
        Tuning.def("nightWork.shaft", 2, "rm-constants 0.39.u2 (was NightWork.SHAFT)", "the shaft of NightWork");
        Tuning.def("nightWork.stairs", 24, "rm-constants 0.39.u2 (was NightWork.STAIRS)", "the stairs of NightWork");
        // options
        Tuning.def("options.maxSwitches", 4, "rm-constants 0.39.u2 (was Options.MAX_SWITCHES)", "the max switches of Options");
        Tuning.def("options.retryMs", 10 * 60_000, "rm-constants 0.39.u2 (was Options.RETRY_MS)", "A failed option is skipped this long per failure (at most an hour)");
        Tuning.def("options.retryMaxMs", 60 * 60_000, "rm-constants 0.39.u2 (was Options.RETRY_MAX_MS)", "A failed option is skipped this long per failure (at most an hour)");
        // oreDetour
        Tuning.def("oreDetour.cap", 8, "rm-constants 0.39.u2 (was OreDetour.CAP)", "the cap of OreDetour");
        Tuning.def("oreDetour.maxUp", 4, "rm-constants 0.39.u2 (was OreDetour.MAX_UP)", "the max up of OreDetour");
        // oreStats
        Tuning.def("oreStats.band", 8, "rm-constants 0.39.u2 (was OreStats.BAND)", "The peak is the busiest band of this many blocks");
        // oreValue
        Tuning.def("oreValue.dropS", 60, "rm-constants 0.39.u2 (was OreValue.DROP_S)", "the drop s of OreValue");
        Tuning.def("oreValue.digS", 1.2, "rm-constants 0.39.u2 (was OreValue.DIG_S)", "Dig time per ore block with the right tool (s)");
        // ownTable
        Tuning.def("ownTable.reachNear", 12, "rm-constants 0.39.u2 (was OwnTable.REACH_NEAR)", "A table this far (eye to block) or nearer is used as it is");
        Tuning.def("ownTable.pitDepth", 3, "rm-constants 0.39.u2 (was OwnTable.PIT_DEPTH)", "A table this many blocks or more below the bot's feet sits in a pit");
        Tuning.def("ownTable.pickS", 4, "rm-constants 0.39.u2 (was OwnTable.PICK_S)", "Seconds: pick it up, place it again, remake one (planks / no planks), a stray block left in the world, a table at a camp without one");
        Tuning.def("ownTable.placeS", 2, "rm-constants 0.39.u2 (was OwnTable.PLACE_S)", "Seconds: pick it up, place it again, remake one (planks / no planks), a stray block left in the world, a table at a camp without one");
        Tuning.def("ownTable.remakeS", 8, "rm-constants 0.39.u2 (was OwnTable.REMAKE_S)", "Seconds: pick it up, place it again, remake one (planks / no planks), a stray block left in the world, a table at a camp without one");
        Tuning.def("ownTable.remakeNoneS", 60, "rm-constants 0.39.u2 (was OwnTable.REMAKE_NONE_S)", "Seconds: pick it up, place it again, remake one (planks / no planks), a stray block left in the world, a table at a camp without one");
        Tuning.def("ownTable.litterS", 10, "rm-constants 0.39.u2 (was OwnTable.LITTER_S)", "Seconds: pick it up, place it again, remake one (planks / no planks), a stray block left in the world, a table at a camp without one");
        Tuning.def("ownTable.campValueS", 15, "rm-constants 0.39.u2 (was OwnTable.CAMP_VALUE_S)", "Seconds: pick it up, place it again, remake one (planks / no planks), a stray block left in the world, a table at a camp without one");
        // ownerFix
        Tuning.def("ownerFix.maxAgeMs", 10_000, "rm-constants 0.39.u2 (was OwnerFix.MAX_AGE_MS)", "the max age ms of OwnerFix");
        Tuning.def("ownerFix.futureSlackMs", 2_000, "rm-constants 0.39.u2 (was OwnerFix.FUTURE_SLACK_MS)", "A fix 'from the future' (the clock was set back) is refused beyond this");
        // paperPlan
        Tuning.def("paperPlan.primaryMs", 4000, "rm-constants 0.39.u2 (was PaperPlan.PRIMARY_MS)", "the primary ms of PaperPlan");
        Tuning.def("paperPlan.failureMs", 20000, "rm-constants 0.39.u2 (was PaperPlan.FAILURE_MS)", "the failure ms of PaperPlan");
        Tuning.def("paperPlan.surfaceR", 96, "rm-constants 0.39.u2 (was PaperPlan.SURFACE_R)", "the surface r of PaperPlan");
        // placeRules
        Tuning.def("placeRules.tableNear", 24, "rm-constants 0.39.u2 (was PlaceRules.TABLE_NEAR)", "How far a table may be for the pickaxe craft to walk to it rather than put one down");
        Tuning.def("placeRules.mobWaitTicks", 200, "rm-constants 0.39.u2 (was PlaceRules.MOB_WAIT_TICKS)", "0.30.7: a mob in the cell is waited for this long (ticks, 10 s) before the place step gives up");
        Tuning.def("placeRules.emptyCorpseWaitTicks", 1800, "rm-constants 0.39.u2 (was PlaceRules.EMPTY_CORPSE_WAIT_TICKS)", "0.30.7 (live probe): an emptied corpse the client still lists is waited for this long");
        Tuning.def("placeRules.leaseMax", 4096, "rm-constants 0.39.u2 (was PlaceRules.LEASE_MAX)", "The most one lease may cover (GuardCore.MAX_LEASE_VOLUME), and a force lease (MAX_FORCE_VOLUME)");
        Tuning.def("placeRules.forceMax", 64, "rm-constants 0.39.u2 (was PlaceRules.FORCE_MAX)", "The most one lease may cover (GuardCore.MAX_LEASE_VOLUME), and a force lease (MAX_FORCE_VOLUME)");
        Tuning.def("placeRules.bigDig", 300, "rm-constants 0.39.u2 (was PlaceRules.BIG_DIG)", "A dig bigger than this restocks its stone pickaxes first");
        Tuning.def("placeRules.stonePicksDefault", 3, "rm-constants 0.39.u2 (was PlaceRules.STONE_PICKS_DEFAULT)", "the stone picks default of PlaceRules");
        // placedPlan
        Tuning.def("placedPlan.defaultRadius", 24, "rm-constants 0.39.u2 (was PlacedPlan.DEFAULT_RADIUS)", "The walk-back limit of 'restore placed' without a radius");
        // planner
        Tuning.def("planner.maxNodes", 2000, "rm-constants 0.39.u2 (was Planner.MAX_NODES)", "the max nodes of Planner");
        Tuning.def("planner.maxSteps", 24, "rm-constants 0.39.u2 (was Planner.MAX_STEPS)", "the max steps of Planner");
        Tuning.def("planner.budgetMs", 200, "rm-constants 0.39.u2 (was Planner.BUDGET_MS)", "the budget ms of Planner");
        Tuning.def("planner.shelterBlocks", 32, "rm-constants 0.39.u2 (was Planner.SHELTER_BLOCKS)", "the shelter blocks of Planner");
        // policyStats
        Tuning.def("policyStats.differFull", 5, "rm-constants 0.39.u2 (was PolicyStats.DIFFER_FULL)", "How many 'would differ' lines go to the log in full before the rate limit (one per LOG_EVERY_MS) applies");
        // predictions
        Tuning.def("predictions.tolerance", 0.25, "rm-constants 0.39.u2 (was Predictions.TOLERANCE)", "the tolerance of Predictions");
        Tuning.def("predictions.minSamples", 5, "rm-constants 0.39.u2 (was Predictions.MIN_SAMPLES)", "the min samples of Predictions");
        Tuning.def("predictions.last", 20, "rm-constants 0.39.u2 (was Predictions.LAST)", "the last of Predictions");
        // reconnectRules
        Tuning.def("reconnectRules.laterWait", 36000, "rm-constants 0.39.u2 (was ReconnectRules.LATER_WAIT)", "T4: the wait after those, in ticks");
        Tuning.def("reconnectRules.giveUpMs", 24L * 3_600_000L, "rm-constants 0.39.u2 (was ReconnectRules.GIVE_UP_MS)", "T4: it stops trying this long after it first found itself out of a world");
        Tuning.def("reconnectRules.perHour", 3, "rm-constants 0.39.u2 (was ReconnectRules.PER_HOUR)", "the per hour of ReconnectRules");
        Tuning.def("reconnectRules.timeoutDefaultS", 60, "rm-constants 0.39.u2 (was ReconnectRules.TIMEOUT_DEFAULT_S)", "the timeout default s of ReconnectRules");
        Tuning.def("reconnectRules.timeoutMinS", 15, "rm-constants 0.39.u2 (was ReconnectRules.TIMEOUT_MIN_S)", "the timeout min s of ReconnectRules");
        Tuning.def("reconnectRules.timeoutMaxS", 600, "rm-constants 0.39.u2 (was ReconnectRules.TIMEOUT_MAX_S)", "the timeout max s of ReconnectRules");
        // reflexNotes
        Tuning.def("reflexNotes.noFoodAt", 6, "rm-constants 0.39.u2 (was ReflexNotes.NO_FOOD_AT)", "the no food at of ReflexNotes");
        // reflexRules
        Tuning.def("reflexRules.eatFood", 14, "rm-constants 0.39.u2 (was ReflexRules.EAT_FOOD)", "eat at this food level or below");
        Tuning.def("reflexRules.goldenBelow", 12f, "rm-constants 0.39.u2 (was ReflexRules.GOLDEN_BELOW)", "health under 6 hearts");
        Tuning.def("reflexRules.retreatAt", 6f, "rm-constants 0.39.u2 (was ReflexRules.RETREAT_AT)", "health at or below: get away");
        Tuning.def("reflexRules.look", 8, "rm-constants 0.39.u2 (was ReflexRules.LOOK)", "monsters within this many blocks count");
        Tuning.def("reflexRules.lookHurt", 24, "rm-constants 0.39.u2 (was ReflexRules.LOOK_HURT)", "... or this many after a hit");
        Tuning.def("reflexRules.hurtTicks", 100, "rm-constants 0.39.u2 (was ReflexRules.HURT_TICKS)", "'after a hit' lasts this long");
        Tuning.def("reflexRules.shoreRadius", 32, "rm-constants 0.39.u2 (was ReflexRules.SHORE_RADIUS)", "0.34.3: how far the swim to the shore looks, and how far up or down from the water line");
        Tuning.def("reflexRules.shoreDy", 4, "rm-constants 0.39.u2 (was ReflexRules.SHORE_DY)", "0.34.3: how far the swim to the shore looks, and how far up or down from the water line");
        Tuning.def("reflexRules.reach", 2.8, "rm-constants 0.39.u2 (was ReflexRules.REACH)", "attack from this close");
        Tuning.def("reflexRules.urgent", 5, "rm-constants 0.39.u2 (was ReflexRules.URGENT)", "a monster this close stops a bridge job (as the bridge did)");
        Tuning.def("reflexRules.eatUrgent", 6, "rm-constants 0.39.u2 (was ReflexRules.EAT_URGENT)", "aa-food: at or below this the bar is urgent");
        // reflexes
        Tuning.def("reflexes.forcedRange", 24, "rm-constants 0.39.u2 (was Reflexes.FORCED_RANGE)", "---- attack | nearest (C1, 0.20.5)");
        Tuning.def("reflexes.forcedTicks", 600, "rm-constants 0.39.u2 (was Reflexes.FORCED_TICKS)", "30 s");
        Tuning.def("reflexes.creeperOverride", 10, "rm-constants 0.39.u2 (was Reflexes.CREEPER_OVERRIDE)", "0.32.1: a creeper this close is the target whatever else is near (the owner's rule)");
        // replan
        Tuning.def("replan.refill", 0.05, "rm-constants 0.39.u2 (was Replan.REFILL)", "the refill of Replan");
        Tuning.def("replan.climbBlock", 3, "rm-constants 0.39.u2 (was Replan.CLIMB_BLOCK)", "the climb block of Replan");
        Tuning.def("replan.climbFix", 10, "rm-constants 0.39.u2 (was Replan.CLIMB_FIX)", "the climb fix of Replan");
        Tuning.def("replan.cheaper", 0.30, "rm-constants 0.39.u2 (was Replan.CHEAPER)", "the cheaper of Replan");
        Tuning.def("replan.minSave", 3, "rm-constants 0.39.u2 (was Replan.MIN_SAVE)", "the min save of Replan");
        Tuning.def("replan.everyMs", 5_000, "rm-constants 0.39.u2 (was Replan.EVERY_MS)", "the every ms of Replan");
        Tuning.def("replan.longStepMs", 15_000, "rm-constants 0.39.u2 (was Replan.LONG_STEP_MS)", "the long step ms of Replan");
        Tuning.def("replan.lockMs", 20_000, "rm-constants 0.39.u2 (was Replan.LOCK_MS)", "the lock ms of Replan");
        Tuning.def("replan.tunnelCap", 40, "rm-constants 0.39.u2 (was Replan.TUNNEL_CAP)", "the tunnel cap of Replan");
        Tuning.def("replan.ownBreak", 1, "rm-constants 0.39.u2 (was Replan.OWN_BREAK)", "the own break of Replan");
        Tuning.def("replan.foreignBreak", 6, "rm-constants 0.39.u2 (was Replan.FOREIGN_BREAK)", "the foreign break of Replan");
        Tuning.def("replan.upPlace", 0.05, "rm-constants 0.39.u2 (was Replan.UP_PLACE)", "the up place of Replan");
        Tuning.def("replan.tunnelBetter", 0.20, "rm-constants 0.39.u2 (was Replan.TUNNEL_BETTER)", "the tunnel better of Replan");
        Tuning.def("replan.lava", 1000, "rm-constants 0.39.u2 (was Replan.LAVA)", "Hazard costs per cell (walk-blocks)");
        Tuning.def("replan.water", 60, "rm-constants 0.39.u2 (was Replan.WATER)", "Hazard costs per cell (walk-blocks)");
        Tuning.def("replan.falling", 40, "rm-constants 0.39.u2 (was Replan.FALLING)", "Hazard costs per cell (walk-blocks)");
        Tuning.def("replan.overCap", 25, "rm-constants 0.39.u2 (was Replan.OVER_CAP)", "Hazard costs per cell (walk-blocks)");
        Tuning.def("replan.noBlock", 30, "rm-constants 0.39.u2 (was Replan.NO_BLOCK)", "Hazard costs per cell (walk-blocks)");
        Tuning.def("replan.wander", 24, "rm-constants 0.39.u2 (was Replan.WANDER)", "Wandered farther than this from the planned target during the ore step");
        // respawnHomes
        Tuning.def("respawnHomes.settleTicks", 100, "rm-constants 0.39.u2 (was RespawnHomes.SETTLE_TICKS)", "the settle ticks of RespawnHomes");
        Tuning.def("respawnHomes.maxHomes", 8, "rm-constants 0.39.u2 (was RespawnHomes.MAX_HOMES)", "the max homes of RespawnHomes");
        // restoreArgs
        Tuning.def("restoreArgs.maxRadius", 64, "rm-constants 0.39.u2 (was RestoreArgs.MAX_RADIUS)", "the max radius of RestoreArgs");
        // restorePlan
        Tuning.def("restorePlan.afterJob", 24, "rm-constants 0.39.u2 (was RestorePlan.AFTER_JOB)", "The walk-back limit after a job (further entries wait for 'restore now')");
        Tuning.def("restorePlan.nowDefault", 32, "rm-constants 0.39.u2 (was RestorePlan.NOW_DEFAULT)", "The walk-back limit after a job (further entries wait for 'restore now')");
        // restoreRun
        Tuning.def("restoreRun.walkMax", 20 * 60, "rm-constants 0.39.u2 (was RestoreRun.WALK_MAX)", "the walk max of RestoreRun");
        Tuning.def("restoreRun.checkTicks", 6, "rm-constants 0.39.u2 (was RestoreRun.CHECK_TICKS)", "the check ticks of RestoreRun");
        Tuning.def("restoreRun.breakMax", 20 * 10, "rm-constants 0.39.u2 (was RestoreRun.BREAK_MAX)", "Seconds (ticks) one scaffold block may take to break");
        // rimPlan
        Tuning.def("rimPlan.maxTries", 2, "rm-constants 0.39.u2 (was RimPlan.MAX_TRIES)", "Tries per cell (a walk that ends out of reach or a click that did not stay both count)");
        Tuning.def("rimPlan.layerCapTicks", 90 * 20, "rm-constants 0.39.u2 (was RimPlan.LAYER_CAP_TICKS)", "The rim placer's cap per layer, in ticks (90 s)");
        // rungOptions
        Tuning.def("rungOptions.nothingSky", 8, "rm-constants 0.39.u2 (was RungOptions.NOTHING_SKY)", "S2 review (aa40b risk)");
        Tuning.def("rungOptions.climbBlock", 3, "rm-constants 0.39.u2 (was RungOptions.CLIMB_BLOCK)", "the climb block of RungOptions");
        Tuning.def("rungOptions.climbFix", 10, "rm-constants 0.39.u2 (was RungOptions.CLIMB_FIX)", "the climb fix of RungOptions");
        Tuning.def("rungOptions.pillarBlock", 1, "rm-constants 0.39.u2 (was RungOptions.PILLAR_BLOCK)", "the pillar block of RungOptions");
        Tuning.def("rungOptions.pillarFix", 5, "rm-constants 0.39.u2 (was RungOptions.PILLAR_FIX)", "the pillar fix of RungOptions");
        Tuning.def("rungOptions.skyWalk", 2, "rm-constants 0.39.u2 (was RungOptions.SKY_WALK)", "the sky walk of RungOptions");
        // safetyRules
        Tuning.def("safetyRules.protectEvery", 200, "rm-constants 0.39.u2 (was SafetyRules.PROTECT_EVERY)", "The protected list goes back into Baritone this often (and once as soon as the guard's floor is ready)");
        Tuning.def("safetyRules.netEvery", 200, "rm-constants 0.39.u2 (was SafetyRules.NET_EVERY)", "Breaking/placing with nobody owning it is turned off at this tick of every 200 (the bridge's 10 s net)");
        Tuning.def("safetyRules.netAt", 100, "rm-constants 0.39.u2 (was SafetyRules.NET_AT)", "Breaking/placing with nobody owning it is turned off at this tick of every 200 (the bridge's 10 s net)");
        // seen
        Tuning.def("seen.range", 24, "rm-constants 0.39.u2 (was Seen.RANGE)", "the range of Seen");
        Tuning.def("seen.raysPerSec", 64, "rm-constants 0.39.u2 (was Seen.RAYS_PER_SEC)", "the rays per sec of Seen");
        Tuning.def("seen.coneDeg", 60, "rm-constants 0.39.u2 (was Seen.CONE_DEG)", "Half-angle of the view cone (degrees)");
        Tuning.def("seen.priorRate", 0.5, "rm-constants 0.39.u2 (was Seen.PRIOR_RATE)", "The cave-yield prior");
        Tuning.def("seen.priorMin", 2.0, "rm-constants 0.39.u2 (was Seen.PRIOR_MIN)", "The cave-yield prior");
        // selfCheck
        Tuning.def("selfCheck.worn", 0.10, "rm-constants 0.39.u2 (was SelfCheck.WORN)", "A tool with this share of its uses left or less is nearly broken");
        Tuning.def("selfCheck.bagLow", 4, "rm-constants 0.39.u2 (was SelfCheck.BAG_LOW)", "Free bag slots at or below this");
        Tuning.def("selfCheck.companionStaleMs", 120_000, "rm-constants 0.39.u2 (was SelfCheck.COMPANION_STALE_MS)", "The companion's position older than this while the owner is online");
        Tuning.def("selfCheck.bagFull", 2, "rm-constants 0.39.u2 (was SelfCheck.BAG_FULL)", "Free bag slots at or below this");
        Tuning.def("selfCheck.whisperEveryMs", 30 * 60_000L, "rm-constants 0.39.u2 (was SelfCheck.WHISPER_EVERY_MS)", "At most one self-check whisper this often, across restarts (commands.json selfCheck.lastWhisper)");
        // selfCheckLive
        Tuning.def("selfCheckLive.idleEveryMs", 30 * 60_000L, "rm-constants 0.39.u2 (was SelfCheckLive.IDLE_EVERY_MS)", "the idle every ms of SelfCheckLive");
        Tuning.def("selfCheckLive.idleLookMs", 60_000L, "rm-constants 0.39.u2 (was SelfCheckLive.IDLE_LOOK_MS)", "0.21.2: the idle check looks this often (it whispers at most every SelfCheck#WHISPER_EVERY_MS)");
        // sequencer
        Tuning.def("sequencer.k", 6, "rm-constants 0.39.u2 (was Sequencer.K)", "the k of Sequencer");
        Tuning.def("sequencer.max", 3, "rm-constants 0.39.u2 (was Sequencer.MAX)", "the max of Sequencer");
        Tuning.def("sequencer.save", 0.15, "rm-constants 0.39.u2 (was Sequencer.SAVE)", "the save of Sequencer");
        Tuning.def("sequencer.defaultHorizonS", 600, "rm-constants 0.39.u2 (was Sequencer.DEFAULT_HORIZON_S)", "the default horizon s of Sequencer");
        // serverCmds
        Tuning.def("serverCmds.maxWalkDefault", 2000, "rm-constants 0.39.u2 (was ServerCmds.MAX_WALK_DEFAULT)", "the max walk default of ServerCmds");
        // shelterJob
        Tuning.def("shelterJob.placeTicks", 6, "rm-constants 0.39.u2 (was ShelterJob.PLACE_TICKS)", "backbone step 3: OUT 3 -> 10 s (Baritone plans the step first)");
        Tuning.def("shelterJob.breakTicks", 800, "rm-constants 0.39.u2 (was ShelterJob.BREAK_TICKS)", "backbone step 3: OUT 3 -> 10 s (Baritone plans the step first)");
        Tuning.def("shelterJob.outTicks", 200, "rm-constants 0.39.u2 (was ShelterJob.OUT_TICKS)", "backbone step 3: OUT 3 -> 10 s (Baritone plans the step first)");
        Tuning.def("shelterJob.maxWait", 20 * 60 * 20, "rm-constants 0.39.u2 (was ShelterJob.MAX_WAIT)", "backbone step 3: OUT 3 -> 10 s (Baritone plans the step first)");
        Tuning.def("shelterJob.skipTicks", 60, "rm-constants 0.39.u2 (was ShelterJob.SKIP_TICKS)", "0.27.4: ticks between the passes over the skipped cells (3 s), and the passes after the first (3)");
        Tuning.def("shelterJob.skipRounds", 3, "rm-constants 0.39.u2 (was ShelterJob.SKIP_ROUNDS)", "the skip rounds of ShelterJob");
        // shelterPlan
        Tuning.def("shelterPlan.full", 10, "rm-constants 0.39.u2 (was ShelterPlan.FULL)", "Blocks the full shell takes (4 at the feet, 4 at the head, the roof's support, the roof)");
        // shoreWatch
        Tuning.def("shoreWatch.shoreTries", 4, "rm-constants 0.39.u2 (was ShoreWatch.SHORE_TRIES)", "the shore tries of ShoreWatch");
        // sightings
        Tuning.def("sightings.animalRange", 32, "rm-constants 0.39.u2 (was Sightings.ANIMAL_RANGE)", "the animal range of Sightings");
        Tuning.def("sightings.merge", 16, "rm-constants 0.39.u2 (was Sightings.MERGE)", "the merge of Sightings");
        Tuning.def("sightings.maxCaves", 400, "rm-constants 0.39.u2 (was Sightings.MAX_CAVES)", "the max caves of Sightings");
        Tuning.def("sightings.maxAnimals", 400, "rm-constants 0.39.u2 (was Sightings.MAX_ANIMALS)", "the max animals of Sightings");
        Tuning.def("sightings.maxTrees", 300, "rm-constants 0.39.u2 (was Sightings.MAX_TREES)", "the max trees of Sightings");
        Tuning.def("sightings.treeMerge", 6, "rm-constants 0.39.u2 (was Sightings.TREE_MERGE)", "the tree merge of Sightings");
        Tuning.def("sightings.treeMinLogs", 3, "rm-constants 0.39.u2 (was Sightings.TREE_MIN_LOGS)", "the tree min logs of Sightings");
        Tuning.def("sightings.confirmMs", 2000, "rm-constants 0.39.u2 (was Sightings.CONFIRM_MS)", "the confirm ms of Sightings");
        Tuning.def("sightings.confirmMaxMs", 30_000, "rm-constants 0.39.u2 (was Sightings.CONFIRM_MAX_MS)", "the confirm max ms of Sightings");
        Tuning.def("sightings.halfLifeMs", 10 * 60_000, "rm-constants 0.39.u2 (was Sightings.HALF_LIFE_MS)", "the half life ms of Sightings");
        Tuning.def("sightings.missConf", 0.1, "rm-constants 0.39.u2 (was Sightings.MISS_CONF)", "the miss conf of Sightings");
        Tuning.def("sightings.priorCaves", 0.3, "rm-constants 0.39.u2 (was Sightings.PRIOR_CAVES)", "The cave-chance prior");
        Tuning.def("sightings.priorHundreds", 5, "rm-constants 0.39.u2 (was Sightings.PRIOR_HUNDREDS)", "The cave-chance prior");
        // site
        Tuning.def("site.same", 16, "rm-constants 0.39.u2 (was Site.SAME)", "A site this near another counts as the same place");
        // sleepJob
        Tuning.def("sleepJob.maxTicks", 15 * 60 * 20, "rm-constants 0.39.u2 (was SleepJob.MAX_TICKS)", "the max ticks of SleepJob");
        Tuning.def("sleepJob.search", 24, "rm-constants 0.39.u2 (was SleepJob.SEARCH)", "the search of SleepJob");
        // stairPlan
        Tuning.def("stairPlan.max", 40, "rm-constants 0.39.u2 (was StairPlan.MAX)", "the max of StairPlan");
        Tuning.def("stairPlan.torchEvery", 6, "rm-constants 0.39.u2 (was StairPlan.TORCH_EVERY)", "the torch every of StairPlan");
        Tuning.def("stairPlan.shaftDrop", 3, "rm-constants 0.39.u2 (was StairPlan.SHAFT_DROP)", "the shaft drop of StairPlan");
        Tuning.def("stairPlan.shaftScan", 64, "rm-constants 0.39.u2 (was StairPlan.SHAFT_SCAN)", "the shaft scan of StairPlan");
        // steerRules
        Tuning.def("steerRules.drivingMs", 1000, "rm-constants 0.39.u2 (was SteerRules.DRIVING_MS)", "How long after the last remapped tick the human counts as driving (the views hold their yaw meanwhile)");
        Tuning.def("steerRules.turnRateDefault", 45f, "rm-constants 0.39.u2 (was SteerRules.TURN_RATE_DEFAULT)", "Degrees per second: the default and the range of watch turn rate N");
        Tuning.def("steerRules.turnRateMin", 5f, "rm-constants 0.39.u2 (was SteerRules.TURN_RATE_MIN)", "Degrees per second: the default and the range of watch turn rate N");
        Tuning.def("steerRules.turnRateMax", 180f, "rm-constants 0.39.u2 (was SteerRules.TURN_RATE_MAX)", "Degrees per second: the default and the range of watch turn rate N");
        Tuning.def("steerRules.full", 5, "rm-constants 0.39.u2 (was SteerRules.FULL)", "the full of SteerRules");
        Tuning.def("steerRules.every", 100, "rm-constants 0.39.u2 (was SteerRules.EVERY)", "the every of SteerRules");
        Tuning.def("steerRules.trip", 20, "rm-constants 0.39.u2 (was SteerRules.TRIP)", "the trip of SteerRules");
        Tuning.def("steerRules.windowMs", 60_000, "rm-constants 0.39.u2 (was SteerRules.WINDOW_MS)", "the window ms of SteerRules");
        // stepRun
        Tuning.def("stepRun.animalRange", 32, "rm-constants 0.39.u2 (was StepRun.ANIMAL_RANGE)", "Blocks round me the food facts count animals in");
        // stepTimes
        Tuning.def("stepTimes.pathFactor", 1.3, "rm-constants 0.39.u2 (was StepTimes.PATH_FACTOR)", "TIME_MODEL: path length = straight line x 1.3 when no plan exists yet");
        Tuning.def("stepTimes.climbS", 0.3, "rm-constants 0.39.u2 (was StepTimes.CLIMB_S)", "TIME_MODEL: path length = straight line x 1.3 when no plan exists yet");
        // steps
        Tuning.def("steps.maxExpansions", 16, "rm-constants 0.39.u2 (was Steps.MAX_EXPANSIONS)", "At most this many expansions (a plan in a plan) per run");
        // storage
        Tuning.def("storage.guiSettle", 8, "rm-constants 0.39.u2 (was Storage.GUI_SETTLE)", "the gui settle of Storage");
        Tuning.def("storage.potsR", 16, "rm-constants 0.39.u2 (was Storage.POTS_R)", "the pots r of Storage");
        Tuning.def("storage.driveR", 16, "rm-constants 0.39.u2 (was Storage.DRIVE_R)", "the drive r of Storage");
        Tuning.def("storage.drivesMax", 4, "rm-constants 0.39.u2 (was Storage.DRIVES_MAX)", "the drives max of Storage");
        // stripRules
        Tuning.def("stripRules.turnAlong", 8, "rm-constants 0.39.u2 (was StripRules.TURN_ALONG)", "the turn along of StripRules");
        Tuning.def("stripRules.baseTripMin", 64, "rm-constants 0.39.u2 (was StripRules.BASE_TRIP_MIN)", "the base trip min of StripRules");
        Tuning.def("stripRules.baseTripFree", 4, "rm-constants 0.39.u2 (was StripRules.BASE_TRIP_FREE)", "the base trip free of StripRules");
        Tuning.def("stripRules.maxRuns", 30, "rm-constants 0.39.u2 (was StripRules.MAX_RUNS)", "the max runs of StripRules");
        Tuning.def("stripRules.maxTicks", 20L * 60 * 60, "rm-constants 0.39.u2 (was StripRules.MAX_TICKS)", "'mine strip' starts no new run after an hour");
        // summaryBuilder
        Tuning.def("summaryBuilder.mineshaftBelow", 45, "rm-constants 0.39.u2 (was SummaryBuilder.MINESHAFT_BELOW)", "the mineshaft below of SummaryBuilder");
        // survivalRules
        Tuning.def("survivalRules.healBelow", 6, "rm-constants 0.39.u2 (was SurvivalRules.HEAL_BELOW)", "the heal below of SurvivalRules");
        Tuning.def("survivalRules.healEveryTicks", 600, "rm-constants 0.39.u2 (was SurvivalRules.HEAL_EVERY_TICKS)", "the heal every ticks of SurvivalRules");
        Tuning.def("survivalRules.waterR", 8, "rm-constants 0.39.u2 (was SurvivalRules.WATER_R)", "the water r of SurvivalRules");
        // swimWatch
        Tuning.def("swimWatch.stallMs", 20_000, "rm-constants 0.39.u2 (was SwimWatch.STALL_MS)", "the stall ms of SwimWatch");
        // techTree
        Tuning.def("techTree.depth", 3, "rm-constants 0.39.u2 (was TechTree.DEPTH)", "the depth of TechTree");
        Tuning.def("techTree.discount", 0.5, "rm-constants 0.39.u2 (was TechTree.DISCOUNT)", "the discount of TechTree");
        // thing
        Tuning.def("thing.learnMin", 25, "rm-constants 0.39.u2 (was Thing.LEARN_MIN)", "Samples before what was learned beats the vanilla table (a few veins say little)");
        // threatFacts
        Tuning.def("threatFacts.shoreReach", 32, "rm-constants 0.39.u2 (was ThreatFacts.SHORE_REACH)", "AA_STEP3 A4: how far the shore scan looks (blocks, horizontally)");
        Tuning.def("threatFacts.lit", 8, "rm-constants 0.39.u2 (was ThreatFacts.LIT)", "Light this high is lit (spawning stops)");
        Tuning.def("threatFacts.litMaxDist", 24, "rm-constants 0.39.u2 (was ThreatFacts.LIT_MAX_DIST)", "Light this high is lit (spawning stops)");
        Tuning.def("threatFacts.rangedRange", 16, "rm-constants 0.39.u2 (was ThreatFacts.RANGED_RANGE)", "A melee mob hits once a second");
        // threatRules
        Tuning.def("threatRules.facingDeg", 35, "rm-constants 0.39.u2 (was ThreatRules.FACING_DEG)", "the facing deg of ThreatRules");
        Tuning.def("threatRules.creeperBar", 16, "rm-constants 0.39.u2 (was ThreatRules.CREEPER_BAR)", "the creeper bar of ThreatRules");
        Tuning.def("threatRules.creeperAlways", 6, "rm-constants 0.39.u2 (was ThreatRules.CREEPER_ALWAYS)", "the creeper always of ThreatRules");
        Tuning.def("threatRules.rangedRange", 16, "rm-constants 0.39.u2 (was ThreatRules.RANGED_RANGE)", "the ranged range of ThreatRules");
        Tuning.def("threatRules.teleportRange", 16, "rm-constants 0.39.u2 (was ThreatRules.TELEPORT_RANGE)", "the teleport range of ThreatRules");
        Tuning.def("threatRules.close", 3, "rm-constants 0.39.u2 (was ThreatRules.CLOSE)", "the close of ThreatRules");
        Tuning.def("threatRules.closeAny", 4, "rm-constants 0.39.u2 (was ThreatRules.CLOSE_ANY)", "0.23.2, the close rule");
        Tuning.def("threatRules.closingStep", 1, "rm-constants 0.39.u2 (was ThreatRules.CLOSING_STEP)", "Path distance must shrink by this much a sample (1 s) to count as closing in");
        Tuning.def("threatRules.sampleRadius", 24, "rm-constants 0.39.u2 (was ThreatRules.SAMPLE_RADIUS)", "Mobs further than this are not sampled at all (the reflexes look at most 24)");
        // threatRuntime
        Tuning.def("threatRuntime.r", 16, "rm-constants 0.39.u2 (was ThreatRuntime.R)", "the r of ThreatRuntime");
        Tuning.def("threatRuntime.down", 8, "rm-constants 0.39.u2 (was ThreatRuntime.DOWN)", "the down of ThreatRuntime");
        Tuning.def("threatRuntime.up", 8, "rm-constants 0.39.u2 (was ThreatRuntime.UP)", "the up of ThreatRuntime");
        Tuning.def("threatRuntime.period", 20, "rm-constants 0.39.u2 (was ThreatRuntime.PERIOD)", "the period of ThreatRuntime");
        Tuning.def("threatRuntime.fullTicks", 100, "rm-constants 0.39.u2 (was ThreatRuntime.FULL_TICKS)", "the full ticks of ThreatRuntime");
        Tuning.def("threatRuntime.freshMs", 12_000, "rm-constants 0.39.u2 (was ThreatRuntime.FRESH_MS)", "the fresh ms of ThreatRuntime");
        Tuning.def("threatRuntime.overrunMs", 5, "rm-constants 0.39.u2 (was ThreatRuntime.OVERRUN_MS)", "the overrun ms of ThreatRuntime");
        // toolWear
        Tuning.def("toolWear.craftS", 10, "rm-constants 0.39.u2 (was ToolWear.CRAFT_S)", "the craft s of ToolWear");
        Tuning.def("toolWear.gatherS", 120, "rm-constants 0.39.u2 (was ToolWear.GATHER_S)", "Review: with nothing to make one, a new pickaxe means gathering its materials first (~2 min), capped by its worth");
        Tuning.def("toolWear.stairBlocksPerUp", 3, "rm-constants 0.39.u2 (was ToolWear.STAIR_BLOCKS_PER_UP)", "Blocks a staircase digs per level up (the climb's 3-high headroom)");
        Tuning.def("toolWear.lowPct", 5, "rm-constants 0.39.u2 (was ToolWear.LOW_PCT)", "the low pct of ToolWear");
        Tuning.def("toolWear.nearLeft", 3, "rm-constants 0.39.u2 (was ToolWear.NEAR_LEFT)", "the near left of ToolWear");
        // tools
        Tuning.def("tools.oreTier", 3, "rm-constants 0.39.u2 (was Tools.ORE_TIER)", "The tier an ore pickaxe has to have under 'tools ores iron'");
        // tpRules
        Tuning.def("tpRules.homeR", 24, "rm-constants 0.39.u2 (was TpRules.HOME_R)", "The destination counts as 'near home' within this many blocks of the landing");
        Tuning.def("tpRules.across", 40, "rm-constants 0.39.u2 (was TpRules.ACROSS)", "Far: more than this many blocks across (x and z) from the destination");
        Tuning.def("tpRules.levels", 10, "rm-constants 0.39.u2 (was TpRules.LEVELS)", "...or more than this many levels up or down");
        Tuning.def("tpRules.retryTicks", 20 * 60, "rm-constants 0.39.u2 (was TpRules.RETRY_TICKS)", "No new /home this many ticks after one that didn't move the bot (1 minute)");
        Tuning.def("tpRules.waitTicks", 200, "rm-constants 0.39.u2 (was TpRules.WAIT_TICKS)", "A /home that hasn't moved the bot after this many ticks failed (10 s)");
        Tuning.def("tpRules.jumpSq", 64, "rm-constants 0.39.u2 (was TpRules.JUMP_SQ)", "The bot 'jumped' when it is more than this far");
        // upgrade
        Tuning.def("upgrade.margin", 1, "rm-constants 0.39.u2 (was Upgrade.MARGIN)", "Bag slots kept free in a round besides what the round needs (a pickup, a stray drop)");
        // utilitySelector
        Tuning.def("utilitySelector.bonus", 15, "rm-constants 0.39.u2 (was UtilitySelector.BONUS)", "the bonus of UtilitySelector");
        Tuning.def("utilitySelector.probeMs", 60_000, "rm-constants 0.39.u2 (was UtilitySelector.PROBE_MS)", "S0 (MINING_PLAN 12, the probe)");
        // veins
        Tuning.def("veins.maxVein", 24, "rm-constants 0.39.u2 (was Veins.MAX_VEIN)", "At most this many blocks per vein, and at most this far from the dug boxes");
        Tuning.def("veins.maxGap", 4, "rm-constants 0.39.u2 (was Veins.MAX_GAP)", "At most this many blocks per vein, and at most this far from the dug boxes");
        // walkEnd
        Tuning.def("walkEnd.tolerance", 3, "rm-constants 0.39.u2 (was WalkEnd.TOLERANCE)", "Blocks (straight line) from the goal a walk may end and still count as arrived");
        Tuning.def("walkEnd.xzTolerance", 5, "rm-constants 0.39.u2 (was WalkEnd.XZ_TOLERANCE)", "0.33.1: a two-number goto may end on top of a wall column or drift in a water column");
        Tuning.def("walkEnd.freeUp", 4, "rm-constants 0.39.u2 (was WalkEnd.FREE_UP)", "0.31.0 (5): how far up the same column, and how far around, a goto into a solid block looks for a free spot");
        Tuning.def("walkEnd.freeAround", 3, "rm-constants 0.39.u2 (was WalkEnd.FREE_AROUND)", "0.31.0 (5): how far up the same column, and how far around, a goto into a solid block looks for a free spot");
        Tuning.def("walkEnd.atGoal", 1.0, "rm-constants 0.39.u2 (was WalkEnd.AT_GOAL)", "Within this straight distance of the goal the walk counts as at it, whatever the goal cell is");
        // watchCamera
        Tuning.def("watchCamera.pitch", 25f, "rm-constants 0.39.u2 (was WatchCamera.PITCH)", "Camera pitch (degrees, positive = looking down) and the cap while watching");
        Tuning.def("watchCamera.watchFps", 60, "rm-constants 0.39.u2 (was WatchCamera.WATCH_FPS)", "the watch fps of WatchCamera");
        Tuning.def("watchCamera.moving", 0.03, "rm-constants 0.39.u2 (was WatchCamera.MOVING)", "Horizontal speed (blocks per tick) below which the bot counts as standing");
        Tuning.def("watchCamera.ease", 0.12f, "rm-constants 0.39.u2 (was WatchCamera.EASE)", "Share of the way to the walking direction covered per client tick (about 0.5 s to settle at 20 ticks/s)");
        // waterWays
        Tuning.def("waterWays.floatSlow", 5, "rm-constants 0.39.u2 (was WaterWays.FLOAT_SLOW)", "Blocks per second walking, swimming");
        // woodOptions
        Tuning.def("woodOptions.cutLog", 6, "rm-constants 0.39.u2 (was WoodOptions.CUT_LOG)", "the cut log of WoodOptions");
        Tuning.def("woodOptions.search", 60, "rm-constants 0.39.u2 (was WoodOptions.SEARCH)", "the search of WoodOptions");
        Tuning.def("woodOptions.farMax", 256, "rm-constants 0.39.u2 (was WoodOptions.FAR_MAX)", "the far max of WoodOptions");
        // ---- inline literals that were hand numbers in a decision (rm-constants part 2)
        Tuning.def("aaDesires.coolMin", 5, "rm-constants 0.39.u2 (inline literal in AaDesires)", "minutes a desire cools after its way failed");
        Tuning.def("aaDesires.dangerBedMin", 3, "rm-constants 0.39.u2 (inline literal in AaDesires)", "minutes a danger bed task may run when it is not night");
        Tuning.def("aaDesires.hereDy", 3, "rm-constants 0.39.u2 (inline literal in AaDesires)", "blocks of height difference within which a bot counts as at a spot");
        Tuning.def("aaDesires.ironBase", 40, "rm-constants 0.39.u2 (inline literal in AaDesires)", "the iron need score floor");
        Tuning.def("aaDesires.ironSpan", 30, "rm-constants 0.39.u2 (inline literal in AaDesires)", "the iron need score span (scaled by the missing share of ingots)");
        Tuning.def("aaDesires.ownerBuildMin", 40, "rm-constants 0.39.u2 (inline literal in AaDesires)", "minutes an owner build task may run before it ends");
        Tuning.def("aaDesires.pointAgeMs", 60_000, "rm-constants 0.39.u2 (inline literal in AaDesires)", "milliseconds a remembered point stays good");
        Tuning.def("aaDesires.samePointR", 4, "rm-constants 0.39.u2 (inline literal in AaDesires)", "blocks within which a point counts as the same one");
        Tuning.def("aaDesires.shareScore", 30, "rm-constants 0.39.u2 (inline literal in AaDesires)", "score of a desire per unit of its planned share");
        Tuning.def("aaRuntime.coalMemoMs", 10_000, "rm-constants 0.39.u2 (inline literal in AaRuntime)", "milliseconds the coal position is remembered");
        Tuning.def("aaRuntime.creeperNear", 10, "rm-constants 0.39.u2 (inline literal in AaRuntime)", "blocks within which an owner-side creeper counts");
        Tuning.def("aaRuntime.errorWindowMs", 60_000, "rm-constants 0.39.u2 (inline literal in AaRuntime)", "milliseconds an error count covers");
        Tuning.def("aaRuntime.errorsLogged", 3, "rm-constants 0.39.u2 (inline literal in AaRuntime)", "errors logged in full before only their text is");
        Tuning.def("aaRuntime.haveEveryMs", 10_000, "rm-constants 0.39.u2 (inline literal in AaRuntime)", "milliseconds between two reads of the items had");
        Tuning.def("aaRuntime.herdSetupS", 60, "rm-constants 0.39.u2 (inline literal in AaRuntime)", "seconds a herd hunt costs for setup");
        Tuning.def("aaRuntime.lightStep", 4, "rm-constants 0.39.u2 (inline literal in AaRuntime)", "blocks between two sampled points");
        Tuning.def("aaRuntime.maxArea", 4096, "rm-constants 0.39.u2 (inline literal in AaRuntime)", "the most blocks of area a base may cover");
        Tuning.def("aaRuntime.minSide", 7, "rm-constants 0.39.u2 (inline literal in AaRuntime)", "blocks a base area must measure each way");
        Tuning.def("aaRuntime.movedBlocks", 8, "rm-constants 0.39.u2 (inline literal in AaRuntime)", "blocks moved that count as the bot having moved");
        Tuning.def("aaRuntime.slowCallUs", 2000, "rm-constants 0.39.u2 (inline literal in AaRuntime)", "microseconds a call may take before it is logged slow");
        Tuning.def("aaRuntime.slowLogMs", 10_000, "rm-constants 0.39.u2 (inline literal in AaRuntime)", "milliseconds between two logs of the same slow call");
        Tuning.def("aaRuntime.snapshotMs", 1000, "rm-constants 0.39.u2 (inline literal in AaRuntime)", "milliseconds between two snapshots");
        Tuning.def("aaRuntime.statsSaveMs", 60_000, "rm-constants 0.39.u2 (inline literal in AaRuntime)", "milliseconds between two saves of the brain stats");
        Tuning.def("aaRuntime.threatMemoMs", 1000, "rm-constants 0.39.u2 (inline literal in AaRuntime)", "milliseconds a threat reading is reused");
        Tuning.def("aaRuntime.torchDy", 4, "rm-constants 0.39.u2 (inline literal in AaRuntime)", "blocks up and down searched for torches");
        Tuning.def("aaRuntime.torchLight", 10, "rm-constants 0.39.u2 (inline literal in AaRuntime)", "light level from which a block counts as a torch");
        Tuning.def("aaRuntime.torchMemoMs", 5000, "rm-constants 0.39.u2 (inline literal in AaRuntime)", "milliseconds a torch count is reused");
        Tuning.def("aaRuntime.torchR", 16, "rm-constants 0.39.u2 (inline literal in AaRuntime)", "blocks round the bot searched for torches");
        Tuning.def("aaRuntime.treeEveryMs", 60_000, "rm-constants 0.39.u2 (inline literal in AaRuntime)", "milliseconds between two checks of the tech tree");
        Tuning.def("aaRuntime.waterNear", 12, "rm-constants 0.39.u2 (inline literal in AaRuntime)", "blocks within which water counts as near");
        Tuning.def("aaRuntime.writeErrorMs", 60_000, "rm-constants 0.39.u2 (inline literal in AaRuntime)", "milliseconds between two logs of a write error");
        Tuning.def("aaScorer.bagBandWidth", 6, "rm-constants 0.39.u2 (inline literal in AaScorer)", "free slots between the bag need switching on and off");
        Tuning.def("aaTree.airShort", 300, "rm-constants 0.39.u2 (inline literal in AaTree)", "air ticks under which air counts as short (a full breath is 300)");
        Tuning.def("aaTree.airUrgent", 100, "rm-constants 0.39.u2 (inline literal in AaTree)", "air ticks under which the air reflex is urgent");
        Tuning.def("aaTree.drownHealth", 8, "rm-constants 0.39.u2 (inline literal in AaTree)", "health at or under which short air is drowning");
        Tuning.def("aaTree.pauseWindowMs", 60_000, "rm-constants 0.39.u2 (inline literal in AaTree)", "milliseconds a reflex pause counts toward the paused sum");
        Tuning.def("breakPolicy.diveRiskK", 4, "rm-constants 0.39.u2 (inline literal in BreakPolicy)", "weight of the dive risk squared in the break price");
        Tuning.def("buildCount.fractionDiv", 10L, "rm-constants 0.39.u2 (inline literal in BuildCount)", "a build counts as done when it holds at least 1/this of what it needs");
        Tuning.def("caveRules.lowFood", 8, "rm-constants 0.39.u2 (inline literal in CaveRules)", "food under which a cave trip is not started without food in the bag");
        Tuning.def("caveRules.lowHealth", 10, "rm-constants 0.39.u2 (inline literal in CaveRules)", "health under which a cave trip is not started");
        Tuning.def("caveSearch.maxDrop", 3, "rm-constants 0.39.u2 (inline literal in CaveSearch)", "blocks a cave path may drop in one step");
        Tuning.def("caves.probeStep", 4, "rm-constants 0.39.u2 (inline literal in Caves)", "blocks between the cells probed round a cave opening");
        Tuning.def("clearEngine.breakSlack", 30, "rm-constants 0.39.u2 (inline literal in ClearEngine)", "ticks of slack added to the expected break time");
        Tuning.def("clearEngine.failTries", 4, "rm-constants 0.39.u2 (inline literal in ClearEngine)", "tries at one block before it is skipped");
        Tuning.def("clearEngine.failWindow", 10, "rm-constants 0.39.u2 (inline literal in ClearEngine)", "blocks broken since a try within which a failure still counts");
        Tuning.def("clearEngine.maxBreakTicks", 600, "rm-constants 0.39.u2 (inline literal in ClearEngine)", "ticks one block may take before it counts as unbreakable");
        Tuning.def("clearEngine.protectedCap", 200, "rm-constants 0.39.u2 (inline literal in ClearEngine)", "protected blocks counted before the engine stops counting");
        Tuning.def("clearEngine.putBackTries", 3, "rm-constants 0.39.u2 (inline literal in ClearEngine)", "times a server may put a block back before it is skipped");
        Tuning.def("clearEngine.reportEvery", 250, "rm-constants 0.39.u2 (inline literal in ClearEngine)", "blocks broken between two progress reports");
        Tuning.def("clearEngine.sightDistSq", 16, "rm-constants 0.39.u2 (inline literal in ClearEngine)", "squared eye distance beyond which a block is not in sight (4 blocks)");
        Tuning.def("clearGrid.badWindow", 10, "rm-constants 0.39.u2 (inline literal in ClearGrid)", "blocks broken since a failed walk within which the spot is still avoided");
        Tuning.def("clearGrid.candCap", 24, "rm-constants 0.39.u2 (inline literal in ClearGrid)", "standing spots priced per block");
        Tuning.def("clearGrid.climbMax", 3, "rm-constants 0.39.u2 (inline literal in ClearGrid)", "blocks up a standing spot may be found");
        Tuning.def("clearGrid.exposedCap", 60, "rm-constants 0.39.u2 (inline literal in ClearGrid)", "exposed blocks looked at for a standing spot");
        Tuning.def("clearGrid.farPenalty", 1000, "rm-constants 0.39.u2 (inline literal in ClearGrid)", "cost added to a spot that is not in reach, so a near one wins");
        Tuning.def("clearGrid.goodEnoughAt", 40, "rm-constants 0.39.u2 (inline literal in ClearGrid)", "spots tried before a good enough one is taken");
        Tuning.def("clearGrid.nearCap", 8, "rm-constants 0.39.u2 (inline literal in ClearGrid)", "near standing spots checked for sight");
        Tuning.def("clearGrid.reachMargin", 0.3, "rm-constants 0.39.u2 (inline literal in ClearGrid)", "blocks taken off the reach so a spot is well within it");
        Tuning.def("clearGrid.scanH", 6, "rm-constants 0.39.u2 (inline literal in ClearGrid)", "blocks up and down a column is scanned");
        Tuning.def("clearGrid.standR", 5, "rm-constants 0.39.u2 (inline literal in ClearGrid)", "blocks round a block searched for a standing spot");
        Tuning.def("clearGrid.standUp", 3, "rm-constants 0.39.u2 (inline literal in ClearGrid)", "blocks above a block searched for a standing spot");
        Tuning.def("clearGrid.vertSlack", 3, "rm-constants 0.39.u2 (inline literal in ClearGrid)", "blocks above and below the player the grid is cut to");
        Tuning.def("clearRun.approachTries", 5, "rm-constants 0.39.u2 (inline literal in ClearRun)", "tries to approach a block before it is skipped");
        Tuning.def("clearRun.atSpotTol", 1.5, "rm-constants 0.39.u2 (inline literal in ClearRun)", "blocks from the standing spot within which the bot counts as there");
        Tuning.def("clearRun.breakGapTicks", 6, "rm-constants 0.39.u2 (inline literal in ClearRun)", "ticks between two breaks");
        Tuning.def("clearRun.breakWaitTicks", 80, "rm-constants 0.39.u2 (inline literal in ClearRun)", "ticks a break may take");
        Tuning.def("clearRun.collectEvery", 10, "rm-constants 0.39.u2 (inline literal in ClearRun)", "blocks broken between two drop sweeps");
        Tuning.def("clearRun.collectR", 10, "rm-constants 0.39.u2 (inline literal in ClearRun)", "blocks round the bot the drop sweep covers");
        Tuning.def("clearRun.depositEvery", 50, "rm-constants 0.39.u2 (inline literal in ClearRun)", "blocks broken between two deposits");
        Tuning.def("clearRun.gapTicks", 5, "rm-constants 0.39.u2 (inline literal in ClearRun)", "ticks without a tick call after which the job counts as interrupted");
        Tuning.def("clearRun.idleAfterTicks", 15, "rm-constants 0.39.u2 (inline literal in ClearRun)", "ticks after which an idle walk ends the wait");
        Tuning.def("clearRun.minTicks", 20, "rm-constants 0.39.u2 (inline literal in ClearRun)", "ticks a phase runs before its idle check");
        Tuning.def("clearRun.nearFar", 24, "rm-constants 0.39.u2 (inline literal in ClearRun)", "blocks within which a block is walked to");
        Tuning.def("clearRun.pickTicks", 30, "rm-constants 0.39.u2 (inline literal in ClearRun)", "ticks a phase waits before it picks the next block");
        Tuning.def("clearRun.rebreakTicks", 30, "rm-constants 0.39.u2 (inline literal in ClearRun)", "ticks after a break before the same spot is tried again");
        Tuning.def("clearRun.stuckTicks", 160, "rm-constants 0.39.u2 (inline literal in ClearRun)", "ticks without a move after which the walk counts as stuck");
        Tuning.def("clearRun.walkFarS", 120, "rm-constants 0.39.u2 (inline literal in ClearRun)", "seconds a walk to a far block may take");
        Tuning.def("clearRun.walkNearS", 30, "rm-constants 0.39.u2 (inline literal in ClearRun)", "seconds a walk to a near block may take");
        Tuning.def("clearRun.waterFar", 64, "rm-constants 0.39.u2 (inline literal in ClearRun)", "blocks within which a water cut-off is checked");
        Tuning.def("clearing.baritonePlaceS", 10, "rm-constants 0.39.u2 (inline literal in Clearing)", "seconds Baritone is given to place");
        Tuning.def("clearing.beatTicks", 20, "rm-constants 0.39.u2 (inline literal in Clearing)", "ticks between two lease beats");
        Tuning.def("clearing.digS", 5, "rm-constants 0.39.u2 (inline literal in Clearing)", "seconds a dig may take");
        Tuning.def("clearing.dropMax", 8, "rm-constants 0.39.u2 (inline literal in Clearing)", "blocks beyond which a drop is not fetched");
        Tuning.def("clearing.dropMin", 1.2, "rm-constants 0.39.u2 (inline literal in Clearing)", "blocks a drop must be away to be fetched");
        Tuning.def("clearing.findDy", 3, "rm-constants 0.39.u2 (inline literal in Clearing)", "blocks above and below searched");
        Tuning.def("clearing.nearBox", 8, "rm-constants 0.39.u2 (inline literal in Clearing)", "blocks from the build box within which the bot counts as at it");
        Tuning.def("clearing.placeAgainS", 5, "rm-constants 0.39.u2 (inline literal in Clearing)", "seconds after which a placement is tried again");
        Tuning.def("clearing.placeTries", 3, "rm-constants 0.39.u2 (inline literal in Clearing)", "tries at one placement before it is skipped");
        Tuning.def("clearing.placeWaitTicks", 6, "rm-constants 0.39.u2 (inline literal in Clearing)", "ticks a placement waits before it is checked");
        Tuning.def("clearing.recountTicks", 20, "rm-constants 0.39.u2 (inline literal in Clearing)", "ticks between two recounts of the missing blocks");
        Tuning.def("clearing.stageWaitTicks", 10, "rm-constants 0.39.u2 (inline literal in Clearing)", "ticks a stage waits");
        Tuning.def("clearing.startWaitTicks", 60, "rm-constants 0.39.u2 (inline literal in Clearing)", "ticks a build waits after its start");
        Tuning.def("clearing.stillTicks", 200, "rm-constants 0.39.u2 (inline literal in Clearing)", "ticks Baritone may stand still before the build is nudged");
        Tuning.def("coalOptions.charcoalExtraS", 10, "rm-constants 0.39.u2 (inline literal in CoalOptions)", "seconds added to the charcoal row for the fuel and the wait");
        Tuning.def("costs.armorCut", 0.6, "rm-constants 0.39.u2 (inline literal in Costs)", "the most full armour cuts the night danger by");
        Tuning.def("costs.foodNeedSpan", 14.0, "rm-constants 0.39.u2 (inline literal in Costs)", "food points short of full at which the food need counts as full");
        Tuning.def("costs.healthNeedSpan", 10.0, "rm-constants 0.39.u2 (inline literal in Costs)", "health points short of full at which the health need counts as full");
        Tuning.def("costs.hostileStep", 0.15, "rm-constants 0.39.u2 (inline literal in Costs)", "the night danger added per hostile near (up to 6)");
        Tuning.def("costs.torchGainStep", 4, "rm-constants 0.39.u2 (inline literal in Costs)", "torches added when the gain of stocking more is priced");
        Tuning.def("crafting.boxDy", 3, "rm-constants 0.39.u2 (inline literal in Crafting)", "blocks above and below the table the box covers");
        Tuning.def("crafting.closeTicks", 10, "rm-constants 0.39.u2 (inline literal in Crafting)", "ticks the crafting screen is given to close");
        Tuning.def("crafting.laterMin", 30, "rm-constants 0.39.u2 (inline literal in Crafting)", "minutes before a smelting job is looked at again");
        Tuning.def("crafting.lookTicks", 10, "rm-constants 0.39.u2 (inline literal in Crafting)", "ticks between two looks at the table");
        Tuning.def("crafting.nearR", 16, "rm-constants 0.39.u2 (inline literal in Crafting)", "blocks within which a station counts as near");
        Tuning.def("crafting.slotsTicks", 4, "rm-constants 0.39.u2 (inline literal in Crafting)", "ticks the slots are given to arrive");
        Tuning.def("crafting.tableWaitTicks", 60, "rm-constants 0.39.u2 (inline literal in Crafting)", "ticks a table is given to open");
        Tuning.def("creeperSprint.safeMax", 17, "rm-constants 0.39.u2 (inline literal in CreeperSprint)", "the most safe distance a chain asks for");
        Tuning.def("creeperSprint.safePerChain", 5, "rm-constants 0.39.u2 (inline literal in CreeperSprint)", "blocks of safe distance added per sprint in a chain");
        Tuning.def("curves.minSamples", 3, "rm-constants 0.39.u2 (inline literal in Curves)", "samples a rate needs before it is trusted over the prior");
        Tuning.def("dangerMoves.baseFar", 8, "rm-constants 0.39.u2 (inline literal in DangerMoves)", "blocks beyond which a base counts as far");
        Tuning.def("dangerMoves.seenDist", 2.5, "rm-constants 0.39.u2 (inline literal in DangerMoves)", "blocks within which a mob counts as seen without a line of sight");
        Tuning.def("dangerMoves.surfaceUp", 3, "rm-constants 0.39.u2 (inline literal in DangerMoves)", "blocks above the surface searched for a way out");
        Tuning.def("dangerMoves.wetUpMax", 64, "rm-constants 0.39.u2 (inline literal in DangerMoves)", "blocks up a water column is followed");
        Tuning.def("dangerOptions.damageMargin", 1.25, "rm-constants 0.39.u2 (inline literal in DangerOptions)", "margin on the damage a foe is expected to do");
        Tuning.def("dangerOptions.shooterDist", 3.5, "rm-constants 0.39.u2 (inline literal in DangerOptions)", "blocks beyond which a ranged foe counts as a shooter");
        Tuning.def("digCommands.forceBox", 64, "rm-constants 0.39.u2 (inline literal in DigCommands)", "the most blocks a force dig may hold");
        Tuning.def("digCommands.maxBox", 20000, "rm-constants 0.39.u2 (inline literal in DigCommands)", "the most blocks one dig or clear box may hold");
        Tuning.def("discovery.floorY", 58, "rm-constants 0.39.u2 (inline literal in Discovery)", "the lowest y (as -58) a discovery search digs to");
        Tuning.def("drownRisk.riskK", 4, "rm-constants 0.39.u2 (inline literal in DrownRisk)", "weight of the drown risk squared in the price");
        Tuning.def("engineProcess.errorsOff", 5, "rm-constants 0.39.u2 (inline literal in EngineProcess)", "errors after which the engine process switches itself off");
        Tuning.def("threatFacts.unknownHit", 5, "rm-final 0.39.u3 (was the table's default)", "raw damage per hit assumed for a mob kind the game data does not know");
        Tuning.def("threatFacts.shooterHit", 3, "rm-final 0.39.u3 (prior: no attribute exists)", "raw damage per shot assumed for a shooter: its arrow carries the damage, the game has no per-mob number (vanilla arrows hurt about 3 at Normal)");
        Tuning.def("threatFacts.unknownHealth", 20, "rm-final 0.39.u3 (was the table's default)", "health assumed for a mob kind the game data does not know (the player's own 20)");
        Tuning.def("exploreRules.endedNear", 4, "rm-constants 0.39.u2 (inline literal in ExploreRules)", "blocks from the start within which an explore counts as having ended where it started");
        Tuning.def("fireReflex.pickUpAgainAt", 12, "rm-constants 0.39.u2 (inline literal in FireReflex)", "use tick at which it is picked up again");
        Tuning.def("fireReflex.pickUpAt", 4, "rm-constants 0.39.u2 (inline literal in FireReflex)", "use tick at which the water is picked up");
        Tuning.def("fireReflex.scanDown", 3, "rm-constants 0.39.u2 (inline literal in FireReflex)", "blocks down searched for water");
        Tuning.def("fireReflex.useEndTicks", 16, "rm-constants 0.39.u2 (inline literal in FireReflex)", "use ticks after which the bucket use ends");
        Tuning.def("fireReflex.useMaxTicks", 45, "rm-constants 0.39.u2 (inline literal in FireReflex)", "ticks a water bucket use may last");
        Tuning.def("fireReflex.useStartTicks", 5, "rm-constants 0.39.u2 (inline literal in FireReflex)", "ticks a use gets to start");
        Tuning.def("fireReflex.whyLogTicks", 100, "rm-constants 0.39.u2 (inline literal in FireReflex)", "ticks between two logs of why the bucket is not used");
        Tuning.def("foodOptions.appleMin", 4, "rm-constants 0.39.u2 (inline literal in FoodOptions)", "minutes of waiting under the leaves per apple round");
        Tuning.def("foodOptions.berryMin", 0.3, "rm-constants 0.39.u2 (inline literal in FoodOptions)", "minutes one berry visit takes");
        Tuning.def("foodOptions.berryYieldK", 2.5, "rm-constants 0.39.u2 (inline literal in FoodOptions)", "berry energy per bush visit, in sweet berries");
        Tuning.def("foodOptions.cookPerMin", 6.0, "rm-constants 0.39.u2 (inline literal in FoodOptions)", "items a furnace cooks per minute");
        Tuning.def("foodOptions.fishPerTrip", 4, "rm-constants 0.39.u2 (inline literal in FoodOptions)", "fish one fishing trip brings");
        Tuning.def("foodOptions.kelpPerTrip", 8, "rm-constants 0.39.u2 (inline literal in FoodOptions)", "dried kelp one kelp trip brings");
        Tuning.def("foodOptions.noGain", 0.1, "rm-constants 0.39.u2 (inline literal in FoodOptions)", "energy gain under which a slow food way counts as stalled");
        Tuning.def("foodOptions.seedMin", 3, "rm-constants 0.39.u2 (inline literal in FoodOptions)", "minutes a seed search takes");
        Tuning.def("foodOptions.seedsEnough", 4, "rm-constants 0.39.u2 (inline literal in FoodOptions)", "seeds in the bag from which a wheat field needs no seed search");
        Tuning.def("foodOptions.wheatBucketMin", 7, "rm-constants 0.39.u2 (inline literal in FoodOptions)", "minutes a wheat field made with a water bucket takes");
        Tuning.def("foodOptions.wheatFieldMin", 6, "rm-constants 0.39.u2 (inline literal in FoodOptions)", "minutes a wheat field by water takes");
        Tuning.def("freeSpots.maxRing", 3, "rm-constants 0.39.u2 (inline literal in FreeSpots)", "rings of cells round a spot the free-spot search looks through");
        Tuning.def("goals.fightHealth", 14, "rm-constants 0.39.u2 (inline literal in Goals)", "health from which the bot fights");
        Tuning.def("goals.maxN", 4096, "rm-constants 0.39.u2 (inline literal in Goals)", "the most items one goal may ask for");
        Tuning.def("guard.astarLogTicks", 100, "rm-constants 0.39.u2 (inline literal in Guard)", "ticks between two logs of the A* search");
        Tuning.def("help.giveWeight", 1.5, "rm-constants 0.39.u2 (inline literal in Help)", "weight of the give pick");
        Tuning.def("help.lightWeight", 1.25, "rm-constants 0.39.u2 (inline literal in Help)", "weight of the light pick");
        Tuning.def("help.perMob", 10, "rm-constants 0.39.u2 (inline literal in Help)", "score added per hostile near the owner");
        Tuning.def("hitLog.deathLookbackMs", 300_000, "rm-constants 0.39.u2 (inline literal in HitLog)", "milliseconds before a death that its hits still count");
        Tuning.def("hitWatch.saveEveryMs", 10_000, "rm-constants 0.39.u2 (inline literal in HitWatch)", "milliseconds between two saves of the damage table");
        Tuning.def("jobs.followFixMs", 1500, "rm-constants 0.39.u2 (inline literal in Jobs)", "milliseconds between two follow fixes");
        Tuning.def("jobs.homeDistSq", 64, "rm-constants 0.39.u2 (inline literal in Jobs)", "squared blocks within which the bot counts as home (8 blocks)");
        Tuning.def("jobs.startWaitTicks", 40, "rm-constants 0.39.u2 (inline literal in Jobs)", "ticks a job gives its first event");
        Tuning.def("jobs.stepGapTicks", 40, "rm-constants 0.39.u2 (inline literal in Jobs)", "ticks since the last step after which a job counts as idle");
        Tuning.def("lootBook.minChance", 0.01, "rm-constants 0.39.u2 (inline literal in LootBook)", "a player-drop chance under this is left out of the book");
        Tuning.def("miningTiers.swordBase", 3, "rm-constants 0.39.u2 (inline literal in MiningTiers)", "attack points below a sword hit that the weapon tier subtracts");
        Tuning.def("mobDamage.maxHit", 100, "rm-constants 0.39.u2 (inline literal in MobDamage)", "the most one hit may be worth before it is dropped as a glitch");
        Tuning.def("nightWork.torchesLow", 8, "rm-constants 0.39.u2 (inline literal in NightWork)", "torches under which the night work crafts more");
        Tuning.def("options.factErrorLogMs", 60_000, "rm-constants 0.39.u2 (inline literal in Options)", "milliseconds between two logs of the same facts error");
        Tuning.def("placeRules.avoidPenalty", 10, "rm-constants 0.39.u2 (inline literal in PlaceRules)", "score taken off a cell inside the area to avoid");
        Tuning.def("placeRules.selfClear", 0.8, "rm-constants 0.39.u2 (inline literal in PlaceRules)", "blocks from the bot centre within which a cell is not placed on");
        Tuning.def("policyRuntime.waterColumnMax", 32, "rm-constants 0.39.u2 (inline literal in PolicyRuntime)", "blocks up a water column is followed");
        Tuning.def("reachGrid.climbMax", 3, "rm-constants 0.39.u2 (inline literal in ReachGrid)", "blocks a step may climb");
        Tuning.def("reachGrid.dropMax", 3, "rm-constants 0.39.u2 (inline literal in ReachGrid)", "blocks a step may drop");
        Tuning.def("reflexNotes.lowHealth", 6, "rm-constants 0.39.u2 (inline literal in ReflexNotes)", "health at or under which the note says low health");
        Tuning.def("reflexRules.airDiv", 3, "rm-constants 0.39.u2 (inline literal in ReflexRules)", "the air reflex takes over under one third of the air");
        Tuning.def("reflexRules.seenDist", 2.5, "rm-constants 0.39.u2 (inline literal in ReflexRules)", "blocks within which a mob counts as seen without a line of sight");
        Tuning.def("reflexes.aaOffRepriceTicks", 20, "rm-constants 0.39.u2 (inline literal in Reflexes)", "ticks between two prices of the AA-off way");
        Tuning.def("reflexes.bitesMax", 10, "rm-constants 0.39.u2 (inline literal in Reflexes)", "bites one meal may take");
        Tuning.def("reflexes.creeperKeep", 4, "rm-constants 0.39.u2 (inline literal in Reflexes)", "blocks beyond which a creeper out of sight is dropped");
        Tuning.def("reflexes.deadRetryTicks", 200, "rm-constants 0.39.u2 (inline literal in Reflexes)", "ticks between two respawn asks");
        Tuning.def("reflexes.deadWaitTicks", 40, "rm-constants 0.39.u2 (inline literal in Reflexes)", "ticks dead before the respawn is asked");
        Tuning.def("reflexes.deniedRetryTicks", 1200, "rm-constants 0.39.u2 (inline literal in Reflexes)", "ticks between two asks");
        Tuning.def("reflexes.deniedWaitTicks", 40, "rm-constants 0.39.u2 (inline literal in Reflexes)", "ticks after a denied dimension before it is asked again");
        Tuning.def("reflexes.eatCheckTicks", 6, "rm-constants 0.39.u2 (inline literal in Reflexes)", "eat ticks after which an unused item ends the try");
        Tuning.def("reflexes.eatCoolTicks", 200, "rm-constants 0.39.u2 (inline literal in Reflexes)", "ticks the eat reflex cools after a try");
        Tuning.def("reflexes.eatDoneCoolTicks", 600, "rm-constants 0.39.u2 (inline literal in Reflexes)", "ticks the eat reflex cools after a meal");
        Tuning.def("reflexes.eatMaxTicks", 60, "rm-constants 0.39.u2 (inline literal in Reflexes)", "ticks one meal may take");
        Tuning.def("reflexes.eatUseFrom", 2, "rm-constants 0.39.u2 (inline literal in Reflexes)", "eat tick from which use is held");
        Tuning.def("reflexes.eatUseTo", 50, "rm-constants 0.39.u2 (inline literal in Reflexes)", "eat tick until which use is held");
        Tuning.def("reflexes.fleeKeepTicks", 20, "rm-constants 0.39.u2 (inline literal in Reflexes)", "ticks a creeper flee is kept");
        Tuning.def("reflexes.seenDist", 2.5, "rm-constants 0.39.u2 (inline literal in Reflexes)", "blocks within which a mob counts as seen without a line of sight");
        Tuning.def("reflexes.swimLogTicks", 200, "rm-constants 0.39.u2 (inline literal in Reflexes)", "ticks between two logs of the air reflex");
        Tuning.def("reflexes.watchCreeperTicks", 160, "rm-constants 0.39.u2 (inline literal in Reflexes)", "ticks a seen creeper stays watched");
        Tuning.def("runLedger.lowFood", 6, "rm-constants 0.39.u2 (inline literal in RunLedger)", "food under which the run ledger counts the bot as hungry");
        Tuning.def("shoreWatch.floatTicks", 100, "rm-constants 0.39.u2 (inline literal in ShoreWatch)", "ticks afloat before the shore watch looks for land");
        Tuning.def("shoreWatch.giveUpTicks", 1200, "rm-constants 0.39.u2 (inline literal in ShoreWatch)", "ticks before the shore watch asks again after a failure");
        Tuning.def("shoreWatch.retryTicks", 200, "rm-constants 0.39.u2 (inline literal in ShoreWatch)", "ticks before the shore watch retries");
        Tuning.def("shoreWatch.shoreDy", 3, "rm-constants 0.39.u2 (inline literal in ShoreWatch)", "blocks of height difference within which the spot counts as reached");
        Tuning.def("threatFacts.sprintFood", 6, "rm-constants 0.39.u2 (inline literal in ThreatFacts)", "food from which the bot may sprint (vanilla needs more than 6)");
        Tuning.def("threatRules.pathBudgetK", 1.6, "rm-constants 0.39.u2 (inline literal in ThreatRules)", "times the straight distance a path may take");
        Tuning.def("threatRuntime.errorLogMs", 60_000, "rm-constants 0.39.u2 (inline literal in ThreatRuntime)", "milliseconds between two error logs");
        Tuning.def("threatRuntime.errorsLogged", 5, "rm-constants 0.39.u2 (inline literal in ThreatRuntime)", "errors logged in full before only their rate is");
        Tuning.def("threatRuntime.fullEveryMs", 15_000, "rm-constants 0.39.u2 (inline literal in ThreatRuntime)", "milliseconds between two full scans while mobs are near");
        Tuning.def("threatRuntime.seenDist", 2.5, "rm-constants 0.39.u2 (inline literal in ThreatRuntime)", "blocks within which a mob counts as seen without a line of sight");
        Tuning.def("ways.woodRetryMs", 1000, "rm-constants 0.39.u2 (inline literal in Ways)", "milliseconds before the wood facts are asked again");
        Tuning.def("worth.baseShare", 0.8, "rm-constants 0.39.u2 (inline literal in Worth)", "a base is worth this share of a bed");
        Tuning.def("worth.bedBase", 20, "rm-constants 0.39.u2 (inline literal in Worth)", "the bed worth floor");
        Tuning.def("worth.bedSpan", 60, "rm-constants 0.39.u2 (inline literal in Worth)", "the bed worth span (scaled by the night danger)");
    }
}
