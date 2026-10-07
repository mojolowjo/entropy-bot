package io.github.mojolowjo.entropycompanion;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.*;

/** 0.3.0 chunk sharing: the scanner on a fake world, the batching and backoff rules, the post body, the config switch. */
class ChunkSharingTest {

    /** Flat ground at y 64 (grass), a leaf at 70 over x&15 == 3, chunks 0..2 x 0 loaded. */
    static final class FakeWorld implements ChunkScanner.World {
        int height = 64;
        public String dim() { return "minecraft:overworld"; }
        public boolean loaded(int cx, int cz) { return cx >= 0 && cx <= 2 && cz == 0; }
        public int minY() { return -64; }
        public ChunkColumns.Source source() {
            return new ChunkColumns.Source() {
                public int kind(int x, int y, int z) {
                    if ((x & 15) == 3 && y == 70) return ChunkColumns.LEAVES;
                    return y <= height ? ChunkColumns.GROUND : ChunkColumns.AIR;
                }
                public int family(int x, int y, int z) { return y == height ? ChunkFamily.GRASS : ChunkFamily.STONE; }
                public int top(int x, int z) { return 80; }
            };
        }
    }

    @Test
    void scansDueChunksIntoV2JsonWithSrc() {
        ChunkScanQueue q = new ChunkScanQueue();
        ChunkBatcher b = new ChunkBatcher();
        q.loaded(0, 0, 0);
        q.loaded(9, 9, 0);                                  // not loaded any more: skipped
        assertEquals(1, ChunkScanner.step(q, new FakeWorld(), b, 1000, System::nanoTime, null));
        List<ChunkBatcher.Item> items = b.take(1000);
        assertEquals(1, items.size());
        JsonObject o = JsonParser.parseString(items.get(0).json()).getAsJsonObject();
        assertEquals(2, o.get("v").getAsInt());
        assertEquals("companion", o.get("src").getAsString());
        assertEquals(1000, o.get("t").getAsLong());
        assertEquals("minecraft:overworld", o.get("dim").getAsString());
        assertEquals(64, o.getAsJsonArray("g").get(0).getAsInt());
        assertEquals(ChunkFamily.GRASS, o.getAsJsonArray("f").get(0).getAsInt());
        assertEquals(70, o.getAsJsonArray("c").get(3).getAsInt(), "the canopy over x 3");
        assertEquals(-64, o.getAsJsonArray("u").get(0).getAsInt(), "the run reaches the floor");
        for (String a : new String[] {"g", "f", "c", "l", "u", "g2", "f2", "u2"}) assertEquals(256, o.getAsJsonArray(a).size(), a);
        assertTrue(items.get(0).json().indexOf("\"src\"") < 120, "src near the head (the bot reads 256 bytes)");
    }

    @Test
    void budgetLeavesTheRestForTheNextTick() {
        ChunkScanQueue q = new ChunkScanQueue();
        ChunkBatcher b = new ChunkBatcher();
        for (int cx = 0; cx <= 2; cx++) q.loaded(cx, 0, 0);
        AtomicLong fake = new AtomicLong();
        int n = ChunkScanner.step(q, new FakeWorld(), b, 10, () -> fake.addAndGet(2_000_000), null);
        assertEquals(1, n, "the first always; then over 1 ms");
        assertEquals(2, q.size(), "the rest are due again");
        assertEquals(2, ChunkScanner.step(q, new FakeWorld(), b, 20, System::nanoTime, null));
    }

    @Test
    void onlyChangedChunksAreSentAgain() {
        ChunkScanQueue q = new ChunkScanQueue();
        ChunkBatcher b = new ChunkBatcher();
        FakeWorld w = new FakeWorld();
        q.loaded(0, 0, 0);
        ChunkScanner.step(q, w, b, 0, System::nanoTime, null);
        List<ChunkBatcher.Item> batch = b.take(0);
        b.done(batch, null, 0);
        assertEquals(1, b.chunksSent());
        q.changed(0, 0, 100);
        assertEquals(0, ChunkScanner.step(q, w, b, 100, System::nanoTime, null), "debounced: not yet");
        assertEquals(1, ChunkScanner.step(q, w, b, 100 + ChunkScanQueue.DEBOUNCE_MS, System::nanoTime, null));
        assertEquals(0, b.pending(), "same columns, later t: nothing to send");
        w.height = 65;
        q.loaded(0, 0, 3000);
        ChunkScanner.step(q, w, b, 3000, System::nanoTime, null);
        assertEquals(1, b.pending(), "a real change is sent");
    }

