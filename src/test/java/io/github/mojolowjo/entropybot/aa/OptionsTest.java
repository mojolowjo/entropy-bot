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
        @Override public Options.Choice choose(F f, Options.OptionMemory m, long now, List<io.github.mojolowjo.entropybot.aa.moves.Step> planned, double cost, String plannerWhy, String afterFail) {
            List<Options.Option> rows = new ArrayList<>();
            List<String> dropped = new ArrayList<>();
            if (f.alpha()) {
                if (m.blocked("alpha", now)) dropped.add("alpha: " + m.reason("alpha"));
                else rows.add(Options.typed("alpha", "alpha", TS.plan("do alpha"), 10, "cheap", "alpha", m));
            }
            if (f.beta()) rows.add(Options.typed("beta", "beta", TS.plan("do beta"), 20, "dear", "beta", m));
            if (planned != null) rows.add(Options.typed("planner", "planner", planned, cost * Options.planUnit(), "the plan", "planner", true, m));
            else dropped.add("planner: " + plannerWhy);
            return Options.Choice.of(rows, dropped, "no way");
        }

        @Override public boolean switchable(String end) { return !Options.ownerOrDanger(end); }
    }

    F facts = new F(true, true);

    Need rung(String goal) {
        bb.fact("t.facts", (Supplier<F>) () -> facts);
        port.planCost = 10;          // the planner's option: 50 walk-blocks
        return new Need("needs.tools", TaskKind.TOOLS, b -> true, b -> null, b -> TS.plan(goal.replaceFirst("^(goal|plan) ", "")), new Options.Picker<>("TOOLS", "t.facts", F.class, new TwoWays()));
    }

    @Test
    void theCheapestRunsAFailureSwitchesAndIsRemembered() {
        Need r = rung("plan x");
        port.planCost = 10;          // 50 walk-blocks
        assertEquals(Status.RUNNING, r.tick(bb));
        assertEquals("plan do alpha", port.started.get(0).chain());
        assertTrue(r.options().startsWith("alpha 10 (cheap); then beta 20 (dear), planner 50"), r.options());
        port.fail(1, "no path to 1 2 3");
        assertEquals(Status.RUNNING, r.tick(bb), "switched at once");
        assertEquals("plan do beta", port.started.get(1).chain());
        assertTrue(r.options().contains("dropped: alpha: no path to 1 2 3"), r.options());
        port.fail(2, "stopped: your stop");
        assertEquals(Status.FAILURE, r.tick(bb), "the owner's stop never switches");
        assertEquals(2, port.started.size());
    }

    @Test
    void noPlanIsAnsweredAtOnceNotAfter45s() {
        Need r = new Need("needs.torches", TaskKind.TORCHES, b -> true, b -> null, b -> java.util.List.of(new io.github.mojolowjo.entropybot.aa.moves.Step.Plan("torch 16")));
        port.noPlan = "missing 2 coal";
        assertEquals(Status.FAILURE, r.tick(bb), "the first tick, no wait");
        assertTrue(r.reason().contains("no plan for goal torch 16: missing 2 coal"), r.reason());
        assertTrue(String.join("|", port.log).contains("no way can run"), port.log.toString());
        // with a picker, NONE drops only the planner option
        Need p = rung("plan x");
        assertEquals(Status.RUNNING, p.tick(bb));
        assertEquals("plan do alpha", port.started.get(0).chain());
        assertTrue(p.options().contains("planner: no plan for goal x: missing 2 coal"), p.options());
    }

    @Test
    void pickerRungRoutesMissingLogsThroughTheWoodOptions() {
        Need r = rung("plan x");
        facts = new F(false, false);          // review 2: no planner-free option, so the priced prerequisite is the only way
        port.noPlan = "missing 1 log - none in storage";
        port.noPlanLogs = 1;
        bb.fact(WoodOptions.FACTS, (Supplier<WoodOptions.WoodFacts>) () -> new WoodOptions.WoodFacts(0,
                List.of(new WoodOptions.Tree(50, 64, 0, 50, null))));
        assertEquals(Status.RUNNING, r.tick(bb));
        assertEquals("goto 50 64 0 near 2 > cut 4 at 50 64 0", port.started.get(0).chain(), "the priced wood prerequisite, not the picker's alpha");
        assertEquals(2, port.started.get(0).steps().size(), "rm-execution: the wood way's typed steps go with the task");
        port.fail(1, "error: chopped nothing - no trees within 32");
        r.tick(bb);
        clock.advance(1000);
        r.tick(bb);
        assertTrue(port.started.size() >= 2, "the next wood option starts, no fail loop");
        assertEquals("cut 4", port.started.get(1).chain(), "the seen tree held: the plain cut");
    }

    /** Review 2 (2a), b2/b4 smoke: "cut 4" ran before bootstrap tools (which chops its own wood); now the picker prices both. */
    @Test
    void stonePickaxePathIsNotBlockedByTheWoodPrerequisite() {
        Need r = rung("plan stone tools");
        port.noPlan = "missing 1 log - none in storage";
        port.noPlanLogs = 1;
        bb.fact(WoodOptions.FACTS, (Supplier<WoodOptions.WoodFacts>) () -> new WoodOptions.WoodFacts(0,
                List.of(new WoodOptions.Tree(50, 64, 0, 50, null))));
        assertEquals(Status.RUNNING, r.tick(bb));
        assertEquals("plan do alpha", port.started.get(0).chain(), "the planner-free whole-job option is cheaper than cut + the rest");
        assertTrue(r.options().contains("cut + planner"), "round 4A N: the wood prerequisite is a priced row: " + r.options());
    }

    /** Review 2 (2b): a done prerequisite waits for the facts RUNNING (never a failure that hands the slot on), bounded by SETTLE_MS. */
    @Test
    void aDonePrerequisiteWaitsRunningNotFailing() {
        Need r = rung("plan stone tools");
        facts = new F(false, false);
        port.noPlan = "missing 1 log - none in storage";
        port.noPlanLogs = 1;
        bb.fact(WoodOptions.FACTS, (Supplier<WoodOptions.WoodFacts>) () -> new WoodOptions.WoodFacts(0,
                List.of(new WoodOptions.Tree(50, 64, 0, 50, null))));
        assertEquals(Status.RUNNING, r.tick(bb));
        assertEquals(1, port.started.size());
        port.done(1, "done");
        r.tick(bb);
        clock.advance(1000);
        assertEquals(Status.RUNNING, r.tick(bb), "the planner's facts lag: the desire keeps the slot");
        assertEquals(1, port.started.size(), "no task while it waits");
        assertTrue(String.valueOf(bb.fact(Need.PLANNING_FACT)).contains(Need.WAIT_FACTS));
        bb.in.wood = 4;                  // round 4A N: the bag moved: the wait ends early
        clock.advance(1000);
        r.tick(bb);
        assertEquals(2, port.started.size(), "the facts changed: the prerequisite runs again at once");
    }

    /** Round 4A N (A2): the searching planner is a row at plannerHandS + searchPenalty: a known way runs at once. */
    @Test
    void aSearchingPlannerIsAPricedRowAndDroppedAfter45s() {
        Need r = rung("plan x");
        port.resolveTo = null;          // the planner searches
        assertEquals(Status.RUNNING, r.tick(bb));
        assertEquals("plan do alpha", port.started.get(0).chain(), "alpha 10 beats the searching planner (60 s + 60 s)");
        assertTrue(r.options().contains("planner (searching)"), r.options());
        // no other row: the need waits RUNNING on the planner, then drops the row after plannerWaitS and holds it
        facts = new F(false, false);
        Need q = rung("plan y");
        for (int i = 0; i < 15; i++) {
            assertEquals(Status.RUNNING, q.tick(bb));
            clock.advance(3_000);
        }
        clock.advance(1_000);              // 46 s searched (a gap over 5 s would start the clock afresh)
        assertEquals(Status.FAILURE, q.tick(bb), port.log.toString());
        assertTrue(q.reason().contains("still searched after 45 s"), q.reason());
        assertEquals(Status.FAILURE, q.tick(bb), "held: no per-tick retry");
        assertTrue(q.reason().contains("held"), q.reason());
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
        Need r = rung("plan x");
        AaManual man = AaManual.of(bb);
        man.ask = true;
        assertEquals(Status.RUNNING, r.tick(bb));
        assertTrue(port.started.isEmpty(), "waits for aa pick");
        assertNotNull(man.pending);
        assertEquals("plan do beta", man.pending.rows().get(1).command());
        man.pick = 2;
        assertEquals(Status.RUNNING, r.tick(bb));
        assertEquals("plan do beta", port.started.get(0).chain());
        assertEquals(1, man.differs);
        // the owner's pick is the one a failure is charged to
        port.fail(1, "no path to 1 2 3");
        r.tick(bb);
        assertTrue(r.options().contains("beta 40 (dear, failed 1x)"), r.options());
    }

    @Test
    void askTimesOutToMyFirstChoice() {
        Need r = rung("plan x");
        AaManual.of(bb).ask = true;
        r.tick(bb);
        clock.advance(AaManual.ASK_MS);
        r.tick(bb);
        r.tick(bb);
        assertEquals("plan do alpha", port.started.get(0).chain());
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
        Need r = rung("plan x");
        AaManual man = AaManual.of(bb);
        man.use = "bet";
        assertEquals(Status.RUNNING, r.tick(bb));
        assertEquals("plan do beta", port.started.get(0).chain());
        assertNull(man.use, "used once");
        assertEquals(1, man.differs);
        assertTrue(String.join("|", port.log).contains("owner pick differs"), port.log.toString());
    }

    @Test
    void aFactsSupplierThatThrowsIsCountedAndThePlanRuns() {
        bb.fact("t.facts", (Supplier<F>) () -> { throw new IllegalStateException("boom"); });
        Options.Picker<F> p = new Options.Picker<>("TOOLS", "t.facts", F.class, new TwoWays());
        Need r = new Need("needs.tools", TaskKind.TOOLS, b -> true, b -> null, b -> java.util.List.of(new io.github.mojolowjo.entropybot.aa.moves.Step.Plan("x")), p);
        assertEquals(Status.RUNNING, r.tick(bb));
        assertEquals("plan x", port.started.get(0).chain(), "the planner's chain as it is");
        assertEquals(1, p.counters.factErrors);
        assertTrue(p.factsFailedSince(clock.now() - 1));
        assertTrue(String.join("|", port.log).contains("TOOLS facts: java.lang.IllegalStateException: boom"), port.log.toString());
    }

    @Test
    void aStuckHaltChargesTheRunningOptionForTheFullHold() {
        bb.fact("t.facts", (Supplier<F>) () -> new F(true, true));
        Options.Picker<F> p = new Options.Picker<>("TOOLS", "t.facts", F.class, new TwoWays());
        assertEquals("plan do alpha", p.pick(bb, null));
        p.stalled(bb, "stuck: needs.tools made no progress for 41s");
        clock.advance(9 * 60_000);
        assertEquals("plan do beta", p.pick(bb, null), "alpha is held 10 min after a stall");
        clock.advance(2 * 60_000);
        assertEquals("plan do alpha", p.pick(bb, null), "the hold ran out");
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
                new UtilitySelector.Option(TaskKind.WAIT, new Cooldown("wait.cd", RunJob.of("wait", TaskKind.WAIT, java.util.List.of(new io.github.mojolowjo.entropybot.aa.moves.Step.Plan("wait 60")), "idle")).ranOnly()));
        idle.tick(bb);
        idle.tick(bb);
        long lines = port.log.stream().filter(l -> l.startsWith("idle picked STOCK_WOOD -> failed at once: nothing to run")).count();
        assertEquals(1, lines, "logged once, not per tick: " + port.log);
    }
}

