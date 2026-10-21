package io.github.mojolowjo.entropybot.aa.brain;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Round 4A T: every moved hand constant is a Tuning entry with its old default; aa tune lists it; the legacy names agree. */
class ConstantsTest {
    private static final Map<String, Double> OLD = Map.ofEntries(
            Map.entry("urgent", 75.0), Map.entry("margin", 15.0), Map.entry("capExempt", 90.0), Map.entry("trumpFood", 4.0),
            Map.entry("shelterMax", 99.0), Map.entry("shelterAt1", 85.0), Map.entry("torchCut", 0.05), Map.entry("lightFloor", 0.7),
            Map.entry("swordValue", 25.0), Map.entry("stashMax", 74.0), Map.entry("diedS", 60.0), Map.entry("dawnClimb", 45.0),
            Map.entry("respawnS", 5.0), Map.entry("healTo", 16.0), Map.entry("hideDayUnder", 10.0), Map.entry("pointS", 6.0),
            Map.entry("sourceFactor", 0.2), Map.entry("flow", 0.9), Map.entry("stationBase", 40.0), Map.entry("stationShare", 40.0),
            Map.entry("chestMin", 35.0), Map.entry("hidePerHp", 7.0), Map.entry("slowPauseS", 20.0), Map.entry("pausedRepriceS", 10.0));

    @Test void everyMovedConstantIsRegisteredWithItsOldDefault() {
        Tuning.reset();
        List<String> lines = Tuning.lines();
        for (var e : OLD.entrySet()) {
            assertTrue(Tuning.names().contains(e.getKey()), e.getKey() + " is not registered");
            assertEquals(e.getValue(), Tuning.defOf(e.getKey()), 1e-12, e.getKey());
            assertEquals(e.getValue(), Tuning.get(e.getKey()), 1e-12, e.getKey());
            assertTrue(lines.stream().anyMatch(l -> l.startsWith(e.getKey() + " ")), "aa tune lists " + e.getKey());
        }
    }

    @Test void legacyNamesAgreeWithTuning() {
        assertEquals(75, Brain.URGENT, 0);
        assertEquals(15, Brain.BONUS, 0);
        assertEquals(90, Brain.CAP_EXEMPT, 0);
        assertEquals(4, Brain.TRUMP_FOOD);
        assertEquals(99, Costs.SHELTER_MAX, 0);
        assertEquals(85, Costs.SHELTER_AT_1, 0);
        assertEquals(25, Costs.SWORD, 0);
        assertEquals(60, Kit.DIED_S, 0);
        assertEquals(74, Kit.STASH_MAX, 0);
        assertEquals(0.9, Curves.FLOW, 0);
    }

    @Test void tuningReachesTheCode() {
        try {
            Tuning.set("stationBase", 10);
            assertEquals(10 + 40, Worth.table(null, null), 1e-9);
            Tuning.set("shelterMax", 50);
            assertEquals(50, Costs.shelterScore(5), 1e-9);
        } finally { Tuning.reset(); }
        assertEquals(80, Worth.table(null, null), 1e-9);
    }
}
