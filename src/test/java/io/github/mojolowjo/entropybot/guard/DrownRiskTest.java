package io.github.mojolowjo.entropybot.guard;

import io.github.mojolowjo.entropybot.aa.IronOptions;
import io.github.mojolowjo.entropybot.cave.MineRules;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** aa-costs item 1: drowning risk in place of "never dig down from in the water" / "skip an ore 2+ below". */
class DrownRiskTest {
    @Test
    void riskFromAirDepthAndWater() {
        assertEquals((2 * 3.4 + 1.5) * 1.5 / 15, DrownRisk.risk(2, 1, 300), 1e-9, "2 down under 1 water, full air, stone pickaxe (5x slower submerged)");
        assertTrue(DrownRisk.fatal(DrownRisk.risk(2, 1, 300, 1)), "director: a wooden pickaxe cannot dig 2 down submerged and come back");
        assertTrue(DrownRisk.fatal(DrownRisk.risk(1, 0, 300, 0)), "by hand: not even one");
        assertFalse(DrownRisk.fatal(DrownRisk.risk(2, 1, 300, 3)), "iron is quicker");
        assertEquals(5 * 0.6 + 0.4, DrownRisk.digS(2), 1e-9);
        assertEquals(3, DrownRisk.pickTier(java.util.List.of("minecraft:stone_pickaxe", "minecraft:iron_pickaxe", "minecraft:dirt")));
        assertEquals(0, DrownRisk.pickTier(java.util.List.of("minecraft:dirt")));
        assertFalse(DrownRisk.fatal(DrownRisk.risk(2, 1, 300)));
        assertTrue(DrownRisk.fatal(DrownRisk.risk(15, 0, 300)), "aa370: 15 down from the pool cannot come back");
        assertTrue(DrownRisk.fatal(DrownRisk.risk(2, 1, 60)), "little air left: even 2 is too far");
        assertTrue(DrownRisk.fatal(DrownRisk.risk(1, 0, 0)), "no air");
        assertTrue(DrownRisk.risk(4, 3, 300) > DrownRisk.risk(4, 0, 300), "more water over me, more risk");
        assertEquals(DrownRisk.K * 0.25, DrownRisk.cost(0.5), 1e-9);
        assertTrue(Double.isInfinite(DrownRisk.cost(1)));
        assertTrue(DrownRisk.terms(2, 1, 300).contains("drown risk 0.83"), DrownRisk.terms(2, 1, 300));
    }

    @Test
    void mineSkipAndWalkStopOnlyWhenFatal() {
        assertFalse(MineRules.waterDownSkip(true, 60, 58, false, 300, 1, 2), "2 below, full air: a cost, not a skip");
        assertTrue(MineRules.waterDownSkip(true, 60, 45, false, 300, 1, 2), "15 below: fatal, skipped");
        assertFalse(MineRules.waterDownSkip(true, 60, 45, true, 300, 1, 2), "the water touches it: sink to it");
        assertFalse(MineRules.waterDigDown(true, true, 59, 60, 58, 300, 1, 2), "2 left to dig with full air: goes on");
        assertTrue(MineRules.waterDigDown(true, true, 59, 60, 58, 40, 6, 2), "2 s of air: stopped");
    }

    @Test
    void breakPolicyDiveIsACostUntilFatal() {
        BreakPolicy.Where full = new BreakPolicy.Where(true, 0, 300, 1, Integer.MIN_VALUE);
        assertFalse(DrownRisk.fatal(full.diveRisk()));
        assertTrue(DrownRisk.fatal(BreakPolicy.Where.WET.diveRisk()), "air unknown: the old rule (never)");
        assertTrue(DrownRisk.fatal(new BreakPolicy.Where(true, 0, 30, 4, Integer.MIN_VALUE).diveRisk()), "1.5 s of air under 4 water");
        BreakPolicy.Where at60 = new BreakPolicy.Where(true, 0, 300, 1, 60);
        assertFalse(DrownRisk.fatal(at60.diveRisk(59)), "one under the feet");
        assertTrue(DrownRisk.fatal(at60.diveRisk(48)), "review: the A* cell 12 down counts the whole dig");
    }

    @Test
    void ironScorerAddsTheDrownCost() {
        IronOptions.Ore dry = new IronOptions.Ore(0, 58, 0, 5, true, false, false, 4, false, null, false);
        IronOptions.Ore wet = new IronOptions.Ore(1, 58, 1, 5, true, false, false, 4, false, null, false, 30, "dig 2 down under 1 water");
        var c = IronOptions.choose(new IronOptions.Facts(List.of(wet, dry), 60, true, 0, false, -1, false, null, -1, false, false, 0),
                new IronOptions.Memory(), 0);
        assertEquals(dry.key(), c.target().key(), c.why());
        assertTrue(c.why().contains("dig 2 down under 1 water +30"), c.why());
    }
}
