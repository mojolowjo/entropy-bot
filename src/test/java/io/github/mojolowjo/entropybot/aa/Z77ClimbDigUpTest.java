package io.github.mojolowjo.entropybot.aa;

import baritone.api.pathing.movement.ActionCosts;
import io.github.mojolowjo.entropybot.aa.NeedChain.Chain;
import io.github.mojolowjo.entropybot.aa.brain.GameTimes;
import io.github.mojolowjo.entropybot.aa.brain.Goals;
import io.github.mojolowjo.entropybot.aa.brain.TestData;
import io.github.mojolowjo.entropybot.aa.brain.Thing;
import io.github.mojolowjo.entropybot.aa.brain.Tuning;
import io.github.mojolowjo.entropybot.aa.brain.Unit;
import io.github.mojolowjo.entropybot.aa.core.Blackboard;
import io.github.mojolowjo.entropybot.aa.core.Clock;
import io.github.mojolowjo.entropybot.aa.core.FakePort;
import io.github.mojolowjo.entropybot.aa.core.Tracer;
import io.github.mojolowjo.entropybot.aa.moves.FactChange;
import io.github.mojolowjo.entropybot.aa.moves.ReflexGoals;
import io.github.mojolowjo.entropybot.aa.moves.Step;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 0.39.z77 climb (run CM, the owner's PC, 17:36-17:48): five orders each stopped on "Baritone's climb goal ended at -45 -9
 * -46 with 59 blocks over the feet" 0.5-2 s after the goal was set, then 5 min idle with no line saying why. Cause: the climb
 * goal's heuristic (3.17 ticks a level, Baritone's Y-level cost) is far below a dug level's price (~300-450 ticks), so
 * Baritone's A* never found a node better than its start and returned no path. Hand numbers: deepslate hardness 3, the stone
 * pickaxe's speed 4: 4 / 3 / 30 a tick = 23 whole ticks (GameTimes), + Baritone's blockBreakAdditionalPenalty 2 = 25, x the
 * policy's breakPolicy.travel 6 (the climb runs the TRAVEL intent) = 150 ticks a cell; a stair level breaks two cells (300 +
 * walk + jump), a pillar level one cell and a placement at walk.placePenalty 60 x breakPolicy.travelPlace 3.
 */
class Z77ClimbDigUpTest {
    @AfterEach
    void clear() {
        TestData.clear();
        FactChange.clear();
        Tuning.reset();
    }

    /** Baritone's coefficients (AStarPathFinder.COEFFICIENTS) and its minimum improvement. */
    static final double[] COEFFICIENTS = {1.5, 2, 2.5, 3, 4, 5, 10};
    static final double MIN_IMPROVEMENT = 0.01;

    /** Baritone's best-so-far rule: a node {@code levels} up at {@code perLevel} ticks a level beats the start for this coefficient. */
    static boolean beatsStart(ReflexGoals.UnderSky g, int startY, int levels, double perLevel, double coefficient) {
        double start = g.heuristic(0, startY, 0);
        double node = g.heuristic(0, startY + levels, 0) + levels * perLevel / coefficient;
        return start - node > MIN_IMPROVEMENT;
    }

    static final ReflexGoals.SkyCells ROCK_TO_49 = new ReflexGoals.SkyCells() {
        @Override public boolean open(int x, int y, int z) { return y >= 50 || y == -9 || y == -8; }
        @Override public boolean sky(int x, int y, int z) { return y >= 50; }
    };

    static double deepslateCell() {
        double ticks = GameTimes.breakSeconds(3, 4, true) * GameTimes.TPS;
        assertEquals(23, ticks, 1e-9, "the game's whole ticks for deepslate with a stone pickaxe");
        return (ticks + 2) * 6;
    }

