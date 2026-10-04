package io.github.mojolowjo.entropybot.clear;

import io.github.mojolowjo.entropybot.guard.Box;
import io.github.mojolowjo.entropybot.guard.GuardCore;
import io.github.mojolowjo.entropybot.guard.Policy;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * B7e F: {@code dig ... floor [block]} over the tunnel's cave crossing: the tunnel x 247..310, y -46..-44, z 853..855
 * (the area "tunnel" x -110..8591, z 852..856, y -50..-44), its floor y -47 missing for x 261..270, the cave under it.
 */
class FloorFillTest {
    static final String OW = "minecraft:overworld";
    static final ClearBox BOX = ClearBox.of(247, -46, 853, 310, -44, 855);
    static final Box AREA = new Box("tunnel", OW, -110, -50, 852, 8591, -44, 856);
    static final ClearJob.StandCheck IN_AREA = (x, y, z) -> x >= -110 && x <= 8591 && z >= 852 && z <= 856 && y >= -50 && y <= -44;
    static final String CD = "minecraft:cobbled_deepslate";

    static Predicate<Pos> leasable(Policy p) {
        return c -> GuardCore.cellLeasable(p, OW, c.x(), c.y(), c.z());
    }

    static final Predicate<Pos> AREA_ONLY = leasable(new Policy(List.of(AREA), List.of()));

    /**
     * The tunnel already dug (x 240..310), its floor y -47 missing for x 261..270 at z 853..855, the cave under it: air
     * at y -48 there (the cave bottom y -49 solid), deeper (y -49 air, -50 solid) for x 264..267.
     */
    static FakeWorld cave() {
        FakeWorld w = new FakeWorld();
        w.fill(240, 310, -46, -44, 853, 855, "air");
        w.fill(261, 270, -48, -47, 853, 855, "air");
        w.fill(264, 267, -49, -49, 853, 855, "air");
        return w;
    }

    static int filledLayer(FakeWorld w, int x1, int x2) {
        int n = 0;
        for (int x = x1; x <= x2; x++) for (int z = 853; z <= 855; z++) if (!w.get(x, -47, z).equals("air")) n++;
        return n;
    }

    // ---- cells ----

    @Test
    void selectsAirAndReplaceableCellsOfTheLayerUnderTheBox() {
        FakeWorld w = cave();
        w.set(262, -47, 854, "short_grass");             // replaceable: filled
        w.set(263, -47, 854, "torch");                   // a built thing without collision: left alone
        FloorFill.Selection s = FloorFill.select(w, BOX, null);
        assertEquals(29, s.todo().size(), "30 cells of the hole, the torch's not");
        assertTrue(s.todo().contains(new Pos(262, -47, 854)));
        assertFalse(s.todo().contains(new Pos(263, -47, 854)));
        assertTrue(s.skipped().isEmpty());
        for (Pos p : s.todo()) assertEquals(-47, p.y(), "only the one layer, never the cave");
    }

    @Test
    void skipsCellsNextToWaterOrLavaAndOutsideTheFence() {
        FakeWorld w = cave();
        w.set(268, -48, 854, "water");                    // under (268 -47 854)
        w.set(270, -47, 853, "lava");                     // in the layer itself
        Policy p = new Policy(List.of(AREA), List.of(new Box("door", OW, 262, -50, 853, 262, -44, 855)));
        FloorFill.Selection s = FloorFill.select(w, BOX, leasable(p));
        assertEquals(FloorFill.LIQUID, s.skipped().get(new Pos(268, -47, 854)));
        assertEquals(FloorFill.LIQUID, s.skipped().get(new Pos(270, -47, 853)));
        assertEquals(FloorFill.LIQUID, s.skipped().get(new Pos(269, -47, 853)), "beside the lava");
        assertEquals(FloorFill.FENCED, s.skipped().get(new Pos(262, -47, 854)), "a protect box");
        // a box whose floor layer lies outside the area: every cell refused
        ClearBox low = ClearBox.of(261, -50, 853, 262, -50, 855);
        FakeWorld deep = cave();
        deep.fill(261, 262, -51, -51, 853, 855, "air");
        FloorFill.Selection out = FloorFill.select(deep, low, AREA_ONLY);
        assertTrue(out.todo().isEmpty());
        assertEquals(FloorFill.FENCED, out.skipped().get(new Pos(261, -51, 854)), "y -51 is below the area");
    }

    @Test
    void bridgingOrderStartsAtTheEdgesAndGoesInward() {
        FakeWorld w = cave();
        FloorFill.Selection s = FloorFill.select(w, BOX, AREA_ONLY);
        Map<Pos, Integer> r = FloorFill.ranks(w, s.todo());
        // the walls (z 852/856) and the floor's ends (x 260/271) touch the outer rows; the middle row hangs over the cave
        assertEquals(0, r.get(new Pos(261, -47, 854)), "next to the floor at x 260");
        assertEquals(0, r.get(new Pos(265, -47, 853)), "against the wall at z 852");
        assertEquals(0, r.get(new Pos(265, -47, 855)));
        assertEquals(1, r.get(new Pos(265, -47, 854)), "the middle row comes after its sides");
        // the order: every cell after one it can be clicked against
        List<Pos> order = s.todo();
        int firstMiddle = order.indexOf(new Pos(265, -47, 854));
        assertTrue(order.indexOf(new Pos(265, -47, 853)) < firstMiddle);
        for (int i = 0; i < order.size(); i++) {
            Pos c = order.get(i);
            if (r.get(c) == 0) assertTrue(FloorFill.supported(w, c), c + " has a face to click");
        }
    }

