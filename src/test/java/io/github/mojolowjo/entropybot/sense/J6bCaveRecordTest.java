package io.github.mojolowjo.entropybot.sense;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 0.39.z78 J6b (D12, to-do/j6b-caving-step.md): the cave record in the one store - the frontier and its branches, the
 * ores seen left and mined, done, the measured yield, a reload, and that only the store writes it.
 */
class J6bCaveRecordTest {
    static final String D = "minecraft:overworld";

    /**
     * A known cave of 200 cells at y 40: a trunk x 0..119 (z 0) from the entry 0 40 0, branch A at x 40 going z 1..40 and
     * branch B at x 100 going z -1..-40 (one-cell tubes, so the counts read plainly).
     */
    static List<Long> cave() {
        List<Long> c = new ArrayList<>();
        for (int x = 0; x <= 119; x++) c.add(Sightings.cell(x, 40, 0));
        for (int z = 1; z <= 40; z++) c.add(Sightings.cell(40, 40, z));
        for (int z = -1; z >= -40; z--) c.add(Sightings.cell(100, 40, z));
        return c;
    }

    static Sightings store() {
        Sightings s = new Sightings();
        assertNotNull(s.knowCave(D, new CaveIndex.Known(0, 40, 0, CaveIndex.Size.SMALL, CaveIndex.Depth.STONE, List.of(Sightings.cell(1, 40, 0))), 1));
        s.caveCells(D, 0, 40, 0, cave());
        return s;
    }

    @Test
    void atTheEntry_theFrontierIsTheEdgeOfWhatIsSeen_afterAWalkSeeing120_twoBranchesWithTheirCounts_explored60() {
        Sightings s = store();
        assertEquals(200, s.caveFacts(D, 0, 40, 0).cellCount());
        assertEquals(1 + CaveRecord.SEE_STEPS, s.caveStood(D, 0, 40, 0, 0, 40, 0, 10), "the entry and the cells SEE_STEPS round it");
        List<CaveRecord.Group> f = s.caveFrontier(D, 0, 40, 0);
        assertEquals(1, f.size());
        assertEquals(Sightings.cell(CaveRecord.SEE_STEPS, 40, 0), f.get(0).first(), "the seen cell next to the unseen");
        assertEquals(CaveRecord.BRANCH_MIN + 1, f.get(0).behind(), "the cave behind it, counted up to BRANCH_MIN + 1 (run EK: the cap)");
        List<Long> trunk = new ArrayList<>();
        for (int x = 0; x <= 119; x++) trunk.add(Sightings.cell(x, 40, 0));
        s.caveSaw(D, 0, 40, 0, trunk);                                  // the walk down the trunk: 120 seen
        f = s.caveFrontier(D, 0, 40, 0);
        assertEquals(2, f.size(), "two branches not walked");
        assertEquals(List.of(CaveRecord.BRANCH_MIN + 1, CaveRecord.BRANCH_MIN + 1), List.of(f.get(0).behind(), f.get(1).behind()), "40 each, capped");
        assertTrue(f.stream().anyMatch(g -> g.first() == Sightings.cell(40, 40, 0)) && f.stream().anyMatch(g -> g.first() == Sightings.cell(100, 40, 0)));
        var k = s.caveFacts(D, 0, 40, 0);
        assertEquals(0.6, k.explored(), 1e-9);
        assertEquals(80, k.unseen());
        assertFalse(k.done(), "40 cells behind each branch: more than BRANCH_MIN");
    }

    @Test
    void oreSeenIsLeft_minedIsMined_theYieldIsMeasuredAfterTheFirstWalk() {
        Sightings s = store();
        long a = Sightings.cell(50, 41, 0), b = Sightings.cell(60, 39, 0);
        assertTrue(s.caveOre(D, 0, 40, 0, a, "iron"));
        assertTrue(s.caveOre(D, 0, 40, 0, b, "iron"));
        assertFalse(s.caveOre(D, 0, 40, 0, a, "iron"), "seen once");
        assertArrayEquals(new int[]{2, 0}, s.caveFacts(D, 0, 40, 0).ores().get("iron"));
        s.caveMined(D, 0, 40, 0, a);
        assertArrayEquals(new int[]{1, 1}, s.caveFacts(D, 0, 40, 0).ores().get("iron"), "one left, one mined");
        assertEquals(1, s.caveLeft(D, 0, 40, 0, "iron").size());
        assertTrue(Double.isNaN(s.caveFacts(D, 0, 40, 0).oreSeenPerMinute()), "never walked: the prior prices it");
        s.caveWalked(D, 0, 40, 0, 4, 2, 20);
        assertEquals(0.5, s.caveFacts(D, 0, 40, 0).oreSeenPerMinute(), 1e-9, "2 ore seen in 4 minutes");
    }

