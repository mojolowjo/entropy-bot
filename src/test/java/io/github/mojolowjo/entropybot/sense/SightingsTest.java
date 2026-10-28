package io.github.mojolowjo.entropybot.sense;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** AA_ROUND3 5: the one sightings memory: rule, confirm-twice, decay per kind, visit outcomes, the cave chance, the file. */
class SightingsTest {
    @Test
    void treeRuleAndMemory() {
        assertTrue(Sightings.treeRule(true, 4, true, false));
        assertFalse(Sightings.treeRule(false, 4, true, false), "a pile on stone is no tree");
        assertFalse(Sightings.treeRule(true, 2, true, false), "a stump");
        assertFalse(Sightings.treeRule(true, 5, false, false), "no leaves: a build");
        assertFalse(Sightings.treeRule(true, 5, true, true), "placed by me");
        Sightings s = new Sightings();
        assertNotNull(s.sawTree("d", 50, 64, 0, 5, 1000));
        assertNull(s.sawTree("d", 53, 64, 2, 5, 2000), "within the merge distance");
        assertNotNull(s.sawTree("d", -40, 64, 10, 4, 3000));
        var near = s.treesNear("d", 0, 0, 60);
        assertEquals(2, near.size());
        assertEquals(-40, near.get(0).x(), "nearest first (40 before 50)");
        assertEquals(1, s.treesNear("d", 0, 0, 45).size());
        assertEquals(1, s.exhaustTree("d", 50, 0, 4000));
        assertEquals(1, s.treesNear("d", 0, 0, 60).size(), "a gone tree is offered no more");
        Sightings back = Sightings.fromJson(s.toJson(), new int[1]);
        assertEquals(1, back.treeCount("d"));
        assertEquals(1, back.treesNear("d", 0, 0, 60).size());
        assertTrue(s.text("d", 0).contains("1 trees"));
    }

    @Test
    void rangePerKind() {
        assertTrue(Sightings.inRange(Sightings.Kind.ANIMAL, 0, 0, 0, 30, 0, 0));
        assertFalse(Sightings.inRange(Sightings.Kind.CAVE, 0, 0, 0, 30, 0, 0));
        assertTrue(Sightings.inRange(Sightings.Kind.CAVE, 0, 0, 0, 23, 0, 0));
    }

    @Test
    void confirmTwice() {
        Sightings s = new Sightings();
        long k = Sightings.key(Sightings.Kind.CAVE, "dark", 10, 40, 10);
        assertFalse(s.confirm(k, 1000), "one glance is noise");
        assertFalse(s.confirm(k, 2500), "too soon");
        assertTrue(s.confirm(k, 3000), "2 s apart: confirmed");
        assertFalse(s.confirm(k, 3100), "starts over");
        // a stale first glance starts over
        assertFalse(s.confirm(k, 100_000));
        assertTrue(s.confirm(k, 102_000));
        // a block off lies in the same coarse cell
        assertEquals(Sightings.key(Sightings.Kind.CAVE, "dark", 8, 40, 9), Sightings.key(Sightings.Kind.CAVE, "dark", 11, 41, 11));
    }

    /** 0.39.z78 J6: the known caves (the dark-cell caves and the mouths are deleted): stored once, offered, walked, the chance learned. */
    @Test
    void knownCavesOnceOfferedWalkedAndChance() {
        Sightings s = new Sightings();
        assertEquals(Sightings.priorCaves(), s.cavesPer100(), 1e-9);
        CaveIndex.Known a = new CaveIndex.Known(0, 40, 0, CaveIndex.Size.SMALL, CaveIndex.Depth.STONE, List.of(Sightings.cell(1, 40, 0), Sightings.cell(2, 41, 0), Sightings.cell(3, 40, 1)));
        assertNotNull(s.knowCave("d", a, 1));
        assertNull(s.knowCave("d", new CaveIndex.Known(5, 40, 5, CaveIndex.Size.SMALL, CaveIndex.Depth.STONE, List.of(Sightings.cell(2, 41, 0))), 2), "a shared hit: the same cave");
        assertNotNull(s.knowCave("d", new CaveIndex.Known(40, 40, 0, CaveIndex.Size.SMALL, CaveIndex.Depth.STONE, List.of(Sightings.cell(41, 40, 0))), 3));
        assertEquals(2, s.allKnownCaves("d").size());
        assertEquals(0, s.nearestCave("d", 2, 40, 2, 64).x());
        assertNull(s.nearestCave("d", 200, 40, 0, 64), "past 64");
        assertTrue(s.walkedCave("d", 0, 40, 0, System.currentTimeMillis(), 0));
        assertEquals(40, s.nearestCave("d", 2, 40, 2, 64).x(), "walked with no ore: held cave.walkedHoldS");
        s.walked(30);
        s.walked(500);                  // a teleport is not a walk
        assertEquals((2 + Sightings.priorCaves() * Sightings.priorHundreds()) / (0.3 + Sightings.priorHundreds()), s.cavesPer100(), 1e-9);
    }

