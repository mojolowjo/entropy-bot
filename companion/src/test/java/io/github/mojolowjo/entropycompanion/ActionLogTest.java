package io.github.mojolowjo.entropycompanion;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.*;

/** 0.4.0: the action log's event shapes, buffer, files (rotation, caps), the uploader (batch, backoff, resend) and privacy. */
class ActionLogTest {
    static final long DAY = 86_400_000L;
    static final long T0 = 20_000 * DAY + 3_600_000L;        // 01:00 UTC

    static JsonObject parse(String line) { return JsonParser.parseString(line).getAsJsonObject(); }

    static ActionLog log() {
        ActionLog l = new ActionLog();
        l.context(24000L * 12 + 13000, "minecraft:overworld", 10.7, 64.2, -3.5);
        return l;
    }

    @Test
    void eventHasTheCommonFields() {
        ActionLog l = log();
        l.add(l.ev("break", T0).str("block", "minecraft:stone").str("tool", "minecraft:iron_pickaxe"));
        l.add(l.ev("eat", T0 + 5).str("item", "minecraft:bread"));
        List<String> lines = l.drain();
        assertEquals(2, lines.size());
        JsonObject a = parse(lines.get(0)), b = parse(lines.get(1));
        assertEquals(T0, a.get("t").getAsLong());
        assertEquals(0, a.get("seq").getAsLong());
        assertEquals(1, b.get("seq").getAsLong());
        assertTrue(a.get("session").getAsString().matches("[a-z0-9]{8}"));
        assertEquals("break", a.get("type").getAsString());
        assertEquals(12, a.getAsJsonObject("game").get("day").getAsLong());
        assertEquals(13000, a.getAsJsonObject("game").get("tod").getAsLong());
        assertEquals("minecraft:overworld", a.get("dim").getAsString());
        assertEquals(10, a.get("x").getAsInt());
        assertEquals(-4, a.get("z").getAsInt());
        assertEquals("minecraft:iron_pickaxe", a.get("tool").getAsString());
        assertTrue(l.drain().isEmpty());
    }

    @Test
    void newSessionRestartsSeq() {
        ActionLog l = log();
        l.ev("pos", T0);
        String s1 = l.session();
        l.newSession();
        assertNotEquals(s1, l.session());
        l.add(l.ev("join", T0));
        assertEquals(0, parse(l.drain().get(0)).get("seq").getAsLong());
    }

    @Test
    void escapingAndCountsAndDecimals() {
        ActionLog l = log();
        Map<String, Integer> in = new LinkedHashMap<>();
        in.put("minecraft:coal", 12);
        l.add(l.ev("mark", T0).str("note", "a \"quoted\"\nline\\").counts("in", in).dec("health", 13.25).bool("quick", true));
        JsonObject o = parse(l.drain().get(0));
        assertEquals("a \"quoted\"\nline\\", o.get("note").getAsString());
        assertEquals(12, o.getAsJsonObject("in").get("minecraft:coal").getAsInt());
        assertEquals(13.2, o.get("health").getAsDouble(), 0.11);
        assertTrue(o.get("quick").getAsBoolean());
    }

    @Test
    void bufferIsBounded() {
        ActionLog l = log();
        for (int i = 0; i < ActionLog.BUFFER_MAX + 50; i++) l.add(l.ev("pos", T0));
        assertEquals(ActionLog.BUFFER_MAX, l.buffered());
        assertEquals(50, l.dropped);
        assertEquals(50, parse(l.drain().get(0)).get("seq").getAsLong());   // the oldest went first
    }

    @Test
    void hungerCrossings() {
        assertEquals(18, ActionLog.hungerCrossed(20, 18));
        assertEquals(-1, ActionLog.hungerCrossed(20, 19));
        assertEquals(6, ActionLog.hungerCrossed(7, 6));
        assertEquals(14, ActionLog.hungerCrossed(14, 15));
        assertEquals(-1, ActionLog.hungerCrossed(-1, -1));
    }

    @Test
    void chestDiff() {
        Map<String, Integer> before = Map.of("minecraft:cobblestone", 64, "minecraft:coal", 5), after = Map.of("minecraft:coal", 5, "minecraft:bread", 8);
        Map<String, Integer> in = new LinkedHashMap<>(), out = new LinkedHashMap<>();
        ActionLog.diff(before, after, in, out);
        assertEquals(Map.of("minecraft:cobblestone", 64), in);
        assertEquals(Map.of("minecraft:bread", 8), out);
    }

