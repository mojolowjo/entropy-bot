package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.brain.Failures;
import io.github.mojolowjo.entropybot.aa.brain.Goals;
import io.github.mojolowjo.entropybot.aa.brain.TestData;
import io.github.mojolowjo.entropybot.aa.brain.Thing;
import io.github.mojolowjo.entropybot.aa.brain.Tuning;
import io.github.mojolowjo.entropybot.aa.core.Blackboard;
import io.github.mojolowjo.entropybot.aa.core.Clock;
import io.github.mojolowjo.entropybot.aa.core.FakePort;
import io.github.mojolowjo.entropybot.aa.core.JobPort;
import io.github.mojolowjo.entropybot.aa.core.RunJob;
import io.github.mojolowjo.entropybot.aa.core.Status;
import io.github.mojolowjo.entropybot.aa.core.TaskKind;
import io.github.mojolowjo.entropybot.aa.core.Tracer;
import io.github.mojolowjo.entropybot.aa.moves.Car;
import io.github.mojolowjo.entropybot.aa.moves.FactChange;
import io.github.mojolowjo.entropybot.aa.moves.PlanDiff;
import io.github.mojolowjo.entropybot.aa.moves.Step;
import io.github.mojolowjo.entropybot.aa.moves.StepId;
import io.github.mojolowjo.entropybot.aa.moves.StepNeeds;
import io.github.mojolowjo.entropybot.aa.moves.Steps;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 0.39.z70 (design D2 3.1 + 6 tests 1-6, Job A; owner's ruling R1 = c): step identity, the honest park, the hidden end,
 * plan stability. Runs M (19:00:59 / 19:01:49: the strip to y 16 failed from y 70 and from y 84, parked as "the same step
 * failed again" by its text), N (21:24:13: a true repeat), O (22:14:51: the re-planned run had ended and the 180 s watch said
 * "no progress" over its end). Fake hands; ticks at 20 a second (game fact).
 */
class Z70StepCheckTest {
    @AfterEach
    void clear() {
        TestData.clear();
        FactChange.clear();
    }

    static final Step STRIP_K1 = new Step.ToStrip(-72, 16, -99, "iron_ore|16|west");

    /** Fake hands: the feet as set, a bag of fixed counts, each step's status from a function, the fact its failure found. */
    static final class Hands implements Steps.Hands {
        int[] feet = {-55, 70, -60};
        Map<String, Integer> bag = Map.of();
        Function<Step, Car.Status> status = s -> Car.Status.running("on it");
        Function<Step, FactChange.Fact> fact = s -> null;
        final List<String> started = new ArrayList<>(), logged = new ArrayList<>();
        FactChange.Fact last;

        @Override public String start(Step s) { started.add(s.text()); return null; }
        @Override public Car.Status status(Step s) {
            Car.Status st = status.apply(s);
            if (st.state() == Car.State.FAILED) last = fact.apply(s);
            return st;
        }
        @Override public void cancel(String why) {}
        @Override public FactChange.Fact lastFact() { FactChange.Fact f = last; last = null; return f; }
        @Override public int[] feet() { return feet; }
        @Override public void log(String line) { logged.add(line); }
        @Override public StepNeeds.Bag bag() {
            return new StepNeeds.Bag() {
                @Override public int bestTier(String kind) { return 0; }
                @Override public String bestTool(String kind) { return null; }
                @Override public int usesLeft(String id) { return io.github.mojolowjo.entropybot.aa.brain.ToolUses.UNLIMITED; }
                @Override public int count(String id) { return bag.getOrDefault(id, 0); }
                @Override public Map<String, Integer> items() { return bag; }        // FS fix: the whole bag is listable (Steps.movedSince)
            };
        }
    }

    static FactChange.Fact stripFact(String why) { return new FactChange.Fact("source strip iron_ore|16|west", why); }

    /**
     * Test 1 (run M, P1): the strip walk fails from y 70; re-planned to the explore while paths compute; a landed path
     * re-prices it back to the same strip walk (no step ended DONE between); it fails again from y 84 - another start cell,
     * so a re-plan, not a park (z30 parked here: the same text "to y 16 (priced path)").
     */
    @Test
    void t1_runMTheSameStripFromAnotherCellRePlans() {
        Hands h = new Hands();
        h.status = s -> s instanceof Step.ToStrip ? Car.Status.failed("walk ended at y " + h.feet[1]) : Car.Status.running("exploring");
        h.fact = s -> stripFact("walk ended at y " + h.feet[1] + " short of y 16");
        List<String> asked = new ArrayList<>();
        Steps run = new Steps(h);
        run.start("goal.iron", List.of(STRIP_K1), 0, (failed, fact, why) -> {
            asked.add(fact.key());
            if (fact.price()) return new Steps.Routine(List.of(STRIP_K1), null);
            return new Steps.Routine(List.of(new Step.AwaitPaths(30)), null);      // z76 send-back 3: the standing step
        });
        run.tick(1);                                     // fails from -55 70 -60: re-planned to the standing step
        assertTrue(run.running(), run.why());
        assertEquals("stand while paths compute (up to 30 s)", run.steps().get(0).text());
        h.feet = new int[]{-50, 84, -55};               // (the bot is elsewhere at the next look: another start cell)
        run.repriceAtNextStep(FactChange.priced("the strip walk's path landed"));
        run.tick(2);                                     // re-priced where it stands: the strip walk again
        assertEquals(STRIP_K1, run.steps().get(0), run.steps().toString());
        assertTrue(run.running(), run.why());
        run.tick(3);                                     // fails again, from y 84: re-planned (2 failure re-plans + 1 re-price)
        assertTrue(run.running(), "another start cell is another question for the planner: " + run.why());
        assertEquals(List.of("source strip iron_ore|16|west", "price walk", "source strip iron_ore|16|west"), asked);
        assertEquals("-50 84 -55", run.lastFailed().startCell());
    }

    /** Test 2 (honest loop, run N P3 shape): the same strip walk, cell, fact and bag twice parks with the honest words. */
    @Test
    void t2_theSameTryTwiceParksHonestly() {
        Hands h = new Hands();
        h.status = s -> Car.Status.failed("walk ended at y 70");
        h.fact = s -> stripFact("walk ended at y 70 short of y 16");
        int[] asked = {0};
        Steps run = new Steps(h);
        run.start("goal.iron", List.of(STRIP_K1), 0, (failed, fact, why) -> { asked[0]++; return new Steps.Routine(List.of(STRIP_K1), null); });
        run.tick(1);
        assertTrue(run.running(), run.why());
        run.tick(2);
        assertEquals(Steps.Phase.FAILED, run.phase());
        assertEquals(1, asked[0], "the second try is not asked again");
        assertEquals("stopped at step 1 (to y 16 (priced path)): the same way failed twice from the same place with the same bag: to y 16 (priced path) - "
                + "walk ended at y 70 short of y 16 (re-plans this run: 1; dropped: strip iron_ore|16|west)", run.why());
    }

    /** job-dig2 (run JAU 05:38:44): two NOT KNOWN ends of the same step from the same cell and bag never park (rule 15). */
    @Test
    void dig2_notKnownEndsNeverPark() {
        Hands h = new Hands();
        h.status = s -> Car.Status.failed("Baritone's goal ended at y 63 without an exhausted search");
        h.fact = s -> new FactChange.Fact(FactChange.NOT_KNOWN + FactChange.bandKey(16, 16), "Baritone's goal ended at y 63 without an exhausted search (a budget stop or another end) - not a no-path (rule 15: not known)");
        int[] asked = {0};
        Steps run = new Steps(h);
        run.start("goal.iron", List.of(STRIP_K1), 0, (failed, fact, why) -> { asked[0]++; return new Steps.Routine(List.of(STRIP_K1), null); });
        run.tick(1);
        run.tick(2);
        run.tick(3);
        assertTrue(run.running(), "a not-known end is no failure: " + run.why());
        assertEquals(3, asked[0], "every not-known end re-plans");
        assertFalse(run.why() != null && run.why().contains("the same way failed twice"), run.why());
    }

    /**
     * Test 3: A fails, re-planned to [B, A]; B ends DONE (progress: the craft put sticks in the bag); A fails the same way again -
     * re-planned, not parked. (FS fix: a step done with the feet AND the whole bag unchanged is no progress - FsParkMemoryTest.)
     */
    @Test
    void t3_aDoneStepBetweenClearsTheMemory() {
        Hands h = new Hands();
        Step b = new Step.Craft("minecraft:stick", 4);
        h.status = s -> {
            if (s instanceof Step.ToStrip) return Car.Status.failed("walk ended at y 70");
            h.bag = Map.of("minecraft:stick", 4);                                 // the craft's sticks: the bag changed
            return Car.Status.done("ok");
        };
        h.fact = s -> stripFact("walk ended at y 70 short of y 16");
        int[] asked = {0};
        Steps run = new Steps(h);
        run.start("goal.iron", List.of(STRIP_K1), 0, (failed, fact, why) -> { asked[0]++; return new Steps.Routine(List.of(b, STRIP_K1), null); });
        run.tick(1);                                     // A fails: [B, A]
        assertNotNull(run.lastFailed());
        run.tick(2);                                     // B done: the memory is cleared
        assertNull(run.lastFailed());
        run.tick(3);                                     // A fails the same way: re-planned
        assertEquals(2, asked[0]);
        assertTrue(run.running(), run.why());
    }

    /**
     * Rule 15: no feet known (or no bag) is never "the same place": re-planned each time. 0.39.z76 send-back 4: the run's own
     * count cap is gone (run AW 03:18:31), so the run asks its planner every time; the order's planner (Goals.replan) answers.
     */
    @Test
    void unknownCellIsNeverTheSame() {
        Hands h = new Hands();
        h.feet = null;
        h.status = s -> Car.Status.failed("walk ended at y 70");
        h.fact = s -> stripFact("walk ended at y 70 short of y 16");
        int[] asked = {0};
        Steps run = new Steps(h);
        run.start("goal.iron", List.of(STRIP_K1), 0, (failed, fact, why) -> { asked[0]++; return new Steps.Routine(List.of(STRIP_K1), null); });
        int past = 11 + 5;                               // past the deleted run cap (replan.maxPerOrder 3 + maxSourceDrops 8)
        for (int t = 1; t <= past; t++) run.tick(t);
        assertTrue(run.running(), run.why());
        assertEquals(past, asked[0]);
    }

    /** The order's own count and dropped sources go into the park line when the order answers (Goals.orderState). */
    @Test
    void theParkLineSaysTheOrdersCount() {
        Hands h = new Hands();
        h.status = s -> Car.Status.failed("walk ended at y 70");
        h.fact = s -> stripFact("walk ended at y 70 short of y 16");
        Steps.Attempt[] parked = {null};
        Steps run = new Steps(h);
        run.start("goal.iron", List.of(STRIP_K1), 0, new Steps.Replanner() {
            @Override public Steps.Routine replan(Step failed, FactChange.Fact fact, String why) { return new Steps.Routine(List.of(STRIP_K1), null); }
            @Override public String orderState() { return "re-plans this order: 3; dropped: strip iron_ore|16|west"; }
            @Override public void parked(Steps.Attempt a) { parked[0] = a; }
        });
        run.tick(1);
        run.tick(2);
        assertTrue(run.why().endsWith("(re-plans this order: 3; dropped: strip iron_ore|16|west)"), run.why());
        assertEquals(new StepId("toStrip", "-72 16 -99 iron_ore|16|west"), parked[0].id());
        assertEquals("-55 70 -60", parked[0].startCell());
        assertEquals("", parked[0].needsBag(), "a walk needs nothing from the bag (known, empty)");
    }

    /**
     * Test 4 (run O, P5): the no-progress watch re-plans the order; the new step is refused at its start ("busy: putting
     * back 3 blocks", no fact), so the typed run ends FAILED inside the watch's call. RunJob stays RUNNING that tick with no
     * cancel and no "no progress"; the next poll reads the run's own end.
     */
    @Test
    void t4_runOTheWatchLeavesTheRunsOwnEnd() {
        Clock.Fake clock = new Clock.Fake(5_000_000);
        FakePort port = new FakePort();
        Blackboard bb = new Blackboard(clock, port, new Tracer());
        Steps run = new Steps(new Steps.Hands() {
            @Override public String start(Step s) { return s instanceof Step.AwaitPaths ? "busy: putting back 3 blocks" : null; }
            @Override public Car.Status status(Step s) { return Car.Status.running("crafting"); }
            @Override public void cancel(String why) {}
        });
        run.start("goal.torch", List.of(new Step.Craft("furnace", 1)), 0, (failed, fact, why) -> new Steps.Routine(List.of(new Step.AwaitPaths(30)), null));
        // the adapter (AaRuntime.noProgress): ended before the watch / ended in its re-plan -> ENDED; the poll then reads the end
        port.onNoProgress = (id, text) -> {
            if (!run.running()) return JobPort.NoProgress.ENDED;
            if (run.noProgress(text)) return JobPort.NoProgress.REPLANNED;
            port.fail(id, run.why());
            return JobPort.NoProgress.ENDED;
        };
        RunJob job = new RunJob("goal.torch.job", TaskKind.EXPLORE, b -> new JobPort.TaskSpec(TaskKind.EXPLORE, "craft furnace 1", "owner goal", 1, 600_000, 180_000));
        assertEquals(Status.RUNNING, job.tick(bb));
        clock.advance(181_000);
        assertEquals(Status.RUNNING, job.tick(bb), "the watch leaves the ended run to the next poll");
        assertTrue(port.cancelled.isEmpty(), port.cancelled.toString());
        assertTrue(port.log.stream().noneMatch(l -> l.contains("no progress")), port.log.toString());
        assertEquals(Status.FAILURE, job.tick(bb));
        assertEquals("stopped at step 1 (stand while paths compute (up to 30 s)): busy: putting back 3 blocks", job.reason());
        assertTrue(port.incidents.isEmpty(), "no stuck incident: " + port.incidents);
    }

    /** NOT_MINE (no order's typed run): the watch cancels and ends "no progress" as before. */
    @Test
    void notMineEndsAsBefore() {
        Clock.Fake clock = new Clock.Fake(5_000_000);
        FakePort port = new FakePort();
        Blackboard bb = new Blackboard(clock, port, new Tracer());
        RunJob job = new RunJob("x.job", TaskKind.EXPLORE, b -> new JobPort.TaskSpec(TaskKind.EXPLORE, "x", "y", 1, 600_000, 180_000));
        job.tick(bb);
        clock.advance(181_000);
        assertEquals(Status.FAILURE, job.tick(bb));
        assertEquals("no progress", job.reason());
        assertEquals(1, port.cancelled.size());
    }

    /** Test 5 (design 1.8): two ChopTree with equal arrays are one step by identity; a re-price with the same steps does not switch. */
    @Test
    void t5_chopTreeIdentityAndNoChurn() {
        Step a = new Step.ChopTree(new int[]{1, 2, 3}, 4, null), b = new Step.ChopTree(new int[]{1, 2, 3}, 4, null);
        assertNotEquals(a, b, "record equality compares the array by reference (the churn's cause)");
        assertEquals(StepId.of(a), StepId.of(b));
        assertTrue(StepId.same(List.of(a), List.of(b)));
        assertNotEquals(StepId.of(a), StepId.of(new Step.ChopTree(new int[]{1, 2, 4}, 4, null)));

        Hands h = new Hands();
        h.status = s -> s instanceof Step.Walk ? Car.Status.done("arrived") : Car.Status.running("cutting");
        int[] switched = {0};
        Steps run = new Steps(h);
        run.start("goal.log", List.of(new Step.Walk(1, 2, 3, 1), a), 0, new Steps.Replanner() {
            @Override public Steps.Routine replan(Step failed, FactChange.Fact fact, String why) { return new Steps.Routine(List.of(new Step.ChopTree(new int[]{1, 2, 3}, 4, null)), null); }
            @Override public void switched(PlanDiff d, boolean reprice) { switched[0]++; }
        });
        run.repriceAtNextStep(FactChange.priced("a path landed"));
        run.tick(1);                                     // the walk done: re-priced at the boundary, the same steps
        assertEquals(0, switched[0]);
        assertSame(a, run.steps().get(1), "kept the running plan");
        assertFalse(run.why().contains("re-priced"), run.why());
        assertTrue(h.logged.isEmpty(), h.logged.toString());
    }

    /** Test 6: the plan diff by identity. */
    @Test
    void t6_planDiff() {
        Step A = new Step.Craft("a", 1), B = new Step.Craft("b", 1), C = new Step.Craft("c", 1), D = new Step.Craft("d", 1);
        Step X = new Step.Craft("x", 1), Y = new Step.Craft("y", 1);
        PlanDiff d1 = PlanDiff.of(List.of(A, B, C, D), List.of(X, B, C, D));
        assertEquals(3, d1.kept());
        assertEquals(2, d1.changed());
        assertEquals("re-plan 1: kept 3, changed 2 (removed: craft a 1; added: craft x 1)", d1.line(1));
        PlanDiff d2 = PlanDiff.of(List.of(A, B, C), List.of(X, Y));
        assertEquals(0, d2.kept());
        assertEquals(5, d2.changed());
        // the middle as a multiset: [A, B, C, D] -> [A, C, B, D] keeps all four (B and C swapped)
        PlanDiff d3 = PlanDiff.of(List.of(A, B, C, D), List.of(A, C, B, D));
        assertEquals(4, d3.kept());
        assertEquals(0, d3.changed());
        assertEquals("re-plan 2: kept 4, changed 0", d3.line(2));
    }

    /** The failure re-plan logs its diff and the order counts it (aa status: re-plans, steps changed per re-plan). */
    @Test
    void theRePlanLogsItsDiffAndTheOrderCountsIt() {
        Hands h = new Hands();
        h.status = s -> s instanceof Step.ToStrip ? Car.Status.failed("walk ended at y 70") : Car.Status.running("on it");
        h.fact = s -> stripFact("walk ended at y 70 short of y 16");
        FakePort port = new FakePort();
        Blackboard bb = new Blackboard(new Clock.Fake(0), port, new Tracer());
        Goals.Goal g = Goals.add(bb, new Thing.Result("iron_ingot", "iron_ingot", false, List.of("minecraft:iron_ingot"), List.of("iron_ingot"), 0, null, "iron_ingot", null), 1, 0, 0);
        Steps.Replanner order = Goals.replanner(bb, g);
        Steps run = new Steps(h);
        Step smelt = new Step.Smelt("minecraft:iron_ingot", 1, false);
        run.start("goal.iron", List.of(STRIP_K1, smelt), 0, new Steps.Replanner() {
            @Override public Steps.Routine replan(Step failed, FactChange.Fact fact, String why) { return new Steps.Routine(List.of(new Step.Explore(5, "minecraft:iron_ore"), STRIP_K1, smelt), null); }
            @Override public void switched(PlanDiff d, boolean reprice) { order.switched(d, reprice); }
        });
        run.tick(1);
        assertEquals(List.of("re-plan 1: kept 2, changed 1 (removed: none; added: explore 5 until minecraft:iron_ore)"), h.logged);
        assertTrue(run.why().contains("re-plan 1: kept 2, changed 1"), run.why());
        assertEquals("iron_ingot: re-plans 1 (0 re-priced), steps changed per re-plan 1.0", Goals.stabilityLine(g));
    }

    // ---- owner's ruling R1 = c: an honest-loop park tries once more when a fact it parked on changes ----

    static Steps.Attempt cobbleTry() {
        return new Steps.Attempt(new StepId("craft", "furnace 1"), "bag #minecraft:stone_crafting_materials", List.of(0, 64, 0),
                new TreeMap<>(Map.of("minecraft:cobblestone", 0)));
    }

    @Test
    void r1_loopChangeIsAFactThatChanged() {
        Goals.LoopPark p = new Goals.LoopPark(cobbleTry(), Map.of("minecraft:cobblestone", 0), Set.of("cobblestone at 1 2 3"));
        assertNull(Goals.loopChange(p, Map.of("minecraft:cobblestone", 0), new int[]{0, 64, 0}, Set.of("cobblestone at 1 2 3")), "nothing changed");
        assertEquals("cobblestone in the bag rose from 0 to 2", Goals.loopChange(p, Map.of("minecraft:cobblestone", 2), new int[]{0, 64, 0}, null));
        int far = (int) Math.ceil(Failures.movedV());
        assertNull(Goals.loopChange(p, Map.of(), new int[]{far - 1, 64, 0}, null), "less than failures.moved");
        assertTrue(Goals.loopChange(p, Map.of(), new int[]{far, 64, 0}, null).startsWith("moved " + far + " blocks from where it parked (0 64 0"));
        assertEquals("a new source seen: cobblestone at 9 9 9", Goals.loopChange(p, Map.of(), new int[]{0, 64, 0}, Set.of("cobblestone at 1 2 3", "cobblestone at 9 9 9")));
        assertNull(Goals.loopChange(p, null, null, null), "rule 15: nothing known is no change");
        assertNull(Goals.loopChange(new Goals.LoopPark(cobbleTry(), Map.of(), null), Map.of(), new int[]{0, 64, 0}, Set.of("cobblestone at 9 9 9")),
                "no sources known at the park: a source now is not 'new'");
    }

    /**
     * R1 flow: the run parks on the honest loop (the order keeps the try), the park line says it tries once more; nothing
     * changed: still parked; 3 cobblestone in the bag: unparked once, said in the chat; a second honest park stays parked.
     */
    @Test
    void r1_anHonestParkTriesOnceMoreWhenANeedRose() {
        FakePort port = new FakePort();
        Blackboard bb = new Blackboard(new Clock.Fake(0), port, new Tracer());
        bb.in.bag = Map.of();
        bb.in.pos = new int[]{0, 64, 0};
        Goals.Goal g = Goals.add(bb, new Thing.Result("furnace", "furnace", false, List.of("minecraft:furnace"), List.of("furnace"), 0, null, "furnace", null), 1, 0, 0);
        Goals.replanner(bb, g).parked(cobbleTry());
        assertTrue(Goals.park(bb, g, "stopped at step 1 (craft furnace 1): the same way failed twice from the same place with the same bag: ..."));
        assertNotNull(g.loopPark);
        assertTrue(port.told.get(0).contains("it tries once more by itself when a need in the bag rises"), port.told.toString());
        assertTrue(Goals.retryLoopParks(bb).isEmpty(), "nothing changed");
        assertNotNull(g.parked);

        bb.in.bag = Map.of("cobblestone", 3);
        List<String> said = Goals.retryLoopParks(bb);
        assertEquals(List.of("order furnace tries once more: cobblestone in the bag rose from 0 to 3 (it parked on: stopped at step 1 (craft furnace 1): the same way failed twice from the same place with the same bag: ...)"), said);
        assertEquals(said.get(0), port.told.get(port.told.size() - 1), "said in the chat");
        assertNull(g.parked);
        assertNull(Goals.blocked(bb, g), "feasible again");

        // the second honest park: no second try (once per order)
        bb.in.bag = Map.of();
        Goals.replanner(bb, g).parked(cobbleTry());
        Goals.park(bb, g, "again");
        assertNull(g.loopPark);
        bb.in.bag = Map.of("cobblestone", 9);
        assertTrue(Goals.retryLoopParks(bb).isEmpty());
        assertEquals("again", g.parked);
    }

    /**
     * Review 1: an honest-loop try whose run ended without a park (a passing end) is dropped when the next run starts, so a
     * later unrelated park ("no way to get it") gets no R1 retry.
     */
    @Test
    void r1_aStaleTryNeverReachesALaterPark() {
        FakePort port = new FakePort();
        Blackboard bb = new Blackboard(new Clock.Fake(0), port, new Tracer());
        bb.in.bag = Map.of();
        bb.in.pos = new int[]{0, 64, 0};
        Goals.Goal g = Goals.add(bb, new Thing.Result("furnace", "furnace", false, List.of("minecraft:furnace"), List.of("furnace"), 0, null, "furnace", null), 1, 0, 0);
        Goals.replanner(bb, g).parked(cobbleTry());     // the run parked on the loop, but its end was passing: no park took it
        Goals.runStarts(g);                              // the next run starts
        assertNull(g.loopAttempt);
        Goals.park(bb, g, "no way to get it: no cobblestone known");
        assertNull(g.loopPark, "the unrelated park keeps no loop facts");
        bb.in.bag = Map.of("cobblestone", 9);
        assertTrue(Goals.retryLoopParks(bb).isEmpty());
        assertNotNull(g.parked);
    }

    /** Review 2: one Attempt part differs, no DONE between - the fact key only: re-planned, not parked. */
    @Test
    void onlyTheFactKeyDiffersRePlans() {
        Hands h = new Hands();
        h.status = s -> Car.Status.failed("walk ended at y 70");
        int[] n = {0};
        h.fact = s -> new FactChange.Fact("source strip k" + (++n[0] <= 1 ? "1" : "2"), "walk ended at y 70 short of y 16");
        int[] asked = {0};
        Steps run = new Steps(h);
        run.start("goal.iron", List.of(STRIP_K1), 0, (failed, fact, why) -> { asked[0]++; return new Steps.Routine(List.of(STRIP_K1), null); });
        run.tick(1);
        run.tick(2);                                     // the same step, cell and bag; another fact key: re-planned
        assertEquals(2, asked[0], run.why());
        assertTrue(run.running(), run.why());
        run.tick(3);                                     // the k2 fact again, all equal: parked
        assertEquals(Steps.Phase.FAILED, run.phase());
        assertTrue(run.why().contains("the same way failed twice from the same place with the same bag"), run.why());
    }

    /** Review 2: one Attempt part differs, no DONE between - the needs' bag count only: re-planned; then all equal: parked. */
    @Test
    void onlyTheBagDiffersRePlans() {
        Hands h = new Hands();
        Step place = new Step.PlaceNear("minecraft:crafting_table");     // needs 1 crafting_table from the bag (its own data)
        h.bag = Map.of("minecraft:crafting_table", 1);
        h.status = s -> Car.Status.failed("no cell within 3 blocks");
        h.fact = s -> new FactChange.Fact("place minecraft:crafting_table", "place crafting_table near me failed: no cell within 3 blocks");
        int[] asked = {0};
        Steps run = new Steps(h);
        run.start("goal.torch", List.of(place), 0, (failed, fact, why) -> { asked[0]++; return new Steps.Routine(List.of(place), null); });
        run.tick(1);
        h.bag = Map.of("minecraft:crafting_table", 2);   // the same step, fact and cell; one more table in the bag
        run.tick(2);
        assertEquals(2, asked[0], run.why());
        assertTrue(run.running(), run.why());
        assertEquals("crafting_table 2", run.lastFailed().needsBag());
        run.tick(3);                                     // all equal now: parked
        assertEquals(Steps.Phase.FAILED, run.phase());
        assertTrue(run.why().contains("the same way failed twice from the same place with the same bag: place minecraft:crafting_table near me"), run.why());
    }

    /** R1 is the honest-loop park only: any other park waits for aa want as before. */
    @Test
    void r1_otherParksStayParked() {
        FakePort port = new FakePort();
        Blackboard bb = new Blackboard(new Clock.Fake(0), port, new Tracer());
        bb.in.bag = Map.of();
        bb.in.pos = new int[]{0, 64, 0};
        Goals.Goal g = Goals.add(bb, new Thing.Result("furnace", "furnace", false, List.of("minecraft:furnace"), List.of("furnace"), 0, null, "furnace", null), 1, 0, 0);
        Goals.park(bb, g, "no way to get it: no cobblestone known");
        assertNull(g.loopPark);
        assertFalse(port.told.get(0).contains("tries once more"), port.told.toString());
        bb.in.bag = Map.of("cobblestone", 9);
        bb.in.pos = new int[]{100, 64, 0};
        assertTrue(Goals.retryLoopParks(bb).isEmpty());
        assertNotNull(g.parked);
    }
}
