package io.github.mojolowjo.entropybot.threat;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/** 0.27.0 the owner's footwork table (heavy-log replay of 2026-10-08) and the motor keys. */
class MobTacticsTest {

    @Test
    void ratesFromTheReplay() {
        assertEquals(0.25, MobTactics.rate("minecraft:zombie"));
        assertEquals(0.25, MobTactics.rate("spider"));
        assertEquals(0.25, MobTactics.rate("husk"), "other walkers: the zombie/spider rate");
        assertEquals(0.70, MobTactics.rate("minecraft:slime"));
        assertEquals(0.70, MobTactics.rate("magma_cube"));
        assertEquals(0.70, MobTactics.rate("somemod:green_larva"));
        assertEquals(0.70, MobTactics.rate("silverfish"));
    }

    @Test
    void jumpers() {
        assertTrue(MobTactics.jumper("minecraft:slime"));
        assertTrue(MobTactics.jumper("acid_jumper"));
        assertFalse(MobTactics.jumper("zombie"));
        assertFalse(MobTactics.jumper(null));
    }

    @Test
    void aQuarterIsEveryFourthHit() {
        StringBuilder b = new StringBuilder();
        for (int n = 1; n <= 8; n++) b.append(MobTactics.rateSays(0.25, n) ? 'B' : '.');
        assertEquals("...B...B", b.toString());
    }

    @Test
    void seventyPercentIsSevenOfTen() {
        int n = 0;
        for (int i = 1; i <= 10; i++) if (MobTactics.rateSays(0.70, i)) n++;
        assertEquals(7, n);
        assertFalse(MobTactics.rateSays(0.5, 0));
        assertFalse(MobTactics.rateSays(0, 3));
        assertTrue(MobTactics.rateSays(1, 1));
    }

    @Test
    void lowHealthBacksOffAfterEveryHit() {
        MobTactics.Decision d = MobTactics.afterHit("zombie", ThreatRules.Move.WALK, 1.6, 9, 10, 1);
        assertEquals(MobTactics.Action.BACK_OFF, d.action());
        assertEquals("back off 2 (zombie at 1.6, health 9)", d.line());
    }

    @Test
    void healthyBacksOffAtTheKindsRate() {
        assertEquals(MobTactics.Action.NONE, MobTactics.afterHit("zombie", ThreatRules.Move.WALK, 1.6, 20, 10, 1).action());
        MobTactics.Decision d = MobTactics.afterHit("zombie", ThreatRules.Move.WALK, 1.6, 20, 10, 4);
        assertEquals(MobTactics.Action.BACK_OFF, d.action());
        assertTrue(d.line().contains("hit 4 at 25 %"), d.line());
        assertEquals(MobTactics.Action.BACK_OFF, MobTactics.afterHit("slime", ThreatRules.Move.WALK, 1.2, 20, 10, 2).action());
    }

    @Test
    void onlyWithinTwoBlocks() {
        assertEquals(MobTactics.Action.NONE, MobTactics.afterHit("zombie", ThreatRules.Move.WALK, 2.5, 4, 10, 4).action());
    }

    @Test
    void healthKeyZeroMeansOnlyTheRate() {
        assertEquals(MobTactics.Action.NONE, MobTactics.afterHit("zombie", ThreatRules.Move.WALK, 1.0, 3, 0, 1).action());
    }

    @Test
    void fliersHoldAndRangedNeverBackOff() {
        MobTactics.Decision d = MobTactics.afterHit("phantom", ThreatRules.Move.FLY, 3.0, 20, 10, 1);
        assertEquals(MobTactics.Action.HOLD, d.action());
        assertEquals(MobTactics.Action.NONE, MobTactics.afterHit("skeleton", ThreatRules.Move.RANGED, 1.5, 4, 10, 1).action());
        assertTrue(MobTactics.holdForFlier(ThreatRules.Move.FLY, 5.0, 3.0));
        assertFalse(MobTactics.holdForFlier(ThreatRules.Move.FLY, 5.5, 3.0));
        assertFalse(MobTactics.holdForFlier(ThreatRules.Move.WALK, 2.0, 3.0));
    }

    @Test
    void phantomsAreFliers() {
        assertEquals(ThreatRules.Move.FLY, ThreatRules.moveOf("minecraft:phantom"));
    }

}
