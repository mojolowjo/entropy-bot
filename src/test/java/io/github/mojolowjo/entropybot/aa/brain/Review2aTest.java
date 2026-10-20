package io.github.mojolowjo.entropybot.aa.brain;

import io.github.mojolowjo.entropybot.aa.AaLadder;
import io.github.mojolowjo.entropybot.aa.core.Blackboard;
import io.github.mojolowjo.entropybot.aa.core.Clock;
import io.github.mojolowjo.entropybot.aa.core.FakePort;
import io.github.mojolowjo.entropybot.aa.core.RunJob;
import io.github.mojolowjo.entropybot.aa.core.TaskKind;
import io.github.mojolowjo.entropybot.aa.core.Tracer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** AA_REVIEW review 2 items 1, 3, 5: the night factor by where the work is, no pickTier surface gate, light as an area. */
class Review2aTest {
    final Clock.Fake clock = new Clock.Fake(10_000_000);
    final Blackboard bb = new Blackboard(clock, new FakePort(), new Tracer());

    Desire d(String id, Desire.Kind k, double v, Desire.Exposure e) {
        return new Desire(id, k, RunJob.of(id + ".job", TaskKind.WAIT, "do " + id, "brain"), b -> v).cost(0).exposure(e);
    }

    void normalNight() {
        bb.in.dayTime = 18000;
        bb.in.hungerDrains = true;
        bb.in.difficulty = 2;
    }

    @Test
    void item1PostDeathToolsAtTheSurfaceFallUnderTheShelter() {
        normalNight();
        // AA_STEP2 1: the danger desire answers the night (night.shelter is gone)
        bb.fact(io.github.mojolowjo.entropybot.aa.AaDesires.THREAT_FACT,
                (java.util.function.Supplier<io.github.mojolowjo.entropybot.threat.ThreatFacts>) () -> new io.github.mojolowjo.entropybot.threat.ThreatFacts(io.github.mojolowjo.entropybot.threat.ThreatFacts.CALM.me(), java.util.List.of(),
                        new io.github.mojolowjo.entropybot.threat.ThreatFacts.Ways(0, -1, -1, true, 0, 0, true)));
        Desire shelter = io.github.mojolowjo.entropybot.aa.AaDesires.danger();
        Brain br = new Brain("brain", "t", d("needs.tools", Desire.Kind.UPKEEP, 98, Desire.Exposure.ANY), shelter);
        double danger = Brain.danger(bb);
        assertEquals(1.0, danger, 1e-9, "Normal, no armour, dark (AA_ROUND4 B2: no bed term)");
        var s = br.scoreAll(bb);
        assertEquals(98 / (1 + danger), s.get(0).score(), 1e-9, s.get(0).parts());
        assertTrue(s.get(0).rank() < 1000, "no longer urgent");
        assertTrue(s.get(0).parts().contains("night x"), s.get(0).parts());
        assertEquals(Costs.shelterScore(danger), s.get(1).score(), 1e-9, "the shelter is night-proof: " + s.get(1).parts());
        assertTrue(s.get(1).rank() > s.get(0).rank(), "the shelter beats post-death tools");
    }

    @Test
    void item1AnyWorkUnderGroundOrAtADeepSiteCountsAsUnder() {
        normalNight();
        Desire tools = d("needs.tools", Desire.Kind.UPKEEP, 98, Desire.Exposure.ANY);
        bb.in.underDepth = 3;
        assertEquals(Desire.Exposure.UNDER, Brain.workExposure(bb, tools), "AA_ROUND4 B1: no site: where the bot is (under ground)");
        bb.in.underDepth = 0;
        bb.in.pos = new int[]{0, 70, 0};
        tools.site(b -> Site.at(5, 40, 5));
        assertEquals(Desire.Exposure.UNDER, Brain.workExposure(bb, tools), "AA_ROUND4 B1: the site is deep: under");
        tools.site(b -> Site.at(5, 69, 5));
        assertEquals(Desire.Exposure.SURFACE, Brain.workExposure(bb, tools), "the site is at the surface");
        assertEquals(Desire.Exposure.UNDER, Brain.workExposure(bb, d("iron", Desire.Kind.PROGRESS, 50, Desire.Exposure.UNDER)), "labels other than ANY stay");
        bb.in.dayTime = 6000;
        Brain br = new Brain("brain", "t", d("needs.tools", Desire.Kind.UPKEEP, 98, Desire.Exposure.ANY));
        assertEquals(98, br.scoreAll(bb).get(0).score(), 1e-9, "day: unchanged");
    }

    @Test
    void item3AStonePickaxeUnderGroundOpensNoSurfaceGate() {
        bb.in.tableKnown = true;
        bb.in.furnaceKnown = true;
        bb.in.baseKnown = true;
        bb.in.pickTier = 2;
        bb.in.underDepth = 10;
        assertNull(AaLadder.openGate(bb), "iron dig with a stone pickaxe: no yo-yo");
        bb.in.tableKnown = false;
        assertEquals("needs.surface", AaLadder.openGate(bb), "a missing table still wants open ground");
    }

    @Test
    void round4B2TorchesWithin16CutTheDangerFivePercentEach() {
        double dark = Costs.nightDanger(2, 0, 20, 20, 0, 0);
        assertEquals(1.0, dark, 1e-9, "Normal, no armour, full health, no torches, no hostiles: 1 (the bed term went)");
        assertEquals(dark * 0.95, Costs.nightDanger(2, 0, 20, 20, 1, 0), 1e-9, "one torch: -5%");
        assertEquals(dark * 0.7, Costs.nightDanger(2, 0, 20, 20, 6, 0), 1e-9, "six: the floor 0.7");
        assertEquals(dark * 0.7, Costs.nightDanger(2, 0, 20, 20, 40, 0), 1e-9, "never under the floor");
        assertTrue(Costs.shelterScore(Costs.nightDanger(2, 0, 20, 20, 1, 0)) > Brain.URGENT, "one torch: the shelter stays urgent");
        assertEquals(0, Costs.litShare(new int[0]), 1e-9);
    }
}