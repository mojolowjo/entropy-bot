package io.github.mojolowjo.entropybot.commands;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * B7e (E1): the pure rules of the {@code debug} verbs ({@link DebugVerbs} reads the game): who may use them and from
 * where, the list, the argument parsing (boxes with the 4096-cell cap, time words) and the JSON of a block slice.
 * The verbs replace the bridge's {@code eval}: read-only, owner only, never from a PM (PMs travel through server chat).
 */
public final class DebugRules {
    private DebugRules() {}

    /** Where a command came from: a PM (in-game chat), or this laptop (the fast channel, cmd.json: bridge.ps1, the dashboard). */
    public enum Source { PM, LOCAL }

    public static final int MAX_CELLS = 4096;
    public static final int EVENTS_DEFAULT = 20, EVENTS_MAX = 300;
    public static final int CHANGES_R_DEFAULT = 8, CHANGES_R_MAX = 64, CHANGES_MAX = 50;
    public static final int TRAIL_MINUTES_DEFAULT = 10, TRAIL_MINUTES_MAX = 24 * 60, TRAIL_MAX = 200;
    public static final int INCIDENTS_LIST = 10;

    public static final String PM_REFUSAL = "debug only from the laptop or the dashboard (bridge.ps1 do debug ..., or the dashboard's command box)";

    public static final String LIST = "debug (read-only): gui | inv | block x y z | blocks x1 y1 z1 x2 y2 z2 [at <time>] (" + MAX_CELLS
            + " blocks at most) | baritone | events [n] | guard x y z | changes x y z [r] [since <time>] | trail [minutes] | incident [n]"
            + " - times: HH:mm (today), -10m, -2h, or epoch ms";

    /** Null when this caller may use debug, else the refusal. */
    public static String gate(Source source, boolean isOwner, String owner) {
        if (source != Source.LOCAL) return PM_REFUSAL;
        if (!isOwner) return "only " + owner + " can use debug";
        return null;
    }

    public static List<String> words(String s) {
        List<String> out = new ArrayList<>();
        for (String w : (s == null ? "" : s.trim()).split("\\s+")) if (!w.isEmpty()) out.add(w);
        return out;
    }

    /** n whole numbers from w[from..]; IllegalArgumentException with the usage when they aren't there. */
    public static int[] ints(List<String> w, int from, int n, String usage) {
        if (w.size() < from + n) throw new IllegalArgumentException(usage);
        int[] out = new int[n];
        for (int i = 0; i < n; i++) {
            try {
                out[i] = Integer.parseInt(w.get(from + i));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(usage);
            }
        }
        return out;
    }

    /** An optional whole number at w[i], kept within lo..hi; def when it is missing. */
    public static int optInt(List<String> w, int i, int def, int lo, int hi, String usage) {
        if (w.size() <= i) return def;
        try {
            return Math.max(lo, Math.min(hi, Integer.parseInt(w.get(i))));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(usage);
        }
    }

    /** A box, corners in any order. */
    public record Box(int x1, int y1, int z1, int x2, int y2, int z2) {
        public static Box of(int[] c) {
            return new Box(Math.min(c[0], c[3]), Math.min(c[1], c[4]), Math.min(c[2], c[5]), Math.max(c[0], c[3]), Math.max(c[1], c[4]), Math.max(c[2], c[5]));
        }

        public int dx() { return x2 - x1 + 1; }

        public int dy() { return y2 - y1 + 1; }

        public int dz() { return z2 - z1 + 1; }

        /** Counted in long from the corners (an int dx overflows for a box across the whole int range). */
        public long cells() {
            long a = (long) x2 - x1 + 1, b = (long) y2 - y1 + 1, c = (long) z2 - z1 + 1;
            if (a > MAX_CELLS || b > MAX_CELLS || c > MAX_CELLS) return Long.MAX_VALUE;
            return a * b * c;
        }

        /** The index of x y z in a slice: ((y - y1) * dz + (z - z1)) * dx + (x - x1) (the Recorder.Slice order). */
        public int index(int x, int y, int z) { return ((y - y1) * dz() + (z - z1)) * dx() + (x - x1); }
    }

    public static final String BLOCKS_USAGE = "usage: debug blocks x1 y1 z1 x2 y2 z2 [at <time>] (" + MAX_CELLS + " blocks at most)";

