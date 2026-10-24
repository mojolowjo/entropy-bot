package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.NeedChain.Chain;
import io.github.mojolowjo.entropybot.aa.NeedChain.Spot;
import io.github.mojolowjo.entropybot.aa.brain.Goals;
import io.github.mojolowjo.entropybot.aa.brain.TestData;
import io.github.mojolowjo.entropybot.aa.moves.Car;
import io.github.mojolowjo.entropybot.aa.moves.FactChange;
import io.github.mojolowjo.entropybot.aa.moves.Step;
import io.github.mojolowjo.entropybot.aa.moves.Steps;
import io.github.mojolowjo.entropybot.cave.ExploreRules;
import io.github.mojolowjo.entropybot.sense.CaveMouths;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 0.39.z48 scenarios (docs/SCENARIOS.md): S63 a placed station is taken where it stands, S64 the hole's floor, the lake
 * (item 5), S65 the explore aims at candidates, S66 the re-price margin. S62's fork half is the fork's interact (a second
 * stand); its mod half (a station fact) is FactChange.stationUnusable below.
 */
class Z48ScenarioTest {

    @AfterEach
    void clear() {
        TestData.clear();
        FactChange.clear();
    }

    // ---- S62: an unusable station is a station fact (re-plan), never a parked order ----

    @Test
    void s62AnUnusableStationIsAStationFact() {
        FactChange.stationUnusable("minecraft:crafting", "crafting table", "-154 16 -160", "no face visible from -156 14 -159; tried from 2 stands");
        FactChange.Fact f = FactChange.take();
        assertNotNull(f);
        assertEquals("station minecraft:crafting", f.key());
        assertTrue(f.text().contains("-154 16 -160 unusable"), f.text());
        assertFalse(f.price());
    }

    // ---- S63: a placed furnace 200 m away vs 8 cobblestone in the bag ----

    private static Chain furnaceChain(Map<String, Integer> bag) {
        ScenariosTest.world("wooden", "stone");
        Spot placed = new Spot(-16, 64, -52, 200, "minecraft:furnace");
        return NeedChain.resolve("furnace", 1, ScenariosTest.w(bag, true, false, Map.of("furnace", List.of(placed))));
    }

    @Test
    void s63ThePlacedFurnaceAndANewOneAreBothPriced() {
        Chain c = furnaceChain(Map.of("minecraft:cobblestone", 8, "minecraft:wooden_pickaxe", 1));
        assertTrue(c.ok(), c.why());
        // the craft from the bag wins; the take is listed with its seconds (the walk of 200 m)
        assertTrue(c.steps().stream().anyMatch(s -> s instanceof Step.Craft k && k.item().contains("furnace")), c.steps().toString());
        assertTrue(c.dropped().stream().anyMatch(d -> d.startsWith("take the furnace 200 m")), c.dropped().toString());
        assertTrue(c.steps().stream().noneMatch(s -> s instanceof Step.Collect), "never the collect's search: " + c.steps());
    }

    @Test
    void s63WithNothingToMakeOneTheFurnaceIsTakenWhereItStands() {
        Chain c = furnaceChain(Map.of("minecraft:wooden_pickaxe", 1));
        assertTrue(c.ok(), c.why());
        int walk = -1, take = -1;
        for (int i = 0; i < c.steps().size(); i++) {
            Step s = c.steps().get(i);
            if (s instanceof Step.Walk w && w.x() == -16 && w.z() == -52) walk = i;
            if (s instanceof Step.TakeStation t && t.x() == -16 && t.y() == 64 && t.z() == -52) take = i;
        }
        assertTrue(walk >= 0 && take == walk + 1, c.steps().toString());
        assertTrue(c.steps().stream().noneMatch(s -> s instanceof Step.Collect), c.steps().toString());
        assertEquals("take at -16 64 -52", NeedChain.sourceKey(c.steps().get(take)));
    }

