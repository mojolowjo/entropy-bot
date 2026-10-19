package io.github.mojolowjo.entropybot.aa.brain;

import io.github.mojolowjo.entropybot.aa.core.Inputs;

/**
 * BRAIN_PLAN 2.11 (B6): helping the owner. One desire, weighted by the personality's {@code help}, rising when the owner
 * is near and busy (the companion mod's activity report). What it does, first that fits:
 * <ol>
 * <li>fight: monsters near the owner: {@code come then attack nearest}; 2 x weight + 10 per monster (3 at most).</li>
 * <li>give: the owner builds and the bag holds 8+ of the block they place: {@code give me <block> n}; 1.5 x weight.</li>
 * <li>fetch: they build and the bag lacks it: the planner's {@code goal <block> 32}, then give; 1 x weight.</li>
 * <li>light: the owner's spot is dark, the bot has 4+ torches: {@code come then light here 8}; 1.25 x weight.</li>
 * <li>follow at a distance: they are busy more than 16 away: {@code come}; 1 x weight.</li>
 * </ol>
 * An idle owner (or no report) halves every part but the fight. Never 75 or more: helping waits for the running task
 * (owner goals, {@code aa want}, stay the direct way). Off when the owner is offline, farther than 48, or {@code assist}
 * runs (the owner's own mode). Pure.
 */
public final class Help {
    private Help() {}

    public static final int FAR = 48, COME_FROM = 16, GIVE_MIN = 8, GIVE_N = 32, TORCHES_MIN = 4, LIGHT_R = 8, MOBS_MAX = 3;
    public static final double CAP = 74;

    /** What to do: {@code goal} (a "goal ..." text the planner resolves first, else null) then {@code chain}. */
    public record Pick(String what, String goal, String chain, double score) {
        /** The text for aa why / the log ("give 30: give me oak_planks 32"). */
        public String line() { return what + " " + Math.round(score) + ": " + (goal == null ? "" : goal + " then ") + chain; }
    }

    /** Why there is no pick (aa status), or null when {@link #pick} has one. */
    public static String off(Inputs in, double weight) {
        if (weight <= 0) return "help 0 in the personality";
        if (!in.ownerOnline) return "owner offline";
        if (in.assistOn) return "assist runs";
        if (in.ownerDist < 0) return "owner not seen";
        if (in.ownerDist > FAR) return "owner " + in.ownerDist + " away (over " + FAR + ")";
        return null;
    }

    public static Pick pick(Inputs in, double weight) {
        if (off(in, weight) != null) return null;
        if (in.ownerHostiles > 0)
            return new Pick("fight", null, "come then attack nearest", Math.min(CAP, 2 * weight + 10 * Math.min(MOBS_MAX, in.ownerHostiles)));
        boolean busy = in.ownerAct != null && !in.ownerAct.equals("idle");
        double f = busy ? 1 : 0.5;
        if ("building".equals(in.ownerAct) && in.ownerPlaced != null) {
            String b = path(in.ownerPlaced);
            int have = in.bag.getOrDefault(b, 0);
            Pick p = have >= GIVE_MIN ? new Pick("give", null, "give me " + b + " " + Math.min(GIVE_N, have), cap(1.5 * weight * f))
                    : new Pick("fetch", "goal " + b + " " + GIVE_N, "give me " + b + " " + GIVE_N, cap(weight * f));
            if (p.score() > 0) return p;
        }
        if (in.ownerDark && busy && in.torches >= TORCHES_MIN) {
            Pick p = new Pick("light", null, "come then light here " + LIGHT_R, cap(1.25 * weight));
            if (p.score() > 0) return p;
        }
        if (busy && in.ownerDist > COME_FROM) {
            Pick p = new Pick("follow", null, "come", cap(weight));
            if (p.score() > 0) return p;
        }
        return null;
    }

    /** Under 1: not wanted (-1); else at most {@link #CAP}. */
    static double cap(double v) { return v < 1 ? -1 : Math.min(CAP, v); }

    /** "minecraft:oak_planks" -> "oak_planks" (the bag's keys). */
    static String path(String id) { return id.substring(id.indexOf(':') + 1); }
}
