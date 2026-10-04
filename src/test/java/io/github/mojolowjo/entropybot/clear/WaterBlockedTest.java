package io.github.mojolowjo.entropybot.clear;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Water plan item 1 (recorder incident #3): a dig stopped by water or lava ends as "blocked by water at x y z", never
 * "ok: finished" with most of the box left, so a chain (which goes on only after "ok"/"done") stops there.
 */
class WaterBlockedTest {
    /** A 1-wide tunnel x 0..20, y 50..51, z 0 in stone, dug in from x -2..-1; a pool fills x 10 in the path. */
    static FakeWorld tunnel(String liquid) {
        FakeWorld w = new FakeWorld();
        w.fill(-2, -1, 50, 51, 0, 0, "air");
        w.set(10, 50, 0, liquid);
        w.set(10, 51, 0, liquid);
        return w;
    }

    static ClearJob.Options dig(boolean liquidBlocks) {
        return new ClearJob.Options().box(ClearBox.of(0, 50, 0, 20, 51, 0)).label("digging 0 50 0 to 20 51 0").liquidBlocks(liquidBlocks);
    }

    @Test
    void aDigStoppedByWaterEndsBlockedNamingTheSpot() {
        FakeWorld w = tunnel("water");
        ClearDriver d = new ClearDriver(w, Bot.at(-1.5, 50, 0.5)).simInventory(100000);
        String msg = d.clear(dig(true));
        assertTrue(msg.startsWith("blocked by water at 10 5"), msg);
        assertTrue(msg.contains(" - broke 18 blocks; "), msg);
        assertTrue(msg.contains(" left"), msg);
        assertFalse(msg.startsWith("ok"), "a chain must not go on");
        assertTrue(ClearEngine.blockedByLiquid(msg));
        assertEquals("stone", w.get(9, 50, 0), "the block next to the water stays");
        assertEquals("stone", w.get(11, 50, 0), "the far side was never reached");
    }

    @Test
    void lavaIsNamedToo() {
        ClearDriver d = new ClearDriver(tunnel("lava"), Bot.at(-1.5, 50, 0.5)).simInventory(100000);
        assertTrue(d.clear(dig(true)).startsWith("blocked by lava at 10 5"));
    }

    @Test
    void withoutTheOptionTheOldEndingStays() {
        // strip branches and build clear keep "ok: finished ... next to water/lava" (their callers expect it)
        ClearDriver d = new ClearDriver(tunnel("water"), Bot.at(-1.5, 50, 0.5)).simInventory(100000);
        String msg = d.clear(dig(false));
        assertTrue(msg.startsWith("ok: finished digging 0 50 0 to 20 51 0 - broke 18 blocks; "), msg);
    }

    @Test
    void waterBesideTheBoxThatWasDugAroundIsNotBlocked() {
        // water that only ever touched blocks outside the box: nothing was left for it, so the dig is done
        FakeWorld w = new FakeWorld();
        w.fill(-2, -1, 50, 51, 0, 0, "air");
        w.set(5, 50, 2, "water");
        ClearDriver d = new ClearDriver(w, Bot.at(-1.5, 50, 0.5)).simInventory(100000);
        assertEquals("ok: done digging 0 50 0 to 20 51 0 - broke 42 blocks", d.clear(dig(true)));
    }

    @Test
    void aSkipWhoseWaterDrainedSinceIsNotABlock() {
        FakeWorld w = new FakeWorld();
        ClearJob j = ClearJob.start(dig(true), null, null);
        j.lastScanLeft = 3;
        j.skip.put("9 50 0", ClearEngine.NEXT_TO_LIQUID);
        assertNull(ClearEngine.liquidBlock(w, j), "no water next to it any more");
        assertTrue(ClearEngine.finishMessage(w, j, null).startsWith("ok: finished"));
        w.set(9, 51, 0, "water");
        assertEquals(new Pos(9, 51, 0), ClearEngine.liquidBlock(w, j), "water above counts");
        assertTrue(ClearEngine.finishMessage(w, j, null).startsWith("blocked by water at 9 51 0 - broke 0 blocks; 3 left"));
        // an end with its own reason keeps it
        assertTrue(ClearEngine.finishMessage(w, j, "stopped: out of pickaxes").startsWith("stopped: out of pickaxes"));
    }
}
