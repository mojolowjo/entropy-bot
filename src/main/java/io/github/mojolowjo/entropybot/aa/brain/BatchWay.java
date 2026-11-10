package io.github.mojolowjo.entropybot.aa.brain;

import io.github.mojolowjo.entropybot.aa.NeedChain;
import io.github.mojolowjo.entropybot.aa.Playbook;
import io.github.mojolowjo.entropybot.aa.core.Blackboard;
import io.github.mojolowjo.entropybot.aa.core.TickLaps;
import io.github.mojolowjo.entropybot.aa.moves.Step;
import io.github.mojolowjo.entropybot.aa.moves.Steps;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;

/**
 * 0.39.z77 SHAPE CHANGE (owner 2026-10-26 10:30, "playbook first, repair locally"; was J2's playbook-vs-ad-hoc comparison,
 * deleted with Tuning playbook.on): every plain-item order - alone, or its {@code aa want} line's batch - is planned as ONE
 * TRIP, the {@link Playbook} derived from the recipes, the tags, the world-gen data and the tiers, priced inside its legs by
 * {@link NeedChain#resolvePlaybookLegs} (which tree, which strip, table carried or placed, climb or walk up). There is no
 * top-level "which planner": the trip's seconds are said, never compared. A leg whose walk is not known yet from here is
 * priced when the bot gets there (NeedChain); the whole trip waiting on a path stands (rule 22); a trip priced 0 s is
 * refused (a way priced 0 is never valid); a derivation that does not stand (a fact not loaded, rule 15; an item the data
 * gives no route for) falls back to the order's own chain as its one leg, said. The trip's line - its legs and what each
 * carries down - is said to the owner when the order takes it, before the bot moves; each leg is said as it starts (Goals).
 * <p>R3 (resolve time on the client tick): the trip is kept per order (each order's holds are its own), keyed by the open orders, the bag
 * (0.39.z77 seen: only the counts of the items its legs reference, {@link #bagPart}), the place
 * (x and z in failures.moved cells, on the surface or under ground - 0.39.z77 carry: never the feet's Y) and the order's
 * holds; one that waits on paths is kept until the next path answer lands. Measured
 * on the AA tick's stopwatch ({@link TickLaps#playbook}: "probe aatick: ... | playbook N = M ms").
 * <p>0.39.z77 noway: a leg with no way ends only the orders whose own items need it ({@link Playbook.Leg#serves}), parked
 * with the leg's reasons; the batch's other orders derive their trip again without them and run on (said in the log).
 * 0.39.z77 pending: a leg is no way only when every one of its ways has a final answer; one still computing or queued makes
 * it "not priced yet" - the trip stands (rule 22), said to the owner, nobody parked (NeedChain.legNoWay).
 * 0.39.z77 carry: the line's carry lists are read from the trip as priced (every leg, before the run's explore cut), so a
 * re-price of the same legs carries the same; the legs the run leaves to be priced when the bot gets there say so.
 * <p>0.39.z77 trip (manager's ruling 2026-10-26 ~21:35, run CU 21:02-21:06: "playbook 2 = 250-650 ms" on most AA ticks while
 * the bot waited at the furnace, 16 trip lines whose carries swapped): the brain's per-tick waiting test (Goals.blocked) asks
 * the trip for every open order; the batch's holds were each asker's own, and the met order whose desire carries the run on
 * is not in {@link #open} - so its ask and the next order's had two keys and derived the trip in turn, twice an AA tick. Now
 * the batch's holds are the same for every asker (the running order's too, {@link #RUN_FACT}); while the running step needs
 * no walk the waiting test reads the kept trip unless a fact that step depends on changed ({@link #stepFacts}); a re-priced
 * trip burns the fuel it burned before while the bag still covers it (NeedChain.keepingFuel).
 * Pure (JUnit: Z77PlaybookTest, Z77RoundFixTest, Z77NoWayTest, Z77PendingTest, Z77CarryTest, Z77TripTest). Loader notes: none (the world, the ore-per-slab fact and the running step come from AaRuntime).
 */
public final class BatchWay {
    /** 0.39.z78 J6b EH review: the trip is under ground - in.underDepth (Jobs.belowSurface, the one reader) at least aaLadder.surfaceDepth. Pure. */
    static boolean under(io.github.mojolowjo.entropybot.aa.core.Inputs in) { return in != null && in.underDepth >= io.github.mojolowjo.entropybot.aa.AaLadder.surfaceDepth(); }

    private BatchWay() {}

    /** Blackboard fact: the adapter's ore per slab at a Y ({@code BiFunction<String, Integer, Double>}: need word, y; NaN: not known). */
    public static final String PER_SLAB_FACT = "aa.perSlabAt";

    /**
     * 0.39.z78 D6: Blackboard fact - the planner off the render thread ({@link Planner}); none: the trip's legs are resolved
     * here, on the AA tick, as before.
     */
    public static final String PLANNER_FACT = "aa.planner";

    /**
     * 0.39.z78 D6 (design "the worker protocol"): the trip's legs for the batch {@code key} on {@code w} - the applied result
     * of the planner worker when its facts still hold now, else the thinking stand (NeedChain.thinking: not priced yet, the
     * trip stands, rule 22) while the worker resolves a snapshot of these facts. {@code keptFuel}: NeedChain.keepingFuel's.
     */
    public interface Planner {
        NeedChain.Legs legs(String key, Playbook pb, Set<String> keptFuel, NeedChain.World w);

        /** A result for the trip {@code key}'s request has arrived (its kept thinking stand is priced again to take it). */
        boolean ready(String key);

        /**
         * 0.39.z78 D6 (run DT 16:25:09-21: the one-leg trip's own chain resolved on the client tick, 2.1-2.2 s each landing):
         * the order's own chain {@code ask} for {@code key} on {@code w}, as {@link #legs} - the applied worker result when
         * its facts hold now, else the thinking stand (NeedChain.thinking).
         */
        NeedChain.Chain own(String key, OwnAsk ask, NeedChain.World w);
    }

    /**
     * 0.39.z78 D6 (run DT): an order's own chain as the worker resolves it - NeedChain.resolveAll({@code what}, {@code ids},
     * {@code n}) for a group, else NeedChain.resolve({@code what}, {@code n}) - Goals' own supplier as a request.
     */
    public record OwnAsk(String what, List<String> ids, int n, boolean group) {
        public OwnAsk {
            ids = ids == null ? List.of() : List.copyOf(ids);
        }

        /** The chain on {@code w} (the worker's resolve, or Goals' own on the tick). */
        public NeedChain.Chain resolve(NeedChain.World w) {
            return group ? NeedChain.resolveAll(what, ids, n, w) : NeedChain.resolve(what, n, w);
        }
    }

