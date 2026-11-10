package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.moves.ReflexGoals;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * sim (owner 2026-11-09: reproduce, fix, prove; values swept): runs JBK-T2 15:20:11, JBM-T2 21:43:51, JBP-T2 23:09:04 and
 * 23:13:07 - the climb after a dig-down collect "ended (failed) after 0.1 s ... Baritone's climb goal ended at -57 87 -12
 * with 16 blocks over the feet", its start line "a dug level priced 8 ticks: no solid cell over the feet". The bot stood
 * at the bottom of the 1x1 shaft it had just dug: open to the sky. The climb's Baritone goal was "the feet under open
 * sky" - true where it stood, so Baritone's walk "arrived" in its first tick; the step's own done test (below the
 * surface: a sky-open shaft counts, Jobs.belowSurface) said 16 down. The goal and the done test must be one answer.
 */
class ClimbFromShaftTest {

    /** A flat ground at {@code ground} (feet stand at ground + 1) with a 1x1 shaft at x 0, z 0 dug {@code depth} down, open to the sky. */
    static ReflexGoals.SkyCells shaft(int ground, int depth) {
        return new ReflexGoals.SkyCells() {
            boolean inShaft(int x, int z) { return x == 0 && z == 0; }
            @Override public boolean open(int x, int y, int z) { return y > ground || (inShaft(x, z) && y > ground - depth); }
            @Override public boolean sky(int x, int y, int z) { return open(x, y, z); }
            @Override public boolean hole(int x, int y, int z) {
                int[] n = new int[8];
                java.util.Arrays.fill(n, ground + 1);                 // the 8 columns round the shaft: the ground's feet level
                if (!inShaft(x, z)) n[0] = y;                         // beside the shaft, one neighbour is the shaft itself: lower, never higher
                return io.github.mojolowjo.entropybot.world.SurfaceHeight.holeDepth(y, n) > 0;
            }
        };
    }

    /** The runs' own numbers: the rim's feet level 103, the feet 16 down at 87. */
    @Test
    void theBottomOfASkyOpenShaftIsNotTheClimbsGoal() {
        var cells = shaft(102, 16);
        var goal = new ReflexGoals.UnderSky(cells, 103, ReflexGoals.digLevelTicks(0));
        assertFalse(goal.isInGoal(0, 87, 0), "16 down a sky-open shaft is not out (the run: the walk 'arrived' there in its first tick)");
        assertFalse(goal.isInGoal(0, 95, 0), "nor half way up it");
        assertTrue(goal.isInGoal(1, 103, 0), "the ground beside the shaft is");
        assertTrue(goal.isInGoal(0, 103, 0), "and the shaft's own top, pillared up to the ground's level");
    }

    /** The depths round the run's, swept: no cell of the shaft below the ground is the goal; a 1-block step is (a player walks out). */
    @Test
    void sweptDepths_noCellDownTheShaftIsOut() {
        List<String> wrong = new ArrayList<>();
        for (int ground : new int[]{-40, 11, 62, 102, 250})
            for (int depth : new int[]{2, 3, 5, 16, 40, 120}) {
                var goal = new ReflexGoals.UnderSky(shaft(ground, depth), ground + 1, ReflexGoals.digLevelTicks(0));
                for (int y = ground + 1 - depth; y <= ground - 1; y++)
                    if (goal.isInGoal(0, y, 0)) wrong.add("ground " + ground + ", depth " + depth + ": y " + y + " counted as out");
                if (!goal.isInGoal(0, ground + 1, 0)) wrong.add("ground " + ground + ", depth " + depth + ": the top is not out");
                if (!goal.isInGoal(3, ground + 1, 3)) wrong.add("ground " + ground + ", depth " + depth + ": the open ground is not out");
            }
        assertTrue(wrong.isEmpty(), wrong.size() + " wrong, the first: " + wrong.stream().limit(3).toList());
        var step = new ReflexGoals.UnderSky(shaft(62, 1), 63, ReflexGoals.digLevelTicks(0));
        assertTrue(step.isInGoal(0, 62, 0), "one block down is a step, not a hole: out");
    }
}
