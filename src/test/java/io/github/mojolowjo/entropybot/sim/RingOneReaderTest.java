package io.github.mojolowjo.entropybot.sim;

import io.github.mojolowjo.entropybot.aa.brain.TestData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * job-hold-sight review 3 (rule 23 (2), the reviewer: the hole reader read the ground under any tree while the surface
 * leg's ring read the leaf-free top, two readers of a column's height that disagree where a trunk or post borders the
 * ring): on flat ground beside a bare stump of two logs the hole reader says "no hole" (a trunk is no wall), and the ring's
 * leg target must agree - the stump's top is no surface to climb onto; the feet's own ground is the surface. The
 * simulator's land reads the game's rule (SimWorld.holeDepth / ringTop over the one column reader, Jobs.ground).
 */
class RingOneReaderTest {
    @BeforeAll
    static void boot() { SimSearch.boot(); }

    @AfterEach
    void clear() { TestData.clear(); }

    @Test
    void aStumpBesideTheFeet_isNoHoleWall_andNoLegTarget() {
        TestData.planner().tag("oak_log", "minecraft:logs").install();     // the game's LOGS tag, as the simulator's data has it
        SimWorld w = SimWorld.flat(64, "minecraft:grass_block", 12);
        w.column(1, 65, 0, 2, "minecraft:oak_log");                 // a bare stump, two logs, no leaves
        int[] feet = {0, 65, 0};
        assertEquals(0, w.holeDepth(0, 65, 0), "the hole reader: a trunk is no wall");
        int[] t = w.ringTop(feet);
        assertNotNull(t, "the ring has standable ground");
        assertEquals(65, t[1], "the ring's target is the ground, not the stump's top at 67 (one reader with the hole's)");
    }
}
