package io.github.mojolowjo.entropybot.commands;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.mojolowjo.entropybot.Core;
import io.github.mojolowjo.entropybot.commands.Seq.Step;
import io.github.mojolowjo.entropybot.craft.CraftJob;
import io.github.mojolowjo.entropybot.craft.CraftPlanner;
import io.github.mojolowjo.entropybot.craft.CraftTexts;
import io.github.mojolowjo.entropybot.craft.Crafter;
import io.github.mojolowjo.entropybot.craft.GridLayout;
import io.github.mojolowjo.entropybot.craft.GridLoop;
import io.github.mojolowjo.entropybot.craft.McRecipes;
import io.github.mojolowjo.entropybot.craft.RecipeData;
import io.github.mojolowjo.entropybot.farm.Compact;
import io.github.mojolowjo.entropybot.farm.FarmCommand;
import io.github.mojolowjo.entropybot.farm.FarmRound;
import io.github.mojolowjo.entropybot.farm.FarmSpot;
import io.github.mojolowjo.entropybot.farm.McFarmWorld;
import io.github.mojolowjo.entropybot.farm.PlanStep;
import io.github.mojolowjo.entropybot.gui.Gui;
import io.github.mojolowjo.entropybot.gui.GuiCore;
import io.github.mojolowjo.entropybot.gui.McMenu;
import io.github.mojolowjo.entropybot.storage.StorageRules;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * B7c: crafting, smelting, get/need/supplies/restock/kit, the farm round and compact in the mod (the bridge's
 * startCraft, stepCraft, startSmelt, startGet, needCommand, suppliesCommand, startRestock, startKit, startFarm,
 * farmStep, startCompact, compactStep), on the craft/ and farm/ packages' game-free logic. The crafting grid is filled
 * with exact cursor clicks from the client's recipes (no EMI); the jobs are {@link Seq} jobs.
 */
final class Crafting {
    private final Core core;
    private final Commands commands;
    private final Jobs jobs;
    private final Storage storage;
    final McRecipes recipes = new McRecipes();
    final CraftPlanner planner = new CraftPlanner(recipes);

    Crafting(Core core, Commands commands, Jobs jobs, Storage storage) {
        this.core = core;
        this.commands = commands;
        this.jobs = jobs;
        this.storage = storage;
    }

    private long now() { return core.tick(); }

    private String startSeq(String label, List<Step> steps, String close) {
        return jobs.startSeq(new Seq(jobs, storage, label, steps, close), close);
    }

    // ---- storage the craft trips take from (the bridge's storageSources / takeTripSteps) ----

    record Source(boolean rs, int[] pos, Map<String, Integer> items) {}

    /** The trusted chests near the base (or the bot), then the RS network as its last reading has it. */
    List<Source> sources(LocalPlayer p) {
        List<Source> out = new ArrayList<>();
        for (StorageRules.Chest c : storage.baseChests(p, false)) out.add(new Source(false, c.pos(), c.items()));
        String grid = core.knowledge.rsGrid();
        JsonObject r = grid != null ? core.knowledge.rs().get(grid) : null;
        if (r != null && r.has("items") && Storage.dim().equals(r.has("dim") ? r.get("dim").getAsString() : null)) {
            StorageRules.Spot spot = StorageRules.resolveSpot(grid, core.knowledge.places(), Storage.dim());
            if (spot.err() == null) {
                Map<String, Integer> items = new LinkedHashMap<>();
                for (Map.Entry<String, JsonElement> e : r.getAsJsonObject("items").entrySet()) items.put(e.getKey(), e.getValue().getAsInt());
                out.add(new Source(true, spot.pos(), items));
            }
        }
        return out;
    }

    boolean rsKnown() { return core.knowledge.rsGrid() != null; }

