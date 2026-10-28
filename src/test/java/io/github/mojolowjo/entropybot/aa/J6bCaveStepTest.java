package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.NeedChain.CaveWay;
import io.github.mojolowjo.entropybot.aa.NeedChain.Chain;
import io.github.mojolowjo.entropybot.aa.brain.TestData;
import io.github.mojolowjo.entropybot.aa.brain.Tuning;
import io.github.mojolowjo.entropybot.aa.moves.FactChange;
import io.github.mojolowjo.entropybot.aa.moves.Step;
import io.github.mojolowjo.entropybot.cave.CaveWalk;
import io.github.mojolowjo.entropybot.cave.ExploreRules;
import io.github.mojolowjo.entropybot.sense.CaveIndex;
import io.github.mojolowjo.entropybot.sense.CaveRecord;
import io.github.mojolowjo.entropybot.sense.Sightings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 0.39.z78 J6b (D12, to-do/j6b-caving-step.md): the caving step's decisions on the record, the cave way priced from the
 * record, and item 5 - the explore's surface goal and the walk yielding to a credited opening.
 */
class J6bCaveStepTest {
    @AfterEach
    void clear() {
        TestData.clear();
        FactChange.clear();
        Tuning.reset();
    }

    static CaveRecord.Facts facts(boolean loaded, boolean done) {
        return new CaveRecord.Facts(0, 40, 0, 200, 80, 0.6, 2, done, Map.of(), 0, Double.NaN, 0, loaded, "test");
    }

    static final int[] FEET = {10, 40, 0};

    @Test
    void theStepsMoves_inOrder() {
        List<CaveRecord.Group> two = List.of(new CaveRecord.Group(Sightings.cell(40, 40, 0), 1, 40), new CaveRecord.Group(Sightings.cell(100, 40, 0), 1, 40));
        assertEquals(CaveWalk.End.NEED_MET, ((CaveWalk.Stop) CaveWalk.next(3, 3, 1, 10, facts(true, false), two, List.of(), FEET, 4.5)).end());
        assertEquals(CaveWalk.End.MINUTES, ((CaveWalk.Stop) CaveWalk.next(1, 3, 10, 10, facts(true, false), two, List.of(), FEET, 4.5)).end());
        var mine = CaveWalk.next(0, 3, 1, 10, facts(true, false), two, List.of(new int[]{12, 41, 0}, new int[]{60, 40, 1}), FEET, 4.5);
        assertArrayEquals(new int[]{12, 41, 0}, ((CaveWalk.Mine) mine).cell(), "a seen ore in reach: mined");
        var toOre = CaveWalk.next(0, 3, 1, 10, facts(true, false), two, List.of(new int[]{60, 40, 1}), FEET, 4.5);
        assertTrue(((CaveWalk.Goto) toOre).why().startsWith("the seen ore at 60 40 1"), "a seen ore farther in: walked to before a branch");
        var branch = CaveWalk.next(0, 3, 1, 10, facts(true, false), two, List.of(), FEET, 4.5);
        assertArrayEquals(new int[]{40, 40, 0}, ((CaveWalk.Goto) branch).cell(), "the nearest branch (ranked rough, by straight distance)");
        assertTrue(((CaveWalk.Goto) branch).why().contains("more than " + CaveRecord.BRANCH_MIN + " cells behind"), "run EK: behind is asked only as more than BRANCH_MIN");
        var nook = CaveWalk.next(0, 3, 1, 10, facts(true, true), List.of(new CaveRecord.Group(Sightings.cell(40, 40, 0), 1, CaveRecord.BRANCH_MIN)), List.of(), FEET, 4.5);
        assertEquals(CaveWalk.End.DONE, ((CaveWalk.Stop) nook).end(), "a nook is no branch: done");
        assertEquals(CaveWalk.End.NOT_LOADED, ((CaveWalk.Stop) CaveWalk.next(0, 3, 1, 10, facts(false, false), List.of(), List.of(), FEET, 4.5)).end());
    }

