package io.github.mojolowjo.entropybot.sense;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 0.39.z78 J6b (D12, to-do/j6b-caving-step.md), D15 pass two: the cave record in the one store - its passages (THE cave
 * model; the frontier groups are deleted), the ores seen left and mined, done, a reload, an old save, the price hold, and
 * that only the store writes it.
 */
class J6bCaveRecordTest {
    static final String D = "minecraft:overworld";

    @AfterEach
    void clear() { io.github.mojolowjo.entropybot.aa.brain.Tuning.reset(); }

    /** The cells a stand at the entry sees of the 2-high trunk: SEE_STEPS along the floor row, one less along the head row. */
    static final int SEEN_AT_ENTRY = 2 * CaveRecord.SEE_STEPS + 1;

    /**
     * A known cave of 400 cells, 2 high (feet y 40, head y 41, the floor y 39 rock): a trunk x 0..119 (z 0) from the entry
     * 0 40 0, branch A at x 40 going z 1..40 and branch B at x 100 going z -1..-40 (one-wide tunnels).
     */
    static List<Long> cave() {
        List<Long> c = new ArrayList<>();
        for (int x = 0; x <= 119; x++) { c.add(Sightings.cell(x, 40, 0)); c.add(Sightings.cell(x, 41, 0)); }
        for (int z = 1; z <= 40; z++) { c.add(Sightings.cell(40, 40, z)); c.add(Sightings.cell(40, 41, z)); }
        for (int z = -1; z >= -40; z--) { c.add(Sightings.cell(100, 40, z)); c.add(Sightings.cell(100, 41, z)); }
        return c;
    }

    static List<Long> trunk() {
        List<Long> t = new ArrayList<>();
        for (int x = 0; x <= 119; x++) { t.add(Sightings.cell(x, 40, 0)); t.add(Sightings.cell(x, 41, 0)); }
        return t;
    }

    static Sightings store() {
        Sightings s = new Sightings();
        assertNotNull(s.knowCave(D, new CaveIndex.Known(0, 40, 0, CaveIndex.Size.SMALL, CaveIndex.Depth.STONE, List.of(Sightings.cell(1, 40, 0))), 1));
        s.caveCells(D, 0, 40, 0, cave());
        return s;
    }

    @Test
    void atTheEntry_thePassagesAreMeasuredFromTheEntry_aWalkDownTheTrunkLeavesTheBranchesOpen() {
        Sightings s = store();
        assertEquals(400, s.caveFacts(D, 0, 40, 0).cellCount());
        assertEquals(SEEN_AT_ENTRY, s.caveStood(D, 0, 40, 0, 0, 40, 0, 10), "the entry and the cells SEE_STEPS round it");
        var ps = s.cavePassageList(D, 0, 40, 0);
        assertNotNull(ps, "loaded: measured from the entry");
        assertEquals(5, ps.size(), "the trunk to A, A, the trunk to B, B, the trunk's end");
        assertTrue(s.caveFacts(D, 0, 40, 0).passages() >= 3);
        s.caveSaw(D, 0, 40, 0, trunk());                               // the walk down the trunk: 240 seen
        var k = s.caveFacts(D, 0, 40, 0);
        assertEquals(0.6, k.explored(), 1e-9);
        assertEquals(160, k.unseen());
        assertFalse(k.done(), "the branches are not seen: open passages");
        assertTrue(s.cavePassageList(D, 0, 40, 0).stream().anyMatch(p -> p.open() && p.end()[2] == 40), "branch A open");
        assertTrue(s.cavePassageList(D, 0, 40, 0).stream().anyMatch(p -> p.open() && p.end()[2] == -40), "branch B open");
    }

