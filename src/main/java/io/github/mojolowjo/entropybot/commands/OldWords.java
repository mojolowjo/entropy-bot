package io.github.mojolowjo.entropybot.commands;

import java.util.ArrayList;
import java.util.List;

/**
 * V1a (0.22.0): the area, protect, guard and zone words that were cut (BRAIN_PLAN, the owner's decisions: no alias
 * period). An old form never runs; it answers with the new one ("that is now ..."). {@link #translate} also rewrites
 * saved routines and rules once (the migration). Pure; the hints go away at 0.24.0.
 */
public final class OldWords {
    private OldWords() {}

    /** The new command for an old one, or null when the line isn't an old form (or has no one-to-one new form). */
    public static String translate(String verb, String rest) {
        String v = verb == null ? "" : verb.toLowerCase();
        List<String> w = Texts.words(rest == null ? "" : rest.trim());
        String sub = w.isEmpty() ? "" : w.get(0).toLowerCase();
        switch (v) {
            case "dig" -> { return io.github.mojolowjo.entropybot.clear.DigArgs.oldSurfaceHint(rest); }     // V1b: down|up N -> -N|+N
            case "protect" -> {
                if (sub.isEmpty() || sub.equals("list")) return "area list";
                return protectForm(w);
            }
            case "unprotect" -> {
                if (w.isEmpty()) return null;
                return "area del " + w.get(0) + (w.size() > 1 && w.get(1).equalsIgnoreCase("confirm") ? " confirm" : "");
            }
            case "guard" -> {
                if (sub.isEmpty() || sub.equals("status")) return "fence";
                if (sub.equals("vetoes") || sub.equals("check") || sub.equals("mode")) return "fence " + String.join(" ", w);
                return null;
            }
            case "area" -> {
                if (sub.equals("add") && w.size() >= 4 && w.get(2).equalsIgnoreCase("here") && isInt(w.get(3)))
                    return "area here " + w.get(3) + " " + w.get(1);
                if (sub.equals("add") && (w.size() == 6 || w.size() == 8) && allInts(w.subList(2, w.size())))
                    return "area " + String.join(" ", w.subList(2, 6)) + " " + w.get(1) + (w.size() == 8 ? " " + w.get(6) + " " + w.get(7) : "");
                if (sub.equals("protect") && w.size() >= 2) {
                    List<String> p = new ArrayList<>(w.subList(1, w.size()));
                    if (p.size() >= 2 && p.size() <= 4 && allInts(p.subList(1, p.size()))) p.add(1, "here");
                    return protectForm(p);
                }
                if ((sub.equals("unprotect") || sub.equals("remove")) && w.size() >= 2)
                    return "area del " + w.get(1) + (w.size() > 2 && w.get(2).equalsIgnoreCase("confirm") ? " confirm" : "");
                // "area <name> <r>" / "area <name> here <r>"
                if (!sub.isEmpty() && !isInt(sub) && !PolicyCommands.AREA_WORDS.contains(sub)) {
                    if (w.size() == 2 && isInt(w.get(1))) return "area here " + w.get(1) + " " + w.get(0);
                    if (w.size() == 3 && w.get(1).equalsIgnoreCase("here") && isInt(w.get(2))) return "area here " + w.get(2) + " " + w.get(0);
                }
                return null;
            }
            default -> { return null; }
        }
    }

    /** "protect <name> here <r> [down up]" or "protect <name> x1 y1 z1 x2 y2 z2" as the safe-area form. */
    private static String protectForm(List<String> w) {
        if (w.size() >= 3 && w.get(1).equalsIgnoreCase("here") && isInt(w.get(2))) {
            String d = w.size() > 3 && isInt(w.get(3)) ? w.get(3) : "8", u = w.size() > 4 && isInt(w.get(4)) ? w.get(4) : "16";
            return "area here " + w.get(2) + " " + w.get(0) + " safe " + d + " " + u;
        }
        if (w.size() == 7 && allInts(w.subList(1, 7)))
            return "area " + w.get(1) + " " + w.get(3) + " " + w.get(4) + " " + w.get(6) + " " + w.get(0) + " safe " + w.get(2) + " " + w.get(5);
        return "area here <r> " + (w.isEmpty() ? "<name>" : w.get(0)) + " safe";
    }

