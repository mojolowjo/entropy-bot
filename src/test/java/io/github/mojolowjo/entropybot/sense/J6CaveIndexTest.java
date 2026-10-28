package io.github.mojolowjo.entropybot.sense;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 0.39.z78 J6 (to-do/j6-caves-by-rays.md; the owner's ruling at the top of docs/CAVE_DETECTION.md; the review of
 * 3317a342): the cave index's scenarios - two volumes under one surface, three rays THROUGH the opening into one make it a
 * known cave with an entry a ray saw, a size bucket and a depth band; three rays stopped by a one-block wall credit
 * nothing; two rays do not make it known; the other volume stays untouched; a reload keeps it known; the index across a
 * chunk edge, an unload drops the unknown volumes and keeps the known marks; the memory a chunk takes.
 */
class J6CaveIndexTest {
    static final int SURFACE = 64;

    /**
     * Chunk 0 0, ground to y 63, the surface at 64 everywhere but a dip: columns x 0..1 at z 0..15 have their surface at
     * y 50 (a ravine-like cut, open to the sky). Volume A: a tunnel at y 50..52, x 2..9, z 4..5 - its x 2 end opens on
     * the cut at x 1 (surface 50: 6 opening cells, the lowest at y 50) - and a shaft at x 8..9 down to y 40 (10 below).
     * Volume D, the same at z 10..11, x 2..5, its shaft at x 4..5. Volume B: a closed room at y 20..24, x 10..13,
     * z 10..13, no opening (no cave by the owner's definition).
     */
    static CaveIndex.ChunkRuns chunk() {
        return CaveIndex.runs(0, 0, cutSurface(), -64, (x, y, z) -> inA(x, y, z) || inB(x, y, z) || inD(x, y, z));
    }

    static boolean inD(int x, int y, int z) { return z >= 10 && z <= 11 && ((x >= 2 && x <= 5 && y >= 50 && y <= 52) || (x >= 4 && x <= 5 && y >= 40 && y <= 52)); }

    static int[] cutSurface() {
        int[] surface = new int[256];
        for (int c = 0; c < 256; c++) surface[c] = (c & 15) <= 1 ? 50 : SURFACE;
        return surface;
    }

    static boolean inA(int x, int y, int z) { return z >= 4 && z <= 5 && ((x >= 2 && x <= 9 && y >= 50 && y <= 52) || (x >= 8 && x <= 9 && y >= 40 && y <= 52)); }

    static boolean inB(int x, int y, int z) { return x >= 10 && x <= 13 && z >= 10 && z <= 13 && y >= 20 && y <= 24; }

    static CaveIndex index() {
        CaveIndex i = new CaveIndex();
        i.put(chunk());
        return i;
    }

    /** The eyes standing in the cut, at A's mouth. */
    static final double EX = 0.5, EY = 51.5, EZ = 4.5;

    /** A ray from the eyes in the cut that reaches the cell x y z (nothing in the way: the clip's MISS). */
    static CaveIndex.Result seen(CaveIndex i, int x, int y, int z) {
        return i.creditRay(CaveIndex.path(EX, EY, EZ, x + 0.5, y + 0.5, z + 0.5));
    }

    @Test
    void twoVolumes_threeRaysThroughTheOpening_aKnownCave_itsEntryASeenCell_aSizeBucketAndADepthBand() {
        CaveIndex i = index();
        int a = i.volumeAt(5, 51, 4), b = i.volumeAt(11, 21, 11);
        assertTrue(a >= 0 && b >= 0 && a != b, "two volumes");
        assertEquals(8 * 2 * 3 + 2 * 2 * 10, i.volume(a).cells(), "A: 8 x 2 x 3 cells and its shaft 2 x 2 x 10");
        assertEquals(4 * 4 * 5, i.volume(b).cells());
        assertTrue(i.isCave(a), "A: 6 opening cells, its air 10 below the opening");
        assertFalse(i.isCave(b), "B: no opening");
        assertEquals(CaveIndex.Credit.CREDITED, seen(i, 9, 51, 5).what());
        assertEquals(CaveIndex.Credit.CREDITED, seen(i, 6, 52, 4).what());
        CaveIndex.Result r = seen(i, 5, 51, 5);
        assertEquals(CaveIndex.Credit.KNOWN, r.what());
        CaveIndex.Known k = r.known();
        assertEquals(3, k.hits().size());
        assertTrue(k.hits().contains(Sightings.cell(k.x(), k.y(), k.z())), "the entry is a cell a ray saw: " + k);
        assertEquals(List.of(6, 52, 4), List.of(k.x(), k.y(), k.z()), "of the seen cells the one nearest its column's surface");
        assertEquals(CaveIndex.Size.SMALL, k.size(), "88 cells: well under a minute of walking");
        assertEquals(CaveIndex.Depth.STONE, k.depth(), "its lowest cell y 40: between y 0 and sea level");
        assertEquals(CaveIndex.Credit.ALREADY_KNOWN, seen(i, 7, 51, 4).what(), "known stays known");
    }

