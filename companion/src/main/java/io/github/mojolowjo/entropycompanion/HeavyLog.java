package io.github.mojolowjo.entropycompanion;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Companion 0.5.0 "heavy log": the pure helpers (no Minecraft classes, unit-tested). The Minecraft side is in
 * {@link ActionLogMc}. Field values are kept to what the dashboard accepts (strings, numbers, booleans, one flat
 * object), so lists are compact strings: a mob list is {@code kind,dist,los,dY;...}, a column sketch is 81 letters.
 */
public final class HeavyLog {
    private HeavyLog() {}

    public static final int MOB_RADIUS = 24, FIGHT_RADIUS = 16, SEEN_RADIUS = 16, SKETCH = 9, MAX_MOBS = 12;
    static final Pattern WORD = Pattern.compile("^[a-z0-9_-]{1,24}$");
    /** The suggested one-word reasons (any other single word works too). */
    static final List<String> WORDS = List.of("bag", "dark", "food", "ore", "mobs", "done");

    /** {@code minecraft:zombie} -> {@code zombie}; modded ids keep their namespace. */
    static String shortId(String id) {
        return id == null ? "" : id.startsWith("minecraft:") ? id.substring(10) : id;
    }

    /** A {@code /bot log why} word, lower case, or null when it is not one word. */
    static String whyWord(String arg) {
        String w = arg == null ? "" : arg.trim().toLowerCase(Locale.ROOT);
        return WORD.matcher(w).matches() ? w : null;
    }

    /** One hostile for the mob string. */
    record Mob(String kind, double dist, boolean los, int dy) {}

    /** The nearest {@link #MAX_MOBS} as {@code kind,dist,los,dY;...} (dist whole blocks, los 1/0), at most 256 chars. */
    static String mobs(List<Mob> in) {
        List<Mob> l = new ArrayList<>(in);
        l.sort((a, b) -> Double.compare(a.dist(), b.dist()));
        StringBuilder b = new StringBuilder();
        int n = 0;
        for (Mob m : l) {
            if (n++ >= MAX_MOBS) break;
            String one = shortId(m.kind()) + "," + Math.round(m.dist()) + "," + (m.los() ? 1 : 0) + "," + m.dy();
            if (b.length() + one.length() + 1 > 256) break;
            if (b.length() > 0) b.append(';');
            b.append(one);
        }
        return b.toString();
    }

    /** One column of the 9x9 sketch: the floor's height relative to the player (-5..4), or null for none. */
    static char sketchChar(Integer dy) {
        if (dy == null || dy < -5 || dy > 4) return '.';
        return (char) ('a' + dy + 5);           // a = 5 below the feet, f = level with them, j = 4 above
    }

    /** The 81-letter sketch, rows north to south (z), columns west to east (x). */
    static String sketch(Integer[] dys) {
        StringBuilder b = new StringBuilder(SKETCH * SKETCH);
        for (Integer d : dys) b.append(sketchChar(d));
        return b.toString();
    }

    /** {@code count/nearest} for the seen counts. */
    static String seenValue(int count, double nearest) {
        return count + "/" + Math.round(nearest);
    }

    /** What a block id says about the owner's activity: mining, chopping, farming, digging, or null. */
    static String blockActivity(String id, boolean ore, boolean log) {
        if (id == null) return null;
        String s = id.toLowerCase(Locale.ROOT);
        if (ore || s.endsWith("_ore") || s.contains("ore_")) return "mining";
        if (log || s.endsWith("_log") || s.endsWith("_stem") || s.endsWith("_wood") || s.endsWith("leaves")) return "chopping";
        if (s.endsWith("wheat") || s.endsWith("carrots") || s.endsWith("potatoes") || s.endsWith("beetroots") || s.contains("_crop")
                || s.startsWith("mysticalagriculture:") || s.endsWith("melon") || s.endsWith("pumpkin") || s.endsWith("sugar_cane")) return "farming";
        if (s.contains("stone") || s.contains("deepslate") || s.endsWith("tuff") || s.endsWith("granite") || s.endsWith("diorite")
                || s.endsWith("andesite") || s.endsWith("netherrack") || s.endsWith("calcite")) return "mining";
        if (s.endsWith("dirt") || s.endsWith("grass_block") || s.endsWith("gravel") || s.endsWith("sand") || s.endsWith("clay")) return "digging";
        return null;
    }

    /**
     * The activity switch detector: actions are fed as labels; a new label becomes the activity after it is seen
     * {@link #NEED} times within {@link #WINDOW_MS} (fighting and sleeping at once). {@link #feed} returns the old
     * activity when a switch happened (the new one is {@link #current}), else null.
     */
    static final class Activity {
        static final int NEED = 3;
        static final long WINDOW_MS = 30_000;
        String current = "idle";
        private String pending;
        private int count;
        private long firstAt;

        String feed(String label, long now) {
            if (label == null) return null;
            if (label.equals(current)) {
                pending = null;
                return null;
            }
            if (!label.equals(pending) || now - firstAt > WINDOW_MS) {
                pending = label;
                count = 0;
                firstAt = now;
            }
            count++;
            boolean at = label.equals("fighting") || label.equals("sleeping") || count >= NEED;
            if (!at) return null;
            String old = current;
            current = label;
            pending = null;
            return old;
        }
    }

    /** Rough per-minute counters for /bot log status: events, bytes, and the heavy work's time per tick. */
    static final class Meter {
        private long minuteStart = -1, ev, by, nanos, ticks, maxNanos;
        long lastEv, lastBy, lastTicks, lastMaxNanos, lastNanos;
        boolean haveLast;

        synchronized void tick(long now, long workNanos) {
            roll(now);
            ticks++;
            nanos += workNanos;
            if (workNanos > maxNanos) maxNanos = workNanos;
        }

        synchronized void event(long now, int bytes) {
            roll(now);
            ev++;
            by += bytes;
        }

        private void roll(long now) {
            if (minuteStart < 0) minuteStart = now;
            if (now - minuteStart < 60_000) return;
            lastEv = ev; lastBy = by; lastTicks = ticks; lastNanos = nanos; lastMaxNanos = maxNanos;
            haveLast = true;
            ev = by = nanos = ticks = maxNanos = 0;
            minuteStart = now;
        }

        /** {@code 42 ev/min, 9 KB/min, 0.031 ms/tick avg (max 0.80)}: the last full minute, else the one so far. */
        synchronized String line(long now) {
            roll(now);
            long e = haveLast ? lastEv : ev, b = haveLast ? lastBy : by, t = haveLast ? lastTicks : ticks,
                    n = haveLast ? lastNanos : nanos, mx = haveLast ? lastMaxNanos : maxNanos;
            double avg = t == 0 ? 0 : n / 1e6 / t;
            return String.format(Locale.ROOT, "%d ev/min, %d KB/min, %.3f ms/tick avg (max %.2f)%s", e, b / 1024, avg, mx / 1e6,
                    haveLast ? "" : " so far");
        }

        synchronized double avgMs() {
            long t = haveLast ? lastTicks : ticks, n = haveLast ? lastNanos : nanos;
            return t == 0 ? 0 : n / 1e6 / t;
        }
    }

    /** A cheap signature of an inventory's totals (to skip a 60 s snapshot that would repeat the last one). */
    static long signature(Map<String, Integer> totals) {
        long h = 17;
        for (Map.Entry<String, Integer> e : new java.util.TreeMap<>(totals).entrySet())
            h = h * 31 + e.getKey().hashCode() * 7L + e.getValue();
        return h;
    }
}
