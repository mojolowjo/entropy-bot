package io.github.mojolowjo.entropybot.summary;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 0.23.6 daily summary: the tally and its line. */
class DaySummaryTest {
    @Test
    void aggregates() {
        DaySummary d = new DaySummary();
        d.reset(0);
        d.bag(Map.of("minecraft:cobblestone", 10), Map.of("minecraft:cobblestone", 30, "minecraft:raw_iron", 4), false);
        // a chest open: what rose was taken, what fell was deposited
        d.bag(Map.of("minecraft:cobblestone", 30, "minecraft:raw_iron", 4), Map.of("minecraft:raw_iron", 4, "minecraft:bread", 8), true);
        d.job("mine", "ok: mined 4 iron_ore");
        d.job("stripmine", "error: blocked by water at 1 2 3");
        d.job("restore", "ok: put back 7 blocks");
        d.job("dig", "done digging; put back 1 block");
        d.death();
        d.need(true);
        d.need(false);
        d.walked(3);
        d.walked(4.5);
        d.walked(200);          // a teleport
        for (String n : new String[]{"food", "upkeep", "upkeep", "gear", "upkeep", "need:torch", "food"}) d.pick(n);
        String t = d.text("day 3");
        assertTrue(t.startsWith("summary day 3: gathered 24 (20 cobblestone, 4 raw_iron)"), t);
        assertTrue(t.contains("deposited 30"), t);
        assertTrue(t.contains("jobs 3 done, 1 failed"), t);
        assertTrue(t.contains("deaths 1"), t);
        assertTrue(t.contains("needs 1 met, 1 unmet"), t);
        assertTrue(t.contains("put back 8 blocks"), t);
        assertTrue(t.contains("walked 8 blocks"), t);
        assertTrue(t.endsWith("brain's top picks: upkeep x3, food x2, gear x1"), t);
        JsonObject j = d.json("day 3", 5);
        assertEquals(1, j.get("jobsFailed").getAsInt());
        assertEquals(30, j.getAsJsonObject("deposited").get("minecraft:cobblestone").getAsInt());
        d.reset(10);
        assertTrue(d.text("day 4").contains("gathered 0, deposited 0; jobs 0 done"), d.text("day 4"));
    }

    @Test
    void dawn() {
        assertTrue(DaySummary.dawn("n12", "d13"));
        assertFalse(DaySummary.dawn("d12", "d12"));
        assertFalse(DaySummary.dawn("d12", "n12"));
        assertFalse(DaySummary.dawn(null, "d1"));
    }
}
