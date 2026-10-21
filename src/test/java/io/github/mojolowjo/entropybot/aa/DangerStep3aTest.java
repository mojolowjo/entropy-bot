package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.brain.Tuning;
import io.github.mojolowjo.entropybot.aa.core.Blackboard;
import io.github.mojolowjo.entropybot.aa.core.Clock;
import io.github.mojolowjo.entropybot.aa.core.FakePort;
import io.github.mojolowjo.entropybot.aa.core.Tracer;
import io.github.mojolowjo.entropybot.threat.ThreatFacts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** AA_STEP3 A: the loss terms by situation (hp curve, air), the wait where it stands, pillar from the bag and out of water, the shore. */
class DangerStep3aTest {
    @AfterEach
    void defaults() { Tuning.reset(); }

    static DangerOptions.Need night(int blocks, boolean water, int air, double death) {
        return new DangerOptions.Need(85, "night 85", 1.0, false, blocks, false, false, blocks, water, air, death, "test");
    }

    static ThreatFacts.Me me(double hp) { return new ThreatFacts.Me(hp, 20, 0, 1, 20); }

    static DangerOptions.Priced way(DangerOptions.Choice c, DangerOptions.Way w) {
        return c.all().stream().filter(p -> p.way() == w).findFirst().orElseThrow(() -> new AssertionError(w + " not priced: " + c.terms()));
    }

    @Test
    void theHpCurveRunsFromHpSAtFullToTheDeathCostAt1() {
        assertEquals(Tuning.get("danger.hpS"), DangerOptions.hpS(me(20), 200), 1e-9);
        assertEquals(200, DangerOptions.hpS(me(1), 200), 1e-9);
        assertTrue(DangerOptions.hpS(me(4), 200) > 100, "4 hp: a point near the death cost " + DangerOptions.hpS(me(4), 200));
    }

    @Test
    void theWatchCaseDeepWaterPricesTheShoreOrPillarOutUnderTheWait() {
        // step2-watch: deep water (12 cells to the floor, 2 over the head), 16 blocks, night, danger 1.0, 4 hp, a shore 20 away
        ThreatFacts.Ways w = new ThreatFacts.Ways(0, -1, -1, false, 16, 3, false, false, false, true, 12, 2, new int[]{-180, 63, -238}, 20);
        DangerOptions.Choice c = DangerOptions.choose(new ThreatFacts(me(4), List.of(), w), night(16, false, 200, 200));
        DangerOptions.Priced wait = way(c, DangerOptions.Way.WAIT);
        assertTrue(c.best().way() == DangerOptions.Way.SHORE || c.best().way() == DangerOptions.Way.PILLAR_OUT, c.terms());
        assertTrue(c.best().costS() < wait.costS(), c.terms());
        assertFalse(way(c, DangerOptions.Way.BOX).possible(), "in water the box is the shore's: " + c.terms());
        assertTrue(c.chain().startsWith("goto -180 63 -238 then shelter"), c.chain());
        assertTrue(c.terms().contains("sinking: air"), c.terms());
        // a shallow pond (2 cells), no shore: the pillar out
        ThreatFacts.Ways pond = new ThreatFacts.Ways(0, -1, -1, false, 16, 3, false, false, false, true, 2, 0, null, -1);
        DangerOptions.Choice p = DangerOptions.choose(new ThreatFacts(me(20), List.of(), pond), night(16, false, 300, 200));
        assertEquals(DangerOptions.Way.PILLAR_OUT, p.best().way(), p.terms());
        assertEquals("pillar then wait 60", p.chain());
    }

