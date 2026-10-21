package io.github.mojolowjo.entropybot.aa.brain;

import io.github.mojolowjo.entropybot.aa.core.Inputs;
import io.github.mojolowjo.entropybot.aa.moves.Step;

import java.util.List;

/**
 * BRAIN_PLAN 2.11 (B6): helping the owner. One desire, weighted by the personality's {@code help}, rising when the owner
 * is near and busy (the companion mod's activity report). What it does, first that fits:
 * <ol>
 * <li>fight: monsters near the owner: come, then attack the nearest; 2 x weight + 10 per monster (3 at most).</li>
 * <li>give: the owner builds and the bag holds 8+ of the block they place: give them n; 1.5 x weight.</li>
 * <li>fetch: they build and the bag lacks it: the planner's {@code <block> 32} ({@link Step.Plan}), then give; 1 x weight.</li>
 * <li>light: the owner's spot is dark, the bot has 4+ torches: come, then light here 8; 1.25 x weight.</li>
 * <li>follow at a distance: they are busy more than 16 away: come; 1 x weight.</li>
 * </ol>
 * An idle owner (or no report) halves every part but the fight. Never 75 or more: helping waits for the running task
 * (owner goals, {@code aa want}, stay the direct way). Off when the owner is offline, farther than 48, or {@code assist}
 * runs (the owner's own mode). Pure.
 */
public final class Help {
    private Help() {}

    public static int far() { return Tuning.i("help.far"); }
    public static int comeFrom() { return Tuning.i("help.comeFrom"); }
    public static int giveMin() { return Tuning.i("help.giveMin"); }
    public static int giveN() { return Tuning.i("help.giveN"); }
    public static int torchesMin() { return Tuning.i("help.torchesMin"); }
    public static int lightR() { return Tuning.i("help.lightR"); }
    public static int mobsMax() { return Tuning.i("help.mobsMax"); }
    public static double capV() { return Tuning.get("help.cap"); }

    /** What to do: the typed steps (a planner {@link Step.Plan} first when the bag lacks the block). */
    public record Pick(String what, List<Step> steps, double score) {
        /** The text for aa why / the log ("give 30: give oak_planks 32"). */
        public String line() { return what + " " + Math.round(score) + ": " + Step.label(steps); }
    }

    /** Why there is no pick (aa status), or null when {@link #pick} has one. */
    public static String off(Inputs in, double weight) {
        if (weight <= 0) return "help 0 in the personality";
        if (!in.ownerOnline) return "owner offline";
        if (in.assistOn) return "assist runs";
        if (in.ownerDist < 0) return "owner not seen";
        if (in.ownerDist > far()) return "owner " + in.ownerDist + " away (over " + far() + ")";
        return null;
    }

    public static Pick pick(Inputs in, double weight) {
        if (off(in, weight) != null) return null;
        if (in.ownerHostiles > 0)
            return new Pick("fight", List.of(new Step.Come(), new Step.Attack()), Math.min(capV(), 2 * weight + 10 * Math.min(mobsMax(), in.ownerHostiles)));
        boolean busy = in.ownerAct != null && !in.ownerAct.equals("idle");
        double f = busy ? 1 : 0.5;
        if ("building".equals(in.ownerAct) && in.ownerPlaced != null) {
            String b = path(in.ownerPlaced);
            int have = in.bag.getOrDefault(b, 0);
            Pick p = have >= giveMin() ? new Pick("give", List.of(new Step.Give(b, Math.min(giveN(), have))), cap(1.5 * weight * f))
                    : new Pick("fetch", List.of(new Step.Plan(b + " " + giveN()), new Step.Give(b, giveN())), cap(weight * f));
            if (p.score() > 0) return p;
        }
        if (in.ownerDark && busy && in.torches >= torchesMin()) {
            Pick p = new Pick("light", List.of(new Step.Come(), new Step.Light(lightR())), cap(1.25 * weight));
            if (p.score() > 0) return p;
        }
        if (busy && in.ownerDist > comeFrom()) {
            Pick p = new Pick("follow", List.of(new Step.Come()), cap(weight));
            if (p.score() > 0) return p;
        }
        return null;
    }

    /** Under 1: not wanted (-1); else at most {@link #CAP}. */
    static double cap(double v) { return v < 1 ? -1 : Math.min(capV(), v); }

    /** "minecraft:oak_planks" -> "oak_planks" (the bag's keys). */
    static String path(String id) { return id.substring(id.indexOf(':') + 1); }
}
