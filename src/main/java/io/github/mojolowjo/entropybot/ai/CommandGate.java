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

    public enum Decision { ALLOW, DENY, FORMS }

    /** Every verb of the table (by its name): allowed, denied, or some forms denied ({@link #DENIED_FORMS}). */
    public static final Map<String, Decision> DECISIONS;
    /** Denied sub-forms: "verb sub" (the first word of the rest). */
    public static final Set<String> DENIED_FORMS = Set.of("brain set", "brain reset", "brain copy", "fence mode", "area del", "area remove",
            "area change", "mine strip reset", "defence players", "defense players");
    /** Words never in the table that must never pass either. */
    public static final Set<String> DENY_NOT_IN_TABLE = Set.of("ai", "recorder", "dashboard", "jar", "launch", "stripmine", "b", "baritone", "run", "reconnect");
    /** Verbs whose rest is another command line (queued for later): the rest is gated too. */
    static final Set<String> QUEUES = Set.of("queue", "repeat", "routine", "rule");
    /** ai.json's extraAllow can't open these. */
    public static final Set<String> HARD_DENY = Set.of("ai", "restart", "server", "allow", "deny", "trust", "untrust", "fence mode", "blind",
            "confirm", "defence players", "defense players", "debug");

    static {
        java.util.LinkedHashMap<String, Decision> d = new java.util.LinkedHashMap<>();
        for (String v : List.of("place", "marker", "places", "fetch", "need", "needs", "goal", "goals", "have", "stock", "get", "supplies", "deposit", "restock",
                "hold", "carry", "unload", "dig", "build", "house", "climb", "cut", "gather", "light", "junk", "defend", "guard", "escort", "assist",
                "dismiss", "attack", "queue", "status", "inv", "stop", "wait", "summary", "check", "look", "why", "deaths", "resume", "kinds", "tools",
                "hotbar", "explore", "find", "scout", "idle", "dayplan", "plan", "actions", "done", "sleep", "shelter", "hunting", "hunt", "fish", "camp",
                "bootstrap", "restore", "come", "follow", "goto", "go", "path", "home", "death", "corpse", "open", "take", "put", "close", "drop",
                "wear", "scan", "rs", "pots", "craft", "kit", "recipe", "smelt", "cook", "eat", "burrow", "flee", "cover", "pillar", "farm", "compact", "infuse", "upgrade", "help", "say",
                "sense", "surface"))
            d.put(v, Decision.ALLOW);
        for (String v : List.of("area", "fence", "mine", "brain", "defence")) d.put(v, Decision.FORMS);
        // owner's answer 4 (2026-10-17): the proposed deny list, except say
        for (String v : List.of("give", "routine", "rule", "repeat", "confirm", "reply", "twerk", "spawn", "allow", "deny", "restart", "blind", "debug",
                "server", "trust", "untrust", "ai", "aa"))                 // 0.36.0: the AI never flips the brains
            d.put(v, Decision.DENY);
        DECISIONS = java.util.Collections.unmodifiableMap(d);
    }

    private static final Pattern CONTROL = Pattern.compile("[\\p{Cntrl}&&[^\\t]]");
    public static final int MAX_LEN = 300;

    private final Set<String> extraAllow = new HashSet<>(), extraDeny = new HashSet<>();

    public CommandGate(Collection<String> extraAllow, Collection<String> extraDeny) {
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

    /** Is this verb (+ the first word of its rest) allowed for the model? null: yes; else the denied form. */
    public String denied(String verb, String rest) {
        String sub = rest == null || rest.isBlank() ? "" : rest.trim().split("\\s+")[0].toLowerCase(Locale.ROOT);
        String form = sub.isEmpty() ? verb : verb + " " + sub;
        if (HARD_DENY.contains(verb)) return verb;
        if (HARD_DENY.contains(form)) return form;
        if (extraDeny.contains(verb)) return verb;
        if (extraDeny.contains(form)) return form;
        if (DENY_NOT_IN_TABLE.contains(verb)) return verb;
        VerbTable.Verb v = VerbTable.of(verb);
        String name = v == null ? verb : v.name();
        Decision dec = DECISIONS.get(name);
        if (dec == null) return name;                                 // a verb with no decision is denied
        String canonForm = sub.isEmpty() ? name : name + " " + sub;   // 0.35.1 review: aliases can't dodge the hard set
        if (HARD_DENY.contains(name)) return name;
        if (HARD_DENY.contains(canonForm)) return canonForm;
        if (dec == Decision.DENY && !extraAllow.contains(name)) return name;
        // 0.35.1 review: a queued line is a command too ("queue give ...", "queue then restart"): every word that is a verb is checked
        if (QUEUES.contains(name) && rest != null && !rest.isBlank()) {
            List<String> w = List.of(rest.trim().toLowerCase(Locale.ROOT).split("\\s+"));
            for (int i = 0; i < w.size(); i++) {
                String t = w.get(i);
                if (QUEUES.contains(t) || (VerbTable.of(t) == null && !DENY_NOT_IN_TABLE.contains(t) && !HARD_DENY.contains(t))) continue;
                String inner = denied(t, String.join(" ", w.subList(i + 1, w.size())));
                if (inner != null) return name + " " + inner;
            }
        }
        if (name.equals("mine") && rest != null && rest.trim().toLowerCase(Locale.ROOT).matches("^strip\\s+reset\\b.*")) return extraAllow.contains("mine strip reset") ? null : "mine strip reset";
        String nameForm = sub.isEmpty() ? name : name + " " + sub;
        if (dec == Decision.FORMS && DENIED_FORMS.contains(nameForm) && !extraAllow.contains(nameForm)) return nameForm;
        return null;
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
            if (!DENY_NOT_IN_TABLE.contains(verb) && VerbTable.of(verb) == null) {
                String sug = VerbTable.suggest(verb);
                return new Verdict(Kind.UNKNOWN, line, verb, "error: unknown verb '" + verb + "'" + (sug != null ? " - did you mean " + sug + "?" : " - help lists the commands"));
            }
            String no = denied(verb, vr[1]);
            if (no != null) return new Verdict(Kind.DENIED, line, verb, "error: not allowed for the AI operator: " + no + " (ask the owner)");
        }
        return new Verdict(Kind.RUN, line, first, null);
    }
}
