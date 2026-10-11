package io.github.mojolowjo.entropybot.motor;

import static org.junit.jupiter.api.Assertions.*;

import io.github.mojolowjo.entropybot.threat.ReachGrid;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 0.27.0 motor: move planning over text grids. */
class MotorPlanTest {

    /** A mutable text grid: [y][z][x]. */
    static final class G {
        final char[][][] c;

        G(int sx, int sy, int sz, int solidLayers) {
            c = new char[sy][sz][sx];
            for (int y = 0; y < sy; y++) for (int z = 0; z < sz; z++) for (int x = 0; x < sx; x++) c[y][z][x] = y < solidLayers ? '#' : '.';
        }

        G set(int x, int y, int z, char ch) {
            c[y][z][x] = ch;
            return this;
        }

        ReachGrid grid() {
            String[][] l = new String[c.length][];
            for (int y = 0; y < c.length; y++) {
                l[y] = new String[c[y].length];
                for (int z = 0; z < c[y].length; z++) l[y][z] = new String(c[y][z]);
            }
            return ReachGrid.parse(l);
        }
    }

    /** 11 x 6 x 11, floor = layers 0 and 1; the bot stands at 5 2 5. */
    static G flat() { return new G(11, 6, 11, 2); }

    static void at(int[] c, int x, int y, int z) { assertArrayEquals(new int[]{x, y, z}, c); }

    @Test
    void backOffWalksAwayFromTheMob() {
        MotorPlan.Plan p = MotorPlan.backOff(flat().grid(), 5, 2, 5, 6.5, 5.5, 2);
        assertTrue(p.ok());
        assertEquals(2, p.cells().size());
        at(p.cells().get(0), 4, 2, 5);
        at(p.cells().get(1), 3, 2, 5);
        assertEquals("clear", p.why());
        assertEquals("back off 2", p.name());
    }

    @Test
    void backOffStopsBeforeADropOfTwo() {
        G g = flat().set(3, 1, 5, '.').set(3, 0, 5, '.');
        MotorPlan.Plan p = MotorPlan.backOff(g.grid(), 5, 2, 5, 6.5, 5.5, 3);
        assertTrue(p.ok());
        assertEquals(1, p.cells().size());
        assertTrue(p.why().contains("drop"), p.why());
    }

    @Test
    void backOffWithADropRightBehindFails() {
        G g = flat().set(4, 1, 5, '.').set(4, 0, 5, '.');
        MotorPlan.Plan p = MotorPlan.backOff(g.grid(), 5, 2, 5, 6.5, 5.5, 2);
        assertFalse(p.ok());
        assertTrue(p.why().startsWith("no standable cell"), p.why());
        assertTrue(p.why().contains("drop"));
    }

    @Test
    void backOffStepsDownOne() {
        G g = flat().set(4, 1, 5, '.');
        MotorPlan.Plan p = MotorPlan.backOff(g.grid(), 5, 2, 5, 6.5, 5.5, 1);
        assertTrue(p.ok());
        at(p.cells().get(0), 4, 1, 5);
    }

    @Test
    void backOffStopsAtAWall() {
        G g = flat().set(4, 2, 5, '#').set(4, 3, 5, '#');
        MotorPlan.Plan p = MotorPlan.backOff(g.grid(), 5, 2, 5, 6.5, 5.5, 2);
        assertFalse(p.ok());
        assertTrue(p.why().contains("wall"), p.why());
    }

    @Test
    void backOffNeverStepsTowardLava() {
        G g = flat().set(4, 1, 5, 'L');
        MotorPlan.Plan p = MotorPlan.backOff(g.grid(), 5, 2, 5, 6.5, 5.5, 2);
        assertFalse(p.ok());
        assertTrue(p.why().contains("lava"), p.why());
    }

    @Test
    void backOffDiagonally() {
        MotorPlan.Plan p = MotorPlan.backOff(flat().grid(), 5, 2, 5, 6.5, 6.5, 2);
        assertTrue(p.ok());
        at(p.cells().get(0), 4, 2, 4);
        at(p.cells().get(1), 3, 2, 3);
    }

    @Test
    void backOffNeverClipsACorner() {
        G g = flat().set(4, 2, 5, '#').set(4, 3, 5, '#');
        MotorPlan.Plan p = MotorPlan.backOff(g.grid(), 5, 2, 5, 6.5, 6.5, 2);
        assertFalse(p.ok());
        assertTrue(p.why().contains("corner"), p.why());
    }

    @Test
    void backOffCapsTheCells() {
        MotorPlan.Plan p = MotorPlan.backOff(flat().grid(), 5, 2, 5, 9.5, 5.5, 50);
        assertTrue(p.cells().size() <= MotorPlan.MAX_CELLS);
    }

