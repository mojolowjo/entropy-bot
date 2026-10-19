package io.github.mojolowjo.entropybot.aa.core;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** AA v0 P1 (AA_PLAN 12): each node's semantics, with a fake clock and port. */
class NodesTest {
    final Clock.Fake clock = new Clock.Fake(1_000_000);
    final FakePort port = new FakePort();
    final Blackboard bb = new Blackboard(clock, port, new Tracer());

    /** A leaf whose answers the test scripts; counts halts. */
    static final class Scripted extends Node {
        final List<Status> script = new ArrayList<>();
        int at, halts, pauses, resumes;

        Scripted(String id, Status... s) {
            super(id, id);
            script.addAll(List.of(s));
        }

        @Override protected Status run(Blackboard bb) {
            Status s = script.get(Math.min(at, script.size() - 1));
            at++;
            return s == Status.FAILURE ? fail(id + " says no") : s;
        }

        @Override public void halt(String why) { halts++; }

        @Override public void pause(String why) { pauses++; }

        @Override public void resume() { resumes++; }
    }

    static Condition yes(String id) { return new Condition(id, id, b -> true); }

    static Condition no(String id) { return new Condition(id, id, b -> false); }

    @Test
    void conditionAndSeq() {
        Seq s = new Seq("s", "s", yes("a"), no("b"), yes("c"));
        assertEquals(Status.FAILURE, s.tick(bb));
        assertEquals("not b", s.reason());
        assertEquals(Status.SUCCESS, new Seq("s2", "s2", yes("a"), yes("c")).tick(bb));
        // a running child that an earlier condition then blocks gets its halt
        Scripted job = new Scripted("job", Status.RUNNING);
        boolean[] gate = {true};
        Seq g = new Seq("g", "g", new Condition("gate", "gate", b -> gate[0]), job);
        assertEquals(Status.RUNNING, g.tick(bb));
        gate[0] = false;
        assertEquals(Status.FAILURE, g.tick(bb));
        assertEquals(1, job.halts);
    }

    @Test
    void fallbackIsReactiveAndHaltsAnOutrankedChild() {
        Scripted low = new Scripted("low", Status.RUNNING);
        boolean[] high = {false};
        Fallback f = new Fallback("f", "f", new Condition("hi", "hi", b -> high[0]), low);
        assertEquals(Status.RUNNING, f.tick(bb));
        high[0] = true;
        assertEquals(Status.SUCCESS, f.tick(bb));
        assertEquals(1, low.halts, "the lower running child was halted");
        Fallback none = new Fallback("n", "n", no("a"), no("b"));
        assertEquals(Status.FAILURE, none.tick(bb));
        assertEquals("not a; not b", none.reason());
    }

    @Test
    void memoryFallbackLatchesTheRunningChildAndPreempts() {
        Scripted first = new Scripted("first", Status.FAILURE);
        Scripted task = new Scripted("task", Status.RUNNING, Status.RUNNING, Status.SUCCESS);
        Scripted idle = new Scripted("idle", Status.RUNNING);
        MemoryFallback m = new MemoryFallback("slot", "slot", first, task, idle);
        assertEquals(Status.RUNNING, m.tick(bb));
        assertEquals(1, m.running());
        first.script.set(0, Status.SUCCESS);          // a higher child becoming able does NOT interrupt
        assertEquals(Status.RUNNING, m.tick(bb));
        assertEquals(1, first.at, "the first child was not re-checked while the task ran");
        assertEquals(Status.SUCCESS, m.tick(bb));
        assertEquals(-1, m.running());
        assertEquals(0, idle.at, "idle never ticked");
        // preempt: the running child is halted and the slot is free
        Scripted t2 = new Scripted("t2", Status.RUNNING);
        MemoryFallback m2 = new MemoryFallback("s2", "s2", t2);
        m2.tick(bb);
        m2.preempt("preempted by owner: goto");
        assertEquals(1, t2.halts);
        assertEquals(-1, m2.running());
    }