    /**
     * 0.39.z78 D6 (run DT): the stand a thinking own chain is (the trip's own words for a leg not priced yet, shape):
     * "trip: stand (not priced yet: the planner waits on its answer (...))", the bot standing (rule 22).
     */
    static NeedChain.Chain ownStand(NeedChain.Chain thinking) {
        return new NeedChain.Chain(List.of(new Step.AwaitPaths(NeedChain.firstAnswerWaitS(), true)), 0, STAND + thinking.missing() + ")", true, null);   // J6c run EU (1b): the worker's stand
    }

    /** The start of a trip line ("trip b1 (6 orders): ..."). */
    public static final String TRIP = "trip ";

    /** The start of the reason an order ends with when no way stands for it ("no way to get it: playbook: ..."). */
    public static final String NO_WAY = "no way to get it: ";

    /** 0.39.z77 pending: the start of a standing trip's why ("trip: stand (not priced yet: leg 5/5 ...)"). */
    static final String STAND = "trip: stand (";

    /**
     * One trip as priced: its key, the path answers landed then ({@code landed}), whether it waited on no path
     * ({@code settled}), the playbook, its chain, the leg starts (by identity) and the line said. 0.39.z77 noway: {@code ends} -
     * the ids of the open orders a leg with no way ends (those whose own items need that leg) when the others do not need it;
     * empty: the trip's verdict is every order's.
     */
    record Trip(String key, long landed, boolean settled, Playbook pb, NeedChain.Chain chain, Map<Step, String> starts, String line, Set<String> ends, Set<String> refs) {
        boolean holds(String k, long landedNow) { return key != null && key.equals(k) && (settled || landed == landedNow); }
    }

    private static long landed(NeedChain.World w) { return w.path() == null ? 0 : w.path().landed(); }

    /** Per BATCH (trip key): the trip last priced, and its leg starts (shape review 2). */
    private static final Map<String, Trip> TRIPS = new ConcurrentHashMap<>();
    private static final Map<String, Map<Step, String>> LEGS = new ConcurrentHashMap<>();
    private static final Map<String, String> SAID = new ConcurrentHashMap<>(), TOLD = new ConcurrentHashMap<>();

    /** Tests and a new runtime: forget every trip. */
    public static void clear() { TRIPS.clear(); LEGS.clear(); OWN.clear(); PRICED.clear(); SAID.clear(); TOLD.clear(); HOLDS.clear(); FUELS.clear(); ANCHOR_SURFACE.clear(); }

    /**
     * D11 fix: Blackboard fact - the surface over a CELL ({@code Function<int[], NeedChain.Surface>}: AaRuntime.surfaceAt, the one
     * surface reader run at a trip's anchor; null: not known). Not published (tests, replay): the trip keeps the live surface.
     */
    public static final String SURFACE_AT_FACT = "aa.surfaceAt";

    /** D11 fix: per trip key, {the anchor last read, the surface over it} - read once per anchor, held until the anchor changes. */
    private static final Map<String, Object[]> ANCHOR_SURFACE = new ConcurrentHashMap<>();

    /**
     * D11 fix (run FF: "re-planned while thinking: surface" 6,801 times in 25 minutes - the live surface fact's distance and cell
     * move with every block walked, so every waiting trip's result was dropped each step): the surface over the anchor
     * {@code pos} for trip {@code tk}, read ONCE per anchor through {@code read} and held in {@code cache} until the anchor
     * changes (a step's end cell does not move while the bot walks to it). Not known (null, the cell's chunk not loaded) is not
     * held: read again next time, and the caller keeps the live surface for now. Released when the trip has no anchor (the
     * live-feet path of {@link #trip}: the step ended or was cancelled) and by {@link #clear}. Pure given the reader.
     */
    static NeedChain.Surface anchorSurface(Map<String, Object[]> cache, String tk, int[] pos, java.util.function.Function<int[], NeedChain.Surface> read) {
        Object[] c = cache.get(tk);
        if (c != null && java.util.Arrays.equals((int[]) c[0], pos)) return (NeedChain.Surface) c[1];
        if (read == null) return null;
        NeedChain.Surface s = read.apply(pos);
        if (s != null) cache.put(tk, new Object[]{pos.clone(), s}); else cache.remove(tk);
        return s;
    }

    /** 0.39.z77 trip: Blackboard fact - the typed order run now ({@link Run}); null while no order's typed run runs. */
    public static final String RUN_FACT = "aa.tripRun";

    /**
     * 0.39.z77 trip: the order whose typed run runs ({@code order}: its goal id, the run's desire), its running step, that
     * step's planned counts ({@code uses}: Chain#uses; null: not known) and the furnaces' remaining inputs ({@code furnace}:
     * item id -> count, this dimension; null: not read). AaRuntime publishes it each AA tick, before the tree.
     * 0.39.z77 trip 2 (run CV 22:03-22:06): {@code goal} - the order itself (null: found on the goal list by id). Goals.done
     * takes a met order off the list, also one whose desire carries the run on, so a lookup by id found no runner then.
     */
    public record Run(String order, Goals.Goal goal, Step step, Map<String, Integer> uses, Map<String, Integer> furnace) {
        public Run(String order, Step step, Map<String, Integer> uses, Map<String, Integer> furnace) { this(order, null, step, uses, furnace); }
    }

    /** 0.39.z77 trip: per batch, the running step the waiting test held the kept trip for (by identity) and its facts then. */
    private record Hold(Step step, String facts) {}

    private static final Map<String, Hold> HOLDS = new ConcurrentHashMap<>();

    /** 0.39.z77 trip: per batch, the fuels the trip last priced burns (need words; NeedChain.keepingFuel). */
    private static final Map<String, Set<String>> FUELS = new ConcurrentHashMap<>();

    /** The batch's kept trip is priced again on its next ask (a re-price: the stand's estimates, a landed path). */
    static void forget(Goals.Goal g) {
        TRIPS.remove(tripKey(g));
        OWN.remove(g.id());
    }

    /** Tests (R3): how many trips this batch priced so far (each a derivation and its legs' resolve). */
    public static int priced(String tk) { return PRICED.getOrDefault(tk, 0); }

    private static final Map<String, Integer> PRICED = new ConcurrentHashMap<>();

    /** The trip's key: the batch ("b1"), or the order alone ("order goal.torch"). */
    public static String tripKey(Goals.Goal g) { return g.batch != null ? g.batch : "order " + g.id(); }

    /** The leg starts of the trip last priced for {@code key} (by identity of its steps); empty: none. */
    public static Map<Step, String> legs(String key) {
        Map<Step, String> m = key == null ? null : LEGS.get(key);
        return m == null ? Map.of() : m;
    }

