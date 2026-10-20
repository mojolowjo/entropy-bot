package io.github.mojolowjo.entropybot.aa.brain;

import io.github.mojolowjo.entropybot.aa.AaDesires;
import io.github.mojolowjo.entropybot.aa.Rung;
import io.github.mojolowjo.entropybot.aa.core.Blackboard;
import io.github.mojolowjo.entropybot.aa.core.Clock;
import io.github.mojolowjo.entropybot.aa.core.FakePort;
import io.github.mojolowjo.entropybot.aa.core.RunJob;
import io.github.mojolowjo.entropybot.aa.core.TaskKind;
import io.github.mojolowjo.entropybot.aa.core.Tracer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** AA_ROUND2 1: one cost path: the picker's live cost first, the hand/measured cost when NaN; upkeep divided below urgent. */
class BrainCostPathTest {
    final Clock.Fake clock = new Clock.Fake(10_000_000);
    final FakePort port = new FakePort();
    final Blackboard bb = new Blackboard(clock, port, new Tracer());

    /** A picker whose last cost the test sets; counts refreshes. */
    static final class FakePicker implements Rung.Picker {
        double cost = Double.NaN;
        int refreshes;

        @Override public String pick(Blackboard bb, String planned) { return planned; }

        @Override public boolean failed(Blackboard bb, String end) { return false; }

        @Override public String why() { return "fake"; }

        @Override public double lastCostS() { return cost; }

        @Override public void refresh(Blackboard bb) { refreshes++; }
    }

    static Desire plain(String id, Desire.Kind k, double v) {
        return new Desire(id, k, RunJob.of(id + ".job", TaskKind.WAIT, "do " + id, "brain"), b -> v);
    }

    @Test
    void aLiveCostRaisesIronAboveTheTable() {
        FakePicker p = new FakePicker();
        p.cost = 600;
        Desire iron = plain("iron", Desire.Kind.PROGRESS, 70).cost(600).liveCost(b -> p.lastCostS());
        Desire table = plain("table", Desire.Kind.PROGRESS, 70).cost(60);
        Brain br = new Brain("brain", "t", iron, table);
        var all = br.scoreAll(bb);
        assertTrue(all.get(0).rank() < all.get(1).rank(), all.toString());
        assertTrue(all.get(0).parts().contains("cost 600s live"), all.get(0).parts());
        assertTrue(all.get(1).parts().contains("cost 60s hand"), all.get(1).parts());
        p.cost = 20;
        all = br.scoreAll(bb);
        assertTrue(all.get(0).rank() > all.get(1).rank(), all.toString());
        assertEquals(70 / (1 + 20 / Curves.COST_C), all.get(0).score(), 1e-9);
    }

    @Test
    void nanFallsBackToTheHandCost() {
        Desire iron = plain("iron", Desire.Kind.PROGRESS, 70).cost(600).liveCost(b -> Double.NaN);
        var s = new Brain("brain", "t", iron).scoreAll(bb).get(0);
        assertEquals(35, s.score(), 1e-9);
        assertTrue(s.parts().contains("cost 600s hand"), s.parts());
    }

    @Test
    void upkeepIsDividedOnlyBelowUrgent() {
        Desire food = plain("food", Desire.Kind.UPKEEP, 35).liveCost(b -> 22.0);
        Desire starving = plain("starving", Desire.Kind.UPKEEP, 80).liveCost(b -> 600.0);
        var all = new Brain("brain", "t", food, starving).scoreAll(bb);
        assertEquals(34, Math.round(all.get(0).score()));
        assertTrue(all.get(0).parts().contains("live"), all.get(0).parts());
        assertEquals(80, all.get(1).score(), 1e-9, "urgent upkeep never gets cheaper by being far");
    }

    @Test
    void aRungDesireTakesThePickersCostAndRescoresEveryTenSecondsWhileIdle() {
        FakePicker p = new FakePicker();
        p.cost = 30;
        // AREAS: no area gate any more
        Rung r = new Rung("needs.iron", TaskKind.IRON, b -> true, b -> null, b -> "goal iron 3", p);
        Desire d = AaDesires.rung(r);
        Brain br = new Brain("brain", "t", d);
        var s = br.scoreAll(bb).get(0);
        assertTrue(s.parts().contains("cost 30s live"), s.parts());
        assertEquals(1, p.refreshes);
        br.scoreAll(bb);
        assertEquals(1, p.refreshes, "within 10 s: no new rescore");
        clock.advance(AaDesires.RESCORE_MS);
        br.scoreAll(bb);
        assertEquals(2, p.refreshes);
    }
}