    @Test
    void privacyWorldHashedNeverTheName() {
        String h = ActionLog.hash("Friend's Server");
        assertTrue(h.matches("[0-9a-f]{12}"));
        assertEquals(h, ActionLog.hash("friend's server "));
        assertNotEquals(h, ActionLog.hash("other"));
        ActionLog l = log();
        l.add(l.ev("join", T0).str("world", ActionLog.hash("example.tun.host:12345")));
        String line = l.drain().get(0);
        assertFalse(line.contains("example") || line.contains("12345"));
    }

    @Test
    void privacyTheMcSideHasNoOtherPlayersOrChat() throws IOException {
        // the Minecraft side is not unit-testable; this pins its rules in the source: players only as a count, the
        // server list name hashed (never ServerData.ip), chat only from the owner's /bot path
        String src = Files.readString(Path.of("src/main/java/io/github/mojolowjo/entropycompanion/ActionLogMc.java"));
        assertFalse(src.contains("sd.ip"), "never the server address");
        assertFalse(src.contains("getGameProfile") || src.contains("getName().getString()") || src.contains("getScoreboardName"), "never a player's name");
        assertFalse(src.contains("ClientChatReceivedEvent"), "never other players' chat");
        assertTrue(src.contains("ActionLog.hash(name)"));
        assertTrue(src.contains("instanceof Player"), "attacks on players are not logged");
    }

    @Test
    void defaultConfigHasNoAddressAndLogOn() {
        CompanionConfig c = new CompanionConfig();
        assertTrue(c.actionLog);
        assertEquals("", c.url);
        assertEquals("", c.key);
    }

    // ---- files ----

    @Test
    void storeWritesDailyFiles(@TempDir Path dir) throws IOException {
        LogStore s = new LogStore(dir, ZoneOffset.UTC, LogStore.CAP_BYTES);
        s.append(List.of("{\"a\":1}", "{\"a\":2}"), T0);
        s.append(List.of("{\"a\":3}"), T0 + DAY);
        assertEquals(2, s.files().size());
        assertEquals(2, s.linesToday(T0));
        assertEquals(1, s.linesToday(T0 + DAY));
        assertTrue(Files.exists(dir.resolve(s.day(T0) + ".jsonl")));
    }

    @Test
    void storeKeeps30Days(@TempDir Path dir) throws IOException {
        for (int d = 0; d < 35; d++) Files.writeString(dir.resolve(new LogStore(dir, ZoneOffset.UTC, 1).day(T0 - d * DAY) + ".jsonl"), "{}\n");
        LogStore s = new LogStore(dir, ZoneOffset.UTC, LogStore.CAP_BYTES);
        s.append(List.of("{}"), T0);
        assertEquals(LogStore.KEEP_DAYS, s.files().size());
        assertEquals(s.day(T0 - 29 * DAY) + ".jsonl", s.files().get(0).getFileName().toString());
    }

    @Test
    void storeCapDropsOldestThenNewLines(@TempDir Path dir) throws IOException {
        LogStore s = new LogStore(dir, ZoneOffset.UTC, 100);
        Files.writeString(dir.resolve(s.day(T0 - DAY) + ".jsonl"), "x".repeat(80) + "\n");
        s.append(List.of("y".repeat(40)), T0);                   // over the cap: yesterday goes
        assertEquals(1, s.files().size());
        s.append(List.of("z".repeat(40), "w".repeat(40)), T0);   // 41 + 41 + 41 > 100: the last one is dropped
        assertEquals(1, s.dropped);
        assertEquals(2, s.linesToday(T0));
    }

    // ---- uploader ----

    static final class FakePoster implements LogUploader.Poster {
        final List<String> bodies = new ArrayList<>();
        int status = 200;
        boolean down;

        public int post(byte[] gz) throws IOException {
            if (down) throw new IOException("connect refused");
            try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(gz))) {
                bodies.add(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
            return status;
        }
    }

