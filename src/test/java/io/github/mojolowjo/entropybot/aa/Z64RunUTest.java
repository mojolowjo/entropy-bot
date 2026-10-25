package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.NeedChain.Chain;
import io.github.mojolowjo.entropybot.aa.brain.TestData;
import io.github.mojolowjo.entropybot.aa.brain.Tuning;
import io.github.mojolowjo.entropybot.aa.moves.FactChange;
import io.github.mojolowjo.entropybot.aa.moves.Step;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 0.39.z64 item 2 (run U on z62, 2026-10-25 06:08:07-06:10:19, docs/runs/runU-z62): a fresh stone pickaxe (131 uses) broke
 * after 67 strip cells - the fork digs the feet and the head cell, two blocks a cell - while the chain's wear counted one
 * block a cell, so the spares made on the surface ran out at y 16 and the next pickaxe needed wood that was up there.
 */
class Z64RunUTest {

    @AfterEach
    void clear() {
        TestData.clear();
        FactChange.clear();
        Tuning.reset();
    }

    /**
     * Hand numbers: strip.maxBlocksPerOre 22 -> 3 ores x 22 = 66 cells; wear 2 x 66 + 3 = 135 > 131 uses: one spare stone
     * pickaxe, crafted before the strip (on the surface) from the bag's cobblestone and sticks. One block a cell (69) would
     * have made none - run U's east tunnel.
     */
    @Test
    void aStripCellWearsThePickaxeTwice() {
        ScenariosTest.world("wooden", "stone");
        Tuning.set("strip.maxBlocksPerOre", 22);
        Map<String, Integer> bag = Map.of("minecraft:stone_pickaxe", 1, "minecraft:cobblestone", 20, "minecraft:stick", 10);
        Chain c = NeedChain.resolve("raw_iron", 3, Z62RunSTest.world(bag, Z62RunSTest.band("east")).withUses(id -> 131));
        assertTrue(c.ok() && !c.explore(), c.why());
        assertTrue(c.why().contains("= 66 blocks"), c.why());
        assertEquals(1, Z62RunSTest.crafted(c, "stone_pickaxe"), c.steps().toString());
        int craft = -1, strip = -1;
        for (int i = 0; i < c.steps().size(); i++) {
            if (c.steps().get(i) instanceof Step.Craft k && k.item().equals("stone_pickaxe")) craft = i;
            if (c.steps().get(i) instanceof Step.Strip && strip < 0) strip = i;
        }
        assertTrue(craft >= 0 && craft < strip, "the spare (and its sticks) before the strip: " + c.steps());
        // 135 uses cover 2 x 66 + 3 exactly: none
        assertEquals(0, Z62RunSTest.crafted(NeedChain.resolve("raw_iron", 3, Z62RunSTest.world(bag, Z62RunSTest.band("east")).withUses(id -> 135)), "stone_pickaxe"));
        assertEquals(2, NeedChain.TUNNEL_HEIGHT);
    }
}
