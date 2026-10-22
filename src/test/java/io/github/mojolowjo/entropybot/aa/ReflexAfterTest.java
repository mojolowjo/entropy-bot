package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.brain.Tuning;
import io.github.mojolowjo.entropybot.aa.moves.ReflexLadder;
import io.github.mojolowjo.entropybot.threat.ThreatFacts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Reflex probe 0.39.s5: a spent reflex goes to the danger desire, which re-prices with the reflex's ways held and acts. */
@org.junit.jupiter.api.extension.ExtendWith(io.github.mojolowjo.entropybot.aa.brain.VanillaData.class)
class ReflexAfterTest {
    @AfterEach
    void defaults() {
        Tuning.reset();
        ReflexLadder.watcher(null);
    }

    static ThreatFacts.Me me(double hp) { return new ThreatFacts.Me(hp, 20, 0, 1, 20); }

    static final String CREEPER = "reflex.creeper -40 64 -30: FAILED after 6 tries (run away, burrow): gave up at burrow 2 down here";
    static final String AIR = "reflex.air -61 63 -13: FAILED after 6 tries (surface goal, burrow up): dig refused";

    @Test
    void aSpentReflexAtDayAndFullHealthStillActs() {
        // day, full health, nothing near (Need.NONE): the old desire scored -1 and did nothing; now a way that acts
        ThreatFacts.Ways land = new ThreatFacts.Ways(16, -1, true, 16, 3, true, true, true);
        DangerOptions.Choice c = DangerOptions.afterReflex(new ThreatFacts(me(20), List.of(), land), DangerOptions.Need.NONE, AIR, ReflexLadder.dangerHeld(ReflexLadder.Kind.AIR), k -> null);
        assertNotNull(c.best(), c.terms());
        assertTrue(c.score() > 0, c.terms());
        assertNotEquals(DangerOptions.Way.WAIT, c.best().way(), "the wait is doing nothing: " + c.terms());
        assertNotNull(c.chain(), c.terms());
        assertTrue(c.terms().contains("after the reflex"), c.terms());
    }

    @Test
    void theCreeperReflexsOwnBurrowIsHeld() {
        ThreatFacts.Ways land = new ThreatFacts.Ways(16, -1, true, 16, 3, true, true, true);
        DangerOptions.Choice c = DangerOptions.afterReflex(new ThreatFacts(me(20), List.of(), land), DangerOptions.Need.NONE, CREEPER, ReflexLadder.dangerHeld(ReflexLadder.Kind.CREEPER), k -> null);
        assertNotNull(c.best(), c.terms());
        assertNotEquals(DangerOptions.Way.BURROW, c.best().way(), c.terms());
        assertTrue(c.terms().contains("burrow held: the reflex's own way, spent"), c.terms());
        assertTrue(ReflexLadder.dangerHeld(ReflexLadder.Kind.CREEPER).contains("flee"));
    }

    @Test
    void noWayThatActsSaysWhy() {
        // floating, no blocks, no shore, nothing to dig: only the wait is possible, and it is not an action
        ThreatFacts.Ways none = new ThreatFacts.Ways(0, -1, false, 0, 0, false, false, false);
        DangerOptions.Choice c = DangerOptions.afterReflex(new ThreatFacts(me(20), List.of(), none), DangerOptions.Need.NONE, AIR, ReflexLadder.dangerHeld(ReflexLadder.Kind.AIR), k -> null);
        assertNull(c.best());
        assertEquals(-1, c.score());
        assertTrue(c.terms().contains("no way that acts is open"), c.terms());
    }

    @Test
    void aWayHeldByTheBrainStaysOut() {
        ThreatFacts.Ways land = new ThreatFacts.Ways(16, -1, true, 16, 3, true, true, true);
        DangerOptions.Choice first = DangerOptions.afterReflex(new ThreatFacts(me(20), List.of(), land), DangerOptions.Need.NONE, AIR, ReflexLadder.dangerHeld(ReflexLadder.Kind.AIR), k -> null);
        String k1 = first.best().way().key();
        DangerOptions.Choice next = DangerOptions.afterReflex(new ThreatFacts(me(20), List.of(), land), DangerOptions.Need.NONE, AIR, ReflexLadder.dangerHeld(ReflexLadder.Kind.AIR),
                k -> k.equals(k1) ? "failed once" : null);
        assertNotNull(next.best(), next.terms());
        assertNotEquals(k1, next.best().way().key(), next.terms());
    }

    @Test
    void oneWatchLinePerTryOnlyWhileWatching() {
        List<String> said = new ArrayList<>();
        int[] cell = {1, 2, 3};
        ReflexLadder.Kind k = ReflexLadder.Kind.AIR;
        ReflexLadder.ended(k);
        ReflexLadder.charge(k, cell, "Baritone found no path (swim up to air)", null);
        assertTrue(said.isEmpty());
        ReflexLadder.watcher(said::add);
        ReflexLadder.charge(k, cell, "Baritone found no path (swim up to air)", null);
        assertEquals(1, said.size());
        assertTrue(said.get(0).startsWith("air: surface goal -> no path (Baritone found no path"), said.get(0));
        ReflexLadder.ended(k);
    }

    @Test
    void theTryLineNamesTheRefusalAndTheNextWay() {
        String l = ReflexLadder.tryLine(ReflexLadder.Kind.CREEPER, ReflexLadder.Way.BURROW,
                "dig 1/2 at -40 63 -30 (stone): refused by the break rules (cost hook / Baritone settings)", null);
        assertTrue(l.startsWith("creeper: burrow -> dig refused"), l);
        assertTrue(l.endsWith("next: FAILED to the danger desire"), l);
    }
}
