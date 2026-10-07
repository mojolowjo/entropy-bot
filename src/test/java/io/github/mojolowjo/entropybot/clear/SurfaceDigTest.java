package io.github.mojolowjo.entropybot.clear;

import io.github.mojolowjo.entropybot.surface.SurfaceColumns;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 0.19.5: RelCoord, the surface dig's block list and the dig words for the new forms. */
class SurfaceDigTest {
    // ---- RelCoord ----

    @Test
    void relCoord() {
        assertEquals(64, RelCoord.parse("~", 64));
        assertEquals(59, RelCoord.parse("~-5", 64));
        assertEquals(67, RelCoord.parse("~+3", 64));
        assertEquals(67, RelCoord.parse("~3", 64));
        assertEquals(-12, RelCoord.parse("-12", 64));
        assertEquals(7, RelCoord.parse("7", 64));
        assertNull(RelCoord.parse("~x", 0));
        assertNull(RelCoord.parse("~~", 0));
        assertNull(RelCoord.parse("~++1", 0));
        assertNull(RelCoord.parse("abc", 0));
        assertNull(RelCoord.parse("", 0));
        assertNull(RelCoord.parse(null, 0));
    }

    // ---- the block list ----

    /** A fake world: GROUND up to a height per column, extra blocks by key. */
    static final class World implements SurfaceDig.Source {
        final Map<String, Integer> kinds = new HashMap<>();
        final Map<String, Boolean> liquids = new HashMap<>();
        int ground = 64;
        final Map<String, Integer> heights = new HashMap<>();

        int h(int x, int z) { return heights.getOrDefault(x + "," + z, ground); }

        void put(int x, int y, int z, int kind) { kinds.put(x + "," + y + "," + z, kind); }

        @Override public int kind(int x, int y, int z) {
            Integer k = kinds.get(x + "," + y + "," + z);
            if (k != null) return k;
            return y <= h(x, z) ? SurfaceColumns.GROUND : SurfaceColumns.AIR;
        }

        @Override public boolean liquid(int x, int y, int z) { return liquids.getOrDefault(x + "," + y + "," + z, false); }

        @Override public int top(int x, int z) { return 100; }
    }

    @Test
    void flatGroundDown() {
        World w = new World();
        SurfaceDig.Plan p = SurfaceDig.build(w, 0, 0, 1, 1, false, 3, -64, 319);
        assertNull(p.error());
        assertEquals(4, p.columns());
        assertEquals(12, p.blocks().size());
        assertTrue(p.blocks().contains(new Pos(0, 64, 0)));
        assertTrue(p.blocks().contains(new Pos(0, 62, 0)));
        assertFalse(p.blocks().contains(new Pos(0, 61, 0)));
        // down 1: just the surface block
        assertEquals(List.of(new Pos(5, 64, 5)), SurfaceDig.build(w, 5, 5, 5, 5, false, 1, -64, 319).blocks());
    }

    @Test
    void hillFollowsEachColumn() {
        World w = new World();
        w.heights.put("1,0", 70);
        SurfaceDig.Plan p = SurfaceDig.build(w, 0, 0, 1, 0, false, 2, -64, 319);
        assertTrue(p.blocks().contains(new Pos(1, 70, 0)));
        assertTrue(p.blocks().contains(new Pos(1, 69, 0)));
        assertTrue(p.blocks().contains(new Pos(0, 64, 0)));
        assertEquals(4, p.blocks().size());
    }

    @Test
    void waterIsTheSurfaceButNeverDug() {
        World w = new World();
        w.put(0, 65, 0, SurfaceColumns.GROUND);
        w.liquids.put("0,65,0", true);
        SurfaceDig.Plan p = SurfaceDig.build(w, 0, 0, 0, 0, false, 2, -64, 319);
        assertEquals(List.of(new Pos(0, 64, 0)), p.blocks(), "the water block is the surface, the block under it is dug");
    }