    @Test
    void batchesOfAtMost64AtMostEvery10s() {
        ChunkBatcher b = new ChunkBatcher();
        for (int i = 0; i < 100; i++) b.offer("d " + i + " 0", "{}", i);
        assertTrue(b.due(0));
        List<ChunkBatcher.Item> first = b.take(0);
        assertEquals(64, first.size());
        assertEquals("d 0 0", first.get(0).key(), "oldest first");
        assertFalse(b.due(5000), "no second post while one is out or within 10 s");
        b.done(first, null, 1000);
        assertFalse(b.due(10_999));
        assertTrue(b.due(11_000));
        assertEquals(36, b.take(11_000).size());
    }

    @Test
    void failuresPutTheChunksBackAndBackOff() {
        ChunkBatcher b = new ChunkBatcher();
        b.offer("k1", "old", 1);
        b.offer("k2", "x", 2);
        List<ChunkBatcher.Item> batch = b.take(0);
        b.offer("k1", "new", 3);                              // rescanned while the post was out
        b.done(batch, "unreachable", 0);
        assertEquals(2, b.pending());
        List<ChunkBatcher.Item> again = b.take(1_000_000);
        assertEquals("new", again.stream().filter(i -> i.key().equals("k1")).findFirst().orElseThrow().json(), "the newer scan wins");
        assertEquals(10_000, ChunkBatcher.backoffMs(1));
        assertEquals(20_000, ChunkBatcher.backoffMs(2));
        assertEquals(40_000, ChunkBatcher.backoffMs(3));
        assertEquals(ChunkBatcher.MAX_BACKOFF_MS, ChunkBatcher.backoffMs(30));
        b.done(again, "x", 1_000_000);                        // second failure in a row
        assertFalse(b.due(1_000_000 + 19_999));
        assertTrue(b.due(1_000_000 + 20_000));
        List<ChunkBatcher.Item> third = b.take(1_020_000);
        b.done(third, null, 1_020_000);
        assertTrue(b.line(1_020_000).contains("failures 2"));
        assertEquals(0, b.pending());
        b.offer("k3", "y", 9);
        assertTrue(b.due(1_030_000), "back to 10 s after a success");
    }

    @Test
    void pendingIsCapped() {
        ChunkBatcher b = new ChunkBatcher();
        for (int i = 0; i < ChunkBatcher.MAX_PENDING + 5; i++) b.offer("k" + i, "", i);
        assertEquals(ChunkBatcher.MAX_PENDING, b.pending());
        assertEquals("k5", b.take(0).get(0).key(), "the oldest were dropped");
    }

    @Test
    void bodyAndGzip() throws Exception {
        List<ChunkBatcher.Item> l = new ArrayList<>();
        l.add(new ChunkBatcher.Item("a", "{\"v\":2}", 1));
        l.add(new ChunkBatcher.Item("b", "{\"v\":2,\"x\":1}", 2));
        String body = ChunkScanner.body(l);
        assertEquals("{\"chunks\":[{\"v\":2},{\"v\":2,\"x\":1}]}", body);
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(ChunkScanner.gzip(body)))) {
            assertEquals(body, new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
        assertNull(ChunkScanner.verdict(200));
        assertTrue(ChunkScanner.verdict(403).contains("key"));
        assertTrue(ChunkScanner.verdict(404).contains("/api/chunks"));
    }

    @Test
    void configSwitchKeepsTheRest(@TempDir Path dir) throws Exception {
        Path f = dir.resolve(CompanionConfig.FILE_NAME);
        Files.writeString(f, "{\"url\":\"http://x:1\",\"key\":\"k\",\"intervalSeconds\":5}");
        assertTrue(CompanionConfig.load(f).shareChunks, "on by default");
        assertNull(CompanionConfig.saveShareChunks(f, false));
        CompanionConfig c = CompanionConfig.load(f);
        assertFalse(c.shareChunks);
        assertEquals("k", c.key);
        assertEquals(5, c.intervalSeconds);
        assertNull(CompanionConfig.saveShareChunks(f, true));
        assertTrue(CompanionConfig.load(f).shareChunks);
    }
}
