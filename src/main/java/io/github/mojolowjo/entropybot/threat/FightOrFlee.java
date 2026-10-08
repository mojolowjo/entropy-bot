package io.github.mojolowjo.entropybot.threat;

import java.util.List;
import java.util.Map;

/**
 * B2: the fight-or-flee assessment over the mobs that count (docs/COMBAT_PLAN.md 4-5, a first cut of its ThreatModel
 * and Verdict with vanilla numbers): expected health lost killing them one by one, against the health above the
 * retreat line. "Only safer, never braver": it can turn a fight into a retreat, never the other way round. Pure Java.
 *
 * <ul>
 * <li>FIGHT: margin at least the buffer (max(4, 20% of max health)), or tight but in the light.</li>
 * <li>LIT: tight (margin 0..buffer) and dark here, with a lit spot within reach: walk there (no new spawns), fight there.</li>
 * <li>HOME: losing (margin below 0), a creeper in a group, or tight in the dark with no lit spot: the retreat home.</li>
 * </ul>
 */
public final class FightOrFlee {
    private FightOrFlee() {}

    /** 0.24.1: SHELTER = fliers/ranged at good health: to the nearest lit spot, the job kept; HOME never sends /home by itself (Reflexes: health under 6 and server commands on). */
    public enum Verdict { FIGHT, LIT, SHELTER, HOME, HOLD }

    public record Foe(String kind, double dist, boolean creeper) {}

    /** health, maxHealth, armor points, weaponHit (one swing), light at the bot (0..15), litDist (-1 = none known). */
    public record Me(double health, double maxHealth, int armor, double weaponHit, int light, int litDist, double retreatAt) {}

    public record Result(Verdict verdict, double margin, double loss, String why) {}

    /** Vanilla Normal: {raw damage per hit, health}. Unknown: 5 / 20 with a 2.5x high band. */
    static final Map<String, double[]> MOBS = Map.ofEntries(
            Map.entry("zombie", new double[]{3, 20}), Map.entry("husk", new double[]{3, 20}), Map.entry("drowned", new double[]{3, 20}),
            Map.entry("zombie_villager", new double[]{3, 20}), Map.entry("skeleton", new double[]{3, 20}), Map.entry("stray", new double[]{3, 20}),
            Map.entry("bogged", new double[]{3, 16}), Map.entry("spider", new double[]{2, 16}), Map.entry("cave_spider", new double[]{3, 12}),
            Map.entry("enderman", new double[]{7, 40}), Map.entry("witch", new double[]{6, 26}), Map.entry("pillager", new double[]{4, 24}),
            Map.entry("vindicator", new double[]{13, 24}), Map.entry("silverfish", new double[]{1, 8}), Map.entry("slime", new double[]{4, 16}),
            Map.entry("magma_cube", new double[]{6, 16}), Map.entry("phantom", new double[]{2, 20}), Map.entry("piglin_brute", new double[]{13, 50}),
            Map.entry("wither_skeleton", new double[]{8, 20}), Map.entry("blaze", new double[]{6, 20}), Map.entry("creeper", new double[]{0, 20}));

    public static final int LIT = 8;
    public static final int LIT_MAX_DIST = 24;
    static final double SWING_S = 0.65;        // a sword's cooldown (12.5 ticks)
    static final double HIT_EVERY_S = 1.0;     // a melee mob's hit interval

    static String basisText(java.util.Map<String, String> b) {
        java.util.List<String> l = new java.util.ArrayList<>();
        b.forEach((k, v) -> l.add(k + " " + v));
        return String.join(", ", l);
    }

    /**
     * 0.24.4 dead-end rule: a retreat (HOME, LIT, SHELTER) into a dead end becomes HOLD: stand at the corridor mouth (a
     * one-wide cell, one mob at a time) and fight. FIGHT and a missing check stay as they are.
     */
    public static Result holdIfDeadEnd(Result r, DeadEnd.Check d) {
        if (r == null || d == null || !d.deadEnd() || r.verdict() == Verdict.FIGHT || r.verdict() == Verdict.HOLD) return r;
        String at = d.mouth() == null ? "here" : d.mouth()[0] + " " + d.mouth()[1] + " " + d.mouth()[2];
        return new Result(Verdict.HOLD, r.margin(), r.loss(), "hold the corridor mouth at " + at + ": a dead end behind me (" + d.cells()
                + " free cells within " + DeadEnd.RANGE + ", under " + DeadEnd.MIN_CELLS + "), instead of: " + r.why());
    }

    /** Damage after armour (vanilla, no toughness). */
    static double taken(double d, int armor) {
        double a = Math.min(20, Math.max(armor / 5.0, armor - d / 2.0));
        return d * (1 - a / 25.0);
    }

    /** 0.23.2: the blast a melee creeper duel should expect (a hit-and-back-off that fails at about 3 blocks). */
    public static final double CREEPER_BLAST = 12;

    /** 0.23.2: a melee duel with a lone creeper only with a big margin: health above the retreat line after that blast (armour counted) is at least the buffer. */
    public static boolean creeperDuelOk(Me me) {
        double margin = me.health() - me.retreatAt() - taken(CREEPER_BLAST, me.armor());
        return margin >= Math.max(4, 0.2 * me.maxHealth());
    }