    @Test
    void aWaitOnLandAtFullHealthStillBeatsABoxWithNoMobNear() {
        ThreatFacts.Ways land = new ThreatFacts.Ways(16, -1, -1, false, 0, 3, true, false, false);
        DangerOptions.Choice c = DangerOptions.choose(new ThreatFacts(me(20), List.of(), land), night(16, false, 300, 200));
        assertEquals(DangerOptions.Way.WAIT, c.best().way(), c.terms());
        // a 3 hp loss at 4 hp prices above a box
        DangerOptions.Choice low = DangerOptions.choose(new ThreatFacts(me(4), List.of(), land), night(16, false, 300, 200));
        assertEquals(DangerOptions.Way.BOX, low.best().way(), low.terms());
        assertTrue(way(low, DangerOptions.Way.WAIT).costS() > way(low, DangerOptions.Way.BOX).costS(), low.terms());
        assertTrue(low.terms().contains("s a point, death 200 s"), "every number in aa why: " + low.terms());
    }

    @Test
    void thePillarReadsTheBagPlanksCount() {
        assertEquals(16 - AaEscape.PLANK_RESERVE, AaEscape.pillarBlocks(Map.of("oak_planks", 16, "log", 35)), "a table and sticks kept back");
        assertEquals(0, AaEscape.pillarBlocks(Map.of("oak_planks", 4)));
        assertEquals(AaDesires.shellBlocks(Map.of("oak_planks", 16, "dirt", 3)), AaEscape.pillarBlocks(Map.of("oak_planks", 16, "dirt", 3)), "one list");
        assertEquals(14, AaEscape.pillarBlocks(Map.of("minecraft:cobblestone", 4, "planks", 16)));
        // the facts said 0 (an older count), the bag 16: the pillar is possible
        ThreatFacts.Ways land = new ThreatFacts.Ways(0, -1, -1, false, 0, 3, true, false, false);
        DangerOptions.Choice c = DangerOptions.choose(new ThreatFacts(me(20), List.of(), land), night(16, false, 300, 200));
        assertTrue(way(c, DangerOptions.Way.PILLAR).possible(), c.terms());
    }

    @Test
    void theDeathCostIsTheKitTheWalkBackTheRespawnAndTheNight() {
        double[] d = AaDesires.deathCost(Map.of(), new int[]{43, 64, 0}, new int[]{0, 64, 0}, 1.0);
        assertEquals(43 / io.github.mojolowjo.entropybot.aa.brain.Site.SPEED + AaDesires.RESPAWN_S + 120, d[0], 1e-6);
        assertEquals(AaDesires.RESPAWN_S + 60, AaDesires.deathCost(Map.of(), null, null, 0)[0], 1e-9);
    }

    @Test
    void aJobPausedTooLongByTheAirReflexEndsAndTheSlotIsFreed() {
        Clock.Fake clock = new Clock.Fake(10_000_000);
        FakePort port = new FakePort();
        Blackboard bb = new Blackboard(clock, port, new Tracer());
        bb.in.pickTier = 0; bb.in.pickaxes = 0; bb.in.tableKnown = false; bb.in.furnaceKnown = false; bb.in.torches = 0;
        bb.in.baseKnown = false; bb.in.bedKnown = false; bb.in.baseChests = false; bb.in.freeSlots = 36; bb.in.wool = 0;
        bb.in.areaName = null; bb.in.hungerDrains = false;
        bb.in.pos = new int[]{-195, 60, -238};
        AaTree tree = AaTree.build(new AaScorer());
        tree.tick(bb);
        assertTrue(bb.task() != null && !bb.task().paused(), "a job runs in the slot");
        bb.in.health = 4;
        bb.in.air = 150;                          // sinking at 4 hp: the drown guard pauses it
        tree.tick(bb);
        assertTrue(bb.task().paused());
        for (int i = 0; i < 12; i++) {
            clock.advance(1_000);
            tree.tick(bb);
        }
        assertTrue(port.log.stream().anyMatch(l -> l.contains("paused too long: drowning, health 4")), port.log.toString());
        clock.advance(AaTree.CALM_MS + 100);
        tree.tick(bb);
        assertTrue(bb.task() == null || !bb.task().paused(), "the paused job ended; the slot picks again");
        assertNotEquals("danger", tree.driver(), "the air guard yields to the brain until the air is full");
        assertTrue(AaDesires.waterHere(bb), "the spot is water for the pricing");
        bb.in.air = 300;
        clock.advance(1_000);
        tree.tick(bb);
        bb.in.air = 150;
        clock.advance(1_000);
        tree.tick(bb);
        assertEquals("danger", tree.driver(), "air full again: the guard is back");    }