    @Test
    void theEndLineSaysWhatTheRunMeasures() {
        String l = CaveWalk.endLine("0 40 0", CaveWalk.End.DONE, "explored", 2.42, 40, facts(true, true), 4, 3);
        assertEquals("cave: the caving step at 0 40 0 ended - DONE (explored): 2.4 min inside, cells seen 40 -> 120 of 200 (explored 60%), ore seen 4 / mined 3, branches left 2", l);
    }

    /** The cave way from the record: ore seen and left is mined first; the rest at the measured yield in what the unseen part holds. */
    @Test
    void theCaveWayIsPricedFromTheRecord() {
        CaveWay left = new CaveWay(20, 21, -10, 30, "minecraft:iron_ore", "iron", 0.5, true, CaveIndex.Size.SMALL, 0.2, List.of("21,20,-10", "22,20,-10", "23,20,-10"));
        Chain c = NeedChain.resolve("raw_iron", 3, Z77CaveWayTest.world(left));
        assertTrue(Z77CaveWayTest.caveMined(c), "3 seen and left there: a way though its unseen part holds 0.2 min - " + c.why());
        Step.Cave k = (Step.Cave) c.steps().stream().filter(s -> s instanceof Step.Cave).findFirst().orElseThrow();
        assertEquals(1, k.minutes(), "at least a minute for the walk to the left ore");
        CaveWay little = new CaveWay(20, 21, -10, 30, "minecraft:iron_ore", "iron", 0.5, true, CaveIndex.Size.LARGE, 0.2, List.of());
        Chain d = NeedChain.resolve("raw_iron", 3, Z77CaveWayTest.world(little));
        assertFalse(Z77CaveWayTest.caveMined(d));
        assertTrue(d.why().contains("min its unseen part holds"), "the record's unseen part, not the size bucket: " + d.why());
        CaveWay measured = new CaveWay(20, 21, -10, 30, "minecraft:iron_ore", "iron", 3, true, CaveIndex.Size.LARGE, 8, List.of());
        Chain e = NeedChain.resolve("raw_iron", 3, Z77CaveWayTest.world(measured));
        assertTrue(Z77CaveWayTest.caveMined(e), e.why());
        assertTrue(e.why().contains("yield ~3.00 ore/min (measured)"), e.why());
    }

    /** J6b review 2: the cave way's yield is the one measured there once walked, the prior before. */
    @Test
    void theMeasuredYieldAfterTheFirstWalk() {
        var never = new CaveRecord.Facts(0, 40, 0, 200, 80, 0.6, 2, false, Map.of(), 0, Double.NaN, 0, true, "t");
        assertEquals(0.3, never.rate(0.3), 1e-9, "never walked: the prior");
        var walked = new CaveRecord.Facts(0, 40, 0, 200, 80, 0.6, 2, false, Map.of(), 4, 0.5, 0, true, "t");
        assertEquals(0.5, walked.rate(0.3), 1e-9, "walked: the measured 0.5 a minute");
    }

    /** J6b review 1 and 4: a cave done with none of the family left is no way; a walk that gained nothing holds it until something new is known. */
    @Test
    void doneWithNoneLeftIsNoWay_aWalkThatGainedNothingIsHeldUntilSomethingNew() {
        Sightings s = new Sightings();
        s.knowCave("d", new CaveIndex.Known(0, 40, 0, CaveIndex.Size.SMALL, CaveIndex.Depth.STONE, List.of(Sightings.cell(1, 40, 0))), 1);
        List<Long> cells = new java.util.ArrayList<>();
        for (int x = 0; x < 30; x++) cells.add(Sightings.cell(x, 40, 0));
        s.caveCells("d", 0, 40, 0, cells);
        s.caveStood("d", 0, 40, 0, 0, 40, 0, 1);
        assertTrue(s.caveFacts("d", 0, 40, 0).offeredFor("iron"));
        s.caveWalked("d", 0, 40, 0, 2, 0, 2, false);                  // a MINUTES / NO_PROGRESS end that saw nothing new
        assertFalse(s.caveFacts("d", 0, 40, 0).offeredFor("iron"), "held: no loop back into the same cave");
        s.caveStood("d", 0, 40, 0, 6, 40, 0, 3);                       // something new is seen of it (passing by)
        assertTrue(s.caveFacts("d", 0, 40, 0).offeredFor("iron"), "lifted by a new fact");
        s.caveSaw("d", 0, 40, 0, cells);
        assertFalse(s.caveFacts("d", 0, 40, 0).offeredFor("iron"), "done, no iron seen: no way");
        s.caveOre("d", 0, 40, 0, Sightings.cell(5, 41, 0), "iron");
        assertTrue(s.caveFacts("d", 0, 40, 0).offeredFor("iron"), "done, iron seen and left: a way for iron");
        assertFalse(s.caveFacts("d", 0, 40, 0).offeredFor("gold"));
    }

