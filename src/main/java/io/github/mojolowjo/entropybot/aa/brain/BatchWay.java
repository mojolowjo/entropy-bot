package io.github.mojolowjo.entropybot.aa.brain;

import io.github.mojolowjo.entropybot.aa.NeedChain;
import io.github.mojolowjo.entropybot.aa.Playbook;
import io.github.mojolowjo.entropybot.aa.core.Blackboard;
import io.github.mojolowjo.entropybot.aa.core.TickLaps;
import io.github.mojolowjo.entropybot.aa.moves.Steps;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;

/**
 * 0.39.z77 J2 (design D5 J2; rule 1 clarified: a playbook is one priced option, never forced): the batch's two ways for the
 * order about to run - the PLAYBOOK (the batch's open orders as one derived route, {@link Playbook}, priced by
 * {@link NeedChain#resolvePlaybook}) and the AD-HOC row (each open order's own chain in turn, this order first, each on the bag
 * the last one left, positions as now: the manager's R1 - it charges every later order its own descent, a bias to the
 * playbook; both numbers are said). Price against price (manager on run BE: no category rule): the playbook is taken when
 * it is priced (an explore leg counts as its price) and cheaper, or the ad-hoc row has no way; a tie (equal seconds) keeps the ad-hoc chain. The running order then runs
 * the playbook's steps; the later orders find their items in the bag (Goals.have). One line per new comparison:
 * "chain | batch b1: playbook 936.4 s vs ad-hoc 1093.0 s: playbook (...)".
 * <p>R3 (resolve time on the client tick): the verdict is kept per batch, keyed by the open orders, the bag, the place
 * (failures.moved cells) and the order's holds; a settled verdict re-prices only the chain it picked (one resolve, as an
 * order alone). A comparison whose rows wait on paths is kept until the next path answer lands (then asked again). Measured on the AA
 * tick's stopwatch ({@link TickLaps#playbook}: "probe aatick: ... | playbook N = M ms").
 * Pure (JUnit: Z77PlaybookTest). Loader notes: none (the world and the ore-per-slab fact come from AaRuntime).
 */
public final class BatchWay {
    private BatchWay() {}

    /** 0.39.z77 J2: the key a failed playbook step holds for its order's life ("playbook b1"). */
    public static final String KEY = "playbook ";

    public static String key(String batch) { return KEY + batch; }

    /** Blackboard fact: the adapter's ore per slab at a Y ({@code BiFunction<String, Integer, Double>}: need word, y; NaN: not known). */
    public static final String PER_SLAB_FACT = "aa.perSlabAt";

    /** Tuning playbook.on (the J2 round's A/B switch). */
    public static boolean on() { return Tuning.get("playbook.on") > 0; }

    /**
     * One comparison: its key, the path answers landed when it was made ({@code landed}), whether neither row waited on a path
     * ({@code settled}), the pick, the playbook, the line said. 0.39.z77 review 1 (R3): an unsettled verdict is kept too, for
     * the same key until the next path answer lands (PathPrice#landed) - never a full comparison per tick while paths compute.
     */
    record Verdict(String key, long landed, boolean settled, boolean playbook, Playbook pb, String line) {
        boolean holds(String k, long landedNow) { return key != null && key.equals(k) && (settled || landed == landedNow); }
    }

    private static long landed(NeedChain.World w) { return w.path() == null ? 0 : w.path().landed(); }

    private static final Map<String, Verdict> VERDICTS = new ConcurrentHashMap<>();
    private static final Map<String, String> SAID = new ConcurrentHashMap<>(), PICKED = new ConcurrentHashMap<>();
    /** The leg starts of the batch's last playbook chain priced (its own step objects -> "playbook leg 2/3: ..."). */
    private static final Map<String, Map<io.github.mojolowjo.entropybot.aa.moves.Step, String>> LEGS = new ConcurrentHashMap<>();

    /** Tests: forget every batch's verdict. */
    public static void clear() { VERDICTS.clear(); SAID.clear(); PICKED.clear(); LEGS.clear(); }

    /** The leg starts of the batch's last playbook chain (by identity of its steps); empty: none. */
    public static Map<io.github.mojolowjo.entropybot.aa.moves.Step, String> legs(String batch) {
        Map<io.github.mojolowjo.entropybot.aa.moves.Step, String> m = batch == null ? null : LEGS.get(batch);
        return m == null ? Map.of() : m;
    }

