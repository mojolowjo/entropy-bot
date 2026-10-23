package io.github.mojolowjo.entropybot.commands;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 0.36.0 AA v0 (AA_PLAN 8; package P4): the {@code aa} verb. ONE table ({@link #TABLE}) dispatches every form and writes
 * {@code aa help} (and the bare {@code aa} line), so the help cannot drift from what runs (owner 2026-10-22; JUnit:
 * AaHelpTest). {@code aa on} and {@code brain on} exclude each other (both saved in brain.json, both survive a restart).
 */
final class AaCommands {
    private AaCommands() {}

    /** One aa verb: its words (the first is the name), its form(s) for the help, its handler. */
    record Verb(List<String> words, String form, Handler run) {}

    @FunctionalInterface
    interface Handler {
        String run(Commands c, AaRuntime aa, String verb, String arg);
    }

    /** The dispatch table, in help order. */
    static final Map<String, Verb> TABLE = new LinkedHashMap<>();

    private static void v(String words, String form, Handler h) {
        Verb verb = new Verb(List.of(words.split("\\|")), form, h);
        for (String w : verb.words()) if (TABLE.put(w, verb) != null) throw new IllegalStateException("aa " + w + " twice");
    }

    static {
        v("on", "on", (c, aa, vb, a) -> aa.turnOn());
        v("off", "off", (c, aa, vb, a) -> aa.turnOff("aa off"));
        v("needs", "needs on|off", (c, aa, vb, a) -> aa.needs(a));             // orders-tier: saved per world
        v("only", "only <need>|off", (c, aa, vb, a) -> {
            String set = aa.only(a);           // live-facts: saved in this world's switch
            return a.isEmpty() ? (set == null ? "aa only: off (usage: aa only <need>|off, e.g. aa only torches)" : "only: " + set)
                    : set == null ? "aa only: off - every need and want is back" : "only: " + set + " - every other need and want is silent (danger, reflexes and your orders stay on); aa only off to end";
        });
        v("want", "want <item|group> [n] [high|low] | want <item> [n], <item> [n], ... [high|low] | want strip <ore[ amount]>[,...] [<feet y>] [<heading>] | want deposit", (c, aa, vb, a) -> aa.want(a));
        v("scan", "scan (read the chests in reach)", (c, aa, vb, a) -> aa.order("scan", List.of(new io.github.mojolowjo.entropybot.aa.moves.Step.Scan(false))));
        v("eat", "eat (the best food in the bag)", (c, aa, vb, a) -> aa.order("eat", List.of(new io.github.mojolowjo.entropybot.aa.moves.Step.Eat())));
        v("restore", "restore [radius] (put back the blocks I broke)", (c, aa, vb, a) -> {
            int r;
            try { r = a.isEmpty() ? io.github.mojolowjo.entropybot.restore.RestorePlan.nowDefault() : Integer.parseInt(a); } catch (NumberFormatException e) { return "usage: aa restore [radius]"; }
            return aa.order("restore", List.of(new io.github.mojolowjo.entropybot.aa.moves.Step.PutBack(r)));
        });
        v("goals", "goals", (c, aa, vb, a) -> aa.goalsText());
        v("drop", "drop <item>|all", (c, aa, vb, a) -> aa.drop(a));
        v("do|ask|pick|skip|use", "do <need>|none | skip <need>|none | ask on|off | pick <n> | use <option>", (c, aa, vb, a) -> {
            if (!aa.on()) return "aa is off (aa on first)";
            return aa.manual(vb, a);
        });
        v("go", "go (the slow mode's next step)", (c, aa, vb, a) -> aa.slow.go());
        v("why", "why", (c, aa, vb, a) -> {
            List<String> d = aa.decisions();
            // STRUCTURE_BLOCKS: the structure refusals AA's ways met and the stations the last lookup skipped (and why)
            String refusals = io.github.mojolowjo.entropybot.guard.PolicyRuntime.INSTANCE.structureLine() + (io.github.mojolowjo.entropybot.aa.Nearby.skippedLine().isEmpty() ? "" : "\n" + io.github.mojolowjo.entropybot.aa.Nearby.skippedLine().substring(3));
            String opts = aa.on() ? "\ndesires (top 5):\n" + String.join("\n", aa.brainLines(5)) + "\n" + aa.wantsLine() + "\n" + aa.ideasLine() + "\n" + String.join("\n", aa.optionLines()) : "";
            if (d.isEmpty()) return "aa why: no decisions yet" + (aa.on() ? " (rungs: " + aa.ladderLine() + ")" + opts : " (aa is off)") + StripStep.liveLine() + refusals;
            List<String> last = d.subList(Math.max(0, d.size() - 10), d.size());
            return "aa why (last " + last.size() + " of " + d.size() + "):\n" + String.join("\n", last) + (aa.on() ? "\nrungs: " + aa.ladderLine() : "") + StripStep.liveLine() + refusals + opts;
        });
        v("status", "status", (c, aa, vb, a) -> aa.statusLine() + StripStep.liveLine() + aa.tuningLine() + "\n" + aa.counts() + "\n" + aa.lootLine() + (aa.on() ? "\n" + aa.brainCounters() + "\ndesires: " + String.join(" | ", aa.brainLines(5)) + "\n" + aa.goalsLine() + "\n" + aa.wantsLine() + "\nrungs: " + aa.ladderLine() + "\n" + aa.optionStatus() : ""));
        v("tune", "tune [<group>|<name> [<n>]]", (c, aa, vb, a) -> aa.tune(a == null || a.isBlank() ? new String[0] : a.trim().split("\\s+")));     // AA_ROUND4 B3
        v("forget", "forget stations", (c, aa, vb, a) -> a.equals("stations") ? c.nearby.forgetAllStations() : "usage: aa forget stations (drops every station record of mine in this world)");
        v("fair", "fair on|off", (c, aa, vb, a) -> aa.fair(a));
        v("like", "like [<key> <n>]", (c, aa, vb, a) -> aa.like(a));
        v("worth", "worth <item>", (c, aa, vb, a) -> a.isEmpty() ? "usage: aa worth <item> (e.g. aa worth iron_pickaxe): the terms its worth is derived from, or UNKNOWN" : io.github.mojolowjo.entropybot.aa.brain.ItemWorth.explain(a.replace(" ", "_")));
        v("build", "build <name> here|at <x y z> [high|low]", (c, aa, vb, a) -> aa.build(a));
        v("ore", "ore [info] [<thing>]", (c, aa, vb, a) -> aa.oreInfo(a));
        v("tree", "tree", (c, aa, vb, a) -> aa.tree.mermaid() + (aa.on() ? "\n%% desires now: " + String.join(" | ", aa.brainLines(30)) + "\n%% " + aa.ideasLine() : ""));
        v("stats", "stats", (c, aa, vb, a) -> aa.statsText());
        v("litter", "litter", (c, aa, vb, a) -> RestoreLive.INSTANCE.litterText());
        v("replan", "replan on|off", (c, aa, vb, a) -> aa.replan(a));
        v("look", "look [smooth <ticks>]", (c, aa, vb, a) -> aa.look(a));
        v("horizon", "horizon [seconds]", (c, aa, vb, a) -> aa.horizon(a));
        v("slow", "slow [on|off|step]", (c, aa, vb, a) -> aa.slow.command(a));          // AA_STEP3 B
        v("predict", "predict", (c, aa, vb, a) -> aa.slow.predict());
        v("help", "help", (c, aa, vb, a) -> help());
    }

    /**
     * aa want's list form: "torch 16, wooden_pickaxe, stone tools high" -> one order per part in list order, a trailing
     * high|low applied to every part. A strip order (aa want strip ...) and a single order stay one part. Pure.
     */
    static List<String> wantList(String arg) {
        String a = arg == null ? "" : arg.trim();
        if (!a.contains(",") || a.toLowerCase().startsWith("strip ") || a.equalsIgnoreCase("strip")) return List.of(a);
        String prio = null;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?i)\\s+(high|low)$").matcher(a);
        if (m.find()) {
            prio = m.group(1).toLowerCase();
            a = a.substring(0, m.start()).trim();
        }
        List<String> out = new ArrayList<>();
        for (String part : a.split(",")) {
            String p = part.trim();
            if (p.isEmpty()) continue;
            if (prio != null && !p.toLowerCase().matches(".*\\s(high|low)$")) p = p + " " + prio;
            out.add(p);
        }
        return out;
    }

    /** Every form of the table, each once, in table order. */
    static List<String> forms() {
        List<String> out = new ArrayList<>();
        for (Verb v : TABLE.values()) if (!out.contains(v.form())) out.add(v.form());
        return out;
    }

    /** aa help (and the bare aa): every live form, from the dispatch table. */
    static String help() {
        List<String> f = new ArrayList<>();
        for (String s : forms()) f.add("aa " + s.replace(" | ", " | aa "));
        return "aa forms: " + String.join(" | ", f);
    }

    /** The old USAGE name: the generated help. */
    static String usage() { return help(); }

    static String handle(Commands c, String rest) {
        String t = rest == null ? "" : rest.trim().toLowerCase();
        if (t.isEmpty()) return help();
        String verb = t.contains(" ") ? t.substring(0, t.indexOf(' ')) : t, arg = t.contains(" ") ? t.substring(t.indexOf(' ') + 1).trim() : "";
        Verb v = TABLE.get(verb);
        if (v == null) return "error: no aa form \"" + verb + "\" - " + help();
        if (verb.equals("help")) return help();
        AaRuntime aa = c.aaRuntime;
        if (aa == null) return "error: aa isn't loaded yet (no world)";
        return v.run().run(c, aa, verb, arg);
    }
}
