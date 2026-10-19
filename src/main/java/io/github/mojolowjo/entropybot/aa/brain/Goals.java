package io.github.mojolowjo.entropybot.aa.brain;

import io.github.mojolowjo.entropybot.aa.core.Blackboard;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * BRAIN_PLAN 2.5 (B4): the owner's goals ({@code aa want/drop/goals}). Kept on the blackboard ({@link #FACT}); each is
 * one OWNER desire (built in {@code AaDesires.ownerGoal}). Pure: words, scores, counts, the blocked reason, done.
 *
 * <p>Score (Q1): the personality's ownerBase (70), high +20, low -20; below urgent upkeep by the brain's rank rule.
 * Done when the bag holds {@code n} (ore: the ore blocks plus their drops). Memory only (gone with a restart).
 */
public final class Goals {
    private Goals() {}

    public static final String FACT = "aa.ownerGoals", KNOWN_FACT = "aa.oreKnown";
    public static final int HIGH = 20, ORE_DEFAULT = 8;
    /** A fresh goal may interrupt the running task (once) within this time. */
    public static final long FRESH_MS = 5 * 60_000;

    public static final class Goal {
        public final Thing.Result r;
        public final int n, prio;
        public final long given;

        public Goal(Thing.Result r, int n, int prio, long given) {
            this.r = r;
            this.n = n;
            this.prio = prio;
            this.given = given;
        }

        public String id() { return "goal." + r.key(); }
    }

    /** {@code aa want} words: the thing (several words joined), n (0 = default), priority -1/0/+1. */
    public record Args(String word, int n, int prio, String err) {}

    public static Args parse(String arg) {
        List<String> words = new ArrayList<>();
        int n = 0, prio = 0;
        for (String t : (arg == null ? "" : arg.trim().toLowerCase(Locale.ROOT)).split("\\s+")) {
            if (t.isEmpty()) continue;
            if (t.equals("high")) prio = 1;
            else if (t.equals("low")) prio = -1;
            else if (t.matches("\\d{1,5}")) n = Math.max(-1, Integer.parseInt(t) == 0 ? -1 : Integer.parseInt(t));
            else words.add(t);
        }
        if (words.isEmpty()) return new Args(null, 0, 0, "usage: aa want <thing> [n] [high|low] (e.g. aa want iron_ore 8)");
        if (n < 0 || n > 4096) return new Args(null, 0, 0, "error: n must be 1..4096");
        return new Args(String.join("_", words), n, prio, null);
    }

    @SuppressWarnings("unchecked")
    public static List<Goal> of(Blackboard bb) {
        if (bb.fact(FACT) instanceof List<?> l) return (List<Goal>) l;
        List<Goal> l = new ArrayList<>();
        bb.fact(FACT, l);
        return l;
    }

    public static Goal find(Blackboard bb, String key) {
        for (Goal g : of(bb)) if (g.r.key().equals(key)) return g;
        return null;
    }

    /** Adds (or replaces) a goal; the default n is 8 for an ore, 1 for an item. */
    public static Goal add(Blackboard bb, Thing.Result r, int n, int prio, long now) {
        Goal g = new Goal(r, n > 0 ? n : r.ore() ? ORE_DEFAULT : 1, prio, now);
        of(bb).removeIf(x -> x.r.key().equals(r.key()));
        of(bb).add(g);
        return g;
    }

    /** How many the bag holds now (bag: path -> count). */
    public static int have(Map<String, Integer> bag, Goal g) {
        int s = 0;
        for (String c : g.r.counts()) s += bag.getOrDefault(c, 0);
        return s;
    }

    /** The goal's own score: ownerBase +/- HIGH; -1 once done. */
    public static double score(Blackboard bb, Goal g) {
        if (have(bb.in.bag, g) >= g.n) return -1;
        return Math.max(1, Personality.of(bb).get("ownerBase") + g.prio * HIGH);
    }

    /** Why it cannot go on now (another dimension, a diamond pickaxe), else null: parked, said in aa goals (Q6). */
    public static String blocked(Blackboard bb, Goal g) {
        Thing.Where w = g.r.where();
        if (w != null && w.dim() != null && !w.dim().equals(bb.in.dimension))
            return g.r.key() + " is only in " + Thing.dimName(w.dim()) + "; I don't travel there yet";
        if (g.r.tier() >= 4 && bb.in.pickTier < 4) return "needs a diamond pickaxe (aa want diamond_pickaxe first)";
        return null;
    }

    /** The desire that supplies the missing pickaxe (value flows to it, 2.2), else null. */
    public static String prereq(Blackboard bb, Goal g) {
        if (g.r.tier() >= 3 && bb.in.pickTier < 3) return "needs.iron";
        if (g.r.tier() >= 1 && bb.in.pickTier < Math.max(1, g.r.tier())) return "needs.tools";
        return null;
    }