    /** 0.23.2: sprint whenever a retreat moves (vanilla needs food above 6). */
    public static boolean sprint(boolean moving, int food) { return moving && food > 6; }

    public static Result assess(Me me, List<Foe> foes) { return assess(me, foes, MobDamage.INSTANCE); }

    /** 0.24.4: with the measured damage per hit (after armour) where a kind has 3+ samples, else the hand table. */
    public static Result assess(Me me, List<Foe> foes, MobDamage seen) {
        if (foes.isEmpty()) return new Result(Verdict.FIGHT, Double.NaN, 0, "nothing counts");
        if (me.health() < me.retreatAt())
            return new Result(Verdict.HOME, Double.NaN, Double.NaN, "retreat home: health " + ThreatRules.fmt(Math.round(me.health())) + " below " + ThreatRules.fmt(me.retreatAt()));
        // 0.24.1: fliers and ranged mobs can't be outrun on foot: at good health never home, fight them when in reach or shelter
        boolean noWalker = true;
        for (Foe f : foes) {
            ThreatRules.Move mv = ThreatRules.moveOf(f.kind());
            if (mv != ThreatRules.Move.FLY && mv != ThreatRules.Move.RANGED) noWalker = false;
        }
        boolean creeper = false;
        double[] rate = new double[foes.size()], kill = new double[foes.size()];
        double band = 1.25;
        java.util.LinkedHashMap<String, String> basis = new java.util.LinkedHashMap<>();
        for (int i = 0; i < foes.size(); i++) {
            Foe f = foes.get(i);
            creeper |= f.creeper();
            double[] m = MOBS.get(ThreatRules.path(f.kind()));
            double obs = seen == null ? Double.NaN : seen.observed(f.kind());
            if (!Double.isNaN(obs)) {
                if (m == null) m = new double[]{obs, 20};
                rate[i] = obs / HIT_EVERY_S;
                double[] st = seen.get(f.kind());
                basis.put(ThreatRules.path(f.kind()), "observed " + ThreatRules.fmt(Math.round(obs * 10) / 10.0) + "/hit (n=" + (long) st[1] + ")");
            } else {
                if (m == null) { m = new double[]{5, 20}; band = 2.5; }
                rate[i] = taken(m[0], me.armor()) / HIT_EVERY_S;
                basis.put(ThreatRules.path(f.kind()), "assumed " + ThreatRules.fmt(Math.round(taken(m[0], me.armor()) * 10) / 10.0) + "/hit");
            }
            kill[i] = Math.ceil(m[1] / Math.max(1, me.weaponHit())) * (me.weaponHit() <= 1 ? 0.25 : SWING_S);
        }
        if (creeper && foes.size() > 1) return new Result(Verdict.HOME, Double.NaN, Double.NaN, "a creeper in a group of " + foes.size());
        // kill the quickest first; while several are alive the i-frames cap the rate at twice the strongest hit
        Integer[] order = new Integer[foes.size()];
        for (int i = 0; i < order.length; i++) order[i] = i;
        java.util.Arrays.sort(order, (a, b) -> Double.compare(kill[a], kill[b]));
        double loss = 0;
        for (int k = 0; k < order.length; k++) {
            double sum = 0, max = 0;
            for (int j = k; j < order.length; j++) { sum += rate[order[j]]; max = Math.max(max, rate[order[j]]); }
            loss += kill[order[k]] * Math.min(sum, 2 * max);
        }
        double high = loss * band;
        double margin = me.health() - me.retreatAt() - high;
        double buffer = Math.max(4, 0.2 * me.maxHealth());
        String num = "margin " + ThreatRules.fmt(Math.round(margin * 10) / 10.0) + " (health " + ThreatRules.fmt(Math.round(me.health()))
                + ", expect to lose up to " + ThreatRules.fmt(Math.round(high * 10) / 10.0) + " to " + foes.size() + " mob" + (foes.size() == 1 ? "" : "s") + "; " + basisText(basis) + ")";
        if (margin >= buffer) return new Result(Verdict.FIGHT, margin, high, "fight: " + num);
        if (noWalker) {
            // already lit here or at the spot: stay and hit them when they dive (a shelter walk of 0 blocks looped live)
            if (me.light() < LIT && me.litDist() > 2 && me.litDist() <= LIT_MAX_DIST)
                return new Result(Verdict.SHELTER, margin, high, "shelter: fliers/ranged, a lit spot " + me.litDist() + " blocks away: " + num);
            return new Result(Verdict.FIGHT, margin, high, (me.light() >= LIT || (me.litDist() >= 0 && me.litDist() <= 2) ? "fight (fliers/ranged, lit here: staying put): " : "fight: fliers/ranged can't be outrun, hitting them when in reach: ") + num);
        }
        if (margin < 0) return new Result(Verdict.HOME, margin, high, "retreat home: losing, " + num);
        if (me.light() >= LIT) return new Result(Verdict.FIGHT, margin, high, "fight (tight, but lit here): " + num);
        if (me.litDist() >= 0 && me.litDist() <= LIT_MAX_DIST)
            return new Result(Verdict.LIT, margin, high, "retreat to a lit spot " + me.litDist() + " blocks away (tight, dark here): " + num);
        return new Result(Verdict.HOME, margin, high, "retreat home: tight, dark, no lit spot near: " + num);
    }
}
