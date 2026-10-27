package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.guard.Policy;
import io.github.mojolowjo.entropybot.restore.Ledger;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 0.39.z77 take (run CY 23:40:56-23:43:52): the place step put my furnace on the cell of my own crafting table at
 * 11 23 -1 (it cleared the table first). The ledger kept the table's record (a held cell keeps its first note), the
 * station-stale check then forgot that record, and my furnace was "a wild station (not placed by me, outside my areas)".
 * The smelt walked from 1 16 -4 toward my surface furnace at 7 67 2 (49 m); the walk failed, a take of it failed, and a
 * new furnace came at 23:43:52: about 128 s lost. Now my new station replaces my old station's record on that cell, so
 * the furnace 13 m away is mine and the nearest.
 */
class Z77TakeOwnFurnaceTest {
    static final String OW = "minecraft:overworld", TABLE = "minecraft:crafting_table", FURNACE = "minecraft:furnace";
    static final int[] ME = {1, 16, -4};

    /** The place step's note, as RestoreLive.noteBuilt does it: drop a replaced station record, then add. */
    static void note(Ledger l, int x, int y, int z, String block, String why) {
        l.dropReplacedStation(OW, x, y, z, block, why);
        l.add(x, y, z, OW, block, block, List.of(), why, 5, "steps", why, 100, 1000);
    }

