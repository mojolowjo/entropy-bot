package io.github.mojolowjo.entropybot.clear;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The sim's strip-mine scenarios ("strip", "strip2", "stripout", "strip pit") at the level of the clear engine: each
 * run's dig steps (the corridor with mustFinish, the two branches with their torches, the ore step over findVeins
 * with minStandY, the hole fills) as the bridge's stripSteps builds them, driven by ClearDriver. The expected
 * reports are the bridge's, from sim runs ("job finished: ..."). The seq around them (chests, deposits, the trip to
 * the base and the walk back) is not modelled: the bot is put back at the corridor end where the sim's walk ends.
 */
class StripTest {
    static final class StripRun {
        final FakeWorld w;
        final SimWorlds.Mine m;
        final boolean collect;
        final ClearDriver d;
        final List<String> log = new ArrayList<>();
        List<Veins.Fill> lastFills = List.of();

        StripRun(FakeWorld w, int ox, boolean collect) {
            this.w = w;
            this.m = new SimWorlds.Mine(ox, 40, 0);
            this.collect = collect;
            this.d = new ClearDriver(w, Bot.at(ox + 0.5, 40, 0.5)).simInventory(100000);
            d.torches = 16;
            // the server takes back the break at the corridor head once (sim: flakyOnce)
            d.flakyOnce.add(Pos.key(ox, 41, -7));
        }

        String dig(ClearJob.Options o) {
            String r = d.clear(o);
            log.add(r);
            return r;
        }

        /** The first run's setup: room for the chests and the table, then they are placed (standing at the start). */
        void setup() {
            dig(new ClearJob.Options().box(m.box(m.chestA(), m.chestB(), 2)).label("digging room for the mine chests"));
            dig(new ClearJob.Options().box(m.box(m.table(), m.table(), 1)).label("digging room for a crafting table"));
            Pos s = m.cell(0, 0);
            d.place("crafting_table", m.table(), s);
            d.place("chest", m.chestA(), s);
            d.place("chest", m.chestB(), s);
        }

        /** One branch pair k (stripSteps): false when a step stopped the run. */
        boolean branch(int k) {
            int length = 12;
            List<Pos> dump = List.of(m.chestA(), m.chestB());
            ClearBox cb = m.corridor(k), lb = m.branch(k, 1, length), rb = m.branch(k, -1, length);
            String r = dig(new ClearJob.Options().box(cb).keepOres(false).collect(collect).dump(dump).mustFinish(true)
                    .torches(m.corridorTorches(k)).label("digging the mine corridor (branch " + k + ")"));
            if (r.startsWith("stopped")) return false;
            for (int side = 1; side >= -1; side -= 2) {
                dig(new ClearJob.Options().box(side > 0 ? lb : rb).dump(dump).torches(m.branchTorches(k, side, length)).keepOres(!collect)
                        .collect(collect).label("digging branch " + k + (side > 0 ? " left" : " right")));
            }
            if (collect) {
                List<ClearBox> boxes = List.of(cb, lb, rb);
                List<Pos> veins = Veins.findVeins(w, boxes, null, SimWorlds.MAP_AREA);
                if (!veins.isEmpty()) {
                    int feetY = Veins.feetY(boxes);
                    dig(Veins.oreDigOptions(veins, feetY, dump, k));
                    lastFills = Veins.holeFills(boxes, veins, feetY);
                    for (Veins.Fill f : lastFills) d.place("cobblestone", f.pos(), f.from());
                }
            }
            return true;
        }

        /** After run 1 with ores collected: the trip to the base, and the next run's walk back to the corridor end. */
        void backFromBase(int k) {
            Pos end = m.cell(3 * k - 3, 0);
            d.moveTo(end.x() + 0.5, end.y(), end.z() + 0.5);
        }
    }

    static final List<String> STRIP_RUN1 = List.of(
            "ok: done digging room for the mine chests - broke 4 blocks",
            "ok: done digging room for a crafting table - broke 1 blocks",
            "ok: done digging the mine corridor (branch 1) - broke 6 blocks",
            "ok: done digging branch 1 left - broke 24 blocks; 1 ores mined",
            "ok: done digging branch 1 right - broke 24 blocks",
            "ok: done mining 1 ore blocks next to branch 1 - broke 1 blocks; 1 ores mined");

