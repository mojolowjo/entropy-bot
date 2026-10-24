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

/** S0 (MINING_PLAN 10, owner additions), one-planner: the generic option picker, the chain row, the manual driving, the idle probe. */
class OptionsTest {
    final Clock.Fake clock = new Clock.Fake(10_000_000);
    final FakePort port = new FakePort();
    final Blackboard bb = new Blackboard(clock, port, new Tracer());

    record F(boolean alpha, boolean beta) {}

    /** alpha 10, beta 20, the need's chain at its own price. */
    static final class TwoWays implements Options.OptionSet<F> {
        @Override public Options.Choice choose(F f, Options.OptionMemory m, long now, List<io.github.mojolowjo.entropybot.aa.moves.Step> planned, double cost, String plannerWhy, String afterFail) {
            List<Options.Option> rows = new ArrayList<>();
            List<String> dropped = new ArrayList<>();
            if (f.alpha()) {
                if (m.blocked("alpha", now)) dropped.add("alpha: " + m.reason("alpha"));
                else rows.add(Options.typed("alpha", "alpha", TS.plan("do alpha"), 10, "cheap", "alpha", m));
            }
            if (f.beta()) rows.add(Options.typed("beta", "beta", TS.plan("do beta"), 20, "dear", "beta", m));
            if (planned != null) rows.add(Options.typed("chain", "chain", planned, cost, "the chain", "chain", true, m));
            else dropped.add("chain: " + plannerWhy);
            return Options.Choice.of(rows, dropped, "no way");
        }

        @Override public boolean switchable(String end) { return !Options.ownerOrDanger(end); }
    }

    F facts = new F(true, true);

    Need rung(String goal) {
        bb.fact("t.facts", (Supplier<F>) () -> facts);
        return new Need("needs.tools", TaskKind.TOOLS, b -> true, b -> null, b -> TS.plan(goal.replaceFirst("^(goal|plan) ", "")), new Options.Picker<>("TOOLS", "t.facts", F.class, new TwoWays()));
    }

    @Test
    void theCheapestRunsAFailureSwitchesAndIsRemembered() {
        Need r = rung("obtain 1 x");
        assertEquals(Status.RUNNING, r.tick(bb));
        assertEquals("obtain 1 do alpha", port.started.get(0).chain());
        assertTrue(r.options().startsWith("alpha 10 (cheap); then beta 20 (dear)"), r.options());
        port.fail(1, "no path to 1 2 3");
        assertEquals(Status.RUNNING, r.tick(bb), "switched at once");
        assertEquals("obtain 1 do beta", port.started.get(1).chain());
        assertTrue(r.options().contains("dropped: alpha: no path to 1 2 3"), r.options());
        port.fail(2, "stopped: your stop");
        assertEquals(Status.FAILURE, r.tick(bb), "the owner's stop never switches");
        assertEquals(2, port.started.size());
    }

    @Test
    void noChainIsAnsweredAtOnceWithItsReason() {
        Need r = new Need("needs.torches", TaskKind.TORCHES, b -> true, b -> null, b -> java.util.List.of(new io.github.mojolowjo.entropybot.aa.moves.Step.Obtain("torch", 16)));
        assertEquals(Status.FAILURE, r.tick(bb), "the first tick, no wait");
        assertTrue(r.reason().contains("chain: no facts for the chain yet"), r.reason());
        assertTrue(String.join("|", port.log).contains("no way can run"), port.log.toString());
        // with a picker, no chain drops only the chain row
        Need p = rung("obtain 1 x");
        assertEquals(Status.RUNNING, p.tick(bb));
        assertEquals("obtain 1 do alpha", port.started.get(0).chain());
        assertTrue(p.options().contains("chain: no facts for the chain yet"), p.options());
    }

    /** Review 2 (2b): a done prerequisite (the climb) waits for the facts RUNNING (never a failure that hands the slot on). */
    @Test
    void aDonePrerequisiteWaitsRunningNotFailing() {
        Need r = new Need("needs.tools", TaskKind.TOOLS, b -> true, b -> null, b -> TS.plan("x"), new Options.Picker<>("TOOLS", "t.facts", F.class, new TwoWays())).surface();
        bb.fact("t.facts", (Supplier<F>) () -> facts);
        bb.in.underDepth = 10;
        assertEquals(Status.RUNNING, r.tick(bb));
        assertEquals(AaLadder.CLIMB, port.started.get(0).chain());
        port.done(1, "done");
        r.tick(bb);
        clock.advance(1000);
        assertEquals(Status.RUNNING, r.tick(bb), "the facts lag: the desire keeps the slot");
        assertEquals(1, port.started.size(), "no task while it waits");
        assertTrue(String.valueOf(bb.fact(Need.PLANNING_FACT)).contains(Need.WAIT_FACTS));
        bb.in.underDepth = 0;            // the depth moved: the wait ends early
        clock.advance(1000);
        r.tick(bb);
        assertEquals(2, port.started.size(), "the facts changed: the need's own row runs at once");
        assertEquals("obtain 1 do alpha", port.started.get(1).chain());
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
        Need r = rung("obtain 1 x");
        AaManual man = AaManual.of(bb);
        man.ask = true;
        assertEquals(Status.RUNNING, r.tick(bb));
        assertTrue(port.started.isEmpty(), "waits for aa pick");
        assertNotNull(man.pending);
        assertEquals("obtain 1 do beta", man.pending.rows().get(1).command());
        man.pick = 2;
        assertEquals(Status.RUNNING, r.tick(bb));
        assertEquals("obtain 1 do beta", port.started.get(0).chain());
        assertEquals(1, man.differs);
        // the owner's pick is the one a failure is charged to
        port.fail(1, "no path to 1 2 3");
        r.tick(bb);
        assertTrue(r.options().contains("beta 40 (dear, failed 1x)"), r.options());
    }

