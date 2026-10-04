package io.github.mojolowjo.entropybot.commands;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Package D: a chain goes on while a furnace works, and picks the output up between two of its steps when it is due. */
class FurnaceChainTest {

    @Test
    void aDueFurnaceIsPickedUpBetweenTwoStepsAndTheChainGoesOn() {
        JsonObject mem = new JsonObject();
        CommandsTest.Fake f = CommandsTest.fake(mem);
        assertTrue(f.chains.startChain("owner", "chain", "goto 1 2 3 then goto 4 5 6", 1).startsWith("started"));
        f.run(5);
        f.bridgeTakes("ok: going to 1 2 3", true);
        f.furnaceDue = 1;                                  // the furnace's output is due while the first step walks
        f.run(10);
        assertEquals(List.of("goto 1 2 3"), f.ran, "never in the middle of a step");
        f.link.done(1, "ok: arrived");
        f.run(5);
        assertEquals(List.of("goto 1 2 3", Chains.PICKUP_STEP), f.ran, "the pickup comes before the next step");
        assertEquals(1, mem.getAsJsonObject("run").get("idx").getAsInt(), "a restart now carries on with step 2, not the pickup");
        f.bridgeTakes("started: collecting 40 iron_ingot from 1 furnace", true);
        f.run(10);
        f.link.done(2, "ok: done collecting 40 iron_ingot from 1 furnace; got 40 iron_ingot from the furnace at -32 53 183");
        f.run(10);
        assertEquals(List.of("goto 1 2 3", Chains.PICKUP_STEP, "goto 4 5 6"), f.ran, "then the chain goes on where it was");
        f.bridgeTakes("ok: going to 4 5 6", true);
        f.link.done(3, "ok: arrived");
        f.run(10);
        assertFalse(f.chains.running());
        assertEquals("chain: done - arrived", f.whispers.get(f.whispers.size() - 1), "the pickup isn't the chain's result");
    }

    @Test
    void aRefusedPickupDoesNotFireAgainAtEveryStep() {
        // the review's finding 7: a pickup refused before it runs (the furnace stays "due") must not re-fire every 5 ticks
        JsonObject mem = new JsonObject();
        CommandsTest.Fake f = CommandsTest.fake(mem);
        f.instant.put("smelt", "busy: something");
        f.furnaceDue = 1000;                                 // due at every boundary
        f.chains.startChain("owner", "chain", "places then places then places then places", 1);
        f.run(200);
        assertEquals(List.of(Chains.PICKUP_STEP, "places", "places", "places", "places"), f.ran, "one pickup, then the cooldown");
        assertFalse(f.chains.running());
        // a new chain starts without the old one's cooldown
        f.ran.clear();
        f.chains.startChain("owner", "chain", "places", 1);
        f.run(20);
        assertEquals(List.of(Chains.PICKUP_STEP, "places"), f.ran);
    }

    @Test
    void aPickupWithNothingDueOrRefusedDoesNotStopTheChain() {
        JsonObject mem = new JsonObject();
        CommandsTest.Fake f = CommandsTest.fake(mem);
        f.instant.put("smelt", "error: the furnace at 1 2 3 is gone");
        f.furnaceDue = 1;
        f.chains.startChain("owner", "chain", "places then places", 1);
        f.run(40);
        assertEquals(List.of(Chains.PICKUP_STEP, "places", "places"), f.ran);
        assertFalse(f.chains.running());
        assertTrue(f.whispers.get(f.whispers.size() - 1).startsWith("chain: done"), f.whispers.toString());
    }
}
