package io.github.mojolowjo.entropybot.aa.brain;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Rule 7: a job that ends within a second without changing anything is a failure with its stage, never "done". */
class FastEndTest {
    @Test
    void doneInZeroSecondsChangingNothingIsAFailureWithItsStage() {
        String v = FastEnd.verdict("done: bootstrap", 0, Map.of("log", 1), Map.of("log", 1), 0, "step 1/1 bootstrap started");
        assertNotNull(v);
        assertTrue(v.startsWith("failed: "), v);
        assertTrue(v.contains("step 1/1 bootstrap started") && v.contains("changing nothing"), v);
    }

    @Test
    void aJobThatChangedTheBagOrMovedOrRanLongStands() {
        assertNull(FastEnd.verdict("done", 200, Map.of(), Map.of("log", 1), 0, "cut"));
        assertNull(FastEnd.verdict("done", 200, Map.of(), Map.of(), Failures.movedV() + 1, "goto"));
        assertNull(FastEnd.verdict("done", FastEnd.limitMs() + 1, Map.of(), Map.of(), 0, "wait"));
    }

    @Test
    void aFailureOrNoEndIsLeftAlone() {
        assertNull(FastEnd.verdict("failed: no path", 0, Map.of(), Map.of(), 0, "goto"));
        assertNull(FastEnd.verdict(null, 0, Map.of(), Map.of(), 0, null));
    }
}