    /** The open orders of {@code g}'s trip (its batch's, not parked, not met, plain items, in the batch's order; alone: itself). */
    static List<Goals.Goal> open(Blackboard bb, Goals.Goal g) {
        List<Goals.Goal> out = new ArrayList<>();
        if (g.batch == null || !(bb.fact(Goals.FACT) instanceof List<?>)) {
            if (bb.in == null || Goals.have(bb.in.bag, g) < g.n) out.add(g);
            return out;
        }
        for (Goals.Goal o : Goals.of(bb)) {
            if (!g.batch.equals(o.batch) || o.parked != null || o.strip != null || o.r.group()) continue;
            if (bb.in != null && Goals.have(bb.in.bag, o) >= o.n) continue;
            out.add(o);
        }
        return out;
    }

    static String itemOf(Goals.Goal o) { return o.r.ids().isEmpty() ? o.r.key() : o.r.ids().get(0); }

    /** A trip is for plain-item orders only (a strip order or a group runs its own chain). */
    static boolean tripOrder(Goals.Goal g) { return g.strip == null && !g.r.group(); }

    /**
     * The trip's key (pure): the batch, its open orders, the place, the batch's holds (the bag's part: {@link #bagPart}). The
     * place: x and z in failures.moved cells and where the legs are placed from - on the surface or under ground ({@code under}:
     * the sensed depth below the surface, Jobs.belowSurface (0.39.z78 EH review: the one reader), at least aaLadder.surfaceDepth, as Playbook.facts reads it). 0.39.z77
     * carry (run CB 16:04:24-16:05:34: the trip re-priced at every Y the bot passed, "here at y -20", "-16", "-15", ...): the
     * feet's Y is no fact the trip depends on - only a leg's place changing (the surface <-> under ground) re-prices it, once.
     */
    static String tripKeyOf(String tk, List<Goals.Goal> open, int[] pos, boolean under, java.util.Collection<String> held) {
        StringBuilder b = new StringBuilder(tk).append(" |");
        for (Goals.Goal o : open) b.append(' ').append(o.r.key()).append(':').append(o.n);
        int cell = Math.max(1, (int) Math.round(Failures.movedV()));
        if (pos != null && pos.length >= 3) b.append(" | at ").append(Math.floorDiv(pos[0], cell)).append(',').append(Math.floorDiv(pos[2], cell));   // structural: x y z (y left out)
        b.append(under ? " under ground" : " on the surface");
        b.append(" | held ").append(new java.util.TreeSet<>(held));
        return b.toString();
    }

    /** 0.39.z77 seen: the {@link #refs} entry of a tool kind ("tool pickaxe"): the bag's tools of that kind count in the key. */
    static final String TOOL = "tool ";
    /** 0.39.z77 seen: the {@link #refs} entry of a burn the plan names no fuel item for: the bag's fuels count. */
    static final String FUEL = "fuel";

    /**
     * 0.39.z77 seen, folded in (carry Report open (a); runs CB 16:04, CE 16:19-16:20: digging walks gained cobblestone or
     * deepslate every block or two, a bag fact, so the trip was priced again about once per Y): the key's bag part (pure) -
     * only the counts of the bag's items the trip's legs reference ({@code refs}: {@link #refs}; an item a word takes, a tool of
     * a kind named, a fuel when a burn names none). Null refs (no trip priced: the order's own chain, no derivation): the
     * whole bag, as before.
     */
    static String bagPart(Set<String> refs, Map<String, Integer> bag) {
        if (bag == null) return " | bag ?";
        if (refs == null) return " | bag " + new TreeMap<>(bag);
        TreeMap<String, Integer> out = new TreeMap<>();
        for (var e : bag.entrySet()) if (e.getValue() != null && e.getValue() > 0 && referenced(refs, e.getKey())) out.put(e.getKey(), e.getValue());
        return " | bag " + out;
    }

    /** The bag's {@code item} is one the trip references. */
    static boolean referenced(Set<String> refs, String item) {
        for (String r : refs) {
            if (r.startsWith(TOOL)) { if (MiningTiers.toolTier(item, r.substring(TOOL.length())) > 0) return true; continue; }
            if (r.equals(FUEL)) { if (ItemWorth.fuels().containsKey(item)) return true; continue; }
            if (r.equals(item) || NeedChain.gives(r, item)) return true;
        }
        return false;
    }

    /**
     * 0.39.z77 seen (pure): what the trip's legs reference - the open orders' items, each leg's gets, and per step of the trip
     * as priced ({@code whole}: every leg's steps) what it makes or gets (DryRun.produces), what it uses up (StepNeeds: the
     * consumes, the tag slots, the plan's own counts, a station placed from the bag, the fuel), an explore's until words and
     * the tool kinds the steps need; the pickaxe always (the planner prices every break and walk with the bag's best one).
     * Null: a step's needs could not be read (the whole bag counts, said).
     */
    static Set<String> refs(List<Goals.Goal> open, Playbook pb, NeedChain.Chain whole) {
        Set<String> out = new java.util.TreeSet<>();
        out.add(TOOL + io.github.mojolowjo.entropybot.aa.moves.StepNeeds.TIER_TOOL);
        for (Goals.Goal o : open) out.add(NeedChain.word(itemOf(o)));
        if (pb != null && pb.ok()) for (Playbook.Leg l : pb.legs()) for (Playbook.Target t : l.gets()) out.add(NeedChain.word(t.item()));
        if (whole == null) return out;
        try {
            for (int k = 0; k < whole.steps().size(); k++) {
                Step s = whole.steps().get(k);
                Map<String, Integer> made = io.github.mojolowjo.entropybot.aa.moves.DryRun.produces(s);
                if (made != null) out.addAll(made.keySet());
                if (s instanceof Step.PlaceNear p) out.add(NeedChain.word(p.item()));
                if (s instanceof Step.Explore ex && ex.until() != null) for (String part : ex.until().split("\\|")) if (!part.isEmpty()) out.add(part);
                var n = io.github.mojolowjo.entropybot.aa.moves.StepNeeds.of(s);
                out.addAll(n.consumes().keySet());
                out.addAll(n.tagUses().keySet());
                if (n.hasTool()) out.add(TOOL + n.toolKind());
                if (n.fuelTicks() > 0) out.add(FUEL);
                Map<String, Integer> u = k < whole.uses().size() ? whole.uses().get(k) : null;
                if (u != null) for (String w : u.keySet()) {
                    if (w == null) continue;
                    out.add(w.startsWith(NeedChain.FUEL_USE) ? NeedChain.word(w.substring(NeedChain.FUEL_USE.length())) : NeedChain.word(w));
                }
            }
        } catch (RuntimeException e) {
            io.github.mojolowjo.entropybot.Quiet.warn("BatchWay.refs: a step's needs could not be read - the whole bag keys the trip", e);
            return null;
        }
        return out;
    }

    /** 0.39.z77 shape review 2: an order's own chain where no trip is derived, kept by the trip's key and the landings (per order). */
    private record Own(String key, long landed, boolean settled, NeedChain.Chain chain) {}

