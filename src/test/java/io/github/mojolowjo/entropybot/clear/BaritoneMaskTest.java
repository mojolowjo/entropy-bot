package io.github.mojolowjo.entropybot.clear;

import io.github.mojolowjo.entropybot.clear.BaritoneMask.Verdict;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 0.29.0: the per-cell rules Baritone's builder gets for "dig ... via baritone" (the only filter its click has). */
class BaritoneMaskTest {
    static final ClearBox BOX = ClearBox.of(0, 50, 0, 3, 52, 3);

    static BaritoneMask mask(boolean force, boolean keepOres) {
        return new BaritoneMask(BOX, force, keepOres, null);
    }

    @Test
    void plainStoneInsideIsDug() {
        FakeWorld w = new FakeWorld();
        assertTrue(mask(false, true).allows(w, 1, 51, 1));
        assertEquals(Verdict.DIG, mask(false, true).verdict(w, 1, 51, 1));
    }

    @Test
    void chestRefusedEvenWithForce() {
        FakeWorld w = new FakeWorld();
        w.set(1, 51, 1, "chest");
        assertFalse(mask(false, true).allows(w, 1, 51, 1));
        assertFalse(mask(true, true).allows(w, 1, 51, 1), "block entities never go, force or not");
        assertEquals(Verdict.PROTECTED, mask(true, true).verdict(w, 1, 51, 1));
    }

    @Test
    void plankRefusedUnlessForce() {
        FakeWorld w = new FakeWorld();
        w.set(2, 51, 2, "oak_planks");
        assertFalse(mask(false, true).allows(w, 2, 51, 2));
        assertTrue(mask(true, true).allows(w, 2, 51, 2));
    }

    @Test
    void outsideTheBoxIsNeverPartOfIt() {
        FakeWorld w = new FakeWorld();
        BaritoneMask m = mask(false, true);
        assertFalse(m.allows(w, 4, 51, 1));
        assertFalse(m.allows(w, 1, 49, 1));
        assertEquals(Verdict.OUTSIDE, m.verdict(w, -1, 51, 0));
        assertEquals(0, m.refused, "outside is not a refusal");
    }

    @Test
    void airAndLiquidsAreEmpty() {
        FakeWorld w = new FakeWorld();
        w.set(1, 52, 1, "air");
        w.set(2, 52, 2, "water");
        BaritoneMask m = mask(false, true);
        assertEquals(Verdict.EMPTY, m.verdict(w, 1, 52, 1));
        assertEquals(Verdict.EMPTY, m.verdict(w, 2, 52, 2));
        assertFalse(m.allows(w, 2, 52, 2));
    }

    @Test
    void safeAreaOrProtectBoxRefused() {
        FakeWorld w = new FakeWorld();
        BaritoneMask m = new BaritoneMask(BOX, false, true, (x, y, z) -> x == 0 ? "protect box home" : null);
        assertFalse(m.allows(w, 0, 51, 1));
        assertEquals(Verdict.FENCED, m.verdict(w, 0, 51, 1));
        assertTrue(m.allows(w, 1, 51, 1));
    }

    @Test
    void waterNeighbourRefused() {
        FakeWorld w = new FakeWorld();
        w.set(1, 52, 1, "water");                       // above 1 51 1
        w.set(3, 51, 2, "water");                       // beside 2 51 2
        BaritoneMask m = mask(false, true);
        assertEquals(Verdict.WATER, m.verdict(w, 1, 51, 1));
        assertEquals(Verdict.WATER, m.verdict(w, 2, 51, 2));
        assertFalse(m.allows(w, 1, 51, 1));
        w.set(1, 49, 0, "water");                       // below 1 50 0: not a neighbour that floods in
        assertEquals(Verdict.DIG, m.verdict(w, 1, 50, 0));
    }

