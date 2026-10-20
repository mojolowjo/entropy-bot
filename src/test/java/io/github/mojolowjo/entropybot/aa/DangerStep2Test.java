package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.brain.Brain;
import io.github.mojolowjo.entropybot.aa.brain.Costs;
import io.github.mojolowjo.entropybot.aa.brain.Desire;
import io.github.mojolowjo.entropybot.aa.brain.Failures;
import io.github.mojolowjo.entropybot.aa.brain.Site;
import io.github.mojolowjo.entropybot.aa.brain.Tuning;
import io.github.mojolowjo.entropybot.aa.core.Blackboard;
import io.github.mojolowjo.entropybot.aa.core.Clock;
import io.github.mojolowjo.entropybot.aa.core.FakePort;
import io.github.mojolowjo.entropybot.aa.core.RunJob;
import io.github.mojolowjo.entropybot.aa.core.TaskKind;
import io.github.mojolowjo.entropybot.aa.core.Tracer;
import io.github.mojolowjo.entropybot.threat.ThreatFacts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/** AA_STEP2: one danger desire with priced ways, the night by place, the cap guard. */
class DangerStep2Test {
    final Clock.Fake clock = new Clock.Fake(10_000_000);
    final FakePort port = new FakePort();
    final Blackboard bb = new Blackboard(clock, port, new Tracer());

    /** No foes; a diggable floor, a wall, 12 blocks for a pillar, head room 3. */
    static final ThreatFacts.Ways HERE = new ThreatFacts.Ways(0, -1, -1, true, 12, 3, true, true, true);

    void normalNight(ThreatFacts.Ways w, ThreatFacts.Foe... foes) {
        bb.in.dayTime = 18000;
        bb.in.hungerDrains = true;
        bb.in.difficulty = 2;
        bb.in.pos = new int[]{0, 64, 0};
        bb.fact(Brain.SURFACE_Y_FACT, (java.util.function.IntBinaryOperator) (x, z) -> 64);
        ThreatFacts f = new ThreatFacts(ThreatFacts.CALM.me(), List.of(foes), w);
        bb.fact(AaDesires.THREAT_FACT, (Supplier<ThreatFacts>) () -> f);
    }

    @AfterEach
    void defaults() { Tuning.reset(); }

    @Test
    void aNormalNightAtTheSurfacePricesTheWaysAndPicksOne() {
        normalNight(HERE);
        bb.in.bag = java.util.Map.of("dirt", 20);
        DangerOptions.Choice c = AaDesires.dangerChoice(bb);
        assertEquals(85, c.score(), 1e-9, "Normal night, danger 1: 85 x 1 " + c.terms());
        assertTrue(c.terms().contains("box") && c.terms().contains("burrow") && c.terms().contains("flee home"), c.terms());
        assertNotNull(c.chain(), c.terms());
        assertNotEquals(DangerOptions.Way.BOX, c.best().way(), "dug in (seconds) is cheaper than a 20 s box: " + c.terms());
        // no blocks for the box: it is not possible, and says why
        bb.in.bag = java.util.Map.of();
        assertTrue(AaDesires.dangerChoice(bb).terms().contains("needs 12 blocks, 0 in the bag"));
        // in my shell: wait it out, the need keeps scoring so surface work does not lure the bot out
        bb.in.sheltered = true;
        DangerOptions.Choice in = AaDesires.dangerChoice(bb);
        assertEquals(DangerOptions.Way.WAIT, in.best().way());
        assertEquals("wait 60", in.chain());
        // Peaceful: nothing
        bb.in.hungerDrains = false;
        assertEquals(-1, AaDesires.dangerScore(bb));
    }

    @Test
    void aFailedWayIsHeldTheOthersStayOpen() {
        normalNight(new ThreatFacts.Ways(0, -1, -1, false, 0, 0, true, true, false));
        bb.in.bag = java.util.Map.of("cobblestone", 30);
        Tuning.set("danger.boxS", 2);            // the box is the cheapest here
        Failures fs = new Failures();
        bb.fact(Brain.FAILURES_FACT, fs);
        assertEquals(DangerOptions.Way.BOX, AaDesires.dangerChoice(bb).best().way());
        fs.failed(AaDesires.DANGER_ID, "box", "I need 12 planks", clock.now(), bb.in);
        DangerOptions.Choice c = AaDesires.dangerChoice(bb);
        assertEquals(DangerOptions.Way.BURROW, c.best().way(), "a failed box: burrow runs " + c.terms());
        assertTrue(c.terms().contains("box held"), c.terms());
        assertEquals(85, c.score(), 1e-9, "the night is not silenced by the failed box");
        fs.failed(AaDesires.DANGER_ID, "burrow", "no ground", clock.now(), bb.in);
        DangerOptions.Choice none = AaDesires.dangerChoice(bb);
        assertEquals(-1, none.score());
        assertTrue(none.terms().endsWith("no way open"), "never silent: " + none.terms());
        clock.advance(Failures.RETRY_MS + 1);
        assertEquals(DangerOptions.Way.BOX, AaDesires.dangerChoice(bb).best().way(), "the hold runs out");
    }