    // ---- the block ----

    @Test
    void blockChoiceNamedElseTheJunkList() {
        assertEquals(CD, FloorFill.chooseBlock(null, Map.of(CD, 3, "minecraft:cobblestone", 64)));
        assertEquals("minecraft:cobblestone", FloorFill.chooseBlock(null, Map.of("minecraft:cobblestone", 64, "minecraft:dirt", 9)));
        assertEquals("minecraft:tuff", FloorFill.chooseBlock(null, Map.of("minecraft:tuff", 1, "minecraft:diamond", 5)));
        assertNull(FloorFill.chooseBlock(null, Map.of("minecraft:diamond", 5, "minecraft:raw_iron", 9, "minecraft:oak_planks", 9)));
        assertEquals("minecraft:stone", FloorFill.chooseBlock("minecraft:stone", Map.of("minecraft:stone", 1, CD, 64)), "the named one first");
        assertNull(FloorFill.chooseBlock("minecraft:stone", Map.of(CD, 64)), "named: nothing else");
        // what may be named
        assertNull(FloorFill.namedProblem("cobbled_deepslate"));
        assertNull(FloorFill.namedProblem("minecraft:stone_bricks"));
        assertEquals("it's valuable", FloorFill.namedProblem("minecraft:diamond_block"));
        assertEquals("it's valuable", FloorFill.namedProblem("minecraft:iron_ore"));
        assertEquals("it's valuable", FloorFill.namedProblem("minecraft:raw_iron"));
        assertEquals("it falls", FloorFill.namedProblem("minecraft:gravel"));
        assertEquals("it falls", FloorFill.namedProblem("sand"));
        assertEquals("it's a container or machine", FloorFill.namedProblem("minecraft:chest"));
        assertEquals("it's a container or machine", FloorFill.namedProblem("minecraft:furnace"));
    }

    // ---- parsing ----

    @Test
    void parsesFloorAndJunkDropWithTheOldWords() {
        DigArgs a = DigArgs.parse("247 -46 853 310 -44 855");
        assertArrayEquals(new int[]{247, -46, 853, 310, -44, 855}, a.n());
        assertFalse(a.floor() || a.force() || a.ores() || a.junkDrop());
        DigArgs b = DigArgs.parse("247 -46 853 310 -44 855 floor");
        assertTrue(b.floor());
        assertNull(b.floorBlock());
        DigArgs c = DigArgs.parse("247 -46 853 310 -44 855 floor cobblestone junk drop");
        assertTrue(c.floor() && c.junkDrop());
        assertEquals("cobblestone", c.floorBlock());
        DigArgs d = DigArgs.parse("1 2 3 4 5 6 ores floor force");
        assertTrue(d.ores() && d.floor() && d.force());
        assertNull(d.floorBlock(), "a keyword after floor is not its block");
        DigArgs e = DigArgs.parse("1 2 3 4 5 6 force ores");
        assertTrue(e.force() && e.ores() && !e.floor());
        DigArgs f = DigArgs.parse("1 2 3 4 5 6 junk drop floor");
        assertTrue(f.junkDrop() && f.floor());
        assertNull(DigArgs.parse("1 2 3 4 5"));
        assertNull(DigArgs.parse("1 2 3 4 5 x"));
        assertNull(DigArgs.parse("1 2 3 4 5 6 sideways"));
        assertNull(DigArgs.parse("1 2 3 4 5 6 junk"), "junk alone isn't a word");
        assertTrue(DigArgs.USAGE.contains("[floor [block]]"));
    }

    // ---- the report ----

    @Test
    void reportNamesFiveCellsThenCountsTheRest() {
        FakeWorld w = cave();
        FloorFill.Run r = new FloorFill.Run(w, BOX, null, c -> c.x() > 263, IN_AREA, false);
        assertEquals(9, r.failed.size(), "x 261..263 refused");
        String rep = r.report();
        assertTrue(rep.startsWith("filled 0 floor cells; couldn't fill 9: 261 -47 853 (" + FloorFill.FENCED + ")"), rep);
        assertTrue(rep.endsWith(", +4 more"), rep);
        assertEquals("ok: done digging - broke 40 blocks; 2 ores left in place for you (PM \"ores\"); filled 3 floor cells",
                FloorFill.endMessage("ok: done digging - broke 12 blocks; 2 ores left in place for you (PM \"ores\")", 40, "filled 3 floor cells"));
    }

    // ---- the run ----