    private static final Map<String, Own> OWN = new ConcurrentHashMap<>();

    /** 0.39.z78 D6: the planner key of an order's own chain ("own " + the order's id), apart from the trips' keys. */
    public static final String OWN_KEY = "own ";

    private static NeedChain.Chain own(Blackboard bb, Goals.Goal g, String key, long landed, java.util.function.Supplier<NeedChain.Chain> own,
                                       OwnAsk ask, NeedChain.World w) {
        // wait-not-park (sim, OrderLineScenariosTest: the chain kept "the mining data is not loaded yet" after the data had
        // landed - no path answer landed in between, and the key did not name that fact): the kept chain is keyed by the
        // mining data too, so the chain resolved without it is not the answer once it is there
        if (!MiningTiers.ready()) key = key + " |no mining data";
        Own o = OWN.get(g.id());
        if (o != null && o.key().equals(key) && (o.settled() || o.landed() == landed)) return o.chain();
        // 0.39.z78 D6 (run DT 16:25:09-21: 2.1-2.2 s on the client tick at each landing): with the planner worker installed the
        // own chain is asked of it as the trip's legs are - its applied result (kept below as before), or the thinking stand
        // while it resolves (never kept: the next ask takes the result); without it, resolved here as before
        Planner port = ask != null && bb.fact(PLANNER_FACT) instanceof Planner pp ? pp : null;
        NeedChain.Chain c;
        if (port != null) {
            c = port.own(OWN_KEY + g.id(), ask, w);
            if (NeedChain.thinking(c)) return ownStand(c);
        } else c = own.get();
        PRICED.merge("own " + g.id(), 1, Integer::sum);
        if (c != null) OWN.put(g.id(), new Own(key, landed, !c.waiting() && !Steps.waitsOnPaths(c.steps()), c));
        return c;
    }

    /**
     * The trip {@code g} runs now on {@code w} (kept while its key holds), or {@code own} - the order's own chain, asked only
     * when the derivation does not stand (its one leg, said).
     * <p>0.39.z77 shape review 2 (manager: one re-price per landed path for the whole batch; the 42-per-tick lesson): the trip
     * is kept per BATCH - every order of it asks the same trip on the same world, the batch's holds all applied (each open
     * order's dropped sources), so the six orders' per-tick tests share one price and a landed path re-prices it once.
     */
    static NeedChain.Chain trip(Blackboard bb, Goals.Goal g, NeedChain.World w0, java.util.function.Supplier<NeedChain.Chain> own) {
        return trip(bb, g, w0, own, false, null);
    }

    static NeedChain.Chain trip(Blackboard bb, Goals.Goal g, NeedChain.World w0, java.util.function.Supplier<NeedChain.Chain> own, boolean waitTest) {
        return trip(bb, g, w0, own, waitTest, null);
    }

    /**
     * 0.39.z77 trip: as above; {@code waitTest} - the brain's per-tick waiting test asks (Goals.ownChain): while the batch's
     * running step needs no walk it reads the kept trip ({@link #hold}); every other ask derives by the key, as before.
     */
    static NeedChain.Chain trip(Blackboard bb, Goals.Goal g, NeedChain.World w0, java.util.function.Supplier<NeedChain.Chain> own, boolean waitTest,
                                OwnAsk ask) {
        List<Goals.Goal> open = open(bb, g);
        if (open.isEmpty()) open = List.of(g);
        String tk = tripKey(g);
        Run run = bb.fact(RUN_FACT) instanceof Run r ? r : null;
        Goals.Goal runner = runner(bb, tk, run);
        // 0.39.z77 trip (run CU 20:52-21:05: "playbook 2" on every AA tick exactly while a met order carried the run on): the
        // batch's holds are its open orders' AND the running order's - the same set whoever asks (was: the asker's own plus
        // the open orders', so the met runner's ask and the next order's keyed the trip twice and derived it in turn)
        List<Goals.Goal> holders = new ArrayList<>(open);
        if (runner != null && !holders.contains(runner)) holders.add(runner);
        Map<String, String> mine = Goals.droppedSources(g), others = new TreeMap<>();
        for (Goals.Goal o : holders) for (var e : Goals.droppedSources(o).entrySet()) if (!mine.containsKey(e.getKey())) others.put(e.getKey(), e.getValue());
        // D11 (the anchor rule): the trip is keyed and its legs placed from where the running step ENDS, not the live feet -
        // the next legs start there (z75); walking inside one step changes nothing (run EV: 2,037 results dropped as the Y moved).
        // Review 2 (rule 23): the trip's WORLD reads its holds at the anchor too (Goals.chainWorld(bb, g, pos)), so the key and
        // the legs read the same holds; the order's own chain keeps the live world (w0)
        int[] feet = bb.in == null ? null : bb.in.pos;
        Step runningStep = run == null ? null : run.step();
        int[] pos = anchorOf(runningStep, feet);
        NeedChain.World anchored = w0;
        if (pos == feet) {
            // D11 fix review: no anchor (no running step, a step that moves the bot nowhere known, a cancelled run) - the held
            // anchor surface goes; a later anchor at the same cell reads the surface again (a shaft may have been dug there)
            ANCHOR_SURFACE.remove(tk);
        } else {
            NeedChain.World at = Goals.chainWorld(bb, g, pos);
            if (at != null) {
                // D11 fix (run FF): the surface fact at the anchor too, read once per anchor (the live one moved every block)
                @SuppressWarnings("unchecked")
                var read = bb.fact(SURFACE_AT_FACT) instanceof java.util.function.Function<?, ?> f ? (java.util.function.Function<int[], NeedChain.Surface>) f : null;
                NeedChain.Surface s = anchorSurface(ANCHOR_SURFACE, tk, pos, read);
                anchored = at.withFeetAt(pos, s == null ? null : () -> s);
            }
        }
        NeedChain.World w = others.isEmpty() ? anchored : anchored.withoutSources(others);
        // 0.39.z77 world: the holds as w0 applied them (Goals.chainWorld: the asker's own by position, the world facts held
        // here), so a hold lifted by moving re-keys the trip (was: the asker's own keys by name, a station back once closer
        // kept the trip priced without it)
        java.util.Set<String> held = new java.util.TreeSet<>(Goals.droppedSources(g, pos).keySet());
        held.addAll(others.keySet());
        // D11 review 1 (rule 23): the holds read at the ANCHOR too - the key must not move with the live feet through them
        held.addAll(WorldFacts.of(bb).heldAt(bb.in, pos, WorldFacts.fluid(bb)).keySet());     // 0.39.z77 world 3: by state (hysteresis), never the raw distance
        held.addAll(Goals.stationHolds(bb, pos).keySet());           // 0.39.z78 J5 slice 1: the stations held (the one store)
        boolean under = anchorUnder(runningStep, under(bb.in));
        String base = tripKeyOf(tk, open, pos, under, held);
        Trip kept = TRIPS.get(tk);
        Held h = waitTest ? hold(bb, tk, run, runner, kept) : Held.NONE;
        if (h == Held.KEEP) return kept.chain();
        // 0.39.z77 carry 2: the kept trip's own band Ys say whether the feet crossed one (its legs placed again)
        // 0.39.z77 seen: and its own references say which of the bag's counts it depends on
        // 0.39.z78 D6: a kept thinking stand is priced again as soon as the planner's result for it has arrived
        boolean answered = kept != null && kept.chain() != null && kept.chain().why().startsWith(STAND + NeedChain.NOT_PRICED + NeedChain.THINKING)
                && bb.fact(PLANNER_FACT) instanceof Planner pp && pp.ready(tk);
        Trip t = h != Held.AGAIN && !answered && kept != null && kept.holds(base + bagPart(kept.refs(), w.bag()) + bandPlace(kept.pb(), pos, under), landed(w)) ? kept : price(bb, g, tk, base, pos, under, open, w);
        // 0.39.z77 noway (run BU 13:34:23: the helmet's band leg had no way and ended the PICKAXE's order, never made): a leg's
        // no-way ends only the orders whose own items need it (with the leg's reasons, as an order's no-way always did); every
        // other order of the batch derives its trip again without them and runs on
        if (!t.ends().isEmpty() && t.chain() != null) {
            boolean gone = bb.port != null;
            for (Goals.Goal o : open) {
                if (o == g || !t.ends().contains(o.id())) continue;
                if (bb.port != null) Goals.park(bb, o, NO_WAY + t.chain().why());
                gone &= o.parked != null;
            }
            if (!t.ends().contains(g.id()) && gone) return trip(bb, g, w0, own, waitTest, ask);     // the ended orders parked: the key changes
        }
        // the own chain's world: the order's (Goals.chainWorld). FR fix (run FR: 2,214 "feet" drops, 237 results in a row dropped
        // on the own trips of the WAITING orders): the running order's own chain keeps the live world (its running step is priced
        // from the feet, D11); every other order's own chain is priced at the anchor like a trip (z75: later steps from where the
        // bot will be), so its facts stand still while the bot walks
        return t.chain() != null ? t.chain() : own(bb, g, t.key(), landed(w), own, ask, g == runner ? w0 : anchored);
    }