    /** The chat words of a pick: "batch b1: the playbook runs - 850 s vs 967 s ad-hoc (3 legs: surface, descent to y 16, at the band)". Pure. */
    public static String chatLine(String batch, Result r) {
        String nums = (Double.isNaN(r.playbookS()) ? "playbook not priced" : String.format(Locale.ROOT, "playbook %.0f s", r.playbookS()))
                + " vs " + (Double.isNaN(r.adhocS()) ? "ad-hoc not priced" : String.format(Locale.ROOT, "ad-hoc %.0f s", r.adhocS()));
        if (!r.playbook()) return "batch " + batch + ": the ad-hoc chains run (" + nums + ")";
        List<String> legs = new ArrayList<>();
        for (Playbook.Leg l : r.pb().legs()) legs.add(l.kind() == Playbook.Kind.DESCENT ? (l.on() ? "strip on at y " : "descent to y ") + l.y() : l.kind() == Playbook.Kind.SURFACE ? "surface" : "at the band");
        return "batch " + batch + ": the playbook runs (" + nums + "; " + legs.size() + " legs: " + String.join(", ", legs) + ")";
    }

    /** The open orders of {@code g}'s batch (not parked, not met, plain items), in the batch's order; empty: no batch. */
    static List<Goals.Goal> open(Blackboard bb, Goals.Goal g) {
        List<Goals.Goal> out = new ArrayList<>();
        if (g.batch == null || !(bb.fact(Goals.FACT) instanceof List<?>)) return out;
        for (Goals.Goal o : Goals.of(bb)) {
            if (!g.batch.equals(o.batch) || o.parked != null || o.strip != null || o.r.group()) continue;
            if (bb.in != null && Goals.have(bb.in.bag, o) >= o.n) continue;
            out.add(o);
        }
        return out;
    }

    static String itemOf(Goals.Goal o) { return o.r.ids().isEmpty() ? o.r.key() : o.r.ids().get(0); }

    /** Not for this order: off, no batch, held, or fewer than two open orders. Null: it is. */
    static String outWhy(Blackboard bb, Goals.Goal g, List<Goals.Goal> open) {
        if (!on()) return "playbook.on 0";
        if (g.batch == null || g.strip != null || g.r.group()) return "no batch";
        if (Goals.droppedSources(g).containsKey(key(g.batch))) return "held for this order";
        if (open.size() < 2) return "one open order";
        return null;
    }

    private static String verdictKey(Blackboard bb, Goals.Goal g, List<Goals.Goal> open, NeedChain.World w) {
        StringBuilder b = new StringBuilder(g.batch).append(" | ").append(g.r.key());
        for (Goals.Goal o : open) b.append(' ').append(o.r.key()).append(':').append(o.n);
        b.append(" | ").append(new TreeMap<>(w.bag()));
        int[] pos = bb.in == null ? null : bb.in.pos;
        int cell = Math.max(1, (int) Math.round(Failures.movedV()));
        if (pos != null && pos.length >= 3) b.append(" | at ").append(Math.floorDiv(pos[0], cell)).append(',').append(Math.floorDiv(pos[1], cell)).append(',').append(Math.floorDiv(pos[2], cell));   // structural: x y z
        b.append(" | held ").append(new TreeMap<>(Goals.droppedSources(g)).keySet());
        return b.toString();
    }

    /** A settled playbook verdict for this order now: the playbook's chain (one resolve); null: none (the caller prices the ad-hoc chain). */
    static NeedChain.Chain cached(Blackboard bb, Goals.Goal g, NeedChain.World w) {
        if (g.batch == null) return null;
        Verdict v = VERDICTS.get(g.batch);
        if (v == null || !v.playbook()) return null;
        List<Goals.Goal> open = open(bb, g);
        if (outWhy(bb, g, open) != null || !v.holds(verdictKey(bb, g, open, w), landed(w))) return null;
        long t0 = TickLaps.AA.now();
        NeedChain.Legs l = NeedChain.resolvePlaybookLegs(v.pb(), w);
        if (t0 != 0) TickLaps.AA.playbook(TickLaps.AA.now() - t0);
        NeedChain.Chain pc = l.chain();
        if (!(pc.ok() && !Steps.waitsOnPaths(pc.steps()))) return null;
        LEGS.put(g.batch, l.starts());
        return pc;
    }

