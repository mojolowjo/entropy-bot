package io.github.mojolowjo.entropybot.clear;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/** The sim's "veins" scenario (findVeins: vein size, reach, the mapped area, gravel above, torches) and holeFills. */
class VeinsTest {
    static List<Pos> veins(FakeWorld w, ClearBox... boxes) {
        return Veins.findVeins(w, List.of(boxes), null, SimWorlds.MAP_AREA);
    }

    static ClearBox B(int x1, int x2, int y1, int y2, int z1, int z2) {
        return ClearBox.of(x1, y1, z1, x2, y2, z2);
    }

    @Test
    void theSimsVeinsScenario() {
        FakeWorld w = new FakeWorld();
        // 1) a slab of ore beside a branch at the edge of the mapped area (z -64): only z -63 and -64 are inside it
        ClearBox branch1 = B(1, 12, 40, 41, -62, -62);
        w.fill(1, 12, 40, 41, -62, -62, "air");
        w.fill(1, 12, 40, 41, -66, -63, "iron_ore");
        List<Pos> v = veins(w, branch1);
        assertEquals(24, v.size(), "one joined vein is kept to its 24 nearest blocks");
        assertTrue(v.stream().allMatch(c -> c.z() >= -64), "nothing outside the mapped area (z < -64)");
        assertEquals(v.size(), new HashSet<>(v).size(), "...and no second vein is started from the same slab");
        // the same 24 as the sim, in its order
        assertEquals("-63,-63,-63,-64,-63,-63,-64,-64,-63,-63,-64,-64,-63,-63,-64,-64,-63,-63,-64,-64,-63,-63,-64,-64",
                v.stream().map(c -> String.valueOf(c.z())).collect(Collectors.joining(",")));
        // 2) a vein running away from a corridor: only the first 4 blocks are within 4 of the dug boxes
        ClearBox corridor = B(100, 100, 40, 41, 0, 10);
        w.fill(100, 100, 40, 41, 0, 10, "air");
        w.fill(101, 110, 40, 40, 5, 5, "iron_ore");
        v = veins(w, corridor);
        assertEquals(4, v.size());
        assertTrue(v.stream().allMatch(c -> c.x() >= 101 && c.x() <= 104), "a long vein stops 4 blocks from the branch");
        // 3) sand or gravel above an ore: left alone (it would fall into the tunnel)
        w.set(101, 40, 8, "coal_ore");
        w.set(101, 41, 8, "gravel");
        v = veins(w, corridor);
        assertTrue(v.stream().noneMatch(c -> c.z() == 8), "an ore with gravel above it is skipped");
        // 4) an ore beside a torch in the dug box counts as exposed; one touching the box is found; one buried far away is not
        w.set(100, 40, 2, "torch");
        w.set(101, 40, 2, "coal_ore");
        w.set(101, 40, 0, "diamond_ore");
        w.set(130, 40, 5, "diamond_ore");
        v = veins(w, corridor);
        assertTrue(v.contains(new Pos(101, 40, 2)), "an ore next to a torch cell counts as exposed");
        assertTrue(v.contains(new Pos(101, 40, 0)), "an ore touching the dug box is found");
        assertTrue(v.stream().noneMatch(c -> c.x() == 130), "an ore far from every box is not");
        assertEquals(6, v.size());
        // 5) a branch outside the area gets nothing
        ClearBox outside = B(300, 300, 40, 41, 0, 5);
        w.fill(300, 300, 40, 41, 0, 5, "air");
        w.set(301, 40, 2, "iron_ore");
        assertEquals(List.of(), veins(w, outside), "outside the mapped area: no vein");
        // 6) veins are found next to any of the boxes
        w.fill(200, 200, 40, 41, 0, 3, "air");
        w.fill(200, 205, 40, 41, 3, 3, "air");
        w.set(203, 40, 4, "coal_ore");
        w.set(199, 40, 1, "coal_ore");
        v = veins(w, B(200, 200, 40, 41, 0, 3), B(200, 205, 40, 41, 3, 3));
        assertEquals(List.of(new Pos(199, 40, 1), new Pos(203, 40, 4)), v, "ores beside either box are found");
    }

    @Test
    void theOreListLeavesOtherOres() {
        FakeWorld w = new FakeWorld();
        w.fill(0, 0, 40, 41, 0, 5, "air");
        w.set(1, 40, 1, "coal_ore");
        w.set(1, 40, 3, "iron_ore");
        w.set(1, 40, 4, "deepslate_iron_ore");
        w.set(-1, 41, 2, "chest");                 // never a vein block (and not an ore)
        List<Pos> v = Veins.findVeins(w, List.of(B(0, 0, 40, 41, 0, 5)), id -> id.endsWith("iron_ore"), SimWorlds.MAP_AREA);
        assertEquals(List.of(new Pos(1, 40, 3), new Pos(1, 40, 4)), v);
    }

    @Test
    void boxGap() {
        List<ClearBox> boxes = List.of(B(0, 0, 40, 41, 0, 10), B(0, 12, 40, 41, 10, 10));
        assertEquals(0, Veins.boxGap(boxes, 0, 40, 5));
        assertEquals(4, Veins.boxGap(boxes, 4, 40, 5));
        assertEquals(1, Veins.boxGap(boxes, 5, 39, 11));
        assertEquals(999, Veins.boxGap(List.of(), 0, 0, 0));
    }

    /** The "strip pit" vein: two coal ores under branch 1 left's floor; filled deepest first, from the walkway. */
    @Test
    void holeFillsDeepestFirstFromTheWalkway() {
        List<ClearBox> boxes = List.of(B(0, 0, 40, 41, -3, -1), B(-12, -1, 40, 41, -3, -3), B(1, 12, 40, 41, -3, -3));
        List<Pos> veins = List.of(new Pos(-4, 39, -3), new Pos(-4, 38, -3), new Pos(5, 40, -4));
        assertEquals(40, Veins.feetY(boxes));
        List<Veins.Fill> fills = Veins.holeFills(boxes, veins, 40);
        assertEquals(2, fills.size(), "only the cells below the walkway");
        assertEquals(new Pos(-4, 38, -3), fills.get(0).pos(), "deepest first");
        assertEquals(new Pos(-4, 39, -3), fills.get(1).pos());
        for (Veins.Fill f : fills) {
            assertEquals("minecraft:cobblestone", f.item());
            assertTrue(f.optional());
            assertEquals(new Pos(-3, 40, -3), f.from(), "a walkway cell beside the hole");
        }
        // a hole beside another hole: never stand over a hole; no walkway beside it at all: null
        List<Veins.Fill> two = Veins.holeFills(boxes, List.of(new Pos(-4, 39, -3), new Pos(-3, 39, -3)), 40);
        assertEquals(new Pos(-2, 40, -3), two.get(1).from());
        assertEquals(new Pos(-5, 40, -3), two.get(0).from());
        assertNull(Veins.holeFills(boxes, List.of(new Pos(-4, 39, -6)), 40).get(0).from());
    }

    @Test
    void oreDigOptions() {
        List<Pos> v = List.of(new Pos(1, 39, 0), new Pos(1, 40, 0));
        ClearJob j = ClearJob.start(Veins.oreDigOptions(v, 40, List.of(new Pos(9, 9, 9)), 3), null, null);
        assertEquals("mining 2 ore blocks next to branch 3", j.label);
        assertTrue(j.collect && j.soft && !j.keepOres);
        assertEquals(40, j.minStandY);
        assertEquals(ClearBox.of(1, 39, 0, 1, 40, 0), j.box);
        assertEquals(2, j.only.size());
    }
}
