package io.github.mojolowjo.entropybot.fast;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/** Package G: the fast channel's server, with a fake game thread that ticks every 50 ms. */
class FastServerTest {
    static final String KEY = "test-key-0123456789abcdef";

    @TempDir Path dir;
    FastServer server;
    Thread ticker;
    final AtomicBoolean ticking = new AtomicBoolean(true);
    final Fake game = new Fake();
    final List<String> logs = Collections.synchronizedList(new ArrayList<>());

    /** The game side: commands answer "ok: <type> <text>" (now, or `delay` ticks later); idle is a switch. */
    static final class Fake implements FastServer.Handler {
        volatile boolean idle = true, reply = true;
        volatile int delay;
        final AtomicInteger beforeIdle = new AtomicInteger(), commands = new AtomicInteger();
        volatile Thread gameThread;
        final List<Runnable> later = new ArrayList<>();

        @Override
        public void command(JsonObject cmd, Consumer<String> r) {
            assertSame(gameThread, Thread.currentThread(), "commands run on the game thread");
            commands.incrementAndGet();
            if (!reply) return;
            String text = "ok: " + cmd.get("type").getAsString() + " " + cmd.get("text").getAsString();
            if (delay == 0) r.accept(text);
            else {
                int[] left = {delay};
                later.add(new Runnable() {
                    @Override public void run() {
                        if (--left[0] <= 0) r.accept(text);
                        else later.add(this);
                    }
                });
            }
        }

        @Override public boolean idle(boolean withChain) { return idle; }

        @Override public JsonObject ping() {
            JsonObject o = new JsonObject();
            o.addProperty("version", "test");
            o.addProperty("inWorld", true);
            return o;
        }

        @Override public void beforeIdleAnswer() { beforeIdle.incrementAndGet(); }

        void tickLater() {
            List<Runnable> now = new ArrayList<>(later);
            later.clear();
            now.forEach(Runnable::run);
        }
    }

    @BeforeEach
    void setUp() throws IOException {
        Path key = dir.resolve("key.txt");
        Files.writeString(key, KEY + "\r\n");
        server = new FastServer(game, new FastServer.KeyFile(key), 0, logs::add);
        assertTrue(server.start().startsWith("ok"), "started");
        ticker = new Thread(() -> {
            game.gameThread = Thread.currentThread();
            while (ticking.get()) {
                server.tick();
                game.tickLater();
                try { Thread.sleep(50); } catch (InterruptedException e) { return; }
            }
        }, "fake-game");
        ticker.setDaemon(true);
        ticker.start();
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        ticking.set(false);
        ticker.join(1000);
        server.stop();
    }

    // ---- a raw HTTP/1.1 client, so the test controls every header ----

    record Resp(int code, String body, long ms) {
        JsonObject json() { return JsonParser.parseString(body).getAsJsonObject(); }
    }

