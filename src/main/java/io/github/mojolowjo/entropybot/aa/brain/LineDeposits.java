package io.github.mojolowjo.entropybot.aa.brain;

import io.github.mojolowjo.entropybot.aa.moves.Step;

import java.util.LinkedHashSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * deposit-words (owner 2026-11-08; was the helper rule of 2026-11-02: the WHOLE bag to the main chest after every order):
 * an order flagged -d that ended (done, or stopped with some of it in the bag) adds the ordered amount of its item (what
 * the bag holds of it) to its line's deposit; when the line's last -d order has ended, ONE deposit of exactly those items
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
        /** A parked order woke: its deposit went with the line already (logged). */
        WOKE_GONE,
        /** A parked order woke: weighed again at its end (logged). */
        WOKE_OPEN
    }

    /** {@code text}: the line for the log (without the "aa: " head), null: nothing to say. {@code items}: for DUE. */
    public record Out(Kind kind, String text, Map<String, Integer> items) {}

    /** Per aa want line, the -d orders not ended yet, and the ones ended so far (by key, in the order they ended). */
    private final Map<String, Set<String>> lineOpen = new HashMap<>();
    private final Map<String, Map<String, Goals.Goal>> lineEnded = new HashMap<>();
    private int lineSerial;

    /** What the bag holds of the order's deposit amount now (counted id -> n; a group: up to the amount of each member). */
    static Map<String, Integer> take(Goals.Goal g, Map<String, Integer> bag) {
        Map<String, Integer> out = new TreeMap<>();
        int left = g.deposit;
        for (String id : g.r.counts()) {
            int have = bag.getOrDefault(id, 0);
            int take = g.r.group() ? Math.min(have, g.deposit) : Math.min(have, left);
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
            how = "done (stopped earlier and met since: weighed again)";
        }
        g.depositWeighed = true;
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
        lineOpen.remove(g.line);
        lineEnded.remove(g.line);
        Map<String, Integer> all = all(ended, bag, true);
        if (all.isEmpty()) return new Out(Kind.NOTHING, g.r.key() + " " + how + ": nothing of the line's deposit items in the bag - no deposit", null);
        if (mainAt == null)
            return new Out(Kind.NO_CHEST, g.r.key() + " " + how + ": no main chest set (aa deposit x y z) - " + Step.Deposit.onlyText(all) + " stay in the bag", all);
        return new Out(Kind.DUE, g.r.key() + " " + how + ": the line's deposit of " + Step.Deposit.onlyText(all) + " to the main chest at " + mainAt + " follows", all);
    }

    /**
     * deposit-words (run JBK-T1 15:18:55: cobblestone parked 30 s in, "its deposit items noted ()", woken, done at 7:20 and
     * never weighed again - the line's deposit missed it): a parked order that woke (parked null again, still weighed) is
     * weighed again at its end. What its park noted is taken back from the line's items; when the line's deposit went
     * already, that much of its ordered amount is done with.
     */
    public Out reopen(Goals.Goal g) {
        g.depositWeighed = false;
        Map<String, Integer> took = g.depositTook == null ? Map.of() : g.depositTook;
        g.depositTook = null;
        Map<String, Goals.Goal> ended = lineEnded.get(g.line);
        int noted = took.values().stream().mapToInt(Integer::intValue).sum();
        if (ended != null && ended.remove(g.r.key()) != null) {
            // the line has not ended: the order is simply open again (its note was of the bag then, nothing was taken)
        } else if (noted > 0) {
            g.deposit = Math.max(0, g.deposit - noted);       // the line's deposit took them already
        }
        if (g.deposit <= 0) return new Out(Kind.WOKE_GONE, g.r.key() + " woke: its deposit went with the line already (" + Step.Deposit.onlyText(took) + ") - nothing more to deposit", null);
        lineOpen.computeIfAbsent(g.line, k -> new LinkedHashSet<>()).add(g.r.key());
        return new Out(Kind.WOKE_OPEN, g.r.key() + " woke: its deposit items are weighed again when it ends (its stop noted "
                + (noted == 0 ? "nothing" : Step.Deposit.onlyText(took)) + "; the line's deposit waits for it)", null);
    }

    /**
     * The goals tick's pass over one open order: a parked one is weighed as "stopped" (a stopped order's bag goes to the
     * chest too); one weighed and running again (it woke) is opened again; else null (nothing to do).
     */
    public Out sweep(Goals.Goal g, Map<String, Integer> bag, String mainAt) {
        if (g.parked != null) return weigh(g, "stopped", bag, mainAt);
        if (g.depositWeighed && g.deposit > 0 && g.strip == null) return reopen(g);
        return null;
    }
}