    /**
     * The review of 3317a342 (rule 17): a tunnel C behind a one-block wall (the column x 2 is rock) - three rays from the
     * cut toward C stop on the wall and credit nothing (the old "within one block of a cave cell" credited C from rock);
     * with the wall gone the same three rays pass through C's air and make it known.
     */
    @Test
    void threeRaysStoppedByAOneBlockWall_creditNothing() {
        int[] twoCuts = new int[256];
        for (int c = 0; c < 256; c++) twoCuts[c] = (c & 15) <= 1 || (c & 15) >= 14 ? 50 : SURFACE;   // C opens on the far cut (x 14)
        CaveIndex walled = new CaveIndex();
        walled.put(CaveIndex.runs(0, 0, twoCuts, -64, (x, y, z) -> inC(x, y, z) && x >= 3));
        assertTrue(walled.isCave(walled.volumeAt(5, 52, 8)), "walled C is a cave (its far opening): the rays below are tested against a cave");
        double ex = 0.5, ey = 52.5, ez = 8.5;
        int[][] targets = {{5, 51, 8}, {6, 52, 8}, {7, 53, 8}};
        for (int[] t : targets) {
            double tx = t[0] + 0.5, ty = t[1] + 0.5, tz = t[2] + 0.5, f = (2.0 - ex) / (tx - ex);   // the clip stops on the wall's face x 2
            double hx = 2.0, hy = ey + f * (ty - ey), hz = ez + f * (tz - ez);
            List<int[]> path = new ArrayList<>(CaveIndex.path(ex, ey, ez, hx, hy, hz));
            int by = (int) Math.floor(hy);
            path.removeIf(q -> q[0] == 2 && q[1] == by && q[2] == 8);        // as CaveRays: the block that stopped it was not seen through
            assertEquals(CaveIndex.Credit.NO_VOLUME, walled.creditRay(path).what(), "stopped by the wall at y " + by);
        }
        assertTrue(walled.volume(walled.volumeAt(5, 52, 8)).credits().isEmpty(), "nothing credited");
        assertTrue(walled.credited().isEmpty());
        assertEquals(CaveIndex.Credit.NO_VOLUME, walled.credit(2, 52, 8).what(), "the wall's own cell is no cave cell (no credit beside air)");

        CaveIndex open = new CaveIndex();
        open.put(CaveIndex.runs(0, 0, twoCuts, -64, J6CaveIndexTest::inC));
        CaveIndex.Result last = null;
        for (int[] t : targets) last = open.creditRay(CaveIndex.path(ex, ey, ez, t[0] + 0.5, t[1] + 0.5, t[2] + 0.5));
        assertEquals(CaveIndex.Credit.KNOWN, last.what(), "the wall gone: through the opening, three rays make it known");
    }

    /** Tunnel C: y 51..53, x 2..13, z 8..9, its shaft at x 10..11 down to y 40. */
    static boolean inC(int x, int y, int z) { return z >= 8 && z <= 9 && ((x >= 2 && x <= 13 && y >= 51 && y <= 53) || (x >= 10 && x <= 11 && y >= 40 && y <= 53)); }

    /**
     * Run ED, the owner's definition ("a few air blocks at the opening which continue downwards"): a 2-cell dip under a
     * tree is no cave - not indexed under the ground (leaves are no roof), and with the crown's WORLD_SURFACE as the
     * surface a dip (its air 2 below its opening): no credit, no ray target, no opening; a 3-wide hole whose air reaches 6
     * below it is a cave.
     */
    @Test
    void aTwoCellDipUnderATreeIsNoCave_aThreeWideHoleGoingSixDownIs() {
        // a crown of leaves y 67..69 over x 4..8, z 4..8; a trunk at 6 _ 6; the ground at 64; a 2-cell dip at 5 62..63 5
        CaveIndex.CellTest leaves = (x, y, z) -> x >= 4 && x <= 8 && z >= 4 && z <= 8 && y >= 67 && y <= 69;
        CaveIndex.CellTest trunk = (x, y, z) -> x == 6 && z == 6 && y >= 64 && y <= 68;
        CaveIndex.CellTest dip = (x, y, z) -> x == 5 && z == 5 && (y == 62 || y == 63);
        CaveIndex.CellTest air = (x, y, z) -> !leaves.test(x, y, z) && !trunk.test(x, y, z) && (y >= 64 || dip.test(x, y, z));
        int[] worldSurface = new int[256], ground = new int[256];
        for (int c = 0; c < 256; c++) {
            int x = c & 15, z = c >> 4;
            worldSurface[c] = x >= 4 && x <= 8 && z >= 4 && z <= 8 ? 70 : 64;
            ground[c] = CaveIndex.ground(x, z, worldSurface[c], -64, (cx, cy, cz) -> air.test(cx, cy, cz) || leaves.test(cx, cy, cz));
        }
        assertEquals(62, ground[5 + 16 * 5], "the dip's column: its ground is the dip's floor");
        assertEquals(64, ground[4 + 16 * 4], "under the crown: the ground, not the leaves");
        CaveIndex g = new CaveIndex();
        g.put(CaveIndex.runs(0, 0, ground, -64, air));
        assertEquals(-1, g.volumeAt(5, 63, 5), "under the ground: nothing indexed");
        assertEquals(-1, g.volumeAt(5, 65, 5));
        CaveIndex w = new CaveIndex();                       // the crown's WORLD_SURFACE as the surface: the air under it is a volume
        w.put(CaveIndex.runs(0, 0, worldSurface, -64, air));
        int v = w.volumeAt(5, 63, 5);
        assertTrue(v >= 0 && v == w.volumeAt(5, 65, 5), "the dip and the air under the crown: one volume");
        assertFalse(w.isCave(v), "its air goes 2 below its opening (y 64): a dip");
        assertEquals(CaveIndex.Credit.DIP, w.credit(5, 63, 5).what(), "never credited");
        assertTrue(w.targets(5, 65, 5, 16, 50, null).isEmpty(), "never a ray's target");
        assertTrue(w.openings(5, 65, 5, 16).isEmpty(), "never an opening");

        // a hole open to the sky at x 5..7 z 5 down to y 58; under the rock from x 8 a tunnel y 58..60 at z 5 (its face on
        // the hole: 3 cells), x 8..12, then a shaft at x 12 down to y 52 - 6 below the opening's lowest cell
        int[] holed = new int[256];
        for (int c = 0; c < 256; c++) holed[c] = (c >> 4) == 5 && (c & 15) >= 5 && (c & 15) <= 7 ? 58 : 64;
        CaveIndex h = new CaveIndex();
        h.put(CaveIndex.runs(0, 0, holed, -64, (x, y, z) -> z == 5 && ((x >= 8 && x <= 12 && y >= 58 && y <= 60) || (x == 12 && y >= 52 && y <= 60))));
        int t = h.volumeAt(10, 59, 5);
        assertTrue(h.isCave(t), "3 opening cells, its air 6 below them: a cave");
        assertEquals(CaveIndex.Credit.CREDITED, h.credit(10, 59, 5).what());
        assertEquals(CaveIndex.Credit.RIM, h.rimCredit(8, 61, 5).what(), "the lip over the hole's face");
        assertEquals(1, h.openings(6, 64, 5, 16).size(), "a credited cave's opening is a target");
        assertEquals(8, h.openings(6, 64, 5, 16).get(0)[0]);
    }

