package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.brain.GenWhere;
import io.github.mojolowjo.entropybot.aa.brain.ItemWorth;
import io.github.mojolowjo.entropybot.aa.brain.MiningTiers;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * 0.39.z77 J2 (design D5 J2; RULES rule 1 clarified 2026-10-25: "a playbook is an option, not a script"): the player's route
 * for a BATCH of orders, derived from the game's data - never a written list (rule 4). One way beside the ad-hoc chain
 * (each order its own chain), priced as one by {@link NeedChain#resolvePlaybook}, picked by Goals only when cheaper (a tie
 * keeps the ad-hoc chain; never forced).
 * <ul>
 * <li>Each order's recipe tree is expanded (crafting recipes, then furnace recipes; a tag stays a tag: any member that
 *     stands, an all-surface one first) down to its leaves - only to CLASSIFY and to SUM (which recipe runs stays the
 *     planner's choice). A leaf is a SURFACE leaf (a known exposed source covering the count, or world-gen places it on the
 *     ground's top: {@link GenWhere}) or a BAND leaf (world-gen places it in a height band; never the own block of a
 *     tool-gated drop - nothing gives that but silk touch).</li>
 * <li>A furnace output fed from a band leaf (an ingot from its raw ore) is an at-band intermediate: summed over the batch
 *     (one smelt for every order), its band leaf summed with it (less what the bag holds of the output), and its fuel: the
 *     longest-burning fuel that is itself a band leaf, {@code smelts x craftJob.smeltTicks / burn ticks} rounded up.</li>
 * <li>The tool gate: each band leaf's blocks ask a pickaxe tier (the mining-tier data); the best pickaxe all-surface leaves
 *     make is the first gate; a leaf above it needs a pickaxe made at the band first (a second descent: a modded tier
 *     chain gives two gates from the same code).</li>
 * <li>Legs: SURFACE (the first gate's pickaxe and every order whose leaves are all surface) &gt; per tier stage: DESCENT (one
 *     Y for all its band leaves: the strip Y with the most {@code need x ore per slab}) &gt; AT_BAND (the smelts, then the
 *     orders and gates that unlock more first - a tool tier - then the rest in the batch's order).</li>
 * </ul>
 * A fact not loaded (the mining tiers, the world-gen read) is "not loaded", said (rule 15); any order that cannot be
 * classified fails the playbook with its reason (rule 7) and the ad-hoc chain runs. Pure (JUnit: Z77PlaybookTest).
 * Loader notes: none (the facts come through AaRuntime: GenWhere from the jars' world-gen JSON, the strip facts, the
 * recipe index synced by the server).
 */
public record Playbook(List<Target> targets, List<Leg> legs, String why, String fail) {
    public Playbook {
        targets = targets == null ? List.of() : List.copyOf(targets);
        legs = legs == null ? List.of() : List.copyOf(legs);
    }

    public boolean ok() { return fail == null; }

    /** One order of the batch ({@code n}: the count the bag must hold, as the order counts it), or a leg's get. */
    public record Target(String item, int n) {
        public String text() { return NeedChain.shw(item) + " " + n; }
    }

    public enum Kind { SURFACE, DESCENT, AT_BAND }

    /** A leg with no Y (not a descent). */
    public static final int NO_Y = Integer.MIN_VALUE;     // structural: no Y

    /** The start of a fail reason for a fact not read yet (rule 15). */
    public static final String NOT_LOADED = "not loaded: ";

    /**
     * One leg. {@code y}: the band Y of a DESCENT ({@link #NO_Y} else), {@code whyY} its reason; {@code perSlab}: a DESCENT's
     * leaf word -> ore a chunk slab at {@code y} (the strip's price); {@code tier}: the pickaxe tier the leg's strip digs
     * with (DESCENT) or its gate makes (SURFACE / AT_BAND; 0 none); {@code gates}: the gets that are a gate's pickaxe (the
     * planner makes the cheapest pickaxe of {@code tier} for them, not that very item).
     */
    public record Leg(Kind kind, List<Target> gets, int y, String whyY, Map<String, Double> perSlab, int tier, Set<String> gates, boolean on) {
        public Leg {
            gets = List.copyOf(gets);
            perSlab = perSlab == null ? Map.of() : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(perSlab));
            gates = gates == null ? Set.of() : Set.copyOf(gates);
        }

        public String text() {
            List<String> g = new ArrayList<>();
            for (Target t : gets) g.add(t.text());
            String head = switch (kind) {
                case SURFACE -> "surface";
                case DESCENT -> (on ? "strip on y " : "descent y ") + y;
                case AT_BAND -> "at band";
            };
            return head + " [" + String.join(", ", g) + "]";
        }
    }

    /**
     * The facts the derivation reads. {@code where}: need word -> where its blocks generate (null: not given = not loaded);
     * {@code surfaceKnown}: (word, count) -> a known exposed source covers it, or the dimension's base block gives it (dug
     * a few blocks down from anywhere); {@code dropOnly}: nothing gives the word but a tool-gated drop of its own block (no
     * leaf); {@code oresOf}: band leaf word -> the blocks its strip takes (null / empty: no strip way here);
     * {@code stripY}: band leaf word -> its strip Y (null: none); {@code perSlab}: (word, y) -> ore a chunk slab at y;
     * {@code bag}: item id -> count; {@code bagTier}: the bag's best pickaxe tier.
     */
    public record Facts(Function<String, GenWhere.Fact> where, BiPredicate<String, Integer> surfaceKnown, Predicate<String> dropOnly,
                        Function<String, List<String>> oresOf, Function<String, Integer> stripY,
                        BiFunction<String, Integer, Double> perSlab, Map<String, Integer> bag, int bagTier) {
        public Facts {
            bag = bag == null ? Map.of() : Map.copyOf(bag);
        }

        int have(String id) { return bag.getOrDefault(NeedChain.id(id), 0); }
    }

    /**
     * The facts from the planner's world ({@code w}: the order's world of now) and the ore per slab at any Y ({@code perSlab}:
     * the adapter's column features, OreBand; null: the strip facts' own Y only, NaN elsewhere - not known). A known source
     * covers a count when its counted blocks reach it (a spot counted 0: as many as needed); the dimension's base block
     * gives what its loot drops (dug from anywhere: the planner's base-block way); a drop-only word is
     * {@link Nearby#noSource}; the strip facts give the blocks, the Y and the slab.
     */
    public static Facts facts(NeedChain.World w, BiFunction<String, Integer, Double> perSlab) {
        BiPredicate<String, Integer> known = (word, n) -> {
            int sum = 0;
            for (NeedChain.Spot s : w.seenAll().apply(word)) {
                if (s.unloaded()) continue;
                if (s.count() <= 0) return true;
                sum += s.count();
            }
            if (sum >= n) return true;
            GenWhere.Base base = w.where().base().get();
            if (base == null) return false;
            if (w.loot() == null) return NeedChain.gives(word, base.block());
            for (var d : w.loot().blockDrops(base.block())) if (d.per() > 0 && NeedChain.gives(word, d.item())) return true;
            return false;
        };
        Function<String, List<String>> ores = word -> { var sb = w.strip().apply(word); return sb == null ? null : sb.ores(); };
        Function<String, Integer> y = word -> { var sb = w.strip().apply(word); return sb == null ? null : sb.y(); };
        BiFunction<String, Integer, Double> ps = perSlab != null ? perSlab
                : (word, yy) -> { var sb = w.strip().apply(word); return sb != null && sb.y() == yy ? sb.perSlab() : Double.NaN; };
        int tier = 0;
        for (var e : w.bag().entrySet()) if (e.getValue() > 0) tier = Math.max(tier, MiningTiers.toolTier(e.getKey(), "pickaxe"));
        return new Facts(word -> w.where().of().apply(word), known, word -> Nearby.noSource(word, w.loot()) != null, ores, y, ps, w.bag(), tier);
    }

    // ---- the expansion (classify and sum) ----

    /** A furnace output fed from a band leaf: {@code out} x {@code n} wanted, from {@code leaf} (null: an input that is no leaf itself), per batch. */
    record Smelt(String out, int n, String leaf, int outPer, int inPer) {}

    /** One word's tree: why it fails (null: it stands), its band leaves not under a smelt (word -> count), its smelts. */
    record Tree(String fail, Map<String, Integer> band, List<Smelt> smelts) {
        static final Tree SURFACE = new Tree(null, Map.of(), List.of());

        static Tree no(String why) { return new Tree(why, Map.of(), List.of()); }

        boolean ok() { return fail == null; }

        boolean surface() { return ok() && band.isEmpty() && smelts.isEmpty(); }

        /** Every band leaf it reaches (directly and through its smelts). */
        Set<String> leaves() {
            Set<String> out = new LinkedHashSet<>(band.keySet());
            for (Smelt s : smelts) if (s.leaf() != null) out.add(s.leaf());
            return out;
        }
    }

    /** The recipe tree's depth bound (the expansion's search bound; a deeper tree is no playbook). */
    static final int MAX_DEPTH = 16;     // structural: search bound

    static Tree expand(Facts f, String raw, int n, List<String> path) {
        String w = NeedChain.word(raw);
        if (n <= 0) return Tree.SURFACE;
        if (path.size() > MAX_DEPTH) return Tree.no("the recipe tree of " + NeedChain.shw(w) + " is deeper than " + MAX_DEPTH);
        if (path.contains(w)) return Tree.no("loop: " + NeedChain.shw(w) + " needs itself");
        if (f.surfaceKnown().test(w, n)) return Tree.SURFACE;
        GenWhere.Fact wf = f.where().apply(w);
        if (wf != null && wf.surface()) return Tree.SURFACE;
        path.add(w);
        try {
            Tree best = null;
            String why = null;
            NeedChain.Want want = new NeedChain.Want(w);
            if (want.item()) {
                String item = want.id();
                for (String kind : List.of(ItemWorth.CRAFT, ItemWorth.SMELT))
                    for (ItemWorth.Recipe r : ItemWorth.recipes(item, kind)) {
                        if (NeedChain.packing(item, r)) continue;
                        Tree t = recipe(f, item, r, n, path);
                        if (!t.ok()) { if (why == null) why = t.fail(); continue; }
                        if (t.surface()) return t;
                        if (best == null) best = t;
                    }
            } else {
                for (String m : want.members()) {
                    Tree t = expand(f, m, n, path);
                    if (!t.ok()) { if (why == null) why = t.fail(); continue; }
                    if (t.surface()) return t;
                    if (best == null) best = t;
                }
            }
            if (best != null) return best;
            if (wf != null && wf.band() && !f.dropOnly().test(w)) return new Tree(null, Map.of(w, n), List.of());
            if (wf == null || wf.kind() == GenWhere.Kind.NOT_LOADED)
                return Tree.no(NOT_LOADED + "where " + NeedChain.shw(w) + " generates" + (wf == null ? "" : " (" + wf.why() + ")"));
            return Tree.no(NeedChain.shw(w) + ": no recipe stands" + (why == null ? "" : " (" + why + ")") + " and " + (wf.band() ? "only its own block's tool-gated drop gives it" : wf.text()));
        } finally {
            path.remove(path.size() - 1);
        }
    }

    private static Tree recipe(Facts f, String item, ItemWorth.Recipe r, int n, List<String> path) {
        int batches = (n + r.outCount() - 1) / r.outCount();
        Map<String, Integer> band = new LinkedHashMap<>();
        List<Smelt> smelts = new ArrayList<>();
        for (var e : NeedChain.slotWords(r).entrySet()) {
            Tree t = expand(f, e.getKey(), e.getValue() * batches, path);
            if (!t.ok()) return Tree.no(NeedChain.sh(item) + " <- " + NeedChain.shw(e.getKey()) + ": " + t.fail());
            for (var b : t.band().entrySet()) band.merge(b.getKey(), b.getValue(), Integer::sum);
            smelts.addAll(t.smelts());
        }
        if (ItemWorth.SMELT.equals(r.kind()) && (!band.isEmpty() || !smelts.isEmpty())) {
            if (band.size() == 1 && smelts.isEmpty()) {          // straight from a band leaf (an ingot from its raw ore)
                String leaf = band.keySet().iterator().next();
                return new Tree(null, Map.of(), List.of(new Smelt(item, n, leaf, r.outCount(), 1)));     // structural: a furnace takes one input a batch
            }
            smelts.add(new Smelt(item, n, null, r.outCount(), 1));     // structural: a furnace takes one input a batch
        }
        return new Tree(null, band, smelts);
    }

    // ---- the derivation ----

    /** A gate: the pickaxe made for {@code tier} and its tree. */
    private record Gate(String pick, int tier, Tree tree) {}

    /** The playbook of {@code batch} (the open orders in the batch's order) on the facts {@code f}. */
    public static Playbook derive(List<Target> batch, Facts f) {
        if (batch == null || batch.isEmpty()) return no(batch, "no open order in the batch");
        if (!MiningTiers.ready() || !ItemWorth.hasData()) return no(batch, NOT_LOADED + "the item and mining-tier data");
        Map<Integer, Tree> trees = new LinkedHashMap<>();
        for (int i = 0; i < batch.size(); i++) {
            Target t = batch.get(i);
            Tree tr = expand(f, NeedChain.id(t.item()), t.n(), new ArrayList<>());
            if (!tr.ok()) return no(batch, NeedChain.sh(NeedChain.id(t.item())) + ": " + tr.fail());
            trees.put(i, tr);
        }
        // the surface's best pickaxe (all its leaves on the surface) and the bag's
        List<String> picks = new ArrayList<>(new TreeSet<>(ItemWorth.itemsIn("pickaxes")));
        Map<String, Integer> surfacePicks = new LinkedHashMap<>();
        int p0 = Math.max(0, f.bagTier());
        for (String pid : picks) {
            int tt = MiningTiers.toolTier(pid, "pickaxe");
            if (tt <= 0 || ItemWorth.recipes(pid, ItemWorth.CRAFT).isEmpty()) continue;
            if (expand(f, pid, 1, new ArrayList<>()).surface()) { surfacePicks.put(pid, tt); p0 = Math.max(p0, tt); }
        }
        // the leaves' tiers and the gates above the surface's tier
        Map<String, Integer> tiers = new TreeMap<>();
        List<Gate> gates = new ArrayList<>();
        for (int round = 0; round <= MAX_DEPTH; round++) {
            Set<String> leaves = new LinkedHashSet<>();
            for (Tree t : trees.values()) leaves.addAll(t.leaves());
            for (Gate g : gates) leaves.addAll(g.tree().leaves());
            int need = 0;
            for (String l : leaves) {
                Integer tl = tiers.computeIfAbsent(l, k -> tierOf(f, k));
                if (tl == null) return no(batch, NeedChain.shw(l) + ": no strip way here (no world-gen band of its blocks at or below the feet, or none read; rule 15)");
                if (tl == MiningTiers.UNKNOWN) return no(batch, NOT_LOADED + "the pickaxe tier of " + NeedChain.shw(l) + "'s blocks");
                if (tl > p0 && covering(gates, tl, tiers) == null) need = Math.max(need, tl);
            }
            if (need == 0) break;
            Gate g = gate(f, picks, need, tiers);
            if (g == null) return no(batch, "no pickaxe of tier " + need + " can be made from the game's data (its tree does not stand)");
            gates.add(g);
            if (round == MAX_DEPTH) return no(batch, "the tool gates do not settle within " + MAX_DEPTH + " rounds");
        }
        // stages: 0 at the surface's tier, one more per gate under it
        Map<String, Integer> stage = new LinkedHashMap<>();
        for (String l : tiers.keySet()) stage(l, tiers, gates, p0, stage, 0);
        int maxStage = -1;
        for (int s : stage.values()) maxStage = Math.max(maxStage, s);
        // the legs
        List<Leg> legs = new ArrayList<>();
        List<Target> surface = new ArrayList<>();
        Set<String> surfaceGates = new LinkedHashSet<>();
        int tier0 = 0;
        for (var e : stage.entrySet()) if (e.getValue() == 0) tier0 = Math.max(tier0, tiers.get(e.getKey()));
        int gateTier0 = 0;
        if (maxStage >= 0 && tier0 > Math.max(0, f.bagTier())) {
            String pick = null;
            int best = Integer.MAX_VALUE;
            for (var e : surfacePicks.entrySet()) if (e.getValue() >= tier0 && e.getValue() < best) { best = e.getValue(); pick = e.getKey(); }
            if (pick == null) return no(batch, "no pickaxe of tier " + tier0 + " can be made from surface leaves");
            surface.add(new Target(pick, 1));
            surfaceGates.add(pick);
            gateTier0 = tier0;
        }
        for (var e : trees.entrySet()) if (e.getValue().surface()) surface.add(batch.get(e.getKey()));
        if (!surface.isEmpty()) legs.add(new Leg(Kind.SURFACE, surface, NO_Y, null, null, gateTier0, surfaceGates, false));
        Map<String, Integer> bagLeft = new LinkedHashMap<>(f.bag());
        for (int k = 0; k <= maxStage; k++) {
            final int sk = k;
            int pk = p0;
            for (Gate g : gates) if (stageOfGate(g, tiers, gates, p0, stage) < sk) pk = Math.max(pk, g.tier());
            // the player's order at the band: the trees that unlock more (this stage's gates and the orders that are a better
            // pickaxe than the one the strip digs with) first - their ore stripped, smelted and the pickaxe made - then the rest
            // stripped on at the same level with it (no second descent; the better pickaxe digs faster: priced, rule 1)
            List<Tree> toolTrees = new ArrayList<>(), restTrees = new ArrayList<>();
            List<Integer> toolIdx = new ArrayList<>(), restIdx = new ArrayList<>();
            List<Gate> gatesK = new ArrayList<>();
            for (Gate g : gates) {
                if (stageOfGate(g, tiers, gates, p0, stage) == sk) { gatesK.add(g); toolTrees.add(g.tree()); }
                else restTrees.add(g.tree());
            }
            for (var e : trees.entrySet()) {
                if (e.getValue().surface()) continue;
                boolean mine = stageOfTree(e.getValue(), stage) == sk;
                boolean better = mine && MiningTiers.toolTier(NeedChain.id(batch.get(e.getKey()).item()), "pickaxe") > pk;
                if (better) { toolTrees.add(e.getValue()); toolIdx.add(e.getKey()); }
                else { restTrees.add(e.getValue()); if (mine) restIdx.add(e.getKey()); }
            }
            boolean split = !toolTrees.isEmpty() && hasLeaves(restTrees, stage, sk);
            if (split) {
                String why = part(f, batch, sk, pk, toolTrees, gatesK, toolIdx, stage, tiers, bagLeft, false, legs);
                if (why != null) return no(batch, why);
                why = part(f, batch, sk, pk, restTrees, List.of(), restIdx, stage, tiers, bagLeft, true, legs);
                if (why != null) return no(batch, why);
            } else {
                List<Tree> all = new ArrayList<>(toolTrees);
                all.addAll(restTrees);
                List<Integer> idx = new ArrayList<>(toolIdx);
                idx.addAll(restIdx);
                java.util.Collections.sort(idx);
                String why = part(f, batch, sk, pk, all, gatesK, idx, stage, tiers, bagLeft, false, legs);
                if (why != null) return no(batch, why);
            }
        }
        List<String> parts = new ArrayList<>();
        for (Leg l : legs) parts.add(l.text());
        return new Playbook(batch, legs, "playbook: " + String.join(" > ", parts), null);
    }

    private static Playbook no(List<Target> batch, String why) { return new Playbook(batch, List.of(), "playbook: " + why, why); }

    /** Any band leaf of stage {@code k} in these trees. */
    private static boolean hasLeaves(List<Tree> trees, Map<String, Integer> stage, int k) {
        for (Tree t : trees) for (String l : t.leaves()) if (stage.getOrDefault(l, -1) == k) return true;
        return false;
    }

    /**
     * One part of a stage: its DESCENT (the band leaves of {@code trees} at stage {@code k}, the smelts' by what the bag still
     * lacks of their output - {@code bagLeft}, used up part by part - and their fuel) and its AT_BAND (the smelts, the gates,
     * the orders: tools first). {@code on}: stripped on at the last descent's level. Null: added; else the reason.
     */
    private static String part(Facts f, List<Target> batch, int k, int pk, List<Tree> trees, List<Gate> gatesK, List<Integer> idx, Map<String, Integer> stage,
                               Map<String, Integer> tiers, Map<String, Integer> bagLeft, boolean on, List<Leg> legs) {
        Map<String, Integer> leafNeed = new LinkedHashMap<>();
        Map<String, Integer> smeltOut = new LinkedHashMap<>();
        Map<String, Smelt> smeltOf = new LinkedHashMap<>();
        for (Tree t : trees) {
            for (var b : t.band().entrySet()) if (stage.getOrDefault(b.getKey(), -1) == k) leafNeed.merge(b.getKey(), b.getValue(), Integer::sum);
            for (Smelt s : t.smelts()) {
                if (s.leaf() == null || stage.getOrDefault(s.leaf(), -1) != k) continue;
                smeltOut.merge(s.out(), s.n(), Integer::sum);
                smeltOf.putIfAbsent(s.out(), s);
            }
        }
        int smelts = 0;
        Map<String, Integer> made = new LinkedHashMap<>();
        for (var e : smeltOut.entrySet()) {
            Smelt s = smeltOf.get(e.getKey());
            int had = Math.min(e.getValue(), bagLeft.getOrDefault(e.getKey(), 0));
            bagLeft.merge(e.getKey(), -had, Integer::sum);
            int lack = e.getValue() - had;
            int batches = (lack + s.outPer() - 1) / s.outPer();
            smelts += batches;
            if (batches > 0) { leafNeed.merge(s.leaf(), batches * s.inPer(), Integer::sum); made.put(e.getKey(), e.getValue()); }
        }
        String fuel = smelts > 0 ? fuelLeaf(f, pk, tiers) : null;
        int fuelN = 0;
        if (fuel != null) {
            int burn = ItemWorth.fuels().getOrDefault(fuel, 0);
            fuelN = burn <= 0 ? 0 : (int) Math.ceil(smelts * NeedChain.smeltTicks() / burn - 1e-9);     // structural: epsilon
        }
        Map<String, Integer> gets = new LinkedHashMap<>(leafNeed);
        if (fuel != null && fuelN > 0) gets.merge(fuel, fuelN, Integer::sum);
        gets.entrySet().removeIf(e -> e.getValue() <= 0);
        if (!gets.isEmpty()) {          // empty: the bag holds this part's ore already - no descent, the at-band leg as below
            Object d = descent(f, k, gets, fuel, tiers, on);
            if (d instanceof String why) return why;
            legs.add((Leg) d);
        }
        // at the band: the smelts, then the gates and the tools, then the rest in the batch's order
        List<Target> at = new ArrayList<>();
        for (var e : made.entrySet()) at.add(new Target(e.getKey(), e.getValue()));
        Set<String> atGates = new LinkedHashSet<>();
        int atTier = 0;
        for (Gate g : gatesK) { at.add(new Target(g.pick(), 1)); atGates.add(g.pick()); atTier = Math.max(atTier, g.tier()); }
        List<Target> tools = new ArrayList<>(), rest = new ArrayList<>();
        for (int i : idx) {
            Target t = batch.get(i);
            if (at.stream().anyMatch(x -> NeedChain.id(x.item()).equals(NeedChain.id(t.item())) && x.n() >= t.n())) continue;     // the order is the smelt itself
            (MiningTiers.anyToolTier(NeedChain.id(t.item())) > 0 ? tools : rest).add(t);
        }
        at.addAll(tools);
        at.addAll(rest);
        if (!at.isEmpty()) legs.add(new Leg(Kind.AT_BAND, at, NO_Y, null, null, atTier, atGates, false));
        return null;
    }

    /**
     * One DESCENT (stage {@code k}): the one Y for all its leaves - of their strip Ys the one with the most {@code need x ore a
     * slab} (rough, rule 14; ties: the first leaf's) - and each leaf's ore a slab there. A leaf with no ore at that Y: the fuel
     * is left to the planner's burn ways, an order's leaf fails the playbook (its reason). Returns the Leg, or the reason.
     */
    private static Object descent(Facts f, int k, Map<String, Integer> gets, String fuel, Map<String, Integer> tiers, boolean on) {
        String names = String.join(", ", gets.keySet().stream().map(NeedChain::shw).toList());
        List<Integer> ys = new ArrayList<>();
        for (String l : gets.keySet()) { Integer y = f.stripY().apply(l); if (y != null && !ys.contains(y)) ys.add(y); }
        if (ys.isEmpty()) return "descent " + (k + 1) + ": no strip Y known for " + names;
        int bestY = ys.get(0);
        double bestScore = -1;
        for (int y : ys) {
            double sc = 0;
            for (var e : gets.entrySet()) { Double d = f.perSlab().apply(e.getKey(), y); if (d != null && d > 0) sc += e.getValue() * d; }
            if (sc > bestScore) { bestScore = sc; bestY = y; }
        }
        Map<String, Double> per = new LinkedHashMap<>();
        List<String> score = new ArrayList<>();
        List<Target> down = new ArrayList<>();
        int tierK = 0;
        for (var e : gets.entrySet()) {
            Double d = f.perSlab().apply(e.getKey(), bestY);
            if (d == null || !(d > 0)) {
                if (e.getKey().equals(fuel)) continue;      // the fuel by the planner's own burn ways then
                return NeedChain.shw(e.getKey()) + " does not generate at y " + bestY + " (the descent's Y for " + names + ")";
            }
            per.put(e.getKey(), d);
            score.add(NeedChain.shw(e.getKey()) + " " + e.getValue() + " x " + String.format(Locale.ROOT, "%.2f", d));
            down.add(new Target(e.getKey(), e.getValue()));
            tierK = Math.max(tierK, tiers.getOrDefault(e.getKey(), 0));
        }
        return new Leg(Kind.DESCENT, down, bestY, (on ? "on at " : "") + "y " + bestY + ": the most ore for the batch (" + String.join(" + ", score) + " a slab)", per, tierK, null, on);
    }

    /** The pickaxe tier the leaf's strip blocks ask (the highest); null: no strip way for it; {@link MiningTiers#UNKNOWN}: not loaded. */
    private static Integer tierOf(Facts f, String leaf) {
        List<String> ores = f.oresOf().apply(leaf);
        if (ores == null || ores.isEmpty()) return null;
        MiningTiers.Source src = MiningTiers.source();
        if (src == null) return MiningTiers.UNKNOWN;
        int t = 0;
        for (String o : ores) {
            int x = src.blockTier(o);
            if (x == MiningTiers.UNKNOWN) return MiningTiers.UNKNOWN;
            t = Math.max(t, x);
        }
        return t;
    }

    /** The gate that digs a leaf of {@code tier}: of at least that tier, its own leaves all below it (made before it is needed), the lowest. */
    private static Gate covering(List<Gate> gates, int tier, Map<String, Integer> tiers) {
        Gate best = null;
        for (Gate g : gates) {
            if (g.tier() < tier || (best != null && g.tier() >= best.tier())) continue;
            boolean before = true;
            for (String l : g.tree().leaves()) if (tiers.getOrDefault(l, Integer.MAX_VALUE) >= tier) { before = false; break; }
            if (before) best = g;
        }
        return best;
    }

    /**
     * The cheapest pickaxe of at least {@code tier} whose tree stands and whose own band leaves its holder can dig before it
     * exists (each leaf's tier below {@code tier}; a pickaxe made from the ore it is for is no gate): the lowest tier first,
     * then the fewest leaves, then by id.
     */
    private static Gate gate(Facts f, List<String> picks, int tier, Map<String, Integer> tiers) {
        Gate best = null;
        for (String pid : picks) {
            int tt = MiningTiers.toolTier(pid, "pickaxe");
            if (tt < tier || ItemWorth.recipes(pid, ItemWorth.CRAFT).isEmpty()) continue;
            Tree t = expand(f, pid, 1, new ArrayList<>());
            if (!t.ok()) continue;
            boolean digs = true;
            for (String l : t.leaves()) {
                Integer tl = tiers.computeIfAbsent(l, k -> tierOf(f, k));
                if (tl == null || tl == MiningTiers.UNKNOWN || tl >= tier) { digs = false; break; }
            }
            if (!digs) continue;
            if (best == null || tt < best.tier() || (tt == best.tier() && t.leaves().size() < best.tree().leaves().size())) best = new Gate(pid, tt, t);
        }
        return best;
    }

    private static int stage(String leaf, Map<String, Integer> tiers, List<Gate> gates, int p0, Map<String, Integer> memo, int depth) {
        Integer m = memo.get(leaf);
        if (m != null) return m;
        int t = tiers.getOrDefault(leaf, 0);
        int s = 0;
        if (t > p0 && depth <= MAX_DEPTH) {
            Gate g = covering(gates, t, tiers);
            s = g == null ? 0 : stageOfGateAt(g, tiers, gates, p0, memo, depth + 1) + 1;
        }
        memo.put(leaf, s);
        return s;
    }

    private static int stageOfGate(Gate g, Map<String, Integer> tiers, List<Gate> gates, int p0, Map<String, Integer> memo) {
        return stageOfGateAt(g, tiers, gates, p0, memo, 0);
    }

    private static int stageOfGateAt(Gate g, Map<String, Integer> tiers, List<Gate> gates, int p0, Map<String, Integer> memo, int depth) {
        int s = 0;
        for (String l : g.tree().leaves()) s = Math.max(s, stage(l, tiers, gates, p0, memo, depth));
        return s;
    }

    private static int stageOfTree(Tree t, Map<String, Integer> stage) {
        int s = -1;
        for (String l : t.leaves()) s = Math.max(s, stage.getOrDefault(l, 0));
        return s;
    }

    /**
     * The fuel a descent strips for its smelts: the game's fuel table, the longest burn a piece first, the first that is a
     * band leaf itself (its own drop, world-gen placed in a band) whose blocks the descent's pickaxe tier {@code tier} digs.
     * Null: none (the planner's burn ways decide: charcoal, a known source, the bag).
     */
    private static String fuelLeaf(Facts f, int tier, Map<String, Integer> tiers) {
        List<Map.Entry<String, Integer>> fuels = new ArrayList<>(ItemWorth.fuels().entrySet());
        fuels.sort(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()));
        for (var e : fuels) {
            if (e.getValue() <= 0) continue;
            String w = NeedChain.word(e.getKey());
            Tree t = expand(f, w, 1, new ArrayList<>());
            if (!t.ok() || !t.smelts().isEmpty() || !t.band().equals(Map.of(w, 1))) continue;
            Integer tl = tiers.computeIfAbsent(w, k -> tierOf(f, k));
            if (tl == null || tl == MiningTiers.UNKNOWN || tl > tier) continue;
            return w;
        }
        return null;
    }
}
