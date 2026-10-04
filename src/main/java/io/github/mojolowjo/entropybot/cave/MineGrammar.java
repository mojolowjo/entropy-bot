package io.github.mojolowjo.entropybot.cave;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The "mine" verb's grammar (B4; the bridge's mineCommand): "mine" alone answers {@link #GRAMMAR}; "mine strip &lt;ores&gt;
 * [n] [at &lt;mine&gt;]" and "mine cave &lt;ores&gt; [n | &lt;min&gt;m] [at &lt;cave&gt;]" (count and minutes in any order after the
 * ores; no ores = "ores prefer"); anything else is the old Baritone "mine &lt;block&gt; [n] [dig]". Plain Java.
 */
public final class MineGrammar {
    private MineGrammar() {}

    public static final String GRAMMAR = "say \"mine strip <ores> [n] [at <mine>]\" or \"mine cave <ores> [n | <minutes>m] [at <cave>]\" (ores: iron,diamond or "
            + "iron_ore or any; \"ores prefer ...\" sets the default list) - \"mine <ore block> [n]\" still mines exposed ores near me";

    public enum Kind { GRAMMAR, ORE, STRIP, CAVE, ERROR }

    /**
     * kind GRAMMAR: text = the grammar; ERROR: text = the reply ("error: ..."); ORE: text = the "mine &lt;block&gt; ..." rest;
     * STRIP/CAVE: spec, n (0 = no count), minutes (0 = none), at (the mine or cave name, or null).
     */
    public record Parsed(Kind kind, String text, OreSpec spec, int n, int minutes, String at) {
        static Parsed of(Kind k, String text) { return new Parsed(k, text, null, 0, 0, null); }
    }

    private static final Pattern MODE = Pattern.compile("^(strip|cave)\\b\\s*(.*)$", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern AT = Pattern.compile("\\s*\\bat\\s+([a-z0-9_-]+)\\s*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern NUM = Pattern.compile("(^|\\s+)(\\d+)(m?)\\s*$", Pattern.CASE_INSENSITIVE);

    public static Parsed parse(String text, String orePrefer, List<String> allOres) {
        String t = text == null ? "" : text.trim();
        if (t.isEmpty()) return Parsed.of(Kind.GRAMMAR, GRAMMAR);
        Matcher m = MODE.matcher(t);
        if (!m.find()) return Parsed.of(Kind.ORE, t);
        String mode = m.group(1).toLowerCase(), rest = m.group(2), at = null;
        int n = 0, minutes = 0;
        Matcher a = AT.matcher(rest);
        if (a.find()) {
            at = a.group(1).toLowerCase();
            rest = rest.substring(0, a.start());
        }
        // a count, minutes ("10m"), or both, after the ores
        while (true) {
            Matcher q = NUM.matcher(rest);
            if (!q.find()) break;
            if (!q.group(3).isEmpty()) minutes = safeInt(q.group(2));
            else n = safeInt(q.group(2));
            rest = rest.substring(0, q.start());
        }
        rest = rest.trim();
        boolean named = !rest.isEmpty();
        if (!named) rest = orePrefer == null ? "" : orePrefer;
        OreSpec.Parsed sp = OreSpec.parse(rest, allOres);
        if (sp.err() != null) return Parsed.of(Kind.ERROR, "error: " + sp.err() + (rest.isEmpty() ? " - or set a default with \"ores prefer iron,diamond\"" : ""));
        if (mode.equals("strip")) {
            if (minutes > 0) return Parsed.of(Kind.ERROR, "error: a strip mine counts ores, not minutes: mine strip " + sp.spec().label + " 16");
            return new Parsed(Kind.STRIP, null, sp.spec(), n, 0, at != null ? at : "mine");
        }
        return new Parsed(Kind.CAVE, null, sp.spec(), n, minutes, at);
    }

    /** Digits as an int, capped (a 10-digit count would overflow parseInt). */
    static int safeInt(String s) {
        return s.length() > 9 ? 999_999_999 : Integer.parseInt(s);
    }
}
