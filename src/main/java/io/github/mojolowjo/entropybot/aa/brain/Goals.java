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

        /** orders-tier (0.39.z9): why the order stopped (said once; aa want again or aa cancel clears it), null: it goes on. */
        public volatile String parked;

        /** helper (owner 2026-11-02): the deposit to the main chest was weighed for this order's end (done or parked), once. */
        public boolean depositWeighed;

        /** parked-holds (owner 2026-11-23): parked with a wake, the order holds its line's deposit, and that was said (LineDeposits.sweep). */
        public boolean depositHeld;

        /** deposit-words (run JBK-T1): what the weighing at a park noted for the line's deposit (item -> n); a wake takes it back. */
        public Map<String, Integer> depositTook;

        /**
         * Merge review 2026-11-23: the deposit this order was weighed for did not reach the chest (its run failed or was
         * refused, or no main chest was set): a wake takes nothing off its amount (LineDeposits.gone / reopen).
         */
        public boolean depositMissed;

        /**
         * short-wake (pilot w057, w061, 2026-11-25): this order's stop was weighed with nothing it waits on able to change,
         * and its line's deposit was owed without the rest of it (LineDeposits.sweep), or its end joined the rest of its line
         * still due (LineDeposits.lineEnds, its own items or none). A wake then takes only this order
         * out of the due deposit, the rest stays due (LineDeposits.reopen); its amount follows with a deposit of its own.
         * Cleared wherever {@link #depositWeighed} is set again.
         */
        public boolean depositOwedShort;

        /** deposit-words (owner 2026-11-08): how many of the item go into the main chest when its line ends (the amount ordered); 0: none. */
        public int deposit;

        /** deposit-words: the aa want line this order came on (its batch, or an id of its own); the line deposits once, after its last order ends. */
        public String line;

        /** The owner's strip order (aa want strip ...): its one step; null for an item goal. */
        public Step.Strip strip;

        /**
         * deposit-goal plan, stage 3 (piece 4): the line's deposit as a goal ({@link Goals#addDeposit}) - its chain is
         * NeedChain.deposit, met when nothing is owed (LineDeposits.owed, read through {@link #owedNone}).
         */
        public boolean depositGoal;
        /** Review 4 (point 1): the deposit goal is parked on the main chest's hold (MainChestHold); it runs again when that lifts. */
        public boolean chestHeld;
        /** Review 4: what a partial put of the line's deposit moved into the chest for this order (LineDeposits.putEnded); never owed again. */
        public final Map<String, Integer> depositPut = new java.util.TreeMap<>();

        /** deposit-goal: true when the one owed store (LineDeposits.owed on the goal's board) owes nothing; null on every other goal. */
        java.util.function.BooleanSupplier owedNone;

        /** deposit-goal: LineDeposits.putsReached when this deposit goal was added (more at its end: the put reached the chest). */
        public int depositPutsAtStart;

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
        /**
         * stop-wake (to-do/stop-wake-plan.md, the owner's yes 2026-11-21): what a park that is not an honest loop waits on
         * ({@link Park}); null: not parked, or parked by the honest loop ({@link #loopPark}).
         */
        public Park park;
        /** stop-wake: the "priced again, stays stopped" words last said for this park (said again only when they differ). */
        public String wakeSaid;
        /** stop-wake: the look-around leg (every order parked on the surface) was taken since this order last ran on a changed fact. */
        public boolean looked;
        /** stop-wake: the order's next run is the look-around leg (one explore step, Baritone's explore), set by {@link Goals#parkedTick}. */
        public boolean lookAround;
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

        /** wait-not-park: what the order waited on when a run of it ended ({@link Goals#runFailed}); null: nothing. Cleared when it lands. */
        public String waitEnded;
        /** wait-not-park: when (the board clock's ms) the wait in {@link #waitEnded} was first noted; the bound goals.waitEndedMaxS counts from it. */
        public long waitEndedAtMs;
    }

    // ---- 0.39.z77 climb (run CM 17:43:10-17:48:31: every order parked under ground, 5 min idle, no line said why) ----

    /** The fact holding the all-parked line last said (said again only when it changes). */
    static final String ALL_PARKED_SAID = "aa.allParkedSaid";
    /** stop-wake: the fact holding when (the clock's ms) every order was first seen parked; null: not all parked. */
    static final String ALL_PARKED_SINCE = "aa.allParkedSince";

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

    /**
     * parked-holds (owner 2026-11-23; pure, the one fact for "this park can still wake"): the order is parked and keeps
     * facts it waits on - the honest loop's ({@link Goal#loopPark}) or the park's own ({@link Goal#park}); {@link #park}
     * gives every park one of them except the one only the owner's word changes (another dimension). False: not parked, or
     * stopped for good. The once-only climb try of a park with no facts is no wake here: it comes only with every order
     * parked under ground.
     */
    public static boolean canWake(Goal g) { return g.parked != null && (g.loopPark != null || g.park != null); }

    /**
     * hold-bound (owner 2026-11-24, plan step 2; worlds run 3 T2 w055, w070, w073, T1 w006, w018: "the line's deposit waits
     * for it - it can still wake", then nothing to the end): {@link #canWake} says a park keeps facts it waits on, not that
     * any of them can change. Pure: what can still change one of the facts {@code g}'s park waits on, as words; null:
     * nothing (the line's deposit is owed). Read per park kind from what moves those facts: every park waits on the feet
     * (distance), the bag, a held way lifting (by distance) or a new source seen, and none of them waits on time (a wait on
     * an answer is no park: {@link #retryWaits}, bounded by goals.waitEndedMaxS). Those facts change while
     * {@code busy} (a run or another job moves the bot), while another order is open and not parked (it runs, or waits on
     * an answer that starts it), and by the two moves {@link #parkedTick} takes with every order parked: under ground the
     * one more try on the climb way (not taken yet), on the surface the one look-around leg of a PLACE park (not taken yet).
     */
    public static String stillChanges(List<Goal> goals, Goal g, boolean underGround, boolean busy, boolean worldClimb) {
        if (g == null || g.parked == null || !canWake(g)) return null;
        if (busy) return "a run or another job moves the bot now";
        if (goals != null)
            // deposit-goal: the deposit goal is no order; its run moves the bot and is counted by {@code busy}
            for (Goal o : goals) if (o != g && o.parked == null && !o.depositGoal) return "order " + o.r.key() + " is open and not stopped";
        if (underGround && climbTryDue(g, worldClimb))
            return "its one more try on the climb way, taken with every order stopped under ground";
        if (!underGround && lookAroundDue(g))
            return "its one look-around leg, taken after goals.lookAroundAfterS with every order stopped";
        return null;
    }

    /** The one more try on the climb way is still to come for {@code g} (read by {@link #parkedTick} and {@link #stillChanges}); {@code byWorld}: a world fact of a failed climb holds for it. */
    static boolean climbTryDue(Goal g, boolean byWorld) {
        return !g.climbRetried && (g.loopPark != null || g.park != null || climbHeld(g) || byWorld);
    }

    /** The one look-around leg is still to come for {@code g} (read by {@link #parkedTick} and {@link #stillChanges}). */
    static boolean lookAroundDue(Goal g) {
        // deposit-goal: an explore is no way for a deposit (rule 22)
        return !g.looked && g.strip == null && !g.depositGoal && g.park != null && g.park.kind() == ParkKind.PLACE;
    }

    /** {@link #wake(Goal)}; {@code worldClimb}: another order's failed climb holds here for it (a world fact, run EJ). */
    static String wake(Goal g, boolean worldClimb) {
        if (g.loopPark != null)
            return "wakes when a need in the bag rises, it moves " + Math.round(Failures.movedV()) + " blocks or a new source is seen";
        String climb = !g.climbRetried && (climbHeld(g) || worldClimb) ? "tries once more on the climb way when every order is parked under ground" : null;
        if (g.park != null) return (climb == null ? "" : climb + "; ") + g.park.wakeWords();
        if (climb != null) return climb;
        if (g.depositGoal) return "no wake by itself (room in the main chest, or a new aa deposit x y z)";
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
            // deposit-goal (open question 5): the deposit's own part, not an order's
            parts.add((g.depositGoal ? "the line's deposit" : g.r.key()) + ": " + why + " - " + wake(g, worldClimb.test(g)));
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
    public static List<String> parkedTick(Blackboard bb, boolean underGround) { return parkedTick(bb, underGround, null); }

    /** job-lookup: {@link #parkedTick} whose climb retry also forgets the brain's hold on the order's desire (re-armed at once). */
    public static List<String> parkedTick(Blackboard bb, boolean underGround, Failures brainHolds) { return parkedTick(bb, underGround, brainHolds, false); }

    /** stop-wake: {@code busy} - a run or another job holds the bot now (the look-around's wait starts again). */
    public static List<String> parkedTick(Blackboard bb, boolean underGround, Failures brainHolds, boolean busy) {
        List<String> out = new ArrayList<>();
        if (!(bb.fact(FACT) instanceof List<?>)) return out;
        List<Goal> goals = of(bb);
        // 0.39.z78 run EJ (rule 7): a failed climb is a world fact - the orders it holds for (not their own) wait on it too
        java.util.Set<Goal> byWorld = new java.util.HashSet<>();
        for (Goal g : goals) if (worldClimb(bb, g)) byWorld.add(g);
        if (underGround && allParked(goals) != null) {
            for (Goal g : goals) {
                if (!climbTryDue(g, byWorld.contains(g))) continue;
                g.climbRetried = true;
                g.sources.forget(g.id(), io.github.mojolowjo.entropybot.aa.NeedChain.CLIMB_KEY);
                WorldFacts.of(bb).forget(io.github.mojolowjo.entropybot.aa.NeedChain.CLIMB_KEY);   // the one more try: for every order
                String line = "order " + g.r.key() + " tries once more on the climb way: every order is parked and the bot is "
                        + (bb.in == null ? "?" : String.valueOf(bb.in.underDepth)) + " blocks under ground, where nothing it waits on can change (it parked on: " + g.parked + ")";
                g.parked = null;
                g.loopPark = null;
                g.park = null;
                g.tries.clear();                 // the try asks its questions afresh (as the R1 try)
                if (brainHolds != null) brainHolds.forget(g.id(), null);
                bb.port.log(line);
                bb.port.tellOwner(line);
                out.add(line);
            }
        }
        String all = allParked(goals, g -> !g.climbRetried && worldClimb(bb, g));
        if (all == null) { bb.fact(ALL_PARKED_SAID, null); bb.fact(ALL_PARKED_SINCE, null); return out; }
        // stop-wake (the owner's yes 2026-11-21: "as long as Baritone does the moving"): every order parked and
        // nothing that moves the bot - no fact a park waits on can change while it stands. One order at a time takes ONE
        // look-around leg (the explore step: Baritone's explore), then it is priced where that ends; an order that looked
        // looks again only after it ran on a changed fact. Starts once the state lasted goals.lookAroundAfterS (a deposit
        // walk that follows the last park moves the bot by itself)
        long nowMs = bb.clock == null ? 0 : bb.clock.now();
        if (!(bb.fact(ALL_PARKED_SINCE) instanceof Long)) bb.fact(ALL_PARKED_SINCE, nowMs);
        long since = (Long) bb.fact(ALL_PARKED_SINCE);
        // review 2026-11-21: the owner's yes was for the SURFACE and a park on the PLACE - under ground the explore step
        // refuses by rule 22 (tester runs 20, 22, 23) and the climb way's try above is the answer there; the time counts
        // only while nothing else runs (a deposit walk or another job longer than the wait must not be taken over)
        if (busy) { bb.fact(ALL_PARKED_SINCE, nowMs); since = nowMs; }
        if (!underGround && nowMs - since >= (long) (Tuning.get("goals.lookAroundAfterS") * 1000)) {       // structural: s -> ms
            for (Goal g : goals) {
                if (!lookAroundDue(g)) continue;
                g.looked = true;
                g.lookAround = true;
                g.wakeSaid = null;
                String line = "order " + g.r.key() + " looks around: every order is stopped and nothing they wait on changes while I stand here; one explore leg (Baritone's explore), then it is priced again (it stopped on: " + g.parked + ")";
                g.parked = null;
                g.loopPark = null;
                g.park = null;
                g.tries.clear();
                if (brainHolds != null) brainHolds.forget(g.id(), null);
                bb.port.log(line);
                bb.port.tellOwner(line);
                out.add(line);
                bb.fact(ALL_PARKED_SAID, null);
                bb.fact(ALL_PARKED_SINCE, null);
                return out;
            }
        }
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
        if (g.playbookRun && g.n - have(free(bb, g), g) <= 0) {
            for (Goal o : BatchWay.open(bb, g)) if (o != g) return chain(bb, o);
        }
        return chain(bb, g);
    }

    /** The end of the other-dimension reason ({@link #blocked}): only the owner's word changes it, so a park on it keeps no wake. */
    static final String OTHER_DIM = "; I don't travel there yet";

    /** stop-wake: what a park waits on - PLACE: where the bot stands (the default when the cause is not told); BAG: an item is missing. */
    public enum ParkKind { PLACE, BAG }

    /**
     * stop-wake (to-do/stop-wake-plan.md): the facts an order parked on, read when it parked - the feet's cell (null: not
     * known), the bag's item total (-1: not known), the cells of the known sources of its item (null: not known) and the keys
     * held for it then (its own dropped sources and the world facts). One check ({@link #parkChange}) asks whether any changed.
     */
    public record Park(ParkKind kind, int[] cell, int items, java.util.Set<String> sources, java.util.Set<String> held) {
        public Park {
            cell = cell == null ? null : cell.clone();
            sources = sources == null ? null : java.util.Set.copyOf(sources);
            held = held == null ? java.util.Set.of() : java.util.Set.copyOf(held);
        }

        static Park of(ParkKind kind, Blackboard bb, java.util.Set<String> sources, java.util.Set<String> held) {
            int[] pos = bb.in == null || bb.in.pos == null || bb.in.pos.length < 3 ? null : bb.in.pos;     // structural: x y z
            return new Park(kind, pos, bb.in == null ? -1 : Failures.items(bb.in), sources, held);
        }

        /** The words of what wakes it (the park line, aa goals, the all-parked line). */
        public String wakeWords() {
            long far = Math.round(Failures.movedV() + WorldFacts.MARGIN);
            return "priced again by itself when I am " + far + " blocks from " + (cell == null ? "here" : cell[0] + " " + cell[1] + " " + cell[2])
                    + ", a way held here lifts or a new source is seen" + (kind == ParkKind.BAG ? ", or the bag grows" : "");
        }
    }

    /**
     * stop-wake (pure): what changed since the park, the words for the log; null: nothing (it stays parked). The feet more
     * than failures.moved + the band from the park's cell; a key held then no longer held; a source cell not known then; for
     * a BAG park the bag's total risen. Rule 15: an unknown part (no feet, no sources, no total) is no change.
     */
    public static String parkChange(Park p, int itemsNow, int[] feetNow, java.util.Set<String> sourcesNow, java.util.Set<String> heldNow) {
        if (p == null) return null;
        if (p.kind() == ParkKind.BAG && p.items() >= 0 && itemsNow > p.items()) return "the bag grew from " + p.items() + " to " + itemsNow + " items";
        if (p.cell() != null && feetNow != null && feetNow.length >= 3) {      // structural: x y z
            double d = Failures.moved(feetNow, p.cell());
            if (d >= Failures.movedV() + WorldFacts.MARGIN) return "moved " + Math.round(d) + " blocks from where it stopped (" + p.cell()[0] + " " + p.cell()[1] + " " + p.cell()[2] + ")";
        }
        if (heldNow != null)
            for (String k : new java.util.TreeSet<>(p.held())) if (!heldNow.contains(k)) return "no longer held: " + k;
        if (p.sources() != null && sourcesNow != null)
            for (String s : new java.util.TreeSet<>(sourcesNow)) if (!p.sources().contains(s)) return "a new source seen: " + s;
        return null;
    }

    private static final java.util.regex.Pattern TRIED = java.util.regex.Pattern.compile("^stopped at step \\d+ \\((.+?)\\): ");

    /** stop-wake: the text of the step a run stopped at ("stopped at step N (<step>): ..."), null: the reason names none. Pure. */
    static String triedStep(String parked) {
        if (parked == null) return null;
        var m = TRIED.matcher(parked);
        return m.find() ? m.group(1) : null;
    }
    /** stop-wake: the keys held for the order now - its own dropped sources (as the chain reads them from the feet) and the world facts. */
    static java.util.Set<String> heldKeys(Blackboard bb, Goal g) {
        java.util.Set<String> out = new java.util.TreeSet<>(droppedSources(g, bb.in == null ? null : bb.in.pos).keySet());
        if (bb.in != null) out.addAll(WorldFacts.of(bb).heldFor(bb, droppedSources(g).keySet()).keySet());
        out.addAll(stationHolds(bb).keySet());           // review 2026-11-21: the chain prices without these too (chainWorld)
        return out;
    }

    /**
     * 0.39.z70 (owner's ruling R1 = c on D2): what an order parked by the honest loop parked on - the try ({@code at}), the
     * bag counts of its needs then ({@code counts}, as {@link #needCounts} reads the sensed bag), and the cells of the known
     * sources of the order's item and the step's needs ({@code sources}; null: not known then, rule 15).
     */
    public record LoopPark(io.github.mojolowjo.entropybot.aa.moves.Steps.Attempt at, Map<String, Integer> counts, java.util.Set<String> sources, int[] cell) {
        public LoopPark {
            counts = Map.copyOf(counts);
            sources = sources == null ? null : java.util.Set.copyOf(sources);
            cell = cell == null ? null : cell.clone();
        }

        /** The park's own cell not known ({@code cell} null): the move is measured from the try's start, as before stop-wake. */
        public LoopPark(io.github.mojolowjo.entropybot.aa.moves.Steps.Attempt at, Map<String, Integer> counts, java.util.Set<String> sources) { this(at, counts, sources, null); }
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
        // review 2026-11-21 (the try's START cell made a step that travelled 16 blocks before it failed wake at once, every
        // time): the move is measured from the cell the bot PARKED on when that is known, the try's start else
        List<Integer> f = p.at() == null ? null : p.at().feet();
        int[] from = p.cell() != null && p.cell().length >= 3 ? p.cell() : f != null && f.size() == 3 ? new int[]{f.get(0), f.get(1), f.get(2)} : null;     // structural: x y z
        if (from != null && feetNow != null && feetNow.length == 3) {      // structural: x y z
            double d = Failures.moved(feetNow, from);
            if (d >= Failures.movedV()) return "moved " + Math.round(d) + " blocks from where it parked (" + from[0] + " " + from[1] + " " + from[2] + ", failures.moved " + Math.round(Failures.movedV()) + ")";
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
        if (g.depositGoal) return new java.util.TreeSet<>();        // deposit-goal: no item of its own to see a source of
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
    public static List<String> retryLoopParks(Blackboard bb) { return retryLoopParks(bb, null); }

    /**
     * job-lookup (run JAY 07:27:01-07:28:56: "tries once more: a new source seen" was followed by two minutes of "no order of
     * the owner's"): the failed run that parked the order also put the brain's hold on its desire (Failures.failed,
     * failures.cdBaseMs 120 s), so the unparked order could not be picked until it lapsed. The one more try re-arms the order
     * at once: the brain's hold on its desire is forgotten with the park ({@code brainHolds} null: none to clear).
     */
    public static List<String> retryLoopParks(Blackboard bb, Failures brainHolds) {
        List<String> out = new ArrayList<>();
        if (!(bb.fact(FACT) instanceof List<?>)) return out;
        for (Goal g : of(bb)) {
            if (g.parked == null || (g.loopPark == null && g.park == null)) continue;
            int[] pos = bb.in == null ? null : bb.in.pos;
            Map<String, Integer> bag = bb.in == null ? null : bb.in.bag;
            // stop-wake: one check for every park, no once-per-order flag - the honest loop's facts (R1) or the park's own
            String change = g.loopPark != null
                    ? loopChange(g.loopPark, needCounts(g.loopPark.counts().keySet(), bag, bb.in == null ? 0 : bb.in.pickTier), pos, sourceCells(bb, g, g.loopPark.at()))
                    : parkChange(g.park, bb.in == null ? -1 : Failures.items(bb.in), pos, sourceCells(bb, g, null), heldKeys(bb, g));
            if (change == null) continue;
            // a wake is a PRICE, not a run: the order runs again only when its chain stands where the bot is now (or still
            // waits on an answer: not known is no verdict, rule 15); else it stays stopped on a fresh snapshot, so the same
            // change is never asked twice and no run starts without a way (no spin)
            // a strip or a group order has no kept chain to price here (a group's would be an uncached resolve on the
            // client thread, review 2026-11-21): it runs and its own start decides
            var ch = g.strip != null || g.r.group() ? null : ownChain(bb, g);
            // the planner still thinks (its stand, no verdict yet): asked again at the next pass, the change still pending
            if (ch != null && !ch.steps().isEmpty() && ch.steps().get(0) instanceof Step.AwaitPaths aw && aw.planner()) continue;
            boolean stands = ch == null || ch.waiting() || (ch.ok() && !ch.steps().isEmpty());
            String why = stands ? null : ch.why();
            // review 2026-11-21: the same first step from the place it stopped at is the try that stopped it, not a way
            // (a new source seen a moment after a stop with no fact ran the same chain again); a grown bag may change it
            String tried = triedStep(g.parked);
            int[] cell = g.park != null ? g.park.cell() : g.loopPark != null ? g.loopPark.cell() : null;
            boolean bagChange = change.contains("in the bag rose") || change.startsWith("the bag grew");
            if (stands && ch != null && !ch.waiting() && !bagChange && tried != null && tried.equals(ch.steps().get(0).text())
                    && cell != null && pos != null && pos.length >= 3 && Failures.moved(pos, cell) < Failures.movedV()) {      // structural: x y z
                stands = false;
                why = "the way from here starts with the step that stopped it (" + tried + ")";
            }
            if (!stands) {
                // the cells known so far stay known: the live senses name the nearest source only, and a nearest that
                // flips between two cells is never "new" twice (review 2026-11-21)
                java.util.Set<String> was = g.park != null ? g.park.sources() : g.loopPark != null ? g.loopPark.sources() : null;
                java.util.Set<String> seenNow = sourceCells(bb, g, null);
                java.util.Set<String> known = was == null && seenNow == null ? null : new java.util.TreeSet<>();
                if (was != null) known.addAll(was);
                if (seenNow != null) known.addAll(seenNow);
                g.loopPark = null;
                g.park = Park.of(Failures.Cause.of(g.parked) == Failures.Cause.NO_ITEM ? ParkKind.BAG : ParkKind.PLACE, bb, known, heldKeys(bb, g));
                // every re-price is said in the log (rule 7; it follows a real change, so never per tick); the whisper only
                // when the reason is a new one
                String stay = "order " + g.r.key() + " priced again (" + change + "): no way from here either (" + why + "); it stays stopped - " + g.park.wakeWords();
                bb.port.log(stay);
                if (!java.util.Objects.equals(why, g.wakeSaid)) {
                    g.wakeSaid = why;
                    bb.port.tellOwner(stay);
                }
                out.add(stay);
                continue;
            }
            String line = "order " + g.r.key() + " runs again: " + change + " (it stopped on: " + g.parked + ")";
            g.parked = null;
            g.loopPark = null;
            g.park = null;
            g.wakeSaid = null;
            g.looked = false;
            g.climbRetried = false;
            g.tries.clear();                 // 0.39.z76 sb4 review 2: the one more try asks its questions afresh
            if (brainHolds != null) brainHolds.forget(g.id(), null);   // job-lookup: re-armed at once, not after the brain's hold
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
                // review 4 (point 1): a put refused by a full or gone chest is the held fact before the re-plan reads the chest
                if (g.depositGoal && failed instanceof Step.Deposit) chestRefused(bb, why);
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
        // tester runs 10, 11: the dig down's place hold lifts the same way (past failures.moved + the band from where it was learned)
        return key != null && (key.startsWith(io.github.mojolowjo.entropybot.aa.NeedChain.STRIP_HEADING) || key.equals(io.github.mojolowjo.entropybot.aa.NeedChain.DIG_DOWN_KEY));
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
        // job-handover (run JBB 08:37:57: the tunnel ended on cave air, the re-price "kept the running pick" - the tunnel way,
        // its step ended - and the order stopped): with {@code left} the step has ENDED (Steps' failOrReplan); its pick is
        // never kept, the new way is taken (rule 7: an order stops only when nothing is left)
        if (fact.price()) return reprice(bb, g, fact, failed, left != null);
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
        if (g.n - have(free(bb, g), g) <= 0 && !trip) return new io.github.mojolowjo.entropybot.aa.moves.Steps.Routine(null, "the order is met");
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
        // job-review-leftovers (1): a place-near that stalled away from the asked cell holds the stall cell too, in the same store
        String stall = sourceFact ? io.github.mojolowjo.entropybot.aa.moves.FactChange.stallPlaceKey(fact) : null;
        if (stall != null && !stall.equals(src)) g.sources.failedFor(g.id(), stall, fact.text(), bb.clock.now(), positional(stall) || headingHold(stall) ? bb.in : null, ORDER_LIFE_MS);
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
            // job-own-aside-worker (worlds run 4, w060 / w065: "craft oak_planks 4 - missing 1 oak_log" repaired with the same
            // craft twice, its log the order's own held -d one): the gap prices without the order's own and its trip's held
            // -d amounts (ownKeptHeld, the runner's keptFromUse answer), so it never crafts from what the runner holds; the
            // gap's own item stays counted (a gap for the held item itself asks only what is missing, as its step counts it)
            Map<String, Integer> held = new java.util.HashMap<>(ownKeptHeld(bb, g, null));
            held.remove(Thing.path(sp.item()));
            var w = heldOff(chainWorld(bb, g), held);
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
        return reprice(bb, g, fact, at, false);
    }

    /** job-handover: {@code ended} - the running pick's step has ended: "kept the running pick" never applies, the new way runs. */
    static io.github.mojolowjo.entropybot.aa.moves.Steps.Routine reprice(Blackboard bb, Goal g, io.github.mojolowjo.entropybot.aa.moves.FactChange.Fact fact, Step at, boolean ended) {
        boolean seen = io.github.mojolowjo.entropybot.aa.moves.FactChange.isSeen(fact);
        // 0.39.z77 answers (1) (manager: re-price only on a fact change - a failed step, a bag count off the plan): a landed
        // price never re-resolves the batch's running playbook; its legs go on as planned
        // (a playbook that ends in an explore is priced again as any explore: its find is the fact that picks the rest)
        // 0.39.z77 seen: a seen source IS a changed fact (owner 2026-10-26: the trigger is the fact itself)
        if (!seen && !ended && g.playbookRun && g.pricedWay && !g.pricedExplore) return new io.github.mojolowjo.entropybot.aa.moves.Steps.Routine(null, "kept: the trip runs on (re-planned only on a changed fact)");
        if (g.n - have(free(bb, g), g) <= 0) return new io.github.mojolowjo.entropybot.aa.moves.Steps.Routine(null, "the order is met");
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
        if (seen && !newWay && !ended) {
            // 0.39.z77 seen: the walk to it is not priced yet - asked again on the next landed path (Steps), said then
            String w = io.github.mojolowjo.entropybot.aa.moves.FactChange.SEEN_WAITS + fact.text() + " (chain: " + ch.why() + ")";
            bb.port.log(g.r.key() + ": " + w);
            return new io.github.mojolowjo.entropybot.aa.moves.Steps.Routine(null, w);
        }
        // FH fix (run FH 17:46:00: the explore found a cave 10 m away, the chain stood on the lip walk's price, switches() kept
        // the explore, and a side walk never lands "known" from a moving bot - 14 min lost): a FOUND source's re-price that
        // stands on paths hands the stand to the run (Steps takes it for an until-explore, rule 22: waiting is standing); the
        // stand's end re-plans with the landed price as any first-answer wait
        // (review: the words are true for the until-explore alone - Steps takes the stand for it only; any other running step
        // is kept below, said with the chain)
        if (io.github.mojolowjo.entropybot.aa.moves.FactChange.isFound(fact) && !newWay && at instanceof Step.Explore ex && ex.until() != null) {
            // FM fix (run FM: every iron order "stopped at step N (stand ...)" within 2 min - the stand was handed over without
            // took(), so pricedWay stayed the explore's and the next re-price "kept" a null-steps answer that ended the stand
            // and failed the run): the stand IS the order's pick now, as the first-answer stand always was
            took(bb, g, ch);
            String w = FOUND_STANDS + "(chain: " + ch.why() + ")";
            bb.port.log(g.r.key() + ": " + fact.text() + " - " + w);
            return new io.github.mojolowjo.entropybot.aa.moves.Steps.Routine(ch.steps(), w, ch.uses());
        }
        boolean sw = ended || switches(g.pricedWay, left, g.pricedExplore, newWay, ch.seconds(), ch.explore(), switchMarginS());
        if (ended) bb.port.log(g.r.key() + ": " + ENDED_TAKES + (at == null ? "?" : at.text()) + ": the new way taken, " + Math.round(ch.seconds()) + " s"
                + (ch.explore() ? " (ends in an explore)" : "") + " (chain: " + ch.why() + ")");
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

    /** job-handover: the line's start when the running pick's step ended (its pick is not kept). */
    public static final String ENDED_TAKES = "the running pick's step ended: ";

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

    /** deposit-goal: the deposit goal's key (its goal id is "goal.deposit"). */
    public static final String DEPOSIT_KEY = "deposit";

    /**
     * Review 4 (point 1): a step's or a run's words that hold the main chest's refusal (full, gone) become the one hold
     * ({@link MainChestHold}), said once (rule 7). No store on the board, or no refusal in the words: nothing.
     */
    public static void chestRefused(Blackboard bb, String words) {
        if (!(bb.fact(MainChestHold.FACT) instanceof MainChestHold h)) return;
        String line = h.refused(words);
        if (line == null) return;
        bb.port.log(line);
    }

    /**
     * deposit-goal plan, stage 3 (piece 4; owner 2026-11-23: the line's deposit is a planned, priced goal): the line's
     * deposit as a goal, built like {@link #addStrip} - key "deposit", n 1, no ids, deposit 0, line null, so no
     * LineDeposits weighing or keeping counts it. Met when LineDeposits.owed is empty ({@link #have}).
     */
    public static Goal addDeposit(Blackboard bb, long now) {
        Thing.Result r = new Thing.Result(DEPOSIT_KEY, DEPOSIT_KEY, false, List.of(), List.of(), 0, null, "the line's deposit to the main chest", null);
        Goal g = new Goal(r, 1, 0, now);
        g.depositGoal = true;
        g.owedNone = () -> owed(bb).isEmpty();
        of(bb).removeIf(x -> x.depositGoal);
        of(bb).add(g);
        return g;
    }

    /** deposit-goal: the deposit goal among the goals, null: none. */
    public static Goal depositGoal(Blackboard bb) {
        if (!(bb.fact(FACT) instanceof List<?>)) return null;
        for (Goal g : of(bb)) if (g.depositGoal) return g;
        return null;
    }

    /** deposit-goal: what the deposit owes now (LineDeposits.owed, the one store; full id -> n); empty: nothing, or no store. */
    static Map<String, Integer> owed(Blackboard bb) {
        return bb.fact(LineDeposits.FACT) instanceof LineDeposits ld ? ld.owed() : Map.of();
    }

    /** deposit-goal: the orders behind {@link #owed} (LineDeposits.owedOrders); empty: none, or no store. */
    static java.util.Set<Goal> owedOrders(Blackboard bb) {
        return bb.fact(LineDeposits.FACT) instanceof LineDeposits ld ? ld.owedOrders() : java.util.Set.of();
    }

    /**
     * orders-tier (0.39.z9): the order cannot progress: parked with its reason, said ONCE in the log and the whisper; the
     * brain never starts it again (it is infeasible while parked). {@code aa want} again makes a fresh goal; {@code aa cancel}
     * removes it. False when it was already parked.
     */
    public static boolean park(Blackboard bb, Goal g, String why) {
        if (g.parked != null) return false;
        g.parked = why == null || why.isBlank() ? "no reason given" : why;
        // 0.39.z70 (R1 = c): the run parked on an honest loop: its facts are kept, one more try comes when one of them changes
        var a = g.loopAttempt;
        g.loopAttempt = null;
        String again = "";
        g.park = null;
        g.wakeSaid = null;
        // merge review 2026-11-23 (an order parked by another order's trip or by the no-way park kept its ended wait and
        // its look-around flag; after its wake the pass said "runs again: what it waited on landed (<old reason>)" for
        // nothing, or its next run was a bare explore leg): a parked order waits on its park's facts only. Every park
        // comes through here, so the un-parks need no second clearing
        g.waitEnded = null;
        g.waitEndedAtMs = 0;
        g.lookAround = false;
        if (a != null) {
            g.loopPark = new LoopPark(a, needCounts(a.needs() == null ? List.of() : a.needs().keySet(), bb.in == null ? null : bb.in.bag, bb.in == null ? 0 : bb.in.pickTier), sourceCells(bb, g, a), bb.in == null ? null : bb.in.pos);
            again = "; it tries once more by itself when a need in the bag rises, it moves " + Math.round(Failures.movedV()) + " blocks or a new source is seen";
        } else if (g.parked.endsWith(OTHER_DIM)) {
            g.loopPark = null;               // stop-wake: only the owner's word changes it - no wake, said as before
        } else if (g.depositGoal && MainChest.of(bb) instanceof MainChest.Held held) {
            // review 4 (point 1): the main chest is full or gone - a fact that holds while it is true (MainChestHold), not a
            // place park: no move wakes it; OrderEnds.depositSync runs it again when the hold lifts
            g.loopPark = null;
            g.chestHeld = true;
            again = "; held: " + held.why();
        } else {
            // stop-wake: every other park keeps what it waits on (its kind by the reason's cause; not told: the place)
            g.loopPark = null;
            g.park = Park.of(Failures.Cause.of(g.parked) == Failures.Cause.NO_ITEM ? ParkKind.BAG : ParkKind.PLACE, bb, sourceCells(bb, g, null), heldKeys(bb, g));
            again = "; " + g.park.wakeWords();
        }
        String line = g.depositGoal
                ? "the line's deposit stopped: " + g.parked + " (the items stay kept and owed: " + Step.Deposit.onlyText(owed(bb)) + again + ")"
                : "order " + g.r.key() + " stopped: " + g.parked + " (aa want " + g.r.word().trim() + " to try again, aa cancel " + g.r.key() + " to clear it" + again + ")";
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
        if (g.depositGoal) chestRefused(bb, why);      // review 4 (point 1): a full or gone chest is a held fact first
        if (passing(why)) return false;
        boolean met = g.met || (bb.in != null && bb.in.bag != null && have(free(bb, g), g) >= g.n);
        if (!met) {
            // wait-not-park (audit A1; runs JBM-T1 21:42:11, JBN-T1 22:32:25: the stand's 30 s ended while "the mining data is
            // not loaded yet", the order parked for good 21 s before the data landed): an order whose chain waits on an answer
            // still to come (the loot book, the mining data, a path, a source not loaded: Chain.waiting) is not stopped by a
            // run's end - not known yet is no verdict (rule 15). It is held with that reason (blocked) and priced again when
            // the answer lands (retryWaits); said once here (rule 7)
            // merge review 2026-11-23: narrowed - only a run that ended ON the wait (waitEnd); a real failure parks as before
            String waits = waitsOn(bb, g);
            if (waitEnd(g.loopAttempt != null, why, waits)) {
                long now = bb.clock == null ? 0 : bb.clock.now();
                if (g.waitEnded == null) g.waitEndedAtMs = now;
                String past = waitedOut(bb, g, now);
                if (past != null) return park(bb, g, (why == null || why.isBlank() ? "no reason given" : why) + past);
                g.waitEnded = waits;
                bb.port.log("order " + g.r.key() + ": its run ended (" + (why == null || why.isBlank() ? "no reason given" : why) + ") while it waits on: " + waits
                        + " - not stopped; it runs again when that lands (rule 15)");
                return false;
            }
            return park(bb, g, why);
        }
        bb.port.log((g.batch != null ? "batch " + g.batch : "order " + g.r.key()) + ": the run ended: "
                + (why == null || why.isBlank() ? "no reason given" : why) + "; the order " + g.r.key() + " is met");
        return false;
    }

    /** wait-not-park: what the order's own chain waits on now (an answer still to come, Chain.waiting), null: nothing, or no chain. */
    static String waitsOn(Blackboard bb, Goal g) {
        if (g.strip != null || bb.in == null) return null;
        var ch = ownChain(bb, g);
        return ch != null && ch.waiting() ? ch.missing() : null;
    }

    /**
     * wait-not-park, narrowed (merge review 2026-11-23; pure): a failed run's end is a wait, not a stop, only when the run
     * ended ON the wait - the failed step is the stand itself (the reason names {@link Step.AwaitPaths#TEXT}, the one
     * source of the stand's words) or the reason carries what the chain waits on ({@code waits}: the re-plan's own
     * answer). A step that failed for a reason of its own (stuck, timed out) parks as before even when the chain priced
     * after it waits on an answer, and so does a run the honest loop ended ({@code loopTry}: its try is kept by the park
     * as the facts it wakes on) - the wait rule swallowed both (the review's finding).
     */
    public static boolean waitEnd(boolean loopTry, String why, String waits) {
        if (waits == null || loopTry || why == null) return false;
        return why.contains(Step.AwaitPaths.TEXT) || why.contains(waits);
    }

    /** wait-not-park: seconds an order whose run ended on a wait may stay neither stopped nor held before it is stopped (Tuning goals.waitEndedMaxS). */
    static double waitEndedMaxS() { return Tuning.get("goals.waitEndedMaxS"); }

    /**
     * wait-not-park, bounded (merge review 2026-11-23: an order waiting on an answer that {@link #blocked} does not hold it
     * for - a source's structure fact not loaded - was neither stopped nor held, so its line never ended and the orders
     * were never "all parked"): the words for the stop when the wait has lasted goals.waitEndedMaxS since it was first
     * noted and nothing holds the order now; null: not yet, or it is held with a said reason ({@link #blocked}: the loot
     * book, the paths, the mining data - those land by themselves and the hold says so in aa goals).
     */
    static String waitedOut(Blackboard bb, Goal g, long nowMs) {
        double maxS = waitEndedMaxS();
        if (nowMs - g.waitEndedAtMs < (long) (maxS * 1000)) return null;         // structural: s -> ms
        if (blocked(bb, g) != null) return null;
        return " (it waited " + Math.round((nowMs - g.waitEndedAtMs) / 1000.0) + " s for an answer that did not land, the most is " + Math.round(maxS)      // structural: ms -> s
                + " s: stopped here, priced again when the place changes)";
    }

    /**
     * wait-not-park: every order whose run ended while it waited on an answer ({@link #runFailed}) and whose chain waits no
     * more runs again at once - the brain's hold on its desire from that run's end is forgotten ({@code brainHolds} null: none
     * to clear), said in the log. Keyed on the fact, no count: it may wait and land again. An order still waiting past
     * goals.waitEndedMaxS that nothing holds is stopped ({@link #waitedOut}; the park says it once). The lines said.
     */
    public static List<String> retryWaits(Blackboard bb, Failures brainHolds) {
        List<String> out = new ArrayList<>();
        if (!(bb.fact(FACT) instanceof List<?>)) return out;
        for (Goal g : new ArrayList<>(of(bb))) {
            if (g.waitEnded == null || g.parked != null) continue;
            String waits = waitsOn(bb, g);
            if (waits != null) {
                String past = waitedOut(bb, g, bb.clock == null ? 0 : bb.clock.now());
                if (past != null && park(bb, g, "waits on: " + waits + past)) out.add("order " + g.r.key() + " stopped: " + g.parked);
                continue;
            }
            String line = "order " + g.r.key() + " runs again: what it waited on landed (" + g.waitEnded + ")";
            g.waitEnded = null;
            g.waitEndedAtMs = 0;
            if (brainHolds != null) brainHolds.forget(g.id(), null);
            bb.port.log(line);
            out.add(line);
        }
        return out;
    }

    /** orders-tier: a failure that says nothing about the order itself (the slot busy, no facts yet): no park. */
    public static boolean passing(String why) {
        if (why == null) return false;
        String w = why.toLowerCase(Locale.ROOT);
        return w.startsWith("busy") || w.equals("nothing to run");
    }

    /**
     * {@code aa want} words: the thing (several words joined), n (0 = default), priority -1/0/+1; deposit-words (owner
     * 2026-11-08): {@code deposit} = the item is put into the main chest when its line ends (-d, -deposit, or a trailing deposit).
     */
    public record Args(String word, int n, int prio, String err, boolean deposit) {
        public Args(String word, int n, int prio, String err) { this(word, n, prio, err, false); }
    }

    /** deposit-words: the words that flag an order's item for the deposit at the line's end. */
    public static boolean depositWord(String t) { return t.equals("-d") || t.equals("-deposit") || t.equals("deposit"); }

    /** deposit-words: the old one-shot "aa want deposit" is gone; its error names the forms that do it now. */
    public static final String WANT_DEPOSIT_GONE = "aa want deposit is gone: aa deposit <item> <n>, ... puts exactly those away now; -d on an order (aa want cobblestone 32 -d) or a trailing deposit puts the order's items away when the line ends";

    public static Args parse(String arg) {
        List<String> words = new ArrayList<>();
        int n = 0, prio = 0;
        boolean deposit = false;
        for (String t : (arg == null ? "" : arg.trim().toLowerCase(Locale.ROOT)).split("\\s+")) {
            if (t.isEmpty()) continue;
            if (t.equals("high")) prio = 1;
            else if (t.equals("low")) prio = -1;
            else if (depositWord(t)) deposit = true;
            else if (t.matches("\\d{1,5}")) n = Math.max(-1, Integer.parseInt(t) == 0 ? -1 : Integer.parseInt(t));
            else words.add(t);
        }
        if (words.isEmpty() && deposit) return new Args(null, 0, 0, WANT_DEPOSIT_GONE);
        if (words.isEmpty()) return new Args(null, 0, 0, "usage: aa want <thing> [n] [-d] [high|low] (e.g. aa want iron_ore 8; -d: put it in the main chest when the line ends)");
        if (n < 0 || n > Tuning.i("goals.maxN")) return new Args(null, 0, 0, "error: n must be 1..4096");
        return new Args(String.join("_", words), n, prio, null, deposit);
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
        if (g.depositGoal) return g.owedNone != null && g.owedNone.getAsBoolean() ? 1 : 0;     // deposit-goal: met when nothing is owed
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
        if (have(free(bb, g), g) >= g.n && !g.playbookRun) return -1;
        return Math.max(1, Personality.of(bb).get("ownerBase") + g.prio * high());
    }

    /** Why it cannot go on now (another dimension, a diamond pickaxe), else null: parked, said in aa goals (Q6). */
    public static String blocked(Blackboard bb, Goal g) {
        if (g.parked != null) return g.parked;
        Thing.Where w = g.r.where();
        if (w != null && w.dim() != null && !w.dim().equals(bb.in.dimension))
            return g.r.key() + " is only in " + Thing.dimName(w.dim()) + OTHER_DIM;
        // loot-book-bg: while the book is read in the background, an order whose chain waits on it is held (not parked) with
        // "waiting for the loot book (N of M)"; once the book is published the chain is priced again on the next pick
        // 0.39.z41: the same while a walk's full path is computed in the background (the chain waits on "path not known yet")
        // 0.39.z47 (run H, S60): a chain's explore that ended with no new source does not start again within explore.retryS
        long waitS = exploreWaitS(exploreBareAtMs, GameClock.ms(), exploreRetryS());
        if (waitS > 0) {
            var ch = ownChain(bb, g);      // 0.39.z77 review 1 (R3): the waiting test, no batch comparison per tick
            if (ch != null && ch.ok() && !ch.steps().isEmpty() && ch.steps().get(0) instanceof Step.Explore)
                return "no new source; next explore in " + waitS + " s";
        }
        var wd = world(bb);
        boolean pathsPending = wd != null && wd.path() != null && wd.path().pending();
        // wait-not-park (audit A1): the same while the mining data is not loaded (the game's tags read a moment after the world)
        if (bb.fact(LootBook.FACT) instanceof LootBook book && !book.ready() || pathsPending || !MiningTiers.ready()) {
            var ch = ownChain(bb, g);      // 0.39.z77 review 1 (R3): the waiting test, no batch comparison per tick
            if (ch != null && ch.waiting()) return ch.missing();
        }
        return null;
    }

    /** 0.39.z47: when (GameClock ms) the last chain explore ended with no new source; 0: none yet. */
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
        if (g.n - have(free(bb, g), g) <= 0) return null;
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
     * the owner took some); at 0 the entry goes. Cleared by {@code aa cancel} and by a new {@code aa want} of the same thing.
     */
    public static final class Finished {
        public final String key;
        public final List<String> ids;
        public final boolean group;
        public int n;
        /** Merge review 2026-11-23: the order this entry was made from (null: not known) - its kept -d amount is these same items ({@link #orderReserves}). */
        public final Goal order;

        public Finished(String key, List<String> ids, int n, boolean group) { this(key, ids, n, group, null); }

        public Finished(String key, List<String> ids, int n, boolean group, Goal order) {
            this.key = key;
            this.ids = List.copyOf(ids);
            this.n = n;
            this.group = group;
            this.order = order;
        }
    }

    @SuppressWarnings("unchecked")
    public static List<Finished> finished(Blackboard bb) {
        if (bb.fact(FINISHED_FACT) instanceof List<?> l) return (List<Finished>) l;
        List<Finished> l = new ArrayList<>();
        bb.fact(FINISHED_FACT, l);
        return l;
    }

    /** reserves-once review: copies of the finished entries, for a reader that must not lower the store's own (null: empty). */
    public static List<Finished> copies(List<Finished> finished) {
        List<Finished> out = new ArrayList<>();
        if (finished != null) for (Finished f : finished) out.add(new Finished(f.key, f.ids, f.n, f.group, f.order));
        return out;
    }

    /** A met order joins the finished list (a strip order holds no items; the same key replaces the older entry). */
    static void finish(Blackboard bb, Goal g) {
        if (g.strip != null || g.r.ids().isEmpty()) return;
        List<Finished> f = finished(bb);
        f.removeIf(x -> x.key.equals(g.r.key()));
        f.add(new Finished(g.r.key(), g.r.ids(), g.n, g.r.group(), g));
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

    /**
     * deposit-kept, placing (the cloud game of 2026-11-15, owner's yes 2026-11-16: 32 cobblestone -d in the bag, its order
     * neither running nor finished, 23 of them placed on the climb for the next order): as above, plus what the bag holds
     * for the -d orders' deposits ({@code kept}: {@link #keptPer}, the one store - per order, counted id -> n). They stay
     * usable as ingredients as every order reserve does (ruling 1 option c): only placing is held off.
     *
     * <p>Merge review 2026-11-23: the fork sums the reserves' units (a max-flow over them), so two reserves of one item
     * are two amounts. A kept amount was left out whenever ANY reserve above named its item, whatever its units: with
     * cobblestone 32 -d kept and a later order of cobblestone 10 running or finished, only 10 were held and 32 of the 42
     * could be placed. Now each order's kept amount is reserved less what the SAME order's own reserve above holds (the
     * running order's target, or the finished entry made from it: the same items of the bag); another order's reserve of
     * the item takes nothing off. The members are the order's full ids ({@link LineDeposits#fullId}): a modded item was
     * reserved as minecraft:&lt;path&gt;.
     */
    public static List<baritone.api.process.Reserves.Reserve<String>> orderReserves(Goal running, List<Finished> finished, Map<String, Integer> bag, List<LineDeposits.Kept> kept) {
        List<baritone.api.process.Reserves.Reserve<String>> out = orderReserves(running, finished, bag);
        if (kept == null) return out;
        // what each order's own reserve above holds, used up as its kept entries are counted (an order may be listed twice)
        Map<Goal, Integer> own = new java.util.IdentityHashMap<>();
        if (running != null && running.strip == null && !running.r.ids().isEmpty()) own.put(running, running.n);
        if (finished != null)
            for (Finished f : finished)
                if (f.order != null && (running == null || !f.key.equals(running.r.key()))) own.merge(f.order, f.n, Integer::sum);
        for (LineDeposits.Kept k : kept) {
            Goal o = k.order();
            String name = o.r.key() + " (kept for a deposit)";
            if (o.r.group()) {             // a group's reserves are per member: each member's kept less the order's own per member
                for (var e : k.items().entrySet()) {
                    int units = e.getValue() - own.getOrDefault(o, 0);
                    if (units > 0) out.add(io.github.mojolowjo.entropybot.aa.moves.Reservations.order(name, List.of(LineDeposits.fullId(o, e.getKey())), units));
                }
                own.remove(o);
                continue;
            }
            int units = 0;
            List<String> ids = new ArrayList<>();
            for (var e : k.items().entrySet()) {
                if (e.getValue() <= 0) continue;
                units += e.getValue();
                ids.add(LineDeposits.fullId(o, e.getKey()));
            }
            int held = Math.min(units, own.getOrDefault(o, 0));
            if (held > 0) own.merge(o, -held, Integer::sum);
            if (units - held > 0) out.add(io.github.mojolowjo.entropybot.aa.moves.Reservations.order(name, ids, units - held));
        }
        return out;
    }

    /** deposit-kept: the last "kept for the deposit" line said (said again only on a change, rule 7). */
    static final String KEPT_SAID_FACT = "aa.depositKeptSaid";

    /** deposit-kept, placing: {@link LineDeposits#keptPer} for every order (nothing left out); empty: nothing kept, or the bag is not read. */
    public static List<LineDeposits.Kept> keptPer(Blackboard bb) {
        if (bb.in == null || bb.in.bag == null || !(bb.fact(LineDeposits.FACT) instanceof LineDeposits ld)) return List.of();
        return ld.keptPer(bb.in.bag, of(bb), null, null);
    }

    /** deposit-kept: what the bag holds for the -d orders' deposits, less {@code self}'s and {@code skip}'s ({@link LineDeposits#kept}); empty: nothing. */
    public static Map<String, Integer> kept(Blackboard bb, Goal self, java.util.Collection<Goal> skip) {
        if (bb.in == null || bb.in.bag == null || !(bb.fact(LineDeposits.FACT) instanceof LineDeposits ld)) return Map.of();
        return ld.kept(bb.in.bag, of(bb), self, skip);
    }

    /**
     * deposit-kept (owner 2026-11-13: "the items that are tagged with -d should be considered separate" - with cobblestone
     * 32 -d waiting for its deposit, a new order of cobblestone 10 gathers 10 more): the bag every order counts itself on
     * ({@link #have}) - the sensed bag less what it holds for ANOTHER order's deposit ({@code g}'s own amount counts for
     * it: a met order carried by its batch's run stays met). Nothing kept: the sensed bag itself.
     */
    public static Map<String, Integer> free(Blackboard bb, Goal g) {
        Map<String, Integer> kept = kept(bb, g, null);
        if (kept.isEmpty()) return bb.in.bag;
        Map<String, Integer> out = new java.util.HashMap<>(bb.in.bag);
        for (var e : kept.entrySet()) out.merge(e.getKey(), -e.getValue(), Integer::sum);
        return out;
    }

    /**
     * deposit-kept: what the bag holds for the deposits of the orders that are not {@code g} and not its trip's open orders
     * (BatchWay.open) - the one answer the planner ({@link #keptOff}) and the running steps (Steps.Hands.freeBag) take off.
     */
    /**
     * deposit-kept, steps: what a run may NOT use up from the bag - {@code orderRun} false: nothing, the bag as it is (the
     * line's own deposit run, which puts the kept amounts away, and the brain's task that is no order's); true with
     * {@code desireId}: an order's run; true with a null {@code desireId} (merge review 2026-11-23): any other run - an
     * owner one-shot, a job - from which every kept amount is held (the owner's rule: a -d amount reaches the chest
     * whole). The caller says which (AaRuntime.keptFromOthers). An order's run whose goal is no longer
     * among the open ones is a CARRIED run (the order was met inside its batch's trip and left the goals; the run goes on
     * for the batch's next order): every kept amount is held from it. Cloud tester run 43 (2026-11-22, the owner's line):
     * oak_log was met at 06:20:51, its carried run then made the torch's furnace from 8 of the 32 kept cobblestone and
     * the charcoal from a kept log - the chest got 24 and 15. (Was: no goal found = "no order's run" = nothing kept.)
     */
    public static Map<String, Integer> keptForRun(Blackboard bb, String desireId, boolean orderRun) { return keptForRun(bb, desireId, orderRun, false); }

    /**
     * reserves-once (b), one "free to consume" bag (run 3 w064 and the "done with less" games: a furnace craft took 8 of
     * the running order's own 32 cobblestone -d, a smelt burned a kept log as fuel): {@code consume} true asks what an
     * order's run may not USE UP as a craft's input or a smelt's input or fuel - every kept -d amount, the running order's
     * own and its trip's included (the owner's rule: a -d amount reaches the chest whole). False: what its steps may not
     * COUNT as theirs (the skip check, the dry run, a repair): the others' amounts only, an order counts its own. Not an
     * order's run: nothing, as before.
     */
    public static Map<String, Integer> keptForRun(Blackboard bb, String desireId, boolean orderRun, boolean consume) {
        return keptForRun(bb, desireId, orderRun, consume, null);
    }

    /**
     * job-review-leftovers (item 4): as above, {@code startBag} the bag at the task's first start - the runner's one store of
     * it (AaRuntime.typedStartBag, the live bag; the simulator's own), read here instead of a second note on the board.
     * Null: not known (a run started without one), the order's own -d amounts are held whole, said once (rule 7).
     */
    public static Map<String, Integer> keptForRun(Blackboard bb, String desireId, boolean orderRun, boolean consume, Map<String, Integer> startBag) {
        if (!orderRun) return Map.of();
        // deposit-goal (stage 3): the deposit goal's run uses up no kept amount (a pickaxe craft never eats owed items); its
        // steps count the owed amounts as theirs (the put's free bag holds them)
        if (desireId != null && bb.fact(FACT) instanceof List<?>)
            for (Goal g : of(bb)) if (g.depositGoal && g.id().equals(desireId)) return consume ? kept(bb, null, null) : kept(bb, null, owedOrders(bb));
        if (consume && desireId != null && bb.fact(FACT) instanceof List<?>)
            for (Goal g : of(bb)) if (g.id().equals(desireId)) return keptFromUse(bb, g, startBag);
        if (consume) return kept(bb, null, null);
        if (desireId != null && bb.fact(FACT) instanceof List<?>)
            for (Goal g : of(bb)) if (g.id().equals(desireId)) return keptFromOthers(bb, g);
        return kept(bb, null, null);
    }

    static final String OWN_WHOLE_SAID_FACT = "aa.keptOwnWholeSaid";

    /**
     * reserves-once (b): what an order's run may not use up - the others' kept amounts whole, and of its own and its trip's
     * ({@link #ownKeptHeld}) what the bag held at the run's start (never more than it holds now). The planner sets the same
     * amounts aside for every chain of the order ({@link #chain}: a trip's in NeedChain.playbookChain, a plain or group
     * order's by {@link #ownAside}), so the runner never refuses what the planner priced.
     */
    private static Map<String, Integer> keptFromUse(Blackboard bb, Goal g, Map<String, Integer> startBag) {
        Map<String, Integer> out = new java.util.HashMap<>(keptFromOthers(bb, g));
        if (startBag == null && bb.port != null && !g.id().equals(bb.fact(OWN_WHOLE_SAID_FACT))) {
            bb.fact(OWN_WHOLE_SAID_FACT, g.id());
            bb.port.log(g.r.key() + ": the bag at the run's start is not known; its own -d amounts are held whole from its crafts and smelts");
        }
        for (var e : ownKeptHeld(bb, g, startBag).entrySet()) out.merge(e.getKey(), e.getValue(), Integer::sum);
        return out;
    }

    /**
     * job-review-leftovers (item 4): of {@code g}'s own and its trip's -d amounts, what the bag holds now and held at
     * {@code atStart} too (the smaller; null {@code atStart}: now only). The one answer the runner (keptFromUse) and the
     * planner ({@link #ownAside}) read. By item path.
     */
    static Map<String, Integer> ownKeptHeld(Blackboard bb, Goal g, Map<String, Integer> atStart) {
        Map<String, Integer> out = new java.util.HashMap<>();
        Map<String, Integer> others = keptFromOthers(bb, g);
        Map<String, Integer> start = null;
        if (atStart != null && bb.fact(LineDeposits.FACT) instanceof LineDeposits ld) {
            // review: the start bag is keyed by full id (liveBag, the sim's runStartBag), the -d counts by path (bb.in.bag)
            Map<String, Integer> byPath = new java.util.HashMap<>();
            for (var e : atStart.entrySet()) if (e.getValue() != null) byPath.merge(Thing.path(e.getKey()), e.getValue(), Integer::sum);
            start = new java.util.HashMap<>(ld.kept(byPath, of(bb), null, null));
            for (var e : ld.kept(byPath, of(bb), g, BatchWay.open(bb, g)).entrySet()) start.merge(e.getKey(), -e.getValue(), Integer::sum);
        }
        for (var e : kept(bb, null, null).entrySet()) {
            int own = e.getValue() - others.getOrDefault(e.getKey(), 0);
            if (start != null) own = Math.min(own, start.getOrDefault(e.getKey(), 0));
            if (own > 0) out.put(e.getKey(), own);
        }
        return out;
    }

    /**
     * job-review-leftovers (item 4: the trip set the held part of its -d targets aside, a plain or a group order's own chain
     * did not, so the runner refused a furnace the planner had priced from the order's own kept cobblestone): an order's own
     * chain prices with what the bag holds of its own -d amount ({@link #ownKeptHeld}) taken off the bag and off the amount
     * asked - the trip's rule (NeedChain.playbookChain heldKept) for every chain: no inner get uses it up, and the order
     * still gathers only the rest. {@code ids}: the order's items ({@code key} when none). {@code bag} and {@code held} are
     * lowered in place; returns the count taken off. Pure.
     */
    static int ownAside(Map<String, Integer> bag, Map<String, Integer> held, List<String> ids, String key) {
        int n = 0;
        java.util.Set<String> paths = new java.util.HashSet<>();
        if (ids.isEmpty()) paths.add(Thing.path(key)); else for (String id : ids) paths.add(Thing.path(id));
        for (var e : bag.entrySet()) {
            String p = Thing.path(e.getKey());
            if (!paths.contains(p)) continue;
            int h = Math.min(e.getValue() == null ? 0 : e.getValue(), held.getOrDefault(p, 0));
            if (h <= 0) continue;
            e.setValue(e.getValue() - h);
            held.merge(p, -h, Integer::sum);
            n += h;
        }
        return n;
    }

    /**
     * review sent back (item 4, group orders): how much the order's ask drops for what {@link #ownAside} took off the bag
     * ({@code taken}, by bag key). A plain order: all of it. A group order: its n is per member (NeedChain.resolveAll gets
     * n of each), so the ask drops by the fewest taken of any member; a member with more held gathers a little extra,
     * never short. Pure.
     */
    static int askAside(Map<String, Integer> taken, List<String> ids, String key, boolean group) {
        Map<String, Integer> byPath = new java.util.HashMap<>();
        for (var e : taken.entrySet()) byPath.merge(Thing.path(e.getKey()), e.getValue(), Integer::sum);
        if (!group || ids.isEmpty()) { int t = 0; for (int v : byPath.values()) t += v; return t; }
        int min = Integer.MAX_VALUE;
        for (String id : ids) min = Math.min(min, byPath.getOrDefault(Thing.path(id), 0));
        return min;
    }

    /**
     * review sent back again (item 1): the order's own ask. What {@link #ownAside} takes off the bag ALWAYS rides in the ask
     * (the runner's keptFromUse holds every member's own -d amount, so no inner get may use it, even when one member holds
     * none); only the asked count drops, by {@link #askAside}, to 0 at the least (all held: met, nothing gathered). Pure.
     */
    static BatchWay.OwnAsk ownAsk(Map<String, Integer> bag, Map<String, Integer> held, List<String> ids, String key, boolean group, int n) {
        var ownBag = new java.util.LinkedHashMap<>(bag);
        ownAside(ownBag, new java.util.HashMap<>(held), ids, key);
        Map<String, Integer> taken = new java.util.HashMap<>();
        for (var e : bag.entrySet()) {
            int d = (e.getValue() == null ? 0 : e.getValue()) - ownBag.getOrDefault(e.getKey(), 0);
            if (d > 0) taken.put(e.getKey(), d);
        }
        int aside = askAside(taken, ids, key, group);
        // review sent back (2): all of n held aside is a met ask (0: the chain gets nothing), never the whole n again
        int askN = Math.max(0, n - aside);
        return new BatchWay.OwnAsk(group ? key : ids.isEmpty() ? key : ids.get(0), ids, askN, group, taken);
    }

    /**
     * job-own-aside-worker (worlds run 4, w060 / w065): {@code w} with every held -d amount of {@code held} (by item path,
     * {@link #ownKeptHeld}) off its bag - {@link #ownAside} over all the held words, not the order's own items only (a
     * repair's gap is for an ingredient, and every one of these amounts is refused by the runner's craft bag). Pure.
     */
    static io.github.mojolowjo.entropybot.aa.NeedChain.World heldOff(io.github.mojolowjo.entropybot.aa.NeedChain.World w, Map<String, Integer> held) {
        if (w == null || held == null || held.isEmpty()) return w;
        var bag = new java.util.LinkedHashMap<>(w.bag());
        ownAside(bag, new java.util.HashMap<>(held), new java.util.ArrayList<>(held.keySet()), null);
        return w.withBag(bag);
    }

    public static Map<String, Integer> keptFromOthers(Blackboard bb, Goal g) {
        return kept(bb, g, g == null ? null : BatchWay.open(bb, g));
    }

    /**
     * deposit-kept (owner 2026-11-13): the planner's world for {@code g} with the other -d orders' amounts taken off its bag
     * - an order gathers its own, the -d amounts go to the chest whole. Not taken off: {@code g}'s own and its trip's open
     * orders' (BatchWay.open: the trip obtains them and sets them aside itself). An order with no deposit word keeps
     * nothing: its items stay ingredients (ruling 1 option c, as before). The items held are said in the log on a change.
     */
    static io.github.mojolowjo.entropybot.aa.NeedChain.World keptOff(Blackboard bb, io.github.mojolowjo.entropybot.aa.NeedChain.World w, Goal g) {
        if (w == null) return w;
        String said = String.join(", ", kept(bb, null, null).keySet());
        if (!said.equals(bb.fact(KEPT_SAID_FACT) instanceof String s ? s : "")) {
            bb.fact(KEPT_SAID_FACT, said);
            if (bb.port != null) bb.port.log(said.isEmpty() ? "kept for a deposit: nothing now" : "kept for a deposit: " + said + " (the -d amounts) - no other order counts them or uses them as ingredients");
        }
        // reserves-once (run 3 w070): the planner's placing reserves come from this same store, with what is taken off the bag here left out
        java.util.Set<Goal> notOff = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        if (g != null) { notOff.add(g); notOff.addAll(BatchWay.open(bb, g)); }
        // deposit-goal stage 4, review 3 (point 2: the chain priced a pickaxe or a lend from the owed amounts, which the
        // runner's craft bag refuses - keptForRun with consume holds every kept amount from the deposit's run): the deposit
        // goal has no amount of its own, so every kept amount, the owed ones included, is off its bag and out of its
        // reserves as for any order (keptFromOthers). Its chain never crafts, places or lends what is owed; the put alone
        // uses it (Step.Deposit carries the owed counts, its free bag counts them: keptForRun without consume)
        Map<String, Integer> kept = keptFromOthers(bb, g);
        var reserves = bb.in == null || bb.in.bag == null ? List.<baritone.api.process.Reserves.Reserve<String>>of()
                : plannerReserves(g, finished(bb), bb.in.bag, keptPer(bb), notOff);
        if (kept.isEmpty()) return w.withReserves(reserves);
        Map<String, Integer> bag = new java.util.LinkedHashMap<>(w.bag());
        for (var e : bag.entrySet()) {
            int k = kept.getOrDefault(Thing.path(e.getKey()), 0);
            if (k > 0) e.setValue(Math.max(0, e.getValue() - k));
        }
        return w.withBag(bag).withReserves(reserves);
    }

    /**
     * reserves-once (run 3 w070, plan step 1 answer 2: bag mangrove_log 18 and cobblestone 35, other orders' -d amounts 16
     * and 32, the planner said "0 spare" of 5): what the PLANNER may not place on a way back up, for {@code g}, read from
     * the one store of kept amounts ({@link #orderReserves} over {@link #keptPer}) and never from the lease's pushed ledger.
     * The kept amounts of the orders not in {@code notOff} are already off the planner's bag ({@link #keptOff}); so their
     * kept entries are left out and each finished entry of such an order is lowered by its kept amount (the same items).
     * What is left: {@code g}'s own target, its own and its trip's kept amounts, and the finished orders' items that no
     * deposit keeps. Pure; {@code finished} is copied (never lowered here).
     */
    public static List<baritone.api.process.Reserves.Reserve<String>> plannerReserves(Goal g, List<Finished> finished, Map<String, Integer> bag,
                                                                                    List<LineDeposits.Kept> kept, java.util.Collection<Goal> notOff) {
        java.util.Set<Goal> stay = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        if (notOff != null) stay.addAll(notOff);
        Map<Goal, Map<String, Integer>> off = new java.util.IdentityHashMap<>();
        List<LineDeposits.Kept> keep = new ArrayList<>();
        if (kept != null) for (LineDeposits.Kept k : kept) {
            if (stay.contains(k.order())) keep.add(k);
            else for (var e : k.items().entrySet()) off.computeIfAbsent(k.order(), o -> new java.util.HashMap<>()).merge(e.getKey(), e.getValue(), Integer::sum);
        }
        List<Finished> copies = new ArrayList<>();
        if (finished != null) for (Finished f : finished) {
            int n = f.n;
            Map<String, Integer> o = f.order == null ? null : off.get(f.order);
            if (o != null) {
                int held = f.group ? Integer.MAX_VALUE : 0, offN = f.group ? Integer.MAX_VALUE : 0;
                for (String id : f.ids) {
                    int h = bag.getOrDefault(Thing.path(id), 0), k = o.getOrDefault(Thing.path(id), 0);
                    if (f.group) { held = Math.min(held, h); offN = Math.min(offN, k); } else { held += h; offN += k; }
                }
                n = Math.max(0, Math.min(n, held) - offN);
            }
            copies.add(new Finished(f.key, f.ids, n, f.group, f.order));
        }
        return orderReserves(g, copies, bag, keep);
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
        var world = keptOff(bb, world(bb), g);
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
        // deposit-goal plan, stage 3 (piece 4): the deposit goal's chain - its ways priced by NeedChain.deposit from the feet
        if (g.depositGoal) return io.github.mojolowjo.entropybot.aa.NeedChain.deposit(owed(bb), MainChest.of(bb), world);
        var laps = io.github.mojolowjo.entropybot.aa.core.TickLaps.AA;    // 0.39.z62 item 4: resolves counted while the probe is on
        // 0.39.z77 SHAPE CHANGE (owner 2026-10-26 10:30: playbook first): a plain-item order runs its trip (alone or with its
        // batch), kept per batch, bag and place (R3); the order's own chain only where no trip is derived (said) or for a group
        // job-review-leftovers (item 4): the order's own -d amount the bag holds is set aside here as the trip sets it aside
        // (one answer, ownAside), so the runner's keptFromUse never refuses what this chain priced
        // review (worker path): the aside rides in the ask, so the planner worker (BatchWay.own, port.own) prices the same
        // world and count as the tick's own supplier: one answer whoever resolves
        var ask = ownAsk(world.bag(), ownKeptHeld(bb, g, null), g.r.ids(), g.r.key(), g.r.group(), g.n);
        java.util.function.Supplier<io.github.mojolowjo.entropybot.aa.NeedChain.Chain> own = () -> {
            long t0 = laps.now();
            var c = ask.resolve(world);
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
            if (coal && have(free(bb, g), g) < g.n) return new double[]{score(bb, g), g.n};
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
        // deposit-goal: never listed (no "got 1 deposit"); OrderEnds.depositSync takes it off when nothing is owed
        for (Goal g : of(bb)) if (!g.depositGoal && have(free(bb, g), g) >= g.n) out.add(g);
        of(bb).removeAll(out);
        for (Goal g : out) g.met = true;         // 0.39.z77 met: a carried run's later end is logged under the batch, never a stop
        for (Goal g : out) finish(bb, g);        // 0.39.z61 (ruling 1 option c): its items stay safe from pillaring
        return out;
    }

    /**
     * lost-after-done (tester-2 w064; LineDeposits#lostAfterDone): a met order is among the goals again - not met, off the
     * finished list (its items are no longer there to keep), its "have" read from the bag like any open order's. The same
     * key already among the goals (a new aa want of it) wins: false, nothing changed.
     */
    public static boolean reopen(Blackboard bb, Goal g) {
        if (find(bb, g.r.key()) != null) return false;
        g.met = false;
        g.playbookRun = false;
        finished(bb).removeIf(x -> x.key.equals(g.r.key()));
        of(bb).add(g);
        return true;
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
        if (g.depositGoal) {
            var mc = MainChest.of(bb);
            return "deposit: " + Step.Deposit.onlyText(owed(bb)) + " to the main chest" + (mc.at() == null ? "" : " at " + mc.at()) + ": " + scoreText
                    + (b != null ? (g.parked == null ? " - held: " : " - parked: ") + b : ch != null ? " - chain: " + ch.why() : " - no facts for the chain yet");
        }
        return g.r.key() + " " + have(free(bb, g), g) + "/" + g.n + (g.prio > 0 ? " high" : g.prio < 0 ? " low" : "") + (g.deposit > 0 ? " (deposit)" : "") + ": " + scoreText
                + (b != null ? (g.parked == null ? " - held: " : " - parked: ") + b + (g.depositHeld && g.parked != null ? " (the line's deposit waits for it: it can still wake)" : "") :ch != null ? " - chain: " + ch.why() : " - no facts for the chain yet")
                + " [" + g.r.text() + "]";
    }
}
