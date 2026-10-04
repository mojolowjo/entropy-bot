package io.github.mojolowjo.entropybot.commands;

import com.google.gson.JsonObject;
import io.github.mojolowjo.entropybot.Core;
import io.github.mojolowjo.entropybot.altar.AltarMemory;
import io.github.mojolowjo.entropybot.altar.AltarPlan;
import io.github.mojolowjo.entropybot.altar.AltarRun;
import io.github.mojolowjo.entropybot.altar.McAltarWorld;
import io.github.mojolowjo.entropybot.commands.Seq.Step;
import io.github.mojolowjo.entropybot.craft.CraftPlanner;
import io.github.mojolowjo.entropybot.craft.CraftTexts;
import io.github.mojolowjo.entropybot.craft.Crafter;
import io.github.mojolowjo.entropybot.craft.RecipeData;
import io.github.mojolowjo.entropybot.craft.Upgrade;
import io.github.mojolowjo.entropybot.gui.Gui;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Package E: the Mystical Agriculture verbs (TO-LOOK-AT-LATER 14 and 15), on the craft planner (package D), the batch
 * grid loop (package G, with the crystal batching of this package) and the storage trips.
 * <ul>
 *   <li>{@code upgrade <essence> [n]}: the tier climb in rounds sized to the bag (Seq step "upgraderound", which splices
 *   a "craftitem" for each round and itself after it).</li>
 *   <li>{@code infuse <seed> [n]}: fetch and craft the ingredients, find the altar (step "altarfind"), stand by it,
 *   then {@link AltarRun} (step "infuse").</li>
 * </ul>
 */
final class Mystical {
    private final Crafting crafting;
    private final Core core;
    private final Commands commands;
    private final Jobs jobs;
    private final Storage storage;

    Mystical(Crafting crafting, Core core, Commands commands, Jobs jobs, Storage storage) {
        this.crafting = crafting;
        this.core = core;
        this.commands = commands;
        this.jobs = jobs;
        this.storage = storage;
    }

    private CraftPlanner planner() { return crafting.planner; }

    private String start(String label, List<Step> steps) {
        return jobs.startSeq(new Seq(jobs, storage, label, steps, "always"), "always");
    }

    private AltarMemory memory() { return new AltarMemory(commands.brainData(), commands::saved); }

    private static int bag(LocalPlayer p, String id) { return Gui.inventory(p).getOrDefault(id, 0); }

    private static int freeSlots(LocalPlayer p) {
        int n = 0;
        for (int i = 0; i < 36; i++) if (p.getInventory().getItem(i).isEmpty()) n++;
        return n;
    }

    private int[] base() {
        JsonObject b = core.knowledge.places().get("base");
        return b != null && Jobs.dimOf(b).equals(Storage.dim()) ? Jobs.pos(b) : null;
    }

    // ---- upgrade ----

    /** The crystal in the bag with the most uses left: {id, usesLeft} (-1 = it doesn't wear), or null. */
    private static Object[] crystalInBag(LocalPlayer p, List<String> crystals) {
        Object[] best = null;
        for (int i = 0; i < 36; i++) {
            ItemStack s = p.getInventory().getItem(i);
            if (s.isEmpty()) continue;
            String id = BuiltInRegistries.ITEM.getKey(s.getItem()).toString();
            if (!crystals.contains(id)) continue;
            int left = s.isDamageableItem() ? s.getMaxDamage() - s.getDamageValue() : -1;
            if (best == null || left == -1 || ((Integer) best[1] != -1 && left > (Integer) best[1])) best = new Object[]{id, left};
        }
        return best;
    }

    /** Upgrade's bookkeeping across its rounds. */
    static final class UpState {
        int base = -1, rounds, lastMade = -1;
        List<String> crystals = List.of();
    }

    String upgrade(LocalPlayer p, String text) {
        Map<String, Integer> inv = Gui.inventory(p);
        Upgrade.Parsed u = Upgrade.parse(text, q -> planner().resolveItem(q, inv));
        if (!u.ok()) return u.error();
        RecipeData up = Upgrade.tierUp(planner(), u.id());
        if (up == null) return Upgrade.noRecipe(u.id());
        List<String> crystals = Upgrade.crystals(planner(), up);
        List<Crafting.Source> src = crafting.sources(p);
        Map<String, Integer> combined = CraftTexts.combine(inv, Crafting.totals(src));
        boolean anyCrystal = false;
        for (String c : crystals) anyCrystal |= combined.getOrDefault(c, 0) > 0;
        if (!anyCrystal) return Upgrade.noCrystal(crystals, crafting.rsKnown());
        CraftPlanner.AllPlan all = planner().planAll(List.of(new CraftPlanner.Target(u.id(), u.n())), new LinkedHashMap<>(combined), combined);
        if (!all.ok()) {
            String why = all.error(), pre = CraftPlanner.shortId(u.id()) + ": ";
            return Upgrade.cantPlan(u.n(), u.id(), why.startsWith(pre) ? why.substring(pre.length()) : why);
        }
        Crafter.Craft down = Upgrade.breakdownIn(planner(), all.steps());
        if (down != null) return Upgrade.breaksDown(u.n(), u.id(), down);
        // a worn crystal in the bag would break halfway (the planner doesn't know durability)
        long crafts = Upgrade.crystalCrafts(planner(), all.steps());
        Object[] cb = crystalInBag(p, crystals);
        if (cb != null && (Integer) cb[1] >= 0 && (Integer) cb[1] < crafts) return Upgrade.crystalWorn((String) cb[0], (Integer) cb[1], crafts);
        Step st = new Step("upgraderound");
        st.id = u.id();
        st.want = u.n();
        UpState us = new UpState();
        us.crystals = crystals;
        st.state = us;
        return start(Upgrade.label(u.n(), u.id(), 0, 0), List.of(st));
    }

