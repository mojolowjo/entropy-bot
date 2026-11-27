package io.github.mojolowjo.entropybot.ghost;

import io.github.mojolowjo.entropybot.ghost.GhostLedger.Outcome;
import io.github.mojolowjo.entropybot.ghost.GhostLedger.Row;
import io.github.mojolowjo.entropybot.ghost.PathCheck.Kind;
import io.github.mojolowjo.entropybot.ghost.PathCheck.Reason;
import io.github.mojolowjo.entropybot.ghost.PathCheck.Verdict;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GhostLedgerTest {
    static final Verdict PASS = new Verdict(Kind.PASS, Reason.NONE, 3, -50, 0, 20, "");
    static final Verdict REFUSE = new Verdict(Kind.REFUSE, Reason.NO_PROGRESS, 15, -50, 0, 41, "point 14 of 29, traverse");

    @Test
    void agreementCountsCheckedPathsWithAKnownOutcome() {
        assertTrue(new Row(1, PASS, Outcome.ARRIVED, "").agrees());
        assertTrue(new Row(2, REFUSE, Outcome.FAILED, "").agrees());
        assertFalse(new Row(3, PASS, Outcome.FAILED, "").agrees());
        assertFalse(new Row(4, REFUSE, Outcome.ARRIVED, "").agrees());
        assertNull(new Row(5, Verdict.unchecked(Reason.BUDGET, ""), Outcome.ARRIVED, "").agrees());
        assertNull(new Row(6, PASS, Outcome.UNKNOWN, "").agrees());
        assertEquals("path 3: ghost pass (20 ghost ticks); real failed (Baritone left it at 2 -50 0); DISAGREE",
                new Row(3, PASS, Outcome.FAILED, "Baritone left it at 2 -50 0").line());
    }

    @Test
    void theStatusCountsEverything() {
        GhostLedger l = new GhostLedger();
        l.newPath();
        l.verdict(PASS);
        l.close(new Row(1, PASS, Outcome.ARRIVED, ""));
        l.newPath();
        l.verdict(REFUSE);
        l.close(new Row(2, REFUSE, Outcome.ARRIVED, ""));
        l.newPath();
        l.verdict(Verdict.unchecked(Reason.DOOR, "at 16 -50 76"));
        l.gameTickCost(1_000_000);
        l.gameTickCost(0);
        assertEquals(1, l.disagreements());
        assertEquals("3 paths: 1 pass, 1 refused (no_progress 1), 1 unchecked (door 1); vs the real walk: 1 agree, 1 disagree; ghost cost per game tick: 1 ticks, avg 1.000 ms, worst 1.000 ms",
                l.status());
    }

    @Test
    void refusalLinesAreRateLimited() {
        GhostLedger l = new GhostLedger();
        int full = 0, counts = 0;
        for (int i = 0; i < 30; i++) {
            if (l.verdict(REFUSE)) full++;
            else if (l.countLineDue()) counts++;
        }
        assertEquals(5, full, "the first 5 in full");
        assertEquals(2, counts, "then one count line per 10 more (at 15 and 25)");
    }
}