    /** CY's world: no owner area (Policy.EMPTY), my ledger, the blocks that stand there now. */
    static Nearby.Scan scan(Ledger l, Map<String, String> world, List<String> forgotten) {
        return new Nearby.Scan() {
            @Override public Policy stationPolicy() { return Policy.EMPTY; }
            @Override public String stationDim() { return OW; }
            @Override public List<int[]> logs(int radius) { return List.of(); }
            @Override public List<int[]> find(Set<String> blocks, int radius) {
                List<int[]> out = new ArrayList<>();
                for (String k : world.keySet()) {
                    String[] p = k.split(" ");
                    out.add(new int[]{Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2])});
                }
                return out;
            }
            @Override public boolean isLog(String block) { return false; }
            @Override public boolean fairOk(int x, int y, int z) { return true; }
            @Override public boolean leavesNear(int x, int y, int z, int reach) { return false; }
            @Override public boolean allowed(int x, int y, int z) { return true; }
            @Override public String blockAt(int x, int y, int z) { return world.get(x + " " + y + " " + z); }
            @Override public Boolean open(int x, int y, int z) { return true; }
            @Override public List<Nearby.Mine> own() {
                List<Nearby.Mine> out = new ArrayList<>();
                for (Ledger.Entry e : l.builtStations(OW)) out.add(new Nearby.Mine(e.x, e.y, e.z, e.block));
                return out;
            }
            @Override public String forgetOwn(Nearby.Mine m, String now) {
                Ledger.Entry e = l.at(OW, m.x(), m.y(), m.z());
                if (e != null) l.remove(e);
                forgotten.add(m.x() + " " + m.y() + " " + m.z());
                return Nearby.forgotLine(m, now);
            }
        };
    }

    @Test
    void myFurnaceOnMyTablesCellIsMineAndTheNearest() {
        Ledger l = new Ledger();
        note(l, 7, 67, 2, FURNACE, Ledger.STATION);            // 23:34:28 my surface furnace
        note(l, 11, 23, -1, TABLE, Ledger.STATION);            // 23:40:50 my table under ground
        note(l, 11, 23, -1, FURNACE, Ledger.STATION);          // 23:40:56 the place step put my furnace on the table's cell

        Ledger.Entry e = l.at(OW, 11, 23, -1);
        assertEquals(FURNACE, e.block, "the cell's record is the furnace that stands there now");
        assertEquals(Ledger.STATION, e.why);
        assertEquals(2, l.builtStations(OW).size(), "my two furnaces, no stale table record");

        Map<String, String> world = new HashMap<>(Map.of("7 67 2", FURNACE, "11 23 -1", FURNACE));
        List<String> forgotten = new ArrayList<>();
        Nearby.lookup(scan(l, world, forgotten), ME, Set.of(TABLE));          // the next craft's table lookup
        Nearby.Lookup look = Nearby.lookup(scan(l, world, forgotten), ME, Set.of(FURNACE));
        assertTrue(forgotten.isEmpty(), "nothing stale to forget: " + forgotten);
        assertTrue(look.skipped().isEmpty(), "no wild station: " + look.skipped());
        assertEquals(2, look.spots().size());
        NeedChain.Spot first = look.spots().get(0);
        assertArrayEquals(new int[]{11, 23, -1}, new int[]{first.x(), first.y(), first.z()},
                "the smelt uses my furnace 13 m away, not the surface one 49 m away");
        assertEquals("stations: 2 of mine, 0 scanned within 32, nearest furnace 13 m at 11 23 -1",
                Nearby.stationsLine(look.spots(), scan(l, world, forgotten).own(), Set.of(FURNACE), 32));
    }

    @Test
    void theOldShapeLostTheRecordAndCalledMyFurnaceWild() {
        // CY as it ran: the furnace's note was dropped (the table's record kept the cell), the lookup forgot the table's
        // record, and the furnace standing there was refused as wild
        Ledger l = new Ledger();
        l.add(7, 67, 2, OW, FURNACE, FURNACE, List.of(), Ledger.STATION, 1, "steps", Ledger.STATION, 1, 1);
        l.add(11, 23, -1, OW, TABLE, TABLE, List.of(), Ledger.STATION, 5, "steps", Ledger.STATION, 100, 1000);
        assertNull(l.add(11, 23, -1, OW, FURNACE, FURNACE, List.of(), Ledger.STATION, 5, "steps", Ledger.STATION, 101, 1001),
                "a plain add keeps the first note");
        Map<String, String> world = new HashMap<>(Map.of("7 67 2", FURNACE, "11 23 -1", FURNACE));
        List<String> forgotten = new ArrayList<>();
        Nearby.lookup(scan(l, world, forgotten), ME, Set.of(TABLE));          // 23:40:56.583 the next craft's table lookup
        assertEquals(List.of("11 23 -1"), forgotten, "the table's record is stale: forgotten");
        Nearby.Lookup look = Nearby.lookup(scan(l, world, forgotten), ME, Set.of(FURNACE));
        assertEquals(List.of("11 23 -1: wild station (not placed by me, outside my areas)"), look.skipped());
        assertEquals(7, look.spots().get(0).x(), "the surface furnace was the only one left");
    }

    @Test
    void onlyAStationOfMineOfAnotherBlockIsReplaced() {
        Ledger l = new Ledger();
        l.add(1, 60, 0, OW, "minecraft:stone", "minecraft:stone", List.of("minecraft:cobblestone"), null, 5, "mine", Ledger.PATH, 1, 1);
        assertNull(l.dropReplacedStation(OW, 1, 60, 0, FURNACE, Ledger.STATION), "broken terrain keeps its first note");
        l.add(2, 60, 0, OW, "minecraft:cobblestone", "minecraft:cobblestone", List.of(), "built: placed", 5, "steps", "built: placed", 1, 1);
        assertNull(l.dropReplacedStation(OW, 2, 60, 0, FURNACE, Ledger.STATION), "a placed block is not a station record");
        l.add(3, 60, 0, OW, TABLE, TABLE, List.of(), Ledger.STATION, 5, "steps", Ledger.STATION, 1, 1);
        assertNull(l.dropReplacedStation(OW, 3, 60, 0, TABLE, Ledger.STATION), "the same station: the first note stays");
        assertNull(l.dropReplacedStation(OW, 3, 60, 0, "minecraft:cobblestone", "built: placed"), "a non-station never replaces my station");
        Ledger.Entry old = l.dropReplacedStation(OW, 3, 60, 0, FURNACE, Ledger.STATION);
        assertNotNull(old);
        assertEquals(TABLE, old.block);
        assertNull(l.at(OW, 3, 60, 0), "the table's record is gone; the furnace's add comes next");
    }
}
