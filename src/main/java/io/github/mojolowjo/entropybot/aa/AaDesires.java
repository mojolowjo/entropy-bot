package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.brain.Brain;
import io.github.mojolowjo.entropybot.aa.brain.Curves;
import io.github.mojolowjo.entropybot.aa.brain.Desire;
import io.github.mojolowjo.entropybot.aa.brain.Kit;
import io.github.mojolowjo.entropybot.aa.brain.Night;
import io.github.mojolowjo.entropybot.aa.brain.NightWork;
import io.github.mojolowjo.entropybot.aa.brain.Personality;
import io.github.mojolowjo.entropybot.aa.brain.Tuning;
import io.github.mojolowjo.entropybot.aa.brain.Wants;
import io.github.mojolowjo.entropybot.aa.brain.Worth;
import io.github.mojolowjo.entropybot.aa.brain.TechTree;
import io.github.mojolowjo.entropybot.aa.core.Blackboard;
import io.github.mojolowjo.entropybot.aa.core.Cooldown;
import io.github.mojolowjo.entropybot.aa.core.Inputs;
import io.github.mojolowjo.entropybot.aa.core.JobPort;
import io.github.mojolowjo.entropybot.aa.core.Node;
import io.github.mojolowjo.entropybot.aa.core.RunJob;
import io.github.mojolowjo.entropybot.aa.core.Scorer;
import io.github.mojolowjo.entropybot.aa.core.Status;
import io.github.mojolowjo.entropybot.aa.core.TaskKind;
import io.github.mojolowjo.entropybot.aa.moves.Step;

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
 * <li>UPKEEP: (no tools need: a pickaxe is made only when a chain's dig asks for one, NeedChain), food {@code 175 x shortfall^2} of 18 (aa-food:
 *     10 -> 35, 6 -> 78 urgent, 4 -> 100) or its stock score (25..60) under the stock target, parked with no source, torches 30 + {@code 30 x shortfall^2} of 16, bag 20 + {@code 60 x shortfall^2} of 10 free.
 *     A pickaxe count that flickers in a craft must not break a running task: the adapter smooths the tier (Costs.ToolSmoother).</li>
 * <li>PROGRESS (value, hand cost s; the brain applies P and cost): table/furnace 70/60, spare table 50/10, base 55/240,
 *     bed 45/120, iron 40 + 30 x the tech tree's iron share / 600. Torches give their value to table/furnace while no
 *     table is known; ideas to iron while under an iron pickaxe.</li>
 * <li>Idle options: their {@link AaScorer} deficit x {@link #IDLE_SCALE} (25 at most): below iron while iron runs well,
 *     above it after two failures in a row (P 0.5).</li>
 * <li>ideas (2.8): the best thing the recipes say can be made now, from an iron pickaxe on: value 30 x its share.</li>
 * </ul>
 * With these numbers a Peaceful fresh world runs in the ladder's order (table/furnace, torches, base, iron).
 */
public final class AaDesires {
    private AaDesires() {}

    public static double idleScale() { return Tuning.get("aaDesires.idleScale"); }
    /** Blackboard facts the adapter fills: the {@link TechTree} and the set of item ids had (bag + known stations). */
    public static final String TECH_FACT = "aa.techTree", HAVE_FACT = "aa.have";

    /** AA_ROUND4 B2: the most a need scores by itself (100 is the owner's aa do; the night discounts by place, B1). */
    static double rungMax() { return Tuning.get("aaDesires.rungMax"); }

    /** A need's score: 100 for the owner's aa do, -1 when skipped or met, else {@code f}. */
    static double rungScore(Need r, Blackboard bb, ToDoubleFunction<Blackboard> f) {
        AaManual man = AaManual.of(bb);
        if (r.id.equals(man.doRung)) return 100;
        if (man.skipped(r.id)) return -1;
        return r.unmet(bb) ? Math.min(rungMax(), f.applyAsDouble(bb)) : -1;      // AA_ROUND4 B2: TOOLS_MAX went; no rung reaches the owner's 100
    }

    @SuppressWarnings("unchecked")
    static Set<String> have(Blackboard bb) {
        Object o = bb.fact(HAVE_FACT);
        return o instanceof Set<?> s ? (Set<String>) s : Set.of();
    }

    /** Wool for a bed (3) needs a source: wool or 12 string had, or a sheep seen. Null = feasible. */
    public static String woolSource(int wool, int string, boolean sheepSeen) {
        return wool >= 3 || string >= 12 || sheepSeen ? null : "no wool source (" + wool + "/3 wool, " + string + "/12 string, no sheep seen)";  // game fact: a bed needs 3 wool; 4 string craft 1 wool
    }

    static String bedInfeasible(Blackboard bb) {
        return bb.in.bedKnown || bb.in.bedInBag ? null : woolSource(bb.in.wool, bb.in.string, bb.in.sheepSeen);
    }

    static TechTree tree(Blackboard bb) { return bb.fact(TECH_FACT) instanceof TechTree t ? t : null; }

    /** Iron's value: 40 + 30 x the tech tree's share of iron ingots (no tree yet: the full 30). */
    static double ironValue(Blackboard bb) {
        TechTree t = tree(bb);
        return Tuning.i("aaDesires.ironBase") + Tuning.i("aaDesires.ironSpan") * (t == null || t.size() == 0 ? 1 : t.norm("iron_ingot", have(bb)));
    }

    // ---- round 4A N: the needs (Need + Ways; the ladder's rungs and gates are gone) ----

    /**
     * The basic needs, each an option set: tools (RungOptions.Tools + the Ways rows), food (FoodOptions), table/furnace/
     * chest, spare table, torches (CoalOptions.Torches), base, bed (BedOptions), bag, iron (IronOptions). The brain
     * scores them; no ladder order, no gate: a need whose work wants open ground prices the climb into its row.
     * Round 4A O (StationOptions, BaseOptions, BagOptions) replaces the station, base and bag lines here.
     */
    /** Round 4A N wiring: the station need's id and its facts key (NeedFacts registers the supplier). */
    public static final String STATION_ID = "needs.tableFurnace", STATION_FACTS = "station.facts";
    /** The bag need's band: on under BAG_ENTER free slots, off at BAG_LEAVE (BagOptions' want). */
    public static int bagEnter() { return (int) Tuning.get("bag.enter"); }
    public static int bagLeave() { return (int) Tuning.get("bag.leave"); }

    /** The station the camp lacks now: the table, then the furnace, then the chest. */
    public static StationOptions.Kind stationKind(Inputs in) {
        if (!(in.tableKnown || in.tableInBag)) return StationOptions.Kind.TABLE;
        if (!(in.furnaceKnown || in.furnaceInBag)) return StationOptions.Kind.FURNACE;
        return StationOptions.Kind.CHEST;
    }

    /** The dawn climb-out need (scores Tuning dawnClimb while NightWork.climbAtDawn). */
    public static final String DAWN_ID = "night.dawnClimb";

    private static int t(String name) { return (int) Tuning.get(name); }

    /** rm-execution2: a need's planner goal (one Step.Plan; Ways asks the planner about it). */
    static List<Step> plan(String goal) { return List.of(new Step.Plan(goal)); }

    /** A row for a need whose facts are not readable (its fixed way). */
    static Options.Option noFacts(String id, List<Step> steps) {
        return new Options.Option(id, id, steps, 0, "no facts: the fixed way", id, false);
    }

    public static List<Need> needs() {
        io.github.mojolowjo.entropybot.aa.core.Band torches = new io.github.mojolowjo.entropybot.aa.core.Band(t("band.torchOn"), t("band.torchOff")),
                bag = new io.github.mojolowjo.entropybot.aa.core.Band(t("bag.enter"), t("bag.leave")), food = new io.github.mojolowjo.entropybot.aa.core.Band(t("band.foodOn"), t("band.foodOff"));
        Need hunger = new Need("needs.food", TaskKind.FOOD,
                bb -> bb.in.hungerDrains && (food.update(bb.in.food) | foodStockLow(bb)),
                bb -> null, bb -> plan("food 8"), new Options.Picker<>("FOOD", FoodOptions.FACTS, FoodOptions.FoodFacts.class, new FoodOptions.Food()));
        // a table or furnace in the bag counts (the near-block fact is false as soon as the bot walks away)
        // round 4A O: craft+place at the cell, cut + craft, the planner, a known one (StationOptions); a gather climbs first there
        Need tableFurnace = new Need(STATION_ID, TaskKind.TABLE_FURNACE,
                bb -> !(bb.in.tableKnown || bb.in.tableInBag) || !(bb.in.furnaceKnown || bb.in.furnaceInBag) || !campChest(bb),
                // rm-areas: the chest has no chain of its own: the station set's place / craft rows own it
                bb -> null, bb -> !(bb.in.tableKnown || bb.in.tableInBag) ? plan("table") : !(bb.in.furnaceKnown || bb.in.furnaceInBag) ? plan("furnace")
                        : null, new Options.Picker<>("STATION", STATION_FACTS, StationOptions.StationFacts.class, new StationOptions.Set()));
        Need spare = new Need("needs.spareTable", TaskKind.TABLE_FURNACE,
                bb -> AaLadder.spareTableWanted(bb.in.tableInBag, bb.in.planks),
                bb -> null, bb -> List.of(new Step.Craft("crafting_table", 1))).hand(io.github.mojolowjo.entropybot.aa.brain.ToolWear.craftSV());
        Need torch = new Need("needs.torches", TaskKind.TORCHES,
                bb -> torches.update(bb.in.torches),
                bb -> !bb.in.torchCoal() ? "coal kept for the coal goal (" + bb.in.torchCoalTerms() + ")" : null,
                bb -> plan("torch " + CoalOptions.Torches.target()), new CoalOptions.Torches());
        Need base = new Need("needs.base", TaskKind.BASE_BED,
                bb -> !bb.in.baseKnown && !bb.flag(AaLadder.SHELTER_FACT),
                bb -> null, bb -> bb.in.basePlace != null ? List.of(new Step.House(bb.in.basePlace)) : List.of(new Step.Shelter()),
                // round 4A O: house <area>, shelter here, burrow, priced with the night (BaseOptions; its rows climb first)
                new Options.Picker<>("BASE", BaseOptions.FACTS, BaseOptions.BaseFacts.class, new BaseOptions.Set())
                        .withoutFacts((b, planned) -> b.in.basePlace != null ? noFacts("house", List.of(new Step.House(b.in.basePlace))) : noFacts("shelter", List.of(new Step.Shelter()))));
        // round 4 fix 4: the ways to a bed are priced (BedOptions); the climb is the first step of its row under ground
        Need bed = new Need("needs.bed", TaskKind.BASE_BED,
                bb -> !bb.in.bedKnown && !bb.in.bedInBag,
                bb -> bb.in.wool >= 3 || bb.in.sheepSeen || sheepHerd(bb) != null ? null : "no wool for a bed (" + bb.in.wool + "/3; no sheep seen)",  // game fact: a bed needs 3 wool
                bb -> BedOptions.steps(bb.in.wool, sheepHerd(bb)),
                new Options.Picker<>("BED", BedOptions.FACTS, BedOptions.BedFacts.class, new BedOptions.Set()));      // its rows price the climb (BedOptions)
        Need deposit = new Need("needs.bag", TaskKind.BAG,
                bb -> bag.update(bb.in.freeSlots),
                bb -> null,
                // round 4A O: deposit at the base chest, craft+place a chest here, drop junk (BagOptions)
                bb -> List.of(new Step.Deposit()), new Options.Picker<>("BAG", BagOptions.FACTS, BagOptions.BagFacts.class, new BagOptions.Set())
                        .withoutFacts((b, planned) -> b.in.baseChests ? noFacts("deposit", List.of(new Step.Deposit())) : null));
        // 0.36.a8 (MINING_PLAN 3.6): the option scorer chooses how the planner's ore and smelt steps run (IronOptions)
        Need iron = new Need("needs.iron", TaskKind.IRON,
                bb -> bb.in.pickTier < 3,  // game fact: pickaxe tier scale: wood 1, stone 2, iron 3, diamond 4
                bb -> null,
                bb -> plan("iron tools"), new IronOptions.Picker());
        // review 4A-N (1): the dawn climb out of the night stairs is a need of its own, priced by WayUp's ways (no gate)
        Need dawn = new Need(DAWN_ID, TaskKind.TABLE_FURNACE, bb -> NightWork.climbAtDawn(bb, AaLadder.surfaceDepth()), bb -> null, bb -> List.of(new Step.Climb()),
                new Options.Picker<>("SURFACE", RungOptions.SURFACE_FACTS, RungOptions.SurfaceFacts.class, new RungOptions.Surface())
                        .withoutFacts((b, planned) -> noFacts("climb", List.of(new Step.Climb()))));
        return List.of(hunger, tableFurnace, spare, torch, base, bed, deposit, iron, dawn);
    }

    /** AA_ROUND3 5: the best sheep herd remembered and not in view ({x, z, dist, conf%}; LootBook.HERDS_FACT), or null. */
    static int[] sheepHerd(Blackboard bb) {
        return bb.fact(io.github.mojolowjo.entropybot.aa.brain.LootBook.HERDS_FACT) instanceof java.util.Map<?, ?> m
                && m.get("minecraft:sheep") instanceof int[] h ? h : null;
    }

    /** Round 4A N: a need as a desire (its score, value-flow prerequisite, feasibility, live cost, probe and site). */
    public static Desire need(Need r) {
        Desire d = switch (r.id) {
            case "needs.food" -> new Desire(r.id, Desire.Kind.UPKEEP, r, bb -> rungScore(r, bb, AaDesires::foodScore)).infeasible(AaDesires::foodInfeasible);
            case "needs.torches" -> new Desire(r.id, Desire.Kind.UPKEEP, r, bb -> rungScore(r, bb, b -> scaled(Tuning.get("torch.base") + Curves.upkeep(Tuning.get("torch.span"), b.in.torches, t("torch.target")), factor(b, "torches"))))
                    .prereq(b -> b.in.tableKnown || b.in.tableInBag ? null : "needs.tableFurnace");
            case "needs.bag" -> new Desire(r.id, Desire.Kind.UPKEEP, r, bb -> rungScore(r, bb, b -> scaled(Tuning.get("bag.base") + Curves.upkeep(Tuning.get("bag.span"), b.in.freeSlots, t("bag.leave")), factor(b, "bag"))));
            case "needs.tableFurnace" -> new Desire(r.id, Desire.Kind.PROGRESS, r, bb -> rungScore(r, bb, AaDesires::stationValue)).cost(Tuning.get("cost.station")).exposure(Desire.Exposure.SURFACE);
            case "needs.spareTable" -> new Desire(r.id, Desire.Kind.PROGRESS, r, bb -> rungScore(r, bb, b -> Worth.spareTable(tree(b), have(b)))).cost(Tuning.get("cost.spareTable"));
            case "needs.base" -> new Desire(r.id, Desire.Kind.PROGRESS, r, bb -> rungScore(r, bb, b -> Worth.base(bedDanger(b)))).cost(Tuning.get("cost.base")).exposure(Desire.Exposure.SURFACE);
            case "needs.bed" -> new Desire(r.id, Desire.Kind.PROGRESS, r, bb -> rungScore(r, bb, b -> Worth.bed(bedDanger(b)))).cost(Tuning.get("cost.bed")).exposure(Desire.Exposure.SURFACE)
                    .infeasible(AaDesires::bedInfeasible);
            case "needs.iron" -> new Desire(r.id, Desire.Kind.PROGRESS, r, bb -> rungScore(r, bb, AaDesires::ironValue)).cost(Tuning.get("cost.iron"))
                    .exposure(Desire.Exposure.UNDER);
            case DAWN_ID -> new Desire(r.id, Desire.Kind.UPKEEP, r, bb -> rungScore(r, bb, b -> Tuning.get("dawnClimb")));
            default -> new Desire(r.id, Desire.Kind.PROGRESS, r, bb -> rungScore(r, bb, b -> Tuning.get("need.defaultScore")));
        };
        return wire(d, r).site(b -> rungSite(r, b));
    }

    /** AA_ROUND3 2: blackboard fact, a {@code Supplier<int[]>} of the nearest known coal ore (the adapter caches it). */
    public static final String COAL_SITE_FACT = "aa.coalSite";

    /**
     * AA_ROUND3 2: a need's site for the sequencer: iron from its chosen option, torches at the nearest known coal,
     * the table/furnace at the camp (else the base), base/bed/bag at the base; the rest none.
     */
    static io.github.mojolowjo.entropybot.aa.brain.Site rungSite(Need r, Blackboard b) {
        int[] place = b.in.campPos != null ? b.in.campPos : b.in.basePos;
        return switch (r.id) {
            case "needs.iron" -> r.picker() == null ? null : r.picker().lastSite();
            case "needs.torches" -> b.fact(COAL_SITE_FACT) instanceof java.util.function.Supplier<?> s && s.get() instanceof int[] p ? io.github.mojolowjo.entropybot.aa.brain.Site.of(p) : null;
            case "needs.tableFurnace", "needs.spareTable" -> io.github.mojolowjo.entropybot.aa.brain.Site.of(place);
            case "needs.base", "needs.bed", "needs.bag" -> io.github.mojolowjo.entropybot.aa.brain.Site.of(b.in.basePos);
            default -> null;
        };
    }

    /** The table/furnace rung's value: the table's while no table, else the furnace's (Worth.station). */
    static double stationValue(Blackboard b) {
        boolean table = b.in.tableKnown || b.in.tableInBag;
        if (!table) return Worth.table(tree(b), have(b));
        if (!(b.in.furnaceKnown || b.in.furnaceInBag)) return Worth.furnace(tree(b), have(b));
        // aa-kit camp: the chest is worth the stash it makes possible (the loot x the night's danger), at least Tuning chestMin
        double v = Math.max(Tuning.get("chestMin"), Math.min(70, Kit.lootWorth(b.in.bag) * bedDanger(b)));
        io.github.mojolowjo.entropybot.aa.brain.Costs.note(b, "camp", "chest: loot " + Math.round(Kit.lootWorth(b.in.bag)) + " x danger "
                + Math.round(bedDanger(b) * 100) / 100.0 + " (at least " + Math.round(Tuning.get("chestMin")) + ") -> " + Math.round(v));
        return v;
    }


    /** aa-kit camp: a chest stands at the base (scanned), or the camp has none yet. */
    static boolean campChest(Blackboard b) { return b.in.baseChests; }

    /** What a bed saves tonight: the night's danger at the current facts (any time of day; Peaceful 0). */
    static double bedDanger(Blackboard b) {
        if (!b.in.hungerDrains) return 0;                  // Peaceful (the adapter's flag, as Night.hostile reads it)
        return io.github.mojolowjo.entropybot.aa.brain.Costs.nightDanger(b.in.difficulty, b.in.armorPoints, b.in.health, b.in.maxHealth,
                b.in.torchesNear, b.in.hostilesNear);
    }

    /** AA_ROUND2 1+2: a rung desire's probe (may it start?) and, with a picker, its live cost. */
    public static long rescoreMs() { return Tuning.l("aaDesires.rescoreMs"); }

    public static Desire wire(Desire d, Need r) {
        d.probe(r::probe);
        return d.liveCost(r::liveCostS);
    }

    public static Desire idle(TaskKind kind, Node node, Scorer scorer) {
        return new Desire("idle." + kind.name().toLowerCase(java.util.Locale.ROOT), Desire.Kind.UPKEEP, node,
                bb -> {
                    // a cooling option is left out (the idle chooser's rule), so the next one gets its turn
                    double s = scorer.score(bb.in)[kind.ordinal()];
                    return s < 0 ? -1 : s * idleScale();
                }).exposure(exposureOf(kind)).cools(kind == TaskKind.WAIT ? 0 : Cooldown.noRunMsV(), kind == TaskKind.WAIT ? 0 : Cooldown.noRunMsV())
                .lastTry();
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
                .patient(Long.MAX_VALUE / 4)          // the options' own watchdog halts a stalled one (the next option runs)  // structural: a patience far beyond any option (a quarter of Long.MAX_VALUE ms)
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
        for (int i = 1; i < ok.size() && i < 4; i++) b.append(i == 1 ? " (next " : ", ").append(optName(ok.get(i).d().id)).append(' ').append(Math.round(ok.get(i).score()));  // structural: display: how many items a text lists
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
    static double scaled(double base, double f) { return base >= Tuning.get("urgent") ? base : Math.min(Tuning.get("urgent") - 1, f * base); }

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
        if (t == null || bb.in.pickTier < 3) return null;  // game fact: pickaxe tier scale: wood 1, stone 2, iron 3, diamond 4
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
            String goal = goalName(p.item()) + " 1";
            JobPort.Resolved r = bb.port.resolveState(goal);
            if (r.state() == JobPort.PlanState.NONE) {             // review: no plan for it: left out for this session, the next idea gets its turn
                bad(bb).add(p.item());
                bb.port.log("ideas: no plan for " + goalName(p.item()) + " - left out this session (" + r.why() + ")");
            }
            return r.state() != JobPort.PlanState.READY ? null : JobPort.TaskSpec.steps(TaskKind.EXPLORE, plan(goal), "ideas");
        });
        Node node = AaTree.idleCd("ideas.cd", job);
        return new Desire("ideas", Desire.Kind.PROGRESS, node, bb -> {
            Pick p = bestIdea(bb);
            return p == null ? -1 : Tuning.i("aaDesires.shareScore") * p.share();
        }).cost(300).prereq(b -> b.in.pickTier < 3 ? "needs.iron" : null)  // game fact: pickaxe tier scale: wood 1, stone 2, iron 3, diamond 4
                .cools(Cooldown.noRunMsV(), Cooldown.noRunMsV()).lastTry();
    }

    /** The top ideas for aa tree / aa why ("shield 3.0, bucket 3.0"), or why there are none. */
    public static String ideasLine(Blackboard bb, int n) {
        TechTree t = tree(bb);
        if (t == null) return "ideas: no recipe graph yet";
        List<String> out = new ArrayList<>();
        for (TechTree.Idea i : t.ideas(have(bb), n)) out.add(goalName(i.item()) + " " + Math.round(i.worth() * 10) / 10.0);  // structural: rounded to one decimal for the text
        return "ideas (" + t.size() + " recipes" + (bb.in.pickTier < 3 ? "; worked on from an iron pickaxe" : "") + "): "  // game fact: pickaxe tier scale: wood 1, stone 2, iron 3, diamond 4
                + (out.isEmpty() ? "none" : String.join(", ", out));
    }

    // ---- B4: the owner's goals (2.5) ----

    /** An owner goal (aa want): ownerBase +/- 20, fresh for 5 min (may interrupt once), parked when blocked. */
    public static Desire ownerGoal(io.github.mojolowjo.entropybot.aa.brain.Goals.Goal g) {
        String id = g.id();
        RunJob job = new RunJob(id + ".job", TaskKind.EXPLORE, bb -> {
            List<Step> s = io.github.mojolowjo.entropybot.aa.brain.Goals.steps(bb, g);
            if (s == null) return null;
            String pg = Ways.planGoal(s);
            if (pg != null) {
                JobPort.Resolved r = bb.port.resolveState(pg);
                if (r.state() != JobPort.PlanState.READY) {
                    bb.port.log("aa goal " + g.r.key() + ": no plan for " + pg + " (" + (r.state() == JobPort.PlanState.SEARCHING ? "still searching" : r.why()) + ")");
                    return null;
                }
            }
            return JobPort.TaskSpec.steps(TaskKind.EXPLORE, s, "owner goal");
        });
        Node node = AaTree.idleCd(id + ".cd", job);
        return new Desire(id, Desire.Kind.OWNER, node, bb -> io.github.mojolowjo.entropybot.aa.brain.Goals.score(bb, g))
                .infeasible(bb -> io.github.mojolowjo.entropybot.aa.brain.Goals.blocked(bb, g))
                .prereq(bb -> io.github.mojolowjo.entropybot.aa.brain.Goals.prereq(bb, g))
                .fresh(g.given + io.github.mojolowjo.entropybot.aa.brain.Goals.freshMs())
                .cost(600).exposure(g.r.ore() ? Desire.Exposure.UNDER : Desire.Exposure.ANY).cools(Cooldown.noRunMsV(), Cooldown.noRunMsV()).lastTry();
    }

    // ---- B7: schematic builds (2.12) ----

    /** A build goal (aa build): as an owner goal; the chain gathers what the current layer lacks, else builds. */
    public static Desire ownerBuild(io.github.mojolowjo.entropybot.aa.brain.Blueprint.Build b) {
        String id = b.id();
        RunJob job = new RunJob(id + ".job", TaskKind.BASE_BED, bb -> {
            Step s = io.github.mojolowjo.entropybot.aa.brain.Blueprint.step(bb, b);
            if (s == null) return null;
            if (s instanceof Step.Plan p) {
                JobPort.Resolved r = bb.port.resolveState(p.goal());
                if (r.state() == JobPort.PlanState.NONE) {                     // review 1: skip it next pick; Baritone builds the rest
                    bb.port.log("aa build " + b.name + ": no plan for " + p.goal() + " - building without it (" + r.why() + ")");
                    b.unresolved.add(p.goal().split(" ")[0]);
                }
                return r.state() != JobPort.PlanState.READY ? null : JobPort.TaskSpec.steps(TaskKind.EXPLORE, List.of(s), "owner build");
            }
            return JobPort.TaskSpec.steps(TaskKind.BASE_BED, List.of(s), "owner build", Tuning.i("aaDesires.ownerBuildMin") * 60_000L, JobPort.TaskSpec.noProgressMsV());
        });
        Node node = AaTree.idleCd(id + ".cd", job);
        return new Desire(id, Desire.Kind.OWNER, node, bb -> io.github.mojolowjo.entropybot.aa.brain.Blueprint.score(bb, b))
                .infeasible(bb -> io.github.mojolowjo.entropybot.aa.brain.Blueprint.blocked(bb, b))
                .fresh(b.given + io.github.mojolowjo.entropybot.aa.brain.Goals.freshMs())
                .cost(1200).cools(Cooldown.noRunMsV(), Cooldown.noRunMsV()).lastTry();
    }

    // ---- B3: wants (2.2, 2.4) and the night (2.10) ----

    /** A want: personality weight x boredom; its job cools down like an idle option. */
    static Desire want(String key, TaskKind kind, Function<Blackboard, List<Step>> steps, Function<Blackboard, String> infeasible, Desire.Exposure exposure) {
        String id = "want." + key;
        RunJob job = new RunJob(id + ".job", kind, bb -> {
            List<Step> s = steps.apply(bb);
            return s == null ? null : JobPort.TaskSpec.steps(kind, s, "want");
        });
        Node node = AaTree.idleCd(id + ".cd", job);
        return new Desire(id, Desire.Kind.WANT, node, bb -> Wants.score(bb, id, key))
                .infeasible(infeasible)
                .exposure(exposure).cools(Cooldown.noRunMsV(), Cooldown.noRunMsV()).lastTry();
    }

    static String buildInfeasible(Blackboard bb) {
        if (bb.in.basePlace == null) return "no base place fits a house (7x7 or more)";
        if (bb.in.baseKnown) return "a house stands already (base known)";
        if (!bb.flag(AaLadder.SHELTER_FACT)) return "needs.base builds the first one";
        return null;
    }

    /** Owner default (Q3): build a house (where only a shelter stands), explore, tidy the bag into the chests. */
    public static List<Desire> wants() {
        return List.of(
                want("build", TaskKind.BASE_BED, bb -> bb.in.basePlace == null ? null : List.of(new Step.House(bb.in.basePlace)), AaDesires::buildInfeasible, Desire.Exposure.SURFACE),
                want("explore", TaskKind.EXPLORE, bb -> List.of(new Step.Explore((int) t("steps.exploreMin"))), bb -> null, Desire.Exposure.SURFACE),
                want("tidy", TaskKind.DEPOSIT, bb -> List.of(new Step.Deposit()), bb -> bb.in.baseChests ? null : "no base chests to put things in", Desire.Exposure.ANY));
    }

    public static long shelterPatienceMs() { return Tuning.l("aaDesires.shelterPatienceMs"); }

    /** 2.10 sleep: a bed known (or in the bag), rising from dusk; off in Peaceful. Never urgent (waits its turn). */
    static double sleepScore(Blackboard bb) {
        if (!bb.in.hungerDrains || !(bb.in.bedKnown || bb.in.bedInBag)) return -1;
        double d = Night.dusk(bb.in.dayTime, bb.in.night);
        return d <= 0 ? -1 : Personality.of(bb).get("sleep") * d;
    }

    /**
     * AA_STEP2 1: the night's part of the danger need (was the night.shelter desire): from dusk at the surface, the full
     * night {@code Costs.shelterScore(nightDanger)}; off in Peaceful, deep under ground (the night work's place,
     * AaLadder.SURFACE_DEPTH+) and in my shell with work under ground ready. In my shell otherwise it keeps scoring
     * (the way is "wait it out"), so surface work does not lure the bot out.
     */
    static double shelterScore(Blackboard bb) {
        if (!bb.in.hungerDrains) return -1;
        double d = Night.dusk(bb.in.dayTime, bb.in.night);
        if (d <= 0) return -1;
        // aa-night2 P0 1 (normal run 01:44: "night (danger 1.37 ...): shelter -" at the spawn): "under ground" was
        // !canSeeSky, true under a tree, an overhang or a staircase's first steps. Off only deep under
        String off = shelterOff(bb.in.sheltered && nightWorkReady(bb), bb.in.underDepth);
        if (off != null) {
            io.github.mojolowjo.entropybot.aa.brain.Costs.note(bb, "shelter", "off: " + off);
            return -1;
        }
        // live aa377: 55 at night lost to tools 74 all night. Full night at the surface: above the urgent line, higher
        // early game (no armour, no bed); the grace lets the running step finish first
        // aa-costs (was a fixed 80 + 5 no armour + 5 no bed): the danger of staying out (Costs.nightDanger)
        if (d >= 1) {
            double danger = io.github.mojolowjo.entropybot.aa.brain.Costs.nightDanger(bb.in.difficulty, bb.in.armorPoints, bb.in.health, bb.in.maxHealth,
                    bb.in.torchesNear, bb.in.hostilesNear);
            io.github.mojolowjo.entropybot.aa.brain.Costs.note(bb, "shelter", io.github.mojolowjo.entropybot.aa.brain.Costs.nightTerms(bb.in.difficulty,
                    bb.in.armorPoints, bb.in.health, bb.in.torchesNear, bb.in.hostilesNear, danger));
            return io.github.mojolowjo.entropybot.aa.brain.Costs.shelterScore(danger);
        }
        if (bb.in.bedKnown || bb.in.bedInBag) return -1;      // dusk with a bed: night.sleep waits its turn
        return Personality.of(bb).get("shelter") * d;
    }

    /** Why the night need is off here (null = it is not): in my shell with work under ground ready, or deep under ground. */
    static String shelterOff(boolean shelteredWorkReady, int underDepth) {
        if (shelteredWorkReady) return "in my shell, work under ground is ready";
        if (underDepth >= AaLadder.surfaceDepth()) return underDepth + " blocks under ground";
        return null;
    }

    // ---- aa-night2 P0 1: hide and heal ----

    /** Health the hide waits for; health under which it starts by day with hostiles near. */
    public static float healTo() { return (float) Tuning.get("healTo"); }
    public static float hideDayUnder() { return (float) Tuning.get("hideDayUnder"); }
    /** Blackboard fact: the hide has started (Boolean): it keeps scoring until health reaches {@link #HEAL_TO}. */
    public static final String HIDE_FACT = "aa.hide";

    /**
     * Hide and heal: nightDanger x missing health (x Tuning hidePerHp, at most 99) at a hostile night, or by day with
     * hostiles near and health under {@link #HIDE_DAY_UNDER}; once started it holds until {@link #HEAL_TO}. Pure.
     */
    public static double hideScore(boolean hostileNight, int hostilesNear, float health, float maxHealth, double danger, boolean started) {
        if (health >= Tuning.get("healTo")) return -1;
        boolean trigger = started || (hostileNight ? health < maxHealth : hostilesNear > 0 && health < Tuning.get("hideDayUnder"));
        if (!trigger) return -1;
        return Math.min(Tuning.get("shelterMax"), Tuning.get("hidePerHp") * Math.max(1, danger) * (maxHealth - health));
    }

    static double hideScore(Blackboard bb) {
        if (!bb.in.hungerDrains || bb.in.sheltered) return -1;
        boolean started = Boolean.TRUE.equals(bb.fact(HIDE_FACT));
        if (bb.in.health >= Tuning.get("healTo") && started) bb.fact(HIDE_FACT, null);
        double danger = io.github.mojolowjo.entropybot.aa.brain.Costs.nightDanger(bb.in.difficulty, bb.in.armorPoints, bb.in.health, bb.in.maxHealth,
                bb.in.torchesNear, bb.in.hostilesNear);
        double v = hideScore(Night.hostile(bb), bb.in.hostilesNear, bb.in.health, bb.in.maxHealth, danger, started && bb.in.health < Tuning.get("healTo"));
        if (v >= Tuning.get("urgent")) bb.fact(HIDE_FACT, Boolean.TRUE);
        if (v > 0) io.github.mojolowjo.entropybot.aa.brain.Costs.note(bb, "hide", "health " + Math.round(bb.in.health) + "/" + Math.round(bb.in.maxHealth)
                + ", danger " + Math.round(danger * 100) / 100.0 + ", hostiles " + bb.in.hostilesNear + " -> " + Math.round(v));  // structural: percent
        return v;
    }

    // ---- AA_CLEANUP 4 + AA_STEP2 1: the one danger desire (the foes' ways and the night ways, priced) ----

    /** Blackboard fact: a {@code Supplier<ThreatFacts>} (the adapter's, cached about a second). */
    public static final String THREAT_FACT = "aa.threatFacts";
    public static final String DANGER_ID = "danger";
    /** Blackboard fact: the way the danger job started last (Way), so a failure holds that way only. */
    public static final String WAY_FACT = "aa.dangerWay";

    /**
     * AA_STEP2 1: the need besides the foes (was night.shelter and night.hide): the larger of the night's
     * ({@link #shelterScore}) and the hide's ({@link #hideScore}); -1 in Peaceful or when nothing threatens.
     */
    static DangerOptions.Need need(Blackboard bb) {
        if (!bb.in.hungerDrains) return DangerOptions.Need.NONE;
        double danger = io.github.mojolowjo.entropybot.aa.brain.Costs.nightDanger(bb.in.difficulty, bb.in.armorPoints, bb.in.health, bb.in.maxHealth,
                bb.in.torchesNear, bb.in.hostilesNear);
        double night = shelterScore(bb), hide = bb.in.sheltered ? -1 : hideScore(bb);
        double v = Math.max(night, hide);
        if (v < 0) return DangerOptions.Need.NONE;
        String why = (hide > night ? "hide and heal " + Math.round(hide) : "night " + Math.round(night)) + " (danger " + Math.round(danger * 100) / 100.0 + ")";  // structural: percent
        double nd = Night.hostile(bb) ? danger : 0;
        double[] dc = deathCost(bb.in.bag, bb.in.pos, bb.in.spawnPos, nd);
        String dw = "kit " + Math.round(dc[1]) + " + walk back " + (bb.in.spawnPos == null ? "0 (spawn unknown)" : String.valueOf(Math.round(dc[2])))
                + " + respawn " + Math.round(Tuning.get("respawnS")) + " + night " + Math.round(dc[3]);
        return new DangerOptions.Need(v, why, nd, bb.in.sheltered || sealedHere(bb), shellBlocks(bb.in.bag),
                bb.in.bedKnown || bb.in.bedInBag, bb.in.baseKnown, AaEscape.pillarBlocks(bb.in.bag), waterHere(bb), bb.in.air, dc[0], dw);
    }

    /** AA_STEP3 A1: seconds the death screen and the respawn take (the respawn button, the chunks loading). */
    static double respawnS() { return Tuning.get("respawnS"); }

    /**
     * AA_STEP3 A1 (TIME_MODEL death cost): {total, kit, walk back, night}: the kit's worth (Kit.worth), the walk from the
     * respawn back here (Site travel; 0 when the spawn is unknown or near), the respawn, and the night's danger at the
     * spot (Kit.DIED_S x (1 + danger), as the recovery prices it). Pure.
     */
    static double[] deathCost(java.util.Map<String, Integer> bag, int[] pos, int[] spawn, double danger) {
        double kit = io.github.mojolowjo.entropybot.aa.brain.Kit.worth(bag);
        double walk = 0;
        if (pos != null && spawn != null) {
            double w = io.github.mojolowjo.entropybot.aa.brain.Site.of(spawn).travelS(io.github.mojolowjo.entropybot.aa.brain.Site.of(pos));
            walk = Double.isInfinite(w) ? 0 : w;
        }
        double night = Tuning.get("diedS") * (1 + Math.max(0, danger));
        return new double[]{kit + walk + Tuning.get("respawnS") + night, kit, walk, night};
    }

    /** AA_STEP3 A5: blackboard fact (long[]{x, y, z, at ms}): where and when the air reflex paused a job too long (this spot is water). */
    public static final String WATER_HERE_FACT = "aa.waterHere";

    /** The air reflex paused a job here within the last minute (within 4 blocks). */
    static boolean waterHere(Blackboard bb) {
        if (!(bb.fact(WATER_HERE_FACT) instanceof long[] a) || bb.in.pos == null) return false;
        int[] p = bb.in.pos;
        if (bb.clock.now() - a[3] > Tuning.i("aaDesires.pointAgeMs")) return false;
        return Math.abs(p[0] - a[0]) <= Tuning.i("aaDesires.samePointR") && Math.abs(p[1] - a[1]) <= Tuning.i("aaDesires.samePointR") && Math.abs(p[2] - a[2]) <= Tuning.i("aaDesires.samePointR");
    }

    /** The priced ways now (null: no facts supplier); the held ways (Failures, this desire's options) are out. */
    static DangerOptions.Choice dangerChoice(Blackboard bb) {
        if (!(bb.fact(THREAT_FACT) instanceof java.util.function.Supplier<?> s)) return null;
        Object f = s.get();
        if (!(f instanceof io.github.mojolowjo.entropybot.threat.ThreatFacts tf)) return null;
        long now = bb.clock.now();
        java.util.function.Function<String, String> held = bb.fact(Brain.FAILURES_FACT) instanceof io.github.mojolowjo.entropybot.aa.brain.Failures fs
                ? k -> fs.held(DANGER_ID, k, now) : k -> null;
        // reflex probe 0.39.s5: a spent reflex (its line while it holds) re-prices with its ways held and runs a way that acts
        if (bb.fact(REFLEX_FAILED_FACT) instanceof io.github.mojolowjo.entropybot.aa.moves.ReflexLadder.Spent sp) return DangerOptions.afterReflex(tf, need(bb), sp.line(), sp.held(), held);
        return DangerOptions.choose(tf, need(bb), held);
    }

    /** Reflex probe 0.39.s5: blackboard fact (ReflexLadder.Spent: the line and the danger ways to hold), the spent reflex while it holds the danger (AaRuntime). */
    public static final String REFLEX_FAILED_FACT = "aa.reflexFailed";

    /** The foes' saved seconds x danger.perS, or the need's score (at most 99: trumps); -1 calm or staying is best. */
    static double dangerScore(Blackboard bb) {
        DangerOptions.Choice c = dangerChoice(bb);
        if (c == null) return -1;
        if (c.stay() != null || c.best() != null || c.score() >= 0 || !c.all().isEmpty()) io.github.mojolowjo.entropybot.aa.brain.Costs.note(bb, "danger", c.terms());
        return c.score();
    }

    /**
     * AA_CLEANUP 4 + AA_STEP2 1: the danger desire (UPKEEP, trumps, never discounted by the night): its job runs the
     * best way's typed steps (DangerOptions.stepsOf: flee and wait, burrow and wait sealed, the shelter, sleep, the walk
     * home and the shelter, a wait...); it ends when staying is cheapest and the night is over. A failed run holds that way only (10 min x fails),
     * the next way runs at once. The reflexes keep only air, lava/fire, fall, the creeper flee and the hit response.
     */
    public static Desire danger() {
        RunJob job = new RunJob(DANGER_ID + ".job", TaskKind.BASE_BED, bb -> {
            DangerOptions.Choice c = dangerChoice(bb);
            List<Step> steps = c == null || c.score() < 0 ? null : c.steps();
            if (steps == null) {
                bb.port.log("danger: nothing to run (" + (c == null ? "no threat facts" : c.terms()) + ")");
                return null;
            }
            bb.fact(WAY_FACT, c.best().way());
            bb.port.log("danger: " + c.terms());
            boolean night = c.best().way().ordinal() >= DangerOptions.Way.BOX.ordinal();
            return JobPort.TaskSpec.steps(TaskKind.BASE_BED, steps, "danger", night ? JobPort.TaskSpec.timeoutMsV() : Tuning.i("aaDesires.dangerBedMin") * 60_000L,
                    night ? shelterPatienceMs() : 90_000L);
        });
        return new Desire(DANGER_ID, Desire.Kind.UPKEEP, wayHolds(job), AaDesires::dangerScore)
                .cools(5_000, 0).optionHolds().patient(shelterPatienceMs()).overTrumps().lastTry().nightProof()
                .met(AaDesires::dangerMet);
    }

    /** Review 4: the ways that leave the bot sealed in; the fact (int[] pos) where it sealed in last. */
    static final java.util.Set<DangerOptions.Way> SEALS = java.util.EnumSet.of(DangerOptions.Way.BURROW, DangerOptions.Way.DIG_IN, DangerOptions.Way.BOX);
    public static final String SEALED_FACT = "aa.sealedAt";
    /** Review 2: a failed way's hold when no other way is open (else Failures' 10 min x fails). */
    static long shortHoldMs() { return Tuning.l("aaDesires.shortHoldMs"); }

    static long holdMs(int othersOpen, int fails) {
        if (othersOpen <= 0) return shortHoldMs();
        return Math.min(io.github.mojolowjo.entropybot.aa.brain.Failures.retryMaxMs(), io.github.mojolowjo.entropybot.aa.brain.Failures.retryMs() * Math.max(1, fails));
    }

    /** Review 4: still in the cell it sealed in (1 across, 3 up or down); moved away = the fact goes. */
    static boolean sealedHere(Blackboard bb) {
        if (!(bb.fact(SEALED_FACT) instanceof int[] at) || bb.in.pos == null) return false;
        int[] p = bb.in.pos;
        boolean here = Math.abs(p[0] - at[0]) <= 1 && Math.abs(p[2] - at[2]) <= 1 && Math.abs(p[1] - at[1]) <= Tuning.i("aaDesires.hereDy");
        if (!here) bb.fact(SEALED_FACT, null);
        return here;
    }

    /** AA_STEP2 1: the danger job's node: a failed run holds its way in Failures ("danger/box"), logged; the rest stay open. */
    static Node wayHolds(RunJob job) {
        return new Node(job.id + ".ways", "the danger job; a failure holds its way") {
            private long repricedAt = -1;

            @Override protected Status run(Blackboard bb) {
                String re = reprice(bb, job, repricedAt);
                if (re != null) {
                    repricedAt = bb.clock.now();
                    bb.port.log("danger: " + re);
                    bb.tracer.event("danger " + re);
                    job.halt(re);
                    bb.fact(WAY_FACT, null);
                    return Status.RUNNING;             // the next tick starts the cheaper way
                }
                boolean ran = job.running();           // review 3: a start refused ("busy: ...") never ran: no hold
                // backbone step 2: a danger move that failed fails its chain (no "wait 120" in the open after it)
                Object mf = bb.fact(MOVE_FAILED_FACT);
                if (mf != null) bb.fact(MOVE_FAILED_FACT, null);
                Status s = ran && mf instanceof String why ? job.failNow("danger move " + why) : job.tick(bb);
                if (s == Status.FAILURE && bb.fact(WAY_FACT) instanceof DangerOptions.Way w) {
                    bb.fact(WAY_FACT, null);
                    if (!ran) bb.port.log("danger: " + w.key() + " did not start (" + job.reason() + "): not held");
                    else if (w == DangerOptions.Way.WAIT) bb.port.log("danger: wait failed (" + job.reason() + "): the fallback is never held");
                    else if (bb.fact(Brain.FAILURES_FACT) instanceof io.github.mojolowjo.entropybot.aa.brain.Failures fs) {
                        long now = bb.clock.now();
                        java.util.function.Function<String, String> held = k -> k.equals(w.key()) ? "this one" : fs.held(DANGER_ID, k, now);
                        int open = DangerOptions.openWays(dangerChoice(bb), held);
                        long ms = holdMs(open, fs.fails(DANGER_ID, w.key()) + 1);
                        fs.failedFor(DANGER_ID, w.key(), job.reason(), now, bb.in, ms);
                        bb.port.log("danger: " + w.key() + " failed (" + job.reason() + "): held " + ms / 1000 + " s, " + open + " other way(s) open");  // structural: milliseconds per second
                    } else bb.port.log("danger: " + w.key() + " failed (" + job.reason() + "), no failure record to hold it");
                }
                if (s == Status.SUCCESS) {
                    // review 4: a dug or built shell counts as sheltered while the bot stays in it (no re-burrow loop)
                    if (bb.fact(WAY_FACT) instanceof DangerOptions.Way w && SEALS.contains(w) && bb.in.pos != null) {
                        bb.fact(SEALED_FACT, bb.in.pos.clone());
                        bb.port.log("danger: " + w.key() + " done, sealed in at " + java.util.Arrays.toString(bb.in.pos) + " (stay: the cheapest way)");
                    }
                    bb.fact(WAY_FACT, null);
                }
                return s == Status.FAILURE ? fail(job.reason()) : s;
            }

            @Override public void halt(String why) { job.halt(why); }

            @Override public List<Node> children() { return List.of(job); }
        };
    }

    /** AA_STEP3 A6: how often a running danger way is priced again (the Replan cadence). */
    static long repriceMs() { return Tuning.l("aaDesires.repriceMs"); }

    /**
     * AA_STEP3 A6 (step2-watch: health 4 while drowning changed nothing): every {@link #REPRICE_MS} the running way is
     * priced again with the fresh facts (health, water, air); when another way is cheaper by more than {@code danger.minS}
     * (or the running one is no longer possible) the job ends with the numbers and the cheaper way starts. Not while a
     * danger move runs (it ends on its own clock) or the job is paused. Null = keep it.
     */
    static String reprice(Blackboard bb, RunJob job, long lastAt) {
        if (!job.running() || job.paused() || Boolean.TRUE.equals(bb.fact(MOVE_FACT))) return null;
        if (!(bb.fact(WAY_FACT) instanceof DangerOptions.Way w)) return null;
        long now = bb.clock.now();
        if (lastAt >= 0 && now - lastAt < repriceMs()) return null;
        if (job.ageS(now) * 1000 < repriceMs()) return null;  // structural: milliseconds per second
        DangerOptions.Choice c = dangerChoice(bb);
        if (c == null || c.best() == null || c.score() < 0 || c.best().way() == w || c.best().way() == DangerOptions.Way.STAY) return null;
        DangerOptions.Priced mine = null;
        for (DangerOptions.Priced p : c.all()) if (p.way() == w) mine = p;
        double margin = Tuning.get("danger.minS");
        // review 0.39.a4 1: a way not priced or not possible now (the shore once out of the water, a box once on the
        // ground) is under way: its chain is kept; only a way saving more than danger.minS over it replaces it
        if (mine == null || !mine.possible() || mine.costS() - c.best().costS() <= margin) return null;
        return "re-priced: " + c.best().way().key() + " " + Math.round(c.best().costS()) + " s vs " + w.key() + " "
                + Math.round(mine.costS()) + " s (over danger.minS " + Math.round(margin) + "): " + w.key() + " ends";
    }

    /** 0.38.b9: blackboard fact (Boolean), a danger move (flee, cover, pillar, burrow) runs now (the adapter's). */
    public static final String MOVE_FACT = "aa.dangerMove";
    /** Backbone step 2: blackboard fact (String), the danger move that just failed and why; set only while the danger job runs. */
    public static final String MOVE_FAILED_FACT = "aa.dangerMoveFailed";

    /**
     * 0.38.b9 (review: flee flapped, "stay" got cheap a few blocks out and the job was cut mid-flee): the danger desire is
     * done only when staying is cheapest AND no danger move runs (the move ends on its own calm clock or arrival).
     */
    static boolean dangerMet(Blackboard b) {
        return !Boolean.TRUE.equals(b.fact(MOVE_FACT)) && dangerScore(b) < 0;
    }

    /** Planks, cobblestone and dirt in the bag (what ShelterPlan builds a shell from). */
    static int shellBlocks(java.util.Map<String, Integer> bag) {
        // review 0.39.a4 4: one list with the pillar (AaEscape.pillarBlocks: throwaway and planks over the reserve)
        return AaEscape.pillarBlocks(bag);
    }

    /**
     * 2.10: sleep (UPKEEP under 75: it waits for the running task, then wins by number). AA_STEP2 1: night.shelter and
     * night.hide are gone; the danger desire's night ways (box, burrow, dig in, pillar, flee home, sleep, wait it out)
     * answer the night. In the shell with work under ground ready the need is off, so that work runs.
     */
    public static List<Desire> night() {
        Node sleep = AaTree.idleCd("night.sleep.cd", RunJob.of("night.sleep.job", TaskKind.BASE_BED, List.of(new Step.Sleep()), "night"));
        return List.of(
                new Desire("night.sleep", Desire.Kind.UPKEEP, sleep, AaDesires::sleepScore).cools(Cooldown.noRunMsV(), Cooldown.noRunMsV()).lastTry().nightProof());
    }

    /** Blackboard fact: when a mining stock-up (UNDER) last scored wanted (ms); the night need yields to it for 10 s after. */
    public static final String UNDER_WANTED_FACT = Brain.UNDER_WANTED_FACT;

    /** In the shell at a hostile night, night work to do (NightWork.pick) and an UNDER stock-up wanted just now. */
    static boolean nightWorkReady(Blackboard bb) {
        if (!NightWork.active(bb) || NightWork.pick(bb.in) == NightWork.Work.NONE) return false;
        return bb.fact(UNDER_WANTED_FACT) instanceof Long at && bb.clock.now() - at <= rescoreMs();
    }

    // ---- B6: helping the owner (2.11) ----

    /** The help desire (UPKEEP under 75: never interrupts); its job does {@link io.github.mojolowjo.entropybot.aa.brain.Help#pick}. */
    public static Desire help() {
        RunJob job = new RunJob("help.owner.job", TaskKind.EXPLORE, bb -> {
            var p = io.github.mojolowjo.entropybot.aa.brain.Help.pick(bb.in, Personality.of(bb).get("help"));
            if (p == null) return null;
            for (Step s : p.steps())
                if (s instanceof Step.Plan pl && bb.port.resolveState(pl.goal()).state() != JobPort.PlanState.READY) {
                    bb.port.log("help: no plan for " + pl.goal() + " (or still searching)");
                    return null;
                }
            bb.port.log("help: " + p.line());
            return JobPort.TaskSpec.steps(TaskKind.EXPLORE, p.steps(), "help", helpTimeoutMs(), JobPort.TaskSpec.noProgressMsV());
        });
        Node node = AaTree.idleCd("help.owner.cd", job);
        // round 2: the brain's own cool-down (Desire.cools, the Failures record) instead of a per-node cooling check
        return new Desire("help.owner", Desire.Kind.UPKEEP, node, bb -> {
            var p = io.github.mojolowjo.entropybot.aa.brain.Help.pick(bb.in, Personality.of(bb).get("help"));
            return p == null ? -1 : p.score();
        }).cools(Cooldown.noRunMsV(), Cooldown.noRunMsV()).lastTry().nightProof();     // the owner's call, not cheaper at night
    }

    /** A help task is short (a walk, a fight, a hand-over); a fetch gets the planner's time. */
    static long helpTimeoutMs() { return Tuning.l("aaDesires.helpTimeoutMs"); }

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
                        + Math.round(Wants.boredom(bb, id, p.get("boredomDays")) * 100) / 100.0 + ")");  // structural: percent
            } else if (id.startsWith("night.")) n.add(id.substring(6) + " " + v);
            else if (id.equals(DANGER_ID)) n.add("danger " + v);          // AA_STEP2 1: the one answer to the night
        }
        double d = Night.dusk(bb.in.dayTime, bb.in.night);
        String time = !bb.in.hungerDrains ? "Peaceful (night desires off)" : d >= 1 ? "night (danger " + Math.round(Brain.danger(bb) * 100) / 100.0 + ": under x" + Math.round(Brain.exposureFactor(Desire.Exposure.UNDER, Brain.danger(bb)) * 100) / 100.0 + ", surface x" + Math.round(Brain.exposureFactor(Desire.Exposure.SURFACE, Brain.danger(bb)) * 100) / 100.0 + ")" : d > 0 ? "dusk" : "day";  // structural: percent
        return "wants: " + (w.isEmpty() ? "none" : String.join(", ", w)) + " | " + time + ": " + String.join(", ", n) + " | " + helpLine(bb)
                + (p.errors.isEmpty() ? "" : " | !! personality: " + String.join("; ", p.errors) + " (defaults used)");
    }

    // ---- aa-kit: recover the kit after a death, stash the loot before dusk and far trips ----

    public static final String RECOVER_ID = "recover.kit", STASH_ID = "stash";

    static double recoverScore(Blackboard bb) {
        Kit.Death d = Kit.of(bb);
        if (d == null) return -1;
        if (d.dim() != null && !d.dim().equals(bb.in.dimension)) return -1;
        double walk = Kit.walkS(bb, d), miss = Kit.missing(d.bag(), bb.in.bag);
        double p = Kit.pThere(bb.clock.now() - d.at(), walk);
        double v = Kit.recoverValue(miss, p);
        io.github.mojolowjo.entropybot.aa.brain.Costs.note(bb, "kit", "missing " + Math.round(miss) + " x P(there) " + Math.round(p * 100) / 100.0  // structural: percent
                + " (" + (bb.clock.now() - d.at()) / 1000 + "s of 300 + walk " + Math.round(walk) + "s) / cost " + Math.round(Kit.recoverCost(walk, Brain.danger(bb))) + "s -> " + Math.round(v));  // structural: milliseconds per second
        return v;
    }

    /** recover.kit (PROGRESS): back to the death spot while the drops last (Kit). */
    public static Desire recover() {
        RunJob job = new RunJob(RECOVER_ID + ".job", TaskKind.EXPLORE, bb -> {
            Kit.Death d = Kit.of(bb);
            return d == null ? null : JobPort.TaskSpec.steps(TaskKind.EXPLORE, Kit.recoverSteps(d), "recover kit", Kit.DESPAWN_MS, JobPort.TaskSpec.noProgressMsV());
        });
        return new Desire(RECOVER_ID, Desire.Kind.PROGRESS, AaTree.idleCd(RECOVER_ID + ".cd", job), AaDesires::recoverScore)
                .liveCost(bb -> {
                    Kit.Death d = Kit.of(bb);
                    return d == null ? null : Kit.recoverCost(Kit.walkS(bb, d), Brain.danger(bb));
                })
                .site(bb -> { Kit.Death d = Kit.of(bb); return d == null ? null : d.site(); })
                .met(bb -> recoverScore(bb) < 0)
                .cools(Cooldown.noRunMsV(), Kit.DESPAWN_MS).lastTry();     // review: one trip; done = rest out the clock
    }

    /** A far trip is next: the iron rung is open and its chosen option's site is FAR_TRIP+ from the base. */
    static boolean farTrip(Need iron, Blackboard bb) {
        if (iron == null || bb.in.basePos == null || !iron.unmet(bb) || iron.picker() == null) return false;
        io.github.mojolowjo.entropybot.aa.brain.Site s = iron.picker().lastSite(), base = io.github.mojolowjo.entropybot.aa.brain.Site.of(bb.in.basePos);
        return s != null && s.sameDim(base) && s.dist(base) >= Kit.farTripV();
    }

    /** The stash's danger: the night's at the current facts any time of day (as the bed's); Peaceful 0. */
    static double stashScore(Blackboard bb, Need iron) {
        double loot = Kit.lootWorth(bb.in.bag);
        if (loot < Kit.minStash()) return -1;
        double danger = bedDanger(bb), soon = Kit.soon(bb.in.dayTime, bb.in.night, farTrip(iron, bb));
        double v = Kit.stashValue(loot, danger, soon);
        io.github.mojolowjo.entropybot.aa.brain.Costs.note(bb, "stash", Kit.terms(loot, danger, soon, v));
        return v;
    }

    /** stash (UPKEEP, never urgent): the loot into the base chest; no chest: the camp rung's value. */
    public static Desire stash(Need iron) {
        Node node = AaTree.idleCd(STASH_ID + ".cd", RunJob.of(STASH_ID + ".job", TaskKind.DEPOSIT, List.of(new Step.Deposit()), "stash"));
        return new Desire(STASH_ID, Desire.Kind.UPKEEP, node, bb -> stashScore(bb, iron))
                .liveCost(bb -> {
                    int[] p = bb.in.pos;
                    io.github.mojolowjo.entropybot.aa.brain.Site base = io.github.mojolowjo.entropybot.aa.brain.Site.of(bb.in.basePos);
                    if (p == null || base == null) return null;
                    double t = io.github.mojolowjo.entropybot.aa.brain.Site.at(p[0], p[1], p[2]).travelS(base);
                    return Double.isInfinite(t) ? null : t;
                })
                .site(bb -> io.github.mojolowjo.entropybot.aa.brain.Site.of(bb.in.basePos))
                .infeasible(bb -> bb.in.baseChests ? null : "no base chest (the camp rung places one)")
                .met(bb -> Kit.lootWorth(bb.in.bag) < Kit.minStash())
                .cools(Cooldown.noRunMsV(), Tuning.i("aaDesires.coolMin") * 60_000L).lastTry();
    }

    /** The brain over the rungs, the idle options and the ideas. */
    public static Brain build(List<Need> rungs, List<Map.Entry<TaskKind, Node>> idle, Scorer scorer) {
        List<Desire> ds = new ArrayList<>();
        for (Need r : rungs) ds.add(need(r));
        ds.add(ideas());
        ds.addAll(night());
        ds.add(danger());           // AA_CLEANUP 4
        ds.addAll(wants());
        ds.add(help());
        Need iron = null;
        for (Need r : rungs) if (r.id.equals("needs.iron")) iron = r;
        ds.add(recover());
        ds.add(stash(iron));
        if (!idle.isEmpty()) ds.add(idleSet(idle, scorer));
        Brain brain = new Brain("brain", "the scored desires", ds.toArray(new Desire[0]));
        // AA_ROUND2 3: the rung pickers' option memories are views over the brain's one failure record
        for (Need r : rungs) r.bindFailures(brain.failures);          // the pickers' and the Ways rows' memories
        return brain;
    }
}
