package io.github.mojolowjo.entropybot.aa.brain;

import io.github.mojolowjo.entropybot.aa.moves.Step;

import java.util.LinkedHashSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * deposit-words (owner 2026-11-08; was the helper rule of 2026-11-02: the WHOLE bag to the main chest after every order):
 * an order flagged -d that ended (done, or stopped FOR GOOD with some of it in the bag; parked-holds, owner 2026-11-23: a
 * stopped order that can still wake has not ended, it holds its line's deposit, see {@link #sweep}) adds the ordered
 * amount of its item (what the bag holds of it) to its line's deposit; when the line's last -d order has ended, ONE deposit of exactly those items
 * goes to the main chest (aa deposit x y z). An order with no deposit word deposits nothing, said in the log (rule 7).
 * Weighed once per order end.
 *
 * <p>sim (owner 2026-11-09, "simulate the parts we need"): moved out of {@code commands.AaRuntime} as it was, so the
 * order-line tests run it with no game. One store (rule 23): the runtime holds one of these and only logs, whispers and
 * starts the deposit job from its answers. No Minecraft or Baritone types. Loader notes: none.
 */
public final class LineDeposits {

    /** What a weighing answers. */
    public enum Kind {
        /** Weighed before for this order's end: nothing said. */
        ALREADY,
        /** A strip order: its own deposit leg. */
        STRIP,
        /** No deposit word on the order: its items stay in the bag (logged). */
        NO_WORD,
        /** Its items are noted; the line's deposit waits for the orders still open (logged). */
        NOTED,
        /** The line ended with none of its deposit items in the bag (logged). */
        NOTHING,
        /** The line ended and no main chest is set (logged and whispered). */
        NO_CHEST,
        /** The line ended: {@link Out#items} go to the main chest when the job slot is free (logged). */
        DUE,
        /**
         * parked-holds: the order stopped and can still wake by itself: it is not weighed, it stays open on its line and the
         * line's deposit waits for it (logged and whispered, once per hold).
         */
        HELD,
        /** A parked order woke: its deposit went with the line already (logged). */
        WOKE_GONE,
        /** A parked order woke: weighed again at its end (logged). */
        WOKE_OPEN,
        /**
         * A parked order woke while its line's deposit was due and had not started: that deposit is taken back and weighed
         * again when the line ends (logged). {@link Out#items}: what is still due of other lines, null: nothing.
         */
        WOKE_TAKEN_BACK,
        /**
         * short-wake (pilot w057, w061): a parked order woke whose line's deposit was owed without it
         * ({@link Goals.Goal#depositOwedShort}) and had not started: only this order leaves the due deposit, the rest stays
         * due, its own amount follows with a deposit of its own when it ends (logged). {@link Out#items}: what is still due,
         * null: nothing.
         */
        WOKE_REST_DUE,
        /**
         * lost-after-done (tester-2 w064, run 3): a met -d order whose deposit is still owed and whose amount the bag no
         * longer holds is open again (logged; {@link #lostAfterDone}).
         */
        REOPENED
    }

    /**
     * {@code text}: the line for the log (without the "aa: " head), null: nothing to say. {@code items}: for DUE (and what
     * stays due after WOKE_TAKEN_BACK).
     */
    public record Out(Kind kind, String text, Map<String, Integer> items) {}

    /** Per aa want line, the -d orders not ended yet, and the ones ended so far (by key, in the order they ended). */
    private final Map<String, Set<String>> lineOpen = new HashMap<>();
    private final Map<String, Map<String, Goals.Goal>> lineEnded = new HashMap<>();
    private int lineSerial;

    /** deposit-kept: the blackboard fact holding the runtime's one LineDeposits (Goals.chainWorld reads {@link #kept}). */
    public static final String FACT = "aa.lineDeposits";

    /** deposit-kept: the items of the lines that ended (DUE) whose deposit has not started yet ({@link #gone}). */
    private final Map<Goals.Goal, Map<String, Integer>> due = new java.util.IdentityHashMap<>();

    /**
     * deposit-kept (owner 2026-11-13: "if we want 64 iron deposited, and we want an iron pickaxe, then 64 iron must be
     * deposited and the iron pickaxe can stay on the bot"; "the items that are tagged with -d should be considered
     * separate"; an order with no deposit word: "anything not being deposited can be used"): what the bag holds for the -d
     * orders' deposits (counted id -> n) - a due deposit not started, the ended -d orders of a line still open, and every
     * -d order of {@code goals} (open, met or parked: what the bag holds of its amount now). Each order once. Left out:
     * {@code self} (an order's own amount counts for it) and {@code skip} (the orders a trip plans for itself: their
     * amounts are set aside inside the trip, Playbook.Target.keep). Goals.keptOff takes the answer off the planner's bag,
     * Goals.free off the bag an order counts itself on. A line none of whose -d orders is among {@code goals} is not
     * counted (its deposit never comes; a dropped order ends its line through {@link #dropped}).
     */
    public Map<String, Integer> kept(Map<String, Integer> bag, java.util.List<Goals.Goal> goals, Goals.Goal self, java.util.Collection<Goals.Goal> skip) {
        Map<String, Integer> out = new TreeMap<>();
        for (Kept k : keptPer(bag, goals, self, skip)) for (var t : k.items().entrySet()) out.merge(t.getKey(), t.getValue(), Integer::sum);
        out.replaceAll((id, n) -> Math.min(n, bag.getOrDefault(id, 0)));
        out.values().removeIf(n -> n <= 0);
        return out;
    }

    /**
     * job-kept-crafts review 3 (worlds run 4 w044: the parked oak_log -d order held 7 of 16, the torch's chain cut a tree for
     * one log, the chop's 6 went into that order's hold and "needs 1 of logs_that_burn; the bag will hold 0"): per counted id,
     * how many more of it {@link #kept} (same {@code self} and {@code skip}) would hold as the bag of that item grows - the
     * holds read from the bag that are not whole yet. A get of the item brings these into the other orders' holds before any
     * is free, so the planner asks them on top of its own need (NeedChain's source ways, World.fills). Each id on its own, as
     * {@link #take} fills it; empty: none. Pure; the one reader of the holds, {@link #keptPer}, is asked on a bag with more.
     */
    public Map<String, Integer> fills(Map<String, Integer> bag, java.util.List<Goals.Goal> goals, Goals.Goal self, java.util.Collection<Goals.Goal> skip) {
        Map<String, Integer> out = new TreeMap<>();
        if (bag == null) return out;
        // the items an order read from the bag may hold (one with none of it in the bag yet too: keptPer leaves it out)
        Set<String> ids = new java.util.TreeSet<>();
        int more = 0;
        java.util.List<Goals.Goal> readers = new java.util.ArrayList<>();
        for (Goals.Goal o : goals) if (o.deposit > 0) readers.add(o);
        for (Map<String, Goals.Goal> ended : lineEnded.values()) readers.addAll(ended.values());
        for (Goals.Goal o : readers) {
            ids.addAll(o.r.counts());
            more += Math.max(0, o.deposit);
        }
        if (ids.isEmpty() || more <= 0) return out;
        Map<String, Integer> now = kept(bag, goals, self, skip);
        for (String id : ids) {
            int then = kept(withMore(bag, id, more), goals, self, skip).getOrDefault(id, 0);
            int fill = then - now.getOrDefault(id, 0);
            if (fill > 0) out.put(id, fill);
        }
        return out;
    }

    /** {@code bag} with {@code more} of {@code id} added (null id: a copy). */
    private static Map<String, Integer> withMore(Map<String, Integer> bag, String id, int more) {
        Map<String, Integer> b = new java.util.HashMap<>(bag);
        if (id != null) b.merge(id, more, Integer::sum);
        return b;
    }

    /** One order's share of {@link #kept}: the order and what the bag holds for its deposit (counted id -> n). */
    public record Kept(Goals.Goal order, Map<String, Integer> items) {}

    /**
     * {@link #kept} per order (merge review 2026-11-23: the placing reserves need the order with each amount - an amount is
     * the same items as a reserve only when that reserve is the SAME order's, and a reserve names the item by its full id,
     * which only the order has). The one computation: {@link #kept} is the sum of these. An order whose line came due again
     * while its earlier deposit is on its way is listed twice (different items of the bag).
     */
    public java.util.List<Kept> keptPer(Map<String, Integer> bag, java.util.List<Goals.Goal> goals, Goals.Goal self, java.util.Collection<Goals.Goal> skip) {
        java.util.List<Kept> out = new java.util.ArrayList<>();
        Set<Goals.Goal> seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        if (self != null) seen.add(self);
        if (skip != null) seen.addAll(skip);
        Set<Goals.Goal> left = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        left.addAll(seen);
        for (var d : way.entrySet()) if (seen.add(d.getKey())) out.add(new Kept(d.getKey(), d.getValue()));
        // an order whose line came due AGAIN while its earlier deposit is on its way counts twice: what is on its way and
        // what is due of it now are different items of the bag (review round 2)
        for (var d : due.entrySet())
            if (!left.contains(d.getKey()) && (seen.add(d.getKey()) || way.containsKey(d.getKey()))) out.add(new Kept(d.getKey(), d.getValue()));
        Set<String> lines = new java.util.HashSet<>();
        for (Goals.Goal g : goals) if (g.deposit > 0 && g.line != null) lines.add(g.line);
        for (var e : lineEnded.entrySet()) {
            if (!lines.contains(e.getKey())) continue;
            for (Goals.Goal o : e.getValue().values()) if (seen.add(o)) out.add(new Kept(o, take(o, bag)));
        }
        for (Goals.Goal o : goals)
            if (o.deposit > 0 && o.strip == null && seen.add(o)) out.add(new Kept(o, take(o, bag)));
        out.removeIf(k -> k.items().isEmpty());
        return out;
    }

    /**
     * Merge review 2026-11-23 (a modded -d item: the bag and the weighing count by the item's path, the chest click and
     * the placing reserve name the item by its full id, and "minecraft:" was put before the bare path): the full id of
     * the order's item whose counted id is {@code counted}, from the order's own ids (the game's registry names, rule 4);
     * the order names no such item: {@code counted} as it is.
     */
    public static String fullId(Goals.Goal g, String counted) {
        for (String id : g.r.ids()) if (Thing.path(id).equals(counted)) return id;
        return counted;
    }

    /**
     * take-back (tester-1 w006, 2026-11-22 04:34:10.244 -> 04:34:11.413: the line went due on two parked orders, both woke a
     * second later and the stale deposit of spruce_log 11 still started): per line whose deposit is DUE and has not
     * started, its ended orders as {@link #lineEnds} found them, so a wake of any of them ({@link #reopen}) can put the
     * line back as it was. Keyed on the line: an order that noted nothing is not in {@link #due}, yet its wake takes the
     * deposit back too (tester-2 w003-3: birch_log noted nothing).
     */
    private final Map<String, Map<String, Goals.Goal>> dueLine = new HashMap<>();
    /** take-back: per line whose deposit run is on its way ({@link #putStarted}), the items it was sent with (for the log). */
    private final Map<String, Map<String, Integer>> wayLine = new HashMap<>();

    /** The due items of the orders in {@code ended}, summed. */
    private Map<String, Integer> dueOf(Map<String, Goals.Goal> ended) {
        Map<String, Integer> out = new TreeMap<>();
        for (Goals.Goal o : ended.values()) {
            Map<String, Integer> t = due.get(o);
            if (t != null) for (var i : t.entrySet()) out.merge(i.getKey(), i.getValue(), Integer::sum);
        }
        return out;
    }

    /**
     * Review round 2 (cobblestone 32 -d went with 20, was met while that run climbed out through stone, and its line came
     * due again on the other 12; the run's end removed "the lines on their way" and the 12 with them): the amounts of the
     * run on its way, per order as {@link #putStarted} sent it. They are kept apart from {@link #due}, so what comes due while
     * the run is on its way (another line, or the SAME line and the same order again) is never the run's to take off.
     */
    private final Map<Goals.Goal, Map<String, Integer>> way = new java.util.IdentityHashMap<>();
    /** A deposit run is on its way ({@link #putStarted} to {@link #gone}). */
    private boolean onWay;

    /**
     * deposit-goal piece 2: the deposit's put started: what is due now goes on its way; from here a wake
     * of one of its orders leaves it alone (take-back).
     */
    public void putStarted() {
        // review 7 (point 2): a second start while a put is on (its leg ended short, the task re-planned) adds what came due
        // since to what is still on its way, as failed() merges back: a remainder on its way is never replaced
        for (var e : dueLine.entrySet()) {
            wayLine.merge(e.getKey(), dueOf(e.getValue()), LineDeposits::plus);
            wayOrders.addAll(e.getValue().values());
        }
        dueLine.clear();
        for (var d : due.entrySet()) way.merge(d.getKey(), d.getValue(), LineDeposits::plus);
        due.clear();
        onWay = true;
    }

    /** Two item counts summed into a new map (id -> n). */
    private static Map<String, Integer> plus(Map<String, Integer> a, Map<String, Integer> b) {
        Map<String, Integer> sum = new TreeMap<>(a);
        for (var i : b.entrySet()) sum.merge(i.getKey(), i.getValue(), Integer::sum);
        return sum;
    }

    /**
     * take-back: the items of every line whose deposit is due and not started (empty: none), by the bag's key. Deposit-goal
     * stage 4: the not-started part of {@link #owed} - the same sum over {@link #due} alone (one reader, rule 23).
     */
    public Map<String, Integer> dueNow() { return sum(java.util.List.of(due), false); }
    /** The orders of the lines whose deposit run is on its way ({@link #putStarted}), the ones that noted nothing included. */
    private final Set<Goals.Goal> wayOrders = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
    /**
     * Review 7 (point 2): per order of the put on its way whose wake cut what the line took off its amount ({@link #reopen}),
     * the part of it that was still on its way then (by the way's keys): when it goes in, it is not noted in depositPut again.
     */
    private final Map<Goals.Goal, Map<String, Integer>> wayCut = new java.util.IdentityHashMap<>();

    /**
     * deposit-kept: the deposit run ended, or the due deposit could not start (said by the caller): its items are no longer
     * kept here. With a run on its way only what that run was sent with goes ({@link #way}): a line that came due
     * meanwhile stays due and kept, the run's own line and orders included (review round 2).
     *
     * <p>{@code reached} (merge review 2026-11-23: a failed or refused deposit run left its orders' notes as "taken by
     * the deposit", and a later wake cut the order's deposit amount by items that were still in the bag): the run ended
     * done, its items are in the chest. False (failed, refused, or its end not read): the orders of that deposit are
     * marked ({@link Goals.Goal#depositMissed}), so a wake ({@link #reopen}) takes nothing off their amounts.
     */
    public void gone(boolean reached) {
        if (!onWay) {
            if (!reached) for (Map<String, Goals.Goal> ended : dueLine.values()) for (Goals.Goal o : ended.values()) o.depositMissed = true;
            due.clear();
            dueLine.clear();
            wayCut.clear();
            return;
        }
        onWay = false;
        if (!reached) {
            for (Goals.Goal o : wayOrders) o.depositMissed = true;
            cutBack();           // review 8: the owner's cancel - what a wake cut and did not go in is the order's again
        }
        wayCut.clear();
        way.clear();
        wayLine.clear();
        wayOrders.clear();
    }

    /**
     * deposit-goal piece 2: the put ended. {@code reached}: as {@link #gone gone(true)}, its items are in the chest. Not
     * reached ({@link #failed}): the put's items are owed again - {@link #way} back into {@link #due}, its lines back into
     * {@link #dueLine} - and no order is marked missed: the items stay owed and kept, and a later wake still takes the
     * deposit back ({@link #reopen}). Nothing on its way: nothing to put back.
     */
    public void putEnded(boolean reached) {
        if (reached) { if (onWay) putsReached++; gone(true); return; }
        failed();
    }

    /** deposit-goal stage 3: the puts that reached the chest so far (OrderEnds.depositSync tells "in the chest" from "taken back" by it). */
    private int putsReached;

    /** deposit-goal stage 3: {@link #putsReached}. */
    public int putsReached() { return putsReached; }

    /** {@link #putEnded putEnded(false)}: the put did not reach the chest; what it was sent with is owed again. */
    public void failed() {
        if (!onWay) return;
        onWay = false;
        // review 8 (serious, the review's case: cobblestone 32 -d put 20, 10 went in, the order woke - its amount cut to 12,
        // the 10 still on its way the put's - then the put's task was lost; the 10 came back into due and the order's
        // re-end REPLACED it with its 12, so the chest got 22 of 32): what a wake cut off an order's amount and did not
        // reach the chest is the order's own again - back on its amount, never left in due for a later end or take-back
        // to replace or drop
        Map<Goals.Goal, Map<String, Integer>> back = cutBack();
        // a line that came due while the put ran keeps its own orders; the put's orders join them per line. An order open
        // again (woke) is not among a due line's orders: it is weighed when it ends
        for (var e : wayLine.entrySet()) {
            Map<String, Goals.Goal> line = dueLine.computeIfAbsent(e.getKey(), k -> new java.util.LinkedHashMap<>());
            for (Goals.Goal o : wayOrders) if (e.getKey().equals(o.line) && o.depositWeighed) line.putIfAbsent(o.r.key(), o);
            if (line.isEmpty()) dueLine.remove(e.getKey());
        }
        for (var d : way.entrySet()) {
            Map<String, Integer> rest = new TreeMap<>(d.getValue());
            Map<String, Integer> b = back.get(d.getKey());
            // an order open again owes its cut part on its own amount (weighed on the bag at its end); one weighed again
            // since (its new due amount taken on the cut amount) keeps it in due beside the new part, added
            // (its noted part grows by it too, so a later wake takes off all that went for it)
            if (b != null && !d.getKey().depositWeighed) for (var i : b.entrySet()) rest.merge(i.getKey(), -i.getValue(), Integer::sum);
            else if (b != null) d.getKey().depositTook = plus(d.getKey().depositTook == null ? Map.of() : d.getKey().depositTook, b);
            rest.values().removeIf(n -> n <= 0);
            if (!rest.isEmpty()) due.merge(d.getKey(), rest, LineDeposits::plus);
        }
        way.clear();
        wayLine.clear();
        wayOrders.clear();
    }

    /**
     * Review 8: the put on its way did not reach the chest (failed, or the owner's cancel): per order, the part of what a
     * wake cut off its amount ({@link #wayCut}) that is still on its way, added back to the order's amount
     * ({@link Goals.Goal#deposit}); {@link #wayCut} is cleared. Returns what went back, per order (by the way's keys).
     */
    private Map<Goals.Goal, Map<String, Integer>> cutBack() {
        Map<Goals.Goal, Map<String, Integer>> out = new java.util.IdentityHashMap<>();
        for (var c : wayCut.entrySet()) {
            Map<String, Integer> on = way.getOrDefault(c.getKey(), Map.of());
            Map<String, Integer> b = new TreeMap<>();
            for (var i : c.getValue().entrySet()) {
                int n = Math.min(i.getValue(), on.getOrDefault(i.getKey(), 0));
                if (n > 0) b.put(i.getKey(), n);
            }
            if (b.isEmpty()) continue;
            c.getKey().deposit += b.values().stream().mapToInt(Integer::intValue).sum();
            out.put(c.getKey(), b);
        }
        wayCut.clear();
        return out;
    }

    /**
     * deposit-goal piece 2: the one reader of what the deposit still owes - the items of {@link #due} and {@link #way}, by
     * each item's full id ({@link #fullId}), summed. Empty: nothing owed.
     */
    public Map<String, Integer> owed() { return sum(java.util.List.of(due, way), true); }

    /**
     * Stage 4 review (the put's items are fixed when the plan is made, but {@link #putStarted} moves what is owed at the
     * step's start; a line that came due, or a take-back, in between made the two differ): null when the planned put
     * {@code only} is what is owed now, else the said reason - the step fails with it and the deposit goal plans again
     * from {@link #owed}, so nothing goes on its way that the put does not carry.
     */
    public String putDiffers(Map<String, Integer> only) {
        // stage 4 review 2 (fault 4): both sides by the one normalisation the step's keys carry (NeedChain.id), so an
        // owed item whose full id fell back to the bare counted key still matches the planned put
        Map<String, Integer> now = normal(owed());
        Map<String, Integer> planned = normal(only == null ? Map.of() : only);
        if (now.equals(planned)) return null;
        return "the deposit owed changed since the plan (planned " + planned + ", owed now " + now + "), planning again";
    }

    /**
     * Stage 4 review 2 (faults 1 and 5, the rule apart and replayed): the end of task {@code endedTask} reads the put
     * started in {@code putTask}; true: the put reached the chest - the run ended done, or failed at a step after the put
     * (the put's own step is never the failed one then). Another task's end never counts it reached.
     */
    public static boolean putReached(long putTask, long endedTask, boolean done, io.github.mojolowjo.entropybot.aa.moves.Step failedAt) {
        if (putTask < 0 || putTask != endedTask) return false;
        return done || (failedAt != null && !(failedAt instanceof io.github.mojolowjo.entropybot.aa.moves.Step.Deposit));
    }

    /** {@code m} keyed by {@link io.github.mojolowjo.entropybot.aa.NeedChain#id} (the step's normalisation), summed. */
    private static Map<String, Integer> normal(Map<String, Integer> m) {
        Map<String, Integer> out = new TreeMap<>();
        for (var e : m.entrySet()) out.merge(io.github.mojolowjo.entropybot.aa.NeedChain.id(e.getKey()), e.getValue(), Integer::sum);
        return out;
    }

    /**
     * Stage 4 review 2 (fault 2: a put that failed after moving some stacks sent the whole amount back to due, and the
     * re-plan put it twice): the put did not reach the chest whole, and {@code put} (by the step's ids, NeedChain.id; null
     * or empty: nothing went in) did go in. What went in is taken off the put's amounts first, order by order, so only
     * the rest is owed again ({@link #failed}); the put counts as reached when nothing is left of it.
     */
    public void putEnded(boolean reached, Map<String, Integer> put) {
        if (reached || !onWay || put == null || put.isEmpty()) { putEnded(reached); return; }
        if (!takeOff(put)) { putsReached++; gone(true); return; }
        failed();
    }

    /**
     * Review 5 (a put started twice in one task asked again for what its first leg put: the in-run re-plan reads
     * {@link #owed}, and {@link #way} kept the whole amount until the put ended): a leg of the put on its way ended inside
     * its running task and moved {@code put} (by the step's ids) into the chest. It comes off {@link #way} now, order by
     * order (the order's {@link Goals.Goal#depositPut}); the put stays on its way. Nothing on its way: nothing.
     */
    public void legEnded(Map<String, Integer> put) {
        if (!onWay || put == null || put.isEmpty()) return;
        takeOff(put);
    }

    /** Review 5: nothing of the put on its way is left in {@link #way} (every leg's part reached the chest). */
    public boolean wayEmpty() { return onWay && way.isEmpty(); }

    /** {@code put} off {@link #way}, order by order, each order's part noted in its depositPut; true: some of the way is left. */
    private boolean takeOff(Map<String, Integer> put) {
        Map<String, Integer> left = new TreeMap<>(normal(put));
        boolean rest = false;
        for (var d : way.entrySet()) {
            Map<String, Integer> now = new TreeMap<>();
            for (var i : d.getValue().entrySet()) {
                String k = io.github.mojolowjo.entropybot.aa.NeedChain.id(fullId(d.getKey(), i.getKey()));
                int in = Math.min(i.getValue(), left.getOrDefault(k, 0));
                if (in > 0) {
                    left.merge(k, -in, Integer::sum);
                    // review 4 (sim: a chest for 10 of 52 - the 10 left the bag, the met order read itself lost, opened
                    // again and owed its whole amount a second time): what is in the chest counts for its order
                    // review 7: the part a wake already cut off the order's amount (its remainder on its way then) is not
                    // noted again; the rest is
                    Map<String, Integer> cut = wayCut.get(d.getKey());
                    int was = cut == null ? 0 : Math.min(in, cut.getOrDefault(i.getKey(), 0));
                    if (was > 0) cut.merge(i.getKey(), -was, Integer::sum);
                    if (in - was > 0) d.getKey().depositPut.merge(i.getKey(), in - was, Integer::sum);
                }
                if (i.getValue() - in > 0) { now.put(i.getKey(), i.getValue() - in); rest = true; }
            }
            d.setValue(now);
        }
        way.values().removeIf(Map::isEmpty);
        return rest;
    }

    /** The items of {@code maps}' orders summed, by each item's full id ({@link #fullId}) or the bag's key. */
    private static Map<String, Integer> sum(java.util.List<Map<Goals.Goal, Map<String, Integer>>> maps, boolean full) {
        Map<String, Integer> out = new TreeMap<>();
        for (var m : maps)
            for (var d : m.entrySet()) for (var i : d.getValue().entrySet()) out.merge(full ? fullId(d.getKey(), i.getKey()) : i.getKey(), i.getValue(), Integer::sum);
        return out;
    }
    /**
     * deposit-goal piece 2: the orders behind {@link #owed}: every order of the due lines and of the put on its way, the
     * ones that noted nothing included; each once, by identity.
     */
    public Set<Goals.Goal> owedOrders() {
        Set<Goals.Goal> out = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (Map<String, Goals.Goal> ended : dueLine.values()) out.addAll(ended.values());
        out.addAll(wayOrders);
        return out;
    }

    /**
     * short-wake (OrderEnds.depositSync's end words): the -d orders still open on their lines (by key, sorted), whose ends
     * a line's deposit waits for; empty: none open.
     */
    public java.util.List<String> openOrders() {
        java.util.TreeSet<String> out = new java.util.TreeSet<>();
        for (Set<String> open : lineOpen.values()) out.addAll(open);
        return new java.util.ArrayList<>(out);
    }

    /** What the bag holds of the order's deposit amount now (counted id -> n; a group: up to the amount of each member). */
    static Map<String, Integer> take(Goals.Goal g, Map<String, Integer> bag) {
        Map<String, Integer> out = new TreeMap<>();
        // review 5 (once): what an earlier partial put already took to the chest for this order is not asked again
        int owes = Math.max(0, g.deposit - g.depositPut.values().stream().mapToInt(Integer::intValue).sum());
        int left = owes;
        for (String id : g.r.counts()) {
            int have = bag.getOrDefault(id, 0);
            int take = g.r.group() ? Math.min(have, owes) : Math.min(have, left);
            if (take > 0) { out.merge(id, take, Integer::sum); left -= take; }
            if (!g.r.group() && left <= 0) break;
        }
        return out;
    }

    /**
     * The ended orders' amounts on {@code bag} (never more of an item than the bag holds); {@code keep}: the line ends now,
     * each order keeps what went for it ({@link Goals.Goal#depositTook}, for a later {@link #reopen}).
     */
    private static Map<String, Integer> all(Map<String, Goals.Goal> ended, Map<String, Integer> bag, boolean keep) {
        Map<String, Integer> all = new TreeMap<>();
        for (Goals.Goal o : ended.values()) {
            Map<String, Integer> t = take(o, bag);
            if (keep) o.depositTook = t;
            for (var e : t.entrySet()) all.merge(e.getKey(), e.getValue(), Integer::sum);
        }
        all.replaceAll((id, n) -> Math.min(n, bag.getOrDefault(id, 0)));
        return all;
    }

    /** A new order: its line ({@code batch}, or a line of its own), its deposit amount, and its place among the line's open orders. */
    public void add(Goals.Goal g, String batch, boolean deposit) {
        g.line = batch != null ? batch : "l" + (++lineSerial);
        g.deposit = deposit ? g.n : 0;
        g.depositWeighed = false;
        g.depositOwedShort = false;
        if (g.deposit > 0) lineOpen.computeIfAbsent(g.line, k -> new LinkedHashSet<>()).add(g.r.key());
    }

    /**
     * The order ended ({@code how}: "done" or "stopped"): weighed once. {@code bag}: the sensed bag by counted id;
     * {@code mainAt}: the main chest's cell as text, null: none set.
     */
    public Out weigh(Goals.Goal g, String how, Map<String, Integer> bag, String mainAt) {
        if (g.depositWeighed) {
            // met-after-stop (run JBK-T1 15:18:55 -> 15:25:38: cobblestone 32 -d stopped with none in the bag, "its deposit
            // items noted ()", never woke, and was met when another order's digging filled the bag; its "done" found it
            // weighed and the line's deposit left the cobblestone out): a stop's weighing is of the bag at the stop; an order
            // met later is weighed again - the stop's note taken back (reopen), then the weighing below. "done" comes once
            // per order (it leaves the goals), so this is once
            if (!"done".equals(how) || g.depositTook == null || g.strip != null || g.deposit <= 0) return new Out(Kind.ALREADY, null, null);
            Out back = reopen(g);
            if (back.kind() == Kind.WOKE_GONE) return back;
            // review 3: a due deposit this weighing took back is said in the line that follows (rule 7), whatever it answers;
            // the deposit goal puts only what owed() holds, so a taken-back one never goes
            how = "done (stopped earlier and met since: weighed again" + (back.kind() == Kind.WOKE_TAKEN_BACK ? "; the line's deposit that had not started is taken back and weighed with it"
                    : back.kind() == Kind.WOKE_REST_DUE ? "; the line's deposit owed without it stays due, its own amount joins it" : "") + ")";
        }
        g.depositWeighed = true;
        g.depositOwedShort = false;                  // short-wake: set after a weighing owed the line without it (sweep), or joined it to its still-due rest (lineEnds)
        g.depositMissed = false;                     // weighed anew: an earlier deposit that did not reach the chest is behind it
        g.depositHeld = false;                       // parked-holds: an order that ended holds nothing
        if (g.strip != null) return new Out(Kind.STRIP, null, null);     // the strip order has its own deposit leg
        if (g.deposit <= 0) return new Out(Kind.NO_WORD, g.r.key() + " " + how + ": no deposit word on the order (-d, or a trailing deposit) - its items stay in the bag", null);
        // bag-at-the-end (run JBO-T1 22:53:47 -> 22:54:30: cobblestone stopped with 17 in the bag, "noted (cobblestone 17)";
        // the torch order's furnace took 8; the line's deposit still asked for the 17): an ended order is kept as an order,
        // not as the amounts its end saw - the line's deposit is what the bag holds of each order's amount when the line ends
        Map<String, Goals.Goal> ended = lineEnded.computeIfAbsent(g.line, k -> new java.util.LinkedHashMap<>());
        ended.put(g.r.key(), g);
        g.depositTook = take(g, bag);
        Set<String> open = lineOpen.get(g.line);
        if (open != null) open.remove(g.r.key());
        if (open != null && !open.isEmpty())
            return new Out(Kind.NOTED, g.r.key() + " " + how + ": its deposit items noted (" + Step.Deposit.onlyText(all(ended, bag, false)) + "); the line's deposit follows when "
                    + String.join(", ", open) + " end" + (open.size() == 1 ? "s" : "") + " (weighed on the bag then)", null);
        return lineEnds(g, how, ended, bag, mainAt);
    }

    /** The line's last open -d order ended ({@code how}): its ended orders' amounts on the bag now, as one deposit. */
    private Out lineEnds(Goals.Goal g, String how, Map<String, Goals.Goal> ended, Map<String, Integer> bag, String mainAt) {
        lineOpen.remove(g.line);
        lineEnded.remove(g.line);
        Map<String, Integer> all = all(ended, bag, true);
        String shortText = shortfalls(ended);
        Map<String, Goals.Goal> still = dueLine.get(g.line);
        if (all.isEmpty() && still != null && !still.isEmpty()) {
            // short-wake review (w061's torch 0 of 4: a woken short order stopped again with nothing in the bag while the rest
            // of its line was still due; it never joined the due line, and its next wake found itself outside it and took
            // the whole rest back): an ended order with nothing in the bag joins the still-due rest of its line all the same,
            // owed short with it, so a later wake takes only itself out ({@link #restDue}); the rest stays due and goes
            for (Goals.Goal o : ended.values()) o.depositOwedShort = true;
            still.putAll(ended);
            return new Out(Kind.NOTED, g.r.key() + " " + how + ": nothing of its own deposit items in the bag; the rest of the line still due goes ("
                    + Step.Deposit.onlyText(dueOf(still)) + ")" + shortText, null);
        }
        if (all.isEmpty()) {
            // rule 7: an ended order with nothing in the bag while its line's earlier deposit is on its way says that one goes
            Map<String, Integer> onIts = wayLine.get(g.line);
            return new Out(Kind.NOTHING, g.r.key() + " " + how + (onIts == null || onIts.isEmpty() ? ": nothing of the line's deposit items in the bag - no deposit"
                    : ": nothing of its own deposit items in the bag - no deposit of its own; the line's deposit of " + Step.Deposit.onlyText(onIts) + " is on its way") + shortText, null);
        }
        if (mainAt == null) {
            // merge review 2026-11-23: nothing goes to a chest here - a later wake of one of these orders takes nothing off
            for (Goals.Goal o : ended.values()) o.depositMissed = true;
            return new Out(Kind.NO_CHEST, g.r.key() + " " + how + ": no main chest set (aa deposit x y z) - " + Step.Deposit.onlyText(all) + " stay in the bag" + shortText, all);
        }
        for (Goals.Goal o : ended.values()) if (o.depositTook != null && !o.depositTook.isEmpty()) due.put(o, o.depositTook);       // deposit-kept: until the deposit starts
        // take-back: the line as it ended, until its run starts. short-wake: merged, never put over - a woken order's later end
        // joins the rest of its line still due ({@link #reopen}), it never replaces it
        Map<String, Goals.Goal> was = dueLine.computeIfAbsent(g.line, k -> new java.util.LinkedHashMap<>());
        boolean joins = !was.isEmpty();
        // short-wake review: an order whose end joins the still-due rest of its line is owed with that rest; its wake takes
        // only itself out ({@link #restDue}), never the rest
        if (joins) for (Goals.Goal o : ended.values()) o.depositOwedShort = true;
        was.putAll(ended);
        return new Out(Kind.DUE, g.r.key() + " " + how + ": the line's deposit of " + Step.Deposit.onlyText(all) + " to the main chest at " + mainAt + " follows"
                + (joins ? " (with the rest of the line still due: " + Step.Deposit.onlyText(dueOf(was)) + " in all)" : "") + shortText, all);
    }

    /**
     * lost-after-done (tester-2 w064, run 3: the line's end said only what the bag held, nothing of the 32 cobblestone
     * missing; rule 7): every order of the ending line whose noted amount ({@link Goals.Goal#depositTook}, set by
     * {@link #all} just before) is under its deposit amount, each as "item delivered of asked"; empty: none short.
     */
    private static String shortfalls(Map<String, Goals.Goal> ended) {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (Goals.Goal o : ended.values()) {
            if (o.deposit <= 0 || o.strip != null) continue;
            int got = o.depositTook == null ? 0 : o.depositTook.values().stream().mapToInt(Integer::intValue).sum();
            if (got < o.deposit) out.add(o.r.key() + " " + got + " of " + o.deposit);
        }
        return out.isEmpty() ? "" : "; short of what was ordered: " + String.join(", ", out);
    }

    /**
     * lost-after-done (tester-2 w064, run 3, 23:20:30 -> 23:20:37: cobblestone 32 -d was met and noted, a creeper killed the
     * bot 7 s later and the whole bag was lost; the met order had left the goals, so nothing opened it again, and the line
     * deposited what the bag held): every MET -d order whose line's deposit is still owed (its line still open, or due and
     * not started) and whose amount the bag no longer holds ({@link #take} on {@code bag} under its deposit amount) is open
     * on its line again, by the same state change a wake makes ({@link #reopen}). Not a death script: it is read from the
     * bag, whatever took the items. A stopped order is not opened here (its end was of the bag then; only a wake opens it).
     * The caller puts each answer's order back among the goals ({@link Goals#reopen}). Each answer is {@link Kind#REOPENED},
     * or {@link Kind#WOKE_TAKEN_BACK} (items: what stays due) when it took back a due deposit that had not started.
     */
    public java.util.List<Map.Entry<Goals.Goal, Out>> lostAfterDone(Map<String, Integer> bag) {
        java.util.List<Goals.Goal> lost = new java.util.ArrayList<>();
        for (var e : lineEnded.entrySet()) {
            Set<String> open = lineOpen.get(e.getKey());
            if (open == null || open.isEmpty()) continue;           // a line that ended is not owed here (its run went or is on its way)
            for (Goals.Goal o : e.getValue().values()) if (isLost(o, bag)) lost.add(o);
        }
        for (Map<String, Goals.Goal> ended : dueLine.values()) for (Goals.Goal o : ended.values()) if (isLost(o, bag) && !lost.contains(o)) lost.add(o);
        java.util.List<Map.Entry<Goals.Goal, Out>> out = new java.util.ArrayList<>();
        for (Goals.Goal o : lost) {
            Map<String, Integer> has = take(o, bag);
            int n = has.values().stream().mapToInt(Integer::intValue).sum();
            Out back = reopen(o);
            String text = o.r.key() + " was done, and the bag holds " + n + " of its " + o.deposit + " for the line's deposit now: open again, the planner prices the rest";
            // a due deposit taken back answers as a wake's take-back does, so the runtime drops its queued run (items: what stays due)
            out.add(Map.entry(o, back.kind() == Kind.WOKE_TAKEN_BACK
                    ? new Out(Kind.WOKE_TAKEN_BACK, text + "; the line's deposit that had not started is taken back and weighed when it ends", back.items())
                    : back.kind() == Kind.WOKE_REST_DUE ? new Out(Kind.WOKE_REST_DUE, text + "; " + back.text(), back.items())
                    : new Out(Kind.REOPENED, text + "; the line's deposit waits for it", null)));
        }
        return out;
    }

    private static boolean isLost(Goals.Goal o, Map<String, Integer> bag) {
        if (!o.met || o.deposit <= 0 || o.strip != null || !o.depositWeighed) return false;
        int inChest = o.depositPut.values().stream().mapToInt(Integer::intValue).sum();     // review 4: a partial put's part
        return take(o, bag).values().stream().mapToInt(Integer::intValue).sum() + inChest < o.deposit;
    }

    /**
     * deposit-kept (owner 2026-11-13: "if I do drop the torch order, it should still deposit the cobblestone"): the owner
     * dropped {@code g} (aa cancel). It leaves its line - nothing of its own is deposited (said); when it was the line's last
     * open -d order, the line ends now and the orders that ended before go to the chest as one deposit. Null: an order with
     * no deposit word, or one whose line ended already.
     */
    public Out dropped(Goals.Goal g, Map<String, Integer> bag, String mainAt) { return leaves(g, "dropped (nothing of it is deposited)", bag, mainAt); }

    /**
     * Merge review 2026-11-23 (aa want of an item that is an open or stopped -d order: Goals.add replaced the goal and the
     * new one got a line of its own, while the old key stayed among its old line's open orders for ever - that line never
     * ended, its finished orders' amounts were never deposited and stopped being kept, with no line said; or the old,
     * weighed goal stayed among the line's ended orders and both lines deposited the amount): the order {@code g} was
     * replaced by a new order of the same item. It leaves its line as a dropped one does ({@link #dropped}): the new
     * order deposits its own amount with its own line. Null as {@link #dropped}.
     */
    public Out replaced(Goals.Goal g, Map<String, Integer> bag, String mainAt) {
        return leaves(g, "ordered again (the new order has a line of its own; nothing of the old one is deposited with this line)", bag, mainAt);
    }

    private Out leaves(Goals.Goal g, String how, Map<String, Integer> bag, String mainAt) {
        if (g.deposit <= 0 || g.line == null || g.strip != null) return null;
        Set<String> open = lineOpen.get(g.line);
        Map<String, Goals.Goal> ended = lineEnded.get(g.line);
        boolean was = open != null && open.remove(g.r.key());
        if (ended != null && ended.get(g.r.key()) == g) { ended.remove(g.r.key()); was = true; }      // dropped while parked
        if (!was) return null;
        g.depositHeld = false;
        if (open != null && !open.isEmpty())
            return new Out(Kind.NOTED, g.r.key() + " " + how + ": the line's deposit follows when " + String.join(", ", open) + " end" + (open.size() == 1 ? "s" : ""), null);
        return lineEnds(g, how, ended == null ? new java.util.LinkedHashMap<>() : ended, bag, mainAt);
    }

    /**
     * short-wake (pilot w057 08:49:46.726, w061 09:17:02.796: the line was owed without {@code g} - "if it wakes later, its
     * amount follows with a deposit of its own" - then {@code g} woke, the take-back of {@link #reopen} took the WHOLE due deposit back,
     * nothing was owed and the deposit goal ended; in w061 the deposit goal's own walk was the wake): {@code g} was owed
     * short ({@link Goals.Goal#depositOwedShort}) and the due deposit of its line had not started. Only {@code g} leaves the
     * due deposit: its noted part stays in the bag as its own (kept for its own amount, {@link #keptPer}), nothing is taken
     * off its amount (nothing of it reached the chest), it is open on its line again; the rest of the line stays due as it
     * was. When {@code g} ends, its deposit joins whatever of the line is still due then ({@link #lineEnds} merges), or goes
     * on its own when the rest went.
     */
    private Out restDue(Goals.Goal g, Map<String, Goals.Goal> back) {
        g.depositOwedShort = false;
        g.depositWeighed = false;
        g.depositMissed = false;
        g.depositTook = null;
        Map<String, Integer> mine = due.remove(g);
        back.remove(g.r.key(), g);
        if (back.isEmpty()) dueLine.remove(g.line);
        lineOpen.computeIfAbsent(g.line, k -> new LinkedHashSet<>()).add(g.r.key());
        Map<String, Integer> rest = dueOf(back);
        Map<String, Integer> still = dueNow();
        int owes = Math.max(0, g.deposit - g.depositPut.values().stream().mapToInt(Integer::intValue).sum());
        String kept = mine == null || mine.isEmpty() ? "" : "; its " + Step.Deposit.onlyText(mine) + " in the bag stay its own";
        String text = g.r.key() + " woke: "
                + (rest.isEmpty() ? "the line's deposit owed without the rest of it held only its own items and had not started - nothing of the line is due now"
                        : "the line's deposit owed without it stays due (" + Step.Deposit.onlyText(rest) + ")")
                + kept + "; its whole amount (" + owes + ") follows with a deposit of its own when it ends"
                + (still.isEmpty() || still.equals(rest) ? "" : " (still due in all: " + Step.Deposit.onlyText(still) + ")");
        return new Out(Kind.WOKE_REST_DUE, text, still.isEmpty() ? null : still);
    }

    /**
     * Review 6 (serious: every leg that ended wrote its part into {@link Goals.Goal#depositPut}, a reached put included,
     * and a wake then cut the order's amount by its noted part as well; {@link #take} took depositPut off a second time -
     * cobblestone 32 -d stopped with 20, the 20 reached the chest, the wake left 12 and take asked for none): what the wake
     * cuts off the amount is what is in the chest for the order - its depositPut when a leg noted it (the count of what
     * went in), else {@code noted} (a put that ended with no leg count) - and depositPut is cleared, so the amount alone
     * holds it from then on. One count, never two.
     */
    private static int inChest(Goals.Goal g, int noted) {
        int put = g.depositPut.values().stream().mapToInt(Integer::intValue).sum();
        g.depositPut.clear();
        return put > 0 ? put : noted;
    }

    /**
     * deposit-words (run JBK-T1 15:18:55: cobblestone parked 30 s in, "its deposit items noted ()", woken, done at 7:20 and
     * never weighed again - the line's deposit missed it): a parked order that woke (parked null again, still weighed) is
     * weighed again at its end. What its park noted is taken back from the line's items; when the line's deposit went
     * already (or is on its way), that much of its ordered amount is done with ({@link #inChest}).
     *
     * <p>take-back (tester-1 w006 04:34:11.245: "spruce_log woke ... the line's deposit waits for it", and the stale deposit
     * started 0.2 s later): a line whose deposit is due and has NOT started is put back as it was before it ended - its
     * ended orders noted again, the woken one open, nothing taken off its amount - and the answer says so
     * ({@link Kind#WOKE_TAKEN_BACK}); the line is weighed on the bag again when it ends. The words for a deposit that is on
     * its way or went already say that, never "waits for it" (rule 7).
     *
     * <p>short-wake: an order owed short with its due line ({@link Goals.Goal#depositOwedShort}) takes only itself out of it
     * ({@link #restDue}); the rest stays due.
     */

    public Out reopen(Goals.Goal g) {
        Map<String, Goals.Goal> back = dueLine.get(g.line);
        if (back != null) {
            // review 1 (line A, B, C all -d: the first deposit went with B's 11, C woke and ended again, the line is due on
            // {C}; B wakes): an order of the line that is NOT one of the due deposit's orders was weighed for an EARLIER
            // deposit of this line, and what that one took of it is done with - taken off its amount here as below, never
            // deposited twice. Nothing left of it: the due deposit is not its, and stays due
            boolean outside = back.get(g.r.key()) != g;
            if (!outside && g.depositOwedShort) return restDue(g, back);
            // merge review 2026-11-23: an earlier deposit that did not reach the chest took nothing of it
            Map<String, Integer> went = outside && !g.depositMissed && g.depositTook != null ? g.depositTook : Map.of();
            int gone = went.values().stream().mapToInt(Integer::intValue).sum();
            if (outside && !g.depositMissed) gone = inChest(g, gone);
            g.depositWeighed = false;
            g.depositOwedShort = false;
            g.depositMissed = false;
            g.depositTook = null;
            if (outside) {
                g.deposit = Math.max(0, g.deposit - gone);
                if (g.deposit <= 0) return new Out(Kind.WOKE_GONE, g.r.key() + " woke: its deposit went with the line already (" + Step.Deposit.onlyText(went)
                        + ") - nothing more to deposit; the line's deposit of " + Step.Deposit.onlyText(dueOf(back)) + " stays due", null);
            }
            dueLine.remove(g.line);
            Map<String, Integer> was = dueOf(back);
            for (Goals.Goal o : back.values()) due.remove(o);
            back.remove(g.r.key(), g);
            if (!back.isEmpty()) lineEnded.put(g.line, back);
            lineOpen.computeIfAbsent(g.line, k -> new LinkedHashSet<>()).add(g.r.key());
            Map<String, Integer> still = dueNow();
            return new Out(Kind.WOKE_TAKEN_BACK, g.r.key() + " woke: the line's deposit of " + Step.Deposit.onlyText(was) + " had not started - taken back, it is weighed on the bag again when "
                    + g.r.key() + " ends" + (gone > 0 ? " (" + Step.Deposit.onlyText(went) + " of it went with an earlier deposit; the rest of its amount, " + g.deposit + ", follows then)" : "")
                    + (still.isEmpty() ? "" : " (still due: " + Step.Deposit.onlyText(still) + ")"), still.isEmpty() ? null : still);
        }
        Map<String, Integer> way = wayLine.get(g.line);
        g.depositWeighed = false;
        g.depositOwedShort = false;
        Map<String, Integer> took = g.depositTook == null ? Map.of() : g.depositTook;
        g.depositTook = null;
        Map<String, Goals.Goal> ended = lineEnded.get(g.line);
        int noted = took.values().stream().mapToInt(Integer::intValue).sum();
        boolean wasOpen = ended != null && ended.remove(g.r.key()) != null;
        // merge review 2026-11-23 (a deposit run that failed or was refused, or a line that ended with no main chest set:
        // the noted items are still in the bag, and the wake cut the order's deposit amount by them - "its deposit went
        // with the line already", and the later end deposited less or nothing): the line's deposit did not reach the
        // chest (gone(false), NO_CHEST), so nothing is taken off the amount, and the line says so (rule 7)
        boolean missed = g.depositMissed;
        g.depositMissed = false;
        if (wasOpen || missed) {
            // the line has not ended, or its deposit never reached the chest: the order is simply open again (its note
            // was of the bag then, nothing was taken)
        } else if (onWay && wayOrders.contains(g)) {
            // review 7 (point 2): its put is on its way: what the line took of it (in the chest by the legs so far, or still on
            // its way) is done with for the order; what of it reaches the chest later is the put's, never cut from the order
            // again (wayCut), so a second start that adds the order's new due amount to its remainder puts each item once
            g.deposit = Math.max(0, g.deposit - noted);
            g.depositPut.clear();
            Map<String, Integer> rest = this.way.get(g);
            // review 8: all of it still on its way is cut now (an earlier cut is a part of it), never more than the way holds
            if (rest != null && !rest.isEmpty()) wayCut.put(g, new TreeMap<>(rest));
        } else if (noted > 0 || !g.depositPut.isEmpty()) {
            g.deposit = Math.max(0, g.deposit - inChest(g, noted));       // the line's deposit took them already
        }
        if (g.deposit <= 0) return new Out(Kind.WOKE_GONE, g.r.key() + " woke: its deposit went with the line already (" + Step.Deposit.onlyText(took) + ") - nothing more to deposit", null);
        lineOpen.computeIfAbsent(g.line, k -> new LinkedHashSet<>()).add(g.r.key());
        String line = wasOpen ? "the line's deposit waits for it"
                : missed ? "the line's deposit did not reach the chest: nothing is taken off its amount (" + g.deposit + "), it follows when it ends"
                : way != null ? "the line's deposit of " + Step.Deposit.onlyText(way) + " is on its way; the rest of its amount (" + g.deposit + ") follows when it ends"
                : "the line's deposit went already; the rest of its amount (" + g.deposit + ") follows when it ends";
        return new Out(Kind.WOKE_OPEN, g.r.key() + " woke: its deposit items are weighed again when it ends (its stop noted "
                + (noted == 0 ? "nothing" : Step.Deposit.onlyText(took)) + "; " + line + ")", null);
    }

    /**
     * The goals tick's pass over one open order; null: nothing to do.
     *
     * <p>parked-holds (owner 2026-11-23, in place of his 2026-11-02 "a stopped order's bag goes to the chest too" for this
     * case; tester-1 w001, tester-2 w053-3 and three more: the line went to the chest short because its last open order
     * was only parked): a parked -d order that can still wake by itself ({@link Goals#canWake}, the one fact every reader
     * asks) HOLDS its line's deposit - it is not weighed, it stays among the line's open orders, and that is said once per
     * hold ({@link Kind#HELD}). The line ends when it is met, when it stops with no wake left, or when the owner drops it
     * ({@link #dropped}). A parked order with no wake (only the owner's word changes it) is weighed as "stopped" as before:
     * its bag goes to the chest. An order weighed and running again (it woke) is opened again.
     */
    public Out sweep(Goals.Goal g, Map<String, Integer> bag, String mainAt) { return sweep(g, bag, mainAt, Goals.canWake(g) ? Goals.wake(g) : null); }

    /**
     * hold-bound (owner 2026-11-24, plan step 2): {@link #sweep} where {@code changes} is what can still change a fact the
     * park waits on ({@link Goals#stillChanges}); null: nothing can, so a hold ends - the order is weighed as stopped, the
     * line's deposit is owed and goes, its end names what the order did not deliver (rule 7). A later wake reopens it
     * ({@link #reopen}) and its amount follows with a deposit of its own.
     */
    public Out sweep(Goals.Goal g, Map<String, Integer> bag, String mainAt, String changes) {
        if (g.parked != null) {
            boolean holds = !g.depositWeighed && g.deposit > 0 && g.strip == null && Goals.canWake(g);
            if (holds && changes != null) {
                if (g.depositHeld) return null;         // said when the hold began
                g.depositHeld = true;
                return new Out(Kind.HELD, g.r.key() + " stopped: the line's deposit waits for it - it can still wake (" + Goals.wake(g) + "; what can change that: " + changes + "); in the bag for the line now: "
                        + soFar(g, bag) + "; aa cancel " + g.r.key() + " ends the line without it", null);
            }
            if (holds && !g.depositWeighed) {
                // a group order: each member is capped at the deposit, so the sum is capped at it too (never "40 of 32")
                int got = Math.min(g.deposit, take(g, bag).values().stream().mapToInt(Integer::intValue).sum());
                Out owed = weigh(g,"stopped, and nothing it waits on can change now (every order is stopped and nothing runs; it waits on: " + Goals.wake(g)
                        + "): the line's deposit is owed and goes without the rest of it - short: " + g.r.key() + " " + got + " of " + g.deposit
                        + "; if it wakes later, its amount follows with a deposit of its own", bag, mainAt);
                // short-wake: the promise just said is kept by the wake ({@link #restDue}): only this order leaves the due deposit
                g.depositOwedShort = g.depositWeighed;
                return owed;
            }
            return weigh(g, "stopped", bag, mainAt);
        }
        g.depositHeld = false;
        if (g.depositWeighed && g.deposit > 0 && g.strip == null) return reopen(g);
        return null;
    }

    /** parked-holds: what the bag holds now for the line of {@code g}: its ended orders' amounts and its own, as text. */
    private String soFar(Goals.Goal g, Map<String, Integer> bag) {
        Map<String, Goals.Goal> with = new java.util.LinkedHashMap<>();
        Map<String, Goals.Goal> ended = lineEnded.get(g.line);
        if (ended != null) with.putAll(ended);
        with.put(g.r.key(), g);
        Map<String, Integer> all = all(with, bag, false);
        all.values().removeIf(n -> n <= 0);
        return all.isEmpty() ? "nothing" : Step.Deposit.onlyText(all);
    }

    /**
     * parked-holds (rule 7: aa goals and aa why): one line per order of {@code goals} that holds its line's deposit now -
     * the order, what the bag holds for the line, what wakes it and the owner's way out. Empty: no deposit is held.
     */
    public java.util.List<String> held(java.util.List<Goals.Goal> goals, Map<String, Integer> bag) {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (Goals.Goal g : goals) {
            if (!g.depositHeld || g.parked == null) continue;
            out.add("deposit held: the line's deposit (in the bag now: " + (bag == null ? "bag not read" : soFar(g, bag)) + ") waits for " + g.r.key() + ", stopped - " + Goals.wake(g)
                    + "; aa cancel " + g.r.key() + " ends the line without it, aa want " + g.r.word().trim() + " orders it anew on a line of its own (this line ends without it too)");
        }
        return out;
    }
}
