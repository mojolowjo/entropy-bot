package io.github.mojolowjo.entropybot.commands;

import io.github.mojolowjo.entropybot.aa.Nearby;
import io.github.mojolowjo.entropybot.aa.brain.FastEnd;
import io.github.mojolowjo.entropybot.aa.brain.Tuning;
import io.github.mojolowjo.entropybot.aa.moves.Car;
import io.github.mojolowjo.entropybot.aa.moves.FactChange;
import io.github.mojolowjo.entropybot.aa.moves.FirstAnswerWait;
import io.github.mojolowjo.entropybot.aa.moves.Step;
import io.github.mojolowjo.entropybot.aa.moves.Steps;
import io.github.mojolowjo.entropybot.guard.BreakPolicy;
import io.github.mojolowjo.entropybot.guard.Policy;
import io.github.mojolowjo.entropybot.guard.WorldGenFiles;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 0.39.z60 (run R on z58, 2026-10-25 03:34:54-03:53:55, fresh peaceful world, `aa want torch 4, iron_pickaxe`): the order's
 * first answers, placed torches as a source, the table with no cell, the finished order reported failed. The fork's halves
 * (nearest-first queue, the room pass, the reserves' world) are its own JUnit (PathQueueTest, LegRulesTest, RunSettingsTest).
 */
class Z60RunRTest {

    private static final int TPS = 20;   // game fact: 20 game ticks a second

    // ---- item 1: the first-answer wait lasts while first answers progress, up to 30 s ----

    /**
     * Run R 03:34:54 order start: 4 first answers started, two landed within 0.7 s (the trees at -28 70 -51 and -35 72 -53);
     * the other two (-138 41 15 and -56 48 -11) ran on, fork segments landing at most 4 s apart, and both landed (no path,
     * 144768 nodes) 6 s after the start. z58 explored at 4 s; z60 waits while they progress and explores only when none is
     * left (or none progressed for pathCost.firstAnswerStallS, or the 30 s cap).
     */
    @Test
    void runRsStartWaitsWhileTheTwoSlowFirstAnswersProgress() {
        int cap = io.github.mojolowjo.entropybot.aa.NeedChain.firstAnswerWaitS();
        double stall = Tuning.get("pathCost.firstAnswerStallS");
        assertEquals(30, cap);
        assertEquals(8, stall, 1e-9);
        // 4 s (03:34:56.955), 2 pending, the last segment landed 1 s ago: z58 explored here, z60 waits
        var at4 = FirstAnswerWait.of(4 * TPS, cap, 2, false, TPS, stall);
        assertEquals(FirstAnswerWait.Next.WAIT, at4.next(), at4.why());
        // 6 s: both landed (no path): none pending, the landing just seen - one more look for its re-price, then explore
        assertEquals(FirstAnswerWait.Next.WAIT, FirstAnswerWait.of(6 * TPS, cap, 0, true, 0, stall).next());
        var after = FirstAnswerWait.of(6 * TPS + 5, cap, 0, false, 5, stall);
        assertEquals(FirstAnswerWait.Next.EXPLORE, after.next());
        assertTrue(after.why().startsWith("no first answer computing any more after 6.3 s"), after.why());
        // the old 4-argument call (no progress fact) never judges a stall
        assertEquals(FirstAnswerWait.Next.WAIT, FirstAnswerWait.of(25 * TPS, cap, 1, false).next());
    }

    /** Stuck searches (no segment, no answer for 8 s) end the wait early, said with the numbers; the cap ends it anyway. */
    @Test
    void aStallOrTheCapEndsTheWait() {
        var stuck = FirstAnswerWait.of(9 * TPS, 30, 2, false, 8 * TPS, 8);
        assertEquals(FirstAnswerWait.Next.EXPLORE, stuck.next());
        assertEquals("2 paths still computing but none progressed for 8.0 s (pathCost.firstAnswerStallS 8.0; waited 9.0 s of 30): exploring meanwhile", stuck.why());
        assertEquals(FirstAnswerWait.Next.WAIT, FirstAnswerWait.of(9 * TPS, 30, 2, false, 8 * TPS - 1, 8).next());
        var capped = FirstAnswerWait.of(30 * TPS, 30, 1, false, 0, 8);
        assertEquals(FirstAnswerWait.Next.EXPLORE, capped.next());
        assertEquals("waited 30 s, 1 path still computing: exploring meanwhile", capped.why());
    }

    // ---- item 2: placed torches are structure blocks (RULES 16), never a source outside my areas ----

    /** A fake scan: one wall_torch at 54 m (run R's), the structure verdict from BreakPolicy.structure over a fact. */
    private static Nearby.Scan scan(String block, BreakPolicy.Structure fact, boolean placedByMe, boolean asks) {
        return new Nearby.Scan() {
            @Override public List<int[]> logs(int radius) { return List.of(); }
            @Override public List<int[]> find(Set<String> blocks, int radius) { return blocks.contains(block) ? List.of(new int[]{-181, 38, -95}) : List.of(); }
            @Override public boolean isLog(String b) { return false; }
            @Override public boolean isOre(String b) { return false; }
            @Override public boolean fairOk(int x, int y, int z) { return true; }
            @Override public boolean leavesNear(int x, int y, int z, int reach) { return false; }
            @Override public boolean allowed(int x, int y, int z) { return true; }       // no keep-out / no-break box there
            @Override public String blockAt(int x, int y, int z) { return block; }
            @Override public Boolean open(int x, int y, int z) { return true; }
            @Override public BreakPolicy.Verdict sourceBreak(int x, int y, int z) {
                return asks ? BreakPolicy.structure(Policy.EMPTY, "minecraft:overworld", x, y, z, fact, placedByMe) : null;
            }
        };
    }

    /**
     * Runs Q 02:29:32 / R 03:35:34 "collect 4 torch" (54 m, 1067 s walk, 237 s of Baritone's mine, "Unable to find any path"):
     * a torch is crafted and world gen never places it (the jars' world-gen set has no torch), so StructureTable says
     * structure; the source scan now asks the break policy and a placed torch outside my areas is no source, said in the
     * source line; one I placed counts.
     */
    @Test
    void placedTorchesAreNoSourceOutsideMyAreas() {
        Set<String> gen = Set.of("minecraft:stone", "minecraft:glowstone", "minecraft:shroomlight");   // run R's set has no torch
        assertEquals(BreakPolicy.Structure.YES, WorldGenFiles.classify(List.of(), List.of(), t -> false, false, false, true, false, gen, "minecraft:wall_torch"));
        int[] me = {-142, 62, -92};
        Set<String> torch = Set.of("minecraft:wall_torch", "minecraft:torch");
        Nearby.Source s = Nearby.source(scan("minecraft:wall_torch", BreakPolicy.Structure.YES, false, true), me, torch, () -> null);
        assertNull(s.spot(), "a wild torch is no source");
        assertEquals("none known | 1 wall_torch refused (" + BreakPolicy.STRUCTURE_OUTSIDE + "), not counted", s.line());
        assertNotNull(Nearby.source(scan("minecraft:wall_torch", BreakPolicy.Structure.YES, true, true), me, torch, () -> null).spot(), "placed by me: mine to take");
        assertNotNull(Nearby.source(scan("minecraft:stone", BreakPolicy.Structure.NO, false, true), me, Set.of("minecraft:stone"), () -> null).spot(), "not a structure: a source");
        assertNull(Nearby.source(scan("minecraft:wall_torch", BreakPolicy.Structure.NOT_LOADED, false, true), me, torch, () -> null).spot(), "facts not loaded: fail closed");
        assertNotNull(Nearby.source(scan("minecraft:wall_torch", BreakPolicy.Structure.YES, false, false), me, torch, () -> null).spot(), "a scan that does not ask: as before");
        // a sighting (the fallback) goes through the same rule
        Nearby.Scan none = scan("minecraft:air", BreakPolicy.Structure.YES, false, true);
        assertNull(Nearby.source(none, me, torch, () -> new io.github.mojolowjo.entropybot.aa.NeedChain.Spot(-181, 38, -95, 105, "minecraft:wall_torch")).spot());
    }

    // ---- item 3: the table with no cell re-plans instead of parking the order ----

    /** Hands where the place step fails like run R's (the fork's words) and says the place fact; every other step is done. */
    private static final class PlaceHands implements Steps.Hands {
        final List<String> started = new ArrayList<>();
        @Override public String start(Step s) {
            started.add(s.text());
            if (s instanceof Step.PlaceNear p) {
                FactChange.placeFailed(p.item(), "stage build: place crafting_table near me: no cell within 3 blocks");
                return "error: gave up at place crafting_table near me: stage build: place crafting_table near me: no cell within 3 blocks";
            }
            return null;
        }
        @Override public Car.Status status(Step s) { return Car.Status.done("ok: " + s.text()); }
        @Override public void cancel(String why) {}
    }

    /**
     * Run R 03:52:52 (-568 61 709, under ground after the cobblestone collect): "place crafting_table near me: no cell within 3
     * blocks" stopped the iron order with no re-plan. Now the place fact re-plans it (the replanner is asked with that fact);
     * the same step failing again after the re-plan ends the run with both reasons (named, never parked silently).
     */
    @Test
    void runRsTableWithNoCellReplans() {
        PlaceHands h = new PlaceHands();
        Steps run = new Steps(h);
        List<FactChange.Fact> facts = new ArrayList<>();
        Step place = new Step.PlaceNear("minecraft:crafting_table");
        run.start("goal.iron_pickaxe", List.of(new Step.Craft("minecraft:crafting_table", 1), place), 0, (failed, fact, why) -> {
            facts.add(fact);
            return new Steps.Routine(List.of(new Step.Walk(-560, 64, 712, 0), place), null);
        });
        run.tick(1);                                       // the craft done -> the place refused at its start -> re-planned
        assertEquals(1, facts.size(), run.why());
        assertEquals("place minecraft:crafting_table", facts.get(0).key());
        assertEquals(Steps.Phase.RUNNING, run.phase(), run.why());
        run.tick(2);                                       // the walk done -> the place fails again: ended with both reasons
        assertEquals(Steps.Phase.FAILED, run.phase());
        assertTrue(run.why().contains("re-planned after"), run.why());
        assertTrue(run.why().contains("no cell within 3 blocks"), run.why());
    }

    // ---- item 4: a finished order never reports a failure ----

    /**
     * Run R 03:50:44.831-45.065: "craft torch 4" made 4 torch in 234 ms; the fast-end check compared two SENSED bags (read
     * every 10 ticks, neither had the torches yet) and said "failed: ended after 256 ms changing nothing". On the bags as
     * they are (the adapter now reads the player's bag at the start and at the end) the end stands.
     */
    @Test
    void runRsTorchCraftEndStandsOnTheLiveBag() {
        Map<String, Integer> before = Map.of("minecraft:stick", 3, "minecraft:coal", 6);
        Map<String, Integer> after = Map.of("minecraft:stick", 2, "minecraft:coal", 5, "minecraft:torch", 4);
        assertNull(FastEnd.verdict("done - ok: made 4 torch", 256, before, after, 0, null));
        // the z58 inputs (the same sensed bag twice) gave the false failure
        assertTrue(FastEnd.verdict("done - ok: made 4 torch", 256, before, before, 0, null).startsWith("failed: ended after 256 ms changing nothing"));
    }
}
