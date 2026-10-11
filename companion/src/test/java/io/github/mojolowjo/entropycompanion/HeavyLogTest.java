package io.github.mojolowjo.entropycompanion;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/** 0.5.0 heavy log: the pure helpers, the config, the store's day cap and the uploader's 2 MB gzip posts. */
class HeavyLogTest {
    static final long T0 = ActionLogTest.T0, DAY = ActionLogTest.DAY;

    @Test
    void whyWords() {
        assertEquals("bag", HeavyLog.whyWord(" Bag "));
        assertEquals("my_word", HeavyLog.whyWord("my_word"));
        assertNull(HeavyLog.whyWord("two words"));
        assertNull(HeavyLog.whyWord(""));
        assertNull(HeavyLog.whyWord("x".repeat(25)));
        assertTrue(HeavyLog.WORDS.containsAll(List.of("bag", "dark", "food", "ore", "mobs", "done")));
    }

    @Test
    void mobStringIsSortedCappedAndShort() {
        List<HeavyLog.Mob> l = new ArrayList<>();
        l.add(new HeavyLog.Mob("minecraft:skeleton", 18.4, true, 3));
        l.add(new HeavyLog.Mob("minecraft:zombie", 6.2, false, -1));
        l.add(new HeavyLog.Mob("othermod:ghoul", 9.0, true, 0));
        assertEquals("zombie,6,0,-1;othermod:ghoul,9,1,0;skeleton,18,1,3", HeavyLog.mobs(l));
        for (int i = 0; i < 30; i++) l.add(new HeavyLog.Mob("minecraft:zombie", 20 + i, true, 0));
        String s = HeavyLog.mobs(l);
        assertEquals(HeavyLog.MAX_MOBS, s.split(";").length);
        assertTrue(s.length() <= 256);
        assertEquals("", HeavyLog.mobs(List.of()));
    }

    @Test
    void sketchLetters() {
        Integer[] d = new Integer[81];
        d[0] = -5; d[1] = 0; d[2] = 4; d[3] = 7;
        String s = HeavyLog.sketch(d);
        assertEquals(81, s.length());
        assertEquals("afj.", s.substring(0, 4));
        assertEquals('.', s.charAt(80));
    }

    @Test
    void blockActivities() {
        assertEquals("mining", HeavyLog.blockActivity("minecraft:deepslate_iron_ore", false, false));
        assertEquals("mining", HeavyLog.blockActivity("minecraft:stone", false, false));
        assertEquals("chopping", HeavyLog.blockActivity("minecraft:oak_log", false, false));
        assertEquals("chopping", HeavyLog.blockActivity("modded:thing", false, true));
        assertEquals("farming", HeavyLog.blockActivity("minecraft:wheat", false, false));
        assertEquals("farming", HeavyLog.blockActivity("mysticalagriculture:inferium_crop", false, false));
        assertEquals("digging", HeavyLog.blockActivity("minecraft:dirt", false, false));
        assertNull(HeavyLog.blockActivity("minecraft:torch", false, false));
    }

    @Test
    void activitySwitchNeedsThreeInAWindowFightAtOnce() {
        HeavyLog.Activity a = new HeavyLog.Activity();
        assertNull(a.feed("mining", T0));
        assertNull(a.feed("mining", T0 + 1000));
        assertEquals("idle", a.feed("mining", T0 + 2000));
        assertEquals("mining", a.current);
        assertNull(a.feed("mining", T0 + 3000));
        assertNull(a.feed("chopping", T0 + 4000));
        assertNull(a.feed("chopping", T0 + 40_000));            // the window ran out: counting starts again
        assertNull(a.feed("chopping", T0 + 41_000));
        assertEquals("mining", a.feed("chopping", T0 + 42_000));
        assertEquals("chopping", a.feed("fighting", T0 + 43_000));
        assertNull(a.feed(null, T0 + 44_000));
    }

    @Test
    void meterReportsTheLastMinute() {
        HeavyLog.Meter m = new HeavyLog.Meter();
        m.tick(T0, 200_000);
        m.event(T0, 1024);
        assertTrue(m.line(T0 + 1000).contains("so far"));
        m.event(T0 + 30_000, 2048);
        for (int i = 1; i <= 1200; i++) m.tick(T0 + i * 50L, 100_000);     // 0.1 ms a tick for a minute
        String s = m.line(T0 + 61_000);
        assertTrue(s.startsWith("2 ev/min, 3 KB/min"), s);
        assertEquals(0.1, m.avgMs(), 0.01);
        assertTrue(s.contains("max 0.20"), s);
    }

    @Test
    void inventorySignature() {
        Map<String, Integer> a = new LinkedHashMap<>(), b = new LinkedHashMap<>();
        a.put("minecraft:coal", 3);
        a.put("minecraft:dirt", 10);
        b.put("minecraft:dirt", 10);
        b.put("minecraft:coal", 3);
        assertEquals(HeavyLog.signature(a), HeavyLog.signature(b));
        b.put("minecraft:coal", 4);
        assertNotEquals(HeavyLog.signature(a), HeavyLog.signature(b));
    }