    /**
     * Run EE (2236 rays, 0 credits: a ray from afar to under-ground air ends on the ground first), the manager's ruling: a
     * hole in a hillside 30 m away - rays that end on its lip (a block beside an opening cell) make it a target, no ray
     * went through its air so it is not known; the bot walks to it and three rays in make it known.
     */
    @Test
    void aHillsideHoleThirtyMetresOff_itsLipSeen_isATargetNotKnown_threeRaysInMakeItKnown() {
        int[] hill = new int[256];
        for (int c = 0; c < 256; c++) hill[c] = 64;
        CaveIndex.CellTest cave = (x, y, z) -> z >= 5 && z <= 6 && ((x >= 20 && x <= 30 && y >= 64 && y <= 66) || (x >= 28 && x <= 29 && y >= 55 && y <= 66));
        CaveIndex i = new CaveIndex();
        i.put(CaveIndex.runs(0, 0, hill, -64, (x, y, z) -> false));                     // the valley, x 0..15
        int[] h2 = new int[256];
        for (int c = 0; c < 256; c++) h2[c] = (c & 15) >= 4 ? 70 : 64;                   // the hill from x 20 (chunk 1: x 16..31)
        i.put(CaveIndex.runs(1, 0, h2, -64, cave));
        int v = i.volumeAt(25, 65, 5);
        assertTrue(i.isCave(v), "6 opening cells at x 20, its air 9 below them");
        assertTrue(i.openings(-10, 65, 5, 64).isEmpty(), "no ray yet: no target");
        List<int[]> t = i.targets(-10, 65, 5, 64, 400, null);
        assertTrue(t.stream().anyMatch(c -> c[0] == 20 && c[1] == 67 && c[2] == 5), "the lip over the opening is a ray target");
        assertEquals(CaveIndex.Credit.NO_VOLUME, i.rimCredit(25, 67, 5).what(), "the roof over unseen air (no opening cell beside it): nothing");
        assertEquals(CaveIndex.Credit.RIM, i.rimCredit(20, 67, 5).what(), "a ray from 30 m ended on the lip");
        assertEquals(CaveIndex.Credit.ALREADY_KNOWN, i.rimCredit(20, 67, 5).what(), "the same lip again");
        assertEquals(1, i.openings(-10, 65, 5, 64).size(), "the lip seen: a target");
        assertFalse(i.isKnown(v), "no ray through its air: not known");
        assertTrue(i.volume(v).credits().isEmpty(), "no air credit");
        assertFalse(i.targets(-10, 65, 5, 64, 400, null).stream().anyMatch(c -> c[1] == 67), "its lip seen: no more lip targets");
        CaveIndex.Result last = null;                                                  // walked to the mouth: three rays in
        for (int[] in : new int[][]{{25, 65, 5}, {26, 66, 6}, {27, 64, 5}}) last = i.creditRay(CaveIndex.path(18.5, 65.6, 5.5, in[0] + 0.5, in[1] + 0.5, in[2] + 0.5));
        assertEquals(CaveIndex.Credit.KNOWN, last.what(), "three rays through its air: known");
        assertTrue(i.counts().startsWith("cave index: volumes 1 (caves 1, dips 0, no opening 0), rim credits 1, air credits 3, known 1"), i.counts());
    }

