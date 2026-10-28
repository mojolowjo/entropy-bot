package io.github.mojolowjo.entropybot.sense;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 0.39.z78 J6 (the owner 2026-10-27 late: "I KNOW if it's a cave; get the information of what caves start like and
 * replicate"): the floor derived from the owner's labels, the check of the floor against them, the file, and the label
 * aim on the index.
 */
class J6CaveLabelsTest {
    static CaveLabels.Label yes(int rim, int reach) { return new CaveLabels.Label(rim, 60, reach, rim, reach, "small", "stone", true, 42L, 1, "w", CaveLabels.MEASURE, List.of()); }

    /** A label of the old measure (9710ddad / 2f215a09: the rim summed over the volume, no cells). */
    static CaveLabels.Label oldYes(int x, int rim, int reach) { return new CaveLabels.Label(x, 61, 0, rim, reach, "small", "stone", true, 42L, 1, "w", 1, List.of()); }

    static CaveLabels.Label no(int x, int rim, int reach) { return new CaveLabels.Label(x, 70, 0, rim, reach, "small", "high", false, 42L, 1, "w", CaveLabels.MEASURE, List.of()); }

    @Test
    void noLabels_theDefaults() {
        CaveLabels.Floor f = CaveLabels.derive(List.of());
        assertEquals(CaveIndex.MIN_RIM, f.rim());
        assertEquals(CaveIndex.MIN_REACH, f.reach());
        assertFalse(f.derived());
        assertTrue(f.line().contains("the defaults: 0 of 5"), f.line());
    }

    @Test
    void fourYesLabels_stillTheDefaults() {
        CaveLabels.Floor f = CaveLabels.derive(List.of(yes(2, 4), yes(2, 4), yes(2, 4), yes(2, 4), no(9, 1, 0)));
        assertEquals(3, f.rim());
        assertEquals(5, f.reach());
        assertFalse(f.derived());
    }

    @Test
    void fiveYesLabels_rim2Reach4_theFloorIs2And4() {
        CaveLabels.Floor f = CaveLabels.derive(List.of(yes(2, 4), yes(5, 9), yes(3, 6), yes(8, 20), yes(4, 5)));
        assertEquals(2, f.rim());
        assertEquals(4, f.reach());
        assertTrue(f.derived());
        assertTrue(f.line().contains("derived from 5 caves labelled yes"), f.line());
    }

    @Test
    void neverBelowTheHardMinimum_neverAboveTheDefaults() {
        CaveLabels.Floor low = CaveLabels.derive(List.of(yes(1, 1), yes(1, 2), yes(6, 9), yes(6, 9), yes(6, 9)));
        assertEquals(CaveLabels.HARD_RIM, low.rim(), "a yes with rim 1: floored at 2");
        assertEquals(CaveLabels.HARD_REACH, low.reach(), "a yes with reach 1: floored at 3");
        CaveLabels.Floor high = CaveLabels.derive(List.of(yes(9, 30), yes(9, 30), yes(9, 30), yes(9, 30), yes(9, 30)));
        assertEquals(3, high.rim(), "big caves only: never above the default 3");
        assertEquals(5, high.reach(), "never above the default 5");
        CaveLabels.Floor noOpening = CaveLabels.derive(List.of(yes(0, -1), yes(0, -1), yes(0, -1), yes(0, -1), yes(0, -1)));
        assertFalse(noOpening.derived(), "a yes with no opening says nothing of an opening: not counted");
    }

    @Test
    void theCheck_precisionRecall_andTheMissesWithTheirRimAndReach() {
        List<CaveLabels.Label> l = new ArrayList<>(List.of(yes(6, 9), yes(4, 7), yes(2, 8), no(10, 1, 0), no(11, 5, 6), no(12, 3, 2)));
        CaveLabels.Floor f = CaveLabels.derive(l);                    // 3 yes: the defaults 3 / 5
        CaveLabels.Check c = CaveLabels.check(l, f);
        assertEquals(3, c.yes());
        assertEquals(3, c.no());
        assertEquals(2, c.yesPass(), "rim 2 misses the default rim 3");
        assertEquals(1, c.noPass(), "the non-cave with rim 5, reach 6 passes");
        assertEquals(2 / 3.0, c.recall(), 1e-9);
        assertEquals(2 / 3.0, c.precision(), 1e-9);
        String t = CaveLabels.checkText(l, f, x -> x.rim() == 6 ? new int[]{2, 3, 1} : x.rim() == 4 ? new int[]{1, 0, 0} : null);
        String[] lines = t.split("\n");
        assertTrue(lines[1].startsWith("classification: 6 labels (3 caves, 3 not); the floor passes 2 of 3 caves (recall 67%) and 1 of 3 non-caves (precision 67%)"), t);
        assertTrue(t.contains("  missed cave at 2 60 8: rim 2, reach 8, size small, depth stone (w) - rim under 3"), t);
        assertTrue(t.contains("  passed non-cave at 11 70 0: rim 5, reach 6"), t);
        assertTrue(lines[lines.length - 1].equals("credits this session (of 3 labelled caves): rim credits 2, air credits 1, known 1, 1 not loaded"), t);
        assertFalse(CaveLabels.checkText(l, f, null).contains("credits this session"), "offline: no credit line");
        assertTrue(CaveLabels.checkText(List.of(), f, null).startsWith("cave check: no labels yet"));
    }

    @Test
    void theFileRoundTrip_aBrokenEntryCounted_aRelabelReplaces() {
        CaveLabels a = new CaveLabels();
        a.add(yes(4, 7));
        a.add(new CaveLabels.Label(5, 60, 9, 1, -1, "small", "high", false, null, 2, "w", CaveLabels.MEASURE, List.of(Sightings.cell(5, 60, 9))));
        a.add(new CaveLabels.Label(4, 60, 7, 4, 7, "small", "stone", false, 42L, 3, "w", CaveLabels.MEASURE, List.of()));   // the same entry, now "no"
        assertEquals(2, a.all().size());
        int[] bad = {0};
        CaveLabels b = CaveLabels.read(a.toJson(), bad);
        assertEquals(0, bad[0]);
        assertEquals(a.all(), b.all());
        assertFalse(b.all().get(1).cave());
        assertNull(b.all().get(0).seed(), "no seed (a server world): none stored");
        CaveLabels c = CaveLabels.read("{\"labels\":[{\"x\":1},{\"x\":1,\"y\":2,\"z\":3,\"rim\":3,\"reach\":5,\"size\":\"small\",\"depth\":\"stone\",\"verdict\":\"yes\",\"at\":1}]}", bad);
        assertEquals(1, c.all().size());
        assertEquals(1, bad[0]);
    }

    /** Run EF: the old measure's labels count for the verdict, never for the derivation; the check says them. */
    @Test
    void oldMeasureLabels_countForTheVerdict_notForTheDerivation() {
        List<CaveLabels.Label> l = List.of(oldYes(1, 4719, 17), oldYes(2, 900, 9), oldYes(3, 50, 8), oldYes(4, 40, 7), oldYes(5, 30, 6), yes(2, 4));
        CaveLabels.Floor f = CaveLabels.derive(l);
        assertFalse(f.derived(), "5 old labels and 1 new: under 5 that derive");
        assertEquals(1, f.yes());
        String t = CaveLabels.checkText(l, f, null);
        assertTrue(t.contains("the floor passes 5 of 6 caves"), t);
        assertTrue(t.contains("old measure: 5 label(s) (one rim summed over the whole volume, no opening cells; their volume not loaded yet): counted for the verdict, not used to derive the floor"), t);
        assertTrue(CaveLabels.checkText(l, f, null, 3).contains("3 labels re-measured (old measure -> per opening, from the index; your verdicts kept)"));
        assertTrue(t.contains("missed cave at 2 60 4: rim 2, reach 4"), t);
        int[] bad = {0};
        var read = CaveLabels.read("{\"labels\":[{\"x\":-78,\"y\":60,\"z\":52,\"rim\":4719,\"reach\":17,\"size\":\"large\",\"depth\":\"stone\",\"verdict\":\"yes\",\"at\":1}]}", bad);
        assertTrue(read.all().get(0).old(), "a 9710ddad label (no measure field): the old measure");
        assertTrue(read.all().get(0).cells().isEmpty());
    }

    /**
     * The owner's ruling: an old-measure label is re-measured per opening when its volume is in the index and promoted -
     * his verdict, seed, time and world kept; one whose volume is not loaded stays old.
     */
    @Test
    void anOldLabelIsReMeasuredWhenItsVolumeIsLoaded_theVerdictKept() {
        CaveIndex i = J6CaveIndexTest.index();
        CaveLabels.Label old = new CaveLabels.Label(2, 50, 4, 4719, 17, "large", "deepslate", true, 99L, 5, "New_World_1_ minecraft:overworld", 1, List.of());
        CaveLabels.Label now = CaveLabels.remeasure(old, i);
        assertNotNull(now);
        assertEquals(CaveLabels.MEASURE, now.measure());
        assertEquals(6, now.rim(), "A's opening: 6 cells, not 4719");
        assertEquals(10, now.reach());
        assertEquals(6, now.cells().size());
        assertTrue(now.cave());
        assertEquals(99L, now.seed());
        assertEquals(5, now.at());
        assertEquals(old.world(), now.world());
        assertEquals("small", now.size());
        assertNull(CaveLabels.remeasure(new CaveLabels.Label(400, 50, 400, 3, 0, "small", "high", true, 99L, 5, "w", 1, List.of()), i), "its volume not loaded: stays old");
        assertNull(CaveLabels.remeasure(now, i), "a new-measure label is not measured again");
        CaveLabels set = new CaveLabels();
        set.add(old);
        set.replace(old, now);
        assertEquals(List.of(now), set.all());
    }

    /** Run EF: an opening's cells are stored (plain when few, deflated when many) and read back for an offline re-measure. */
    @Test
    void theOpeningsCellsRoundTrip_plainAndPacked() {
        List<Long> few = List.of(Sightings.cell(1, 2, 3), Sightings.cell(-4, 60, 9)), many = new ArrayList<>();
        for (int i = 0; i < 1000; i++) many.add(Sightings.cell(i, 40 + i % 7, -i));
        CaveLabels a = new CaveLabels();
        a.add(new CaveLabels.Label(1, 2, 3, 2, 5, "small", "stone", true, 7L, 1, "w", CaveLabels.MEASURE, few));
        a.add(new CaveLabels.Label(9, 2, 3, 1000, 5, "large", "stone", true, 7L, 1, "w", CaveLabels.MEASURE, many));
        String json = a.toJson();
        assertTrue(json.contains("\"cells\":[[1,2,3],[-4,60,9]]"), json.substring(0, Math.min(300, json.length())));
        assertTrue(json.contains("\"cellsZ\":"), "1000 cells: packed");
        int[] bad = {0};
        var b = CaveLabels.read(json, bad);
        assertEquals(0, bad[0]);
        assertEquals(few, b.all().get(0).cells());
        assertEquals(many, b.all().get(1).cells());
        assertFalse(b.all().get(1).old());
    }

    /** The label aim: the crosshair's air, the block beside a volume, the opening nearest the eyes, or nothing. */
    @Test
    void theLabelAimFindsTheVolume_anyVolume_orSaysNone() {
        CaveIndex i = J6CaveIndexTest.index();
        var s = i.labelSpot(CaveIndex.path(0.5, 51.5, 4.5, 6.5, 51.5, 4.5), null, 0.5, 51.5, 4.5, 4.5);
        assertNotNull(s);
        assertEquals(List.of(2, 51, 4), List.of(s.x(), s.y(), s.z()), "A: its entry the opening cell nearest the eyes");
        assertEquals(6, s.rim());
        assertEquals(10, s.reach());
        assertTrue(s.cave());
        var beside = i.labelSpot(List.of(), new int[]{11, 25, 11}, 11.5, 30, 11.5, 4.5);
        assertNotNull(beside, "the roof block over B: B (no opening: labelled all the same)");
        assertEquals(-1, beside.reach());
        assertFalse(beside.cave());
        var near = i.labelSpot(List.of(), null, 0.5, 51.6, 10.5, 4.5);
        assertNotNull(near, "the opening of D within reach");
        assertEquals(2, near.x());
        assertEquals(10, near.z());
        assertNull(i.labelSpot(List.of(), null, 0.5, 70, 30.5, 4.5), "no air volume here");
    }

    /** Nothing in the planner reads the labels: only through the floor the index takes (ChunkPass sets it). */
    @Test
    void noPlannerClassReadsTheLabels() throws java.io.IOException {
        try (var files = java.nio.file.Files.walk(java.nio.file.Path.of("src/main/java/io/github/mojolowjo/entropybot/aa"))) {
            for (var f : (Iterable<java.nio.file.Path>) files.filter(p -> p.toString().endsWith(".java"))::iterator)
                assertFalse(java.nio.file.Files.readString(f).contains("CaveLabels"), f + " reads the labels");
        }
    }

    /** The derived floor reaches the index: a floor of rim 2 makes a 2-cell opening a cave. */
    @Test
    void theDerivedFloorIsTheIndexs() {
        int[] holed = new int[256];
        for (int c = 0; c < 256; c++) holed[c] = (c >> 4) == 5 && (c & 15) >= 5 && (c & 15) <= 7 ? 58 : 64;
        CaveIndex h = new CaveIndex();
        h.put(CaveIndex.runs(0, 0, holed, -64, (x, y, z) -> z == 5 && ((x >= 8 && x <= 12 && y >= 59 && y <= 60) || (x == 12 && y >= 52 && y <= 60))));
        int v = h.volumeAt(10, 59, 5);
        assertFalse(h.isCave(v), "2 opening cells: under the default 3");
        h.setFloor(2, 5);
        assertTrue(h.isCave(v), "the labels' floor rim 2: a cave");
    }
}