    @Test
    void seenObjectAndShapes() {
        ActionLog l = ActionLogTest.log();
        Map<String, String> ore = new LinkedHashMap<>();
        ore.put("iron_ore", HeavyLog.seenValue(3, 6.6));
        l.add(l.ev("seen", T0).num("r", 16).strs("ore", ore));
        l.add(l.ev("ctx", T0).str("tr", "t").dec("hp", 18).num("lt", 4).str("mobs", "skeleton,18,1,3"));
        List<String> lines = l.drain();
        JsonObject o = JsonParser.parseString(lines.get(0)).getAsJsonObject();
        assertEquals("3/7", o.getAsJsonObject("ore").get("iron_ore").getAsString());
        JsonObject c = JsonParser.parseString(lines.get(1)).getAsJsonObject();
        assertEquals("skeleton,18,1,3", c.get("mobs").getAsString());
        assertTrue(lines.get(0).length() < 200, "compact: " + lines.get(0));
    }

    @Test
    void configDefaultsClampAndOld040File(@TempDir Path dir) throws IOException {
        CompanionConfig c = new CompanionConfig();
        assertTrue(c.actionLogHeavy);
        assertFalse(c.actionLogPrompt);
        assertEquals(5, c.actionLogRate.posSeconds);
        assertEquals(1, c.actionLogRate.fightPosSeconds);
        assertEquals(5, c.actionLogRate.seenSeconds);
        assertEquals(60, c.actionLogRate.invSeconds);
        Path f = dir.resolve("entropy-companion.json");
        Files.writeString(f, "{\"enabled\":true,\"actionLog\":true}");          // a 0.4.0 file
        CompanionConfig old = CompanionConfig.load(f);
        assertTrue(old.actionLogHeavy);
        assertNotNull(old.actionLogRate);
        Files.writeString(f, "{\"actionLogRate\":{\"ctxSeconds\":0,\"seenSeconds\":9999},\"actionLogPrompt\":true}");
        CompanionConfig odd = CompanionConfig.load(f);
        assertEquals(2, odd.actionLogRate.ctxSeconds);
        assertEquals(120, odd.actionLogRate.seenSeconds);
        assertTrue(odd.actionLogPrompt);
        assertNull(CompanionConfig.saveBoolean(f, "actionLogHeavy", false));
        assertFalse(CompanionConfig.load(f).actionLogHeavy);
    }

    @Test
    void storeCapsAreRaisedAndTodayHasItsOwnCap(@TempDir Path dir) throws IOException {
        assertEquals(500L * 1024 * 1024, LogStore.CAP_BYTES);
        assertEquals(300L * 1024 * 1024, LogStore.DAY_CAP_BYTES);
        LogStore s = new LogStore(dir, ZoneOffset.UTC, 1000, 100);
        Files.writeString(dir.resolve(s.day(T0 - DAY) + ".jsonl"), "x".repeat(500) + "\n");
        s.append(List.of("a".repeat(40), "b".repeat(40), "c".repeat(40)), T0);   // 41 + 41 fit today's 100, the third is dropped
        assertEquals(1, s.dropped);
        assertEquals(2, s.linesToday(T0));
        assertEquals(2, s.files().size(), "yesterday kept: only today's cap was hit");
        s.append(List.of("d"), T0 + DAY);                                          // a new day has room again
        assertEquals(1, s.linesToday(T0 + DAY));
    }

    @Test
    void uploaderKeepsPostsUnder2MbGzip(@TempDir Path dir) {
        LogStore s = new LogStore(dir, ZoneOffset.UTC, LogStore.CAP_BYTES);
        Random r = new Random(7);
        List<String> lines = new ArrayList<>();
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < 12000; i++) {                       // ~12 MB of random hex: hardly compresses
            b.setLength(0);
            for (int k = 0; k < 500; k++) b.append(Integer.toHexString(r.nextInt(16)));
            lines.add("{\"seq\":" + i + ",\"n\":\"" + b + "\"}");
        }
        s.append(lines, T0);
        List<Integer> sizes = new ArrayList<>();
        List<Integer> counts = new ArrayList<>();
        LogUploader u = new LogUploader(s, m -> {});
        LogUploader.Poster p = gz -> {
            sizes.add(gz.length);
            return 200;
        };
        long t = T0;
        for (int i = 0; i < 20 && u.queuedBytes() > 0; i++) {
            long before = u.sentLines;
            u.step(t, true, p);
            counts.add((int) (u.sentLines - before));
            t += LogUploader.INTERVAL_MS;
        }
        assertEquals(0, u.queuedBytes(), "everything sent");
        assertEquals(12000, counts.stream().mapToInt(Integer::intValue).sum(), "every line once");
        for (int z : sizes) assertTrue(z <= LogUploader.GZIP_MAX, "post " + z + " bytes");
        assertTrue(sizes.size() >= 2, "split into several posts: " + sizes);
    }

    @Test
    void privacyHeavySideNamesNoPlayers() throws IOException {
        String src = Files.readString(Path.of("src/main/java/io/github/mojolowjo/entropycompanion/ActionLogMc.java"));
        assertTrue(src.contains("e instanceof Player ? \"player\""), "a player in a hit or the crosshair is only \"player\"");
        assertTrue(src.contains("e instanceof Enemy"), "the mob list holds hostiles only");
        assertFalse(src.contains("getDisplayName") || src.contains("getCustomName"), "no names of anything");
        assertFalse(src.contains("ClientChatReceivedEvent") || src.contains("screenshot"), "no chat, no screen");
    }
}
