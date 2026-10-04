package io.github.mojolowjo.entropybot.commands;

/**
 * B7e package N (item 2): replies that say the next step. A refusal ends with the exact command that fixes it, in one
 * form: {@code "<reply> - next: <command>"}. Pure.
 */
public final class Hints {
    private Hints() {}

    public static final String AREA_HERE = "area add <name> here 30";
    public static final String SCAN_BASE = "scan base";
    public static final String DEPOSIT = "deposit";

    /** reply + " - next: " + command; the reply as it is when it already names that command (or there is none). */
    public static String next(String reply, String command) {
        if (reply == null) return null;
        if (command == null || command.isEmpty() || reply.contains(command)) return reply;
        return reply + " - next: " + command;
    }

    /** An unknown word: the nearest known one when there is one, else help. */
    public static String unknown(String verb) {
        String s = VerbTable.suggest(verb);
        return "unknown command \"" + verb + "\" - " + (s != null ? "did you mean " + s + "? (help " + s + ")" : "next: help");
    }

    /** No pickaxe for a block that needs the given tier ("iron", "stone", "diamond"; null = any). */
    public static String pickaxeFix(String tier) {
        String t = tier == null || tier.isEmpty() || tier.equals("wood") || tier.equals("wooden") ? "stone" : tier;
        return t.equals("stone") ? "craft stone_pickaxe 3" : "get " + t + "_pickaxe 1 (or craft " + t + "_pickaxe)";
    }

    /** A place the bot doesn't know: base gets "setbase", mine "mark mine", food "mark food", others "places". */
    public static String placeFix(String name) {
        String n = name == null ? "" : name.trim().toLowerCase();
        if (n.equals("base")) return "setbase (standing at the base)";
        if (n.equals("mine")) return "mark mine (standing at its start, facing the way to dig)";
        if (n.equals("food")) return "mark food (next to the food chest)";
        return "places";
    }
}
