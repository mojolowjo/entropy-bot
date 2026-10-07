package io.github.mojolowjo.entropybot.brain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * B1 (BRAIN_LOOP step 2 and 3, the owner's answer 4): the changes between two loops as events, each with its rank:
 * danger (the B2 threat test counted a mob: a fight, flight or retreat runs; or health below {@code dangerHealth}) >
 * the owner's order > broken tool / hungry / bag full > the rest (night, seen, job done). An event interrupts a running
 * job only when it outranks it; the brain's own jobs rank as "the rest". Pure.
 */
public final class Interrupts {
    private Interrupts() {}

    public enum Event {
        DANGER(0), OWNER(1), BROKEN(2), HUNGRY(2), FULL(2), NIGHT(3), SEEN(3), DONE(3);

        public final int rank;

        Event(int rank) { this.rank = rank; }
    }

    /** The rank of a job the brain started (lower outranks). */
    public static final int BRAIN_JOB_RANK = 3;

    public static List<Event> events(BrainState prev, BrainState now, BrainConfig c) {
        List<Event> out = new ArrayList<>();
        if (now.danger || now.health < c.i("dangerHealth")) out.add(Event.DANGER);
        if (now.ownerJob != null && (prev == null || prev.ownerJob == null)) out.add(Event.OWNER);
        if (now.toolBroke || (now.pickaxes > 0 && now.pickPct <= c.i("toolsWornPct") && SupplyCheck.tier(now.stage) != null)) out.add(Event.BROKEN);
        if (now.food <= 14) out.add(Event.HUNGRY);
        if (now.freeSlots <= 4) out.add(Event.FULL);
        if (now.night && (prev == null || !prev.night)) out.add(Event.NIGHT);
        if (now.ownerPos != null && (prev == null || prev.ownerPos == null)) out.add(Event.SEEN);
        if (prev != null && prev.brainJob != null && now.brainJob == null) out.add(Event.DONE);
        out.sort(Comparator.comparingInt(e -> e.rank));
        return out;
    }

    public static boolean outranks(Event e, int runningRank) { return e.rank < runningRank; }

    /**
     * Mid-job only (BRAIN_PLAN 4.4): what stops a running brain job to be handled first, as {event, handler chain, why},
     * or null. Something broke (no pickaxe left), food at {@code hungryInterrupt} or below, the bag full.
     */
    public static String[] midJob(BrainState s, BrainConfig c) {
        String tier = SupplyCheck.tier(s.stage);
        if (s.toolBroke && s.pickaxes == 0 && tier != null) return new String[]{"BROKEN", "craft " + tier + "_pickaxe 1", "my pickaxe broke"};
        if (s.food <= c.i("hungryInterrupt")) return new String[]{"HUNGRY", "eat", "food " + s.food + "/20"};
        if (s.freeSlots <= c.i("fullInterrupt")) return new String[]{"FULL", "deposit", "my bag is full"};
        return null;
    }
}
