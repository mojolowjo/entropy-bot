package io.github.mojolowjo.entropybot.commands;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.mojolowjo.entropybot.commands.DebugRules.Box;
import io.github.mojolowjo.entropybot.commands.DebugRules.Source;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** B7e (E1): the debug verbs' rules: owner and channel, the 4096 cap, the time words, the slice JSON. */
class DebugVerbTest {
    static final ZoneId Z = ZoneId.of("Europe/Berlin");
    static final long NOW = ZonedDateTime.of(LocalDate.of(2026, 10, 4), LocalTime.of(14, 30, 0), Z).toInstant().toEpochMilli();

    @Test
    void onlyTheOwnerAndNeverFromAPm() {
        assertEquals(DebugRules.PM_REFUSAL, DebugRules.gate(Source.PM, true, "o"), "even the owner: PMs travel through server chat");
        assertEquals(DebugRules.PM_REFUSAL, DebugRules.gate(Source.PM, false, "o"));
        assertEquals("only o can use debug", DebugRules.gate(Source.LOCAL, false, "o"));
        assertNull(DebugRules.gate(Source.LOCAL, true, "o"), "bridge.ps1 do debug ..., the dashboard");
        assertTrue(DebugRules.PM_REFUSAL.startsWith("debug only from the laptop or the dashboard"));
    }

    @Test
    void theListNamesEveryVerb() {
        for (String v : new String[]{"gui", "inv", "block x y z", "blocks x1 y1 z1 x2 y2 z2 [at <time>]", "baritone", "events [n]", "guard x y z",
                "changes x y z [r] [since <time>]", "trail [minutes]", "incident [n]"}) {
            assertTrue(DebugRules.LIST.contains(v), v);
        }
    }

    @Test
    void theCap() {
        Box b = DebugRules.box(DebugRules.words("blocks 0 0 0 15 15 15"));
        assertEquals(4096, b.cells(), "16 x 16 x 16 is just allowed");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> DebugRules.box(DebugRules.words("blocks 0 0 0 15 16 15")));
        assertTrue(e.getMessage().startsWith("that box has 4352 blocks (16 x 17 x 16); 4096 at most"), e.getMessage());
        assertThrows(IllegalArgumentException.class, () -> DebugRules.box(DebugRules.words("blocks -30000000 0 -30000000 30000000 300 30000000")), "no overflow");
        assertEquals(DebugRules.BLOCKS_USAGE, assertThrows(IllegalArgumentException.class, () -> DebugRules.box(DebugRules.words("blocks 1 2 3"))).getMessage());
        assertEquals(DebugRules.BLOCKS_USAGE, assertThrows(IllegalArgumentException.class, () -> DebugRules.box(DebugRules.words("blocks a 2 3 4 5 6"))).getMessage());
    }

    @Test
    void boxCornersInAnyOrderAndTheSliceOrder() {
        Box b = DebugRules.box(DebugRules.words("blocks 5 70 -3 3 68 -1"));
        assertEquals(new Box(3, 68, -3, 5, 70, -1), b);
        assertEquals(27, b.cells());
        assertEquals(0, b.index(3, 68, -3));
        assertEquals(1, b.index(4, 68, -3), "x fastest");
        assertEquals(3, b.index(3, 68, -2), "then z");
        assertEquals(9, b.index(3, 69, -3), "then y (Recorder.Slice's order)");
    }

    @Test
    void timeWords() {
        long today1230 = ZonedDateTime.of(LocalDate.of(2026, 10, 4), LocalTime.of(12, 30), Z).toInstant().toEpochMilli();
        assertEquals(today1230, DebugRules.time("12:30", NOW, Z), "HH:mm is today, local time");
        assertEquals(NOW - 600_000L, DebugRules.time("-10m", NOW, Z));
        assertEquals(NOW - 7_200_000L, DebugRules.time("-2h", NOW, Z));
        assertEquals(NOW - 30_000L, DebugRules.time("-30s", NOW, Z));
        assertEquals(NOW - 86_400_000L, DebugRules.time("-1d", NOW, Z));
        assertEquals(1759600000000L, DebugRules.time("1759600000000", NOW, Z), "epoch ms");
        assertEquals(NOW, DebugRules.time("now", NOW, Z));
        for (String bad : new String[]{"25:00", "12:61", "yesterday", "-10", "10m", "", "123"}) {
            assertThrows(IllegalArgumentException.class, () -> DebugRules.time(bad, NOW, Z), bad);
        }
        assertEquals("12:30:00", DebugRules.timeText(today1230, NOW, Z));
        assertEquals("10-03 14:30:00", DebugRules.timeText(NOW - 86_400_000L, NOW, Z));
    }

    @Test
    void theWordAfterAKey() {
        List<String> w = DebugRules.words("blocks 0 0 0 1 1 1 at -10m");
        assertEquals("-10m", DebugRules.after(w, "at"));
        assertNull(DebugRules.after(DebugRules.words("blocks 0 0 0 1 1 1"), "at"));
        assertThrows(IllegalArgumentException.class, () -> DebugRules.after(DebugRules.words("changes 1 2 3 since"), "since"));
        assertEquals(8, DebugRules.optInt(DebugRules.words("changes 1 2 3"), 4, 8, 0, 64, "u"));
        assertEquals(64, DebugRules.optInt(DebugRules.words("changes 1 2 3 500"), 4, 8, 0, 64, "u"), "kept within the cap");
        assertThrows(IllegalArgumentException.class, () -> DebugRules.optInt(DebugRules.words("trail x"), 1, 10, 1, 60, "u"));
    }

    @Test
    void theSliceJson() {
        Box b = new Box(0, 0, 0, 1, 0, 1);
        String json = DebugRules.sliceJson(b, new String[]{"minecraft:stone", "minecraft:air", null, "minecraft:stone"}, "exact");
        JsonObject o = JsonParser.parseString(json).getAsJsonObject();
        assertEquals("[0,0,0,1,0,1]", o.get("box").toString());
        assertEquals("y,z,x", o.get("order").getAsString());
        assertEquals("[\"minecraft:stone\",\"minecraft:air\"]", o.get("palette").toString());
        assertEquals("[0,1,-1,0]", o.get("cells").toString(), "-1 where nothing is known");
        assertEquals("exact", o.get("note").getAsString());
    }
}
