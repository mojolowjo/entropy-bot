package io.github.mojolowjo.entropybot.nav.check;

import org.junit.jupiter.api.Test;

import static io.github.mojolowjo.entropybot.nav.check.CheckBook.Ghost.*;
import static io.github.mojolowjo.entropybot.nav.check.CheckBook.Real.*;
import static org.junit.jupiter.api.Assertions.*;

/** Nav step 9c: the route checker's books (which pairs agree, the counts, the cost). */
class CheckBookTest {
    static CheckBook.Row row(CheckBook.Ghost g, CheckBook.Real r) {
        return new CheckBook.Row(1, g, "why", r, "note");
    }

    @Test
    void whichPairsAgree() {
        assertEquals(Boolean.TRUE, row(PASS, ARRIVED).agree());
        assertEquals(Boolean.TRUE, row(PASS, PART_DONE).agree());
        assertEquals(Boolean.FALSE, row(PASS, FAILED).agree());
        assertEquals(Boolean.TRUE, row(REFUSE, FAILED).agree());
        assertEquals(Boolean.FALSE, row(REFUSE, ARRIVED).agree());
        assertEquals(Boolean.TRUE, row(FLOWN, ARRIVED).agree(), "flown to the hands and the real got there");
        assertNull(row(FLOWN, FAILED).agree(), "it may have failed past the flown stretch");
        assertNull(row(UNCHECKED, ARRIVED).agree());
        assertNull(row(PASS, UNKNOWN).agree());
    }

    @Test
    void theCountsAndTheLine() {
        CheckBook b = new CheckBook();
        b.newPart();
        b.close(row(PASS, ARRIVED));
        b.newPart();
        b.close(row(REFUSE, ARRIVED));
        b.newPart();
        b.close(row(UNCHECKED, FAILED));
        b.cost(0.5);
        b.cost(1.5);
        assertEquals("check: 3 parts, ghost pass 1, flown to the hands 0, refuse 1, unchecked 1; agree 1 of 2 compared (50%); ghost 1.000 ms a tick, worst 1.5", b.status());
        assertEquals("part 1: ghost refuse (why), real arrived (note): DISAGREE", b.last().get(1).line());
        assertEquals("part 1: ghost pass (why), real part done (note): agree", row(PASS, PART_DONE).line());
    }
}
