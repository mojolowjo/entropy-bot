package io.github.mojolowjo.entropybot.watchview;

import io.github.mojolowjo.entropybot.engine.WatchCamera;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SeenRuleTest {
    @Test
    void lightAndSkyRule() {
        assertFalse(SeenRule.record(true, 15, 2), "air side under open sky: never");
        assertTrue(SeenRule.record(false, 0, 8), "dark but within 8 blocks");
        assertFalse(SeenRule.record(false, 0, 8.5), "dark and far: skipped");
        assertTrue(SeenRule.record(false, 1, 70), "lit: any distance");
        assertEquals(0.35f, SeenRule.dim(0), 1e-6);
        assertEquals(1.0f, SeenRule.dim(15), 1e-6);
        assertTrue(SeenRule.dim(7) > SeenRule.dim(3));
        assertEquals(1.0f, SeenRule.dim(99), 1e-6);
        int[] t = SeenRule.tint(200, 200, 200);
        assertTrue(t[0] < t[1] && t[1] == t[2] && t[2] <= 255, "cyan");
        assertEquals(255, SeenRule.tint(255, 255, 255)[2], 1);
    }

    @Test
    void twentyErrorsInAMinuteTurnItOff() {
        SeenRule.Errors e = new SeenRule.Errors();
        for (int i = 0; i < 19; i++) assertFalse(e.add(i * 1000L));
        assertTrue(e.add(19_000), "the 20th within a minute");
        SeenRule.Errors slow = new SeenRule.Errors();
        for (int i = 0; i < 40; i++) assertFalse(slow.add(i * 4000L), "spread out: never");
        SeenRule.Errors log = new SeenRule.Errors();
        int logged = 0;
        for (int i = 1; i <= 300; i++) {
            log.add(i * 10_000L);
            if (log.logIt()) logged++;
        }
        assertEquals(5 + 3, logged, "the first 5, then every 100th");
    }

    @Test
    void stalledSamplerWarning() {
        assertFalse(WatchChecks.seenStalled(false, true, 10_000, -1));
        assertFalse(WatchChecks.seenStalled(true, false, 10_000, -1), "not in a world");
        assertFalse(WatchChecks.seenStalled(true, true, 1500, -1), "just turned on");
        assertTrue(WatchChecks.seenStalled(true, true, 5000, -1));
        assertTrue(WatchChecks.seenStalled(true, true, 5000, 2500));
        assertFalse(WatchChecks.seenStalled(true, true, 5000, 100));
    }

    @Test
    void meshRebuildsForSeenFacesAtMostEveryTwoSeconds() {
        // nothing else changed: the seen store moved
        assertFalse(MeshRule.due(true, 1, 1, 5, 4, true, true, 1000, 0, 0), "1 s: wait");
        assertTrue(MeshRule.due(true, 1, 1, 5, 4, true, true, 2000, 0, 0));
        assertFalse(MeshRule.due(true, 1, 1, 4, 4, true, true, 5000, 0, 0), "nothing changed");
        assertTrue(MeshRule.due(true, 1, 1, 4, 4, false, true, 500, 0, 0), "the tint switched");
        assertFalse(MeshRule.due(true, 1, 1, 4, 4, false, true, 100, 0, 0), "but not within the 0.4 s gap");
        assertTrue(MeshRule.due(true, 2, 1, 4, 4, true, true, 500, 0, 0), "known air changed: as before");
        assertTrue(MeshRule.due(false, 1, 1, 4, 4, true, true, 0, 0, 0));
    }

    @Test
    void dollhouseWords() {
        assertEquals(Boolean.TRUE, WatchCamera.parseDollhouse("dollhouse", false), "a bare word toggles");
        assertEquals(Boolean.FALSE, WatchCamera.parseDollhouse("dollhouse", true));
        assertEquals(Boolean.TRUE, WatchCamera.parseDollhouse("dollhouse on", true));
        assertEquals(Boolean.FALSE, WatchCamera.parseDollhouse("dollhouse off", true));
        assertNull(WatchCamera.parseDollhouse("dollhouse maybe", true));
        assertNull(WatchCamera.parseDollhouse("height 4", true));
    }
}
