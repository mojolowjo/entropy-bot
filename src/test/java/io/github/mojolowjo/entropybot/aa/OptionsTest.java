package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.core.Blackboard;
import io.github.mojolowjo.entropybot.aa.core.Clock;
import io.github.mojolowjo.entropybot.aa.core.Cooldown;
import io.github.mojolowjo.entropybot.aa.core.FakePort;
import io.github.mojolowjo.entropybot.aa.core.Inputs;
import io.github.mojolowjo.entropybot.aa.core.RunJob;
import io.github.mojolowjo.entropybot.aa.core.Status;
import io.github.mojolowjo.entropybot.aa.core.TaskKind;
import io.github.mojolowjo.entropybot.aa.core.Tracer;
import io.github.mojolowjo.entropybot.aa.core.UtilitySelector;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/** S0 (MINING_PLAN 10, owner additions): the generic option picker, the planner tri-state, the manual driving, the idle probe. */
class OptionsTest {
    final Clock.Fake clock = new Clock.Fake(10_000_000);
    final FakePort port = new FakePort();
    final Blackboard bb = new Blackboard(clock, port, new Tracer());

    record F(boolean alpha, boolean beta) {}

    /** alpha 10, beta 20, the planner's chain at its cost x PLAN_UNIT. */
    static final class TwoWays implements Options.OptionSet<F> {
        @Override public Options.Choice choose(F f, Options.OptionMemory m, long now, String planned, double cost, String plannerWhy, String afterFail) {
            List<Options.Option> rows = new ArrayList<>();
            List<String> dropped = new ArrayList<>();
            if (f.alpha()) {
                if (m.blocked("alpha", now)) dropped.add("alpha: " + m.reason("alpha"));
                else rows.add(Options.scored("alpha", "alpha", "do alpha", 10, "cheap", "alpha", false, m));
            }
            if (f.beta()) rows.add(Options.scored("beta", "beta", "do beta", 20, "dear", "beta", false, m));
            if (planned != null) rows.add(Options.scored("planner", "planner", planned, cost * Options.PLAN_UNIT, "the plan", "planner", true, m));
            else dropped.add("planner: " + plannerWhy);
            return Options.Choice.of(rows, dropped, "no way");
        }

        @Override public boolean switchable(String end) { return !Options.ownerOrDanger(end); }
    }

    F facts = new F(true, true);

    Rung rung(String goal) {
        bb.fact("t.facts", (Supplier<F>) () -> facts);
        port.planCost = 10;          // the planner's option: 50 walk-blocks
        return new Rung("needs.tools", TaskKind.TOOLS, b -> true, b -> null, b -> goal, new Options.Picker<>("TOOLS", "t.facts", F.class, new TwoWays()));
    }

    @Test
    void theCheapestRunsAFailureSwitchesAndIsRemembered() {
        Rung r = rung("goal x");
        port.planCost = 10;          // 50 walk-blocks
        assertEquals(Status.RUNNING, r.tick(bb));
        assertEquals("do alpha", port.started.get(0).chain());
        assertTrue(r.options().startsWith("alpha 10 (cheap); then beta 20 (dear), planner 50"), r.options());
        port.fail(1, "no path to 1 2 3");
        assertEquals(Status.RUNNING, r.tick(bb), "switched at once");
        assertEquals("do beta", port.started.get(1).chain());
        assertTrue(r.options().contains("dropped: alpha: no path to 1 2 3"), r.options());
        port.fail(2, "stopped: your stop");
        assertEquals(Status.FAILURE, r.tick(bb), "the owner's stop never switches");
        assertEquals(2, port.started.size());
    }

    @Test
    void noPlanIsAnsweredAtOnceNotAfter45s() {
        Rung r = new Rung("needs.torches", TaskKind.TORCHES, b -> true, b -> null, b -> "goal torch 16");
        port.noPlan = "missing 2 coal";
        assertEquals(Status.FAILURE, r.tick(bb), "the first tick, no wait");
        assertTrue(r.reason().contains("no plan for goal torch 16: missing 2 coal"), r.reason());
        assertTrue(String.join("|", port.log).contains("skipped at once"), port.log.toString());
        // with a picker, NONE drops only the planner option
        Rung p = rung("goal x");
        assertEquals(Status.RUNNING, p.tick(bb));
        assertEquals("do alpha", port.started.get(0).chain());
        assertTrue(p.options().contains("planner: no plan for goal x: missing 2 coal"), p.options());
    }

