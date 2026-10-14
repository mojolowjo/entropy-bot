package io.github.mojolowjo.entropybot.craft;

import io.github.mojolowjo.entropybot.craft.FurnaceJobs.Put;
import io.github.mojolowjo.entropybot.craft.FurnaceJobs.Slots;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Package 4: a fuel slot holding another fuel is not a conflict (trial 8: 1 charcoal refused three charcoal crafts). */
class FurnaceForeignFuelTest {
    private static final String PLANKS = "minecraft:spruce_planks";

    private static Slots fuel(String id, int n) { return new Slots(null, 0, id, n, null, 0); }

    @Test void oneCharcoalSmeltsASmallJobAsIs() {
        var c = FurnaceJobs.check(fuel("minecraft:charcoal", 1), PLANKS, null, 4);
        assertEquals(Put.FUEL_AS_IS, c.put());
    }

    @Test void oneCharcoalForABigJobBurnsFirstThenTopsUp() {
        var c = FurnaceJobs.check(fuel("minecraft:charcoal", 1), PLANKS, null, 20);
        assertEquals(Put.FUEL_BURN_FIRST, c.put());
        assertEquals(80, FurnaceJobs.burnSeconds("minecraft:charcoal", 1));
    }

    @Test void aLotOfOtherFuelIsWaitedOut() {
        var c = FurnaceJobs.check(fuel("minecraft:coal", 10), PLANKS, null, 200);
        assertEquals(Put.BUSY, c.put());
        assertTrue(c.why().contains("10 coal"));
    }

    @Test void ourOwnFuelIsFreeAsBefore() {
        assertEquals(Put.FREE, FurnaceJobs.check(fuel(PLANKS, 3), PLANKS, null, 8).put());
    }

    @Test void anotherInputIsStillBusy() {
        var s = new Slots("minecraft:raw_iron", 5, "minecraft:charcoal", 1, null, 0);
        var c = FurnaceJobs.check(s, PLANKS, null, 4);
        assertEquals(Put.BUSY, c.put());
        assertTrue(c.why().startsWith("it is smelting"));
    }

    @Test void foreignOutputIsStillBusy() {
        var s = new Slots(null, 0, "minecraft:charcoal", 1, "minecraft:iron_ingot", 3);
        assertEquals(Put.BUSY, FurnaceJobs.check(s, PLANKS, null, 4).put());
    }

    @Test void planksSmeltOneAndAHalf() {
        assertEquals(1.5, FurnaceJobs.smeltsPer("minecraft:oak_planks"));
        assertEquals(1, FurnaceJobs.smeltsPer("modded:weird_fuel"));
    }
}