    @Test
    void plantsSkippedLeavesOnlyForUp() {
        World w = new World();
        w.put(0, 65, 0, SurfaceColumns.PLANT);
        w.put(0, 66, 0, SurfaceColumns.LEAVES);
        w.put(0, 67, 0, SurfaceColumns.LEAVES);
        SurfaceDig.Plan down = SurfaceDig.build(w, 0, 0, 0, 0, false, 1, -64, 319);
        assertEquals(List.of(new Pos(0, 64, 0)), down.blocks(), "plants and leaves are not the surface");
        SurfaceDig.Plan up = SurfaceDig.build(w, 0, 0, 0, 0, true, 3, -64, 319);
        assertEquals(List.of(new Pos(0, 66, 0), new Pos(0, 67, 0)), up.blocks(), "up: leaves count, the plant doesn't");
    }

    @Test
    void nClampedAndCounted() {
        World w = new World();
        assertEquals(64, SurfaceDig.build(w, 0, 0, 0, 0, false, 500, -64, 319).blocks().size());
        assertEquals(1, SurfaceDig.build(w, 0, 0, 0, 0, false, 0, -64, 319).blocks().size());
        assertEquals(64, SurfaceDig.clampN(99));
        assertEquals(1, SurfaceDig.clampN(-3));
        // the real count (what confirm and the area check see): 11x11 columns, down 10
        assertEquals(1210, SurfaceDig.build(w, 0, 0, 10, 10, false, 10, -64, 319).blocks().size());
        assertNotNull(SurfaceDig.build(w, 0, 0, 100, 100, false, 1, -64, 319).error(), "too many columns");
        // the gate's upper bound for the same line
        assertEquals(1210, io.github.mojolowjo.entropybot.commands.ConfirmGateAccess.volume("0 0 10 10 -10"));
    }

    @Test
    void bounds() {
        ClearBox b = SurfaceDig.bounds(List.of(new Pos(1, 5, 3), new Pos(-2, 9, 0)));
        assertEquals(ClearBox.of(-2, 5, 0, 1, 9, 3).toString(), b.toString());
        assertNull(SurfaceDig.bounds(List.of()));
    }

    // ---- the words ----

    @Test
    void surfaceFormWithTrailingWords() {
        DigArgs a = DigArgs.parse("1 2 3 4 -3 ores", new int[] {0, 0, 0});
        assertNotNull(a);
        assertTrue(a.surfaceForm());
        assertEquals("down", a.surface());
        assertEquals(3, a.depth());
        assertTrue(a.ores());
        assertArrayEquals(new int[] {1, 2, 3, 4}, a.n());
        DigArgs b = DigArgs.parse("~-8 ~-8 ~8 ~8 +10 junk drop water large", new int[] {100, 64, -50});
        assertNotNull(b);
        assertEquals("up", b.surface());
        assertArrayEquals(new int[] {92, -58, 108, -42}, b.n());
        assertTrue(b.junkDrop() && b.water() && b.large());
        assertEquals(64, DigArgs.parse("0 0 1 1 -200", null).depth());
        assertNull(DigArgs.parse("0 0 1 1 -x", null));
        assertNull(DigArgs.parse("0 0 1 1", null));
        assertNull(DigArgs.parse("0 0 1 1 sideways 3", null));
    }

    @Test
    void boxFormWithTilde() {
        DigArgs a = DigArgs.parse("~-2 ~ ~-2 ~2 ~-5 ~2 ores", new int[] {10, 64, 20});
        assertNotNull(a);
        assertFalse(a.surfaceForm());
        assertArrayEquals(new int[] {8, 64, 18, 12, 59, 22}, a.n());
        assertTrue(a.ores());
        assertArrayEquals(new int[] {1, 2, 3, 4, 5, 6}, DigArgs.parse("1 2 3 4 5 6").n());
        assertNull(DigArgs.parse("~a 1 2 3 4 5", null));
    }
}
