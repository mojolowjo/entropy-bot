package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.NeedChain.Chain;
import io.github.mojolowjo.entropybot.aa.NeedChain.StripBand;
import io.github.mojolowjo.entropybot.aa.brain.Goals;
import io.github.mojolowjo.entropybot.aa.brain.OreBand;
import io.github.mojolowjo.entropybot.aa.brain.StripOrder;
import io.github.mojolowjo.entropybot.aa.brain.TestData;
import io.github.mojolowjo.entropybot.aa.brain.ToolUses;
import io.github.mojolowjo.entropybot.aa.brain.Tuning;
import io.github.mojolowjo.entropybot.aa.core.Blackboard;
import io.github.mojolowjo.entropybot.aa.core.Clock;
import io.github.mojolowjo.entropybot.aa.core.FakePort;
import io.github.mojolowjo.entropybot.aa.core.TickLaps;
import io.github.mojolowjo.entropybot.aa.core.Tracer;
import io.github.mojolowjo.entropybot.aa.moves.DryRun;
import io.github.mojolowjo.entropybot.aa.moves.FactChange;
import io.github.mojolowjo.entropybot.aa.moves.KeepClear;
import io.github.mojolowjo.entropybot.aa.moves.Step;
import io.github.mojolowjo.entropybot.aa.moves.StepNeeds;
import io.github.mojolowjo.entropybot.aa.moves.Steps;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 0.39.z62 (run S on z60, 2026-10-25 04:53-04:58, docs/runs/runS-z60): the iron order after the stone pickaxe broke at y 16 -
 * the new table on the strip's first cell, the blocked heading counted three times against replan.maxPerOrder, one pickaxe
 * for a 335-block way; the dry run's lagging bag and its silent stops; the AA tick's sections.
 */
class Z62RunSTest {

    @AfterEach
    void clear() {
        TestData.clear();
        FactChange.clear();
        Tuning.reset();
    }

    static final List<String> IRON = List.of("minecraft:deepslate_iron_ore", "minecraft:iron_ore");

    /** Run S's band at the bot: y 16 = the feet (the strip starts where it stands), every heading open, east first (the facing). */
    static StripBand band(String first) {
        List<OreBand.Feature> fs = Z43StripWayTest.iron();
        return new StripBand(IRON, -64, 384, 16, OreBand.perSlab(fs, 16), -220, 33, 16, first, "minecraft:stone", StripOrder.headingOrder(null, first));
    }

    static NeedChain.World world(Map<String, Integer> bag, StripBand b) {
        return ScenariosTest.w(bag, true, true, Map.of()).withStrip(word -> word.contains("raw_iron") || word.contains("iron_ore") ? b : null);
    }

    static Step.Strip strip(Chain c) { return (Step.Strip) c.steps().stream().filter(s -> s instanceof Step.Strip).findFirst().orElseThrow(); }

    static int crafted(Chain c, String item) {
        int n = 0;
        for (Step s : c.steps()) if (s instanceof Step.Craft k && k.item().equals(item)) n += k.n();
        return n;
    }

    // ---- item 1b: the heading ----

    @Test
    void theHeadingsAreTheStartedTunnelThenTheFacingThenClockwise() {
        assertEquals(List.of("east", "south", "west", "north"), StripOrder.headingOrder(null, "east"));
        assertEquals(List.of("south", "east", "west", "north"), StripOrder.headingOrder("south", "east"), "run S: the south tunnel (30 blocks) is resumed");
        assertEquals(List.of("north", "east", "south", "west"), StripOrder.headingOrder(null, "up"), "no compass facing: clockwise from north");
    }

