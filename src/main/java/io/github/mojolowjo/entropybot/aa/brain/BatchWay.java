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
 * <p>R3 (resolve time on the client tick): the trip is kept per order (each order's holds are its own), keyed by the open orders, the bag, the place
 * (failures.moved cells) and the order's holds; one that waits on paths is kept until the next path answer lands. Measured
 * on the AA tick's stopwatch ({@link TickLaps#playbook}: "probe aatick: ... | playbook N = M ms").
 * <p>0.39.z77 noway: a leg with no way ends only the orders whose own items need it ({@link Playbook.Leg#serves}), parked
 * with the leg's reasons; the batch's other orders derive their trip again without them and run on (said in the log).
 * Pure (JUnit: Z77PlaybookTest, Z77RoundFixTest, Z77NoWayTest). Loader notes: none (the world and the ore-per-slab fact come from AaRuntime).
 */
public final class BatchWay {
    private BatchWay() {}

    /** Blackboard fact: the adapter's ore per slab at a Y ({@code BiFunction<String, Integer, Double>}: need word, y; NaN: not known). */
    public static final String PER_SLAB_FACT = "aa.perSlabAt";

    /** The start of a trip line ("trip b1 (6 orders): ..."). */
    public static final String TRIP = "trip ";

    /** The start of the reason an order ends with when no way stands for it ("no way to get it: playbook: ..."). */
    public static final String NO_WAY = "no way to get it: ";

    /**
     * One trip as priced: its key, the path answers landed then ({@code landed}), whether it waited on no path
     * ({@code settled}), the playbook, its chain, the leg starts (by identity) and the line said. 0.39.z77 noway: {@code ends} -
     * the ids of the open orders a leg with no way ends (those whose own items need that leg) when the others do not need it;
     * empty: the trip's verdict is every order's.
     */
    record Trip(String key, long landed, boolean settled, Playbook pb, NeedChain.Chain chain, Map<Step, String> starts, String line, Set<String> ends) {
        boolean holds(String k, long landedNow) { return key != null && key.equals(k) && (settled || landed == landedNow); }
    }

    private static long landed(NeedChain.World w) { return w.path() == null ? 0 : w.path().landed(); }

    /** Per BATCH (trip key): the trip last priced, and its leg starts (shape review 2). */
    private static final Map<String, Trip> TRIPS = new ConcurrentHashMap<>();
    private static final Map<String, Map<Step, String>> LEGS = new ConcurrentHashMap<>();
    private static final Map<String, String> SAID = new ConcurrentHashMap<>(), TOLD = new ConcurrentHashMap<>();

    /** Tests and a new runtime: forget every trip. */
    public static void clear() { TRIPS.clear(); LEGS.clear(); OWN.clear(); PRICED.clear(); SAID.clear(); TOLD.clear(); }

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

    /** The trip's key (pure): the batch, its open orders, the bag, the place in failures.moved cells, the batch's holds. */
    static String tripKeyOf(String tk, List<Goals.Goal> open, Map<String, Integer> bag, int[] pos, java.util.Collection<String> held) {
        StringBuilder b = new StringBuilder(tk).append(" |");
        for (Goals.Goal o : open) b.append(' ').append(o.r.key()).append(':').append(o.n);
        b.append(" | ").append(new TreeMap<>(bag));
        int cell = Math.max(1, (int) Math.round(Failures.movedV()));
        if (pos != null && pos.length >= 3) b.append(" | at ").append(Math.floorDiv(pos[0], cell)).append(',').append(Math.floorDiv(pos[1], cell)).append(',').append(Math.floorDiv(pos[2], cell));   // structural: x y z
        b.append(" | held ").append(new java.util.TreeSet<>(held));
        return b.toString();
    }

    /** 0.39.z77 shape review 2: an order's own chain where no trip is derived, kept by the trip's key and the landings (per order). */
    private record Own(String key, long landed, boolean settled, NeedChain.Chain chain) {}

    private static final Map<String, Own> OWN = new ConcurrentHashMap<>();

    private static NeedChain.Chain own(Goals.Goal g, String key, long landed, java.util.function.Supplier<NeedChain.Chain> own) {
        Own o = OWN.get(g.id());
        if (o != null && o.key().equals(key) && (o.settled() || o.landed() == landed)) return o.chain();
        NeedChain.Chain c = own.get();
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
        List<Goals.Goal> open = open(bb, g);
        if (open.isEmpty()) open = List.of(g);
        String tk = tripKey(g);
        Map<String, String> mine = Goals.droppedSources(g), others = new TreeMap<>();
        for (Goals.Goal o : open) for (var e : Goals.droppedSources(o).entrySet()) if (!mine.containsKey(e.getKey())) others.put(e.getKey(), e.getValue());
        NeedChain.World w = others.isEmpty() ? w0 : w0.withoutSources(others);
        java.util.Set<String> held = new java.util.TreeSet<>(mine.keySet());
        held.addAll(others.keySet());
        String key = tripKeyOf(tk, open, w.bag(), bb.in == null ? null : bb.in.pos, held);
        Trip kept = TRIPS.get(tk);
        Trip t = kept != null && kept.holds(key, landed(w)) ? kept : price(bb, g, tk, key, open, w);
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
            if (!t.ends().contains(g.id()) && gone) return trip(bb, g, w0, own);     // the ended orders parked: the key changes
        }
        return t.chain() != null ? t.chain() : own(g, key, landed(w), own);
    }

    /** The trip of the batch {@code tk} priced now on {@code w} for its {@code open} orders, kept under {@code key}. */
    private static Trip price(Blackboard bb, Goals.Goal g, String tk, String key, List<Goals.Goal> open, NeedChain.World w) {
        PRICED.merge(tk, 1, Integer::sum);
        long t0 = TickLaps.AA.now();
        List<Playbook.Target> targets = new ArrayList<>();
        for (Goals.Goal o : open) targets.add(new Playbook.Target(itemOf(o), o.n));
        @SuppressWarnings("unchecked")
        BiFunction<String, Integer, Double> perSlab = bb.fact(PER_SLAB_FACT) instanceof BiFunction<?, ?, ?> f ? (BiFunction<String, Integer, Double>) f : null;
        Playbook pb = Playbook.derive(targets, Playbook.facts(w, perSlab));
        // an order alone whose trip is one leg: that leg IS the order's own chain (the same planner on the same facts; its
        // words and aa debug tree as before)
        boolean oneLeg = open.size() == 1 && pb.ok() && pb.legs().size() == 1;
        NeedChain.Legs l = pb.ok() && !oneLeg ? NeedChain.resolvePlaybookLegs(pb, w) : null;
        if (t0 != 0) TickLaps.AA.playbook(TickLaps.AA.now() - t0);
        Shaped s = oneLeg ? new Shaped(null, TRIP + tk + " (1 order): 1 leg - " + pb.legs().get(0).text() + ": the order's own chain", true)
                : shape(tk, open.size(), pb, l == null ? null : l.chain(), l == null ? Map.of() : l.starts());
        Map<Step, String> starts = l == null || s.chain() != l.chain() ? Map.of() : l.starts();
        Set<String> ends = l == null || s.chain() != l.chain() ? Set.of() : ends(open, l.failed(pb));
        Trip t = new Trip(key, landed(w), s.settled(), pb, s.chain(), starts, s.line(), ends);
        TRIPS.put(tk, t);
        LEGS.put(tk, starts);
        if (sayAgain(tk, s.change()) && bb.port != null) bb.port.log(g.r.key() + ": " + s.line());    // 0.39.z77: on a change only
        if (!ends.isEmpty() && bb.port != null) {
            List<String> names = new ArrayList<>();
            for (Goals.Goal o : open) if (ends.contains(o.id())) names.add(o.r.key());
            bb.port.log(TRIP + tk + ": leg " + (l.failedLeg() + 1) + "/" + pb.legs().size() + " (" + l.failed(pb).text() + ") has no way: it ends the order"
                    + (names.size() == 1 ? " " : "s ") + String.join(", ", names) + " (their items need it); the other orders' trip is derived again without "
                    + (names.size() == 1 ? "it" : "them"));
        }
        return t;
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
        String head = TRIP + key + " (" + orders + " order" + (orders == 1 ? "" : "s") + "): ";
        if (pb == null || !pb.ok() || pc == null)
            return new Shaped(null, head + "no trip derived (" + (pb == null ? "no playbook" : pb.fail()) + "): the order's own chain as its one leg", true);
        String bare = head + legs(pb, null, null);           // 0.39.z77: the legs with no carry known (no priced chain)
        if (!pc.ok() && NeedChain.waitsHere(pc.missing())) {
            if (pc.missing().contains(io.github.mojolowjo.entropybot.aa.PathPrice.NOT_KNOWN)) {
                NeedChain.Chain stand = new NeedChain.Chain(List.of(new Step.AwaitPaths(NeedChain.firstAnswerWaitS())), 0, "trip: stand (" + pc.missing() + ")", true, null);
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
        return new Shaped(pc, head + line(pb, pc, starts), !waits, head + legs(pb, pc, starts));
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
        String price = pc.explore() ? "priced with its explore " + Math.round(pc.seconds()) + " s" : "priced " + Math.round(pc.seconds()) + " s";
        return legs(pb, pc, starts) + "; " + price;
    }

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
            parts.add((i + 1) + " " + head + ": " + String.join(", ", g) + (carry.isEmpty() ? "" : ", carries " + carry));
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
