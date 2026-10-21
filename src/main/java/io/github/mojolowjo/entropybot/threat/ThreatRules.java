package io.github.mojolowjo.entropybot.threat;

import io.github.mojolowjo.entropybot.aa.brain.Tuning;

import java.util.Locale;
import java.util.Set;

/**
 * B2 threat test, the rules (docs/BRAIN_PLAN.md 5.1; the owner's rule in docs/BRAIN_LOOP.md): a mob counts only when
 * (a) it is aggroed on the bot, (b) it can reach the bot and (c) it is closing in. Creepers: within 16 and any path.
 * Safety (never weaker than before B2): a mob that hit the bot in the retaliation window always counts, and so does a
 * creeper within 6. Pure Java.
 */
public final class ThreatRules {
    private ThreatRules() {}

    /** How a kind gets to the bot. */
    public enum Move { WALK, SPIDER, FLY, RANGED, TELEPORT }

    public static double facingDeg() { return Tuning.get("threatRules.facingDeg"); }
    public static double creeperBar() { return Tuning.get("threatRules.creeperBar"); }
    public static double creeperAlways() { return Tuning.get("threatRules.creeperAlways"); }
    public static double rangedRange() { return Tuning.get("threatRules.rangedRange"); }
    public static double teleportRange() { return Tuning.get("threatRules.teleportRange"); }
    public static double close() { return Tuning.get("threatRules.close"); }
    /** 0.23.2, the close rule: any hostile closer than this (3D; strictly: a zombie on the roof of a 3-high room stands exactly 4 above) or one that hit the bot in the last 5 s counts at once. */
    public static double closeAny() { return Tuning.get("threatRules.closeAny"); }
    /** Path distance must shrink by this much a sample (1 s) to count as closing in. */
    public static double closingStep() { return Tuning.get("threatRules.closingStep"); }
    /** Mobs further than this are not sampled at all (the reflexes look at most 24). */
    public static double sampleRadius() { return Tuning.get("threatRules.sampleRadius"); }

    /** "minecraft:zombie" -> "zombie"; "nyfsspiders:big_spider" -> "big_spider". */
    public static String path(String id) {
        if (id == null) return "";
        int c = id.indexOf(':');
        return (c >= 0 ? id.substring(c + 1) : id).toLowerCase(Locale.ROOT);
    }

    public static Move moveOf(String id) {
        // from the game's entity class and attributes (ItemWorth.mob); a kind the game data does not know walks
        var m = io.github.mojolowjo.entropybot.aa.brain.ItemWorth.mob(id);
        return m == null ? Move.WALK : Move.valueOf(m.move().name());
    }

    public static boolean creeper(String id) { return path(id).endsWith("creeper"); }

    /** Kinds whose synced aggressive flag vanilla AI never sets say "aggro" only as "it can see the bot" (from the game's entity class). */
    public static boolean aggroFlagReliable(String id) {
        var m = io.github.mojolowjo.entropybot.aa.brain.ItemWorth.mob(id);
        return m == null || m.flagged();
    }

    /** Path budget: a walk longer than this is "no reach" (owner: max(24, 1.6 x straight)). */
    public static double pathBudget(double straight) { return Math.max(24, Tuning.get("threatRules.pathBudgetK") * straight); }

    /**
     * One sample of one mob. pathDist: moves from the grid (-1 = no path, -2 = outside the grid / no grid: straight
     * distance is used); prevReach: the reach one sample ago (NaN = none / first sample). yawOff: how far its head
     * turns away from the bot, degrees (0 = straight at it).
     */
    public record MobSample(int id, String kind, double straight, boolean aggressive, double yawOff, boolean seen,
                            boolean hitMe, int pathDist, double prevReach) {}