    @Test
    void pickerRungRoutesMissingLogsThroughTheWoodOptions() {
        Rung r = rung("goal x");
        port.noPlan = "missing 1 logs - none in storage; no trees in my areas";
        bb.fact(WoodOptions.FACTS, (Supplier<WoodOptions.WoodFacts>) () -> new WoodOptions.WoodFacts(0,
                List.of(new WoodOptions.Tree(50, 64, 0, 50, null)), 0, true));
        assertEquals(Status.RUNNING, r.tick(bb));
        assertEquals("area 30 -20 70 20 trees1 neutral then cut 4", port.started.get(0).chain(), "the priced wood prerequisite, not the picker's alpha");
        port.fail(1, "error: chopped nothing - no trees in my areas");
        r.tick(bb);
        clock.advance(1000);
        r.tick(bb);
        assertTrue(port.started.size() >= 2, "the next wood option starts, no fail loop");
        assertTrue(port.started.get(1).chain().startsWith("area 42 -8 58 8 treeat"), port.started.get(1).chain());
    }

    @Test
    void searchingIsWaitedForOnlyBrieflyWhenAnotherOptionCanRun() {
        Rung r = rung("goal x");
        port.resolveTo = null;          // the planner searches
        assertEquals(Status.RUNNING, r.tick(bb));
        assertTrue(port.started.isEmpty(), "the planner gets its peek");
        clock.advance(Rung.PLAN_PEEK_MS + 1);
        assertEquals(Status.RUNNING, r.tick(bb));
        assertEquals("do alpha", port.started.get(0).chain(), "after the peek a planner-free option runs");
        // no option at all: the full wait, then the skip
        facts = new F(false, false);
        Rung q = rung("goal y");
        for (int i = 0; i < 4; i++) {
            assertEquals(Status.RUNNING, q.tick(bb));
            clock.advance(Rung.PLAN_PEEK_MS + 1);
        }
        clock.advance(Rung.PLAN_WAIT_MS);
        assertEquals(Status.RUNNING, q.tick(bb), "the gap restarts the clock: still within the wait");
        for (int i = 0; i < 20; i++) {
            clock.advance(3000);
            if (q.tick(bb) == Status.FAILURE) break;
        }
        assertTrue(q.reason().contains("still searched after 45s"), q.reason());
    }

    @Test
    void theMemorySweepDropsAFailureWhoseCauseIsGone() {
        Options.OptionMemory m = new Options.OptionMemory();
        Inputs in = new Inputs();
        in.pos = new int[]{0, 64, 0};
        m.failed("walk", "no path to 5 5 5", 0, in);
        m.failed("craft", "missing 2 stick", 0, in);
        assertTrue(m.blocked("walk", 1) && m.blocked("craft", 1));
        in.pos = new int[]{10, 64, 0};
        assertTrue(m.sweep(in).isEmpty(), "10 blocks is not far enough");
        in.pos = new int[]{20, 64, 0};
        assertEquals(List.of("walk NO_PATH"), m.sweep(in));
        in.planks = 4;
        assertEquals(List.of("craft NO_ITEM"), m.sweep(in));
        assertFalse(m.blocked("walk", 1) || m.blocked("craft", 1), "the blocks lift");
        assertEquals(1, m.fails("walk"), "the failure keeps weighing");
    }

    // ---- the owner's manual driving ----

    @Test
    void askWaitsForThePickAndCountsADifferentChoice() {
        Rung r = rung("goal x");
        AaManual man = AaManual.of(bb);
        man.ask = true;
        assertEquals(Status.RUNNING, r.tick(bb));
        assertTrue(port.started.isEmpty(), "waits for aa pick");
        assertNotNull(man.pending);
        assertEquals("do beta", man.pending.rows().get(1).command());
        man.pick = 2;
        assertEquals(Status.RUNNING, r.tick(bb));
        assertEquals("do beta", port.started.get(0).chain());
        assertEquals(1, man.differs);
        // the owner's pick is the one a failure is charged to
        port.fail(1, "no path to 1 2 3");
        r.tick(bb);
        assertTrue(r.options().contains("beta 40 (dear, failed 1x)"), r.options());
    }

