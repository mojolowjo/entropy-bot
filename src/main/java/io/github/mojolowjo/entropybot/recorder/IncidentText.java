package io.github.mojolowjo.entropybot.recorder;

import io.github.mojolowjo.entropybot.recorder.Recorder.Change;
import io.github.mojolowjo.entropybot.recorder.Recorder.TrailPoint;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Incidents (pure): when one is due ({@link #isFailure}, {@link GuardStreak}) and its text. The text is what
 * {@code debug incident n} shows: a header, each box as layers (top first) of one character per block with a legend,
 * then the trail and the changes of the last minutes.
 */
public final class IncidentText {
    private IncidentText() {}

    static final String SYMBOLS = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";
    /** BridgeLink.REPLACED and RELOADED (copied: this class stays free of game classes). */
    static final String REPLACED = "stopped: replaced by a new command", RELOADED = "interrupted: the bridge script reloaded";

    /** A job's end message that deserves a snapshot: anything not "ok...", except a plain stop or a replacement. */
    public static boolean isFailure(String msg) {
        if (msg == null) return false;
        String m = msg.trim();
        if (m.isEmpty() || m.startsWith("ok")) return false;
        if (m.equals("stopped") || m.equals(REPLACED) || m.equals(RELOADED)) return false;
        if (m.startsWith("done") || m.startsWith("finished")) return false;
        return true;
    }

    private static final Pattern XYZ = Pattern.compile("(-?\\d{1,8}) (-?\\d{1,4}) (-?\\d{1,8})");

    /** The first "x y z" in the text (the target or where it got stuck), or null. */
    public static int[] coords(String text) {
        if (text == null) return null;
        Matcher m = XYZ.matcher(text);
        if (!m.find()) return null;
        try {
            return new int[]{Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3))};
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Guard refusals within a window (ms): due() is true once the count reaches the limit, then quiet for the cool-down. */
    public static final class GuardStreak {
        final int limit;
        final long window, cooldown;
        private final ArrayDeque<Long> times = new ArrayDeque<>();
        private long lastFired = Long.MIN_VALUE / 2;

        public GuardStreak(int limit, long windowMs, long cooldownMs) {
            this.limit = limit;
            this.window = windowMs;
            this.cooldown = cooldownMs;
        }

        /** One refusal at nowMs; true when this one makes a streak (and the last streak is long enough ago). */
        public boolean refused(long nowMs) {
            times.addLast(nowMs);
            while (!times.isEmpty() && times.peekFirst() < nowMs - window) times.removeFirst();
            if (times.size() >= limit && nowMs - lastFired >= cooldown) {
                lastFired = nowMs;
                times.clear();
                return true;
            }
            return false;
        }

        public int count() { return times.size(); }
    }

    /** One box of the incident: ids[((y - y1) * dz + (z - z1)) * dx + (x - x1)], null = not loaded. */
    public record Box(String name, int x1, int y1, int z1, int x2, int y2, int z2, String[] ids) {}

    public static String render(long atMs, String reason, String job, String dim, int[] bot, int half, List<Box> boxes,
                                List<TrailPoint> trail, List<Change> changes, ZoneId zone) {
        StringBuilder b = new StringBuilder(8192);
        b.append("incident ").append(time(atMs, zone, "yyyy-MM-dd HH:mm:ss")).append(" (").append(atMs).append(")\n");
        b.append("reason: ").append(oneLine(reason)).append('\n');
        if (job != null) b.append("job: ").append(oneLine(job)).append('\n');
        b.append("bot: ").append(dim);
        if (bot != null) b.append(' ').append(bot[0]).append(' ').append(bot[1]).append(' ').append(bot[2]);
        b.append('\n');
        for (Box box : boxes) box(b, box, half);
        b.append("trail (").append(trail.size()).append(" points):\n");
        for (TrailPoint p : trail) {
            b.append("  ").append(time(p.atMs(), zone, "HH:mm:ss.SSS")).append(' ').append(p.x()).append(' ').append(p.y()).append(' ').append(p.z());
            if (!p.dim().equals(dim)) b.append(' ').append(p.dim());
            if (p.note() != null) b.append("  ").append(p.note());
            b.append('\n');
        }
        b.append("changes (").append(changes.size()).append(", newest first):\n");
        for (Change c : changes) {
            b.append("  ").append(time(c.atMs(), zone, "HH:mm:ss.SSS")).append(' ').append(c.x()).append(' ').append(c.y()).append(' ').append(c.z())
                    .append(' ').append(RecStore.shortId(c.from())).append(" -> ").append(RecStore.shortId(c.to())).append(c.byBot() ? " (bot)" : "").append('\n');
        }
        return b.toString();
    }

    static boolean isAir(String id) {
        return id != null && (id.equals("minecraft:air") || id.equals("minecraft:cave_air") || id.equals("minecraft:void_air"));
    }

    private static void box(StringBuilder b, Box box, int half) {
        int dx = box.x2() - box.x1() + 1, dz = box.z2() - box.z1() + 1;
        Map<String, Character> sym = new LinkedHashMap<>();
        Map<String, Integer> counts = new LinkedHashMap<>();
        boolean other = false;
        for (String id : box.ids()) {
            if (id == null || isAir(id)) continue;
            counts.merge(id, 1, Integer::sum);
        }
        // the commonest blocks get the first symbols
        counts.entrySet().stream().sorted((a, c) -> c.getValue() - a.getValue()).forEach(e -> {
            if (sym.size() < SYMBOLS.length()) sym.put(e.getKey(), SYMBOLS.charAt(sym.size()));
        });
        b.append("box ").append(box.name()).append(": ").append(box.x1()).append(' ').append(box.y1()).append(' ').append(box.z1())
                .append(" .. ").append(box.x2()).append(' ').append(box.y2()).append(' ').append(box.z2()).append(" (half ").append(half).append(")\n");
        StringBuilder legend = new StringBuilder("legend: .=air ?=not loaded");
        for (Map.Entry<String, Character> e : sym.entrySet()) legend.append(' ').append(e.getValue()).append('=').append(RecStore.shortId(e.getKey()));
        if (counts.size() > sym.size()) { legend.append(" *=other"); other = true; }
        b.append(legend).append('\n');
        b.append("layers top first; each row is z, x from ").append(box.x1()).append(" to ").append(box.x2()).append('\n');
        for (int y = box.y2(); y >= box.y1(); y--) {
            b.append("y=").append(y).append('\n');
            for (int z = box.z1(); z <= box.z2(); z++) {
                b.append(String.format(Locale.ROOT, "  z=%-7d ", z));
                for (int x = box.x1(); x <= box.x2(); x++) {
                    String id = box.ids()[((y - box.y1()) * dz + (z - box.z1())) * dx + (x - box.x1())];
                    char ch;
                    if (id == null) ch = '?';
                    else if (isAir(id)) ch = '.';
                    else {
                        Character c = sym.get(id);
                        ch = c != null ? c : '*';
                    }
                    b.append(ch);
                }
                b.append('\n');
            }
        }
        if (other) b.append("(* = a block beyond the ").append(SYMBOLS.length()).append(" commonest)\n");
    }

    static String oneLine(String s) { return s == null ? "" : s.replace('\n', ' ').replace('\r', ' '); }

    static String time(long ms, ZoneId zone, String pattern) {
        return DateTimeFormatter.ofPattern(pattern, Locale.ROOT).format(Instant.ofEpochMilli(ms).atZone(zone));
    }
}
