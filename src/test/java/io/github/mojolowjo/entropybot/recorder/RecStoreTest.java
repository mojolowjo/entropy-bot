package io.github.mojolowjo.entropybot.recorder;

import io.github.mojolowjo.entropybot.recorder.Recorder.Change;
import io.github.mojolowjo.entropybot.recorder.Recorder.Incident;
import io.github.mojolowjo.entropybot.recorder.Recorder.TrailPoint;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class RecStoreTest {
    static final String OW = "minecraft:overworld";
    static final long T0 = 1_759_600_000_000L;
    static final long H = 3_600_000L;

    @TempDir
    Path dir;

    static Change ch(long at, int x, int y, int z, String from, String to, boolean bot) {
        return new Change(at, OW, x, y, z, from, to, bot);
    }

    @Test
    void changesComeBackNewestFirstFromMemoryAndFromTheFilesAfterARestart() {
        AtomicLong now = new AtomicLong(T0);
        RecStore s = new RecStore(dir, now::get, RecStore.DEFAULT_CAP);
        s.addChange(ch(T0 + 1, 10, 64, 10, "minecraft:stone", "minecraft:air", true));
        s.addChange(ch(T0 + 2, 11, 64, 10, "minecraft:air", "minecraft:cobblestone", false));
        s.addChange(ch(T0 + 3, 90, 64, 10, "minecraft:dirt", "minecraft:air", false));
        s.addChange(new Change(T0 + 4, "minecraft:the_nether", 10, 64, 10, "minecraft:netherrack", "minecraft:air", false));
        List<Change> got = s.changes(OW, 10, 64, 10, 4, 0, 10);
        assertEquals(2, got.size());
        assertEquals(11, got.get(0).x(), "newest first");
        assertTrue(got.get(1).byBot());
        assertEquals(4, s.flush());
        assertEquals(0, s.flush(), "nothing left to write");

        now.set(T0 + 10_000);
        RecStore again = new RecStore(dir, now::get, RecStore.DEFAULT_CAP);
        got = again.changes(OW, 10, 64, 10, 4, 0, 10);
        assertEquals(2, got.size(), "read back from the day file");
        assertEquals("minecraft:cobblestone", got.get(0).to());
        assertEquals("minecraft:stone", got.get(1).from());
        assertTrue(got.get(1).byBot());
        assertEquals(1, again.changes(OW, 10, 64, 10, 4, T0 + 2, 10).size(), "since is honoured");
        assertEquals(1, again.changes(OW, 10, 64, 10, 4, 0, 1).size(), "max is honoured");
    }

    @Test
    void stateIdsAndModdedIdsSurviveTheLineFormat() {
        Change c = new Change(T0, OW, -5, -60, 7, "minecraft:furnace[facing=north,lit=false]", "oritech:nickel_ore", false);
        assertEquals(c, RecStore.parseChange(RecStore.changeLine(c)));
        assertEquals("minecraft:furnace", RecStore.bare("minecraft:furnace[lit=true]"));
        assertNull(RecStore.parseChange("garbage line"));
        TrailPoint p = new TrailPoint(T0, OW, 1, 2, 3, "start travel: walking to 1 2 3");
        assertEquals(p, RecStore.parseTrail(RecStore.trailLine(p)));
        TrailPoint q = new TrailPoint(T0, OW, 1, 2, 3, null);
        assertEquals(q, RecStore.parseTrail(RecStore.trailLine(q)));
    }

    @Test
    void theTrailIsOldestFirstAndKeepsTheNewestWhenCut() {
        AtomicLong now = new AtomicLong(T0);
        RecStore s = new RecStore(dir, now::get, RecStore.DEFAULT_CAP);
        for (int i = 0; i < 10; i++) s.addTrail(new TrailPoint(T0 + i * 1000, OW, i, 64, 0, i == 3 ? "mark: test" : null));
        List<TrailPoint> t = s.trail(0, 4);
        assertEquals(List.of(6, 7, 8, 9), t.stream().map(TrailPoint::x).toList());
        s.flush();
        RecStore again = new RecStore(dir, () -> T0 + 20_000, RecStore.DEFAULT_CAP);
        again.addTrail(new TrailPoint(T0 + 20_000, OW, 99, 64, 0, null));
        t = again.trail(T0 + 2000, 100);
        assertEquals(List.of(2, 3, 4, 5, 6, 7, 8, 9, 99), t.stream().map(TrailPoint::x).toList(), "files then memory, in order");
        assertEquals("mark: test", t.get(1).note());
        t = again.trail(0, 3);
        assertEquals(List.of(8, 9, 99), t.stream().map(TrailPoint::x).toList());
    }

    @Test
    void retentionDeletesWhatIsPastKeepButNeverToday() throws Exception {
        AtomicLong now = new AtomicLong(T0);
        RecStore s = new RecStore(dir, now::get, RecStore.DEFAULT_CAP);
        s.keepHours(24);
        Files.createDirectories(dir);
        Path old = dir.resolve("changes-2020-01-01.log"), recent = dir.resolve("trail-2020-01-02.log");
        Files.writeString(old, "x\n");
        Files.writeString(recent, "x\n");
        Files.setLastModifiedTime(old, FileTime.fromMillis(T0 - 30 * H));
        Files.setLastModifiedTime(recent, FileTime.fromMillis(T0 - 2 * H));
        s.addChange(ch(T0, 1, 1, 1, "minecraft:stone", "minecraft:air", false));
        s.flush();
        Path today = dir.resolve("changes-" + s.day(T0) + ".log");
        Files.setLastModifiedTime(today, FileTime.fromMillis(T0 - 100 * H));
        Path base = dir.resolve("base/minecraft_overworld/0.0.gz");
        Files.createDirectories(base.getParent());
        Files.writeString(base, "x");
        Files.setLastModifiedTime(base, FileTime.fromMillis(T0 - 25 * H));
        s.writeIncident(T0 - 100 * H, "stuck", "incident\nreason: stuck\n");
        Path inc = dir.resolve("incidents/inc-" + (T0 - 100 * H) + ".txt.gz");
        Files.setLastModifiedTime(inc, FileTime.fromMillis(T0 - 100 * H));
        s.maintain();
        assertFalse(Files.exists(old), "past keep");
        assertTrue(Files.exists(recent));
        assertTrue(Files.exists(today), "today's file stays whatever its time");
        assertFalse(Files.exists(base), "an old baseline goes too");
        assertTrue(Files.exists(inc), "incidents stay longer (7 days at least)");
        Files.setLastModifiedTime(inc, FileTime.fromMillis(T0 - 8 * 24 * H));
        s.maintain();
        assertFalse(Files.exists(inc));
        assertTrue(s.diskBytes() > 0);
    }

    @Test
    void theHardCapDeletesTheOldestFirstAndIncidentsLast() throws Exception {
        AtomicLong now = new AtomicLong(T0);
        RecStore s = new RecStore(dir, now::get, 10_000);
        Files.createDirectories(dir.resolve("base/minecraft_overworld"));
        String kb = "x".repeat(4000);
        java.util.Random rnd = new java.util.Random(1);
        StringBuilder noise = new StringBuilder();
        for (int i = 0; i < 4000; i++) noise.append((char) ('a' + rnd.nextInt(26)));
        Path a = dir.resolve("changes-2020-01-01.log"), b = dir.resolve("base/minecraft_overworld/1.1.gz"), c = dir.resolve("trail-2020-01-03.log");
        Files.writeString(a, kb);
        Files.writeString(b, kb);
        Files.writeString(c, kb);
        Files.setLastModifiedTime(a, FileTime.fromMillis(T0 - 3 * H));
        Files.setLastModifiedTime(b, FileTime.fromMillis(T0 - 2 * H));
        Files.setLastModifiedTime(c, FileTime.fromMillis(T0 - 1 * H));
        s.writeIncident(T0 - 5 * H, "old", "reason: old\n" + noise);
        Path inc = dir.resolve("incidents/inc-" + (T0 - 5 * H) + ".txt.gz");
        Files.setLastModifiedTime(inc, FileTime.fromMillis(T0 - 5 * H));
        s.maintain();
        assertFalse(Files.exists(a), "the oldest day file goes first");
        assertFalse(Files.exists(b), "then the next oldest");
        assertTrue(Files.exists(c), "under 90 % of the cap: the rest stays");
        assertTrue(Files.exists(inc), "the incident is older but goes last");
        assertTrue(s.diskBytes() <= 9_000, "now " + s.diskBytes());
        assertFalse(s.capped());

        // today's file alone over the cap: recording goes to memory only
        RecStore tiny = new RecStore(dir.resolve("tiny"), now::get, 100);
        Files.createDirectories(dir.resolve("tiny"));
        Files.writeString(dir.resolve("tiny/changes-" + tiny.day(T0) + ".log"), kb);
        tiny.maintain();
        assertTrue(tiny.capped());
        tiny.addChange(ch(T0, 1, 1, 1, "minecraft:stone", "minecraft:air", false));
        assertEquals(0, tiny.flush(), "nothing queued while capped");
        assertEquals(1, tiny.changes(OW, 1, 1, 1, 0, 0, 5).size(), "still in memory");
    }

    @Test
    void incidentsAreNumberedOldestFirstAndListedNewestFirst() {
        RecStore s = new RecStore(dir, () -> T0, RecStore.DEFAULT_CAP);
        s.writeIncident(T0 - 2000, "first", "incident\nreason: first\nbody one\n");
        s.writeIncident(T0 - 1000, "second", "incident\nreason: second\nbody two\n");
        List<Incident> l = s.incidents(10);
        assertEquals(2, l.size());
        assertEquals(2, l.get(0).n());
        assertEquals("second", l.get(0).reason());
        assertEquals(1, l.get(1).n());
        assertTrue(s.incident(1).contains("body one"));
        assertTrue(s.incident(2).contains("body two"));
        assertNull(s.incident(3));
        assertNull(s.incident(0));
        // a fresh store (another session) reads the reason from the file
        RecStore again = new RecStore(dir, () -> T0, RecStore.DEFAULT_CAP);
        assertEquals("first", again.incidents(5).get(1).reason());
    }

    @Test
    void settingsFileRoundTrip() {
        RecStore s = new RecStore(dir, () -> T0, RecStore.DEFAULT_CAP);
        assertNull(s.readSettings());
        s.writeSettings("{\"preset\":\"max\"}");
        assertEquals("max", RecorderSettings.fromJson(s.readSettings()).preset);
    }
}