    /** Run S 04:57:30-35: "strip east ... 0 blocks ... no path" for three ore sets; one fact for the heading, any ore set. */
    @Test
    void aBlockedHeadingIsOneFactForEveryOreSetAndTheNextHeadingIsTheWay() {
        ScenariosTest.world("wooden", "stone");
        FactChange.stripBlocked(16, "east", "strip: no path (strip east feet y 16: 0 blocks, 0 clusters mined)");
        FactChange.Fact f = FactChange.take();
        assertEquals("source " + NeedChain.headingKey(16, "east"), f.key());
        assertEquals(NeedChain.headingKey(16, "east"), FactChange.ownSourceKey(f), "the fact names its own source key");
        assertNull(FactChange.ownSourceKey(new FactChange.Fact("source strip x", "y")));
        var w = world(Map.of("minecraft:stone_pickaxe", 1), band("east"));
        assertEquals("east", strip(NeedChain.resolve("raw_iron", 3, w)).heading());
        Chain c = NeedChain.resolve("raw_iron", 3, w.withoutSources(Map.of(NeedChain.headingKey(16, "east"), f.text())));
        assertTrue(c.ok() && !c.explore(), c.why());
        assertEquals("south", strip(c).heading(), "the next heading clockwise");
        // the iron_ore-only and deepslate-only ways (run S's 2nd and 3rd re-plans) skip east too
        assertEquals("south", strip(NeedChain.resolve("iron_ore", 3, w.withoutSources(Map.of(NeedChain.headingKey(16, "east"), "x")))).heading());
        // a strip dropped by its own key (lava, no path down) still drops the way, as z43
        Map<String, String> both = Map.of(NeedChain.headingKey(16, "east"), "x", band("east").toward("south").key(), "lava ahead");
        assertTrue(NeedChain.resolve("raw_iron", 3, w.withoutSources(both)).steps().stream().noneMatch(s -> s instanceof Step.Strip));
        // every heading blocked: no strip way
        Map<String, String> all = new java.util.HashMap<>();
        for (String h : StripOrder.HEADINGS) all.put(NeedChain.headingKey(16, h), "blocked");
        assertTrue(NeedChain.resolve("raw_iron", 3, w.withoutSources(all)).steps().stream().noneMatch(s -> s instanceof Step.Strip));
    }

    /**
     * Run S 04:57:35: the order stopped "re-planned 3 times already (replan.maxPerOrder 3)" - the pickaxe fact, then the same
     * blocked heading as three ore sets. A fact that drops a NEW source is counted apart from the other re-plans. 0.39.z76
     * sb4 review: both caps are deleted; the counts are shown, never a stop.
     */
    @Test
    void aNewDroppedSourceDoesNotCountAgainstMaxPerOrder() {
        ScenariosTest.world("wooden", "stone");
        Clock.Fake clock = new Clock.Fake(10_000_000);
        Blackboard bb = new Blackboard(clock, new FakePort(), new Tracer());
        bb.fact(NeedChain.WORLD_FACT, (Supplier<NeedChain.World>) () -> world(Map.of("minecraft:stone_pickaxe", 1), band("east")));
        Goals.Goal g = Goals.draft(DebugTreeTest.item("raw_iron"), 3, 0, clock.now());
        g.replans = 3;                                       // run S: the old replan.maxPerOrder already reached
        Step.Strip east = new Step.Strip(IRON, Map.of("iron", 3), 16, "east", 0);
        FactChange.stripBlocked(16, "east", "strip: no path (0 blocks)");
        Steps.Routine r = Goals.replan(bb, g, east, FactChange.take(), "strip: no path");
        assertNotNull(r.steps(), r.why());
        assertEquals("south", r.steps().stream().filter(s -> s instanceof Step.Strip).map(s -> ((Step.Strip) s).heading()).findFirst().orElseThrow());
        assertEquals(1, g.sourceReplans);
        assertEquals(3, g.replans, "a new source is counted apart");
        // 0.39.z76 sb4 review: no count stops an order (replan.maxPerOrder / maxSourceDrops deleted); the same heading
        // again and a new source past the old 8 both re-plan
        FactChange.stripBlocked(16, "east", "strip: no path (0 blocks)");
        Steps.Routine again = Goals.replan(bb, g, east, FactChange.take(), "strip: no path");
        assertNotNull(again.steps(), again.why());
        assertEquals(4, g.replans, "a repeat is counted (shown), not a stop");
        g.sourceReplans = 8;
        FactChange.stripBlocked(16, "south", "strip: no path (0 blocks)");
        Steps.Routine past = Goals.replan(bb, g, east, FactChange.take(), "x");
        assertTrue(past.why() == null || !past.why().contains("replan.max"), past.why());
        assertNotNull(past.steps(), past.why());
    }