    /** The command for what is left, or null (done, or the pickaxe missing: the prereq works first). */
    public static String chain(Blackboard bb, Goal g) {
        int left = g.n - have(bb.in.bag, g);
        if (left <= 0 || bb.in.pickTier < g.r.tier()) return null;
        if (!g.r.ore()) return "goal " + g.r.key() + " " + left;
        String w = g.r.key().replaceFirst("^.*:", "");
        // round 2 item 5: the nearest known ore by name (that cell and its vein); the goal picks again for the next one
        int[] at = nearest(bb, g.r.key());
        if (at != null) return "mine " + w + " 1 at " + at[0] + " " + at[1] + " " + at[2] + (at.length > 3 && at[3] == 0 ? " walk" : " dig");
        if (known(bb, g.r.key()) > 0) return "mine " + w + " " + left + " dig";
        // B5: no known ore and no idea at what height it lives (not learned yet): look around first, the chunk pass learns
        if (g.r.where() != null && !g.r.where().yKnown()) return "explore 5";
        // overnight 2026-10-19 (coal goal: "mine strip" with no mine marked failed twice): the iron-style ways in order
        if (bb.fact(WAYS_FACT) instanceof Ways ws) {
            if (ws.caveDist() >= 0) return "mine cave " + w + " " + left;
            if (ws.mineMarked() && ws.inAreas()) return "mine strip " + w + " " + left;
            if (ws.inAreas()) return "place mine north then mine strip " + w + " " + left;
            return "explore 5";
        }
        return "mine strip " + w + " " + left;
    }

    /** The adapter's ore-goal facts (set with {@link #KNOWN_FACT}): a known cave within reach (-1 none), a mine marked, in my areas. */
    public static final String WAYS_FACT = "aa.oreWays";

    public record Ways(int caveDist, boolean mineMarked, boolean inAreas) {}

    /**
     * aa-costs (was: 4 coal kept back while a coal goal is open): the open coal goal's score and size, {score, n}
     * ({0, 0} none); torches weigh their gain per coal against score / n (Costs.torchCoal).
     */
    public static double[] coalGoal(Blackboard bb) {
        if (!(bb.fact(FACT) instanceof List<?>)) return new double[]{0, 0};
        for (Goal g : of(bb)) if (g.r.key().replaceFirst("^.*:", "").matches("(deepslate_)?coal(_ore)?") && have(bb.in.bag, g) < g.n) return new double[]{score(bb, g), g.n};
        return new double[]{0, 0};
    }

    /** Round 2 item 5: goal key -> the nearest known ore AA can take, {x, y, z, dig 1 | walk 0} (the adapter refreshes it). */
    public static final String NEAREST_FACT = "aa.oreNearest";

    public static int[] nearest(Blackboard bb, String key) {
        return bb.fact(NEAREST_FACT) instanceof Map<?, ?> m && m.get(key) instanceof int[] a && a.length >= 3 ? a : null;
    }

    /**
     * The first ore of the scorer's facts (nearest first) that may be taken: not denied, not a fatal wet dig, an open
     * face (walk) or loaded and at most {@code digMax} deep (dig); null none. Pure.
     */
    public static int[] pickOre(List<io.github.mojolowjo.entropybot.aa.IronOptions.Ore> ores, int digMax) {
        for (var o : ores) {
            if (o.deny() != null || o.floor()) continue;
            if (o.openFace()) return new int[]{o.x(), o.y(), o.z(), o.loaded() ? 0 : 1};
            if (o.loaded() && !o.wetDigDown() && o.depth() <= digMax) return new int[]{o.x(), o.y(), o.z(), 1};
        }
        return null;
    }

    /** Known positions of the family (the adapter refreshes {@link #KNOWN_FACT}). */
    public static int known(Blackboard bb, String key) {
        return bb.fact(KNOWN_FACT) instanceof Map<?, ?> m && m.get(key) instanceof Integer i ? i : 0;
    }

    /** The goals reached now (removed from the list); the adapter drops their desires and tells the owner. */
    public static List<Goal> done(Blackboard bb) {
        List<Goal> out = new ArrayList<>();
        for (Goal g : of(bb)) if (have(bb.in.bag, g) >= g.n) out.add(g);
        of(bb).removeAll(out);
        return out;
    }

    /** One aa goals line: "iron 2/8 high: score 90 - mine iron 6 dig | ...". */
    public static String line(Blackboard bb, Goal g, String scoreText) {
        String b = blocked(bb, g);
        String c = b == null ? chain(bb, g) : null;
        String p = b == null && c == null ? prereq(bb, g) : null;
        return g.r.key() + " " + have(bb.in.bag, g) + "/" + g.n + (g.prio > 0 ? " high" : g.prio < 0 ? " low" : "") + ": " + scoreText
                + (b != null ? " - parked: " + b : c != null ? " - next: " + c : p != null ? " - first " + p : "")
                + " [" + g.r.text() + (known(bb, g.r.key()) > 0 ? "; " + known(bb, g.r.key()) + " known near" : "") + "]";
    }
}