    @Test
    void strip() {
        StripRun s = new StripRun(SimWorlds.strip(0, false, false), 0, true);
        s.setup();
        assertTrue(s.branch(1));
        assertEquals(STRIP_RUN1, s.log);
        s.log.clear();
        s.backFromBase(2);
        assertTrue(s.branch(2));
        assertEquals(List.of(
                "ok: done digging the mine corridor (branch 2) - broke 6 blocks; 1 ores mined",
                "ok: done digging branch 2 left - broke 24 blocks",
                "ok: finished digging branch 2 right - broke 16 blocks; 8 left, e.g. 8 41 -6 next to water/lava, "
                        + "9 41 -6 out of reach (nowhere to stand close enough), 10 40 -6 out of reach (nowhere to stand close enough)"), s.log);
        s.log.clear();
        assertTrue(s.branch(3));
        assertEquals(List.of(
                "ok: done digging the mine corridor (branch 3) - broke 7 blocks",
                "ok: done digging branch 3 left - broke 24 blocks",
                "ok: done digging branch 3 right - broke 24 blocks",
                "ok: done mining 1 ore blocks next to branch 3 - broke 1 blocks; 1 ores mined"), s.log);
        FakeWorld w = s.w;
        // every ore is mined: branch path, wall, wall, corridor
        for (Pos p : List.of(new Pos(-7, 40, -3), new Pos(5, 40, -4), new Pos(-2, 41, -8), new Pos(0, 41, -5))) assertFalse(w.get(p).endsWith("_ore"), p.key());
        // branch 1 left is not cut short by the iron in its path
        for (int x = -1; x >= -12; x--) for (int y = 40; y <= 41; y++) assertTrue(w.get(x, y, -3).equals("air") || w.get(x, y, -3).equals("torch"), x + " " + y);
        assertEquals(Map.of(), s.d.ores.ores, "nothing is left listed");
        // the torches, as the sim placed them
        assertEquals(List.of("crafting_table@1 40 0", "chest@-1 40 0", "chest@-1 40 1", "torch@-5 40 -3", "torch@-11 40 -3", "torch@5 40 -3",
                "torch@11 40 -3", "torch@0 40 -6", "torch@-5 40 -6", "torch@-11 40 -6", "torch@5 40 -6", "torch@-5 40 -9", "torch@-11 40 -9",
                "torch@5 40 -9", "torch@11 40 -9"), s.d.placed);
    }

    @Test
    void strip2BedrockBlocksTheCorridor() {
        StripRun s = new StripRun(SimWorlds.strip(0, true, false), 0, true);
        s.setup();
        assertTrue(s.branch(1));
        assertEquals(STRIP_RUN1, s.log);
        s.log.clear();
        s.backFromBase(2);
        assertFalse(s.branch(2), "the run stops");
        assertEquals(List.of("stopped: the mine corridor is blocked at 0 40 -5 (bedrock) - broke 5 blocks; 1 ores mined"), s.log);
    }

    @Test
    void stripoutLeavesTheOresListed() {
        int ox = 300;
        StripRun s = new StripRun(SimWorlds.strip(ox, false, false), ox, false);
        s.setup();
        for (int k = 1; k <= 3; k++) assertTrue(s.branch(k));
        assertEquals(List.of(
                "ok: done digging room for the mine chests - broke 4 blocks",
                "ok: done digging room for a crafting table - broke 1 blocks",
                "ok: done digging the mine corridor (branch 1) - broke 6 blocks",
                "ok: done digging branch 1 left - broke 23 blocks; 1 ores left in place for you (PM \"ores\")",
                "ok: done digging branch 1 right - broke 24 blocks",
                "ok: done digging the mine corridor (branch 2) - broke 6 blocks",
                "ok: done digging branch 2 left - broke 24 blocks",
                "ok: finished digging branch 2 right - broke 16 blocks; 8 left, e.g. 308 41 -6 next to water/lava, "
                        + "309 41 -6 out of reach (nowhere to stand close enough), 310 40 -6 out of reach (nowhere to stand close enough)",
                "ok: done digging the mine corridor (branch 3) - broke 7 blocks",
                "ok: done digging branch 3 left - broke 24 blocks; 1 ores left in place for you (PM \"ores\")",
                "ok: done digging branch 3 right - broke 24 blocks"), s.log);
        FakeWorld w = s.w;
        assertFalse(w.get(ox, 41, -5).endsWith("_ore"), "the corridor ore is still mined");
        assertEquals("iron_ore", w.get(ox - 7, 40, -3));
        assertEquals("coal_ore", w.get(ox + 5, 40, -4));
        assertEquals("diamond_ore", w.get(ox - 2, 41, -8));
        // the ones showing are listed (the coal hides behind a torch: scanOres counts air only, as in the bridge)
        assertEquals(Map.of("293 40 -3", "iron_ore", "298 41 -8", "diamond_ore"), s.d.ores.ores);
    }

    @Test
    void stripPitFillsTheHoles() {
        StripRun s = new StripRun(SimWorlds.strip(0, false, true), 0, true);
        s.setup();
        assertTrue(s.branch(1));
        assertEquals("ok: done mining 3 ore blocks next to branch 1 - broke 3 blocks; 3 ores mined", s.log.get(s.log.size() - 1));
        assertTrue(s.d.minY >= 40, "the bot never stood below the branch floor: " + s.d.minY);
        assertEquals("cobblestone", s.w.get(-4, 39, -3));
        assertEquals("cobblestone", s.w.get(-4, 38, -3));
        assertEquals(List.of(new Pos(-4, 38, -3), new Pos(-4, 39, -3)), s.lastFills.stream().map(Veins.Fill::pos).toList(), "deepest first");
        int i = s.d.placed.indexOf("cobblestone@-4 38 -3");
        assertTrue(i >= 0 && s.d.placed.get(i + 1).equals("cobblestone@-4 39 -3"));
    }
}