    /**
     * D11 (the anchor rule; pure): where the running step {@code step} leaves the bot - a walk's, a station's, a cave lip's or
     * a surface cell; a band leg's end: the feet's own column (the leg digs straight down it, so x and z ARE the end's) at the
     * band's middle Y (structural; the live Y is what moved in run EV); any other step (a craft, a smelt, a stand, a climb
     * whose top is not known, a strip) and no step: the feet themselves ({@code feet} returned as given). Assumption (D11
     * review 1): a CAVING step is anchored at the cave's entry cell - where it ends inside the cave is not known in advance.
     */
    static int[] anchorOf(Step step, int[] feet) {
        if (step == null || feet == null || feet.length < 3) return feet;     // structural: x y z
        if (step instanceof Step.Walk s) return new int[]{s.x(), s.y(), s.z()};
        if (step instanceof Step.ToStation s) return new int[]{s.x(), s.y(), s.z()};
        if (step instanceof Step.ToCave s) return new int[]{s.x(), s.y(), s.z()};
        if (step instanceof Step.ToSurface s) return new int[]{s.x(), s.y(), s.z()};
        if (step instanceof Step.Cave s) return new int[]{s.x(), s.y(), s.z()};
        if (step instanceof Step.ToStrip s) return new int[]{s.x(), s.y(), s.z()};
        if (step instanceof Step.ToBand s) return new int[]{feet[0], (s.minY() + s.maxY()) / 2, feet[2]};   // structural: the band's middle
        return feet;
    }

    /**
     * D11 (pure): whether the trip is under ground at the anchor - a surface walk or a climb ends on the surface, a band or strip
     * leg under ground; any other step keeps the live reading ({@code liveUnder}: {@link #under}).
     */
    static boolean anchorUnder(Step step, boolean liveUnder) {
        if (step instanceof Step.ToSurface || step instanceof Step.Climb) return false;
        if (step instanceof Step.ToBand || step instanceof Step.ToStrip || step instanceof Step.Strip) return true;
        return liveUnder;
    }

    /** 0.39.z77 trip: the batch {@code tk}'s order whose typed run runs now ({@code run}), null: none or another batch's. */
    static Goals.Goal runner(Blackboard bb, String tk, Run run) {
        if (run == null) return null;
        // 0.39.z77 trip 2 (run CV 22:03:00: the met chestplate carried the run on off the goal list; "playbook 2" each tick)
        if (run.goal() != null) return tk.equals(tripKey(run.goal())) ? run.goal() : null;
        if (run.order() == null || !(bb.fact(Goals.FACT) instanceof List<?>)) return null;
        for (Goals.Goal o : Goals.of(bb)) if (run.order().equals(o.id()) && tk.equals(tripKey(o))) return o;
        return null;
    }

    /** 0.39.z77 trip: the waiting test's verdict - no hold (by the key), the kept trip, or derived again once (a step fact changed). */
    enum Held { NONE, KEEP, AGAIN }

    /**
     * 0.39.z77 trip (manager's ruling 2026-10-26 ~21:35, fix 1): while the batch's running step needs no walk (a craft, a smelt
     * or cook here, a smelt's take, a placement - SeenAsk.holds; the stand waits on the answers by design and is not held) the
     * waiting test reads the kept trip: path answers landing for other legs and the bag or furnace changes the step makes do
     * not derive it. Only a fact that step itself depends on ({@link #stepFacts}: its station, its inputs) changing derives it
     * again - once (AGAIN); the hold then keeps the new facts. A new step starts the hold anew. Only a kept trip that stands
     * (settled, priced, not waiting) is held.
     */
    static Held hold(Blackboard bb, String tk, Run run, Goals.Goal runner, Trip kept) {
        Step s = run == null ? null : run.step();
        boolean noWalk = s != null && io.github.mojolowjo.entropybot.aa.SeenAsk.holds(s) && !(s instanceof Step.AwaitPaths);
        if (!noWalk || runner == null || kept == null || kept.chain() == null || !kept.settled() || !kept.chain().ok() || kept.chain().waiting()) {
            HOLDS.remove(tk);
            return Held.NONE;
        }
        String f = stepFacts(run, bb.in == null ? null : bb.in.bag, bb.in != null && bb.in.tableKnown, bb.in != null && bb.in.furnaceKnown);
        Hold was = HOLDS.put(tk, new Hold(s, f));
        return was == null || was.step() != s || was.facts().equals(f) ? Held.KEEP : Held.AGAIN;
    }

