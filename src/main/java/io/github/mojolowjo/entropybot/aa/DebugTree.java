package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.brain.Goals;
import io.github.mojolowjo.entropybot.aa.core.Blackboard;
import io.github.mojolowjo.entropybot.aa.moves.Step;
import io.github.mojolowjo.entropybot.aa.moves.Steps;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * 0.39.z28 aa debug (owner 2026-10-23: "does all the thinking ... but never toggles the bot to start moving, then prints
 * what its thought process tree was"): an owner order priced by the one chain exactly as the order's run would price it
 * ({@link Goals#chain} on the world fact now), recorded ({@link NeedChain#traced}), printed as one indented tree. It
 * reads only: the goal is a draft ({@link Goals#draft}, never in the goals list), nothing is started. Every number and
 * phrase in it is the chain's own (its line, its dropped list, its trace); the first step is said as the run says it
 * ({@link Steps#started}). Pure (JUnit: DebugTreeTest). Loader notes: none.
 */
public final class DebugTree {
    private DebugTree() {}

    /** The header every debug answer starts with: what was not done. */
    public static final String DRY = "(dry run: no goal saved, no job started, no Baritone goal, nothing moves)";

    /**
     * The tree of one item or group order. {@code blocks}: an explore word -> the blocks whose finding ends it (null: not
     * known here); {@code pick}: the pick lines (the order's score against the best scored now), from the brain's formatters;
     * {@code stations}: the station lookups' lines (aa why's).
     */
    public static String order(Blackboard bb, Goals.Goal g, String line, Function<String, Set<String>> blocks, List<String> pick, List<String> stations) {
        List<String> out = new ArrayList<>();
        out.add("aa debug " + line + " " + DRY);
        // 0.39.z36 (rule 15, NOT_LOADED): no world fact yet: the one body line says what is not ready, never an empty tree
        if (Goals.world(bb) == null) {
            out.add("  " + Goals.notReady(bb));
            return String.join("\n", out);
        }
        out.add("order: " + g.r.key() + " x" + g.n + (g.prio > 0 ? " high" : g.prio < 0 ? " low" : "") + " [" + g.r.text() + "]");
        int have = Goals.have(bb.in.bag, g);
        out.add("  need: " + Math.max(0, g.n - have) + " more " + g.r.key() + " (have " + have + " of " + g.n + ")");
        if (have >= g.n) {
            out.add("  nothing to plan: the bag holds it already (the goal would be met at once)");
            return String.join("\n", out);
        }
        String blocked = Goals.blocked(bb, g);
        if (blocked != null) out.add("  held: " + blocked);
        NeedChain.Trace t = NeedChain.trace();
        NeedChain.Chain ch = NeedChain.traced(t, () -> Goals.chain(bb, g));
        if (ch == null) {
            out.add("  chain: none (" + Goals.notReady(bb) + ")");
            return String.join("\n", out);
        }
        out.add("  chain: " + ch.why());
        out.add("  ways priced (the chain's search, in order):");
        out.addAll(t.lines(2));
        Set<String> until = untilSet(ch.steps());
        if (!until.isEmpty()) {
            out.add("  explore until one of these is known (" + until.size() + "):");
            for (String w : until) {
                Set<String> b = blocks == null ? null : blocks.apply(w);
                Set<String> from = t.untilFrom.getOrDefault(w, Set.of());
                out.add("    " + w + (b == null || b.isEmpty() ? "" : " - blocks: " + String.join(", ", b))
                        + " - put there by: " + (from.isEmpty() ? "the chain's own leaf" : String.join(", ", from)));
            }
        }
        out.add("  dropped (" + ch.dropped().size() + "):");
        for (String d : ch.dropped()) out.add("    " + d);
        if (pick != null) for (String p : pick) out.add("  " + p);
        if (stations != null) for (String s : stations) out.add("  " + s);
        out.add("  first step: " + firstStep(ch));
        return String.join("\n", out);
    }

    /** What the run would start first, in its own words; or why nothing would start. */
    public static String firstStep(NeedChain.Chain ch) {
        if (!ch.ok()) return "none: " + ch.missing();
        if (ch.steps().isEmpty()) return "none: nothing to do";
        return Steps.started(0, ch.steps()) + " (of " + Step.label(ch.steps()) + ")";
    }

    /** The explore steps' until words, in order. */
    public static Set<String> untilSet(List<Step> steps) {
        Set<String> out = new LinkedHashSet<>();
        for (Step s : steps)
            if (s instanceof Step.Explore e && e.until() != null) for (String p : e.until().split("\\|")) if (!p.isEmpty()) out.add(p);
        return out;
    }
}
