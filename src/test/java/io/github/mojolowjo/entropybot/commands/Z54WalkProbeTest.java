package io.github.mojolowjo.entropybot.commands;

import io.github.mojolowjo.entropybot.aa.brain.Tuning;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 0.39.z54 B: the walk probe's lines (run M 19:00:59: the walk ended short of a complete priced path; 38 "no progress"
 * answers). Hand-made values; the probe is off by default (no line, no cost).
 */
class Z54WalkProbeTest {

    @org.junit.jupiter.api.AfterEach
    void defaults() { Tuning.reset(); }

    @Test
    void offByDefaultAndAtZero() {
        // WalkProbe.on() is ProbeLines.on() (WalkProbe itself loads Baritone's API, not on the test classpath); every
        // WalkProbe entry (tick, event, cancelled, price, unreachable) returns at once when it is false
        assertEquals(0.0, Tuning.get("debug.walkProbe"));
        assertFalse(ProbeLines.on());
        Tuning.set("debug.walkProbe", 1);
        assertTrue(ProbeLines.on());
        Tuning.set("debug.walkProbe", 0);
        assertFalse(ProbeLines.on(), "set back to 0: off again");
    }

    /** A movement whose getters count their calls; the probe's read must leave its caches as they were. */
    static final class FakeMove implements ProbeLines.MoveFacts {
        List<Object> toBreakCached, toPlaceCached;
        int reads;
        public List<?> breakCached() { reads++; return toBreakCached; }
        public List<?> placeCached() { reads++; return toPlaceCached; }
        public int plannedBreaks() { return 2; }
        public boolean plannedPlace() { return true; }
    }

    @Test
    void theMoveReadLeavesBaritonesCachesAsTheyWere() {
        FakeMove m = new FakeMove();
        String t = ProbeLines.move(m);
        assertNull(m.toBreakCached, "not computed stays not computed (no toBreak call)");
        assertNull(m.toPlaceCached);
        assertTrue(t.startsWith("breaks ? places ? (not computed yet by Baritone; planned: breaks 2, place yes)"), t);
        List<Object> b = List.of("a"), p = List.of();
        m.toBreakCached = b;
        m.toPlaceCached = p;
        assertEquals("breaks 1 places 0 (planned: breaks 2, place yes)", ProbeLines.move(m));
        assertSame(b, m.toBreakCached);
        assertSame(p, m.toPlaceCached);
    }

    @Test
    void walkLineCarriesEveryFact() {
        String l = new ProbeLines.Walk(1200, new int[]{-34, 70, -55}, "to y 16 (priced path)", "GoalGetToBlock{x=-30,y=16,z=-50}", "Custom Goal",
                true, true, new int[]{-34, 70, -55}, new int[]{-31, 40, -52}, 12, 80, false, "kept", new int[]{2, 1}, "kept path offered",
                "MovementDownward", "breaks 1 places 0 (planned: breaks 1, place no)", "TRAVEL", false).line();
        assertTrue(l.startsWith("t=1200 feet -34 70 -55 | step to y 16 (priced path) | goal GoalGetToBlock"), l);
        assertTrue(l.contains("| in control Custom Goal | custom goal active true"), l);
        assertTrue(l.contains("| path -34 70 -55 -> -31 40 -52 at 12/80 | calc none"), l);
        assertTrue(l.contains("| origin kept (offered 2, taken 1; walk start: kept path offered)"), l);
        assertTrue(l.contains("| move MovementDownward breaks 1 places 0 (planned: breaks 1, place no) | intent TRAVEL | reflex hold false"), l);
        String idle = new ProbeLines.Walk(5, null, "s", "null", "none", false, false, null, null, -1, 0, true, "-", new int[]{0, 0}, "-",
                "-", "", "TRAVEL", true).line();
        assertTrue(idle.contains("| path none executing | calc in progress"), idle);
        assertTrue(idle.contains("| move - | intent"), idle);
    }

    @Test
    void priceAndUnreachableLines() {
        ProbeLines.Segment s = new ProbeLines.Segment(new int[]{0, 70, 0}, new int[]{3, 40, 2}, 381.4, Map.of("Downward", 30, "Traverse", 4), 31, 0, 2);
        String p = ProbeLines.price(new int[]{0, 70, 0}, new int[]{5, 16, 5}, "381 ticks", List.of(s));
        assertEquals("from 0 70 0 to 5 16 5 | 381 ticks | 1 segment(s) | 1: 0 70 0 -> 3 40 2 381 ticks [Downward 30, Traverse 4] planned breaks 31 places 0 falls 2", p);
        assertTrue(ProbeLines.price(null, new int[]{1, 2, 3}, "noPath(unreachable)", List.of()).contains("none kept"));
        String u = ProbeLines.unreachable(new int[]{0, 70, 0}, new int[]{5, 16, 5}, "no progress", null, false, 3, 0);
        assertTrue(u.contains("no failure detail kept by the fork"), u);
        assertTrue(u.contains("goal chunk NOT loaded"), u);
        assertTrue(u.endsWith("break 3, place 0"), u);
        assertTrue(ProbeLines.cancel(7, new int[]{1, 2, 3}, "Jobs.cancel", "StepHands.toStripStatus:856", "s").contains("CANCEL by Jobs.cancel (StepHands.toStripStatus:856)"));
    }
}