    /**
     * 0.39.z77 trip (pure): the facts the running step depends on - its station (a smelt, a cook or a smelt's take: a furnace
     * known; a craft: a table known) and each input its plan counts ({@code run.uses}, the fuel left out: it goes into the
     * furnace) being anywhere the bot knows: in the bag (an item, or a tag's member), in a furnace job still running
     * ({@code run.furnace}), or the step's own output in the bag. "furnace known | raw_iron there".
     */
    static String stepFacts(Run run, Map<String, Integer> bag, boolean tableKnown, boolean furnaceKnown) {
        Step s = run.step();
        List<String> out = new ArrayList<>();
        if (s instanceof Step.Smelt || s instanceof Step.Cook || s instanceof Step.SmeltCollect) out.add("furnace " + (furnaceKnown ? "known" : "gone"));
        if (s instanceof Step.Craft) out.add("table " + (tableKnown ? "known" : "none"));
        String made = s instanceof Step.Smelt m ? m.item() : s instanceof Step.Cook k ? k.item() : s instanceof Step.Craft c ? c.item() : null;
        boolean got = made != null && count(bag, made) > 0;
        if (run.uses() != null) for (var e : new TreeMap<>(run.uses()).entrySet()) {
            String w = e.getKey();
            if (w == null || w.startsWith(NeedChain.FUEL_USE) || e.getValue() == null || e.getValue() <= 0) continue;
            out.add(NeedChain.shw(w) + (got || count(bag, w) + count(run.furnace(), w) > 0 ? " there" : " gone"));
        }
        return String.join(" | ", out);
    }

    /** 0.39.z77 trip: what {@code counts} hold of the need word {@code w} (the item, or any member of a tag). */
    private static int count(Map<String, Integer> counts, String w) {
        if (counts == null || w == null) return 0;
        String word = NeedChain.word(w);
        int n = 0;
        for (var e : counts.entrySet())
            if (e.getValue() != null && e.getValue() > 0 && (word.equals(NeedChain.word(e.getKey())) || NeedChain.gives(word, e.getKey()))) n += e.getValue();
        return n;
    }

    /**
     * 0.39.z77 carry 2 (pure; review of carry 1: under ground Playbook.facts places the legs from the feet's Y - a descent at
     * the feet's own Y is the strip here, one above them the strip from below): where the feet stand against each band Y the
     * trip's descents use - above, at, below ("| band 16 at") - so digging down INTO a band Y re-prices the trip once, and
     * the Y in between does not. On the surface (legs placed as from the surface), no trip, or no feet: nothing.
     */
    static String bandPlace(Playbook pb, int[] pos, boolean under) {
        if (!under || pb == null || !pb.ok() || pos == null || pos.length < 3) return "";     // structural: x y z
        StringBuilder b = new StringBuilder();
        Set<Integer> seen = new LinkedHashSet<>();
        for (Playbook.Leg l : pb.legs()) {
            if (l.kind() != Playbook.Kind.DESCENT || !seen.add(l.y())) continue;
            b.append(" | band ").append(l.y()).append(pos[1] > l.y() ? " above" : pos[1] == l.y() ? " at" : " below");
        }
        return b.toString();
    }

    /** The trip of the batch {@code tk} priced now on {@code w} for its {@code open} orders, kept under {@code base} and its band place. */
    private static Trip price(Blackboard bb, Goals.Goal g, String tk, String base, int[] pos, boolean under, List<Goals.Goal> open, NeedChain.World w) {
        PRICED.merge(tk, 1, Integer::sum);
        long t0 = TickLaps.AA.now();
        List<Playbook.Target> targets = new ArrayList<>();
        for (Goals.Goal o : open) targets.add(new Playbook.Target(itemOf(o), o.n));
        @SuppressWarnings("unchecked")
        BiFunction<String, Integer, Double> perSlab = bb.fact(PER_SLAB_FACT) instanceof BiFunction<?, ?, ?> f ? (BiFunction<String, Integer, Double>) f : null;
        long d0 = TickLaps.AA.now();
        Playbook pb = Playbook.derive(targets, Playbook.facts(w, perSlab));
        if (d0 != 0) TickLaps.AA.split("derive", TickLaps.AA.now() - d0);     // 0.39.z78 D6 (run DP): the split
        // an order alone whose trip is one leg: that leg IS the order's own chain (the same planner on the same facts; its
        // words and aa debug tree as before)
        boolean oneLeg = open.size() == 1 && pb.ok() && pb.legs().size() == 1;
        // 0.39.z77 trip (fix 2; run CU 21:02-21:05: the descent's fuel swapped coal / oak_slab / coal_block between prices): a
        // re-priced trip burns the fuel it burned before while the bag still covers it
        // 0.39.z78 D6: off the render thread when the adapter installed the planner worker (its result, or the thinking stand)
        Planner port = bb.fact(PLANNER_FACT) instanceof Planner pp ? pp : null;
        Set<String> kept = FUELS.getOrDefault(tk, Set.of());
        NeedChain.Legs l = !pb.ok() || oneLeg ? null : port != null ? port.legs(tk, pb, kept, w) : NeedChain.keepingFuel(kept, () -> NeedChain.resolvePlaybookLegs(pb, w));
        boolean thinking = l != null && NeedChain.thinking(l.chain());
        if (l != null && l.chain().ok()) FUELS.put(tk, fuelsOf(l.whole() == null ? l.chain() : l.whole()));
        if (t0 != 0) TickLaps.AA.playbook(TickLaps.AA.now() - t0);
        Shaped s = oneLeg ? new Shaped(null, TRIP + tk + " (1 order): 1 leg - " + pb.legs().get(0).text() + ": the order's own chain", true)
                : shape(tk, open.size(), pb, l == null ? null : l.chain(), l == null ? Map.of() : l.starts(), l == null ? null : l.whole());
        Map<Step, String> starts = l == null || s.chain() != l.chain() ? Map.of() : l.starts();
        Set<String> ends = l == null || s.chain() != l.chain() ? Set.of() : ends(open, l.failed(pb));
        // 0.39.z77 seen: a priced trip is keyed by the bag's counts its legs reference; the order's own chain (or no trip, a
        // trip that does not stand) by the whole bag, as before
        Set<String> refs = l != null && s.chain() == l.chain() && l.chain().ok() ? refs(open, pb, l.whole() == null ? l.chain() : l.whole()) : null;
        String key = base + bagPart(refs, w.bag()) + bandPlace(pb, pos, under);
        Trip t = new Trip(key, landed(w), s.settled(), pb, s.chain(), starts, s.line(), ends, refs);
        TRIPS.put(tk, t);
        LEGS.put(tk, starts);
        if (sayAgain(tk, s.change()) && bb.port != null) {
            bb.port.log(g.r.key() + ": " + s.line());    // 0.39.z77: on a change only
            // 0.39.z77 pending: a trip standing on a leg not priced yet says so to the owner (once while that leg waits)
            // 0.39.z78 D6: the planner's thinking stand is logged, not whispered (it comes and goes with every re-price)
            if (s.chain() != null && s.chain().why().startsWith(STAND + NeedChain.NOT_PRICED) && !thinking) bb.port.tellOwner(s.line());
        }
        if (!ends.isEmpty() && bb.port != null) {
            List<String> names = new ArrayList<>();
            for (Goals.Goal o : open) if (ends.contains(o.id())) names.add(o.r.key());
            bb.port.log(TRIP + tk + ": leg " + (l.failedLeg() + 1) + "/" + pb.legs().size() + " (" + l.failed(pb).text() + ") has no way: it ends the order"
                    + (names.size() == 1 ? " " : "s ") + String.join(", ", names) + " (their items need it); the other orders' trip is derived again without "
                    + (names.size() == 1 ? "it" : "them"));
        }
        return t;
    }