    /** "x1 y1 z1 x2 y2 z2" from w[1..]: the box, refused over 4096 cells. */
    public static Box box(List<String> w) {
        Box b = Box.of(ints(w, 1, 6, BLOCKS_USAGE));
        if (b.cells() > MAX_CELLS) {
            throw new IllegalArgumentException("that box has " + b.cells() + " blocks (" + b.dx() + " x " + b.dy() + " x " + b.dz() + "); " + MAX_CELLS + " at most, e.g. 16 x 16 x 16");
        }
        return b;
    }

    /** The word after {@code key} (e.g. "at", "since"), or null when key isn't there. */
    public static String after(List<String> w, String key) {
        for (int i = 0; i < w.size(); i++) {
            if (w.get(i).equalsIgnoreCase(key)) {
                if (i + 1 >= w.size()) throw new IllegalArgumentException("\"" + key + "\" needs a time: HH:mm, -10m, -2h or epoch ms");
                return w.get(i + 1);
            }
        }
        return null;
    }

    private static final Pattern HHMM = Pattern.compile("^(\\d{1,2}):(\\d{2})$");
    private static final Pattern AGO = Pattern.compile("^-(\\d+)([smhd])$", Pattern.CASE_INSENSITIVE);
    private static final Pattern EPOCH = Pattern.compile("^\\d{10,}$");

    /** A time word: "HH:mm" (today, local time), "-10m" / "-2h" / "-30s" / "-1d" (before now), "now", or epoch ms. */
    public static long time(String word, long now, ZoneId zone) {
        String t = word == null ? "" : word.trim();
        if (t.equalsIgnoreCase("now")) return now;
        Matcher m = HHMM.matcher(t);
        if (m.find()) {
            int h = Integer.parseInt(m.group(1)), min = Integer.parseInt(m.group(2));
            if (h > 23 || min > 59) throw new IllegalArgumentException("bad time \"" + t + "\"");
            LocalDate today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate();
            return ZonedDateTime.of(today, LocalTime.of(h, min), zone).toInstant().toEpochMilli();
        }
        m = AGO.matcher(t);
        if (m.find()) {
            long n = Long.parseLong(m.group(1));
            long unit = switch (m.group(2).toLowerCase()) {
                case "s" -> 1000L;
                case "m" -> 60_000L;
                case "h" -> 3_600_000L;
                default -> 86_400_000L;
            };
            return now - n * unit;
        }
        if (EPOCH.matcher(t).find()) {
            try {
                return Long.parseLong(t);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("bad time \"" + t + "\"");
            }
        }
        throw new IllegalArgumentException("bad time \"" + t + "\" (use HH:mm, -10m, -2h or epoch ms)");
    }

    /** "12:41:05" today, "10-03 12:41:05" before. */
    public static String timeText(long ms, long now, ZoneId zone) {
        ZonedDateTime t = Instant.ofEpochMilli(ms).atZone(zone);
        boolean today = t.toLocalDate().equals(Instant.ofEpochMilli(now).atZone(zone).toLocalDate());
        return t.format(DateTimeFormatter.ofPattern(today ? "HH:mm:ss" : "MM-dd HH:mm:ss"));
    }

    /**
     * A block slice as JSON: {"box":[x1,y1,z1,x2,y2,z2], "order":"y,z,x", "palette":[ids], "cells":[palette index or
     * -1 where nothing is known], "note": ...}. ids in the Recorder.Slice order (Box.index).
     */
    public static String sliceJson(Box b, String[] ids, String note) {
        JsonObject o = new JsonObject();
        JsonArray box = new JsonArray();
        for (int v : new int[]{b.x1(), b.y1(), b.z1(), b.x2(), b.y2(), b.z2()}) box.add(v);
        o.add("box", box);
        o.addProperty("order", "y,z,x");
        Map<String, Integer> pal = new LinkedHashMap<>();
        JsonArray cells = new JsonArray();
        for (String id : ids) {
            if (id == null) {
                cells.add(-1);
                continue;
            }
            Integer k = pal.get(id);
            if (k == null) {
                k = pal.size();
                pal.put(id, k);
            }
            cells.add(k);
        }
        JsonArray palette = new JsonArray();
        pal.keySet().forEach(palette::add);
        o.add("palette", palette);
        o.add("cells", cells);
        if (note != null) o.addProperty("note", note);
        return o.toString();
    }
}