    /**
     * The cause, then the fix: 59 levels from y -9 to the sky at 49. Baritone's own Y-level heuristic gains 3.17 ticks a level;
     * a stair level costs 2 x 150 + walk + jump = 307.8, a pillar level 150 + 180 + jump = 333.2: six levels up (past Baritone's
     * MIN_DIST_PATH 5) never beat the start at any coefficient - no partial path, the climb's search ends with none. Priced by
     * the column's dearest cell (3 x 150 + walk + jump = 457.8 a level), six levels up beat the start at the first coefficient
     * for both moves.
     */
    @Test
    void theDugLevelIsProgress_onlyWithTheColumnsPrice() {
        double cell = deepslateCell();
        assertEquals(150, cell, 1e-9);
        double stair = 2 * cell + ActionCosts.WALK_ONE_BLOCK_COST + ActionCosts.JUMP_ONE_BLOCK_COST;
        double pillar = cell + 60 * 3 + ActionCosts.JUMP_ONE_BLOCK_COST;
        var old = new ReflexGoals.UnderSky(ROCK_TO_49, 49, ActionCosts.JUMP_ONE_BLOCK_COST);      // the heuristic of run CM
        assertEquals(58 * ActionCosts.JUMP_ONE_BLOCK_COST, old.heuristic(0, -9, 0), 1e-9, "Baritone's Y-level cost: 3.17 a level");
        for (double c : COEFFICIENTS) {
            assertFalse(beatsStart(old, -9, 6, stair, c), "run CM: no stair node beats the start at " + c);
            assertFalse(beatsStart(old, -9, 6, pillar, c), "run CM: no pillar node beats the start at " + c);
        }
        double level = ReflexGoals.digLevelTicks(cell);
        assertEquals(3 * 150 + ActionCosts.WALK_ONE_BLOCK_COST + ActionCosts.JUMP_ONE_BLOCK_COST, level, 1e-9);
        var now = new ReflexGoals.UnderSky(ROCK_TO_49, 49, level);
        assertTrue(beatsStart(now, -9, 6, stair, COEFFICIENTS[0]), "a stair level dug up is progress");
        assertTrue(beatsStart(now, -9, 6, pillar, COEFFICIENTS[0]), "a pillar level is progress");
        assertTrue(beatsStart(now, -9, 6, 3 * cell + ActionCosts.WALK_ONE_BLOCK_COST + ActionCosts.JUMP_ONE_BLOCK_COST, COEFFICIENTS[0]),
                "even a three-cell stair level");
        assertEquals(0, now.heuristic(0, 49, 0));
        assertEquals(0, now.heuristic(0, 70, 0));
        assertFalse(now.isInGoal(0, -9, 0), "the cave at the feet is no way out");
        assertTrue(now.isInGoal(0, 50, 0));
    }

    /** No solid cell over the feet (an open shaft): Baritone's walk and jump a level, never below its own Y-level cost. */
    @Test
    void anOpenColumnKeepsAtLeastBaritonesOwnLevelCost() {
        assertEquals(ActionCosts.WALK_ONE_BLOCK_COST + ActionCosts.JUMP_ONE_BLOCK_COST, ReflexGoals.digLevelTicks(0), 1e-9);
        assertEquals(ActionCosts.JUMP_ONE_BLOCK_COST, new ReflexGoals.UnderSky(ROCK_TO_49, 49, 0).levelTicks(), 1e-9);
    }

    /**
     * The brief's scenario: the bot at -45 -9 -46, 59 blocks of rock over the feet, 0 blocks in the bag (a stone pickaxe), no
     * strip way at the feet, the walk up unreachable: the dig-up way runs - the climb (59 x rungOptions.climbBlock 3 +
     * climbFix 10 = 187 walk-blocks = 43.3 s, run CM's own row), then the surface explore.
     */
    @Test
    void underGround_noBlocks_noWalkUp_theDigUpWayRuns() {
        ScenariosTest.world("wooden", "stone");
        var w = ScenariosTest.w(Map.of("minecraft:stone_pickaxe", 1), false, false, Map.of())
                .withMobs(x -> List.of(), Z77ExploreTest.book()).withStrip(word -> null)
                .withWhere(new NeedChain.WhereFacts(Z77ExploreTest::where, () -> new NeedChain.Surface(59, -59, 59, -81, 41), () -> new int[]{-45, -9, -46}))
                .withPath((x, y, z, d) -> PathPrice.Answer.unreachable("no path: Baritone's search made no progress (no progress)"));
        Chain c = NeedChain.resolve("raw_iron", 3, w);
        assertTrue(c.ok() && c.explore(), c.why());
        assertEquals(List.of(new Step.Climb(), new Step.Explore(NeedChain.exploreMinutes(), "raw_iron")), c.steps());
        assertEquals((59 * 3 + 10) / Unit.WALK_BPS + NeedChain.exploreS(), c.seconds(), 0.1, c.why());
        assertTrue(c.why().contains("climb up 59 with 0 spare blocks of 0 in the bag and the stone_pickaxe"), c.why());
    }

