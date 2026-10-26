package io.github.mojolowjo.entropybot.commands;

import io.github.mojolowjo.entropybot.aa.Nearby;
import io.github.mojolowjo.entropybot.aa.brain.FastEnd;
import io.github.mojolowjo.entropybot.aa.brain.Tuning;
import io.github.mojolowjo.entropybot.aa.moves.Car;
import io.github.mojolowjo.entropybot.aa.moves.FactChange;
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

    // ---- item 1 (the first-answer wait of the explore while paths compute) deleted in 0.39.z76 send-back 3 (rule 22): the bot
    // stands while its walks compute (Step.AwaitPaths, StandWait; Z58PathQueueTest, Z76StandTest) ----

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
        // review 1 (rule 15): the structure fact not loaded yet is NOT KNOWN - the spot comes back marked not loaded (the chain
        // waits on it), counted in the unloaded bucket, never refused
        Nearby.Source wait = Nearby.source(scan("minecraft:wall_torch", BreakPolicy.Structure.NOT_LOADED, false, true), me, torch, () -> null);
        assertNotNull(wait.spot());
        assertTrue(wait.spot().unloaded());
        assertEquals("not loaded", wait.from());
        assertTrue(wait.refused().isEmpty(), wait.refused().toString());
        assertEquals("nearest wall_torch at -181 38 -95: not known yet (its structure fact is not loaded; rule 15) | 1 wall_torch: structure fact not loaded yet, waiting", wait.line());
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
        @Override public int[] feet() { return new int[]{-568, 61, 709}; }    // 0.39.z70: the try's start cell (run R's spot)
        @Override public io.github.mojolowjo.entropybot.aa.moves.StepNeeds.Bag bag() {
            return new io.github.mojolowjo.entropybot.aa.moves.StepNeeds.Bag() {
                @Override public int bestTier(String kind) { return 0; }
                @Override public String bestTool(String kind) { return null; }
                @Override public int usesLeft(String id) { return io.github.mojolowjo.entropybot.aa.brain.ToolUses.UNLIMITED; }
                @Override public int count(String id) { return 1; }
            };
        }
    }

    /**
     * Run R 03:52:52 (-568 61 709, under ground after the cobblestone collect): "place crafting_table near me: no cell within 3
     * blocks" stopped the iron order with no re-plan. Now the place fact re-plans it (the replanner is asked with that fact).
     * 0.39.z70 (D2 3.1): the walk ending DONE between is progress, so the place failing after it is re-planned again; the
     * same try again (the same place-near, fact, cell and bag, nothing done between) ends the run with the honest words.
     */
    @Test
    void runRsTableWithNoCellReplans() {
        PlaceHands h = new PlaceHands();
        Steps run = new Steps(h);
        List<FactChange.Fact> facts = new ArrayList<>();
        Step place = new Step.PlaceNear("minecraft:crafting_table");
        run.start("goal.iron_pickaxe", List.of(new Step.Craft("minecraft:crafting_table", 1), place), 0, (failed, fact, why) -> {
            facts.add(fact);
            return new Steps.Routine(facts.size() == 1 ? List.of(new Step.Walk(-560, 64, 712, 0), place) : List.of(place), null);
        });
        run.tick(1);                                       // the craft done -> the place refused at its start -> re-planned
        assertEquals(1, facts.size(), run.why());
        assertEquals("place minecraft:crafting_table", facts.get(0).key());
        assertEquals(Steps.Phase.RUNNING, run.phase(), run.why());
        run.tick(2);                                       // the walk done (progress) -> the place fails again: re-planned, then the same try at once
        assertEquals(2, facts.size(), run.why());
        assertEquals(Steps.Phase.FAILED, run.phase());
        assertTrue(run.why().contains("the same way failed twice from the same place with the same bag: place minecraft:crafting_table near me"), run.why());
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

    /**
     * Review 4: on a server the torches reach the client's bag 2 ticks after the craft step ended - the end is held up to
     * failures.fastEndSyncTicks (4) and stands once the bag shows them; a bag that never changes is called a failure at 4.
     */
    @Test
    void aDelayedBagIsWaitedForAFewTicks() {
        assertEquals(4, FastEnd.syncTicks());
        Map<String, Integer> before = Map.of("minecraft:stick", 3, "minecraft:coal", 6);
        Map<String, Integer> synced = Map.of("minecraft:stick", 2, "minecraft:coal", 5, "minecraft:torch", 4);
        String end = "done - ok: made 4 torch";
        // the delayed bag: unchanged at the end tick and the next, the torches at tick 2
        FastEnd.Hold t0 = FastEnd.settle(end, 256, before, before, 0, null, 0);
        assertTrue(t0.waitSync(), "held for the sync");
        assertTrue(FastEnd.settle(end, 256, before, before, 0, null, 1).waitSync());
        FastEnd.Hold t2 = FastEnd.settle(end, 256, before, synced, 0, null, 2);
        assertFalse(t2.waitSync());
        assertNull(t2.verdict(), "the end stands");
        // a bag that never changes: a failure once the sync ticks are over
        FastEnd.Hold t4 = FastEnd.settle(end, 256, before, before, 0, null, 4);
        assertFalse(t4.waitSync());
        assertTrue(t4.verdict().startsWith("failed: ended after 256 ms changing nothing"), t4.verdict());
    }
}
