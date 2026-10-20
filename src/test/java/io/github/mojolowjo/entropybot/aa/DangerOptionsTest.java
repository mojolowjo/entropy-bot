package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.brain.Brain;
import io.github.mojolowjo.entropybot.aa.brain.Tuning;
import io.github.mojolowjo.entropybot.threat.ThreatFacts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** AA_CLEANUP 4: the danger desire's ways priced on the threat facts (by number, no rule). */
class DangerOptionsTest {
    static final ThreatFacts.Ways OPEN = new ThreatFacts.Ways(16, -1, -1, true, 0, 3, true);

    static ThreatFacts.Me me(double hp, double weapon) { return new ThreatFacts.Me(hp, 20, 0, weapon, 20); }

    static ThreatFacts facts(ThreatFacts.Me me, ThreatFacts.Ways w, ThreatFacts.Foe... foes) { return new ThreatFacts(me, List.of(foes), w); }

    static ThreatFacts.Foe zombie(double dist) { return ThreatFacts.foe("minecraft:zombie", dist, 0, null); }

    @AfterEach
    void defaults() { Tuning.reset(); }

    @Test
    void aZombieAndAPickaxeAtTwelveHealthFleesOrDigsInByNumber() {
        DangerOptions.Choice c = DangerOptions.choose(facts(me(12, 1), OPEN, zombie(3)));
        assertTrue(c.best().way() == DangerOptions.Way.FLEE || c.best().way() == DangerOptions.Way.DIG_IN, c.terms());
        assertTrue(c.score() >= Brain.URGENT, "a likely death if it stays: urgent " + c.terms());
        assertTrue(c.chain().startsWith("flee then wait") || c.chain().startsWith("burrow then wait"), c.chain());
        // the same zombie, cornered with nowhere to dig: no way out; fighting saves too little over the hit response
        DangerOptions.Choice cornered = DangerOptions.choose(facts(me(12, 1), ThreatFacts.Ways.NONE, zombie(3)));
        assertEquals(-1, cornered.score(), cornered.terms());
        assertNull(cornered.chain());
    }

    @Test
    void aSwordAtFullHealthStaysAndTheHitResponseFights() {
        DangerOptions.Choice c = DangerOptions.choose(facts(me(20, 7), OPEN, zombie(3)));
        assertEquals(-1, c.score(), "no way saves enough over the hit response: " + c.terms());
        assertNull(c.chain());
    }

    @Test
    void lowHealthGoesHomeWhenTheBaseIsInReachElsePillars() {
        ThreatFacts.Ways home = new ThreatFacts.Ways(16, 40, -1, false, 0, 3, true);
        DangerOptions.Choice c = DangerOptions.choose(facts(me(6, 7), home, zombie(5)));
        assertEquals(DangerOptions.Way.HOME, c.best().way(), c.terms());
        assertEquals("go base", c.chain());
        assertTrue(c.score() >= Brain.URGENT, "a likely death avoided: urgent " + c.terms());
        ThreatFacts.Ways pillar = new ThreatFacts.Ways(16, -1, -1, false, 12, 3, true);
        DangerOptions.Choice p = DangerOptions.choose(facts(me(6, 7), pillar, zombie(5)));
        assertEquals(DangerOptions.Way.PILLAR, p.best().way(), p.terms());
    }

    @Test
    void aSkeletonAtRangeIsCoveredFromNotFought() {
        ThreatFacts.Foe sk = ThreatFacts.foe("minecraft:skeleton", 12, 0, null);
        assertTrue(sk.ranged());
        ThreatFacts.Ways cover = new ThreatFacts.Ways(16, -1, 2, false, 0, 3, true);
        DangerOptions.Choice c = DangerOptions.choose(facts(me(20, 7), cover, sk));
        assertEquals(DangerOptions.Way.COVER, c.best().way(), c.terms());
        assertTrue(c.chain().startsWith("cover then wait"), c.chain());
        // no cover cell: a pillar is no cover from arrows, so it never wins against a skeleton
        ThreatFacts.Ways noCover = new ThreatFacts.Ways(16, -1, -1, false, 12, 3, true);
        DangerOptions.Choice n = DangerOptions.choose(facts(me(20, 7), noCover, sk));
        assertNotEquals(DangerOptions.Way.PILLAR, n.best().way(), n.terms());
        assertNotEquals(DangerOptions.Way.COVER, n.best().way(), "no cell: cover is not possible");
    }

    @Test
    void aCreeperStaysAReflex() {
        DangerOptions.Choice c = DangerOptions.choose(facts(me(20, 1), OPEN, ThreatFacts.foe("minecraft:creeper", 3, 0, null)));
        assertEquals(-1, c.score());
        assertNull(c.chain());
        assertTrue(c.terms().contains("reflex"), c.terms());
        assertEquals(-1, DangerOptions.choose(ThreatFacts.CALM).score(), "calm: nothing");
    }

    @Test
    void theMeasuredDamageReplacesTheTable() {
        io.github.mojolowjo.entropybot.threat.MobDamage seen = new io.github.mojolowjo.entropybot.threat.MobDamage();
        for (int i = 0; i < 3; i++) seen.add("minecraft:zombie", 6);
        ThreatFacts.Foe f = ThreatFacts.foe("minecraft:zombie", 3, 0, seen);
        assertEquals(6, f.dps(), 1e-9);
        assertTrue(f.basis().startsWith("observed"), f.basis());
        assertEquals(3, zombie(3).dps(), 1e-9, "vanilla table: 3 a hit, a hit a second");
    }

    @Test
    void tuningMovesTheThreshold() {
        DangerOptions.Choice at20 = DangerOptions.choose(facts(me(20, 1), OPEN, zombie(3)));
        assertTrue(at20.score() > 0 && at20.score() < Brain.URGENT, "full health, a pickaxe: worth a flee, not urgent " + at20.terms());
        assertTrue(Tuning.set("danger.minS", 600).startsWith("ok"));
        assertEquals(-1, DangerOptions.choose(facts(me(20, 1), OPEN, zombie(3))).score(), "tuned: not worth it");
    }

    @Test
    void aRunningMoveHoldsTheDesireUntilItEnds() {
        var bb = new io.github.mojolowjo.entropybot.aa.core.Blackboard(new io.github.mojolowjo.entropybot.aa.core.Clock.Fake(1_000_000),
                new io.github.mojolowjo.entropybot.aa.core.FakePort(), new io.github.mojolowjo.entropybot.aa.core.Tracer());
        bb.fact(AaDesires.THREAT_FACT, (java.util.function.Supplier<ThreatFacts>) () -> ThreatFacts.CALM);
        bb.fact(AaDesires.MOVE_FACT, true);
        assertFalse(AaDesires.dangerMet(bb), "a flee still running: not done though staying looks cheap now");
        bb.fact(AaDesires.MOVE_FACT, false);
        assertTrue(AaDesires.dangerMet(bb), "the move ended and nothing is priced: done");
    }
}