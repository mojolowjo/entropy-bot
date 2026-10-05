package io.github.mojolowjo.entropybot.routewalk;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The rows of {@code entropybot/routes/trips.csv} (one per {@code route test} trip) and the summary that compares the
 * modes. Pure: the game side appends {@link #row} (with {@link #HEADER} first when the file is new or empty).
 */
public final class TripLog {
    private TripLog() {
    }

    public static final String HEADER = "time,test,trip,from,to,mode,used,ok,seconds,blocks,searches,segments,stopped_s,"
            + "first_step_s,nodes,movements,search_ms,failures,debug_lines,lost_events,result";

    /**
     * One trip. mode: what the trip asked for (goal, legs, plain); used: what the walk really did
     * ({@link RouteWalk#used}). nodes/movements are -1 when no debug line came (chatDebug not reaching the ring).
     */
    public record Trip(String time, String test, int trip, String from, String to, String mode, String used, boolean ok,
                       double seconds, double blocks, int searches, int segments, double stoppedSeconds,
                       double firstStepSeconds, long nodes, long movements, long searchMs, int failures, int debugLines,
                       boolean lost, String result) {
    }

    /** The CSV line for a trip (no line break). */
    public static String row(Trip t) {
        List<String> f = new ArrayList<>();
        f.add(csv(t.time()));
        f.add(csv(t.test()));
        f.add(Integer.toString(t.trip()));
        f.add(csv(t.from()));
        f.add(csv(t.to()));
        f.add(csv(t.mode()));
        f.add(csv(t.used()));
        f.add(t.ok() ? "1" : "0");
        f.add(num(t.seconds()));
        f.add(num(t.blocks()));
        f.add(Integer.toString(t.searches()));
        f.add(Integer.toString(t.segments()));
        f.add(num(t.stoppedSeconds()));
        f.add(num(t.firstStepSeconds()));
        f.add(Long.toString(t.nodes()));
        f.add(Long.toString(t.movements()));
        f.add(Long.toString(t.searchMs()));
        f.add(Integer.toString(t.failures()));
        f.add(Integer.toString(t.debugLines()));
        f.add(t.lost() ? "1" : "0");
        f.add(csv(t.result()));
        return String.join(",", f);
    }

    static String num(double d) {
        return String.format(Locale.ROOT, "%.2f", d);
    }

    /** Quotes a field holding a comma, a quote or a line break (quotes doubled); line breaks become spaces. */
    static String csv(String s) {
        if (s == null) return "";
        String v = s.replace('\n', ' ').replace('\r', ' ');
        if (v.indexOf(',') >= 0 || v.indexOf('"') >= 0) return "\"" + v.replace("\"", "\"\"") + "\"";
        return v;
    }

    /** Splits one CSV line (the format {@link #row} writes). */
    public static List<String> split(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder b = new StringBuilder();
        boolean q = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (q) {
                if (c == '"' && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    b.append('"');
                    i++;
                } else if (c == '"') q = false;
                else b.append(c);
            } else if (c == '"') q = true;
            else if (c == ',') {
                out.add(b.toString());
                b.setLength(0);
            } else b.append(c);
        }
        out.add(b.toString());
        return out;
    }

    /** Averages per mode over the ok trips. */
    public record ModeSummary(String mode, int trips, int ok, double seconds, double nodes, double segments,
                              double stoppedSeconds, double searches) {
    }

    public static Map<String, ModeSummary> byMode(List<Trip> trips) {
        Map<String, List<Trip>> groups = new LinkedHashMap<>();
        for (String m : List.of("goal", "legs", "plain")) groups.put(m, new ArrayList<>());
        for (Trip t : trips) groups.computeIfAbsent(t.mode(), k -> new ArrayList<>()).add(t);
        Map<String, ModeSummary> out = new LinkedHashMap<>();
        for (Map.Entry<String, List<Trip>> e : groups.entrySet()) {
            List<Trip> all = e.getValue();
            if (all.isEmpty()) continue;
            int ok = 0, withNodes = 0;
            double s = 0, n = 0, seg = 0, st = 0, se = 0;
            for (Trip t : all) {
                if (!t.ok()) continue;
                ok++;
                s += t.seconds();
                seg += t.segments();
                st += t.stoppedSeconds();
                se += t.searches();
                if (t.nodes() >= 0 && t.debugLines() > 0) {
                    n += t.nodes();
                    withNodes++;
                }
            }
            out.put(e.getKey(), new ModeSummary(e.getKey(), all.size(), ok, ok == 0 ? 0 : s / ok,
                    withNodes == 0 ? -1 : n / withNodes, ok == 0 ? 0 : seg / ok, ok == 0 ? 0 : st / ok, ok == 0 ? 0 : se / ok));
        }
        return out;
    }

    /**
     * The summary: one line per mode (averages over its ok trips) and, for goal and legs, the change against plain
     * (time, nodes, segments). The pass mark (plan section 1 with the review): nodes at least halved, trip time no worse
     * than +5%, no new failures.
     */
    public static String summary(List<Trip> trips) {
        Map<String, ModeSummary> m = byMode(trips);
        if (m.isEmpty()) return "route test: no trips recorded";
        List<String> lines = new ArrayList<>();
        ModeSummary plain = m.get("plain");
        for (ModeSummary s : m.values()) {
            StringBuilder b = new StringBuilder();
            b.append(s.mode()).append(": ").append(s.ok()).append('/').append(s.trips()).append(" ok");
            if (s.ok() > 0) {
                b.append(String.format(Locale.ROOT, ", %.1f s, %.1f segments, %.1f s stopped, %.1f searches", s.seconds(),
                        s.segments(), s.stoppedSeconds(), s.searches()));
                b.append(s.nodes() < 0 ? ", nodes ?" : String.format(Locale.ROOT, ", %.0f nodes", s.nodes()));
            }
            if (plain != null && s != plain && s.ok() > 0 && plain.ok() > 0) {
                b.append(" | vs plain: time ").append(pct(s.seconds(), plain.seconds()));
                if (s.nodes() >= 0 && plain.nodes() > 0) b.append(", nodes ").append(pct(s.nodes(), plain.nodes()));
                b.append(String.format(Locale.ROOT, ", segments %+.1f", s.segments() - plain.segments()));
                int newFails = (s.trips() - s.ok()) - (plain.trips() - plain.ok());
                if (newFails > 0) b.append(", ").append(newFails).append(" more failed");
            }
            lines.add(b.toString());
        }
        return String.join("\n", lines);
    }

    static String pct(double v, double base) {
        if (base <= 0) return "?";
        return String.format(Locale.ROOT, "%+.0f%%", (v - base) * 100.0 / base);
    }
}
