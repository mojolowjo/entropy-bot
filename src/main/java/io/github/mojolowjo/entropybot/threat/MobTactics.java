package io.github.mojolowjo.entropybot.threat;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 0.27.0 the owner's melee footwork per mob kind (the heavy-log replay of 2026-10-08): he backs off in 20-25 % of hits
 * vs zombies and spiders, 62-75 % vs jumping/larva mobs, and stands under fliers (dY 2). Pure (JUnit); loader notes: none.
 */
public final class MobTactics {
    private MobTactics() {}

    public static final double WALK_RATE = 0.25, JUMP_RATE = 0.70;
    /** Back off when the mob is this close (blocks). */
    public static final double BACKOFF_DIST = 2.0;
    public static final int BACKOFF_CELLS = 2;
    /** A flier within reach + this: hold and hit. */
    public static final double FLIER_HOLD_EXTRA = 2.0;

    static final Set<String> JUMPERS = Set.of("slime", "magma_cube", "silverfish", "endermite", "frog");
    static final String[] JUMP_WORDS = {"larva", "jumper", "hopper", "slime"};
    /** The owner's measured exceptions (kind -> rate); empty today: the two class rates cover the replay. */
    static final Map<String, Double> MEASURED = Map.of("zombie", 0.25, "spider", 0.25);

    public enum Action { NONE, BACK_OFF, HOLD }

    public record Decision(Action action, String line) {}

    static String path(String kind) {
        if (kind == null) return "";
        String k = kind.toLowerCase(Locale.ROOT);
        int c = k.indexOf(':');
        return c >= 0 ? k.substring(c + 1) : k;
    }

    public static boolean jumper(String kind) {
        String p = path(kind);
        if (JUMPERS.contains(p)) return true;
        for (String w : JUMP_WORDS) if (p.contains(w)) return true;
        return false;
    }

    /** The back-off rate (0..1) for this kind. */
    public static double rate(String kind) {
        String p = path(kind);
        Double m = MEASURED.get(p);
        if (m != null) return m;
        return jumper(p) ? JUMP_RATE : WALK_RATE;
    }

    /** Hit number n (1-based) of a run is one the rate picks: floor(n * rate) went up (25 % = every 4th hit). */
    public static boolean rateSays(double rate, int n) {
        if (n <= 0 || rate <= 0) return false;
        if (rate >= 1) return true;
        return Math.floor(n * rate + 1e-9) > Math.floor((n - 1) * rate + 1e-9);
    }

    /**
     * After the bot's own hit number n on this mob: back off {@link #BACKOFF_CELLS} when it is within
     * {@link #BACKOFF_DIST} and (health at or below backoffHealth, or the kind's rate picks this hit); a flier: hold.
     */
    public static Decision afterHit(String kind, ThreatRules.Move move, double dist, double health, int backoffHealth, int n) {
        String p = path(kind);
        if (move == ThreatRules.Move.FLY) return new Decision(Action.HOLD, "hold (" + p + " flies, at " + r1(dist) + ")");
        if (move == ThreatRules.Move.RANGED || dist > BACKOFF_DIST) return new Decision(Action.NONE, "");
        boolean low = health <= backoffHealth;
        double rate = rate(p);
        if (!low && !rateSays(rate, n)) return new Decision(Action.NONE, "");
        return new Decision(Action.BACK_OFF, "back off " + BACKOFF_CELLS + " (" + p + " at " + r1(dist) + ", health " + Math.round(health)
                + (low ? "" : ", hit " + n + " at " + Math.round(rate * 100) + " %") + ")");
    }

    /** A flier in this range: hold (stand, face, hit) instead of chasing it with Baritone. */
    public static boolean holdForFlier(ThreatRules.Move move, double dist, double reach) {
        return move == ThreatRules.Move.FLY && dist <= reach + FLIER_HOLD_EXTRA;
    }

    static double r1(double d) { return Math.round(d * 10) / 10.0; }
}
