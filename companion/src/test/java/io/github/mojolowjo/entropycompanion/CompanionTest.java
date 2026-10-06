package io.github.mojolowjo.entropycompanion;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompanionTest {
    @TempDir Path tmp;

    // ---- config
    @Test void firstRunWritesDefaultsAndSendsNothing() throws IOException {
        Path f = tmp.resolve("config").resolve(CompanionConfig.FILE_NAME);
        CompanionConfig c = CompanionConfig.load(f);
        assertTrue(Files.exists(f));
        JsonObject o = JsonParser.parseString(Files.readString(f)).getAsJsonObject();
        assertTrue(o.get("enabled").getAsBoolean());
        assertEquals("", o.get("key").getAsString());
        assertEquals(2, o.get("intervalSeconds").getAsInt());
        assertTrue(o.has("url"));
        assertFalse(c.active(), "an empty key sends nothing");
    }

    @Test void activeNeedsEnabledUrlAndKey() throws IOException {
        Path f = tmp.resolve("c.json");
        Files.writeString(f, "{\"enabled\":true,\"url\":\"http://192.168.1.20:8765/\",\"key\":\"abc\",\"intervalSeconds\":2}");
        CompanionConfig c = CompanionConfig.load(f);
        assertTrue(c.active());
        assertEquals(URI.create("http://192.168.1.20:8765/api/owner"), c.endpoint());
        Files.writeString(f, "{\"enabled\":false,\"url\":\"http://h:1\",\"key\":\"abc\"}");
        assertFalse(CompanionConfig.load(f).active());
        Files.writeString(f, "{\"enabled\":true,\"url\":\"\",\"key\":\"abc\"}");
        assertFalse(CompanionConfig.load(f).active());
        Files.writeString(f, "{\"enabled\":true,\"url\":\"ftp://h:1\",\"key\":\"abc\"}");
        assertFalse(CompanionConfig.load(f).active(), "only http(s)");
        Files.writeString(f, "{\"enabled\":true,\"url\":\"not a url\",\"key\":\"abc\"}");
        assertFalse(CompanionConfig.load(f).active());
    }

    @Test void intervalIsClampedAndMissingFieldsKeepDefaults() throws IOException {
        Path f = tmp.resolve("c.json");
        Files.writeString(f, "{\"key\":\"k\",\"intervalSeconds\":0}");
        CompanionConfig c = CompanionConfig.load(f);
        assertEquals(1, c.intervalSeconds);
        assertTrue(c.enabled);
        Files.writeString(f, "{\"key\":\"k\",\"intervalSeconds\":9999}");
        assertEquals(60, CompanionConfig.load(f).intervalSeconds);
    }

    @Test void brokenFileDoesNotStartSendingAndIsLeftAlone() throws IOException {
        Path f = tmp.resolve("c.json");
        Files.writeString(f, "{ this is not json");
        assertFalse(CompanionConfig.load(f).active());
        assertEquals("{ this is not json", Files.readString(f));
    }

    @Test void toStringHidesTheKey() {
        CompanionConfig c = new CompanionConfig();
        c.key = "super-secret-key";
        assertFalse(c.toString().contains("super-secret-key"));
    }

    // ---- payload
    @Test void payloadHasTheSixFields() {
        String s = OwnerPayload.json(new OwnerPayload.Snapshot("mojolowjo", 10.123456, 64, -3.5, "minecraft:overworld"), 1790000000000L);
        JsonObject o = JsonParser.parseString(s).getAsJsonObject();
        assertEquals("mojolowjo", o.get("name").getAsString());
        assertEquals(10.12, o.get("x").getAsDouble(), 1e-9);
        assertEquals(64.0, o.get("y").getAsDouble(), 1e-9);
        assertEquals(-3.5, o.get("z").getAsDouble(), 1e-9);
        assertEquals("minecraft:overworld", o.get("dim").getAsString());
        assertEquals(1790000000000L, o.get("at").getAsLong());
        assertEquals(6, o.size());
    }

    @Test void payloadRefusesNonFiniteOrEmpty() {
        assertNull(OwnerPayload.json(new OwnerPayload.Snapshot("a", Double.NaN, 0, 0, "minecraft:overworld"), 1));
        assertNull(OwnerPayload.json(new OwnerPayload.Snapshot("a", 0, Double.POSITIVE_INFINITY, 0, "minecraft:overworld"), 1));
        assertNull(OwnerPayload.json(new OwnerPayload.Snapshot("", 0, 0, 0, "minecraft:overworld"), 1));
        assertNull(OwnerPayload.json(new OwnerPayload.Snapshot("a", 0, 0, 0, null), 1));
        assertNull(OwnerPayload.json(null, 1));
    }

    // ---- backoff
    @Test void backsOffAfterThreeFailuresAndLogsOncePerChange() {
        Backoff b = new Backoff();
        assertTrue(b.success(), "first success is a change");
        assertFalse(b.success());
        assertFalse(b.failure());
        assertFalse(b.failure());
        assertEquals(2000, b.delayMs(2000));
        assertTrue(b.failure(), "third failure tips into failing");
        assertFalse(b.failure(), "no second line while failing");
        assertEquals(30_000, b.delayMs(2000));
        assertTrue(b.success(), "recovery is a change");
        assertEquals(2000, b.delayMs(2000));
        assertFalse(b.failure());
        assertFalse(b.success(), "a single lost post is not a change");
    }

    // ---- post loop
    static final class Fake implements PostLoop.Sender {
        final List<String> bodies = new ArrayList<>();
        final List<String> keys = new ArrayList<>();
        int status = 200;
        IOException fail;

        @Override public int post(URI endpoint, String key, String body, int timeoutMs) throws IOException {
            assertEquals(PostLoop.TIMEOUT_MS, timeoutMs);
            if (fail != null) throw fail;
            bodies.add(body);
            keys.add(key);
            return status;
        }
    }

    static CompanionConfig cfg(String key) {
        CompanionConfig c = new CompanionConfig();
        c.key = key;
        c.url = "http://192.168.1.20:8765";
        return c.normalised();
    }

    static final OwnerPayload.Snapshot SNAP = new OwnerPayload.Snapshot("mojolowjo", 1, 2, 3, "minecraft:overworld");

    @Test void emptyKeySendsNothing() {
        Fake net = new Fake();
        List<String> log = new ArrayList<>();
        AtomicLong t = new AtomicLong(1000);
        PostLoop loop = new PostLoop(() -> cfg(""), net, log::add, t::get);
        assertTrue(loop.due());
        loop.attempt(SNAP);
        loop.attempt(SNAP);
        assertTrue(net.bodies.isEmpty());
        assertEquals(1, log.size(), "one line about it, not one per check");
        assertFalse(loop.due(), "looks again in a few seconds");
        t.addAndGet(PostLoop.CONFIG_CHECK_MS);
        assertTrue(loop.due());
    }

    @Test void postsAtTheIntervalWithKeyAndBody() {
        Fake net = new Fake();
        AtomicLong t = new AtomicLong(1000);
        PostLoop loop = new PostLoop(() -> cfg("k1"), net, m -> {}, t::get);
        loop.attempt(SNAP);
        assertEquals(1, net.bodies.size());
        assertEquals("k1", net.keys.get(0));
        assertTrue(net.bodies.get(0).contains("\"at\":1000"));
        assertFalse(loop.due());
        t.addAndGet(1999);
        assertFalse(loop.due());
        t.addAndGet(1);
        assertTrue(loop.due());
    }

    @Test void failuresBackOffAndLogOnce_neverTheKey() {
        Fake net = new Fake();
        net.fail = new ConnectException("Connection refused");
        List<String> log = new ArrayList<>();
        AtomicLong t = new AtomicLong(0);
        PostLoop loop = new PostLoop(() -> cfg("topsecret"), net, log::add, t::get);
        for (int i = 0; i < 6; i++) {
            loop.attempt(SNAP);
            t.addAndGet(Backoff.FAILING_INTERVAL_MS);
        }
        assertEquals(1, log.size());
        assertFalse(log.get(0).contains("topsecret"));
        assertTrue(loop.backoff().failing());
        // after backing off: the next post waits 30 s
        loop.attempt(SNAP);
        assertFalse(loop.due());
        t.addAndGet(29_999);
        assertFalse(loop.due());
        t.addAndGet(1);
        assertTrue(loop.due());
        // and recovery is logged once
        net.fail = null;
        loop.attempt(SNAP);
        assertEquals(2, log.size());
        assertTrue(log.get(1).startsWith("connected"));
        assertFalse(loop.backoff().failing());
    }

    @Test void a403CountsAsFailureWithAHint() {
        Fake net = new Fake();
        net.status = 403;
        List<String> log = new ArrayList<>();
        AtomicLong t = new AtomicLong(0);
        PostLoop loop = new PostLoop(() -> cfg("k"), net, log::add, t::get);
        for (int i = 0; i < 3; i++) loop.attempt(SNAP);
        assertEquals(1, log.size());
        assertTrue(log.get(0).contains("403"));
    }

    @Test void configEditedLiveSwitchesItOff() {
        Fake net = new Fake();
        AtomicLong t = new AtomicLong(0);
        CompanionConfig[] now = {cfg("k")};
        PostLoop loop = new PostLoop(() -> now[0], net, m -> {}, t::get);
        loop.attempt(SNAP);
        assertEquals(1, net.bodies.size());
        now[0] = cfg("k");
        now[0].enabled = false;
        t.addAndGet(2000);
        loop.attempt(SNAP);
        assertEquals(1, net.bodies.size());
    }

    @Test void badSnapshotIsSkippedNotSent() {
        Fake net = new Fake();
        AtomicLong t = new AtomicLong(0);
        PostLoop loop = new PostLoop(() -> cfg("k"), net, m -> {}, t::get);
        loop.attempt(new OwnerPayload.Snapshot("a", Double.NaN, 0, 0, "minecraft:overworld"));
        assertTrue(net.bodies.isEmpty());
        assertNotNull(loop.backoff());
    }
}