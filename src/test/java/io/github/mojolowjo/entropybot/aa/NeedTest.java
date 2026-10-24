package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.brain.Brain;
import io.github.mojolowjo.entropybot.aa.brain.TestData;
import io.github.mojolowjo.entropybot.aa.brain.Tuning;
import io.github.mojolowjo.entropybot.aa.brain.Unit;
import io.github.mojolowjo.entropybot.aa.core.Blackboard;
import io.github.mojolowjo.entropybot.aa.core.Clock;
import io.github.mojolowjo.entropybot.aa.core.FakePort;
import io.github.mojolowjo.entropybot.aa.core.Status;
import io.github.mojolowjo.entropybot.aa.core.TaskKind;
import io.github.mojolowjo.entropybot.aa.core.Tracer;
import io.github.mojolowjo.entropybot.aa.moves.Step;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/** Round 4A N (AA_ROUND4 A1-A4), one-planner: a need is an option set; its "I need N of X" is the chain's row, the climb a priced first step. */
class NeedTest {
    final Clock.Fake clock = new Clock.Fake(10_000_000);
    final FakePort port = new FakePort();
    final Blackboard bb = new Blackboard(clock, port, new Tracer());

    @BeforeEach
    void data() { TestData.planner().install(); }

    @AfterEach
    void clear() { TestData.clear(); }

    /** The chain's world: the bag, a tree 10 m away. */
    void world(Map<String, Integer> bag) {
        NeedChain.Spot tree = new NeedChain.Spot(10, 64, 0, 10, "minecraft:oak_log", 8);
        bb.fact(NeedChain.WORLD_FACT, (Supplier<NeedChain.World>) () -> io.github.mojolowjo.entropybot.aa.FakePaths.paths(new NeedChain.World(bag, false, w -> w.equals("minecraft:oak_log") ? tree : null)));
    }

    @Test
    void theNeedsGoalIsTheChainsRowAndItRunsTheChainsSteps() {
        world(Map.of());
        Need n = new Need("needs.table", TaskKind.TABLE_FURNACE, b -> true, b -> null, b -> List.of(new Step.Obtain("crafting_table", 1)));
        assertEquals(Status.RUNNING, n.tick(bb));
        String ran = port.started.get(0).chain();
        assertTrue(ran.startsWith("cut 1 at 10 64 0") && ran.endsWith("craft crafting_table 1"), "chop B2: no walk before the cut: " + ran);
        assertTrue(n.options().startsWith("chain"), n.options());
        assertTrue(n.options().contains("crafting_table <- craft: 4 oak_planks"), n.options());
        port.done(1, "done");
        assertEquals(Status.SUCCESS, n.tick(bb), "a chain row is the need's own way: done is done");
    }

    @Test
    void noWorldFactsDropTheChainRowWithItsReason() {
        Need n = new Need("needs.table", TaskKind.TABLE_FURNACE, b -> true, b -> null, b -> List.of(new Step.Obtain("crafting_table", 1)));
        assertEquals(Status.FAILURE, n.tick(bb));
        assertTrue(n.options().contains("chain: no facts for the chain yet"), n.options());
        assertTrue(port.started.isEmpty());
        assertTrue(n.probe(bb).startsWith("no chain for obtain 1 crafting_table"), n.probe(bb));
    }

    @Test
    void aSurfaceNeedUnderGroundPricesTheClimbAsItsFirstRow() {
        world(Map.of("minecraft:oak_planks", 2));
        Need tools = new Need("needs.table", TaskKind.TABLE_FURNACE, b -> true, b -> null, b -> List.of(new Step.Obtain("stick", 4))).surface();
        bb.in.underDepth = 10;
        assertEquals(Status.RUNNING, tools.tick(bb));
        assertEquals(AaLadder.CLIMB, port.started.get(0).chain());
        assertTrue(tools.wantsSurface() && tools.options().startsWith("climb 10 up 40"), tools.options());
    }

    @Test
    void theDawnClimbIsAPricedNeedNotAGate() {
        Need dawn = AaDesires.needs().stream().filter(x -> x.id.equals(AaDesires.DAWN_ID)).findFirst().orElseThrow();
        Brain br = new Brain("brain", "t", AaDesires.need(dawn));
        bb.in.underDepth = 10;
        assertEquals(-1, br.scoreAll(bb).get(0).score(), 1e-9, "no night stairs dug: met");
        bb.fact(io.github.mojolowjo.entropybot.aa.brain.NightWork.DUG_FACT, Boolean.TRUE);
        assertEquals(Tuning.get("dawnClimb"), br.scoreAll(bb).get(0).score(), 1e-9, "day, dug at night, deep: dawnClimb");
        assertEquals(Status.RUNNING, br.tick(bb));
        assertEquals(AaLadder.CLIMB, port.started.get(0).chain());
    }

    @Test
    void aNeedWithEveryRowHeldRestsNoPerTickRetry() {
        Need n = new Need("needs.bag", TaskKind.BAG, b -> true, b -> null, b -> List.of(new Step.Deposit()));
        Brain br = new Brain("brain", "t", AaDesires.need(n));
        n.bindFailures(br.failures);
        assertEquals(Status.RUNNING, br.tick(bb));
        port.fail(1, "no path to the chest");
        br.tick(bb);
        int calls = port.started.size();
        for (int i = 0; i < 20; i++) {
            clock.advance(250);
            br.tick(bb);
        }
        assertEquals(calls, port.started.size(), "the held row is not retried per tick");
        assertNotNull(br.failures.held("needs.bag", null, clock.now()), "the brain rests the need (no runnable row)");
    }

    @Test
    void theSurfaceClimbIsAPricedFirstStepNotAGate() {
        world(Map.of("minecraft:oak_planks", 2));
        Need sticks = new Need("needs.bed", TaskKind.BASE_BED, b -> true, b -> null, b -> List.of(new Step.Obtain("stick", 4))).surface();
        bb.in.underDepth = 10;
        assertEquals(Status.RUNNING, sticks.tick(bb));
        assertEquals(AaLadder.CLIMB, port.started.get(0).chain(), "the climb first");
        assertTrue(sticks.options().startsWith("climb 10 up 40"), sticks.options());
        assertTrue(sticks.options().contains("then chain"), sticks.options());
        assertEquals(Unit.seconds(40) + 0.3, sticks.liveCostS(bb), 1e-6, "the row's price holds the climb and the chain (one craft, 0.3 s)");
        port.done(1, "done");
        bb.in.underDepth = 0;
        assertEquals(Status.RUNNING, sticks.tick(bb));
        assertEquals("craft stick 4", port.started.get(1).chain(), "up: the need's own chain");
        // an under-ground need that wants no surface prices no climb
        Need iron = new Need("needs.iron", TaskKind.IRON, b -> true, b -> null, b -> List.of(new Step.Obtain("stick", 4)));
        bb.in.underDepth = 10;
        iron.tick(bb);
        assertEquals("craft stick 4", port.started.get(2).chain(), "no climb before it");
    }
}
