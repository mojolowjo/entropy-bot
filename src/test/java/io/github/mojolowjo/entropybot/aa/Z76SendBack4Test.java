package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.brain.TestData;
import io.github.mojolowjo.entropybot.aa.brain.Tuning;
import io.github.mojolowjo.entropybot.aa.moves.Car;
import io.github.mojolowjo.entropybot.aa.moves.FactChange;
import io.github.mojolowjo.entropybot.aa.moves.Step;
import io.github.mojolowjo.entropybot.aa.moves.StepMonitor;
import io.github.mojolowjo.entropybot.aa.moves.StepNeeds;
import io.github.mojolowjo.entropybot.aa.moves.StepSignals;
import io.github.mojolowjo.entropybot.aa.moves.Steps;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 0.39.z76 send-back 4 (rows S1 run AV 2/6, S2' run AW 5/6).
 * <ul>
 * <li>1. The run's re-plan count cap is deleted (run AW 03:18:31 "not re-planned: 11 re-plans in this run already"): a run
 *     with a new fact each time keeps re-planning past 11.</li>
 * <li>2. Run AV 02:43:29 (iron_pickaxe, the strip at y -5 south: 19 blocks mined) and 02:56:09 (iron_chestplate, y -34
 *     east: 40 blocks): the strip worked, one deepslate block every ~3 s of game time, and the monitor ended it "no progress
 *     30 s: the goal 3.6 ... no block broken". Each new GoalBlock re-bases the goal best (3.6 a block), the branches zig-zag
 *     inside the farthest-from-the-start best, and the break stage - the signal meant for a tunnel - read -1 in every
 *     monitor line of runs AC-AW: Baritone's break helper clears the game's isDestroying flag after every break tick and the
 *     live read gated the stage on it. The live read now takes the stage alone (StepSignalsLive.destroyStage); here the
 *     monitor sees AV's strip with and without the stage.</li>
 * </ul>
 * Ticks at 20 a second (game fact); Tuning defaults monitor.everyTicks 20, monitor.stallS 30.
 */
class Z76SendBack4Test {
    @AfterEach
    void clear() {
        TestData.clear();
        FactChange.clear();
    }

    // ---- 1. no run cap ----

    static final class Hands implements Steps.Hands {
        final int[] feet = {-29, -5, -68};
        @Override public String start(Step s) { return null; }
        @Override public Car.Status status(Step s) { return Car.Status.failed("strip: no path"); }
        @Override public void cancel(String why) {}
        int n;
        @Override public FactChange.Fact lastFact() { n++; return new FactChange.Fact("source strip iron_ore|-5|heading " + n, "strip: no path (heading " + n + ")"); }
        @Override public int[] feet() { return feet; }
        @Override public void log(String line) {}
        @Override public StepNeeds.Bag bag() {
            return new StepNeeds.Bag() {
                @Override public int bestTier(String kind) { return 0; }
                @Override public String bestTool(String kind) { return null; }
                @Override public int usesLeft(String id) { return io.github.mojolowjo.entropybot.aa.brain.ToolUses.UNLIMITED; }
                @Override public int count(String id) { return 0; }
            };
        }
    }

    /** The same cell and bag, a new fact (another heading) each time: 20 re-plans, still running, never "re-plans in this run". */
    @Test
    void aRunPastElevenRePlansWithNewFactsKeepsRePlanning() {
        Hands h = new Hands();
        Step strip = new Step.ToStrip(-29, -5, -68, "iron_ore|-5|south");
        List<String> facts = new ArrayList<>();
        Steps run = new Steps(h);
        run.start("goal.iron", List.of(strip), 0, (failed, fact, why) -> { facts.add(fact.key()); return new Steps.Routine(List.of(strip), null); });
        assertFalse(Tuning.names().contains("replan.maxPerOrder") || Tuning.names().contains("replan.maxSourceDrops"), "deleted with the order's caps (sb4 review)");
        for (int t = 1; t <= 20; t++) run.tick(t);
        assertTrue(run.running(), run.why());
        assertEquals(20, facts.size(), facts.toString());
        assertEquals(20, facts.stream().distinct().count(), "each re-plan on a new fact");
        assertFalse(String.valueOf(run.why()).contains("in this run already"), run.why());
    }

    /**
     * sb4 review (manager): no count stops an order. Twelve trees fail one after another, each dropped by its position
     * ("cut at x y z"), never picked again; then the explore from here freezes; the order ends "no way left: <each dropped
     * source with its reason>", not "N sources dropped already".
     */
    @Test
    void twelveFailingSourcesEachDroppedByPositionEndWithNoWayLeft() {
        ScenariosTest.world("wooden");
        List<NeedChain.Spot> trees = new ArrayList<>();
        for (int i = 1; i <= 12; i++) trees.add(new NeedChain.Spot(10 * i, 64, 0, 10 * i, "minecraft:oak_log", 3));
        int[] here = {0, 64, 0};
        NeedChain.World world = ScenariosTest.w(Map.of(), false, false, Map.of("oak_log", trees))
                .withWhere(new NeedChain.WhereFacts(word -> null, () -> null, () -> here));
        io.github.mojolowjo.entropybot.aa.core.Clock.Fake clock = new io.github.mojolowjo.entropybot.aa.core.Clock.Fake(10_000_000);
        io.github.mojolowjo.entropybot.aa.core.Blackboard bb = new io.github.mojolowjo.entropybot.aa.core.Blackboard(clock,
                new io.github.mojolowjo.entropybot.aa.core.FakePort(), new io.github.mojolowjo.entropybot.aa.core.Tracer());
        bb.fact(NeedChain.WORLD_FACT, (java.util.function.Supplier<NeedChain.World>) () -> world);
        bb.in.pos = here;
        io.github.mojolowjo.entropybot.aa.brain.Goals.Goal g = io.github.mojolowjo.entropybot.aa.brain.Goals.draft(DebugTreeTest.item("oak_log"), 3, 0, clock.now());
        java.util.Set<String> cut = new java.util.LinkedHashSet<>();
        for (int k = 0; k < 12; k++) {
            NeedChain.Chain ch = io.github.mojolowjo.entropybot.aa.brain.Goals.chain(bb, g);
            Step chop = ch.steps().stream().filter(s -> s instanceof Step.ChopTree).findFirst().orElse(null);
            assertNotNull(chop, "tree " + (k + 1) + ": " + ch.why());
            String key = NeedChain.sourceKey(chop);
            assertTrue(cut.add(key), "a dropped tree is never picked again: " + key + " in " + cut);
            Steps.Routine r = io.github.mojolowjo.entropybot.aa.brain.Goals.replan(bb, g, chop, FactChange.noProgress(key, "no progress 30 s at " + key), "no progress");
            assertTrue(r.why() == null || !r.why().contains("already"), r.why());
        }
        assertEquals(12, cut.size());
        assertEquals(12, g.sourceReplans, "counted, never a stop");
        NeedChain.Chain ch = io.github.mojolowjo.entropybot.aa.brain.Goals.chain(bb, g);
        assertTrue(ch.explore(), "all trees out: the explore from here: " + ch.why());
        Step ex = ch.steps().stream().filter(s -> s instanceof Step.Explore).findFirst().orElseThrow();
        Steps.Routine end = io.github.mojolowjo.entropybot.aa.brain.Goals.replan(bb, g, ex,
                FactChange.noProgress(NeedChain.exploreFrozenKey(here), "Baritone found no path 3 times"), "no path");
        assertNull(end.steps(), end.why());
        assertTrue(end.why().startsWith("no way left: "), end.why());
        for (String c : cut) assertTrue(end.why().contains(c + ": no progress 30 s at " + c), c + " in " + end.why());
        assertTrue(end.why().contains(NeedChain.exploreFrozenKey(here)), end.why());
    }

    /**
     * sb4 review (run AV 02:40:47: "strip west feet y -2: 0 blocks ... no path" from -28 -2 -70, no reason logged): the
     * blocked-heading fact carries the rule's answer (row and cost) for both first cells; a cell not known and a read that
     * throws are said, never skipped.
     */
    @Test
    void aStripBlockedAtItsFirstCellSaysTheRuleForBothCells() {
        String t = io.github.mojolowjo.entropybot.aa.brain.StripOrder.firstCells(new int[]{-28, -2, -70}, "west",
                c -> c[1] == -2 ? "denied: lava beside (lavaNear)" : null);
        assertEquals("first cells: -29 -2 -70: denied: lava beside (lavaNear); -29 -1 -70: not known", t);
        String thrown = io.github.mojolowjo.entropybot.aa.brain.StripOrder.firstCells(new int[]{0, 0, 0}, "north",
                c -> { throw new IllegalStateException("no snapshot"); });
        assertTrue(thrown.contains("0 0 -1: the rule could not be read: java.lang.IllegalStateException: no snapshot"), thrown);
        FactChange.stripBlocked(-2, "west", "strip: no path (strip west feet y -2: 0 blocks, 0 clusters mined); " + t);
        FactChange.Fact f = FactChange.take();
        assertTrue(f.text().endsWith("-29 -2 -70: denied: lava beside (lavaNear); -29 -1 -70: not known"), f.text());
    }

    /** sb4 review: the progress tick counts a stage change only between two stages; a -1 <-> stale flip is none. */
    @Test
    void aStaleStageFlippingWithTheSwingIsNoProgress() {
        int[] flips = {-1, 4, -1, 4, -1, 4};
        int n = 0;
        for (int i = 1; i < flips.length; i++) if (io.github.mojolowjo.entropybot.aa.core.BreakProgress.counts(flips[i - 1], flips[i])) n++;
        assertEquals(0, n, "a stale stage shown on each swing");
        int[] digging = {-1, 0, 3, 7, -1, 1, 6};
        n = 0;
        for (int i = 1; i < digging.length; i++) if (io.github.mojolowjo.entropybot.aa.core.BreakProgress.counts(digging[i - 1], digging[i])) n++;
        assertEquals(3, n, "0->3, 3->7, 1->6");
    }

    // ---- sb4 review 2: facts the order holds nothing for ----

    /** Every step fails with the fact {@code fact} gives it; the feet move one block each look (a new cell every try). */
    static final class AltHands implements Steps.Hands {
        final int[] feet = {0, 64, 0};
        java.util.function.Function<Step, FactChange.Fact> fact = s -> null;
        FactChange.Fact last;
        @Override public String start(Step s) { feet[0]++; return null; }
        @Override public Car.Status status(Step s) { last = fact.apply(s); return Car.Status.failed("ran out"); }
        @Override public void cancel(String why) {}
        @Override public FactChange.Fact lastFact() { FactChange.Fact f = last; last = null; return f; }
        @Override public int[] feet() { return feet.clone(); }
        @Override public void log(String line) {}
        @Override public StepNeeds.Bag bag() {
            return new StepNeeds.Bag() {
                @Override public int bestTier(String kind) { return 0; }
                @Override public String bestTool(String kind) { return null; }
                @Override public int usesLeft(String id) { return io.github.mojolowjo.entropybot.aa.brain.ToolUses.UNLIMITED; }
                @Override public int count(String id) { return 0; }
            };
        }
    }

    /** The planner alternates: after A it gives B, after B it gives A; it records each fact it was asked on. */
    static Steps.Replanner alternate(Step a, Step b, List<String> asked) {
        return (failed, fact, why) -> {
            asked.add(fact.key());
            return new Steps.Routine(List.of(failed.equals(a) ? b : a), null);
        };
    }

    /** A craft that runs out of sticks and a place that lacks a table, alternating from a new cell each time: parks on the repeat. */
    @Test
    void twoStepsAlternatingOnBagFactsPark() {
        AltHands h = new AltHands();
        Step a = new Step.Craft("minecraft:torch", 4), b = new Step.PlaceNear("minecraft:crafting_table");
        h.fact = s -> s.equals(a) ? new FactChange.Fact("bag stick", "short of stick") : new FactChange.Fact("bag crafting_table", "no crafting_table");
        List<String> asked = new ArrayList<>();
        Steps run = new Steps(h);
        run.start("goal.torch", List.of(a), 0, alternate(a, b, asked));
        for (int t = 1; t <= 10 && run.running(); t++) run.tick(t);
        assertEquals(Steps.Phase.FAILED, run.phase(), run.why());
        assertEquals(List.of("bag stick", "bag crafting_table"), asked, "A and B asked once each, the third try (A again) parks");
        assertTrue(run.why().contains("the same try failed again in this order"), run.why());
        assertTrue(run.why().contains(a.text()), run.why());
    }

    /** A throw at two places, alternating: the third throw (the first place again) parks. */
    @Test
    void throwsAtTwoPlacesAlternatingPark() {
        AltHands h = new AltHands();
        Step a = new Step.Craft("minecraft:torch", 4), b = new Step.Craft("minecraft:stick", 4);
        h.fact = s -> s.equals(a) ? new FactChange.Fact("threw at Mining.exploreStep0:1228", "NullPointerException at Mining.exploreStep0:1228")
                : new FactChange.Fact("threw at Crafting.tick:310", "IllegalStateException at Crafting.tick:310");
        List<String> asked = new ArrayList<>();
        Steps run = new Steps(h);
        run.start("goal.torch", List.of(a), 0, alternate(a, b, asked));
        for (int t = 1; t <= 10 && run.running(); t++) run.tick(t);
        assertEquals(Steps.Phase.FAILED, run.phase(), run.why());
        assertEquals(2, asked.size(), asked.toString());
        assertTrue(run.why().contains("the same try failed again in this order"), run.why());
    }

    /** The order keeps the tries across its runs (Goals' replanner hands its set); a new bag count is a new try. */
    @Test
    void theOrdersTriesSpanItsRunsAndAChangedBagIsANewTry() {
        Step a = new Step.Craft("minecraft:torch", 4);
        java.util.Set<String> orderTries = new java.util.HashSet<>();
        Steps.Replanner p = new Steps.Replanner() {
            @Override public Steps.Routine replan(Step failed, FactChange.Fact fact, String why) { return new Steps.Routine(List.of(a), null); }
            @Override public java.util.Set<String> tries() { return orderTries; }
        };
        AltHands h = new AltHands();
        h.fact = s -> new FactChange.Fact("bag stick", "short of stick");
        Steps first = new Steps(h);
        first.start("goal.torch", List.of(a), 0, p);
        first.tick(1);                                    // re-planned once: the try is the order's now
        assertEquals(1, orderTries.size(), orderTries.toString());
        Steps second = new Steps(h);                      // the order's next run
        second.start("goal.torch", List.of(a), 0, p);
        second.tick(1);
        assertEquals(Steps.Phase.FAILED, second.phase(), "the order has seen this try: " + second.why());
        assertTrue(second.why().contains("the same try failed again in this order"), second.why());
    }

    /** Hands for the place cases: the place fails with the plain place fact, any other step is done; the feet as set. */
    static final class PlaceHands implements Steps.Hands {
        int[] feet = {-568, 61, 709};
        boolean moveEachTry;
        FactChange.Fact last;
        @Override public String start(Step s) {
            if (s instanceof Step.PlaceNear && moveEachTry) feet = new int[]{feet[0] + 20, feet[1], feet[2]};
            return null;
        }
        @Override public Car.Status status(Step s) {
            if (!(s instanceof Step.PlaceNear)) return Car.Status.done("ok");
            last = new FactChange.Fact("place minecraft:crafting_table", "place crafting_table near me failed here: no cell within 3 blocks");
            return Car.Status.failed("no cell within 3 blocks");
        }
        @Override public void cancel(String why) {}
        @Override public FactChange.Fact lastFact() { FactChange.Fact f = last; last = null; return f; }
        @Override public int[] feet() { return feet.clone(); }
        @Override public void log(String line) {}
        @Override public StepNeeds.Bag bag() {
            return new StepNeeds.Bag() {
                @Override public int bestTier(String kind) { return 0; }
                @Override public String bestTool(String kind) { return null; }
                @Override public int usesLeft(String id) { return io.github.mojolowjo.entropybot.aa.brain.ToolUses.UNLIMITED; }
                @Override public int count(String id) { return 1; }
            };
        }
    }

    /**
     * sb4 review 3: the same place failing twice at the SAME cell parks, even with a done step between (which clears the
     * one-slot park); at a new cell each time it re-plans (a place fact is about where).
     */
    @Test
    void theSamePlaceFailingTwiceAtTheSameCellParks() {
        Step place = new Step.PlaceNear("minecraft:crafting_table"), craft = new Step.Craft("minecraft:crafting_table", 1);
        PlaceHands h = new PlaceHands();
        int[] asked = {0};
        Steps run = new Steps(h);
        run.start("goal.iron_pickaxe", List.of(place), 0, (failed, fact, why) -> { asked[0]++; return new Steps.Routine(List.of(craft, place), null); });
        for (int t = 1; t <= 6 && run.running(); t++) run.tick(t);
        assertEquals(Steps.Phase.FAILED, run.phase(), run.why());
        assertEquals(1, asked[0], "re-planned once; the second failure at -568 61 709 parks");
        assertTrue(run.why().contains("the same try failed again in this order"), run.why());

        PlaceHands moving = new PlaceHands();
        moving.moveEachTry = true;
        int[] asked2 = {0};
        Steps run2 = new Steps(moving);
        run2.start("goal.iron_pickaxe", List.of(place), 0, (failed, fact, why) -> { asked2[0]++; return new Steps.Routine(List.of(craft, place), null); });
        for (int t = 1; t <= 12; t++) run2.tick(t);
        assertTrue(run2.running(), "a new cell each try is a new question: " + run2.why());
        assertTrue(asked2[0] >= 3, "re-planned " + asked2[0]);
    }

    /** sb4 review 2: any part of the first-cells read that throws is said in the fact, never lost. */
    @Test
    void aFirstCellsReadThatThrowsIsSaid() {
        String t = io.github.mojolowjo.entropybot.aa.brain.StripOrder.guardedFirstCells(() -> { throw new IllegalArgumentException("no heading up"); });
        assertEquals("first cells: the rule could not be read: java.lang.IllegalArgumentException: no heading up", t);
        assertEquals("first cells: the rule not known", io.github.mojolowjo.entropybot.aa.brain.StripOrder.guardedFirstCells(() -> null));
        assertEquals("first cells: x", io.github.mojolowjo.entropybot.aa.brain.StripOrder.guardedFirstCells(() -> "first cells: x"));
    }

    // ---- 2. run AV's strip through the monitor ----

    static final Step STRIP_AV = new Step.Strip(List.of("minecraft:deepslate_iron_ore", "minecraft:iron_ore"), Map.of("iron", 3), -5, "south", 1L);

    /** One move of the strip: dig into {x, z} (3 looks: 2 breaking, 1 standing in it) or walk back to it (1 look). */
    record Move(boolean dig, int x, int z) {}

    /**
     * AV 02:41:36-02:43:29 (feet y -5 from -29 -71): tunnel 3 south, branch right 8 (west), back, branch left 8 (east), back,
     * tunnel 3, and again - one dug block every 3 s, as logged.
     */
    static List<Move> avMoves() {
        List<Move> m = new ArrayList<>();
        int z = -71;
        for (int round = 0; round < 3; round++) {
            for (int i = 0; i < 3; i++) m.add(new Move(true, -29, ++z));
            for (int i = 1; i <= 8; i++) m.add(new Move(true, -29 - i, z));
            m.add(new Move(false, -29, z));
            for (int i = 1; i <= 8; i++) m.add(new Move(true, -29 + i, z));
            m.add(new Move(false, -29, z));
        }
        return m;
    }

    static StepSignals sig(int[] feet, String goal, int stage, String cell) {
        return new StepSignals(true, null, feet, 3.6, goal, false, true, false, 0, -1, stage, cell, 12, -1, null, true);
    }

    /** The looks, one a second; {@code stageSeen} false: the old live read (-1 always). */
    static List<StepSignals> looks(boolean stageSeen) {
        List<Move> moves = avMoves();
        List<StepSignals> out = new ArrayList<>();
        int[] feet = {-29, -5, -71};
        for (int i = 0; i < moves.size(); i++) {
            Move mv = moves.get(i);
            String goal = "GoalBlock{x=" + mv.x() + ",y=-5,z=" + mv.z() + "}";
            String cell = mv.x() + " -5 " + mv.z();
            if (mv.dig()) {
                out.add(sig(feet, goal, stageSeen ? 2 : -1, stageSeen ? cell : null));
                out.add(sig(feet, goal, stageSeen ? 7 : -1, stageSeen ? cell : null));
            }
            feet = new int[]{mv.x(), -5, mv.z()};
            Move next = i + 1 < moves.size() ? moves.get(i + 1) : mv;
            out.add(sig(feet, "GoalBlock{x=" + next.x() + ",y=-5,z=" + next.z() + "}", -1, null));
        }
        return out;
    }

    /** The second of the first REPLAN, -1: none; {@code why} gets its reason. */
    static int firstReplan(List<StepSignals> looks, String[] why) {
        StepMonitor m = new StepMonitor();
        m.begin(STRIP_AV, looks.get(0), 0);
        for (int k = 1; k < looks.size(); k++) {
            StepMonitor.Look l = m.look(looks.get(k), 20L * k);
            if (l.v() == StepMonitor.Verdict.REPLAN) {
                why[0] = l.reason();
                return k;
            }
        }
        return -1;
    }

    /** The old read (stage -1 always): AV's stop - "no progress 30 s ... no block broken" about a minute into a working strip. */
    @Test
    void avStripWithoutTheStageIsCutAsInTheRun() {
        String[] why = {null};
        int s = firstReplan(looks(false), why);
        assertTrue(s > 30 && s <= 75, "AV: 60 s in; here " + s + " s");
        assertTrue(why[0].startsWith("no progress 30 s: the goal heuristic 3.6 (best 3.6"), why[0]);
        assertTrue(why[0].contains("no block broken"), why[0]);
    }

    /** The stage read: every dug cell is a new best, so the working strip runs on (over 2 min of looks, no REPLAN). */
    @Test
    void avStripWithTheStageRunsOn() {
        List<StepSignals> l = looks(true);
        assertTrue(l.size() > 150, "the replay covers " + l.size() + " s");
        String[] why = {null};
        assertEquals(-1, firstReplan(l, why), why[0]);
    }

    /** The fix hides no stall: a stage stuck on one cell (a stale break, nothing moving) still ends at 30 s. */
    @Test
    void aStaleStageOnOneCellIsNoProgress() {
        StepMonitor m = new StepMonitor();
        int[] feet = {-29, -5, -68};
        StepSignals still = sig(feet, "GoalBlock{x=-29,y=-5,z=-67}", 4, "-29 -5 -67");
        m.begin(STRIP_AV, still, 0);
        StepMonitor.Look last = null;
        int k = 1;
        for (; k <= 40; k++) {
            last = m.look(still, 20L * k);
            if (last.v() == StepMonitor.Verdict.REPLAN) break;
        }
        assertEquals(StepMonitor.Verdict.REPLAN, last.v(), last.reason());
        assertEquals(30, k, last.reason());
    }
}
