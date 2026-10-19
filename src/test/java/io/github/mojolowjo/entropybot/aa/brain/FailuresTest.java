package io.github.mojolowjo.entropybot.aa.brain;

import io.github.mojolowjo.entropybot.aa.Options;
import io.github.mojolowjo.entropybot.aa.core.Inputs;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** AA_ROUND2 3: one failure record (retry clocks, sweep by the bag total and by 16 moved, rest, describe, the views). */
class FailuresTest {
    static Inputs in(int[] pos, Map<String, Integer> bag) {
        Inputs in = new Inputs();
        in.pos = pos;
        in.bag = bag;
        return in;
    }

    @Test
    void optionClockIsTenMinutesPerFailureAtMostAnHour() {
        Failures f = new Failures();
        f.failed("needs.iron", "dig 1 2 3", "no path to 1 2 3", 0, null);
        assertTrue(f.blocked("needs.iron", "dig 1 2 3", Failures.RETRY_MS - 1));
        assertFalse(f.blocked("needs.iron", "dig 1 2 3", Failures.RETRY_MS));
        for (int i = 0; i < 10; i++) f.failed("needs.iron", "dig 1 2 3", "no path", 0, null);
        assertEquals(11, f.fails("needs.iron", "dig 1 2 3"));
        assertEquals(Failures.RETRY_MAX_MS, f.until("needs.iron", "dig 1 2 3"));
    }

    @Test
    void desireClockDoublesFromTwoMinutesAndSuccessClearsOnlyTheDesire() {
        Failures f = new Failures();
        f.failed("idle.wood", null, "stuck", 0, null);
        assertEquals(Failures.CD_BASE_MS, f.until("idle.wood", null));
        f.failed("idle.wood", null, "stuck", 0, null);
        assertEquals(2 * Failures.CD_BASE_MS, f.until("idle.wood", null));
        f.failed("idle.wood", "x", "no path", 0, null);
        f.succeeded("idle.wood");
        assertEquals(0, f.fails("idle.wood", null));
        assertEquals(1, f.fails("idle.wood", "x"), "an option keeps weighing");
        f.forget("idle.wood");
        assertEquals(0, f.size());
    }

    @Test
    void sweepLiftsByTheBagTotalAndByMoving16() {
        Failures f = new Failures();
        f.failed("r", "craft", "missing 2 stick", 0, in(new int[]{0, 64, 0}, Map.of("modded:osmium", 3)));
        f.failed("r", "walk", "no path to 5 5 5", 0, in(new int[]{0, 64, 0}, Map.of()));
        assertEquals(List.of(), f.sweep(in(new int[]{5, 64, 0}, Map.of("modded:osmium", 3))));
        assertEquals(List.of("r/craft NO_ITEM"), f.sweep(in(new int[]{5, 64, 0}, Map.of("modded:osmium", 4))), "any item counts");
        assertEquals(List.of("r/walk NO_PATH"), f.sweep(in(new int[]{16, 64, 0}, Map.of("modded:osmium", 4))));
        assertFalse(f.blocked("r", "walk", 1));
        assertEquals(1, f.fails("r", "walk"), "the count stays");
    }

    @Test
    void restHoldsWithoutAFailureAndNeverShortensAHold() {
        Failures f = new Failures();
        f.rest("idle.wood", 30_000, 0, "done just now");
        assertEquals("done just now (30s)", f.held("idle.wood", null, 0));
        assertEquals(0, f.fails("idle.wood", null));
        f.failed("idle.wood", null, "stuck", 0, null);
        f.rest("idle.wood", 30_000, 0, "rest");
        assertEquals(Failures.CD_BASE_MS, f.until("idle.wood", null));
        assertNull(f.held("idle.wood", null, Failures.CD_BASE_MS));
    }

    @Test
    void describeAndTheOptionMemoryView() {
        Failures f = new Failures();
        Options.OptionMemory m = new Options.OptionMemory();
        m.failed("hunt", "no animal in reach", 0);
        m.bind(f, "needs.food");
        assertEquals(1, m.fails("hunt"), "what was remembered moves along");
        m.failed("hunt", "no animal in reach", 0);
        assertEquals(2, f.fails("needs.food", "hunt"));
        assertTrue(m.blocked("hunt", 1));
        assertEquals(List.of("held: needs.food/hunt no animal in reach (20 min)"), f.describe(0));
        assertEquals(1, f.heldCount(0));
    }
}
