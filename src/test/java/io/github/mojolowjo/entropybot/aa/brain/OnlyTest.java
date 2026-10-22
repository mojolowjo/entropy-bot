package io.github.mojolowjo.entropybot.aa.brain;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** simple-tools: aa only <need>|off. */
class OnlyTest {
    @AfterEach
    void off() { Only.set("off"); Tuning.reset(); }

    @Test
    void offChangesNothing() {
        assertNull(Only.need());
        assertEquals(42, Only.apply("needs.food", Desire.Kind.UPKEEP, 42), 1e-9);
        assertNull(Only.statusText());
    }

    @Test
    void theNamedNeedKeepsItsWeightAndEveryOtherNeedAndWantIsSilent() {
        assertEquals("tools", Only.set(" Tools "));
        assertEquals("only: tools", Only.statusText());
        assertEquals(40, Only.apply("needs.tools", Desire.Kind.UPKEEP, 40), 1e-9);
        assertEquals(0, Only.apply("needs.food", Desire.Kind.UPKEEP, 80), 1e-9);
        assertEquals(0, Only.apply("want.build", Desire.Kind.WANT, 30), 1e-9);
        assertEquals(0, Only.apply("night.sleep", Desire.Kind.UPKEEP, 30), 1e-9);
    }

    @Test
    void dangerTheOwnersGoalsAndOrdersStayOn() {
        Only.set("tools");
        assertEquals(70, Only.apply("danger", Desire.Kind.UPKEEP, 70), 1e-9);
        assertEquals(55, Only.apply("goal.x", Desire.Kind.OWNER, 55), 1e-9);
        assertEquals(100, Only.apply("needs.food", Desire.Kind.UPKEEP, 100), 1e-9);
        assertEquals(-1, Only.apply("needs.food", Desire.Kind.UPKEEP, -1), 1e-9);
    }

    @Test
    void theWeightIsATuningEntryAndOffRestoresEverything() {
        Only.set("tools");
        Tuning.set("only.otherWeight", 0.5);
        assertEquals(40, Only.apply("needs.food", Desire.Kind.UPKEEP, 80), 1e-9);
        assertNull(Only.set("off"));
        assertEquals(80, Only.apply("needs.food", Desire.Kind.UPKEEP, 80), 1e-9);
    }
}
