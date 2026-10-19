package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.brain.Brain;
import io.github.mojolowjo.entropybot.aa.brain.Curves;
import io.github.mojolowjo.entropybot.aa.brain.Desire;
import io.github.mojolowjo.entropybot.aa.brain.TechTree;
import io.github.mojolowjo.entropybot.aa.core.Blackboard;
import io.github.mojolowjo.entropybot.aa.core.Cooldown;
import io.github.mojolowjo.entropybot.aa.core.JobPort;
import io.github.mojolowjo.entropybot.aa.core.Node;
import io.github.mojolowjo.entropybot.aa.core.RunJob;
import io.github.mojolowjo.entropybot.aa.core.Scorer;
import io.github.mojolowjo.entropybot.aa.core.TaskKind;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.ToDoubleFunction;

/**
 * BRAIN_PLAN B1/B2: the ladder's rungs, the idle options and the tech tree's ideas as desires of one {@link Brain}.
 *
 * <p>B2 scores (0..100; a met rung -1; the owner's {@code aa do} 100):
 * <ul>
 * <li>GATE: area 100, tree area 95, surface 90 (hard limits; never interrupt).</li>
 * <li>UPKEEP: tools (no pickaxe 74, wooden 65, worn 40 + curve), food {@code 125 x shortfall^2} of 18 (10 -> 25,
 *     4 -> 76: urgent), torches 30 + {@code 30 x shortfall^2} of 16, bag 20 + {@code 60 x shortfall^2} of 10 free.
 *     Tools stay under 75 on purpose: a pickaxe count that flickers in a craft must not break a running task.</li>
 * <li>PROGRESS (value, hand cost s; the brain applies P and cost): table/furnace 70/60, spare table 50/10, base 55/240,
 *     bed 45/120, iron 40 + 30 x the tech tree's iron share / 600. Iron gives its value to tools while it has no stone
 *     pickaxe; torches to table/furnace while no table is known; ideas to iron while under an iron pickaxe.</li>
 * <li>Idle options: their {@link AaScorer} deficit x {@link #IDLE_SCALE} (25 at most): below iron while iron runs well,
 *     above it after two failures in a row (P 0.5).</li>
 * <li>ideas (2.8): the best thing the recipes say can be made now, from an iron pickaxe on: value 30 x its share.</li>
 * </ul>
 * With these numbers a Peaceful fresh world runs in the ladder's order (area, tools, table/furnace, torches, base, iron).
 */
public final class AaDesires {
    private AaDesires() {}

    public static final double IDLE_SCALE = 0.25;
    /** Blackboard facts the adapter fills: the {@link TechTree} and the set of item ids had (bag + known stations). */
    public static final String TECH_FACT = "aa.techTree", HAVE_FACT = "aa.have";

    static Desire.Kind kindOf(String rungId) {
        return switch (rungId) {
            case "needs.area", "needs.woodArea", "needs.surface" -> Desire.Kind.GATE;
            case "needs.tools", "needs.food", "needs.torches", "needs.bag" -> Desire.Kind.UPKEEP;
            default -> Desire.Kind.PROGRESS;
        };
    }

    /** A rung's score: 100 for the owner's aa do, -1 when skipped or met, else {@code f}. */
    static double rungScore(Rung r, Blackboard bb, ToDoubleFunction<Blackboard> f) {
        AaManual man = AaManual.of(bb);
        if (r.id.equals(man.doRung)) return 100;
        if (man.skipped(r.id)) return -1;
        return r.unmet(bb) ? f.applyAsDouble(bb) : -1;
    }

    static double tools(Blackboard bb) {
        if (bb.in.pickTier == 0) return 74;
        if (bb.in.pickTier < 2) return 65;
        return 40 + Curves.upkeep(30, bb.in.pickPct, 15);
    }

    @SuppressWarnings("unchecked")
    static Set<String> have(Blackboard bb) {
        Object o = bb.fact(HAVE_FACT);
        return o instanceof Set<?> s ? (Set<String>) s : Set.of();
    }

    /** Wool for a bed (3) needs a source: wool or 12 string had, or a sheep seen. Null = feasible. */
    public static String woolSource(int wool, int string, boolean sheepSeen) {
        return wool >= 3 || string >= 12 || sheepSeen ? null : "no wool source (" + wool + "/3 wool, " + string + "/12 string, no sheep seen)";
    }

    static String bedInfeasible(Blackboard bb) {
        return bb.in.bedKnown || bb.in.bedInBag ? null : woolSource(bb.in.wool, bb.in.string, bb.in.sheepSeen);
    }

    static TechTree tree(Blackboard bb) { return bb.fact(TECH_FACT) instanceof TechTree t ? t : null; }

    /** Iron's value: 40 + 30 x the tech tree's share of iron ingots (no tree yet: the full 30). */
    static double ironValue(Blackboard bb) {
        TechTree t = tree(bb);
        return 40 + 30 * (t == null || t.size() == 0 ? 1 : t.norm("iron_ingot", have(bb)));
    }