    /**
     * The answer to an old word, or null when the line isn't one (it then runs as usual). Covers every old form: the
     * ones without a one-to-one new form get a short pointer.
     */
    public static String hint(String verb, String rest) {
        String v = verb == null ? "" : verb.toLowerCase();
        String r = rest == null ? "" : rest.trim();
        String sub = Texts.words(r).isEmpty() ? "" : Texts.words(r).get(0).toLowerCase();
        String t = translate(v, r);
        if (t != null) return "that is now " + t;
        switch (v) {
            case "protect" -> { return "that is now area here <r> <name> safe (or area x z x2 z2 <name> safe y1 y2)"; }
            case "unprotect" -> { return "that is now area del <name> confirm"; }
            case "guard" -> { return "that is now fence (fence, fence vetoes, fence check x y z break|place|go, fence mode strict|log confirm)"; }
            case "zone" -> { return "zone is gone: make an area (area x z x2 z2 <name> [type] [y1 y2]) and name it: build floor <block> <area>, build clear <area>"; }
            case "area" -> {
                if (sub.equals("add") || sub.equals("protect")) return "that is now area here <r> <name> [type] | area x z x2 z2 <name> [type] [y1 y2]";
                if (sub.equals("remove") || sub.equals("unprotect")) return "that is now area del <name> confirm";
                if (sub.equals("corner1") || sub.equals("corner2")) return "area corner1/corner2 are gone: the companion marks corners, or type area x z x2 z2 <name> [type]";
                if (sub.equals("grow")) return "area grow is gone: set the area again with area x z x2 z2 <name> [type] (or area here <r> <name>)";
                return null;
            }
            default -> { return null; }
        }
    }

    /**
     * V1b: the answer to a typed line (one command or a chain) that uses a removed word, or null (it runs). Each step of
     * a chain is checked; the first old one answers. The V1a words (area, protect, guard, zone) answer in their own verbs.
     */
    public static String removedAnswer(String verb, String rest, String raw) {
        String v = verb == null ? "" : verb.toLowerCase();
        String inner = null;          // a chain inside repeat, routine save, rule ... do
        String r0 = rest == null ? "" : rest.trim();
        if (v.equals("repeat")) inner = r0.replaceFirst("(?i)^(\\d+|forever)\\s+", "");
        else if ((v.equals("routine") || v.equals("routines")) && r0.toLowerCase().startsWith("save ")) inner = r0.replaceFirst("(?i)^save\\s+\\S+\\s*", "");
        else if ((v.equals("rule") || v.equals("rules")) && r0.toLowerCase().contains(" do ")) inner = r0.substring(r0.toLowerCase().indexOf(" do ") + 4);
        if (inner != null) {
            for (String s : Texts.splitChain(inner)) {
                String[] vr = Texts.verbAndRest(s);
                String h = v1b(vr[0], vr[1]);
                if (h != null) return h + " (in: " + s + ")";
            }
            return null;
        }
        List<String> steps = Texts.splitChain(raw == null ? "" : raw);
        if (steps.size() <= 1) return v1b(verb, rest);
        for (String s : steps) {
            String[] vr = Texts.verbAndRest(s);
            String h = v1b(vr[0], vr[1]);
            if (h != null) return h + " (in the chain: " + s + ")";
        }
        return null;
    }

    /** V1b: the plumbing verbs that left the surface; each still runs after the word debug ("debug autominer on"). */
    public static final java.util.Set<String> DEBUG_VERBS = java.util.Set.of("b", "memory", "watch", "recorder", "mouse", "route", "autominer",
            "reconnect", "sethome", "use", "caves", "ores", "poi");