    Resp call(String method, String path, String key, String host, String body) throws IOException {
        long t0 = System.nanoTime();
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), server.port()), 2000);
            s.setSoTimeout(20000);
            byte[] b = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
            StringBuilder h = new StringBuilder(method + " " + path + " HTTP/1.1\r\n");
            if (host != null) h.append("Host: ").append(host).append("\r\n");
            if (key != null) h.append("X-Bot-Key: ").append(key).append("\r\n");
            h.append("Content-Length: ").append(b.length).append("\r\nConnection: close\r\n\r\n");
            OutputStream o = s.getOutputStream();
            o.write(h.toString().getBytes(StandardCharsets.US_ASCII));
            o.write(b);
            o.flush();
            InputStream in = s.getInputStream();
            String all = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            int code = Integer.parseInt(all.substring(9, 12));
            int at = all.indexOf("\r\n\r\n");
            return new Resp(code, at < 0 ? "" : all.substring(at + 4), (System.nanoTime() - t0) / 1_000_000);
        }
    }

    Resp call(String method, String path, String body) throws IOException {
        return call(method, path, KEY, "127.0.0.1:" + server.port(), body);
    }

    static String cmd(String type, String text) {
        JsonObject o = new JsonObject();
        o.addProperty("id", String.valueOf(System.currentTimeMillis()));
        o.addProperty("type", type);
        o.addProperty("text", text);
        return o.toString();
    }

    // ---- tests ----

    @Test
    void bindsLoopbackOnly() throws IOException {
        assertTrue(server.address().getAddress().isLoopbackAddress(), "bound to " + server.address());
        // a non-loopback address of this machine must not reach it
        for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
            for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                if (a.isLoopbackAddress() || a.isLinkLocalAddress() || !(a instanceof java.net.Inet4Address)) continue;
                try (Socket s = new Socket()) {
                    assertThrows(IOException.class, () -> s.connect(new InetSocketAddress(a, server.port()), 500), "reachable on " + a);
                }
            }
        }
    }

    @Test
    void refusedWithoutTheKey() throws IOException {
        assertEquals(401, call("GET", "/ping", null, "127.0.0.1:" + server.port(), null).code());
        assertEquals(401, call("GET", "/ping", "wrong-key-0123456789", "127.0.0.1:" + server.port(), null).code());
        assertEquals(401, call("POST", "/cmd", null, "127.0.0.1:" + server.port(), cmd("eval", "1")).code());
        assertEquals(0, game.commands.get(), "nothing ran");
        // the key never comes back in an answer
        assertFalse(call("GET", "/ping", "nope-nope-nope-nope", "127.0.0.1:" + server.port(), null).body().contains(KEY));
    }

    @Test
    void refusedForAForeignHost() throws IOException {
        assertEquals(403, call("GET", "/ping", KEY, "evil.example:" + server.port(), null).code());
        assertEquals(403, call("GET", "/ping", KEY, null, null).code());
        assertEquals(200, call("GET", "/ping", KEY, "localhost:" + server.port(), null).code());
    }

    @Test
    void pingAnswersFromTheGameThread() throws IOException {
        Resp r = call("GET", "/ping", null);
        assertEquals(200, r.code());
        JsonObject o = r.json();
        assertTrue(o.get("ok").getAsBoolean());
        assertEquals("test", o.get("version").getAsString());
        assertTrue(r.ms() < 1000, "ping took " + r.ms() + " ms");
    }

    @Test
    void aCommandIsAnswered() throws IOException {
        Resp r = call("POST", "/cmd", cmd("eval", "return 1"));
        assertEquals(200, r.code());
        assertEquals("ok: eval return 1", r.json().get("result").getAsString());
        assertTrue(r.ms() < 1000, "took " + r.ms() + " ms");
        assertEquals(1, game.commands.get());
    }

    @Test
    void aLateAnswerStillArrives() throws IOException {
        game.delay = 5;
        Resp r = call("POST", "/cmd", cmd("pm", "status"));
        assertEquals("ok: pm status", r.json().get("result").getAsString());
    }

    @Test
    void aCommandWithNoAnswerTimesOut() throws IOException {
        game.reply = false;
        Resp r = call("POST", "/cmd?timeout=300", cmd("eval", "x"));
        assertEquals(200, r.code());
        JsonObject o = r.json();
        assertTrue(o.get("result").isJsonNull());
        assertTrue(o.get("timeout").getAsBoolean());
        assertTrue(r.ms() >= 250 && r.ms() < 3000, "took " + r.ms() + " ms");
    }

    @Test
    void badRequests() throws IOException {
        assertEquals(405, call("GET", "/cmd", null).code());
        assertEquals(400, call("POST", "/cmd", "not json").code());
        assertEquals(400, call("POST", "/cmd", "{\"text\":\"x\"}").code());
        assertEquals(404, call("GET", "/nope", null).code());
    }

    @Test
    void waitTimesOutWhileBusy() throws IOException {
        game.idle = false;
        Resp r = call("GET", "/wait?timeout=400", null);
        JsonObject o = r.json();
        assertFalse(o.get("idle").getAsBoolean());
        assertTrue(o.get("timeout").getAsBoolean());
        assertTrue(r.ms() >= 350 && r.ms() < 3000, "took " + r.ms() + " ms");
        assertEquals(0, game.beforeIdle.get());
    }

    @Test
    void waitReturnsOnceIdle() throws Exception {
        game.idle = false;
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(600);
            } catch (InterruptedException ignored) {}
            game.idle = true;
        });
        t.start();
        Resp r = call("GET", "/wait?timeout=10000", null);
        JsonObject o = r.json();
        assertTrue(o.get("idle").getAsBoolean());
        // the job ended after 600 ms, then SETTLE_TICKS of quiet (50 ms each here)
        assertTrue(r.ms() >= 600 + (FastServer.SETTLE_TICKS - 1) * 50 - 100 && r.ms() < 5000, "took " + r.ms() + " ms");
        assertEquals(1, game.beforeIdle.get(), "state.json is written before the answer");
    }

    @Test
    void aKeyChangeIsPickedUp() throws Exception {
        Path key = dir.resolve("key.txt");
        String other = "another-key-abcdefghijklmnop";
        Files.writeString(key, other);
        Files.setLastModifiedTime(key, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 5000));
        assertEquals(401, call("GET", "/ping", null).code());
        assertEquals(200, call("GET", "/ping", other, "127.0.0.1:" + server.port(), null).code());
    }

    @Test
    void aShortKeyIsNotServed() throws IOException {
        Path k = dir.resolve("short.txt");
        Files.writeString(k, "short");
        FastServer s = new FastServer(game, new FastServer.KeyFile(k), 0, null);
        assertTrue(s.start().startsWith("error"));
        assertFalse(s.running());
        FastServer none = new FastServer(game, new FastServer.KeyFile(dir.resolve("missing.txt")), 0, null);
        assertTrue(none.start().startsWith("error"));
    }

    @Test
    void theLogNeverHoldsTheKey() {
        for (String l : logs) assertFalse(l.contains(KEY), l);
    }

    @Test
    void hostRules() {
        assertTrue(FastServer.hostOk("127.0.0.1:8766"));
        assertTrue(FastServer.hostOk("localhost"));
        assertTrue(FastServer.hostOk("[::1]:8766"));
        assertFalse(FastServer.hostOk("192.168.1.5:8766"));
        assertFalse(FastServer.hostOk("127.0.0.1.evil.com"));
        assertEquals(10_000, FastServer.clamp(null, 10_000, 60_000));
        assertEquals(60_000, FastServer.clamp("999999", 10_000, 60_000));
        assertEquals(10_000, FastServer.clamp("abc", 10_000, 60_000));
    }

    // ---- FastChannel: fast.json switches it on and off ----

    @Test
    void theChannelFollowsFastJson() throws IOException {
        Path inst = dir.resolve("entropybot");
        Files.createDirectories(inst);
        List<String> log = new ArrayList<>();
        FastChannel ch = new FastChannel(inst, game, log::add);
        ch.check();
        assertFalse(ch.running(), "no fast.json, no server");
        Path key = dir.resolve("key.txt");
        JsonObject c = new JsonObject();
        c.addProperty("keyFile", key.toString());
        c.addProperty("port", 0);
        Files.writeString(inst.resolve(FastChannel.CONFIG), c.toString());
        ch.check();
        try {
            assertTrue(ch.running());
            JsonObject up = JsonParser.parseString(Files.readString(inst.resolve(FastChannel.UP))).getAsJsonObject();
            assertEquals(ch.server().port(), up.get("port").getAsInt());
            assertEquals(ProcessHandle.current().pid(), up.get("pid").getAsLong());
            for (String l : log) assertFalse(l.contains(KEY), l);
            Files.delete(inst.resolve(FastChannel.CONFIG));
            ch.check();
            assertFalse(ch.running());
            assertFalse(Files.exists(inst.resolve(FastChannel.UP)), "the up file goes with the server");
        } finally {
            ch.stop();
        }
    }
}
