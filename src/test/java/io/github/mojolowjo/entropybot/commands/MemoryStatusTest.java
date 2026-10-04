package io.github.mojolowjo.entropybot.commands;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** B7e (E1): "memory" from the mod's stores; "memory fresh" is gone and says why. */
class MemoryStatusTest {
    static final long NOW = 1_759_600_000_000L, START = NOW - 3_600_000L;

    @Test
    void theProblemComesFromTheLoadLineOrAFreshBrokenCopy() {
        String kn = "places.json: 12 entries; chests.json: broken, loaded the backup (40 entries); rs.json: 2 readings";
        assertNull(MemoryStatus.problem("places.json", kn, -1, START));
        assertEquals("broken, loaded the backup (40 entries)", MemoryStatus.problem("chests.json", kn, NOW - 1000, START));
        assertEquals("broken, no good backup, starting empty", MemoryStatus.problem("pm.json", "pm.json: broken, no good backup, starting empty", NOW, START));
        assertEquals("broken at start, set aside, started empty", MemoryStatus.problem("caves.json", null, START + 5, START), "the stores without a backup");
        assertNull(MemoryStatus.problem("caves.json", null, START - 86_400_000L, START), "an old broken copy from another session is no problem now");
    }

    @Test
    void stateOkBackupBroken() {
        MemoryStatus.Store ok = new MemoryStatus.Store("places.json", "12 places", NOW - 5000, NOW - 600_000, -1, null);
        MemoryStatus.Store bak = new MemoryStatus.Store("chests.json", "40 chests", NOW, NOW, NOW, "broken, loaded the backup (40 entries)");
        MemoryStatus.Store empty = new MemoryStatus.Store("caves.json", "0 caves", -1, -1, NOW, "broken at start, set aside, started empty");
        assertEquals("ok", MemoryStatus.state(List.of(ok)));
        assertEquals("backup", MemoryStatus.state(List.of(ok, bak)));
        assertEquals("broken", MemoryStatus.state(List.of(bak, empty)));
    }

    @Test
    void theText() {
        MemoryStatus.Store ok = new MemoryStatus.Store("places.json", "12 places", NOW - 5000, NOW - 600_000, NOW - 3 * 86_400_000L, null);
        MemoryStatus.Store fresh = new MemoryStatus.Store("rs.json", "0 RS readings", -1, -1, -1, null);
        MemoryStatus.Store bak = new MemoryStatus.Store("chests.json", "40 chests", NOW - 1000, -1, NOW - 2000, "broken, loaded the backup (40 entries)");
        String t = MemoryStatus.text(List.of(ok, fresh), NOW, START);
        assertTrue(t.startsWith("memory: ok | places.json 12 places, saved 5s ago, backup 10m ago | rs.json 0 RS readings, not written yet"), t);
        assertTrue(t.endsWith("broken copies set aside earlier: places.broken.json (72h ago)"), t);
        t = MemoryStatus.text(List.of(ok, bak), NOW, START);
        assertTrue(t.startsWith("memory: 1 problem: chests.json broken, loaded the backup (40 entries) (the unreadable copy is chests.broken.json)"), t);
    }

    @Test
    void theStateBlockKeepsTheBridgesShape() {
        MemoryStatus.Store a = new MemoryStatus.Store("places.json", "1 places", 100, 50, -1, null);
        MemoryStatus.Store b = new MemoryStatus.Store("pm.json", "0 allowed players", 300, -1, 290, "broken, loaded the backup");
        JsonObject o = MemoryStatus.stateBlock(List.of(a, b));
        assertEquals("backup", o.get("state").getAsString());
        assertEquals("pm.json broken, loaded the backup", o.get("problem").getAsString());
        assertEquals(300, o.get("savedAt").getAsLong());
        assertEquals(50, o.get("backupAt").getAsLong());
        JsonObject clean = MemoryStatus.stateBlock(List.of(a));
        assertEquals("ok", clean.get("state").getAsString());
        assertFalse(clean.has("problem"));
    }

    @Test
    void theVerb() {
        assertEquals("S", MemoryStatus.command("", () -> "S"));
        assertEquals("S", MemoryStatus.command("status", () -> "S"));
        assertEquals(MemoryStatus.FRESH, MemoryStatus.command("FRESH", () -> "S"));
        assertTrue(MemoryStatus.FRESH.contains("gone") && MemoryStatus.FRESH.contains("*.broken.json"));
        assertEquals(MemoryStatus.USAGE, MemoryStatus.command("wipe", () -> "S"));
    }
}