    /** Pass two: the done check - one passage with an unseen cell is not done; none is. */
    @Test
    void done_oneUnseenPassageIsNotDone_noneIsDone() {
        Sightings s = store();
        List<Long> most = cave();
        most.remove(Sightings.cell(100, 41, -40));                       // B's last head cell
        s.caveSaw(D, 0, 40, 0, most);
        var k = s.caveFacts(D, 0, 40, 0);
        assertEquals(1, k.passages(), "one open passage: branch B");
        assertFalse(k.done(), "one passage with an unseen cell: not done");
        assertTrue(k.offeredFor("gold"), "not done: offered");
        s.caveSaw(D, 0, 40, 0, cave());
        k = s.caveFacts(D, 0, 40, 0);
        assertEquals(0, k.passages());
        assertTrue(k.done(), "no open passage: done");
        assertFalse(k.offeredFor("gold"), "done, no gold seen: no way for gold");
        s.caveOre(D, 0, 40, 0, Sightings.cell(10, 42, 0), "iron");
        assertTrue(s.caveFacts(D, 0, 40, 0).offeredFor("iron"), "done, iron seen and left: offered for iron");
        s.oreBroken(D, 10, 42, 0);
        assertFalse(s.caveFacts(D, 0, 40, 0).offeredFor("iron"), "mined: none left");
    }

    /** Pass two: no walkable cell at or near the entry - no passage, never done, the reason said. */
    @Test
    void noWalkableCell_noPassage_notDone_saidWhy() {
        Sightings s = new Sightings();
        assertNotNull(s.knowCave(D, new CaveIndex.Known(0, 40, 0, CaveIndex.Size.SMALL, CaveIndex.Depth.STONE, List.of(Sightings.cell(1, 40, 0))), 1));
        List<Long> tube = new ArrayList<>();
        for (int x = 0; x < 20; x++) tube.add(Sightings.cell(x, 40, 0));   // one cell high: nowhere to stand
        s.caveCells(D, 0, 40, 0, tube);
        s.caveSaw(D, 0, 40, 0, tube);
        var k = s.caveFacts(D, 0, 40, 0);
        assertFalse(k.done(), "no passage measured: not done");
        assertTrue(k.line().contains("no walkable cave cell"), k.line());
    }

    /** Run GT fix: beside = a record cell among the six neighbours, whatever the index holds of the entry's chunk. */
    @Test
    void anOreIsBesideTheCave_whenARecordCellTouchesIt_byTheRecordsOwnCells() {
        Sightings s = store();
        assertTrue(s.caveBeside(D, 0, 40, 0, 50, 42, 0), "the trunk's roof at x 50");
        assertTrue(s.caveBeside(D, 0, 40, 0, 40, 40, 41), "the end of branch A, one past its last cell");
        assertFalse(s.caveBeside(D, 0, 40, 0, 50, 44, 0), "two blocks into the rock");
        assertFalse(s.caveBeside(D, 0, 40, 0, 300, 40, 0), "far from the cave");
        assertFalse(s.caveBeside(D, 9, 9, 9, 50, 42, 0), "no record there");
    }

    @Test
    void oreSeenIsLeft_minedIsMined_theYieldIsMeasuredAfterTheFirstWalk() {
        Sightings s = store();
        long a = Sightings.cell(50, 42, 0), b = Sightings.cell(60, 39, 0);
        assertTrue(s.caveOre(D, 0, 40, 0, a, "iron"));
        assertTrue(s.caveOre(D, 0, 40, 0, b, "iron"));
        assertFalse(s.caveOre(D, 0, 40, 0, a, "iron"), "seen once");
        assertArrayEquals(new int[]{2, 0}, s.caveFacts(D, 0, 40, 0).ores().get("iron"));
        s.oreBroken(D, 50, 42, 0);                                    // a (J6c review: the one mined reader)
        assertArrayEquals(new int[]{1, 1}, s.caveFacts(D, 0, 40, 0).ores().get("iron"), "one left, one mined");
        assertEquals(1, s.caveLeft(D, 0, 40, 0, "iron").size());
        assertTrue(Double.isNaN(s.caveFacts(D, 0, 40, 0).oreSeenPerMinute()), "never walked: the prior prices it");
        s.caveWalked(D, 0, 40, 0, 4, 2, 20);
        assertEquals(0.5, s.caveFacts(D, 0, 40, 0).oreSeenPerMinute(), 1e-9, "2 ore seen in 4 minutes");
    }

