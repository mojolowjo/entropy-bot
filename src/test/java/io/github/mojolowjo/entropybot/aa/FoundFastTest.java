package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.moves.FactChange;
import io.github.mojolowjo.entropybot.aa.moves.Step;
import io.github.mojolowjo.entropybot.aa.moves.Steps;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * found-fast (owner 2026-11-08; run JBG-T1 12:16-12:35: the explore for oak_log found a tree, stood up to 30 s for its walk's
 * price, cut it, explored again - ten times): a source the until-explore FOUND is taken at once on Baritone's estimate
 * (the hands put the walks past their wait, the planner is asked again), no stand. Hands that cannot: the stand as before.
 */
class FoundFastTest {
    static class Hands implements Steps.Hands {
        final List<String> started = new ArrayList<>();
        int cancels, pastWaits;
        boolean can = true;
        @Override public String start(Step s) { started.add(s.text()); return null; }
        @Override public io.github.mojolowjo.entropybot.aa.moves.Car.Status status(Step s) { return io.github.mojolowjo.entropybot.aa.moves.Car.Status.running("running"); }
        @Override public void cancel(String why) { cancels++; }
        @Override public FactChange.Fact lastFact() { return null; }
        @Override public boolean pastWait() { pastWaits++; return can; }
    }

    static final Step EX = new Step.Explore(5, "oak_log"), STAND = new Step.AwaitPaths(30), CUT = new Step.ChopTree(new int[]{-117, 64, -11}, 4, null);
    static final Step CRAFT = new Step.Craft("minecraft:oak_planks", 4);

    @Test
    void aFoundSourceIsTakenAtOnceOnTheEstimate_noStand() {
        Hands h = new Hands();
        Steps run = new Steps(h);
        List<List<Step>> answers = new ArrayList<>(List.of(List.of(STAND), List.of(CUT, CRAFT)));   // first the stand, then the priced way by the estimate
        run.start("goal.oak_log", List.of(EX, CRAFT), 0, (failed, fact, why) -> new Steps.Routine(answers.remove(0), null));
        run.repriceAtNextStep(FactChange.found("a source of oak_log is known: oak_log at -117 64 -11 (5 away)"));
        run.tick(1);
        assertEquals(1, h.pastWaits, "the walks are put past their wait once");
        assertEquals(1, h.cancels, "the explore ends for the found tree");
        assertEquals(CUT.text(), h.started.get(h.started.size() - 1), "the cut starts, never the stand: " + h.started);
        assertTrue(h.started.stream().noneMatch(s -> s.startsWith(Step.AwaitPaths.TEXT)), h.started.toString());
        assertTrue(run.why().contains("taken at once on Baritone's estimate"), run.why());
    }

    @Test
    void handsThatCannotLeaveTheStandAsBefore() {
        Hands h = new Hands();
        h.can = false;
        Steps run = new Steps(h);
        List<List<Step>> answers = new ArrayList<>(List.of(List.of(STAND)));
        run.start("goal.oak_log", List.of(EX, CRAFT), 0, (failed, fact, why) -> new Steps.Routine(answers.remove(0), null));
        run.repriceAtNextStep(FactChange.found("a source of oak_log is known: oak_log at -117 64 -11 (5 away)"));
        run.tick(1);
        assertEquals(1, h.cancels);
        assertEquals(STAND.text(), h.started.get(h.started.size() - 1), "the FH stand: " + h.started);
    }

    @Test
    void aSecondAnswerThatStillStandsKeepsTheStand() {
        Hands h = new Hands();
        Steps run = new Steps(h);
        List<List<Step>> answers = new ArrayList<>(List.of(List.of(STAND), List.of(STAND)));   // the estimate did not price it either: the stand
        run.start("goal.oak_log", List.of(EX, CRAFT), 0, (failed, fact, why) -> new Steps.Routine(answers.remove(0), null));
        run.repriceAtNextStep(FactChange.found("a source of oak_log is known: oak_log at -117 64 -11 (5 away)"));
        run.tick(1);
        assertEquals(1, h.pastWaits);
        assertEquals(STAND.text(), h.started.get(h.started.size() - 1), h.started.toString());
    }
}
