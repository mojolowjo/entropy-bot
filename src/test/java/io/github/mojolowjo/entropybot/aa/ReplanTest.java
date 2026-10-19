package io.github.mojolowjo.entropybot.aa;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ReplanTest {
    private static Options.Option opt(String id, String cmd, double cost) { return new Options.Option(id, id, cmd, cost, "t", id, true); }

    @Test void pillarBeatsWalkForOwnShaft() {
        Replan.Ret r = Replan.chooseReturn(20, true, 32, true, 0, 0);
        assertEquals(Replan.Leg.PILLAR, r.leg());
        assertEquals(5.0, r.cost(), 1e-9);                 // 20 x 5 x 0.05
        assertEquals(Replan.Leg.WALK, Replan.chooseReturn(20, true, 10, true, 0, 0).leg());      // block count check
        assertEquals(Replan.Leg.WALK, Replan.chooseReturn(20, true, 32, false, 0, 0).leg());
        assertEquals(70.0, Replan.chooseReturn(20, true, 10, true, 0, 0).cost(), 1e-9);          // 20 x 3 + 10
        assertEquals(Replan.Leg.NONE, Replan.chooseReturn(0, false, 0, true, 0, 0).leg());
    }

    @Test void returnNowFallsBackToWalk() {
        Replan.Ret p = Replan.chooseReturn(10, true, 20, true, 0, 0);
        assertEquals(Replan.Leg.PILLAR, Replan.returnNow(p, 10, true, 20));
        assertEquals(Replan.Leg.WALK, Replan.returnNow(p, 10, false, 20));
        assertEquals(Replan.Leg.WALK, Replan.returnNow(p, 10, true, 5));
        assertEquals(Replan.Leg.NONE, Replan.returnNow(p, 0, true, 20));
    }

    @Test void ironDigRanksByRoundTrip() {
        IronOptions.Ore buried = new IronOptions.Ore(0, 40, 0, 10, true, false, false, 12, false, null, false);
        IronOptions.Ore cave = new IronOptions.Ore(30, 60, 0, 30, true, true, false, 5, false, null, false);
        IronOptions.Facts f = new IronOptions.Facts(List.of(buried, cave), 64, false, 64, true, -1, false, "no", -1, false, false, 0);
        Options.Choice c = new IronOptions.Set().choose(f, new Options.OptionMemory(), 0, "mine iron 3 then smelt raw_iron 3", 1, null, null);
        Options.Option dig = c.ranked().stream().filter(o -> o.way().equals("dig_known")).findFirst().orElseThrow();
        assertEquals(Replan.Leg.PILLAR, dig.ret().leg());
        assertEquals(0, dig.ret().afterStep());
        Options.Option walk = c.ranked().stream().filter(o -> o.way().equals("walk_known")).findFirst().orElseThrow();
        assertEquals(Replan.Leg.WALK, walk.ret().leg());
        assertTrue(walk.total() > walk.cost());
        for (int i = 1; i < c.ranked().size(); i++) assertTrue(c.ranked().get(i - 1).total() <= c.ranked().get(i).total());
    }

    @Test void timerBoundaryAndLongStep() {
        Replan.Timer t = new Replan.Timer();
        assertTrue(t.due("1/3 a", 0));
        assertFalse(t.due("1/3 a", 5_000));
        assertFalse(t.due("1/3 a", 14_000));
        assertTrue(t.due("1/3 a", 15_000));
        assertFalse(t.due("1/3 a", 19_000));
        assertTrue(t.due("1/3 a", 20_000));
        assertTrue(t.due("2/3 b", 21_000));
    }

    @Test void switchRules() {
        Options.Option run = opt("walk", "mine iron 3 then smelt raw_iron 3", 100);
        Options.Option alt = opt("cave", "mine cave iron 3 then smelt raw_iron 3", 60);
        List<Options.Option> fresh = List.of(alt, opt("walk", run.command(), 100));
        Replan.Decision d = Replan.decide(run, fresh, 0, 100_000, -1, false);
        assertTrue(d.switches());
        assertEquals(0, d.from());
        assertTrue(d.why().contains("100") && d.why().contains("60"));
        assertFalse(Replan.decide(run, fresh, 0, 100_000, 90_000, false).switches());        // 20 s lock
        assertFalse(Replan.decide(run, fresh, 0, 100_000, -1, true).switches());             // mid dig/pillar
        assertFalse(Replan.decide(run, fresh, 1, 100_000, -1, false).switches());            // shared smelt step
        assertFalse(Replan.decide(run, List.of(opt("cave", alt.command(), 75), opt("walk", run.command(), 100)), 0, 100_000, -1, false).switches());  // only 25%
        assertFalse(Replan.decide(opt("a", "x", 4), List.of(opt("b", "y", 2), opt("a", "x", 4)), 0, 100_000, -1, false).switches());               // saves < 3 s
    }

    private static List<Replan.Cell> col(int n, Replan.Cell c) { return java.util.Collections.nCopies(n, c); }

    @Test void returnChosenAtTheEndByCost() {
        // open own shaft 6 up: pillar 0.3 vs walk 28
        Replan.Way w = Replan.chooseNow(col(6, Replan.Cell.air()), 6, 20, 28);
        assertEquals(Replan.Leg.PILLAR, w.leg());
        assertEquals(0.3, w.cost(), 1e-9);
        assertTrue(w.why().contains("walk 28"));
        // aa381: the shaft broken by a block above: tunnel up, 1 foreign break = 6 + 0.3
        List<Replan.Cell> c = new java.util.ArrayList<>(col(5, Replan.Cell.air()));
        c.add(Replan.Cell.stone(false));
        assertEquals(Replan.Leg.TUNNEL, Replan.chooseNow(c, 6, 20, 28).leg());
        assertEquals(6.3, Replan.tunnelUp(c, 6, 20).cost(), 1e-9);
        // 30 foreign breaks (180) vs walk 100: walk
        assertEquals(Replan.Leg.WALK, Replan.chooseNow(col(30, Replan.Cell.stone(false)), 30, 64, 100).leg());
        // own dug cells are x1: 30 + 1.5 vs 100
        assertEquals(Replan.Leg.TUNNEL, Replan.chooseNow(col(30, Replan.Cell.stone(true)), 30, 64, 100).leg());
        // only 20% better wins: 6.3 vs 7.5 (16% cheaper) walks
        assertEquals(Replan.Leg.WALK, Replan.chooseNow(c, 6, 20, 7.5).leg());
        assertEquals(Replan.Leg.NONE, Replan.chooseNow(List.of(), 0, 0, 0).leg());
    }

    @Test void hazardsAndCapAreCosts() {
        List<Replan.Cell> lava = List.of(Replan.Cell.air(), new Replan.Cell(false, false, 1, true, false, false));
        assertEquals(Replan.Leg.WALK, Replan.chooseNow(lava, 2, 20, 50).leg());
        List<Replan.Cell> gravel = List.of(new Replan.Cell(false, false, 1, false, false, true));
        assertEquals(Replan.Leg.WALK, Replan.chooseNow(gravel, 1, 20, 40).leg());
        assertEquals(Replan.Leg.WALK, Replan.chooseNow(List.of(new Replan.Cell(false, false, Double.POSITIVE_INFINITY, false, false, false)), 1, 20, 40).leg());
        assertTrue(Replan.tunnelUp(col(50, Replan.Cell.air()), 50, 64).cost() > 10 * Replan.OVER_CAP - 1);
        assertTrue(Replan.tunnelUp(col(10, Replan.Cell.air()), 10, 2).why().contains("short"));
    }

    @Test void wanderTriggersReplan() {
        assertArrayEquals(new int[]{-65, 57, -10}, Replan.targetOf("dig_known -65 57 -10"));
        assertNull(Replan.targetOf("cave"));
        assertTrue(Replan.wandered(new int[]{-65, 57, -10}, new int[]{-87, 40, -44}));
        assertFalse(Replan.wandered(new int[]{-65, 57, -10}, new int[]{-60, 50, -10}));
        Options.Option run = opt("dig_known -65 57 -10", "mine iron 3 dig then smelt raw_iron 3", 50);
        List<Options.Option> fresh = List.of(opt("cave", "mine cave iron 3 then smelt raw_iron 3", 60));
        assertFalse(Replan.decide(run, fresh, 0, 100_000, -1, true, false).switches());
        Replan.Decision d = Replan.decide(run, fresh, 0, 100_000, -1, true, true);
        assertTrue(d.switches());
        assertTrue(d.why().contains("wandered"));
    }

    @Test void stepHelpers() {
        assertEquals(2, Replan.stepIndex("3/7 craft iron_pickaxe 1"));
        assertEquals("craft iron_pickaxe 1", Replan.stepBody("3/7 craft iron_pickaxe 1"));
        assertTrue(Replan.digOrPillarStep("mine iron 3 dig", new int[]{0, 64, 0}));
        assertTrue(Replan.digOrPillarStep("goto 1 70 1", new int[]{0, 64, 0}));
        assertFalse(Replan.digOrPillarStep("goto 1 64 1", new int[]{0, 64, 0}));
        assertFalse(Replan.digOrPillarStep("mine iron 3", new int[]{0, 64, 0}));
    }
}
