package io.github.mojolowjo.entropybot.fast;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.function.Consumer;

/**
 * The fast channel's life (package G): bridge.ps1 writes {@code entropybot/fast.json} = {"keyFile": "&lt;path of the
 * dashboard's key.txt&gt;", "port": 8766} (a path, never the key); the mod starts {@link FastServer} once that file names
 * a usable key, and writes {@code entropybot/fast-server.json} = {port, pid, at} while it runs (touched every
 * {@link #BEAT_TICKS}, deleted when the game closes), so bridge.ps1 knows whether to try it at all. No fast.json, no
 * server: everything works through cmd.json as before. Plain Java; the game thread calls {@link #tick}.
 */
public final class FastChannel {
    public static final String CONFIG = "fast.json", UP = "fast-server.json";
    /** How often the config is looked at while the server is off, and the "up" file touched while it is on. */
    public static final int CHECK_TICKS = 100, BEAT_TICKS = 200;

    private final Path dir;
    private final FastServer.Handler handler;
    private final Consumer<String> log;
    private FastServer server;
    private String configSeen, lastMsg;
    private Thread hook;

    public FastChannel(Path dir, FastServer.Handler handler, Consumer<String> log) {
        this.dir = dir;
        this.handler = handler;
        this.log = log == null ? s -> {} : log;
    }

    public synchronized FastServer server() { return server; }

    public synchronized boolean running() { return server != null && server.running(); }

    /** Once a game tick (also outside a world), on the game thread. */
    public void tick(long tick) {
        FastServer s;
        synchronized (this) {
            if (tick % CHECK_TICKS == 0) check();
            if (server != null && tick % BEAT_TICKS == 0) writeUp();
            s = server;
        }
        if (s != null) s.tick();
    }

    /** Reads fast.json; (re)starts or stops the server when it changed. */
    synchronized void check() {
        String raw = read(dir.resolve(CONFIG));
        if (raw == null) {
            if (server != null) {
                stop();
                say("fast channel off: " + CONFIG + " is gone");
            }
            configSeen = null;
            return;
        }
        if (raw.equals(configSeen) && server != null) return;
        if (raw.equals(configSeen) && server == null) {
            // same config, and it failed before: try again (the key file may be there now) but don't log it again
            start(raw);
            return;
        }
        configSeen = raw;
        if (server != null) stop();
        start(raw);
    }

    private void start(String raw) {
        JsonObject c;
        try {
            c = JsonParser.parseString(raw).getAsJsonObject();
        } catch (RuntimeException e) {
            say("fast channel: " + CONFIG + " is not a JSON object");
            return;
        }
        if (!c.has("keyFile") || !c.get("keyFile").isJsonPrimitive()) {
            say("fast channel: " + CONFIG + " has no keyFile");
            return;
        }
        int port = port(c);
        if (port < 0) {
            say("fast channel: " + CONFIG + "'s port is not a number from 1 to 65535: using " + FastServer.DEFAULT_PORT);
            port = FastServer.DEFAULT_PORT;
        }
        Path key;
        try {
            key = Path.of(c.get("keyFile").getAsString());
        } catch (RuntimeException e) {
            say("fast channel: keyFile is not a path");
            return;
        }
        FastServer s = new FastServer(handler, new FastServer.KeyFile(key), port, log);
        String r = s.start();
        say(r);
        if (!r.startsWith("ok")) return;
        server = s;
        writeUp();
        if (hook == null) {
            hook = new Thread(this::shutdown, "entropybot-fast-exit");
            try { Runtime.getRuntime().addShutdownHook(hook); } catch (RuntimeException ignored) {}
        }
    }

    /**
     * fast.json's optional "port" (0.26.0, so a second bot instance can use its own: bridge.ps1 picks it with
     * $env:BRIDGE_FAST_PORT): absent = {@link FastServer#DEFAULT_PORT}, -1 when present but not 1..65535.
     */
    static int port(JsonObject c) {
        if (!c.has("port")) return FastServer.DEFAULT_PORT;
        try {
            int p = c.get("port").getAsInt();
            return p >= 0 && p <= 65535 ? p : -1;          // 0 = any free port (tests)
        } catch (RuntimeException e) {
            return -1;
        }
    }

    /** Stops the server and removes the "up" file (the game closes, or fast.json went away). */
    public synchronized void stop() {
        if (server != null) server.stop();
        server = null;
        try { Files.deleteIfExists(dir.resolve(UP)); } catch (IOException ignored) {}
    }

    private void shutdown() {
        try { stop(); } catch (RuntimeException ignored) {}
    }

    private void writeUp() {
        if (server == null) return;
        JsonObject o = new JsonObject();
        o.addProperty("port", server.port());
        o.addProperty("pid", ProcessHandle.current().pid());
        o.addProperty("at", System.currentTimeMillis());
        try {
            Files.createDirectories(dir);
            Path tmp = dir.resolve(UP + ".tmp");
            Files.writeString(tmp, o.toString(), StandardCharsets.UTF_8);
            Files.move(tmp, dir.resolve(UP), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | RuntimeException e) {
            say("fast channel: couldn't write " + UP + ": " + e);
        }
    }

    private void say(String msg) {
        if (msg.equals(lastMsg)) return;
        lastMsg = msg;
        log.accept("[entropybot] " + msg);
    }

    private static String read(Path p) {
        try {
            return Files.exists(p) ? Files.readString(p, StandardCharsets.UTF_8).trim() : null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }
}
