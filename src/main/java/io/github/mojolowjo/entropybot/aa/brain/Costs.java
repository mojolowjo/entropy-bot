package io.github.mojolowjo.entropybot.aa.brain;

import io.github.mojolowjo.entropybot.aa.core.Blackboard;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * aa-costs (owner's design rule 2026-10-19: AA reaches the best decision on its own): the hard-wired rules turned into
 * costs and values the scorer weighs. Each function is pure and returns (or notes) its terms, so {@code aa why} shows
 * how the number came about ({@link #note}, {@link #lines}). The danger reflexes and the safety floors (lava, player
 * builds, block entities) are not here and stay as they are. JUnit: CostsTest. Loader notes: none (plain numbers).
 */
public final class Costs {
    private Costs() {}

    // ---- the terms aa why shows ----

    public static final String TERMS_FACT = "aa.costTerms";

    /** Records the latest terms of one cost ("tools", "shelter", "coal", "sprint") for aa why. */
    @SuppressWarnings("unchecked")
    public static void note(Blackboard bb, String key, String terms) {
        if (bb == null) return;
        Map<String, String> m;
        if (bb.fact(TERMS_FACT) instanceof Map<?, ?> x) m = (Map<String, String>) x;
        else {
            m = new LinkedHashMap<>();
            bb.fact(TERMS_FACT, m);
        }
        m.put(key, terms);
    }

    /** "costs <key>: <terms>" per noted cost. */
    @SuppressWarnings("unchecked")
    public static java.util.List<String> lines(Blackboard bb) {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (bb != null && bb.fact(TERMS_FACT) instanceof Map<?, ?> m)
            for (var e : ((Map<String, String>) m).entrySet()) out.add("costs " + e.getKey() + ": " + e.getValue());
        return out;
    }

    static String f1(double v) { return String.format(Locale.ROOT, "%.1f", v); }

    // ---- 2: food urgency and the hunt outside the areas ----

    /** 0.05..1: how badly food is needed (the bar from 20 down to 6, or health from 20 down to 10). */
    public static double foodUrgency(int food, float health) {
        double u = Math.max((20 - food) / 14.0, (20 - health) / 10.0);
        return Math.max(0.05, Math.min(1, u));
    }

    /** The out-hunt's travel (there and back, walk-blocks) divided by the urgency: far and not hungry is dear. */
    public static double huntOutTravel(double dist, int food, float health) {
        return 2 * Math.max(0, dist) / foodUrgency(food, health);
    }

    public static String huntOutTerms(double dist, int food, float health) {
        return "travel 2x" + Math.round(dist) + " / urgency " + f1(foodUrgency(food, health)) + " = " + Math.round(huntOutTravel(dist, food, health));
    }

    // ---- 3: coal for torches vs for the coal goal ----

    /** The torch desire's curve at {@code torches} (30 + 30 x shortfall^2 of 16; its floor 30 from 16 on). */
    public static double torchScore(int torches) {
        return 30 + Curves.upkeep(30, Math.min(16, Math.max(0, torches)), 16);
    }

    /** One coal makes 4 torches: what the torch score gains by them. */
    public static double torchGain(int torches) { return torchScore(torches) - torchScore(torches + 4); }

    /** One coal burnt sets the coal goal back by 1 of its n: the goal's score / n (0 without a goal). */
    public static double coalLoss(double goalScore, int goalN) { return goalScore <= 0 || goalN <= 0 ? 0 : goalScore / goalN; }

    /** Torches may burn coal: no coal goal, or the torches' gain per coal over the goal's loss per coal. */
    public static boolean torchCoal(int coal, int torches, double goalScore, int goalN) {
        return coalLoss(goalScore, goalN) <= 0 || torchGain(torches) > coalLoss(goalScore, goalN);
    }

    public static String coalTerms(int coal, int torches, double goalScore, int goalN) {
        return "torches " + torches + ": +" + f1(torchGain(torches)) + " per coal vs coal goal " + f1(coalLoss(goalScore, goalN))
                + " per coal (" + Math.round(goalScore) + "/" + goalN + ") -> " + (torchCoal(coal, torches, goalScore, goalN) ? "torches" : "keep coal");
    }

    // ---- 4: sprinting vs hunger ----

    /** Minecraft: sprint 0.1 exhaustion per metre, 4 exhaustion = 1 food point; walk 4.317 m/s, sprint 5.612 m/s. */
    public static final double SPRINT_POINTS_PER_M = 0.1 / 4, SAVED_S_PER_M = 1 / 4.317 - 1 / 5.612;
    /** Seconds of work one food point is worth when food is fully scarce (no source, an empty bag). */
    public static final double POINT_S = Tuning.defOf("pointS");
    /** With a food source known, scarcity counts this much. */
    public static final double SOURCE_FACTOR = Tuning.defOf("sourceFactor");

    public record Sprint(boolean conserve, double savedS, double costS, double scarcity, String terms) {}

    /**
     * Sprint per metre saves {@link #SAVED_S_PER_M} and burns {@link #SPRINT_POINTS_PER_M} x the measured drain scale;
     * a point is worth {@link #POINT_S} x scarcity seconds (scarcity: the bag's shortfall of the target, x
     * {@link #SOURCE_FACTOR} with a source). Conserve when the burn is worth more than the time.
     * @param drainScale measured drain / hand drain of long walks (1 before anything is measured)
     */
    public static Sprint sprint(boolean drains, boolean noSource, double bagEnergy, double target, double drainScale) {
        double scarcity = target <= 0 ? 0 : Math.max(0, Math.min(1, 1 - bagEnergy / target)) * (noSource ? 1 : Tuning.get("sourceFactor"));
        double cost = drains ? SPRINT_POINTS_PER_M * Math.max(0.25, drainScale) * Tuning.get("pointS") * scarcity : 0;
        boolean conserve = drains && cost > SAVED_S_PER_M;
        String terms = String.format(Locale.ROOT, "sprint saves %.3f s/m, costs %.3f s/m (scarcity %.2f%s, drain x%.2f) -> %s",
                SAVED_S_PER_M, cost, scarcity, noSource ? " no source" : "", drainScale, conserve ? "walk" : "sprint");
        return new Sprint(conserve, SAVED_S_PER_M, cost, scarcity, terms);
    }

    // ---- 5: tools ----

    /**
     * The tool upkeep's value: no pickaxe 55 + 30 x the share of the next desires it blocks; wooden 55 + 20 x that share;
     * stone or better 40 + the wear curve. No cap: the anti-flicker is {@link ToolSmoother}.
     * @param blocked 0..1, how much of what comes next needs the better pickaxe
     */
    public static double tools(int pickTier, int pickPct, double blocked) {
        double b = Math.max(0, Math.min(1, blocked));
        if (pickTier == 0) return 55 + 30 * b;
        if (pickTier < 2) return 55 + 20 * b;
        return 40 + Curves.upkeep(30, pickPct, 15);
    }

    /**
     * aa-night2 P0 2 (normal run: 11 zombie deaths "fighting zombie" with a pickaxe): once a zombie was seen, no weapon
     * with hit 4+ is a tools need of its own worth {@link #SWORD}, added to the pickaxe's.
     */
    public static final double SWORD = Tuning.defOf("swordValue");

    public static boolean swordWanted(boolean zombieSeen, boolean armed) { return zombieSeen && !armed; }

    public static double sword(boolean zombieSeen, boolean armed) { return swordWanted(zombieSeen, armed) ? Tuning.get("swordValue") : 0; }

    /** The share of the next steps a missing (tier 0) or wooden (tier 1) pickaxe blocks: stone/iron/furnace/coal. */
    public static double blocked(int pickTier, boolean furnace, int coal, int torches, boolean ownerOreGoal) {
        int n = 0, of = 4;
        if (!furnace) n++;                              // cobble for the furnace
        if (pickTier < 2) n++;                          // iron needs a stone pickaxe
        if (coal == 0 && torches < 16) n++;             // coal for torches
        if (ownerOreGoal) n++;
        if (pickTier == 1) n = Math.max(0, n - (furnace ? 0 : 1));      // a wooden pickaxe mines cobble already
        return (double) n / of;
    }

    public static String toolsTerms(int pickTier, int pickPct, double blocked) {
        return "pickaxe tier " + pickTier + (pickTier >= 2 ? " at " + pickPct + "%" : ", blocks " + Math.round(blocked * 100) + "% of what is next")
                + " -> " + Math.round(tools(pickTier, pickPct, blocked));
    }

    /**
     * Anti-flicker for the pickaxe count: a drop counts only once it held {@link #HOLD_MS} (a craft moves the tool
     * through the grid); a rise counts at once.
     */
    public static final class ToolSmoother {
        public static final long HOLD_MS = 4_000;
        private int shown = -1, low = -1;
        private long lowSince;

        public int update(int raw, long now) {
            if (shown < 0 || raw >= shown) { shown = raw; low = -1; return shown; }
            if (low != raw) { low = raw; lowSince = now; }
            if (now - lowSince >= HOLD_MS) { shown = raw; low = -1; }
            return shown;
        }
    }

    // ---- 6: the night ----

    /** Danger weights per difficulty (peaceful, easy, normal, hard). */
    static final double[] DIFF = {0, 0.6, 1.0, 1.3};
    /** The full-night shelter score at danger 1. */
    public static final double SHELTER_AT_1 = Tuning.defOf("shelterAt1"), SHELTER_MAX = Tuning.defOf("shelterMax");

    /**
     * The danger of staying out at night: difficulty x armour (full iron about halves it) x missing health x light
     * (lit: mobs spawn less) x hostiles near x no bed (no way to skip the night).
     */
    /** Block light this high keeps a point lit (spawning is far rarer there). */
    public static final int LIT_AT = 8;

    /** The share of sampled points with block light {@link #LIT_AT}+ (0 when none sampled). */
    public static double litShare(int[] lights) {
        if (lights == null || lights.length == 0) return 0;
        int n = 0;
        for (int l : lights) if (l >= LIT_AT) n++;
        return n / (double) lights.length;
    }

    /** AA_ROUND4 B2: each torch within 16 cuts the danger by this share, down to {@link #LIGHT_FLOOR} (Tuning torchCut, lightFloor). */
    public static final double TORCH_CUT = Tuning.defOf("torchCut"), LIGHT_FLOOR = Tuning.defOf("lightFloor");

    /** AA_ROUND4 B2: the light term from the torches within 16 of the place (sightings or my own placements). */
    public static double lightF(int torches) {
        return Math.max(Tuning.get("lightFloor"), 1 - Tuning.get("torchCut") * Math.max(0, torches));
    }

    /**
     * AA_ROUND4 B2 (danger terms from the area, not the feet): difficulty x armour (full iron about halves it) x missing
     * health x the torches within 16 x hostiles near. The bed term went (a bed changes what the shelter does, not the danger).
     */
    public static double nightDanger(int difficulty, int armor, float health, float maxHealth, int torches, int hostiles) {
        double d = DIFF[Math.max(0, Math.min(3, difficulty))];
        double armorF = 1 - 0.6 * Math.min(20, Math.max(0, armor)) / 20.0;
        double healthF = 1 + 0.5 * (1 - Math.max(0, health) / Math.max(1, maxHealth));
        double hostF = 1 + 0.15 * Math.min(6, Math.max(0, hostiles));
        return d * armorF * healthF * lightF(torches) * hostF;
    }

    public static double shelterScore(double danger) { return Math.min(Tuning.get("shelterMax"), Tuning.get("shelterAt1") * danger); }

    public static String nightTerms(int difficulty, int armor, float health, int torches, int hostiles, double danger) {
        return "difficulty " + difficulty + ", armour " + armor + ", health " + Math.round(health) + ", torches " + torches + " (light x" + f1(lightF(torches))
                + "), hostiles " + hostiles + ": danger " + f1(danger) + " -> " + Math.round(shelterScore(danger));
    }
}
