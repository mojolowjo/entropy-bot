package io.github.mojolowjo.entropybot.strip;

import com.google.gson.JsonObject;
import io.github.mojolowjo.entropybot.clear.Pos;

import java.util.ArrayList;
import java.util.List;

/** B7d D2: the strip mine's replies, word for word the bridge's (startStripmine, mineCommand, oresCommand's prefer). Pure. */
public final class StripTexts {
    private StripTexts() {}

    public static final String NO_MINE = "error: no mine marked - stand where it should start, face the way to dig, and say \"place mine\"";
    public static final String TURN_USAGE = "usage: stripmine turn left|right";
    public static final String AREA_HINT = "area here <r> <name>";

    public static String noMine(String name) {
        return name.equals("mine") ? NO_MINE
                : "error: " + name + " is no mine (mark it standing at the start, facing the way to dig: \"mark " + name + " north\")";
    }

    public static String resetReply() { return "ok: the mine starts over from branch 1 (the chests stay where they are)"; }

    /** "stripmine status". */
    public static String status(MineGeom g, JsonObject note, boolean collect) {
        List<Pos> chests = MineBook.chests(note);
        List<String> cs = new ArrayList<>();
        for (Pos c : chests) cs.add(c.key());
        return "mine at " + g.x + " " + g.y + " " + g.z + " going " + g.dir + ": " + (MineBook.k(note) - 1) + " branch pairs dug"
                + (MineBook.setup(note) ? ", chests at " + String.join(" and ", cs) : ", not set up yet")
                + "; ores: " + (collect ? "collecting inside the mapped area" : "listing only");
    }

    /** "stripmine ores collect|list" (areaText: "my areas (map, home)"). */
    public static String oresSet(boolean collect, String areaText) {
        return "ok: " + (collect ? "I'll mine the ores I pass inside " + areaText + " and take them to the base; elsewhere they stay listed"
                : "I'll leave ores in place and list them (PM \"ores\")") + " - from the next run on";
    }

    /** "stripmine ores". */
    public static String oresMode(boolean collect) {
        return "ores: " + (collect ? "mined inside the mapped area and taken to the base (\"mine strip ores list\" to leave them)"
                : "left in place and listed (\"mine strip ores collect\" to mine them inside the mapped area)");
    }

    /** mapAreaText with the mod: "my areas (a, b)" or "my areas (none set - area <name> <r>)". */
    public static String areaText(List<String> names) {
        return "my areas (" + (names.isEmpty() ? "none set - " + AREA_HINT : String.join(", ", names)) + ")";
    }

    public static String badEntrance(String mineName, Pos p, String why) {
        return "error: the " + mineName + " entrance " + p.key() + " is no place to start: " + why + " - mark it where I can stand";
    }

    public static String outsideAreas(String mineName, Pos p, String areaText) {
        return "error: the mine " + mineName + " (" + p.key() + ") is outside " + areaText + ", where I mine no ores - " + AREA_HINT;
    }

    public static String stripJobLabel(OreSpec spec, int target, String mineName) {
        return "strip mining for " + spec.label + (target != 0 ? " (" + target + ")" : "") + " at " + mineName;
    }

    /** stripnext's final note: "mined 2 iron ores in 2 runs (that makes 2); last run: ...". */
    public static String stripDone(OreSpec spec, int got, int runs, String why, String last) {
        return "mined " + spec.oresWord(got) + " in " + runs + " run" + (runs == 1 ? "" : "s") + " (" + why + "); last run: " + (last == null ? "" : last);
    }

    /** Why "mine strip" stops starting runs, or null to go on. */
    public static String stripStop(int target, int got, boolean timeUp, int runs) {
        if (target != 0 && got >= target) return "that makes " + target;
        if (timeUp) return "an hour is up";
        if (runs >= StripRules.MAX_RUNS) return StripRules.MAX_RUNS + " runs";
        return null;
    }

    public static final String MINE_GRAMMAR = "say \"mine strip <ores> [n] [at <mine>]\" or \"mine cave <ores> [n | <minutes>m] [at <cave>]\" (ores: iron,diamond or "
            + "iron_ore or any; \"ores prefer ...\" sets the default list) - \"mine <ore block> [n]\" still mines exposed ores near me";

    // ---- "ores prefer" ----

    public static String preferShow(String prefer) {
        return prefer != null ? "I go for " + prefer + " when a \"mine\" names no ores" : "no preferred ores yet - \"ores prefer diamond,iron,copper\"";
    }

    public static String preferSet(String label) { return "ok: when a \"mine\" names no ores I go for " + label + ", in that order"; }

    public static String anA(String word) { return (word.matches("(?i)^[aeiou].*") ? "an " : "a ") + word; }

    /** toolcheck: the corridor's pickaxe is here: "fetched an iron pickaxe for the deepslate_redstone_ore at x y z". */
    public static String toolNote(String how, String need, String why) {
        return how + " " + anA(need) + " pickaxe for the " + why.replaceFirst("^blocked:tool ", "").replaceFirst(" needs \\w+$", "");
    }

    /** toolcheck: no pickaxe to be had. */
    public static String noTool(String why, String need, String craftErr) {
        return "the mine corridor is blocked - " + why + " - I have no " + need + " pickaxe, none in my chests and I couldn't make one"
                + (craftErr != null ? " (" + craftErr.replaceFirst("^error: ", "") + ")" : "")
                + " - next: " + io.github.mojolowjo.entropybot.commands.Hints.pickaxeFix(need);
    }

    /** A turn the run made itself, as the run's note ("the corridor was blocked (...): mine turned west at ... - the next run digs there"). */
    public static String turnNote(String why, String turnedOk) { return "the corridor was blocked (" + why + "): " + turnedOk; }

    public static String turnOk(String said) { return said + " - the next run digs there"; }

    /** S1: a note with the base trip's count after it ("...; took 71 items to base"), an earlier count replaced. */
    public static String withBasePut(String note, int put) {
        String n = note == null ? "" : note.replaceFirst("; took \\d+ items to base$", "");
        return (n.isEmpty() ? "" : n + "; ") + "took " + put + " items to base";
    }

    /**
     * S1: the walk to the corridor end can't cross this ("couldn't reach the mine: ..." keeps the autominer's give-up
     * match): "the corridor runs over lava from 58 -55 194 - mark a new mine ("mark mine" a few levels up, or "mark mine
     * 57 -54 194 north")". The suggested spot is the last good cell before it, turned left.
     */
    public static String badFloorText(MineGeom g, StripRules.BadFloor b) {
        Pos last = g.cell(Math.max(0, b.i() - 1), 0);
        String head = b.what().equals("a drop") ? "the corridor has a drop at " + b.at().key() + " (a hole too deep to climb out of)"
                : "the corridor runs over " + b.what() + " from " + b.at().key();
        return "couldn't reach the mine: " + head + " - place a new mine (\"place mine\" a few levels up, or \"place mine "
                + last.key() + " " + g.leftDir() + "\")";
    }
}
