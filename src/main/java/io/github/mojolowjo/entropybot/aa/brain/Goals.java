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
        if (!g.r.ore()) {
            LootBook.Plan lp = loot(bb, g, left);
            return lp != null && lp.command() != null ? lp.command() : "goal " + g.r.key() + " " + left;
        }
        String w = g.r.key().replaceFirst("^.*:", "");
        return known(bb, g.r.key()) > 0 ? "mine " + w + " " + left + " dig" : "mine strip " + w + " " + left;
    }

    /**
     * Loot data: an item no recipe makes, with a drop source the bot can work ("mine gravel 30", "hunt 3 cow for
     * leather 3"), else the reason; null when the book has nothing (or a recipe makes it: the planner goes first).
     */
    public static LootBook.Plan loot(Blackboard bb, Goal g, int left) {
        if (g.r.ore() || !(bb.fact(LootBook.FACT) instanceof LootBook book) || book.isEmpty()) return null;
        String id = g.r.ids().isEmpty() ? g.r.key() : g.r.ids().get(0);
        if (bb.fact(TechTree.FACT) instanceof TechTree t && t.makes(id)) return null;
        LootBook.Ctx ctx = bb.fact(LootBook.CTX_FACT) instanceof LootBook.Ctx c ? c : LootBook.Ctx.NONE;
        LootBook.Plan p = book.plan(id, Math.max(1, left), g.n, ctx, fightOk(bb, ctx));     // review: hunt stops at the bag total
        return p.command() == null && p.text() == null ? null : p;
    }

    /** The brain's fight rule for a hunt of a hostile mob: armed, health 14+, no danger or creeper near. */
    public static boolean fightOk(Blackboard bb, LootBook.Ctx ctx) {
        return ctx.armed() && bb.in.health >= 14 && bb.in.danger == 0 && !bb.in.creeperClose && !bb.in.hurtRecently;
    }

    /** Known positions of the family (the adapter refreshes {@link #KNOWN_FACT}). */
    public static int known(Blackboard bb, String key) {
        return bb.fact(KNOWN_FACT) instanceof Map<?, ?> m && m.get(key) instanceof Integer i ? i : 0;
    }

    private static String lootNote(Blackboard bb, Goal g) {
        LootBook.Plan lp = loot(bb, g, Math.max(1, g.n - have(bb.in.bag, g)));
        return lp == null || lp.text() == null ? "" : " (" + lp.text() + ")";
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
                + lootNote(bb, g)
                + " [" + g.r.text() + (known(bb, g.r.key()) > 0 ? "; " + known(bb, g.r.key()) + " known near" : "") + "]";
    }
}
