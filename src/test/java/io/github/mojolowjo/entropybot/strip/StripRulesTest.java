package io.github.mojolowjo.entropybot.strip;

import io.github.mojolowjo.entropybot.clear.ClearBox;
import io.github.mojolowjo.entropybot.clear.Pos;
import io.github.mojolowjo.entropybot.storage.StorageRules;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * B7d D2: the strip mine's decisions with the sim's worlds and fake clear reports (D1's engine is not needed): what
 * blocks a corridor (strip2's bedrock, stripblocked's ore, water, planks, a stuck clear), the run's verdict and
 * stripRecover's choice, the turns, mark-mine's stand check, the mine's table and chests, the base trip.
 */
class StripRulesTest {
    static final MineGeom MINE = new MineGeom(0, 40, 0, "north");

    /** The strip world after run 1: the corridor and both branch-1 arms dug. */
    static TestWorld afterRun1(int ox, boolean bedrock) {
        TestWorld w = TestWorld.strip(ox, bedrock);
        MineGeom g = new MineGeom(ox, 40, 0, "north");
        w.fill(g.corridor(1), "air").fill(g.branch(1, 1, 12), "air").fill(g.branch(1, -1, 12), "air");
        return w;
    }

    @Test
    void strip2BedrockBlocksTheCorridorAndTheMineTurnsWest() {
        TestWorld w = afterRun1(0, true);
        // run 2's corridor: everything but the bedrock is dug (the clear never targets it, so it counts as done)
        w.fill(MINE.corridor(2), "air").set(0, 40, -5, "bedrock");
        String report = "ok: done digging the mine corridor (branch 2) - broke 5 blocks; 1 ores mined";
        StripRules.Verdict v = StripRules.judge("corridor", report, () -> StripRules.corridorBlock(w, MINE.corridor(2), new int[]{0, 40, -4}, report, 2));
        assertNull(v.fail());
        assertEquals("unbreakable", v.kind());
        assertEquals("blocked:unbreakable bedrock at 0 40 -5", v.why());
        assertEquals(StripRules.Action.TURN, StripRules.recover(v.kind(), "corridor", 1));
        // left of north is west, at the last open corridor cell (k = 2: cell 3)
        List<StripRules.TurnOption> opts = StripRules.turnOptions(MINE, 2, null);
        assertEquals(new StripRules.TurnOption(new Pos(0, 40, -3), "west"), opts.get(0));
        StripRules.Turn t = StripRules.chooseTurn(opts, o -> StripRules.mineSpotCheck(w, o.pos(), o.dir(), q -> null, (x, y, z) -> "would refuse: no lease here", true));
        assertEquals("west", t.option().dir());
        String said = StripRules.turnedText(t.option().dir(), t.option().pos());
        assertEquals("mine turned west at 0 40 -3", said);
        // the run's note and the whisper, as the sim expects them
        assertEquals("the corridor was blocked (blocked:unbreakable bedrock at 0 40 -5): mine turned west at 0 40 -3 - the next run digs there",
                StripTexts.turnNote(v.why(), StripTexts.turnOk(said)));
    }

    @Test
    void anOpenCorridorGoesOn() {
        TestWorld w = afterRun1(0, false);
        w.fill(MINE.corridor(2), "air");
        String report = "ok: done digging the mine corridor (branch 2) - broke 6 blocks; 1 ores mined";
        StripRules.Verdict v = StripRules.judge("corridor", report, () -> StripRules.corridorBlock(w, MINE.corridor(2), new int[]{0, 40, -4}, report, 2));
        assertNull(v.fail());
        assertNull(v.kind());
    }

    @Test
    void theOreThatNeedsIronIsAToolBlock() {
        TestWorld w = afterRun1(100, false);
        MineGeom g = new MineGeom(100, 40, 0, "north");
        w.fill(g.corridor(2), "air").set(100, 40, -5, "deepslate_redstone_ore");
        String report = "ok: finished digging the mine corridor (branch 2) - broke 5 blocks; 1 left, e.g. 100 40 -5 needs a better tool (deepslate_redstone_ore)";
        StripRules.Block b = StripRules.corridorBlock(w, g.corridor(2), new int[]{100, 40, -3}, report, 3);
        assertEquals(new StripRules.Block("tool", "blocked:tool deepslate_redstone_ore at 100 40 -5 needs iron", "iron"), b);
        // without the clear's note it still knows: no pickaxe of that tier in the bag (copper = 3)
        assertEquals("tool", StripRules.corridorBlock(w, g.corridor(2), new int[]{100, 40, -3}, "ok: done", 3).kind());
        // with an iron pickaxe and no note, it was something else: stuck
        assertEquals("blocked:stuck deepslate_redstone_ore at 100 40 -5", StripRules.corridorBlock(w, g.corridor(2), new int[]{100, 40, -3}, "ok: done", 4).text());
        assertEquals(StripRules.Action.TOOL, StripRules.recover("tool", "corridor", 1));
        assertEquals(StripRules.Action.FAIL, StripRules.recover("tool", "corridor", 2), "it had the pickaxe, and the block still stopped it");
        assertEquals("fetched an iron pickaxe for the deepslate_redstone_ore at 100 40 -5", StripTexts.toolNote("fetched", "iron", b.text()));
        assertEquals("the mine corridor is blocked - blocked:tool deepslate_redstone_ore at 100 40 -5 needs iron - I have no iron pickaxe, none in my chests "
                + "and I couldn't make one (no recipe) - next: get iron_pickaxe 1 (or craft iron_pickaxe)", StripTexts.noTool(b.text(), "iron", "error: no recipe"));
    }

    @Test
    void waterPlanksAndNearestFirst() {
        MineGeom g = new MineGeom(130, 40, 0, "north");
        TestWorld w = afterRun1(130, false);
        w.fill(g.corridor(2), "air").set(130, 41, -5, "water");
        assertEquals("blocked:liquid water at 130 41 -5", StripRules.corridorBlock(w, g.corridor(2), new int[]{130, 40, -3}, "ok", 4).text());
        // a block next to lava (not in the box): the next-to note
        TestWorld w2 = afterRun1(130, false);
        w2.fill(g.corridor(2), "air").set(130, 40, -5, "stone").set(129, 40, -5, "lava");
        assertEquals("blocked:liquid stone at 130 40 -5 (next to water/lava)", StripRules.corridorBlock(w2, g.corridor(2), new int[]{130, 40, -3}, "ok", 4).text());
        MineGeom h = new MineGeom(160, 40, 0, "north");
        TestWorld w3 = afterRun1(160, false);
        w3.fill(h.corridor(2), "air").set(160, 40, -5, "oak_planks").set(160, 41, -6, "stone");
        StripRules.Block b = StripRules.corridorBlock(w3, h.corridor(2), new int[]{160, 40, -3}, "ok", 4);
        assertEquals("blocked:protected oak_planks at 160 40 -5 - if it's a mineshaft, PM dig 160 40 -5 160 40 -5 force", b.text(), "the nearest block first");
        TestWorld w4 = afterRun1(160, false);
        w4.fill(h.corridor(2), "air").set(160, 40, -4, "chest");
        assertEquals("blocked:protected chest at 160 40 -4 (I never break containers)", StripRules.corridorBlock(w4, h.corridor(2), new int[]{160, 40, -3}, "ok", 4).text());
    }

    @Test
    void verdicts() {
        // a stuck corridor with nothing to name: stuck, the reason without the tally
        StripRules.Verdict v = StripRules.judge("corridor", "stopped: stuck - 8 tries in a row at 0 40 -5 - broke 2 blocks", () -> null);
        assertEquals("stuck", v.kind());
        assertEquals("stuck - 8 tries in a row at 0 40 -5", v.why());
        // a stuck corridor with a block left: the block's reason
        v = StripRules.judge("corridor", "stopped: stuck - 8 tries in a row - broke 2 blocks", () -> new StripRules.Block("stuck", "blocked:stuck stone at 0 40 -5", null));
        assertEquals("blocked:stuck stone at 0 40 -5", v.why());
        // any other failure ends the run as before
        v = StripRules.judge("corridor", "stopped: out of pickaxes and I can't make more (no cobblestone) - broke 1 blocks", () -> null);
        assertEquals("out of pickaxes and I can't make more (no cobblestone) - broke 1 blocks", v.fail());
        // a branch: stuck is handled, the rest ends the run, ok goes on
        assertEquals("stuck", StripRules.judge("left", "stopped: couldn't get there (12 blocks away) - broke 0 blocks", () -> null).kind());
        assertNotNull(StripRules.judge("right", "stopped: out of pickaxes - broke 3 blocks", () -> null).fail());
        assertNull(StripRules.judge("right", "ok: finished digging branch 2 right - broke 16 blocks; 8 left", () -> null).kind());
        // a plain dig (setup, the ore step): ok goes on, else the run ends
        assertNull(StripRules.judge(null, "ok: gave up mining 3 ore blocks next to branch 1 (stuck)", () -> null).fail());
        assertEquals("couldn't place it", StripRules.judge(null, "error: couldn't place it", () -> null).fail());
        // the guard's refusal, as the dig step words it
        String area = "stopped: blocked:area at 190 40 -6 (that box is not inside one of my areas)";
        assertEquals("area", StripRules.kindOf(area));
        assertEquals("blocked:area at 190 40 -6 (that box is not inside one of my areas)", StripRules.whyOf(area));
    }

    @Test
    void recoverChoices() {
        assertEquals(StripRules.Action.RETRY, StripRules.recover("stuck", "corridor", 1));
        assertEquals(StripRules.Action.RETRY, StripRules.recover("stuck", "corridor", 2));
        assertEquals(StripRules.Action.TURN, StripRules.recover("stuck", "corridor", 3), "a corridor stuck a third time turns the mine");
        assertEquals(StripRules.Action.RETRY, StripRules.recover("stuck", "left", 1));
        assertEquals(StripRules.Action.SKIP, StripRules.recover("stuck", "left", 2), "a branch stuck again is noted bad");
        assertEquals(StripRules.Action.SKIP, StripRules.recover("area", "right", 1));
        assertEquals(StripRules.Action.TURN, StripRules.recover("area", "corridor", 1));
        assertEquals(StripRules.Action.TURN, StripRules.recover("liquid", "corridor", 1));
        assertEquals("branch 7 right skipped (outside my areas)", StripRules.skipText(7, "right", "area", "blocked:area at 1 2 3 (x)"));
        assertEquals("branch 7 left skipped (stuck - 8 tries)", StripRules.skipText(7, "left", "stuck", "stuck - 8 tries"));
    }

    @Test
    void turnsByHandAndNoWayOn() {
        // stripblocked "liquid": the mine turned west at 130 40 -3; right of west is north, into the water's corridor
        TestWorld w = afterRun1(130, false);
        MineGeom old = new MineGeom(130, 40, 0, "north");
        w.fill(old.corridor(2), "air").set(130, 41, -5, "water");
        MineGeom west = new MineGeom(130, 40, -3, "west");
        StripRules.Turn t = StripRules.chooseTurn(StripRules.turnOptions(west, 1, "right"),
                o -> StripRules.mineSpotCheck(w, o.pos(), o.dir(), q -> null, null, true));
        assertTrue(t.err().startsWith("the mine can't turn - north at 130 40 -3: water or lava at 130 4"), t.err());
        t = StripRules.chooseTurn(StripRules.turnOptions(west, 1, "left"), o -> StripRules.mineSpotCheck(w, o.pos(), o.dir(), q -> null, null, true));
        assertEquals(new StripRules.TurnOption(new Pos(130, 40, -3), "south"), t.option());
        // "no way on": every option refused by the guard, each named
        TestWorld w2 = afterRun1(190, false);
        MineGeom m = new MineGeom(190, 40, -3, "west");
        StripRules.Turn none = StripRules.chooseTurn(StripRules.turnOptions(m, 1, null), o -> StripRules.mineSpotCheck(w2, o.pos(), o.dir(), q -> null,
                (x, y, z) -> z < -4 || z > -2 ? "would refuse: outside every area" : "would refuse: no lease here", true));
        assertEquals("the mine can't turn - south at 190 40 -3: the guard says no at 190 40 -1 (outside every area); north at 190 40 -3: the guard says no at 190 40 -5 (outside every area)", none.err());
        // with k > 1 the fresh mines 8 along the last branch come after left and right
        List<StripRules.TurnOption> all = StripRules.turnOptions(MINE, 3, null);
        assertEquals(6, all.size());
        assertEquals(new StripRules.TurnOption(new Pos(-8, 40, -6), "north"), all.get(2));
        assertEquals(new StripRules.TurnOption(new Pos(8, 40, -6), "east"), all.get(5));
    }

    @Test
    void theGuardInLogModeOnlyNotes() {
        TestWorld w = afterRun1(0, false);
        // a log-mode refusal of the area counts only with the fence on
        assertNull(StripRules.mineSpotCheck(w, new Pos(0, 40, -3), "west", q -> null, (x, y, z) -> "would refuse (log mode): outside every area", false));
        assertNotNull(StripRules.mineSpotCheck(w, new Pos(0, 40, -3), "west", q -> null, (x, y, z) -> "would refuse: protected (base)", false));
        assertEquals("outside my areas", StripRules.mineSpotCheck(w, new Pos(0, 40, -3), "west", q -> "outside my areas", null, true));
        assertEquals("I can't see 0 40 -3 from here", StripRules.mineSpotCheck(unloaded(afterRun1(0, false)), new Pos(0, 40, -3), "west", q -> null, null, true));
    }

    private static TestWorld unloaded(TestWorld w) {
        w.unloaded.add("0 -3");
        return w;
    }

    @Test
    void standCheckAsMarkMine() {
        TestWorld w = TestWorld.strip(0, false);
        assertEquals("I can't stand at 0 40 50 (stone)", StripRules.standCheck(w, new Pos(0, 40, 50)));
        w.set(0, 40, 50, "air");
        assertEquals("I can't stand at 0 40 50 (stone above it)", StripRules.standCheck(w, new Pos(0, 40, 50)));
        w.set(0, 41, 50, "air").set(0, 39, 50, "air");
        assertEquals("there is nothing to stand on at 0 40 50", StripRules.standCheck(w, new Pos(0, 40, 50)));
        assertNull(StripRules.standCheck(w, new Pos(0, 40, 0)));
        assertEquals("error: the mine entrance 0 40 50 is no place to start: there is nothing to stand on at 0 40 50 - mark it where I can stand",
                StripTexts.badEntrance("mine", new Pos(0, 40, 50), "there is nothing to stand on at 0 40 50"));
    }

    @Test
    void setupPicksTheOldSpots() {
        StripRules.Setup s = StripRules.setupPlan(TestWorld.strip(0, false), MINE);
        assertNull(s.err());
        assertNull(s.note());
        assertEquals(List.of(
                "dig -1 40 0 to -1 41 1 '" + "digging room for the mine chests'",
                "dig 1 40 0 to 1 40 0 'digging room for a crafting table'",
                "craft crafting_table unless minecraft:crafting_table 1",
                "place minecraft:crafting_table 1 40 0",
                "craft chest 2 unless minecraft:chest 2",
                "place minecraft:chest -1 40 0",
                "place minecraft:chest -1 40 1",
                "setupdone [-1 40 0, -1 40 1] table 1 40 0"), s.items().stream().map(StripPlan.Item::toString).toList());
    }

    @Test
    void setupUsesAChestAlreadyThere() {
        // the sim's minesetup: an old chest next to the entrance (101 40 0) is used, one more is placed
        MineGeom g = new MineGeom(100, 40, 0, "north");
        TestWorld w = TestWorld.strip(100, false);
        w.set(101, 40, 0, "chest");
        StripRules.Setup s = StripRules.setupPlan(w, g);
        assertEquals("used the chest at 101 40 0 already there", s.note());
        List<String> l = s.items().stream().map(StripPlan.Item::toString).toList();
        assertTrue(l.contains("craft chest 1 unless minecraft:chest 1"), l.toString());
        // the new chest on the old left spot, the table on the next free one (the right spot holds the old chest)
        assertEquals("setupdone [101 40 0, 99 40 0] table 99 40 1", l.get(l.size() - 1));
    }

    @Test
    void setupWithNoRoomSaysSo() {
        TestWorld w = TestWorld.strip(0, false);
        for (int x = -3; x <= 3; x++) for (int z = -3; z <= 3; z++) for (int y = 39; y <= 41; y++) if (!(x == 0 && z == 0 && y > 39)) w.set(x, y, z, "water");
        assertEquals("couldn't place the crafting_table: no free cell next to the mine entrance 0 40 0", StripRules.setupPlan(w, MINE).err());
    }

    @Test
    void baseTripAndBagRoom() {
        // the sim's strip: 70 raw copper over the keep (0) = a trip
        assertTrue(StripRules.baseTrip(Map.of("minecraft:raw_copper", 0), Map.of("minecraft:raw_copper", 70), 20));
        assertFalse(StripRules.baseTrip(Map.of("minecraft:raw_iron", 0), Map.of("minecraft:raw_iron", 10), 20));
        assertTrue(StripRules.baseTrip(Map.of("minecraft:raw_iron", 0), Map.of("minecraft:raw_iron", 10), 4), "4 slots left for ore");
        assertFalse(StripRules.baseTrip(Map.of(), Map.of(), 0), "nothing to take");
        // 16 coal kept: 20 coal is 4 valuables
        assertFalse(StripRules.baseTrip(Map.of("minecraft:coal", 16), Map.of("minecraft:coal", 20), 10));
        List<StorageRules.Held> slots = new ArrayList<>();
        for (int i = 0; i < 36; i++) slots.add(null);
        slots.set(0, new StorageRules.Held("minecraft:dirt", 64, false, 0));
        slots.set(1, new StorageRules.Held("minecraft:cobblestone", 64, false, 1));
        slots.set(2, new StorageRules.Held("minecraft:cobblestone", 64, false, 2));
        slots.set(3, new StorageRules.Held("minecraft:raw_iron", 9, false, 3));
        // dirt goes in the next dump, one stack of cobblestone is kept, raw iron is no junk: 32 free + dirt + 1 cobblestone
        assertEquals(34, StripRules.bagRoom(slots, Map.of("minecraft:dirt", 0, "minecraft:cobblestone", 64)));
    }

    @Test
    void skipHints() {
        String r = "ok: finished digging branch 2 right - broke 16 blocks; 8 left, e.g. 8 41 -6 next to water/lava, 9 41 -6 out of reach (nowhere to stand close enough)";
        assertEquals("next to water/lava", StripRules.skipHint(r, "8 41 -6"));
        assertEquals("out of reach (nowhere to stand close enough)", StripRules.skipHint(r, "9 41 -6"));
        assertEquals("", StripRules.skipHint(r, "-8 41 -6"));
        assertEquals("", StripRules.skipHint(null, "1 2 3"));
    }

    @Test
    void keepAfterATurn() {
        assertEquals(List.of(2), StripRules.keepAfterTurn(List.of("stripdig", "stripore", "stripbase", "stripdone")));
        assertEquals(List.of(1, 3, 4, 5), StripRules.keepAfterTurn(List.of("stripdig", "stripbase", "stripdone", "stripnext", "stripleg", "stripdig")));
        assertEquals(List.of(), StripRules.keepAfterTurn(List.of("stripdig", "stripdone")));
    }

    @Test
    void theUnionBoxDecidesCollecting() {
        // a mine whose right branch reaches past the area's edge collects nothing in that branch pair
        MineGeom edge = new MineGeom(215, 40, 0, "north");
        ClearBox u = edge.union(1, 12);
        assertEquals(new ClearBox(203, 40, -3, 227, 41, -1), u);
        assertFalse(StripPlanTest.MAP.test(u));
    }
}
