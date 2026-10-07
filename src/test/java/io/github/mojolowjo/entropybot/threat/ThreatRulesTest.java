package io.github.mojolowjo.entropybot.threat;

import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ThreatRulesTest {
    static ThreatRules.MobSample s(String kind, double straight, boolean aggressive, double yawOff, boolean seen, boolean hit, int path, double prev) {
        return new ThreatRules.MobSample(1, kind, straight, aggressive, yawOff, seen, hit, path, prev);
    }

    @Test
    void allThreeCount() {
        assertTrue(ThreatRules.decide(s("minecraft:zombie", 6, true, 10, true, false, 7, 8.5)).counts());
    }

    @Test
    void eachCheckAloneFails() {
        assertEquals("not aggro", ThreatRules.decide(s("minecraft:zombie", 6, false, 0, true, false, 7, 9)).rule());
        assertEquals("not aggro", ThreatRules.decide(s("minecraft:zombie", 6, true, 80, true, false, 7, 9)).rule(), "looking away");
        assertEquals("can't reach me", ThreatRules.decide(s("minecraft:zombie", 4, true, 0, true, false, -1, Double.NaN)).rule());
        assertEquals("not closing in", ThreatRules.decide(s("minecraft:zombie", 6, true, 0, true, false, 7, 7)).rule());
    }

    @Test
    void theRoofZombieIsNoted() {
        ThreatRules.Decision d = ThreatRules.decide(s("minecraft:zombie", 4, true, 0, false, false, -1, Double.NaN));
        assertFalse(d.counts());
        assertTrue(d.line().contains("no path"), d.line());
    }

    @Test
    void closingRule() {
        assertTrue(ThreatRules.decide(s("minecraft:zombie", 8, true, 0, true, false, 9, 10)).isClosing(), "shrank by 1");
        assertFalse(ThreatRules.decide(s("minecraft:zombie", 8, true, 0, true, false, 9, 9.5)).isClosing(), "only 0.5");
        assertFalse(ThreatRules.decide(s("minecraft:zombie", 8, true, 0, true, false, 9, Double.NaN)).isClosing(), "first sample");
        assertTrue(ThreatRules.decide(s("minecraft:zombie", 2.5, true, 0, true, false, 3, 3)).isClosing(), "within 3");
    }

    @Test
    void aPathLongerThanTheBudgetIsNoReach() {
        assertEquals(24, ThreatRules.pathBudget(5));
        assertEquals(32, ThreatRules.pathBudget(20));
        assertFalse(ThreatRules.decide(s("minecraft:zombie", 5, true, 0, true, false, 30, 31)).counts());
    }

    @Test
    void creeperBar() {
        assertTrue(ThreatRules.decide(s("minecraft:creeper", 12, false, 180, false, false, 14, Double.NaN)).counts(), "within 16 with a path");
        assertFalse(ThreatRules.decide(s("minecraft:creeper", 12, false, 180, false, false, -1, Double.NaN)).counts(), "no path");
        assertFalse(ThreatRules.decide(s("minecraft:creeper", 18, true, 0, true, false, 20, 22)).counts(), "beyond 16");
    }

    @Test
    void safetyInvariant_hitsAndCloseCreepersAlwaysCount() {
        assertTrue(ThreatRules.decide(s("minecraft:zombie", 4, false, 180, false, true, -1, Double.NaN)).counts(), "it hit me");
        assertTrue(ThreatRules.decide(s("minecraft:creeper", 5, false, 180, false, false, -1, Double.NaN)).counts(), "creeper within 6");
        assertTrue(ThreatRules.filter(null, true, 10, true, false));
        assertTrue(ThreatRules.filter(null, true, 5, false, true));
        ThreatRules.Decision noted = ThreatRules.decide(s("minecraft:zombie", 4, true, 0, false, false, -1, Double.NaN));
        assertTrue(ThreatRules.filter(noted, true, 4, true, false), "a noted mob that hits counts");
        assertTrue(ThreatRules.filter(noted, false, 4, false, false), "no fresh grid: the old test stands");
        assertFalse(ThreatRules.filter(noted, true, 4, false, false));
        assertFalse(ThreatRules.filter(null, true, 6, false, false), "not sampled yet and not right here");
        assertTrue(ThreatRules.filter(null, true, 2, false, false), "not sampled yet but right here");
    }

    @Test
    void kinds() {
        assertEquals(ThreatRules.Move.SPIDER, ThreatRules.moveOf("nyfsspiders:big_spider"));
        assertEquals(ThreatRules.Move.FLY, ThreatRules.moveOf("minecraft:phantom"));
        assertEquals(ThreatRules.Move.RANGED, ThreatRules.moveOf("minecraft:skeleton"));
        assertEquals(ThreatRules.Move.TELEPORT, ThreatRules.moveOf("minecraft:enderman"));
        assertEquals(ThreatRules.Move.WALK, ThreatRules.moveOf("minecraft:zombie"));
    }

    @Test
    void fliersIgnoreWalls() {
        ThreatRules.Decision d = ThreatRules.decide(s("minecraft:phantom", 10, false, 0, true, false, -1, 12));
        assertEquals("flies", d.how());
        assertTrue(d.counts(), d.line());
    }

    @Test
    void rangedMobsCountByLineOfSightInRange() {
        ThreatRules.Decision d = ThreatRules.decide(s("minecraft:skeleton", 12, true, 5, true, false, -1, Double.NaN));
        assertEquals("sight", d.how());
        assertTrue(d.counts(), d.line());
        assertFalse(ThreatRules.decide(s("minecraft:skeleton", 12, true, 5, false, false, -1, Double.NaN)).counts(), "behind a wall, no path");
        assertFalse(ThreatRules.decide(s("minecraft:skeleton", 20, true, 5, true, false, -1, Double.NaN)).counts(), "out of range");
    }

    @Test
    void endermenTeleportWithin16() {
        assertTrue(ThreatRules.decide(s("minecraft:enderman", 10, true, 0, true, false, -1, 11.5)).counts());
        assertFalse(ThreatRules.decide(s("minecraft:enderman", 20, true, 0, true, false, -1, 21)).counts());
    }

    @Test
    void outsideTheGridFallsBackToStraight() {
        ThreatRules.Decision d = ThreatRules.decide(s("minecraft:zombie", 20, true, 0, true, false, -2, 21.5));
        assertEquals("beyond grid", d.how());
        assertTrue(d.counts());
    }

    // ---- fight or flee ----

    static FightOrFlee.Me me(double hp, int armor, double weapon, int light, int lit) {
        return new FightOrFlee.Me(hp, 20, armor, weapon, light, lit, 6);
    }

    static List<FightOrFlee.Foe> zombies(int n) {
        return java.util.stream.IntStream.range(0, n).mapToObj(i -> new FightOrFlee.Foe("zombie", 4, false)).toList();
    }

    @Test
    void fightOrFleeTable() {
        // full iron (15) and an iron sword (hit 6), full health: one zombie is a fight, six are a flight
        assertEquals(FightOrFlee.Verdict.FIGHT, FightOrFlee.assess(me(20, 15, 6, 0, -1), zombies(1)).verdict());
        assertEquals(FightOrFlee.Verdict.HOME, FightOrFlee.assess(me(20, 15, 6, 0, -1), zombies(6)).verdict());
        // no armour, a wooden sword (hit 4): tight against one zombie
        FightOrFlee.Result tight = FightOrFlee.assess(me(20, 0, 4, 0, 10), zombies(1));
        assertEquals(FightOrFlee.Verdict.LIT, tight.verdict(), tight.why());
        assertEquals(FightOrFlee.Verdict.FIGHT, FightOrFlee.assess(me(20, 0, 4, 12, 10), zombies(1)).verdict(), "tight but lit here");
        assertEquals(FightOrFlee.Verdict.HOME, FightOrFlee.assess(me(20, 0, 4, 0, -1), zombies(1)).verdict(), "tight, dark, no lit spot");
        // a creeper in a group: home
        assertEquals(FightOrFlee.Verdict.HOME, FightOrFlee.assess(me(20, 15, 6, 15, -1),
                List.of(new FightOrFlee.Foe("zombie", 4, false), new FightOrFlee.Foe("creeper", 5, true))).verdict());
        // low health: losing
        assertEquals(FightOrFlee.Verdict.HOME, FightOrFlee.assess(me(9, 0, 6, 15, -1), zombies(2)).verdict());
    }
}