    /** J6b review 6: a seen ore the chain takes by a collect way is not counted again by the cave way. */
    @Test
    void aSeenOreTakenByACollectWayIsNotCountedAgainByTheCaveWay() {
        List<String> left = List.of("21,20,-10", "22,20,-10", "23,20,-10");
        assertEquals(left, NeedChain.freeLeft(left, Map.of()));
        assertEquals(List.of("21,20,-10", "23,20,-10"), NeedChain.freeLeft(left, Map.of("22,20,-10", 1)), "taken by the collect: out");
    }

    /**
     * Item 5, the owner's "when exploring it should not break blocks": a target behind a hill that the fork's no-break walk
     * cannot reach ("no path (no-break walk)") fails at once and is passed over; the next target is picked.
     */
    @Test
    void anUnreachableNoBreakTargetIsDropped_theNextOnePicked() {
        assertTrue(ExploreRules.noBreakNoPath("no path (no-break walk)"));
        assertFalse(ExploreRules.noBreakNoPath("no path"), "a plain walk's no path is not the no-break one");
        assertFalse(ExploreRules.noBreakNoPath("arrived"));
        assertFalse(ExploreRules.noBreakNoPath(""));
        int[] feet = {0, 64, 0};
        var hill = new ExploreRules.Target(40, 0);
        var h = ExploreRules.TargetHold.start(hill, 0, feet, 0);
        h = ExploreRules.TargetHold.failNow(h, "no path without breaking or placing (the no-break walk)");
        assertEquals(ExploreRules.TargetHold.State.FAILED, h.state());
        var failed = List.of(new ExploreRules.TargetHold.Failed(hill.x(), hill.z(), feet, h.why()));
        assertTrue(ExploreRules.TargetHold.skip(failed, 40 >> 4, 0, feet, 32), "the hill's chunk is passed over from here");
        List<int[]> openings = List.of(new int[]{40, 64, 0}, new int[]{-30, 66, 5});
        assertArrayEquals(new int[]{-30, 66, 5}, ExploreRules.toward(openings, c -> ExploreRules.TargetHold.skipCell(failed, c[0], c[2], feet, 32)), "the next one");
        assertEquals(ExploreRules.TargetHold.State.REACHED, ExploreRules.TargetHold.failNow(
                ExploreRules.TargetHold.look(ExploreRules.TargetHold.start(hill, 0, new int[]{40, 64, 0}, 0), 1, new int[]{40, 64, 0}, 0, true), "x").state(), "a reached hold stays reached");
    }

    /** 57db13b7 review (a): only growth lifts the stuck mark; (b): an owner stop leaves it as it was. */
    @Test
    void theStuckMark_liftedOnlyByGrowth_keptByAStop() {
        Sightings s = new Sightings();
        s.knowCave("d", new CaveIndex.Known(0, 40, 0, CaveIndex.Size.SMALL, CaveIndex.Depth.STONE, List.of(Sightings.cell(1, 40, 0))), 1);
        List<Long> cells = new java.util.ArrayList<>();
        for (int x = 0; x < 30; x++) cells.add(Sightings.cell(x, 40, 0));
        s.caveCells("d", 0, 40, 0, cells);
        s.caveStood("d", 0, 40, 0, 0, 40, 0, 1);
        s.caveOre("d", 0, 40, 0, Sightings.cell(3, 41, 0), "iron");
        s.caveWalked("d", 0, 40, 0, 2, 0, 2, false);
        assertFalse(s.caveFacts("d", 0, 40, 0).offeredFor("iron"), "stuck");
        s.caveMined("d", 0, 40, 0, Sightings.cell(3, 41, 0));
        assertFalse(s.caveFacts("d", 0, 40, 0).offeredFor("iron"), "a mined ore is no new knowledge: still stuck");
        s.caveWalked("d", 0, 40, 0, 1, 0, 3, null);                    // an owner stop
        assertFalse(s.caveFacts("d", 0, 40, 0).offeredFor("iron"), "a stop leaves the mark");
        s.caveOre("d", 0, 40, 0, Sightings.cell(9, 39, 0), "iron");    // a new ore seen: growth
        assertTrue(s.caveFacts("d", 0, 40, 0).offeredFor("iron"), "lifted by growth");
    }