    // ---- item 1a: the station keeps off the chain's known cells ----

    /** Run S 04:57:21: the bot at -220 16 33, the strip east at y 16 next: the table must not take -219 16 33 (or the head cell). */
    @Test
    void theStationKeepsOffTheStripsFirstCells() {
        List<Step> steps = List.of(new Step.PlaceNear("crafting_table"), new Step.Craft("stone_pickaxe", 1), new Step.Strip(IRON, Map.of("iron", 3), 16, "east", 0));
        List<int[]> keep = KeepClear.cells(steps, 1, new int[]{-220, 16, 33}, 3, null);
        assertTrue(has(keep, -219, 16, 33) && has(keep, -219, 17, 33), "the tunnel's first cell, feet and head");
        assertTrue(has(keep, -216, 16, 33), "out to the radius + 1");
        assertFalse(has(keep, -220, 16, 34), "south of the bot is free");
        assertEquals(8, keep.size());
        // a strip at another Y (a walk down first) keeps nothing here; a walk keeps its path's first cells near the feet
        List<Step> down = List.of(new Step.PlaceNear("crafting_table"), new Step.ToStrip(-220, 0, 33, "k"), new Step.Strip(IRON, Map.of(), 0, "east", 0));
        List<int[]> path = List.of(new int[]{-220, 16, 33}, new int[]{-220, 15, 33}, new int[]{-220, 14, 33}, new int[]{-220, 5, 33});
        List<int[]> k2 = KeepClear.cells(down, 1, new int[]{-220, 16, 33}, 3, goal -> path);
        assertTrue(has(k2, -220, 15, 33) && has(k2, -220, 14, 33), "the walk's first cells");
        assertFalse(has(k2, -220, 5, 33), "far cells are not this placement's business");
        assertTrue(KeepClear.cells(down, 1, new int[]{-220, 16, 33}, 3, goal -> null).isEmpty(), "no kept path known: nothing kept");
    }

    static boolean has(List<int[]> cells, int x, int y, int z) {
        for (int[] c : cells) if (c[0] == x && c[1] == y && c[2] == z) return true;
        return false;
    }

    // ---- item 2: the pickaxe's wear ----

    /**
     * Run S: one stone pickaxe for 332 strip cells + 3 ores. Hand numbers (z64 item 2: a cell is two blocks, feet and head):
     * wear 2 x 332 + 3 = 667, 131 uses left -> over 536, ceil(536 / 131) = 5 spares, tools.maxSpares 3 holds: 3 spare stone
     * pickaxes made up front from the bag's cobblestone and sticks.
     */
    @Test
    void theStripPlansSparePickaxesForItsWear() {
        ScenariosTest.world("wooden", "stone");
        Map<String, Integer> bag = Map.of("minecraft:stone_pickaxe", 1, "minecraft:cobblestone", 20, "minecraft:stick", 10);
        Chain c = NeedChain.resolve("raw_iron", 3, world(bag, band("east")).withUses(id -> 131));
        assertTrue(c.ok() && !c.explore(), c.why());
        assertEquals(3, crafted(c, "stone_pickaxe"), c.steps().toString());
        assertTrue(c.why().contains("+ 3 spare stone_pickaxe"), c.why());
        int craft = -1, st = -1;
        for (int i = 0; i < c.steps().size(); i++) {
            if (c.steps().get(i) instanceof Step.Craft k && k.item().equals("stone_pickaxe")) craft = i;
            if (c.steps().get(i) instanceof Step.Strip && st < 0) st = i;
        }
        assertTrue(craft >= 0 && craft < st, "the spares before the strip: " + c.steps());
        // enough uses: none; durability unknown (rule 15): none, said
        assertEquals(0, crafted(NeedChain.resolve("raw_iron", 3, world(bag, band("east")).withUses(id -> 667)), "stone_pickaxe"));
        assertEquals(1, crafted(NeedChain.resolve("raw_iron", 3, world(bag, band("east")).withUses(id -> 666)), "stone_pickaxe"), "one use short: one spare");
        assertEquals(0, crafted(NeedChain.resolve("raw_iron", 3, world(bag, band("east")).withUses(id -> ToolUses.UNKNOWN)), "stone_pickaxe"));
        assertEquals(0, crafted(NeedChain.resolve("raw_iron", 3, world(bag, band("east"))), "stone_pickaxe"), "no wear facts: unlimited");
    }

