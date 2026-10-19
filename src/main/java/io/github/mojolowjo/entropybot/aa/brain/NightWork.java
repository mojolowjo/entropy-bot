package io.github.mojolowjo.entropybot.aa.brain;

import io.github.mojolowjo.entropybot.aa.core.Blackboard;
import io.github.mojolowjo.entropybot.aa.core.Inputs;

import java.util.ArrayList;
import java.util.List;

/**
 * to-do 1d (nightwork): sheltered at night the bot used to wait till morning. From inside the shell it may do the work
 * that needs no surface: smelt and craft where it stands, or dig down from the shelter floor (a 2-deep shaft, then a
 * staircase from its bottom: the shell and its roof stay closed to the surface). Mining desires keep their night bonus
 * (Brain.score); once the bot is under ground ({@code sheltered} false) they run as they do for any dug-in bot. At dawn
 * {@link Night#hostile} turns false, this desire goes and the ladder's surface gate climbs out. Sorting the bag has no
 * command that works without chests, so it is not offered. Pure.
 */
public final class NightWork {
    private NightWork() {}

    /** Over {@link Brain#URGENT} (75) so it ends the shelter's wait, under the shelter's own 80 and the owner's 100. */
    public static final double SCORE = 78;
    public static final int SHAFT = 2, STAIRS = 24, MIN_PICK_PCT = 10;

    public enum Work { NONE, SMELT, CRAFT, STAIRS }

    /** In a shell of its own, built and waiting, with monsters about (night, not Peaceful). */
    public static boolean active(Blackboard bb) { return bb.in.sheltered && Night.hostile(bb); }

    /** Score of the night-work desire: {@link #SCORE} when there is something to do from inside, else -1. */
    public static double score(Blackboard bb) { return active(bb) && pick(bb.in) != Work.NONE ? SCORE : -1; }

    /** What to do now, in order: smelt the raw iron, craft the next tool or torches, else dig down. */
    public static Work pick(Inputs in) {
        if (in.bag.getOrDefault("raw_iron", 0) > 0 && in.furnaceInBag) return Work.SMELT;
        if (craft(in) != null) return Work.CRAFT;
        if (in.pickTier >= 1 && in.pickPct > MIN_PICK_PCT) return Work.STAIRS;
        return Work.NONE;
    }

    /** The craft chain, or null: an iron pickaxe from 3 ingots, torches from coal and sticks/planks. */
    public static String craft(Inputs in) {
        if (in.pickTier < 3 && in.ironIngots >= 3 && (in.tableKnown || in.tableInBag)) return "craft iron_pickaxe";
        if (in.torches < 8 && in.coal >= 1 && (in.planks >= 2 || in.wood >= 1) && (in.tableKnown || in.tableInBag)) return "craft torch 16";
        return null;
    }

    /** The chain for {@code work} at feet (x, y, z); null for NONE. */
    public static String chain(Inputs in, Work work, int x, int y, int z, int dx, int dz) {
        return switch (work) {
            case SMELT -> "smelt iron_ingot " + Math.min(64, in.bag.getOrDefault("raw_iron", 0)) + " here";
            case CRAFT -> craft(in);
            case STAIRS -> String.join(" then ", stairs(x, y, z, dx, dz));
            case NONE -> null;
        };
    }

    /**
     * Down the floor cell first (SHAFT deep, straight below: a hole that only joins the shell), then a staircase from its
     * bottom. The staircase's first column then lies under the shell wall's foot, never in it.
     */
    public static List<String> stairs(int x, int y, int z, int dx, int dz) {
        List<String> out = new ArrayList<>();
        out.add("dig " + x + " " + (y - SHAFT) + " " + z + " " + x + " " + (y - 1) + " " + z + " ores via engine");
        out.add("goto " + x + " " + (y - SHAFT) + " " + z);
        out.addAll(io.github.mojolowjo.entropybot.commands.StairPlan.steps(x, y - SHAFT, z, dx, dz, STAIRS));
        return out;
    }
}