    /**
     * 57db13b7 review (c): each way is tried from the same snapshot and only the winner's marks stay (NeedChain.choose) -
     * the cave way that counts the seen ore at 8 64 0 loses to collecting it, and the collect still counts all 3.
     */
    @Test
    void theCaveWayLosesToACollect_theOreStillCountedByTheCollect() {
        var near3 = new NeedChain.Spot(8, 64, 0, 8, "minecraft:iron_ore", 3);
        CaveWay cw = new CaveWay(20, 21, -10, 30, "minecraft:iron_ore", "iron", 3, true, CaveIndex.Size.LARGE, 8, List.of("8,64,0"));
        Chain c = NeedChain.resolve("raw_iron", 3, Z77CaveWayTest.world(cw, near3));
        assertTrue(c.ok() && !Z77CaveWayTest.caveMined(c), c.why());
        Chain ctl = NeedChain.resolve("raw_iron", 3, Z77CaveWayTest.world(null, near3));
        assertEquals(ctl.seconds(), c.seconds(), 1e-9, "the collect priced as with no cave at all: its 3 ore all counted - " + c.why());
    }

    /**
     * Run EF (1)+(2): an opening cell above a 4-block drop, the bot at the lip - the walk is done there (the entry in
     * reach; never a goto into the opening), and the caving step goes on down the drop to its first branch (Baritone
     * digs or descends, the cave way's lease).
     */
    @Test
    void anOpeningAboveADrop_theWalkIsDoneAtTheLip_theCaveStepDescends() {
        int[] entry = {5, 64, 5}, lip = {4, 64, 5};                     // the lip: the ground beside the hole, feet at y 64
        assertTrue(ExploreRules.withinReach(lip, entry), "the entry in reach from the lip: the walk is done");
        assertFalse(ExploreRules.withinReach(new int[]{-2, 64, 5}, entry), "7 blocks off: walking");
        Sightings s = new Sightings();
        s.knowCave("d", new CaveIndex.Known(5, 64, 5, CaveIndex.Size.SMALL, CaveIndex.Depth.HIGH, List.of(Sightings.cell(5, 63, 5))), 1);
        List<Long> cells = new java.util.ArrayList<>();
        for (int y = 60; y <= 64; y++) cells.add(Sightings.cell(5, y, 5));          // the 4-block drop under the opening
        for (int x = 6; x <= 30; x++) cells.add(Sightings.cell(x, 60, 5));         // the tunnel at its foot
        s.caveCells("d", 5, 64, 5, cells);
        s.caveStood("d", 5, 64, 5, lip[0], lip[1], lip[2], 1);
        var next = CaveWalk.next(0, 3, 0, 10, s.caveFacts("d", 5, 64, 5), s.caveFrontier("d", 5, 64, 5), List.of(), lip, 4.5);
        assertTrue(next instanceof CaveWalk.Goto, "from the lip: into the cave - " + next);
        assertTrue(((CaveWalk.Goto) next).cell()[1] <= 60, "down the drop: " + java.util.Arrays.toString(((CaveWalk.Goto) next).cell()));
    }