    @Test
    void s63AStationNotLoadedIsNotKnown() {
        ScenariosTest.world("wooden", "stone");
        Spot placed = new Spot(-16, 64, -52, 200, "minecraft:furnace").notLoaded();
        Chain c = NeedChain.resolve("furnace", 1, ScenariosTest.w(Map.of("minecraft:cobblestone", 8), true, false, Map.of("furnace", List.of(placed))));
        assertTrue(c.ok(), c.why());
        assertTrue(c.steps().stream().noneMatch(s -> s instanceof Step.TakeStation || s instanceof Step.Collect), c.steps().toString());
    }

    // ---- S64: the floor of the hole's own air column ----

    private static String verdict(int airCells, boolean waterFloor, int skyFloor, int skyAbove) {
        boolean[] air = new boolean[airCells + 1];
        boolean[] water = new boolean[airCells + 1];
        int[] sky = new int[airCells + 1];
        for (int i = 0; i < airCells; i++) { air[i] = true; sky[i] = 15; }
        if (airCells >= 2) sky[airCells - 2] = skyAbove;
        sky[airCells - 1] = skyFloor;
        water[airCells] = waterFloor;
        return CaveMouths.floorVerdict(air, water, sky);
    }

    @Test
    void s64ADryHoleDarkAtItsFloorIsACave() {
        assertNull(verdict(6, false, 0, 3));
        assertEquals(5, CaveMouths.floor(new boolean[]{true, true, true, true, true, true, false}));
    }

    @Test
    void s64AHoleLitToItsFloorIsDropped() {
        String v = verdict(6, false, 15, 15);
        assertNotNull(v);
        assertTrue(v.startsWith("lit to the floor"), v);
    }

    @Test
    void s64APondIsDropped() {
        String v = verdict(2, true, 0, 0);
        assertNotNull(v);
        assertTrue(v.contains("water"), v);
        assertEquals("the candidate cell is not air", CaveMouths.floorVerdict(new boolean[]{false}, new boolean[]{false}, new int[]{0}));
    }

    // ---- item 5: a lake gives no candidate ----

    /** A hole with a 6-high cliff on one side and a gentle slope on the other: a mouth on dry ground. */
    private static int[] mouthTop() {
        // x 0..8 slopes down 1 a column to x 8 at 70; x 9.. jumps to 76 (the cliff)
        int[] top = new int[256];
        for (int lz = 0; lz < 16; lz++)
            for (int lx = 0; lx < 16; lx++) top[lx + 16 * lz] = lx <= 8 ? 70 + (8 - lx) : 76;
        return top;
    }

    @Test
    void lakeColumnsAreNeverCandidates() {
        int[] top = mouthTop();
        assertFalse(CaveMouths.candidates(0, 0, top).isEmpty(), "the dry shape is a candidate");
        boolean[] water = new boolean[256];
        java.util.Arrays.fill(water, true);
        CaveMouths.Shapes sh = CaveMouths.shapes(0, 0, top, water);
        assertTrue(sh.candidates().isEmpty(), sh.candidates().toString());
        assertFalse(sh.flooded().isEmpty(), "the flooded hole is noted");
        assertTrue(sh.flooded().get(0).shape().startsWith("flooded "));
    }

    // ---- S65: the explore aims at the nearest unvisited candidate ----

    @Test
    void s65TheExploreAimsAtTheNearestUnvisitedCandidate() {
        List<int[]> nearestFirst = List.of(new int[]{10, 60, 0}, new int[]{40, 58, 5}, new int[]{90, 61, -20});
        assertArrayEquals(new int[]{10, 60, 0}, ExploreRules.toward(nearestFirst, c -> false));
        Set<String> visited = Set.of("10 60 0");
        assertArrayEquals(new int[]{40, 58, 5}, ExploreRules.toward(nearestFirst, c -> visited.contains(c[0] + " " + c[1] + " " + c[2])));
        assertNull(ExploreRules.toward(List.of(), c -> false), "none: the next unexplored chunk as before");
    }

