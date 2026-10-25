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

        /** 0.39.z30: re-plans on a changed fact so far (aa want again sets it back to 0). */
        public int replans;

        /** 0.39.z62 item 1: re-plans on a source new to this order (replan.maxSourceDrops; aa want again sets it back to 0). */
        public int sourceReplans;

        /**
         * 0.39.z48 (S66): the running pick's price when it was taken (seconds, NaN: none), when (ms) and whether it ends in an
         * explore; a mid-job re-price switches only when cheaper than what is left of it by replan.switchMarginS.
         */
        public double pricedS = Double.NaN;
        public long pricedAtMs;
        public boolean pricedExplore;
        /** 0.39.z49 (S67): the running pick is a real priced way; false: none taken, or the explore while paths compute (no price). */
        public boolean pricedWay;

        /** 0.39.z33 (S36): the sources this order dropped (unreachable, gone), charged by their source key; held for the order's life. */
        public final Failures sources = new Failures();
    }

    /** 0.39.z33: a dropped source is held for the order's life (a new aa want makes a new goal). */
    static final long ORDER_LIFE_MS = Long.MAX_VALUE / 4; // structural: no lapse within an order

    /** The order's dropped sources, key -> reason (the chain prices without them and lists them). */
    public static Map<String, String> droppedSources(Goal g) {
        Map<String, String> out = new java.util.LinkedHashMap<>();
        for (Failures.Rec r : g.sources.of(g.id())) if (r.option() != null) out.put(r.option(), r.reason());
        return out;
    }

    /** 0.39.z30: at most this many re-plans on a changed fact per order (Tuning replan.maxPerOrder). */
    public static int replanMax() { return Tuning.i("replan.maxPerOrder"); }

    /** 0.39.z62 item 1: at most this many re-plans on a NEW dropped source per order (Tuning replan.maxSourceDrops). */
    public static int replanMaxSources() { return Tuning.i("replan.maxSourceDrops"); }

    /**
     * 0.39.z30 (owner 2026-10-23: a job whose fact changed re-plans and continues): the order's chain priced again on the
     * facts of now - the same call the order's job makes ({@link #steps}) - after {@code failed} found {@code fact} changed.
     * One line to the owner says it; the cap is said when reached; no way now: null steps with the chain's why (the run ends
     * with the step's own reason). Nothing here knows which fact or item it is.
     */
    public static io.github.mojolowjo.entropybot.aa.moves.Steps.Routine replan(Blackboard bb, Goal g, Step failed,
                                                                             io.github.mojolowjo.entropybot.aa.moves.FactChange.Fact fact, String why) {
        if (fact.price()) return reprice(bb, g, fact);
        // 0.39.z33 (S36): a source fact charges the failed step's source by its key: the chain drops it for this order.
        // 0.39.z62 item 1b: a fact may name its own source key (a strip heading blocked at its Y, any ore set)
        String own = io.github.mojolowjo.entropybot.aa.moves.FactChange.ownSourceKey(fact);
        String src = own != null ? own : io.github.mojolowjo.entropybot.aa.NeedChain.sourceKey(failed);
        boolean sourceFact = src != null && fact.key().startsWith("source ");
        // 0.39.z62 item 1 (run S 04:57:35: "not re-planned: 3 re-plans already" - the pickaxe broke, then the same blocked
        // heading three times as three ore sets): a source fact that drops a source NEW to this order takes one way out of a
        // finite set, so it counts against replan.maxSourceDrops, not replan.maxPerOrder; every other fact (a bag, a station,
        // a placement) and a source dropped again count against replan.maxPerOrder as before
        boolean newSource = sourceFact && !droppedSources(g).containsKey(src);
        String cap = newSource ? (g.sourceReplans >= replanMaxSources() ? "replan.maxSourceDrops " + replanMaxSources() : null)
                : (g.replans >= replanMax() ? "replan.maxPerOrder " + replanMax() : null);
        if (cap != null) {
            int n = newSource ? g.sourceReplans : g.replans;
            String line = g.r.key() + ": " + fact.text() + " - not re-planned: " + n + (newSource ? " sources dropped" : " re-plans") + " already (" + cap + ")";
            bb.port.log(line);
            bb.port.tellOwner(line);
            return new io.github.mojolowjo.entropybot.aa.moves.Steps.Routine(null, (newSource ? "dropped " + n + " sources" : "re-planned " + n + " times") + " already (" + cap + ")");
        }
        if (g.n - have(bb.in.bag, g) <= 0) return new io.github.mojolowjo.entropybot.aa.moves.Steps.Routine(null, "the order is met");
        if (sourceFact) g.sources.failedFor(g.id(), src, fact.text(), bb.clock.now(), null, ORDER_LIFE_MS);
        var ch = chain(bb, g);
        if (ch == null) return new io.github.mojolowjo.entropybot.aa.moves.Steps.Routine(null, "no facts for the chain yet (the senses are not ready)");
        if (!ch.ok() || ch.steps().isEmpty()) return new io.github.mojolowjo.entropybot.aa.moves.Steps.Routine(null, ch.why());
        if (newSource) g.sourceReplans++;
        else g.replans++;
        took(g, ch, bb.clock.now());
        String line = g.r.key() + ": " + fact.text() + " - re-planned: " + ch.steps().get(0).text() + " first";
        bb.port.log(line + " (after step " + failed.text() + ": " + why + "; chain: " + ch.why() + ")");
        bb.port.tellOwner(line);
        return new io.github.mojolowjo.entropybot.aa.moves.Steps.Routine(ch.steps(), null, ch.uses());
    }

    /**
     * 0.39.z41: a walk's complete path landed while the order runs: its chain priced again on the facts of now (the same call
     * as {@link #replan}); not a failure, not counted against replan.maxPerOrder. The steps from here (the caller keeps the
     * old ones when they are the same, or when no way stands now: null steps with why), logged with the chain.
     */
    static io.github.mojolowjo.entropybot.aa.moves.Steps.Routine reprice(Blackboard bb, Goal g, io.github.mojolowjo.entropybot.aa.moves.FactChange.Fact fact) {
        if (g.n - have(bb.in.bag, g) <= 0) return new io.github.mojolowjo.entropybot.aa.moves.Steps.Routine(null, "the order is met");
        var ch = chain(bb, g);
        if (ch == null) return new io.github.mojolowjo.entropybot.aa.moves.Steps.Routine(null, "no facts for the chain yet (the senses are not ready)");
        if (!ch.ok() || ch.steps().isEmpty()) return new io.github.mojolowjo.entropybot.aa.moves.Steps.Routine(null, ch.why());
        // 0.39.z48 (S66, runs I/J: 12-24 "re-priced ... the pick changed" per run): the new way replaces the running pick
        // only when cheaper than what is left of it by replan.switchMarginS; else the running steps go on
        long now = bb.clock.now();
        double left = leftS(g, now);
        boolean newWay = !io.github.mojolowjo.entropybot.aa.moves.Steps.waitsOnPaths(ch.steps());
        if (!switches(g.pricedWay, left, g.pricedExplore, newWay, ch.seconds(), ch.explore(), switchMarginS())) {
            String kept = "kept the running pick: the new way " + Math.round(ch.seconds()) + " s"
                    + (ch.explore() ? " (ends in an explore)" : "") + " vs " + (Double.isNaN(left) ? "?" : Math.round(left)) + " s left"
                    + (g.pricedExplore ? " (ends in an explore)" : "") + " (replan.switchMarginS " + Math.round(switchMarginS()) + ")";
            bb.port.log(g.r.key() + ": " + fact.text() + " - " + kept);
            return new io.github.mojolowjo.entropybot.aa.moves.Steps.Routine(null, kept);
        }
        took(g, ch, now);
        bb.port.log(g.r.key() + ": " + fact.text() + " - priced again (chain: " + ch.why() + ")");
        return new io.github.mojolowjo.entropybot.aa.moves.Steps.Routine(ch.steps(), null, ch.uses());
    }

    /** 0.39.z48: the seconds a re-price must save to switch the running pick (Tuning replan.switchMarginS). */
    public static double switchMarginS() { return Tuning.get("replan.switchMarginS"); }

    /** 0.39.z48: the order's pick was taken now at the chain's price. */
    static void took(Goal g, io.github.mojolowjo.entropybot.aa.NeedChain.Chain ch, long nowMs) {
        // 0.39.z49 (S67, run K: "vs 104 s left" for the explore while paths compute): that explore is not a way - no price
        g.pricedWay = !io.github.mojolowjo.entropybot.aa.moves.Steps.waitsOnPaths(ch.steps());
        g.pricedS = g.pricedWay ? ch.seconds() : Double.NaN;
        g.pricedAtMs = nowMs;
        g.pricedExplore = g.pricedWay && ch.explore();
    }

    /** 0.39.z48: what is left of the running pick's price now (its price less the time since; NaN: none taken). */
    static double leftS(Goal g, long nowMs) {
        if (Double.isNaN(g.pricedS)) return Double.NaN;
        return Math.max(0, g.pricedS - (nowMs - g.pricedAtMs) / 1000.0);   // structural: milliseconds per second
    }

    /**
     * 0.39.z48 (S66, pure): a re-priced way replaces the running pick: a priced way over one that ends in an explore, never
     * an explore over a priced way, no running price known: yes; else only when it is cheaper by {@code marginS}.
     */
    public static boolean switches(double leftS, boolean leftExplore, double newS, boolean newExplore, double marginS) {
        return switches(true, leftS, leftExplore, true, newS, newExplore, marginS);
    }

    /**
     * 0.39.z49 (S67, pure): the margin applies only when BOTH the running pick and the new pick are real priced ways. The
     * running pick no way (the explore while paths compute, none taken): the new pick replaces it at once; the new pick no
     * way (it waits on paths again): the running way goes on.
     */
    public static boolean switches(boolean leftWay, double leftS, boolean leftExplore, boolean newWay, double newS, boolean newExplore, double marginS) {
        if (!leftWay) return true;
        if (!newWay) return false;
        if (newExplore && !leftExplore) return false;
        if (leftExplore && !newExplore) return true;
        if (Double.isNaN(leftS)) return true;
        return leftS - newS >= marginS;
    }

    /** The strip orders whose run ended done (their goals are met; a failed run parks its goal instead). */
    private static final java.util.Set<Step.Strip> STRIPS_DONE = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** StepHands: the strip step ended done. */
    public static void stripDone(Step.Strip s) { STRIPS_DONE.add(s); }

    /** The strip order is met (its run ended done by amounts or the tunnel's end). */
    public static boolean stripMet(Step.Strip s) { return STRIPS_DONE.contains(s); }

    /**
     * The strip order's loop (owner 2026-10-22): the jobs of ONE order, the brain's checks at each job boundary: strip
     * (resumed from the saved tunnel end) -> deposit (the bag full) -> walk back. The goal stays open until its amounts are
     * met or the owner drops it (no amounts: until stopped); the next pick runs the same three again.
     */
    public static List<Step> stripLoop(Step.Strip s) {
        return List.of(s, new Step.Deposit(s), new Step.StripBack(s));
    }

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

    /** The goal {@link #add} would add, not in the list (aa debug prices it and drops it); the default n is 8 for an ore, 1 for an item. */
    public static Goal draft(Thing.Result r, int n, int prio, long now) {
        return new Goal(r, n > 0 ? n : r.ore() ? oreDefault() : 1, prio, now);
    }

    /** Adds (or replaces) a goal; the default n is 8 for an ore, 1 for an item. */
    public static Goal add(Blackboard bb, Thing.Result r, int n, int prio, long now) {
        Goal g = draft(r, n, prio, now);
        of(bb).removeIf(x -> x.r.key().equals(r.key()));
        finished(bb).removeIf(x -> x.key.equals(r.key()));      // 0.39.z61: the new order of the same thing takes over its items
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
        // 0.39.z41: the same while a walk's full path is computed in the background (the chain waits on "path not known yet")
        // 0.39.z47 (run H, S60): a chain's explore that ended with no new source does not start again within explore.retryS
        long waitS = exploreWaitS(exploreBareAtMs, System.currentTimeMillis(), exploreRetryS());
        if (waitS > 0) {
            var ch = chain(bb, g);
            if (ch != null && ch.ok() && !ch.steps().isEmpty() && ch.steps().get(0) instanceof Step.Explore)
                return "no new source; next explore in " + waitS + " s";
        }
        var wd = world(bb);
        boolean pathsPending = wd != null && wd.path() != null && wd.path().pending();
        if (bb.fact(LootBook.FACT) instanceof LootBook book && !book.ready() || pathsPending) {
            var ch = chain(bb, g);
            if (ch != null && ch.waiting()) return ch.missing();
        }
        return null;
    }

    /** 0.39.z47: when (System ms) the last chain explore ended with no new source; 0: none yet. */
    private static volatile long exploreBareAtMs;

    /** 0.39.z47: the explore job (Mining) says its chain explore ended with no new source. */
    public static void exploreEndedBare(long atMs) { exploreBareAtMs = atMs; }

    /** 0.39.z47: seconds an order waits after a chain explore that found nothing new (Tuning explore.retryS). */
    public static int exploreRetryS() { return Tuning.i("explore.retryS"); }

    /** 0.39.z47 (pure): seconds left of the wait after a bare explore ended at {@code endedMs} (0: none or over). */
    public static long exploreWaitS(long endedMs, long nowMs, int retryS) {
        if (endedMs <= 0 || retryS <= 0) return 0;
        long left = endedMs + retryS * 1000L - nowMs;          // structural: milliseconds per second
        return left <= 0 ? 0 : (left + 999) / 1000;          // structural: whole seconds, rounded up
    }

    /** one-planner: no prerequisite desire: the chain makes the pickaxe a dig asks for (the lowest tier that does it). */
    public static String prereq(Blackboard bb, Goal g) { return null; }

    /** 0.39.z36 (rule 15): the adapter's Supplier&lt;String&gt; of what is not ready while {@link #world} is null. */
    public static final String NOT_READY_FACT = "aa.notReady";

    /**
     * 0.39.z36 (rule 15, NOT_LOADED): the one line said while the chain has no world: what is not ready (the adapter's
     * {@link #NOT_READY_FACT}; none installed: said so) and how long the bot has been in the world.
     */
    public static String notReady(Blackboard bb) {
        return bb.fact(NOT_READY_FACT) instanceof java.util.function.Supplier<?> s && s.get() instanceof String w ? w
                : notReadyLine("the reason is not known (no adapter installed it)", -1, "try again");
    }

    /** The NOT_LOADED line's words: "senses not ready: {what} (world up {upS} s); {next}" ({@code upS} &lt; 0: unknown). */
    public static String notReadyLine(String what, long upS, String next) {
        return "senses not ready: " + what + " (world up " + (upS < 0 ? "unknown" : upS + " s") + "); " + next;
    }

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
        var r = routine(bb, g);
        return r == null ? null : r.steps();
    }

    /** 0.39.z61: as {@link #steps}, with the chain's own counts per step (Chain#uses: the run's reserves come from them). */
    public static io.github.mojolowjo.entropybot.aa.moves.Steps.Routine routine(Blackboard bb, Goal g) {
        if (g.n - have(bb.in.bag, g) <= 0) return null;
        if (g.strip != null) return new io.github.mojolowjo.entropybot.aa.moves.Steps.Routine(stripLoop(g.strip), null);   // the strip order's own loop (no chain)
        var ch = chain(bb, g);
        if (ch == null || !ch.ok() || ch.steps().isEmpty()) return null;
        took(g, ch, bb.clock.now());           // 0.39.z48: the running pick's price (the re-price margin)
        return new io.github.mojolowjo.entropybot.aa.moves.Steps.Routine(ch.steps(), null, ch.uses());
    }

    // ---- 0.39.z61: the orders' reserves (design D1 J2; owner's ruling 1, option c) ----

    /** Blackboard fact: the finished orders whose items stay safe from pillaring ({@link Finished}). */
    public static final String FINISHED_FACT = "aa.ordersFinished";

    /**
     * 0.39.z61 (owner's ruling 1 on D1, option c): an order that was met - its items are never Baritone's throwaway, yet any
     * chain may use them as ingredients. {@code n} only goes down: to what the bag still holds of them (a craft used some,
     * the owner took some); at 0 the entry goes. Cleared by {@code aa drop} and by a new {@code aa want} of the same thing.
     */
    public static final class Finished {
        public final String key;
        public final List<String> ids;
        public final boolean group;
        public int n;

        public Finished(String key, List<String> ids, int n, boolean group) {
            this.key = key;
            this.ids = List.copyOf(ids);
            this.n = n;
            this.group = group;
        }
    }

    @SuppressWarnings("unchecked")
    public static List<Finished> finished(Blackboard bb) {
        if (bb.fact(FINISHED_FACT) instanceof List<?> l) return (List<Finished>) l;
        List<Finished> l = new ArrayList<>();
        bb.fact(FINISHED_FACT, l);
        return l;
    }

    /** A met order joins the finished list (a strip order holds no items; the same key replaces the older entry). */
    static void finish(Blackboard bb, Goal g) {
        if (g.strip != null || g.r.ids().isEmpty()) return;
        List<Finished> f = finished(bb);
        f.removeIf(x -> x.key.equals(g.r.key()));
        f.add(new Finished(g.r.key(), g.r.ids(), g.n, g.r.group()));
    }

    /**
     * 0.39.z61 (pure): the ORDER reserves: the running order's target ({@code running}, null: none; a group: each member n)
     * and every finished order's items, each lowered first to what {@code bag} (path -> count, as {@link #have}) holds now.
     * A queued order reserves nothing (it plans from the bag when it starts; D1 3.4 "two orders, one bag").
     */
    public static List<baritone.api.process.Reserves.Reserve<String>> orderReserves(Goal running, List<Finished> finished, Map<String, Integer> bag) {
        List<baritone.api.process.Reserves.Reserve<String>> out = new ArrayList<>();
        if (running != null && running.strip == null && !running.r.ids().isEmpty()) {
            if (running.r.group()) for (String id : running.r.ids()) out.add(io.github.mojolowjo.entropybot.aa.moves.Reservations.order(running.r.key(), List.of(id), running.n));
            else out.add(io.github.mojolowjo.entropybot.aa.moves.Reservations.order(running.r.key(), running.r.ids(), running.n));
        }
        if (finished != null) {
            finished.removeIf(f -> {
                int held = 0;
                if (f.group) {
                    int low = Integer.MAX_VALUE;
                    for (String id : f.ids) low = Math.min(low, bag.getOrDefault(Thing.path(id), 0));
                    held = low == Integer.MAX_VALUE ? 0 : low;
                } else for (String id : f.ids) held += bag.getOrDefault(Thing.path(id), 0);
                f.n = Math.min(f.n, held);
                return f.n <= 0;
            });
            for (Finished f : finished) {
                if (running != null && f.key.equals(running.r.key())) continue;      // the running order of the same thing holds it
                if (f.group) for (String id : f.ids) out.add(io.github.mojolowjo.entropybot.aa.moves.Reservations.order(f.key + " (finished)", List.of(id), f.n));
                else out.add(io.github.mojolowjo.entropybot.aa.moves.Reservations.order(f.key + " (finished)", f.ids, f.n));
            }
        }
        return out;
    }

    /** The goal's chain on the world fact now (null: no world facts yet). */
    public static io.github.mojolowjo.entropybot.aa.NeedChain.Chain chain(Blackboard bb, Goal g) {
        if (g.strip != null) return null;
        var world = world(bb);
        if (world == null) return null;
        world = world.withoutSources(droppedSources(g));          // 0.39.z33: the order's dropped sources, listed with their reasons
        var laps = io.github.mojolowjo.entropybot.aa.core.TickLaps.AA;    // 0.39.z62 item 4: resolves counted while the probe is on
        long t0 = laps.now();
        var ch = g.r.group() ? io.github.mojolowjo.entropybot.aa.NeedChain.resolveAll(g.r.key(), g.r.ids(), g.n, world)
                : io.github.mojolowjo.entropybot.aa.NeedChain.resolve(g.r.ids().isEmpty() ? g.r.key() : g.r.ids().get(0), g.n, world);
        if (t0 != 0) laps.resolved(laps.now() - t0);
        // 0.39.z41: a source Baritone's complete search found unreachable is the z33 source fact: dropped for the order's life
        for (var e : ch.unreachable().entrySet()) g.sources.failedFor(g.id(), e.getKey(), e.getValue(), bb.clock.now(), null, ORDER_LIFE_MS);
        return ch;
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
        for (Goal g : out) finish(bb, g);        // 0.39.z61 (ruling 1 option c): its items stay safe from pillaring
        return out;
    }

    /** One aa goals line: "iron 2/8 high: score 90 - mine iron 6 dig | ...". */
    /** The strip orders' saved progress (set by the game side, StripStep; the default says it is not wired). */
    public static volatile java.util.function.Function<Step.Strip, StripOrder.Saved> stripProgress = s -> {
        throw new IllegalStateException("strip progress lookup not wired");
    };

    /** The strip orders' saved deposits (set by the game side, StripStep). */
    public static volatile java.util.function.Function<Step.Strip, DepositRule.Deposits> stripDeposits = s -> {
        throw new IllegalStateException("strip deposits lookup not wired");
    };

    public static String line(Blackboard bb, Goal g, String scoreText) {
        if (g.strip != null) {
            StripOrder.Saved p;
            DepositRule.Deposits d;
            try {
                p = stripProgress.apply(g.strip);
                d = stripDeposits.apply(g.strip);
            } catch (RuntimeException e) {
                return "strip: " + scoreText + " - progress unreadable: " + e.getMessage() + " [" + g.strip.text() + "]";
            }
            return StripOrder.goalLine(g.strip.heading(), g.strip.y(), g.strip.amounts(), p, d) + ": " + scoreText
                    + (g.parked != null ? " - parked: " + g.parked : "") + " [" + g.strip.text() + "]";
        }
        String b = blocked(bb, g);
        var ch = b == null ? chain(bb, g) : null;
        return g.r.key() + " " + have(bb.in.bag, g) + "/" + g.n + (g.prio > 0 ? " high" : g.prio < 0 ? " low" : "") + ": " + scoreText
                + (b != null ? (g.parked == null ? " - held: " : " - parked: ") + b :ch != null ? " - chain: " + ch.why() : " - no facts for the chain yet")
                + " [" + g.r.text() + "]";
    }
}
