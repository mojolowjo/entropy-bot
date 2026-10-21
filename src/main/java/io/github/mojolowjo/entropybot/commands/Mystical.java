package io.github.mojolowjo.entropybot.commands;

import io.github.mojolowjo.entropybot.Core;
import io.github.mojolowjo.entropybot.commands.Seq.Step;
import io.github.mojolowjo.entropybot.craft.CraftPlanner;
import io.github.mojolowjo.entropybot.craft.CraftTexts;
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


    private static int bag(LocalPlayer p, String id) { return Gui.inventory(p).getOrDefault(id, 0); }

    private static int freeSlots(LocalPlayer p) {
        int n = 0;
        for (int i = 0; i < 36; i++) if (p.getInventory().getItem(i).isEmpty()) n++;
        return n;
    }

    // ---- upgrade ----

    /**
     * The crystal the grid will use (review fix: the same one GridLayout + GridLoop pick, {@link Upgrade#crystalSlot}):
     * {id, usesLeft} (-1 = it doesn't wear), or null when the bag has none.
     */
    private static Object[] crystalInBag(LocalPlayer p, List<String> crystals) {
        // menu order of a crafting grid: the inventory rows (9..35), then the hotbar (0..8)
        List<ItemStack> order = new ArrayList<>();
        for (int i = 9; i < 36; i++) order.add(p.getInventory().getItem(i));
        for (int i = 0; i < 9; i++) order.add(p.getInventory().getItem(i));
        List<String> ids = new ArrayList<>();
        for (ItemStack s : order) ids.add(s.isEmpty() ? "" : BuiltInRegistries.ITEM.getKey(s.getItem()).toString());
        int at = Upgrade.crystalSlot(ids, crystals);
        if (at < 0) return null;
        ItemStack s = order.get(at);
        return new Object[]{ids.get(at), s.isDamageableItem() ? s.getMaxDamage() - s.getDamageValue() : -1};
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
        String notUp = Upgrade.notUp(planner(), all.steps(), u.id());
        if (notUp != null) return Upgrade.notUpRefusal(u.n(), u.id(), notUp);
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
            s.note = Upgrade.done(made, st.id, us.rounds, cb == null ? null : (String) cb[0], cb == null ? -1 : (Integer) cb[1]);
            return "next";
        }
        if (us.rounds > 0 && made <= us.lastMade) return Upgrade.noProgress(made, st.want, st.id);
        Map<String, Integer> inv = Gui.inventory(p);
        Map<String, Integer> combined = CraftTexts.combine(inv, Crafting.totals(crafting.sources(p)));
        // review fix: every round is planned here first (the craftitem below plans the same way from the same stock) and
        // only a climb up is sized: a plan that takes a higher tier apart or unpacks the target's own block is refused
        int k = Upgrade.roundSize(st.want - made, freeSlots(p), kk -> Upgrade.slotsFor(planner(), st.id, kk, inv, combined));
        if (k < 1) {
            Upgrade.RoundPlan one = Upgrade.roundPlan(planner(), st.id, 1, inv, combined);
            if (one == null) return "I can't plan another " + CraftPlanner.shortId(st.id) + " from what is left (" + made + " of " + st.want + " made)";
            String why = Upgrade.notUp(planner(), one.plan().steps(), st.id);
            if (why != null) return Upgrade.notUpRefusal(st.want - made, st.id, why).replaceFirst("^error: ", "") + " (" + made + " of " + st.want + " made)";
            return Upgrade.bagFull(made, st.want, st.id);
        }
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
}
