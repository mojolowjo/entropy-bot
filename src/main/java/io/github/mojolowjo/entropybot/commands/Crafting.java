package io.github.mojolowjo.entropybot.commands;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.mojolowjo.entropybot.Core;
import io.github.mojolowjo.entropybot.commands.Seq.Step;
import io.github.mojolowjo.entropybot.craft.CraftJob;
import io.github.mojolowjo.entropybot.craft.CraftPlanner;
import io.github.mojolowjo.entropybot.craft.CraftTexts;
import io.github.mojolowjo.entropybot.craft.Crafter;
import io.github.mojolowjo.entropybot.craft.FurnaceJobs;
import io.github.mojolowjo.entropybot.craft.FurnacePlan;
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
    /** Package E: infuse and upgrade (Mystical Agriculture). */
    final Mystical mystical;

    Crafting(Core core, Commands commands, Jobs jobs, Storage storage) {
        this.core = core;
        this.commands = commands;
        this.jobs = jobs;
        this.storage = storage;
        this.mystical = new Mystical(this, core, commands, jobs, storage);
    }

    /** Package E: "infuse &lt;seed&gt; [n]" on the infusion altar. */
    String infuse(LocalPlayer p, String text) { return mystical.infuse(p, text); }

    /** Package E: "upgrade &lt;essence&gt; [n]", the essence tiers in bag-sized rounds. */
    String upgrade(LocalPlayer p, String text) { return mystical.upgrade(p, text); }

    private long now() { return core.tick(); }

    private String startSeq(String label, List<Step> steps, String close) {
        return jobs.startSeq(new Seq(jobs, storage, label, steps, close), close);
    }

    // ---- storage the craft trips take from (the bridge's storageSources / takeTripSteps) ----

    /** A place to take from: a chest, the RS network, or (package D) a remembered furnace job's output still to collect. */
    record Source(boolean rs, int[] pos, Map<String, Integer> items, FurnaceJobs.Job furnace) {
        Source(boolean rs, int[] pos, Map<String, Integer> items) {
            this(rs, pos, items, null);
        }
    }

    /** The chests and the RS network, then the furnaces' output still to collect (package D: after a restart a craft takes it from there). */
    List<Source> sources(LocalPlayer p) {
        List<Source> out = storageSources(p);
        for (FurnaceJobs.Job j : furnaces().all()) {
            if (!j.dim.equals(Storage.dim()) || j.remaining() <= 0) continue;
            Map<String, Integer> items = new LinkedHashMap<>();
            items.put(j.item, j.remaining());
            out.add(new Source(false, j.pos, items, j));
        }
        return out;
    }

    /** The trusted chests near the base (or the bot), then the RS network as its last reading has it. */
    List<Source> storageSources(LocalPlayer p) {
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
            if (s.furnace() != null) {
                // package D: a furnace job's output - the collect step walks there, waits for it if needed, takes it
                for (Map<String, Object> t : take) {
                    Step c = new Step("smeltcollect");
                    c.jobId = s.furnace().id;
                    c.n = (Integer) t.get("n");
                    steps.add(c);
                }
                continue;
            }
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
        return findBlockAround(c, needle, radius, dyMax, null);
    }

    /**
     * P2: crafting tables that answered a click without opening (in a Visual Workbench pack a plain
     * {@code minecraft:crafting_table} set by a command has no block entity and never opens; the real ones are
     * {@code visualworkbench:...}). Key: "x y z|block id", so a re-placed table counts again. Session memory.
     */
    static final java.util.Set<String> DEAD_TABLES = java.util.concurrent.ConcurrentHashMap.newKeySet();

    static String tableKey(int[] t) {
        Minecraft mc = Minecraft.getInstance();
        String id = mc.level == null ? "?" : String.valueOf(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(
                mc.level.getBlockState(new BlockPos(t[0], t[1], t[2])).getBlock()));
        return Jobs.fmt(t) + "|" + id;
    }

    static int[] findBlockAround(int[] c, String needle, int radius, int dyMax, java.util.function.Predicate<int[]> skip) {
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
                    int[] at = new int[]{c[0] + dx, c[1] + dy, c[2] + dz};
                    if (skip != null && skip.test(at)) continue;
                    bestD = d;
                    best = at;
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
        java.util.function.Predicate<int[]> dead = at -> !DEAD_TABLES.isEmpty() && DEAD_TABLES.contains(tableKey(at));
        int[] me = Jobs.here(p), t = findBlockAround(me, "crafting_table", 12, 6, dead);
        if (t != null) return t;
        int[] b = base();
        int[] bt = b != null ? findBlockAround(b, "crafting_table", 12, 6, dead) : null;
        // S1 (D2 review): the strip mine's own table (commands.json's mine note) counts too, when it is still there
        // (loaded and a crafting table) and nearer than the base's (the bridge's "a table near it, the mine's, or the base's")
        int[] mt = mineTable();
        if (mt != null && (bt == null || Jobs.distSq(me, mt) < Jobs.distSq(me, bt))) return mt;
        return bt;
    }

    /** S1: the strip mine's table if it is in this dimension, not untrusted, loaded and still a crafting table, else null. */
    private int[] mineTable() {
        try {
            int[] t = StripMine.get().mineTable();
            if (t == null || untrusted(t) || DEAD_TABLES.contains(tableKey(t))) return null;
            JsonObject mine = core.knowledge.places().get("mine");
            String mdim = mine != null && mine.has("dim") ? mine.get("dim").getAsString() : null;
            if (mdim != null && !mdim.equals(Storage.dim())) return null;
            Minecraft mc = Minecraft.getInstance();
            BlockPos bp = new BlockPos(t[0], t[1], t[2]);
            if (mc.level == null || !mc.level.isLoaded(bp) || !mc.level.getBlockState(bp).getBlock().getDescriptionId().contains("crafting_table")) return null;
            return t;
        } catch (RuntimeException e) {
            return null;
        }
    }

    static final String FURNACE = "block.minecraft.furnace";

    /** The nearest furnace: near the bot, else near the base. */
    int[] findFurnace(LocalPlayer p) {
        int[] t = findBlockAround(Jobs.here(p), FURNACE, 12, 6);
        if (t != null) return t;
        int[] b = base();
        return b != null ? findBlockAround(b, FURNACE, 16, 6) : null;
    }

    /**
     * Package D: every furnace within 12 of the bot and 16 of the base (loaded chunks), nearest to the bot first; never
     * one the owner untrusted. {@code baseOnly}: only those within 16 of the base (a job left running is left there).
     */
    List<int[]> findFurnaces(LocalPlayer p, boolean baseOnly) {
        int[] me = Jobs.here(p);
        int[] b = base();
        List<int[]> out = new ArrayList<>();
        for (int[] f : findBlocksAround(me, FURNACE, 12, 6)) if (!baseOnly || (b != null && nearBase(f, b))) out.add(f);
        if (b != null) {
            for (int[] f : findBlocksAround(b, FURNACE, 16, 6)) {
                boolean dup = false;
                for (int[] o : out) dup |= o[0] == f[0] && o[1] == f[1] && o[2] == f[2];
                if (!dup) out.add(f);
            }
        }
        out.removeIf(f -> untrusted(f));
        out.sort(java.util.Comparator.comparingLong(f -> dist2(f, me)));
        return out;
    }

    /** Within 16 of the base on each axis (the base's furnaces). */
    static boolean nearBase(int[] f, int[] b) {
        return Math.abs(f[0] - b[0]) <= 16 && Math.abs(f[1] - b[1]) <= 16 && Math.abs(f[2] - b[2]) <= 16;
    }

    /** The owner said "untrust x y z" for this block: the bot never uses it. */
    private boolean untrusted(int[] f) {
        JsonObject c = core.knowledge.chests().get(Jobs.fmt(f));
        return c != null && c.has("trusted") && !c.get("trusted").getAsBoolean();
    }

    private static long dist2(int[] a, int[] b) {
        long dx = a[0] - b[0], dy = a[1] - b[1], dz = a[2] - b[2];
        return dx * dx + dy * dy + dz * dz;
    }

    /** Every block whose description id contains needle within radius (dyMax up or down) of center. */
    static List<int[]> findBlocksAround(int[] c, String needle, int radius, int dyMax) {
        Minecraft mc = Minecraft.getInstance();
        List<int[]> out = new ArrayList<>();
        BlockPos.MutableBlockPos bp = new BlockPos.MutableBlockPos();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -dyMax; dy <= dyMax; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    bp.set(c[0] + dx, c[1] + dy, c[2] + dz);
                    if (!mc.level.isLoaded(bp) || !mc.level.getBlockState(bp).getBlock().getDescriptionId().equals(needle)) continue;
                    out.add(new int[]{c[0] + dx, c[1] + dy, c[2] + dz});
                }
            }
        }
        return out;
    }

    /** Is there (still) a furnace at pos? null = can't tell (the chunk isn't loaded). */
    static Boolean furnaceAt(int[] pos) {
        Minecraft mc = Minecraft.getInstance();
        BlockPos bp = new BlockPos(pos[0], pos[1], pos[2]);
        if (mc.level == null || !mc.level.isLoaded(bp)) return null;
        return mc.level.getBlockState(bp).getBlock().getDescriptionId().equals(FURNACE);
    }

    // ---- package D: the remembered furnace jobs ----

    private FurnaceJobs furnaceJobs;
    private JsonObject furnaceRoot;
    /** When this session's commands were loaded (a job's 30 minutes past due count from here at the earliest). */
    final long sessionStart = System.currentTimeMillis();

    /** The furnace jobs, kept in commands.json ("furnaces"). */
    FurnaceJobs furnaces() {
        JsonObject root = commands.brainData();
        if (furnaceJobs == null || root != furnaceRoot) {
            furnaceRoot = root;
            furnaceJobs = new FurnaceJobs(root, commands::saved);
        }
        return furnaceJobs;
    }

    static final String MODE_EFFICIENT = "efficient", MODE_WAIT = "wait";

    /** "efficient" (the default: furnaces run while the bot does other things) or "wait" (the old way). */
    String smeltMode() {
        JsonObject b = commands.brainData();
        return b.has("smeltMode") && MODE_WAIT.equals(b.get("smeltMode").getAsString()) ? MODE_WAIT : MODE_EFFICIENT;
    }

    private static long nowMs() { return System.currentTimeMillis(); }

    private static void issue(Seq s, String text) {
        s.furnaceIssue = s.furnaceIssue == null ? text : s.furnaceIssue + "; " + text;
        addNote(s, text);
    }

    private static void addNote(Seq s, String text) {
        if (text == null || text.isEmpty()) return;
        s.note = s.note == null || s.note.isEmpty() ? text : s.note + "; " + text;
    }

    // ---- craft ----

    /** A craft job to start (or splice): its steps and label, or why not; direct = crafting from the bag only. */
    record Prepared(List<Step> steps, String label, String err, boolean direct) {
        static Prepared fail(String why) { return new Prepared(null, null, why, false); }
    }

    /** The bridge's craftPlanSteps: runs of crafts become one "craft" step, a smelting step the furnace's steps. */
    String planSteps(LocalPlayer p, List<Crafter.Step> plan, String label, List<Step> out) {
        return planSteps(p, plan, label, out, true, FurnaceJobs.CRAFT, MODE_EFFICIENT.equals(smeltMode()));
    }

    /**
     * Package D: the plan as Seq steps in {@link FurnacePlan}'s order. A furnace step is "smeltpick" (a free furnace,
     * never a busy one: walk, open, check, put, remember the job) and "smeltcollect" before the step that uses its
     * output (efficient) or right after it (wait). {@code collectTargets}: the asked-for furnace output is collected
     * at the end (a craft); else the job ends once the furnace runs and the pickup collects it when due (the smelt verb).
     */
    String planSteps(LocalPlayer p, List<Crafter.Step> plan, String label, List<Step> out, boolean collectTargets, String kind, boolean efficient) {
        // a job is only ever left running in a furnace at the base: no base marked -> stand by the furnace (wait mode)
        if (efficient && base() == null) {
            efficient = false;
            collectTargets = true;
        }
        boolean smelts = false;
        for (Crafter.Step s : plan) {
            if (s instanceof Crafter.Smelt sm) {
                smelts = true;
                if (efficient ? findFurnaces(p, true).isEmpty() : findFurnace(p) == null) return CraftJob.noFurnace(sm.item());
            }
        }
        for (FurnacePlan.Action a : FurnacePlan.order(plan, planner::recipe, efficient, collectTargets)) {
            if (a instanceof FurnacePlan.CraftRun r) {
                Step c = new Step("craft");
                c.crafts = r.crafts();
                c.text = label;
                out.add(c);
            } else if (a instanceof FurnacePlan.Start st) {
                Step s = new Step("smeltpick");
                s.smelt = st.smelt();
                s.smeltKey = st.key();
                s.kind = kind;
                s.text = label;
                s.near = efficient;                 // only a furnace at the base
                out.add(s);
            } else if (a instanceof FurnacePlan.Collect c) {
                Step s = new Step("smeltcollect");
                s.smelt = c.smelt();
                s.smeltKey = c.key();
                s.want = c.need();
                s.all = c.all();
                out.add(s);
            }
        }
        if (smelts && collectTargets) {
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

    /** What the bag holds of {@code item} (the plan's counts were made from it). */
    private static int bag(LocalPlayer p, String item) {
        return Gui.inventory(p).getOrDefault(item, 0);
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
        // package D: the lower-tier check sees the chests and the RS network on the bag-only pass too
        List<Source> src = sources(p);
        Map<String, Integer> combined = CraftTexts.combine(inv, totals(src));
        CraftPlanner.AllPlan r = planner.planAll(targets.list(), new LinkedHashMap<>(inv), combined);
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
            if (src.isEmpty()) return Prepared.fail(CraftTexts.craftNoStorage(label, r.error()));
            CraftPlanner.AllPlan r2 = planner.planAll(targets.list(), new LinkedHashMap<>(combined), combined);
            if (!r2.ok()) return Prepared.fail(CraftTexts.craftEvenWithStorage(label, r2.error(), rsKnown()));
            Trips trips = takeTrips(src, CraftTexts.fromStorage(combined, r2.counts(), inv, r2.catalysts()));
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

    /** The "smelt" sub-verbs that answer at once (package D): jobs, mode, forget. */
    static boolean smeltInstant(String rest) {
        return SmeltTexts.instant(rest);
    }

    String smelt(LocalPlayer p, String text) {
        String t = text == null ? "" : text.trim().toLowerCase(java.util.Locale.ROOT);
        // package D: the remembered furnace jobs
        if (t.equals("jobs") || t.equals("status") || t.equals("list")) return furnaces().list(nowMs());
        if (t.equals("mode") || t.startsWith("mode ")) return smeltModeCommand(t.substring(4).trim());
        if (t.startsWith("forget")) return SmeltTexts.forget(furnaces(), t.substring(6).trim());
        if (t.equals("collect") || t.startsWith("collect ")) return collect(p, t.substring(7).trim());
        CraftTexts.NameCount nc = CraftTexts.parseNameCount(text, 1);
        if (nc == null) return CraftTexts.SMELT_USAGE;
        Map<String, Integer> inv = Gui.inventory(p);
        String id = planner.resolveItem(nc.name(), inv);
        if (id == null) return CraftTexts.unknownItem(nc.words());
        if (planner.smeltingRecipes(id).isEmpty()) return CraftTexts.noFurnaceRecipe(id);
        String label = nc.n() + " " + CraftPlanner.shortId(id);
        List<Source> src = storageSources(p);
        Map<String, Integer> combined = CraftTexts.combine(inv, totals(src));
        Crafter.Plan plan = planner.planSmelt(id, nc.n(), new LinkedHashMap<>(inv), combined);
        Trips trips = new Trips(List.of(), 0);
        if (!plan.ok()) {
            Map<String, Integer> counts = new LinkedHashMap<>(combined);
            plan = planner.planSmelt(id, nc.n(), counts, combined);
            if (!plan.ok()) return CraftTexts.cantSmelt(label, plan.error());
            trips = takeTrips(src, CraftTexts.fromStorage(combined, counts, inv));
        }
        List<Step> steps = new ArrayList<>(trips.steps());
        // efficient (the default): the job ends once the furnace runs, the pickup collects it when it is due
        boolean efficient = MODE_EFFICIENT.equals(smeltMode());
        String err = planSteps(p, plan.steps(), label, steps, !efficient, FurnaceJobs.SMELT, efficient);
        if (err != null) return "error: " + err;
        // a job left running (efficient, a base marked) ends "ok: done starting the furnace for ...", not "done smelting"
        String seqLabel = efficient && base() != null ? SmeltTexts.startLabel(trips.used() > 0, label) : CraftTexts.smeltSeqLabel(trips.used() > 0, label);
        return startSeq(seqLabel, steps, "always");
    }

    /** "smelt mode [efficient|wait]". */
    String smeltModeCommand(String arg) {
        if (arg.isEmpty()) return SmeltTexts.modeReply(smeltMode());
        String m = arg.startsWith("eff") || arg.equals("on") ? MODE_EFFICIENT : arg.startsWith("wait") || arg.equals("off") ? MODE_WAIT : null;
        if (m == null) return SmeltTexts.MODE_USAGE;
        commands.brainData().addProperty("smeltMode", m);
        commands.saved();
        return "ok: " + SmeltTexts.modeReply(m);
    }

    /** "smelt collect [all]": walks to the furnaces whose output is due (all: every one, waiting where needed) and takes it. */
    String collect(LocalPlayer p, String arg) {
        FurnaceJobs fj = furnaces();
        long now = nowMs();
        boolean all = arg.equals("all"), auto = arg.equals(SmeltTexts.AUTO);
        List<FurnaceJobs.Job> js = new ArrayList<>();
        List<String> fenced = new ArrayList<>();
        // the automatic pickup (idle, between a chain's steps) only takes what is due and not stalled; by hand stalled
        // jobs are tried again
        for (FurnaceJobs.Job j : auto ? fj.due(now, Storage.dim()) : fj.all()) {
            if (!j.dim.equals(Storage.dim()) || !(all || j.due(now))) continue;
            // the fence before any walk or /home
            String why = jobs.goalAllowed(j.pos[0], j.pos[1], j.pos[2]);
            if (why != null) {
                fenced.add(j.where() + ": " + why);
                continue;
            }
            js.add(j);
        }
        if (js.isEmpty()) {
            if (!fenced.isEmpty()) {
                fj.later(fj.due(now, Storage.dim()), now);
                return "error: I can't go to " + String.join("; ", fenced);
            }
            return "ok: nothing to collect yet - " + fj.list(now);
        }
        if (!auto) fj.unstall(js);
        fj.later(js, now);                 // a pickup that fails is not tried again at once
        List<Step> steps = new ArrayList<>();
        List<String> what = new ArrayList<>();
        for (FurnaceJobs.Job j : js) {
            Step c = new Step("smeltcollect");
            c.jobId = j.id;
            c.all = true;
            c.pickup = true;
            steps.add(c);
            what.add(j.remaining() + " " + CraftPlanner.shortId(j.item));
            if (FurnaceJobs.CRAFT.equals(j.kind)) {
                // a craft's leftover nobody waits for any more: put away after the pickup
                Step store = new Step("smeltstore");
                store.id = j.item;
                store.jobId = j.id;
                steps.add(store);
            }
        }
        return startSeq("collecting " + String.join(", ", what) + " from " + js.size() + " furnace" + (js.size() > 1 ? "s" : ""), steps, "always");
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
            case "smeltpick": return smeltPick(s, st, p);
            case "smeltput": return smeltPut(s, st, p);
            case "smeltreg": return smeltReg(s, st);
            case "smeltcollect": return smeltCollect(s, st, p);
            case "smeltopen": return smeltOpen(s, st, p);
            case "smelttake": return smeltTake(s, st, p);
            case "smeltstore": {
                // only what this pickup took, never the bot's own stock of the item
                int got = s.pickupTook.getOrDefault(st.jobId, 0);
                if (got <= 0 || bag(p, st.id) <= 0) return "next";
                Storage.DepositSteps dep = storage.depositSteps(p, st.id, true, null);
                if (dep.err() != null) {
                    addNote(s, "kept the " + CraftPlanner.shortId(st.id) + " (" + dep.err().replaceFirst("^error: ", "") + ")");
                    return "next";
                }
                int keep = Math.max(0, bag(p, st.id) - got);
                for (Step d : dep.steps()) {
                    if (!d.type.equals("put") || d.items == null) continue;
                    Map<String, Integer> only = new LinkedHashMap<>();
                    only.put(st.id, keep);
                    d.items = only;
                }
                s.splice(s.idx + 1, dep.steps());
                return "next";
            }
            case "smeltcleared":
                furnaces().clearLeftover(st.pos, Storage.dim());
                return "next";
            case "smeltstart":
                s.smeltBase = Gui.inventory(p);
                return "next";
            case "smeltnote":
                s.note = CraftJob.smeltNote(st.text) + (s.furnaceIssue != null ? "; " + s.furnaceIssue : "");
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
            // package E: Mystical Agriculture
            case "upgraderound": return mystical.upgradeRound(s, st, p);
            case "altarfind": return mystical.altarFind(s, st, p);
            case "infuse": return mystical.infuseStep(s, st, p);
            default:
                if (st.type.startsWith("farm") && s.farm != null) return farmStep(s, st, p, elapsed);
                return "unknown step " + st.type;
        }
    }

    // ---- package D: the furnace steps (never a busy furnace; the job remembered; collected when it is needed) ----

    /** smeltpick's state: furnaces tried this round and why they were busy, the busy rounds, the wait. */
    static final class PickState {
        final java.util.Set<String> tried = new java.util.LinkedHashSet<>();
        final List<String> busy = new ArrayList<>();
        /** Furnaces whose job of ours was collected to free them (never again in this pick: a stalled one would loop). */
        final java.util.Set<String> freed = new java.util.HashSet<>();
        int rounds;
        long waitUntil = -1;
    }

    /** A free furnace for the smelt: the nearest one with no job of ours that the bot hasn't found busy; splices walk/open/smeltput. */
    private String smeltPick(Seq s, Step st, LocalPlayer p) {
        PickState ps = st.state instanceof PickState x ? x : new PickState();
        st.state = ps;
        if (ps.waitUntil >= 0) {
            if (now() < ps.waitUntil) {
                s.setStatus(s.label + " - every furnace is busy, looking again in " + Math.max(1, (ps.waitUntil - now()) / 20) + " s");
                return "wait";
            }
            ps.waitUntil = -1;
            ps.tried.clear();
        }
        FurnaceJobs fj = furnaces();
        if (fj.full()) return FurnaceJobs.fullRefusal();
        List<int[]> cands = findFurnaces(p, st.near);
        if (cands.isEmpty()) return CraftJob.noFurnace(st.smelt.item());
        int[] pick = null;
        for (int[] f : cands) {
            if (!ps.tried.contains(Jobs.fmt(f)) && fj.at(f, Storage.dim()) == null) {
                pick = f;
                break;
            }
        }
        if (pick == null) {
            // one of ours is in the way (this job's, or one that is done): collect it, then that furnace is free again
            for (int[] f : cands) {
                FurnaceJobs.Job mine = fj.at(f, Storage.dim());
                if (mine == null || mine.stalled || ps.freed.contains(Jobs.fmt(f)) || !(s.smeltJobs.containsValue(mine.id) || mine.due(nowMs()))) continue;
                ps.freed.add(Jobs.fmt(f));
                Step c = new Step("smeltcollect");
                c.jobId = mine.id;
                c.all = true;
                c.pickup = !s.smeltJobs.containsValue(mine.id);
                ps.tried.clear();
                s.splice(s.idx, List.of(c));
                s.stage = null;
                return "wait";
            }
            if (++ps.rounds > FurnaceJobs.BUSY_ROUNDS) return SmeltTexts.allBusy(cands.size(), ps.busy);
            ps.waitUntil = now() + FurnaceJobs.BUSY_WAIT_TICKS;
            commands.log("furnaces busy (" + String.join("; ", ps.busy) + "): looking again in a minute");
            return "wait";
        }
        ps.tried.add(Jobs.fmt(pick));
        Step walk = Step.walk(pick, false);
        walk.why = "the furnace at " + Jobs.fmt(pick);
        Step put = new Step("smeltput");
        put.pos = pick;
        put.smelt = st.smelt;
        put.smeltKey = st.smeltKey;
        put.kind = st.kind;
        put.text = st.text;
        put.redo = st.redo;
        put.state = st;                    // the pick step, to try again with its tried list
        s.splice(s.idx + 1, List.of(walk, Step.open(pick, "yes"), put));
        return "next";
    }

    /** The furnace's three slots in the open menu. */
    static FurnaceJobs.Slots furnaceSlots(LocalPlayer p) {
        McMenu m = new McMenu(p);
        Map<String, List<Integer>> roles = GuiCore.roles(m);
        String[] in = firstStack(m, roles.get("input")), fu = firstStack(m, roles.get("fuel")), out = firstStack(m, roles.get("output"));
        return new FurnaceJobs.Slots(in[0], Integer.parseInt(in[1]), fu[0], Integer.parseInt(fu[1]), out[0], Integer.parseInt(out[1]));
    }

    private static String[] firstStack(McMenu m, List<Integer> slots) {
        if (slots != null) for (int i : slots) if (m.id(i) != null) return new String[]{m.id(i), String.valueOf(m.count(i))};
        return new String[]{null, "0"};
    }

    private static boolean furnaceMenu(LocalPlayer p) {
        return java.util.regex.Pattern.compile("Furnace", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(Gui.menuName(p)).find();
    }

    /** At the open furnace: busy (someone's smelting, or output nobody took) -> close it and pick another; else put input and fuel in. */
    private String smeltPut(Seq s, Step st, LocalPlayer p) {
        if (!Gui.open(p)) return "no container open";
        if (now() - s.stepStart < 4) return "wait";            // let its slots arrive
        String bad = Gui.wrongScreen(p);
        if (bad != null) return bad;
        if (!furnaceMenu(p)) return "the block at " + Jobs.fmt(st.pos) + " opened a " + Gui.menuName(p).replaceFirst("Menu$", "") + ", not a furnace";
        FurnaceJobs.Leftover lo = furnaces().leftover(st.pos, Storage.dim());
        FurnaceJobs.PutCheck chk = FurnaceJobs.check(furnaceSlots(p), st.smelt.fuel(), lo);
        String busy = chk.put() == FurnaceJobs.Put.BUSY ? chk.why() : null;
        Step pick = (Step) st.state;
        if (chk.put() == FurnaceJobs.Put.CLEAR_FIRST) {
            // our own leftover from an earlier job: take it out, then look again
            Step clear = new Step("ops");
            clear.pos = st.pos;
            clear.ops = List.of(op("collect", lo.item(), 0, null));
            Step cleared = new Step("smeltcleared");
            cleared.pos = st.pos;
            addNote(s, "took my old " + CraftPlanner.shortId(lo.item()) + " out of the furnace at " + Jobs.fmt(st.pos));
            s.splice(s.idx + 1, List.of(clear, cleared, st));
            return "next";
        }
        if (busy != null) {
            // never cleared, never added to: someone else's (or a forgotten) smelt
            if (pick.state instanceof PickState ps) ps.busy.add(Jobs.fmt(st.pos) + ": " + busy);
            commands.log("the furnace at " + Jobs.fmt(st.pos) + " is busy (" + busy + "), trying another");
            s.splice(s.idx + 1, List.of(Step.close(), pick));
            return "next";
        }
        if (furnaces().full()) return FurnaceJobs.fullRefusal();
        Step ops = new Step("ops");
        ops.pos = st.pos;
        // the fuel slot is empty or holds our fuel (else it was busy): top it up to the plan's count
        Map<String, Object> fuel = op("topup", st.smelt.fuel(), 0, List.of("fuel"));
        fuel.put("to", st.smelt.fuelCount());
        ops.ops = List.of(op("put", st.smelt.input(), st.smelt.n(), List.of("input")), fuel);
        ops.expect = "Furnace";
        ops.expectName = "furnace";
        Step reg = new Step("smeltreg");
        reg.pos = st.pos;
        reg.smelt = st.smelt;
        reg.smeltKey = st.smeltKey;
        reg.kind = st.kind;
        reg.text = st.text;
        reg.redo = st.redo;
        s.splice(s.idx + 1, List.of(ops, reg, Step.close()));
        return "next";
    }

    /** The input is in: remember the job (persisted), so a collect, a pickup or a craft after a restart finds it. */
    private String smeltReg(Seq s, Step st) {
        FurnaceJobs.Job j = furnaces().add(st.pos, Storage.dim(), st.smelt, st.kind, st.text, nowMs());
        if (j == null) return "I put " + st.smelt.n() + " " + CraftPlanner.shortId(st.smelt.input()) + " in the furnace at " + Jobs.fmt(st.pos)
                + " but couldn't remember it (" + FurnaceJobs.fullRefusal() + ")";
        if (st.redo) {
            j.redone = true;
            commands.saved();
        }
        s.smeltJobs.put(st.smeltKey, j.id);
        commands.log("furnace job #" + j.id + ": " + FurnaceJobs.startedNote(j, nowMs()));
        addNote(s, FurnaceJobs.startedNote(j, nowMs()));
        return "next";
    }

    /** smeltcollect's job: by id (a trip, a pickup) or by the plan's key. */
    private FurnaceJobs.Job jobOf(Seq s, Step st) {
        int id = st.jobId > 0 ? st.jobId : s.smeltJobs.getOrDefault(st.smeltKey, -1);
        return id > 0 ? furnaces().get(id) : null;
    }

    /** How many to collect now: everything left (all), a trip's amount, or what the next step needs beyond the bag. */
    private static int collectTarget(Step st, FurnaceJobs.Job j, int bagHas) {
        if (st.all) return j.remaining();
        if (st.n != null) return Math.min(st.n, j.remaining());
        return Math.min(j.remaining(), Math.max(0, st.want - bagHas));
    }

    /** Collect a furnace job's output: nothing to do when the bag already has what the next step needs, else walk there. */
    private String smeltCollect(Seq s, Step st, LocalPlayer p) {
        FurnaceJobs.Job j = jobOf(s, st);
        String item = j != null ? j.item : st.smelt != null ? st.smelt.item() : null;
        if (j == null) {
            int id = st.jobId > 0 ? st.jobId : s.smeltJobs.getOrDefault(st.smeltKey, -1);
            if (s.doneJobs.contains(id)) return "next";       // this job collected it all already (to free the furnace)
            if (item != null && !st.all && st.n == null && bag(p, item) >= st.want) return "next";
            return lost(s, st, p, null, "I no longer remember that furnace job (\"smelt forget\", or 30 min past due)");
        }
        int target = collectTarget(st, j, bag(p, j.item));
        if (target <= 0) return "next";                   // early: the bag has enough, the rest keeps smelting
        Step walk = Step.walk(j.pos, false);
        walk.why = "the furnace at " + j.where();
        Step open = new Step("smeltopen");
        open.jobId = j.id;
        open.n = target;
        open.pickup = st.pickup;
        open.want = st.want;
        open.all = st.all;
        open.smelt = st.smelt;
        s.splice(s.idx + 1, List.of(walk, open));
        if (!j.due(nowMs())) s.setStatus(s.label + " - " + FurnaceJobs.waitStatus(j, nowMs()));
        return "next";
    }

    /** Next to the furnace: still there? Then open it and take as it comes. */
    private String smeltOpen(Seq s, Step st, LocalPlayer p) {
        FurnaceJobs.Job j = furnaces().get(st.jobId);
        if (j == null) return lost(s, st, p, null, "I no longer remember that furnace job");
        if (Boolean.FALSE.equals(furnaceAt(j.pos))) return lost(s, st, p, j, "the furnace at " + j.where() + " is gone");
        Step take = new Step("smelttake");
        take.jobId = j.id;
        take.n = st.n;
        take.pickup = st.pickup;
        take.smelt = st.smelt;
        take.want = st.want;
        take.pos = j.pos;
        s.splice(s.idx + 1, List.of(Step.open(j.pos, "yes"), take, Step.close()));
        return "next";
    }

    /** smelttake's state: taken so far, the last time more came out, a take waiting for the server. */
    static final class TakeState {
        int took, before = -1, asked, lastInput = -1;
        long lastProgress = System.currentTimeMillis(), settleTick, lookTick = -100, reopenAt = -1;
    }

    /** The open furnace: take our output as it comes until the target is reached; wait, or say it is gone or stopped. */
    private String smeltTake(Seq s, Step st, LocalPlayer p) {
        FurnaceJobs fj = furnaces();
        FurnaceJobs.Job j = fj.get(st.jobId);
        if (j == null) {
            if (s.doneJobs.contains(st.jobId) || st.pickup) return "next";      // collected in full (this or another visit)
            // "smelt forget" while waiting here: the owner took it off my list, so the step that needs it can't go on
            return "furnace job #" + st.jobId + " was forgotten (smelt forget) while I waited for it at the furnace";
        }
        TakeState ts = st.state instanceof TakeState x ? x : new TakeState();
        st.state = ts;
        if (ts.before >= 0) {
            // a take is on its way: count what really arrived
            if (now() - ts.settleTick < Storage.GUI_SETTLE) return "wait";
            int got = Math.max(0, bag(p, j.item) - ts.before);
            ts.before = -1;
            if (got > 0) {
                if (st.pickup) s.pickupTook.merge(j.id, got, Integer::sum);
                ts.took += got;
                ts.lastProgress = nowMs();
                fj.collected(j, got);
                if (fj.get(j.id) == null) {
                    s.doneJobs.add(j.id);
                    if (st.pickup) addNote(s, "got " + j.want + " " + CraftPlanner.shortId(j.item) + " from the furnace at " + j.where());
                    return "next";
                }
            } else if (ts.asked > 0) {
                if (st.pickup) {
                    // a full bag: say so once, try again in half an hour (not every 5 minutes)
                    fj.later(List.of(j), nowMs(), 30 * 60_000L);
                    addNote(s, "my inventory is full - left " + j.remaining() + " " + CraftPlanner.shortId(j.item) + " in the furnace at " + j.where());
                    return "next";
                }
                return Hints.next("my inventory is full (took " + ts.took + " of " + st.n + " " + CraftPlanner.shortId(j.item) + " from the furnace at " + j.where() + ")",
                        "deposit, then smelt collect");
            }
        }
        if (!Gui.open(p)) {
            // waiting with the furnace closed (the bot can eat meanwhile): open it again when it is time to look
            if (ts.reopenAt < 0) return "no container open";
            if (now() < ts.reopenAt) {
                s.setStatus(s.label + " - " + FurnaceJobs.waitStatus(j, nowMs()));
                return "wait";
            }
            ts.reopenAt = -1;
            s.splice(s.idx, List.of(Step.open(j.pos, "yes")));
            s.stage = null;
            return "wait";
        }
        if (now() - ts.lookTick < 10) return "wait";            // look twice a second
        ts.lookTick = now();
        if (!furnaceMenu(p)) return "the block at " + j.where() + " opened a " + Gui.menuName(p).replaceFirst("Menu$", "") + ", not a furnace";
        FurnaceJobs.Slots sl = furnaceSlots(p);
        FurnaceJobs.Collect c = FurnaceJobs.collect(sl, j, ts.took, st.n == null ? j.remaining() : st.n, nowMs(), ts.lastProgress, ts.lastInput);
        ts.lastInput = sl.input() != null && sl.input().equals(j.input) ? sl.inputN() : 0;
        switch (c.next()) {
            case DONE:
                if (st.pickup || ts.took > 0) addNote(s, "got " + ts.took + " " + CraftPlanner.shortId(j.item) + " from the furnace at " + j.where()
                        + (j.remaining() > 0 && fj.get(j.id) != null ? " (" + j.remaining() + " still smelting)" : ""));
                return "next";
            case TAKE: {
                McMenu m = new McMenu(p);
                ts.before = bag(p, j.item);
                ts.asked = c.take();
                ts.settleTick = now();
                GuiCore.take(m, j.item, c.take(), List.of("output"), GuiCore.roles(m));
                return "wait";
            }
            case WAIT:
                // close it while waiting (an open menu blocks eating), look again in 5-30 s
                s.setStatus(s.label + " - " + FurnaceJobs.waitStatus(j, nowMs()));
                ts.reopenAt = now() + FurnaceJobs.lookAgainTicks(j.dueAt - nowMs());
                Gui.close(p);
                return "wait";
            case STALLED:
                fj.markStalled(j);                                // never retried by itself (a manual "smelt collect" does)
                if (st.pickup) {
                    // a pickup leaves it remembered and says so (tried again later)
                    addNote(s, "the furnace at " + j.where() + ": " + c.why() + " - got " + ts.took + " " + CraftPlanner.shortId(j.item));
                    return "next";
                }
                return "the furnace at " + j.where() + ": " + c.why() + " - got " + ts.took + " of " + st.n + " " + CraftPlanner.shortId(j.item);
            default:
                return lost(s, st, p, j, "the furnace at " + j.where() + ": " + c.why());
        }
    }

    /**
     * The output is gone (taken, the furnace broken or replaced, the job forgotten). A pickup just says so. A plan
     * looks in the chests and the RS network (someone may have put the items away) and takes them from there, else
     * smelts them again once (from what it carries or storage holds), else ends with why.
     */
    private String lost(Seq s, Step st, LocalPlayer p, FurnaceJobs.Job j, String why) {
        String item = j != null ? j.item : st.smelt != null ? st.smelt.item() : null;
        int left = j != null ? Math.min(j.remaining(), st.n != null ? st.n : j.remaining()) : st.smelt != null ? st.smelt.want() : 0;
        if (j != null) furnaces().remove(j);
        commands.log("furnace: " + why);
        if (st.pickup || item == null || left <= 0) {
            addNote(s, why + (item != null ? SmeltTexts.storageHint(item, totals(storageSources(p))) : ""));
            return "next";
        }
        if (!st.all && st.n == null) left = Math.max(0, st.want - bag(p, item));
        if (left <= 0) return "next";
        List<Source> src = storageSources(p);
        int have = totals(src).getOrDefault(item, 0);
        List<Step> add = new ArrayList<>();
        if (Gui.open(p)) add.add(Step.close());
        if (have >= left) {
            Map<String, Integer> need = new LinkedHashMap<>();
            need.put(item, left);
            add.addAll(takeTrips(src, need).steps());
            issue(s, why + " - took " + left + " " + CraftPlanner.shortId(item) + " from storage instead (someone put them away?)");
            s.splice(s.idx + 1, add);
            return "next";
        }
        if (j == null || !j.redone) {
            Map<String, Integer> inv = Gui.inventory(p);
            Map<String, Integer> combined = CraftTexts.combine(inv, totals(src)), counts = new LinkedHashMap<>(combined);
            Crafter.Plan again = planner.planSmelt(item, left, counts);
            if (again.ok()) {
                add.addAll(takeTrips(src, CraftTexts.fromStorage(combined, counts, inv)).steps());
                List<Step> redo = new ArrayList<>();
                String err = planSteps(p, again.steps(), CraftPlanner.shortId(item), redo, true, FurnaceJobs.CRAFT, false);
                if (err == null) {
                    for (Step r : redo) {
                        if (r.type.equals("smeltpick")) r.redo = true;
                        if (r.smeltKey >= 0) r.smeltKey += 1000 * (s.idx + 1);      // its own keys, apart from the plan's
                    }
                    redo.removeIf(r -> r.type.equals("smeltnote"));
                    add.addAll(redo);
                    issue(s, why + " - smelting " + left + " " + CraftPlanner.shortId(item) + " again");
                    s.splice(s.idx + 1, add);
                    return "next";
                }
            }
        }
        return why + " - and " + left + " " + CraftPlanner.shortId(item) + " are neither in my chests nor can I smelt them again";
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
        // package E: the altar looks again (what it placed is found again), unless it is crafting or taking the seed
        if (st.state instanceof io.github.mojolowjo.entropybot.altar.AltarRun r) r.interrupted();
        Minecraft.getInstance().options.keyShift.setDown(false);
    }

    // ---- the crafting grid (the bridge's stepCraft, with exact clicks instead of EMI's fill) ----

    /** A craft step's progress: which craft, how many made, its stage. */
    static final class CraftRun {
        int ci, made;
        String stage;
        long stageTick;
        int[] table;
        boolean triedTable, deadRetried;
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
            if (now() - c.stageTick <= 60) return "wait";
            // P2: this table answers clicks but never opens (see DEAD_TABLES): skip it from now on, try another once
            DEAD_TABLES.add(tableKey(c.table));
            int[] other = c.deadRetried ? null : findTable(p);
            if (other == null) return craftFail(s, st, CraftJob.tableDidNotOpen(Jobs.fmt(c.table), tableKey(c.table)));
            c.deadRetried = true;
            c.table = other;
            c.stageTick = -1;
            if (p.getEyePosition().distanceTo(new net.minecraft.world.phys.Vec3(other[0] + 0.5, other[1] + 0.5, other[2] + 0.5)) > 4.4) {
                Step walk = Step.walk(other, false);
                walk.why = "the crafting table at " + Jobs.fmt(other);
                s.splice(s.idx, List.of(walk));
                s.stage = null;
                s.stepStart = now();
            }
            return "wait";
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

        /** Package E: the crafting remainder is the item itself (the infusion crystal): one in its cell serves a whole batch. */
        @Override
        public boolean catalyst(String id) {
            try {
                ItemStack st = stackOf(id);
                if (st.isEmpty() || !st.hasCraftingRemainingItem()) return false;
                ItemStack rem = st.getCraftingRemainingItem();
                return rem != null && !rem.isEmpty() && rem.getItem() == st.getItem();
            } catch (RuntimeException e) {
                return false;
            }
        }
    };

    private static net.minecraft.world.item.ItemStack stackOf(String id) {
        return new net.minecraft.world.item.ItemStack(net.minecraft.core.registries.BuiltInRegistries.ITEM.get(net.minecraft.resources.ResourceLocation.parse(id)));
    }
}
