package io.github.mojolowjo.entropybot.aa.brain;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** AA_ROUND2 4: the rung values move with the tech tree and the night's danger. */
class WorthTest {
    static TechTree tree() {
        return new TechTree(List.of(
                new TechTree.Recipe("iron_pickaxe", List.of(Set.of("iron_ingot"), Set.of("iron_ingot"), Set.of("iron_ingot"), Set.of("stick"), Set.of("stick"))),
                new TechTree.Recipe("stone_pickaxe", List.of(Set.of("cobblestone"), Set.of("cobblestone"), Set.of("cobblestone"), Set.of("stick"), Set.of("stick"))),
                new TechTree.Recipe("bucket", List.of(Set.of("iron_ingot"), Set.of("iron_ingot"), Set.of("iron_ingot"))),
                new TechTree.Recipe("stick", List.of(Set.of("oak_planks"), Set.of("oak_planks"))),
                new TechTree.Recipe("oak_button", List.of(Set.of("oak_planks")))));
    }

    @Test
    void stationsFollowTheTree() {
        TechTree t = tree();
        double table = Worth.table(t, Set.of());
        assertTrue(table > 40 && table < 80, "two of five results need the 3x3 grid: " + table);
        assertEquals(40, Worth.table(t, Set.of("iron_pickaxe", "stone_pickaxe")), 1e-9, "nothing left that needs the grid");
        assertEquals(80, Worth.furnace(t, Set.of()), 1e-9, "iron is the best item here");
        assertTrue(Worth.furnace(t, Set.of("iron_pickaxe", "bucket")) < 80, "iron's uses had: the furnace is worth less");
        assertEquals(80, Worth.table(null, Set.of()), 1e-9, "no tree yet: the full share");
        assertEquals(Worth.table(t, Set.of()) / 2, Worth.spareTable(t, Set.of()), 1e-9);
    }

    @Test
    void bedAndBaseFollowTheDanger() {
        assertEquals(20, Worth.bed(0), 1e-9, "Peaceful");
        assertEquals(16, Worth.base(0), 1e-9);
        double easy = Costs.nightDanger(1, 0, 20, 20, 0, 0), hard = Costs.nightDanger(3, 0, 20, 20, 0, 0);
        assertTrue(Worth.bed(hard) > Worth.bed(easy) && Worth.bed(easy) > 20);
        assertEquals(0.8 * Worth.bed(easy), Worth.base(easy), 1e-9);
        assertTrue(Worth.bed(5) <= 99);
    }
}
