package io.github.mojolowjo.entropybot.summary;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 0.23.6 daily summary, the tally (pure, JUnit): items gathered and deposited, jobs done and failed, deaths, the brain's
 * needs met and unmet, blocks put back, distance walked and the brain's picks. The game side ({@link SummaryRuntime})
 * feeds it and, at dawn (or on {@code summary}), whispers {@link #text} and writes {@code summary.json}.
 * Thread-safe (synchronized): job ends come from the game thread, the brain from its tick.
 *
 * <p>Loader notes: none.
 */
public final class DaySummary {
    public static final DaySummary INSTANCE = new DaySummary();

    private static final Pattern PUT_BACK = Pattern.compile("put back (\\d+) blocks?");
    private static final Pattern FAIL = Pattern.compile("(?i)^(error|failed|stopped|couldn't|can't|blocked)|\\berror:");

    private final Map<String, Integer> gathered = new LinkedHashMap<>(), deposited = new LinkedHashMap<>(), picks = new LinkedHashMap<>();
    private int jobsDone, jobsFailed, deaths, needsMet, needsUnmet, restored;
    private double walked;
    private long since;

    public synchronized void reset(long now) {
        gathered.clear();
        deposited.clear();
        picks.clear();
        jobsDone = jobsFailed = deaths = needsMet = needsUnmet = restored = 0;
        walked = 0;
        since = now;
    }

    /** A job ended (type, its last message): done or failed, and a restore's "put back N blocks". */
    public synchronized void job(String type, String msg) {
        if (msg == null) msg = "";
        if (FAIL.matcher(msg.trim()).find()) jobsFailed++;
        else jobsDone++;
        Matcher m = PUT_BACK.matcher(msg);
        while (m.find()) restored += Integer.parseInt(m.group(1));
    }

    public synchronized void death() { deaths++; }

    /** 0.24.3: deaths so far today (the night safety score). */
    public synchronized int deaths() { return deaths; }

    /** The brain started a job for this need. */
    public synchronized void pick(String need) {
        if (need != null) picks.merge(need, 1, Integer::sum);
    }

    /** The brain's job for a need ended: met (finished) or not. */
    public synchronized void need(boolean met) {
        if (met) needsMet++;
        else needsUnmet++;
    }

    public synchronized void walked(double blocks) {
        if (blocks > 0 && blocks < 20) walked += blocks;           // a teleport or a respawn is not walking
    }

    /**
     * One look at the bag: what rose counts as gathered unless a container was open (then it was taken, not gathered);
     * what fell while a container was open counts as deposited.
     */
    public synchronized void bag(Map<String, Integer> before, Map<String, Integer> after, boolean containerOpen) {
        if (before == null || after == null) return;
        for (Map.Entry<String, Integer> e : after.entrySet()) {
            int d = e.getValue() - before.getOrDefault(e.getKey(), 0);
            if (d > 0 && !containerOpen) gathered.merge(e.getKey(), d, Integer::sum);
        }
        if (!containerOpen) return;
        for (Map.Entry<String, Integer> e : before.entrySet()) {
            int d = e.getValue() - after.getOrDefault(e.getKey(), 0);
            if (d > 0) deposited.merge(e.getKey(), d, Integer::sum);
        }
    }

    static int sum(Map<String, Integer> m) {
        int n = 0;
        for (int v : m.values()) n += v;
        return n;
    }

    /** The n biggest entries, "12 cobblestone, 5 raw_iron". */
    static String top(Map<String, Integer> m, int n) {
        List<Map.Entry<String, Integer>> l = new ArrayList<>(m.entrySet());
        l.sort((a, b) -> b.getValue() - a.getValue());
        List<String> out = new ArrayList<>();
        for (int i = 0; i < Math.min(n, l.size()); i++) {
            String k = l.get(i).getKey();
            out.add(l.get(i).getValue() + " " + k.substring(k.indexOf(':') + 1));
        }
        return String.join(", ", out);
    }

    /** The one-whisper line. */
    public synchronized String text(String day) {
        StringBuilder b = new StringBuilder("summary " + day + ": ");
        b.append("gathered ").append(sum(gathered)).append(gathered.isEmpty() ? "" : " (" + top(gathered, 3) + ")");
        b.append(", deposited ").append(sum(deposited));
        b.append("; jobs ").append(jobsDone).append(" done, ").append(jobsFailed).append(" failed");
        b.append("; deaths ").append(deaths);
        b.append("; needs ").append(needsMet).append(" met, ").append(needsUnmet).append(" unmet");
        b.append("; put back ").append(restored).append(" blocks");
        b.append("; walked ").append(Math.round(walked)).append(" blocks");
        List<Map.Entry<String, Integer>> l = new ArrayList<>(picks.entrySet());
        l.sort((a, c) -> c.getValue() - a.getValue());
        List<String> tp = new ArrayList<>();
        for (int i = 0; i < Math.min(3, l.size()); i++) tp.add(l.get(i).getKey() + " x" + l.get(i).getValue());
        b.append("; brain's top picks: ").append(tp.isEmpty() ? "none" : String.join(", ", tp));
        return b.toString();
    }

    public synchronized JsonObject json(String day, long now) {
        JsonObject o = new JsonObject();
        o.addProperty("day", day);
        o.addProperty("since", since);
        o.addProperty("at", now);
        JsonObject g = new JsonObject();
        gathered.forEach(g::addProperty);
        o.add("gathered", g);
        JsonObject d = new JsonObject();
        deposited.forEach(d::addProperty);
        o.add("deposited", d);
        o.addProperty("jobsDone", jobsDone);
        o.addProperty("jobsFailed", jobsFailed);
        o.addProperty("deaths", deaths);
        o.addProperty("needsMet", needsMet);
        o.addProperty("needsUnmet", needsUnmet);
        o.addProperty("restored", restored);
        o.addProperty("walked", Math.round(walked));
        JsonObject p = new JsonObject();
        picks.forEach(p::addProperty);
        o.add("picks", p);
        o.addProperty("text", text(day));
        return o;
    }

    /** Dawn: the day key moved from a night ("n12") to a day ("d13"). */
    public static boolean dawn(String lastKey, String key) {
        return lastKey != null && key != null && lastKey.startsWith("n") && key.startsWith("d");
    }
}
