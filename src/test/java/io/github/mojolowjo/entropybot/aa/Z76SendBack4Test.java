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
        int old = Tuning.i("replan.maxPerOrder") + Tuning.i("replan.maxSourceDrops");
        assertEquals(11, old, "the deleted cap was 3 + 8");
        for (int t = 1; t <= 20; t++) run.tick(t);
        assertTrue(run.running(), run.why());
        assertEquals(20, facts.size(), facts.toString());
        assertEquals(20, facts.stream().distinct().count(), "each re-plan on a new fact");
        assertFalse(String.valueOf(run.why()).contains("in this run already"), run.why());
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
        return new StepSignals(true, null, feet, 3.6, goal, false, true, false, 0, -1, stage, cell, 12, -1, null);
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
        assertTrue(why[0].startsWith("no progress 30 s: the goal 3.6 (best 3.6"), why[0]);
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
