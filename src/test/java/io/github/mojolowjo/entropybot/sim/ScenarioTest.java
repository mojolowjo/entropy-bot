package io.github.mojolowjo.entropybot.sim;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * sim step 1 (to-do/simulator-plan.md): the scenario file - what the game writes is what a reader gets back, cell for
 * cell; a cell with no chunk is "not loaded", never air; a broken file is refused with its reason.
 */
class ScenarioTest {
    /** A 5 x 4 x 3 box at -2 60 7: a stone floor at y 60, a chest and stairs on it, air over, one column not loaded. */
    static Scenario small() {
        Scenario s = new Scenario();
        s.when = "2026-11-13T10:01:42";
        s.why = "deposit: stuck 10 s short of the chest at -53 107 -8";
        s.dim = "minecraft:overworld";
        s.feet = new int[]{0, 61, 8};
        s.hp = 20;
        s.food = 18;
        s.x0 = -2; s.y0 = 60; s.z0 = 7;
        s.sx = 5; s.sy = 4; s.sz = 3;
        s.palette = new java.util.ArrayList<>(List.of("minecraft:stone", "minecraft:air", "minecraft:chest[facing=north,type=single,waterlogged=false]",
                "minecraft:oak_stairs[facing=east,half=bottom,shape=straight,waterlogged=false]", Scenario.NOT_LOADED));
        int[] cells = new int[s.size()];
        for (int y = 60; y < 64; y++) for (int z = 7; z < 10; z++) for (int x = -2; x < 3; x++) {
            int i = s.index(x, y, z);
            cells[i] = x == 2 ? 4 : y == 60 ? 0 : y == 61 && x == -1 && z == 8 ? 2 : y == 61 && x == 1 && z == 9 ? 3 : 1;
        }
        s.blocks(cells);
        s.bag.add(new Scenario.Slot(0, "minecraft:cobblestone", 32, 0));
        s.bag.add(new Scenario.Slot(1, "minecraft:wooden_pickaxe", 1, 57));
        s.bag.add(new Scenario.Slot(9, "minecraft:cobblestone", 7, 0));
        s.orders = "no orders";
        s.status = "AA: orders";
        s.lines.add("deposit: trail: broken at -73 94 -29");
        return s;
    }

    @Test
    void theCellsReadBack_asWritten() {
        Scenario s = small();
        assertEquals("minecraft:stone", s.blockAt(0, 60, 8));
        assertEquals("minecraft:air", s.blockAt(0, 61, 8), "the feet's cell");
        assertEquals("minecraft:chest[facing=north,type=single,waterlogged=false]", s.blockAt(-1, 61, 8), "the state's own text");
        assertEquals("minecraft:chest", s.blockIdAt(-1, 61, 8));
        assertEquals("minecraft:oak_stairs", s.blockIdAt(1, 61, 9));
        assertEquals(Scenario.NOT_LOADED, s.blockAt(2, 62, 8), "no chunk there: not known, never air (rule 15)");
        assertNull(s.blockAt(3, 61, 8), "outside the box");
        assertNull(s.blockAt(0, 64, 8));
        assertNull(s.blockAt(0, 59, 8));
        assertEquals(12, s.counts().get(Scenario.NOT_LOADED), "the column x 2: 4 high x 3 deep");
        assertEquals(12, s.counts().get("minecraft:stone"), "the floor less the column not loaded");
        assertEquals(39, s.bagCounts().get("minecraft:cobblestone"), "two slots summed");
    }

    @Test
    void aFileRoundTrip_keepsEveryCellAndWord(@TempDir Path dir) throws Exception {
        Scenario s = small();
        Path f = dir.resolve("scenarios").resolve("20261113-100142-deposit-stuck.json.gz");
        s.write(f);
        Scenario r = Scenario.read(f);
        for (int y = 59; y < 65; y++) for (int z = 6; z < 11; z++) for (int x = -3; x < 4; x++)
            assertEquals(s.blockAt(x, y, z), r.blockAt(x, y, z), x + " " + y + " " + z);
        assertArrayEquals(s.feet, r.feet);
        assertEquals(s.bag, r.bag);
        assertEquals(s.why, r.why);
        assertEquals(s.lines, r.lines);
        assertEquals(s.summary(), r.summary());
        assertTrue(r.summary().contains("12 not loaded") && r.summary().contains("5 x 4 x 3"), r.summary());
    }