    @Test
    void animalsDecayAndVisits() {
        Sightings s = new Sightings();
        boolean[] fresh = new boolean[1];
        var r = s.sawHerd("d", "minecraft:cow", 100, 64, 100, 8, 0, fresh);
        assertTrue(fresh[0]);
        assertEquals(1, Sightings.confidence(r, 0), 1e-9);
        assertEquals(0.5, Sightings.confidence(r, Sightings.halfLifeMs()), 1e-9, "halves every 10 min");
        assertEquals(0.25, Sightings.confidence(r, 2 * Sightings.halfLifeMs()), 1e-9);
        // the herd drifted 6 blocks: the same record, the new place and count
        s.sawHerd("d", "minecraft:cow", 106, 64, 100, 6, 1000, fresh);
        assertFalse(fresh[0]);
        assertEquals(1, s.animals("d", "minecraft:cow").size());
        assertEquals(106, s.animals("d", "minecraft:cow").get(0).x());
        s.sawHerd("d", "minecraft:sheep", 106, 64, 100, 3, 1000, fresh);
        assertTrue(fresh[0], "another type is its own record");
        // a visit that finds none: confidence 0.1, kept (herds drift)
        var miss = s.visit("d", "minecraft:cow", 108, 98, false, 0, 5000);
        assertEquals(Sightings.missConf(), Sightings.confidence(miss, 5000), 1e-9);
        assertEquals(1, s.animals("d", "minecraft:cow").size());
        // a visit that finds some: reset to 1, the count renewed
        var hit = s.visit("d", "minecraft:cow", 106, 100, true, 4, 9000);
        assertEquals(1, Sightings.confidence(hit, 9000), 1e-9);
        assertEquals(4, hit.count());
        assertNull(s.visit("d", "minecraft:cow", 500, 500, false, 0, 9000), "no sighting there");
    }

    @Test
    void file() {
        Sightings s = new Sightings();
        s.ore().saw("d", 1, 2, 3, "iron", 4);
        s.knowCave("d", new CaveIndex.Known(10, 40, 10, CaveIndex.Size.MEDIUM, CaveIndex.Depth.DEEPSLATE, List.of(Sightings.cell(11, 40, 10))), 5);
        s.walkedCave("d", 10, 40, 10, 6, 2);
        s.sawHerd("d", "modded:yak", 50, 70, 50, 3, 7, null);
        s.walked(40);
        int[] bad = {0};
        Sightings b = Sightings.fromJson(s.toJson(), bad);
        assertEquals(0, bad[0]);
        assertTrue(b.ore().has("d", 1, 2, 3));
        assertEquals(6, b.allKnownCaves("d").get(0).walked());
        assertEquals(2, b.allKnownCaves("d").get(0).oreOnWalk());
        assertEquals(CaveIndex.Size.MEDIUM, b.allKnownCaves("d").get(0).size());
        assertEquals(CaveIndex.Depth.DEEPSLATE, b.allKnownCaves("d").get(0).depth());
        List<Sightings.Rec> yak = b.animals("d", "modded:yak");
        assertEquals(1, yak.size());
        assertEquals(3, yak.get(0).count());
        assertEquals(s.cavesPer100(), b.cavesPer100(), 1e-9);
        assertFalse(b.dirty());
        // broken text: an empty memory, counted
        assertEquals(0, Sightings.fromJson("{oops", bad).allKnownCaves("d").size());
        assertTrue(bad[0] > 0);
        // one bad entry is skipped, the rest load
        int[] bad2 = {0};
        Sightings c = Sightings.fromJson("{\"knownCaves\":{\"d\":[[1,2,3,\"SMALL\",\"STONE\",9,[7],0,0],[\"x\"]]}}", bad2);
        assertEquals(1, c.allKnownCaves("d").size());
        assertEquals(1, bad2[0]);
    }

    @Test
    void regrownTreeNearAnExhaustedRecordIsRecorded() {
        Sightings s = new Sightings();
        assertNotNull(s.sawTree("d", 50, 64, 0, 5, 1000));
        assertEquals(1, s.exhaustTree("d", 50, 0, 2000));
        assertNotNull(s.sawTree("d", 52, 64, 1, 5, 3000));
    }
}