    /** Pass two: the saved record is the passage list - read back before the cells load, measured again once they do. */
    @Test
    void reload_thePassageListRoundTrips() {
        Sightings s = store();
        s.caveSaw(D, 0, 40, 0, trunk());
        s.caveOre(D, 0, 40, 0, Sightings.cell(40, 42, 20), "iron");
        s.caveWalked(D, 0, 40, 0, 3, 1, 30);
        var saved = s.cavePassageList(D, 0, 40, 0);
        int open = s.caveFacts(D, 0, 40, 0).passages();
        int[] bad = {0};
        Sightings back = Sightings.fromJson(s.toJson(), bad);
        assertEquals(0, bad[0]);
        var k = back.caveFacts(D, 0, 40, 0);
        assertFalse(k.loaded(), "the cells come back with the chunks");
        assertEquals(400, k.cellCount());
        assertEquals(160, k.unseen());
        var read = back.cavePassageList(D, 0, 40, 0);
        assertEquals(saved.size(), read.size(), "the list read back");
        for (int i = 0; i < saved.size(); i++) {
            assertArrayEquals(saved.get(i).end(), read.get(i).end());
            assertEquals(saved.get(i).unseen(), read.get(i).unseen());
            assertEquals(saved.get(i).length(), read.get(i).length());
            assertEquals(saved.get(i).endKind(), read.get(i).endKind());
        }
        assertEquals(open, k.passages(), "the last measure");
        assertFalse(k.done());
        assertEquals(1, k.left("iron"));
        assertEquals(1 / 3.0, k.oreSeenPerMinute(), 1e-9);
        back.caveCells(D, 0, 40, 0, cave());                            // the chunks load again
        assertEquals(open, back.caveFacts(D, 0, 40, 0).passages(), "measured again from the entry: the same open passages");
        assertTrue(back.caveFacts(D, 0, 40, 0).line().startsWith("0 40 0: explored 60% (240 of 400 cells), " + open + " open passages, iron 1 left 0 mined, walked 3.0 min"),
                back.caveFacts(D, 0, 40, 0).line());
        assertTrue(back.caveFacts(D, 0, 40, 0).line().contains(" | passages: " + open + " of "), "aa cave list: open of total passages: " + back.caveFacts(D, 0, 40, 0).line());
        assertNull(back.caveFacts(D, 5, 5, 5), "no known cave there: no record");
    }

    /** Pass two (rule 7): a save from before the passage model (frontier groups) is read; its groups are dropped. */
    @Test
    void anOldSave_itsGroupsDropped_thePassagesMeasuredAgain() {
        Sightings s = store();
        s.caveSaw(D, 0, 40, 0, trunk());
        var o = com.google.gson.JsonParser.parseString(s.toJson()).getAsJsonObject();
        for (var d : o.getAsJsonObject("caveRecords").entrySet())
            for (var e : d.getValue().getAsJsonArray()) {
                var r = e.getAsJsonObject();
                r.remove("passages");
                r.addProperty("groups", 2);
                r.addProperty("done", false);
            }
        int[] bad = {0};
        Sightings back = Sightings.fromJson(o.toString(), bad);
        assertEquals(0, bad[0], "the old record is read, not skipped");
        assertNull(back.cavePassageList(D, 0, 40, 0), "its groups dropped: not measured");
        assertEquals(-1, back.caveFacts(D, 0, 40, 0).passages());
        assertFalse(back.caveFacts(D, 0, 40, 0).done());
        back.caveCells(D, 0, 40, 0, cave());
        assertNotNull(back.cavePassageList(D, 0, 40, 0), "the cells loaded: measured again");
        assertFalse(back.caveFacts(D, 0, 40, 0).done());
    }

