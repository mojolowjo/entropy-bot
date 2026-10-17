package io.github.mojolowjo.entropybot.view;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 0.32.0 (operator view, package 1): one reply shape for whoever reads the bot (the AI operator through bridge.ps1,
 * the owner in chat): RESULT / HERE / NEW / OPTIONS, about {@link #BUDGET} characters. The full text is never lost:
 * a failure is kept whole for {@code why}, a cut reply ends "(more: why)", a failure is never folded into "ok".
 * Multi-line answers (look, help, lists) and the information verbs pass through untouched. Plain Java (JUnit).
 * Loader API: none.
 */
public final class ReplyShaper {
    public static final int BUDGET = 600;
    static final int MAX_OPTIONS = 3, MAX_NEWS = 3;

    /** Verbs whose answer is the information itself (shown whole). */
    static final Set<String> PASS = Set.of("why", "look", "help", "?", "debug", "check", "memory", "summary", "status",
            "reply", "area", "areas", "places", "have", "stock", "recipe", "actions", "goals", "plan", "goal", "brain",
            "route", "pois", "poi", "caves", "ores", "allowed", "commands", "routines", "queue",
            // review 5: lists and coordinates are never cut
            "where", "stripmine", "inv", "tools", "deaths", "death", "find", "scout", "hotbar", "supplies", "need", "gather");

    private static final Pattern FAIL = Pattern.compile("^(error|stopped|failed|can't|couldn't|cannot|busy|sorry|no path|refused|blocked|usage:|unknown|i have no|i don't|i didn't|didn't|nothing to|not in a world)"
            + "|^\\S+: (stopped|error|failed|blocked)\\b|\\bblocked by (water|lava)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern NEXT = Pattern.compile("(?:\\s*[-(;,]\\s*)?\\bnext:\\s*", Pattern.CASE_INSENSITIVE);

    private String lastResult;
    private int repeats;
    private String lastFailure, lastFull, lastCut;
    private String lastVerb;
    private long lastCutAt;
    private long lastFailureAt;
    private long shaped, cut, folded;

    public static boolean failure(String text) {
        return text != null && FAIL.matcher(text.trim()).find();
    }

    /** The RESULT text and the OPTIONS split out of a reply: "... - next: a or b; c" gives [a, b, c]. */
    static String[] split(String full, List<String> options) {
        // live (gather): a "next:" inside "(last: ...)" was cut short; the last one outside brackets wins
        Matcher m = NEXT.matcher(full);
        int s = -1, e = -1, ls = -1, le = -1;
        while (m.find()) {
            int depth = 0;
            for (int i = 0; i < m.start(); i++) { char ch = full.charAt(i); if (ch == '(') depth++; else if (ch == ')') depth--; }
            ls = m.start(); le = m.end();
            if (depth <= 0) { s = m.start(); e = m.end(); }
        }
        if (ls < 0) return new String[]{full.trim()};
        if (s < 0) { s = ls; e = le; }
        String result = full.substring(0, s).trim();
        String rest = full.substring(e).trim();
        int open = 0;
        int end = rest.length();
        for (int i = 0; i < rest.length(); i++) {                     // a ")" closing the "(next: ...)" ends it
            char ch = rest.charAt(i);
            if (ch == '(') open++;
            else if (ch == ')') { if (open == 0) { end = i; break; } open--; }
        }
        String tail = rest.substring(Math.min(end + 1, rest.length())).trim();
        for (String o : rest.substring(0, end).split("\\s+or\\s+|;\\s+")) {
            String t = o.trim().replaceAll("[.,]+$", "");
            if (!t.isEmpty() && options.size() < MAX_OPTIONS) options.add(t);
        }
        if (!tail.isEmpty()) result = result + " " + tail;
        return new String[]{result.replaceAll("[\\s(-]+$", "")};
    }

    /**
     * The shaped reply. verb: the command's first word (null for a job's end). here: one line of surroundings (or null),
     * news: events since the last reply (highest first).
     */
    public synchronized String shape(String verb, String full, String here, List<String> news) {
        return shapeLazy(verb, full, here, () -> news);
    }

    /** review 1: news is taken only when the shaped reply prints it (a PASS reply leaves it for the next one). */
    public synchronized String shapeLazy(String verb, String full, String here, java.util.function.Supplier<List<String>> newsSource) {
        if (full == null || full.isBlank()) return full;
        if ("why".equalsIgnoreCase(verb)) return full;                       // live: why never answers with itself
        boolean fail = failure(full);
        lastFull = full;
        if (fail) { lastFailure = full; lastFailureAt = System.currentTimeMillis(); }
        if ((verb != null && PASS.contains(verb.toLowerCase(Locale.ROOT))) || full.indexOf('\n') >= 0) {
            lastResult = null;                                         // review 4: any other reply resets the fold
            return full;
        }
        shaped++;
        List<String> news = newsSource == null ? null : newsSource.get();
        List<String> opts = new ArrayList<>();
        String result = split(full, opts)[0];
        String v = verb == null ? "" : verb.toLowerCase(Locale.ROOT);
        if (result.equals(lastResult) && v.equals(lastVerb)) {          // review 4: same verb and same text only
            repeats++;
            folded++;
            result = "same as last time (x" + (repeats + 1) + "): " + shorten(result, 160);
        } else {
            lastResult = result;
            lastVerb = v;
            repeats = 0;
        }
        if (fail && !opts.contains("why")) opts.add("why");
        StringBuilder o = new StringBuilder();
        for (int i = 0; i < opts.size(); i++) o.append(i > 0 ? "  " : "").append(i + 1).append(") ").append(opts.get(i));
        String hereLine = here == null || here.isBlank() ? null : "HERE    " + shorten(here, 160);
        String newsLine = null;
        if (news != null && !news.isEmpty()) newsLine = "NEW     " + shorten(String.join("; ", news.subList(0, Math.min(MAX_NEWS, news.size()))), 160);
        String optLine = opts.isEmpty() ? null : "OPTIONS " + shorten(o.toString(), 200);
        int others = (hereLine == null ? 0 : hereLine.length() + 1) + (newsLine == null ? 0 : newsLine.length() + 1) + (optLine == null ? 0 : optLine.length() + 1);
        int room = BUDGET - others - "RESULT  ".length();
        String res = result;
        if (res.length() > room) {
            cut++;
            lastCut = full;                                             // review 2: "why cut" shows it; why stays the last failure
            lastCutAt = System.currentTimeMillis();
            res = res.substring(0, Math.max(20, room - 16)).trim() + (fail ? " (more: why)" : " (more: why cut)");
        }
        StringBuilder out = new StringBuilder("RESULT  ").append(res);
        if (hereLine != null) out.append('\n').append(hereLine);
        if (newsLine != null) out.append('\n').append(newsLine);
        if (optLine != null) out.append('\n').append(optLine);
        return out.toString();
    }

    /** The "why" verb: the last failure (or cut reply) in full. */
    public synchronized String why(long now) { return why(now, ""); }

    /** "why" = the last failure; "why cut" = the last reply the budget cut, whole. */
    public synchronized String why(long now, String arg) {
        if (arg != null && arg.trim().equalsIgnoreCase("cut"))
            return lastCut == null ? "no reply was cut yet" : "last cut reply (" + Math.max(0, (now - lastCutAt) / 1000) + " s ago): " + lastCut;
        if (lastFailure == null) return lastFull == null ? "nothing has gone wrong yet" : "no failure yet; the last reply was: " + lastFull;
        return "last failure (" + Math.max(0, (now - lastFailureAt) / 1000) + " s ago): " + lastFailure;
    }

    public synchronized String counters() {
        return "reply shaper: " + shaped + " shaped, " + cut + " cut (more: why), " + folded + " repeats folded";
    }

    static String shorten(String s, int n) {
        return s.length() <= n ? s : s.substring(0, n - 3).trim() + "...";
    }
}
