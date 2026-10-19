package io.github.mojolowjo.entropybot.threat;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CornerEscapeTest {
    @Test
    void pillarsWhenCorneredAndFightingIsBad() {
        var p = CornerEscape.plan(true, true, false, 3, 20);
        assertTrue(p.pillar());
        assertEquals(3, p.height());
        assertEquals(2, CornerEscape.plan(true, true, false, 2, 20).height());
        assertEquals(2, CornerEscape.plan(true, true, false, 3, 2).height());
    }

    @Test
    void fightsWhenNoPillarFits() {
        assertFalse(CornerEscape.plan(false, true, false, 3, 20).pillar());       // fighting is fine
        assertFalse(CornerEscape.plan(true, false, false, 3, 20).pillar());       // in the air
        assertFalse(CornerEscape.plan(true, true, true, 3, 20).pillar());         // in water
        assertFalse(CornerEscape.plan(true, true, false, 1, 20).pillar());        // low ceiling
        assertFalse(CornerEscape.plan(true, true, false, 3, 1).pillar());         // no blocks
    }

    @Test
    void fightBad() {
        assertTrue(CornerEscape.fightBad(5, 6, false, false, 1));
        assertTrue(CornerEscape.fightBad(20, 6, false, false, 3));
        assertTrue(CornerEscape.fightBad(20, 6, true, false, 1));
        assertFalse(CornerEscape.fightBad(20, 6, false, false, 1));
    }

    @Test
    void placesOnlyOnceTheFeetAreClear() {
        assertFalse(CornerEscape.placeNow(64.6, 64));
        assertTrue(CornerEscape.placeNow(65.1, 64));
    }

    @Test
    void creeperHeadingAvoidsTheOtherMobs() {
        // creeper at +x 3, a zombie at -x 5: straight away from the creeper runs into the zombie
        List<FleeHeading.Mob> mobs = List.of(new FleeHeading.Mob(3, 0, 2), new FleeHeading.Mob(-5, 0, 1));
        double[] d = FleeHeading.direction(0, 0, mobs, 12);
        assertNotNull(d);
        assertFalse(FleeHeading.blocked(0, 0, mobs, d[0], d[1], 12));
        assertTrue(FleeHeading.blocked(0, 0, mobs, -1, 0, 12));
        assertTrue(Math.abs(d[1]) > 0.5, d[0] + " " + d[1]);
    }

    @Test
    void loneWeakMobIsFoughtWhenArmed() {
        // full health, wooden sword (4), no armour, dark, no lit spot: the margin is tight, but a lone zombie is fought
        var me = new FightOrFlee.Me(20, 20, 0, 4, 0, -1, 6);
        var r = FightOrFlee.assess(me, List.of(new FightOrFlee.Foe("zombie", 4, false)), null);
        assertEquals(FightOrFlee.Verdict.FIGHT, r.verdict(), r.why());
        // bare hands: still a retreat
        var bare = FightOrFlee.assess(new FightOrFlee.Me(20, 20, 0, 1, 0, -1, 6), List.of(new FightOrFlee.Foe("zombie", 4, false)), null);
        assertNotEquals(FightOrFlee.Verdict.FIGHT, bare.verdict(), bare.why());
        // two zombies at 12 health: no lone-mob rule
        var two = FightOrFlee.assess(new FightOrFlee.Me(12, 20, 0, 4, 0, -1, 6),
                List.of(new FightOrFlee.Foe("zombie", 4, false), new FightOrFlee.Foe("zombie", 5, false)), null);
        assertNotEquals(FightOrFlee.Verdict.FIGHT, two.verdict(), two.why());
    }
}
