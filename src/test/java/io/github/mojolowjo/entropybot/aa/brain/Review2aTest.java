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
        var night = io.github.mojolowjo.entropybot.aa.AaDesires.night();
        Desire shelter = night.stream().filter(x -> x.id.equals("night.shelter")).findFirst().orElseThrow();
        Brain br = new Brain("brain", "t", d("needs.tools", Desire.Kind.UPKEEP, 98, Desire.Exposure.ANY), shelter);
        double danger = Brain.danger(bb);
        assertEquals(1.05, danger, 1e-9, "Normal, no armour, no bed, dark");
        var s = br.scoreAll(bb);
        assertEquals(98 / (1 + danger), s.get(0).score(), 1e-9, s.get(0).parts());
        assertTrue(s.get(0).rank() < 1000, "no longer urgent");
        assertTrue(s.get(0).parts().contains("night x"), s.get(0).parts());
        assertEquals(Costs.shelterScore(danger), s.get(1).score(), 1e-9, "the shelter is night-proof: " + s.get(1).parts());
        assertTrue(s.get(1).rank() > s.get(0).rank(), "the shelter beats post-death tools");
    }

    @Test
    void item1AnyWorkUnderGroundOrAtADeepSiteKeepsItsLabel() {
        normalNight();
        Desire tools = d("needs.tools", Desire.Kind.UPKEEP, 98, Desire.Exposure.ANY);
        bb.in.underDepth = 3;
        assertEquals(Desire.Exposure.ANY, Brain.workExposure(bb, tools), "the bot is under ground");
        bb.in.underDepth = 0;
        bb.in.pos = new int[]{0, 70, 0};
        tools.site(b -> Site.at(5, 40, 5));
        assertEquals(Desire.Exposure.ANY, Brain.workExposure(bb, tools), "the site is deep");
        tools.site(b -> Site.at(5, 69, 5));
        assertEquals(Desire.Exposure.SURFACE, Brain.workExposure(bb, tools), "the site is at the surface");
        assertEquals(Desire.Exposure.UNDER, Brain.workExposure(bb, d("iron", Desire.Kind.PROGRESS, 50, Desire.Exposure.UNDER)), "labels other than ANY stay");
        bb.in.dayTime = 6000;
        Brain br = new Brain("brain", "t", d("needs.tools", Desire.Kind.UPKEEP, 98, Desire.Exposure.ANY));
        assertEquals(98, br.scoreAll(bb).get(0).score(), 1e-9, "day: unchanged");
    }

    @Test
    void item3AStonePickaxeUnderGroundOpensNoSurfaceGate() {
        bb.in.areaKnown = true;
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
    void item5OneTorchAtTheFeetIsNotALitArea() {
        double dark = Costs.nightDanger(2, 0, 20, 20, 0, 0, false);
        int[] oneTorch = new int[81];
        for (int i = 0; i < 5; i++) oneTorch[i] = 10;
        double torch = Costs.nightDanger(2, 0, 20, 20, Costs.litShare(oneTorch), 0, false);
        assertTrue(torch > dark * 0.97, "one torch cuts under 3%: " + torch);
        assertTrue(Costs.shelterScore(torch) > 85, "the shelter keeps its 89-ish");
        int[] all = new int[81];
        java.util.Arrays.fill(all, 14);
        assertEquals(dark * (1 - Costs.LIGHT_CUT), Costs.nightDanger(2, 0, 20, 20, Costs.litShare(all), 0, false), 1e-9, "at most -30%");
        assertEquals(dark * (1 - Costs.LIGHT_CUT), Costs.nightDanger(2, 0, 20, 20, 5.0, 0, false), 1e-9, "clamped");
        assertEquals(0, Costs.litShare(new int[0]), 1e-9);
    }
}