    public static Desire rung(Rung r) {
        Desire d = switch (r.id) {
            case "needs.area" -> new Desire(r.id, Desire.Kind.GATE, r, bb -> rungScore(r, bb, b -> 100));
            case "needs.woodArea" -> new Desire(r.id, Desire.Kind.GATE, r, bb -> rungScore(r, bb, b -> 95));
            case "needs.surface" -> new Desire(r.id, Desire.Kind.GATE, r, bb -> rungScore(r, bb, b -> 90));
            case "needs.tools" -> new Desire(r.id, Desire.Kind.UPKEEP, r, bb -> rungScore(r, bb, AaDesires::tools));
            case "needs.food" -> new Desire(r.id, Desire.Kind.UPKEEP, r, bb -> rungScore(r, bb, b -> Math.max(1, Curves.upkeep(125, b.in.food, 18))));
            case "needs.torches" -> new Desire(r.id, Desire.Kind.UPKEEP, r, bb -> rungScore(r, bb, b -> 30 + Curves.upkeep(30, b.in.torches, 16)))
                    .prereq(b -> b.in.tableKnown || b.in.tableInBag ? null : "needs.tableFurnace");
            case "needs.bag" -> new Desire(r.id, Desire.Kind.UPKEEP, r, bb -> rungScore(r, bb, b -> 20 + Curves.upkeep(60, b.in.freeSlots, 10)));
            case "needs.tableFurnace" -> new Desire(r.id, Desire.Kind.PROGRESS, r, bb -> rungScore(r, bb, b -> 70)).cost(60);
            case "needs.spareTable" -> new Desire(r.id, Desire.Kind.PROGRESS, r, bb -> rungScore(r, bb, b -> 50)).cost(10);
            case "needs.base" -> new Desire(r.id, Desire.Kind.PROGRESS, r, bb -> rungScore(r, bb, b -> 55)).cost(240);
            case "needs.bed" -> new Desire(r.id, Desire.Kind.PROGRESS, r, bb -> rungScore(r, bb, b -> 45)).cost(120).infeasible(AaDesires::bedInfeasible);
            case "needs.iron" -> new Desire(r.id, Desire.Kind.PROGRESS, r, bb -> rungScore(r, bb, AaDesires::ironValue)).cost(600)
                    .prereq(b -> b.in.pickTier < 2 ? "needs.tools" : null);
            default -> new Desire(r.id, kindOf(r.id), r, bb -> rungScore(r, bb, b -> 30));
        };
        return d;
    }

    public static Desire idle(TaskKind kind, Node node, Scorer scorer) {
        return new Desire("idle." + kind.name().toLowerCase(java.util.Locale.ROOT), Desire.Kind.UPKEEP, node,
                bb -> {
                    // a cooling option is left out (the idle chooser's rule), so the next one gets its turn
                    if (node instanceof Cooldown cd && cd.cooling(bb.clock.now())) return -1;
                    double s = scorer.score(bb.in)[kind.ordinal()];
                    return s < 0 ? -1 : s * IDLE_SCALE;
                }).lastTry();
    }

    /** The idea the ideas desire works on now (null none), with its share 0..1. */
    record Pick(String item, double share) {}

    static Pick bestIdea(Blackboard bb) {
        TechTree t = tree(bb);
        if (t == null || bb.in.pickTier < 3) return null;
        Set<String> have = have(bb);
        for (TechTree.Idea i : t.ideas(have, 8)) {
            if (bad(bb).contains(i.item())) continue;
            double share = t.norm(i.item(), have);
            return new Pick(i.item(), Math.max(0.2, share));
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    static Set<String> bad(Blackboard bb) {
        if (bb.fact("aa.badIdeas") instanceof Set<?> s) return (Set<String>) s;
        Set<String> s = new java.util.HashSet<>();
        bb.fact("aa.badIdeas", s);
        return s;
    }

    /** "minecraft:shield" -> "shield"; a modded id stays whole. */
    static String goalName(String id) { return id.startsWith("minecraft:") ? id.substring(10) : id; }

    /** 2.8: the tech tree's best idea as a planner goal ("goal shield 1"), from an iron pickaxe on. */
    public static Desire ideas() {
        RunJob job = new RunJob("ideas.job", TaskKind.EXPLORE, bb -> {
            Pick p = bestIdea(bb);
            if (p == null) return null;
            String chain = bb.port.resolve("goal " + goalName(p.item()) + " 1");
            if (chain == null) {             // review: no plan for it: left out for this session, the next idea gets its turn
                bad(bb).add(p.item());
                bb.port.log("ideas: no plan for " + goalName(p.item()) + " - left out this session");
            }
            return chain == null ? null : JobPort.TaskSpec.of(TaskKind.EXPLORE, chain, "ideas");
        });
        Node node = AaTree.idleCd("ideas.cd", job);
        return new Desire("ideas", Desire.Kind.PROGRESS, node, bb -> {
            if (node instanceof Cooldown cd && cd.cooling(bb.clock.now())) return -1;
            Pick p = bestIdea(bb);
            return p == null ? -1 : 30 * p.share();
        }).cost(300).prereq(b -> b.in.pickTier < 3 ? "needs.iron" : null).lastTry();
    }

    /** The top ideas for aa tree / aa why ("shield 3.0, bucket 3.0"), or why there are none. */
    public static String ideasLine(Blackboard bb, int n) {
        TechTree t = tree(bb);
        if (t == null) return "ideas: no recipe graph yet";
        List<String> out = new ArrayList<>();
        for (TechTree.Idea i : t.ideas(have(bb), n)) out.add(goalName(i.item()) + " " + Math.round(i.worth() * 10) / 10.0);
        return "ideas (" + t.size() + " recipes" + (bb.in.pickTier < 3 ? "; worked on from an iron pickaxe" : "") + "): "
                + (out.isEmpty() ? "none" : String.join(", ", out));
    }

    /** The brain over the rungs, the idle options and the ideas. */
    public static Brain build(List<Rung> rungs, List<Map.Entry<TaskKind, Node>> idle, Scorer scorer) {
        List<Desire> ds = new ArrayList<>();
        for (Rung r : rungs) ds.add(rung(r));
        ds.add(ideas());
        for (var e : idle) ds.add(idle(e.getKey(), e.getValue(), scorer));
        return new Brain("brain", "the scored desires", ds.toArray(new Desire[0]));
    }
}