    /**
     * Run EL: the lip goal for the JUnit - the fork's GoalReachBlock rule (GoalReachBlock.java: the cube ceil(reach) + 1
     * each way and ceil(eye) + 1 more below, never the entry nor the cell over it, the entry's centre within reach of the
     * eye, standable, a face in sight) mirrored here because BlockPos is not on the test classpath; the world a set of
     * solid cells, the sight a ray marched from the eye to the entry's centre.
     */
    static ExploreRules.LipGoal reachGoal(int[] e, java.util.Set<Long> solid) {
        double reach = ExploreRules.OPENING_REACH, eye = 1.62;
        int r = (int) Math.ceil(reach) + 1, up = (int) Math.ceil(eye) + 1;
        java.util.Set<Long> in = new java.util.HashSet<>();
        for (int dx = -r; dx <= r; dx++)
            for (int dz = -r; dz <= r; dz++)
                for (int dy = -r - up; dy <= r; dy++) {
                    int x = e[0] + dx, y = e[1] + dy, z = e[2] + dz;
                    if (dx == 0 && dz == 0 && (dy == 0 || dy == 1)) continue;
                    double ex = x + 0.5 - (e[0] + 0.5), ey = y + eye - (e[1] + 0.5), ez = z + 0.5 - (e[2] + 0.5);
                    if (ex * ex + ey * ey + ez * ez > reach * reach) continue;
                    boolean stand = !solid.contains(Sightings.cell(x, y, z)) && !solid.contains(Sightings.cell(x, y + 1, z)) && solid.contains(Sightings.cell(x, y - 1, z));
                    if (stand && sees(x + 0.5, y + eye, z + 0.5, e, solid)) in.add(Sightings.cell(x, y, z));
                }
        return new ExploreRules.LipGoal() {
            @Override public boolean isEmpty() { return in.isEmpty(); }
            @Override public boolean isInGoal(int x, int y, int z) { return in.contains(Sightings.cell(x, y, z)); }
        };
    }

    private static boolean sees(double ax, double ay, double az, int[] e, java.util.Set<Long> solid) {
        double bx = e[0] + 0.5, by = e[1] + 0.5, bz = e[2] + 0.5;
        int n = 400;
        for (int i = 0; i <= n; i++) {
            double t = i / (double) n;
            int x = (int) Math.floor(ax + (bx - ax) * t), y = (int) Math.floor(ay + (by - ay) * t), z = (int) Math.floor(az + (bz - az) * t);
            if (x == e[0] && y == e[1] && z == e[2]) return true;
            if (solid.contains(Sightings.cell(x, y, z))) return false;
        }
        return true;
    }

    /** Solid ground below y 65 round {@code c} (16 each way), less the {@code air} cells. */
    static java.util.Set<Long> ground(int[] c, List<int[]> air) {
        java.util.Set<Long> s = new java.util.HashSet<>();
        for (int x = c[0] - 16; x <= c[0] + 16; x++) for (int z = c[2] - 16; z <= c[2] + 16; z++) for (int y = c[1] - 16; y < 65; y++) s.add(Sightings.cell(x, y, z));
        for (int[] a : air) s.remove(Sightings.cell(a[0], a[1], a[2]));
        return s;
    }

    /**
     * Run EL's geometry: the entry -34 62 80 with the opening cell -34 63 80 over it, the ground's top at 64; the bot at
     * -34 65 80, 3 over the entry (in reach: 4.12), its eye ray down through the block it stands on. The reach goal has no
     * cell, so it is not arrived: the step's named fact. (The deleted GoalNear 3 stand-in held it at distance 3.)
     */
    @Test
    void elsGeometry_theRimCellOverTheEntryIsNotArrived() {
        int[] entry = {-34, 62, 80}, feet = {-34, 65, 80};
        var goal = reachGoal(entry, ground(entry, List.of(entry, new int[]{-34, 63, 80})));
        assertTrue(ExploreRules.withinReach(feet, entry), "in reach - the old test's reach part passed");
        assertNotEquals(ExploreRules.Lip.ARRIVED, ExploreRules.lip(goal, feet));
        assertEquals(ExploreRules.Lip.NO_CELL, ExploreRules.lip(goal, feet), "no standable cell sees the entry: the named fact");
        assertEquals("no standable cell within reach and sight of the entry -34 62 80", ExploreRules.noLipCell(entry));
        assertEquals(ExploreRules.Lip.APPROACH, ExploreRules.lip(null, feet), "the cube not loaded: an approach, never done");
    }