    /**
     * z62 review 1 and 3: the bag's other pickaxes of the tier count before spares (z64: wear 667; 6 stone pickaxes x 131 =
     * 786 >= 667: none made; 5 x 131 = 655: one more); the spare count is capped by tools.maxSpares.
     */
    @Test
    void theBagsOtherPickaxesCountFirstAndTheSparesAreCapped() {
        ScenariosTest.world("wooden", "stone");
        Map<String, Integer> six = Map.of("minecraft:stone_pickaxe", 6, "minecraft:cobblestone", 20, "minecraft:stick", 10);
        Chain c = NeedChain.resolve("raw_iron", 3, world(six, band("east")).withUses(id -> 131));
        assertTrue(c.ok(), c.why());
        assertEquals(0, crafted(c, "stone_pickaxe"), c.why());
        Map<String, Integer> five = Map.of("minecraft:stone_pickaxe", 5, "minecraft:cobblestone", 20, "minecraft:stick", 10);
        assertEquals(1, crafted(NeedChain.resolve("raw_iron", 3, world(five, band("east")).withUses(id -> 131)), "stone_pickaxe"), "655 of 667: one more");
        Map<String, Integer> one = Map.of("minecraft:stone_pickaxe", 1, "minecraft:cobblestone", 20, "minecraft:stick", 10);
        Tuning.set("tools.maxSpares", 1);
        assertEquals(1, crafted(NeedChain.resolve("raw_iron", 3, world(one, band("east")).withUses(id -> 131)), "stone_pickaxe"), "5 needed, the cap holds at 1");
        Tuning.set("tools.maxSpares", 0);
        assertEquals(0, crafted(NeedChain.resolve("raw_iron", 3, world(one, band("east")).withUses(id -> 131)), "stone_pickaxe"));
    }

    /** z62 review 2: only "no path" at the first cell, after the fork stood at the strip's Y, burns the heading. */
    @Test
    void onlyANoPathAtTheFirstCellBlocksTheHeading() {
        assertTrue(StripOrder.noPathAtFirstCell("no path", true, 0, 0, 0, 0), "run S 04:57:30");
        assertTrue(StripOrder.noPathAtFirstCell("no path", true, 12, 12, 30, 30), "a resumed tunnel stuck at its next cell");
        assertFalse(StripOrder.noPathAtFirstCell("no pickaxe left", true, 0, 0, 0, 0), "the pickaxe broke");
        assertFalse(StripOrder.noPathAtFirstCell("lava ahead", true, 0, 0, 0, 0));
        assertFalse(StripOrder.noPathAtFirstCell("stopped", true, 0, 0, 0, 0), "a danger stop or a pause");
        assertFalse(StripOrder.noPathAtFirstCell("no path", false, 0, 0, 0, 0), "no path DOWN to the strip's Y");
        assertFalse(StripOrder.noPathAtFirstCell("no path", true, 0, 3, 0, 2), "it got past the first cell");
        assertFalse(StripOrder.noPathAtFirstCell("no path", true, 4, 6, 3, 3), "a branch's return steps (no fresh block) moved on");
    }

