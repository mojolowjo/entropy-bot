package io.github.mojolowjo.entropybot.vocab;

import java.util.List;
import java.util.Locale;

/**
 * V1b (VOCABULARY 4): {@code attack <entity|player|mob>}. A hostile mob: go. A passive mob: a {@code confirm} first,
 * unless the owner typed its kind out in full ({@code attack cow}). A player: never, unless {@code defence players on}
 * (owner only, off at every game start). A pet or someone's animal: never. A mob with a name tag: a confirm first, and a
 * name counts only when it has a letter (health-display mods put "20/20" or "12" there). Pure.
 */
public final class AttackRules {
    private AttackRules() {}

    public enum How { NEAREST, ID, NAME }

    /** What "attack ..." asked for: how, the id or the word, and a trailing confirm. */
    public record Arg(How how, int id, String word, boolean confirm, String error) {}

    public static final String USAGE = "usage: attack <mob kind|player|entity id> [confirm] | attack nearest | attack target <id>";

    public static Arg parse(String rest) {
        List<String> w = new java.util.ArrayList<>(List.of((rest == null ? "" : rest.trim().toLowerCase(Locale.ROOT)).split("\\s+")));
        w.removeIf(String::isEmpty);
        boolean confirm = !w.isEmpty() && w.get(w.size() - 1).equals("confirm");
        if (confirm) w.remove(w.size() - 1);
        if (!w.isEmpty() && w.get(0).equals("target")) w.remove(0);       // the companion's point key: "attack target <id>"
        if (w.isEmpty() || (w.size() == 1 && w.get(0).equals("nearest"))) return new Arg(How.NEAREST, -1, null, confirm, null);
        if (w.size() != 1) return new Arg(null, -1, null, false, USAGE);
        String a = w.get(0);
        if (a.matches("^\\d+$")) {
            try { return new Arg(How.ID, Integer.parseInt(a), null, confirm, null); } catch (NumberFormatException e) { return new Arg(null, -1, null, false, USAGE); }
        }
        if (!a.matches("^[a-z0-9_:.-]{1,40}$")) return new Arg(null, -1, null, false, USAGE);
        return new Arg(How.NAME, -1, a, confirm, null);
    }

    /** A name tag counts as a name only with a letter in it ("20/20", "12" are health displays). */
    public static boolean isName(String customName) {
        return customName != null && customName.matches("(?s).*[A-Za-z].*");
    }

    /** What the rules see of the target. typedOut: the owner wrote its kind (or the player's name) in full. */
    public record Target(String kind, boolean player, boolean pet, boolean hostile, String customName, boolean typedOut) {}

    public enum Verdict { GO, CONFIRM, REFUSE }

    public record Answer(Verdict verdict, String text) {}

    public static Answer decide(Target t, boolean confirmed, boolean playersOn, int id) {
        if (t.player()) {
            if (!playersOn) return new Answer(Verdict.REFUSE, "error: " + t.kind() + " is a player: never (defence players is off)");
            return new Answer(Verdict.GO, "attacking the player " + t.kind() + " (defence players is on)");
        }
        if (t.pet()) return new Answer(Verdict.REFUSE, "error: that " + t.kind() + " is a pet or someone's animal: never");
        if (isName(t.customName()) && !confirmed)
            return new Answer(Verdict.CONFIRM, "that " + t.kind() + " is named '" + t.customName() + "' - say attack " + id + " confirm");
        if (t.hostile()) return new Answer(Verdict.GO, "attacking the " + t.kind());
        if (t.typedOut() || confirmed) return new Answer(Verdict.GO, "attacking the " + t.kind() + " (a passive mob, as you asked)");
        return new Answer(Verdict.CONFIRM, "that " + t.kind() + " is passive - say attack " + id + " confirm (or type it out: attack " + t.kind() + ")");
    }

    /** "defence players on|off" -> true/false, null when the line isn't that. */
    public static Boolean playersSetting(String rest) {
        String r = rest == null ? "" : rest.trim().toLowerCase(Locale.ROOT);
        if (r.equals("players on")) return true;
        if (r.equals("players off")) return false;
        return null;
    }
}