    /** One "upgraderound": done, or the next round (a craft of what fits the bag) and this step again after it. */
    String upgradeRound(Seq s, Step st, LocalPlayer p) {
        UpState us = st.state instanceof UpState x ? x : new UpState();
        st.state = us;
        if (us.base < 0) us.base = bag(p, st.id);
        int made = Math.max(0, bag(p, st.id) - us.base);
        if (made >= st.want) {
            Object[] cb = crystalInBag(p, us.crystals);
            s.label = Upgrade.label(st.want, st.id, 0, 0);          // "ok: done upgrading to 4 ...; made 4 ... in 1 round"
            s.note =Upgrade.done(made, st.id, us.rounds, cb == null ? null : (String) cb[0], cb == null ? -1 : (Integer) cb[1]);
            return "next";
        }
        if (us.rounds > 0 && made <= us.lastMade) return Upgrade.noProgress(made, st.want, st.id);
        Map<String, Integer> inv = Gui.inventory(p);
        Map<String, Integer> combined = CraftTexts.combine(inv, Crafting.totals(crafting.sources(p)));
        int k = Upgrade.roundSize(st.want - made, freeSlots(p), kk -> Upgrade.slotsFor(planner(), st.id, kk, inv, combined));
        if (k < 1) return Upgrade.bagFull(made, st.want, st.id);
        us.rounds++;
        us.lastMade = made;
        s.label = Upgrade.label(st.want, st.id, us.rounds, made);
        Step c = new Step("craftitem");
        c.text = st.id + " " + k;
        Step again = new Step("upgraderound");
        again.id = st.id;
        again.want = st.want;
        again.state = us;
        s.splice(s.idx + 1, List.of(c, again));
        return "next";
    }

    // ---- infuse ----

    /** What the altar steps need: the seed, how many, the item per slot. */
    record InfuseSpec(String seed, int n, AltarPlan.Pick pick) {}

    /** The seed's infusion recipe ("silicon" -> silicon_seeds), or null. */
    private RecipeData infusionRecipe(String id) {
        if (id == null) return null;
        for (RecipeData r : planner().source().recipesFor(id)) if (AltarPlan.infusion(r) && id.equals(r.output())) return r;
        return null;
    }

    /** The nearest infusion altar within 16 of the bot, else within 24 of the base (loaded chunks only), or null. */
    private int[] findAltar(LocalPlayer p) {
        int[] a = Crafting.findBlockAround(Jobs.here(p), AltarPlan.ALTAR, 16, 6);
        if (a != null) return a;
        int[] b = base();
        return b != null ? Crafting.findBlockAround(b, AltarPlan.ALTAR, 24, 6) : null;
    }

