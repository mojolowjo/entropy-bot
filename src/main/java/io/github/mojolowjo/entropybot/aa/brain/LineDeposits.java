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

    /** Per aa want line, the -d orders not ended yet, and the items collected so far. */
    private final Map<String, Set<String>> lineOpen = new HashMap<>();
    private final Map<String, Map<String, Integer>> lineItems = new HashMap<>();
    private int lineSerial;

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
        if (g.depositWeighed) return new Out(Kind.ALREADY, null, null);
        g.depositWeighed = true;
        if (g.strip != null) return new Out(Kind.STRIP, null, null);     // the strip order has its own deposit leg
        if (g.deposit <= 0) return new Out(Kind.NO_WORD, g.r.key() + " " + how + ": no deposit word on the order (-d, or a trailing deposit) - its items stay in the bag", null);
        Map<String, Integer> items = lineItems.computeIfAbsent(g.line, k -> new TreeMap<>());
        int left = g.deposit;
        g.depositTook = new TreeMap<>();
        for (String id : g.r.counts()) {
            int have = bag.getOrDefault(id, 0);
            int take = g.r.group() ? Math.min(have, g.deposit) : Math.min(have, left);
            if (take > 0) { items.merge(id, take, Integer::sum); g.depositTook.merge(id, take, Integer::sum); left -= take; }
            if (!g.r.group() && left <= 0) break;
        }
        Set<String> open = lineOpen.get(g.line);
        if (open != null) open.remove(g.r.key());
        if (open != null && !open.isEmpty())
            return new Out(Kind.NOTED, g.r.key() + " " + how + ": its deposit items noted (" + Step.Deposit.onlyText(items) + "); the line's deposit follows when "
                    + String.join(", ", open) + " end" + (open.size() == 1 ? "s" : ""), null);
        lineOpen.remove(g.line);
        Map<String, Integer> all = lineItems.remove(g.line);
        if (all == null || all.isEmpty()) return new Out(Kind.NOTHING, g.r.key() + " " + how + ": nothing of the line's deposit items in the bag - no deposit", null);
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
        Map<String, Integer> items = lineItems.get(g.line);
        int noted = took.values().stream().mapToInt(Integer::intValue).sum();
        if (items != null) {
            for (var e : took.entrySet()) { int n = items.getOrDefault(e.getKey(), 0) - e.getValue(); if (n > 0) items.put(e.getKey(), n); else items.remove(e.getKey()); }
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
