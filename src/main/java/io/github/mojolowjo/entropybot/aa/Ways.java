package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.brain.Goals;
import io.github.mojolowjo.entropybot.aa.brain.Unit;
import io.github.mojolowjo.entropybot.aa.core.Blackboard;
import io.github.mojolowjo.entropybot.aa.moves.Step;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * Round 4A N, one-planner: the rows every {@link Need} shares. A need's goal "I need N of X" ({@link Step.Obtain}) is
 * priced by the one planner, {@link NeedChain}, on the facts of the moment: its chain is a row at its own price (seconds,
 * TIME_MODEL), an explore chain carries the explore leg, no chain at all is dropped with its reason. The climb out from
 * under ground is a priced first step ({@link WayUp} over the surface facts) of a need whose work wants open ground. Every
 * number comes from Tuning or the facts and is printed in the row's reason. Option costs are walk-blocks; seconds are
 * converted with {@link Unit#blocks}. Pure but for {@code bb.port} (JUnit: NeedTest). Loader notes: none.
 */
public final class Ways {
    private Ways() {}

    /** The way of a row whose command is a prerequisite (the need waits for the facts after it, then picks again). */
    public static final String PREREQ = "prereq";

    /** The chain for a need's goal: {@code chain} null when there is no world to price on yet ({@code why} says so). */
    public record Plan(Step.Obtain goal, NeedChain.Chain chain, String why) {
        /** A chain that can run: it has steps (an explore chain too). */
        public boolean ready() { return chain != null && chain.ok() && !chain.steps().isEmpty(); }

        public List<Step> steps() { return chain == null ? null : chain.steps(); }

        public double cost() { return chain == null ? 0 : chain.cost(); }
    }

    /** The goal of a need's steps when it is one {@link Step.Obtain}, else null: a plain way. */
    public static Step.Obtain obtainOf(List<Step> goal) {
        return goal != null && goal.size() == 1 && goal.get(0) instanceof Step.Obtain o ? o : null;
    }

    /** The Failures key a chain row is held under. */
    public static String chainKey(Step.Obtain o) { return "chain " + o.text(); }

    /**
     * The chain for the need's goal (one {@link Step.Obtain}; any other steps: null, a plain way), on the world fact
     * ({@link NeedChain#WORLD_FACT}); a chain held in Failures after it failed is dropped with that reason.
     */
    public static Plan plan(Blackboard bb, io.github.mojolowjo.entropybot.aa.brain.Failures f, String need, List<Step> goalSteps, long now) {
        Step.Obtain o = obtainOf(goalSteps);
        if (o == null) return null;
        String held = f == null ? null : f.held(need, chainKey(o), now);
        if (held != null) return new Plan(o, null, "held: " + held);
        NeedChain.World w = Goals.world(bb);
        if (w == null) return new Plan(o, null, "no facts for the chain yet (the senses are not ready)");
        NeedChain.Chain c = NeedChain.of(o, w);
        if (!c.ok()) return new Plan(o, c, c.why());
        if (c.steps().isEmpty()) return new Plan(o, c, o.what() + " is in the bag");
        return new Plan(o, c, null);
    }

    /**
     * The rows of a need with no option set of its own: its plain way at the need's hand price, or its chain (one row at the
     * chain's price; none: dropped with its reason).
     */
    public static Options.Choice goalRows(List<Step> goal, Plan p, Options.OptionMemory m, long now, double handS) {
        List<Options.Option> rows = new ArrayList<>();
        List<String> dropped = new ArrayList<>();
        String label = Step.label(goal);
        if (p == null) {
            String key = "way " + label;
            if (m.blocked(key, now)) dropped.add(label + ": " + m.reason(key));
            else rows.add(Options.typed(label, "command", goal, Unit.blocks(handS), "hand " + Math.round(handS) + " s", key, m));
        } else if (p.ready()) {
            String key = chainKey(p.goal());
            if (m.blocked(key, now)) dropped.add("chain: " + m.reason(key));
            else rows.add(Options.typed("chain", "chain", p.steps(), p.cost(), p.chain().why(), key, true, m));
        } else {
            dropped.add("chain: " + p.why());
        }
        return Options.Choice.of(rows, dropped, "no way to " + label);
    }

    /** A priced first step (the climb): option null = the step is needed and no way does it ({@code why} says why). */
    public record Pre(Options.Option option, double cost, String why, String key) {
        public double seconds() { return Unit.seconds(cost); }
    }

    /**
     * Under {@link AaLadder#SURFACE_DEPTH}+ of ground, the climb is the first step of a surface need's row: the best of
     * WayUp's ways over the surface facts (none readable: the climb verb at depth x CLIMB_BLOCK + CLIMB_FIX); null when
     * the bot is not under ground.
     */
    public static Pre climb(Blackboard bb, Options.OptionMemory m, long now) {
        int d = bb.in.underDepth;
        if (d < AaLadder.surfaceDepth()) return null;
        if (bb.fact(RungOptions.SURFACE_FACTS) instanceof Supplier<?> sup) {
            Object fo = null;
            try {
                fo = sup.get();
            } catch (RuntimeException e) {
                bb.port.log("surface facts: " + e);
            }
            if (fo instanceof RungOptions.SurfaceFacts f) {
                Options.Choice c = WayUp.choose(f, m, now);
                Options.Option b = c.best();
                if (b == null) return new Pre(null, Double.POSITIVE_INFINITY, d + " under ground, no way up (" + c.why() + ")", null);
                if (Options.NOTHING.equals(b.command())) return null;        // open sky is a short walk: the row's own walk gets there
                return new Pre(b, b.total(), "climb " + d + " up: " + b.text(), b.memoryKey());
            }
        }
        double cost = d * RungOptions.climbBlock() + RungOptions.climbFix();
        String key = "climb";
        if (m.blocked(key, now)) return new Pre(null, Double.POSITIVE_INFINITY, d + " under ground, the climb is held: " + m.reason(key), null);
        String why = "climb " + d + " up " + Math.round(cost) + " (no surface facts: " + d + " x " + Math.round(RungOptions.climbBlock()) + " + " + Math.round(RungOptions.climbFix()) + ")";
        return new Pre(new Options.Option("climb", "climb", List.of(new Step.Climb()), cost, why, key, false), cost, why, key);
    }

    /** "12 s" for a row cost in walk-blocks. */
    static String secs(double blocks) { return String.format(Locale.ROOT, "%d s", Math.round(Unit.seconds(blocks))); }
}
