package io.github.mojolowjo.entropycompanion;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Companion 0.4.0: the owner's action log, the pure part. Events are built on the client thread ({@link #ev}), finished
 * into one JSON line each and put in a bounded buffer; a worker drains it ({@link #drain}) into {@link LogStore} and
 * {@link LogUploader} posts the files. Every event carries {@code t} (epoch ms), {@code seq} (per session),
 * {@code session}, {@code type}, {@code game} (day, time of day), {@code dim} and {@code x y z} (rounded).
 *
 * <p>Privacy: no other player's name ever goes in (only a count of players within 16), the world or server is a hash
 * of its name ({@link #hash}), never an address, and chat is only the owner's own /bot lines.
 */
public final class ActionLog {
    public static final int BUFFER_MAX = 20_000;
    static final int[] HUNGER_MARKS = {6, 10, 14, 18};
    private static final SecureRandom RANDOM = new SecureRandom();

    static ActionLog INSTANCE;

    private final ArrayDeque<String> buf = new ArrayDeque<>();
    private String session = newId();
    private long seq;
    volatile long emitted, dropped;
    // the context, set by the client tick (client thread only; no allocation)
    long gameDay;
    int tod;
    String dim = "";
    int x, y, z;

    static String newId() {
        byte[] b = new byte[4];
        RANDOM.nextBytes(b);
        return HexFormat.of().formatHex(b);
    }

    /** Starts a new session (a join): a new id, seq from 0. */
    synchronized void newSession() {
        session = newId();
        seq = 0;
    }

    synchronized String session() { return session; }

    void context(long dayTime, String dim, double px, double py, double pz) {
        this.gameDay = dayTime / 24000L;
        this.tod = (int) (dayTime % 24000L);
        this.dim = dim;
        this.x = (int) Math.floor(px);
        this.y = (int) Math.floor(py);
        this.z = (int) Math.floor(pz);
    }

    /** A new event with the common fields; add its own ones and hand it to {@link #add}. */
    LogEvent ev(String type, long now) {
        long s;
        String id;
        synchronized (this) {
            s = seq++;
            id = session;
        }
        LogEvent e = new LogEvent();
        e.num("t", now).num("seq", s).str("session", id).str("type", type);
        e.raw("game", "{\"day\":" + gameDay + ",\"tod\":" + tod + "}");
        if (!dim.isEmpty()) e.str("dim", dim);
        return e.num("x", x).num("y", y).num("z", z);
    }

    /** 0.5.0: events and bytes a minute, and the heavy work's time a tick (for /bot log status). */
    final HeavyLog.Meter meter = new HeavyLog.Meter();

    void add(LogEvent e) {
        String line = e.finish();
        meter.event(System.currentTimeMillis(), line.length() + 1);
        synchronized (buf) {
            buf.addLast(line);
            emitted++;
            while (buf.size() > BUFFER_MAX) {
                buf.removeFirst();
                dropped++;
            }
        }
    }

    List<String> drain() {
        synchronized (buf) {
            List<String> out = new ArrayList<>(buf);
            buf.clear();
            return out;
        }
    }

    int buffered() {
        synchronized (buf) {
            return buf.size();
        }
    }

    /** A world or server name as 12 hex characters: the same name gives the same hash, the name itself never leaves. */
    static String hash(String name) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(("entropy-companion:" + (name == null ? "" : name.trim().toLowerCase(Locale.ROOT)))
                    .getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d).substring(0, 12);
        } catch (Exception e) {
            return "unknown";
        }
    }

    /** The hunger mark crossed going from food a to b (either way), or -1. */
    static int hungerCrossed(int a, int b) {
        for (int m : HUNGER_MARKS) if ((a > m && b <= m) || (a <= m && b > m)) return m;
        return -1;
    }

    /** The diff of two item -> count totals: what went down (in: put into the container) and up (out: taken). */
    static void diff(Map<String, Integer> before, Map<String, Integer> after, Map<String, Integer> in, Map<String, Integer> out) {
        for (Map.Entry<String, Integer> e : before.entrySet()) {
            int d = e.getValue() - after.getOrDefault(e.getKey(), 0);
            if (d > 0) in.put(e.getKey(), d);
        }
        for (Map.Entry<String, Integer> e : after.entrySet()) {
            int d = e.getValue() - before.getOrDefault(e.getKey(), 0);
            if (d > 0) out.put(e.getKey(), d);
        }
    }

    /** One JSON object, written by hand (no reflection, a single StringBuilder). */
    static final class LogEvent {
        private final StringBuilder b = new StringBuilder(192).append('{');

        private LogEvent key(String k) {
            if (b.length() > 1) b.append(',');
            b.append('"').append(k).append("\":");
            return this;
        }

        LogEvent str(String k, String v) {
            key(k);
            quote(b, v == null ? "" : v.length() > 256 ? v.substring(0, 256) : v);
            return this;
        }

        LogEvent num(String k, long v) {
            key(k).b.append(v);
            return this;
        }

        LogEvent dec(String k, double v) {
            key(k).b.append(Double.isFinite(v) ? String.format(Locale.ROOT, "%.1f", v) : "0");
            return this;
        }

        LogEvent bool(String k, boolean v) {
            key(k).b.append(v);
            return this;
        }

        LogEvent raw(String k, String json) {
            key(k).b.append(json);
            return this;
        }

        /** A flat item -> count object (at most 64 entries). */
        LogEvent counts(String k, Map<String, Integer> m) {
            key(k).b.append('{');
            int n = 0;
            for (Map.Entry<String, Integer> e : m.entrySet()) {
                if (n++ >= 64) break;
                if (n > 1) b.append(',');
                quote(b, e.getKey());
                b.append(':').append(e.getValue());
            }
            b.append('}');
            return this;
        }

        /** 0.5.0: a flat name -> short string object (at most 64 entries). */
        LogEvent strs(String k, Map<String, String> m) {
            key(k).b.append('{');
            int n = 0;
            for (Map.Entry<String, String> e : m.entrySet()) {
                if (n++ >= 64) break;
                if (n > 1) b.append(',');
                quote(b, e.getKey());
                b.append(':');
                quote(b, e.getValue());
            }
            b.append('}');
            return this;
        }

        String finish() {
            return b.append('}').toString();
        }

        static void quote(StringBuilder b, String s) {
            b.append('"');
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                switch (c) {
                    case '"' -> b.append("\\\"");
                    case '\\' -> b.append("\\\\");
                    case '\n' -> b.append("\\n");
                    case '\r' -> b.append("\\r");
                    case '\t' -> b.append("\\t");
                    default -> {
                        if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                        else b.append(c);
                    }
                }
            }
            b.append('"');
        }
    }
}
