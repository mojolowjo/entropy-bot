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

    /** The job the chain started last (its request id noted for the test to end it), by its text. */
    static String take(CommandsTest.Fake f, List<Long> ids) {
        var r = f.takeNext();
        ids.add(r.id);
        return r.text;
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

}