    @Test
    void review5TheSinkToTheFloorCountsForTheAir() {
        // 8 deep, at the surface (head 0 under): 6 s of sinking first makes the pillar out too long for 5 s of air
        ThreatFacts.Ways w = new ThreatFacts.Ways(0, -1, -1, false, 16, 3, false, false, false, true, 8, 0, null, -1);
        DangerOptions.Choice c = DangerOptions.choose(new ThreatFacts(me(20), List.of(), w), night(16, false, 100, 200));
        DangerOptions.Priced po = way(c, DangerOptions.Way.PILLAR_OUT);
        assertFalse(po.possible(), c.terms());
        assertTrue(po.why().contains("not enough air"), po.why());
        assertTrue(po.timeS() > 8 * (DangerOptions.PLACE_S + 1 / DangerOptions.SWIM_BPS), "the sink is in the time");
    }

    @Test
    void review1AWayNotPricedNowKeepsItsChain() {
        Clock.Fake clock = new Clock.Fake(10_000_000);
        FakePort port = new FakePort();
        Blackboard bb = new Blackboard(clock, port, new Tracer());
        bb.in.dayTime = 18000;
        bb.in.hungerDrains = true;
        bb.in.difficulty = 2;
        bb.in.pos = new int[]{0, 64, 0};
        bb.fact(io.github.mojolowjo.entropybot.aa.brain.Brain.SURFACE_Y_FACT, (java.util.function.IntBinaryOperator) (x, z) -> 64);
        ThreatFacts land = new ThreatFacts(ThreatFacts.CALM.me(), List.of(), new ThreatFacts.Ways(0, -1, -1, false, 0, 3, true, false, false));
        bb.fact(AaDesires.THREAT_FACT, (java.util.function.Supplier<ThreatFacts>) () -> land);
        io.github.mojolowjo.entropybot.aa.core.RunJob job = io.github.mojolowjo.entropybot.aa.core.RunJob.of("j", io.github.mojolowjo.entropybot.aa.core.TaskKind.WAIT, "goto 5 64 5 then shelter", "danger");
        job.tick(bb);
        bb.fact(AaDesires.WAY_FACT, DangerOptions.Way.SHORE);        // swam ashore: the shore row is not priced on land
        clock.advance(10_000);
        assertNull(AaDesires.reprice(bb, job, -1), "the shore chain goes on");
    }

    @Test
    void review6OnlyTheAirReflexPauseCountsTowardsPausedTooLong() {
        Clock.Fake clock = new Clock.Fake(10_000_000);
        FakePort port = new FakePort();
        Blackboard bb = new Blackboard(clock, port, new Tracer());
        bb.in.pickTier = 0; bb.in.pickaxes = 0; bb.in.tableKnown = false; bb.in.furnaceKnown = false; bb.in.torches = 0;
        bb.in.baseKnown = false; bb.in.bedKnown = false; bb.in.baseChests = false; bb.in.freeSlots = 36; bb.in.wool = 0;
        bb.in.areaName = null; bb.in.hungerDrains = false;
        AaTree tree = AaTree.build(new AaScorer());
        tree.tick(bb);
        bb.in.inLavaOrFire = true;
        for (int i = 0; i < 15; i++) {
            clock.advance(1_000);
            tree.tick(bb);
        }
        assertFalse(port.log.stream().anyMatch(l -> l.contains("paused too long")), port.log.toString());
        assertTrue(bb.task().paused(), "lava keeps the pause");
    }
}