    @Test
    void guardSelectorPausesAndResumesTheLowerChild() {
        boolean[] danger = {false};
        Scripted reflex = new Scripted("reflex", Status.RUNNING);
        Scripted slot = new Scripted("slot", Status.RUNNING);
        GuardSelector root = new GuardSelector("root", "root",
                new GuardSelector.Branch(new Condition("danger", "danger", b -> danger[0]), reflex),
                new GuardSelector.Branch(yes("always"), slot));
        assertEquals(Status.RUNNING, root.tick(bb));
        assertEquals(1, root.active());
        danger[0] = true;
        assertEquals(Status.RUNNING, root.tick(bb));
        assertEquals(0, root.active());
        assertEquals(1, slot.pauses);
        assertEquals(0, slot.halts, "paused, not halted");
        root.tick(bb);
        assertEquals(1, slot.pauses, "paused once");
        danger[0] = false;
        assertEquals(Status.RUNNING, root.tick(bb));
        assertEquals(1, slot.resumes);
        assertEquals(1, root.active());
        assertEquals(List.of("paused slot: danger", "resumed slot"), bb.tracer.events());
    }

    @Test
    void cooldownDoublesAndForgetsOnSuccess() {
        Scripted leaf = new Scripted("leaf", Status.FAILURE);
        Cooldown cd = new Cooldown("cd", leaf, 1000, 4000);
        assertEquals(Status.FAILURE, cd.tick(bb));
        assertEquals("leaf says no", cd.reason());
        assertEquals(Status.FAILURE, cd.tick(bb));
        assertTrue(cd.reason().startsWith("cooling down 1s after: leaf says no"), cd.reason());
        assertEquals(1, leaf.at, "not ticked while cooling");
        clock.advance(1000);
        assertEquals(Status.FAILURE, cd.tick(bb));     // second failure: 2 s
        clock.advance(1500);
        assertEquals(Status.FAILURE, cd.tick(bb));
        assertEquals(2, leaf.at);
        clock.advance(500);
        cd.tick(bb);                                   // third: 4 s (capped)
        clock.advance(4000);
        cd.tick(bb);                                   // fourth: still 4 s
        assertEquals(4, leaf.at);
        assertEquals(4, cd.fails());
        leaf.script.set(0, Status.SUCCESS);
        clock.advance(4000);
        assertEquals(Status.SUCCESS, cd.tick(bb));
        assertEquals(0, cd.fails());
        assertFalse(cd.cooling(clock.now()));
    }

    @Test
    void cooldownNeverCountsARunningInterruption() {
        Scripted leaf = new Scripted("leaf", Status.RUNNING);
        Cooldown cd = new Cooldown("cd", leaf, 1000, 4000);
        assertEquals(Status.RUNNING, cd.tick(bb));
        cd.halt("preempted by owner");
        assertEquals(1, leaf.halts);
        assertEquals(0, cd.fails());
        assertFalse(cd.cooling(clock.now()));
    }

    @Test
    void bandHysteresisAndCalmLatch() {
        Band torches = new Band(8, 16);
        assertFalse(torches.update(10));
        assertTrue(torches.update(7));
        assertTrue(torches.update(12), "still on until 16");
        assertFalse(torches.update(16));
        assertFalse(torches.update(9));
        assertThrows(IllegalArgumentException.class, () -> new Band(16, 8));
        Band.CalmLatch danger = new Band.CalmLatch(3000);
        long t = 0;
        assertFalse(danger.update(false, t));
        assertTrue(danger.update(true, t += 100));
        assertTrue(danger.update(false, t += 1000));
        assertTrue(danger.update(false, t += 1000));
        assertTrue(danger.update(true, t += 500), "danger again resets the calm clock");
        assertTrue(danger.update(false, t += 2900));
        assertFalse(danger.update(false, t += 200));
    }

