package io.github.mojolowjo.entropybot.sense;

import io.github.mojolowjo.entropybot.cave.CaveWalk;
import io.github.mojolowjo.entropybot.cave.ExploreRules;
import io.github.mojolowjo.entropybot.cave.MineRules;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 0.39.z78 J6c run ER (docs/runs/runER-z78j6n; save runER-z78j6n.zip, r.-1.0.mca): the helmet's caving step mined the
 * vein at -10 -5 62 (07:41:17, "8 cells": mineRules.atVein 8) and Baritone's mine process broke all 9 iron blocks (the
 * probe tool lines, 07:41:18-07:41:29; the save: all air), -8 -5 62 the 9th, outside the list. The record heard only the
 * listed cells, kept -8 -5 62 "left" (07:44:59 "iron 1 left 9 mined"), and the chestplate's caving step walked to it and
 * ended NO_PROGRESS ("no block of the vein is there to mine"). The fix: the record hears every broken ore
 * (Sightings.oreBroken, from ChunkPass.blockChanged; rule 23 (2)); MineRules.vein tests its start, so a gone ore is the
 * next move.
 */
class J6cVeinEndTest {
    static final String D = "minecraft:overworld";

    /** ER's 9 iron blocks, as the probe tool lines broke them (the save: every one air now). */
    static final int[][] VEIN = {{-10, -5, 62}, {-10, -6, 62}, {-9, -6, 62}, {-10, -5, 61}, {-10, -6, 61},
            {-8, -5, 62}, {-8, -6, 62}, {-9, -5, 61}, {-9, -6, 61}};

    @AfterEach
    void clear() { io.github.mojolowjo.entropybot.aa.brain.Tuning.reset(); }

    static Set<String> keys(int[][] cells) {
        Set<String> s = new HashSet<>();
        for (int[] c : cells) s.add(c[0] + " " + c[1] + " " + c[2]);
        return s;
    }

    /** A known cave beside the vein: the air cells the bot stood in (-11 -4 62 .. -9 -5 62 in the save). */
    static Sightings store() {
        Sightings s = new Sightings();
        List<Long> air = new ArrayList<>(List.of(Sightings.cell(-11, -4, 62), Sightings.cell(-12, -4, 62), Sightings.cell(-9, -5, 62),
                Sightings.cell(-9, -4, 62), Sightings.cell(-8, -4, 62), Sightings.cell(-7, -4, 62)));
        assertNotNull(s.knowCave(D, new CaveIndex.Known(-11, -4, 62, CaveIndex.Size.SMALL, CaveIndex.Depth.DEEPSLATE, List.of(air.get(0))), 1));
        s.caveCells(D, -11, -4, 62, air);
        for (int[] c : VEIN) assertTrue(s.caveOre(D, -11, -4, 62, Sightings.cell(c[0], c[1], c[2]), "iron"));
        return s;
    }

    @Test
    void theCapCutsTheNinthBlock_asInRunEr() {
        Set<String> ore = keys(VEIN);
        List<int[]> v = MineRules.vein(new int[]{-10, -5, 62}, c -> ore.contains(c[0] + " " + c[1] + " " + c[2]), MineRules.atVein());
        assertEquals(8, v.size(), "mineRules.atVein 8");
        assertTrue(v.stream().noneMatch(c -> c[0] == -8 && c[1] == -5 && c[2] == 62), "-8 -5 62 is the 9th (BFS layers 1+2+3+2)");
    }

    @Test
    void everyBrokenOre_isMinedInTheRecord_theNextCavingStepNeverWalksToIt() {
        Sightings s = store();
        assertArrayEquals(new int[]{9, 0}, s.caveFacts(D, -11, -4, 62).ores().get("iron"));
        // mineWith broke all 9 - ChunkPass.blockChanged hands each ore -> no ore change to the record
        for (int[] c : VEIN) assertTrue(s.oreBroken(D, c[0], c[1], c[2]), "heard: " + c[0] + " " + c[1] + " " + c[2]);
        assertFalse(s.oreBroken(D, -8, -5, 62), "heard once");
        assertFalse(s.oreBroken(D, 0, 0, 0), "a cell no record holds: nothing");
        assertArrayEquals(new int[]{0, 9}, s.caveFacts(D, -11, -4, 62).ores().get("iron"), "9 mined, none left");
        assertTrue(s.caveLeft(D, -11, -4, 62, "iron").isEmpty());
        // the chestplate's step (07:44:59, feet -8 56 73): no seen ore to walk to
        var next = CaveWalk.next(0, 1, 0, 10, s.caveFacts(D, -11, -4, 62), s.caveFrontier(D, -11, -4, 62),
                s.caveLeft(D, -11, -4, 62, "iron"), new int[]{-8, 56, 73}, ExploreRules.OPENING_REACH);
        String said = String.valueOf(next);
        assertFalse(next instanceof CaveWalk.Mine, said);
        assertFalse(said.contains("seen ore at -8 -5 62"), said);
    }

    @Test
    void aGoneSeenOre_isNoVein_soTheGuardPicksTheNextMove() {
        // the world now: no iron at -8 -5 62 (the save: air)
        assertTrue(MineRules.vein(new int[]{-8, -5, 62}, c -> false, MineRules.atVein()).isEmpty(),
                "no vein: StepHands' 'the seen ore is gone, picking the next move' fires (was: the start kept, VeinMine ended the step)");
        Sightings s = store();
        assertTrue(s.oreBroken(D, -8, -5, 62), "the guard marks it the same way");
        assertEquals(8, s.caveLeft(D, -11, -4, 62, "iron").size());
    }
}
