package io.github.mojolowjo.entropybot.commands;

import com.google.gson.JsonObject;
import io.github.mojolowjo.entropybot.aa.door.Door;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** rm-execution: the owner's rules are automations; while AA is on a rule cannot start a job (one driver). */
class ChainsAaRulesTest {
    @AfterEach
    void unwire() {
        Door.wire(null, null);
        Door.reset();
    }

    @Test
    void aDueRuleStartsNothingWhileAaIsOn() {
        CommandsTest.Fake f = CommandsTest.fake(new JsonObject());
        assertTrue(f.chains.ruleCommand("every 30m do places").startsWith("ok: rule #1"));
        f.now += 31 * 60000;
        Door.wire(() -> true, null);
        f.chains.rulesTick();
        assertFalse(f.chains.running(), "AA on: the due rule starts no chain");
        assertTrue(f.ran.isEmpty(), "and no step ran");
        // a rule's chain started by hand under the rule driver is refused at the door too
        try (var s = Door.scope(io.github.mojolowjo.entropybot.aa.door.Driver.RULE)) {
            assertTrue(f.chains.startChain("owner", "rule #1", "places", 1).startsWith("error"), "the door refuses a rule's chain under AA");
        }
        assertFalse(f.chains.running());
        Door.wire(() -> false, null);
        f.chains.rulesTick();
        assertEquals("rule #1", f.chains.name(), "AA off: the owner's rule runs again");
    }

    @Test
    void noDeathParkingAndNoAutominer() {
        CommandsTest.Fake f = CommandsTest.fake(new JsonObject());
        for (int i = 0; i < 6; i++) f.chains.noteDeath();
        assertFalse(f.chains.deathsCommand("").contains("PARKED"), "DEATHS_PARK is gone: deaths are counted, nothing is parked");
    }
}
