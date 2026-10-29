package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.brain.Desire;
import io.github.mojolowjo.entropybot.aa.brain.Only;
import io.github.mojolowjo.entropybot.aa.brain.Tuning;
import io.github.mojolowjo.entropybot.aa.core.Inputs;
import io.github.mojolowjo.entropybot.aa.core.TaskKind;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Owner 2026-10-29 ("a spot for it to report back to that can be set ... when it's done with whatever job we gave it. If we
 * didn't give it a spot that's fine. It can just stand still where it's at in idle like it already does"): the idle
 * report-back option - scored above the wait while a base place is set and the bot stands away from it, -1 otherwise (the
 * wait stands, as before); never silenced by orders-only mode (the owner's fallback).
 */
class ReportBackTest {
    @AfterEach
    void clear() {
        Tuning.reset();
        Only.set(null);
    }

    static Inputs at(int x, int y, int z, int[] base) {
        Inputs in = new Inputs();
        in.pos = new int[]{x, y, z};
        in.basePos = base;
        return in;
    }

    @Test
    void awayFromTheBase_scoresAboveTheWait_atItOrWithoutOne_minusOne() {
        AaScorer s = new AaScorer();
        int[] base = {100, 64, 100};
        double[] away = s.score(at(0, 64, 0, base));
        assertEquals(AaScorer.reportBackScore(), away[TaskKind.REPORT_BACK.ordinal()], 1e-9);
        assertTrue(away[TaskKind.REPORT_BACK.ordinal()] > away[TaskKind.WAIT.ordinal()], "above the wait: the bot walks back instead of standing");
        assertEquals(-1, s.score(at(101, 64, 100, base))[TaskKind.REPORT_BACK.ordinal()], 1e-9, "at the base (within reportBackNearM): nothing to do");
        assertEquals(-1, s.score(at(0, 64, 0, null))[TaskKind.REPORT_BACK.ordinal()], 1e-9, "no base place set: the option is out, the wait stands");
        Inputs noFeet = at(0, 64, 0, base);
        noFeet.pos = null;
        assertEquals(-1, s.score(noFeet)[TaskKind.REPORT_BACK.ordinal()], 1e-9, "rule 15: feet not known: not away");
        assertTrue(AaScorer.awayFromBase(at(0, 64, 0, base)));
        assertFalse(AaScorer.awayFromBase(at(100, 66, 100, base)), "two blocks up: within the radius");
    }

    @Test
    void ordersOnlyMode_keepsTheReportBackOption() {
        Only.set(Only.ORDERS);
        assertTrue(Only.orders());
        assertEquals(1, Only.factor(AaDesires.REPORT_BACK_ID, Desire.Kind.UPKEEP), 1e-9, "the owner's fallback runs in orders-only mode too");
        assertEquals(1, Only.factor(AaDesires.IDLE_ID, Desire.Kind.UPKEEP), 1e-9, "the idle desire carries its best option's score");
        assertEquals(0, Only.factor(AaDesires.WAIT_ID, Desire.Kind.UPKEEP), 1e-9, "the wait stays silent in orders-only mode: no base = the idle desire scores 0 = the bot stands as before");
        assertEquals(0, Only.factor("idle.stock_wood", Desire.Kind.UPKEEP), 1e-9, "the stock-ups stay silent");
        Only.set(null);
        assertEquals(1, Only.factor(AaDesires.WAIT_ID, Desire.Kind.UPKEEP), 1e-9, "the full set: the wait as before");
        assertEquals("idle.report_back", AaDesires.REPORT_BACK_ID);
    }

    /** The tree offers the option inside the idle desire (one idle desire in the main list, as before). */
    @Test
    void theIdleSetHasTheOption() {
        AaTree tree = AaTree.build(new AaScorer());
        assertEquals(1, tree.brain.desires().stream().filter(d -> d.id.startsWith("idle")).count(), "one idle desire in the main list");
        assertEquals(Desire.Exposure.ANY, AaDesires.exposureOf(TaskKind.REPORT_BACK), "a walk on the surface or under: no exposure rule");
    }
}
