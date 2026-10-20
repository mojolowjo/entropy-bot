package io.github.mojolowjo.entropybot.sense;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** AA_ROUND3 3: the seen-ore rules (distance, cone, face), the store (filter, forgetting), the budget and the file. */
class SeenTest {
    static final boolean[] ALL = {true, true, true, true, true, true}, NONE = new boolean[6];

    @Test
    void distance() {
        assertTrue(Seen.inRange(0.5, 64.6, 0.5, 20, 64, 0));
        assertFalse(Seen.inRange(0.5, 64.6, 0.5, 30, 64, 0));
    }

    @Test
    void cone() {
        // looking +x: ahead yes, 45 degrees yes, sideways and behind no
        assertTrue(Seen.inCone(0.5, 64.5, 0.5, 1, 0, 0, 10, 64, 0));
        assertTrue(Seen.inCone(0.5, 64.5, 0.5, 1, 0, 0, 10, 64, 10));
        assertFalse(Seen.inCone(0.5, 64.5, 0.5, 1, 0, 0, 0, 64, 10));
        assertFalse(Seen.inCone(0.5, 64.5, 0.5, 1, 0, 0, -10, 64, 0));
    }

    @Test
    void face() {
        // only the open face away from the eyes: no ray (buried from where I stand)
        boolean[] back = new boolean[6];
        back[0] = true;                                          // +x face; the eyes are at -x
        assertEquals(-1, Seen.faceToward(0.5, 64.5, 0.5, 10, 64, 0, back));
        boolean[] front = new boolean[6];
        front[1] = true;                                         // -x face toward the eyes
        assertEquals(1, Seen.faceToward(0.5, 64.5, 0.5, 10, 64, 0, front));
        assertEquals(-1, Seen.faceToward(0.5, 64.5, 0.5, 10, 64, 0, NONE));
        Seen s = new Seen();
        assertTrue(s.candidate("d", 0.5, 64.5, 0.5, 1, 0, 0, 10, 64, 0, ALL));
        assertFalse(s.candidate("d", 0.5, 64.5, 0.5, 1, 0, 0, 10, 64, 0, NONE));
    }

    @Test
    void filterAndForgetting() {
        Seen s = new Seen();
        assertTrue(s.saw("d", 1, 2, 3, "iron", 100));
        assertFalse(s.saw("d", 1, 2, 3, "iron", 200));
        assertFalse(s.candidate("d", 0.5, 2.5, 0.5, 1, 0, 0, 1, 2, 3, ALL));       // seen already: no more rays
        List<OreIndex.Hit> hits = List.of(new OreIndex.Hit(1, 2, 3, "minecraft:iron_ore", "iron", 1, false),
                new OreIndex.Hit(9, 2, 3, "minecraft:iron_ore", "iron", 64, false));
        assertEquals(1, s.filter("d", hits).size());
        assertEquals(0, s.filter("other", hits).size());
        assertEquals(1, s.near("d", "iron", 0, 0, 0, 10).size());
        assertEquals(0, s.near("d", "coal", 0, 0, 0, 10).size());
        assertTrue(s.mined("d", 1, 2, 3));
        assertFalse(s.has("d", 1, 2, 3));
        assertEquals(0, s.filter("d", hits).size());
        assertTrue(s.saw("d", -5, -60, -7, "iron", 1));
        assertTrue(s.has("d", -5, -60, -7));
        assertFalse(s.has("d", -5, -59, -7));
    }

    @Test
    void theOldestGoPastTheCap() {
        Seen s = new Seen();
        for (int i = 0; i <= Seen.MAX; i++) s.saw("d", i, 0, 0, "coal", i);
        assertEquals(Seen.MAX, s.size("d"));
        assertFalse(s.has("d", 0, 0, 0));
        assertTrue(s.has("d", Seen.MAX, 0, 0));
    }

    @Test
    void budget() {
        Seen.Budget b = new Seen.Budget();
        int n = 0;
        while (b.take(1000)) n++;
        assertEquals(Seen.RAYS_PER_SEC, n);
        assertFalse(b.take(1000));
        assertTrue(b.take(1100));          // 6.4 rays refilled in 100 ms
    }

    @Test
    void caveYieldAndTheFile() {
        Seen s = new Seen();
        double prior = s.caveRate("iron");
        assertEquals(Seen.PRIOR_RATE, prior, 1e-9);
        s.caving = true;
        s.saw("d", 1, 1, 1, "iron", 1);
        s.saw("d", 2, 1, 1, "iron", 1);
        s.caving = false;
        s.caveTime(2);
        assertEquals((2 + Seen.PRIOR_RATE * Seen.PRIOR_MIN) / (2 + Seen.PRIOR_MIN), s.caveRate("iron"), 1e-9);
        // AA_ROUND3 5: the ore cells live in sightings.json (Sightings kind ORE)
        Sightings o = new Sightings();
        o.ore().saw("d", 4, 5, 6, "iron", 7);
        o.ore().caveTime(1);
        assertTrue(o.dirty());
        int[] bad = {0};
        Sightings back = Sightings.fromJson(o.toJson(), bad);
        assertEquals(0, bad[0]);
        assertTrue(back.ore().has("d", 4, 5, 6));
        assertFalse(back.dirty());
        // an old ore-stats.json with the part: read once as the legacy cells (ChunkPass carries them over), not written back
        OreStats old = OreStats.fromJson("{\"hist\":{},\"seen\":" + o.ore().toJson() + "}", bad);
        assertTrue(old.legacySeen().has("d", 4, 5, 6));
        assertFalse(old.toJson().contains("\"seen\""));
        assertNull(OreStats.fromJson("{\"hist\":{}}", bad).legacySeen());
    }
}