    /** reach: blocks (NaN = none); how: path|sight|flies|teleports|beyond grid|none. */
    public record Decision(int id, String kind, boolean aggro, double reach, String how, double straight, double closing,
                           boolean isClosing, boolean counts, String rule) {
        /** "zombie: aggro y, path 7 (straight 5.2), closing -1.0/s -> counts (all three)". */
        public String line() {
            String r = Double.isNaN(reach) ? "no path" : how + " " + fmt(reach);
            String c = Double.isNaN(closing) ? "closing ?" : "distance " + (closing > 0 ? "+" : "") + fmt(closing) + "/s";
            return path(kind) + ": aggro " + (aggro ? "y" : "n") + ", " + r + " (straight " + fmt(straight) + "), " + c
                    + " -> " + (counts ? "counts" : "noted") + " (" + rule + ")";
        }
    }

    static String fmt(double d) { return d == Math.rint(d) ? String.valueOf((long) d) : String.valueOf(Math.round(d * 10) / 10.0); }  // structural: rounded to one decimal for the text

    public static boolean aggro(MobSample s) {
        if (s.hitMe()) return true;
        if (!s.seen()) return false;
        if (!aggroFlagReliable(s.kind())) return s.straight() <= sampleRadius();
        return s.aggressive() && Math.abs(s.yawOff()) <= facingDeg();
    }

    /** {reach, how}: reach NaN = none. */
    static Object[] reach(MobSample s) {
        double st = s.straight();
        double path = s.pathDist() >= 0 ? s.pathDist() : s.pathDist() == -2 ? st : Double.NaN;
        String how = s.pathDist() >= 0 ? "path" : s.pathDist() == -2 ? "beyond grid" : "none";
        if (!Double.isNaN(path) && path > pathBudget(st)) { path = Double.NaN; how = "too long"; }
        switch (moveOf(s.kind())) {
            case FLY -> { return new Object[]{st, "flies"}; }
            case TELEPORT -> { if (st <= teleportRange()) return new Object[]{st, "teleports"}; }
            case RANGED -> {
                if (s.seen() && st <= rangedRange() && (Double.isNaN(path) || st < path)) return new Object[]{st, "sight"};
            }
            default -> {}
        }
        return new Object[]{path, how};
    }

    public static Decision decide(MobSample s) {
        Object[] r = reach(s);
        double reach = (double) r[0];
        String how = (String) r[1];
        boolean aggro = aggro(s);
        double closing = Double.isNaN(reach) || Double.isNaN(s.prevReach()) ? Double.NaN : reach - s.prevReach();
        boolean isClosing = !Double.isNaN(reach) && (reach <= close() || s.straight() <= close()
                || (!Double.isNaN(closing) && closing <= -closingStep())
                || ("sight".equals(how) && aggro));                  // a ranged mob in range shoots, it needn't come
        boolean counts;
        String rule;
        if (s.hitMe() || s.straight() < closeAny()) { counts = true; rule = "close"; }
        else if (creeper(s.kind()) && s.straight() <= creeperAlways()) { counts = true; rule = "creeper within " + fmt(creeperAlways()); }
        else if (creeper(s.kind())) {
            counts = s.straight() <= creeperBar() && !Double.isNaN(reach);
            rule = counts ? "creeper bar" : s.straight() > creeperBar() ? "creeper beyond 16" : "creeper, no path";
        } else {
            counts = aggro && !Double.isNaN(reach) && isClosing;
            rule = counts ? "all three" : !aggro ? "not aggro" : Double.isNaN(reach) ? "can't reach me" : "not closing in";
        }
        return new Decision(s.id(), s.kind(), aggro, reach, how, s.straight(), closing, isClosing, counts, rule);
    }

    /**
     * The filter in front of the fight code: whether a mob the old test counts still counts. decision null = not
     * sampled yet (a new mob): counts only within {@link #CLOSE}. gridOk false (no fresh search): the old test stands.
     */
    public static boolean filter(Decision d, boolean gridOk, double straight, boolean hitMe, boolean creeper) {
        if (hitMe || !gridOk || straight < closeAny()) return true;
        if (creeper && straight <= creeperAlways()) return true;
        if (d == null) return false;                         // not sampled yet and beyond the close rule
        return d.counts();
    }
}
