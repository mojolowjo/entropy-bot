package io.github.mojolowjo.entropybot.commands;

import io.github.mojolowjo.entropybot.aa.NeedChain;
import io.github.mojolowjo.entropybot.aa.NeedChain.Surface;
import io.github.mojolowjo.entropybot.aa.PlanNodes;
import io.github.mojolowjo.entropybot.aa.brain.Tuning;
import io.github.mojolowjo.entropybot.aa.moves.Step;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Queue item 25 (run FG: 655 "surface" drops, all own-goal trips; run FP: 3,936 of 4,509 drops, 176 results in a row dropped,
 * the own trips starved): the live surface fact moves with every block walked - its distance and its nearest sky cell - but a
 * result that walks to no surface cell used only the under-ground state; one with a walk up uses the cell, never the distance.
 */
class Item25SurfaceSameTest {
    static final PlanNodes.Fact F = new PlanNodes.Fact("surface", null);

    @Test
    void aResultWithNoWalkUp_seesOnlyTheUnderGroundState() {
        int under = (int) Tuning.get("aaLadder.surfaceDepth") + 3;
        Surface a = new Surface(under, -328, 63, 33, 7.0), b = new Surface(under + 1, -327, 63, 34, 7.07), c = new Surface(0, -43, 69, -34, 3.1);
        assertTrue(PlannerPort.same(F, a, b, false), "deeper by a block, the nearest sky cell one over: the same under-ground state");
        assertFalse(PlannerPort.same(F, a, c, false), "under ground -> on the surface: a change");
        Surface hole = new Surface(2, -43, 69, -34, 3.1, true);
        assertFalse(PlannerPort.same(F, c, hole, false), "a hole round the feet is below the surface (run EH): a change");
    }

    @Test
    void aResultWithAWalkUp_seesTheCell_neverTheStraightDistance() {
        Surface a = new Surface(0, -43, 69, -34, 3.74), b = new Surface(0, -43, 69, -34, 2.45), c = new Surface(0, -44, 69, -31, 3.0);
        assertTrue(PlannerPort.same(F, a, b, true), "the same cell, the bot a block nearer: the same");
        assertFalse(PlannerPort.same(F, a, c, true), "another surface cell: a change for a walk up");
        assertFalse(PlannerPort.same(F, a, new Surface(0, -43, 69, -34, 0.0), true), "the target is here now (dist < 1): a change (no walk up)");
        assertFalse(PlannerPort.same(F, a, a.withFloor(60), true), "the floor read: a change");
        assertTrue(PlannerPort.same(F, a, b), "the old two-argument form compares as a walk up would");
    }

    @Test
    void theResultsStepsSayWhetherTheCellMatters() {
        var walkUp = new NeedChain.Chain(List.of(new Step.ToSurface(-43, 69, -34), new Step.Explore(5, "raw_iron")), 40, "up", true, null, List.of(), Map.of(), List.of());
        var caving = new NeedChain.Chain(List.of(new Step.Cave(46, 57, -1, "iron", 3, 10)), 40, "cave", false, null, List.of(), Map.of(), List.of());
        assertTrue(PlannerPort.usesSurfaceCell(done(walkUp)));
        assertFalse(PlannerPort.usesSurfaceCell(done(caving)));
        assertFalse(PlannerPort.usesSurfaceCell(new PlannerWorker.Done(null, null, null, 0, null)), "no legs: no cell");
    }

    private static PlannerWorker.Done done(NeedChain.Chain c) {
        return new PlannerWorker.Done(null, new NeedChain.Legs(c, Map.of()), null, 0, null);
    }
}