    /** 0.39.z77 trip (pure): the fuels a priced chain burns - its smelts' planned fuel words (NeedChain.FUEL_USE in its uses). */
    static Set<String> fuelsOf(NeedChain.Chain c) {
        Set<String> out = new LinkedHashSet<>();
        if (c == null || c.uses() == null) return out;
        for (Map<String, Integer> u : c.uses()) {
            if (u == null) continue;
            for (var e : u.entrySet()) if (e.getKey() != null && e.getKey().startsWith(NeedChain.FUEL_USE) && e.getValue() != null && e.getValue() > 0)
                out.add(NeedChain.word(e.getKey().substring(NeedChain.FUEL_USE.length())));
        }
        return out;
    }

    /**
     * 0.39.z77 noway (pure): the ids of the {@code open} orders (in the trip's target order) that a leg with no way ends - the
     * orders it serves ({@link Playbook.Leg#serves}); empty when no leg failed, it serves none, or it serves every open order
     * (then the trip's no-way is every order's, as before).
     */
    static Set<String> ends(List<Goals.Goal> open, Playbook.Leg failed) {
        if (failed == null || open.size() < 2) return Set.of();
        Set<String> out = new LinkedHashSet<>();
        for (int i : failed.serves()) if (i >= 0 && i < open.size()) out.add(open.get(i).id());
        return out.isEmpty() || out.size() >= open.size() ? Set.of() : out;
    }

    /** The owner hears the trip the order took, once a line, before its steps run (Goals.took). */
    static void announce(Blackboard bb, Goals.Goal g) {
        String tk = tripKey(g);
        Trip t = TRIPS.get(tk);
        if (t == null || t.chain() == null || bb.port == null || !t.settled() || t.line().equals(TOLD.get(tk))) return;
        TOLD.put(tk, t.line());
        bb.port.tellOwner(t.line());
    }

    /**
     * A trip's chain as it runs ({@code chain}: null - the order's own chain, as said in {@code line}), kept or not.
     * 0.39.z77 walk commit (manager; run BT: 269 trip lines in 20 min, several a second at 12:57 - one per re-price):
     * {@code change} - the trip's legs and carry lists only (no price, no stand or no-way reason); the line is logged again
     * only when this differs from the last one logged for the batch ({@link #sayAgain}).
     */
    public record Shaped(NeedChain.Chain chain, String line, boolean settled, String change) {
        public Shaped(NeedChain.Chain chain, String line, boolean settled) { this(chain, line, settled, line); }
    }

    /** 0.39.z77: the trip {@code tk}'s line is logged when its {@code change} differs from the last logged (recorded). */
    public static boolean sayAgain(String tk, String change) {
        if (change == null || change.equals(SAID.get(tk))) return false;
        SAID.put(tk, change);
        return true;
    }

    /**
     * The trip's chain as the order runs it (pure): the playbook's chain; the whole trip waiting on a path from here - a
     * stand (rule 22); priced 0 s - refused; no derivation - the order's own chain (null chain), the reason said.
     */
    public static Shaped shape(String key, int orders, Playbook pb, NeedChain.Chain pc, Map<Step, String> starts) {
        return shape(key, orders, pb, pc, starts, pc);
    }

    /**
     * 0.39.z77 carry: {@link #shape} with the trip as priced ({@code whole}, {@link NeedChain.Legs#whole}: every leg's steps,
     * before the run's cut at a surface explore) - the line's legs and carry lists are read from it, the run is {@code pc}.
     */
    public static Shaped shape(String key, int orders, Playbook pb, NeedChain.Chain pc, Map<Step, String> starts, NeedChain.Chain whole) {
        if (whole == null || pc == null || !pc.ok()) whole = pc;
        String head = TRIP + key + " (" + orders + " order" + (orders == 1 ? "" : "s") + "): ";
        if (pb == null || !pb.ok() || pc == null)
            return new Shaped(null, head + "no trip derived (" + (pb == null ? "no playbook" : pb.fail()) + "): the order's own chain as its one leg", true);
        String bare = head + legs(pb, null, null);           // 0.39.z77: the legs with no carry known (no priced chain)
        if (!pc.ok() && NeedChain.waitsHere(pc.missing())) {
            // 0.39.z77 pending (run BV 13:59:36): a leg whose ways still wait on answers is not priced yet - the trip stands, its
            // line says the leg and what it waits on (said to the owner once while that leg waits, price()); nobody is parked
            if (pc.missing().startsWith(NeedChain.NOT_PRICED)) {
                // thinking-stand (run JBO-T1 22:53:39.69-.85, a line of three: the trip's "the planner waits on its answer
                // (thinking: ...)" stand ended twice in 160 ms, was counted as two path stands, the walks dropped, "no way
                // is left", two orders parked): the trip's thinking stand is the planner worker's stand as an order's own
                // is (ownStand, J6c run EU 1b) - the step machine never counts it
                NeedChain.Chain stand = new NeedChain.Chain(List.of(new Step.AwaitPaths(NeedChain.firstAnswerWaitS(), NeedChain.thinking(pc))), 0, STAND + pc.missing() + ")", true, null);
                int cut = pc.missing().indexOf(NeedChain.WAITS_ON);
                return new Shaped(stand, head + "stand: " + pc.missing(), false, bare + " | stand: " + (cut < 0 ? pc.missing() : pc.missing().substring(0, cut)));
            }
            if (pc.missing().contains(io.github.mojolowjo.entropybot.aa.PathPrice.NOT_KNOWN)) {
                NeedChain.Chain stand = new NeedChain.Chain(List.of(new Step.AwaitPaths(NeedChain.firstAnswerWaitS())), 0, STAND + pc.missing() + ")", true, null);
                return new Shaped(stand, head + "stand while the first leg's walks compute (" + pc.missing() + ")", false, bare);
            }
            return new Shaped(null, head + "waits (" + pc.missing() + "): the order's own chain says it", false, bare);
        }
        if (!pc.ok()) return new Shaped(pc, head + "no way (" + pc.missing() + ")", true, bare);
        if (!pc.steps().isEmpty() && !(pc.seconds() > 0)) {                      // shape review 3: an explore trip too
            String z = zero(pc.seconds());
            return new Shaped(new NeedChain.Chain(List.of(), 0, "trip refused: " + z, false, "trip refused: " + z), head + "refused (" + z + ")", true, bare);
        }
        boolean waits = Steps.waitsOnPaths(pc.steps());
        return new Shaped(pc, head + legs(pb, whole, starts) + "; " + price(pc), !waits, head + legs(pb, whole, starts));
    }

