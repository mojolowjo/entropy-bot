package io.github.mojolowjo.entropybot.engine;

import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.util.ArrayDeque;

import static org.junit.jupiter.api.Assertions.*;

class ReconnectTest {
    static final long H = 3_600_000L, MIN = 60_000L;

    @Test
    void waitsOneFiveAndFifteenMinutesThenEveryHalfHour() {
        ArrayDeque<Long> recent = new ArrayDeque<>();
        assertFalse(ReconnectRules.due(1199, 0, recent, 0, 0));
        assertTrue(ReconnectRules.due(1200, 0, recent, 0, 0));
        assertFalse(ReconnectRules.due(5999, 1, recent, 0, 0));
        assertTrue(ReconnectRules.due(6000, 1, recent, 0, 0));
        assertTrue(ReconnectRules.due(18000, 2, recent, 0, 0));
        // T4: the night of Oct 4 it stopped here (06:48, 06:53, 07:08); now every 30 minutes
        assertFalse(ReconnectRules.due(35999, 3, recent, H, 0));
        assertTrue(ReconnectRules.due(36000, 3, recent, H, 0));
        assertTrue(ReconnectRules.due(36000, 40, recent, 20 * H, 0));
    }

    @Test
    void givesUpAfterTwentyFourHours() {
        ArrayDeque<Long> recent = new ArrayDeque<>();
        assertTrue(ReconnectRules.due(36000, 10, recent, 24 * H - 1, 0));
        assertFalse(ReconnectRules.due(36000, 10, recent, 24 * H, 0));
        assertTrue(ReconnectRules.due(36000, 10, recent, 24 * H, 5 * H), "the 24 h count from the first time out");
        assertEquals(-1, ReconnectRules.nextTryMs(0, 10, recent, 24 * H, 0));
        assertEquals(-1, ReconnectRules.nextTryMs(0, 10, recent, 23 * H + 45 * MIN, 0), "the next one would fall after it");
    }

    @Test
    void atMostThreeAnHour() {
        ArrayDeque<Long> recent = new ArrayDeque<>();
        recent.add(1_000L);
        recent.add(2_000L);
        recent.add(3_000L);
        assertFalse(ReconnectRules.due(1200, 0, recent, 10_000L, 0));
        assertTrue(ReconnectRules.due(1200, 0, recent, 3_700_000L, 0), "an hour later the old ones no longer count");
        // the real schedule: tries at 1, 6, 21 min; the 4th (30 min later, at 51) waits for the hour after the first
        ArrayDeque<Long> r = new ArrayDeque<>();
        r.add(1 * MIN);
        r.add(6 * MIN);
        r.add(21 * MIN);
        assertFalse(ReconnectRules.due(36000, 3, r, 51 * MIN, 0));
        assertEquals(61 * MIN + 1, ReconnectRules.nextTryMs(36000, 3, r, 51 * MIN, 0));
        assertTrue(ReconnectRules.due(36000 + 12000, 3, r, 61 * MIN + 1, 0));
    }

    @Test
    void nextTryAndTheStatusText() {
        ArrayDeque<Long> recent = new ArrayDeque<>();
        // 10 minutes of the 30 are over: 20 to go
        assertEquals(H + 20 * MIN, ReconnectRules.nextTryMs(12000, 3, recent, H, 0));
        assertEquals(MIN, ReconnectRules.nextTryMs(0, 0, recent, 0, 0));
        ZoneId utc = ZoneId.of("UTC");
        assertEquals("next try to example.org:25565 at 01:20 (try 4)",
                ReconnectRules.statusText(true, "example.org:25565", 3, H + 20 * MIN, utc));
        assertEquals("gave up on example.org:25565 after 24 h - relaunch me", ReconnectRules.statusText(true, "example.org:25565", 50, -1, utc));
        assertEquals("off", ReconnectRules.statusText(false, "example.org:25565", 0, 0, utc));
        assertEquals("no server to go back to", ReconnectRules.statusText(true, null, 0, -1, utc));
    }

    @Test
    void theLaunchServerSeedsAFirstJoinThatWasRefused() {
        assertEquals("example.org:25565", ReconnectRules.seed(null, " example.org:25565 "));
        assertEquals("played.on:1", ReconnectRules.seed("played.on:1", "example.org:25565"), "the one last played on wins");
        assertNull(ReconnectRules.seed(null, null));
        assertNull(ReconnectRules.seed(null, "  "));
    }

    @Test
    void savedReconnectOffHoldsAFreshLaunch() throws Exception {
        java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("rc");
        java.nio.file.Path f = dir.resolve("commands.json");
        assertTrue(ReconnectRules.savedFlag(f));                       // no file: on
        java.nio.file.Files.writeString(f, "{\"reconnect\": false}");
        assertFalse(ReconnectRules.savedFlag(f));
        java.nio.file.Files.writeString(f, "{\"reconnect\": true, \"routines\": {}}");
        assertTrue(ReconnectRules.savedFlag(f));
        java.nio.file.Files.writeString(f, "{broken");
        assertTrue(ReconnectRules.savedFlag(f));                       // unreadable: on (as before)
    }
}