    /** The chain the order runs: {@code adhoc} (its own chain, priced already) or the batch's playbook when that is cheaper. */
    static NeedChain.Chain choose(Blackboard bb, Goals.Goal g, NeedChain.World w, NeedChain.Chain adhoc) {
        List<Goals.Goal> open = open(bb, g);
        if (outWhy(bb, g, open) != null) return adhoc;
        String key = verdictKey(bb, g, open, w);
        Verdict v = VERDICTS.get(g.batch);
        if (v != null && v.holds(key, landed(w)) && !v.playbook()) return adhoc;
        long t0 = TickLaps.AA.now();
        Result r = compare(bb, g, open, w);
        if (t0 != 0) TickLaps.AA.playbook(TickLaps.AA.now() - t0);
        VERDICTS.put(g.batch, new Verdict(key, landed(w), r.settled(), r.playbook(), r.pb(), r.line()));
        if (!r.line().equals(SAID.get(g.batch))) {
            SAID.put(g.batch, r.line());
            if (bb.port != null) bb.port.log(r.line());
        }
        // 0.39.z77 J2 (owner via the manager: the watcher sees the choice): the pick in chat when it is new or changed
        String pickWord = r.playbook() ? "playbook" : "ad-hoc";
        if (!pickWord.equals(PICKED.get(g.batch)) && bb.port != null) {
            PICKED.put(g.batch, pickWord);
            bb.port.tellOwner(chatLine(g.batch, r));
        }
        return r.playbook() ? r.chain() : adhoc;
    }

    /** One comparison's outcome: the pick, the playbook and its chain, both rows' numbers in {@code line}, whether it may be kept. */
    public record Result(boolean playbook, Playbook pb, NeedChain.Chain chain, double playbookS, double adhocS, String line, boolean settled) {}

    static Result compare(Blackboard bb, Goals.Goal g, List<Goals.Goal> open, NeedChain.World w) {
        List<Goals.Goal> inOrder = new ArrayList<>(open);                 // the ad-hoc row: this order first, then the batch's order
        inOrder.remove(g);
        inOrder.add(0, g);
        List<Playbook.Target> targets = new ArrayList<>();
        for (Goals.Goal o : open) targets.add(new Playbook.Target(itemOf(o), o.n));
        @SuppressWarnings("unchecked")
        BiFunction<String, Integer, Double> perSlab = bb.fact(PER_SLAB_FACT) instanceof BiFunction<?, ?, ?> f ? (BiFunction<String, Integer, Double>) f : null;
        Playbook pb = Playbook.derive(targets, Playbook.facts(w, perSlab));
        NeedChain.Legs l = pb.ok() ? NeedChain.resolvePlaybookLegs(pb, w) : null;
        NeedChain.Chain pc = l == null ? null : l.chain();
        if (l != null) LEGS.put(g.batch, l.starts());
        Row ad = adhoc(bb, inOrder, w);
        return pick(g.batch, pb, pc, ad);
    }

    /** The ad-hoc row: the sum of the orders' chains in turn ({@code fail}: the first that has no way; {@code explore}: one ends in an explore). */
    public record Row(double seconds, boolean explore, String fail, boolean waiting, int orders) {}

    static Row adhoc(Blackboard bb, List<Goals.Goal> inOrder, NeedChain.World w) {
        List<Playbook.Target> ts = new ArrayList<>();
        for (Goals.Goal o : inOrder) ts.add(new Playbook.Target(itemOf(o), o.n));
        return sequential(ts, i -> {
            NeedChain.World wo = i == 0 ? w : Goals.chainWorld(bb, inOrder.get(i));
            return wo == null ? w : wo;
        }, w.bag());
    }