    /**
     * Pass two, the price hold: while a caving step runs the planner reads the list the step measured first; a later
     * measure (the bot walked on) does not move it until the step ends.
     */
    @Test
    void whileAStepRuns_thePlannersListHolds_theEndReopensIt() {
        Sightings s = store();
        s.caveHold(D, 0, 40, 0, true);
        s.cavePassages(D, 0, 40, 0, new int[]{0, 40, 0}, java.util.Set.of(), 3, 3, 4096, 8, 2);
        var first = s.cavePassageList(D, 0, 40, 0);
        s.caveSaw(D, 0, 40, 0, trunk());
        s.cavePassages(D, 0, 40, 0, new int[]{60, 40, 0}, java.util.Set.of(), 3, 3, 4096, 8, 2);     // walked on
        var held = s.cavePassageList(D, 0, 40, 0);
        assertEquals(first, held, "held: the step's first measure, counts and all");
        int heldPrice = (int) io.github.mojolowjo.entropybot.cave.CaveWalk.findSeconds(held, 3, 0.5, 0.01).seconds();
        assertEquals(heldPrice, (int) io.github.mojolowjo.entropybot.cave.CaveWalk.findSeconds(s.cavePassageList(D, 0, 40, 0), 3, 0.5, 0.01).seconds());
        s.caveHold(D, 0, 40, 0, false);
        var after = s.cavePassageList(D, 0, 40, 0);
        assertNotEquals(first, after, "the step's end: its last measure (from 60 40 0) is stored");
        assertTrue(after.stream().allMatch(p -> p.fromFeet() >= 0));
    }

    /** Run JAJ (1): an ore seen on a passage's wall (the record's ore, by sight) is an ore face of that passage, and left to mine. */
    @Test
    void anOreSeenOnAPassagesWall_isItsOreFace() {
        Sightings s = store();
        s.caveOre(D, 0, 40, 0, Sightings.cell(40, 42, 20), "iron");            // the roof of branch A
        var a = s.cavePassageList(D, 0, 40, 0).stream().filter(p -> p.end()[2] == 40).findFirst().orElseThrow();
        assertEquals(1, a.oreFaces(), "the ore on A's roof: one face of A");
        assertTrue(s.cavePassageList(D, 0, 40, 0).stream().filter(p -> p != a && p.end()[2] != 40).allMatch(p -> p.oreFaces() == 0), "on no other passage");
        assertEquals(1, s.caveLeft(D, 0, 40, 0, "iron").size(), "left: the caving step's next moves walk to it and mine it");
    }

    /**
     * Run JAJ (2): the lip 4 blocks from the entry (feet -124 64 -32, the entry -124 64 -28) - the flood starts at the
     * nearest walkable cave cell within cave.passageLipNear (5), so the step has passages, not "no walkable cave cell".
     */
    @Test
    void jajsLip_fourBlocksOff_floodsFromTheNearestCaveCell() {
        Sightings s = new Sightings();
        assertNotNull(s.knowCave(D, new CaveIndex.Known(-124, 64, -28, CaveIndex.Size.LARGE, CaveIndex.Depth.STONE, List.of(Sightings.cell(-124, 64, -28))), 1));
        List<Long> c = new ArrayList<>();
        for (int z = -28; z <= 0; z++) { c.add(Sightings.cell(-124, 64, z)); c.add(Sightings.cell(-124, 65, z)); }   // a tunnel north from the entry
        s.caveCells(D, -124, 64, -28, c);
        int[] lip = {-124, 64, -32};
        var near2 = s.cavePassages(D, -124, 64, -28, lip, java.util.Set.of(), 3, 3, 4096, 8, 2);
        assertTrue(near2.passages().isEmpty(), "pass one's reach (2): none, as in JAJ");
        var f = s.cavePassages(D, -124, 64, -28, lip, java.util.Set.of(), 3, 3, 4096, 8,
                io.github.mojolowjo.entropybot.aa.brain.Tuning.i("cave.passageLipNear"));
        assertTrue(f.lip(), f.why());
        assertArrayEquals(new int[]{-124, 64, -28}, f.start());
        assertFalse(f.passages().isEmpty());
        assertArrayEquals(new int[]{-124, 64, 0}, f.passages().get(0).end());
    }

    /** One store (rule 23): only Sightings writes a cave record, and nothing else makes one. */
    @Test
    void onlyTheStoreWritesTheRecord() throws java.io.IOException {
        Pattern writer = Pattern.compile("\\.rec(Cells|Feed|Stood|See|Ore|Mined|Walked|Passages|Hold)\\(|new CaveRecord\\(");
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
