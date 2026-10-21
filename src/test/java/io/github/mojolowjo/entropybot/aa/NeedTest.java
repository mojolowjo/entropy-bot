package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.brain.Brain;
import io.github.mojolowjo.entropybot.aa.brain.Tuning;
import io.github.mojolowjo.entropybot.aa.brain.Unit;
import io.github.mojolowjo.entropybot.aa.core.Blackboard;
import io.github.mojolowjo.entropybot.aa.core.Clock;
import io.github.mojolowjo.entropybot.aa.core.FakePort;
import io.github.mojolowjo.entropybot.aa.core.JobPort;
import io.github.mojolowjo.entropybot.aa.core.Status;
import io.github.mojolowjo.entropybot.aa.core.TaskKind;
import io.github.mojolowjo.entropybot.aa.core.Tracer;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/** Round 4A N (AA_ROUND4 A1-A4): a need is an option set; the planner, the wood and the climb are priced rows. */
class NeedTest {
    final Clock.Fake clock = new Clock.Fake(10_000_000);
    final FakePort port = new FakePort();
    final Blackboard bb = new Blackboard(clock, port, new Tracer());

    @Test
    void aPrerequisiteEndKeepsTheNeedRunningNoSettleHole() {
        Need n = new Need("needs.table", TaskKind.TABLE_FURNACE, b -> true, b -> null, b -> java.util.List.of(new io.github.mojolowjo.entropybot.aa.moves.Step.Plan("table")));
        port.noPlan = "missing 1 log - none in storage";
        port.noPlanLogs = 1;
        bb.fact(WoodOptions.FACTS, (Supplier<WoodOptions.WoodFacts>) () -> new WoodOptions.WoodFacts(0, List.of(new WoodOptions.Tree(10, 64, 0, 10, null))));
        assertEquals(Status.RUNNING, n.tick(bb));
        assertTrue(port.started.get(0).chain().endsWith("> cut 4 at 10 64 0"), port.started.get(0).chain());
        assertTrue(n.options().contains("cut + planner"), n.options());
        port.done(1, "done");
        for (int i = 0; i < 10; i++) {
            assertEquals(Status.RUNNING, n.tick(bb), "the facts lag: RUNNING on wait facts, never a FAILURE");
            clock.advance(1000);
        }
        assertTrue(n.waitingFacts());
        assertTrue(String.valueOf(bb.fact(Need.PLANNING_FACT)).contains(Need.WAIT_FACTS));
        bb.in.wood = 4;                            // the logs arrive in the bag: the wait ends
        port.noPlan = null;
        assertEquals(Status.RUNNING, n.tick(bb));
        assertEquals("goal table", port.started.get(1).chain(), "then the planner's chain");
    }

    @Test
    void theWaitIsBoundedBySettleMaxS() {
        Need n = new Need("needs.table", TaskKind.TABLE_FURNACE, b -> true, b -> null, b -> java.util.List.of(new io.github.mojolowjo.entropybot.aa.moves.Step.Plan("table")));
        port.noPlan = "missing 1 log - none in storage";
        port.noPlanLogs = 1;
        n.tick(bb);
        port.done(1, "done");
        n.tick(bb);
        clock.advance((long) (Tuning.get("settleMaxS") * 1000) + 1);
        assertEquals(Status.FAILURE, n.tick(bb), "review 4A-N (2): the cut moved no fact: held, not run again");
        assertEquals(1, port.started.size());
        assertTrue(String.join("|", port.log).contains("held: no change after the prerequisite"), port.log.toString());
    }

