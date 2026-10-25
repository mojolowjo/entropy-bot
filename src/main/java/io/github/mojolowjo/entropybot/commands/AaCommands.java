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

    /** One form of a verb for the help: its group (one of {@link #GROUPS}), the form, its one-line meaning. */
    record Form(String group, String form, String meaning) {}

    /** One aa verb: its words (the first is the name), its forms for the help, its handler. */
    record Verb(List<String> words, List<Form> forms, Handler run) {
        /** The old one-line form text ("a | b"). */
        String form() {
            List<String> f = new ArrayList<>();
            for (Form x : forms) f.add(x.form());
            return String.join(" | ", f);
        }
    }

    /** 0.39.z27: the help's headings in order; "areas and tags" comes from VerbTable's area row (the owner verb). */
    static final List<String> GROUPS = List.of("orders", "areas and tags", "stations", "caves", "status", "tuning", "help");
    static final String AREAS = "areas and tags";

    /** aa do / use (and their debug forms) while aa is off. */
    static final String OFF = "aa is off (aa on first)";

    /** Longest meaning of one help line (owner 2026-10-23; the long text stays in docs/COMMANDS.md). */
    static final int MEANING_MAX = 100;    // structural: a chat line's length, not a tuning value

    private static Form f(String group, String form, String meaning) {
        if (!GROUPS.contains(group) || group.equals(AREAS)) throw new IllegalStateException("aa form " + form + ": no group " + group);
        if (meaning.length() > MEANING_MAX || meaning.contains("\n")) throw new IllegalStateException("aa form " + form + ": meaning over one line of " + MEANING_MAX);
        return new Form(group, form, meaning);
    }

    @FunctionalInterface
    interface Handler {
        String run(Commands c, AaRuntime aa, String verb, String arg);
    }

    /** The dispatch table, in help order. */
    static final Map<String, Verb> TABLE = new LinkedHashMap<>();

    private static void v(String words, Handler h, Form... forms) {
        Verb verb = new Verb(List.of(words.split("\\|")), List.of(forms), h);
        for (String w : verb.words()) if (TABLE.put(w, verb) != null) throw new IllegalStateException("aa " + w + " twice");
    }

    static {
        String O = "orders", S = "status", T = "tuning";
        // ---- orders
        v("want", (c, aa, vb, a) -> aa.want(a),
                f(O, "want <item|group> [n] [high|low]", "get that item or group (vanilla or modded, from the game's data); high|low = its priority"),
                f(O, "want <item> [n], <item> [n], ... [high|low]", "several orders at once, done in list order"),
                f(O, "want deposit", "put the bag's items away in the chests"),
                f(O, "want strip <ore[ amount]>[,...] [<feet y>] [<heading>]", "dig a strip mine for those ores (at that feet height, that way)"));
        v("drop", (c, aa, vb, a) -> aa.drop(a), f(O, "drop <item>|all", "drop that order, or every order"));
        v("goals", (c, aa, vb, a) -> aa.goalsText(), f(O, "goals", "the orders waiting, in turn"));
        v("go", (c, aa, vb, a) -> aa.slow.go(), f(O, "go (the slow mode's next step)", "slow mode: run the next step"));
        v("skip|do|use|pick|ask", (c, aa, vb, a) -> {
            if (!aa.on()) return OFF;
            return aa.manual(vb, a);
        },
                f(O, "skip <need>|none", "leave that need out this run (none: bring every skipped need back)"),
                f(O, "do <need>|none", "run that need now, the others wait until it ends (none: clear it)"),
                f(O, "use <option>", "the current need uses that option (as aa why names it)"),
                f(O, "pick <n>", "answer the asking need: run its option n"),
                f(O, "ask on|off", "before each task show my options and wait for aa pick"));
        v("build", (c, aa, vb, a) -> aa.build(a), f(O, "build <name> here|at <x y z> [high|low]", "build that saved design here or there (its material list said)"));
        // ---- stations (main-stations 0.39.z35; 0.39.z54 A6: wired, the forms had no caller since z35 - run M's help dump)
        v("look", (c, aa, vb, a) -> MainStationsLive.look(c, aa, a),
                f("stations", "look", "what my crosshair is on (a station kind, my main?)"),
                f("stations", "look <station kind>", "turn my head to the nearest known one of that kind (Baritone's look)"),
                f("stations", "look smooth <ticks>", "how many ticks a head turn takes (1..60, saved)"));
        v("station", (c, aa, vb, a) -> MainStationsLive.station(c, a),
                f("stations", "station main", "the station under my crosshair becomes the main of its kind"),
                f("stations", "station list", "the main stations of this world"),
                f("stations", "station clear <station kind>", "forget the main of that kind"));
        v("deposit", (c, aa, vb, a) -> MainStationsLive.deposit(c, a),
                f("stations", "deposit", "the chests the deposit uses, the main first"),
                f("stations", "deposit <x y z>", "that chest becomes the deposit's main"));
        // ---- caves (0.39.z50, S71: the owner labels spots; the cave rule comes from the labelled patterns)
        v("cave", (c, aa, vb, a) -> CaveLabel.run(a),
                f("caves", "cave yes|no|hill|cliff", "label the spot under the crosshair (or where I stand): a cave, not one, a hill, a cliff"),
                f("caves", "cave list", "the labelled cave patterns and the cave rule derived from them"));
        // ---- status
        v("status", (c, aa, vb, a) -> aa.statusLine() + StripStep.liveLine() + aa.tuningLine() + "\n" + io.github.mojolowjo.entropybot.aa.MainStations.now().line() + "\n" + aa.counts() + "\n" + aa.cavesLine() + "\n" + aa.lootLine() + "\n" + aa.gameDataLine() + (aa.on() ? "\n" + aa.brainCounters() + "\ndesires: " + String.join(" | ", aa.brainLines(5)) + "\n" + aa.goalsLine() + "\n" + aa.wantsLine() + "\nrungs: " + aa.ladderLine() + "\n" + aa.optionStatus() : ""),
                f(S, "status", "who drives, the counters, the desires, goals and wants, each rung's reason"));
        v("why", (c, aa, vb, a) -> {
            List<String> d = aa.decisions();
            // STRUCTURE_BLOCKS: the station lookups this why reports, each with its own notes (skipped, priced); a way's
            // structure refusal is in that way's own option line (no global last state, 0.39.z26 fix 11)
            String refusals = "\n" + c.nearby.stationsLine(io.github.mojolowjo.entropybot.aa.brain.ItemWorth.CRAFT) + "\n" + c.nearby.stationsLine(io.github.mojolowjo.entropybot.aa.brain.ItemWorth.SMELT);
            String opts = aa.on() ? "\ndesires (top 5):\n" + String.join("\n", aa.brainLines(5)) + "\n" + aa.wantsLine() + "\n" + aa.ideasLine() + "\n" + String.join("\n", aa.optionLines()) : "";
            if (d.isEmpty()) return "aa why: no decisions yet" + (aa.on() ? " (rungs: " + aa.ladderLine() + ")" + opts : " (aa is off)") + StripStep.liveLine() + refusals;
            List<String> last = d.subList(Math.max(0, d.size() - 10), d.size());
            return "aa why (last " + last.size() + " of " + d.size() + "):\n" + String.join("\n", last) + (aa.on() ? "\nrungs: " + aa.ladderLine() : "") + StripStep.liveLine() + refusals + opts;
        }, f(S, "why", "the last 10 decisions, the rungs, the desires and options, the station lookups"));
        v("debug", (c, aa, vb, a) -> aa.debugToLog(a),     // 0.39.z28: /bot debug <aa line> is the same; z29: the tree goes to aa-debug.log
                f(S, "debug want|do|use ...", "the thinking of that line as a tree (ways, seconds, dropped), nothing moves"));
        v("tree", (c, aa, vb, a) -> aa.tree.mermaid() + (aa.on() ? "\n%% desires now: " + String.join(" | ", aa.brainLines(30)) + "\n%% " + aa.ideasLine() : ""),
                f(S, "tree", "the behaviour tree as Mermaid, with the desires now"));
        v("predict", (c, aa, vb, a) -> aa.slow.predict(), f(S, "predict", "what the next step should take, before it runs"));
        v("slow", (c, aa, vb, a) -> aa.slow.command(a), f(S, "slow [on|off|step]", "slow mode: one step at a time, each waiting for aa go"));          // AA_STEP3 B
        v("only", (c, aa, vb, a) -> {
            String set = aa.only(a);           // live-facts: saved in this world's switch
            return a.isEmpty() ? (set == null ? "aa only: off (usage: aa only <need>|off, e.g. aa only torches)" : "only: " + set)
                    : set == null ? "aa only: off - every need and want is back" : "only: " + set + " - every other need and want is silent (danger, reflexes and your orders stay on); aa only off to end";
        }, f(S, "only <need>|off", "only that need runs (danger, reflexes and your orders stay on); off ends it"));
        v("needs", (c, aa, vb, a) -> aa.needs(a), f(S, "needs on|off", "own needs on (an order: only worried hunger/danger cut in), or orders only (per world)"));             // orders-tier: saved per world
        v("forget", (c, aa, vb, a) -> a.equals("stations") ? c.nearby.forgetAllStations() : "usage: aa forget stations (drops every station record of mine in this world)",
                f(S, "forget stations", "drop every station record of mine in this world"));
        v("eat", (c, aa, vb, a) -> aa.order("eat", List.of(new io.github.mojolowjo.entropybot.aa.moves.Step.Eat())), f(S, "eat (the best food in the bag)", "eat the best food in the bag"));
        v("scan", (c, aa, vb, a) -> aa.order("scan", List.of(new io.github.mojolowjo.entropybot.aa.moves.Step.Scan(false))), f(S, "scan (read the chests in reach)", "read the chests in reach"));
        v("restore", (c, aa, vb, a) -> {
            int r;
            try { r = a.isEmpty() ? io.github.mojolowjo.entropybot.restore.RestorePlan.nowDefault() : Integer.parseInt(a); } catch (NumberFormatException e) { return "usage: aa restore [radius]"; }
            return aa.order("restore", List.of(new io.github.mojolowjo.entropybot.aa.moves.Step.PutBack(r)));
        }, f(S, "restore [radius] (put back the blocks I broke)", "put back the blocks I broke within that radius"));
        v("worth", (c, aa, vb, a) -> a.isEmpty() ? "usage: aa worth <item> (e.g. aa worth iron_pickaxe): the terms its worth is derived from, or UNKNOWN" : io.github.mojolowjo.entropybot.aa.brain.ItemWorth.explain(a.replace(" ", "_")),
                f(S, "worth <item>", "the terms an item's worth is derived from, or UNKNOWN"));
        v("ore", (c, aa, vb, a) -> aa.oreInfo(a), f(S, "ore [info] [<thing>]", "the ore heights learned from the chunks seen"));
        v("stats", (c, aa, vb, a) -> aa.statsText(), f(S, "stats", "AA's run statistics"));
        v("litter", (c, aa, vb, a) -> RestoreLive.INSTANCE.litterText(), f(S, "litter", "the blocks I broke or placed that are still out of place"));
        // ---- tuning
        v("tune", (c, aa, vb, a) -> aa.tune(a == null || a.isBlank() ? new String[0] : a.trim().split("\\s+")),
                f(T, "tune [<group>|<name> [<n>]]", "the tuning numbers: list a group, show or set one"));     // AA_ROUND4 B3
        v("on", (c, aa, vb, a) -> aa.turnOn(), f(T, "on", "AA drives (saved, survives a restart)"));
        v("off", (c, aa, vb, a) -> aa.turnOff("aa off"), f(T, "off", "AA stops driving"));
        v("fair", (c, aa, vb, a) -> aa.fair(a), f(T, "fair on|off", "on: only ore I have seen counts; off: the whole chunk index"));
        v("like", (c, aa, vb, a) -> aa.like(a), f(T, "like [<key> <n>]", "the personality (want weights, upkeep, sleep, margin): list or set one"));
        v("replan", (c, aa, vb, a) -> aa.replan(a), f(T, "replan on|off", "whether a task is planned again when its facts change"));
        v("horizon", (c, aa, vb, a) -> aa.horizon(a), f(T, "horizon [seconds]", "how far ahead the sequencer plans (saved)"));
        // ---- help
        v("help", (c, aa, vb, a) -> help(), f("help", "help", "this list"));
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

    /** Every form of the table, each once, in table order. */
    static List<Form> allForms() {
        List<Form> out = new ArrayList<>();
        for (Verb v : TABLE.values()) for (Form x : v.forms()) if (!out.contains(x)) out.add(x);
        return out;
    }

    /** One line of every form ("aa forms: aa on | aa off | ..."): the area of the owner's help page. */
    static String formsLine() {
        List<String> f = new ArrayList<>();
        for (Form x : allForms()) f.add("aa " + x.form());
        return "aa forms: " + String.join(" | ", f);
    }

    /**
     * aa help (and the bare aa), 0.39.z27: every live form with its meaning, one line each, under the headings of
     * {@link #GROUPS}; the area forms come from VerbTable's area row (one place each).
     */
    static String help() {
        List<String> out = new ArrayList<>();
        out.add("aa help: every aa form (docs/COMMANDS.md has the long text)");
        for (String g : GROUPS) {
            out.add(g + ":");
            if (g.equals(AREAS)) {
                for (VerbTable.Form x : VerbTable.of("area").forms()) out.add("  " + x.form() + " - " + x.meaning());
                continue;
            }
            for (Form x : allForms()) if (x.group().equals(g)) out.add("  aa " + x.form() + " - " + x.meaning());
        }
        return String.join("\n", out);
    }

    /** /bot tab completion after "aa ": the verb words of the dispatch table. */
    static List<String> words() { return List.copyOf(TABLE.keySet()); }

    /** The old USAGE name: the generated help. */
    static String usage() { return help(); }

    static String handle(Commands c, String rest) {
        String t = rest == null ? "" : rest.trim().toLowerCase();
        if (t.isEmpty()) return help();
        String verb = t.contains(" ") ? t.substring(0, t.indexOf(' ')) : t, arg = t.contains(" ") ? t.substring(t.indexOf(' ') + 1).trim() : "";
        Verb v = TABLE.get(verb);
        if (v == null) return "error: no aa form \"" + verb + "\" - " + formsLine();
        if (verb.equals("help")) return help();
        AaRuntime aa = c.aaRuntime;
        if (aa == null) return "error: aa isn't loaded yet (no world)";
        return v.run().run(c, aa, verb, arg);
    }
}