    @Test
    void askTimesOutToMyFirstChoice() {
        Rung r = rung("goal x");
        AaManual.of(bb).ask = true;
        r.tick(bb);
        clock.advance(AaManual.ASK_MS);
        r.tick(bb);
        r.tick(bb);
        assertEquals("do alpha", port.started.get(0).chain());
    }

    @Test
    void doRunsAMetRungNowAndSkipLeavesOneOut() {
        Rung met = new Rung("needs.torches", TaskKind.TORCHES, b -> false, b -> null, b -> "craft torch 4");
        Rung other = new Rung("needs.bag", TaskKind.BAG, b -> true, b -> null, b -> "deposit");
        AaManual man = AaManual.of(bb);
        assertEquals("needs.torches", AaManual.rungId("torch"));
        man.doRung = "needs.torches";
        assertEquals(Status.FAILURE, other.tick(bb));
        assertTrue(other.reason().contains("waits: the owner's aa do torches"), other.reason());
        assertEquals(Status.RUNNING, met.tick(bb), "met, but the owner said do");
        port.done(1, "done");
        assertEquals(Status.SUCCESS, met.tick(bb));
        assertNull(man.doRung, "cleared when its task ended");
        man.skipped.add("needs.bag");
        assertEquals(Status.FAILURE, other.tick(bb));
        assertTrue(other.reason().contains("aa skip"), other.reason());
    }

    @Test
    void useForcesARankedOption() {
        Rung r = rung("goal x");
        AaManual man = AaManual.of(bb);
        man.use = "bet";
        assertEquals(Status.RUNNING, r.tick(bb));
        assertEquals("do beta", port.started.get(0).chain());
        assertNull(man.use, "used once");
        assertEquals(1, man.differs);
        assertTrue(String.join("|", port.log).contains("owner pick differs"), port.log.toString());
    }

    @Test
    void aFactsSupplierThatThrowsIsCountedAndThePlanRuns() {
        bb.fact("t.facts", (Supplier<F>) () -> { throw new IllegalStateException("boom"); });
        Options.Picker<F> p = new Options.Picker<>("TOOLS", "t.facts", F.class, new TwoWays());
        Rung r = new Rung("needs.tools", TaskKind.TOOLS, b -> true, b -> null, b -> "goal x", p);
        assertEquals(Status.RUNNING, r.tick(bb));
        assertEquals("goal x", port.started.get(0).chain(), "the planner's chain as it is");
        assertEquals(1, p.counters.factErrors);
        assertTrue(p.factsFailedSince(clock.now() - 1));
        assertTrue(String.join("|", port.log).contains("TOOLS facts: java.lang.IllegalStateException: boom"), port.log.toString());
    }

    @Test
    void aStuckHaltChargesTheRunningOptionForTheFullHold() {
        bb.fact("t.facts", (Supplier<F>) () -> new F(true, true));
        Options.Picker<F> p = new Options.Picker<>("TOOLS", "t.facts", F.class, new TwoWays());
        assertEquals("do alpha", p.pick(bb, null));
        p.stalled(bb, "stuck: needs.tools made no progress for 41s");
        clock.advance(9 * 60_000);
        assertEquals("do beta", p.pick(bb, null), "alpha is held 10 min after a stall");
        clock.advance(2 * 60_000);
        assertEquals("do alpha", p.pick(bb, null), "the hold ran out");
    }

    @Test
    void theIdleProbeLineSaysWhyNothingRuns() {
        UtilitySelector idle = new UtilitySelector("idle", "idle", in -> {
            double[] s = new double[TaskKind.values().length];
            s[TaskKind.STOCK_WOOD.ordinal()] = 50;
            s[TaskKind.WAIT.ordinal()] = 10;
            return s;
        }, UtilitySelector.BONUS,
                new UtilitySelector.Option(TaskKind.STOCK_WOOD, new Cooldown("w.cd", new RunJob("w", TaskKind.STOCK_WOOD, b -> null)).ranOnly()),
                new UtilitySelector.Option(TaskKind.WAIT, new Cooldown("wait.cd", RunJob.of("wait", TaskKind.WAIT, "wait 60", "idle")).ranOnly()));
        idle.tick(bb);
        idle.tick(bb);
        long lines = port.log.stream().filter(l -> l.startsWith("idle picked STOCK_WOOD -> failed at once: nothing to run")).count();
        assertEquals(1, lines, "logged once, not per tick: " + port.log);
    }
}