    @Test
    void toolsUnderGroundPriceTheClimbAsTheirFirstRow() {
        Need tools = AaDesires.needs().stream().filter(x -> x.id.equals("needs.tools")).findFirst().orElseThrow();
        bb.in.pickTier = 0;
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
    void theWoodRowIsChosenOncePerSecondNotPerTick() {
        int[] reads = {0};
        bb.fact(WoodOptions.FACTS, (Supplier<WoodOptions.WoodFacts>) () -> { reads[0]++; return new WoodOptions.WoodFacts(0, List.of()); });
        port.noPlan = "missing 1 log - none in storage";
        port.noPlanLogs = 1;
        Ways.State st = new Ways.State();
        var f = new io.github.mojolowjo.entropybot.aa.brain.Failures();
        Options.OptionMemory m = new Options.OptionMemory();
        for (int i = 0; i < 4; i++) Ways.plan(bb, st, f, "n", m, TS.plan("table"), clock.now());
        assertEquals(1, reads[0]);
        clock.advance(1000);
        Ways.plan(bb, st, f, "n", m, TS.plan("table"), clock.now());
        assertEquals(2, reads[0]);
    }

    @Test
    void aSearchingPlannerAndCutPlusPlannerLoseToBootstrapTools() {
        double hand = Tuning.get("plannerHandS"), pen = Tuning.get("searchPenalty");
        Options.OptionMemory m = new Options.OptionMemory();
        Options.Choice tools = new RungOptions.Tools().choose(new RungOptions.ToolsFacts(1, false), m, 0, null, 0, "still searching", null);
        Ways.Plan searching = new Ways.Plan("goal stone tools", JobPort.PlanState.SEARCHING, null, 0, "still searching (3 s)", 3000, null);
        Options.Choice c = Ways.withPlan(tools, searching, new Ways.State(), m, 0);
        assertEquals("bootstrap tools", c.best().id(), c.why());
        Options.Option p = c.ranked().stream().filter(o -> o.command().equals(Ways.WAIT_PLAN)).findFirst().orElseThrow();
        assertEquals(Unit.blocks(hand + pen), p.cost(), 1e-9, "hand + searchPenalty, in walk-blocks");
        // the planner said missing logs: cut (a tree 10 away: 10 + 6 x 4 + 10 = 44) + the planner's hand price
        Options.Option wood = WoodOptions.choose(new WoodOptions.WoodFacts(4, List.of(new WoodOptions.Tree(10, 64, 0, 10, null))), m, 0).best();
        assertEquals(44, wood.cost(), 1e-9);
        Ways.Plan none = new Ways.Plan("goal stone tools", JobPort.PlanState.NONE, null, 0, "missing 1 logs", 0, wood);
        Options.Choice d = Ways.withPlan(tools, none, new Ways.State(), m, 0);
        assertEquals("bootstrap tools", d.best().id(), d.why());
        Options.Option cut = d.ranked().stream().filter(o -> o.id().equals("cut + planner")).findFirst().orElseThrow();
        assertEquals(44 + Unit.blocks(hand), cut.cost(), 1e-9);
        assertTrue(cut.why().contains("+ planner"), cut.why());
    }

    @Test
    void aNeedWithEveryRowHeldRestsNoPerTickRetry() {
        Need n = new Need("needs.bag", TaskKind.BAG, b -> true, b -> null, b -> java.util.List.of(new io.github.mojolowjo.entropybot.aa.moves.Step.Deposit()));
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
        Need bed = new Need("needs.bed", TaskKind.BASE_BED, b -> true, b -> null, b -> java.util.List.of(new io.github.mojolowjo.entropybot.aa.moves.Step.Plan("bed"))).surface();
        bb.in.underDepth = 10;
        assertEquals(Status.RUNNING, bed.tick(bb));
        assertEquals(AaLadder.CLIMB, port.started.get(0).chain(), "the climb first");
        assertTrue(bed.options().startsWith("climb 10 up 40"), bed.options());
        assertTrue(bed.options().contains("then planner"), bed.options());
        assertEquals(Unit.seconds(40) + 0, bed.liveCostS(bb), 1e-6, "the row's price holds the climb (the plan costs 0 here)");
        port.done(1, "done");
        bb.in.underDepth = 0;
        assertEquals(Status.RUNNING, bed.tick(bb));
        assertEquals("goal bed", port.started.get(1).chain(), "up: the bed's own row");
        // an under-ground need that wants no surface prices no climb
        Need iron = new Need("needs.iron", TaskKind.IRON, b -> true, b -> null, b -> java.util.List.of(new io.github.mojolowjo.entropybot.aa.moves.Step.Plan("iron tools")));
        bb.in.underDepth = 10;
        iron.tick(bb);
        assertEquals("goal iron tools", port.started.get(2).chain(), "a dig for iron is not followed by a climb");
    }
}