    @Test
    void noGridNoMove() {
        assertFalse(MotorPlan.backOff(null, 0, 0, 0, 1, 1, 2).ok());
        assertFalse(MotorPlan.stepTo(null, 0, 0, 0, 1, 0, 0).ok());
        assertFalse(MotorPlan.climb(null, 0, 0, 0, 1, 0).ok());
        assertFalse(MotorPlan.outOfWater(null, 0, 0, 0).ok());
    }

    @Test
    void mobOnTheSameCellGivesNoDirection() {
        MotorPlan.Plan p = MotorPlan.backOff(flat().grid(), 5, 2, 5, 5.5, 5.5, 2);
        assertFalse(p.ok());
    }

    @Test
    void strafeLeftAndRightFacingEast() {
        MotorPlan.Plan l = MotorPlan.strafe(flat().grid(), 5, 2, 5, 7.5, 5.5, true, 1);
        at(l.cells().get(0), 5, 2, 4);      // facing east, the left hand is north
        MotorPlan.Plan r = MotorPlan.strafe(flat().grid(), 5, 2, 5, 7.5, 5.5, false, 1);
        at(r.cells().get(0), 5, 2, 6);
        assertEquals("strafe left 1", l.name());
    }

    @Test
    void stepToOnFlatGround() {
        MotorPlan.Plan p = MotorPlan.stepTo(flat().grid(), 5, 2, 5, 7, 2, 7);
        assertTrue(p.ok());
        assertEquals(4, p.cells().size());
        at(p.end(), 7, 2, 7);
    }

    @Test
    void stepToRefusesFarCells() {
        MotorPlan.Plan p = MotorPlan.stepTo(flat().grid(), 5, 2, 5, 10, 2, 5);
        assertFalse(p.ok());
        assertTrue(p.why().contains("further than 4"));
    }

    @Test
    void stepToRefusesASolidCell() {
        G g = flat().set(6, 2, 5, '#').set(6, 3, 5, '#');
        MotorPlan.Plan p = MotorPlan.stepTo(g.grid(), 5, 2, 5, 6, 2, 5);
        assertFalse(p.ok());
        assertEquals("no standable cell there", p.why());
    }

    @Test
    void stepToGoesAroundAWall() {
        G g = flat();
        for (int z = 4; z <= 6; z++) g.set(6, 2, z, '#').set(6, 3, z, '#');
        MotorPlan.Plan p = MotorPlan.stepTo(g.grid(), 5, 2, 5, 7, 2, 5);
        assertTrue(p.ok(), p.why());
        assertEquals(6, p.cells().size());
        for (int[] c : p.cells()) assertFalse(c[0] == 6 && c[2] >= 4 && c[2] <= 6, "through the wall");
        at(p.end(), 7, 2, 5);
    }

    @Test
    void stepToWalledInFails() {
        G g = flat();
        for (int x = 4; x <= 6; x++) for (int z = 4; z <= 6; z++) if (x != 5 || z != 5) g.set(x, 2, z, '#').set(x, 3, z, '#').set(x, 4, z, '#');
        MotorPlan.Plan p = MotorPlan.stepTo(g.grid(), 5, 2, 5, 7, 2, 7);
        assertFalse(p.ok());
        assertTrue(p.why().startsWith("no walk there"), p.why());
    }

    @Test
    void stepToWhereItStandsIsDone() {
        MotorPlan.Plan p = MotorPlan.stepTo(flat().grid(), 5, 2, 5, 5, 2, 5);
        assertTrue(p.ok());
        assertTrue(p.cells().isEmpty());
    }

    @Test
    void stepToUpOneBlock() {
        G g = flat().set(6, 2, 5, '#');
        MotorPlan.Plan p = MotorPlan.stepTo(g.grid(), 5, 2, 5, 6, 3, 5);
        assertTrue(p.ok());
        assertEquals(1, p.cells().size());
        at(p.end(), 6, 3, 5);
    }

    @Test
    void climbOntoTheBlockAhead() {
        G g = flat().set(6, 2, 5, '#');
        MotorPlan.Plan p = MotorPlan.climb(g.grid(), 5, 2, 5, 1, 0);
        assertTrue(p.ok());
        at(p.end(), 6, 3, 5);
    }

    @Test
    void climbRefusesAFence() {
        G g = flat().set(6, 2, 5, 'F');
        MotorPlan.Plan p = MotorPlan.climb(g.grid(), 5, 2, 5, 1, 0);
        assertFalse(p.ok());
        assertTrue(p.why().contains("fence"));
    }

