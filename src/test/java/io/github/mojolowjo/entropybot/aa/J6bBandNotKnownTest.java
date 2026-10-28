package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.brain.Goals;
import io.github.mojolowjo.entropybot.aa.brain.TestData;
import io.github.mojolowjo.entropybot.aa.brain.Tuning;
import io.github.mojolowjo.entropybot.aa.brain.WorldFacts;
import io.github.mojolowjo.entropybot.aa.core.Blackboard;
import io.github.mojolowjo.entropybot.aa.core.Clock;
import io.github.mojolowjo.entropybot.aa.core.FakePort;
import io.github.mojolowjo.entropybot.aa.core.Tracer;
import io.github.mojolowjo.entropybot.aa.moves.BandLeg;
import io.github.mojolowjo.entropybot.aa.moves.Car;
import io.github.mojolowjo.entropybot.aa.moves.FactChange;
import io.github.mojolowjo.entropybot.aa.moves.Step;
import io.github.mojolowjo.entropybot.aa.moves.Steps;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 0.39.z78 J6b queue item 14 (the manager's ruling on run ER (2); docs/runs/runER-z78j6n 07:37:48-07:38:18): the pickaxe's
 * dig-down started on the floorless edge cell -10 61 69 (save: -10 60 69 air, the floor at 59; the bot stood on the lip of
 * -10 60 68's grass). The priced search to -10 45 69 stopped on its budget with no step past the start for 30 s, the wait's
 * estimate was read as "no path into the band from y 61", and that world fact held the band for the helmet, chestplate,
 * leggings and boots. The ruling: a budget stop is NOT KNOWN (rule 15), never "no path", never a world fact; only a
 * complete search's no-path from a loaded, standable start is the band's fact; the leg is priced from the cell Baritone's
 * searches start from (its pathStart: the supporting block off an edge - -10 61 68 here), standable or said.
 */
class J6bBandNotKnownTest {
    @AfterEach
    void clear() {
        TestData.clear();
        FactChange.clear();
        Tuning.reset();
    }

    static final int[] EDGE = {-10, 61, 69}, START = {-10, 61, 68};

    @Test
    void theBandLegsAnswers() {
        var est = BandLeg.of(PathPrice.Answer.estimated(new PathPrice.Walk(40), "not known after 30 s from here (no progress ...): Baritone's estimate, ranked, not priced"), START, true);
        assertEquals(BandLeg.Act.WALK, est.act(), "ER's estimate: walked on Baritone's own search, never 'no path' (was: the band's fact)");
        assertEquals(BandLeg.Act.WALK, BandLeg.of(PathPrice.Answer.priced(new PathPrice.Walk(20)), START, true).act());
        assertEquals(BandLeg.Act.WAIT, BandLeg.of(PathPrice.Answer.computing("no progress"), START, true).act());
        assertEquals(BandLeg.Act.WAIT, BandLeg.of(PathPrice.Answer.chunks("chunks not loaded"), START, true).act());
        var none = BandLeg.of(PathPrice.Answer.none(PathPrice.NO_ESTIMATE), START, true);
        assertEquals(BandLeg.Act.NOT_KNOWN, none.act(), none.why());
        var fact = BandLeg.of(PathPrice.Answer.unreachable("no path: the complete search found none"), START, true);
        assertEquals(BandLeg.Act.FACT, fact.act());
        assertEquals("no path into the band from -10 61 68 (no path: the complete search found none)", fact.why(), "a true no-path, from the standable start cell");
        var floorless = BandLeg.of(PathPrice.Answer.unreachable("no path: the complete search found none"), EDGE, false);
        assertEquals(BandLeg.Act.NOT_KNOWN, floorless.act(), "a start with no floor: not a true no-path");
        assertTrue(floorless.why().contains("-10 61 69") && floorless.why().contains("no floor"), floorless.why());
    }

    /**
     * job-vein a2d4e1c8: "no path" only from the fork's exhausted flag - a budget stop is not known, whatever the reason's
     * words; no calc kept is not known too; the walk's own end the same.
     */
    @Test
    void aBudgetStop_isNotKnown_anExhaustedSearch_isNoPath() {
        var budget = io.github.mojolowjo.entropybot.commands.PricedAnswers.noPath("unreachable", false, true, "chunks not loaded");
        assertEquals(PathPrice.Kind.COMPUTING, budget.kind(), "a budget stop, even with the 'unreachable' words: not known - " + budget);
        assertEquals(BandLeg.Act.WAIT, BandLeg.of(budget, START, true).act());
        var exhausted = io.github.mojolowjo.entropybot.commands.PricedAnswers.noPath("no progress", true, false, "chunks not loaded");
        assertEquals(PathPrice.Kind.UNREACHABLE, exhausted.kind(), "the graph exhausted: no path, whatever the words - " + exhausted);
        assertEquals(BandLeg.Act.FACT, BandLeg.of(exhausted, START, true).act());
        var unkept = io.github.mojolowjo.entropybot.commands.PricedAnswers.noPath("unreachable", null, null, "chunks not loaded");
        assertEquals(PathPrice.Kind.COMPUTING, unkept.kind(), "no calc kept: not known - " + unkept);
        assertTrue(unkept.reason().startsWith(io.github.mojolowjo.entropybot.commands.PricedAnswers.NO_STOP_KEPT), unkept.reason());
        assertEquals(PathPrice.Kind.CHUNKS, io.github.mojolowjo.entropybot.commands.PricedAnswers.noPath("chunks not loaded", false, false, "chunks not loaded").kind());
        // the walk's end
        assertNull(BandLeg.walkEnd(true, START, true, 61), "exhausted from a standable start: the band's fact");
        assertTrue(BandLeg.walkEnd(false, START, true, 61).contains("a budget stop"), "a budget stop: not known");
        assertTrue(BandLeg.walkEnd(null, START, true, 61).contains("no calc kept"));
        assertTrue(BandLeg.walkEnd(true, EDGE, false, 61).contains("has no floor"), "no floor: not known even exhausted");
    }

    /** 569bb820 review (1): neither flag (CHUNKS / CANCELLED / ERROR stops) is not known, the reason carried - never none. */
    @Test
    void neitherFlag_isNotKnown_theReasonCarried() {
        for (String r : List.of("cancelled", "error", "chunks")) {
            var a = io.github.mojolowjo.entropybot.commands.PricedAnswers.noPath(r, false, false, "chunks not loaded");
            assertEquals(PathPrice.Kind.COMPUTING, a.kind(), r + ": " + a);
            assertTrue(a.reason().startsWith(io.github.mojolowjo.entropybot.commands.PricedAnswers.OTHER_STOP) && a.reason().endsWith("(" + r + ")"), a.reason());
            assertEquals(BandLeg.Act.WAIT, BandLeg.of(a, START, true).act(), "never the band's fact");
        }
    }

    /** 569bb820 review (2): an explore target fails on a no-break walk's end only when that search exhausted the graph. */
    @Test
    void aNoBreakEnd_failsTheTarget_onlyWhenExhausted() {
        var E = io.github.mojolowjo.entropybot.cave.ExploreRules.NoBreakEnd.class;
        assertEquals(io.github.mojolowjo.entropybot.cave.ExploreRules.NoBreakEnd.NO_PATH, io.github.mojolowjo.entropybot.cave.ExploreRules.noBreakEnd("no path (no-break walk)", true, true));
        assertEquals(io.github.mojolowjo.entropybot.cave.ExploreRules.NoBreakEnd.NOT_KNOWN, io.github.mojolowjo.entropybot.cave.ExploreRules.noBreakEnd("no path (no-break walk)", true, false), "a budget stop: not known");
        assertEquals(io.github.mojolowjo.entropybot.cave.ExploreRules.NoBreakEnd.NOT_KNOWN, io.github.mojolowjo.entropybot.cave.ExploreRules.noBreakEnd("no path (no-break walk)", null, null), "no calc kept: not known");
        assertEquals(io.github.mojolowjo.entropybot.cave.ExploreRules.NoBreakEnd.NOT_KNOWN, io.github.mojolowjo.entropybot.cave.ExploreRules.noBreakEnd("no path (no-break walk)", false, true), "the calc of another (breaking) walk: not this one's");
        assertEquals(io.github.mojolowjo.entropybot.cave.ExploreRules.NoBreakEnd.NONE, io.github.mojolowjo.entropybot.cave.ExploreRules.noBreakEnd("arrived", true, true));
        assertNotNull(E);
    }

    /** ER: the not-known band leg re-plans the pickaxe; nothing held for it, no world fact for the boots (was: all four held it). */
    @Test
    void aNotKnownBandLeg_noWorldFact_theOtherOrdersStillPriceIt() {
        ScenariosTest.world("wooden", "stone");
        Clock.Fake clock = new Clock.Fake(10_000_000);
        Blackboard bb = new Blackboard(clock, new FakePort(), new Tracer());
        bb.fact(NeedChain.WORLD_FACT, (Supplier<NeedChain.World>) () -> ScenariosTest.w(Map.of("minecraft:cobblestone", 8), true, true, Map.of()));
        bb.in.pos = EDGE.clone();
        bb.in.bag = Map.of("minecraft:cobblestone", 8);
        Goals.Goal pick = Goals.draft(DebugTreeTest.item("iron_ingot"), 3, 0, clock.now());
        Step.ToBand leg = new Step.ToBand(45, 56);
        FactChange.bandNotKnown(45, 56, BandLeg.of(PathPrice.Answer.none(PathPrice.NO_ESTIMATE), START, true).why());
        FactChange.Fact f = FactChange.take();
        assertFalse(f.key().startsWith("source "), "never a source fact: " + f.key());
        Goals.replan(bb, pick, leg, f, f.text());
        assertTrue(WorldFacts.of(bb).heldAt(bb).isEmpty(), "no world fact: " + WorldFacts.of(bb).heldAt(bb));
        assertFalse(Goals.droppedSources(pick).containsKey(FactChange.bandKey(45, 56)), "nothing held for the order either");
        // control: a true no-path from a standable start is the band's fact, shared as before (rule 23 (1))
        FactChange.band(45, 56, BandLeg.of(PathPrice.Answer.unreachable("no path: the complete search found none"), START, true).why());
        Goals.replan(bb, pick, leg, FactChange.take(), "no path");
        assertTrue(WorldFacts.of(bb).heldAt(bb).containsKey(FactChange.bandKey(45, 56)), WorldFacts.of(bb).heldAt(bb).toString());
    }

    /** The band leg's column is the search's start cell (Hands.standStart), not the floorless feet's: -10 .. 68, not 69. */
    @Test
    void theBandLegsCell_isTheSearchStartsColumn() {
        Steps run = new Steps(new Steps.Hands() {
            @Override public String start(Step s) { return null; }
            @Override public Car.Status status(Step s) { return Car.Status.running("walking"); }
            @Override public void cancel(String why) {}
            @Override public int[] feet() { return EDGE.clone(); }
            @Override public int[] standStart() { return START.clone(); }
            @Override public io.github.mojolowjo.entropybot.aa.moves.StepNeeds.Bag bag() {
                return new io.github.mojolowjo.entropybot.aa.moves.StepNeeds.Bag() {
                    @Override public int bestTier(String kind) { return 0; }
                    @Override public String bestTool(String kind) { return null; }
                    @Override public int usesLeft(String id) { return io.github.mojolowjo.entropybot.aa.brain.ToolUses.UNLIMITED; }
                    @Override public int count(String id) { return 0; }
                };
            }
        });
        run.start("goal.iron_boots", List.of(new Step.ToBand(45, 56)), 0, (failed, fact, why) -> new Steps.Routine(null, "no"));
        for (int t = 1; t <= 3; t++) run.tick(t);
        Steps.Ahead a = run.ahead();
        assertNotNull(a, "the running band leg");
        assertArrayEquals(new int[]{-10, new Step.ToBand(45, 56).target(61), 68}, a.cell(), "the start cell's column (z 68, not the feet's 69) at the band's target Y");
        assertTrue(a.into());
    }
}
