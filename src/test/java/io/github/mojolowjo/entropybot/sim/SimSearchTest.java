package io.github.mojolowjo.entropybot.sim;

import baritone.api.pathing.calc.IPath;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.movement.IMovement;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

/** sim step 3, the trial: Baritone's real search answers on the sim's blocks (a flat walk; a shaft with and without filler). */
class SimSearchTest {
    static Map<String, Integer> kinds(IPath p) {
        Map<String, Integer> out = new TreeMap<>();
        for (IMovement m : p.movements()) out.merge(m.getClass().getSimpleName(), 1, Integer::sum);
        return out;
    }

    @Test
    void aFlatWalk_isWalked() throws Exception {
        SimWorld w = SimWorld.flat(63, "minecraft:grass_block", 40);
        IPath p = SimSearch.search(SimSearch.context(w, false, 60, 1), new int[]{0, 64, 0}, new GoalBlock(10, 64, 0));
        assertNotNull(p, "a path over flat grass");
        System.out.println("flat: " + kinds(p) + " ends " + p.getDest());
        assertEquals(10, p.getDest().x);
        assertEquals(64, p.getDest().y);
    }

    /** Bare hands (with a pickaxe Baritone digs a stair either way, seen 2026-11-16). A 1x1 shaft 3 deep in stone (the flat map is 4 thick): with filler the search pillars up; with none it digs a way (no pillar move). */
    @Test
    void outOfAShaft_pillarsOnlyWithFiller() throws Exception {
        SimWorld w = SimWorld.flat(63, "minecraft:stone", 40);
        for (int y = 61; y <= 63; y++) w.set(0, y, 0, SimWorld.AIR);
        int[] feet = {0, 61, 0};
        IPath with = SimSearch.search(SimSearch.context(w, true, 60, 1), feet, new baritone.api.pathing.goals.GoalYLevel(64));
        IPath without = SimSearch.search(SimSearch.context(w, false, 60, 1), feet, new baritone.api.pathing.goals.GoalYLevel(64));
        assertNotNull(with, "a way up with filler");
        assertNotNull(without, "a way up without filler");
        System.out.println("shaft with filler: " + kinds(with) + "\nshaft without: " + kinds(without));
        assertTrue(kinds(with).containsKey("MovementPillar"), kinds(with).toString());
        assertFalse(kinds(without).containsKey("MovementPillar"), kinds(without).toString());
    }
}