    /** z62 review 4: the started tunnel's heading from the progress keys, and a heading shut at its first cell. */
    @Test
    void theStartedHeadingAndTheBlockedStartAreReadFromFacts() {
        java.util.Set<String> keys = java.util.Set.of("minecraft:deepslate_iron_ore,minecraft:iron_ore|16|south",
                "minecraft:deepslate_iron_ore,minecraft:iron_ore|16|south|deposits", "minecraft:coal_ore|40|west");
        assertEquals("south", StripOrder.startedHeading(keys, List.of("minecraft:iron_ore", "minecraft:deepslate_iron_ore"), 16), "the ores in any order");
        assertNull(StripOrder.startedHeading(keys, IRON, 12), "another Y");
        assertNull(StripOrder.startedHeading(keys, List.of("minecraft:iron_ore"), 16), "another ore set");
        assertNull(StripOrder.startedHeading(null, IRON, 16));
        int[] feet = {-220, 16, 33};
        java.util.function.Function<int[], String> table = c -> c[0] == -219 && c[1] == 16 && c[2] == 33 ? "crafting_table at -219 16 33, which Baritone will not break" : null;
        assertEquals("crafting_table at -219 16 33, which Baritone will not break", StripOrder.startBlocked(feet, "east", table), "run S's table");
        assertNull(StripOrder.startBlocked(feet, "south", table));
        java.util.function.Function<int[], String> head = c -> c[1] == 17 && c[0] == -220 && c[2] == 32 ? "bedrock" : null;
        assertEquals("bedrock", StripOrder.startBlocked(feet, "north", head), "the head cell counts");
        assertNull(StripOrder.startBlocked(feet, "west", head));
    }

    /** The walk's pickaxe blocks count: 700 uses, 667 strip (z64: 2 x 332 + 3) + 100 dug on the path = 767 -> 1 spare; the same walk with 0 breaks -> none. */
    @Test
    void theWalksDugBlocksWearThePickaxeToo() {
        ScenariosTest.world("wooden", "stone");
        Map<String, Integer> bag = Map.of("minecraft:stone_pickaxe", 1, "minecraft:cobblestone", 20, "minecraft:stick", 10);
        StripBand deep = new StripBand(IRON, -64, 384, 16, band("east").perSlab(), 0, 0, 64, "east", "minecraft:stone");
        var w = ScenariosTest.w(bag, true, true, Map.of()).withStrip(word -> word.contains("raw_iron") || word.contains("iron_ore") ? deep : null).withUses(id -> 700);
        Chain dug = NeedChain.resolve("raw_iron", 3, w.withPath((x, y, z, d) -> PathPrice.Answer.priced(new PathPrice.Walk(30, 0, 100))));
        assertEquals(1, crafted(dug, "stone_pickaxe"), dug.why());
        Chain open = NeedChain.resolve("raw_iron", 3, w.withPath((x, y, z, d) -> PathPrice.Answer.priced(new PathPrice.Walk(30, 0, 0))));
        assertEquals(0, crafted(open, "stone_pickaxe"), open.why());
    }

    // ---- item 3: the dry run ----

    /** z61 review (a): the running step's own shortage is left to its own checks; a later step's is judged. */
    @Test
    void theDryRunJudgesOnlyTheLaterSteps() {
        Z61ReservationsTest.stoneGame();
        List<Step> steps = List.of(new Step.Craft("stone_pickaxe", 1), new Step.Craft("furnace", 1));
        List<StepNeeds> n = Z61ReservationsTest.needs(steps);
        Map<String, Integer> bag = Map.of(Z61ReservationsTest.STICK, 2, Z61ReservationsTest.COBBLE, 1);
        assertNotNull(DryRun.check(steps, 0, n, bag), "judged: step 1 short");
        DryRun.Outcome o = DryRun.look(steps, 0, n, bag, false);
        assertNotNull(o.shortage(), "step 2 is still short");
        assertEquals(1, o.shortage().step());
        Map<String, Integer> noSticks = Map.of(Z61ReservationsTest.COBBLE, 11);
        assertNotNull(DryRun.check(steps, 0, n, noSticks), "judged: step 1 has no sticks");
        DryRun.Outcome fine = DryRun.look(steps, 0, n, noSticks, false);
        assertNull(fine.shortage(), "step 1's own sticks are its own check; step 2's 8 cobblestone are there after step 1 takes 3");
        assertTrue(fine.note().contains("checked steps 2") && fine.note().contains("step 1 (running) left to its own checks"), fine.note());
    }

