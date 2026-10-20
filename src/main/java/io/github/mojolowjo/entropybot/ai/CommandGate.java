package io.github.mojolowjo.entropybot.ai;

import io.github.mojolowjo.entropybot.commands.Texts;
import io.github.mojolowjo.entropybot.commands.VerbTable;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 0.35.0: what the model may send. Pure Java over {@link VerbTable}: every table verb has a decision here (JUnit fails
 * when a new verb has none), every step of an "a then b" chain is checked, settings and trust verbs are denied, and a
 * hard-deny set can't be opened by ai.json. The verbs' own argument checks, the fence, the protected blocks and the
 * area rules stay below this and are unchanged.
 */
public final class CommandGate {
    public enum Kind { RUN, WAIT, FINISHED, EMPTY, UNKNOWN, DENIED, BAD }

    /** RUN: line is the command; WAIT/FINISHED/EMPTY: no command; else error is the feedback for the model. */
    public record Verdict(Kind kind, String line, String verb, String error) {
        public boolean failed() { return kind == Kind.UNKNOWN || kind == Kind.DENIED || kind == Kind.BAD; }
    }

    /** The only lines the model may send (besides wait/finished); the same rule as the Door under AA. */
    public static final String ALLOWED_HELP = "aa want <thing> [n] [high|low], aa drop <thing>, aa status, aa why, status";

    private static final Pattern CONTROL = Pattern.compile("[\\p{Cntrl}&&[^\\t]]");
    public static final int MAX_LEN = 300;

    private final Set<String> extraAllow = new HashSet<>(), extraDeny = new HashSet<>();

    public CommandGate(Collection<String> extraAllow, Collection<String> extraDeny) { // the lists are kept for ai.json but cannot open anything now
        if (extraAllow != null) for (String s : extraAllow) if (s != null) this.extraAllow.add(norm(s));
        if (extraDeny != null) for (String s : extraDeny) if (s != null) this.extraDeny.add(norm(s));
    }

    private static String norm(String s) { return s.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " "); }

    /**
     * The first non-empty line of a model answer, with markdown and a leading "do ", backticks, quotes or "> " removed.
     * Thinking blocks ({@code <think>...</think>}) are dropped first.
     */
    public static String normalise(String answer) {
        if (answer == null) return "";
        String a = answer.replaceAll("(?s)<think>.*?</think>", "").replace("\r", "");
        for (String line : a.split("\n")) {
            String t = line.trim();
            if (t.startsWith("```")) continue;
            t = t.replaceAll("^(?:>\\s*|[-*]\\s+|\\d+[.)]\\s+)", "").trim();
            t = t.replaceAll("^(?i)(command|answer|reply)\\s*:\\s*", "");
            t = t.replaceAll("^[`\"'*]+|[`\"'*]+$", "").trim();
            if (t.regionMatches(true, 0, "do ", 0, 3)) t = t.substring(3).trim();
            if (t.endsWith(".") && !t.matches(".*\\d\\.$")) t = t.substring(0, t.length() - 1).trim();
            if (!t.isEmpty()) return t;
        }
        return "";
    }

    /** How many non-empty lines came after the first (a model that rambles). */
    public static int extraLines(String answer) {
        if (answer == null) return 0;
        int n = 0;
        for (String l : answer.replaceAll("(?s)<think>.*?</think>", "").split("\n")) if (!l.isBlank() && !l.trim().startsWith("```")) n++;
        return Math.max(0, n - 1);
    }

    /** Is this verb (+ the rest) allowed for the model? null: yes; else the denied form. 0.38.c1: only aa want/drop/status/why and status. */
    public String denied(String verb, String rest) {
        String line = rest == null || rest.isBlank() ? verb : verb + " " + rest.trim();
        return io.github.mojolowjo.entropybot.aa.door.Door.aiMayUnderAa(line) ? null : line;
    }
    public Verdict check(String answer) {
        String line = normalise(answer);
        if (line.isEmpty()) return new Verdict(Kind.EMPTY, "", null, "error: the reply was empty - reply with one command line");
        String low = line.toLowerCase(Locale.ROOT);
        if (low.equals("wait")) return new Verdict(Kind.WAIT, "wait", "wait", null);
        if (low.equals("finished") || low.equals("goal finished")) return new Verdict(Kind.FINISHED, "finished", null, null);
        if (line.length() > MAX_LEN) return new Verdict(Kind.BAD, line, null, "error: the command is over " + MAX_LEN + " characters - send one short command");
        if (CONTROL.matcher(line).find()) return new Verdict(Kind.BAD, line, null, "error: control characters in the command");
        if (line.startsWith("/") || line.startsWith("#")) return new Verdict(Kind.BAD, line, null, "error: slash and # commands are not allowed - use the bot's own commands");
        List<String> steps = Texts.splitChain(line);
        if (steps.isEmpty()) return new Verdict(Kind.EMPTY, "", null, "error: the reply was empty - reply with one command line");
        String first = null;
        for (String s : steps) {
            String[] vr = Texts.verbAndRest(s);
            String verb = vr[0];
            if (first == null) first = verb;
            if (VerbTable.of(verb) == null) {
                String sug = VerbTable.suggest(verb);
                return new Verdict(Kind.UNKNOWN, line, verb, "error: unknown verb '" + verb + "'" + (sug != null ? " - did you mean " + sug + "?" : " - help lists the commands"));
            }
            String no = denied(verb, vr[1]);
            if (no != null) return new Verdict(Kind.DENIED, line, verb, "error: refused, not allowed for the AI operator: \"" + no + "\". You may only send: " + ALLOWED_HELP + ", or a question to the owner. Everything else is done by the bot itself under AA.");
        }
        return new Verdict(Kind.RUN, line, first, null);
    }
}

