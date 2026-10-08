package io.github.mojolowjo.entropybot.commands;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The command words, help texts and small text rules of the PM handler (B7a, ported from the KubeJS bridge's
 * handlePm with the same wording, so the owner sees no difference). Plain Java: JUnit tests it.
 */
public final class Texts {
    private Texts() {}

    /** V1b: what allowed players who aren't the owner may use (plus the read-only forms in {@link #guestRefusal}). */
    public static final Set<String> GUEST_VERBS = Set.of("help", "", "status", "inv", "queue", "places", "have", "find", "come", "follow", "goto", "stop", "restart", "dismiss", "hold", "why");

    /** Every command word of the surface (V1b: docs/VOCABULARY.md and nothing else); routine names can't use these nor {@link OldWords#REMOVED}. */
    public static final List<String> BUILTIN_VERBS = List.of("place", "marker", "places", "area", "fence", "fetch", "need", "needs", "goal", "goals", "have", "stock", "get",
            "supplies", "deposit", "mine", "dig", "build", "cut", "gather", "light", "junk", "defence", "defend", "guard", "escort", "assist", "dismiss", "attack", "queue", "status", "inv",
            "stop", "routine", "routines", "rule", "rules", "repeat", "wait", "confirm", "check", "summary", "kinds", "tools", "hotbar", "explore", "find", "scout", "done", "free", "sleep",
            "camp", "bootstrap", "restore", "come", "follow", "goto", "go", "home", "open", "take", "put", "close", "drop", "wear", "scan", "rs", "pots", "craft", "kit", "recipe",
            "smelt", "cook", "eat", "farm", "compact", "infuse", "upgrade", "help", "allow", "deny", "restart", "surface", "path", "server", "debug",
            "why", "brain", "idle", "plan", "actions", "deaths", "resume", "death", "corpse", "hold", "give", "carry", "unload", "restock", "say", "twerk", "spawn", "trust", "untrust", "shelter", "hunt", "hunting");

    /**
     * The jobs the mod runs itself: walks since B7b part 1, the storage errands (and "go poi") since part 2, crafting,
     * the furnace, get/restock, the farm and compact since B7c (need/supplies/recipe are instant there).
     */
    public static final Set<String> MOD_JOB_VERBS = Set.of("come", "follow", "goto", "spawn", "bed", "go", "base", "home", "wait", "twerk", "find",
            "open", "scan", "deposit", "corpse", "death", "rs", "pots",
            "craft", "kit", "smelt", "get", "restock", "farm", "compact", "recipe", "need", "supplies", "infuse", "upgrade",
            "dig", "build", "place", "stripmine", "mine", "explore", "route", "restore", "chop", "cook", "sleep", "scout", "cut", "shelter");

    /** The instant GUI verbs the mod does since B7b part 2 (no job). */
    public static final Set<String> MOD_VERBS = Set.of("take", "put", "close", "drop", "use", "wear", "equip");


    private static final Pattern CHAIN_SPLIT = Pattern.compile("\\s*;\\s*|\\s+then\\s+", Pattern.CASE_INSENSITIVE);
    private static final Pattern FAILED = Pattern.compile("^(error|busy|unknown|usage|only |sorry|i can't|i cannot|i have no|i don't|i haven't|no |nothing|none|say what)",
            Pattern.CASE_INSENSITIVE);