    String infuse(LocalPlayer p, String text) {
        String t = text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
        if (t.isEmpty()) return AltarPlan.USAGE;
        String[] w = t.split("\\s+");
        int n = 1;
        String q = t;
        if (w.length >= 2 && w[w.length - 1].matches("\\d{1,9}")) {
            n = Integer.parseInt(w[w.length - 1]);
            q = String.join("_", java.util.Arrays.copyOf(w, w.length - 1));
        } else {
            q = String.join("_", w);
        }
        if (n < 1) return "error: infuse needs a count of 1 or more";
        if (n > AltarPlan.MAX_N) return "error: at most " + AltarPlan.MAX_N + " seeds in one infuse";
        Map<String, Integer> inv = Gui.inventory(p);
        String id = null;
        RecipeData r = null;
        for (String cand : new String[]{q, q.endsWith("_seeds") ? null : q + "_seeds"}) {
            if (cand == null || r != null) continue;
            String c = planner().resolveItem(cand, inv);
            RecipeData rr = infusionRecipe(c);
            if (rr != null) {
                id = c;
                r = rr;
            }
        }
        if (r == null) return AltarPlan.noRecipe(q);
        AltarPlan.Split split = AltarPlan.split(r, planner()::itemExists);
        if (!split.ok()) return "error: " + split.error();
        List<Crafting.Source> src = crafting.storageSources(p);
        Map<String, Integer> stored = Crafting.totals(src);
        Map<String, Integer> combined = CraftTexts.combine(inv, stored);
        AltarPlan.Pick pick = AltarPlan.pick(split, combined);
        Map<String, Integer> need = pick.times(n);
        // what the bag lacks: from the chests and the RS network, else crafted (the seed base)
        Map<String, Integer> take = new LinkedHashMap<>(), make = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> e : need.entrySet()) {
            int lack = e.getValue() - inv.getOrDefault(e.getKey(), 0);
            if (lack <= 0) continue;
            int fromStore = Math.min(lack, stored.getOrDefault(e.getKey(), 0));
            if (fromStore > 0) take.put(e.getKey(), fromStore);
            if (lack > fromStore) make.put(e.getKey(), lack - fromStore);
        }
        if (!make.isEmpty()) {
            Map<String, Integer> left = new LinkedHashMap<>(combined);
            for (Map.Entry<String, Integer> e : need.entrySet()) left.merge(e.getKey(), -Math.min(e.getValue(), combined.getOrDefault(e.getKey(), 0)), Integer::sum);
            for (Map.Entry<String, Integer> e : make.entrySet()) {
                CraftPlanner.AllPlan cp = planner().planAll(List.of(new CraftPlanner.Target(e.getKey(), e.getValue())), left, left);
                if (!cp.ok()) {
                    String why = cp.error(), pre = CraftPlanner.shortId(e.getKey()) + ": ";
                    return AltarPlan.missing(n, id, e.getKey(), combined.getOrDefault(e.getKey(), 0), pick.perRound().get(e.getKey()),
                            why.startsWith(pre) ? why.substring(pre.length()) : why);
                }
            }
        }
        // the altar now, when it is in sight: a pedestal with somebody else's item is refused before any trip
        int[] altar = findAltar(p);
        if (altar != null) {
            String why = altarCheck(p, altar, pick);
            if (why != null) return "error: " + why;
        } else if (base() == null) {
            return AltarPlan.noAltar(false);
        }
        List<Step> steps = new ArrayList<>(Crafting.takeTrips(src, take).steps());
        for (Map.Entry<String, Integer> e : make.entrySet()) {
            Step c = new Step("craftitem");
            c.text = e.getKey() + " " + e.getValue();
            steps.add(c);
        }
        if (altar == null) steps.add(Step.walk(base(), true));       // far away: the base first, then look for it
        Step find = new Step("altarfind");
        find.state = new InfuseSpec(id, n, pick);
        steps.add(find);
        String label = AltarPlan.label(n, id) + (split.guessed() ? " (" + AltarPlan.guessedNote(pick.center()) + ")" : "");
        return start(label, steps);
    }

    /** The altar's layout and what is on it: null when it can be used, else why not. */
    private String altarCheck(LocalPlayer p, int[] altar, AltarPlan.Pick pick) {
        McAltarWorld w = new McAltarWorld(p);
        AltarPlan.Layout l = AltarPlan.layout(altar, w, w::standable, pick.pedestals().size());
        if (!l.ok()) return l.error();
        AltarPlan.Survey sv = AltarPlan.survey(l, AltarPlan.targets(l, pick), w, memory());
        return sv.foreign();
    }

    /** "altarfind": the altar near the bot or the base; the walk to the spot by it, then the altar run. */
    String altarFind(Seq s, Step st, LocalPlayer p) {
        InfuseSpec spec = (InfuseSpec) st.state;
        int[] altar = findAltar(p);
        if (altar == null) return AltarPlan.noAltar(base() != null).replaceFirst("^error: ", "");
        McAltarWorld w = new McAltarWorld(p);
        AltarPlan.Layout l = AltarPlan.layout(altar, w, w::standable, spec.pick().pedestals().size());
        if (!l.ok()) return l.error();
        AltarMemory mem = memory();
        AltarPlan.Survey sv = AltarPlan.survey(l, AltarPlan.targets(l, spec.pick()), w, mem);
        if (sv.foreign() != null) return sv.foreign();
        Step walk = Step.walk(l.stand(), false);
        walk.exact = true;
        walk.why = "the infusion altar at " + Jobs.fmt(altar);
        Step run = new Step("infuse");
        run.state = new AltarRun(l, spec.pick(), spec.seed(), spec.n(), mem);
        s.splice(s.idx + 1, List.of(walk, run));
        return "next";
    }

    /** "infuse": the altar run, every 2 ticks. */
    String infuseStep(Seq s, Step st, LocalPlayer p) {
        AltarRun run = (AltarRun) st.state;
        AltarRun.Out o = run.tick(new McAltarWorld(p), core.tick());
        if (o.status() != null) s.setStatus(o.status());
        switch (o.state()) {
            case DONE:
                s.note = o.text();
                return "next";
            case FAIL:
                return o.text();
            default:
                return "wait";
        }
    }
}
