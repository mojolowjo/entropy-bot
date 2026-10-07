package io.github.mojolowjo.entropybot.move;

import io.github.mojolowjo.entropybot.restore.BuildSpotter;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 0.23.4 movement fixes from the obstacle matrix (runs 10, 11, 8). */
class MovementFixesTest {
    // ---- run 10: sealed at the start ----

    @Test
    void boxedInDigsOutOnTheFirstFailure() {
        NoDeadStop m = new NoDeadStop();
        assertEquals(NoDeadStop.Action.DIG_OUT, m.onFail(true, true));
        assertEquals(NoDeadStop.Action.DETOUR, m.onFail(true, true), "one dig-out per run of failures");
        assertEquals(NoDeadStop.Action.REPORT, m.onFail(true, true));
    }

    @Test
    void boxedInButNoDigOutAllowedKeepsTheOldOrder() {
        NoDeadStop m = new NoDeadStop();
        assertEquals(NoDeadStop.Action.NEXT_WAYPOINT, m.onFail(false, true));
        assertEquals(NoDeadStop.Action.DETOUR, m.onFail(false, true));
        assertEquals(NoDeadStop.Action.REPORT, m.onFail(false, true));
    }

    @Test
    void freeStepKeepsTheThreeStepRule() {
        NoDeadStop m = new NoDeadStop();
        assertEquals(NoDeadStop.Action.NEXT_WAYPOINT, m.onFail(true, false));
        assertEquals(NoDeadStop.Action.DETOUR, m.onFail(true, false));
        assertEquals(NoDeadStop.Action.DIG_OUT, m.onFail(true, false));
        assertEquals(NoDeadStop.Action.REPORT, m.onFail(true, false));
    }

    @Test
    void aDigOutAtTheStartCountsAsTheRunsOne() {
        NoDeadStop m = new NoDeadStop();
        m.markDugOut();
        assertEquals(NoDeadStop.Action.NEXT_WAYPOINT, m.onFail(true, true));
        m.onLegReached();
        assertEquals(NoDeadStop.Action.DIG_OUT, m.onFail(true, true), "a reached leg starts a new run");
    }

    @Test
    void digOutStartIsInPathStatus() {
        MoveStats s = new MoveStats();
        s.digOutStart(12);
        s.noMove++;
        assertTrue(s.firstMoveText().contains("dig-out began 12 ticks after the command (1 walks)"), s.firstMoveText());
        assertTrue(s.firstMoveText().contains("walks that never moved 1"));
    }

    // ---- run 11: resume vs re-plan after a fight ----

    @Test
    void pauseNotesSayWhatHappened() {
        assertEquals("resumed leg 2/4 after fight (moved 1 block)", PauseNote.resumed(2, 4, PauseNote.cause("FIGHTING"), 1));
        assertEquals("re-planned route after fight (moved 8 blocks)", PauseNote.replanned(PauseNote.cause("fleeing"), 8));
        assertEquals("meal", PauseNote.cause("EATING"));
        assertEquals("a cancel", PauseNote.cause("after a cancel"));
        assertEquals("pause", PauseNote.cause(null));
        assertTrue(LegJudge.resume(new int[]{0, 64, 0}, new int[]{3, 64, 0}), "the 3-block rule stays");
        assertFalse(LegJudge.resume(new int[]{0, 64, 0}, new int[]{4, 64, 0}));
    }

    // ---- run 8: a build beside the walk ----

    static List<int[]> hut(int x0, int y0, int z0) {
        List<int[]> b = new ArrayList<>();
        for (int x = 0; x < 9; x++) for (int z = 0; z < 9; z++) for (int y = 0; y < 3; y++)
            if (x == 0 || z == 0 || x == 8 || z == 8) b.add(new int[]{x0 + x, y0 + y, z0 + z});
        return b;
    }

    @Test
    void aPlankHutGivesAWalkHint() {
        BuildSpotter.Hint h = BuildSpotter.spot(hut(66, -60, 58));
        assertNotNull(h);
        assertEquals("looks like a build at 70 -59 62 - area here 8 build_70_62 safe?", h.walkWhisper());
        assertTrue(BuildSpotter.walkHintAllowed(null, false, false), "outside every area");
        assertTrue(BuildSpotter.walkHintAllowed("neutral", false, false));
        assertTrue(BuildSpotter.walkHintAllowed("destroy", false, false));
    }

    @Test
    void walkHintNeverInSafeOrMainOrNearTheOwnerOrTwice() {
        assertFalse(BuildSpotter.walkHintAllowed("safe", false, false));
        assertFalse(BuildSpotter.walkHintAllowed("main", false, false));
        assertFalse(BuildSpotter.walkHintAllowed(null, true, false), "near-me zone");
        assertFalse(BuildSpotter.walkHintAllowed(null, false, true), "once per spot");
    }

    @Test
    void walkHintIsRateLimited() {
        assertTrue(BuildSpotter.walkHintDue(0, 1_000));
        assertFalse(BuildSpotter.walkHintDue(1_000, 30_000));
        assertTrue(BuildSpotter.walkHintDue(1_000, 61_000));
    }

    @Test
    void scatteredBlocksAreNoBuild() {
        List<int[]> few = List.of(new int[]{0, 64, 0}, new int[]{20, 64, 0}, new int[]{40, 64, 0});
        assertNull(BuildSpotter.spot(few));
    }
}
