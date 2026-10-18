package io.github.mojolowjo.entropybot.commands;

import java.util.List;

/**
 * 0.36.0 AA v0 (AA_PLAN 8; package P4): the {@code aa} verb: on | off | status | why | tree. {@code aa on} and
 * {@code brain on} exclude each other (both saved in brain.json, both survive a restart).
 */
final class AaCommands {
    private AaCommands() {}

    static final String USAGE = "usage: aa on|off|status|why|tree";

    static String handle(Commands c, String rest) {
        AaRuntime aa = c.aaRuntime;
        if (aa == null) return "error: aa isn't loaded yet (no world)";
        String t = rest == null ? "" : rest.trim().toLowerCase();
        switch (t) {
            case "on" -> { return aa.turnOn(); }
            case "off" -> { return aa.turnOff("aa off"); }
            case "", "status" -> {
                return aa.statusLine() + "\n" + aa.counts() + (aa.on() ? "\nladder: " + aa.ladderLine() : "");
            }
            case "why" -> {
                List<String> d = aa.decisions();
                if (d.isEmpty()) return "aa why: no decisions yet" + (aa.on() ? " (ladder: " + aa.ladderLine() + ")" : " (aa is off)");
                List<String> last = d.subList(Math.max(0, d.size() - 10), d.size());
                String iron = aa.ironOptions();        // 0.36.a8: the IRON rung's option scorer
                return "aa why (last " + last.size() + " of " + d.size() + "):\n" + String.join("\n", last) + (aa.on() ? "\nladder: " + aa.ladderLine() : "")
                        + (iron == null ? "" : "\niron options: " + iron);
            }
            case "tree" -> { return aa.tree.mermaid(); }
            default -> { return USAGE; }
        }
    }
}
