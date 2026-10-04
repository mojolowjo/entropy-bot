package io.github.mojolowjo.entropybot.strip;

import io.github.mojolowjo.entropybot.clear.ClearBox;
import io.github.mojolowjo.entropybot.guard.Box;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** S1: the corridor over lava (live 2026-10-03), the protect-box refusal before a strip dig, the base count after a turn. */
class S1StripTest {
    /** The live mine: -119 -54 194 going east, its corridor dug 200 cells, the floor lava from x 58 to 81. */
    static TestWorld liveMine() {
        TestWorld w = new TestWorld();
        w.fill(new ClearBox(-119, -54, 194, 81, -53, 194), "air");
        for (int x = 58; x <= 81; x++) w.set(x, -55, 194, "lava");
        return w;
    }

    @Test
    void theLiveCorridorOverLavaSaysWhereAndWhatToDo() {
        TestWorld w = liveMine();
        MineGeom g = new MineGeom(-119, -54, 194, "east");
        StripRules.BadFloor b = StripRules.badFloor(w, g, 0, 200, true);
        assertNotNull(b);
        assertEquals(177, b.i());
        assertEquals("lava", b.what());
        assertEquals("58 -55 194", b.at().key());
        assertEquals("couldn't reach the mine: the corridor runs over lava from 58 -55 194 - mark a new mine (\"mark mine\" a few levels up, "
                + "or \"mark mine 57 -54 194 north\")", StripTexts.badFloorText(g, b));
        // the autominer's give-up match still sees it
        assertTrue(("error: " + StripTexts.badFloorText(g, b)).matches("(?s).*(blocked:|stuck|couldn't reach the mine|no progress).*"));
    }

    @Test
    void fromTheBotsCellOnlyWhatLiesAheadCounts() {
        TestWorld w = liveMine();
        MineGeom g = new MineGeom(-119, -54, 194, "east");
        // standing at x 57 (cell 176) by the corridor: the lava is ahead
        assertEquals(176, StripRules.scanFrom(g, new int[]{57, -54, 194}, 200));
        // past it at x 90 (cell 209): nothing ahead up to cell 220
        w.fill(new ClearBox(82, -54, 194, 101, -53, 194), "air");
        assertNull(StripRules.badFloor(w, g, StripRules.scanFrom(g, new int[]{90, -54, 194}, 220), 220, true));
        // far away (the base): from the entrance
        assertEquals(0, StripRules.scanFrom(g, new int[]{-30, 54, 190}, 200));
        // in a branch 16 to the side: still along the corridor
        assertEquals(30, StripRules.scanFrom(g, new int[]{-89, -54, 178}, 200));
    }

    @Test
    void unloadedCellsAreUnknownNotBad() {
        TestWorld w = liveMine();
        MineGeom g = new MineGeom(-119, -54, 194, "east");
        for (int x = 40; x <= 90; x++) w.unloaded.add(x + " 194");
        assertNull(StripRules.badFloor(w, g, 0, 200, true));
    }

    @Test
    void waterAndDeepHolesCountOnlyAfterTheWalkFailed() {
        TestWorld w = new TestWorld();
        MineGeom g = new MineGeom(0, 40, 0, "east");
        w.fill(new ClearBox(0, 40, 0, 30, 41, 0), "air");
        w.set(10, 39, 0, "water");
        w.set(20, 39, 0, "air").set(20, 38, 0, "air");
        assertNull(StripRules.badFloor(w, g, 0, 30, true), "before a walk only lava counts");
        StripRules.BadFloor b = StripRules.badFloor(w, g, 0, 30, false);
        assertEquals("water", b.what());
        assertEquals(10, b.i());
        b = StripRules.badFloor(w, g, 11, 30, false);
        assertEquals("a drop", b.what());
        assertEquals("20 39 0", b.at().key());
        assertTrue(StripTexts.badFloorText(g, b).startsWith("couldn't reach the mine: the corridor has a drop at 20 39 0"));
        // a one-deep dip is walkable (down and up again)
        w.set(20, 38, 0, "stone");
        assertNull(StripRules.badFloor(w, g, 11, 30, false));
        // lava in the corridor itself (it flowed in)
        w.set(25, 40, 0, "lava");
        assertEquals("25 40 0", StripRules.badFloor(w, g, 0, 30, true).at().key());
    }

    @Test
    void aStripDigIntoAProtectBoxIsRefusedBeforeItStarts() {
        List<Box> prot = List.of(new Box("base", "minecraft:overworld", -40, -64, 170, -10, 80, 200));
        ClearBox inside = new ClearBox(-20, 50, 180, -18, 51, 180);
        ClearBox next = new ClearBox(-9, 50, 180, -7, 51, 180);       // one block outside the box's x 10
        ClearBox far = new ClearBox(10, 50, 180, 12, 51, 180);
        assertEquals("it reaches into the protected base", StripRules.protectRefusal(prot, "minecraft:overworld", inside, false));
        assertNull(StripRules.protectRefusal(prot, "minecraft:overworld", next, false));
        assertNotNull(StripRules.protectRefusal(prot, "minecraft:overworld", next, true), "its torches would go into the box");
        assertNull(StripRules.protectRefusal(prot, "minecraft:overworld", far, true));
        assertNull(StripRules.protectRefusal(prot, "minecraft:the_nether", inside, false));
        // what the dig makes of it: an "area" kind, so a branch is skipped and a corridor turns
        String msg = "stopped: blocked:area at -20 50 180 (it reaches into the protected base)";
        assertEquals("area", StripRules.kindOf(msg));
        assertEquals(StripRules.Action.SKIP, StripRules.recover("area", "left", 1));
        assertEquals(StripRules.Action.TURN, StripRules.recover("area", "corridor", 1));
    }

    @Test
    void theBaseCountAfterATurn() {
        assertEquals("the corridor was blocked (x): mine turned west at 1 2 3 - the next run digs there; took 12 items to base",
                StripTexts.withBasePut("the corridor was blocked (x): mine turned west at 1 2 3 - the next run digs there", 12));
        assertEquals("a; took 20 items to base", StripTexts.withBasePut("a; took 12 items to base", 20));
        assertEquals("took 5 items to base", StripTexts.withBasePut(null, 5));
        // after a turn the base trip is kept (and S1 counts it with a "stripput" after it)
        assertEquals(List.of(1), StripRules.keepAfterTurn(List.of("stripdig", "stripbase", "stripdone")));
    }

    @Test
    void geometryAlongAndSide() {
        MineGeom g = new MineGeom(-119, -54, 194, "east");
        assertEquals(176, g.along(new int[]{57, -54, 194}));
        assertEquals(16, g.side(new int[]{-89, -54, 178}), "north is the left of east");
        assertEquals(40, g.indexOf(g.cell(40, 0)));
        MineGeom n = new MineGeom(0, 0, 0, "north");
        assertEquals(5, n.along(new int[]{0, 0, -5}));
        assertEquals(3, n.side(new int[]{-3, 0, 0}), "west is the left of north");
    }
}
