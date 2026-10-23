package io.github.mojolowjo.entropybot.screen;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 0.39.z24 config screen: who may give the bot commands in game chat (whispers / PMs). The list is EMPTY by default:
 * then nobody may, only the person at the keyboard ({@code /bot ...}, the screen's command box) and the local
 * dashboard / bridge (they hold the key). A name on the list may PM commands; a name not on it hears "not allowed"
 * once. Pure (JUnit).
 */
public final class AllowRule {
    private AllowRule() {}

    public enum Verdict { ALLOW, REFUSE_SAY, REFUSE_QUIET }

    public static final String REFUSAL = "Sorry, you are not allowed to give me commands.";

    /** Whether name is on the list (case-insensitive). */
    public static boolean onList(String name, List<String> allowed) {
        if (name == null || allowed == null) return false;
        for (String a : allowed) if (a.equalsIgnoreCase(name)) return true;
        return false;
    }

    /** A PM from name: allowed, or refused (said once: alreadyRefused holds the lower-case names told before). */
    public static Verdict pm(String name, List<String> allowed, Set<String> alreadyRefused) {
        if (onList(name, allowed)) return Verdict.ALLOW;
        return alreadyRefused.contains(name.toLowerCase(Locale.ROOT)) ? Verdict.REFUSE_QUIET : Verdict.REFUSE_SAY;
    }

    /** The screen's text field: names split on commas / spaces, valid player names only, no duplicates; bad ones go to bad. */
    public static List<String> parse(String text, List<String> bad) {
        List<String> out = new ArrayList<>();
        if (text == null) return out;
        for (String w : text.split("[,\\s]+")) {
            if (w.isEmpty()) continue;
            if (!w.matches("\\w{3,16}")) { if (bad != null) bad.add(w); continue; }
            if (!onList(w, out)) out.add(w);
        }
        return out;
    }

    public static String describe(List<String> allowed) {
        return allowed == null || allowed.isEmpty() ? "nobody in chat (only the keyboard, the dashboard and the bridge)"
                : String.join(", ", allowed) + " (and the keyboard, the dashboard, the bridge)";
    }
}
