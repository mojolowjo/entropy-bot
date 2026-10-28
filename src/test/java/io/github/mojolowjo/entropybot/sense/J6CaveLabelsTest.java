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
    static CaveLabels.Label yes(int rim, int reach) { return new CaveLabels.Label(rim, 60, reach, rim, reach, "small", "stone", true, 42L, 1, "w"); }

    static CaveLabels.Label no(int x, int rim, int reach) { return new CaveLabels.Label(x, 70, 0, rim, reach, "small", "high", false, 42L, 1, "w"); }

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
        a.add(new CaveLabels.Label(5, 60, 9, 1, -1, "small", "high", false, null, 2, "w"));
        a.add(new CaveLabels.Label(4, 60, 7, 4, 7, "small", "stone", false, 42L, 3, "w"));   // the same entry, now "no"
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

    /** The label aim: the crosshair's air, the block beside a volume, the opening nearest the eyes, or nothing. */
    @Test
    void theLabelAimFindsTheVolume_anyVolume_orSaysNone() {
        CaveIndex i = J6CaveIndexTest.index();
        var s = i.labelSpot(CaveIndex.path(0.5, 51.5, 4.5, 6.5, 51.5, 4.5), null, 0.5, 51.5, 4.5, 4.5);
        assertNotNull(s);
        assertEquals(List.of(2, 50, 4), List.of(s.x(), s.y(), s.z()), "A: its entry the opening cell nearest the eyes");
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
