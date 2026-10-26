package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.NeedChain.Chain;
import io.github.mojolowjo.entropybot.aa.NeedChain.Spot;
import io.github.mojolowjo.entropybot.aa.NeedChain.World;
import io.github.mojolowjo.entropybot.aa.brain.BatchWay;
import io.github.mojolowjo.entropybot.aa.brain.GameTimes;
import io.github.mojolowjo.entropybot.aa.brain.TestData;
import io.github.mojolowjo.entropybot.aa.brain.Tuning;
import io.github.mojolowjo.entropybot.aa.brain.Unit;
import io.github.mojolowjo.entropybot.aa.moves.FactChange;
import io.github.mojolowjo.entropybot.aa.moves.Step;
import io.github.mojolowjo.entropybot.aa.moves.Steps;
import io.github.mojolowjo.entropybot.commands.PricedAnswers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 0.39.z77 round fix (the round on 7796844c: runs BF S2', BG S1, BH S3). One scenario per item, hand numbers as
 * Z77PlaybookTest (stone 0.6 s, iron ore 1.15 s, 16 blocks an ore at y 16, a cell 2 breaks):
 * 1 (BH) a CHUNKS answer keeps the first-answer clock: past it, Baritone's estimate or "no estimate";
 * 2 (BH) the stands are counted per run, and the second stand at a cell drops its walks for the order - the next way runs;
 * 3 (BF) the batch's surface orders come first in the playbook's surface leg (the torches before the gate's pickaxe);
 * 4 (BF) a spare pickaxe is the lowest tier that digs the strip, never the kit's iron;
 * 5 (BG) a walk up that ends under ground is no walk up (the climb instead), and a way priced 0 s is refused.
 */
class Z77RoundFixTest {
    @AfterEach
    void clear() {
        TestData.clear();
        FactChange.clear();
        Tuning.reset();
        BatchWay.clear();
    }

    // ---- 1. run BH: CHUNKS from one cell, again and again ----

    /**
     * Run BH 09:31:00-09:34:12: "walk to in:-235 15 -201: CHUNKS (chunks not loaded)" from one cell for 3 min. CHUNKS is a
     * path not known (rule 18) and keeps the clock as COMPUTING does: before pathCost.firstAnswerWaitS 30 s (600 ticks) it is
     * as it is; at 600 ticks Baritone's estimate (12.0 s here) is the walk, "ranked, not priced"; no estimate: the way drops.
     */
    @Test
    void item1_chunksKeepsTheFirstAnswerClock() {
        PathPrice.Answer chunks = PathPrice.Answer.chunks("CHUNKS");
        assertTrue(PricedAnswers.waits(chunks), "CHUNKS waits like COMPUTING");
        assertTrue(PricedAnswers.waits(PathPrice.Answer.computing("searching")));
        assertFalse(PricedAnswers.waits(PathPrice.Answer.priced(new PathPrice.Walk(5))));
        assertFalse(PricedAnswers.waits(PathPrice.Answer.unreachable("no path")));
        long wait = 30L * GameTimes.TPS;
        assertSame(chunks, PricedAnswers.afterWait(chunks, wait - 1, 30, 12.0), "before the wait: as it is");
        PathPrice.Answer est = PricedAnswers.afterWait(chunks, wait, 30, 12.0);
        assertEquals(PathPrice.Kind.ESTIMATED, est.kind(), est.toString());
        assertEquals(12.0, est.walk().seconds(), 1e-9);
        NeedChain.Walked w = NeedChain.walked(est);
        assertNull(w.fail(), "an estimate is a walk to rank by");
        assertEquals(12.0, w.seconds(), 1e-9);
        PathPrice.Answer none = PricedAnswers.afterWait(chunks, wait, 30, Double.NaN);
        assertEquals(PathPrice.Kind.NONE, none.kind());
        assertTrue(none.reason().startsWith(PathPrice.NO_ESTIMATE), none.reason());
        assertFalse(NeedChain.walked(none).fail().startsWith(PathPrice.NOT_KNOWN), "no estimate drops the way, never another stand");
    }

    // ---- 2. run BH: the stand's count per run; the second stand drops the walks, not the order ----

    /** The kit world of Z77PlaybookTest: raw iron 3 known 10 m away, its path answering CHUNKS; the strip at y 16 priced. */
    static World chunksWorld() {
        Z77PlaybookTest.data();
        return Z77PlaybookTest.world(Z77PlaybookTest.kitBag(9), Map.of("raw_iron", List.of(new Spot(10, 64, 0, 10, Z77PlaybookTest.IRON_ORE, 3))))
                .withPath((x, y, z, d) -> x == 10 && y == 64 && z == 0 ? PathPrice.Answer.chunks("CHUNKS") : Z77PlaybookTest.PATHS.price(x, y, z, d));
    }

    /**
     * The order raw_iron 3 with its known spot's walk CHUNKS: the chain stands (the spot may be the cheapest). Held "stood at
     * 0 64 0" (the second stand there): that walk drops - "walk: dropped, path not known (CHUNKS) after the stands here" - and
     * the next way runs: the explore toward the band (no strip way beside a known source), dug down to y 30 first by its path
     * (64 - 30) x 0.6 = 20.4 + the explore's 450: 470.4 s. Away from that cell (failures.moved 16 and more) the hold says nothing.
     */
    @Test
    void item2_theSecondStandDropsItsWalks_theNextWayRuns() {
        World w = chunksWorld();
        Chain stand = NeedChain.resolve("raw_iron", 3, w);
        assertTrue(Steps.waitsOnPaths(stand.steps()), "the walk not known: the order stands - " + stand.why());
        World held = w.withoutSources(Map.of(NeedChain.stoodKey(new int[]{0, 64, 0}), "stood 2 times at 0 64 0 with walks' paths still not known"));
        Chain c = NeedChain.resolve("raw_iron", 3, held);
        assertTrue(c.ok() && !Steps.waitsOnPaths(c.steps()), c.why());
        assertEquals(20.4 + NeedChain.exploreS(), c.seconds(), 0.1, c.why());
        assertTrue(c.explore(), c.steps().toString());
        assertTrue(String.join(" | ", c.dropped()).contains("walk: dropped, path not known (CHUNKS) after the stands here"), c.dropped().toString());
        World far = w.withoutSources(Map.of(NeedChain.stoodKey(new int[]{0, 64, 40}), "stood 2 times there"));
        assertTrue(Steps.waitsOnPaths(NeedChain.resolve("raw_iron", 3, far).steps()), "another cell's stands: as before");
    }

    /**
     * At runtime (the step machine): two stands at one cell, the re-plan a stand again - the order re-plans once more with the
     * source fact "stood at 0 64 0" and runs what that gives (a smelt here), never stopped on the wait; zero movement.
     */
    @Test
    void item2_atRuntime_theDropRePlanRunsTheNextWay() {
        Z76StandTest.FeetHands h = new Z76StandTest.FeetHands();
        Steps run = new Steps(h);
        List<String> facts = new ArrayList<>();
        Step smelt = new Step.Smelt("iron_ingot", 1, true);
        run.start("goal.helmet", List.of(new Step.AwaitPaths(30)), 0, (failed, fact, why) -> {
            facts.add(fact.key());
            return new Steps.Routine(List.of(fact.key().startsWith("source " + NeedChain.STOOD_AT) ? smelt : new Step.AwaitPaths(30)), null);
        });
        Z76StandTest.tick(run, h, 20 * 30 * 2 + 20 * 60 + 40);
        assertFalse(run.running(), run.why());
        assertEquals(List.of("stand while paths compute (up to 30 s)", "stand while paths compute (up to 30 s)", smelt.text()), h.started);
        assertEquals("source stood at 0 64 0", facts.get(facts.size() - 1));
        assertFalse(String.valueOf(run.why()).contains("no third stand"), run.why());
        assertArrayEquals(new int[]{0, 64, 0}, h.feet, "zero movement");
    }

    /**
     * Run BH: "stood 2, 3, 4, 5, 6 times" - the count went on across the orders on the one step machine. Per run: an order
     * whose run ended after its two stands leaves nothing to the next order's run, which stands twice too before its drop.
     */
    @Test
    void item2_theStandsAreCountedPerRun() {
        Z76StandTest.FeetHands h = new Z76StandTest.FeetHands();
        Steps run = new Steps(h);
        Steps.Replanner always = (failed, fact, why) -> new Steps.Routine(List.of(new Step.AwaitPaths(30)), null);
        run.start("goal.pickaxe", List.of(new Step.AwaitPaths(30)), 0, always);
        Z76StandTest.tick(run, h, 20 * 30 * 3);
        assertFalse(run.running());
        assertTrue(run.why().contains("stood 2 times") && run.why().contains("with those walks dropped no way is left"), run.why());
        h.started.clear();
        run.start("goal.helmet", List.of(new Step.AwaitPaths(30)), 0, always);
        Z76StandTest.tick(run, h, 20 * 30 * 3);
        assertEquals(2, h.started.size(), "the next order stands twice as well: " + h.started);
        assertTrue(run.why().contains("stood 2 times"), "never 3: " + run.why());
    }

    // ---- 3. run BF: the torches first ----

    /**
     * Run BF (torch 12:37; baseline 1:43): the surface leg made the gate's stone pickaxe before the batch's torches. Now the
     * orders whose leaves are all surface come first: "surface [torch 4, stone_pickaxe 1]", and with run BE's bag (64 sticks,
     * 9 coal, no tree known) the run's first step is the torches' craft (0.3 s), before the stone pickaxe's explore - the
     * torch order is done before the explore; the price is the same sum (450 + 716.25 = 1166.25 s; z77 legs: the stations
     * placed again at the strip on's tunnel end, Z77PlaybookTest).
     */
    @Test
    void item3_theTorchesFirstInTheSurfaceLeg() {
        Z77PlaybookTest.data();
        World w = Z77PlaybookTest.world(Map.of("minecraft:stick", 64, "minecraft:crafting_table", 5, "minecraft:furnace", 5, "minecraft:coal", 9), Map.of());
        Playbook pb = Playbook.derive(Z77PlaybookTest.KIT, Playbook.facts(w, null));
        assertTrue(pb.why().startsWith("playbook: surface [torch 4, stone_pickaxe 1] > "), pb.why());
        Chain pc = NeedChain.resolvePlaybook(pb, w);
        assertTrue(pc.ok() && pc.explore(), pc.why());
        assertEquals(new Step.Craft("torch", 4), pc.steps().get(0), pc.steps().toString());
        int explore = 0;
        for (int i = 0; i < pc.steps().size(); i++) if (pc.steps().get(i) instanceof Step.Explore) explore = i;
        assertTrue(explore > 0, "the torches before the explore: " + pc.steps());
        assertEquals(NeedChain.exploreS() + 716.25, pc.seconds(), 0.1, pc.why());
    }

    // ---- 4. run BF: no spare iron pickaxes ----

    /**
     * Run BF 08:57:41 ("iron_pickaxe: 22 spares would cover 5861 blocks; tools.maxSpares 3 holds: 3 made"): the iron pickaxe
     * in hand with 10 uses left, a strip of raw iron 3 wearing 667 (Z62RunSTest's band: wear 667). The spare kind is the lowest
     * tier that digs iron ore - the stone pickaxe (131 uses): ceil((667 - 10) / 131) = 6, tools.maxSpares 3 -> 3 stone pickaxes
     * from the bag's cobblestone and sticks; no iron pickaxe made, and "3 made" is said with what was made.
     */
    @Test
    void item4_theSpareIsTheLowestTierThatDigsIt_neverTheKitsIron() {
        ScenariosTest.world("wooden", "stone", "iron");
        Map<String, Integer> bag = Map.of("minecraft:iron_pickaxe", 1, "minecraft:cobblestone", 20, "minecraft:stick", 10);
        Chain c = NeedChain.resolve("raw_iron", 3, Z62RunSTest.world(bag, Z62RunSTest.band("east")).withUses(id -> id.contains("iron") ? 10 : 131));
        assertTrue(c.ok(), c.why());
        assertEquals(0, Z62RunSTest.crafted(c, "iron_pickaxe"), c.steps().toString());
        assertEquals(3, Z62RunSTest.crafted(c, "stone_pickaxe"), c.steps().toString());
        assertTrue(c.spares().contains("iron_pickaxe: 6 spare stone_pickaxe would cover 667 blocks; tools.maxSpares 3 holds: 3 made"), c.spares());
        assertTrue(c.spares().contains("-> 3 spare stone_pickaxe made first"), c.spares());
        // the one scale (MiningTiers: wooden 1, stone 2, iron 3): coal's tier 1 a wooden one, iron's tier 2 a stone one
        assertEquals("minecraft:wooden_pickaxe", NeedChain.spareKind(1, "minecraft:iron_pickaxe"), "the lowest tier digging tier 1");
        assertEquals("minecraft:stone_pickaxe", NeedChain.spareKind(2, "minecraft:iron_pickaxe"), "the lowest tier digging tier 2");
        assertEquals("minecraft:iron_pickaxe", NeedChain.spareKind(3, "minecraft:iron_pickaxe"), "none lower digs tier 3: the tool's own kind");
    }

    // ---- 5. run BG: the walk up next to the feet; a 0 s playbook ----

    /**
     * Run BG 09:07:39-09:23:30 (321 re-plans): the walk up "arrived" next to the feet under 47 blocks of rock, the explore
     * failed under ground from there, the re-plan walked "up" again. (a) A walk up that ends with aaLadder.surfaceDepth 3 or
     * more blocks over the feet failed. (b) Deep (Z77ExploreTest: -181 16 27, 48 under), the explore from here held (frozen
     * here, FactChange.exploreHere): no walk up from here - the climb with the bag's 20 cobblestone and the stone pickaxe,
     * (48 x 3 + 10) / 4.317 = 35.67 s, then the surface explore.
     */
    @Test
    void item5_aWalkUpThatEndsUnderGroundIsNoWalkUp() {
        assertNotNull(NeedChain.walkUpShort(47, 3, "-28 22 -48"));
        assertTrue(NeedChain.walkUpShort(47, 3, "-28 22 -48").contains("ended under ground (47 blocks over the feet at -28 22 -48"));
        assertNull(NeedChain.walkUpShort(0, 3, "x"), "under open sky");
        assertNull(NeedChain.walkUpShort(2, 3, "x"), "under the surface depth: the surface");
        // shape review 1 (rule 15): the surface over the column not known is never "on the surface" - the walk up ends with that
        assertTrue(NeedChain.walkUpShort(-1, 3, "-28 22 -48").contains("where the surface is not known"), "not known is never reached");
        // the climb the same: out only on a known surface; not known - on while Baritone climbs, then its fact
        var rg = io.github.mojolowjo.entropybot.aa.moves.ReflexGoals.class;
        assertTrue(io.github.mojolowjo.entropybot.aa.moves.ReflexGoals.climbEnd(0, true, "x").startsWith("ok: on the surface"), rg.getSimpleName());
        assertNull(io.github.mojolowjo.entropybot.aa.moves.ReflexGoals.climbEnd(-1, true, "x"), "not known: climbing on");
        assertNull(io.github.mojolowjo.entropybot.aa.moves.ReflexGoals.climbEnd(47, true, "x"));
        assertTrue(io.github.mojolowjo.entropybot.aa.moves.ReflexGoals.climbEnd(-1, false, "x").contains("where the surface is not known"));
        assertTrue(io.github.mojolowjo.entropybot.aa.moves.ReflexGoals.climbEnd(47, false, "x").contains("with 47 blocks over the feet"));
        var w = Z77ExploreTest.deep(word -> null).withBag(Map.of("minecraft:stone_pickaxe", 1, "minecraft:cobblestone", 20));
        assertEquals(new Step.ToSurface(-181, 64, 27), NeedChain.resolve("raw_iron", 3, w).steps().get(0), "the walk up as before");
        FactChange.exploreHere(new int[]{-181, 16, 27}, "under ground: no target from here");
        FactChange.Fact f = FactChange.take();
        Chain c = NeedChain.resolve("raw_iron", 3, w.withoutSources(Map.of(f.key().substring("source ".length()), f.text())));
        assertTrue(c.ok() && c.explore(), c.why());
        assertEquals(List.of(new Step.Climb(), new Step.Explore(NeedChain.exploreMinutes(), "raw_iron")), c.steps());
        assertEquals((48 * 3 + 10) / Unit.WALK_BPS + NeedChain.exploreS(), c.seconds(), 0.1, c.why());
    }

    /**
     * Run BI 15:51 / 22:41 ("climb: stopped at step 1 (goto -131 14 19): "goto" was deleted"): the climb step is one Baritone
     * goal, the feet under open sky (rule 2) - never the old climb verb's staircase of deleted owner verbs. A column of rock
     * from y 17 to 63 under open sky at 64: in the goal only at 64 and up (feet and head open, the head under sky); the
     * heuristic pulls up (Baritone's Y-level cost, 0 at y 64).
     */
    @Test
    void climb_isOneBaritoneGoal_neverTheDeletedVerbs() throws java.io.IOException {
        var cells = new io.github.mojolowjo.entropybot.aa.moves.ReflexGoals.SkyCells() {
            @Override public boolean open(int x, int y, int z) { return y < 17 || y >= 64 || y == 22 || y == 23; }      // a pocket at 22-23
            @Override public boolean sky(int x, int y, int z) { return y >= 64; }
        };
        var g = new io.github.mojolowjo.entropybot.aa.moves.ReflexGoals.UnderSky(cells, 64);
        assertFalse(g.isInGoal(0, 22, 0), "a pocket under the rock is no way out");
        assertTrue(g.isInGoal(0, 64, 0));
        assertTrue(g.heuristic(0, 22, 0) > 0);
        assertEquals(0, g.heuristic(0, 64, 0));
        String hands = java.nio.file.Files.readString(java.nio.file.Path.of("src/main/java/io/github/mojolowjo/entropybot/commands/StepHands.java"));
        assertFalse(hands.contains("DigCommands.climb("), "the step never runs the climb verb's chain");
        assertTrue(hands.contains("new io.github.mojolowjo.entropybot.aa.moves.ReflexGoals.UnderSky("), "the climb is the Baritone goal");
    }

    /**
     * Run BG 09:23:33 ("the ad-hoc chains run (playbook 0 s vs ad-hoc 1463 s)": a playbook not priced, shown as 0 s; manager:
     * a way priced 0 is never valid). SHAPE CHANGE: no comparison is left to show it, and a trip "priced" 0 s with steps is
     * refused with that reason (no steps run); a trip waiting on its first leg's walk stands, its line with no number.
     */
    @Test
    void item5_aZeroSecondTripIsRefused() {
        Z77PlaybookTest.data();
        World w = Z77PlaybookTest.world(Z77PlaybookTest.kitBag(9), Map.of());
        Playbook pb = Playbook.derive(Z77PlaybookTest.KIT, Playbook.facts(w, null));
        Chain zero = new Chain(List.of(new Step.Craft("torch", 4)), 0, "x", false, null);
        BatchWay.Shaped z = BatchWay.shape("b1", 6, pb, zero, Map.of());
        assertFalse(z.chain().ok(), z.line());
        assertTrue(z.chain().steps().isEmpty());
        assertEquals("trip b1 (6 orders): refused (priced 0.0 s: a way priced 0 or less is never valid)", z.line());
        Chain notYet = new Chain(List.of(), 0, "x", false, "descent to y 16: " + PathPrice.NOT_KNOWN + " yet (computing)");
        BatchWay.Shaped s = BatchWay.shape("b1", 4, pb, notYet, Map.of());
        assertTrue(s.chain().ok() && s.chain().steps().get(0) instanceof Step.AwaitPaths, s.line());
        assertFalse(s.line().contains(" 0 s"), "no number for a trip not priced: " + s.line());
        Chain priced = NeedChain.resolvePlaybook(pb, w);
        assertSame(priced, BatchWay.shape("b1", 6, pb, priced, Map.of()).chain(), "a priced trip runs");
        // shape review 3: an explore trip priced 0 s is refused too
        Chain zeroExplore = new Chain(List.of(new Step.Explore(5, "raw_iron")), 0, "x", true, null);
        assertTrue(BatchWay.shape("b1", 6, pb, zeroExplore, Map.of()).line().contains("refused (priced 0.0 s"), "an explore trip of 0 s");
    }
}
