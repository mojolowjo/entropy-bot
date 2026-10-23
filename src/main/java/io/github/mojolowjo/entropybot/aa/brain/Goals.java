package io.github.mojolowjo.entropybot.aa.brain;

import io.github.mojolowjo.entropybot.aa.core.Blackboard;
import io.github.mojolowjo.entropybot.aa.moves.Step;

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

    public static final String FACT = "aa.ownerGoals";
    public static int high() { return Tuning.i("goals.high"); }
    public static int oreDefault() { return Tuning.i("goals.oreDefault"); }
    /** A fresh goal may interrupt the running task (once) within this time. */
    public static long freshMs() { return Tuning.l("goals.freshMs"); }

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

        /** orders-tier (0.39.z9): why the order stopped (said once; aa want again or aa drop clears it), null: it goes on. */
        public volatile String parked;

        /** The owner's strip order (aa want strip ...): its one step; null for an item goal. */
        public Step.Strip strip;
    }

    /** The strip orders whose run ended done (their goals are met; a failed run parks its goal instead). */
    private static final java.util.Set<Step.Strip> STRIPS_DONE = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** StepHands: the strip step ended done. */
    public static void stripDone(Step.Strip s) { STRIPS_DONE.add(s); }

    /** A strip order as a goal: key "strip", n 1, done when its run ended done. */
    public static Goal addStrip(Blackboard bb, Step.Strip s, int prio, long now) {
        Thing.Result r = new Thing.Result("strip", "strip", false, List.of(), List.of(), 0, null, s.text(), null);
        Goal g = new Goal(r, 1, prio, now);
        g.strip = s;
        of(bb).removeIf(x -> x.r.key().equals("strip"));
        of(bb).add(g);
        return g;
    }

    /**
     * orders-tier (0.39.z9): the order cannot progress: parked with its reason, said ONCE in the log and the whisper; the
     * brain never starts it again (it is infeasible while parked). {@code aa want} again makes a fresh goal; {@code aa drop}
     * removes it. False when it was already parked.
     */
    public static boolean park(Blackboard bb, Goal g, String why) {
        if (g.parked != null) return false;
        g.parked = why == null || why.isBlank() ? "no reason given" : why;
        String line = "order " + g.r.key() + " stopped: " + g.parked + " (aa want " + g.r.word().trim() + " to try again, aa drop " + g.r.key() + " to clear it)";
        bb.port.log(line);
        bb.port.tellOwner(line);
        return true;
    }

    /** orders-tier: a failure that says nothing about the order itself (the slot busy, no facts yet): no park. */
    public static boolean passing(String why) {
        if (why == null) return false;
        String w = why.toLowerCase(Locale.ROOT);
        return w.startsWith("busy") || w.equals("nothing to run");
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
        if (n < 0 || n > Tuning.i("goals.maxN")) return new Args(null, 0, 0, "error: n must be 1..4096");
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
        Goal g = new Goal(r, n > 0 ? n : r.ore() ? oreDefault() : 1, prio, now);
        of(bb).removeIf(x -> x.r.key().equals(r.key()));
        of(bb).add(g);
        return g;
    }

    /** How many the bag holds now (bag: path -> count). */
    public static int have(Map<String, Integer> bag, Goal g) {
        if (g.strip != null) return STRIPS_DONE.contains(g.strip) ? g.n : 0;
        if (g.strip != null) return STRIPS_DONE.contains(g.strip) ? g.n : 0;
        if (g.r.group()) {          // a group is done when every member is had: the fewest of them
            int low = Integer.MAX_VALUE;
            for (String c : g.r.counts()) low = Math.min(low, bag.getOrDefault(c, 0));
            return low == Integer.MAX_VALUE ? 0 : low;
        }
        int s = 0;
        for (String c : g.r.counts()) s += bag.getOrDefault(c, 0);
        return s;
    }

    /** The goal's own score: ownerBase +/- HIGH; -1 once done. */
    public static double score(Blackboard bb, Goal g) {
        if (have(bb.in.bag, g) >= g.n) return -1;
        return Math.max(1, Personality.of(bb).get("ownerBase") + g.prio * high());
    }

    /** Why it cannot go on now (another dimension, a diamond pickaxe), else null: parked, said in aa goals (Q6). */
    public static String blocked(Blackboard bb, Goal g) {
        if (g.parked != null) return g.parked;
        Thing.Where w = g.r.where();
        if (w != null && w.dim() != null && !w.dim().equals(bb.in.dimension))
            return g.r.key() + " is only in " + Thing.dimName(w.dim()) + "; I don't travel there yet";
        // loot-book-bg: while the book is read in the background, an order whose chain waits on it is held (not parked) with
        // "waiting for the loot book (N of M)"; once the book is published the chain is priced again on the next pick
        if (bb.fact(LootBook.FACT) instanceof LootBook book && !book.ready()) {
            var ch = chain(bb, g);
            if (ch != null && ch.waiting()) return ch.missing();
        }
        return null;
    }

    /** one-planner: no prerequisite desire: the chain makes the pickaxe a dig asks for (the lowest tier that does it). */
    public static String prereq(Blackboard bb, Goal g) { return null; }

    /** What the chains start from (the adapter's {@link NeedChain#WORLD_FACT}), null: not readable yet. */
    public static io.github.mojolowjo.entropybot.aa.NeedChain.World world(Blackboard bb) {
        return bb.fact(io.github.mojolowjo.entropybot.aa.NeedChain.WORLD_FACT) instanceof java.util.function.Supplier<?> s
                && s.get() instanceof io.github.mojolowjo.entropybot.aa.NeedChain.World w ? w : null;
    }

    /**
     * one-planner: every owner order goes through the chain. A group (ruling b) is one job ({@code NeedChain.resolveAll}: one
     * table, one trip, a simulated bag); an item is "I need n of it" ({@code NeedChain.resolve}: have it, craft it, dig a
     * known source with the pickaxe its block asks for, hunt it, else explore, at its worldgen height first). Null: no world
     * yet, done, or no chain at all ({@link #chain} says why).
     */
    public static List<Step> steps(Blackboard bb, Goal g) {
        if (g.n - have(bb.in.bag, g) <= 0) return null;
        if (g.strip != null) return List.of(g.strip);          // the strip order is its own one step (no chain)
        var ch = chain(bb, g);
        return ch == null || !ch.ok() || ch.steps().isEmpty() ? null : ch.steps();
    }

    /** The goal's chain on the world fact now (null: no world facts yet). */
    public static io.github.mojolowjo.entropybot.aa.NeedChain.Chain chain(Blackboard bb, Goal g) {
        if (g.strip != null) return null;
        var world = world(bb);
        if (world == null) return null;
        if (g.r.group()) return io.github.mojolowjo.entropybot.aa.NeedChain.resolveAll(g.r.key(), g.r.ids(), g.n, world);
        return io.github.mojolowjo.entropybot.aa.NeedChain.resolve(g.r.ids().isEmpty() ? g.r.key() : g.r.ids().get(0), g.n, world);
    }

    /**
     * aa-costs (was: 4 coal kept back while a coal goal is open): the open coal goal's score and size, {score, n}
     * ({0, 0} none); torches weigh their gain per coal against score / n (Costs.torchCoal).
     */
    public static double[] coalGoal(Blackboard bb) {
        if (!(bb.fact(FACT) instanceof List<?>)) return new double[]{0, 0};
        for (Goal g : of(bb)) {
            boolean coal = false;
            for (String id : g.r.ids()) coal |= ItemWorth.hasTag(id, "minecraft:coals");       // the game's coals tag, not a name
            if (coal && have(bb.in.bag, g) < g.n) return new double[]{score(bb, g), g.n};
        }
        return new double[]{0, 0};
    }

    /**
     * aa-water (1f): the ore goal's pick by the iron scorer's prices (the water ways weigh against a floating dig and
     * the other ores): the cheapest per-ore row; {x, y, z, dig 1 | walk 0} and, for a water way, its steps. Null none.
     */
    public record Pick(int[] at, List<Step> waterSteps) {}

    public static Pick pickPriced(String ore, List<io.github.mojolowjo.entropybot.aa.IronOptions.Ore> ores, int meY, boolean inWater, int throwaway, boolean placeAllowed, int digMax) {
        var f = new io.github.mojolowjo.entropybot.aa.IronOptions.Facts(ores, meY, inWater, throwaway, placeAllowed, -1, false, null, -1, false, false, 0);
        var c = io.github.mojolowjo.entropybot.aa.IronOptions.choose(f, new io.github.mojolowjo.entropybot.aa.IronOptions.Memory(), 0);
        for (var s : c.ranked()) {
            var o = s.target();
            if (o == null || s.way() == io.github.mojolowjo.entropybot.aa.IronOptions.Way.PILLAR_KNOWN) continue;
            if (s.way() == io.github.mojolowjo.entropybot.aa.IronOptions.Way.WATER_STAND || s.way() == io.github.mojolowjo.entropybot.aa.IronOptions.Way.WATER_PLACE
                    || s.way() == io.github.mojolowjo.entropybot.aa.IronOptions.Way.DRY_SIDE) {
                var one = new io.github.mojolowjo.entropybot.aa.IronOptions.Choice(s.way(), o, c.smelt(), c.ranked(), c.dropped(), s.stand(), s.place());
                return new Pick(new int[]{o.x(), o.y(), o.z(), 1}, one.steps(ore, 1));
            }
            // the cheapest walk/dig row (the floating dig priced in: "another ore" wins over a wet one)
            if (s.way() == io.github.mojolowjo.entropybot.aa.IronOptions.Way.WALK_KNOWN) return new Pick(new int[]{o.x(), o.y(), o.z(), o.loaded() ? 0 : 1}, null);
            if (s.way() == io.github.mojolowjo.entropybot.aa.IronOptions.Way.DIG_KNOWN && o.depth() <= digMax) return new Pick(new int[]{o.x(), o.y(), o.z(), 1}, null);
        }
        int[] at = pickOre(ores, digMax);
        return at == null ? null : new Pick(at, null);
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


    /** The goals reached now (removed from the list); the adapter drops their desires and tells the owner. */
    public static List<Goal> done(Blackboard bb) {
        List<Goal> out = new ArrayList<>();
        // jobs (owner 2026-10-22): "the goal check waits until the current job is done, then checks/cancels"
        if (JobBoundary.mid()) return out;
        for (Goal g : of(bb)) if (have(bb.in.bag, g) >= g.n) out.add(g);
        of(bb).removeAll(out);
        return out;
    }

    /** One aa goals line: "iron 2/8 high: score 90 - mine iron 6 dig | ...". */
    /** The strip orders' saved progress (set by the game side, StripStep; the default says it is not wired). */
    public static volatile java.util.function.Function<Step.Strip, StripOrder.Saved> stripProgress = s -> {
        throw new IllegalStateException("strip progress lookup not wired");
    };

    public static String line(Blackboard bb, Goal g, String scoreText) {
        if (g.strip != null) {
            StripOrder.Saved p;
            try {
                p = stripProgress.apply(g.strip);
            } catch (RuntimeException e) {
                return "strip: " + scoreText + " - progress unreadable: " + e.getMessage() + " [" + g.strip.text() + "]";
            }
            return StripOrder.goalLine(g.strip.heading(), g.strip.y(), g.strip.amounts(), p) + ": " + scoreText
                    + (g.parked != null ? " - parked: " + g.parked : "") + " [" + g.strip.text() + "]";
        }
        String b = blocked(bb, g);
        var ch = b == null ? chain(bb, g) : null;
        return g.r.key() + " " + have(bb.in.bag, g) + "/" + g.n + (g.prio > 0 ? " high" : g.prio < 0 ? " low" : "") + ": " + scoreText
                + (b != null ? (g.parked == null ? " - held: " : " - parked: ") + b :ch != null ? " - chain: " + ch.why() : " - no facts for the chain yet")
                + " [" + g.r.text() + "]";
    }
}