    @Test
    void s65ADroppedCandidateIsNeverListedAgain() {
        CaveMouths m = new CaveMouths();
        m.put(0, 0, List.of(new CaveMouths.Candidate(4, 60, 4, "mouth"), new CaveMouths.Candidate(12, 61, 12, "mouth")));
        assertEquals(2, m.near(0, 60, 0, 128).size());
        m.refuse(4, 60, 4);
        assertEquals(1, m.near(0, 60, 0, 128).size());
        m.put(0, 0, List.of(new CaveMouths.Candidate(4, 60, 4, "mouth")));     // the chunk read again
        assertTrue(m.near(0, 60, 0, 128).isEmpty());
    }

    // ---- S66: the re-price margin ----

    @Test
    void s66TheMarginDecides() {
        assertFalse(Goals.switches(100, false, 98, false, 10), "2 s cheaper: the running step goes on");
        assertTrue(Goals.switches(100, false, 70, false, 10), "30 s cheaper: switch");
        assertTrue(Goals.switches(Double.NaN, false, 70, false, 10), "no running price: switch");
        assertTrue(Goals.switches(5, true, 400, false, 10), "a priced way over an explore");
        assertFalse(Goals.switches(400, false, 5, true, 10), "never an explore over a priced way");
    }

    /** Fake hands: every step runs until {@link #done} names it; cancels are counted. */
    static final class Hands implements Steps.Hands {
        final List<String> started = new ArrayList<>();
        int cancels;
        String done;

        @Override public String start(Step s) { started.add(s.text()); return null; }

        @Override public Car.Status status(Step s) { return s.text().equals(done) ? Car.Status.done("ok") : Car.Status.running("running"); }

        @Override public void cancel(String why) { cancels++; }

        @Override public FactChange.Fact lastFact() { return null; }
    }

    @Test
    void s66ARunningStepIsNeverCutTheSwitchWaitsForTheBoundary() {
        Hands h = new Hands();
        Steps run = new Steps(h);
        Step a = new Step.Craft("minecraft:stick", 4), b = new Step.Craft("minecraft:torch", 4), c = new Step.Craft("minecraft:oak_planks", 4);
        run.start("goal.torch", List.of(a, b), 0, (failed, fact, why) -> new Steps.Routine(List.of(c), null));
        run.repriceAtNextStep(FactChange.priced("walk to 1 2 3: 40 s, complete"));
        run.tick(1);
        assertEquals(0, h.cancels, "the running craft is not cut");
        h.done = a.text();
        run.tick(2);
        assertEquals(List.of(a.text(), c.text()), h.started, "switched at the boundary");
    }

    @Test
    void s66AnExploreWhilePathsRunsOnWhenTheNewPickWaitsOnPathsAgain() {
        Hands h = new Hands();
        Steps run = new Steps(h);
        Step ex = new Step.Explore(5, null, true), craft = new Step.Craft("minecraft:torch", 4);
        Step ex2 = new Step.Explore(5, null, true), other = new Step.Craft("minecraft:stick", 4);
        List<List<Step>> answers = new ArrayList<>(List.of(List.of(ex2, other), List.of(other)));
        run.start("goal.torch", List.of(ex, craft), 0, (failed, fact, why) -> new Steps.Routine(answers.remove(0), null));
        run.repriceAtNextStep(FactChange.priced("walk to 1 2 3: computing"));
        run.tick(1);
        assertEquals(0, h.cancels, "a pick that waits on paths again: the explore runs on");
        run.repriceAtNextStep(FactChange.priced("walk to 1 2 3: 40 s, complete"));
        run.tick(2);
        assertEquals(1, h.cancels, "a priced pick ends the explore (its own end: the paths answered)");
        assertEquals(other.text(), h.started.get(h.started.size() - 1));
    }
}
