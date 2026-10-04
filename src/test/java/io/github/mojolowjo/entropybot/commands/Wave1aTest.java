package io.github.mojolowjo.entropybot.commands;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.mojolowjo.entropybot.guard.GuardCore;
import io.github.mojolowjo.entropybot.storage.StorageRules;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Package A, wave 1 (TO-LOOK-AT-LATER 3, 4, 5, 9): "stop" holds the autominer for 10 minutes, a chain's retry after a
 * fight walks back to where the step was first, a deposit from far away goes to the base chests, and "guard check
 * ... break" says when the block is next to a liquid.
 */
class Wave1aTest {

    @Test
    void stopHoldsTheAutominerForTenMinutes() {
        JsonObject mem = JsonParser.parseString("{\"autominer\":{\"on\":true,\"log\":[],\"pausedUntil\":0,\"defaults\":true}}").getAsJsonObject();
        CommandsTest.Fake f = CommandsTest.fake(mem);
        assertEquals("; the autominer waits 10 min (\"autominer on\" to go on now)", f.chains.holdAutominer());
        assertEquals(f.now + Chains.AUTOMINER_HOLD_MS, mem.getAsJsonObject("autominer").get("heldUntil").getAsLong());
        assertTrue(f.chains.autominerCommand("status").startsWith("autominer is on (waiting 10 min after a stop)"), f.chains.autominerCommand("status"));
        assertEquals(f.now + Chains.AUTOMINER_HOLD_MS, f.chains.autominerState().get("heldUntil").getAsLong());
        f.tick = 1000;
        f.chains.autominerTick();
        assertFalse(f.chains.running(), "no decision while it waits");
        f.now += Chains.AUTOMINER_HOLD_MS - 1000;
        f.tick += 300;
        f.chains.autominerTick();
        assertFalse(f.chains.running(), "still waiting a second before the 10 minutes are up");
        f.now += 2000;
        f.tick += 300;
        f.chains.autominerTick();
        assertEquals("autominer", f.chains.name(), "after 10 minutes it decides again");
    }

    @Test
    void autominerOnLiftsTheHoldAndAStopWithItOffSaysNothing() {
        JsonObject mem = JsonParser.parseString("{\"autominer\":{\"on\":true,\"log\":[],\"pausedUntil\":0,\"defaults\":true}}").getAsJsonObject();
        CommandsTest.Fake f = CommandsTest.fake(mem);
        f.chains.holdAutominer();
        f.chains.autominerCommand("on");
        assertFalse(mem.getAsJsonObject("autominer").has("heldUntil"));
        f.tick = 1000;
        f.chains.autominerTick();
        assertTrue(f.chains.running(), "on again: it decides at once");
        f.chains.clear();
        f.chains.autominerCommand("off");
        assertEquals("", f.chains.holdAutominer(), "off: nothing to hold, nothing to say");
        assertEquals("", CommandsTest.fake(new JsonObject()).chains.holdAutominer(), "never on");
    }

    /** The job the chain started last (its request id noted for the test to end it), by its text. */
    static String take(CommandsTest.Fake f, List<Long> ids) {
        var r = f.takeNext();
        ids.add(r.id);
        return r.text;
    }

