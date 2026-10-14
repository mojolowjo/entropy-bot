package io.github.mojolowjo.entropybot.clear;

import io.github.mojolowjo.entropybot.clear.BaritoneMask.Verdict;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
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
        assertEquals(0, m.refused.get(), "outside is not a refusal");
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
        assertEquals(2, m.errors.get());
        assertEquals(2, m.refused.get());
        assertEquals(3, m.calls.get());
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

    /** FakeWorld with waterlogged cells: a block that also holds water. */
    static ClearWorld waterlogged(FakeWorld f, java.util.Set<Long> wet) {
        return new ClearWorld() {
            boolean wet(int x, int y, int z) { return wet.contains(BaritoneMask.packed(x, y, z)); }
            @Override public String id(int x, int y, int z) { return f.id(x, y, z); }
            @Override public boolean air(int x, int y, int z) { return f.air(x, y, z); }
            @Override public boolean fluid(int x, int y, int z) { return wet(x, y, z) || f.fluid(x, y, z); }
            @Override public String fluidKind(int x, int y, int z) { return wet(x, y, z) ? "water" : f.fluidKind(x, y, z); }
            @Override public boolean blockEntity(int x, int y, int z) { return f.blockEntity(x, y, z); }
            @Override public boolean unbreakable(int x, int y, int z) { return f.unbreakable(x, y, z); }
            @Override public boolean builtBlock(int x, int y, int z) { return f.builtBlock(x, y, z); }
            @Override public boolean avoided(int x, int y, int z) { return f.avoided(x, y, z); }
            @Override public boolean ore(int x, int y, int z) { return f.ore(x, y, z); }
            @Override public boolean noCollision(int x, int y, int z) { return f.noCollision(x, y, z); }
            @Override public boolean fullBlock(int x, int y, int z) { return f.fullBlock(x, y, z); }
            @Override public double collisionHeight(int x, int y, int z) { return f.collisionHeight(x, y, z); }
            @Override public int blockLight(int x, int y, int z) { return f.blockLight(x, y, z); }
            @Override public Hit clip(double fx, double fy, double fz, double tx, double ty, double tz) { return f.clip(fx, fy, fz, tx, ty, tz); }
        };
    }

    @Test
    void waterloggedBlockIsWaterNotEmpty() {
        FakeWorld f = new FakeWorld();
        f.fill(0, 3, 50, 52, 0, 3, "air");
        f.set(1, 50, 1, "cobblestone_wall");             // stands there, holds water
        ClearWorld w = waterlogged(f, java.util.Set.of(BaritoneMask.packed(1, 50, 1)));
        BaritoneMask m = mask(true, true);
        assertEquals(Verdict.WATER, m.verdict(w, 1, 50, 1));
        assertFalse(m.allows(w, 1, 50, 1));
        BaritoneMask.Leftover l = m.leftover(w);
        assertEquals(1, l.count(Verdict.WATER));
        assertEquals(new Pos(1, 50, 1), l.liquid, "the waterlogged cell itself is the water");
        assertFalse(BuilderReport.text("d", 0, l, BuilderReport.End.IDLE, 0).startsWith("done"));
        f.set(2, 50, 2, "water");                        // a plain water block stays EMPTY
        assertEquals(Verdict.EMPTY, m.verdict(w, 2, 50, 2));
    }

    @Test
    void partOfReadsOnlyTheSnapshotWithTheOriginOffset() {
        FakeWorld w = new FakeWorld();
        w.fill(0, 3, 51, 52, 0, 3, "air");
        w.set(2, 50, 3, "chest");
        BaritoneMask m = mask(false, true);
        assertFalse(m.partOf(0, 50, 0, 1, 0, 1), "empty before the first refresh");
        assertEquals(15, m.refresh(w));
        assertEquals(1, m.refused.get());
        assertTrue(m.partOf(0, 50, 0, 1, 0, 1), "relative 1 0 1 + origin 0 50 0 = 1 50 1");
        assertFalse(m.partOf(0, 50, 0, 2, 0, 3), "the chest");
        assertFalse(m.partOf(0, 50, 0, 1, 1, 1), "air above");
        assertTrue(m.partOf(-5, 40, 7, 6, 10, -6), "any origin: absolute 1 50 1");
        w.set(1, 50, 1, "air");                          // broken: still in the snapshot until the refresh
        assertTrue(m.partOf(0, 50, 0, 1, 0, 1));
        m.refresh(w);
        assertFalse(m.partOf(0, 50, 0, 1, 0, 1));
        assertTrue(m.calls.get() == 7);
    }

    @Test
    void partOfNeverThrows() {
        BaritoneMask m = mask(false, true);
        // packed() itself cannot throw; prove the guard by a snapshot that throws on lookup
        try {
            var f = BaritoneMask.class.getDeclaredField("snapshot");
            f.setAccessible(true);
            f.set(m, new java.util.AbstractSet<Long>() {
                @Override public java.util.Iterator<Long> iterator() { throw new IllegalStateException("x"); }
                @Override public int size() { return 0; }
                @Override public boolean contains(Object o) { throw new StackOverflowError("deep"); }
            });
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
        assertFalse(m.partOf(0, 0, 0, 1, 1, 1));
        assertEquals(1, m.errors.get());
        assertTrue(m.firstError.contains("deep"));
    }

    @Test
    void fillSizeIsInclusive() {
        assertArrayEquals(new int[]{4, 3, 4}, BaritoneMask.size(BOX));
        assertArrayEquals(new int[]{1, 1, 1}, BaritoneMask.size(ClearBox.of(5, 5, 5, 5, 5, 5)));
        assertArrayEquals(new int[]{6, 1, 6}, BaritoneMask.size(ClearBox.of(5, 83, 2, 0, 83, -3)));
    }

    // ---- 0.30.0 (P2): an only list ----

    static final java.util.List<Pos> STAIRS = java.util.List.of(new Pos(0, 52, 0), new Pos(0, 51, 0), new Pos(1, 51, 0), new Pos(1, 50, 0),
            new Pos(2, 50, 0), new Pos(2, 49, 0));

    static BaritoneMask onlyMask() {
        return new BaritoneMask(BaritoneMask.onlyBox(STAIRS), false, true, null, BaritoneMask.onlyCells(STAIRS));
    }

    @Test
    void onlyListBoxIsItsBoundingBox() {
        ClearBox b = BaritoneMask.onlyBox(STAIRS);
        assertEquals(0, b.x1());
        assertEquals(2, b.x2());
        assertEquals(49, b.y1());
        assertEquals(52, b.y2());
        assertEquals(0, b.z1());
        assertEquals(0, b.z2());
        assertEquals(null, BaritoneMask.onlyBox(java.util.List.of()));
        assertEquals(null, BaritoneMask.onlyCells(null));
    }

    @Test
    void onlyListedCellsArePartOfTheSchematic() {
        FakeWorld w = new FakeWorld();
        BaritoneMask m = onlyMask();
        assertEquals(Verdict.DIG, m.verdict(w, 1, 51, 0));
        assertEquals(Verdict.OUTSIDE, m.verdict(w, 1, 52, 0), "in the bounding box, not listed: the step above stays");
        assertEquals(Verdict.OUTSIDE, m.verdict(w, 2, 51, 0));
        assertEquals(6, m.refresh(w));
        java.util.Set<Long> snap = m.snapshot();
        for (Pos p : STAIRS) assertTrue(snap.contains(BaritoneMask.packed(p.x(), p.y(), p.z())));
        assertFalse(m.partOf(0, 49, 0, 0, 1, 0), "0 50 0 is not listed");
        assertTrue(m.partOf(0, 49, 0, 1, 1, 0));
        assertEquals(0, m.refused.get(), "unlisted cells are not refusals");
    }

    @Test
    void onlyListStillAppliesTheRules() {
        FakeWorld w = new FakeWorld();
        w.set(1, 50, 0, "chest");
        w.set(2, 49, 0, "air");
        BaritoneMask m = onlyMask();
        assertEquals(4, m.refresh(w), "the chest refused, the air empty");
        assertEquals(Verdict.PROTECTED, m.verdict(w, 1, 50, 0));
        BaritoneMask.Leftover left = m.leftover(w);
        assertEquals(1, left.count(Verdict.PROTECTED));
        assertEquals(4, left.unreachable(), "only listed cells count as left");
        assertEquals(4, m.digCells(w).size());
    }

    @Test
    void packedKeepsNegativeCoordinates() {
        long k = BaritoneMask.packed(-20, -60, -300);
        assertEquals(-20, (int) (k >> 38));
        assertEquals(-300, (int) ((k << 26) >> 38));
        assertEquals(-60, (int) ((k << 52) >> 52));
    }
}
