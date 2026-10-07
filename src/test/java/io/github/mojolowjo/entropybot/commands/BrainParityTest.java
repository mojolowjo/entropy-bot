package io.github.mojolowjo.entropybot.commands;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.mojolowjo.entropybot.brain.IdleList;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** B1: the brain's upkeep leaf picks what the old autominer picked (bag-full is the brain's bag need now). */
class BrainParityTest {

    static String upkeep(IdleList.Facts f) {
        List<IdleList.Pick> c = IdleList.candidates(IdleList.DEFAULT, f);
        return c.isEmpty() ? null : c.get(0).chain() + " | " + c.get(0).why();
    }

    static String old(CommandsTest.Fake f) {
        String[] d = f.chains.autominerDecide();
        return d[0] + " | " + d[1];
    }

    @Test
    void theUpkeepLeafMatchesTheAutominer() {
        // a supply short
        JsonObject mem = new JsonObject();
        CommandsTest.Fake f = CommandsTest.fake(mem);
        mem.add("autominer", new JsonObject());
        f.supplies = JsonParser.parseString("{\"minecraft:torch\":32}").getAsJsonObject();
        IdleList.Facts x = new IdleList.Facts();
        x.now = f.now;
        x.shortSupply = "torch";
        assertEquals(old(f), upkeep(x));
        // the mine ready
        f.supplies = null;
        f.mine = JsonParser.parseString("{\"x\":10,\"y\":40,\"z\":-5,\"dir\":\"north\"}").getAsJsonObject();
        x.shortSupply = null;
        x.mine = new int[]{10, 40, -5};
        x.mineReady = true;
        assertEquals(old(f), upkeep(x));
        // no mine
        f.mine = null;
        x.mine = null;
        x.mineReady = false;
        assertEquals(old(f), upkeep(x));
    }
}
