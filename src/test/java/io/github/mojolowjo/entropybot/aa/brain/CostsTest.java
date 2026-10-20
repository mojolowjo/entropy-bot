package io.github.mojolowjo.entropybot.aa.brain;

import io.github.mojolowjo.entropybot.craft.OwnTable;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** aa-costs items 2-7: the hard-wired rules as costs and values (pure scoring). */
class CostsTest {
    @Test
    void huntOutTravelShrinksWithUrgency() {
        assertEquals(1, Costs.foodUrgency(6, 20), 1e-9);
        assertEquals(1, Costs.foodUrgency(18, 9), 1e-9, "low health is urgent too");
        assertEquals(0.05, Costs.foodUrgency(20, 20), 1e-9);
        assertEquals(60, Costs.huntOutTravel(30, 6, 20), 1e-9, "urgent: just there and back");
        assertTrue(Costs.huntOutTravel(30, 16, 20) > 3 * Costs.huntOutTravel(30, 6, 20), "fed: the trip is dear");
        assertTrue(Costs.huntOutTerms(30, 6, 20).contains("urgency 1.0"));
    }

    @Test
    void coalGoesWhereItIsWorthMore() {
        assertTrue(Costs.torchCoal(0, 0, 0, 0), "no coal goal: torches as before");
        assertTrue(Costs.torchCoal(4, 0, 50, 16), "no torches: 4 torches beat 1/16 of the goal");
        assertFalse(Costs.torchCoal(4, 14, 50, 16), "14 torches: the coal goes to the goal");
        assertFalse(Costs.torchCoal(4, 4, 70, 4), "a small coal goal near its end: each coal is dear");
        assertTrue(Costs.coalTerms(4, 14, 50, 16).endsWith("keep coal"), Costs.coalTerms(4, 14, 50, 16));
    }

    @Test
    void sprintWeighsHungerAgainstTime() {
        assertFalse(Costs.sprint(true, false, 5, 30, 1).conserve(), "a food source known: sprint");
        assertTrue(Costs.sprint(true, true, 0, 30, 1).conserve(), "no source, empty bag: walk");
        assertFalse(Costs.sprint(true, true, 25, 30, 1).conserve(), "no source, the bag nearly covers it: sprint");
        assertTrue(Costs.sprint(true, true, 18, 30, 2).conserve(), "measured drain twice the hand rate tips it");
        assertFalse(Costs.sprint(false, true, 0, 30, 1).conserve(), "no drain (creative/peaceful)");
        assertTrue(Costs.sprint(true, true, 0, 30, 1).terms().endsWith("walk"));
    }

    @Test
    void toolsValueFromWhatTheyBlockNoCap() {
        double all = Costs.blocked(0, false, 0, 0, true);
        assertEquals(1, all, 1e-9);
        assertEquals(85, Costs.tools(0, 0, all), 1e-9, "no pickaxe blocking everything: over the urgent line");
        assertTrue(Costs.tools(0, 0, Costs.blocked(0, true, 5, 16, false)) < Brain.URGENT, "only iron waits: not urgent");
        assertTrue(Costs.blocked(1, false, 5, 16, false) < Costs.blocked(0, false, 5, 16, false), "a wooden pickaxe mines cobble");
        assertEquals(40, Costs.tools(3, 100, 0), 1e-9);
    }

    @Test
    void toolSmootherHoldsADropOnly() {
        Costs.ToolSmoother s = new Costs.ToolSmoother();
        assertEquals(2, s.update(2, 0));
        assertEquals(2, s.update(0, 100), "a craft flicker");
        assertEquals(2, s.update(2, 500));
        assertEquals(2, s.update(0, 1000));
        assertEquals(0, s.update(0, 1000 + Costs.ToolSmoother.HOLD_MS), "held: real");
        assertEquals(3, s.update(3, 6000), "a rise counts at once");
    }

    @Test
    void nightDangerFromTheFacts() {
        double early = Costs.nightDanger(2, 0, 20, 20, 0, 0);
        assertTrue(Costs.shelterScore(early) >= Brain.URGENT, "early game, Normal: shelter");
        assertEquals(0, Costs.nightDanger(0, 0, 20, 20, 0, 5), 1e-9, "Peaceful");
        assertTrue(Costs.nightDanger(3, 0, 20, 20, 0, 0) > early, "Hard");
        assertTrue(Costs.shelterScore(Costs.nightDanger(2, 15, 20, 20, 0, 0)) < Brain.URGENT, "iron armour: work on");
        assertTrue(Costs.nightDanger(2, 0, 8, 20, 0, 0) > early, "hurt");
        assertTrue(Costs.nightDanger(2, 0, 20, 20, 3, 0) < early, "torches near");
        assertTrue(Costs.nightDanger(2, 0, 20, 20, 0, 4) > early, "hostiles near");
        assertTrue(Costs.shelterScore(10) <= Costs.SHELTER_MAX);
    }

    @Test
    void tableEndIsTheCheaperOne() {
        assertEquals(OwnTable.End.KEEP_FOR_NEXT, OwnTable.decide(false, true, false, false, true), "the next step crafts");
        assertEquals(OwnTable.End.TAKE, OwnTable.decide(false, false, false, false, true), "away from the camp: back in the bag");
        assertEquals(OwnTable.End.LEAVE_AT_CAMP, OwnTable.decide(false, false, true, false, true), "at a camp without a table: it stays");
        assertEquals(OwnTable.End.TAKE, OwnTable.decide(false, false, true, true, true), "the camp has one already");
        assertEquals(OwnTable.End.TAKE, OwnTable.decide(false, false, true, false, false), "no wood to remake one: keep it");
        assertEquals(OwnTable.End.TAKE, OwnTable.decide(true, true, true, false, true), "a failed craft");
        assertTrue(OwnTable.terms(false, false, true, false, true).endsWith("leave_at_camp"));
        assertTrue(OwnTable.registersAsCamp(OwnTable.End.LEAVE_AT_CAMP), "director: a table left at the camp counts as the camp's");
        assertFalse(OwnTable.registersAsCamp(OwnTable.End.KEEP_FOR_NEXT));
        OwnTable.takeCampTable();
        OwnTable.noteCampTable();
        assertTrue(OwnTable.takeCampTable(), "the latch is fed once");
        assertFalse(OwnTable.takeCampTable());
    }
}