    static Map<String, Integer> totals(List<Source> src) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (Source s : src) s.items().forEach((k, v) -> out.merge(k, v, Integer::sum));
        return out;
    }

    record Trips(List<Step> steps, int used) {}

    /** Steps that take `need` (used up as it goes) from the sources, nearest chest first, the network last. */
    static Trips takeTrips(List<Source> sources, Map<String, Integer> need) {
        List<Step> steps = new ArrayList<>();
        int used = 0;
        for (Source s : sources) {
            List<Map<String, Object>> take = new ArrayList<>();
            for (Map.Entry<String, Integer> e : need.entrySet()) {
                int have = s.items().getOrDefault(e.getKey(), 0);
                if (e.getValue() > 0 && have > 0) {
                    int amt = Math.min(e.getValue(), have);
                    Map<String, Object> op = new LinkedHashMap<>();
                    op.put("op", "take");
                    op.put("id", e.getKey());
                    op.put("n", amt);
                    op.put("roles", GuiCore.TAKE_ROLES);
                    take.add(op);
                    e.setValue(e.getValue() - amt);
                }
            }
            if (take.isEmpty()) continue;
            used++;
            steps.add(Step.walk(s.pos(), false));
            if (s.rs()) {
                steps.add(Step.open(s.pos(), "yes"));
                for (Map<String, Object> t : take) {
                    Step mv = new Step("rsmove");
                    mv.op = "take";
                    mv.id = (String) t.get("id");
                    mv.n = (Integer) t.get("n");
                    steps.add(mv);
                }
                Step read = new Step("rsread");
                read.key = Jobs.fmt(s.pos());
                read.keepNote = true;
                steps.add(read);
            } else {
                steps.add(Step.open(s.pos(), "no"));
                Step ops = new Step("ops");
                ops.pos = s.pos();
                ops.ops = take;
                steps.add(ops);
            }
            steps.add(Step.close());
        }
        return new Trips(steps, used);
    }

    // ---- tables and furnaces ----

    /** The nearest block whose description id contains needle within radius (dyMax up or down) of center, or null. */
    static int[] findBlockAround(int[] c, String needle, int radius, int dyMax) {
        Minecraft mc = Minecraft.getInstance();
        int[] best = null;
        long bestD = Long.MAX_VALUE;
        BlockPos.MutableBlockPos bp = new BlockPos.MutableBlockPos();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -dyMax; dy <= dyMax; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    long d = (long) dx * dx + (long) dy * dy + (long) dz * dz;
                    if (d >= bestD) continue;
                    bp.set(c[0] + dx, c[1] + dy, c[2] + dz);
                    if (!mc.level.isLoaded(bp) || !mc.level.getBlockState(bp).getBlock().getDescriptionId().contains(needle)) continue;
                    bestD = d;
                    best = new int[]{c[0] + dx, c[1] + dy, c[2] + dz};
                }
            }
        }
        return best;
    }

    private int[] base() {
        JsonObject b = core.knowledge.places().get("base");
        return b != null && Jobs.dimOf(b).equals(Storage.dim()) ? Jobs.pos(b) : null;
    }

    /** A crafting table within 12 of the bot, else one near the base (the bridge's findTable; height counts double). */
    int[] findTable(LocalPlayer p) {
        int[] me = Jobs.here(p), t = findBlockAround(me, "crafting_table", 12, 6);
        if (t != null) return t;
        int[] b = base();
        return b != null ? findBlockAround(b, "crafting_table", 12, 6) : null;
    }

    /** The nearest furnace: near the bot, else near the base. */
    int[] findFurnace(LocalPlayer p) {
        int[] t = findBlockAround(Jobs.here(p), "block.minecraft.furnace", 12, 6);
        if (t != null) return t;
        int[] b = base();
        return b != null ? findBlockAround(b, "block.minecraft.furnace", 16, 6) : null;
    }

    // ---- craft ----

    /** A craft job to start (or splice): its steps and label, or why not; direct = crafting from the bag only. */
    record Prepared(List<Step> steps, String label, String err, boolean direct) {
        static Prepared fail(String why) { return new Prepared(null, null, why, false); }
    }

    /** The bridge's craftPlanSteps: runs of crafts become one "craft" step, a smelting step the furnace's steps. */
    String planSteps(LocalPlayer p, List<Crafter.Step> plan, String label, List<Step> out) {
        int[] furnace = null;
        boolean smelts = false;
        for (CraftJob.Segment seg : CraftJob.segments(plan)) {
            if (!seg.isSmelt()) {
                Step c = new Step("craft");
                c.crafts = seg.crafts();
                c.text = label;
                out.add(c);
                continue;
            }
            Crafter.Smelt s = seg.smelt();
            if (furnace == null) furnace = findFurnace(p);
            if (furnace == null) return CraftJob.noFurnace(s.item());
            smelts = true;
            Step start = new Step("smeltstart");
            start.id = s.item();
            out.add(start);
            out.add(Step.walk(furnace, false));
            out.add(Step.open(furnace, "yes"));
            Step ops = new Step("ops");
            ops.pos = furnace;
            Map<String, Object> fuel = op("put", s.fuel(), s.fuelCount(), List.of("fuel"));
            fuel.put("soft", true);           // a fuel slot holding another fuel already: the furnace burns that
            ops.ops = List.of(op("put", s.input(), s.n(), List.of("input")), fuel);
            ops.expect = "Furnace";
            ops.expectName = "furnace";
            out.add(ops);
            out.add(Step.close());
            Step wait = new Step("smeltwait");
            wait.ticks = CraftJob.smeltWaitTicks(s.n());
            wait.id = s.item();
            out.add(wait);
            out.addAll(collectSteps(furnace, s.item()));
            Step check = new Step("smeltcheck");
            check.id = s.item();
            check.want = s.want();
            check.pos = furnace;
            check.tries = 1;
            out.add(check);
        }
        if (smelts) {
            Step note = new Step("smeltnote");
            note.text = label;
            out.add(note);
        }
        return null;
    }

    private static Map<String, Object> op(String kind, String id, int n, List<String> roles) {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("op", kind);
        if (id != null) o.put("id", id);
        if (n > 0) o.put("n", n);
        if (roles != null) o.put("roles", roles);
        return o;
    }

    private static List<Step> collectSteps(int[] furnace, String id) {
        Step ops = new Step("ops");
        ops.pos = furnace;
        ops.ops = List.of(op("collect", id, 0, null));
        return List.of(Step.walk(furnace, false), Step.open(furnace, "yes"), ops, Step.close());
    }

    /** startCraft: counts are items wanted, not crafts; short in the bag: a trip to the chests and the network first. */
    Prepared prepareCraft(LocalPlayer p, String text, List<Step> extra) {
        Map<String, Integer> inv = Gui.inventory(p);
        CraftPlanner.Targets targets = planner.parseCraftTargets(text, inv);
        if (!targets.ok()) return Prepared.fail("error: " + targets.error());
        if (targets.list().isEmpty()) return Prepared.fail(CraftTexts.CRAFT_WHAT);
        String label = CraftPlanner.label(targets.list());
        List<Step> steps = new ArrayList<>();
        int used = 0;
        CraftPlanner.AllPlan r = planner.planAll(targets.list(), new LinkedHashMap<>(inv));
        if (r.ok()) {
            if ((extra == null || extra.isEmpty()) && !r.anySmelt()) {
                Step c = new Step("craft");
                c.crafts = crafts(r.steps());
                c.text = label;
                c.direct = true;
                return new Prepared(List.of(c), CraftJob.startStatus(r.steps()), null, true);
            }
            String err = planSteps(p, r.steps(), label, steps);
            if (err != null) return Prepared.fail("error: can't craft " + label + " - " + err);
        } else {
            List<Source> src = sources(p);
            if (src.isEmpty()) return Prepared.fail(CraftTexts.craftNoStorage(label, r.error()));
            Map<String, Integer> combined = CraftTexts.combine(inv, totals(src));
            CraftPlanner.AllPlan r2 = planner.planAll(targets.list(), new LinkedHashMap<>(combined));
            if (!r2.ok()) return Prepared.fail(CraftTexts.craftEvenWithStorage(label, r2.error(), rsKnown()));
            Trips trips = takeTrips(src, CraftTexts.fromStorage(combined, r2.counts(), inv));
            used = trips.used();
            steps.addAll(trips.steps());
            String err = planSteps(p, r2.steps(), label, steps);
            if (err != null) return Prepared.fail("error: can't craft " + label + " - " + err);
        }
        if (extra != null) steps.addAll(extra);
        return new Prepared(steps, CraftTexts.craftSeqLabel(used, label), null, false);
    }

    private static List<Crafter.Craft> crafts(List<Crafter.Step> steps) {
        List<Crafter.Craft> out = new ArrayList<>();
        for (Crafter.Step s : steps) if (s instanceof Crafter.Craft c) out.add(c);
        return out;
    }

    String craft(LocalPlayer p, String text, List<Step> extra) {
        Prepared r = prepareCraft(p, text, extra);
        if (r.err() != null) return r.err();
        boolean opens = r.steps().stream().anyMatch(s -> s.type.equals("open") || s.type.equals("craft"));
        return startSeq(r.label(), r.steps(), opens ? "always" : "fail");
    }

    String recipe(LocalPlayer p, String text) { return planner.recipeInfo(text, Gui.inventory(p)); }

    String need(LocalPlayer p, String text) {
        return CraftTexts.needReply(planner, text, Gui.inventory(p), totals(sources(p)), rsKnown());
    }

    // ---- smelt / get ----

    String smelt(LocalPlayer p, String text) {
        CraftTexts.NameCount nc = CraftTexts.parseNameCount(text, 1);
        if (nc == null) return CraftTexts.SMELT_USAGE;
        Map<String, Integer> inv = Gui.inventory(p);
        String id = planner.resolveItem(nc.name(), inv);
        if (id == null) return CraftTexts.unknownItem(nc.words());
        if (planner.smeltingRecipes(id).isEmpty()) return CraftTexts.noFurnaceRecipe(id);
        String label = nc.n() + " " + CraftPlanner.shortId(id);
        Crafter.Plan plan = planner.planSmelt(id, nc.n(), new LinkedHashMap<>(inv));
        Trips trips = new Trips(List.of(), 0);
        if (!plan.ok()) {
            List<Source> src = sources(p);
            Map<String, Integer> combined = CraftTexts.combine(inv, totals(src)), counts = new LinkedHashMap<>(combined);
            plan = planner.planSmelt(id, nc.n(), counts);
            if (!plan.ok()) return CraftTexts.cantSmelt(label, plan.error());
            trips = takeTrips(src, CraftTexts.fromStorage(combined, counts, inv));
        }
        List<Step> steps = new ArrayList<>(trips.steps());
        String err = planSteps(p, plan.steps(), label, steps);
        if (err != null) return "error: " + err;
        return startSeq(CraftTexts.smeltSeqLabel(trips.used() > 0, label), steps, "always");
    }

    String get(LocalPlayer p, String text) {
        CraftTexts.NameCount nc = CraftTexts.parseNameCount(text, CraftTexts.GET_DEFAULT);
        if (nc == null) return CraftTexts.GET_USAGE;
        String id = planner.resolveItem(nc.name(), Gui.inventory(p));
        List<Source> src = sources(p);
        if (id == null) {
            // a modded item the bot never held: look for it in the storage notes
            for (Source s : src) for (String k : s.items().keySet()) if (id == null && (GuiCore.bareId(k).equals(nc.name()) || k.equals(nc.words()))) id = k;
        }
        if (id == null) return CraftTexts.unknownItem(nc.words());
        int have = 0;
        for (Source s : src) have += s.items().getOrDefault(id, 0);
        if (have == 0) return CraftTexts.getNone(id, rsKnown());
        Map<String, Integer> need = new LinkedHashMap<>();
        need.put(id, Math.min(nc.n(), have));
        return startSeq(CraftTexts.getSeqLabel(id, nc.n(), have), takeTrips(src, need).steps(), "always");
    }

    // ---- supplies / restock / kit ----

    String supplies(LocalPlayer p, String text) {
        String t = text == null ? "" : text.trim();
        Map<String, Integer> inv = Gui.inventory(p);
        if (t.equalsIgnoreCase("clear")) {
            commands.setSupplies(Map.of());
            return CraftTexts.SUPPLIES_CLEARED;
        }
        if (t.toLowerCase().matches("^set\\b.*")) {
            String[] err = new String[1];
            Map<String, Integer> s = CraftTexts.parseSupplies(planner, t.replaceFirst("(?i)^set\\s*", ""), inv, err);
            if (s == null) return err[0];
            commands.setSupplies(s);
        }
        return CraftTexts.suppliesReply(commands.suppliesMap(), inv);
    }

    String restock(LocalPlayer p) {
        List<Source> src = sources(p);
        CraftTexts.Restock r = CraftTexts.restock(commands.suppliesMap(), Gui.inventory(p), totals(src));
        if (r.reply() != null) return r.reply();
        List<Step> steps = new ArrayList<>(takeTrips(src, new LinkedHashMap<>(r.take())).steps());
        if (!r.craftText().isEmpty()) {
            Step c = new Step("craftitem");
            c.text = r.craftText();
            c.optional = true;
            steps.add(c);
        }
        steps.add(new Step("restocknote"));
        return startSeq(r.label(), steps, "always");
    }

    String kit(LocalPlayer p, String text) {
        String m = text == null ? "" : text.trim().toLowerCase();
        if (m.isEmpty()) return CraftTexts.KIT_USAGE;
        // pieces it carries or wears count as had
        Map<String, Integer> have = new LinkedHashMap<>(Gui.inventory(p));
        for (int i = 5; i <= 8; i++) {
            ItemStack s = p.inventoryMenu.getSlot(i).getItem();
            if (!s.isEmpty()) have.merge(Gui.itemId(s), 1, Integer::sum);
        }
        CraftTexts.Kit k = CraftTexts.kit(planner, m, have);
        if (k.all().isEmpty()) return CraftTexts.kitUnknown(m);
        if (k.missing().isEmpty()) {
            String r = Gui.wearArmor(p);
            return r.startsWith("error: ") ? CraftTexts.kitComplete(m) : r;
        }
        return craft(p, String.join(", ", k.missing()), List.of(new Step("wear")));
    }

    // ---- farm / compact ----

    private FarmSpot farmSpot() {
        JsonObject f = core.knowledge.places().get("farm");
        return f == null ? null : FarmSpot.fromJson(f);
    }

    /** "farm [here | compact block|prudentium|off]". */
    String farm(LocalPlayer p, String text) {
        FarmCommand.Reply r = FarmCommand.handle(text, farmSpot(), new McFarmWorld(p));
        if (r.save() != null) commands.putPlace("farm", r.save().toJson());
        if (r.start() == null) return r.text();
        FarmCommand.Start st = r.start();
        Seq s = new Seq(jobs, storage, st.label(), toSteps(st.steps()), "always");
        s.farm = st.round();
        String res = jobs.startSeq(s, "always");
        return res + (st.note() != null ? " (" + st.note() + ")" : "");
    }

    /** "compact <item> [here | place | x y z]". */
    String compact(LocalPlayer p, String text, String from) {
        String[] w = Compact.words(text);
        if (w.length == 0) return Compact.USAGE;
        String id = planner.resolveItem(w[0], Gui.inventory(p));
        if (id == null) return Compact.unknownItem(w[0]);
        Compact.Pair pair = Compact.pair(planner, id);
        if (pair == null) return Compact.noPair(id);
        int[] center;
        String centerLabel;
        if (w[1].isEmpty() || w[1].equals("here")) {
            var pos = commands.resolvePos(Minecraft.getInstance(), "", from);
            center = new int[]{pos.x(), pos.y(), pos.z()};
            centerLabel = Jobs.fmt(center);
        } else {
            StorageRules.Spot spot = StorageRules.resolveSpot(w[1], core.knowledge.places(), Storage.dim());
            if (spot.err() != null) return Compact.placeError(spot.err());
            center = spot.pos();
            centerLabel = spot.name() != null ? spot.name() : spot.fmt();
        }
        if (findTable(p) == null && findBlockAround(center, "crafting_table", 12, 6) == null) return Compact.NO_TABLE;
        Compact.Start st = Compact.start(pair, center, centerLabel, Jobs.here(p));
        Seq s = new Seq(jobs, storage, st.label(), toSteps(st.steps()), "always");
        s.compact = st.run();
        return jobs.startSeq(s, "always");
    }

    /** The farm and compact logic's game-free steps as Seq steps. */
    static List<Step> toSteps(List<PlanStep> plan) {
        List<Step> out = new ArrayList<>();
        for (PlanStep ps : plan) {
            switch (ps.type()) {
                case "walk" -> out.add(Step.walk(ps.pos(), ps.near()));
                case "open" -> out.add(Step.open(ps.pos(), "no"));
                case "close" -> out.add(Step.close());
                case "take" -> {
                    Step ops = new Step("ops");
                    ops.pos = ps.pos();
                    ops.ops = List.of(op("take", ps.item(), ps.n(), GuiCore.TAKE_ROLES));
                    out.add(ops);
                }
                case "put" -> {
                    Step put = new Step("put");
                    put.items = new LinkedHashMap<>(ps.keep());
                    out.add(put);
                }
                case "craftitem" -> {
                    Step c = new Step("craftitem");
                    c.text = ps.item() + " " + ps.n();
                    c.optional = ps.optional();
                    out.add(c);
                }
                default -> {
                    Step s = new Step(ps.type());
                    s.pos = ps.pos();
                    s.optional = ps.again();          // compactchest: "again"
                    s.why = ps.label();
                    out.add(s);
                }
            }
        }
        return out;
    }

    // ---- the steps ----

    /** The steps this class adds to Seq; "unknown step" for anything else. */
    String step(Seq s, Step st, LocalPlayer p, long elapsed) {
        switch (st.type) {
            case "craft": return craftStep(s, st, p);
            case "craftitem": {
                // hand over to a craft plan spliced in after this step; the sequence carries on when it ends
                Prepared r = prepareCraft(p, st.text, null);
                if (r.err() != null) return st.optional ? "next" : r.err().replaceFirst("^error: ", "");
                List<Step> add = new ArrayList<>(r.steps());
                for (Step a : add) a.direct = false;
                s.splice(s.idx + 1, add);
                return "next";
            }
            case "wear":
                if (elapsed < 10) return "wait";         // let the crafting screen close first
                s.wore = Gui.wearArmor(p);
                return "next";
            case "restocknote": {
                Map<String, Integer> inv = Gui.inventory(p);
                List<String> parts = new ArrayList<>();
                commands.suppliesMap().forEach((k, v) -> parts.add(CraftPlanner.shortId(k) + " " + inv.getOrDefault(k, 0) + "/" + v));
                s.note = "supplies now: " + String.join(", ", parts);
                return "next";
            }
            case "smeltstart":
                s.smeltBase = Gui.inventory(p);
                return "next";
            case "smeltnote":
                s.note = CraftJob.smeltNote(st.text);
                return "next";
            case "smeltwait":
                s.setStatus(CraftJob.smeltWaitStatus(s.label, st.id, st.ticks, (int) elapsed));
                return elapsed >= st.ticks ? "next" : "wait";
            case "smeltcheck": {
                int have = Gui.inventory(p).getOrDefault(st.id, 0) - (s.smeltBase == null ? 0 : s.smeltBase.getOrDefault(st.id, 0));
                switch (CraftJob.smeltCheck(have, st.want, st.tries)) {
                    case DONE: return "next";
                    case GIVE_UP: return CraftJob.furnaceShort(Jobs.fmt(st.pos), have, st.want, st.id);
                    default: {
                        List<Step> add = new ArrayList<>();
                        Step wait = new Step("smeltwait");
                        wait.ticks = CraftJob.smeltRetryTicks(have, st.want);
                        wait.id = st.id;
                        add.add(wait);
                        add.addAll(collectSteps(st.pos, st.id));
                        Step again = new Step("smeltcheck");
                        again.id = st.id;
                        again.want = st.want;
                        again.pos = st.pos;
                        again.tries = st.tries + 1;
                        add.add(again);
                        s.splice(s.idx + 1, add);
                        return "next";
                    }
                }
            }
            case "farmnote":
                s.note = s.farm.noteAfterDeposit(s.note);
                return "next";
            case "compacthere":
            case "compactchest":
            case "compactdone": return compactStep(s, st, p);
            default:
                if (st.type.startsWith("farm") && s.farm != null) return farmStep(s, st, p, elapsed);
                return "unknown step " + st.type;
        }
    }

    private String farmStep(Seq s, Step st, LocalPlayer p, long elapsed) {
        McFarmWorld w = new McFarmWorld(p);
        FarmRound.Tick t = s.farm.step(st.type, w, elapsed, now());
        for (FarmRound.Effect fx : t.effects()) {
            if (fx instanceof FarmRound.Craft c) {
                Step cs = new Step("craftitem");
                cs.text = c.item() + " " + c.n();
                cs.optional = c.optional();
                s.splice(s.idx + 1, List.of(cs));
            } else if (fx instanceof FarmRound.Deposit) {
                Storage.DepositSteps dep = storage.depositSteps(p, "", true, null);
                if (dep.err() != null) {
                    s.note = FarmRound.depositFailed(s.note, dep.err());
                } else {
                    List<Step> add = new ArrayList<>(dep.steps());
                    add.add(new Step("farmnote"));
                    s.splice(s.idx + 1, add);
                }
            } else {
                if (fx instanceof FarmRound.Walk) Jobs.safeSettings();
                w.apply(fx);
            }
        }
        if (t.status() != null) s.setStatus(t.status());
        if (t.note() != null) s.note = t.note();
        if (!t.result().equals("wait")) Minecraft.getInstance().options.keyShift.setDown(false);
        return t.result();
    }

    private String compactStep(Seq s, Step st, LocalPlayer p) {
        Compact.Result r;
        switch (st.type) {
            case "compacthere": {
                List<int[]> found = new ArrayList<>();
                for (Storage.Found f : Storage.findContainers(st.pos, Compact.COMPACT_R)) if (Storage.isStorageBlock(f.pos())) found.add(f.pos());
                r = s.compact.here(found, Jobs.here(p), st.why);
                break;
            }
            case "compactchest": {
                int have = 0;
                if (Gui.open(p)) have = GuiCore.contents(new McMenu(p), GuiCore.TAKE_ROLES, GuiCore.roles(new McMenu(p))).getOrDefault(s.compact.pair.item(), 0);
                r = s.compact.chest(st.pos, st.optional, Gui.open(p), have, new McFarmWorld(p).bag());
                break;
            }
            default:
                r = s.compact.done(s.putLeft);
        }
        if (!r.splice().isEmpty()) s.splice(s.idx + 1, toSteps(r.splice()));
        if (r.label() != null) s.label = r.label();
        if (r.note() != null) s.note = r.note();
        return r.result();
    }

    /** After a reflex held the job: a craft step looks at its menu again (a fight may have closed the table). */
    static void afterHold(Step st) {
        if (st.state instanceof CraftRun c && !"opentable".equals(c.stage)) {
            c.stage = null;
            if (c.loop != null) c.loop.interrupted();      // a batch in flight is still counted (never dropped)
        }
        Minecraft.getInstance().options.keyShift.setDown(false);
    }

    // ---- the crafting grid (the bridge's stepCraft, with exact clicks instead of EMI's fill) ----

    /** A craft step's progress: which craft, how many made, its stage. */
    static final class CraftRun {
        int ci, made;
        String stage;
        long stageTick;
        int[] table;
        boolean triedTable;
        String lastError;
        /** Package G: the batch loop of the current recipe (kept through a fight; replaced for another grid once it is idle). */
        GridLoop loop;
    }

    private static boolean craftingMenu(AbstractContainerMenu m) {
        return m instanceof CraftingMenu || m.getClass().getSimpleName().contains("Crafting");
    }

    /** The grid's size in the open menu: 3 (a table), 2 (the inventory), 0 (something else is open). */
    private static int gridSize(LocalPlayer p) {
        if (p.containerMenu == p.inventoryMenu) return 2;
        return craftingMenu(p.containerMenu) ? 3 : 0;
    }

    /** Shift-clicks everything out of the grid back into the inventory. */
    private static void clearGrid(McMenu m, int size) {
        for (int i = 1; i <= size * size; i++) if (m.id(i) != null) m.click(i, 0, "QUICK_MOVE");
    }

    private String craftFail(Seq s, Step st, String msg) {
        LocalPlayer p = Minecraft.getInstance().player;
        if (p != null && gridSize(p) > 0) clearGrid(new McMenu(p), gridSize(p));
        if (st.direct) {
            jobs.finish(msg);
            return "wait";
        }
        return msg.replaceFirst("^(error|partial): ", "");
    }

    private String craftStep(Seq s, Step st, LocalPlayer p) {
        CraftRun c = st.state instanceof CraftRun cr ? cr : null;
        if (c == null) {
            c = new CraftRun();
            st.state = c;
            s.setStatus(CraftJob.startStatus(st.crafts));
        }
        Crafter.Craft step = st.crafts.get(c.ci);
        RecipeData recipe = planner.recipe(step.recipeId());
        if (recipe == null) return craftFail(s, st, "error: I don't know the recipe " + step.recipeId() + " any more");
        if ("opentable".equals(c.stage)) {
            // walked to the table (the spliced walk step): right-click it and wait for its menu
            if (c.stageTick < 0) {
                if (Gui.open(p) || Minecraft.getInstance().screen != null) Gui.close(p);
                String r = Jobs.useBlock(p, Jobs.fmt(c.table));
                if (!r.startsWith("ok")) return craftFail(s, st, CraftJob.couldNotOpenTable(r));
                c.stageTick = now();
                return "wait";
            }
            if (gridSize(p) == 3) {
                c.stage = null;
                return "wait";
            }
            return now() - c.stageTick > 60 ? craftFail(s, st, CraftJob.TABLE_DID_NOT_OPEN) : "wait";
        }
        int size = gridSize(p);
        // package G: a batch that was crafted is counted to the end, whatever menu a fight or a meal left open
        if (c.loop != null && c.loop.settling()) {
            String r = loopStep(s, st, p, c, step, size);
            if (r != null) return r;
            if (c.loop != null && c.loop.settling()) return "wait";
        }
        // fill: the right grid first
        if (size == 0) {
            Gui.close(p);                          // a fight closed the table (or something else is open)
            return "wait";
        }
        if ((step.needsTable() || !recipe.fits(2)) && size == 2) {
            if (c.triedTable && c.table == null) return craftFail(s, st, CraftJob.noTable(step.item()));
            int[] t = c.table != null ? c.table : findTable(p);
            if (t == null) return craftFail(s, st, CraftJob.noTable(step.item()));
            c.triedTable = true;
            c.table = t;
            c.stage = "opentable";
            c.stageTick = -1;
            if (p.getEyePosition().distanceTo(new net.minecraft.world.phys.Vec3(t[0] + 0.5, t[1] + 0.5, t[2] + 0.5)) > 4.4) {
                Step walk = Step.walk(t, false);
                walk.why = "the crafting table at " + Jobs.fmt(t);
                s.splice(s.idx, List.of(walk));
                s.stage = null;
                s.stepStart = now();
            }
            return "wait";
        }
        // package G: a whole batch per fill, on ticks (GridLoop; Visual Workbench leftovers are cleared first). A loop
        // for another grid size is replaced only once it has nothing in flight (settling is handled above).
        if (c.loop == null || c.loop.size() != size) c.loop = new GridLoop(recipe, size, step.want(), c.made);
        String r = loopStep(s, st, p, c, step, size);
        return r != null ? r : "wait";
    }

    /** One GridLoop step and what it means for the craft step: "wait"/"next"/an error, or null = carry on this tick. */
    private String loopStep(Seq s, Step st, LocalPlayer p, CraftRun c, Crafter.Craft step, int size) {
        int madeBefore = c.made;
        GridLoop.Out o = c.loop.tick(new McMenu(p), size, ITEMS, now());
        c.made = c.loop.made();
        if (c.made != madeBefore) s.setStatus(CraftJob.progress(step.item(), c.made, step.want(), c.ci, st.crafts.size()));
        switch (o.state()) {
            case WAIT:
                return c.loop.settling() ? "wait" : null;
            case FAIL:
                c.loop = null;
                if (o.why() == GridLoop.Why.NO_RESULT) return craftFail(s, st, CraftJob.gridDidNotMake(c.ci, c.made, step.item()));
                if (o.why() == GridLoop.Why.FULL) return craftFail(s, st, CraftJob.failPrefix(c.ci, c.made) + o.error() + " (made " + c.made + " of " + step.want() + " " + CraftPlanner.shortId(step.item()) + ")");
                c.lastError = o.error();
                return craftFail(s, st, CraftJob.couldNotCraft(c.ci, c.made, step.item(), c.lastError));
            default:
                break;
        }
        // this recipe is done: the next one, or the end
        c.loop = null;
        c.ci++;
        c.made = 0;
        if (c.ci < st.crafts.size()) return "wait";
        if (gridSize(p) == 3) Gui.close(p);
        if (st.direct) {
            jobs.finish(CraftJob.done(st.text));
            return "wait";
        }
        return "next";
    }

    /** Stack sizes and crafting remainders from the item registry (64 / none when the id is unknown). */
    static final GridLoop.Items ITEMS = new GridLoop.Items() {
        @Override
        public int maxStack(String id) {
            try {
                int n = stackOf(id).getMaxStackSize();
                return n > 0 ? n : 64;
            } catch (RuntimeException e) {
                return 64;
            }
        }

        @Override
        public boolean remainder(String id) {
            try {
                return stackOf(id).hasCraftingRemainingItem();
            } catch (RuntimeException e) {
                return false;
            }
        }
    };

    private static net.minecraft.world.item.ItemStack stackOf(String id) {
        return new net.minecraft.world.item.ItemStack(net.minecraft.core.registries.BuiltInRegistries.ITEM.get(net.minecraft.resources.ResourceLocation.parse(id)));
    }
}