    @Test
    void climbNeedsHeadRoom() {
        G g = flat().set(6, 2, 5, '#').set(5, 4, 5, '#');
        MotorPlan.Plan p = MotorPlan.climb(g.grid(), 5, 2, 5, 1, 0);
        assertFalse(p.ok());
        assertEquals("no room to jump", p.why());
    }

    @Test
    void climbNeedsABlockAhead() {
        assertFalse(MotorPlan.climb(flat().grid(), 5, 2, 5, 1, 0).ok());
        G g = flat().set(6, 2, 5, '#').set(6, 3, 5, '#');       // two high: no standing on it
        assertFalse(MotorPlan.climb(g.grid(), 5, 2, 5, 1, 0).ok());
    }

    @Test
    void climbOnlyAlongAnAxis() {
        G g = flat().set(6, 2, 6, '#');
        assertEquals("no direction", MotorPlan.climb(g.grid(), 5, 2, 5, 1, 1).why());
    }

    static G pool() {
        G g = flat();
        for (int x = 3; x <= 7; x++) for (int z = 3; z <= 7; z++) g.set(x, 1, z, 'W');
        return g;
    }

    @Test
    void outOfWaterToTheBank() {
        ReachGrid gr = pool().grid();
        MotorPlan.Plan p = MotorPlan.outOfWater(gr, 5, 1, 5);
        assertTrue(p.ok(), p.why());
        assertTrue(p.why().startsWith("to the bank"));
        int[] e = p.end();
        assertNotEquals(ReachGrid.WATER, gr.codeAt(e[0], e[1], e[2]));
        assertTrue(gr.standable(e[0], e[1], e[2]));
        assertEquals(3, p.cells().size());
    }

    @Test
    void outOfWaterOnlyInWater() {
        MotorPlan.Plan p = MotorPlan.outOfWater(flat().grid(), 5, 2, 5);
        assertFalse(p.ok());
        assertEquals("not in water", p.why());
    }

    @Test
    void outOfWaterSwimsUpWithoutABank() {
        G g = new G(3, 6, 3, 6);        // all solid
        for (int y = 1; y <= 3; y++) g.set(1, y, 1, 'W');
        g.set(1, 4, 1, '.');
        MotorPlan.Plan p = MotorPlan.outOfWater(g.grid(), 1, 1, 1);
        assertTrue(p.ok(), p.why());
        assertTrue(p.why().contains("swim up"));
        at(p.end(), 1, 4, 1);
    }

    @Test
    void outOfWaterTrappedUnderAFloor() {
        G g = new G(3, 4, 3, 4);
        g.set(1, 1, 1, 'W');
        MotorPlan.Plan p = MotorPlan.outOfWater(g.grid(), 1, 1, 1);
        assertFalse(p.ok());
    }

    @Test
    void holdIsAlwaysAPlan() {
        MotorPlan.Plan p = MotorPlan.hold("phantom");
        assertTrue(p.ok());
        assertEquals(MotorPlan.Kind.HOLD, p.kind());
        assertNull(p.end());
    }

    @Test
    void eightDirections() {
        assertArrayEquals(new int[]{1, 0}, MotorPlan.dir8(1, 0));
        assertArrayEquals(new int[]{1, 1}, MotorPlan.dir8(1, 1));
        assertArrayEquals(new int[]{0, -1}, MotorPlan.dir8(0, -1));
        assertArrayEquals(new int[]{-1, 0}, MotorPlan.dir8(-2, 0.3));
        assertArrayEquals(new int[]{0, 0}, MotorPlan.dir8(0, 0));
    }

    @Test
    void cellKeysRoundTrip() {
        for (int[] c : List.of(new int[]{0, 0, 0}, new int[]{-1234, -64, 98765}, new int[]{29_000_000, 319, -29_000_000})) {
            assertArrayEquals(c, MotorPlan.unkey(MotorPlan.key(c[0], c[1], c[2])));
        }
    }

    @Test
    void worldOffsetsAreHonoured() {
        G g = flat();
        String[][] l = new String[g.c.length][];
        for (int y = 0; y < g.c.length; y++) {
            l[y] = new String[g.c[y].length];
            for (int z = 0; z < g.c[y].length; z++) l[y][z] = new String(g.c[y][z]);
        }
        ReachGrid base = ReachGrid.parse(l);
        ReachGrid off = new ReachGrid(base.sx, base.sy, base.sz, 100, 60, -200);
        System.arraycopy(base.codes, 0, off.codes, 0, base.codes.length);
        MotorPlan.Plan p = MotorPlan.backOff(off, 105, 62, -195, 106.5, -194.5, 1);
        assertTrue(p.ok());
        at(p.end(), 104, 62, -195);
    }
}
