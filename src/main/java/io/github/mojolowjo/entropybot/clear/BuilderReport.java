package io.github.mojolowjo.entropybot.clear;

import io.github.mojolowjo.entropybot.clear.BaritoneMask.Leftover;
import io.github.mojolowjo.entropybot.clear.BaritoneMask.Verdict;

import java.util.ArrayList;
import java.util.List;

/**
 * 0.29.0: the end text of a {@code dig ... via baritone} (docs/DIG_VIA_BARITONE_PLAN.md section 3). Baritone gives no
 * reasons, so the text comes from the mod's own count (blocks of the box that turned to air) and the end re-scan
 * ({@link BaritoneMask#leftover}). Never a bare ok: "done: ..." only when nothing breakable is left; water left in the way
 * is "blocked by water at x y z ..." (what the water round and chains read, as the plain dig); anything else is
 * "stopped: ... M left (...) - next: ...". Game-free.
 */
public final class BuilderReport {
    private BuilderReport() {}

    /** Why the builder step ended before the re-scan. */
    public enum End {
        /** Baritone's builder went idle ("Done building", or it was cancelled from outside) */
        IDLE,
        /** the builder paused itself ("Unable to do it. Pausing."): it found no way to reach the rest */
        PAUSED,
        /** no block of the box changed for {@link #STALL_SECONDS} s while it was active */
        STALLED
    }

    public static final int STALL_SECONDS = 60;

    /**
     * The step's per-tick watch: null while the builder works, else how it ended. active/paused: Baritone's builder
     * (isActive, isPaused; a pause the mod asked for itself never gets here); ticksSinceChange: client ticks since a block
     * of the box last changed (or since the start).
     */
    public static End watch(boolean active, boolean paused, long ticksSinceChange) {
        if (!active) return End.IDLE;
        if (paused) return End.PAUSED;
        if (ticksSinceChange >= STALL_SECONDS * 20L) return End.STALLED;
        return null;
    }

    /** True when the step gave up (an incident is due, the text says "gave up"). */
    public static boolean gaveUp(End end) {
        return end == End.PAUSED || end == End.STALLED;
    }

    /**
     * The end text. label: the job's label ("digging 0 83 -3 to 5 83 2"); broke: blocks of the box it broke; left: the
     * re-scan; maskErrors: exceptions inside the mask during the job.
     */
    public static String text(String label, int broke, Leftover left, End end, long maskErrors) {
        int unreach = left.unreachable(), water = left.count(Verdict.WATER), errs = left.count(Verdict.ERROR);
        String brokeText = "broke " + broke + (broke == 1 ? " block" : " blocks");
        List<String> parts = parts(left);
        if (water > 0 && left.liquid != null) {
            String kind = left.liquidKind == null ? "water" : left.liquidKind;
            return ClearEngine.BLOCKED_BY + kind + " at " + left.liquid.key() + " - " + brokeText + "; " + total(left) + " left ("
                    + String.join(", ", parts) + ")" + (gaveUp(end) ? "; " + why(end) : "") + maskNote(maskErrors)
                    + " - next: say the same dig with water to seal it off first";
        }
        // 0.30.2 (trial 8 door cut): nothing breakable left is a finished job, whatever the builder's state (a pause or
        // a stall after the last block went is not a give-up)
        if (unreach == 0 && errs == 0) {
            return "done: " + label + " - " + brokeText + (parts.isEmpty() ? "" : " (left " + String.join(", ", parts) + ")") + maskNote(maskErrors);
        }
        StringBuilder sb = new StringBuilder("stopped: ").append(label);
        if (gaveUp(end)) sb.append(" - gave up (").append(why(end)).append(")");
        sb.append(" - ").append(brokeText).append("; ").append(total(left)).append(" left");
        if (!parts.isEmpty()) sb.append(" (").append(String.join(", ", parts)).append(")");
        sb.append(maskNote(maskErrors));
        sb.append(" - next: ").append(next(left));
        return sb.toString();
    }

    /**
     * The end when the bag holds no tool that can harvest a block still to dig (a pickaxe broke mid-box): the builder
     * would dig it by hand, slowly and with no drop. Plain dig's text ({@link DigWhy#pickaxeText}).
     */
    /** 0.34.3: "... (broke 0 blocks); 2 more on the way in (outside the box) - next: ...": the way-in count before the next part. */
    public static String withWayIn(String msg, int outside) {
        if (msg == null || outside <= 0) return msg;
        String add = "; " + outside + " more on the way in (outside the box)";
        int i = msg.indexOf(" - next: ");
        return i < 0 ? msg + add : msg.substring(0, i) + add + msg.substring(i);
    }

    public static String noTool(String label, int broke, String block) {
        String t = DigWhy.pickaxeText(block);
        int i = t.indexOf(" - next: ");
        return "stopped: " + label + " - " + t.substring(0, i) + " (broke " + broke + (broke == 1 ? " block" : " blocks") + ")" + t.substring(i);
    }

    static String why(End end) {
        return switch (end) {
            case PAUSED -> "Baritone found no way to reach the rest";
            case STALLED -> "no block broken for " + STALL_SECONDS + " s";
            default -> "it stopped";
        };
    }

    /** Every non-empty cell left in the box. */
    static int total(Leftover left) {
        int n = 0;
        for (int c : left.counts.values()) n += c;
        return n;
    }

    /** "3 unreachable, e.g. 1 2 3", "2 protected (chest...)", ... in a fixed order. */
    static List<String> parts(Leftover left) {
        List<String> out = new ArrayList<>();
        add(out, left, Verdict.DIG, "unreachable", true);
        add(out, left, Verdict.PROTECTED, "protected", true);
        add(out, left, Verdict.FENCED, "in a safe area or protect box", false);
        add(out, left, Verdict.WATER, "next to water/lava", true);
        add(out, left, Verdict.HAZARD, "hazards", false);
        add(out, left, Verdict.UNBREAKABLE, "unbreakable", false);
        add(out, left, Verdict.ORE, left.count(Verdict.ORE) == 1 ? "ore left in place for you" : "ores left in place for you", false);
        add(out, left, Verdict.ERROR, "mask errors", true);
        return out;
    }

    private static void add(List<String> out, Leftover left, Verdict v, String what, boolean example) {
        int n = left.count(v);
        if (n <= 0) return;
        String s = n + " " + what;
        List<Pos> ex = left.examples.get(v);
        if (example && ex != null && !ex.isEmpty()) s += ", e.g. " + ex.get(0).key();
        out.add(s);
    }

    static String maskNote(long maskErrors) {
        return maskErrors > 0 ? "; the mask failed " + maskErrors + (maskErrors == 1 ? " time" : " times") + " (those blocks were left alone, see the log)" : "";
    }

    static String next(Leftover left) {
        if (left.unreachable() > 0) return "dig the rest again from closer (or without via baritone), or goto near " + left.examples.get(Verdict.DIG).get(0).key();
        if (left.count(Verdict.ERROR) > 0) return "check the log for [entropybot] dig via baritone";
        return "nothing more I may dig there";
    }
}
