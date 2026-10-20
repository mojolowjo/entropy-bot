package io.github.mojolowjo.entropybot.commands;

import java.util.List;

/**
 * 0.36.0 AA v0 (AA_PLAN 8; package P4): the {@code aa} verb: on | off | status | why | tree, and (S0, the owner's manual
 * driving) ask on|off | pick n | do need | skip need | use option. {@code aa on} and {@code brain on} exclude each other
 * (both saved in brain.json, both survive a restart).
 */
final class AaCommands {
    private AaCommands() {}

    static final String USAGE = "usage: aa on|off|status|why|tree|stats | aa like [<key> <n>] | aa want <thing> [n] [high|low] | aa drop <thing>|all | aa goals | aa build <name> here|at <x y z> [high|low] | aa ore [info] [<thing>] | aa ask on|off | aa pick <n> | aa do <need>|none | aa skip <need>|none | aa use <option> | aa replan on|off | aa look [smooth <ticks>] | aa horizon [seconds] | aa fair [on|off] | aa litter";

    static String handle(Commands c, String rest) {
        AaRuntime aa = c.aaRuntime;
        if (aa == null) return "error: aa isn't loaded yet (no world)";
        String t = rest == null ? "" : rest.trim().toLowerCase();
        String verb = t.contains(" ") ? t.substring(0, t.indexOf(' ')) : t, arg = t.contains(" ") ? t.substring(t.indexOf(' ') + 1).trim() : "";
        switch (verb) {
            case "on" -> { return aa.turnOn(); }
            case "off" -> { return aa.turnOff("aa off"); }
            case "", "status" -> {
                return aa.statusLine() + "\n" + aa.counts() + (aa.on() ? "\n" + aa.brainCounters() + "\ndesires: " + String.join(" | ", aa.brainLines(5)) + "\n" + aa.goalsLine() + "\n" + aa.wantsLine() + "\nrungs: " + aa.ladderLine() + "\n" + aa.optionStatus() : "");
            }
            case "why" -> {
                List<String> d = aa.decisions();
                String opts = aa.on() ? "\ndesires (top 5):\n" + String.join("\n", aa.brainLines(5)) + "\n" + aa.wantsLine() + "\n" + aa.ideasLine() + "\n" + String.join("\n", aa.optionLines()) : "";
                if (d.isEmpty()) return "aa why: no decisions yet" + (aa.on() ? " (rungs: " + aa.ladderLine() + ")" + opts : " (aa is off)");
                List<String> last = d.subList(Math.max(0, d.size() - 10), d.size());
                return "aa why (last " + last.size() + " of " + d.size() + "):\n" + String.join("\n", last) + (aa.on() ? "\nrungs: " + aa.ladderLine() : "") + opts;
            }
            case "tree" -> { return aa.tree.mermaid() + (aa.on() ? "\n%% desires now: " + String.join(" | ", aa.brainLines(30)) + "\n%% " + aa.ideasLine() : ""); }
            case "stats" -> { return aa.statsText(); }
            case "litter" -> { return RestoreLive.INSTANCE.litterText(); }
            case "like" -> { return aa.like(arg); }
            case "want" -> { return aa.want(arg); }
            case "drop" -> { return aa.drop(arg); }
            case "goals" -> { return aa.goalsText(); }
            case "ore" -> { return aa.oreInfo(arg); }
            case "replan" -> { return aa.replan(arg); }
            case "look" -> { return aa.look(arg); }
            case "horizon" -> { return aa.horizon(arg); }
            case "fair" -> { return aa.fair(arg); }
            case "build" -> { return aa.build(arg); }
            case "ask", "pick", "do", "skip", "use" -> {
                if (!aa.on()) return "aa is off (aa on first)";
                return aa.manual(verb, arg);
            }
            default -> { return USAGE; }
        }
    }
}
