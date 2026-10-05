package io.github.mojolowjo.entropybot.engine;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WatchSettingsTest {
    private static final WatchSettings.Values D = WatchSettings.Values.DEFAULTS;

    @Test
    void defaults() {
        assertEquals(new WatchSettings.Values(4f, 4, true, true), D);
        assertEquals(D, WatchSettings.parse(null).values());
        assertNull(WatchSettings.parse(null).note());
        assertEquals(D, WatchSettings.parse("  ").values());
    }

    @Test
    void roundTrip() {
        WatchSettings.Values v = new WatchSettings.Values(2.5f, 12, false, false);
        WatchSettings.Parsed p = WatchSettings.parse(WatchSettings.toJson(v));
        assertEquals(v, p.values());
        assertNull(p.note());
    }

    @Test
    void brokenFileGivesTheDefaultsWithANote() {
        for (String bad : new String[]{"{not json", "[1,2]", "\"text\"", "{\"distance\": 3,"}) {
            WatchSettings.Parsed p = WatchSettings.parse(bad);
            assertEquals(D, p.values(), bad);
            assertNotNull(p.note(), bad);
        }
    }

    @Test
    void aBadValueFallsBackAloneAndIsNamed() {
        WatchSettings.Parsed p = WatchSettings.parse("{\"distance\": 99, \"tunnelHeight\": 7, \"dollhouse\": \"yes\", \"steer\": false}");
        assertEquals(new WatchSettings.Values(4f, 7, true, false), p.values());
        assertTrue(p.note().contains("distance"));
        assertTrue(p.note().contains("dollhouse"));
        WatchSettings.Parsed q = WatchSettings.parse("{\"tunnelHeight\": 0.5, \"distance\": {}}");
        assertEquals(D, q.values());
        assertTrue(q.note().contains("tunnelHeight") && q.note().contains("distance"));
    }

    @Test
    void missingFieldsKeepTheirDefaults() {
        WatchSettings.Parsed p = WatchSettings.parse("{\"distance\": 6}");
        assertEquals(new WatchSettings.Values(6f, 4, true, true), p.values());
        assertNull(p.note());
    }
}
