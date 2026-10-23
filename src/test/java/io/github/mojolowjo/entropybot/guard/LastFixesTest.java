package io.github.mojolowjo.entropybot.guard;

import io.github.mojolowjo.entropybot.aa.Replan;
import io.github.mojolowjo.entropybot.aa.WayUp;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** 0.39.z26 (last): the table build retry, the way-up refusal reason, the tags-without-effect line. */
class LastFixesTest {
    @Test
    void aBuildThatThrowsOnceIsRetriedAndEndsLoaded() {
        TableRetry<String> r = new TableRetry<>();
        AtomicInteger calls = new AtomicInteger();
        assertFalse(r.attempt(5, null, () -> { calls.incrementAndGet(); throw new IllegalStateException("boom"); }));
        assertNull(r.value);
        assertFalse(r.current(5, null), "a failed build is asked again");
        assertTrue(r.attempt(5, null, () -> { calls.incrementAndGet(); return "table"; }));
        assertEquals("table", r.value);
        assertTrue(r.current(5, null));
        assertEquals(2, calls.get());
    }
    @Test
    void aWayRefusedByAStructureBlockSaysSo() {
        Replan.Cell refused = new Replan.Cell(false, false, Double.POSITIVE_INFINITY, false, false, false, BreakPolicy.STRUCTURE_OUTSIDE);
        var h = new WayUp.Heading("east", 6, List.of(refused), null);
        var p = WayUp.stairs(h, 6, null);
        assertTrue(p.why().contains("structure block outside my areas"), p.why());
    }
}
