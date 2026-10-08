package io.github.mojolowjo.entropybot.gather;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * P2: {@code gather}'s grammar, stop rules and texts. Pure Java.
 * <ul>
 *   <li>{@code gather <item> [n] [<min>m]}: until n are in the bag (60 min at most by default);</li>
 *   <li>{@code gather sources} (the table) / {@code gather sources <item>} (that item's next step now);</li>
 *   <li>{@code gather source <item> <command>} (an owner's source; {n} = how many) / {@code gather source <item> clear};</li>
 *   <li>{@code gather status}.</li>
 * </ul>
 */
public final class GatherRules {
    private GatherRules() {}

    public static final String USAGE = "usage: gather <item> [n] [<minutes>m] | gather sources [item] | gather source <item> <command with {n}> | gather source <item> clear | gather status";
    public static final int DEFAULT_MINUTES = 60, MAX_MINUTES = 240, MAX_N = 2304;
    /** Failed attempts at one leaf before the gather stops (the task's 3). */
    public static final int LEAF_FAILS = 3;
    /** Steps in a row that brought nothing before it stops (any leaf). */
    public static final int NO_PROGRESS = 4;
    /** A safety cap on steps. */
    public static final int MAX_STEPS = 80;

    public enum Mode { RUN, SOURCES, SOURCE_SET, SOURCE_CLEAR, STATUS, ERROR }

    public record Args(Mode mode, String item, int n, int minutes, String command, String error) {
        static Args err(String e) { return new Args(Mode.ERROR, null, 0, 0, null, e); }
    }

    private static final Pattern RUN = Pattern.compile("^([a-z0-9_.:-]+)(?:\\s+(\\d+))?(?:\\s+(\\d+)m)?$");

    public static Args parse(String rest) {
        String t = rest == null ? "" : rest.trim();
        String lower = t.toLowerCase(Locale.ROOT);
        if (lower.isEmpty()) return Args.err(USAGE);
        if (lower.equals("status")) return new Args(Mode.STATUS, null, 0, 0, null, null);
        if (lower.equals("sources") || lower.startsWith("sources ")) {
            String item = lower.substring(7).trim();
            return new Args(Mode.SOURCES, item.isEmpty() ? null : GatherSources.full(item), 0, 0, null, null);
        }
        if (lower.startsWith("source ")) {
            String[] p = t.substring(7).trim().split("\\s+", 2);
            if (p.length < 2 || p[0].isEmpty()) return Args.err("usage: gather source <item> <command with {n}> | gather source <item> clear");
            String item = GatherSources.full(p[0]);
            String cmd = p[1].trim().replaceAll("^\"|\"$", "");
            if (cmd.equalsIgnoreCase("clear") || cmd.equalsIgnoreCase("default") || cmd.equalsIgnoreCase("none")) {
                return new Args(Mode.SOURCE_CLEAR, item, 0, 0, null, null);
            }
            String why = commandRefusal(cmd);
            if (why != null) return Args.err(why);
            return new Args(Mode.SOURCE_SET, item, 0, 0, cmd, null);
        }
        Matcher m = RUN.matcher(lower);
        if (!m.find()) return Args.err(USAGE);
        int n = m.group(2) == null ? 1 : safeInt(m.group(2));
        int min = m.group(3) == null ? DEFAULT_MINUTES : safeInt(m.group(3));
        if (n < 1 || n > MAX_N) return Args.err("error: gather 1 to " + MAX_N + " at a time");
        if (min < 1 || min > MAX_MINUTES) return Args.err("error: a gather runs 1 to " + MAX_MINUTES + " minutes");
        return new Args(Mode.RUN, GatherSources.full(m.group(1)), n, min, null, null);
    }

    /** An owner's source command: one plain step (no chain, no gather inside a gather, no chat). */
    static String commandRefusal(String cmd) {
        String c = cmd.toLowerCase(Locale.ROOT);
        if (c.matches(".*(\\bthen\\b|;).*")) return "error: a source is one command, not a chain";
        String verb = c.split("\\s+")[0];
        if (List.of("gather", "say", "repeat", "run", "rule", "routine", "stop", "allow", "deny", "b", "baritone").contains(verb)) {
            return "error: \"" + verb + "\" can't be a source";
        }
        return null;
    }

    static int safeInt(String s) {
        return s.length() > 9 ? Integer.MAX_VALUE : Integer.parseInt(s);
    }

    /** Why the run stops before the next step, or null. */
    public static String stopReason(long now, long deadline, int minutes, int leafFails, String leaf, String lastFail, int noProgress, int steps) {
        if (now >= deadline) return "the " + minutes + " minutes are up";
        if (leafFails >= LEAF_FAILS) return "3 tries at " + GatherSources.shortId(leaf) + " failed" + (lastFail != null ? " (last: " + clean(lastFail) + ")" : "");
        if (noProgress >= NO_PROGRESS) return NO_PROGRESS + " steps in a row brought nothing" + (lastFail != null ? " (last: " + clean(lastFail) + ")" : "");
        if (steps >= MAX_STEPS) return MAX_STEPS + " steps and still short";
        return null;
    }

    static String clean(String s) {
        String t = s == null ? "" : s.replaceFirst("^(error|stopped|partial|done|ok): ", "");
        return t.length() > 200 ? t.substring(0, 200) + "..." : t;
    }

    /** What a step did, for the end line: "mined 40 raw_iron", "took 24 from the base", "crafted 16 oak_planks". */
    public static final class Tally {
        private final Map<String, Integer> done = new LinkedHashMap<>();

        public void add(String word, String item, int n) {
            if (n <= 0) return;
            String k = word + " " + GatherSources.shortId(item);
            done.merge(k, n, Integer::sum);
        }

        public String text() {
            List<String> out = new ArrayList<>();
            for (Map.Entry<String, Integer> e : done.entrySet()) {
                String[] p = e.getKey().split(" ", 2);
                out.add(p[0].equals("took") ? "took " + e.getValue() + " " + p[1] + " from storage" : p[0] + " " + e.getValue() + " " + p[1]);
            }
            return String.join(", ", out);
        }

        public boolean isEmpty() { return done.isEmpty(); }
    }

    /** The end line. */
    public static String endText(String item, int want, int have, Tally t, String stopped, String next) {
        String s = GatherSources.shortId(item);
        String did = t.isEmpty() ? "" : " (" + t.text() + ")";
        if (stopped == null) {
            return t.isEmpty() ? "ok: I already have " + have + " " + s : "ok: gathered " + want + " " + s + did;
        }
        String prefix = have > 0 || !t.isEmpty() ? "stopped: " : "error: ";
        return prefix + "gather " + s + ": " + Math.min(have, want) + "/" + want + did + ", stopped: " + stopped + (next != null ? " - next: " + next : "");
    }

    /** The no-way end line (the plan's "no way to get X - next: ..."). */
    public static String noWayText(String item, String target, int want, int have, Tally t, String why) {
        String hint = why == null ? "put some in the base chests" : why;
        String x = GatherSources.shortId(item);
        String did = t.isEmpty() ? "" : " (" + t.text() + ")";
        String head = (have > 0 || !t.isEmpty() ? "stopped: " : "error: ") + "gather " + GatherSources.shortId(target) + ": " + Math.min(have, want) + "/" + want + did + ", ";
        if (hint.startsWith("needs ") && hint.endsWith("which I don't know")) {
            return head + "no way to get " + GatherSources.shortId(target) + ": " + hint + " - next: put some " + GatherSources.shortId(target) + " in the base chests";
        }
        if (hint.startsWith("no recipe") || hint.startsWith("its recipe")) {
            return head + "no way to get " + x + " (" + hint + ") - next: put some in the base chests, or tell me how: gather source " + x + " <command with {n}>";
        }
        return head + "no way to get " + x + " - next: " + hint;
    }

    /** The "next:" of a run that stopped on a leaf, by its source. */
    public static String nextHint(GatherSources.Source s, String leaf) {
        String x = GatherSources.shortId(leaf);
        String put = "put some " + x + " in the base chests";
        if (s == null) return put + ", or tell me how: gather source " + x + " <command with {n}>";
        return switch (s.kind()) {
            case ORE -> "place a mine (stand at its start facing the way to dig: place mine) so I can strip-mine, take me near exposed " + s.family()
                    + " ore, or " + put;
            case LOG -> "\"area here <r> <name>\" where trees grow, or " + put;
            case CROP -> "check the farm has " + x + " crops (\"farm here\" by it), or " + put;
            case BLOCK -> "take me near some " + GatherSources.shortId(s.block()) + " inside my areas, or " + put;
            case OVERRIDE -> "check your source (gather source " + x + " ...: \"" + s.command() + "\"), or " + put;
            default -> s.hint() != null ? s.hint() : put;
        };
    }

    /** The status line ("gather: 40/64 iron_ingot, step 3: mine strip iron 24"). */
    public static String status(String item, int want, int have, int step, String command) {
        return "gather: " + Math.min(have, want) + "/" + want + " " + GatherSources.shortId(item) + (command != null ? ", step " + step + ": " + command : "");
    }

    /** "15 min" from ticks. */
    public static long deadline(long now, int minutes) {
        return now + minutes * 1200L;
    }
}
