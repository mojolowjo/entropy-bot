package io.github.mojolowjo.entropycompanion;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpConnectTimeoutException;
import java.net.ConnectException;
import java.util.function.LongSupplier;

/**
 * Sends a command line to the dashboard's {@code /api/cmd} (it runs as the owner there, so the key is the whole
 * authority: it goes only in the X-Key header and is masked out of every text this class returns). Plain Java with
 * the network behind {@link Http} for the tests (COMPANION_PLAN 2.6):
 * <ul>
 * <li>at most 2 commands a second and 1 in flight ({@link #admit});</li>
 * <li>no queue and no retries: a command that may have reached the bot is never sent twice;</li>
 * <li>connection refused: "dashboard unreachable - commands paused" once (the {@link Link} goes down), and while the
 *     link is down and the last failure is under {@link #DOWN_QUIET_MS} old, commands answer that at once;</li>
 * <li>403: the key text; another status: "the dashboard answered HTTP n".</li>
 * </ul>
 */
public final class CmdClient {
    /** The network: method GET or POST, the key as X-Key. Throws on no connection or a timeout. */
    public interface Http {
        Resp send(String method, URI uri, String key, String body, int timeoutMs) throws IOException;
    }

    public record Resp(int status, String body) {}

    /** Up/down shared by the position posts and the commands, so one notice covers both. */
    public static final class Link {
        private boolean down;
        private long lastFailure;

        /** True when this is a change to down (show the notice once). */
        public synchronized boolean failed(long now) {
            lastFailure = now;
            boolean change = !down;
            down = true;
            return change;
        }

        /** True when this is a change to up ("connected"). */
        public synchronized boolean worked() {
            boolean change = down;
            down = false;
            return change;
        }

        public synchronized boolean down() { return down; }

        synchronized long lastFailure() { return lastFailure; }
    }

    public static final int TIMEOUT_MS = 12_000;        // the dashboard waits up to 6 s for the bot, + the file path
    public static final long MIN_GAP_MS = 500;          // 2 a second
    public static final long DOWN_QUIET_MS = 5_000;
    public static final String UNREACHABLE = "dashboard unreachable - commands paused";
    public static final String KEY_REFUSED = "the dashboard refused the key (403) - check config/" + CompanionConfig.FILE_NAME;
    public static final String WAITING = "still waiting for the last answer";
    public static final String TOO_FAST = "slow down: at most 2 commands a second";

    private final Http http;
    private final Link link;
    private final LongSupplier clock;
    private boolean inFlight;
    private long lastSend = Long.MIN_VALUE / 2;
    // counters for /bot companion
    private int sent, failed;
    private String lastError = "none";
    private long lastReplyAt;

    public CmdClient(Http http, Link link, LongSupplier clock) {
        this.http = http;
        this.link = link;
        this.clock = clock;
    }

    /** Called before a command is handed to the worker: null = go (now in flight), else the text to show instead. */
    public synchronized String admit() {
        long now = clock.getAsLong();
        if (inFlight) return WAITING;
        if (now - lastSend < MIN_GAP_MS) return TOO_FAST;
        if (link.down() && now - link.lastFailure() < DOWN_QUIET_MS) return UNREACHABLE;
        inFlight = true;
        lastSend = now;
        return null;
    }

    /** Sends one admitted command (on the worker thread) and returns the reply to show. Never throws; releases the slot. */
    public String run(CompanionConfig c, String text) {
        try {
            if (!c.active()) return c.enabled ? "fill in url and key in config/" + CompanionConfig.FILE_NAME : "the companion is disabled in its config";
            URI uri = c.apiUri("/api/cmd");
            if (uri == null) return "the url in config/" + CompanionConfig.FILE_NAME + " is not an http address";
            Resp r;
            try {
                r = http.send("POST", uri, c.key, text, TIMEOUT_MS);
            } catch (ConnectException | HttpConnectTimeoutException e) {
                // never reached the dashboard: the command did not run
                return failure(c, UNREACHABLE, e);
            } catch (IOException | RuntimeException e) {
                return failure(c, "no answer from the dashboard (" + e.getClass().getSimpleName() + ") - the command may still run", e);
            }
            link.worked();
            synchronized (this) { sent++; lastReplyAt = clock.getAsLong(); }
            if (r.status() == 403) return note(c, KEY_REFUSED);
            if (r.status() != 200) return note(c, "the dashboard answered HTTP " + r.status());
            return mask(c, result(r.body()));
        } catch (RuntimeException e) {
            return failure(c, "companion error: " + e.getClass().getSimpleName(), e);
        } finally {
            synchronized (this) { inFlight = false; }
        }
    }

    /** {"result": "..."} -> the text; anything else as is (cut). */
    static String result(String body) {
        if (body == null) return "(empty answer)";
        try {
            JsonElement e = JsonParser.parseString(body);
            if (e.isJsonObject()) {
                JsonObject o = e.getAsJsonObject();
                if (o.has("result") && o.get("result").isJsonPrimitive()) return o.get("result").getAsString();
            }
        } catch (RuntimeException ignored) {}
        return body.length() > 200 ? body.substring(0, 200) : body;
    }

    private String failure(CompanionConfig c, String text, Exception e) {
        link.failed(clock.getAsLong());
        synchronized (this) { failed++; }
        return note(c, text);
    }

    private synchronized String note(CompanionConfig c, String text) {
        String t = mask(c, text);
        lastError = t;
        return t;
    }

    static String mask(CompanionConfig c, String s) {
        if (s == null) return "";
        return c.key == null || c.key.isEmpty() ? s : s.replace(c.key, "***");
    }

    public synchronized int sent() { return sent; }
    public synchronized int failed() { return failed; }
    public synchronized String lastError() { return lastError; }
    public synchronized long lastReplyAt() { return lastReplyAt; }
}