    @Test
    void fillsTheHoleFromTheWalkwayBridgingInward() {
        FakeWorld w = cave();
        FillDriver d = new FillDriver(w, Bot.at(258.5, -46, 854.5));
        d.bag.put(CD, 200);
        FloorFill.Run r = d.fill(new FloorFill.Run(w, BOX, null, AREA_ONLY, IN_AREA, false));
        assertEquals(30, filledLayer(w, 261, 270), r.report());
        assertEquals(30, r.filledTotal());
        assertEquals("filled 30 floor cells with cobbled_deepslate", r.report());
        assertTrue(d.minY >= -46, "never stood in the cave: " + d.minY);
        assertEquals(0, d.wildWalks, "only walks it could make");
        assertEquals("air", w.get(265, -48, 854), "the cave itself stays open");
        assertEquals("air", w.get(265, -49, 854));
        assertEquals(170, d.bag.get(CD));
        for (Pos c : d.placedCells) assertEquals(-47, c.y());
    }

    @Test
    void climbsOutOfTheCaveFirstAndEndsOnTheWalkway() {
        FakeWorld w = cave();
        FillDriver d = new FillDriver(w, Bot.at(265.5, -49, 854.5));           // at the cave bottom, 2 below the hole's rim
        d.bag.put(CD, 200);
        FloorFill.Run r = d.fill(new FloorFill.Run(w, BOX, null, AREA_ONLY, IN_AREA, false));
        assertEquals(30, filledLayer(w, 261, 270), r.report());
        assertTrue(d.y >= -46, "back up on the walkway: " + d.y);
        assertTrue(r.stepsTotal() >= 1 && r.stepsTotal() <= 3, r.report());
        assertTrue(r.report().contains("below it to climb out of the cave"), r.report());
        int below = 0;
        for (Pos c : d.placedCells) if (c.y() < -47) below++;
        assertEquals(r.stepsTotal(), below, "only the steps went below the floor");
        assertEquals(0, d.wildWalks);
    }

    @Test
    void climbOnlyWalksUpWhenThereIsAWayAndPlacesNothing() {
        FakeWorld w = cave();
        // a 1-deep part: the bot stands in the hole (feet -47 on the cave bottom at -48) and can step out
        w.fill(261, 261, -48, -48, 853, 855, "stone");
        FillDriver d = new FillDriver(w, Bot.at(261.5, -47, 854.5));
        d.bag.put(CD, 10);
        FloorFill.Run r = d.fill(new FloorFill.Run(w, BOX, null, AREA_ONLY, IN_AREA, true));
        assertTrue(d.y >= -46, "on the walkway: " + d.y);
        assertTrue(d.placedCells.isEmpty());
        assertEquals("filled 0 floor cells", r.report());
    }

    @Test
    void runsOutOfBlocksAndSaysHowManyAreLeft() {
        FakeWorld w = cave();
        FillDriver d = new FillDriver(w, Bot.at(258.5, -46, 854.5));
        d.bag.put("minecraft:cobblestone", 7);
        FloorFill.Run r = d.fill(new FloorFill.Run(w, BOX, null, AREA_ONLY, IN_AREA, false));
        assertEquals(7, r.filledTotal());
        assertTrue(r.report().contains("ran out of blocks - 23 floor cells left"), r.report());
        FillDriver d2 = new FillDriver(cave(), Bot.at(258.5, -46, 854.5));
        d2.bag.put(CD, 64);
        FloorFill.Run r2 = d2.fill(new FloorFill.Run(d2.w, BOX, "minecraft:stone", AREA_ONLY, IN_AREA, false));
        assertTrue(r2.report().contains("ran out of stone - 30 floor cells left"), r2.report());
    }

    @Test
    void nothingToDoOnASolidFloor() {
        FakeWorld w = new FakeWorld();
        w.fill(240, 310, -46, -44, 853, 855, "air");
        FillDriver d = new FillDriver(w, Bot.at(258.5, -46, 854.5));
        d.bag.put(CD, 5);
        FloorFill.Run r = d.fill(new FloorFill.Run(w, BOX, null, AREA_ONLY, IN_AREA, false));
        assertEquals("filled 0 floor cells", r.report());
        assertTrue(d.placedCells.isEmpty());
    }

    @Test
    void bodyHitsOnlyTheCellsItStandsIn() {
        assertTrue(FloorFill.bodyHits(5.5, 10, 5.5, new Pos(5, 10, 5)));
        assertTrue(FloorFill.bodyHits(5.5, 10, 5.5, new Pos(5, 11, 5)));
        assertFalse(FloorFill.bodyHits(5.5, 10, 5.5, new Pos(5, 9, 5)), "the block it stands on");
        assertFalse(FloorFill.bodyHits(5.5, 10, 5.5, new Pos(6, 10, 5)));
        assertTrue(FloorFill.bodyHits(5.9, 10, 5.5, new Pos(6, 10, 5)), "near the edge it overlaps the next cell");
    }

    @Test
    void endMessageWithoutABrokeCountKeepsTheClearsText() {
        Map<String, Integer> none = new HashMap<>();
        assertNull(FloorFill.chooseBlock(null, none));
        assertEquals("stopped: x; filled 0 floor cells", FloorFill.endMessage("stopped: x", 5, "filled 0 floor cells"));
    }
}
