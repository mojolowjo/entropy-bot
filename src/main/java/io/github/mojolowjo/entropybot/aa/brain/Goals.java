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

        /** 0.39.z62 item 1: re-plans on a source new to this order, a count shown, never a stop (aa want again sets it back to 0). */
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

        /** 0.39.z77 world 4: the positional held sources (a station's walk, a walk target) back now - the hysteresis state of {@link #droppedSources(Goal, int[])}. */
        public final java.util.Set<String> back = new java.util.HashSet<>();

        /** 0.39.z65 item 2: the spare-tool line last said for this order (said again only when a taken chain's line differs). */
        public String sparesSaid;

        /**
         * 0.39.z73 (D2 3.4): each step object of the chains this order took -> its rough seconds (Chain#stepSeconds), so a
         * repair prices the kept tail at its old prices. By identity: the run holds the chain's own step objects.
         */
        public final Map<Step, Double> stepSeconds = new java.util.IdentityHashMap<>();

        /** 0.39.z70 (D2 3.1 stability): the order's plan switches (failure re-plans + re-prices), the re-prices among them, the steps they changed. */
        public int switches, repriceSwitches, stepsChanged;

        /** 0.39.z70 (R1 = c): the honest-loop try the run parked on, until {@link #park} keeps it as {@link #loopPark}. */
        public io.github.mojolowjo.entropybot.aa.moves.Steps.Attempt loopAttempt;
        /** 0.39.z70 (R1 = c): the facts the order parked on by the honest loop (null: another park, or none). */
        public LoopPark loopPark;
        /** 0.39.z70 (R1 = c): the one more try was taken (once per order; aa want again makes a new order). */
        public boolean loopRetried;
        /**
         * 0.39.z76 sb4 review 2: the order's tries ({@code Steps.Attempt#tryKey}: step, fact, needs in the bag; no cell, no
         * order); a try seen again parks the run. Emptied by the one more try (R1) and by aa want again.
         */
        public final java.util.Set<String> tries = new java.util.HashSet<>();

        /**
         * 0.39.z77 J2: the batch this order came in with (one {@code aa want a, b, c} line: AaRuntime names it), null: an
         * order alone. The batch's open orders may run as one playbook ({@link BatchWay}).
         */
        public String batch;

        /** 0.39.z77 J2: the steps of the playbook chains this order took (by identity): a failure of one holds the playbook for the order. */
        public final java.util.Set<Step> playbookSteps = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());

        /** 0.39.z77 J2: the first step of each playbook leg this order took -> its words, said once when that step starts. */
        public final Map<Step, String> legStarts = new java.util.IdentityHashMap<>();

        /**
         * 0.39.z77 answers (1): the order runs its batch's playbook now (the last chain it took is one). Its own "got" is then a
         * fact inside that run, not the run's end ({@link #carriesOn}); the run is re-planned only on a changed fact.
         */
        public boolean playbookRun;

        /** 0.39.z77 met: the order was taken off the goals as met ({@link #done}); a later end of its run is no stop ({@link #runFailed}). */
        public boolean met;

        /** 0.39.z77 climb: the one more try on the climb way (every order parked under ground) was taken; once per order. */
        public boolean climbRetried;
    }

    // ---- 0.39.z77 climb (run CM 17:43:10-17:48:31: every order parked under ground, 5 min idle, no line said why) ----

    /** The fact holding the all-parked line last said (said again only when it changes). */
    static final String ALL_PARKED_SAID = "aa.allParkedSaid";

    /** structural: a park's reason is cut to this many characters in the all-parked line (the whole reason was said at the park). */
    static final int PARK_REASON_CHARS = 120; // structural: text length

    /** The order holds a failed climb (its source key {@link io.github.mojolowjo.entropybot.aa.NeedChain#CLIMB_KEY}). */
    static boolean climbHeld(Goal g) { return droppedSources(g).containsKey(io.github.mojolowjo.entropybot.aa.NeedChain.CLIMB_KEY); }

    /**
     * 0.39.z77 climb (pure): what would wake a parked order - the honest-loop park's facts (a need rises, it moves
     * failures.moved, a new source), the one more try on the climb way for an order whose climb failed (taken when every
     * order is parked under ground), else nothing but the owner's word.
     */
    public static String wake(Goal g) { return wake(g, false); }

    /** {@link #wake(Goal)}; {@code worldClimb}: another order's failed climb holds here for it (a world fact, run EJ). */
    static String wake(Goal g, boolean worldClimb) {
        if (g.loopPark != null && !g.loopRetried)
            return "wakes when a need in the bag rises, it moves " + Math.round(Failures.movedV()) + " blocks or a new source is seen";
        if (!g.climbRetried && (climbHeld(g) || worldClimb)) return "tries once more on the climb way when every order is parked under ground";
        return "no wake by itself (aa want " + g.r.word().trim() + " again)";
    }

    /**
     * 0.39.z77 climb (pure; rule 7): every open order is parked - one line naming each park (its reason's head) and what would
     * wake it; null: none open, or one is not parked (it runs, or waits on a said reason of its own).
     */
    public static String allParked(List<Goal> goals) { return allParked(goals, g -> false); }

    /** {@link #allParked(List)}; {@code worldClimb}: the orders a world fact of a failed climb holds for (run EJ). */
    static String allParked(List<Goal> goals, java.util.function.Predicate<Goal> worldClimb) {
        if (goals == null || goals.isEmpty()) return null;
        List<String> parts = new ArrayList<>();
        for (Goal g : goals) {
            if (g.parked == null) return null;
            String why = g.parked.length() > PARK_REASON_CHARS ? g.parked.substring(0, PARK_REASON_CHARS) + "..." : g.parked;
            parts.add(g.r.key() + ": " + why + " - " + wake(g, worldClimb.test(g)));
        }
        return "every order is parked, nothing runs: " + String.join("; ", parts);
    }

    /** 0.39.z78 run EJ: the climb way's world fact (another order's failed climb here) holds for {@code g}. */
    static boolean worldClimb(Blackboard bb, Goal g) {
        return WorldFacts.of(bb).holds(io.github.mojolowjo.entropybot.aa.NeedChain.CLIMB_KEY, droppedSources(g).keySet(), bb.in, WorldFacts.fluid(bb));
    }

    /**
     * 0.39.z77 climb (run CM: the bot 59 blocks under ground, every order parked on its failed climb or the honest loop;
     * moving was the only fact that could wake them and it could not move): with every order parked and the feet
     * {@code underGround}, each parked order that waits on moving (the honest loop) or whose climb failed tries once more -
     * its held climb is forgotten, so its chain offers the climb way again (NeedChain.climbUp). Once per order; said in the
     * log and the whisper. Then the all-parked line ({@link #allParked}) is said once while it holds (rule 7). The lines said.
     */
    public static List<String> parkedTick(Blackboard bb, boolean underGround) {
        List<String> out = new ArrayList<>();
        if (!(bb.fact(FACT) instanceof List<?>)) return out;
        List<Goal> goals = of(bb);
        // 0.39.z78 run EJ (rule 7): a failed climb is a world fact - the orders it holds for (not their own) wait on it too
        java.util.Set<Goal> byWorld = new java.util.HashSet<>();
        for (Goal g : goals) if (worldClimb(bb, g)) byWorld.add(g);
        if (underGround && allParked(goals) != null) {
            for (Goal g : goals) {
                boolean movesOnly = g.loopPark != null && !g.loopRetried;
                if (g.climbRetried || !(movesOnly || climbHeld(g) || byWorld.contains(g))) continue;
                g.climbRetried = true;
                g.sources.forget(g.id(), io.github.mojolowjo.entropybot.aa.NeedChain.CLIMB_KEY);
                WorldFacts.of(bb).forget(io.github.mojolowjo.entropybot.aa.NeedChain.CLIMB_KEY);   // the one more try: for every order
                String line = "order " + g.r.key() + " tries once more on the climb way: every order is parked and the bot is "
                        + (bb.in == null ? "?" : String.valueOf(bb.in.underDepth)) + " blocks under ground, where nothing it waits on can change (it parked on: " + g.parked + ")";
                g.parked = null;
                g.loopPark = null;
                g.tries.clear();                 // the try asks its questions afresh (as the R1 try)
                bb.port.log(line);
                bb.port.tellOwner(line);
                out.add(line);
            }
        }
        String all = allParked(goals, g -> !g.climbRetried && worldClimb(bb, g));
        if (all == null) { bb.fact(ALL_PARKED_SAID, null); return out; }
        if (all.equals(bb.fact(ALL_PARKED_SAID))) return out;
        bb.fact(ALL_PARKED_SAID, all);
        bb.port.log(all);
        bb.port.tellOwner(all);
        out.add(all);
        return out;
    }

    /**
     * 0.39.z77 answers (1) (manager 2026-10-26: "the batch's run carries on"): an order met while its desire runs the batch's
     * playbook ({@code runningId}: the brain's running desire) is said ("got ...") but its run is not ended: the legs go on
     * with their numbers; its desire leaves when that run ends. Pure.
     */
    public static boolean carriesOn(Goal g, String runningId) {
        return g != null && g.playbookRun && g.batch != null && g.id().equals(runningId);
    }

    /**
     * 0.39.z77 answers (1): the chain a re-plan of {@code g} takes - its own; for an order already met inside its batch's
     * playbook run, the batch's open orders' (the first open one's chain: the playbook when two or more are open), so the run
     * goes on for the rest of the batch. Null: no world.
     */
    static io.github.mojolowjo.entropybot.aa.NeedChain.Chain runChain(Blackboard bb, Goal g) {
        if (g.playbookRun && g.n - have(bb.in.bag, g) <= 0) {
            for (Goal o : BatchWay.open(bb, g)) if (o != g) return chain(bb, o);
        }
        return chain(bb, g);
    }

    /**
     * 0.39.z70 (owner's ruling R1 = c on D2): what an order parked by the honest loop parked on - the try ({@code at}), the
     * bag counts of its needs then ({@code counts}, as {@link #needCounts} reads the sensed bag), and the cells of the known
     * sources of the order's item and the step's needs ({@code sources}; null: not known then, rule 15).
     */
    public record LoopPark(io.github.mojolowjo.entropybot.aa.moves.Steps.Attempt at, Map<String, Integer> counts, java.util.Set<String> sources) {
        public LoopPark {
            counts = Map.copyOf(counts);
            sources = sources == null ? null : java.util.Set.copyOf(sources);
        }
    }

    /**
     * 0.39.z70 (R1 = c; pure): what changed since the honest-loop park, the words for the chat; null: nothing (it stays
     * parked). A need's count in the bag rose; the bot stands {@code failures.moved} blocks from the cell the try started
     * at; a source cell not known at the park. Rule 15: an unknown part (no feet, no sources) is no change.
     */
    public static String loopChange(LoopPark p, Map<String, Integer> countsNow, int[] feetNow, java.util.Set<String> sourcesNow) {
        if (p == null) return null;
        for (var e : new java.util.TreeMap<>(p.counts()).entrySet()) {
            Integer now = countsNow == null ? null : countsNow.get(e.getKey());
            if (now != null && now > e.getValue()) return e.getKey().replaceAll("(^|[|#])minecraft:", "$1") + " in the bag rose from " + e.getValue() + " to " + now;
        }
        List<Integer> f = p.at() == null ? null : p.at().feet();
        if (f != null && f.size() == 3 && feetNow != null && feetNow.length == 3) {      // structural: x y z
            double d = Failures.moved(feetNow, new int[]{f.get(0), f.get(1), f.get(2)});
            if (d >= Failures.movedV()) return "moved " + Math.round(d) + " blocks from where it parked (" + p.at().startCell() + ", failures.moved " + Math.round(Failures.movedV()) + ")";
        }
        if (p.sources() != null && sourcesNow != null)
            for (String s : new java.util.TreeSet<>(sourcesNow)) if (!p.sources().contains(s)) return "a new source seen: " + s;
        return null;
    }

    /**
     * 0.39.z70 (R1 = c): the bag counts of an Attempt's need keys from the sensed bag (path -> count): a plain id by its
     * path, a counted slot (a tag, an "a|b" word) as its members summed, a tool tier ("pickaxe tier") as the bag's pickaxe
     * tier. Pure.
     */
    public static Map<String, Integer> needCounts(java.util.Collection<String> keys, Map<String, Integer> bag, int pickTier) {
        Map<String, Integer> out = new java.util.LinkedHashMap<>();
        if (keys == null || bag == null) return out;
        for (String k : keys) {
            if (k.endsWith(" tier")) { out.put(k, pickTier); continue; }
            if (io.github.mojolowjo.entropybot.aa.NeedChain.plain(k)) { out.put(k, bag.getOrDefault(Thing.path(k), 0)); continue; }
            int have = 0;
            for (String m : io.github.mojolowjo.entropybot.aa.NeedChain.membersOf(k)) have += bag.getOrDefault(Thing.path(m), 0);
            out.put(k, have);
        }
        return out;
    }

    /** 0.39.z70 (R1 = c): the cells of the known sources of the order's item and the try's plain needs; null: no world facts. */
    static java.util.Set<String> sourceCells(Blackboard bb, Goal g, io.github.mojolowjo.entropybot.aa.moves.Steps.Attempt a) {
        var w = world(bb);
        if (w == null) return null;
        java.util.Set<String> items = new java.util.TreeSet<>(g.r.ids().isEmpty() ? List.of(g.r.key()) : g.r.ids());
        if (a != null && a.needs() != null)
            for (String k : a.needs().keySet()) if (!k.endsWith(" tier") && io.github.mojolowjo.entropybot.aa.NeedChain.plain(k)) items.add(k);
        java.util.Set<String> out = new java.util.TreeSet<>();
        for (String id : items)
            for (var s : w.seenAll().apply(id)) out.add(Thing.path(id) + " at " + s.x() + " " + s.y() + " " + s.z());
        return out;
    }

    /**
     * 0.39.z70 (R1 = c): every order parked by the honest loop whose parked-on facts changed tries once more - it is unparked
     * (once per order), said in the log and the whisper. Called with the goals' done check. The lines said.
     */
    public static List<String> retryLoopParks(Blackboard bb) {
        List<String> out = new ArrayList<>();
        if (!(bb.fact(FACT) instanceof List<?>)) return out;
        for (Goal g : of(bb)) {
            if (g.parked == null || g.loopPark == null || g.loopRetried) continue;
            int[] pos = bb.in == null ? null : bb.in.pos;
            Map<String, Integer> bag = bb.in == null ? null : bb.in.bag;
            String change = loopChange(g.loopPark, needCounts(g.loopPark.counts().keySet(), bag, bb.in == null ? 0 : bb.in.pickTier), pos, sourceCells(bb, g, g.loopPark.at()));
            if (change == null) continue;
            String line = "order " + g.r.key() + " tries once more: " + change + " (it parked on: " + g.parked + ")";
            g.parked = null;
            g.loopRetried = true;
            g.loopPark = null;
            g.tries.clear();                 // 0.39.z76 sb4 review 2: the one more try asks its questions afresh
            bb.port.log(line);
            bb.port.tellOwner(line);
            out.add(line);
        }
        return out;
    }

    /**
     * 0.39.z70: the order's re-planner for its run - {@link #replan}, its count and dropped sources for the park line, the
     * plan diffs counted for aa status, and the honest-loop park's try kept for R1.
     */
    public static io.github.mojolowjo.entropybot.aa.moves.Steps.Replanner replanner(Blackboard bb, Goal g) {
        return new io.github.mojolowjo.entropybot.aa.moves.Steps.Replanner() {
            @Override public io.github.mojolowjo.entropybot.aa.moves.Steps.Routine replan(Step failed, io.github.mojolowjo.entropybot.aa.moves.FactChange.Fact fact, String why) {
                return Goals.replan(bb, g, failed, fact, why);
            }

            @Override public io.github.mojolowjo.entropybot.aa.moves.Steps.Routine replan(Step failed, io.github.mojolowjo.entropybot.aa.moves.FactChange.Fact fact, String why,
                                                                                  io.github.mojolowjo.entropybot.aa.moves.Steps.Remaining left) {
                return Goals.replan(bb, g, failed, fact, why, left);
            }

            @Override public String orderState() { return Goals.orderState(g); }

            @Override public void switched(io.github.mojolowjo.entropybot.aa.moves.PlanDiff d, boolean reprice) {
                g.switches++;
                if (reprice) g.repriceSwitches++;
                g.stepsChanged += d.changed();
            }

            @Override public void parked(io.github.mojolowjo.entropybot.aa.moves.Steps.Attempt a) { g.loopAttempt = a; }

            @Override public java.util.Set<String> tries() { return g.tries; }

            // 0.39.z77 J2 (owner via the manager: the watcher sees each leg): "playbook leg 2/3: descent to y 16 for ..." in
            // the log and the whisper, once, when its first step starts
            @Override public void started(Step s) {
                String t = g.legStarts.remove(s);
                if (t == null) return;
                bb.port.log(g.r.key() + ": " + t);
                bb.port.tellOwner(t);
            }
        };
    }

    /**
     * 0.39.z70 review 1: a run of the order is about to start (its job's spec is asked): a try handed by an earlier run's
     * honest-loop park that no park took (the run's end was a passing one) is dropped, so it never reaches a later, unrelated
     * park and gives that one an R1 retry.
     */
    public static void runStarts(Goal g) { g.loopAttempt = null; }

    /** 0.39.z70: "re-plans this order: 2; dropped: strip ..., tree at 1 2 3" (the honest-loop park line). Pure. */
    public static String orderState(Goal g) {
        var d = droppedSources(g).keySet();
        return "re-plans this order: " + (g.replans + g.sourceReplans) + "; dropped: " + (d.isEmpty() ? "none" : String.join(", ", d));
    }

    /**
     * 0.39.z76 sb4 review: the order's stop when the re-plan finds no way: "no way left: &lt;each dropped source: its reason&gt;
     * (chain: &lt;the chain's why&gt;)". Pure.
     */
    public static String noWayLeft(Goal g, String chainWhy) {
        List<String> d = new ArrayList<>();
        for (var e : droppedSources(g).entrySet()) d.add(e.getKey() + ": " + e.getValue());
        // 0.39.z78 J5 slice 1: the stations held (StationHolds), as every reader says them (feet not known here: all held)
        for (String l : StationHolds.INSTANCE.lines(null, null)) d.add(l);
        return "no way left: " + (d.isEmpty() ? "no source dropped" : String.join("; ", d)) + " (chain: " + chainWhy + ")";
    }

    /** 0.39.z70 (D2 3.1, aa status): "iron_pickaxe: re-plans 3 (1 re-priced), steps changed per re-plan 2.3". Pure. */
    public static String stabilityLine(Goal g) {
        double per = g.switches == 0 ? 0 : (double) g.stepsChanged / g.switches;
        return g.r.key() + ": re-plans " + g.switches + " (" + g.repriceSwitches + " re-priced), steps changed per re-plan " + String.format(Locale.ROOT, "%.1f", per);
    }

    /** 0.39.z33: a dropped source is held for the order's life (a new aa want makes a new goal). */
    static final long ORDER_LIFE_MS = Long.MAX_VALUE / 4; // structural: no lapse within an order

    /** The order's dropped sources, key -> reason (the chain prices without them and lists them). */
    public static Map<String, String> droppedSources(Goal g) {
        Map<String, String> out = new java.util.LinkedHashMap<>();
        for (Failures.Rec r : g.sources.of(g.id())) if (r.option() != null) out.put(r.option(), r.reason());
        return out;
    }

    /**
     * 0.39.z65 review 1: the order's dropped sources as the chain sees them from {@code pos} (the feet now, null: unknown): a
     * known station dropped because its walk failed ({@link io.github.mojolowjo.entropybot.aa.NeedChain#STATION_AT}) is
     * back once the bot stands {@code failures.moved} blocks closer to it than where the walk failed (a walk from there is
     * another walk); every other source stays dropped for the order's life. A new order starts with none.
     * <p>0.39.z77 world 4 (re-check: the trip key reads these, BatchWay.trip; feet jittering at the 16-block edge flipped it
     * every tick): the same band as the world facts (WorldFacts.MARGIN) - back once failures.moved + 8 closer, held again
     * only when less than failures.moved closer; in between the order's last state ({@link Goal#back}). Feet unknown: held,
     * the state kept. Updates the state.
     */
    public static Map<String, String> droppedSources(Goal g, int[] pos) {
        Map<String, String> out = new java.util.LinkedHashMap<>();
        for (Failures.Rec r : g.sources.of(g.id())) {
            if (r.option() == null) continue;
            if (pos != null && headingHold(r.option()) && r.pos() != null) {
                // 0.39.z78 strip (manager 2026-10-27): a heading hold lifts as the other holds do, by the bot's move - past
                // failures.moved (+ the same 8-block band) from where it was learned, so a later strip may take that heading
                // from another cell; held again within failures.moved
                double d = Failures.moved(r.pos(), pos);
                if (d >= Failures.movedV() + WorldFacts.MARGIN) g.back.add(r.option());
                else if (d < Failures.movedV()) g.back.remove(r.option());
                if (g.back.contains(r.option())) continue;
            } else if (pos != null && positional(r.option())) {
                double gain = stationGain(r.option(), r.pos(), pos);
                if (gain >= Failures.movedV() + WorldFacts.MARGIN) g.back.add(r.option());
                else if (!(gain >= Failures.movedV())) g.back.remove(r.option());        // less, or not a cell (NaN): held
                if (g.back.contains(r.option())) continue;
            }
            out.put(r.option(), r.reason());
        }
        return out;
    }

    /**
     * 0.39.z65 review 1; 0.39.z77 walk: a key held by where the bot stood when it failed - a station's walk ("station at x y
     * z") or a walk target that ended short twice ("walk target x y z", FactChange.walkHeld). Pure.
     */
    /** 0.39.z78 strip: a strip heading hold ("strip heading y Y h[|from ...]", NeedChain.stripHold) - kept with where the bot stood. Pure. */
    static boolean headingHold(String key) {
        return key != null && key.startsWith(io.github.mojolowjo.entropybot.aa.NeedChain.STRIP_HEADING);
    }

    static boolean positional(String key) {
        return key != null && (key.startsWith(io.github.mojolowjo.entropybot.aa.NeedChain.STATION_AT) || key.startsWith(io.github.mojolowjo.entropybot.aa.NeedChain.WALK_TARGET));
    }

    /** 0.39.z78 J5 slice 1 (pure): the cell {x, y, z} of a "station at x y z" key, null: not one. */
    static int[] stationCell(String key) {
        if (key == null || !key.startsWith(io.github.mojolowjo.entropybot.aa.NeedChain.STATION_AT)) return null;
        String[] c = key.substring(io.github.mojolowjo.entropybot.aa.NeedChain.STATION_AT.length()).trim().split("\\s+");
        if (c.length != 3) return null;     // structural: x y z
        for (String n : c) if (!n.matches("-?\\d{1,9}")) return null;
        return new int[]{Integer.parseInt(c[0]), Integer.parseInt(c[1]), Integer.parseInt(c[2])};
    }

    /** 0.39.z78 J5 slice 1: the blackboard fact - a cell {x, y, z} -&gt; the block id there now (null: not loaded), for StationHolds' refusals. */
    public static final String BLOCK_AT_FACT = "aa.blockAt";

    /** The stations held now from the feet (StationHolds, the one store), key -&gt; how it is said. */
    @SuppressWarnings("unchecked")
    public static Map<String, String> stationHolds(Blackboard bb) { return stationHolds(bb, bb.in == null ? null : bb.in.pos); }

    /** D11 (the anchor rule): the stations held at {@code pos} - a trip reads them at the running step's end, not the live feet. */
    @SuppressWarnings("unchecked")
    public static Map<String, String> stationHolds(Blackboard bb, int[] pos) {
        var f = bb.fact(BLOCK_AT_FACT) instanceof java.util.function.Function<?, ?> fn ? (java.util.function.Function<int[], String>) fn : null;
        return StationHolds.INSTANCE.heldAt(pos, f);
    }

    /** 0.39.z65 review 1 (pure): a station key (0.39.z77: or a walk target) held from {@code from} is released at {@code now} ({@code failures.moved} closer). */
    public static boolean stationBack(String key, int[] from, int[] now) {
        return stationGain(key, from, now) >= Failures.movedV();
    }

    /** 0.39.z77 world 4 (pure): how much closer to a positional key's cell {@code now} is than {@code from} (blocks); NaN: not one, or not known. */
    static double stationGain(String key, int[] from, int[] now) {
        if (!positional(key) || from == null || now == null) return Double.NaN;
        String p = key.startsWith(io.github.mojolowjo.entropybot.aa.NeedChain.STATION_AT) ? io.github.mojolowjo.entropybot.aa.NeedChain.STATION_AT
                : io.github.mojolowjo.entropybot.aa.NeedChain.WALK_TARGET;
        String[] c = key.substring(p.length()).trim().split("\\s+");
        if (c.length != 3) return Double.NaN;     // structural: x y z
        for (String n : c) if (!n.matches("-?\\d{1,9}")) return Double.NaN;     // not a station cell: held as any source
        int[] at = {Integer.parseInt(c[0]), Integer.parseInt(c[1]), Integer.parseInt(c[2])};
        return Failures.moved(from, at) - Failures.moved(now, at);
    }

    /**
     * 0.39.z30 (owner 2026-10-23: a job whose fact changed re-plans and continues): the order's chain priced again on the
     * facts of now - the same call the order's job makes ({@link #steps}) - after {@code failed} found {@code fact} changed.
     * One line to the owner says it; no way now: null steps with "no way left: " + the order's dropped sources and the
     * chain's why ({@link #noWayLeft}; the run ends with the step's own reason). No count stops it (0.39.z76 sb4 review). Nothing here knows which fact or item it is.
     */
    public static io.github.mojolowjo.entropybot.aa.moves.Steps.Routine replan(Blackboard bb, Goal g, Step failed,
                                                                             io.github.mojolowjo.entropybot.aa.moves.FactChange.Fact fact, String why) {
        return replan(bb, g, failed, fact, why, null);
    }

    /**
     * 0.39.z73 (design D2 3.4, Job D): as above with what was left of the run ({@code left}, null: not known): the full
     * re-plan and the repair (the gap for what the failed step served + the kept tail, dry-run on the bag) are both priced,
     * the repair kept unless the full re-plan is cheaper by replan.switchMarginS ({@link io.github.mojolowjo.entropybot.aa.moves.Repair}).
     * The dropped source and the line to the owner as before; the pick is said with both prices.
     */
    public static io.github.mojolowjo.entropybot.aa.moves.Steps.Routine replan(Blackboard bb, Goal g, Step failed,
                                                                             io.github.mojolowjo.entropybot.aa.moves.FactChange.Fact fact, String why,
                                                                             io.github.mojolowjo.entropybot.aa.moves.Steps.Remaining left) {
        if (fact.price()) return reprice(bb, g, fact, failed);
        // 0.39.z77 SHAPE CHANGE (owner 2026-10-26 10:30: repair locally): a failed step of the trip is repaired inside its
        // leg - the gap for what it served, from the bag as it is, the trip's later steps kept (D2's repair; never priced
        // against a whole re-plan); only when no repair stands is the trip priced again from the bag (the same derivation,
        // what is had counted); no way left ends the order with the reasons. The J2 hold "playbook <batch>" is deleted
        boolean trip = g.playbookRun;
        // 0.39.z33 (S36): a source fact charges the failed step's source by its key: the chain drops it for this order.
        // 0.39.z62 item 1b: a fact may name its own source key (a strip heading blocked at its Y, any ore set)
        String own = io.github.mojolowjo.entropybot.aa.moves.FactChange.ownSourceKey(fact);
        String src = own != null ? own : io.github.mojolowjo.entropybot.aa.NeedChain.sourceKey(failed);
        // 0.39.z76 sb4 review 2: a source fact whose step names no source key is held by the fact's own key (never a fact
        // that holds nothing: Steps' order tries leave every "source ..." fact to the order's holds)
        if (src == null && fact.key().startsWith("source ") && fact.key().length() > "source ".length()) src = fact.key().substring("source ".length());
        boolean sourceFact = src != null && fact.key().startsWith("source ");
        // 0.39.z62 item 1: a source fact that drops a source NEW to this order is counted apart (the counts are shown in the
        // park line, aa status). 0.39.z76 sb4 review (manager, run AV 02:43:29 "not re-planned: 8 sources dropped already
        // (replan.maxSourceDrops 8)"; rule 7: a count is never a stop reason): replan.maxPerOrder and replan.maxSourceDrops are
        // deleted. The order stops only when no way is left - every way held or dropped with its reason - or the honest park
        boolean newSource = sourceFact && !droppedSources(g).containsKey(src);
        // 0.39.z77 answers (1): an order met inside its batch's playbook run re-plans that run for the batch's open orders
        if (g.n - have(bb.in.bag, g) <= 0 && !trip) return new io.github.mojolowjo.entropybot.aa.moves.Steps.Routine(null, "the order is met");
        // 0.39.z65 review 1: a station's walk fact keeps where the bot stood (released once it stands failures.moved closer)
        // 0.39.z77 walk: a held walk target keeps it too (the same per-position hold)
        int[] feet = bb.in == null ? null : bb.in.pos;
        // 0.39.z78 J5 slice 1 (rule 23; audit D1, D8): a station walk's hold goes to the ONE station store (StationHolds) -
        // held for every order, named by this one, back once the bot stands the band away from here - never the order's sources nor the world
        // facts as well
        boolean station = sourceFact && src.startsWith(io.github.mojolowjo.entropybot.aa.NeedChain.STATION_AT);
        if (station) {
            int[] at = stationCell(src);
            if (at != null) StationHolds.INSTANCE.walkFailed(g.r.key(), at[0], at[1], at[2], fact.text(), feet);
            sourceFact = at == null;                             // not a cell (never): held as any source, said
        }
        if (sourceFact) g.sources.failedFor(g.id(), src, fact.text(), bb.clock.now(), positional(src) || headingHold(src) ? bb.in : null, ORDER_LIFE_MS);
        // 0.39.z77 world (run CX 22:43:18: the met pickaxe's water-ahead strip re-tried by the helmet): the fact is the world's
        // 0.39.z77 world 3 (review 1): a heading fact is kept by the tunnel's start too - the strip's fresh start, else the
        // square of the feet's column (where a strip from the feet starts, NeedChain.stripStart)
        // 0.39.z78 strip: a heading fact that names its cause cell (water / lava ahead) is the tunnel's, whatever the start (the
        // fork resumes a tunnel by its key from anywhere): kept unscoped, lifted when that cell reads dry (WorldFacts.gone)
        String start = WorldFacts.causeCell(fact.text()) != null ? null
                : failed instanceof io.github.mojolowjo.entropybot.aa.moves.Step.Strip st && st.from() != null ? st.from()
                : feet == null || feet.length < 3 ? null : "area " + StripAreas.square(feet[0], feet[2]);     // structural: x y z
        if (sourceFact) WorldFacts.of(bb).learned(src, fact.text(), g.r.key(), bb.in, start);
        var ch = runChain(bb, g);
        if (ch == null) return new io.github.mojolowjo.entropybot.aa.moves.Steps.Routine(null, "no facts for the chain yet (the senses are not ready)");
        // 0.39.z73 (D2 3.4): the repair, priced against this full re-plan
        var pk = left == null ? null : repair(bb, g, left, ch, sourceFact || station ? src : null, trip ? Double.POSITIVE_INFINITY : switchMarginS());
        if (pk != null) bb.port.log(g.r.key() + ": " + pk.line());
        if (pk != null && pk.repair()) {
            if (newSource) g.sourceReplans++;
            else g.replans++;
            for (int i = 0; i < pk.steps().size() && i < pk.gapSeconds().size(); i++) g.stepSeconds.put(pk.steps().get(i), pk.gapSeconds().get(i));
            g.pricedWay = true;
            g.pricedS = pk.repairS();
            g.pricedAtMs = bb.clock.now();
            g.pricedExplore = false;
            String line = g.r.key() + ": " + fact.text() + " - repaired: " + pk.steps().get(0).text() + " first, " + pk.kept() + " step" + (pk.kept() == 1 ? "" : "s") + " kept";
            bb.port.log(line + " (after step " + failed.text() + ": " + why + ")");
            bb.port.tellOwner(line);
            return new io.github.mojolowjo.entropybot.aa.moves.Steps.Routine(pk.steps(), null, pk.uses());
        }
        if (!ch.ok() || ch.steps().isEmpty()) return new io.github.mojolowjo.entropybot.aa.moves.Steps.Routine(null, noWayLeft(g, ch.why()));
        if (newSource) g.sourceReplans++;
        else g.replans++;
        took(bb, g, ch);
        String line = g.r.key() + ": " + fact.text() + " - re-planned: " + ch.steps().get(0).text() + " first";
        bb.port.log(line + " (after step " + failed.text() + ": " + why + "; chain: " + ch.why() + ")");
        bb.port.tellOwner(line);
        return new io.github.mojolowjo.entropybot.aa.moves.Steps.Routine(ch.steps(), null, ch.uses());
    }

    /**
     * 0.39.z73 (D2 3.4): the repair candidate against {@code full}: the split of what was left, the gap's chain for what the
     * failed step served (on the order's world of now, its dropped sources applied), the kept tail's old seconds (from the
     * chains this order took, {@link Goal#stepSeconds}; one missing: not known), the dry run on the bag.
     */
    static io.github.mojolowjo.entropybot.aa.moves.Repair.Pick repair(Blackboard bb, Goal g, io.github.mojolowjo.entropybot.aa.moves.Steps.Remaining left,
                                                                    io.github.mojolowjo.entropybot.aa.NeedChain.Chain full, String dropped) {
        return repair(bb, g, left, full, dropped, switchMarginS());
    }

    /** 0.39.z77 SHAPE CHANGE: {@code margin} - a trip's repair is never priced against its whole re-plan (infinite). */
    static io.github.mojolowjo.entropybot.aa.moves.Repair.Pick repair(Blackboard bb, Goal g, io.github.mojolowjo.entropybot.aa.moves.Steps.Remaining left,
                                                                    io.github.mojolowjo.entropybot.aa.NeedChain.Chain full, String dropped, double margin) {
        // 0.39.z78 strip-off review: with the strip way off, a tail that holds a strip is never kept - the full re-plan
        if (!StripWays.on() && StripWays.holdsStrip(left.steps())) return null;
        var sp = io.github.mojolowjo.entropybot.aa.moves.Repair.split(left.steps());
        io.github.mojolowjo.entropybot.aa.NeedChain.Chain gap = null;
        double tail = Double.NaN;
        if (sp.why() == null) {
            var w = chainWorld(bb, g);
            if (w != null) gap = io.github.mojolowjo.entropybot.aa.NeedChain.resolve(sp.item(), sp.n(), w);
            tail = 0;
            for (int i = sp.served() + 1; i < left.steps().size(); i++) {
                Double s = g.stepSeconds.get(left.steps().get(i));
                if (s == null) { tail = Double.NaN; break; }
                tail += s;
            }
        }
        return io.github.mojolowjo.entropybot.aa.moves.Repair.pick(left.steps(), left.uses(), sp, gap, tail, full, left.bag(), margin, dropped);
    }

    /**
     * 0.39.z41: a walk's complete path landed while the order runs: its chain priced again on the facts of now (the same call
     * as {@link #replan}); not a failure, not counted as a re-plan. The steps from here (the caller keeps the
     * old ones when they are the same, or when no way stands now: null steps with why), logged with the chain.
     */
    static io.github.mojolowjo.entropybot.aa.moves.Steps.Routine reprice(Blackboard bb, Goal g, io.github.mojolowjo.entropybot.aa.moves.FactChange.Fact fact) {
        return reprice(bb, g, fact, null);
    }

    /**
     * 0.39.z77 seen: as above, {@code at} the running step (null: not known). A seen source ({@link
     * io.github.mojolowjo.entropybot.aa.moves.FactChange#seen}) is a changed fact: it re-prices a running trip too, and the
     * pick is said once per vein to the owner - "seen iron at x y z: collect now (N s) vs finish &lt;step&gt; (M s)" ({@link
     * #seenLine}); while the walk to it is not priced yet, the answer waits (FactChange.SEEN_WAITS, logged only).
     */
    static io.github.mojolowjo.entropybot.aa.moves.Steps.Routine reprice(Blackboard bb, Goal g, io.github.mojolowjo.entropybot.aa.moves.FactChange.Fact fact, Step at) {
        boolean seen = io.github.mojolowjo.entropybot.aa.moves.FactChange.isSeen(fact);
        // 0.39.z77 answers (1) (manager: re-price only on a fact change - a failed step, a bag count off the plan): a landed
        // price never re-resolves the batch's running playbook; its legs go on as planned
        // (a playbook that ends in an explore is priced again as any explore: its find is the fact that picks the rest)
        // 0.39.z77 seen: a seen source IS a changed fact (owner 2026-10-26: the trigger is the fact itself)
        if (!seen && g.playbookRun && g.pricedWay && !g.pricedExplore) return new io.github.mojolowjo.entropybot.aa.moves.Steps.Routine(null, "kept: the trip runs on (re-planned only on a changed fact)");
        if (g.n - have(bb.in.bag, g) <= 0) return new io.github.mojolowjo.entropybot.aa.moves.Steps.Routine(null, "the order is met");
        BatchWay.forget(g);          // SHAPE CHANGE: a re-price asks the trip's walks again (a stand's estimates, a landed path)
        var ch = chain(bb, g);
        if (ch == null) return new io.github.mojolowjo.entropybot.aa.moves.Steps.Routine(null, "no facts for the chain yet (the senses are not ready)");
        if (!ch.ok() || ch.steps().isEmpty()) {
            if (seen) bb.port.log(g.r.key() + ": " + fact.text() + ": no way priced now - the running step goes on (chain: " + ch.why() + ")");     // rule 7: said
            return new io.github.mojolowjo.entropybot.aa.moves.Steps.Routine(null, ch.why());
        }
        // 0.39.z48 (S66, runs I/J: 12-24 "re-priced ... the pick changed" per run): the new way replaces the running pick
        // only when cheaper than what is left of it by replan.switchMarginS; else the running steps go on
        long now = bb.clock.now();
        double left = leftS(g, now);
        boolean newWay = !io.github.mojolowjo.entropybot.aa.moves.Steps.waitsOnPaths(ch.steps());
        if (seen && !newWay) {
            // 0.39.z77 seen: the walk to it is not priced yet - asked again on the next landed path (Steps), said then
            String w = io.github.mojolowjo.entropybot.aa.moves.FactChange.SEEN_WAITS + fact.text() + " (chain: " + ch.why() + ")";
            bb.port.log(g.r.key() + ": " + w);
            return new io.github.mojolowjo.entropybot.aa.moves.Steps.Routine(null, w);
        }
        // FH fix (run FH 17:46:00: the explore found a cave 10 m away, the chain stood on the lip walk's price, switches() kept
        // the explore, and a side walk never lands "known" from a moving bot - 14 min lost): a FOUND source's re-price that
        // stands on paths hands the stand to the run (Steps takes it for an until-explore, rule 22: waiting is standing); the
        // stand's end re-plans with the landed price as any first-answer wait
        if (io.github.mojolowjo.entropybot.aa.moves.FactChange.isFound(fact) && !newWay) {
            String w = FOUND_STANDS + "(chain: " + ch.why() + ")";
            bb.port.log(g.r.key() + ": " + fact.text() + " - " + w);
            return new io.github.mojolowjo.entropybot.aa.moves.Steps.Routine(ch.steps(), w, ch.uses());
        }
        boolean sw = switches(g.pricedWay, left, g.pricedExplore, newWay, ch.seconds(), ch.explore(), switchMarginS());
        if (seen) {
            String line = seenLine(fact, ch, left, at, sw);
            bb.port.log(g.r.key() + ": " + line + " (chain: " + ch.why() + ")");
            bb.port.tellOwner(line);
        }
        if (!sw) {
            // FH fix probe (rule 7): the chain kept out is said - the FH log could not show what the new way was
            String kept = "kept the running pick: the new way " + Math.round(ch.seconds()) + " s"
                    + (ch.explore() ? " (ends in an explore)" : "") + " vs " + (Double.isNaN(left) ? "?" : Math.round(left)) + " s left"
                    + (g.pricedExplore ? " (ends in an explore)" : "") + " (replan.switchMarginS " + Math.round(switchMarginS()) + ") (chain: " + ch.why() + ")";
            bb.port.log(g.r.key() + ": " + fact.text() + " - " + kept);
            return new io.github.mojolowjo.entropybot.aa.moves.Steps.Routine(null, kept);
        }
        took(bb, g, ch);
        bb.port.log(g.r.key() + ": " + fact.text() + " - priced again (chain: " + ch.why() + ")");
        return new io.github.mojolowjo.entropybot.aa.moves.Steps.Routine(ch.steps(), null, ch.uses());
    }

    /** FH fix: the start of a found source's re-price answer that stands on the source's walk (Steps takes the stand for an until-explore). */
    public static final String FOUND_STANDS = "the found source's walk is not priced yet: the run stands for it (rule 22) ";

    /** 0.39.z48: the seconds a re-price must save to switch the running pick (Tuning replan.switchMarginS). */
    public static double switchMarginS() { return Tuning.get("replan.switchMarginS"); }

    /**
     * 0.39.z65 item 2 (run V 07:13:11: one stone pickaxe and no spare; why only in aa debug): a taken chain's spare-tool
     * decisions, one info line per chain taken ("iron_pickaxe: spare pickaxes: stone_pickaxe: 0 uses left, ..."), said again
     * only when they differ from the last line said for this order. Nothing when the chain decided none.
     */
    static void took(Blackboard bb, Goal g, io.github.mojolowjo.entropybot.aa.NeedChain.Chain ch) {
        took(g, ch, bb.clock.now());
        if (g.playbookRun) BatchWay.announce(bb, g);       // SHAPE CHANGE: the whole trip said before the bot moves
        String s = sparesLine(g, ch);
        if (s != null) bb.port.log(s);
    }

    /** 0.39.z65 item 2 (pure): the line to say for a taken chain's spares, null: none, or the same as last said (then kept). */
    public static String sparesLine(Goal g, io.github.mojolowjo.entropybot.aa.NeedChain.Chain ch) {
        if (ch == null || ch.spares().isEmpty() || ch.spares().equals(g.sparesSaid)) return null;
        g.sparesSaid = ch.spares();
        return g.r.key() + ": spare pickaxes: " + ch.spares();
    }

    /** 0.39.z48: the order's pick was taken now at the chain's price. */
    static void took(Goal g, io.github.mojolowjo.entropybot.aa.NeedChain.Chain ch, long nowMs) {
        // 0.39.z77 J2: a playbook chain's steps are the playbook's (a failure of one holds it for the order, replan)
        g.playbookRun = ch.why() != null && ch.why().startsWith(io.github.mojolowjo.entropybot.aa.NeedChain.PLAYBOOK);
        if (g.playbookRun) {
            g.playbookSteps.addAll(ch.steps());
            var legs = BatchWay.legs(BatchWay.tripKey(g));
            for (Step s : ch.steps()) { String t = legs.get(s); if (t != null) g.legStarts.put(s, t); }
        }
        // 0.39.z73 (D2 3.4): the taken chain's per-step seconds, kept for a later repair's tail price
        if (ch.stepSeconds().size() == ch.steps().size()) for (int i = 0; i < ch.steps().size(); i++) g.stepSeconds.put(ch.steps().get(i), ch.stepSeconds().get(i));
        // 0.39.z49 (S67, run K: "vs 104 s left" for the explore while paths compute): that explore is not a way - no price
        g.pricedWay = !io.github.mojolowjo.entropybot.aa.moves.Steps.waitsOnPaths(ch.steps());
        g.pricedS = g.pricedWay ? ch.seconds() : Double.NaN;
        g.pricedAtMs = nowMs;
        g.pricedExplore = g.pricedWay && ch.explore();
    }

    /**
     * 0.39.z77 seen (pure): the owner's line for a seen source's re-price - "seen iron at 3 40 0: collect now (5 s) vs finish
     * to y 16 (priced path) (78 s) - collect now" ({@code sw}: the new pick runs) or "... - the step runs on (replan.switchMarginS
     * 10)". The new pick that does not take the seen vein says so: "collect now (not in the cheapest way: N s without it)". {@code left}
     * NaN: "?" (no running price). {@code at} null: "the running step".
     */
    public static String seenLine(io.github.mojolowjo.entropybot.aa.moves.FactChange.Fact fact, io.github.mojolowjo.entropybot.aa.NeedChain.Chain ch, double left, Step at, boolean sw) {
        int[] cell = io.github.mojolowjo.entropybot.aa.moves.FactChange.seenCell(fact);
        String fam = io.github.mojolowjo.entropybot.aa.moves.FactChange.seenFamily(fact);
        String where = cell == null ? fact.text() : "seen " + fam + " at " + cell[0] + " " + cell[1] + " " + cell[2];
        boolean uses = cell != null && io.github.mojolowjo.entropybot.aa.SeenAsk.takes(ch.steps(), cell);
        String now = uses ? "collect now (" + Math.round(ch.seconds()) + " s)" : "collect now (not in the cheapest way: " + Math.round(ch.seconds()) + " s without it)";
        String step = at == null ? "the running step" : at.text();
        return where + ": " + now + " vs finish " + step + " (" + (Double.isNaN(left) ? "?" : Math.round(left)) + " s) - "
                + (sw ? (uses ? "collect now" : "switched to " + (ch.steps().isEmpty() ? "nothing" : ch.steps().get(0).text())) : "the step runs on (replan.switchMarginS " + Math.round(switchMarginS()) + ")");
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
        // 0.39.z70 (R1 = c): the run parked on an honest loop: its facts are kept, one more try comes when one of them changes
        var a = g.loopAttempt;
        g.loopAttempt = null;
        String again = "";
        if (a != null && !g.loopRetried) {
            g.loopPark = new LoopPark(a, needCounts(a.needs() == null ? List.of() : a.needs().keySet(), bb.in == null ? null : bb.in.bag, bb.in == null ? 0 : bb.in.pickTier), sourceCells(bb, g, a));
            again = "; it tries once more by itself when a need in the bag rises, it moves " + Math.round(Failures.movedV()) + " blocks or a new source is seen";
        }
        String line = "order " + g.r.key() + " stopped: " + g.parked + " (aa want " + g.r.word().trim() + " to try again, aa drop " + g.r.key() + " to clear it" + again + ")";
        bb.port.log(line);
        bb.port.tellOwner(line);
        return true;
    }

    /**
     * 0.39.z77 met (run BR 12:04:02: "order iron_pickaxe stopped: stopped at step 20 (stand while paths compute ...) (re-plan:
     * the order is met)", 48 s after its "got"): the order's run ended failed. A met order has no stop - the carried batch run's
     * end is logged under the batch ("batch b1: the run ended: &lt;why&gt;; the order iron_pickaxe is met"), nothing is parked,
     * and the batch's next open order takes over as before (the met order's desire leaves when its run ends, AaRuntime's
     * carried set). A passing reason parks nothing; any other parks the order ({@link #park}). True: parked.
     */
    public static boolean runFailed(Blackboard bb, Goal g, String why) {
        if (passing(why)) return false;
        boolean met = g.met || (bb.in != null && bb.in.bag != null && have(bb.in.bag, g) >= g.n);
        if (!met) return park(bb, g, why);
        bb.port.log((g.batch != null ? "batch " + g.batch : "order " + g.r.key()) + ": the run ended: "
                + (why == null || why.isBlank() ? "no reason given" : why) + "; the order " + g.r.key() + " is met");
        return false;
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
        // 0.39.z77 answers (1): a met order whose desire runs its batch's playbook keeps its score until that run ends (no
        // interrupt by the batch's next order at a step boundary)
        if (have(bb.in.bag, g) >= g.n && !g.playbookRun) return -1;
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
            var ch = ownChain(bb, g);      // 0.39.z77 review 1 (R3): the waiting test, no batch comparison per tick
            if (ch != null && ch.ok() && !ch.steps().isEmpty() && ch.steps().get(0) instanceof Step.Explore)
                return "no new source; next explore in " + waitS + " s";
        }
        var wd = world(bb);
        boolean pathsPending = wd != null && wd.path() != null && wd.path().pending();
        if (bb.fact(LootBook.FACT) instanceof LootBook book && !book.ready() || pathsPending) {
            var ch = ownChain(bb, g);      // 0.39.z77 review 1 (R3): the waiting test, no batch comparison per tick
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
        took(bb, g, ch);           // 0.39.z48: the running pick's price (the re-price margin)
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
    /** 0.39.z73: the order's world of now - the planner's facts with the order's dropped sources and failed places applied; null: none yet. */
    static io.github.mojolowjo.entropybot.aa.NeedChain.World chainWorld(Blackboard bb, Goal g) { return chainWorld(bb, g, bb.in == null ? null : bb.in.pos); }

    /**
     * D11 (the anchor rule, review 2): the order's world with every hold read at {@code pos} - the live feet for the order's own
     * chain, the running step's END cell for a batch trip (BatchWay.trip), so a trip's key and its legs read the same holds.
     * The live hysteresis state is moved only by the live feet's read (WorldFacts.heldFor(bb, own)).
     */
    static io.github.mojolowjo.entropybot.aa.NeedChain.World chainWorld(Blackboard bb, Goal g, int[] pos) {
        var world = world(bb);
        if (world == null) return null;
        // 0.39.z78 strip-off (rule 7): the strip way off is said once per order (the World's strip facts answer none, StripWays)
        if (bb.port != null && StripWays.sayOnce(g.id() + " " + g.given)) bb.port.log(g.r.key() + ": " + StripWays.OFF);
        boolean live = bb.in == null || pos == bb.in.pos;
        var dropped = droppedSources(g, pos);
        // 0.39.z77 world (manager 2026-10-26; run CX 22:43:18, run CS 19:33-19:37): another order's source fact holds here too
        var facts = live ? WorldFacts.of(bb).heldFor(bb, droppedSources(g).keySet())
                : WorldFacts.of(bb).heldFor(droppedSources(g).keySet(), bb.in, pos, WorldFacts.fluid(bb));
        for (var e : facts.entrySet()) {
            if (dropped.putIfAbsent(e.getKey(), e.getValue()) != null || bb.port == null) continue;
            String line = WorldFacts.of(bb).sayOnce(g.id() + " " + g.given, g.r.key(), e.getKey(), e.getValue());
            if (line != null) bb.port.log(line);
        }
        // 0.39.z78 J5 slice 1: the stations held (StationHolds, the one store) - the chain prices without them and lists each
        for (var e : stationHolds(bb, pos).entrySet()) dropped.putIfAbsent(e.getKey(), e.getValue());
        // 0.39.z33 (z65: a station back once closer); 0.39.z67 item 1: a place-near that failed holds its spot (near it: a cell with room)
        return world.withoutSources(dropped).withPlaceFailures(dropped, pos);
    }

    /**
     * 0.39.z77 cave (run BW 14:24:10): the confirmed cave the planner offers for {@code word} on the world fact
     * ({@link io.github.mojolowjo.entropybot.aa.NeedChain#caveSource}) - none when an open order's world dropped it (its key
     * held: a mouth with no path), as a dropped seen source ends no explore. The chain's explore until {@code word} ends on it.
     * Null: no world facts yet, no cave, or dropped.
     */
    public static io.github.mojolowjo.entropybot.aa.NeedChain.Spot caveFor(Blackboard bb, String word) {
        var s = io.github.mojolowjo.entropybot.aa.NeedChain.caveSource(world(bb), word);
        if (s == null) return null;
        for (Goal g : of(bb)) if (io.github.mojolowjo.entropybot.aa.NeedChain.caveSource(chainWorld(bb, g), word) == null) return null;
        return s;
    }

    public static io.github.mojolowjo.entropybot.aa.NeedChain.Chain chain(Blackboard bb, Goal g) { return chain(bb, g, true); }

    /**
     * 0.39.z77 review 1 (R3): the order's OWN chain, no batch comparison - for the per-tick waiting tests (blocked: does the
     * chain wait on the loot book or a path?), which must never price the playbook and six chains every tick. 0.39.z77 trip:
     * a trip order's test asks the batch's trip as the waiting test - kept while the running step needs no walk (BatchWay.hold).
     */
    static io.github.mojolowjo.entropybot.aa.NeedChain.Chain ownChain(Blackboard bb, Goal g) { return chain(bb, g, false); }

    private static io.github.mojolowjo.entropybot.aa.NeedChain.Chain chain(Blackboard bb, Goal g, boolean batch) {
        if (g.strip != null) return null;
        var world = chainWorld(bb, g);
        if (world == null) return null;
        var laps = io.github.mojolowjo.entropybot.aa.core.TickLaps.AA;    // 0.39.z62 item 4: resolves counted while the probe is on
        // 0.39.z77 SHAPE CHANGE (owner 2026-10-26 10:30: playbook first): a plain-item order runs its trip (alone or with its
        // batch), kept per batch, bag and place (R3); the order's own chain only where no trip is derived (said) or for a group
        final var wf = world;
        java.util.function.Supplier<io.github.mojolowjo.entropybot.aa.NeedChain.Chain> own = () -> {
            long t0 = laps.now();
            var c = g.r.group() ? io.github.mojolowjo.entropybot.aa.NeedChain.resolveAll(g.r.key(), g.r.ids(), g.n, wf)
                    : io.github.mojolowjo.entropybot.aa.NeedChain.resolve(g.r.ids().isEmpty() ? g.r.key() : g.r.ids().get(0), g.n, wf);
            if (t0 != 0) {
                laps.resolved(laps.now() - t0);
                var st = io.github.mojolowjo.entropybot.aa.PlanNodes.last();      // 0.39.z78 D6 (run DT): this thread's node counts
                laps.resolvedNodes(st.nodes(), st.resolved(), st.reused());
            }
            return c;
        };
        // 0.39.z77 trip (run CU 21:02-21:06): the waiting test (batch false) reads the batch's kept trip while its running step
        // needs no walk (BatchWay.hold); the flag was unused since the SHAPE CHANGE, so every tick's test derived the trip
        // 0.39.z78 D6 (run DT): the one-leg trip's own chain is asked of the planner worker when one is installed (OwnAsk)
        var ask = new BatchWay.OwnAsk(g.r.group() ? g.r.key() : g.r.ids().isEmpty() ? g.r.key() : g.r.ids().get(0), g.r.ids(), g.n, g.r.group());
        var ch = BatchWay.tripOrder(g) ? BatchWay.trip(bb, g, world, own, !batch, ask) : own.get();
        // 0.39.z41: a source Baritone's complete search found unreachable is the z33 source fact: dropped for the order's life
        for (var e : ch.unreachable().entrySet()) {
            g.sources.failedFor(g.id(), e.getKey(), e.getValue(), bb.clock.now(), null, ORDER_LIFE_MS);
            WorldFacts.of(bb).learned(e.getKey(), e.getValue(), g.r.key(), bb.in);     // 0.39.z77 world: unreachable for every order
        }
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
        for (Goal g : out) g.met = true;         // 0.39.z77 met: a carried run's later end is logged under the batch, never a stop
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
