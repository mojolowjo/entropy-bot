package io.github.mojolowjo.entropybot.commands;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * B7e package N (item 1): the "help" verb from {@link VerbTable}. {@code help} = the page index, {@code help <n>} = one
 * section, {@code help <verb>} = usage, examples, the next step, {@code help all} = every page. Guests get the commands
 * they may use, and for a verb that isn't theirs a polite no. Pure: the answer is text ("\n" starts a new whisper).
 */
public final class HelpCommand {
    private HelpCommand() {}

    /** The answer to "help [rest]". */
    public static String answer(String rest, boolean isOwner, String owner) {
        String r = rest == null ? "" : rest.trim().toLowerCase(Locale.ROOT);
        if (!isOwner) return guest(r, owner);
        if (r.isEmpty()) return index();
        if (r.equals("all")) {
            List<String> out = new ArrayList<>();
            for (int i = 1; i <= VerbTable.SECTIONS.size(); i++) out.add(pageText(i));
            return String.join("\n", out);
        }
        if (r.matches("^\\d+$")) {
            int n = Integer.parseInt(r.length() > 3 ? "999" : r);
            if (n < 1 || n > VerbTable.SECTIONS.size()) return "there are pages 1 to " + VerbTable.SECTIONS.size() + " - " + index();
            return page(n);
        }
        int si = VerbTable.of(r) != null ? -1 : VerbTable.SECTIONS.indexOf(r);     // B1: "help brain" is the verb (its page: help 8)
        if (si >= 0) return page(si + 1);
        String word = r.split("\\s+")[0];
        if (DELETED.contains(word) && !OldWords.REMOVED.contains(word)) return gone(word);
        VerbTable.Verb v = VerbTable.of(word);
        if (v == null) return unknownWord(word);
        return detail(v, true);
    }

    /** "help": what the pages hold and where to start. */
    public static String index() {
        List<String> s = new ArrayList<>();
        for (int i = 0; i < VerbTable.SECTIONS.size(); i++) s.add((i + 1) + " " + VerbTable.SECTIONS.get(i));
        return "help <page>: " + String.join(", ", s) + " | help <verb> (usage, examples, what next), help all | start with: status, check, come, aa help";
    }

    /** One page: its verbs' usages, then the way on. */
    public static String page(int n) {
        String next = n < VerbTable.SECTIONS.size() ? "help " + (n + 1) + " for " + VerbTable.SECTIONS.get(n) : "help 1 for the first page";
        return pageText(n) + "\n(help <verb> for examples; " + next + ")";
    }

    /**
     * The owner's deleted verbs (2026-10-22, "aa only driver"): never in the help pages; help <verb> names the aa way. The
     * table keeps rows some of them still have as the AA steps' inner commands.
     */
    public static final java.util.Set<String> DELETED = java.util.Set.of("stripmine", "chop", "dig", "clear", "hunt", "hunting", "gather", "fish",
            "place", "shelter", "house", "smelt", "use", "twerk", "scout", "mule", "altar", "summary", "world", "escort",
            // owner 2026-10-23 ("AA is the only driver"): the old verbs and the AI operator
            "deposit", "scan", "take", "put", "get", "give", "drop", "craft", "cook", "cut", "farm", "explore", "go", "goto", "come",
            "follow", "flee", "climb", "burrow", "build", "light", "compact", "restock", "restore", "repeat", "wait", "sleep", "eat",
            "attack", "upgrade", "pots", "rs", "goal", "plan", "need", "surface", "assist", "open", "close", "stop", "ai");

    static boolean deleted(VerbTable.Verb v) {
        for (String w : VerbTable.words(v)) if (DELETED.contains(w)) return true;
        return false;
    }

    static String pageText(int n) {
        String s = VerbTable.SECTIONS.get(n - 1);
        List<String> parts = new ArrayList<>();
        for (VerbTable.Verb v : VerbTable.section(s)) if (!deleted(v)) parts.add(v.usage());
        return "help " + n + "/" + VerbTable.SECTIONS.size() + " " + s + ": " + String.join("; ", parts);
    }

    /** "help <verb>": usage, what it does, examples, the next step (and for the owner what guests may use of it). */
    public static String detail(VerbTable.Verb v, boolean forOwner) {
        StringBuilder b = new StringBuilder();
        b.append(v.usage()).append(" - ").append(v.what());
        if (!v.examples().isEmpty()) b.append(". e.g. ").append(String.join("; ", v.examples()));
        if (v.next() != null) b.append(". next: ").append(v.next());
        if (!v.aliases().isEmpty()) b.append(" (also: ").append(String.join(", ", v.aliases())).append(")");
        if (forOwner) {
            if (v.who() == VerbTable.Who.GUEST) b.append(" [allowed players too]");
            else if (v.who() == VerbTable.Who.PARTLY) b.append(" [allowed players: ").append(v.guestForms()).append("]");
        }
        return b.toString();
    }

    /** "help gather": the verb is gone; the owner drives through aa. */
    static String gone(String word) {
        return "\"" + word + "\" was deleted: the owner drives through aa now (aa want <item|group> [n], aa want strip <ore> [<feet y>] [<heading>], aa want deposit, aa scan, aa eat, aa restore, aa off to stop) - aa help lists every form";
    }

    /** "I don't know ..." with the nearest word, if any. */
    static String unknownWord(String word) {
        // 0.33.0: an old word (V1b, docs/COMMANDS.md "Removed in 0.22") is not a verb any more: help names its new form
        String lw = word.toLowerCase(java.util.Locale.ROOT);
        if (OldWords.REMOVED.contains(lw)) {
            String h = OldWords.v1b(lw, "");
            if (h == null) h = OldWords.hint(lw, "");
            if (h != null) return "\"" + lw + "\" is an old word, not a command any more: " + h;
        }
        String s = VerbTable.suggest(word);
        return "I don't know \"" + word + "\"" + (s != null ? " - did you mean " + s + "? (help " + s + ")" : " - help lists the pages");
    }

    /** What guests may use, from the table. */
    public static String guestList() {
        List<String> out = new ArrayList<>();
        for (VerbTable.Verb v : VerbTable.all()) {
            if (deleted(v)) continue;
            if (v.who() == VerbTable.Who.GUEST) out.add(v.usage());
            else if (v.who() == VerbTable.Who.PARTLY) out.add(v.guestForms());
        }
        return String.join(", ", out);
    }

    static String guest(String r, String owner) {
        if (r.isEmpty() || r.matches("^\\d+$") || r.equals("all") || (VerbTable.SECTIONS.contains(r) && VerbTable.of(r) == null)) {
            return "You can use: " + guestList() + " - help <command> for one";
        }
        String word = r.split("\\s+")[0];
        VerbTable.Verb v = VerbTable.of(word);
        if (v == null) return unknownWord(word);
        if (v.who() == VerbTable.Who.OWNER) return "\"" + v.name() + "\" is only for " + owner + " - help lists what you can use";
        if (v.who() == VerbTable.Who.PARTLY) return "you can use: " + v.guestForms() + " - " + v.what() + " (the rest is " + owner + "'s)";
        return detail(v, false);
    }
}
