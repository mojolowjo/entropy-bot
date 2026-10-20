package io.github.mojolowjo.entropybot.aa.brain;

import java.util.ArrayList;
import java.util.List;

/**
 * AA_ROUND3 2 (director's design, approved): order planning within the horizon. From the scored list (winner first, then
 * up to {@link #K} - 1 more wanted desires, each with its best option's cost in seconds and its site) build a plan of at
 * most {@link #MAX} desires by greedy insertion: each other desire is tried at every slot; it is kept when
 * {@code cost(plan + it) <= cost(plan) + cost(it alone) - SAVE x cost(it alone)}. Plan cost = for each step the travel
 * from the previous site (the bot's position first; within {@link Site#SAME} = 0, distance / {@link Site#SPEED}) plus
 * its work (its own cost less its walk there from the bot). A desire whose slot would start after {@code horizonS} is
 * not sequenced; a desire without a site is never inserted. Value is not re-weighed; only cost. Pure (SequencerTest).
 */
public final class Sequencer {
    private Sequencer() {}

    public static final int K = 6, MAX = 3;
    public static final double SAVE = 0.15;
    public static final int DEFAULT_HORIZON_S = 600;

    /** A candidate: its id, its own cost in seconds (the round trip from here), its site (null: unknown). */
    public record Item(String id, double costS, Site site) {}

    /** One plan step: the saving it brought (seconds; 0 for the first) and how ("same place" / "on the way"). */
    public record Step(String id, Site site, double savedS, String how) {}

    public record Plan(List<Step> steps, double costS, String note) {
        public String next(String after) {
            for (int i = 0; i + 1 < steps.size(); i++) if (steps.get(i).id().equals(after)) return steps.get(i + 1).id();
            return null;
        }

        public String first() { return steps.isEmpty() ? null : steps.get(0).id(); }

        /** "iron (at -65 57 -10) then coal (same place, saves 40 s)". */
        public String text() {
            if (steps.isEmpty()) return note == null ? "none" : "none (" + note + ")";
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < steps.size(); i++) {
                Step s = steps.get(i);
                if (i > 0) b.append(" then ");
                b.append(s.id());
                if (i == 0 && s.site() != null) b.append(" (at ").append(s.site().text()).append(")");
                if (s.how().equals("first")) { if (i > 0) b.append(" (the top pick)"); }
                else b.append(" (").append(s.how()).append(", saves ").append(Math.round(s.savedS())).append(" s)");
            }
            if (note != null) b.append("; ").append(note);
            return b.toString();
        }
    }

    /** The work part of an item: its cost less its walk there from {@code here} (never below 0). */
    static double work(Site here, Item it) {
        double t = here == null || it.site() == null ? 0 : it.site().travelS(here);
        if (Double.isInfinite(t)) t = 0;
        return Math.max(0, it.costS() - t);
    }

    /** The plan's cost from {@code here}, and each slot's start (seconds) in {@code starts} (may be null). */
    static double cost(Site here, List<Item> plan, double[] starts) {
        double c = 0;
        Site at = here;
        for (int i = 0; i < plan.size(); i++) {
            Item it = plan.get(i);
            double t = at == null || it.site() == null ? 0 : it.site().travelS(at);
            if (Double.isInfinite(t)) return Double.POSITIVE_INFINITY;
            if (starts != null) starts[i] = c + t;
            c += t + work(here, it);
            if (it.site() != null) at = it.site();
        }
        return c;
    }

    /**
     * @param here     the bot's position (null: no plan, only the winner)
     * @param ranked   wanted desires, highest rank first (the winner first); only the first {@link #K} are looked at
     * @param horizonS the horizon in seconds ({@code aa horizon})
     */
    public static Plan build(Site here, List<Item> ranked, double horizonS) {
        if (ranked == null || ranked.isEmpty()) return new Plan(List.of(), 0, "nothing wanted");
        Item win = ranked.get(0);
        List<Item> plan = new ArrayList<>(List.of(win));
        List<Step> steps = new ArrayList<>(List.of(new Step(win.id(), win.site(), 0, "first")));
        if (here == null || win.site() == null || Double.isNaN(win.costS()))
            return new Plan(steps, win.costS(), here == null ? "no position" : win.site() == null ? "the winner has no site" : "the winner has no cost");
        String note = null;
        for (int k = 1; k < Math.min(K, ranked.size()) && plan.size() < MAX; k++) {
            Item it = ranked.get(k);
            if (it.site() == null || Double.isNaN(it.costS()) || it.costS() <= 0) continue;
            double base = cost(here, plan, null), alone = it.costS();
            double best = Double.POSITIVE_INFINITY;
            int bestAt = -1;
            for (int at = 0; at <= plan.size(); at++) {
                List<Item> tryPlan = new ArrayList<>(plan);
                tryPlan.add(at, it);
                double[] starts = new double[tryPlan.size()];
                double c = cost(here, tryPlan, starts);
                boolean late = false;
                for (double s : starts) if (s > horizonS) late = true;
                if (late) continue;
                if (c < best) { best = c; bestAt = at; }
            }
            if (bestAt < 0) { note = it.id() + " past the horizon"; continue; }
            double saved = base + alone - best;
            if (saved < SAVE * alone) continue;
            plan.add(bestAt, it);
            Item prev = bestAt > 0 ? plan.get(bestAt - 1) : null;
            Item nxt = bestAt + 1 < plan.size() ? plan.get(bestAt + 1) : null;
            boolean same = (prev != null && prev.site() != null && prev.site().near(it.site())) || (nxt != null && nxt.site() != null && nxt.site().near(it.site()));
            steps.add(bestAt, new Step(it.id(), it.site(), saved, same ? "same place" : "on the way"));
        }
        return new Plan(steps, cost(here, plan, null), note);
    }
}
