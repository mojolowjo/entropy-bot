package io.github.mojolowjo.entropybot.events;

import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class EventRingTest {
    @Test
    void sequenceNumbersAndSince() {
        EventRing r = new EventRing();
        assertEquals("[]", r.since(0, 10));
        r.setTick(5);
        assertEquals(1, r.push("path", "CALC_STARTED", null));
        assertEquals(2, r.push("log", "hello", null));
        JsonArray a = JsonParser.parseString(r.since(0, 10)).getAsJsonArray();
        assertEquals(2, a.size());
        assertEquals("CALC_STARTED", a.get(0).getAsJsonObject().get("text").getAsString());
        assertEquals(5, a.get(0).getAsJsonObject().get("tick").getAsInt());
        assertEquals(1, JsonParser.parseString(r.since(1, 10)).getAsJsonArray().size());
        assertEquals("[]", r.since(2, 10));
        assertEquals(2, r.lastSeq());
    }

    @Test
    void ringDropsTheOldestAndMaxLimits() {
        EventRing r = new EventRing();
        for (int i = 0; i < EventRing.CAPACITY + 20; i++) r.push("path", "e" + i, null);
        JsonArray a = JsonParser.parseString(r.since(0, 0)).getAsJsonArray();
        assertEquals(EventRing.CAPACITY, a.size());
        assertEquals("e20", a.get(0).getAsJsonObject().get("text").getAsString());
        assertEquals(3, JsonParser.parseString(r.since(0, 3)).getAsJsonArray().size());
    }
}