    static Goals.Goal order(Blackboard bb, String key) {
        return Goals.add(bb, new Thing.Result(key, key, false, List.of("minecraft:" + key), List.of(key), 0, null, key, null), 1, 0, 0);
    }

    static void climbFailed(Goals.Goal g) {
        g.sources.failedFor(g.id(), NeedChain.CLIMB_KEY, "the climb up failed: Baritone's climb goal ended at -45 -9 -46 with 59 blocks over the feet", 0, null, Long.MAX_VALUE / 4);
    }

    /**
     * Every order parked (any shape: a failed climb, the honest loop, a plain stop): one line names each park and its wake,
     * said once while it holds. Under ground the ones that wait on moving or on their climb try the climb way once (the held
     * climb forgotten), the plain stop stays; parked again, the line comes again with no wake left for them, no second try.
     */
    @Test
    void everyOrderParked_oneLine_andTheClimbWayOnceUnderGround() {
        FakePort port = new FakePort();
        Blackboard bb = new Blackboard(new Clock.Fake(0), port, new Tracer());
        bb.in.bag = Map.of();
        bb.in.pos = new int[]{-45, -9, -46};
        bb.in.underDepth = 59;
        Goals.Goal helmet = order(bb, "iron_helmet"), boots = order(bb, "iron_boots"), furnace = order(bb, "furnace"), rod = order(bb, "blaze_rod");
        climbFailed(helmet);
        climbFailed(boots);
        Goals.replanner(bb, furnace).parked(Z70StepCheckTest.cobbleTry());
        Goals.park(bb, helmet, "stopped at step 4 (climb): climb: Baritone's climb goal ended at -42 -12 -46 with 65 blocks over the feet");
        Goals.park(bb, boots, "stopped at step 1 (climb): climb: Baritone's climb goal ended at -45 -9 -46 with 59 blocks over the feet");
        Goals.park(bb, furnace, "stopped at step 1 (craft furnace 1): the same way failed twice from the same place with the same bag: ...");
        assertTrue(Goals.parkedTick(bb, false).isEmpty(), "one order still runs: no all-parked line");

        Goals.park(bb, rod, "blaze_rod is only in the Nether; I don't travel there yet");
        List<String> said = Goals.parkedTick(bb, false);
        assertEquals(1, said.size(), said.toString());
        String line = said.get(0);
        assertTrue(line.startsWith("every order is parked, nothing runs: iron_helmet: stopped at step 4 (climb)"), line);
        assertTrue(line.contains("iron_boots: ") && line.contains("furnace: ") && line.contains("blaze_rod: "), line);
        assertTrue(line.contains("tries once more on the climb way when every order is parked under ground"), line);
        assertTrue(line.contains("wakes when a need in the bag rises, it moves 16 blocks or a new source is seen"), line);
        assertTrue(line.contains("blaze_rod is only in the Nether; I don't travel there yet - no wake by itself (aa want blaze_rod again)"), line);
        assertEquals(line, port.told.get(port.told.size() - 1), "said in the chat");
        assertTrue(Goals.parkedTick(bb, false).isEmpty(), "said once while it holds");

        List<String> tried = Goals.parkedTick(bb, true);
        assertEquals(3, tried.size(), tried.toString());
        assertTrue(tried.get(0).startsWith("order iron_helmet tries once more on the climb way: every order is parked and the bot is 59 blocks under ground"), tried.get(0));
        assertNull(helmet.parked);
        assertNull(boots.parked);
        assertNull(furnace.parked);
        assertNotNull(rod.parked, "a plain stop waits on nothing the climb changes");
        assertFalse(Goals.droppedSources(helmet).containsKey(NeedChain.CLIMB_KEY), "the climb way is offered again");
        assertNull(Goals.blocked(bb, helmet), "feasible again");

        // the climb failed again: parked again, said again; no second try
        climbFailed(helmet);
        climbFailed(boots);
        Goals.park(bb, helmet, "again");
        Goals.park(bb, boots, "again");
        Goals.park(bb, furnace, "again");
        List<String> again = Goals.parkedTick(bb, true);
        assertEquals(1, again.size(), again.toString());
        assertTrue(again.get(0).contains("iron_helmet: again - no wake by itself (aa want iron_helmet again)"), again.get(0));
        assertNotNull(helmet.parked);
    }
}