    @Test
    void utilitySelectorWinnerBonusAndStrictGreater() {
        double[] scores = new double[TaskKind.values().length];
        scores[TaskKind.STOCK_WOOD.ordinal()] = 50;
        scores[TaskKind.STOCK_STONE.ordinal()] = 50;
        scores[TaskKind.WAIT.ordinal()] = -1;
        Scripted wood = new Scripted("wood", Status.SUCCESS), stone = new Scripted("stone", Status.SUCCESS), wait = new Scripted("wait", Status.SUCCESS);
        UtilitySelector u = new UtilitySelector("idle", "idle", in -> scores, 15,
                new UtilitySelector.Option(TaskKind.STOCK_WOOD, wood), new UtilitySelector.Option(TaskKind.STOCK_STONE, stone),
                new UtilitySelector.Option(TaskKind.WAIT, wait));
        assertEquals(Status.SUCCESS, u.tick(bb));
        assertEquals(0, u.lastWinner(), "a tie goes to the earlier option");
        scores[TaskKind.STOCK_STONE.ordinal()] = 60;
        u.tick(bb);
        assertEquals(0, u.lastWinner(), "50 + 15 bonus beats 60");
        scores[TaskKind.STOCK_STONE.ordinal()] = 70;
        u.tick(bb);
        assertEquals(1, u.lastWinner());
        assertEquals(0, wait.at, "a negative score never runs");
        scores[TaskKind.STOCK_WOOD.ordinal()] = -1;
        scores[TaskKind.STOCK_STONE.ordinal()] = -1;
        assertEquals(Status.FAILURE, u.tick(bb));
        assertEquals("no idle option can run", u.reason());
    }

    @Test
    void utilitySelectorCommitsToARunningOption() {
        double[] scores = new double[TaskKind.values().length];
        scores[TaskKind.STOCK_WOOD.ordinal()] = 10;
        scores[TaskKind.STOCK_STONE.ordinal()] = 90;
        Scripted wood = new Scripted("wood", Status.SUCCESS), stone = new Scripted("stone", Status.RUNNING, Status.RUNNING, Status.FAILURE);
        UtilitySelector u = new UtilitySelector("idle", "idle", in -> scores, 15,
                new UtilitySelector.Option(TaskKind.STOCK_WOOD, wood), new UtilitySelector.Option(TaskKind.STOCK_STONE, stone));
        assertEquals(Status.RUNNING, u.tick(bb));
        scores[TaskKind.STOCK_WOOD.ordinal()] = 100;
        assertEquals(Status.RUNNING, u.tick(bb), "no re-choice while an option runs");
        assertEquals(Status.FAILURE, u.tick(bb));
        assertEquals("stone says no", u.reason());
        assertEquals(0, wood.at);
    }

    @Test
    void inputsVectorIsFixedAndScaled() {
        Inputs in = new Inputs();
        float[] v = in.vector();
        assertEquals(Inputs.NAMES.length, v.length);
        for (float f : v) assertTrue(f >= 0 && f <= 1, "scaled: " + f);
        in.torches = 200;
        in.health = 10;
        v = in.vector();
        assertEquals(1f, v[9], "torches capped");
        assertEquals(0.5f, v[0]);
    }

    @Test
    void blackboardRequests() {
        bb.post(new Request("ai", TaskKind.AI, "cut 8", 1, 0, clock.now() + 1000, "the AI asked"));
        bb.post(new Request("owner", TaskKind.OWNER, "goto 1 2 3", 2, 0, 0, "pm"));
        assertEquals("cut 8", bb.peek("ai").chain());
        clock.advance(2000);
        assertNull(bb.peek("ai"), "expired");
        assertEquals("goto 1 2 3", bb.take("owner").chain());
        assertNull(bb.take("owner"));
        bb.post(new Request("ai", TaskKind.AI, "a", 1, 0, 0, ""));
        bb.post(new Request("ai", TaskKind.AI, "b", 1, 0, 0, ""));
        assertEquals(2, bb.drop("ai"));
        assertTrue(bb.requests().isEmpty());
    }
}
