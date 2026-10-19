package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.brain.Brain;
import io.github.mojolowjo.entropybot.aa.brain.Curves;
import io.github.mojolowjo.entropybot.aa.brain.Desire;
import io.github.mojolowjo.entropybot.aa.brain.Night;
import io.github.mojolowjo.entropybot.aa.brain.NightWork;
import io.github.mojolowjo.entropybot.aa.brain.Personality;
import io.github.mojolowjo.entropybot.aa.brain.Wants;
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
import java.util.function.Function;
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
            case "needs.tools" -> new Desire(r.id, Desire.Kind.UPKEEP, r, bb -> rungScore(r, bb, b -> Math.min(74, factor(b, "tools") * tools(b))));
            case "needs.food" -> new Desire(r.id, Desire.Kind.UPKEEP, r, bb -> rungScore(r, bb, b -> Math.max(1, factor(b, "food") * Curves.upkeep(125, b.in.food, 18))));
            case "needs.torches" -> new Desire(r.id, Desire.Kind.UPKEEP, r, bb -> rungScore(r, bb, b -> scaled(30 + Curves.upkeep(30, b.in.torches, 16), factor(b, "torches"))))
                    .prereq(b -> b.in.tableKnown || b.in.tableInBag ? null : "needs.tableFurnace");
            case "needs.bag" -> new Desire(r.id, Desire.Kind.UPKEEP, r, bb -> rungScore(r, bb, b -> scaled(20 + Curves.upkeep(60, b.in.freeSlots, 10), factor(b, "bag"))));
            case "needs.tableFurnace" -> new Desire(r.id, Desire.Kind.PROGRESS, r, bb -> rungScore(r, bb, b -> 70)).cost(60);
            case "needs.spareTable" -> new Desire(r.id, Desire.Kind.PROGRESS, r, bb -> rungScore(r, bb, b -> 50)).cost(10);
            case "needs.base" -> new Desire(r.id, Desire.Kind.PROGRESS, r, bb -> rungScore(r, bb, b -> 55)).cost(240);
            case "needs.bed" -> new Desire(r.id, Desire.Kind.PROGRESS, r, bb -> rungScore(r, bb, b -> 45)).cost(120).infeasible(AaDesires::bedInfeasible);
            case "needs.iron" -> new Desire(r.id, Desire.Kind.PROGRESS, r, bb -> rungScore(r, bb, AaDesires::ironValue)).cost(600)
                    .prereq(b -> b.in.pickTier < 2 ? "needs.tools" : null).night(+1);
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
                }).night(nightSign(kind)).lastTry();
    }

    /** B3 (2.4): the personality's upkeep factor (1 by default). */
    static double factor(Blackboard bb, String key) { return Personality.of(bb).get(key); }

    /** review: a factor never makes a non-urgent score urgent (only food's curve may); an urgent one stays urgent. */
    static double scaled(double base, double f) { return base >= Brain.URGENT ? base : Math.min(Brain.URGENT - 1, f * base); }

    /** B3 (2.10): mining stock-ups get the night bonus, wood (surface) the malus. */
    static int nightSign(TaskKind k) {
        return switch (k) {
            case STOCK_STONE, STOCK_COAL, STOCK_IRON -> +1;
            case STOCK_WOOD -> -1;
            default -> 0;
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
        return new Desire(id, Desire.Kind.OWNER, node, bb -> cooling(node, bb) ? -1 : io.github.mojolowjo.entropybot.aa.brain.Goals.score(bb, g))
                .infeasible(bb -> io.github.mojolowjo.entropybot.aa.brain.Goals.blocked(bb, g))
                .prereq(bb -> io.github.mojolowjo.entropybot.aa.brain.Goals.prereq(bb, g))
                .fresh(g.given + io.github.mojolowjo.entropybot.aa.brain.Goals.FRESH_MS)
                .cost(600).night(g.r.ore() ? +1 : 0).lastTry();
    }

    // ---- B3: wants (2.2, 2.4) and the night (2.10) ----

    /** A want: personality weight x boredom; its job cools down like an idle option. */
    static Desire want(String key, TaskKind kind, Function<Blackboard, String> chain, Function<Blackboard, String> infeasible, int night) {
        String id = "want." + key;
        RunJob job = new RunJob(id + ".job", kind, bb -> {
            String c = chain.apply(bb);
            return c == null ? null : JobPort.TaskSpec.of(kind, c, "want");
        });
        Node node = AaTree.idleCd(id + ".cd", job);
        return new Desire(id, Desire.Kind.WANT, node, bb -> cooling(node, bb) ? -1 : Wants.score(bb, id, key))
                .infeasible(infeasible).night(night).lastTry();
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
                want("build", TaskKind.BASE_BED, bb -> bb.in.areaName == null ? null : "house " + bb.in.areaName, AaDesires::buildInfeasible, -1),
                want("explore", TaskKind.EXPLORE, bb -> "explore 5", bb -> null, -1),
                want("tidy", TaskKind.DEPOSIT, bb -> "deposit", bb -> bb.in.baseChests ? null : "no base chests to put things in", 0));
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
        if (!bb.in.hungerDrains || bb.in.underGround) return -1;
        double d = Night.dusk(bb.in.dayTime, bb.in.night);
        if (d <= 0) return -1;
        // live aa377: 55 at night lost to tools 74 all night. Full night at the surface: above the urgent line, higher
        // early game (no armour, no bed); the grace lets the running step finish first
        if (d >= 1) return SHELTER_NIGHT + (bb.in.armorPoints <= 0 ? SHELTER_EARLY : 0) + (bb.in.bedKnown || bb.in.bedInBag ? 0 : SHELTER_EARLY);
        if (bb.in.bedKnown || bb.in.bedInBag) return -1;      // dusk with a bed: night.sleep waits its turn
        return Personality.of(bb).get("shelter") * d;
    }

    /** Full-night shelter score (over {@link Brain#URGENT}), the early-game bonus each, the step grace. */
    static final double SHELTER_NIGHT = 80, SHELTER_EARLY = 5;
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
        return List.of(
                new Desire("night.sleep", Desire.Kind.UPKEEP, sleep, bb -> cooling(sleep, bb) ? -1 : sleepScore(bb)).lastTry(),
                new Desire("night.shelter", Desire.Kind.UPKEEP, shelter, bb -> cooling(shelter, bb) ? -1 : shelterScore(bb))
                        .patient(SHELTER_PATIENCE_MS).grace(SHELTER_GRACE_MS).lastTry());
    }

    static boolean cooling(Node n, Blackboard bb) { return n instanceof Cooldown cd && cd.cooling(bb.clock.now()); }

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
        String time = !bb.in.hungerDrains ? "Peaceful (night desires off)" : d >= 1 ? "night (mining +" + Personality.fmt(p.get("night")) + ", surface -)" : d > 0 ? "dusk" : "day";
        return "wants: " + (w.isEmpty() ? "none" : String.join(", ", w)) + " | " + time + ": " + String.join(", ", n)
                + (p.errors.isEmpty() ? "" : " | !! personality: " + String.join("; ", p.errors) + " (defaults used)");
    }

    /** The brain over the rungs, the idle options and the ideas. */
    public static Brain build(List<Rung> rungs, List<Map.Entry<TaskKind, Node>> idle, Scorer scorer) {
        List<Desire> ds = new ArrayList<>();
        for (Rung r : rungs) ds.add(rung(r));
        ds.add(ideas());
        ds.addAll(night());
        ds.addAll(wants());
        for (var e : idle) ds.add(idle(e.getKey(), e.getValue(), scorer));
        return new Brain("brain", "the scored desires", ds.toArray(new Desire[0]));
    }
}
