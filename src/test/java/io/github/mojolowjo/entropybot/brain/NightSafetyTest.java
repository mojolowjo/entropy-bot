package io.github.mojolowjo.entropybot.brain;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

/** 0.24.3: the night safety score, its bands and the brain's night choices. */
class NightSafetyTest {
    static BrainState state(int armour, int weapon, int food, long dayTime, boolean night) {
        BrainState s = new BrainState();
        s.armourTier = armour;
        s.weaponTier = weapon;
        s.foodItems = food;
        s.torches = 0;
        s.health = 20;
        s.dayTime = dayTime;
        s.night = night;
        s.litHere = true;
        s.othersSleeping = true;
        return s;
    }

    @Test void scoreAndBands() {
        BrainConfig c = BrainConfig.defaults();
        assertEquals(NightSafety.FULL_IRON + 1, NightSafety.score(2, 2, 8, 0, 20, 0));    // full iron + sword + 8 food + health
        assertEquals(NightSafety.Band.SHELTER, NightSafety.band(NightSafety.score(0, 0, 0, 0, 20, 0), c));
        assertEquals(NightSafety.Band.SHELTER, NightSafety.band(NightSafety.score(2, 2, 3, 0, 10, 0), c));     // iron but no food
        assertEquals(NightSafety.Band.UNDERGROUND, NightSafety.band(NightSafety.score(2, 2, 8, 1, 20, 0), c));
        assertEquals(NightSafety.Band.NORMAL, NightSafety.band(NightSafety.score(3, 3, 8, 1, 20, 0), c));
        assertEquals(NightSafety.Band.SHELTER, NightSafety.band(NightSafety.score(2, 2, 8, 1, 20, 3), c));     // three deaths today
        c.set("night.shelterBelow", 0);
        assertEquals(NightSafety.Band.UNDERGROUND, NightSafety.band(0, c));                                      // editable
    }

    @Test void tiers() {
        assertEquals(0, NightSafety.armourTier(List.of("minecraft:iron_helmet")));          // pieces missing
        assertEquals(2, NightSafety.armourTier(List.of("minecraft:iron_helmet", "minecraft:iron_chestplate", "minecraft:diamond_leggings", "minecraft:iron_boots")));
        assertEquals(1, NightSafety.tierOf("minecraft:stone_sword"));
        assertEquals(3, NightSafety.tierOf("minecraft:netherite_sword"));
    }

    @Test void dusk() {
        assertEquals(542, NightSafety.toDusk(12000));
        assertTrue(NightSafety.nearDusk(11000, 2));
        assertFalse(NightSafety.nearDusk(9000, 2));
        assertFalse(NightSafety.nearDusk(13000, 2));        // already night
    }

    @Test void brainSheltersInTheLowBand() {
        BrainTree t = new BrainTree();
        BrainConfig c = BrainConfig.defaults();
        BrainState s = state(0, 0, 2, 14000, true);
        BrainTree.Decision d = t.run(new BrainTree.Ctx(s, c, Needs.score(s, c, List.of(), java.util.Map.of()), null, false));
        assertEquals("night.shelter", d.branch());
        assertTrue(d.chain().endsWith("shelter"), d.chain());
        // near dusk, still day: shelter early, no surface job
        s = state(0, 0, 2, 11500, false);
        d = t.run(new BrainTree.Ctx(s, c, Needs.score(s, c, List.of(), java.util.Map.of()), null, false));
        assertEquals("night.shelter", d.branch());
        // short of blocks: logs first
        s.shelterBlocks = 0;
        d = t.run(new BrainTree.Ctx(s, c, Needs.score(s, c, List.of(), java.util.Map.of()), null, false));
        assertTrue(d.chain().startsWith("cut 8 logs then craft planks 32 then shelter"), d.chain());
    }

    @Test void wellArmedSleepsAsBefore() {
        BrainTree t = new BrainTree();
        BrainConfig c = BrainConfig.defaults();
        BrainState s = state(3, 3, 16, 14000, true);
        s.torches = 32;
        BrainTree.Decision d = t.run(new BrainTree.Ctx(s, c, Needs.score(s, c, List.of(), java.util.Map.of()), null, false));
        assertEquals("night.sleep", d.branch());
    }

    @Test void undergroundOnly() {
        assertTrue(NightSafety.underground("mine strip any 32"));
        assertTrue(NightSafety.underground("go camp then shelter"));
        assertFalse(NightSafety.underground("cut 16 logs"));
        assertFalse(NightSafety.underground("farm"));
    }
}