    /** 0.39.z77 round fix 5: why a trip's seconds are no price ("priced 0.0 s: a way priced 0 or less is never valid"). */
    static String zero(double s) {
        return "priced " + (Double.isNaN(s) ? "NaN" : String.format(Locale.ROOT, "%.1f", s)) + " s: a way priced 0 or less is never valid";
    }

    /**
     * The trip as the owner reads it (pure): "5 legs - 1 surface: torch 4, stone_pickaxe 1 | 2 descent to y 16: raw_iron 3,
     * coal 1, carries crafting_table 1, stick 2 | ... ; priced 687 s". A descent's carry: what the steps after its start use
     * that no step after it makes and no later leg gets (the take-along, from the plan's own per-step uses).
     */
    static String line(Playbook pb, NeedChain.Chain pc, Map<Step, String> starts) {
        return legs(pb, pc, starts) + "; " + price(pc);
    }

    private static String price(NeedChain.Chain pc) {
        return pc.explore() ? "priced with its explore " + Math.round(pc.seconds()) + " s" : "priced " + Math.round(pc.seconds()) + " s";
    }

    /** 0.39.z77 carry: a leg the trip prices when the bot gets there (after the run's last leg; NeedChain.PRICED_THERE). */
    static final String NOT_PRICED_HERE = " (not priced yet)";

    /**
     * 0.39.z77: the trip's legs and what each descent carries (pure) - {@link #line} without the price; {@code pc} null: the
     * legs only (no carry is known without a priced chain).
     */
    static String legs(Playbook pb, NeedChain.Chain pc, Map<Step, String> starts) {
        List<Playbook.Leg> legs = pb.legs();
        // the first step of each leg in the chain, by the planner's leg numbers ("playbook leg 2/5: ...")
        Map<Integer, Integer> at = new java.util.HashMap<>();
        for (int k = 0; pc != null && k < pc.steps().size(); k++) {
            String t = starts == null ? null : starts.get(pc.steps().get(k));
            if (t == null || !t.startsWith("playbook leg ")) continue;
            int slash = t.indexOf('/');
            if (slash > 13) at.put(Integer.parseInt(t.substring(13, slash)) - 1, k);    // structural: "playbook leg ".length()
        }
        // 0.39.z77 carry: the run ends before a leg priced when the bot gets there - that leg and the ones after it say so (a
        // descent among them carries nothing yet: its legs below are not priced; NeedChain cuts the run before it)
        int last = -1;
        for (int i : at.keySet()) last = Math.max(last, i);
        boolean cut = pc != null && pc.why() != null && pc.why().contains(NeedChain.PRICED_THERE);
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < legs.size(); i++) {
            Playbook.Leg l = legs.get(i);
            List<String> g = new ArrayList<>();
            for (Playbook.Target t : l.gets()) g.add(t.text());
            String head = switch (l.kind()) {
                case SURFACE -> l.climb() ? l.whyY() : "surface";
                case HERE -> "here at y " + l.y();
                case DESCENT -> (l.on() ? "strip on at y " : l.here() ? "strip here at y " : "descent to y ") + l.y();
                case AT_BAND -> "at the band";
            };
            String carry = l.kind() == Playbook.Kind.DESCENT && !l.on() && !l.here() && at.containsKey(i) ? carries(pb, i, at.get(i), pc) : "";
            parts.add((i + 1) + " " + head + ": " + String.join(", ", g) + (carry.isEmpty() ? "" : ", carries " + carry) + (cut && last >= 0 && i > last ? NOT_PRICED_HERE : ""));
        }
        return legs.size() + " leg" + (legs.size() == 1 ? "" : "s") + " - " + String.join(" | ", parts);
    }

    /** What the bot takes down at descent leg {@code i}: the uses after the descent's strip start, less what is made or got there. */
    static String carries(Playbook pb, int i, int start, NeedChain.Chain pc) {
        if (start < 0 || pc.uses().size() != pc.steps().size()) return "";
        while (start < pc.steps().size() && !(pc.steps().get(start) instanceof Step.ToStrip || pc.steps().get(start) instanceof Step.Strip)) start++;   // what is made up there before going down is not carried
        Set<String> made = new LinkedHashSet<>();
        for (int j = i; j < pb.legs().size(); j++) for (Playbook.Target t : pb.legs().get(j).gets()) made.add(NeedChain.shw(t.item()));
        for (int k = start; k < pc.steps().size(); k++) {
            Step s = pc.steps().get(k);
            if (s instanceof Step.Craft c) made.add(NeedChain.shw(c.item()));
            if (s instanceof Step.Smelt c) made.add(NeedChain.shw(c.item()));
        }
        Map<String, Integer> used = new LinkedHashMap<>();
        for (int k = start; k < pc.steps().size(); k++) {
            // a station placed down there from the bag (the table, the furnace) goes down with the bot
            if (pc.steps().get(k) instanceof Step.PlaceNear p && !made.contains(NeedChain.shw(p.item()))) used.merge(NeedChain.shw(p.item()), 1, Integer::sum);
            Map<String, Integer> u = pc.uses().get(k);
            if (u == null) continue;
            for (var e : u.entrySet()) {
                boolean fuel = e.getKey().startsWith("fuel ");                  // StepNeeds' fuel use ("fuel minecraft:coal")
                String w = NeedChain.shw(fuel ? e.getKey().substring("fuel ".length()) : e.getKey());
                if (!fuel && made.contains(w) || e.getValue() <= 0) continue;    // the fuel the plan burns is the bag's (StepNeeds' count)
                used.merge(w + (fuel ? " (fuel)" : ""), e.getValue(), Integer::sum);
            }
        }
        List<String> out = new ArrayList<>();
        for (var e : used.entrySet()) {
            String k = e.getKey();
            out.add(k.endsWith(" (fuel)") ? k.substring(0, k.length() - " (fuel)".length()) + " " + e.getValue() + " (fuel)" : k + " " + e.getValue());
        }
        return String.join(", ", out);
    }
}
