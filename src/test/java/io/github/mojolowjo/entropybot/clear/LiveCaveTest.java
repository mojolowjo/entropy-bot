package io.github.mojolowjo.entropybot.clear;

import org.junit.jupiter.api.Test;

import java.util.List;

import static io.github.mojolowjo.entropybot.clear.FloorFillTest.AREA_ONLY;
import static io.github.mojolowjo.entropybot.clear.FloorFillTest.BOX;
import static io.github.mojolowjo.entropybot.clear.FloorFillTest.CD;
import static io.github.mojolowjo.entropybot.clear.FloorFillTest.IN_AREA;
import static org.junit.jupiter.api.Assertions.*;

/**
 * B7e F: the live cave crossing of 2026-10-04 (mod 0.12.1, the area "tunnel" x -110..8591, z 852..856, y -50..-44), block
 * for block from the orchestrator's map of x 252..270. The chain's {@code dig 247 -46 853 310 -44 855} ended 10:59 with
 * "stopped: couldn't get there (28 blocks away) - broke 22 blocks; 354 left": a walk toward a stand spot it couldn't
 * reach (Baritone's partial path) took it down the cave. The bot came from x 243 along the dug tunnel; an old restock
 * table stands at 264 -48 854.
 */
class LiveCaveTest {
    /** One z row of the map, x 252..270: '.' air, '#' stone. */
    static void row(FakeWorld w, int y, int z, String cells) {
        for (int i = 0; i < cells.length(); i++) w.set(252 + i, y, z, cells.charAt(i) == '.' ? "air" : "stone");
    }

    static FakeWorld live() {
        FakeWorld w = new FakeWorld();                      // stone everywhere below y 52
        w.fill(240, 251, -46, -44, 853, 855, "air");       // the tunnel dug so far
        row(w, -44, 853, ".............######");
        row(w, -44, 854, ".............######");
        row(w, -44, 855, "..............#####");
        row(w, -45, 852, "########......#####");
        for (int z = 853; z <= 855; z++) row(w, -45, z, "..............#####");
        row(w, -46, 852, "#######............");
        row(w, -46, 853, "..................#");
        row(w, -46, 854, "...............####");
        row(w, -46, 855, "..............#####");
        row(w, -47, 852, "#######............");
        row(w, -47, 853, "#########..........");
        row(w, -47, 854, "#########.........#");
        row(w, -47, 855, "############..#####");
        row(w, -48, 852, "#######............");
        row(w, -48, 853, "#########..........");
        row(w, -48, 854, "##########........#");
        row(w, -48, 855, "############..#####");
        row(w, -48, 856, "###############....");
        row(w, -49, 854, "################...");
        row(w, -49, 855, "##############.....");
        row(w, -49, 856, "#############......");
        // the cave opens to the north (z 851, outside the area) and down past y -49
        w.fill(259, 270, -48, -46, 851, 851, "air");
        w.fill(259, 270, -55, -49, 852, 852, "air");
        w.fill(267, 270, -55, -49, 853, 853, "air");
        w.set(264, -48, 854, "crafting_table");             // the restock table left there
        return w;
    }

    static boolean inArea(double x, double y, double z) {
        return IN_AREA.ok((int) Math.floor(x), (int) Math.floor(y + 0.01), (int) Math.floor(z));
    }

    @Test
    void theClearDigsTheFaceFromTheCaveCellsItCanWalkToAndNeverWandersOff() {
        FakeWorld w = live();
        FillDriver d = new FillDriver(w, Bot.at(243.5, -46, 854.5)).simInventory(100000);
        ClearJob job = ClearJob.start(new ClearJob.Options().box(BOX).label("digging"), null, d.ores);
        job.standOk = IN_AREA;
        String r = d.run(job);
        assertEquals(0, d.wildWalks, "every walk went to a spot it could walk to: " + r);
        assertFalse(r.contains("couldn't get there"), r);
        assertFalse(r.contains("stuck"), r);
        assertTrue(r.startsWith("ok: finished digging"), r);
        assertTrue(d.minY >= -50 && d.minZ >= 852 && d.maxZ < 857, "stayed inside the area");
        // the face at x 265..267 went, dug from the cave (feet y -48, z 853..855)
        for (int x = 265; x <= 267; x++) for (int y = -46; y <= -44; y++) assertEquals("air", w.get(x, y, 854), x + " " + y);
        assertEquals("crafting_table", w.get(264, -48, 854), "the table is outside the box: left alone");
    }

    @Test
    void withTheFenceOffAnUnreachableSpotMayStillBeTried() {
        // the old rule stays for boxes without areas (Baritone may find a way the map doesn't show)
        FakeWorld w = live();
        FillDriver d = new FillDriver(w, Bot.at(243.5, -46, 854.5)).simInventory(100000);
        String r = d.run(ClearJob.start(new ClearJob.Options().box(BOX).label("digging"), null, d.ores));
        assertTrue(d.wildWalks > 0, r);
    }

    @Test
    void aFloorDigBridgesTheCaveAndDigsTheWholeBoxInRounds() {
        FakeWorld w = live();
        FillDriver d = new FillDriver(w, Bot.at(243.5, -46, 854.5)).simInventory(100000);
        d.bag.put(CD, 64);
        ClearJob.Options o = new ClearJob.Options().box(BOX).label("digging").floor(true, null).minStandY(BOX.y1());
        FloorFill.Run prev = null;
        int broken = 0, rounds = 0;
        String msg = null;
        for (; rounds < 4; rounds++) {
            ClearJob job = ClearJob.start(o, null, d.ores);
            job.standOk = IN_AREA;
            String r = d.run(job);
            broken += job.broken;
            assertTrue(d.minY >= -46, "the clear stays on the walkway: " + d.minY);
            FloorFill.Run f = d.fill(new FloorFill.Run(w, BOX, null, AREA_ONLY, IN_AREA, false).carry(prev));
            prev = f;
            msg = FloorFill.endMessage(r, broken, f.report());
            int left = job.lastScanLeft != null ? job.lastScanLeft : 0;
            if (left == 0 || f.filled() == 0) break;
        }
        assertEquals(0, d.wildWalks, msg);
        assertTrue(rounds >= 1, "a second round dug from the new floor: " + msg);
        for (int x = 247; x <= 310; x++) {
            for (int z = 853; z <= 855; z++) {
                for (int y = -46; y <= -44; y++) assertEquals("air", w.get(x, y, z), "box " + x + " " + y + " " + z + ": " + msg);
                assertNotEquals("air", w.get(x, -47, z), "floor " + x + " -47 " + z + ": " + msg);
            }
        }
        assertTrue(msg.startsWith("ok: done digging - broke " + broken + " blocks"), msg);
        assertTrue(msg.contains("; filled 21 floor cells with cobbled_deepslate"), msg);
        assertEquals("air", w.get(265, -48, 853), "the cave under the floor stays a cave");
        assertTrue(d.minY >= -46 && d.minZ >= 852 && d.maxZ < 857);
    }

    @Test
    void fromWhereTheOldBotEndedItWalksBackUpBeforeTheClear() {
        FakeWorld w = live();
        FillDriver d = new FillDriver(w, Bot.at(262.5, -48, 854.5));
        d.bag.put(CD, 64);
        d.fill(new FloorFill.Run(w, BOX, null, AREA_ONLY, IN_AREA, true));
        assertTrue(d.y >= -46, "on the walkway: " + d.y);
        assertTrue(d.placedCells.isEmpty(), "the staircase at z 854 is walkable: " + d.placedCells);
        assertEquals(List.of(), d.placedCells);
    }
}