    /** aa cave list's truth part (run EE, a debug read): each cave within the radius with its fields; dips and no-opening counted. */
    @Test
    void theIndexTruthListsEachCaveWithItsFields_andNoPlannerReadsIt() throws java.io.IOException {
        CaveIndex i = index();
        i.rimCredit(2, 53, 4);                               // the roof over A's opening at x 2
        var tl = i.truth(0, 64, 0, 64);
        assertEquals(2, tl.caves().size(), "A and D");
        assertEquals(1, tl.noOpening(), "B");
        String t = CaveList.text(List.of(), java.util.Map.of(), tl, 64, i.counts(), "rays: test", 0, 64, 0);
        assertTrue(t.contains("index caves within 64 (the index's truth, a debug read; never a way or a target): 2"), t);
        assertTrue(t.contains("volume at 2 52 4: ") && t.contains("size small, depth stone, rim 1, air 0, not known"), t);
        assertTrue(t.contains("  dips 0, no opening 1"), t);
        assertTrue(t.contains("cave index: volumes 3 (caves 2, dips 0, no opening 1), rim credits 1, air credits 0, known 0"), t);
        // rule 17: nothing in the planner reads the truth - only aa cave list and aa debug call it
        java.util.Set<String> callers = new java.util.TreeSet<>();
        try (var files = java.nio.file.Files.walk(java.nio.file.Path.of("src/main/java"))) {
            for (var f : (Iterable<java.nio.file.Path>) files.filter(p -> p.toString().endsWith(".java"))::iterator) {
                String src = java.nio.file.Files.readString(f);
                if ((src.contains(".truth(") || src.contains("TruthList") || src.contains("CaveIndex.Truth")) && !f.endsWith("CaveIndex.java") && !f.endsWith("CaveList.java"))
                    callers.add(f.getFileName().toString());
            }
        }
        assertEquals(java.util.Set.of("AaRuntime.java", "CaveLabel.java"), callers, "the truth's readers: aa debug and aa cave list only");
        try (var files = java.nio.file.Files.walk(java.nio.file.Path.of("src/main/java/io/github/mojolowjo/entropybot/aa"))) {
            for (var f : (Iterable<java.nio.file.Path>) files.filter(p -> p.toString().endsWith(".java"))::iterator)
                assertFalse(java.nio.file.Files.readString(f).contains("truth("), f + " reads the index's truth");
        }
    }

    /** aa cave list all: every cave the index holds, no radius, with distance, compass, credits, known and the owner's verdict. */
    @Test
    void theListAllHasEveryCaveWithItsDirectionAndVerdict() {
        CaveIndex i = index();
        var all = i.truthAll(0, 51, 4);
        assertEquals(2, all.caves().size(), "A and D");
        assertTrue(i.truth(500, 51, 500, 64).caves().isEmpty(), "the capped list from far away: none");
        assertEquals(2, i.truthAll(500, 51, 500).caves().size(), "list all from far away: both");
        int a = i.volumeAt(5, 51, 4);
        String t = CaveList.allText(all, java.util.Map.of(a, true), 0.5, 51, 4.5);
        assertTrue(t.startsWith("index caves, every chunk held (the index's truth, a debug read; never a way or a target): 2"), t);
        assertTrue(t.contains("  2 50 4: 2 m E, size small, depth stone, rim credits 0, air credits 0, not known, labelled yes"), t);
        assertTrue(t.contains("  2 50 10: 6 m S, size small, depth stone, rim credits 0, air credits 0, not known, labelled -"), t);
        assertTrue(t.endsWith("  dips 0, no opening 1"), t);
        assertEquals("W", CaveList.compass(-106, 3));
        assertEquals("NE", CaveList.compass(5, -5));
    }

    /**
     * Run EF (the owner's first label: "rim 4719, reach 17" - the rim summed over the whole volume): each opening is
     * measured on its own. Three separate 1-cell holes over one tunnel are no cave (rim 1 each), though the old summed
     * measure (rim 3, reach 10) passed it; one 3-cell hole reaching 6 down is.
     */
    @Test
    void threeSeparateOneCellHolesAreNoCave_oneThreeCellHoleReachingSixIs() {
        int[] holes = new int[256];
        for (int c = 0; c < 256; c++) { int x = c & 15, z = c >> 4; holes[c] = z == 5 && (x == 3 || x == 7 || x == 11) ? 60 : 64; }
        CaveIndex i = new CaveIndex();                       // a tunnel y 60 at z 6, x 2..12, each hole beside one of its cells; a shaft at x 12 to y 50
        i.put(CaveIndex.runs(0, 0, holes, -64, (x, y, z) -> z == 6 && ((x >= 2 && x <= 12 && y == 60) || (x == 12 && y >= 50 && y <= 60))));
        int v = i.volumeAt(5, 60, 6);
        assertEquals(3, i.summedRim(v), "the old measure: 3 sky-adjacent cells summed");
        assertFalse(i.isCave(v), "three openings of 1 cell each: no cave");
        assertEquals(CaveIndex.Credit.DIP, i.credit(5, 60, 6).what());
        var spot = i.labelSpot(List.of(), null, 3.5, 61.6, 4.5, 4.5);
        assertEquals(1, spot.rim(), "the label measures one opening: rim 1");
        assertEquals(10, spot.reach(), "its reach: 60 - 50");
        assertEquals(1, spot.cells().size());

        int[] holed = new int[256];
        for (int c = 0; c < 256; c++) holed[c] = (c >> 4) == 5 && (c & 15) >= 5 && (c & 15) <= 7 ? 58 : 64;
        CaveIndex h = new CaveIndex();
        h.put(CaveIndex.runs(0, 0, holed, -64, (x, y, z) -> z == 5 && ((x >= 8 && x <= 12 && y >= 58 && y <= 60) || (x == 12 && y >= 52 && y <= 60))));
        int t = h.volumeAt(10, 59, 5);
        assertTrue(h.isCave(t), "one opening of 3 cells, 6 down: a cave");
        var s = h.labelSpot(List.of(), null, 7.5, 59.6, 5.5, 4.5);
        assertEquals(3, s.rim());
        assertEquals(6, s.reach());
        assertEquals(java.util.Set.of(Sightings.cell(8, 58, 5), Sightings.cell(8, 59, 5), Sightings.cell(8, 60, 5)), java.util.Set.copyOf(s.cells()), "the opening's cells, for the label");
    }

