package io.github.mojolowjo.entropybot.commands;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * B7e package N (item 8): big or destructive verbs ask first. {@code build clear}, a {@code dig} of more than
 * {@link #BIG_DIG} blocks, {@code area remove <name>} and {@code stripmine reset} answer a summary and "say confirm
 * within 30 s"; {@code confirm} from the same sender runs it; a timeout or any other command from that sender cancels.
 *
 * <p>Pass-through, so saved routines keep working: a chain's own steps never ask (internal), and a line that ends with
 * the word {@code confirm} runs at once (the word dropped, except for {@code area remove}, whose own syntax wants it).
 * A typed chain with a big step that lacks {@code confirm} asks once for the whole chain. Pure: time comes in as ms.
 */
public final class ConfirmGate {
    public static final long WINDOW_MS = 30_000;
    /** A dig box bigger than this asks first. */
    public static final long BIG_DIG = 1000;

    /** What the summaries can say about the world (each may be null: a plainer summary then). */
    public interface Facts {
        /** "zone 1 60 1 to 9 64 9 (9x5x9)". */
        default String zone() { return null; }

        /** "the mine at 10 -40 20 north, branch 7". */
        default String mine() { return null; }

        /** "x -10..50, z 0..60". */
        default String area(String name) { return null; }
    }

    /** reply != null: answer it and do nothing else; else run line (maybe changed from what came in). */
    public record Gate(String reply, String line) {
        static Gate run(String line) { return new Gate(null, line); }

        static Gate say(String reply) { return new Gate(reply, null); }
    }

    enum Kind { BUILD_CLEAR, DIG, AREA_REMOVE, STRIP_RESET }

    private record Pending(String line, String what, long until) {}

    private final Map<String, Pending> pending = new HashMap<>();
    private final Facts facts;

    public ConfirmGate(Facts facts) {
        this.facts = facts == null ? new Facts() {} : facts;
    }

    /**
     * The gate for one command line. internal: a step of a running chain (never asks). from: whose confirm it waits
     * for (the owner, from a PM, the dashboard or the laptop alike).
     */
    public synchronized Gate gate(String from, String line, boolean internal, long now) {
        String key = from == null ? "" : from.toLowerCase(Locale.ROOT);
        String l = line == null ? "" : line.trim();
        String[] vr = Texts.verbAndRest(l);
        if (vr[0].equals("confirm") && vr[1].isEmpty()) {
            if (internal) return Gate.say("error: nothing to confirm (a chain's steps never ask)");
            Pending p = pending.remove(key);
            if (p == null) return Gate.say("error: nothing to confirm - say the command again");
            if (now > p.until) return Gate.say("error: too late, \"" + p.what + "\" waited more than " + WINDOW_MS / 1000 + " s - say it again");
            return Gate.run(p.line);
        }
        if (!internal) pending.remove(key);              // any other command cancels a question
        List<String> steps = Texts.splitChain(l);
        if (steps.size() > 1) {
            if (internal) return Gate.run(l);
            for (String s : steps) {
                Kind k = kind(s);
                if (k != null && !endsWithConfirm(s)) return ask(key, l, "this chain has a big step: " + summary(k, s), now);
            }
            return Gate.run(l);
        }
        Kind k = kind(l);
        if (k == null) return Gate.run(l);
        if (internal || endsWithConfirm(l)) return Gate.run(runLine(k, l));
        return ask(key, runLine(k, l), summary(k, l), now);
    }

    /** True while a question waits for this sender (and isn't stale). */
    public synchronized boolean waiting(String from, long now) {
        Pending p = pending.get(from == null ? "" : from.toLowerCase(Locale.ROOT));
        return p != null && now <= p.until;
    }

    private Gate ask(String key, String runLine, String summary, long now) {
        pending.put(key, new Pending(runLine, Texts.verbAndRest(runLine)[2].replaceFirst("(?i)\\s+confirm$", ""), now + WINDOW_MS));
        return Gate.say("confirm? " + summary + " - say \"confirm\" within " + WINDOW_MS / 1000 + " s (anything else cancels)");
    }

    /** The big kind of one step, or null. */
    static Kind kind(String step) {
        String[] vr = Texts.verbAndRest(step);
        List<String> w = Texts.words(vr[1].toLowerCase(Locale.ROOT));
        if (!w.isEmpty() && w.get(w.size() - 1).equals("confirm")) w = w.subList(0, w.size() - 1);
        switch (vr[0]) {
            case "build" -> { return !w.isEmpty() && w.get(0).equals("clear") ? Kind.BUILD_CLEAR : null; }
            case "dig" -> { return digVolume(w) > BIG_DIG ? Kind.DIG : null; }
            case "area" -> { return w.size() >= 2 && w.get(0).equals("remove") ? Kind.AREA_REMOVE : null; }
            case "stripmine" -> { return w.size() == 1 && w.get(0).equals("reset") ? Kind.STRIP_RESET : null; }
            default -> { return null; }
        }
    }

    /** The box's block count of "dig x1 y1 z1 x2 y2 z2 [words]", or -1 when it isn't one. */
    static long digVolume(List<String> w) {
        if (w.size() < 6) return -1;
        long[] n = new long[6];
        try {
            for (int i = 0; i < 6; i++) n[i] = Long.parseLong(w.get(i));
        } catch (NumberFormatException e) {
            return -1;
        }
        return (Math.abs(n[3] - n[0]) + 1) * (Math.abs(n[4] - n[1]) + 1) * (Math.abs(n[5] - n[2]) + 1);
    }

    static boolean endsWithConfirm(String step) {
        List<String> w = Texts.words(step);
        return w.size() > 1 && w.get(w.size() - 1).equalsIgnoreCase("confirm");
    }

    /** What runs: "area remove <name> confirm" (its own syntax); the others without the word. */
    static String runLine(Kind k, String line) {
        String bare = line.trim().replaceFirst("(?i)\\s+confirm$", "");
        return k == Kind.AREA_REMOVE ? bare + " confirm" : bare;
    }

    private String summary(Kind k, String step) {
        List<String> w = Texts.words(Texts.verbAndRest(step)[1]);
        switch (k) {
            case BUILD_CLEAR -> {
                String z = facts.zone();
                return "build clear breaks every block in the " + (z != null ? z : "work zone") + ", top down (chests, built blocks and ores next to water or lava stay)";
            }
            case DIG -> {
                return "dig breaks up to " + digVolume(w) + " blocks (" + String.join(" ", w.subList(0, 3)) + " to " + String.join(" ", w.subList(3, 6)) + ")";
            }
            case AREA_REMOVE -> {
                String name = w.get(1), a = facts.area(name);
                return "area remove forgets the work area " + name + (a != null ? " (" + a + ")" : "") + "; a job whose box falls outside my areas stops";
            }
            default -> {
                String m = facts.mine();
                return "stripmine reset starts " + (m != null ? m : "the mine") + " over at branch 1 (the chests stay, the note forgets them)";
            }
        }
    }
}