    /** z61 review (b): where the dry run stopped or what it skipped is said, never a bare "no shortage". */
    @Test
    void theDryRunSaysWhereItStoppedAndWhatItSkipped() {
        Z61ReservationsTest.stoneGame();
        List<Step> steps = List.of(new Step.Craft("stick", 4), new Step.Strip(IRON, Map.of("iron", 3), 16, "east", 0), new Step.Craft("furnace", 1));
        DryRun.Outcome o = DryRun.look(steps, 0, Z61ReservationsTest.needs(steps), Map.of("minecraft:oak_planks", 2), true);
        assertNull(o.shortage());
        assertTrue(o.note().contains("stopped at step 2 (strip") && o.note().contains("its yield is not known, nothing after it checked"), o.note());
        assertTrue(DryRun.look(steps, 0, Z61ReservationsTest.needs(steps), null, true).note().contains("no bag"));
        List<StepNeeds> guessed = new ArrayList<>(Z61ReservationsTest.needs(steps));
        guessed.set(0, new StepNeeds(null, 0, Map.of(), null, 0, Map.of(), "recipe guessed (2 recipes, the bag covers none)", null));
        String g = DryRun.look(steps, 0, guessed, Map.of(), true).note();
        assertTrue(g.contains("step 1 (craft stick 4) not checked: recipe guessed"), g);
    }

    /** z61 review (a): a bag a tick behind a pickup: the step waits one tick, looks again, and starts - no re-plan. */
    @Test
    void aLaggingBagIsLookedAtAgainBeforeItReplans() {
        Z61ReservationsTest.stoneGame();
        Z61ReservationsTest.Hands h = new Z61ReservationsTest.Hands();
        h.bag.putAll(Map.of(Z61ReservationsTest.COBBLE, 1, Z61ReservationsTest.STICK, 2));
        int[] replans = {0};
        Steps run = new Steps(h);
        run.start("owner goal", List.of(new Step.Walk(1, 64, 1, 0), new Step.Craft("furnace", 1)), 0, (failed, fact, why) -> { replans[0]++; return new Steps.Routine(null, "no"); });
        assertEquals(List.of(), h.started, "held one tick");
        assertTrue(run.reservesText().contains("step 2 (craft furnace 1) needs 8"), run.reservesText());
        h.bag.put(Z61ReservationsTest.COBBLE, 9);            // the pickup lands
        run.tick(1);
        assertEquals(0, replans[0]);
        assertEquals(1, h.started.size(), "started on the second look: " + run.why());
        assertTrue(run.running());
        assertTrue(run.reservesText().contains("checked steps 2"), run.reservesText());
    }

    // ---- item 4: the AA tick's sections ----

    @Test
    void theAaTickLineNamesTheSlowSectionAndTheResolves() {
        long[] t = {0};
        TickLaps laps = new TickLaps(() -> t[0]);
        laps.begin(true);
        t[0] += 50_000;                      // 0.05 ms
        laps.lap("worldFacts");
        t[0] += 5_000_000_000L;              // run S 04:57:12: 5 s
        laps.resolved(4_900_000_000L);
        laps.lap("tree");
        String l = laps.line(12077, 20);
        assertTrue(l.contains("t=12077 | aa tick 5000.1 ms (over debug.aaTickMs 20)"), l);
        assertTrue(l.contains("tree 5000.0") && l.contains("others 0.1") && !l.contains("worldFacts"), l);
        assertTrue(l.contains("resolves 1 = 4900.0 ms"), l);
        laps.begin(true);
        assertNull(laps.line(1, 20), "a fast tick: no line");
        laps.begin(false);
        t[0] += 9_000_000_000L;
        laps.lap("tree");
        assertNull(laps.line(1, 20), "off: nothing");
        assertEquals(0, laps.now());
    }
}
