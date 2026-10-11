package io.github.mojolowjo.entropybot.engine;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** 0.26.3: the reconnect connect timeout's pure rule (a hung "Connecting to ..." is cancelled and counted as a failed try). */
class ReconnectTimeoutTest {

    @Test void timedOutAtTheLimit() {
        assertFalse(ReconnectRules.connectTimedOut(0, 59_999, 60, false));
        assertTrue(ReconnectRules.connectTimedOut(0, 60_000, 60, false));
        assertTrue(ReconnectRules.connectTimedOut(0, 61_000, 60, false));
    }

    @Test void neverInAWorldOrWithoutAnAttempt() {
        assertFalse(ReconnectRules.connectTimedOut(0, 600_000, 60, true));
        assertFalse(ReconnectRules.connectTimedOut(-1, 600_000, 60, false));
    }

    @Test void range() {
        assertNotNull(ReconnectRules.timeoutRefusal(14));
        assertNull(ReconnectRules.timeoutRefusal(15));
        assertNull(ReconnectRules.timeoutRefusal(600));
        assertNotNull(ReconnectRules.timeoutRefusal(601));
    }

    @Test void savedValueOrDefault() {
        JsonObject o = new JsonObject();
        assertEquals(60, ReconnectRules.timeoutOf(o));
        o.addProperty(ReconnectRules.TIMEOUT_KEY, 120);
        assertEquals(120, ReconnectRules.timeoutOf(o));
        o.addProperty(ReconnectRules.TIMEOUT_KEY, 5);
        assertEquals(60, ReconnectRules.timeoutOf(o));
        o.addProperty(ReconnectRules.TIMEOUT_KEY, "soon");
        assertEquals(60, ReconnectRules.timeoutOf(o));
        assertEquals(60, ReconnectRules.timeoutOf(null));
    }

    @Test void savedFromFile() throws Exception {
        Path f = Files.createTempFile("commands", ".json");
        try {
            Files.writeString(f, "{\"reconnect\":true,\"reconnect.connectTimeoutS\":90}");
            assertEquals(90, ReconnectRules.savedTimeout(f));
            Files.writeString(f, "{broken");
            assertEquals(60, ReconnectRules.savedTimeout(f));
        } finally {
            Files.deleteIfExists(f);
        }
        assertEquals(60, ReconnectRules.savedTimeout(f));
    }

    @Test void checkText() {
        assertEquals("reconnect: the last connect attempt timed out after 60 s", ReconnectRules.timeoutText(60));
    }

    @Test void aTimedOutTryKeepsTheBackoff() {
        // the try counts (tries 1 -> the next wait is WAITS[1], 5 min) and the wait starts at the cancel
        assertEquals(6000, ReconnectRules.waitFor(1));
        assertFalse(ReconnectRules.due(5999, 1, new java.util.ArrayDeque<>(), 0, 0));
        assertTrue(ReconnectRules.due(6000, 1, new java.util.ArrayDeque<>(), 0, 0));
    }
}
