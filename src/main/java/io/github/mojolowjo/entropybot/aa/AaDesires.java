package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.brain.Brain;
import io.github.mojolowjo.entropybot.aa.brain.Curves;
import io.github.mojolowjo.entropybot.aa.brain.Desire;
import io.github.mojolowjo.entropybot.aa.brain.Night;
import io.github.mojolowjo.entropybot.aa.brain.NightWork;
import io.github.mojolowjo.entropybot.aa.brain.Personality;
import io.github.mojolowjo.entropybot.aa.brain.Wants;
import io.github.mojolowjo.entropybot.aa.brain.Worth;
import io.github.mojolowjo.entropybot.aa.brain.TechTree;
import io.github.mojolowjo.entropybot.aa.core.Blackboard;
import io.github.mojolowjo.entropybot.aa.core.Cooldown;
import io.github.mojolowjo.entropybot.aa.core.JobPort;
import io.github.mojolowjo.entropybot.aa.core.Node;
import io.github.mojolowjo.entropybot.aa.core.RunJob;
import io.github.mojolowjo.entropybot.aa.core.Scorer;
import io.github.mojolowjo.entropybot.aa.core.Status;
import io.github.mojolowjo.entropybot.aa.core.TaskKind;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.ToDoubleFunction;

/**
 * BRAIN_PLAN B1/B2: the ladder's rungs, the idle options and the tech tree's ideas as desires of one {@link Brain}.
 *
 * <p>B2 scores (0..100; a met rung -1; the owner's {@code aa do} 100):
 * <ul>
 * <li>GATE: area 100, tree area 95, surface 90 (hard limits; never interrupt).</li>
 * <li>UPKEEP: tools (aa-costs: no pickaxe 55 + 30 x blocked share, wooden 55 + 20 x it, worn 40 + curve; no cap), food {@code 175 x shortfall^2} of 18 (aa-food:
 *     10 -> 35, 6 -> 78 urgent, 4 -> 100) or its stock score (25..60) under the stock target, parked with no source, torches 30 + {@code 30 x shortfall^2} of 16, bag 20 + {@code 60 x shortfall^2} of 10 free.
 *     A pickaxe count that flickers in a craft must not break a running task: the adapter smooths the tier (Costs.ToolSmoother).</li>
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

    /**
     * aa-costs (was: no pickaxe 74, wooden 65, capped under 75): the tool's real value, what it blocks next
     * (Costs.tools); the anti-flicker is the adapter's smoothed pickaxe tier (Costs.ToolSmoother), not a cap.
     */
    static double tools(Blackboard bb) {
        double blocked = io.github.mojolowjo.entropybot.aa.brain.Costs.blocked(bb.in.pickTier, bb.in.furnaceKnown || bb.in.furnaceInBag,
                bb.in.coal, bb.in.torches, ownerOreGoal(bb));
        double sword = io.github.mojolowjo.entropybot.aa.brain.Costs.sword(bb.in.zombieSeen, bb.in.armed);
        io.github.mojolowjo.entropybot.aa.brain.Costs.note(bb, "tools", io.github.mojolowjo.entropybot.aa.brain.Costs.toolsTerms(bb.in.pickTier, bb.in.pickPct, blocked)
                + (sword > 0 ? ", + " + Math.round(sword) + " no weapon (a zombie seen)" : ""));
        // the pickaxe's worth only while it is wanted (a stone pickaxe at 100%: the sword alone)
        boolean pickWanted = bb.in.pickTier < 2 || (bb.in.pickPct < 15 && bb.in.pickaxes < 2);
        return (pickWanted ? io.github.mojolowjo.entropybot.aa.brain.Costs.tools(bb.in.pickTier, bb.in.pickPct, blocked) : 30) + sword;
    }

    /** An owner goal that needs a pickaxe is open. */
    static boolean ownerOreGoal(Blackboard bb) {
        if (!(bb.fact(io.github.mojolowjo.entropybot.aa.brain.Goals.FACT) instanceof List<?>)) return false;
        for (var g : io.github.mojolowjo.entropybot.aa.brain.Goals.of(bb)) if (g.r.ore() || g.r.tier() >= 1) return true;
        return false;
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

    /** small-1: the surface rung's own score when a night mine is to be left at dawn. */
    static final double DAWN_CLIMB = 45;

    /** aa-night2 P0 1: tools stay under the full-night shelter's 99 (a tie kept the running one). */
    static final double TOOLS_MAX = 98;

    public static Desire rung(Rung r) {
        Desire d = switch (r.id) {
            // AA_ROUND2 4 (were 100/95/90): a gate scores nothing itself; what it blocks flows to it (FLOW of the best)
            case "needs.area", "needs.woodArea" -> new Desire(r.id, Desire.Kind.GATE, r, bb -> rungScore(r, bb, b -> 0));
            // small-1: the surface gate binds SURFACE desires only; the dawn climb out of a night mine is its own SURFACE desire
            case "needs.surface" -> new Desire(r.id, Desire.Kind.GATE, r, bb -> rungScore(r, bb, b -> NightWork.climbAtDawn(b, AaLadder.SURFACE_DEPTH) ? DAWN_CLIMB : 0)).exposure(Desire.Exposure.SURFACE);
            case "needs.tools" -> gated(new Desire(r.id, Desire.Kind.UPKEEP, r, bb -> rungScore(r, bb, b -> Math.min(TOOLS_MAX, factor(b, "tools") * tools(b)))), b -> null, b -> null);
            // urgent food is never parked by a gate (starving is starving); a stock-up waits for it
            case "needs.food" -> gated(new Desire(r.id, Desire.Kind.UPKEEP, r, bb -> rungScore(r, bb, AaDesires::foodScore)), b -> null, AaDesires::foodInfeasible)
                    .infeasible(b -> {
                        String no = foodInfeasible(b);
                        if (no != null || b.in.food <= 6) return no;
                        return gateWhy(b, Desire.Exposure.ANY);
                    }).prereq(b -> b.in.food <= 6 ? null : AaLadder.openGate(b, Desire.Exposure.ANY));      // review: urgent food gives no gate its value
            case "needs.torches" -> gated(new Desire(r.id, Desire.Kind.UPKEEP, r, bb -> rungScore(r, bb, b -> scaled(30 + Curves.upkeep(30, b.in.torches, 16), factor(b, "torches")))),
                    b -> b.in.tableKnown || b.in.tableInBag ? null : "needs.tableFurnace", b -> null);
            case "needs.bag" -> gated(new Desire(r.id, Desire.Kind.UPKEEP, r, bb -> rungScore(r, bb, b -> scaled(20 + Curves.upkeep(60, b.in.freeSlots, 10), factor(b, "bag")))), b -> null, b -> null);
            case "needs.tableFurnace" -> gated(new Desire(r.id, Desire.Kind.PROGRESS, r, bb -> rungScore(r, bb, AaDesires::stationValue)).cost(60).exposure(Desire.Exposure.SURFACE), b -> null, b -> null);
            case "needs.spareTable" -> gated(new Desire(r.id, Desire.Kind.PROGRESS, r, bb -> rungScore(r, bb, b -> Worth.spareTable(tree(b), have(b)))).cost(10).exposure(Desire.Exposure.SURFACE), b -> null, b -> null);
            case "needs.base" -> gated(new Desire(r.id, Desire.Kind.PROGRESS, r, bb -> rungScore(r, bb, b -> Worth.base(bedDanger(b)))).cost(240).exposure(Desire.Exposure.SURFACE), b -> null, b -> null);
            case "needs.bed" -> gated(new Desire(r.id, Desire.Kind.PROGRESS, r, bb -> rungScore(r, bb, b -> Worth.bed(bedDanger(b)))).cost(120).exposure(Desire.Exposure.SURFACE), b -> null, AaDesires::bedInfeasible);
            case "needs.iron" -> gated(new Desire(r.id, Desire.Kind.PROGRESS, r, bb -> rungScore(r, bb, AaDesires::ironValue)).cost(600),
                    b -> b.in.pickTier < 2 ? "needs.tools" : null, b -> null).exposure(Desire.Exposure.UNDER);
            default -> new Desire(r.id, kindOf(r.id), r, bb -> rungScore(r, bb, b -> 30));
        };
        // aa-night2 P0 3: a running rung ends on its own predicate only (skipped or met); the surface climb is done once
        // up (its unmet also reads needsOpenGround, which flickers with what the other rungs want)
        if (d.kind == Desire.Kind.GATE) d.met(b -> {
            AaManual man = AaManual.of(b);
            if (r.id.equals(man.doRung)) return false;          // the owner's aa do runs until its job ends
            if (man.skipped(r.id)) return true;
            return r.id.equals("needs.surface") ? b.in.underDepth < AaLadder.SURFACE_DEPTH : !r.unmet(b);
        });
        return wire(d, r).site(b -> rungSite(r, b));
    }

    /** AA_ROUND3 2: blackboard fact, a {@code Supplier<int[]>} of the nearest known coal ore (the adapter caches it). */
    public static final String COAL_SITE_FACT = "aa.coalSite";

    /**
     * AA_ROUND3 2: a rung's site for the sequencer: iron from its chosen option, torches at the nearest known coal,
     * the table/furnace at the camp (else the base), base/bed/bag at the base; the gates and the rest none.
     */
    @SuppressWarnings("unchecked")
    static io.github.mojolowjo.entropybot.aa.brain.Site rungSite(Rung r, Blackboard b) {
        int[] place = b.in.campPos != null ? b.in.campPos : b.in.basePos;
        return switch (r.id) {
            case "needs.iron" -> r.picker() == null ? null : r.picker().lastSite();
            case "needs.torches" -> b.fact(COAL_SITE_FACT) instanceof java.util.function.Supplier<?> s && s.get() instanceof int[] p ? io.github.mojolowjo.entropybot.aa.brain.Site.of(p) : null;
            case "needs.tableFurnace", "needs.spareTable" -> io.github.mojolowjo.entropybot.aa.brain.Site.of(place);
            case "needs.base", "needs.bed", "needs.bag" -> io.github.mojolowjo.entropybot.aa.brain.Site.of(b.in.basePos);
            default -> null;
        };
    }

    /** "needs.area first" while a gate is open (AaLadder.openGate), else null. */
    static String gateWhy(Blackboard b) { return gateWhy(b, Desire.Exposure.SURFACE); }

    static String gateWhy(Blackboard b, Desire.Exposure e) {
        String g = AaLadder.openGate(b, e);
        return g == null ? null : g + " first";
    }

    /**
     * AA_ROUND2 4: gates as prerequisites. While a gate is open the desire is parked ({@code <gate> first}) and its
     * value flows to the gate (Brain.scoreAll); otherwise its own prerequisite and feasibility apply.
     */
    static Desire gated(Desire d, Function<Blackboard, String> prereq, Function<Blackboard, String> infeasible) {
        return d.prereq(b -> {
            String g = AaLadder.openGate(b, d.exposure());
            return g != null ? g : prereq.apply(b);
        }).infeasible(b -> {
            String g = gateWhy(b, d.exposure());
            return g != null ? g : infeasible.apply(b);
        });
    }

    /** The table/furnace rung's value: the table's while no table, else the furnace's (Worth.station). */
    static double stationValue(Blackboard b) {
        boolean table = b.in.tableKnown || b.in.tableInBag;
        return table ? Worth.furnace(tree(b), have(b)) : Worth.table(tree(b), have(b));
    }

    /** What a bed saves tonight: the night's danger at the current facts (any time of day; Peaceful 0). */
    static double bedDanger(Blackboard b) {
        if (!b.in.hungerDrains) return 0;                  // Peaceful (the adapter's flag, as Night.hostile reads it)
        return io.github.mojolowjo.entropybot.aa.brain.Costs.nightDanger(b.in.difficulty, b.in.armorPoints, b.in.health, b.in.maxHealth,
                b.in.light, b.in.hostilesNear, false);
    }

    /** AA_ROUND2 1+2: a rung desire's probe (may it start?) and, with a picker, its live cost. */
    public static final long RESCORE_MS = 10_000;

    public static Desire wire(Desire d, Rung r) {
        d.probe(r::probe);
        Rung.Picker p = r.picker();
        if (p == null) return d;
        String key = "aa.rescoreAt." + r.id;
        return d.liveCost(bb -> {
            // while not running the picker is re-scored at most every RESCORE_MS (a near ore raises iron before it runs);
            // while running aa-replan re-scores it every 5 s
            if (!r.job().running()) {
                long now = bb.clock.now();
                Object at = bb.fact(key);
                if (!(at instanceof Long t) || now >= t) {
                    bb.fact(key, now + RESCORE_MS);
                    p.refresh(bb);
                }
            }
            return p.lastCostS();
        });
    }

    public static Desire idle(TaskKind kind, Node node, Scorer scorer) {
        return new Desire("idle." + kind.name().toLowerCase(java.util.Locale.ROOT), Desire.Kind.UPKEEP, node,
                bb -> {
                    // a cooling option is left out (the idle chooser's rule), so the next one gets its turn
                    double s = scorer.score(bb.in)[kind.ordinal()];
                    return s < 0 ? -1 : s * IDLE_SCALE;
                }).exposure(exposureOf(kind)).cools(kind == TaskKind.WAIT ? 0 : Cooldown.NO_RUN_MS, kind == TaskKind.WAIT ? 0 : Cooldown.NO_RUN_MS)
                .infeasible(b -> gateWhy(b, exposureOf(kind))).prereq(b -> AaLadder.openGate(b, exposureOf(kind))).lastTry();
    }

    // ---- director 2026-10-19: idle as one desire ----

    /** The one idle desire's id; its options are the idle kinds ("idle.stock_wood"...), scored inside it. */
    public static final String IDLE_ID = "idle";

    /**
     * Idle as one desire (UPKEEP, low, never urgent: an option scores at most {@code 100 x IDLE_SCALE} before the night
     * factor, which never makes it urgent). Inside it the idle options are an option set: a nested {@link Brain} scores
     * them (each keeps its own night exposure, gate parking, rest and cooling), and the desire's score is its best
     * runnable option's. aa why shows "idle: stock wood 20 (next light 12, wait 10)"; the live tree shows the options
     * under the one node. Parked options still give their value to the gate that parks them.
     */
    public static Desire idleSet(List<Map.Entry<TaskKind, Node>> idle, Scorer scorer) {
        List<Desire> opts = new ArrayList<>();
        Map<String, TaskKind> kinds = new java.util.HashMap<>();
        for (var e : idle) {
            Desire d = idle(e.getKey(), e.getValue(), scorer);
            opts.add(d);
            kinds.put(d.id, e.getKey());
        }
        Brain set = new Brain("idle.options", "the idle options, scored inside the idle desire", opts.toArray(new Desire[0])).nested();
        return new Desire(IDLE_ID, Desire.Kind.UPKEEP, set, bb -> {
            double best = -1;
            for (Brain.Scored s : set.scoreOptions(bb)) if (s.skip() == null && s.score() > best) best = s.score();
            return best;
        }).detail(bb -> idleText(set.lastScored()))
                .parkedValue(bb -> {
                    Brain.Scored p = bestParked(set.lastScored());
                    return p == null ? 0 : p.parked();
                })
                .prereq(bb -> {
                    Brain.Scored p = bestParked(set.lastScored());
                    return p == null ? null : AaLadder.openGate(bb, exposureOf(kinds.get(p.d().id)));
                })
                .patient(Long.MAX_VALUE / 4)          // the options' own watchdog halts a stalled one (the next option runs)
                .lastTry();
    }

    /** The parked option with the highest would-be value, or null. */
    static Brain.Scored bestParked(List<Brain.Scored> all) {
        Brain.Scored best = null;
        for (Brain.Scored s : all) if (s.parked() > 0 && (best == null || s.parked() > best.parked())) best = s;
        return best;
    }

    /** "idle: stock wood 20 (next light 12, wait 10)": the best runnable option, then up to three more. */
    public static String idleText(List<Brain.Scored> all) {
        List<Brain.Scored> ok = new ArrayList<>();
        for (Brain.Scored s : all) if (s.skip() == null && s.score() >= 0) ok.add(s);
        ok.sort((a, b) -> Double.compare(b.rank(), a.rank()));
        if (ok.isEmpty()) return "idle: no option can run";
        StringBuilder b = new StringBuilder("idle: ").append(optName(ok.get(0).d().id)).append(' ').append(Math.round(ok.get(0).score()));
        for (int i = 1; i < ok.size() && i < 4; i++) b.append(i == 1 ? " (next " : ", ").append(optName(ok.get(i).d().id)).append(' ').append(Math.round(ok.get(i).score()));
        if (ok.size() > 1) b.append(')');
        return b.toString();
    }

    static String optName(String id) {
        return (id.startsWith("idle.") ? id.substring(5) : id).replace('_', ' ');
    }

    // ---- aa-food: the bar's curve and the stock target ----

    /** Blackboard fact: the brain's BrainStats (measured drain per kind); absent: hand rates. */
    public static final String STATS_FACT = "aa.brainStats";

    static io.github.mojolowjo.entropybot.aa.brain.BrainStats stats(Blackboard bb) {
        return bb.fact(STATS_FACT) instanceof io.github.mojolowjo.entropybot.aa.brain.BrainStats s ? s : null;
    }

    /** The bag's food target now (energy points): the planned work's measured drain for an hour, less the body's reserve. */
    public static double foodTarget(Blackboard bb) {
        TaskKind running = bb.task() == null ? TaskKind.NONE : bb.task().kind;
        return io.github.mojolowjo.entropybot.aa.brain.FoodModel.target(io.github.mojolowjo.entropybot.aa.brain.FoodModel.plannedRate(stats(bb), running),
                bb.in.food, bb.in.saturation);
    }

    public static double foodStock(Blackboard bb) { return io.github.mojolowjo.entropybot.aa.brain.FoodModel.energy(bb.in.bag); }

    /** The bag holds less food than the target (gathered before the bar is low). */
    public static boolean foodStockLow(Blackboard bb) { return foodStock(bb) < foodTarget(bb); }

    /**
     * The bar's curve (Curves.food: urgent from 6) x the personality, or the stock score (25..60) when that is higher;
     * at most 100. The personality never lifts a stock-up over the urgent line.
     */
    static double foodScore(Blackboard b) {
        double bar = factor(b, "food") * Curves.food(b.in.food);
        double stock = io.github.mojolowjo.entropybot.aa.brain.FoodModel.stockScore(foodStock(b), foodTarget(b));
        return Math.min(100, Math.max(1, Math.max(bar, stock < 0 ? 0 : scaled(stock, factor(b, "food")))));
    }

    /** Parked while no way to food exists (FoodOptions.noSource on the adapter's facts; re-checked as they refresh). */
    @SuppressWarnings("unchecked")
    static String foodInfeasible(Blackboard bb) {
        if (!(bb.fact(FoodOptions.FACTS) instanceof java.util.function.Supplier<?> s)) return null;
        Object f = s.get();
        return f instanceof FoodOptions.FoodFacts ff ? FoodOptions.noSource(ff) : null;
    }

    /** B3 (2.4): the personality's upkeep factor (1 by default). */
    static double factor(Blackboard bb, String key) { return Personality.of(bb).get(key); }

    /** review: a factor never makes a non-urgent score urgent (only food's curve may); an urgent one stays urgent. */
    static double scaled(double base, double f) { return base >= Brain.URGENT ? base : Math.min(Brain.URGENT - 1, f * base); }

    /** AA_ROUND2 4 (was B3's night sign): mining stock-ups work under ground, wood at the surface. */
    static Desire.Exposure exposureOf(TaskKind k) {
        return switch (k) {
            case STOCK_STONE, STOCK_COAL, STOCK_IRON -> Desire.Exposure.UNDER;
            case STOCK_WOOD -> Desire.Exposure.SURFACE;
            default -> Desire.Exposure.ANY;
        };
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
            Pick p = bestIdea(bb);
            return p == null ? -1 : 30 * p.share();
        }).cost(300).prereq(b -> b.in.pickTier < 3 ? "needs.iron" : AaLadder.openGate(b, Desire.Exposure.ANY)).infeasible(b -> gateWhy(b, Desire.Exposure.ANY))
                .cools(Cooldown.NO_RUN_MS, Cooldown.NO_RUN_MS).lastTry();
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

    // ---- B4: the owner's goals (2.5) ----

    /** An owner goal (aa want): ownerBase +/- 20, fresh for 5 min (may interrupt once), parked when blocked. */
    public static Desire ownerGoal(io.github.mojolowjo.entropybot.aa.brain.Goals.Goal g) {
        String id = g.id();
        RunJob job = new RunJob(id + ".job", TaskKind.EXPLORE, bb -> {
            String c = io.github.mojolowjo.entropybot.aa.brain.Goals.chain(bb, g);
            if (c == null) return null;
            String chain = c.startsWith("goal ") ? bb.port.resolve(c) : c;
            if (chain == null) bb.port.log("aa goal " + g.r.key() + ": no plan for \"" + c + "\"");
            return chain == null ? null : JobPort.TaskSpec.of(TaskKind.EXPLORE, chain, "owner goal");
        });
        Node node = AaTree.idleCd(id + ".cd", job);
        return new Desire(id, Desire.Kind.OWNER, node, bb -> io.github.mojolowjo.entropybot.aa.brain.Goals.score(bb, g))
                .infeasible(bb -> io.github.mojolowjo.entropybot.aa.brain.Goals.blocked(bb, g))
                .prereq(bb -> io.github.mojolowjo.entropybot.aa.brain.Goals.prereq(bb, g))
                .fresh(g.given + io.github.mojolowjo.entropybot.aa.brain.Goals.FRESH_MS)
                .cost(600).exposure(g.r.ore() ? Desire.Exposure.UNDER : Desire.Exposure.ANY).cools(Cooldown.NO_RUN_MS, Cooldown.NO_RUN_MS).lastTry();
    }

    // ---- B7: schematic builds (2.12) ----

    /** A build goal (aa build): as an owner goal; the chain gathers what the current layer lacks, else builds. */
    public static Desire ownerBuild(io.github.mojolowjo.entropybot.aa.brain.Blueprint.Build b) {
        String id = b.id();
        RunJob job = new RunJob(id + ".job", TaskKind.BASE_BED, bb -> {
            String c = io.github.mojolowjo.entropybot.aa.brain.Blueprint.chain(bb, b);
            if (c == null) return null;
            if (c.startsWith("goal ")) {
                String chain = bb.port.resolve(c);
                if (chain == null) {                     // review 1: skip it next pick; Baritone builds the rest
                    bb.port.log("aa build " + b.name + ": no plan for \"" + c + "\" - building without it");
                    b.unresolved.add(c.split(" ")[1]);
                }
                return chain == null ? null : JobPort.TaskSpec.of(TaskKind.EXPLORE, chain, "owner build");
            }
            return new JobPort.TaskSpec(TaskKind.BASE_BED, c, "owner build", 1, 40 * 60_000L, JobPort.TaskSpec.NO_PROGRESS_MS);
        });
        Node node = AaTree.idleCd(id + ".cd", job);
        return new Desire(id, Desire.Kind.OWNER, node, bb -> io.github.mojolowjo.entropybot.aa.brain.Blueprint.score(bb, b))
                .infeasible(bb -> io.github.mojolowjo.entropybot.aa.brain.Blueprint.blocked(bb, b))
                .fresh(b.given + io.github.mojolowjo.entropybot.aa.brain.Goals.FRESH_MS)
                .cost(1200).cools(Cooldown.NO_RUN_MS, Cooldown.NO_RUN_MS).lastTry();
    }

    // ---- B3: wants (2.2, 2.4) and the night (2.10) ----

    /** A want: personality weight x boredom; its job cools down like an idle option. */
    static Desire want(String key, TaskKind kind, Function<Blackboard, String> chain, Function<Blackboard, String> infeasible, Desire.Exposure exposure) {
        String id = "want." + key;
        RunJob job = new RunJob(id + ".job", kind, bb -> {
            String c = chain.apply(bb);
            return c == null ? null : JobPort.TaskSpec.of(kind, c, "want");
        });
        Node node = AaTree.idleCd(id + ".cd", job);
        return new Desire(id, Desire.Kind.WANT, node, bb -> Wants.score(bb, id, key))
                .infeasible(b -> { String g = gateWhy(b, exposure); return g != null ? g : infeasible.apply(b); }).prereq(b -> AaLadder.openGate(b, exposure))
                .exposure(exposure).cools(Cooldown.NO_RUN_MS, Cooldown.NO_RUN_MS).lastTry();
    }

    static String buildInfeasible(Blackboard bb) {
        if (bb.in.areaName == null) return "no area fits a house (7x7 or more)";
        if (bb.in.baseKnown) return "a house stands already (base known)";
        if (!bb.flag(AaLadder.SHELTER_FACT)) return "needs.base builds the first one";
        return null;
    }

    /** Owner default (Q3): build a house (where only a shelter stands), explore, tidy the bag into the chests. */
    public static List<Desire> wants() {
        return List.of(
                want("build", TaskKind.BASE_BED, bb -> bb.in.areaName == null ? null : "house " + bb.in.areaName, AaDesires::buildInfeasible, Desire.Exposure.SURFACE),
                want("explore", TaskKind.EXPLORE, bb -> "explore 5", bb -> null, Desire.Exposure.SURFACE),
                want("tidy", TaskKind.DEPOSIT, bb -> "deposit", bb -> bb.in.baseChests ? null : "no base chests to put things in", Desire.Exposure.ANY));
    }

    public static final long SHELTER_PATIENCE_MS = 12 * 60_000;

    /** 2.10 sleep: a bed known (or in the bag), rising from dusk; off in Peaceful. Never urgent (waits its turn). */
    static double sleepScore(Blackboard bb) {
        if (!bb.in.hungerDrains || !(bb.in.bedKnown || bb.in.bedInBag)) return -1;
        double d = Night.dusk(bb.in.dayTime, bb.in.night);
        return d <= 0 ? -1 : Personality.of(bb).get("sleep") * d;
    }

    /** 2.10 shelter: no bed, at the surface, from dusk; off in Peaceful and under ground (dug in already). */
    static double shelterScore(Blackboard bb) {
        if (!bb.in.hungerDrains) return -1;
        double d = Night.dusk(bb.in.dayTime, bb.in.night);
        if (d <= 0) return -1;
        // aa-night2 P0 1 (normal run 01:44: "night (danger 1.37 ...): shelter -" at the spawn): "under ground" was
        // !canSeeSky, true under a tree, an overhang or a staircase's first steps. Off only in my own shell or deep
        // under (the night work's place, AaLadder.SURFACE_DEPTH+)
        String off = shelterOff(bb.in.sheltered, bb.in.underDepth);
        if (off != null) {
            io.github.mojolowjo.entropybot.aa.brain.Costs.note(bb, "shelter", "off: " + off);
            return -1;
        }
        // live aa377: 55 at night lost to tools 74 all night. Full night at the surface: above the urgent line, higher
        // early game (no armour, no bed); the grace lets the running step finish first
        // aa-costs (was a fixed 80 + 5 no armour + 5 no bed): the danger of staying out (Costs.nightDanger)
        if (d >= 1) {
            boolean bed = bb.in.bedKnown || bb.in.bedInBag;
            double danger = io.github.mojolowjo.entropybot.aa.brain.Costs.nightDanger(bb.in.difficulty, bb.in.armorPoints, bb.in.health, bb.in.maxHealth,
                    bb.in.light, bb.in.hostilesNear, bed);
            io.github.mojolowjo.entropybot.aa.brain.Costs.note(bb, "shelter", io.github.mojolowjo.entropybot.aa.brain.Costs.nightTerms(bb.in.difficulty,
                    bb.in.armorPoints, bb.in.health, bb.in.light, bb.in.hostilesNear, bed, danger));
            return io.github.mojolowjo.entropybot.aa.brain.Costs.shelterScore(danger);
        }
        if (bb.in.bedKnown || bb.in.bedInBag) return -1;      // dusk with a bed: night.sleep waits its turn
        return Personality.of(bb).get("shelter") * d;
    }

    /** Why the night shelter is not needed here (null = it is): in my shell, or deep under ground. */
    static String shelterOff(boolean sheltered, int underDepth) {
        if (sheltered) return "in my shell";
        if (underDepth >= AaLadder.SURFACE_DEPTH) return underDepth + " blocks under ground";
        return null;
    }

    // ---- aa-night2 P0 1: hide and heal ----

    /** Health the hide waits for; health under which it starts by day with hostiles near. */
    public static final float HEAL_TO = 16, HIDE_DAY_UNDER = 10;
    /** Score per point of missing health x the night danger. */
    static final double HIDE_PER_HP = 7;
    /** Blackboard fact: the hide has started (Boolean): it keeps scoring until health reaches {@link #HEAL_TO}. */
    public static final String HIDE_FACT = "aa.hide";

    /**
     * Hide and heal: nightDanger x missing health (x {@link #HIDE_PER_HP}, at most 99) at a hostile night, or by day with
     * hostiles near and health under {@link #HIDE_DAY_UNDER}; once started it holds until {@link #HEAL_TO}. Pure.
     */
    public static double hideScore(boolean hostileNight, int hostilesNear, float health, float maxHealth, double danger, boolean started) {
        if (health >= HEAL_TO) return -1;
        boolean trigger = started || (hostileNight ? health < maxHealth : hostilesNear > 0 && health < HIDE_DAY_UNDER);
        if (!trigger) return -1;
        return Math.min(io.github.mojolowjo.entropybot.aa.brain.Costs.SHELTER_MAX, HIDE_PER_HP * Math.max(1, danger) * (maxHealth - health));
    }

    static double hideScore(Blackboard bb) {
        if (!bb.in.hungerDrains || bb.in.sheltered) return -1;
        boolean started = Boolean.TRUE.equals(bb.fact(HIDE_FACT));
        if (bb.in.health >= HEAL_TO && started) bb.fact(HIDE_FACT, null);
        double danger = io.github.mojolowjo.entropybot.aa.brain.Costs.nightDanger(bb.in.difficulty, bb.in.armorPoints, bb.in.health, bb.in.maxHealth,
                bb.in.light, bb.in.hostilesNear, bb.in.bedKnown || bb.in.bedInBag);
        double v = hideScore(Night.hostile(bb), bb.in.hostilesNear, bb.in.health, bb.in.maxHealth, danger, started && bb.in.health < HEAL_TO);
        if (v >= Brain.URGENT) bb.fact(HIDE_FACT, Boolean.TRUE);
        if (v > 0) io.github.mojolowjo.entropybot.aa.brain.Costs.note(bb, "hide", "health " + Math.round(bb.in.health) + "/" + Math.round(bb.in.maxHealth)
                + ", danger " + Math.round(danger * 100) / 100.0 + ", hostiles " + bb.in.hostilesNear + " -> " + Math.round(v));
        return v;
    }

    /** The hide's chain: a sealed shell right here (dug in where it can), held 3 min; the hide's score ends it at 16 hp. */
    static final String HIDE_CHAIN = "shelter 3m";

    static Desire hide() {
        Node n = AaTree.idleCd("night.hide.cd", RunJob.of("night.hide.job", TaskKind.BASE_BED, HIDE_CHAIN, "hide"));
        return new Desire("night.hide", Desire.Kind.UPKEEP, n, AaDesires::hideScore)
                .cools(Cooldown.NO_RUN_MS, Cooldown.NO_RUN_MS).grace(2_000).patient(4 * 60_000).trumps().lastTry()
                // its own end: healed (the shell it waits in turns the score off; that is no gone reason)
                .met(b -> !b.in.hungerDrains || b.in.health >= HEAL_TO);
    }

    /** The step grace before the shelter interrupts. */
    static final long SHELTER_GRACE_MS = 8_000;

    /** Where to shelter: the bed if known, else the base (house), else a shell right here (dug or built, sealed). */
    static String shelterChain(Blackboard bb) {
        if (bb.in.bedKnown || bb.in.bedInBag) return "sleep";
        if (bb.in.baseKnown) return "go base then shelter";
        return "shelter";
    }

    /** 2.10: sleep and shelter (UPKEEP under 75: they wait for the running task, then win by number). */
    public static List<Desire> night() {
        Node sleep = AaTree.idleCd("night.sleep.cd", RunJob.of("night.sleep.job", TaskKind.BASE_BED, "sleep", "night"));
        Node shelter = AaTree.idleCd("night.shelter.cd", new RunJob("night.shelter.job", TaskKind.BASE_BED,
                bb -> new JobPort.TaskSpec(TaskKind.BASE_BED, shelterChain(bb), "night", 1, JobPort.TaskSpec.TIMEOUT_MS, SHELTER_PATIENCE_MS)));
        // AA_ROUND2 4: night.work (78) is gone; NightWork is the mining stock-ups' UNDER option (AaTree.underGoal). Once
        // the shell is built and work under ground is wanted, the shelter's wait ends (done) so that work can run.
        Node shelterYield = yieldWhenSheltered(shelter);
        return List.of(
                new Desire("night.sleep", Desire.Kind.UPKEEP, sleep, AaDesires::sleepScore).cools(Cooldown.NO_RUN_MS, Cooldown.NO_RUN_MS).lastTry(),
                new Desire("night.shelter", Desire.Kind.UPKEEP, shelterYield, bb -> nightWorkReady(bb) ? -1 : shelterScore(bb))
                        .cools(Cooldown.NO_RUN_MS, Cooldown.NO_RUN_MS).patient(SHELTER_PATIENCE_MS).grace(SHELTER_GRACE_MS).trumps().lastTry()
                        // its own end: day (or Peaceful); in the shell the score is off, which is no gone reason
                        .met(b -> !b.in.hungerDrains || Night.dusk(b.in.dayTime, b.in.night) <= 0),
                hide());
    }

    /** Blackboard fact: when a mining stock-up (UNDER) last scored wanted (ms); the shelter yields to it for 10 s after. */
    public static final String UNDER_WANTED_FACT = Brain.UNDER_WANTED_FACT;

    /** In the shell at a hostile night, night work to do (NightWork.pick) and an UNDER stock-up wanted just now. */
    static boolean nightWorkReady(Blackboard bb) {
        if (!NightWork.active(bb) || NightWork.pick(bb.in) == NightWork.Work.NONE) return false;
        return bb.fact(UNDER_WANTED_FACT) instanceof Long at && bb.clock.now() - at <= RESCORE_MS;
    }

    /** The shelter's node: while its wait runs and night work is ready, the wait ends done (the shell stays). */
    static Node yieldWhenSheltered(Node shelter) {
        return new Node(shelter.id + ".yield", "shelter, until work under ground is ready") {
            @Override protected Status run(Blackboard bb) {
                Status s = shelter.tick(bb);
                if (s == Status.RUNNING && nightWorkReady(bb)) {
                    shelter.halt("sheltered: work under ground is cheaper now");
                    bb.port.log("night: sheltered, the wait ends - work under ground (" + NightWork.pick(bb.in) + ")");
                    return Status.SUCCESS;
                }
                return s == Status.FAILURE ? fail(shelter.reason()) : s;
            }

            @Override public void halt(String why) { shelter.halt(why); }

            @Override public List<Node> children() { return List.of(shelter); }
        };
    }

    // ---- B6: helping the owner (2.11) ----

    /** The help desire (UPKEEP under 75: never interrupts); its job does {@link io.github.mojolowjo.entropybot.aa.brain.Help#pick}. */
    public static Desire help() {
        RunJob job = new RunJob("help.owner.job", TaskKind.EXPLORE, bb -> {
            var p = io.github.mojolowjo.entropybot.aa.brain.Help.pick(bb.in, Personality.of(bb).get("help"));
            if (p == null) return null;
            String chain = p.chain();
            if (p.goal() != null) {
                String g = bb.port.resolve(p.goal());
                if (g == null) {
                    bb.port.log("help: no plan for \"" + p.goal() + "\" (or still searching)");
                    return null;
                }
                chain = g + " then " + chain;
            }
            bb.port.log("help: " + p.line());
            return new JobPort.TaskSpec(TaskKind.EXPLORE, chain, "help", 1, HELP_TIMEOUT_MS, JobPort.TaskSpec.NO_PROGRESS_MS);
        });
        Node node = AaTree.idleCd("help.owner.cd", job);
        // round 2: the brain's own cool-down (Desire.cools, the Failures record) instead of a per-node cooling check
        return new Desire("help.owner", Desire.Kind.UPKEEP, node, bb -> {
            var p = io.github.mojolowjo.entropybot.aa.brain.Help.pick(bb.in, Personality.of(bb).get("help"));
            return p == null ? -1 : p.score();
        }).cools(Cooldown.NO_RUN_MS, Cooldown.NO_RUN_MS).lastTry();
    }

    /** A help task is short (a walk, a fight, a hand-over); a fetch gets the planner's time. */
    static final long HELP_TIMEOUT_MS = 10 * 60_000;

    /** aa status: "help: give 30 (give me oak_planks 32)" or why it is off. */
    public static String helpLine(Blackboard bb) {
        double w = Personality.of(bb).get("help");
        String off = io.github.mojolowjo.entropybot.aa.brain.Help.off(bb.in, w);
        if (off != null) return "help: off (" + off + ")";
        var p = io.github.mojolowjo.entropybot.aa.brain.Help.pick(bb.in, w);
        return "help: " + (p == null ? "nothing to do (owner " + (bb.in.ownerAct == null ? "no report" : bb.in.ownerAct) + ", " + bb.in.ownerDist + " away)" : p.line());
    }

    /** aa status: "wants: build parked (...), explore 4 (weight 15, bored 0.27), tidy - | day: sleep -, shelter -". */
    public static String wantsLine(Blackboard bb, List<Brain.Scored> scored) {
        Personality p = Personality.of(bb);
        List<String> w = new ArrayList<>(), n = new ArrayList<>();
        for (Brain.Scored s : scored) {
            String id = s.d().id;
            String v = s.skip() == null ? String.valueOf(Math.round(s.score())) : s.skip().equals("not wanted") ? "-" : s.skip();
            if (s.d().kind == Desire.Kind.WANT) {
                String key = id.substring(5);
                w.add(key + " " + v + " (weight " + Personality.fmt(p.get(key)) + ", bored "
                        + Math.round(Wants.boredom(bb, id, p.get("boredomDays")) * 100) / 100.0 + ")");
            } else if (id.startsWith("night.")) n.add(id.substring(6) + " " + v);
        }
        double d = Night.dusk(bb.in.dayTime, bb.in.night);
        String time = !bb.in.hungerDrains ? "Peaceful (night desires off)" : d >= 1 ? "night (danger " + Math.round(Brain.danger(bb) * 100) / 100.0 + ": under x" + Math.round(Brain.exposureFactor(Desire.Exposure.UNDER, Brain.danger(bb)) * 100) / 100.0 + ", surface x" + Math.round(Brain.exposureFactor(Desire.Exposure.SURFACE, Brain.danger(bb)) * 100) / 100.0 + ")" : d > 0 ? "dusk" : "day";
        return "wants: " + (w.isEmpty() ? "none" : String.join(", ", w)) + " | " + time + ": " + String.join(", ", n) + " | " + helpLine(bb)
                + (p.errors.isEmpty() ? "" : " | !! personality: " + String.join("; ", p.errors) + " (defaults used)");
    }

    /** The brain over the rungs, the idle options and the ideas. */
    public static Brain build(List<Rung> rungs, List<Map.Entry<TaskKind, Node>> idle, Scorer scorer) {
        List<Desire> ds = new ArrayList<>();
        for (Rung r : rungs) ds.add(rung(r));
        ds.add(ideas());
        ds.addAll(night());
        ds.addAll(wants());
        ds.add(help());
        if (!idle.isEmpty()) ds.add(idleSet(idle, scorer));
        Brain brain = new Brain("brain", "the scored desires", ds.toArray(new Desire[0]));
        // AA_ROUND2 3: the rung pickers' option memories are views over the brain's one failure record
        for (Rung r : rungs) if (r.picker() instanceof Options.Picker<?> op) op.memory.bind(brain.failures, r.id);
        return brain;
    }
}