    @Test
    void aRetryAfterAFightWalksBackToWhereTheStepWasFirst() {
        CommandsTest.Fake f = CommandsTest.fake(new JsonObject());
        List<Long> ids = new ArrayList<>();
        f.pos = new int[]{100, 40, 100};
        f.chains.startChain("owner", "chain", "mine coal_ore 40 dig then places", 1);
        f.run(5);
        assertEquals("mine coal_ore 40 dig", take(f, ids));
        f.run(40);                                            // mining at 100 40 100
        f.fighting = true;
        f.pos = new int[]{100, 40, 130};                      // the fight takes it 30 blocks away (not the step's spot)
        f.run(20);
        f.link.done(ids.get(0), "stopped: attacked by zombie");
        f.run(20);
        f.fighting = false;
        f.run(250);
        assertEquals("goto 100 40 100", take(f, ids), "back to where it was mining first");
        f.run(20);
        f.link.done(ids.get(1), "ok: arrived near 100 40 100");
        f.pos = new int[]{100, 40, 100};
        f.run(20);
        assertEquals("mine coal_ore 40 dig", take(f, ids), "then the step again");
        f.run(20);
        f.link.done(ids.get(2), "done: mining coal_ore stopped - got 40 coal");
        f.run(40);
        assertFalse(f.chains.running());
        assertEquals(List.of("mine coal_ore 40 dig", "goto 100 40 100", "mine coal_ore 40 dig", "places"), f.ran);
        assertTrue(f.whispers.get(f.whispers.size() - 1).startsWith("chain: done"), f.whispers.toString());
    }

    @Test
    void aRetryCloseByNeedsNoWalkAndARefusedWalkStillRetries() {
        CommandsTest.Fake f = CommandsTest.fake(new JsonObject());
        List<Long> ids = new ArrayList<>();
        f.pos = new int[]{0, 60, 0};
        f.chains.startChain("owner", "chain", "explore 5", 1);
        f.run(5);
        take(f, ids);
        f.run(20);
        f.pos = new int[]{10, 60, 0};                         // 10 blocks: close enough
        f.link.done(ids.get(0), "stopped: attacked by spider");
        f.run(260);
        assertEquals("explore 5", take(f, ids), "no walk back for 10 blocks");
        // a second fight, far away; the walk back is refused (instant answer): the step is retried anyway
        f.run(20);
        f.pos = new int[]{60, 60, 0};
        f.link.done(ids.get(1), "stopped: low health, getting away from skeleton");
        f.instant.put("goto", "error: no path");
        f.run(260);
        assertEquals("goto 10 60 0", f.ran.get(2));
        f.run(10);
        assertEquals("explore 5", take(f, ids), "the step after the refused walk");
    }

    @Test
    void detourOnlyWhenFarAndKnown() {
        assertNull(Chains.detourFor(null, new int[]{0, 0, 0}));
        assertNull(Chains.detourFor(new int[]{0, 0, 0}, null));
        assertNull(Chains.detourFor(new int[]{0, 0, 0}, new int[]{16, 0, 0}));
        assertEquals("goto 0 -54 0", Chains.detourFor(new int[]{0, -54, 0}, new int[]{0, 53, 0}), "up at the base after a retreat");
    }

    @Test
    void aDepositFromFarAwayGoesToTheBaseChests() {
        int[] base = {-27, 53, 187};
        assertFalse(StorageRules.farFromBase(base, "minecraft:overworld", new int[]{0, 53, 200}, "minecraft:overworld"));
        assertTrue(StorageRules.farFromBase(base, "minecraft:overworld", new int[]{97, 68, 193}, "minecraft:overworld"), "the coalfield, 124 across");
        assertTrue(StorageRules.farFromBase(base, null, new int[]{-27, -54, 300}, "minecraft:overworld"), "a base without a dimension is this one");
        assertFalse(StorageRules.farFromBase(base, "minecraft:the_nether", new int[]{500, 70, 500}, "minecraft:overworld"));
        assertFalse(StorageRules.farFromBase(null, null, new int[]{500, 70, 500}, "minecraft:overworld"));
    }