    /** Swept: random boxes, palettes and cells survive the run-length pairs and the JSON, cell for cell. */
    @Test
    void sweptBoxes_roundTripCellForCell() {
        Random rnd = new Random(20261113);
        for (int round = 0; round < 200; round++) {
            Scenario s = new Scenario();
            s.dim = "d";
            s.sx = 1 + rnd.nextInt(9); s.sy = 1 + rnd.nextInt(9); s.sz = 1 + rnd.nextInt(9);
            s.x0 = rnd.nextInt(200) - 100; s.y0 = rnd.nextInt(380) - 64; s.z0 = rnd.nextInt(200) - 100;
            int pal = 1 + rnd.nextInt(6);
            for (int i = 0; i < pal; i++) s.palette.add("b" + i);
            int[] cells = new int[s.size()];
            int runny = 1 + rnd.nextInt(8);                        // long runs and noise both
            for (int i = 0; i < cells.length; i++) cells[i] = i > 0 && rnd.nextInt(runny) > 0 ? cells[i - 1] : rnd.nextInt(pal);
            s.blocks(cells);
            assertArrayEquals(cells, Scenario.unrle(s.runs, s.size()), "round " + round);
            Scenario r = Scenario.fromJson(s.toJson());
            for (int y = 0; y < s.sy; y++) for (int z = 0; z < s.sz; z++) for (int x = 0; x < s.sx; x++)
                assertEquals("b" + cells[(y * s.sz + z) * s.sx + x], r.blockAt(s.x0 + x, s.y0 + y, s.z0 + z), "round " + round);
        }
    }

    /**
     * The first file a game wrote (run JBS-T1 02:00:34, debug.scenarioOnFail: "explore: boxed in at -61 114 -7: 3 no-break
     * walks in a row found no path from here"): it loads, the feet's cells are open, there is ground under them, and the
     * whole cube was loaded.
     */
    @Test
    void aFileAGameWrote_loads_withTheBotStandingInIt() throws Exception {
        Path f = Path.of(ScenarioTest.class.getResource("/scenarios/runJBS-T1-boxed-in.json.gz").toURI());
        Scenario s = Scenario.read(f);
        assertArrayEquals(new int[]{-61, 114, -7}, s.feet);
        assertEquals("minecraft:overworld", s.dim);
        assertTrue(s.why.contains("boxed in at -61 114 -7"), s.why);
        assertEquals(49 * 49 * 49, s.size(), s.summary());
        assertNull(s.counts().get(Scenario.NOT_LOADED), "every cell of the cube was loaded: " + s.summary());
        String feet = s.blockIdAt(-61, 114, -7), head = s.blockIdAt(-61, 115, -7), under = s.blockIdAt(-61, 113, -7);
        assertNotNull(feet);
        assertNotEquals("minecraft:air", under, "something under the feet: " + under);
        assertTrue(s.counts().size() > 10, "real terrain: " + s.counts().keySet());
        assertFalse(s.bag.isEmpty(), "the bag came with it");
        System.out.println("scenario runJBS-T1 boxed in: feet " + feet + ", head " + head + ", under " + under + "; bag " + s.bagCounts() + "; top blocks "
                + s.counts().entrySet().stream().limit(8).toList());
    }

    @Test
    void aBrokenFile_isRefusedWithItsReason() {
        Scenario s = small();
        String json = s.toJson();
        assertTrue(assertThrows(IllegalArgumentException.class, () -> Scenario.fromJson(json.replace("\"v\":1", "\"v\":2"))).getMessage().contains("version 2"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> Scenario.fromJson(json.replace("\"sy\":4", "\"sy\":5"))).getMessage().contains("cells"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> Scenario.unrle(new int[]{3, 0, 2}, 5)).getMessage().contains("not pairs"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> Scenario.unrle(new int[]{9, 0}, 5)).getMessage().contains("more than"));
        Scenario bad = small();
        bad.palette.remove(4);
        assertTrue(assertThrows(IllegalArgumentException.class, () -> Scenario.fromJson(bad.toJson())).getMessage().contains("palette"));
    }
}
