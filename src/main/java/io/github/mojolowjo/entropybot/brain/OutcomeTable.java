package io.github.mojolowjo.entropybot.brain;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 0.26.5 (BRAIN_PLAN 4.5d, roadmap step 4): learned scores as a table, no runtime model. Per (job kind, bucket): ok,
 * failed and interrupted counts, the minutes to finish, the last outcome time, all decaying with a half-life. The
 * brain multiplies a need option's score by {@link #multiplier} and orders the idle list by it. Built from the
 * decision logs ({@link #replay}) or outcomes.json ({@link #fromJson}), updated at each outcome. Pure (Gson only).
 * Synchronized: the loader worker and the client thread both touch it.
 */
public final class OutcomeTable {
    public static final Set<String> STAGES = Set.of("nothing", "wood", "stone", "iron", "diamond");
    public static final int NEAR = 48;
    public static final int MAX_LINES = 20_000;
    static final double DAY_MS = 86_400_000.0;

    /** The settings it reads (BrainConfig brain.learn*). floor/ceil as fractions. */
    public record Params(boolean on, int min, double floor, double ceil, double halfLifeDays) {
        public static Params of(BrainConfig c) {
            return new Params(c.i("brain.learn") != 0, c.i("brain.learnMin"), c.i("brain.learnFloor") / 100.0,
                    c.i("brain.learnCeil") / 100.0, Math.max(1, c.i("brain.learnHalfLifeDays")));
        }

        public static Params defaults() { return new Params(true, 3, 0.6, 1.3, 7); }
    }

    /** One key's decayed counts, as of {@code at}. */
    public static final class Cell {
        double ok, failed, interrupted, minutes;      // minutes: decayed sum over the finished ones
        long at, last;
        String lastOutcome = "";

        void decayTo(long now, double halfLifeDays) {
            if (now <= at) return;
            double f = Math.pow(0.5, (now - at) / (halfLifeDays * DAY_MS));
            ok *= f;
            failed *= f;
            interrupted *= f;
            minutes *= f;
            at = now;
        }

        public double n() { return ok + failed; }

        public double meanMinutes() { return ok > 0 ? minutes / ok : 0; }
    }

    /** A pick's adjustment: the multiplier and the counts it came from (null cell: none, m 1). */
    public record Adjust(String kind, String bucket, double m, double ok, double n, boolean fallback) {
        /** "x0.8 (learned: mine cave any 2/7 ok, night, far)"; "" when m is 1. */
        public String note() {
            if (Math.abs(m - 1.0) < 0.005) return "";
            String[] b = bucket.split("\\|");
            String ctx = fallback ? "all contexts" : b.length == 3 ? b[1] + ", " + b[2] : bucket;
            return "x" + fmt(m) + " (learned: " + kind + " " + Math.round(ok) + "/" + Math.round(n) + " ok, " + ctx + ")";
        }
    }

    private final Map<String, Cell> cells = new LinkedHashMap<>();
    private long lastOutcomeT;
    private final Map<Long, String[]> pending = new LinkedHashMap<>();      // ref -> {kind, bucket, t}
    private int saveErrors;

    // ---- the key ----

    /** The job kind of a chain: its first step, numbers, coordinates, durations and namespaces cut, 3 words at most. */
    public static String kind(String chain) {
        if (chain == null) return null;
        String first = chain.trim().toLowerCase(Locale.ROOT).split("\\s+then\\s+", 2)[0];
        List<String> w = new ArrayList<>();
        for (String t : first.split("\\s+")) {
            if (t.isEmpty() || t.matches("^[-+~]?\\d+(\\.\\d+)?[smhd]?$") || t.matches("^x\\d+$")) continue;
            int c = t.indexOf(':');
            if (c > 0 && c < t.length() - 1) t = t.substring(c + 1);
            w.add(t);
            if (w.size() == 3) break;
        }
        return w.isEmpty() ? null : String.join(" ", w);
    }

    /** day / dusk / night from the night flag and the level's day time (-1 unknown). */
    public static String daypart(boolean night, long dayTime) {
        if (night) return "night";
        long t = dayTime < 0 ? -1 : dayTime % 24000;
        return t >= 11000 && t < 13000 ? "dusk" : "day";
    }

    /** near / far / unknown from the flat distance to the base (negative: unknown). */
    public static String dist(int baseDist) {
        return baseDist < 0 ? "unknown" : baseDist <= NEAR ? "near" : "far";
    }

    public static String bucket(String stage, String daypart, String dist) {
        String st = stage == null ? "?" : stage.toLowerCase(Locale.ROOT);
        return (STAGES.contains(st) ? st : "?") + "|" + daypart + "|" + dist;
    }

    /** The bucket of a sensed state. */
    public static String bucket(BrainState s) {
        int bd = s.basePos == null ? -1 : (int) Math.round(BrainState.flat(s.botPos, s.basePos));
        return bucket(s.stage, daypart(s.night, s.dayTime), dist(bd));
    }

    /** The bucket of a decision line's state (old lines: no daypart -> night flag; no baseDist -> unknown). */
    public static String bucket(JsonObject state) {
        String stage = str(state, "stage");
        String dp = str(state, "daypart");
        if (dp == null) dp = state != null && state.has("night") && state.get("night").getAsBoolean() ? "night" : "day";
        int bd = state != null && state.has("baseDist") && state.get("baseDist").isJsonPrimitive() ? state.get("baseDist").getAsInt() : -1;
        return bucket(stage, dp, dist(bd));
    }

    static String str(JsonObject o, String k) {
        return o != null && o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString() : null;
    }

    /** "ok" | "failed" | "interrupted" | null (not an end). */
    public static String cls(String outcome) {
        if (outcome == null) return null;
        if (outcome.equals("finished")) return "ok";
        if (outcome.startsWith("failed")) return "failed";
        if (outcome.startsWith("interrupted") || outcome.startsWith("overridden")) return "interrupted";
        return null;
    }

    // ---- updates ----

    /** A decision the brain logged (ref = its t): remembered until its outcome. */
    public synchronized void decision(long ref, String chosen, String bucket) {
        String k = kind(chosen);
        if (k == null) return;
        pending.put(ref, new String[]{k, bucket, String.valueOf(ref)});
        if (pending.size() > 500) pending.remove(pending.keySet().iterator().next());
    }

    /** The pending decisions of another table (the one that caught live decisions while this one loaded). */
    public void adoptPending(OutcomeTable other) {
        Map<Long, String[]> p;
        synchronized (other) { p = new LinkedHashMap<>(other.pending); }
        synchronized (this) { p.forEach(pending::putIfAbsent); }
    }

    /** The outcome of ref at t; false when skipped (unknown ref, not an end, or older than the table). */
    public synchronized boolean outcome(long t, long ref, String outcome, double halfLifeDays) {
        String[] p = pending.remove(ref);
        String c = cls(outcome);
        if (p == null || c == null || t < lastOutcomeT) return false;
        add(p[0], p[1], c, t, (t - Long.parseLong(p[2])) / 60000.0, outcome, halfLifeDays);
        lastOutcomeT = t;
        return true;
    }

    synchronized void add(String kind, String bucket, String cls, long t, double minutes, String outcome, double halfLifeDays) {
        Cell x = cells.computeIfAbsent(kind + "|" + bucket, k -> new Cell());
        if (x.at == 0) x.at = t;
        x.decayTo(t, halfLifeDays);
        switch (cls) {
            case "ok" -> { x.ok += 1; x.minutes += Math.max(0, minutes); }
            case "failed" -> x.failed += 1;
            default -> x.interrupted += 1;
        }
        x.last = Math.max(x.last, t);
        x.lastOutcome = outcome.length() > 120 ? outcome.substring(0, 120) : outcome;
    }

    /** Replays decision-log lines (oldest first); returns the outcomes counted. */
    public synchronized int replay(List<String> lines, double halfLifeDays) {
        int n = 0;
        for (String line : lines) {
            try {
                JsonElement e = JsonParser.parseString(line);
                if (!e.isJsonObject()) continue;
                JsonObject o = e.getAsJsonObject();
                if (o.has("ref") && o.has("outcome")) {
                    if (outcome(o.get("t").getAsLong(), o.get("ref").getAsLong(), o.get("outcome").getAsString(), halfLifeDays)) n++;
                } else if (o.has("chosen") && o.get("chosen").isJsonPrimitive() && !o.has("history") && o.has("t")) {
                    decision(o.get("t").getAsLong(), o.get("chosen").getAsString(), bucket(o.has("state") && o.get("state").isJsonObject() ? o.getAsJsonObject("state") : null));
                }
            } catch (RuntimeException ignored) {
                // a torn line: skipped
            }
        }
        pending.clear();
        return n;
    }

    // ---- reads ----

    /** The multiplier for a chain in this bucket at now. */
    public synchronized Adjust adjust(String chain, String bucket, long now, Params p) {
        String k = kind(chain);
        if (k == null || !p.on()) return new Adjust(k, bucket, 1.0, 0, 0, false);
        Cell c = cells.get(k + "|" + bucket);
        double ok = 0, n = 0;
        if (c != null) {
            c.decayTo(now, p.halfLifeDays());
            ok = c.ok;
            n = c.n();
        }
        boolean fb = false;
        if (Math.round(n) < p.min()) {
            double aok = 0, an = 0;
            for (Map.Entry<String, Cell> e : cells.entrySet()) {
                if (!e.getKey().startsWith(k + "|")) continue;
                e.getValue().decayTo(now, p.halfLifeDays());
                aok += e.getValue().ok;
                an += e.getValue().n();
            }
            if (Math.round(an) >= p.min()) { ok = aok; n = an; fb = true; }
        }
        return new Adjust(k, bucket, multiplier(ok, n, p), ok, n, fb);
    }

    /** m = 1 under min tries; else floor + (ceil - floor) * (ok + 1) / (n + 2), clamped to [floor, ceil]. */
    public static double multiplier(double ok, double n, Params p) {
        if (Math.round(n) < p.min()) return 1.0;      // decayed counts: rounded
        double m = p.floor() + (p.ceil() - p.floor()) * (ok + 1) / (n + 2);
        return Math.max(p.floor(), Math.min(p.ceil(), m));
    }

    /** The idle picks ordered by their multiplier (highest first, the list's order on a tie). */
    public List<IdleList.Pick> order(List<IdleList.Pick> picks, String bucket, long now, Params p) {
        if (!p.on() || picks.size() < 2) return picks;
        Map<IdleList.Pick, Double> m = new HashMap<>();
        for (IdleList.Pick x : picks) m.put(x, adjust(x.chain(), bucket, now, p).m());
        List<IdleList.Pick> out = new ArrayList<>(picks);
        out.sort(Comparator.comparingDouble((IdleList.Pick x) -> -m.get(x)));      // stable
        return out;
    }

    public synchronized int keys() { return cells.size(); }

    public synchronized long tries() {
        double t = 0;
        for (Cell c : cells.values()) t += c.ok + c.failed + c.interrupted;
        return Math.round(t);
    }

    public synchronized void reset() {
        cells.clear();
        pending.clear();
    }

    public synchronized long lastOutcomeT() { return lastOutcomeT; }

    public synchronized void saveError() { saveErrors++; }

    public synchronized int saveErrors() { return saveErrors; }

    /** The rows (key, cell) by |m - 1| descending (the top adjustments), optionally one job kind only. */
    public synchronized List<JsonObject> rows(String onlyKind, long now, Params p, int max) {
        List<JsonObject> out = new ArrayList<>();
        for (Map.Entry<String, Cell> e : cells.entrySet()) {
            String key = e.getKey();
            int i = key.indexOf('|');
            String k = key.substring(0, i), b = key.substring(i + 1);
            if (onlyKind != null && !k.equals(onlyKind)) continue;
            Cell c = e.getValue();
            c.decayTo(now, p.halfLifeDays());
            JsonObject o = new JsonObject();
            o.addProperty("kind", k);
            o.addProperty("bucket", b);
            o.addProperty("m", Math.round(multiplier(c.ok, c.n(), p) * 100) / 100.0);
            o.addProperty("ok", r1(c.ok));
            o.addProperty("failed", r1(c.failed));
            o.addProperty("interrupted", r1(c.interrupted));
            o.addProperty("meanMin", r1(c.meanMinutes()));
            o.addProperty("last", c.last);
            o.addProperty("lastOutcome", c.lastOutcome);
            out.add(o);
        }
        out.sort(Comparator.comparingDouble((JsonObject o) -> -Math.abs(o.get("m").getAsDouble() - 1.0))
                .thenComparingDouble(o -> -(o.get("ok").getAsDouble() + o.get("failed").getAsDouble())));
        return out.size() > max ? new ArrayList<>(out.subList(0, max)) : out;
    }

    /** "brain learned [kind]": the table as text. */
    public String show(String onlyKind, long now, Params p) {
        List<JsonObject> rows = rows(onlyKind, now, p, 20);
        if (rows.isEmpty()) return onlyKind == null ? "learned: nothing yet (" + keys() + " keys) - the table fills as brain jobs end"
                : "learned: nothing for " + onlyKind;
        StringBuilder b = new StringBuilder("learned (" + keys() + " keys, " + tries() + " tries, half-life " + fmt(p.halfLifeDays()) + " d"
                + (p.on() ? "" : ", brain.learn 0: not used") + (saveErrors() > 0 ? ", " + saveErrors() + " save errors" : "") + "):");
        for (JsonObject o : rows) {
            b.append("\n").append(o.get("kind").getAsString()).append(" [").append(o.get("bucket").getAsString()).append("] x")
                    .append(fmt(o.get("m").getAsDouble())).append(": ").append(fmt(o.get("ok").getAsDouble())).append(" ok, ")
                    .append(fmt(o.get("failed").getAsDouble())).append(" failed, ").append(fmt(o.get("interrupted").getAsDouble())).append(" interrupted")
                    .append(o.get("meanMin").getAsDouble() > 0 ? ", " + fmt(o.get("meanMin").getAsDouble()) + " min" : "");
        }
        return b.toString();
    }

    static double r1(double v) { return Math.round(v * 10) / 10.0; }

    static String fmt(double v) {
        double r = Math.round(v * 100) / 100.0;
        return r == Math.rint(r) ? String.valueOf((long) r) : String.valueOf(r);
    }

    // ---- outcomes.json ----

    public synchronized JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("version", 1);
        o.addProperty("lastOutcomeT", lastOutcomeT);
        JsonObject cs = new JsonObject();
        for (Map.Entry<String, Cell> e : cells.entrySet()) {
            Cell c = e.getValue();
            JsonArray a = new JsonArray();
            a.add(c.ok);
            a.add(c.failed);
            a.add(c.interrupted);
            a.add(c.minutes);
            a.add(c.at);
            a.add(c.last);
            a.add(c.lastOutcome);
            cs.add(e.getKey(), a);
        }
        o.add("cells", cs);
        return o;
    }

    /** Parses outcomes.json; throws IllegalArgumentException with why when broken. */
    public static OutcomeTable fromJson(String text) {
        OutcomeTable t = new OutcomeTable();
        try {
            JsonObject o = JsonParser.parseString(text).getAsJsonObject();
            if (!o.has("cells") || !o.get("cells").isJsonObject()) throw new IllegalArgumentException("no cells");
            t.lastOutcomeT = o.has("lastOutcomeT") ? o.get("lastOutcomeT").getAsLong() : 0;
            for (Map.Entry<String, JsonElement> e : o.getAsJsonObject("cells").entrySet()) {
                if (e.getKey().split("\\|").length != 4) throw new IllegalArgumentException("bad key " + e.getKey());
                JsonArray a = e.getValue().getAsJsonArray();
                Cell c = new Cell();
                c.ok = a.get(0).getAsDouble();
                c.failed = a.get(1).getAsDouble();
                c.interrupted = a.get(2).getAsDouble();
                c.minutes = a.get(3).getAsDouble();
                c.at = a.get(4).getAsLong();
                c.last = a.get(5).getAsLong();
                c.lastOutcome = a.size() > 6 ? a.get(6).getAsString() : "";
                if (c.ok < 0 || c.failed < 0 || c.interrupted < 0 || Double.isNaN(c.ok + c.failed + c.interrupted + c.minutes))
                    throw new IllegalArgumentException("bad counts at " + e.getKey());
                t.cells.put(e.getKey(), c);
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new IllegalArgumentException(e.getMessage() == null ? e.toString() : e.getMessage());
        }
        return t;
    }
}