    @Test
    void theFoesComeFirstWhenTheyCostMoreThanTheNight() {
        // a zombie on me at 6 hp with bare hands: its ways (flee, dig in) beat the night's 85 at 99
        bb.in.health = 6;
        normalNight(new ThreatFacts.Ways(16, -1, -1, true, 0, 3, true, true, true));
        ThreatFacts f = new ThreatFacts(new ThreatFacts.Me(6, 20, 0, 1, 20), List.of(ThreatFacts.foe("minecraft:zombie", 2, 0, null)),
                new ThreatFacts.Ways(16, -1, -1, true, 0, 3, true, true, true));
        bb.fact(AaDesires.THREAT_FACT, (Supplier<ThreatFacts>) () -> f);
        DangerOptions.Choice c = AaDesires.dangerChoice(bb);
        assertTrue(c.score() >= Brain.URGENT, c.terms());
        assertNotNull(c.chain());
    }

    @Test
    void fightOnlyArmedWithTheExchangeInMyFavourAndNeverAtAShooterOutOfReach() {
        ThreatFacts.Ways open = new ThreatFacts.Ways(16, -1, -1, false, 0, 3, true);
        DangerOptions.Choice fist = DangerOptions.choose(new ThreatFacts(new ThreatFacts.Me(20, 20, 0, 1, 20), List.of(ThreatFacts.foe("minecraft:zombie", 3, 0, null)), open));
        assertFalse(fist.all().stream().filter(p -> p.way() == DangerOptions.Way.FIGHT).findFirst().orElseThrow().possible(), fist.terms());
        DangerOptions.Choice sk = DangerOptions.choose(new ThreatFacts(new ThreatFacts.Me(20, 20, 0, 7, 20), List.of(ThreatFacts.foe("minecraft:skeleton", 12, 0, null)), open));
        assertFalse(sk.all().stream().filter(p -> p.way() == DangerOptions.Way.FIGHT).findFirst().orElseThrow().possible(), sk.terms());
        DangerOptions.Choice sword = DangerOptions.choose(new ThreatFacts(new ThreatFacts.Me(20, 20, 0, 7, 20), List.of(ThreatFacts.foe("minecraft:zombie", 3, 0, null)), open));
        assertTrue(sword.all().stream().filter(p -> p.way() == DangerOptions.Way.FIGHT).findFirst().orElseThrow().possible(), sword.terms());
        Tuning.set("danger.fightHit", 8);
        DangerOptions.Choice tuned = DangerOptions.choose(new ThreatFacts(new ThreatFacts.Me(20, 20, 0, 7, 20), List.of(ThreatFacts.foe("minecraft:zombie", 3, 0, null)), open));
        assertFalse(tuned.all().stream().filter(p -> p.way() == DangerOptions.Way.FIGHT).findFirst().orElseThrow().possible(), "Tuning danger.fightHit");
    }

    @Test
    void twoDesiresAtTheCapCannotAlternate() {
        double[] v = {99, -1};
        Brain br = new Brain("brain", "t",
                new Desire("a", Desire.Kind.UPKEEP, RunJob.of("a.job", TaskKind.WAIT, "do a", "brain"), b -> v[0]).trumps(),
                new Desire("b", Desire.Kind.UPKEEP, RunJob.of("b.job", TaskKind.WAIT, "do b", "brain"), b -> v[1]).overTrumps());
        bb.in.food = 20;
        br.tick(bb);
        assertEquals("do a", port.started.get(port.started.size() - 1).chain());
        v[1] = 99;
        for (int i = 0; i < 5; i++) {
            clock.advance(3_000);
            br.tick(bb);
        }
        assertEquals(1, port.started.size(), "99 vs 99: the margin holds at the cap, no switch");
    }

    Desire siteDesire(String id, double v, Site s) {
        return new Desire(id, Desire.Kind.UPKEEP, RunJob.of(id + ".job", TaskKind.WAIT, "do " + id, "brain"), b -> v).cost(0).site(b -> s);
    }

    @Test
    void theWoodGameSheepHuntAtNightLosesToDangerTheStoneGameMiningKeepsItsBonus() {
        normalNight(HERE);
        Desire bed = siteDesire("needs.bed", 70, Site.at(40, 64, 40));          // a sheep in the open
        Desire mine = siteDesire("needs.iron", 40, Site.at(5, 30, 5));          // an UNDER option
        Desire tools = siteDesire("needs.tools", Costs.tools(0, 0, 1), Site.at(3, 64, 3));
        Brain br = new Brain("brain", "t", bed, mine, tools, AaDesires.danger());
        var s = br.scoreAll(bb);
        assertEquals(35, s.get(0).score(), 1e-9, "the surface sheep hunt is halved at danger 1: " + s.get(0).parts());
        assertEquals(60, s.get(1).score(), 1e-9, "under ground: x 1.5 " + s.get(1).parts());
        assertTrue(s.get(2).score() < s.get(3).score(), "tools at tier 0 at the surface score under danger: " + s.get(2).parts());
        assertTrue(s.get(3).rank() > s.get(0).rank(), "danger beats the sheep hunt");
        // the stone game: deep under ground the night need is off; the mining goes on
        bb.in.underDepth = 20;
        bb.in.pos = new int[]{5, 30, 5};
        assertEquals(-1, AaDesires.dangerScore(bb), "under ground at night: no danger need");
    }
}