    @Test
    void guardCheckSaysNextToLava() {
        assertEquals("", GuardCore.liquidNote(null));
        assertEquals(" - but it is next to lava, and I never break a block next to water or lava", GuardCore.liquidNote("lava"));
        // the cells looked at: above and the four sides (not below), lava before water
        java.util.Map<String, String> w = new java.util.HashMap<>();
        GuardCore.FluidAt at = (x, y, z) -> w.get(x + " " + y + " " + z);
        assertNull(GuardCore.liquidNextTo(at, 0, 0, 0));
        w.put("0 -1 0", "lava");
        assertNull(GuardCore.liquidNextTo(at, 0, 0, 0), "lava below is not next to it for the digging");
        w.put("0 0 1", "water");
        assertEquals("water", GuardCore.liquidNextTo(at, 0, 0, 0));
        w.put("1 0 0", "lava");
        assertEquals("lava", GuardCore.liquidNextTo(at, 0, 0, 0));
        w.clear();
        w.put("0 1 0", "lava");
        assertEquals("lava", GuardCore.liquidNextTo(at, 0, 0, 0), "above counts");
        // the reply "guard check" gives (Commands' GuardView)
        assertEquals("would refuse: no lease here - but it is next to lava, and I never break a block next to water or lava",
                GuardCore.checkReply("would refuse: no lease here", "break", "lava"));
        assertEquals("ok", GuardCore.checkReply("ok", "break", null));
        assertEquals("ok", GuardCore.checkReply("ok", "go", "lava"), "only a break gets the note");
        assertEquals("error: action must be break, place or go", GuardCore.checkReply("error: action must be break, place or go", "break", "lava"));
    }

    @Test
    void theDepositVerbChoosesTheBaseChestsFromFarAway() {
        JsonObject base = JsonParser.parseString("{\"x\":-27,\"y\":53,\"z\":187,\"dim\":\"minecraft:overworld\"}").getAsJsonObject();
        assertFalse(StorageRules.depositAtBase(base, new int[]{-20, 53, 190}, "minecraft:overworld"), "at the base");
        assertTrue(StorageRules.depositAtBase(base, new int[]{-119, -54, 194}, "minecraft:overworld"), "at the mine, 92 across");
        assertFalse(StorageRules.depositAtBase(base, new int[]{-119, -54, 194}, "minecraft:the_nether"));
        assertTrue(StorageRules.depositAtBase(JsonParser.parseString("{\"x\":0,\"y\":60,\"z\":0}").getAsJsonObject(), new int[]{0, 60, 100}, "minecraft:overworld"));
        assertFalse(StorageRules.depositAtBase(null, new int[]{500, 60, 500}, "minecraft:overworld"), "no base: as before");
    }

    @Test
    void aWalkBackCutShortByLowHealthIsAnotherTry() {
        CommandsTest.Fake f = CommandsTest.fake(new JsonObject());
        List<Long> ids = new ArrayList<>();
        f.pos = new int[]{100, 40, 100};
        f.chains.startChain("owner", "chain", "mine coal_ore 40 dig", 1);
        f.run(5);
        take(f, ids);
        f.run(20);
        f.pos = new int[]{100, 53, 200};                      // a retreat to the base
        f.link.done(ids.get(0), "stopped: low health, getting away from skeleton");
        f.run(260);
        assertEquals("goto 100 40 100", take(f, ids));
        f.run(20);
        f.pos = new int[]{100, 45, 160};                      // half way back, another fight
        f.link.done(ids.get(1), "stopped: attacked by zombie");
        f.run(260);
        assertEquals("goto 100 40 100", take(f, ids), "the walk back again, not the step from half way");
        f.run(20);
        f.pos = new int[]{100, 40, 100};
        f.link.done(ids.get(2), "ok: arrived near 100 40 100");
        f.run(20);
        assertEquals("mine coal_ore 40 dig", take(f, ids));
        // the step cut short again, and its walk back too: 3 tries in a row are all, the chain ends saying why
        f.run(20);
        f.pos = new int[]{100, 53, 200};
        f.link.done(ids.get(3), "stopped: low health, getting away from creeper");
        f.run(260);
        assertEquals("goto 100 40 100", take(f, ids));
        f.link.done(ids.get(4), "stopped: low health, getting away from creeper");
        f.run(20);
        assertFalse(f.chains.running(), f.ran.toString());
        assertTrue(f.whispers.get(f.whispers.size() - 1).contains("the walk back was cut short too"), f.whispers.toString());
    }
}
