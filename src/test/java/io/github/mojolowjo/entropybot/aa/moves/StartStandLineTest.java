package io.github.mojolowjo.entropybot.aa.moves;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** job-start: the stand says its reason once per change, never per tick. */
class StartStandLineTest {
    @Test void oneLinePerReason() {
        StandWait w = new StandWait();
        List<String> said = new ArrayList<>();
        Step.AwaitPaths a = new Step.AwaitPaths(30);
        w.begin(0);
        for (int t = 0; t < 40; t++) w.status(a, t, 3, said::add);
        assertTrue(said.isEmpty(), "the start line said the first reason: " + said);
        for (int t = 40; t < 80; t++) w.status(a, t, 2, said::add);
        assertEquals(1, said.size(), said.toString());
        assertTrue(said.get(0).contains("2 walks still computing"), said.get(0));
        w.begin(100);
        w.status(a, 100, 2, said::add);
        assertEquals(1, said.size(), "a new stand records its first reason silently");
    }
}
