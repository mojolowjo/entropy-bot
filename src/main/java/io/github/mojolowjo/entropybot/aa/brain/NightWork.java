package io.github.mojolowjo.entropybot.aa.brain;

import io.github.mojolowjo.entropybot.aa.core.Blackboard;
import io.github.mojolowjo.entropybot.aa.core.Inputs;
import io.github.mojolowjo.entropybot.aa.moves.Step;

import java.util.ArrayList;
import java.util.List;

/**
 * to-do 1d (nightwork): sheltered at night the bot used to wait till morning. From inside the shell it may do the work
 * that needs no surface: smelt and craft where it stands, or dig down from the shelter floor (a 2-deep shaft, then a
 * staircase from its bottom: the shell and its roof stay closed to the surface). AA_ROUND2 4: this is the mining
 * stock-ups' UNDER option (AaTree.underGoal; was a desire at 78), and UNDER work gains by the night's danger (Brain.score); once the bot is under ground ({@code sheltered} false) they run as they do for any dug-in bot. At dawn
 * {@link Night#hostile} turns false, this desire goes and the ladder's surface gate climbs out. Sorting the bag has no
 * command that works without chests, so it is not offered. Pure.
 */
public final class NightWork {
    private NightWork() {}

    public static int shaft() { return Tuning.i("nightWork.shaft"); }
    public static int stairsV() { return Tuning.i("nightWork.stairs"); }

    public enum Work { NONE, SMELT, CRAFT, STAIRS }

    /** In a shell of its own, built and waiting, with monsters about (night, not Peaceful). */
    public static boolean active(Blackboard bb) { return bb.in.sheltered && Night.hostile(bb); }

    /** What to do now, in order: smelt the raw iron, craft the next tool or torches, else dig down. */
    public static Work pick(Inputs in) {
        if (in.bag.getOrDefault("raw_iron", 0) > 0 && in.furnaceInBag) return Work.SMELT;
        if (craft(in) != null) return Work.CRAFT;
        // director 2026-10-19: no "save a nearly broken tool" rule (live aa388 dug 114 blocks by hand at 29/131): a worn
        // pickaxe still digs; its wear is a cost (ToolWear) where the ways are priced
        if (in.pickTier >= 1) return Work.STAIRS;
        return Work.NONE;
    }

    /** The craft, or null: an iron pickaxe from 3 ingots, torches from coal and sticks/planks. */
    public static Step craft(Inputs in) {
        if (in.pickTier < 3 && in.ironIngots >= 3 && (in.tableKnown || in.tableInBag)) return new Step.Craft("iron_pickaxe", 1);
        if (in.torches < 8 && in.coal >= 1 && in.torchCoal() && (in.planks >= 2 || in.wood >= 1) && (in.tableKnown || in.tableInBag)) return new Step.Craft("torch", 16);
        return null;
    }

    /** Set when the night work dug its stairs down; the surface gate climbs back up them at dawn. */
    public static final String DUG_FACT = "aa.nightDug";

    /** Notes the staircase dug at night (the adapter's chain start). */
    public static void noteDug(Blackboard bb, Work w) { if (w == Work.STAIRS) bb.fact(DUG_FACT, Boolean.TRUE); }

    /**
     * overnight 2026-10-19 (stayed at Y30 after dawn): the bot dug down at night and it is day now, under
     * {@code surfaceDepth} or more of ground: climb out (its own stairs, or pillar up the shaft) before day work.
     * Back near the surface the note is cleared.
     */
    public static boolean climbAtDawn(Blackboard bb, int surfaceDepth) {
        if (!(bb.fact(DUG_FACT) instanceof Boolean b && b)) return false;
        if (bb.in.underDepth < surfaceDepth) { bb.fact(DUG_FACT, Boolean.FALSE); return false; }
        return !Night.hostile(bb);
    }

    /** The typed steps for {@code work} at feet (x, y, z); null for NONE. */
    public static List<Step> steps(Inputs in, Work work, int x, int y, int z, int dx, int dz) {
        return switch (work) {
            case SMELT -> {
                int n = Math.min(64, in.bag.getOrDefault("raw_iron", 0));
                yield List.of(new Step.Smelt("iron_ingot", n, true));
            }
            case CRAFT -> craft(in) == null ? null : List.of(craft(in));
            case STAIRS -> stairs(x, y, z, dx, dz);
            case NONE -> null;
        };
    }

    /**
     * Down the floor cell first (SHAFT deep, straight below: a hole that only joins the shell), then a staircase from its
     * bottom. The staircase's first column then lies under the shell wall's foot, never in it.
     */
    public static List<Step> stairs(int x, int y, int z, int dx, int dz) {
        List<Step> out = new ArrayList<>();
        out.add(new Step.Dig(new int[]{x, y - shaft(), z}, new int[]{x, y - 1, z}, true, true));
        out.add(new Step.Walk(x, y - shaft(), z, 0));
        int sy = y - shaft(), every = io.github.mojolowjo.entropybot.commands.StairPlan.torchEvery();
        for (int i = 1; i <= stairsV(); i++) {
            int cx = x + dx * i, cz = z + dz * i;
            out.add(new Step.Dig(new int[]{cx, sy - i, cz}, new int[]{cx, sy - i + 2, cz}, true, true));
            out.add(new Step.Walk(cx, sy - i, cz, 0));
            if (i % every == 0) out.add(Step.Build.one("torch", x + dx * (i - 1), sy - i + 1, z + dz * (i - 1)));
        }
        return out;
    }
}