    @Test
    void uploaderSendsBatchesAndAdvances(@TempDir Path dir) {
        LogStore s = new LogStore(dir, ZoneOffset.UTC, LogStore.CAP_BYTES);
        List<String> notes = new ArrayList<>();
        LogUploader u = new LogUploader(s, notes::add);
        FakePoster p = new FakePoster();
        s.append(List.of("{\"seq\":0}", "{\"seq\":1}"), T0);
        u.step(T0, true, p);
        assertEquals(List.of("{\"seq\":0}\n{\"seq\":1}\n"), p.bodies);
        u.step(T0 + 1000, true, p);                         // not due yet
        s.append(List.of("{\"seq\":2}"), T0 + 2000);
        u.step(T0 + 11_000, true, p);
        assertEquals("{\"seq\":2}\n", p.bodies.get(1));
        u.step(T0 + 30_000, true, p);                        // nothing new
        assertEquals(2, p.bodies.size());
        assertEquals(0, u.queuedBytes());
        u.step(T0 + 60_000, false, p);                       // not set up: nothing
        assertEquals(2, p.bodies.size());
    }

    @Test
    void uploaderBacksOffKeepsAndResends(@TempDir Path dir) {
        LogStore s = new LogStore(dir, ZoneOffset.UTC, LogStore.CAP_BYTES);
        List<String> notes = new ArrayList<>();
        LogUploader u = new LogUploader(s, notes::add);
        FakePoster p = new FakePoster();
        p.down = true;
        s.append(List.of("{\"seq\":0}"), T0);
        long t = T0;
        for (int i = 0; i < 3; i++) {
            u.step(t, true, p);
            t += LogUploader.INTERVAL_MS;
        }
        assertTrue(u.failing());
        assertEquals(1, notes.size(), "one notice when it starts failing");
        assertTrue(notes.get(0).contains("keeping events on this PC"));
        assertTrue(u.queuedBytes() > 0);
        u.step(t, true, p);                                  // 10 s later: still backing off (30 s)
        assertEquals(3, u.failed);
        p.down = false;
        s.append(List.of("{\"seq\":1}"), t);
        u.step(t + Backoff.FAILING_INTERVAL_MS, true, p);
        assertEquals(List.of("{\"seq\":0}\n{\"seq\":1}\n"), p.bodies);
        assertFalse(u.failing());
        assertEquals(2, notes.size());
        // a restart: a new uploader reads the cursor and sends nothing twice
        LogUploader u2 = new LogUploader(s, notes::add);
        u2.step(t + 100_000, true, p);
        assertEquals(1, p.bodies.size());
    }

    @Test
    void uploaderRefusedKeyIsAFailure(@TempDir Path dir) {
        LogStore s = new LogStore(dir, ZoneOffset.UTC, LogStore.CAP_BYTES);
        LogUploader u = new LogUploader(s, m -> {});
        FakePoster p = new FakePoster();
        p.status = 403;
        s.append(List.of("{}"), T0);
        u.step(T0, true, p);
        assertTrue(u.lastError.contains("403"));
        assertTrue(u.queuedBytes() > 0);
    }

    @Test
    void uploaderWaitsForAHalfLineAndCrossesDays(@TempDir Path dir) throws IOException {
        LogStore s = new LogStore(dir, ZoneOffset.UTC, LogStore.CAP_BYTES);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(s.day(T0) + ".jsonl"), "{\"a\":1}\n{\"half");
        Files.writeString(dir.resolve(s.day(T0 + DAY) + ".jsonl"), "{\"b\":1}\n");
        LogUploader u = new LogUploader(s, m -> {});
        FakePoster p = new FakePoster();
        u.step(T0, true, p);
        u.step(T0 + 20_000, true, p);
        assertEquals(List.of("{\"a\":1}\n", "{\"b\":1}\n"), p.bodies);
    }

    @Test
    void uploaderBatchIsCapped(@TempDir Path dir) {
        LogStore s = new LogStore(dir, ZoneOffset.UTC, LogStore.CAP_BYTES);
        List<String> many = new ArrayList<>();
        for (int i = 0; i < LogUploader.BATCH_LINES + 10; i++) many.add("{\"seq\":" + i + "}");
        s.append(many, T0);
        LogUploader u = new LogUploader(s, m -> {});
        FakePoster p = new FakePoster();
        u.step(T0, true, p);
        assertEquals(LogUploader.BATCH_LINES, p.bodies.get(0).split("\n").length);
        u.step(T0 + 20_000, true, p);
        assertEquals(10, p.bodies.get(1).split("\n").length);
    }
}