    @Test
    void oresKeptOrTaken() {
        FakeWorld w = new FakeWorld();
        w.set(1, 50, 1, "iron_ore");
        assertEquals(Verdict.ORE, mask(false, true).verdict(w, 1, 50, 1));
        assertFalse(mask(false, true).allows(w, 1, 50, 1));
        assertTrue(mask(false, false).allows(w, 1, 50, 1), "with ores the ore goes too");
    }

    @Test
    void hazardRefused() {
        FakeWorld w = new FakeWorld();
        w.set(2, 50, 2, "magma_block");
        assertEquals(Verdict.HAZARD, mask(false, true).verdict(w, 2, 50, 2));
        assertFalse(mask(false, true).allows(w, 2, 50, 2));
    }

    @Test
    void bedrockAndAvoidList() {
        FakeWorld w = new FakeWorld();
        w.set(0, 50, 0, "bedrock");
        w.set(3, 50, 3, "gravel");
        w.avoid.add("gravel");
        assertEquals(Verdict.UNBREAKABLE, mask(false, true).verdict(w, 0, 50, 0));
        assertEquals(Verdict.PROTECTED, mask(true, true).verdict(w, 3, 50, 3));
    }

    @Test
    void exceptionRefusesAndCounts() {
        FakeWorld w = new FakeWorld();
        BaritoneMask m = new BaritoneMask(BOX, false, true, (x, y, z) -> {
            if (x == 2) throw new IllegalStateException("chunk gone");
            return null;
        });
        assertFalse(m.allows(w, 2, 51, 2));
        assertFalse(m.allows(w, 2, 51, 1));
        assertTrue(m.allows(w, 1, 51, 1));
        assertEquals(2, m.errors);
        assertEquals(2, m.refused);
        assertEquals(3, m.calls);
        assertNotNull(m.firstError);
        assertTrue(m.firstError.contains("chunk gone"));
        assertTrue(m.counters().contains("mask errors 2"));
    }

    @Test
    void leftoverCountsByReasonAndFindsTheWater() {
        FakeWorld w = new FakeWorld();
        w.fill(0, 3, 50, 52, 0, 3, "air");
        w.set(0, 50, 0, "chest");
        w.set(1, 50, 0, "oak_planks");
        w.set(2, 50, 0, "stone");                       // left: unreachable
        w.set(3, 50, 3, "stone");
        w.set(3, 51, 3, "water");                       // above it
        w.set(1, 50, 2, "coal_ore");
        BaritoneMask m = mask(false, true);
        BaritoneMask.Leftover l = m.leftover(w);
        assertEquals(2, l.count(Verdict.PROTECTED));
        assertEquals(1, l.unreachable());
        assertEquals(1, l.count(Verdict.WATER));
        assertEquals(1, l.count(Verdict.ORE));
        assertEquals(new Pos(3, 51, 3), l.liquid);
        assertEquals("water", l.liquidKind);
        assertTrue(l.waterCells.containsKey("3 50 3"));
        assertEquals(new Pos(2, 50, 0), l.examples.get(Verdict.DIG).get(0));
    }

    @Test
    void digCellsIsTheStartSnapshot() {
        FakeWorld w = new FakeWorld();
        w.fill(0, 3, 51, 52, 0, 3, "air");               // only the bottom layer is stone
        w.set(0, 50, 0, "chest");
        BaritoneMask m = mask(false, true);
        var cells = m.digCells(w);
        assertEquals(15, cells.size());
        assertTrue(cells.contains(BaritoneMask.packed(3, 50, 3)));
        assertFalse(cells.contains(BaritoneMask.packed(0, 50, 0)));
    }

    @Test
    void packedKeepsNegativeCoordinates() {
        long k = BaritoneMask.packed(-20, -60, -300);
        assertEquals(-20, (int) (k >> 38));
        assertEquals(-300, (int) ((k << 26) >> 38));
        assertEquals(-60, (int) ((k << 52) >> 52));
    }
}