    @Test
    void aCaveWalkedToDone_notOfferedForAFamilyItHasNone_offeredForOneSeenAndLeft() {
        Sightings s = store();
        s.caveSaw(D, 0, 40, 0, cave());
        var k = s.caveFacts(D, 0, 40, 0);
        assertTrue(k.done(), "every cell seen: no branch left");
        assertEquals(0, k.groups());
        assertFalse(k.offeredFor("gold"), "done, no gold seen: no way for gold");
        s.caveOre(D, 0, 40, 0, Sightings.cell(10, 41, 0), "iron");
        assertTrue(s.caveFacts(D, 0, 40, 0).offeredFor("iron"), "done, iron seen and left: offered for iron");
        s.caveMined(D, 0, 40, 0, Sightings.cell(10, 41, 0));
        assertFalse(s.caveFacts(D, 0, 40, 0).offeredFor("iron"), "mined: none left");
        // a nook is no branch: one unseen cell behind a frontier leaves it done
        Sightings n = store();
        List<Long> most = cave();
        most.remove(Sightings.cell(100, 40, -40));
        n.caveSaw(D, 0, 40, 0, most);
        assertTrue(n.caveFacts(D, 0, 40, 0).done(), "1 cell behind: under BRANCH_MIN " + CaveRecord.BRANCH_MIN);
    }

    @Test
    void reload_theRecordPersists_theNextStepStartsAtTheFrontier() {
        Sightings s = store();
        List<Long> trunk = new ArrayList<>();
        for (int x = 0; x <= 119; x++) trunk.add(Sightings.cell(x, 40, 0));
        s.caveSaw(D, 0, 40, 0, trunk);
        s.caveOre(D, 0, 40, 0, Sightings.cell(40, 41, 20), "iron");
        s.caveWalked(D, 0, 40, 0, 3, 1, 30);
        int[] bad = {0};
        Sightings back = Sightings.fromJson(s.toJson(), bad);
        assertEquals(0, bad[0]);
        var k = back.caveFacts(D, 0, 40, 0);
        assertFalse(k.loaded(), "the cells come back with the chunks");
        assertEquals(200, k.cellCount());
        assertEquals(80, k.unseen());
        assertEquals(2, k.groups(), "the last measure");
        assertEquals(1, k.left("iron"));
        assertEquals(1 / 3.0, k.oreSeenPerMinute(), 1e-9);
        back.caveCells(D, 0, 40, 0, cave());                            // the chunks load again
        List<CaveRecord.Group> f = back.caveFrontier(D, 0, 40, 0);
        assertEquals(2, f.size(), "the next step starts at the frontier");
        assertTrue(back.caveFacts(D, 0, 40, 0).line().startsWith("0 40 0: explored 60% (120 of 200 cells), 2 branches, iron 1 left 0 mined, walked 3.0 min"),
                back.caveFacts(D, 0, 40, 0).line());
        assertNull(back.caveFacts(D, 5, 5, 5), "no known cave there: no record");
    }

    /** One store (rule 23): only Sightings writes a cave record, and nothing else makes one. */
    @Test
    void onlyTheStoreWritesTheRecord() throws java.io.IOException {
        Pattern writer = Pattern.compile("\\.rec(Cells|Feed|Stood|See|Ore|Mined|Walked)\\(|new CaveRecord\\(");
        List<String> bad = new ArrayList<>();
        try (var files = java.nio.file.Files.walk(java.nio.file.Path.of("src/main/java"))) {
            for (var f : (Iterable<java.nio.file.Path>) files.filter(p -> p.toString().endsWith(".java"))::iterator) {
                if (f.endsWith("Sightings.java") || f.endsWith("CaveRecord.java")) continue;
                if (writer.matcher(java.nio.file.Files.readString(f)).find()) bad.add(f.toString());
            }
        }
        assertTrue(bad.isEmpty(), "a cave record written outside the store: " + bad);
    }
}
