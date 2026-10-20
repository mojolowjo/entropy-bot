package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.brain.ToolWear;
import io.github.mojolowjo.entropybot.aa.core.RestoreProgress;
import io.github.mojolowjo.entropybot.commands.StairPlan;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** AA_ROUND3 1: the one way-up option set, the return levels, the restore progress. */
class WayUpTest {
    static final int[] ME = {0, 40, 0};
    static final ToolWear.Kit KIT = new ToolWear.Kit(4, 100, ToolWear.CRAFT_S);

    static List<Replan.Cell> col(int n, Replan.Cell c) {
        List<Replan.Cell> out = new ArrayList<>();
        for (int i = 0; i < n; i++) out.add(c);
        return out;
    }

    static WayUp.Heading heading(String dir, int n, Replan.Cell c, int[] outside) {
        return new WayUp.Heading(dir, n, col(n * 4, c), outside);
    }

    static RungOptions.SurfaceFacts facts(int depth, List<Replan.Cell> column, List<WayUp.Heading> hs, int[] trail, double trailLen, int[] sky, double skyDist) {
        return new RungOptions.SurfaceFacts(depth, ME, false, 20, true, true, trail, trailLen, sky, skyDist, false, KIT, column, hs);
    }

    @Test
    void theSixOptionsArePriced() {
        // an open own shaft: pillar at 0.05 a level
        var c = WayUp.choose(facts(6, col(6, Replan.Cell.air()), List.of(heading("east", 6, Replan.Cell.stone(false), null)), null, 0, null, -1), new Options.OptionMemory(), 0);
        assertEquals("pillar", c.best().id(), c.why());
        assertEquals("goto 0 46 0", c.best().command());
        assertTrue(c.ranked().stream().anyMatch(o -> o.id().equals("stairs east")), c.why());
        // rock overhead: tunnel (breaks + the tool's seconds and wear) vs stairs, trail, sky
        c = WayUp.choose(facts(6, col(6, Replan.Cell.stone(false)), List.of(heading("east", 6, Replan.Cell.stone(false), null)),
                new int[]{30, 60, 0}, 200, new int[]{10, 60, 0}, 90), new Options.OptionMemory(), 0);
        assertEquals("tunnel", c.best().id(), c.why());
        assertTrue(c.best().why().contains("tools use"), "the wear term is in the price: " + c.best().why());
        assertEquals(List.of("tunnel", "stairs east", "sky", "trail"), c.ranked().stream().map(Options.Option::id).toList(), c.why());
        // a near open-sky cell beats digging
        c = WayUp.choose(facts(6, col(6, Replan.Cell.stone(false)), List.of(), null, 0, new int[]{3, 41, 0}, 4), new Options.OptionMemory(), 0);
        assertEquals("sky", c.best().id());
        // none fits: wait (no best; the line says why)
        var none = new RungOptions.SurfaceFacts(6, ME, false, 0, false, false, null, 0, null, -1, false, new ToolWear.Kit(0, 0, ToolWear.INF), null, List.of(heading("east", 6, Replan.Cell.stone(false), null)));
        c = WayUp.choose(none, new Options.OptionMemory(), 0);
        assertNull(c.best());
        assertTrue(c.why().contains("stairs east: no pickaxe"), c.why());
    }

    @Test
    void stairsStayInsideTheAreasAndAFailedHeadingIsHeld() {
        List<WayUp.Heading> hs = List.of(heading("west", 6, Replan.Cell.stone(false), new int[]{-3, 43, 0}),
                heading("north", 6, Replan.Cell.stone(false), null),
                heading("south", 6, new Replan.Cell(false, false, Double.POSITIVE_INFINITY, false, false, false), null));
        var m = new Options.OptionMemory();
        var c = WayUp.choose(facts(6, null, hs, null, 0, null, -1), m, 0);
        assertEquals("stairs north", c.best().id(), c.why());
        assertTrue(c.why().contains("stairs west: leaves my areas at -3 43 0"), c.why());
        assertTrue(c.why().contains("stairs south: a cell may not be broken"), c.why());
        assertTrue(c.best().command().startsWith("dig 0 41 -1 0 42 0 ores via engine then goto 0 41 -1"), c.best().command());
        m.failed(c.best().memoryKey(), "stopped at step 9 (dig ...): that box is not inside one of my areas", 0);
        c = WayUp.choose(facts(6, null, hs, null, 0, null, -1), m, 1);
        assertNull(c.best(), "north is held, west and south dropped: " + c.why());
        assertTrue(c.why().contains("stairs north:"), c.why());
        // too short (the 40-step cap) does not reach the step-out level
        c = WayUp.choose(facts(50, null, List.of(heading("east", 40, Replan.Cell.air(), null)), null, 0, null, -1), new Options.OptionMemory(), 0);
        assertTrue(c.why().contains("reach only 40 of 50"), c.why());
    }

    @Test
    void stairStepsMatchTheVerbAndCellsCoverThem() {
        assertEquals(StairPlan.upSteps(5, 60, -3, 1, 0, 7), WayUp.stairSteps(5, 60, -3, 1, 0, 7));
        assertEquals(StairPlan.upSteps(5, 60, -3, 0, -1, 3), WayUp.stairSteps(5, 60, -3, 0, -1, 3));
        assertEquals(4 * 7, WayUp.stairCells(5, 60, -3, 1, 0, 7).size());
        assertArrayEquals(new int[]{5, 61, -3}, WayUp.stairCells(5, 60, -3, 1, 0, 7).get(0), "step 1 starts at the head cell");
    }

    @Test
    void aSixDeepOwnShaftIsSixLevels() {
        // aa388: a 1-wide shaft dug from the surface, 6 deep; side walls up to the rim, open above the rim
        int depth = 6;
        int hole = AaEscape.holeExit(AaEscape.HOLE_MAX_UP, dy -> true, dy -> dy >= depth);
        assertEquals(6, hole);
        assertEquals(6, WayUp.returnLevels(0, hole, 1), "never the 1 the sky scan gave");
        assertEquals(12, WayUp.returnLevels(9, -1, 12), "ground overhead: the sky scan");
        assertEquals(0, WayUp.returnLevels(0, 0, 5), "out already");
        var w = Replan.chooseNow(col(6, Replan.Cell.air()), 6, 20, Replan.walkCost(6, 0), KIT);
        assertEquals(Replan.Leg.PILLAR, w.leg(), w.why());
        assertTrue(w.why().startsWith("pillar 0.3 (6 up"), w.why());
    }

    @Test
    void theOldFlagsStillChooseThroughWayUp() {
        assertEquals(AaEscape.Way.PILLAR, AaEscape.choose(6, true, 20, true).way());
        assertEquals(AaEscape.Way.CLIMB, AaEscape.choose(6, false, 64, true).way());
        assertEquals(AaEscape.Way.NONE, AaEscape.choose(6, false, 64, false).way());
    }

    @Test
    void restorePlacementsMoveTheProgressCounter() {
        long p = 10;
        int last = -1;
        for (int placed = 0; placed < 8; placed++) {          // a fake restore, one block a tick
            p = RestoreProgress.tick(p, last, placed);
            last = placed;
        }
        assertEquals(17, p, "seven changes after the first reading");
        assertEquals(17, RestoreProgress.tick(17, 7, 7), "no placement: no progress");
        assertEquals(17, RestoreProgress.tick(17, 7, -1), "the restore ended");
    }
}
