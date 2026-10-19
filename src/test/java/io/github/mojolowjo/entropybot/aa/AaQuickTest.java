package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.core.Blackboard;
import io.github.mojolowjo.entropybot.aa.core.Clock;
import io.github.mojolowjo.entropybot.aa.core.FakePort;
import io.github.mojolowjo.entropybot.aa.core.Status;
import io.github.mojolowjo.entropybot.aa.core.TaskKind;
import io.github.mojolowjo.entropybot.aa.core.Tracer;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 0.36.a6 (aa371): the planner wait clock, the camp latch, the under-ground escape choice. */
class AaQuickTest {
    final Clock.Fake clock = new Clock.Fake(10_000_000);
    final FakePort port = new FakePort();
    final Blackboard bb = new Blackboard(clock, port, new Tracer());

    @Test
    void aStaleWaitClockNeverSkipsAFreshPlan() {
        Rung r = new Rung("t", TaskKind.TABLE_FURNACE, b -> true, b -> null, b -> "goal furnace");
        port.resolveTo = null;
        assertEquals(Status.RUNNING, r.tick(bb), "planning starts");
        clock.advance(Rung.PLAN_WAIT_MS + 60_000);              // another rung's job ran meanwhile
        assertEquals(Status.RUNNING, r.tick(bb), "a fresh ask waits again, not 'found none in 45s'");
        port.resolveTo = "goal_furnace";
        clock.advance(250);
        r.tick(bb);
        assertEquals("goal_furnace", port.started.get(port.started.size() - 1).chain(), "the planner's answer runs");
    }

    @Test
    void anotherGoalRestartsTheWait() {
        String[] g = {"goal table"};
        Rung r = new Rung("t", TaskKind.TABLE_FURNACE, b -> true, b -> null, b -> g[0]);
        port.resolveTo = null;
        for (int i = 0; i < 40; i++) { r.tick(bb); clock.advance(1000); }
        g[0] = "goal furnace";
        for (int i = 0; i < 10; i++) { assertEquals(Status.RUNNING, r.tick(bb)); clock.advance(1000); }
    }

    @Test
    void campLatch() {
        assertTrue(AaLadder.campLatch(false, true, 10), "seen at the camp");
        assertFalse(AaLadder.campLatch(false, true, 60), "seen far from the camp: not the camp's");
        assertTrue(AaLadder.campLatch(true, false, 200), "away from the camp it still counts");
        assertFalse(AaLadder.campLatch(true, false, -1), "no camp or base any more");
        assertFalse(AaLadder.campLatch(false, false, 5));
    }

    @Test
    void escapeChoice() {
        assertEquals(AaEscape.Way.OUT, AaEscape.choose(0, false, 0, true).way());
        assertEquals(AaEscape.Way.PILLAR, AaEscape.choose(6, true, 20, true).way());
        assertEquals(AaEscape.Way.CLIMB, AaEscape.choose(6, true, 3, true).way(), "too few blocks for the pillar");
        assertEquals(AaEscape.Way.CLIMB, AaEscape.choose(6, false, 64, true).way(), "no shaft: staircase");
        assertEquals(AaEscape.Way.NONE, AaEscape.choose(6, false, 64, false).way());
        assertTrue(AaEscape.choose(6, false, 64, true).why().contains("no open shaft"));
    }

    @Test
    void escapeResumeText() {
        String end = "stopped at step 5 (goto -53 67 -7): error: stuck under ground at -66 59 -19 - aa gets me out next";
        assertTrue(AaEscape.stuck(end));
        assertEquals(5, AaEscape.failedStep(end));
        assertEquals(-1, AaEscape.failedStep("done"));
        List<String> steps = List.of("a", "b", "c", "d", "goto -53 67 -7", "smelt iron");
        assertEquals("goto -53 67 -7 then smelt iron", AaEscape.resume(steps, 5));
        assertNull(AaEscape.resume(steps, 7));
        assertEquals(12, AaEscape.throwaway(Map.of("cobblestone", 10, "dirt", 2, "cobbled_deepslate", 5)));
    }

    /** aa372 gap: a 1x1 shaft open to the sky is "not under ground", but still a hole to get out of. */
    @Test
    void holeExit() {
        // a shaft 7 deep: walls all the way, the column open, step-out at the top
        assertEquals(7, AaEscape.holeExit(48, dy -> true, dy -> dy >= 7));
        // standing on open ground: out right here
        assertEquals(0, AaEscape.holeExit(48, dy -> true, dy -> true));
        // a roof 3 above the head before any way out: none
        assertEquals(-1, AaEscape.holeExit(48, dy -> dy < 4, dy -> dy >= 7));
        // walls higher than the scan looks: none
        assertEquals(-1, AaEscape.holeExit(10, dy -> true, dy -> dy >= 11));
    }

    @Test
    void holeStuckTrigger() {
        String noPath = "stopped at step 3 (goto -53 67 -7): error: no path to -53 67 -7 - stuck at -60 52 -9";
        assertArrayEquals(new int[]{-53, 67, -7}, AaEscape.destOf(noPath));
        assertNull(AaEscape.destOf("done"));
        assertTrue(AaEscape.walkFailed(noPath));
        assertFalse(AaEscape.walkFailed("done: arrived"));
        // the goal is above me and there is a hole with a way out above: fire
        assertTrue(AaEscape.holeStuck(noPath, 52, new int[]{-53, 67, -7}, false, 7));
        // in a cell I dug myself: fire even with the goal at my level
        assertTrue(AaEscape.holeStuck(noPath, 52, new int[]{-53, 52, -7}, true, 7));
        // the goal at my level, not my own hole: a plain no-path, no escape
        assertFalse(AaEscape.holeStuck(noPath, 52, new int[]{-53, 53, -7}, false, 7));
        // no hole (out here, or no way out above): nothing to pillar out of
        assertFalse(AaEscape.holeStuck(noPath, 52, new int[]{-53, 67, -7}, true, 0));
        assertFalse(AaEscape.holeStuck(noPath, 52, new int[]{-53, 67, -7}, true, -1));
        // a walk that arrived never triggers
        assertFalse(AaEscape.holeStuck("done", 52, new int[]{-53, 67, -7}, true, 7));
        // the hole's height feeds the same choice: pillar with enough blocks
        assertEquals(AaEscape.Way.PILLAR, AaEscape.choose(7, true, 9, true).way());
    }
}