    /**
     * Run EG: two volumes credited 2 and 2, joined by a chunk load between them: one volume with 4 credits, known; and a
     * known cave's credits survive its chunks unloading and coming back (22:52-22:54: "rim 0, air 0, known").
     */
    @Test
    void twoVolumesCredited2And2_mergeByAChunkLoad_oneVolume4Credits_known_andTheCreditsSurviveAReload() {
        int[] c0 = new int[256], c1 = new int[256], c2 = new int[256];
        for (int c = 0; c < 256; c++) { c0[c] = (c & 15) <= 1 ? 50 : SURFACE; c1[c] = SURFACE; c2[c] = (c & 15) >= 14 ? 50 : SURFACE; }
        CaveIndex.CellTest tunnel = (x, y, z) -> z >= 8 && z <= 9 && ((x >= 2 && x <= 45 && y >= 51 && y <= 53) || ((x == 4 || x == 5 || x == 42 || x == 43) && y >= 40 && y <= 53));
        CaveIndex i = new CaveIndex();
        i.put(CaveIndex.runs(0, 0, c0, -64, tunnel));        // P: x 2..15, its opening at x 2
        i.put(CaveIndex.runs(2, 0, c2, -64, tunnel));        // Q: x 32..45, its opening at x 45
        int p = i.volumeAt(6, 52, 8), q = i.volumeAt(40, 52, 8);
        assertTrue(p != q && i.isCave(p) && i.isCave(q));
        i.credit(6, 52, 8); i.credit(7, 52, 9);
        i.credit(40, 52, 8); i.credit(41, 52, 9);
        assertEquals(2, i.volume(p).credits().size());
        assertTrue(i.drainKnown().isEmpty(), "2 and 2: not known");
        i.put(CaveIndex.runs(1, 0, c1, -64, tunnel));        // the chunk between loads: one volume
        int v = i.volumeAt(6, 52, 8);
        assertEquals(v, i.volumeAt(40, 52, 8));
        assertEquals(4, i.volume(v).credits().size(), "the credits followed the union");
        List<CaveIndex.Known> k = i.drainKnown();
        assertEquals(1, k.size(), "4 credits: known");
        assertTrue(i.isKnown(v));
        assertEquals(4, k.get(0).hits().size());
        assertTrue(i.drainKnown().isEmpty(), "said once");
        assertTrue(i.counts().endsWith("rim credits 0, air credits 4, known 1"), i.counts());
        i.remove(0, 0); i.remove(1, 0); i.remove(2, 0);     // the owner teleports away
        assertTrue(i.counts().endsWith("known 0"), i.counts());
        i.put(CaveIndex.runs(0, 0, c0, -64, tunnel));
        i.put(CaveIndex.runs(1, 0, c1, -64, tunnel));
        i.put(CaveIndex.runs(2, 0, c2, -64, tunnel));        // and back
        int w = i.volumeAt(6, 52, 8);
        assertTrue(i.isKnown(w), "known stays known");
        assertEquals(4, i.volume(w).credits().size(), "its credits came back with its chunks (were: air 0)");
        assertTrue(i.counts().endsWith("air credits 4, known 1"), i.counts());
    }

    /**
     * c921e903 review (rule 17): a volume with two passing openings, a rim credit on A only - A is the target, never B
     * (no ray saw B); B's lip stays a ray target, A's does not.
     */
    @Test
    void twoPassingOpenings_aRimCreditOnAOnly_targetsANeverB() {
        int[] cuts = new int[256];
        for (int c = 0; c < 256; c++) cuts[c] = (c & 15) <= 1 || (c & 15) >= 14 ? 50 : SURFACE;
        CaveIndex i = new CaveIndex();                       // tunnel z 8..9, y 51..53, x 2..13; A at x 2, B at x 13; a shaft at x 7..8 to y 40
        i.put(CaveIndex.runs(0, 0, cuts, -64, (x, y, z) -> z >= 8 && z <= 9 && ((x >= 2 && x <= 13 && y >= 51 && y <= 53) || ((x == 7 || x == 8) && y >= 40 && y <= 53))));
        assertTrue(i.isCave(i.volumeAt(5, 52, 8)));
        assertEquals(CaveIndex.Credit.RIM, i.rimCredit(2, 54, 8).what(), "a ray ended on A's lip");
        List<int[]> o = i.openings(7, 64, 8, 64);
        assertEquals(1, o.size(), "only A");
        assertEquals(2, o.get(0)[0], "A at x 2, never B at x 13");
        List<int[]> t = i.targets(7, 64, 8, 64, 400, null);
        assertTrue(t.stream().anyMatch(c -> c[0] == 13 && c[1] == 54), "B's lip is still a ray target");
        assertFalse(t.stream().anyMatch(c -> c[0] == 2 && c[1] == 54), "A's lip seen: no more a target");
    }

