package io.github.mojolowjo.entropybot.threat;

import java.util.List;
import java.util.Map;

/**
 * AA_CLEANUP 4: the threat model as facts for AA's danger desire (it was FightOrFlee's hand verdicts). What is near
 * (each foe's kind, distance, how it moves, its damage per second after the bot's armour from {@link MobDamage} when
 * measured, else the vanilla table), the bot (health, armour, weapon, food) and which ways out exist here (a flee
 * heading, the base, a cover cell, a dig-down or wall niche, a pillar). No decisions: {@code aa.DangerOptions} prices
 * the ways. Creepers are kept out (the creeper flee is a reflex). Pure; loader notes: none (the game side fills it,
 * {@code engine.DangerMoves#facts}).
 */
public record ThreatFacts(Me me, List<Foe> foes, Ways ways) {

    /** health incl. absorption, max health, armour points, weapon hit (Hostility.weaponHit), food bar. */
    public record Me(double health, double maxHealth, int armor, double weaponHit, int food) {}

    /**
     * One foe. dps: damage per second it deals when it can hit (after armour); hp its health; speed blocks/s; reach the
     * distance it hits from (melee 2, ranged {@link #RANGED_RANGE}).
     */
    public record Foe(String kind, double dist, ThreatRules.Move move, double dps, double hp, double speed, double reach, String basis) {
        public boolean ranged() { return move == ThreatRules.Move.RANGED; }

        public boolean flies() { return move == ThreatRules.Move.FLY; }

        /** Seconds until it can hit the bot from where it is (0 = now). */
        public double reachT() { return Math.max(0, dist - reach) / Math.max(0.1, speed); }
    }

    /**
     * The ways out here. fleeRun: the run length of the least-threat heading (0 = cornered); baseDist: walk to the
     * base (-1 none in reach); coverDist: moves to a cell out of the nearest ranged mob's sight (-1 none); digIn: a
     * dig-down or a wall niche fits here; pillarBlocks/headRoom: throwaway blocks and free cells over the head.
     */
    public record Ways(int fleeRun, double baseDist, int coverDist, boolean digIn, int pillarBlocks, int headRoom, boolean onGround) {
        public static final Ways NONE = new Ways(0, -1, -1, false, 0, 0, true);
    }

    public static final ThreatFacts CALM = new ThreatFacts(new Me(20, 20, 0, 1, 20), List.of(), Ways.NONE);

    public boolean calm() { return foes == null || foes.isEmpty(); }

    // ---- the mob model (from FightOrFlee, AA_CLEANUP 4) ----

    /** Vanilla Normal: {raw damage per hit, health}. Unknown: 5 / 20. */
    static final Map<String, double[]> MOBS = Map.ofEntries(
            Map.entry("zombie", new double[]{3, 20}), Map.entry("husk", new double[]{3, 20}), Map.entry("drowned", new double[]{3, 20}),
            Map.entry("zombie_villager", new double[]{3, 20}), Map.entry("skeleton", new double[]{3, 20}), Map.entry("stray", new double[]{3, 20}),
            Map.entry("bogged", new double[]{3, 16}), Map.entry("spider", new double[]{2, 16}), Map.entry("cave_spider", new double[]{3, 12}),
            Map.entry("enderman", new double[]{7, 40}), Map.entry("witch", new double[]{6, 26}), Map.entry("pillager", new double[]{4, 24}),
            Map.entry("vindicator", new double[]{13, 24}), Map.entry("silverfish", new double[]{1, 8}), Map.entry("slime", new double[]{4, 16}),
            Map.entry("magma_cube", new double[]{6, 16}), Map.entry("phantom", new double[]{2, 20}), Map.entry("piglin_brute", new double[]{13, 50}),
            Map.entry("wither_skeleton", new double[]{8, 20}), Map.entry("blaze", new double[]{6, 20}));

    /** Light this high is lit (spawning stops); the lit-spot search reaches this far (ThreatRuntime). */
    public static final int LIT = 8, LIT_MAX_DIST = 24;
    /** A weapon hit at least this (a wooden sword) counts as armed. */
    public static final double ARMED = 4;
    /** A melee mob hits once a second; a ranged one every 2 s from up to RANGED_RANGE. */
    public static final double HIT_EVERY_S = 1.0, SHOT_EVERY_S = 2.0, RANGED_RANGE = 16, MELEE_REACH = 2;

    /** Damage after armour (vanilla, no toughness). */
    public static double taken(double d, int armor) {
        double a = Math.min(20, Math.max(armor / 5.0, armor - d / 2.0));
        return d * (1 - a / 25.0);
    }

    /** Blocks per second (vanilla-ish): walkers 2.3, spiders 3, fliers 4, the rest 2.5. */
    public static double speedOf(ThreatRules.Move m) {
        return switch (m) {
            case SPIDER -> 3.0;
            case FLY -> 4.0;
            case RANGED -> 2.3;
            default -> 2.3;
        };
    }

    /** One foe from its kind: measured damage per hit (3+ samples) or the table, after armour. Pure. */
    public static Foe foe(String kind, double dist, int armor, MobDamage seen) {
        String k = ThreatRules.path(kind);
        ThreatRules.Move mv = ThreatRules.moveOf(kind);
        double[] m = MOBS.get(k);
        double obs = seen == null ? Double.NaN : seen.observed(kind);
        double hit;
        String basis;
        if (!Double.isNaN(obs)) {
            hit = obs;
            basis = "observed " + ThreatRules.fmt(Math.round(obs * 10) / 10.0) + "/hit";
        } else {
            hit = taken(m == null ? 5 : m[0], armor);
            basis = (m == null ? "unknown kind, assumed " : "table ") + ThreatRules.fmt(Math.round(hit * 10) / 10.0) + "/hit";
        }
        boolean ranged = mv == ThreatRules.Move.RANGED;
        double dps = hit / (ranged ? SHOT_EVERY_S : HIT_EVERY_S);
        return new Foe(k, dist, mv, dps, m == null ? 20 : m[1], speedOf(mv), ranged ? RANGED_RANGE : MELEE_REACH, basis);
    }

    /** 0.23.2: sprint whenever a run moves (vanilla needs food above 6). */
    public static boolean sprint(boolean moving, int food) { return moving && food > 6; }

    /** The bot's walking pace (a Baritone path), blocks per second on the flat. */
    public static final double WALK = 4.3;

    /** The bot's run speed: sprint 5.6 with food above 6, else walk 4.3. */
    public static double runSpeed(int food) { return food > 6 ? 5.6 : WALK; }

    /** "zombie 3.0 (table 3/hit), skeleton 12.0 (...)" for aa why. */
    public String foesText() {
        if (calm()) return "none";
        StringBuilder b = new StringBuilder();
        for (Foe f : foes) {
            if (b.length() > 0) b.append(", ");
            b.append(f.kind()).append(' ').append(ThreatRules.fmt(Math.round(f.dist() * 10) / 10.0)).append(" (").append(f.basis()).append(')');
        }
        return b.toString();
    }
}
