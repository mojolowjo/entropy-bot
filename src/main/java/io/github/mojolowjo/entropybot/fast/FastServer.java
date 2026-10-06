package io.github.mojolowjo.entropybot.fast;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Package G, "the fast channel" (TO-LOOK-AT-LATER 16/17): a small HTTP server on 127.0.0.1 only, so bridge.ps1 gets
 * an answer in milliseconds instead of writing cmd.json and polling state.json (~1 s a call). Same commands as
 * cmd.json, same key as the dashboard (read from a file whose path bridge.ps1 puts in {@code entropybot/fast.json};
 * the key itself is never logged, never written anywhere by the mod and never sent back).
 *
 * <ul>
 *   <li>{@code GET /ping}: answered on the game thread at its next tick: {ok, tick, inWorld, version}.</li>
 *   <li>{@code GET /state}: state.json's object, built at the next tick (at most once a tick, shared by the calls
 *       waiting for it; 504 when the game does not tick for 5 s).</li>
 *   <li>{@code POST /cmd[?timeout=ms]}, body = a cmd.json object {id, type, text, from?, notify?}: run at the next
 *       tick exactly as a cmd.json command; answers {id, result} when the command answers (a long job answers
 *       "started: ..." at once, as today), or {id, result: null, timeout: true} (plus notRun: true when the game
 *       never took it: it is dropped then and will not run later).</li>
 *   <li>{@code GET /block?x=&y=&z=} and {@code GET /column?x=&z=} (P4, 0.19.10): one block or one surface column for the
 *       dashboard's map menu, answered on the game thread at the next tick (400 for bad numbers, 503 outside a world,
 *       504 after 5 s).</li>
 *   <li>{@code GET /wait[?timeout=ms][&chain=0|1]}: a long poll that answers {idle: true, waitedMs} once the bot has
 *       been idle (no job, no chain unless chain=0, Baritone idle) for {@link #SETTLE_TICKS}
 *       ticks in a row, or {idle: false, timeout: true, busy: "chain night"} when the time runs out.</li>
 * </ul>
 * Every request needs the header {@code X-Bot-Key: <key>} (401 without), and a Host header naming 127.0.0.1 or
 * localhost (403 otherwise: a web page can't reach it through DNS rebinding). Game state is touched only from
 * {@link #tick()}, which the game thread calls once a tick; the HTTP threads only queue work and wait for it.
 * Plain Java: JUnit drives {@link #tick()} itself.
 */
public final class FastServer {
    /** The default port (the dashboard is 8765). */
    public static final int DEFAULT_PORT = 8766;
    /** A key shorter than this is refused (the dashboard's is 32+ characters). */
    public static final int MIN_KEY = 16;
    /** Ticks the bot has to be idle in a row before a /wait answers (a job that is about to start has a moment). */
    public static final int SETTLE_TICKS = 10;
    public static final long CMD_TIMEOUT_MS = 10_000, CMD_TIMEOUT_MAX = 60_000, WAIT_TIMEOUT_MS = 120_000, WAIT_TIMEOUT_MAX = 3_600_000;
    static final int MAX_BODY = 1 << 20, MAX_WAITERS = 4, TASKS_PER_TICK = 16, THREADS = 8;

    /** What the game side does; every method is called on the game thread (from {@link #tick()}). */
    public interface Handler {
        /** Runs a cmd.json command; {@code reply} gets its answer once (now or on a later tick). */
        void command(JsonObject cmd, Consumer<String> reply);

        /**
         * What keeps the bot busy ("job ...", "chain ...", "baritone ..."), or null when idle: no job,
         * Baritone idle (and no chain when {@code withChain}). Outside a world: null.
         */
        String busy(boolean withChain);

        /** Fields for /ping besides ok and tick (version, inWorld). */
        JsonObject ping();

        /** GET /state: the same object state.json holds (built by the state.json writer). */
        default JsonObject state() { return new JsonObject(); }

        /** Just before a /wait answers idle: write state.json now, so the caller's next read is fresh. */
        default void beforeIdleAnswer() {}

        /**
         * P4 (0.19.10) GET /block: {id, x, y, z, loaded, container, protected, family} of the block at x y z (an unloaded
         * chunk: loaded false, id null). Null when not in a world (503).
         */
        default JsonObject block(int x, int y, int z) { return null; }

        /** P4 GET /column: {x, z, ground, id, family, canopy} by the surface export's rule (ground/canopy -1 = none). Null: not in a world. */
        default JsonObject column(int x, int z) { return null; }
    }

    /** Where the key lives: re-read when the file changes, so a new dashboard key works without a restart. */
    public static final class KeyFile {
        private final Path path;
        private FileTime seen;
        private byte[] key;

        public KeyFile(Path path) { this.path = path; }

        public Path path() { return path; }

        /** The key's bytes, or null when the file is missing, unreadable or too short. */
        public synchronized byte[] key() {
            try {
                FileTime t = Files.getLastModifiedTime(path);
                if (!t.equals(seen)) {
                    seen = t;
                    String k = Files.readString(path, StandardCharsets.UTF_8).trim();
                    key = k.length() >= MIN_KEY ? k.getBytes(StandardCharsets.UTF_8) : null;
                }
            } catch (IOException | RuntimeException e) {
                seen = null;
                key = null;
            }
            return key;
        }
    }

    private final Handler handler;
    private final KeyFile keyFile;
    private final int wantPort;
    private final Consumer<String> log;
    private final ConcurrentLinkedQueue<Runnable> tasks = new ConcurrentLinkedQueue<>();
    private final List<Waiter> waiters = new ArrayList<>();
    private final AtomicInteger waiting = new AtomicInteger();
    private HttpServer http;
    private ExecutorService pool;
    private volatile long ticks;
    private volatile boolean open;
    private int nextId;

    private static final class Waiter {
        final boolean chain;
        final long startMs = System.currentTimeMillis();
        long idleSince = -1;
        /** What kept the bot busy at the last check (for a timed-out answer). */
        volatile String busy;
        final CompletableFuture<Boolean> done = new CompletableFuture<>();

        Waiter(boolean chain) { this.chain = chain; }
    }

    public FastServer(Handler handler, KeyFile keyFile, int port, Consumer<String> log) {
        this.handler = handler;
        this.keyFile = keyFile;
        this.wantPort = port;
        this.log = log == null ? s -> {} : log;
    }

    /** Binds 127.0.0.1:port (loopback only, never the LAN); "ok: ..." or "error: ...". The key is never logged. */
    public synchronized String start() {
        if (http != null) return "ok: already on 127.0.0.1:" + port();
        if (keyFile.key() == null) return "error: no usable key in " + keyFile.path() + " (missing, unreadable or shorter than " + MIN_KEY + ")";
        HttpServer s = null;
        try {
            // 127.0.0.1 explicitly: getLoopbackAddress() is ::1 in the game's JVM (seen live, 0.10.0), which bridge.ps1 didn't reach
            s = HttpServer.create(new InetSocketAddress(InetAddress.getByAddress(new byte[] {127, 0, 0, 1}), wantPort), 16);
            pool = Executors.newFixedThreadPool(THREADS, r -> {
                Thread t = new Thread(r, "entropybot-fast");
                t.setDaemon(true);
                return t;
            });
            s.setExecutor(pool);
            s.createContext("/", this::serve);
            // the dispatcher thread is a child of this one: start it from a daemon thread so it never keeps the game alive
            Thread starter = new Thread(s::start, "entropybot-fast-start");
            starter.setDaemon(true);
            starter.start();
            starter.join(5000);
            http = s;
            open = true;
            return "ok: fast channel on 127.0.0.1:" + port();
        } catch (IOException | InterruptedException | RuntimeException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            // the starter may have started it already: never leave a listening socket behind
            if (s != null) {
                try { s.stop(0); } catch (RuntimeException ignored) {}
            }
            if (pool != null) pool.shutdownNow();
            pool = null;
            return "error: fast channel: " + e;
        }
    }

    public synchronized boolean running() { return http != null; }

    /** The bound port (the real one when 0 was asked for), or -1. */
    public synchronized int port() { return http == null ? -1 : http.getAddress().getPort(); }

    /** The bound address (always the loopback one). */
    public synchronized InetSocketAddress address() { return http == null ? null : http.getAddress(); }

    public synchronized void stop() {
        open = false;
        if (http != null) http.stop(0);
        http = null;
        if (pool != null) pool.shutdownNow();
        pool = null;
        List<Waiter> w;
        synchronized (waiters) {
            w = new ArrayList<>(waiters);
            waiters.clear();
        }
        for (Waiter x : w) x.done.complete(false);
        tasks.clear();             // nothing runs any more; a queued command's caller times out
    }

    /** Once a game tick, on the game thread: runs the queued requests and answers the waits that are due. */
    public void tick() {
        long t = ++ticks;
        Runnable r;
        int n = 0;
        while (n++ < TASKS_PER_TICK && (r = tasks.poll()) != null) {
            try {
                r.run();
            } catch (RuntimeException e) {
                log.accept("[entropybot] fast channel task: " + e);
            }
        }
        List<Waiter> due = new ArrayList<>();
        synchronized (waiters) {
            if (waiters.isEmpty()) return;
            String[] busy = new String[2];
            boolean[] asked = new boolean[2];
            for (Iterator<Waiter> it = waiters.iterator(); it.hasNext(); ) {
                Waiter w = it.next();
                if (w.done.isDone()) {
                    it.remove();
                    continue;
                }
                int i = w.chain ? 1 : 0;
                if (!asked[i]) {
                    asked[i] = true;
                    try {
                        busy[i] = handler.busy(w.chain);
                    } catch (RuntimeException e) {
                        busy[i] = "unknown (" + e + ")";
                    }
                }
                if (busy[i] != null) {
                    w.busy = busy[i];
                    w.idleSince = -1;
                    continue;
                }
                if (w.idleSince < 0) w.idleSince = t;
                if (t - w.idleSince + 1 >= SETTLE_TICKS) {
                    it.remove();
                    due.add(w);
                }
            }
        }
        if (due.isEmpty()) return;
        try {
            handler.beforeIdleAnswer();
        } catch (RuntimeException e) {
            log.accept("[entropybot] fast channel state: " + e);
        }
        for (Waiter w : due) w.done.complete(true);
    }

    // ---- HTTP (the server's threads) ----

    private void serve(HttpExchange ex) throws IOException {
        try (ex) {
            if (!open) {
                send(ex, 503, err("shutting down"));
                return;
            }
            if (!hostOk(ex.getRequestHeaders().getFirst("Host"))) {
                send(ex, 403, err("bad host"));
                return;
            }
            if (!keyOk(ex.getRequestHeaders().getFirst("X-Bot-Key"))) {
                drain(ex.getRequestBody());
                send(ex, 401, err("key"));
                return;
            }
            URI u = ex.getRequestURI();
            Map<String, String> q = query(u.getRawQuery());
            String path = u.getPath(), method = ex.getRequestMethod();
            switch (path) {
                case "/ping" -> ping(ex);
                case "/state" -> {
                    if (!method.equals("GET")) {
                        send(ex, 405, err("GET only"));
                        return;
                    }
                    state(ex);
                }
                case "/cmd" -> {
                    if (!method.equals("POST")) {
                        send(ex, 405, err("POST only"));
                        return;
                    }
                    cmd(ex, q);
                }
                case "/wait" -> waitIdle(ex, q);
                case "/block", "/column" -> {
                    if (!method.equals("GET")) {
                        send(ex, 405, err("GET only"));
                        return;
                    }
                    boolean col = path.equals("/column");
                    int[] v = ints(q, col ? new String[] {"x", "z"} : new String[] {"x", "y", "z"});
                    if (v == null) {
                        send(ex, 400, err(col ? "x and z must be whole numbers" : "x, y and z must be whole numbers"));
                        return;
                    }
                    onGame(ex, () -> col ? handler.column(v[0], v[1]) : handler.block(v[0], v[1], v[2]));
                }
                default -> send(ex, 404, err("unknown path " + path));
            }
        } catch (RuntimeException e) {
            try { send(ex, 500, err(String.valueOf(e))); } catch (IOException | RuntimeException ignored) {}
        }
    }

    private void ping(HttpExchange ex) throws IOException {
        CompletableFuture<JsonObject> f = new CompletableFuture<>();
        tasks.add(() -> {
            JsonObject o = handler.ping();
            JsonObject r = o == null ? new JsonObject() : o.deepCopy();
            r.addProperty("ok", true);
            r.addProperty("tick", ticks);
            f.complete(r);
        });
        try {
            send(ex, 200, f.get(5, TimeUnit.SECONDS));
        } catch (TimeoutException e) {
            send(ex, 504, err("the game did not tick for 5 s"));
        } catch (Exception e) {
            send(ex, 500, err(String.valueOf(e)));
        }
    }

    // /state: one build per tick, shared by every /state call waiting for that tick
    private final Object stateLock = new Object();
    private CompletableFuture<JsonObject> statePending;
    private long stateTick = -1;
    private JsonObject stateCached;
    /** How many times /state built the state (tests). */
    final AtomicInteger stateBuilds = new AtomicInteger();

    private void state(HttpExchange ex) throws IOException {
        CompletableFuture<JsonObject> f;
        synchronized (stateLock) {
            if (statePending == null) {
                CompletableFuture<JsonObject> mine = new CompletableFuture<>();
                statePending = mine;
                tasks.add(() -> {
                    JsonObject r;
                    synchronized (stateLock) {
                        if (statePending == mine) statePending = null;
                        r = stateTick == ticks ? stateCached : null;
                    }
                    try {
                        if (r == null) {
                            JsonObject o = handler.state();
                            r = o == null ? new JsonObject() : o;
                            stateBuilds.incrementAndGet();
                            synchronized (stateLock) {
                                stateTick = ticks;
                                stateCached = r;
                            }
                        }
                        mine.complete(r);
                    } catch (RuntimeException e) {
                        mine.completeExceptionally(e);
                    }
                });
            }
            f = statePending;
        }
        try {
            send(ex, 200, f.get(5, TimeUnit.SECONDS));
        } catch (TimeoutException e) {
            send(ex, 504, err("the game did not tick for 5 s"));
        } catch (Exception e) {
            send(ex, 500, err(String.valueOf(e)));
        }
    }

    private void cmd(HttpExchange ex, Map<String, String> q) throws IOException {
        byte[] body = readBody(ex.getRequestBody());
        if (body == null) {
            send(ex, 413, err("body too large"));
            return;
        }
        JsonObject cmd;
        try {
            cmd = JsonParser.parseString(new String(body, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (RuntimeException e) {
            send(ex, 400, err("body is not a JSON object"));
            return;
        }
        if (!cmd.has("type") || !cmd.get("type").isJsonPrimitive()) {
            send(ex, 400, err("no type"));
            return;
        }
        if (!cmd.has("id") || cmd.get("id").isJsonNull()) cmd.addProperty("id", "fast-" + System.currentTimeMillis() + "-" + nextId());
        String id = cmd.get("id").getAsString();
        long timeout = clamp(q.get("timeout"), CMD_TIMEOUT_MS, CMD_TIMEOUT_MAX);
        CompletableFuture<String> f = new CompletableFuture<>();
        // 0 = queued, 1 = the game thread took it (it runs), 2 = given up before it ran (it never will)
        AtomicInteger claim = new AtomicInteger();
        tasks.add(() -> {
            if (claim.compareAndSet(0, 1)) handler.command(cmd, f::complete);
        });
        JsonObject r = new JsonObject();
        r.addProperty("id", id);
        try {
            r.addProperty("result", f.get(timeout, TimeUnit.MILLISECONDS));
        } catch (TimeoutException e) {
            r.add("result", com.google.gson.JsonNull.INSTANCE);
            r.addProperty("timeout", true);
            // the game never got to it (frozen, or not ticking): it is dropped, so the caller may send it another way
            if (claim.compareAndSet(0, 2)) r.addProperty("notRun", true);
        } catch (Exception e) {
            r.addProperty("result", "error: " + e);
        }
        send(ex, 200, r);
    }

    private void waitIdle(HttpExchange ex, Map<String, String> q) throws IOException {
        long timeout = clamp(q.get("timeout"), WAIT_TIMEOUT_MS, WAIT_TIMEOUT_MAX);
        boolean chain = !"0".equals(q.get("chain"));
        if (waiting.incrementAndGet() > MAX_WAITERS) {
            waiting.decrementAndGet();
            send(ex, 503, err("too many waits at once"));
            return;
        }
        Waiter w = new Waiter(chain);
        try {
            synchronized (waiters) {
                waiters.add(w);
            }
            boolean idle;
            try {
                idle = w.done.get(timeout, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                idle = false;
            } catch (Exception e) {
                idle = false;
            }
            w.done.complete(false);            // a timed-out waiter is dropped at the next tick
            JsonObject r = new JsonObject();
            r.addProperty("idle", idle);
            r.addProperty("waitedMs", System.currentTimeMillis() - w.startMs);
            if (!idle) {
                r.addProperty("timeout", true);
                if (w.busy != null) r.addProperty("busy", w.busy);
            }
            send(ex, 200, r);
        } finally {
            waiting.decrementAndGet();
        }
    }

    /** The named query values as ints, or null when one is missing or not a whole number. */
    static int[] ints(Map<String, String> q, String[] names) {
        int[] out = new int[names.length];
        for (int i = 0; i < names.length; i++) {
            String v = q.get(names[i]);
            if (v == null) return null;
            try {
                out[i] = Integer.parseInt(v.trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return out;
    }

    /** Runs a read on the game thread at its next tick: 200 with its object, 503 when not in a world (null), 504 after 5 s. */
    private void onGame(HttpExchange ex, java.util.function.Supplier<JsonObject> read) throws IOException {
        CompletableFuture<JsonObject> f = new CompletableFuture<>();
        tasks.add(() -> {
            try {
                f.complete(read.get());
            } catch (RuntimeException e) {
                f.completeExceptionally(e);
            }
        });
        try {
            JsonObject o = f.get(5, TimeUnit.SECONDS);
            if (o == null) send(ex, 503, err("not in a world"));
            else send(ex, 200, o);
        } catch (TimeoutException e) {
            send(ex, 504, err("the game did not tick for 5 s"));
        } catch (Exception e) {
            send(ex, 500, err(String.valueOf(e.getCause() != null ? e.getCause() : e)));
        }
    }

    private synchronized int nextId() { return ++nextId; }

    boolean keyOk(String given) {
        byte[] k = keyFile.key();
        if (k == null || given == null) return false;
        return MessageDigest.isEqual(k, given.trim().getBytes(StandardCharsets.UTF_8));
    }

    static boolean hostOk(String host) {
        if (host == null) return false;
        String h = host.trim().toLowerCase();
        int colon = h.lastIndexOf(':');
        if (colon > 0 && h.indexOf(']') < colon) h = h.substring(0, colon);
        return h.equals("127.0.0.1") || h.equals("localhost") || h.equals("[::1]");
    }

    static long clamp(String v, long dflt, long max) {
        if (v == null) return dflt;
        try {
            long n = Long.parseLong(v.trim());
            return n <= 0 ? dflt : Math.min(n, max);
        } catch (NumberFormatException e) {
            return dflt;
        }
    }

    static Map<String, String> query(String raw) {
        Map<String, String> m = new HashMap<>();
        if (raw == null || raw.isEmpty()) return m;
        for (String part : raw.split("&")) {
            int eq = part.indexOf('=');
            String k = eq < 0 ? part : part.substring(0, eq), v = eq < 0 ? "" : part.substring(eq + 1);
            m.put(java.net.URLDecoder.decode(k, StandardCharsets.UTF_8), java.net.URLDecoder.decode(v, StandardCharsets.UTF_8));
        }
        return m;
    }

    private static byte[] readBody(InputStream in) throws IOException {
        byte[] b = in.readNBytes(MAX_BODY + 1);
        return b.length > MAX_BODY ? null : b;
    }

    private static void drain(InputStream in) {
        try { in.readNBytes(MAX_BODY); } catch (IOException ignored) {}
    }

    private static JsonObject err(String why) {
        JsonObject o = new JsonObject();
        o.addProperty("error", why);
        return o;
    }

    private static void send(HttpExchange ex, int code, JsonObject body) throws IOException {
        byte[] b = body.toString().getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(code, b.length);
        try (OutputStream o = ex.getResponseBody()) {
            o.write(b);
        }
    }
}
