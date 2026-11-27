package io.github.mojolowjo.entropybot.ghost;

import io.github.mojolowjo.entropybot.ghost.ShadowRun.Pos;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShadowRunTest {
    static final GhostInput WALK = new GhostInput(1f, 0f, false, true, false, -90f, 0f);

    static ShadowRun run3() {
        ShadowRun r = new ShadowRun(new Pos(0, 64, 0), 3);
        r.add(WALK, new Pos(0.1, 64, 0));
        r.add(new GhostInput(1f, 0f, true, true, false, -90f, 0f), new Pos(0.3, 64.42, 0));
        r.add(WALK, new Pos(0.6, 64.75, 0));
        return r;
    }

    @Test
    void anExactReplayPasses() {
        ShadowRun r = run3();
        assertTrue(r.full());
        ShadowRun.Report rep = r.compare(List.of(new Pos(0.1, 64, 0), new Pos(0.3, 64.42, 0), new Pos(0.6, 64.75, 0)), 0.01, 0.1);
        assertTrue(rep.pass());
        assertEquals(0.0, rep.endGap());
        assertEquals(-1, rep.firstDriftTick());
        assertTrue(rep.line().startsWith("PASS: 3 ticks, end gap 0.0000"), rep.line());
    }

    @Test
    void driftIsFoundAtItsTickAndTheEndGapDecides() {
        ShadowRun.Report rep = run3().compare(List.of(new Pos(0.1, 64, 0), new Pos(0.32, 64.42, 0), new Pos(0.6, 64.9, 0)), 0.01, 0.1);
        assertFalse(rep.pass(), "0.15 off at the end");
        assertEquals(2, rep.firstDriftTick());
        assertEquals(3, rep.worstTick());
        assertEquals(0.15, rep.endGap(), 1e-9);
        ShadowRun.Report near = run3().compare(List.of(new Pos(0.1, 64, 0), new Pos(0.3, 64.42, 0), new Pos(0.65, 64.75, 0)), 0.01, 0.1);
        assertTrue(near.pass(), "0.05 off is within the mark");
    }

    @Test
    void lengthsMustMatchAndTheRunStopsWhenFull() {
        ShadowRun r = run3();
        assertThrows(IllegalArgumentException.class, () -> r.compare(List.of(new Pos(0, 0, 0)), 0.01, 0.1));
        assertThrows(IllegalStateException.class, () -> r.add(WALK, new Pos(0, 0, 0)));
        assertThrows(IllegalArgumentException.class, () -> new ShadowRun(new Pos(0, 0, 0), 0));
        assertThrows(IllegalStateException.class, () -> new ShadowRun(new Pos(0, 0, 0), 5).compare(List.of(), 0.01, 0.1));
    }

    @Test
    void theSummaryCountsWhatThePlayerDid() {
        assertEquals("3 ticks from (0.000, 64.000, 0.000) to (0.600, 64.750, 0.000) (0.96 blocks), jump held 1, sprint 3, sneak 0", run3().summary());
    }
}