    /** An entry with exactly one valid rim cell (a side pocket at 1 60 0): arrived there and nowhere else. */
    @Test
    void anEntryWithOneValidRimCell_isArrivedOnlyThere() {
        int[] entry = {0, 60, 0};
        var goal = reachGoal(entry, ground(entry, List.of(entry, new int[]{0, 61, 0}, new int[]{1, 60, 0}, new int[]{1, 61, 0})));
        assertFalse(goal.isEmpty());
        assertEquals(ExploreRules.Lip.ARRIVED, ExploreRules.lip(goal, new int[]{1, 60, 0}), "the one rim cell");
        for (int[] f : List.of(new int[]{0, 61, 0}, new int[]{1, 61, 0}, new int[]{0, 65, 0}, new int[]{1, 65, 0}, new int[]{2, 60, 0}))
            assertEquals(ExploreRules.Lip.WALKING, ExploreRules.lip(goal, f), "not the goal's cell: walking on - " + java.util.Arrays.toString(f));
    }

    /** Run EL: the GoalNear stand-in is gone from the lip step (rule 23 (2): the reach goal is its one arrived reader). */
    @Test
    void theLipStepHasNoGoalNear() throws java.io.IOException {
        String src = java.nio.file.Files.readString(java.nio.file.Path.of("src/main/java/io/github/mojolowjo/entropybot/commands/StepHands.java"));
        int from = src.indexOf("private String toCave("), to = src.indexOf("// ---- 0.39.z78 J6b (D12): the caving step");
        assertTrue(from > 0 && to > from, "the lip step's source found");
        String lip = src.substring(from, to);
        assertFalse(lip.contains("GoalNear"), "no GoalNear in the lip step");
        assertFalse(lip.contains("atLip"), "no second arrived test");
        assertTrue(lip.contains("ExploreRules.lip(lipGoalOf(lipGoal), me)"), "done read from the goal");
    }

    /** Run EF (3): a cave whose first opening is held tries its next opening; only every opening held drops the cave. */
    @Test
    void anOpeningHeld_theNextOpeningIsTried_beforeTheWholeCave() {
        CaveWay cw = new CaveWay(-45, 60, 78, 1, "minecraft:iron_ore", "iron", 0.5, false, CaveIndex.Size.LARGE, 8, List.of(),
                List.of(new int[]{-45, 60, 78}, new int[]{-40, 58, 90}));
        CaveWay next = cw.without(k -> k.equals(CaveWay.keyOf(new int[]{-45, 60, 78})));
        assertNotNull(next);
        assertArrayEquals(new int[]{-40, 58, 90}, next.targets().get(0), "the next opening");
        assertEquals(List.of(-45, 60, 78), List.of(next.x(), next.y(), next.z()), "the record's key stays the known cave's entry");
        assertNull(cw.without(k -> true), "every opening held: no cave way");
        assertEquals("known cave -40,58,90", CaveWay.keyOf(new int[]{-40, 58, 90}), "the hold names the cell");
    }

    /** Item 5 (a): an unexplored chunk is walked to as its surface cell; not loaded yet: the column goal. */
    @Test
    void noCreditedOpening_anUnexploredSurfaceCell() {
        assertArrayEquals(new int[]{40, 71, -8}, ExploreRules.surfaceCell(40, -8, 71));
        assertNull(ExploreRules.surfaceCell(40, -8, io.github.mojolowjo.entropybot.world.SurfaceHeight.NOT_KNOWN), "not loaded: not known (rule 15)");
    }

    /** Item 5 (b): a rim credit mid-walk - the chunk walk yields to the opening at once; a visited one is passed over. */
    @Test
    void aRimCreditMidWalk_theTargetSwitchesAtOnce() {
        List<int[]> credited = List.of(new int[]{5, 64, 5}, new int[]{9, 64, 9});
        assertArrayEquals(new int[]{5, 64, 5}, ExploreRules.yieldTo(true, credited, null));
        assertArrayEquals(new int[]{9, 64, 9}, ExploreRules.yieldTo(true, credited, c -> c[0] == 5), "the visited one passed over");
        assertNull(ExploreRules.yieldTo(true, List.of(), null), "none credited: the walk goes on");
        assertNull(ExploreRules.yieldTo(false, credited, null), "a walk toward an opening never yields");
    }
}
