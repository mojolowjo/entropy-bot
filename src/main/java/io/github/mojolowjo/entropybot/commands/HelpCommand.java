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
        VerbTable.Verb v = VerbTable.of(word);
        if (v == null) return unknownWord(word);
        return detail(v, true);
    }

    /** "help": what the pages hold and where to start. */
    public static String index() {
        List<String> s = new ArrayList<>();
        for (int i = 0; i < VerbTable.SECTIONS.size(); i++) s.add((i + 1) + " " + VerbTable.SECTIONS.get(i));
        return "help <page>: " + String.join(", ", s) + " | help <verb> (usage, examples, what next), help all | start with: status, check, come, help mine";
    }

    /** One page: its verbs' usages, then the way on. */
    public static String page(int n) {
        String next = n < VerbTable.SECTIONS.size() ? "help " + (n + 1) + " for " + VerbTable.SECTIONS.get(n) : "help 1 for the first page";
        return pageText(n) + "\n(help <verb> for examples; " + next + ")";
    }

    static String pageText(int n) {
        String s = VerbTable.SECTIONS.get(n - 1);
        List<String> parts = new ArrayList<>();
        for (VerbTable.Verb v : VerbTable.section(s)) parts.add(v.usage());
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

    /** "I don't know ..." with the nearest word, if any. */
    static String unknownWord(String word) {
        String s = VerbTable.suggest(word);
        return "I don't know \"" + word + "\"" + (s != null ? " - did you mean " + s + "? (help " + s + ")" : " - help lists the pages");
    }

    /** What guests may use, from the table. */
    public static String guestList() {
        List<String> out = new ArrayList<>();
        for (VerbTable.Verb v : VerbTable.all()) {
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
