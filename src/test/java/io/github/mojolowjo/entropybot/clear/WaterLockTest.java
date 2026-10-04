package io.github.mojolowjo.entropybot.clear;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TLL 30 (live 2026-10-04, recorder incidents #9-#11): water flowing across a tunnel the bot had already dug, between it
 * and the rock still to break. The clear's walk map counted water as walkable, Baritone won't path through flowing water,
 * so every walk failed and the dig ended "stuck - 8 tries in a row where I couldn't reach anything" with no water word,
 * and the water plan (which starts from a "blocked by water" end) never ran. Now the walk map stays dry and the dig ends
 * "blocked by water at x y z (it cuts me off ...)", naming the first water cell on the way, which the plug case takes.
 */
class WaterLockTest {
    /** The box: x -10..20, y 50..51, z -1..1 (3 wide, 2 high, floor y 49), like the tunnel's dig from 567. */
    static final ClearBox BOX = ClearBox.of(-10, 50, -1, 20, 51, 1);

    /**
     * Dug x -10..9 (rock from 10). A source 3 blocks beside the tunnel (1 50 4) feeds a channel (1 50 3, 1 50 2) that
     * spills flowing water across the floor at x 0..2 (as the owner's hole at x 570 did).
     */
    static FakeWorld tunnel() {
        FakeWorld w = new FakeWorld();
        w.fill(-12, 9, 50, 51, -1, 1, "air");
        w.set(1, 50, 4, "water");
        w.flow(1, 50, 3, 7, false);
        w.flow(1, 50, 2, 6, false);
        for (int z = -1; z <= 1; z++) {
            w.flow(0, 50, z, z == 1 ? 4 : 3, false);
            w.flow(1, 50, z, z == 1 ? 5 : 4, false);
            w.flow(2, 50, z, z == 1 ? 4 : 3, false);
        }
        return w;
    }

    static ClearJob.Options dig(boolean liquidBlocks) {
        return new ClearJob.Options().box(BOX).label("digging -10 50 -1 to 20 51 1").liquidBlocks(liquidBlocks);
    }

    /** The real tick loop ({@link ClearRun}), walks that can't cross water as Baritone's. */
    static RunDriver driver(FakeWorld w) {
        RunDriver d = new RunDriver(w, Bot.at(-8.5, 50, 0.5)).simInventory(100000);
        d.waterBlocksWalks = true;
        return d;
    }

    @Test
    void theWalkMapNeverCrossesWater() {
        FakeWorld w = tunnel();
        ClearGrid g = ClearGrid.build(w, BOX, Bot.at(-8.5, 50, 0.5), null);
        int[] dist = g.walkDistances(Bot.at(-8.5, 50, 0.5));
        assertTrue(dist[g.idx(-1, 50, 0)] >= 0, "the dry side");
        assertTrue(dist[g.idx(0, 50, 0)] < 0, "in the water");
        assertTrue(dist[g.idx(5, 50, 0)] < 0, "the far side, behind the water");
    }

    @Test
    void aDigCutOffByWaterEndsBlockedNamingTheFirstWaterCell() {
        FakeWorld w = tunnel();
        ClearDriver d = driver(w);
        String msg = d.clear(dig(true));
        assertTrue(msg.startsWith("blocked by water at 0 50 "), msg);
        assertTrue(msg.contains(ClearEngine.CUT_OFF), msg);
        assertTrue(ClearEngine.blockedByLiquid(msg), "the water plan takes it (WaterSteps.wants)");
        assertFalse(msg.contains("stuck"), msg);
        assertEquals("stone", w.get(10, 50, 0), "nothing broken past the water");
    }

    @Test
    void withTheFenceOnItEndsBlockedTooNotOk() {
        // the live case: the fence on, spots behind the water are skipped, so no walk is even tried
        FakeWorld w = tunnel();
        ClearDriver d = driver(w);
        ClearJob job = ClearJob.start(dig(true), null, d.ores);
        job.standOk = (x, y, z) -> z >= -1 && z <= 1;
        String msg = d.run(job);
        assertTrue(msg.startsWith("blocked by water at 0 50 "), msg);
        assertFalse(msg.startsWith("ok"), "a chain must stop");
    }

    @Test
    void withoutTheOptionNothingChangesInWording() {
        String msg = driver(tunnel()).clear(dig(false));
        assertFalse(msg.startsWith("blocked"), msg);
    }

    @Test
    void dryCorridorNoLock() {
        FakeWorld w = new FakeWorld();
        w.fill(-12, 9, 50, 51, -1, 1, "air");
        ClearDriver d = driver(w);
        assertEquals("ok: done digging -10 50 -1 to 20 51 1 - broke 66 blocks", d.clear(dig(true)));
    }

    @Test
    void theLockStartsThePlugCaseAndTheDigGoesOnAfterIt() {
        FakeWorld w = tunnel();
        ClearDriver d = driver(w);
        ClearJob job = ClearJob.start(dig(true), null, d.ores);
        String msg = d.run(job);
        assertTrue(msg.startsWith("blocked by water"), msg);
        Pos at = ClearEngine.liquidBlock(w, job);
        assertNotNull(at, "WaterSteps starts from this cell");
        assertEquals(job.waterLock, at);
        WaterPlan.Round r = WaterPlan.round(new WaterPlan.Inputs(w, BOX, d.bot(), at, false, null, null,
                Map.of("minecraft:cobbled_deepslate", 200)));
        assertNull(r.blocked(), r.what());
        assertEquals(WaterScan.Kind.FLOWING_IN, r.kind());
        assertEquals(List.of(new Pos(1, 50, 2)), r.placements().stream().map(WaterPlan.Placement::cell).toList(), "the entry only");
        assertTrue(r.drain());
        WaterPlan.Placement p = r.placements().get(0);
        assertNotNull(p.from(), "a dry spot to place it from");
        assertFalse(w.fluid(p.from().x(), p.from().y(), p.from().z()));
        // the seal goes in, the rest drains (no source feeds it any more); the dig again finishes
        w.set(1, 50, 2, "cobbled_deepslate");
        for (int x = 0; x <= 2; x++) for (int z = -1; z <= 1; z++) w.set(x, 50, z, "air");
        String again = d.clear(dig(true));
        assertTrue(again.startsWith("ok: done"), again);
        assertEquals("air", w.get(20, 51, 1));
    }
}