    /**
     * The owner's label 733 68 1663 (yes; the index: no cave): a real cave can enter LEVEL and drop further in. The reach
     * is the volume's lowest cell below the opening's lowest cell, wherever in the volume that lies - so a 3-wide level
     * tunnel into a hillside that drops 6 after 6 blocks is a cave; a 3-wide overhang 2 deep with no drop is not.
     */
    @Test
    void aLevelEntryThatDropsFurtherInIsACave_anOverhangWithNoDropIsNot() {
        int[] hill = new int[256];
        for (int c = 0; c < 256; c++) hill[c] = (c & 15) >= 4 ? 70 : 64;          // the hillside from x 4
        CaveIndex level = new CaveIndex();                   // 3 wide (z 4..6), 2 tall (y 64..65), level x 4..9, then a pit x 10..12 down to y 58
        level.put(CaveIndex.runs(0, 0, hill, -64, (x, y, z) -> z >= 4 && z <= 6 && ((x >= 4 && x <= 9 && y >= 64 && y <= 65) || (x >= 10 && x <= 12 && y >= 58 && y <= 65))));
        int v = level.volumeAt(6, 64, 5);
        var s = level.labelSpot(List.of(), null, 2.5, 65.6, 5.5, 4.5);
        assertEquals(6, s.rim(), "the face: 3 wide, 2 tall");
        assertEquals(6, s.reach(), "level at the mouth, 6 down further in");
        assertTrue(level.isCave(v), "a level entry that drops: a cave");
        CaveIndex over = new CaveIndex();                    // 3 wide, 2 deep (x 4..5), 2 tall, no drop
        over.put(CaveIndex.runs(0, 0, hill, -64, (x, y, z) -> z >= 4 && z <= 6 && x >= 4 && x <= 5 && y >= 64 && y <= 65));
        var o = over.labelSpot(List.of(), null, 2.5, 65.6, 5.5, 4.5);
        assertEquals(0, o.reach(), "no drop");
        assertFalse(over.isCave(over.volumeAt(4, 64, 5)), "an overhang 2 deep: no cave");
    }

    /**
     * 94b82de3 review 1 and 3: a known cave's other openings are offered by a STABLE cell (the same wherever the bot
     * stands, so a hold on it keys the opening), and only those that pass the floor (a 1-cell hole is not offered).
     */
    @Test
    void otherOpenings_aStableCellWhereverTheBotStands_onlyThosePassingTheFloor() {
        int[] cuts = new int[256];
        for (int c = 0; c < 256; c++) cuts[c] = (c & 15) <= 1 || (c & 15) >= 14 ? 50 : SURFACE;
        cuts[8 + 16 * 7] = 53;                                // a 1-cell hole over the tunnel at x 8 (z 7 beside z 8)
        CaveIndex i = new CaveIndex();
        i.put(CaveIndex.runs(0, 0, cuts, -64, (x, y, z) -> z >= 8 && z <= 9 && ((x >= 2 && x <= 13 && y >= 51 && y <= 53) || ((x == 7 || x == 8) && y >= 40 && y <= 53))));
        List<int[]> near = i.otherOpenings(2, 52, 8, 0, 64, 0, 3), far = i.otherOpenings(2, 52, 8, 40, 70, 40, 3);
        assertEquals(1, near.size(), "B only: the 1-cell hole fails the floor - " + near.size());
        assertArrayEquals(new int[]{13, 51, 8}, near.get(0), "B's lowest cell");
        assertArrayEquals(near.get(0), far.get(0), "the same cell from anywhere: the hold keys the opening");
    }

    /** The owner's definition, (b) alone: a tunnel with a wide opening that goes only 4 below it is a dip. */
    @Test
    void aWideOpeningThatGoesOnlyFourDownIsADip() {
        int[] holed = new int[256];
        for (int c = 0; c < 256; c++) holed[c] = (c >> 4) == 5 && (c & 15) >= 5 && (c & 15) <= 7 ? 58 : 64;
        CaveIndex h = new CaveIndex();
        h.put(CaveIndex.runs(0, 0, holed, -64, (x, y, z) -> z == 5 && ((x >= 8 && x <= 12 && y >= 58 && y <= 60) || (x == 12 && y >= 54 && y <= 60))));
        assertFalse(h.isCave(h.volumeAt(10, 59, 5)), "4 below its opening: a dip");
    }

    @Test
    void twoRays_notKnown_andTheOtherVolumeUntouched() {
        CaveIndex i = index();
        i.credit(9, 51, 5);
        i.credit(6, 52, 4);
        int a = i.volumeAt(5, 51, 4), b = i.volumeAt(11, 21, 11);
        assertFalse(i.isKnown(a), "two rays: not known");
        assertEquals(2, i.volume(a).credits().size());
        assertTrue(i.volume(b).credits().isEmpty(), "B untouched");
        assertFalse(i.isKnown(b));
        assertEquals(CaveIndex.Credit.CREDITED, i.credit(9, 51, 5).what(), "the same cell again is no new ray");
        assertEquals(2, i.volume(a).credits().size());
    }