    /** V1b: every word that left the surface (0.22.2); it answers with its new form. The hints go at 0.24.0. */
    public static final java.util.Set<String> REMOVED;

    static {
        java.util.Set<String> r = new java.util.TreeSet<>(DEBUG_VERBS);
        r.addAll(List.of("mark", "setbase", "forget", "base", "stripmine", "chop", "defense", "pos", "inventory", "equip", "?", "bed", "pois", "baritone",
                "allowed", "run", "where", "zone", "protect", "unprotect"));
        REMOVED = java.util.Collections.unmodifiableSet(r);
    }

    /** The V1b hint for one step, or null (the line runs). */
    static String v1b(String verb, String rest) {
        String v = verb == null ? "" : verb.toLowerCase();
        String r = rest == null ? "" : rest.trim();
        String lr = r.toLowerCase();
        String sp = r.isEmpty() ? "" : " " + r;
        if (DEBUG_VERBS.contains(v)) return "removed in 0.22; use debug " + v + sp;
        switch (v) {
            case "dig" -> {
                String t = translate(v, rest);
                return t == null ? null : "that is now " + t;
            }
            case "mark" -> { return "that is now place" + (r.isEmpty() ? " <name>" : sp); }
            case "setbase" -> { return "that is now place base" + sp; }
            case "forget" -> { return "that is now places forget" + (r.isEmpty() ? " <name>" : sp); }
            case "base" -> { return "that is now go base"; }
            case "stripmine" -> { return "that is now mine strip" + sp; }
            case "chop" -> { return "that is now cut" + (r.isEmpty() ? " <n>" : sp); }
            case "defense" -> { return "that is now defence" + sp; }
            case "defend" -> { return r.isEmpty() ? null : "that is now defence" + sp + " (defend alone holds this spot)"; }
            case "escort" -> { return lr.equals("off") ? "that is now dismiss" : null; }
            case "tools" -> {
                if (lr.matches("^ores\\s+iron$")) return "that is now tools mode best (or tools mode stone)";
                if (lr.matches("^ores\\s+cheapest$")) return "that is now tools mode cheapest";
                if (lr.startsWith("ores")) return "that is now tools mode best|cheapest|stone";
                return null;
            }
            case "build" -> { return lr.matches("^clear\\b.*") ? "that is now dig" + (lr.split("\\s+").length > 1 ? " " + r.split("\\s+")[1] : " <area>") + " (build only places now)" : null; }
            case "pos" -> { return "that is now status"; }
            case "inventory" -> { return "that is now inv"; }
            case "equip" -> { return "that is now wear"; }
            case "?" -> { return "that is now help" + sp; }
            case "bed" -> { return "that is now spawn"; }
            case "pois" -> { return "removed in 0.22; use debug poi" + sp; }
            case "baritone" -> { return "removed in 0.22; use debug b" + sp; }
            case "allowed" -> { return "that is now allow"; }
            case "run" -> { return "that is now " + (r.isEmpty() ? "<routine name>" : r) + " (say the routine's name)"; }
            case "where" -> { return "that is now have" + (r.isEmpty() ? " <item>" : sp); }
            default -> { return null; }
        }
    }

    /** A saved chain with every old form rewritten (null when nothing changed). */
    public static String rewriteChain(String chain) {
        if (chain == null) return null;
        List<String> steps = Texts.splitChain(chain);
        boolean changed = false;
        List<String> out = new ArrayList<>();
        for (String s : steps) {
            String[] vr = Texts.verbAndRest(s);
            String t = translate(vr[0], vr[1]);
            if (t != null && !t.contains("<")) {
                out.add(t);
                changed = true;
            } else out.add(s);
        }
        return changed ? String.join(" then ", out) : null;
    }

    static boolean isInt(String s) { return s != null && s.matches("^-?\\d+$"); }

    static boolean allInts(List<String> l) {
        if (l.isEmpty()) return false;
        for (String s : l) if (!isInt(s)) return false;
        return true;
    }
}