    /** "a then b; c" -> [a, b, c] (empty parts dropped). */
    public static List<String> splitChain(String text) {
        List<String> out = new ArrayList<>();
        for (String s : CHAIN_SPLIT.split(text == null ? "" : text, -1)) {
            String t = s.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    /** A reply that means the step didn't happen. */
    public static boolean stepFailed(String reply) {
        String r = reply == null ? "" : reply.replaceFirst("^ok: ", "");
        return FAILED.matcher(r).find();
    }

    /** Null when an allowed player who isn't the owner may run this, else the refusal. */
    /** 0.21.0: "stock targets|set|clear ..." is C4's base-chest targets; "stock" alone or "stock <filter>" is C2's shared view. */
    public static boolean isStockTargetsForm(String rest) {
        String r = rest == null ? "" : rest.trim().toLowerCase();
        return r.matches("^(targets|set|clear)(\\s.*)?$");
    }

    public static String guestRefusal(String verb, String rest, String raw, String owner) {
        String r = rest == null ? "" : rest.trim().toLowerCase();
        if (splitChain(raw).size() > 1) return "sorry, only " + owner + " can start chains";
        if (verb.equals("stock") && isStockTargetsForm(r)) return "sorry, stock targets|set|clear are " + owner + "'s - you can use stock [filter]";
        if (GUEST_VERBS.contains(verb)) return null;
        if (verb.equals("guard") && (r.isEmpty() || r.equals("vetoes"))) return OldWords.hint(verb, r);       // V1a: the cut word, its new form
        if (verb.equals("fence") && (r.isEmpty() || r.equals("vetoes") || r.equals("status"))) return null;
        if (verb.equals("escort") && r.matches("^(|status|me(\\s+\\d+)?)$")) return null;     // C7: guests escort only themselves
        if (verb.equals("area") && r.equals("list")) return null;
        if (verb.equals("deaths") && r.isEmpty()) return null;
        return "sorry, only " + owner + " can use \"" + verb + "\". You can use: " + HelpCommand.guestList();
    }

    /** B7e: the answer to a cmd.json type the mod doesn't know (the KubeJS bridge used to take the rest). */
    public static String unknownType(String type) {
        return "error: unknown type " + type;
    }

    /** B7e: a "debug" answer (a 4096-cell slice is ~15 KB) goes into the log cut to this many characters and its length. */
    public static final int DEBUG_LOG_CHARS = 200;

    /** What the log line of a cmd.json answer shows: the answer, or for "debug" (a type or a pm) its start and length. */
    public static String cmdLogged(String type, String text, String result) {
        boolean debug = "debug".equals(type) || ("pm".equals(type) && verbAndRest(text)[0].equals("debug"));
        if (!debug || result == null || result.length() <= DEBUG_LOG_CHARS) return result;
        return result.substring(0, DEBUG_LOG_CHARS) + "... (" + result.length() + " chars)";
    }

    /** "say" only ever talks: a line starting with "/" or Baritone's prefix would be a command. */
    public static String sayRefusal(String text, String prefix) {
        String t = text == null ? "" : text.trim(), p = prefix == null ? "#" : prefix;
        if (t.startsWith("/") || t.startsWith("#") || (!p.isEmpty() && t.startsWith(p))) {
            return "error: say only talks, it won't send commands (lines starting with / or " + p + ")";
        }
        return null;
    }

    /**
     * A whisper split into pieces of at most 200 characters at spaces (a chat command maxes out at 256). Each line
     * starts a piece of its own (package A: "why" puts the latest decision on a line by itself).
     */
    public static List<String> whisperParts(String text) {
        List<String> out = new ArrayList<>();
        for (String line : String.valueOf(text).split("\n")) {
            String t = line.replaceAll("\\s+", " ").trim();
            while (!t.isEmpty()) {
                int cut = t.length() <= 200 ? t.length() : t.lastIndexOf(' ', 200);
                if (cut <= 0) cut = 200;
                out.add(t.substring(0, cut));
                t = t.substring(cut).trim();
            }
        }
        return out;
    }

    /** "12s ago", "5m ago", "3h ago". */
    public static String ago(long ms, long now) {
        long s = Math.round((now - ms) / 1000.0);
        if (s < 90) return s + "s ago";
        if (s < 5400) return Math.round(s / 60.0) + "m ago";
        return Math.round(s / 3600.0) + "h ago";
    }

    public static String shortId(String id) {
        return id == null ? "" : id.replaceFirst("^minecraft:", "");
    }

    /** The first word (lower case) and the rest of a command line, a leading "!" dropped. */
    public static String[] verbAndRest(String message) {
        String raw = message == null ? "" : message.trim();
        if (raw.startsWith("!")) raw = raw.substring(1);
        int sp = raw.indexOf(' ');
        String verb = (sp < 0 ? raw : raw.substring(0, sp)).toLowerCase();
        String rest = sp < 0 ? "" : raw.substring(sp + 1).trim();
        return new String[]{verb, rest, raw};
    }

    static boolean isBuiltin(String verb) {
        return BUILTIN_VERBS.contains(verb) || OldWords.REMOVED.contains(verb);
    }

    static List<String> words(String s) {
        List<String> out = new ArrayList<>();
        for (String w : (s == null ? "" : s.trim()).split("\\s+")) if (!w.isEmpty()) out.add(w);
        return out;
    }

    /** S1: "goto me" / "goto <the sender's own name>" means "come" (the owner typed it live, 2026-10-03). */
    static boolean gotoMeansCome(String rest, String from) {
        String r = rest == null ? "" : rest.trim();
        return r.equalsIgnoreCase("me") || (from != null && !from.isEmpty() && r.equalsIgnoreCase(from));
    }
}