    @Test
    void aRayIntoNoVolume_isNoVolume() {
        CaveIndex i = index();
        assertEquals(CaveIndex.Credit.NO_VOLUME, i.credit(40, 51, 40).what(), "a chunk not read (an edge)");
        assertEquals(CaveIndex.Credit.NO_VOLUME, i.credit(5, 30, 8).what(), "solid rock");
        assertEquals(CaveIndex.Credit.NO_VOLUME, i.credit(5, 53, 5).what(), "the roof over A: rock beside air credits nothing");
        assertEquals(CaveIndex.Credit.DIP, i.credit(11, 21, 11).what(), "the closed room B: no opening, no cave");
        assertEquals(CaveIndex.Credit.NO_VOLUME, i.creditRay(CaveIndex.path(0.5, 60.5, 8.5, 1.5, 60.5, 8.5)).what(), "only open sky on the path");
    }

    /** A reload keeps it known: the index is built again; the known cave of Sightings marks its volume known. */
    @Test
    void aReloadKeepsItKnown() {
        CaveIndex i = index();
        seen(i, 9, 51, 5);
        seen(i, 6, 52, 4);
        CaveIndex.Known k = seen(i, 5, 51, 5).known();
        Sightings s = new Sightings();
        assertNotNull(s.knowCave("minecraft:overworld", k, 1000));
        Sightings back = Sightings.fromJson(s.toJson(), new int[1]);
        List<Sightings.KnownCave> kc = back.allKnownCaves("minecraft:overworld");
        assertEquals(1, kc.size());
        assertEquals(k.x(), kc.get(0).x());
        assertEquals(CaveIndex.Size.SMALL, kc.get(0).size());
        assertEquals(CaveIndex.Depth.STONE, kc.get(0).depth());
        assertEquals(3, kc.get(0).hits().size());
        CaveIndex fresh = index();                           // the next session: the index again, no credits
        for (var c : kc) fresh.markKnown(c.x(), c.y(), c.z());
        assertTrue(fresh.isKnown(fresh.volumeAt(5, 51, 4)), "known stays known");
        assertNull(back.knowCave("minecraft:overworld", k, 2000), "never twice");
    }

    /** Two chunks: a tunnel across the edge x 15 / x 16 is one volume; the next chunk unloaded, its part goes. */
    @Test
    void aVolumeAcrossAChunkEdgeIsOne_anUnloadTakesItsPart() {
        int[] surface = new int[256];
        java.util.Arrays.fill(surface, SURFACE);
        CaveIndex.CellTest tunnel = (x, y, z) -> z == 8 && y == 30 && x >= 12 && x <= 19;
        CaveIndex i = new CaveIndex();
        i.put(CaveIndex.runs(0, 0, surface, -64, tunnel));
        assertNotEquals(i.volumeAt(12, 30, 8), i.volumeAt(20, 30, 8), "the next chunk not read yet");
        i.put(CaveIndex.runs(1, 0, surface, -64, tunnel));
        assertEquals(i.volumeAt(12, 30, 8), i.volumeAt(19, 30, 8));
        assertEquals(8, i.volume(i.volumeAt(12, 30, 8)).cells());
        i.remove(1, 0);
        assertEquals(-1, i.volumeAt(19, 30, 8));
        assertEquals(4, i.volume(i.volumeAt(12, 30, 8)).cells(), "the loaded part only");
    }

    /**
     * Memory (the review): the index keeps the loaded chunks' volumes plus the known marks; an unload drops an unknown
     * volume with its credits, a known one is known again when its chunk comes back.
     */
    @Test
    void anUnloadDropsTheUnknownVolumes_theKnownStayKnown() {
        CaveIndex i = index();
        i.credit(9, 51, 5);                                  // A: one credit, unknown
        i.markKnown(11, 21, 11);                             // B: a known cave of Sightings
        assertEquals(1, i.credited().size());
        i.remove(0, 0);
        assertEquals(0, i.chunks());
        assertEquals(-1, i.volumeAt(5, 51, 4));
        assertTrue(i.credited().isEmpty(), "A's credit went with its chunk");
        i.put(chunk());
        assertTrue(i.volume(i.volumeAt(5, 51, 4)).credits().isEmpty(), "A again: no credit carried over");
        assertTrue(i.isKnown(i.volumeAt(11, 21, 11)), "B again: known (a known mark stays)");
    }

    /**
     * The measured size a chunk takes ({@link CaveIndex#approxBytes}): a chunk with no cave air shares its run arrays
     * (1072 bytes: its surface heights); the A + B + D chunk with its 40 runs 2820 bytes (+ 16 bytes a run, + the 257
     * column starts); the union-find 28 bytes a node.
     */
    @Test
    void theBytesAChunkTakes() {
        CaveIndex none = new CaveIndex();
        long base = none.approxBytes();
        none.put(CaveIndex.runs(5, 5, cutSurface(), -64, (x, y, z) -> false));
        assertEquals(1072, none.approxBytes() - base, "no cave air: the surface heights only, the run arrays shared");
        CaveIndex ab = new CaveIndex();
        ab.put(chunk());
        assertEquals(2820, ab.approxBytes() - base, "40 runs");
    }

