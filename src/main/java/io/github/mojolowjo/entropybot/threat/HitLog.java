package io.github.mojolowjo.entropybot.threat;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 0.24.4 damage log (pure, JUnit): every hit the bot took, newest last, in a ring of {@link #CAP}. A death freezes the
 * last {@link #DEATH_HITS} as that death's hit list ({@code deaths}, the recorder incident); {@code debug hits [n]}
 * reads the ring; {@link #bySource} feeds the daily summary. The game side is {@link HitWatch}. Loader notes: none.
 */
public final class HitLog {
    public static final HitLog INSTANCE = new HitLog();
    public static final int CAP = 200, DEATH_HITS = 20;

    /** type: the damage type id ("mob_attack", "fall", "lava"...); id/name: the attacker's entity type / name (null: none). */
    public record Hit(long atMs, String type, String id, String name, double amount, double healthAfter, int x, int y, int z, String job) {
        /** "zombie" (attacker kind) else the damage type. */
        public String source() {
            if (id != null && !id.isEmpty()) return id.replaceFirst("^minecraft:", "");
            return type == null || type.isEmpty() ? "unknown" : type.replaceFirst("^minecraft:", "");
        }

        public String line(ZoneId zone) {
            String t = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(zone).format(Instant.ofEpochMilli(atMs));
            String who = source() + (name != null && !name.isEmpty() && !name.equalsIgnoreCase(source()) ? " \"" + name + "\"" : "")
                    + (id != null && type != null && !type.isEmpty() ? " (" + type.replaceFirst("^minecraft:", "") + ")" : "");
            return t + " -" + fmt(amount) + " from " + who + ", health " + fmt(healthAfter) + " at " + x + " " + y + " " + z
                    + (job == null || job.isEmpty() ? "" : ", job " + job);
        }
    }

    private final ArrayDeque<Hit> ring = new ArrayDeque<>();
    private List<Hit> lastDeath = List.of();
    private long lastDeathMs;
    private final Map<String, Double> today = new LinkedHashMap<>();

    static String fmt(double d) {
        double r = Math.round(d * 10) / 10.0;
        return r == Math.rint(r) ? String.valueOf((long) r) : String.valueOf(r);
    }

    public synchronized void add(Hit h) {
        if (h == null || !(h.amount() > 0)) return;
        ring.addLast(h);
        while (ring.size() > CAP) ring.removeFirst();
        today.merge(h.source(), h.amount(), Double::sum);
    }

    /** The last n hits, oldest first. */
    public synchronized List<Hit> last(int n) {
        List<Hit> all = new ArrayList<>(ring);
        return new ArrayList<>(all.subList(Math.max(0, all.size() - Math.max(0, n)), all.size()));
    }

    /** A death: the last 20 hits within 5 minutes before it become that death's list. */
    public synchronized List<Hit> death(long now) {
        List<Hit> l = new ArrayList<>();
        for (Hit h : last(DEATH_HITS)) if (now - h.atMs() <= 300_000) l.add(h);
        lastDeath = List.copyOf(l);
        lastDeathMs = now;
        return lastDeath;
    }

    public synchronized List<Hit> lastDeath() { return lastDeath; }

    /** Damage taken by source since the last reset, biggest first: "zombie 14, fall 3". */
    public synchronized String bySource() {
        List<Map.Entry<String, Double>> l = new ArrayList<>(today.entrySet());
        l.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, Double> e : l) out.add(e.getKey() + " " + fmt(e.getValue()));
        return out.isEmpty() ? "none" : String.join(", ", out);
    }

    public synchronized Map<String, Double> bySourceMap() { return new LinkedHashMap<>(today); }

    public synchronized void resetDay() { today.clear(); }

    public synchronized void clear() {
        ring.clear();
        lastDeath = List.of();
        today.clear();
    }

    /** The lines of a list, or "none". */
    public static String text(List<Hit> hits, ZoneId zone) {
        if (hits.isEmpty()) return "none recorded";
        StringBuilder b = new StringBuilder();
        double sum = 0;
        for (Hit h : hits) {
            b.append("\n  ").append(h.line(zone));
            sum += h.amount();
        }
        return hits.size() + " hit" + (hits.size() == 1 ? "" : "s") + ", " + fmt(sum) + " damage:" + b;
    }

    /** The "deaths" addition: the last death's hits. */
    public synchronized String deathText(ZoneId zone) {
        if (lastDeathMs == 0) return "";
        return "\nlast death's hits: " + text(lastDeath, zone);
    }
}