    /**
     * The ad-hoc row (pure): each order's own chain in turn ({@code worldOf}: the order's world by its index; the bag of
     * each is the one the last left, from {@code bag}), the seconds summed (an explore chain's explore leg in it).
     */
    public static Row sequential(List<Playbook.Target> inOrder, java.util.function.IntFunction<NeedChain.World> worldOf, Map<String, Integer> bag) {
        double sum = 0;
        boolean explore = false, waiting = false;
        for (int i = 0; i < inOrder.size(); i++) {
            Playbook.Target o = inOrder.get(i);
            var p = NeedChain.resolveWithBag(o.item(), o.n(), worldOf.apply(i).withBag(bag));
            if (!p.chain().ok()) return new Row(sum, explore, p.chain().why(), waiting || p.chain().waiting(), i);
            sum += p.chain().seconds();
            explore |= p.chain().explore();
            waiting |= Steps.waitsOnPaths(p.chain().steps());
            bag = p.bagAfter();
        }
        return new Row(sum, explore, null, waiting, inOrder.size());
    }

    /**
     * The pick (pure), price against price (manager's ruling on run BE: no category rule outside the price, rule 1): the
     * playbook when it is priced (ok, no stand on paths; an explore leg counts its price like any leg) and the ad-hoc row has
     * no way or costs more; a tie (equal seconds) keeps the ad-hoc chain. A playbook with a leg not priced yet (a walk's
     * path not known, rule 15) is "not priced yet": the ad-hoc chain runs meanwhile, the line says so. Settled (kept): neither
     * row waits on paths.
     */
    public static Result pick(String batch, Playbook pb, NeedChain.Chain pc, Row ad) {
        boolean pbWaits = pc != null && (pc.waiting() || Steps.waitsOnPaths(pc.steps()) || pc.missing() != null && (pc.missing().contains(io.github.mojolowjo.entropybot.aa.PathPrice.NOT_KNOWN) || pc.missing().startsWith(NeedChain.NOT_PRICED)));
        // 0.39.z77 round fix 5 (run BG 09:23:33: "the ad-hoc chains run (playbook 0 s vs ad-hoc 1463 s)"; manager: a way priced 0
        // is never valid, as a count 0 is not): a row is a price only when its seconds are over 0 - a playbook or an ad-hoc row of
        // 0 s (or not a number) is refused with that reason, never compared
        String pbZero = pc != null && pc.ok() && !Steps.waitsOnPaths(pc.steps()) && !(pc.seconds() > 0) ? zero(pc.seconds()) : null;
        boolean pbPriced = pc != null && pc.ok() && !Steps.waitsOnPaths(pc.steps()) && pbZero == null;
        String adZero = ad.fail() == null && !(ad.seconds() > 0) ? zero(ad.seconds()) : null;
        boolean adStands = ad.fail() == null && adZero == null;
        boolean take = pbPriced && (!adStands || pc.seconds() < ad.seconds());
        String pbText = pc == null ? "not derived (" + pb.fail() + ")" : pbWaits ? (pc.missing() != null && pc.missing().startsWith(NeedChain.NOT_PRICED) ? pc.missing() : "not priced yet (" + (pc.missing() != null ? pc.missing() : "a walk's path computing") + ")")
                : !pc.ok() ? "has no way (" + pc.missing() + ")"
                : pbZero != null ? "refused (" + pbZero + ")"
                : String.format(Locale.ROOT, "%.1f s", pc.seconds()) + (pc.explore() ? " (an explore leg in it)" : "");
        String adText = adZero != null ? "refused (" + adZero + ")"
                : !adStands ? (ad.waiting() ? "not priced yet (" + ad.fail() + ")" : "has no way (" + ad.fail() + ")")
                : String.format(Locale.ROOT, "%.1f s", ad.seconds()) + (ad.explore() ? " (an explore leg in it)" : "");
        String line = "chain | batch " + batch + ": playbook " + pbText + " vs ad-hoc " + adText + " (" + ad.orders() + " orders in turn): " + (take ? "playbook" : "ad-hoc")
                + (pbWaits ? " meanwhile" : "") + (pc != null && pc.ok() ? " [" + pb.why() + "]" : "");
        // the chat's numbers are prices only: a row not priced (waiting, no way, refused) says so, never its partial seconds
        return new Result(take, pb, pc, pbPriced ? pc.seconds() : Double.NaN, adStands ? ad.seconds() : Double.NaN, line, !pbWaits && !ad.waiting());
    }

    /** 0.39.z77 round fix 5: why a row's seconds are no price ("priced 0.0 s: no way is free"). */
    static String zero(double s) {
        return "priced " + (Double.isNaN(s) ? "NaN" : String.format(Locale.ROOT, "%.1f", s)) + " s: a way priced 0 or less is never valid";
    }
}
