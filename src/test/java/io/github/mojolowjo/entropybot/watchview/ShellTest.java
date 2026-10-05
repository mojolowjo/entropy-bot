package io.github.mojolowjo.entropybot.watchview;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Camera v2's shell builder: known cells in, faces out; never a face the bot could not have seen. */
class ShellTest {
    static long[] keys(List<int[]> cells) {
        long[] a = new long[cells.size()];
        for (int i = 0; i < a.length; i++) a[i] = CellKey.of(cells.get(i)[0], cells.get(i)[1], cells.get(i)[2]);
        return a;
    }

    /** A 1x2 tunnel along +x, 5 long, at y 10-11, z 0: dug and known. */
    static GridWorld tunnel() {
        return new GridWorld().openBox(0, 10, 0, 4, 11, 0);
    }

    static long[] tunnelCells() {
        long[] a = new long[10];
        int i = 0;
        for (int x = 0; x <= 4; x++) for (int y = 10; y <= 11; y++) a[i++] = CellKey.of(x, y, 0);
        return a;
    }

    @Test
    void aTunnelsWallsFloorCeilingAndEnds() {
        Shell.Result r = Shell.build(tunnelCells(), tunnel(), 0, 10, 0, 100_000);
        // per column of 2: north 2 + south 2 + floor 1 + ceiling 1 = 6, five columns = 30, plus the two ends 2 + 2
        assertEquals(34, r.faces().size());
        assertFalse(r.capped());
        assertEquals(10, r.cellsUsed());
    }

    @Test
    void everyFaceIsASolidBlockFacingAKnownOpenCell() {
        GridWorld w = tunnel();
        long[] cells = tunnelCells();
        Set<Long> known = new HashSet<>();
        for (long k : cells) known.add(k);
        for (Shell.Face f : Shell.build(cells, w, 0, 10, 0, 100_000).faces()) {
            assertEquals(Shell.SOLID, w.kind(f.x(), f.y(), f.z()), "the face's block is solid: " + f);
            assertTrue(known.contains(CellKey.of(f.airX(), f.airY(), f.airZ())), "it faces a known cell: " + f);
            assertEquals(Shell.OPEN, w.kind(f.airX(), f.airY(), f.airZ()));
        }
    }

    @Test
    void anOreOneBlockBehindTheWallIsNeverProduced() {
        // the ore sits at 2 10 -2: the wall block 2 10 -1 is between it and the tunnel
        for (Shell.Face f : Shell.build(tunnelCells(), tunnel(), 0, 10, 0, 100_000).faces())
            assertFalse(f.x() == 2 && f.y() == 10 && f.z() == -2, "no x-ray: " + f);
        // while the wall itself is drawn
        assertTrue(Shell.build(tunnelCells(), tunnel(), 0, 10, 0, 100_000).faces().contains(new Shell.Face(2, 10, -1, 3)));
    }

    @Test
    void anUnknownCaveNextToTheTunnelGetsNoFacesAndShowsNothingInside() {
        GridWorld w = tunnel();
        w.openBox(0, 10, 2, 4, 14, 6);                      // a cave beyond the wall at z 1, never opened
        w.open(2, 10, 1);                                   // ...and now the wall has a hole into it
        for (Shell.Face f : Shell.build(tunnelCells(), w, 0, 10, 0, 100_000).faces()) {
            assertTrue(f.z() <= 1 && f.z() >= -1, "only faces round the tunnel itself: " + f);
            assertFalse(f.x() == 2 && f.y() == 10 && f.z() == 1, "the hole is open: no face");
        }
    }

    @Test
    void noFaceBetweenTwoKnownCells() {
        for (Shell.Face f : Shell.build(tunnelCells(), tunnel(), 0, 10, 0, 100_000).faces())
            assertNotEquals(Shell.OPEN, tunnel().kind(f.x(), f.y(), f.z()), f.toString());
    }

    @Test
    void aFilledKnownCellIsAWallNow() {
        GridWorld w = tunnel().solid(2, 10, 0);             // the bot filled the floor cell at x 2
        List<Shell.Face> faces = Shell.build(tunnelCells(), w, 0, 10, 0, 100_000).faces();
        assertTrue(faces.contains(new Shell.Face(2, 10, 0, 1)), "its top faces the open cell above");
        assertTrue(faces.contains(new Shell.Face(2, 10, 0, 4)), "its west side faces the open cell at x 1");
        for (Shell.Face f : faces) assertFalse(f.airX() == 2 && f.airY() == 10 && f.airZ() == 0, "nothing from inside the fill");
    }

    @Test
    void notLoadedMeansNoFace() {
        GridWorld w = tunnel();
        w.unknown.add(CellKey.of(4, 10, -1));
        w.unknown.add(CellKey.of(4, 11, 0));
        List<Shell.Face> faces = Shell.build(tunnelCells(), w, 0, 10, 0, 100_000).faces();
        assertFalse(faces.contains(new Shell.Face(4, 10, -1, 3)));
        // the not-loaded wall face, and the 4 solid faces round the not-loaded known cell (north, south, ceiling, end)
        assertEquals(34 - 1 - 4, faces.size());
    }

    @Test
    void theCapKeepsTheNearestCells() {
        Shell.Result r = Shell.build(tunnelCells(), tunnel(), 0, 10, 0, 7);
        assertTrue(r.capped());
        assertEquals(7, r.faces().size());
        for (Shell.Face f : r.faces()) assertTrue(f.airX() <= 1, "the nearest cells first: " + f);
    }

    /** Random caves: no face ever borders a cell outside the known set, and every known-to-solid boundary is there. */
    @Test
    void randomWorldsNeverShowMoreThanTheShell() {
        Random rnd = new Random(7);
        for (int round = 0; round < 40; round++) {
            GridWorld w = new GridWorld();
            for (int i = 0; i < 300; i++) w.open(rnd.nextInt(12), rnd.nextInt(12), rnd.nextInt(12));
            Set<Long> known = new HashSet<>();
            for (long k : w.open) if (rnd.nextBoolean()) known.add(k);
            long[] cells = known.stream().mapToLong(Long::longValue).toArray();
            List<Shell.Face> faces = Shell.build(cells, w, 6, 6, 6, 1_000_000).faces();
            Set<Shell.Face> got = new HashSet<>(faces);
            assertEquals(faces.size(), got.size(), "no face twice");
            int expected = 0;
            for (long k : known)
                for (int[] o : Shell.OFF)
                    if (w.kind(CellKey.x(k) + o[0], CellKey.y(k) + o[1], CellKey.z(k) + o[2]) == Shell.SOLID) expected++;
            assertEquals(expected, faces.size());
            for (Shell.Face f : faces) assertTrue(known.contains(CellKey.of(f.airX(), f.airY(), f.airZ())));
        }
    }
}