    /**
     * The openings the explore walks toward (the manager's ruling on J6 (a)): an opening no ray reached is no target (x-ray
     * by proxy); one credit (a ray saw the hole or its rim) makes A's hole in the cut (x 2 at y 50) a target; three make A
     * a known cave (no more an opening). The closed room B never has an opening.
     */
    @Test
    void anOpeningWithNoCreditIsNotAimedAt_oneCreditIs_threeIsAKnownCave() {
        CaveIndex i = index();
        assertTrue(i.openings(0, 64, 0, 64).isEmpty(), "no ray yet: not a target, the explore explores plainly");
        i.credit(9, 51, 5);
        assertTrue(i.openings(0, 64, 0, 64).isEmpty(), "an air credit alone: no target (c921e903 review: the opening itself needs a rim credit)");
        assertEquals(CaveIndex.Credit.RIM, i.rimCredit(2, 53, 4).what(), "a ray ended on the roof over A's opening cell 2 52 4");
        List<int[]> o = i.openings(0, 64, 0, 64);
        assertEquals(CaveIndex.TARGET_RAYS, i.volume(i.volumeAt(5, 51, 4)).credits().size());
        assertEquals(1, o.size(), "A's seen opening is a target (D: no credit; B is closed: no opening)");
        assertEquals(List.of(2, 52, 4), List.of(o.get(0)[0], o.get(0)[1], o.get(0)[2]), "aimed at the credited cell beside the lip");
        assertEquals(CaveIndex.Credit.DIP, i.credit(11, 21, 11).what(), "B closed: no cave, no credit");
        assertEquals(1, i.openings(0, 64, 0, 64).size());
        i.credit(6, 52, 4);
        assertEquals(CaveIndex.Credit.KNOWN, i.credit(5, 51, 4).what(), "three: a known cave");
        assertTrue(i.openings(0, 64, 0, 64).isEmpty(), "known: no more an opening to explore toward");
    }

    /** aa cave list: the known caves nearest first with their size, depth and rays, then the volumes credited so far. */
    @Test
    void theCaveListSaysTheKnownAndTheCredited() {
        CaveIndex i = index();
        i.credit(3, 51, 10);                                 // one ray into D
        seen(i, 9, 51, 5);
        seen(i, 6, 52, 4);
        CaveIndex.Known k = seen(i, 5, 51, 5).known();
        Sightings s = new Sightings();
        s.knowCave("d", k, 1);
        String t = CaveList.text(s.allKnownCaves("d"), i.credited(), "rays: test", 0, 64, 0);
        assertTrue(t.startsWith("known caves: 1\n  " + k.x() + " " + k.y() + " " + k.z() + ": "), t);
        assertTrue(t.contains("size small, depth stone, 3 rays"), t);
        assertTrue(t.contains("credited, not known yet: 1") && t.contains(": 1 of 3 rays (first at 3 51 10)"), t);
        assertTrue(CaveList.text(List.of(), java.util.Map.of(), "r", 0, 0, 0).startsWith("known caves: none yet"));
    }

    /** The size bucket's edges: one minute of walking, and the caving way's cap (cave.exploreMinutes). */
    @Test
    void theSizeBucketsEdges() {
        double walk = 4.317, cap = 10;                       // the walk's speed (GameTimes), the cap's default
        assertEquals(CaveIndex.Size.SMALL, CaveIndex.Size.of(1000, walk, cap), "1000 cells: 0.97 min");
        assertEquals(CaveIndex.Size.MEDIUM, CaveIndex.Size.of(1100, walk, cap), "1100 cells: 1.06 min");
        assertEquals(CaveIndex.Size.LARGE, CaveIndex.Size.of(10400, walk, cap), "10400 cells: 10.04 min");
        assertEquals(1, CaveIndex.Size.SMALL.holdsMinutes(cap));
        assertEquals(5.5, CaveIndex.Size.MEDIUM.holdsMinutes(cap));
        assertEquals(cap, CaveIndex.Size.LARGE.holdsMinutes(cap));
        assertEquals(CaveIndex.Depth.DEEPSLATE, CaveIndex.Depth.of(-1));
        assertEquals(CaveIndex.Depth.STONE, CaveIndex.Depth.of(0));
        assertEquals(CaveIndex.Depth.STONE, CaveIndex.Depth.of(62));
        assertEquals(CaveIndex.Depth.HIGH, CaveIndex.Depth.of(63));
    }

    /** The rays' targets: one per run near the eyes, nearest first, the known left out. */
    @Test
    void targetsAreTheRunsNearTheEyes() {
        CaveIndex i = index();
        List<int[]> t = i.targets(5, 51, 5, 32, 10, null);
        assertFalse(t.isEmpty());
        assertTrue(i.volumeAt(t.get(0)[0], t.get(0)[1], t.get(0)[2]) == i.volumeAt(5, 51, 4), "A's cells first (nearest)");
        i.credit(9, 51, 5);
        i.credit(6, 52, 4);
        i.credit(5, 51, 4);
        for (int[] c : i.targets(5, 51, 5, 32, 100, null)) assertNotEquals(i.volumeAt(5, 51, 4), i.volumeAt(c[0], c[1], c[2]), "a known volume is no target");
    }
}
