package io.github.mojolowjo.entropybot.commands;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.junit.jupiter.api.Assertions.*;

/** B7e N item 9: settings.rules for the dashboard's rules card (next fire times). */
class RulesBlockTest {
    static final ZoneId Z = ZoneId.of("Europe/Berlin");

    static long at(int day, int h, int m) {
        return ZonedDateTime.of(2026, 10, day, h, m, 0, 0, Z).toInstant().toEpochMilli();
    }

    @Test
    void listsRulesWithNextTimes() {
        long now = at(4, 12, 0);
        JsonObject brain = JsonParser.parseString("{\"rules\":["
                + "{\"kind\":\"every\",\"arg\":\"30m\",\"text\":\"farm then deposit\",\"last\":" + (now - 600_000) + "},"
                + "{\"kind\":\"every\",\"arg\":\"2h\",\"text\":\"pots\",\"last\":" + now + "},"
                + "{\"kind\":\"at\",\"arg\":\"06:30\",\"text\":\"pots\",\"last\":" + at(4, 6, 30) + "},"
                + "{\"kind\":\"at\",\"arg\":\"18:00\",\"text\":\"deposit\",\"last\":" + at(3, 18, 0) + "},"
                + "{\"kind\":\"when\",\"arg\":\"full\",\"text\":\"deposit\",\"last\":0,\"refused\":\"error: too many steps\"},"
                + "{\"kind\":\"when\",\"arg\":\"idle 10m\",\"text\":\"restock\",\"last\":0}]}").getAsJsonObject();
        JsonArray a = RulesBlock.build(brain, now, Z);
        assertEquals(6, a.size());
        JsonObject r1 = a.get(0).getAsJsonObject();
        assertEquals(1, r1.get("n").getAsInt());
        assertEquals("every", r1.get("kind").getAsString());
        assertEquals("farm then deposit", r1.get("text").getAsString());
        assertEquals(now + 20 * 60_000, r1.get("next").getAsLong());
        assertEquals(now + 2 * 3600_000, a.get(1).getAsJsonObject().get("next").getAsLong());
        assertEquals(at(5, 6, 30), a.get(2).getAsJsonObject().get("next").getAsLong(), "ran today: tomorrow");
        assertEquals(at(4, 18, 0), a.get(3).getAsJsonObject().get("next").getAsLong(), "later today");
        assertTrue(a.get(4).getAsJsonObject().get("next").isJsonNull(), "when full waits for the bag");
        assertEquals("error: too many steps", a.get(4).getAsJsonObject().get("refused").getAsString());
        assertTrue(a.get(5).getAsJsonObject().get("next").isJsonNull());
    }

    @Test
    void dueNowAndOddInput() {
        long now = at(4, 7, 0);
        // 06:30 not run yet today: due (in the past)
        assertEquals(at(4, 6, 30), RulesBlock.next("at", "06:30", at(3, 6, 30), now, Z));
        assertNull(RulesBlock.next("at", "junk", 0, now, Z));
        assertNull(RulesBlock.next("every", "", 0, now, Z));
        assertEquals(0, RulesBlock.build(new JsonObject(), now, Z).size());
        assertEquals(0, RulesBlock.build(null, now, Z).size());
        assertEquals(1, RulesBlock.build(JsonParser.parseString("{\"rules\":[1,{\"kind\":\"every\",\"arg\":\"5m\",\"text\":\"x\"}]}").getAsJsonObject(), now, Z).size());
    }
}
