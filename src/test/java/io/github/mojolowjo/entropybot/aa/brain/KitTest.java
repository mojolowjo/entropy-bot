package io.github.mojolowjo.entropybot.aa.brain;

import io.github.mojolowjo.entropybot.aa.AaDesires;
import io.github.mojolowjo.entropybot.aa.core.Blackboard;
import io.github.mojolowjo.entropybot.aa.core.Clock;
import io.github.mojolowjo.entropybot.aa.core.FakePort;
import io.github.mojolowjo.entropybot.aa.core.Tracer;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** aa-kit: the kit's worth, the recover after a death, the stash before dusk, the camp's chest chain. */
class KitTest {
    final Clock.Fake clock = new Clock.Fake(10_000_000);
    final FakePort port = new FakePort();
    final Blackboard bb = new Blackboard(clock, port, new Tracer());

    @Test
    void worthOfToolsFoodAndLoot() {
        assertEquals(15, Kit.toolValue("minecraft:stone_pickaxe"));
        assertEquals(9, Kit.toolValue("stone_sword"), 1e-9);
        assertEquals(5, Kit.toolValue("iron_chestplate"));
        assertEquals(0, Kit.toolValue("iron_ore"));
        assertTrue(Kit.foodValue("cooked_beef") > 5);
        assertTrue(Kit.kept("bread") && Kit.kept("torch") && !Kit.kept("raw_iron"));
        Map<String, Integer> bag = Map.of("stone_pickaxe", 1, "raw_iron", 10, "cobblestone", 50);
        assertEquals(15 + 15 + 1, Kit.worth(bag), 1e-9);
        assertEquals(15, Kit.lootWorth(bag), 1e-9);      // plain blocks are no loot
    }

    @Test
    void missingIsWhatTheBagLacks() {
        Map<String, Integer> dead = Map.of("stone_pickaxe", 1, "raw_iron", 10);
        assertEquals(30, Kit.missing(dead, Map.of()), 1e-9);
        assertEquals(0, Kit.missing(dead, dead), "keepInventory: nothing to go back for");
        assertEquals(7.5, Kit.missing(dead, Map.of("stone_pickaxe", 1, "raw_iron", 5)), 1e-9);
    }

    @Test
    void pThereRunsOutWithTheClock() {
        assertEquals(1, Kit.pThere(0, 0));
        assertEquals(0.5, Kit.pThere(120_000, 30), 1e-9);
        assertEquals(0, Kit.pThere(300_000, 0));
        assertEquals(-1, Kit.recoverValue(30, 0));
        assertEquals(-1, Kit.recoverValue(1, 1));
        assertEquals(15, Kit.recoverValue(30, 0.5), 1e-9);
        assertEquals(60 + 60 * 2, Kit.recoverCost(60, 1), 1e-9);
    }

    @Test
    void deathRecordedThenDroppedAfterTheClock() {
        bb.in.pos = new int[]{100, 64, 0};
        bb.in.bag = Map.of("stone_pickaxe", 1, "bread", 4);
        Kit.died(bb, clock.now());
        assertNotNull(Kit.of(bb));
        bb.in.bag = Map.of();                       // respawned with nothing
        bb.in.pos = new int[]{0, 64, 0};
        Desire d = AaDesires.recover();
        double v = d.score.applyAsDouble(bb);
        assertTrue(v > 10, "a stone pickaxe (bread not counted), close in time: " + v);
        assertFalse(d.metNow(bb));
        clock.advance(Kit.DESPAWN_MS);
        assertEquals(-1, d.score.applyAsDouble(bb));
        assertNull(Kit.of(bb), "dropped after the clock");
    }

    @Test
    void recoverScoresInTheBrainWithItsCost() {
        bb.in.pos = new int[]{0, 64, 0};
        bb.in.bag = Map.of("iron_pickaxe", 1);
        Kit.died(bb, clock.now());
        bb.in.bag = Map.of();
        Brain br = new Brain("brain", "t", AaDesires.recover());
        var s = br.scoreAll(bb).get(0);
        assertTrue(s.score() > 0 && s.parts().contains("live"), s.parts());
    }

    @Test
    void stashRisesBeforeDuskAndBeforeAFarTrip() {
        assertEquals(0, Kit.soon(1000, false, false));
        assertEquals(0.5, Kit.soon(Night.DUSK - Kit.STASH_LEAD / 2, false, false), 1e-9);
        assertEquals(1, Kit.soon(Night.DUSK + 10, false, false));
        assertEquals(0, Kit.soon(15_000, true, false), "night: the shelter's turn");
        assertEquals(1, Kit.soon(1000, false, true), "a far trip");
        assertEquals(-1, Kit.stashValue(2, 1, 1));
        assertEquals(-1, Kit.stashValue(20, 0, 1), "Peaceful");
        assertEquals(20, Kit.stashValue(20, 1, 1), 1e-9);
        assertEquals(Kit.STASH_MAX, Kit.stashValue(500, 1, 1));
    }

    @Test
    void stashParkedWithoutAChestAndScoredWithOne() {
        bb.in.hungerDrains = true;
        bb.in.bag = Map.of("raw_iron", 20);
        bb.in.dayTime = Night.DUSK - 10;
        bb.in.basePos = new int[]{0, 64, 0};
        bb.in.pos = new int[]{0, 64, 0};
        Brain br = new Brain("brain", "t", AaDesires.stash(null));
        assertTrue(br.scoreAll(bb).get(0).skip().contains("no base chest"));
        bb.in.baseChests = true;
        var s = br.scoreAll(bb).get(0);
        assertNull(s.skip());
        assertTrue(s.score() > 20, s.parts());
    }

    @Test
    void campChestChain() {
        assertEquals("craft chest 1 then go base then place chest 11 64 5 then scan base", AaDesires.chestChain(new int[]{10, 64, 5}, null, false));
        assertEquals("place chest 2 70 3 then place base 1 70 3 then scan base", AaDesires.chestChain(null, new int[]{1, 70, 3}, true));
        assertNull(AaDesires.chestChain(null, null, true));
    }
}