    @Test
    void askTimesOutToMyFirstChoice() {
        Need r = rung("obtain 1 x");
        AaManual.of(bb).ask = true;
        r.tick(bb);
        clock.advance(AaManual.askMs());
        r.tick(bb);
        r.tick(bb);
        assertEquals("obtain 1 do alpha", port.started.get(0).chain());
    }

    @Test
    void doRunsAMetRungNowAndSkipLeavesOneOut() {
        Need met = new Need("needs.torches", TaskKind.TORCHES, b -> false, b -> null, b -> TS.s("craft torch 4"));
        Need other = new Need("needs.bag", TaskKind.BAG, b -> true, b -> null, b -> TS.s("deposit"));
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
        Need r = rung("obtain 1 x");
        AaManual man = AaManual.of(bb);
        man.use = "bet";
        assertEquals(Status.RUNNING, r.tick(bb));
        assertEquals("obtain 1 do beta", port.started.get(0).chain());
        assertNull(man.use, "used once");
        assertEquals(1, man.differs);
        assertTrue(String.join("|", port.log).contains("owner pick differs"), port.log.toString());
    }

    @Test
    void aFactsSupplierThatThrowsIsCountedAndTheChainRuns() {
        bb.fact("t.facts", (Supplier<F>) () -> { throw new IllegalStateException("boom"); });
        bb.fact(NeedChain.WORLD_FACT, (Supplier<NeedChain.World>) () -> io.github.mojolowjo.entropybot.aa.FakePaths.paths(new NeedChain.World(java.util.Map.of(), false, w -> null)));
        Options.Picker<F> p = new Options.Picker<>("TOOLS", "t.facts", F.class, new TwoWays());
        Need r = new Need("needs.tools", TaskKind.TOOLS, b -> true, b -> null, b -> java.util.List.of(new io.github.mojolowjo.entropybot.aa.moves.Step.Obtain("x", 1)), p);
        assertEquals(Status.RUNNING, r.tick(bb));
        assertTrue(port.started.get(0).chain().startsWith("explore"), "the chain as it is (nothing known gives x: explore): " + port.started.get(0).chain());
        assertEquals(1, p.counters.factErrors);
        assertTrue(p.factsFailedSince(clock.now() - 1));
        assertTrue(String.join("|", port.log).contains("TOOLS facts: java.lang.IllegalStateException: boom"), port.log.toString());
    }

    @Test
    void aStuckHaltChargesTheRunningOptionForTheFullHold() {
        bb.fact("t.facts", (Supplier<F>) () -> new F(true, true));
        Options.Picker<F> p = new Options.Picker<>("TOOLS", "t.facts", F.class, new TwoWays());
        assertEquals("obtain 1 do alpha", p.pick(bb, null));
        p.stalled(bb, "stuck: needs.tools made no progress for 41s");
        clock.advance(9 * 60_000);
        assertEquals("obtain 1 do beta", p.pick(bb, null), "alpha is held 10 min after a stall");
        clock.advance(2 * 60_000);
        assertEquals("obtain 1 do alpha", p.pick(bb, null), "the hold ran out");
    }

    @Test
    void theIdleProbeLineSaysWhyNothingRuns() {
        UtilitySelector idle = new UtilitySelector("idle", "idle", in -> {
            double[] s = new double[TaskKind.values().length];
            s[TaskKind.STOCK_WOOD.ordinal()] = 50;
            s[TaskKind.WAIT.ordinal()] = 10;
            return s;
        }, UtilitySelector.bonusV(),
                new UtilitySelector.Option(TaskKind.STOCK_WOOD, new Cooldown("w.cd", new RunJob("w", TaskKind.STOCK_WOOD, b -> null)).ranOnly()),
                new UtilitySelector.Option(TaskKind.WAIT, new Cooldown("wait.cd", RunJob.of("wait", TaskKind.WAIT, java.util.List.of(new io.github.mojolowjo.entropybot.aa.moves.Step.Obtain("wait 60", 1)), "idle")).ranOnly()));
        idle.tick(bb);
        idle.tick(bb);
        long lines = port.log.stream().filter(l -> l.startsWith("idle picked STOCK_WOOD -> failed at once: nothing to run")).count();
        assertEquals(1, lines, "logged once, not per tick: " + port.log);
    }
}

