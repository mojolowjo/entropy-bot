package io.github.mojolowjo.entropybot.commands;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.pathing.goals.GoalNear;
import baritone.api.pathing.goals.GoalXZ;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import io.github.mojolowjo.entropybot.Core;
import io.github.mojolowjo.entropybot.cave.CaveRules;
import io.github.mojolowjo.entropybot.cave.CaveSearch;
import io.github.mojolowjo.entropybot.cave.Caves;
import io.github.mojolowjo.entropybot.cave.ExploreRules;
import io.github.mojolowjo.entropybot.cave.LevelWorld;
import io.github.mojolowjo.entropybot.cave.MineGrammar;
import io.github.mojolowjo.entropybot.cave.MineNotes;
import io.github.mojolowjo.entropybot.cave.MineRules;
import io.github.mojolowjo.entropybot.cave.OreSpec;
import io.github.mojolowjo.entropybot.cave.OresList;
import io.github.mojolowjo.entropybot.clear.ClearJob;
import io.github.mojolowjo.entropybot.clear.Pos;
import io.github.mojolowjo.entropybot.engine.Hotbar;
import io.github.mojolowjo.entropybot.guard.Box;
import io.github.mojolowjo.entropybot.gui.Gui;
import io.github.mojolowjo.entropybot.storage.StorageRules;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.common.Tags;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.Function;

/**
 * B7d (D3): the mining verbs in the mod - "mine &lt;block&gt; [n] [dig]" (Baritone's mine with the bridge's rules: the
 * areas, the protect-box distance, the pickaxe in hand, new land 3 times, no-progress stops, the 20-minute limit, a fight
 * holds it and it walks back), "mine cave &lt;ores&gt; [n] [&lt;min&gt;m] [at &lt;cave&gt;]" (the cave search, the lighter, one vein
 * at a time by a clear step, the frontier, moves when there is no cave), "explore [minutes]" and "ores [name|clear]".
 * Faithful ports of the bridge's startMine/stepMineJob, startCave/caveStep, startExplore/exploreStep and oresCommand
 * (same wording); the decisions live in the game-free {@code cave/} classes. The jobs are {@link Seq} jobs whose own
 * steps (cave*, explore, mineore*) Seq hands to {@link CaveSteps}; a job's run state is kept here per Seq.
 */
final class Mining {
    private static final Logger LOG = LogUtils.getLogger();
    private static Mining instance;

    final Core core;
    final Commands commands;
    final Jobs jobs;
    final Storage storage;
    final Crafting crafting;
    /** The running jobs' state (one Seq each; gone with the Seq). */
    private final Map<Seq, Object> runs = new WeakHashMap<>();
    private List<String> oreIds;

    private Mining(Core core) {
        this.core = core;
        this.commands = core.commands;
        this.jobs = commands.jobs;
        this.storage = commands.storage;
        this.crafting = commands.crafting;
    }

    static synchronized Mining get() {
        if (instance == null) instance = new Mining(Core.INSTANCE);
        return instance;
    }

    private long now() { return core.tick(); }

    private static String dim() { return Storage.dim(); }

    private MineNotes notes() { return core.mineNotes; }

    /** Every registered ore block id (the bridge's oreIds; cached). */
    List<String> oreIds() {
        if (oreIds == null) {
            List<String> all = new ArrayList<>();
            for (ResourceLocation rl : BuiltInRegistries.BLOCK.keySet()) all.add(rl.toString());
            oreIds = OreSpec.oreIds(all);
        }
        return oreIds;
    }

    // ---- the policy as boxes ----

    /** S1 (fail closed): the policy, or {@link MineRules.BadPolicy} when it can't be read; the callers refuse or stop. */
    private JsonObject policy() {
        return MineRules.parsePolicy(commands.policyJson());
    }

    /** S1 (fail closed): a malformed box throws {@link MineRules.BadPolicy} instead of being skipped. */
    private static List<Box> boxes(JsonObject pol, String key) {
        return MineRules.policyBoxes(pol, key, PolicyCommands.DEFAULT_DIM);
    }

    /** S1: the protect boxes, for the cave's targets. */
    private List<Box> protectBoxes() { return boxes(policy(), "protect"); }

    private static List<String> areaNames(JsonObject pol) {
        List<String> out = new ArrayList<>();
        for (Box b : boxes(pol, "areas")) out.add(b.name != null ? b.name : "box");
        return out;
    }

    private static boolean strict(JsonObject pol) {
        try { return pol.has("strict") && pol.get("strict").getAsBoolean(); } catch (RuntimeException e) { return false; }
    }

    private ExploreRules.Land land(String dim) {
        return new ExploreRules.Land() {
            @Override public boolean explored(String key) { return notes().explored(key); }
            @Override public boolean inside(int x, int z) { return commands.inAreas(dim, x, z); }
            @Override public boolean refused(int x, int y, int z) { return jobs.goalAllowed(x, y, z) != null; }
            @Override public void markRefused(String key) { notes().markExplored(List.of(key)); }
        };
    }

    private int[] basePos() {
        JsonObject b = core.knowledge.places().get("base");
        return b != null && Jobs.dimOf(b).equals(dim()) ? Jobs.pos(b) : null;
    }

    /** The base is known here but the bot is out of its reach (more than 64 across): the base chests count too. */
    private boolean farFromBase(LocalPlayer p) {
        int[] b = basePos(), me = Jobs.here(p);
        return b != null && (Math.abs(b[0] - me[0]) > 64 || Math.abs(b[2] - me[2]) > 64);
    }

    private static BlockPos bp(int[] p) { return new BlockPos(p[0], p[1], p[2]); }

    /** Splices steps in front of the running one (they run first, then it again). */
    private void spliceHere(Seq s, List<Seq.Step> add) {
        s.splice(s.idx, add);
        s.stepStart = now();
        s.stage = null;
    }

    /** Slots that are free or hold only junk a dump would put away (the bridge's bagRoom). */
    int bagRoom(LocalPlayer p) {
        return io.github.mojolowjo.entropybot.storage.BagRoom.count(Storage.slots(p), storage.keeps());   // B7e: one count for all
    }

    /** Does the bot carry a pickaxe that gets the block's drops? (A stone one can't mine deepslate diamond.) */
    static boolean canMineAt(LocalPlayer p, int x, int y, int z) {
        BlockState st = p.level().getBlockState(new BlockPos(x, y, z));
        if (!st.requiresCorrectToolForDrops()) return true;
        for (int i = 0; i < 36; i++) {
            ItemStack s = p.getInventory().getItem(i);
            if (!s.isEmpty() && Gui.itemId(s).endsWith("_pickaxe") && s.isCorrectToolForDrops(st)) return true;
        }
        return false;
    }

    /** The pickaxe tier a block needs from its needs_*_tool tag ("stone" when in none: any pickaxe will do). */
    static String toolNeed(BlockState st) {
        if (st.is(BlockTags.NEEDS_DIAMOND_TOOL)) return "diamond";
        if (st.is(BlockTags.NEEDS_IRON_TOOL)) return "iron";
        return "stone";
    }

    /** Breaking off, the break list empty, placing off (the bridge's restoreSafeSettings, Baritone's part). */
    static void restoreSettings() {
        try {
            var s = BaritoneAPI.getSettings();
            s.allowBreak.value = false;
            s.allowPlace.value = false;
            s.allowBreakAnyway.value = new ArrayList<>();
        } catch (Throwable ignored) {}
    }

    // ==== the verbs ====

    /**
     * "mine ..." (the bridge's mineCommand): the grammar, "mine cave", the Baritone mine; "mine strip" goes to the strip
     * mine (D2) through {@code strip} (null: not in the mod yet).
     */
    String mine(LocalPlayer p, String rest, Function<MineGrammar.Parsed, String> strip) {
        MineGrammar.Parsed m = MineGrammar.parse(rest, commands.orePrefer(), oreIds());
        switch (m.kind()) {
            case GRAMMAR:
            case ERROR: return m.text();
            case STRIP: return strip != null ? strip.apply(m) : "error: the strip mine isn't in the mod yet";
            default: break;
        }
        // S1 (D3 review): a job of another kind runs: refuse before anything is set up (the hooks would land on that job)
        String busy = busyText();
        if (busy != null) return busy;
        try {
            return m.kind() == MineGrammar.Kind.CAVE ? startCave(p, m) : startMine(p, m.text());
        } catch (MineRules.BadPolicy e) {
            return "error: " + MineRules.badPolicyText(e);
        }
    }

    /** S1: the cmd.json dispatcher's busy answer when a job (not a mere walk, which a new job replaces) runs; else null. */
    private String busyText() {
        return jobs.running() && !jobs.walking() ? "error: busy with \"" + jobs.job.status + "\" - send stop first" : null;
    }

    /** "ores [name]" / "ores clear" ("ores prefer ..." stays with the bridge until B7e: the dispatcher forwards it). */
    String ores(LocalPlayer p, String rest) {
        String t = rest == null ? "" : rest.trim().toLowerCase();
        if (t.equals("clear") || t.equals("forget")) {
            notes().clearOres();
            return OresList.CLEARED;
        }
        int[] me = Jobs.here(p);
        Level level = p.level();
        OresList.Answer a = OresList.list(notes().ores(), dim(), me[0], me[1], me[2], t, pos -> {
            BlockPos q = new BlockPos(pos[0], pos[1], pos[2]);
            return level.isLoaded(q) && !level.getBlockState(q).is(Tags.Blocks.ORES);
        });
        for (String k : a.gone()) notes().forget(k);
        return a.text();
    }

    /** The clear engine's ore book in this dimension (for D1's Clearing: the ores a clear leaves are listed here). */
    io.github.mojolowjo.entropybot.clear.OreBook oreBook() { return notes().book(dim()); }

    /** Seq hands the cave, explore and mineore steps here (through CaveSteps). */
    String step(Seq s, Seq.Step st, LocalPlayer p, long elapsed) {
        switch (st.type) {
            case "cavestep":
            case "caveend":
            case "cavexz":
            case "caverestart": return caveStep(s, st, p, elapsed);
            case "explore": return exploreStep(s, p);
            case "mineore": return mineStep(s, st, p);
            case "mineoretool": return toolGetStep(s, st, p);
            case "mineorecheck": return toolCheckStep(s, st, p);
            default: return "unknown step " + st.type;
        }
    }

    // ==== explore ====

    static final class ExRun {
        long until, start, targetAt;
        int chunks;
        final Set<Integer> poiIds = new HashSet<>();
        ExploreRules.Target target;
    }

    /** "explore [minutes]": unvisited land inside the areas, then home; the report names the new points of interest. */
    String explore(LocalPlayer p, String rest) {
        int minutes = ExploreRules.minutes(rest);
        String busy = busyText();
        if (busy != null) return busy;
        try {
            if (boxes(policy(), "areas").isEmpty()) return ExploreRules.NO_AREAS + PolicyCommands.AREA_HINT;
        } catch (MineRules.BadPolicy e) {
            return "error: " + MineRules.badPolicyText(e).replace("I won't mine", "I won't explore");
        }
        ExRun ex = new ExRun();
        ex.until = now() + minutes * 1200L;
        ex.start = now();
        for (ExploreRules.Poi q : pois()) ex.poiIds.add(q.id());
        Seq s = new Seq(jobs, storage, "exploring for " + minutes + " min", List.of(new Seq.Step("explore")), "always");
        runs.put(s, ex);
        String r = jobs.startSeq(s, "always");
        jobs.job.holdOnFight = true;              // a fight holds the walk (the mod fights), it doesn't end it
        return r;
    }

    private List<ExploreRules.Poi> pois() {
        List<ExploreRules.Poi> out = new ArrayList<>();
        try {
            for (JsonElement e : core.pois.toJson().getAsJsonArray("pois")) {
                JsonObject o = e.getAsJsonObject();
                out.add(new ExploreRules.Poi(o.get("id").getAsInt(), o.get("kind").getAsString(), o.get("x").getAsInt(), o.get("y").getAsInt(), o.get("z").getAsInt()));
            }
        } catch (RuntimeException ignored) {}
        return out;
    }

    private String exploreStep(Seq s, LocalPlayer p) {
        ExRun ex = runs.get(s) instanceof ExRun x ? x : null;
        if (ex == null) return "the explore run is gone";
        IBaritone b = Jobs.baritone();
        int[] me = Jobs.here(p);
        String d = dim();
        if (now() % 20 == 0 && notes().markExplored(ExploreRules.around(d, me[0], me[2])) > 0) ex.chunks++;
        String done = now() > ex.until ? "the time is up" : null;
        if (done == null && ex.target != null && (now() - ex.targetAt < 20 || (b != null && !Jobs.idle(b) && now() - ex.targetAt < ExploreRules.WALK_TICKS))) {
            s.setStatus(s.label + " - walking to " + ex.target.x() + " " + ex.target.z() + " (" + ex.chunks + " chunks so far)");
            return "wait";
        }
        // didn't get there (water, cliffs): give that chunk up
        if (ex.target != null && !ExploreRules.arrived(me[0], me[2], ex.target)) notes().markExplored(List.of(ExploreRules.key(d, ex.target.x() >> 4, ex.target.z() >> 4)));
        ex.target = done != null ? null : ExploreRules.target(d, me[0], me[1], me[2], land(d), null);
        if (ex.target == null) {
            List<ExploreRules.Poi> fresh = new ArrayList<>();
            for (ExploreRules.Poi q : pois()) if (!ex.poiIds.contains(q.id())) fresh.add(q);
            s.note = ExploreRules.note(ex.chunks, now() - ex.start, done != null ? done : "nothing left to explore in reach", fresh);
            int[] base = basePos();
            if (base != null) s.splice(s.idx + 1, List.of(Seq.Step.walk(base, true)));
            notes().flush();
            return "next";
        }
        if (b != null) {
            Jobs.safeSettings();
            b.getCustomGoalProcess().setGoalAndPath(new GoalXZ(ex.target.x(), ex.target.z()));
        }
        ex.targetAt = now();
        return "wait";
    }

    // ==== mine cave ====

    static final class CaveRun {
        String name, own;
        int[] entrance;
        OreSpec spec;
        Set<String> ids;
        int target, missed, torches, steps, moves, visitStart, pendingTorch = -1;
        long until, start, walkAt;
        int[] walk;
        boolean noTorchTried;
        String lastTorchSpot;
        Seq.Step pendingClear;
        final Map<String, Integer> tried = new HashMap<>(), tally = new LinkedHashMap<>();
        final Set<String> tooHard = new LinkedHashSet<>(), skip = new HashSet<>();
        final List<String> noCaveAt = new ArrayList<>();

        int got() { return spec.count(tally); }
    }

    String startCave(LocalPlayer p, MineGrammar.Parsed m) {
        if (bagRoom(p) <= CaveRules.FREE) return "error: my bag is nearly full - \"deposit\" first";
        protectBoxes();                           // S1: fail closed - a policy it can't read refuses here (BadPolicy, caught in mine())
        int[] me = Jobs.here(p);
        Caves.Cave c = core.caves.pick(m.at() == null ? "" : m.at(), dim(), me[0], me[1], me[2], System.currentTimeMillis(), now());
        if (c == null) return "error: I know no cave called " + m.at() + " (\"caves\" lists them)";
        int minutes = m.minutes() > 0 ? m.minutes() : CaveRules.MINUTES;
        CaveRun cv = new CaveRun();
        cv.name = c.name;
        cv.entrance = new int[]{c.ex, c.ey, c.ez};
        cv.spec = m.spec();
        cv.ids = m.spec().ids();
        cv.target = m.n();
        cv.until = now() + minutes * 1200L;
        cv.start = now();
        cv.own = c.visited.isEmpty() ? c.name : null;
        Seq s = new Seq(jobs, storage, CaveRules.label(c.name, m.spec(), m.n(), minutes), List.of(new Seq.Step("cavestep")), "always");
        runs.put(s, cv);
        String r = jobs.startSeq(s, "always");
        jobs.job.holdOnFight = true;              // monsters live in caves: a fight holds the caving, it doesn't end it
        return r;
    }

    private void visit(CaveRun cv, int x, int y, int z) {
        Caves.Cave c = core.caves.get(cv.name);
        if (c != null) core.caves.visit(c, x, y, z, System.currentTimeMillis(), now());
    }

    private boolean caveEmpty(CaveRun cv) { return cv.got() - cv.visitStart == 0; }

    /** S1 (fail closed): a policy that can't be read mid-caving ends the caving the usual way (out, report, home). */
    private String caveStep(Seq s, Seq.Step st, LocalPlayer p, long elapsed) {
        try {
            return caveStepChecked(s, st, p, elapsed);
        } catch (MineRules.BadPolicy e) {
            CaveRun cv = runs.get(s) instanceof CaveRun x ? x : null;
            if (cv == null || st.type.equals("caveend")) return MineRules.badPolicyText(e);
            return caveFinish(s, cv, MineRules.badPolicyText(e), false, false);
        }
    }

    private String caveStepChecked(Seq s, Seq.Step st, LocalPlayer p, long elapsed) {
        CaveRun cv = runs.get(s) instanceof CaveRun x ? x : null;
        if (cv == null) return "the cave run is gone";
        switch (st.type) {
            case "caveend": {
                Caves.Cave c = core.caves.get(cv.name);
                if (c != null) core.caves.finish(c, st.redo ? !cv.name.equals(cv.own) : !st.all, System.currentTimeMillis(), now());
                s.note = CaveRules.endNote(cv.spec, cv.got(), cv.name, st.why, st.all, st.redo, cv.torches, cv.tooHard, cv.noCaveAt);
                return "next";
            }
            case "cavexz": return caveXzStep(s, st, p, cv, elapsed);
            case "caverestart": return caveRestart(s, st, p, cv);
            default: break;
        }
        int[] me = Jobs.here(p);
        // what the steps spliced last time did: the vein clear's ores, the torch
        if (cv.pendingClear != null) {
            Clearing.Outcome o = cv.pendingClear.cleared;
            if (o != null && o.mined() != null) o.mined().forEach((k, v) -> cv.tally.merge(k, v, Integer::sum));
            cv.pendingClear = null;
        }
        if (cv.pendingTorch >= 0) {
            if (Gui.inventory(p).getOrDefault("minecraft:torch", 0) < cv.pendingTorch) cv.torches++;
            cv.pendingTorch = -1;
        }
        IBaritone b = Jobs.baritone();
        // walking to a frontier: until Baritone is done (or 60 s)
        if (cv.walk != null) {
            if (now() - cv.walkAt < 20) return "wait";
            if (b != null && !Jobs.idle(b) && now() - cv.walkAt < CaveRules.WALK_TICKS) {
                s.setStatus(s.label + " - walking to " + Jobs.fmt(cv.walk) + " (" + cv.got() + " ores so far)");
                return "wait";
            }
            if (Jobs.distSq(me, cv.walk) > 9) {
                // didn't get there: that spot counts as explored, so the next search looks elsewhere
                visit(cv, cv.walk[0], cv.walk[1], cv.walk[2]);
                cv.missed++;
            } else {
                cv.missed = 0;
            }
            cv.walk = null;
        }
        String why = CaveRules.stopReason(cv.spec, cv.got(), cv.target, now(), cv.until, bagRoom(p), p.getHealth(), p.getFoodData().getFoodLevel(), Gui.inventory(p));
        if (why == null && cv.missed >= CaveRules.MISSES) {
            why = "I couldn't reach the next dark spot 4 times";
            if (caveEmpty(cv)) return caveMove(s, cv, p, why);          // wave 1, item 2: no cave here, so somewhere else
        }
        if (why != null) return caveFinish(s, cv, why, false, false);
        // light the spot: monsters spawn at block light 0 (once per spot: a torch that won't go there is not tried again)
        Level level = p.level();
        BlockPos feet = bp(me);
        // S1: the protect boxes (the base): no target inside one or within the mine verb's margin; no torch inside one
        String d = dim();
        List<Box> prot = protectBoxes();
        MineRules.Near feetNear = CaveRules.offLimits(prot, d, me[0], me[1], me[2]);
        boolean feetInside = feetNear != null && feetNear.gap() == 0;
        if (!feetInside && CaveRules.needsTorch(level.getBrightness(LightLayer.BLOCK, feet), level.getBrightness(LightLayer.SKY, feet))) {
            int torches = Gui.inventory(p).getOrDefault("minecraft:torch", 0);
            if (torches == 0) {
                if (!cv.noTorchTried) {
                    cv.noTorchTried = true;
                    Seq.Step c = new Seq.Step("craftitem");
                    c.text = "torch 8";
                    c.optional = true;
                    spliceHere(s, List.of(c));
                    return "wait";
                }
                return caveFinish(s, cv, "I am out of torches in the dark", false, false);
            }
            String spot = Jobs.fmt(me);
            if (!spot.equals(cv.lastTorchSpot)) {
                cv.lastTorchSpot = spot;
                cv.pendingTorch = torches;
                Seq.Step t = Clearing.placeStep(me.clone(), "minecraft:torch");
                t.optional = true;                    // the bridge carried on when a torch wouldn't go there
                spliceHere(s, List.of(t));
                return "wait";
            }
        }
        Caves.Cave c = core.caves.get(cv.name);
        if (c == null) return "the cave search failed: no cave called " + cv.name;
        core.caves.visit(c, me[0], me[1], me[2], System.currentTimeMillis(), now());
        CaveSearch.OffLimits off = (x, y, z) -> CaveRules.offLimits(prot, d, x, y, z) != null;
        CaveSearch.Result r = CaveSearch.search(new LevelWorld(level), me[0], me[1], me[2], c.visited, cv.ids::contains, c.ex, c.ey, c.ez, CaveRules.FROM_ENTRANCE, off);
        cv.steps++;
        // ores first: one vein at a time
        List<CaveSearch.Ore> vein = CaveRules.pickVein(r.ores(), cv.tried,
                o -> jobs.goalAllowed(o.x(), o.y(), o.z()) != null || off.test(o.x(), o.y(), o.z()),
                o -> canMineAt(p, o.x(), o.y(), o.z()), cv.tooHard);
        if (!vein.isEmpty()) {
            List<Pos> cells = new ArrayList<>();
            for (CaveSearch.Ore o : vein) cells.add(new Pos(o.x(), o.y(), o.z()));
            Seq.Step clear = Clearing.clearStep(new ClearJob.Options().only(cells).collect(true).soft(true)
                    .label("mining " + cells.size() + " ore blocks in " + cv.name));
            cv.pendingClear = clear;
            spliceHere(s, List.of(clear));
            return "wait";
        }
        if (r.frontier() == null) {
            // S1: a cave in (or by) a protect box with nothing outside it: no cave here, closed for good, move on
            MineRules.Near en = CaveRules.offLimits(prot, d, c.ex, c.ey, c.ez);
            if (caveEmpty(cv) && en != null) return caveMove(s, cv, p, CaveRules.protectedCaveText(cv.name, en), true);
            if (caveEmpty(cv)) return caveMove(s, cv, p, "no dark corner in reach");
            return caveFinish(s, cv, "no dark corner left in reach", true, false);
        }
        CaveSearch.Cell f = r.frontier();
        if (jobs.goalAllowed(f.x(), f.y(), f.z()) != null || off.test(f.x(), f.y(), f.z())) {
            visit(cv, f.x(), f.y(), f.z());
            return "wait";
        }
        cv.walk = new int[]{f.x(), f.y(), f.z()};
        cv.walkAt = now();
        if (b != null) {
            Jobs.safeSettings();
            b.getCustomGoalProcess().setGoalAndPath(new GoalNear(bp(cv.walk), 1));
        }
        return "wait";
    }

    /** The known caves (caves.json) as the move sees them. */
    private List<CaveRules.Known> knownCaves() {
        List<CaveRules.Known> out = new ArrayList<>();
        for (Map.Entry<String, JsonElement> e : core.caves.toJson(false).getAsJsonObject("caves").entrySet()) {
            JsonObject v = e.getValue().getAsJsonObject(), en = v.getAsJsonObject("entrance");
            out.add(new CaveRules.Known(e.getKey(), v.has("dim") ? v.get("dim").getAsString() : null, en.get("x").getAsInt(), en.get("y").getAsInt(),
                    en.get("z").getAsInt(), v.get("frontierLeft").getAsBoolean()));
        }
        return out;
    }

    /**
     * Wave 1, item 2: no cave here (nothing on this visit, and no way on): the cave is closed for good when this job made
     * it, and the bot moves to the nearest known cave with frontier left, else to unexplored land; MOVES times.
     */
    private String caveMove(Seq s, CaveRun cv, LocalPlayer p, String why) { return caveMove(s, cv, p, why, false); }

    /** closeForGood (S1: a cave in a protect box): the cave is closed even when an earlier job made it. */
    private String caveMove(Seq s, CaveRun cv, LocalPlayer p, String why, boolean closeForGood) {
        int[] me = Jobs.here(p);
        String d = dim();
        List<Box> prot = protectBoxes();
        Caves.Cave c = core.caves.get(cv.name);
        if (c != null) core.caves.finish(c, !closeForGood && !cv.name.equals(cv.own), System.currentTimeMillis(), now());
        cv.noCaveAt.add(cv.name + " (" + Jobs.fmt(cv.entrance != null ? cv.entrance : me) + ")");
        cv.moves++;
        LOG.info("[entropybot] cave: no cave at {} ({}) - move {}", cv.name, why, cv.moves);
        if (cv.moves > CaveRules.MOVES) return caveFinish(s, cv, CaveRules.noCaveText(cv.noCaveAt, why), false, true);
        // S1: never a known cave whose entrance lies in or by a protect box
        CaveRules.Known best = CaveRules.nearestKnown(knownCaves(), d, me[0], me[1], me[2], CaveRules.closedNames(cv.noCaveAt),
                k -> jobs.goalAllowed(k.x(), k.y(), k.z()) != null || CaveRules.offLimits(prot, d, k.x(), k.y(), k.z()) != null);
        List<Seq.Step> steps = new ArrayList<>();
        ExploreRules.Target t = null;
        if (best != null) {
            steps.add(Seq.Step.walk(new int[]{best.x(), best.y(), best.z()}, true));
            Seq.Step rs = new Seq.Step("caverestart");
            rs.id = best.name();
            rs.why = why;
            steps.add(rs);
        } else {
            cv.skip.addAll(ExploreRules.around(d, me[0], me[2]));       // the job's own set, not the explored notes
            // S1: unexplored land in or by a protect box is passed over (that chunk goes in the job's skip set)
            for (int n = 0; n < 12; n++) {
                t = ExploreRules.target(d, me[0], me[1], me[2], land(d), cv.skip);
                if (t == null || CaveRules.offLimits(prot, d, t.x(), me[1], t.z()) == null) break;
                cv.skip.add(ExploreRules.key(d, t.x() >> 4, t.z() >> 4));
                t = null;
            }
            if (t == null) return caveFinish(s, cv, "there is no cave here (" + why + "), and no unexplored land is left in reach", false, true);
            Seq.Step xz = new Seq.Step("cavexz");
            xz.pos = new int[]{t.x(), me[1], t.z()};
            steps.add(xz);
            // the search there goes on in the cave this job made, if it made one: one cave record per job at most
            Seq.Step rs = new Seq.Step("caverestart");
            rs.id = cv.own != null ? cv.own : "";
            rs.why = why;
            steps.add(rs);
        }
        spliceHere(s, steps);
        s.setStatus(s.label + " - no cave here (" + why + "), moving to " + (best != null ? best.name() : t.x() + " " + t.z()));
        return "wait";
    }

    /** Walk to x z (a chunk to look for a cave in), like explore: until Baritone is done or 90 s. */
    private String caveXzStep(Seq s, Seq.Step st, LocalPlayer p, CaveRun cv, long elapsed) {
        IBaritone b = Jobs.baritone();
        if (s.stage == null) {
            if (b != null) {
                Jobs.safeSettings();
                b.getCustomGoalProcess().setGoalAndPath(new GoalXZ(st.pos[0], st.pos[2]));
            }
            s.stage = "walking";
            s.setStatus(s.label + " - walking to " + st.pos[0] + " " + st.pos[2] + " to look for a cave");
            return "wait";
        }
        if (elapsed < 20) return "wait";
        if (b != null && !Jobs.idle(b) && elapsed < ExploreRules.WALK_TICKS) return "wait";
        if (b != null) Jobs.cancel(b);
        int[] me = Jobs.here(p);
        // didn't get there: the search goes on from here
        if (!ExploreRules.arrived(me[0], me[2], new ExploreRules.Target(st.pos[0], st.pos[2]))) cv.skip.add(ExploreRules.key(dim(), st.pos[0] >> 4, st.pos[2] >> 4));
        return "next";
    }

    /** Starts the cave search again where the bot is now (or in the named cave). */
    private String caveRestart(Seq s, Seq.Step st, LocalPlayer p, CaveRun cv) {
        int[] me = Jobs.here(p);
        String name = st.id == null ? "" : st.id;
        Caves.Cave c = core.caves.pick(name, dim(), me[0], me[1], me[2], System.currentTimeMillis(), now());
        if (c == null) return "I know no cave called " + name + " (\"caves\" lists them)";
        cv.name = c.name;
        cv.entrance = new int[]{c.ex, c.ey, c.ez};
        if (c.visited.isEmpty() && cv.own == null) cv.own = c.name;
        cv.tried.clear();
        cv.missed = 0;
        cv.walk = null;
        cv.visitStart = cv.got();
        s.label = CaveRules.relabel(s.label, c.name);
        s.job().label = s.label;
        LOG.info("[entropybot] cave: searching again in {} at {}", c.name, Jobs.fmt(cv.entrance));
        return "next";
    }

    /** The way out: to the entrance, then home (the walk step teleports when that's worth it); the report after the entrance. */
    private String caveFinish(Seq s, CaveRun cv, String why, boolean finished, boolean noCave) {
        List<Seq.Step> steps = new ArrayList<>();
        if (cv.entrance != null) steps.add(Seq.Step.walk(cv.entrance.clone(), true));
        Seq.Step end = new Seq.Step("caveend");
        end.why = why;
        end.all = finished;
        end.redo = noCave;
        steps.add(end);
        int[] base = basePos();
        if (base != null) steps.add(Seq.Step.walk(base, true));
        s.splice(s.idx + 1, steps);
        return "next";
    }

    // ==== mine <block> [n] [dig]: Baritone's mine ====

    static final class MineRun {
        String target, text, need, phase = "mining", seekWhy, wdSig, toolNote, heldBy;
        Block block;
        int count, seeks, stalls, toolTrips, walkTries;
        boolean dig, ore, req, started, afterTool, held, leaseWarned;
        Map<String, Integer> before = new LinkedHashMap<>();
        long deadline, startTick, lastAct = Long.MIN_VALUE / 2, walkAt, wdSince;
        int[] anchor, walk;
        final List<int[]> trail = new ArrayList<>();
        List<int[]> legs;
        final Set<String> skip = new HashSet<>();
        final List<String> leases = new ArrayList<>();
    }

    /** A mine run checked and ready (not started), or the reply that refuses it. */
    private Object prepareMine(LocalPlayer p, String text) {
        MineRules.Args a = MineRules.args(text);
        if (!MineRules.BLOCK_ID.matcher(a.id()).matches()) return MineRules.notABlock(a.word());
        ResourceLocation rl = ResourceLocation.tryParse(a.id());
        if (rl == null || !BuiltInRegistries.BLOCK.containsKey(rl)) {
            // "mine coal" (the owner's words): the family's ore block, coal -> coal_ore (deepslate_coal_ore is its own)
            ResourceLocation ore = ResourceLocation.tryParse(a.id() + "_ore");
            if (ore != null && BuiltInRegistries.BLOCK.containsKey(ore) && text != null && !text.trim().isEmpty())
                return prepareMine(p, text.trim().replaceFirst("^(\\S+)", "$1_ore"));
            return "error: unknown block " + a.id();
        }
        Block block = BuiltInRegistries.BLOCK.get(rl);
        BlockState st = block.defaultBlockState();
        boolean ore = st.is(Tags.Blocks.ORES);
        if (!ore && !MineRules.EXTRA.matcher(a.id()).matches()) return MineRules.notAnOre(a.id());
        JsonObject pol = policy();
        String d = dim();
        int[] me = Jobs.here(p);
        if (!commands.inAreas(d, me[0], me[2])) return MineRules.outsideAreas(areaNames(pol), PolicyCommands.AREA_HINT);
        if (Jobs.baritone() == null) return "error: baritone not loaded";
        // wave 1, item 8: Baritone's mine digs where it likes, so never inside or near a protect box (the base)
        MineRules.Near pn = MineRules.protectNear(boxes(pol, "protect"), d, me[0], me[1], me[2], MineRules.PROTECT_MARGIN);
        if (pn != null) return "error: " + MineRules.protectNearText(pn) + " - walk me further away first";
        MineRun r = new MineRun();
        r.target = a.id();
        r.text = text;
        r.block = block;
        r.count = a.count();
        r.dig = a.dig();
        r.ore = ore;
        r.req = st.requiresCorrectToolForDrops();
        r.need = ore ? MineRules.pickNeed(r.req, toolNeed(st), a.dig()) : null;
        return r;
    }

    private List<MineRules.Slot> pickSlots(LocalPlayer p, MineRun j) {
        BlockState st = j.block.defaultBlockState();
        List<MineRules.Slot> out = new ArrayList<>();
        for (int i = 0; i < 36; i++) {
            ItemStack s = p.getInventory().getItem(i);
            if (!s.isEmpty()) out.add(new MineRules.Slot(i, Gui.itemId(s), s.isCorrectToolForDrops(st)));
        }
        return out;
    }

    private int pickSlot(LocalPlayer p, MineRun j) {
        return MineRules.pickSlot(pickSlots(p, j), j.req, j.need, Hotbar.toolOres());
    }

    /** That pickaxe into the hand (its laid-out hotbar slot): Baritone's autoTool only looks at the hotbar. */
    private boolean holdPick(LocalPlayer p, MineRun j) {
        int i = pickSlot(p, j);
        if (i < 0) return false;
        if (i >= 9 || p.getInventory().selected != i) Hotbar.toHand(Minecraft.getInstance(), p, i);
        return true;
    }

    private int bestPickTier(LocalPlayer p) {
        return MineRules.bestPickTier(Gui.inventory(p).keySet());
    }

    private Map<String, Integer> gained(LocalPlayer p, MineRun j) { return MineRules.gained(j.before, Gui.inventory(p)); }

    private int mineCount(LocalPlayer p, MineRun j) { return MineRules.count(j.target, gained(p, j)); }

    private String gains(LocalPlayer p, MineRun j) { return MineRules.gains(gained(p, j)); }

    private static String sid(MineRun j) { return MineRules.shortId(j.target); }

    /** The break leases around me (the areas minus the protect boxes, sliced): null, or the refusal (strict mode). */
    private String takeLeases(MineRun j, int[] me) {
        JsonObject pol = policy();
        String d = dim(), task = "mine " + sid(j);
        List<Box> areas = boxes(pol, "areas");
        boolean strict = strict(pol);
        List<int[]> parts = MineRules.leaseBoxes(MineRules.mineBox(me[0], me[1], me[2]), areas, boxes(pol, "protect"), d);
        if (parts == null) {
            String why = areas.isEmpty() ? "no areas set" : "that box is not inside one of my areas";
            if (strict) return "error: the guard refused: " + why + " - " + PolicyCommands.AREA_HINT;
            if (!j.leaseWarned) LOG.info("[entropybot] lease refused (log mode): {} ({})", why, task);
            j.leaseWarned = true;
            return null;
        }
        for (int[] b : parts) {
            String r = core.guard.core.lease(core.token, task, new Box(null, d, b[0], b[1], b[2], b[3], b[4], b[5]), false, false);
            if (r.startsWith("error")) {
                if (strict) return "error: the guard refused: " + r.replaceFirst("^error: ", "") + " - " + PolicyCommands.AREA_HINT;
                if (!j.leaseWarned) LOG.info("[entropybot] lease refused (log mode): {} ({})", r, task);
                j.leaseWarned = true;
                continue;
            }
            j.leases.add(r);
        }
        return null;
    }

    private void releaseLeases(MineRun j) {
        for (String id : j.leases) core.guard.core.release(id);
        j.leases.clear();
    }

    /** Only the job's block may be broken (with dig: tunnelling too; the protected blocks never). */
    private static void mineSettings(MineRun j) {
        try {
            Jobs.safeSettings();
            var s = BaritoneAPI.getSettings();
            List<Block> list = new ArrayList<>();
            list.add(j.block);
            s.allowBreakAnyway.value = list;
            s.allowBreak.value = j.dig;
        } catch (Throwable ignored) {}
    }

    /** The job's hooks: a fight holds it (Baritone's mine stops, breaking off), and however it ends, breaking goes off. */
    private void hooks(Jobs.Job job, MineRun j) {
        job.holdOnFight = true;
        job.ownsBreaking = true;
        job.onHold = () -> {
            j.held = true;
            j.heldBy = core.reflexes.reflex().name().toLowerCase();
            IBaritone b = Jobs.baritone();
            if (b != null) Jobs.cancel(b);
            restoreSettings();
        };
        job.onEnd = () -> {
            restoreSettings();
            releaseLeases(j);
        };
    }

    String startMine(LocalPlayer p, String text) {
        Object o = prepareMine(p, text);
        if (o instanceof String e) return e;
        MineRun r = (MineRun) o;
        int[] me = Jobs.here(p);
        // wave 1, items 6 and 7: a pickaxe that can mine it, before Baritone starts (fetched or made when missing)
        if (r.need != null && pickSlot(p, r) < 0) {
            Seq.Step back = Seq.Step.walk(me.clone(), true);
            back.why = "where I was asked to mine (" + Jobs.fmt(me) + ")";
            Seq.Step go = new Seq.Step("mineore");
            go.text = text;
            Seq s = new Seq(jobs, storage, "getting " + MineRules.anA(r.need) + " pickaxe, then mining " + sid(r),
                    List.of(toolStep(r.need, "mining " + sid(r), r.target, false), back, go), "always");
            runs.put(s, r);
            String res = jobs.startSeq(s, "always");
            hooks(jobs.job, r);
            return res;
        }
        r.anchor = me;
        r.trail.add(me.clone());
        String le = takeLeases(r, me);
        if (le != null) {
            releaseLeases(r);
            return le;
        }
        String status = MineRules.status(r.count, r.target, r.dig, 0);
        Seq s = new Seq(jobs, storage, status, List.of(new Seq.Step("mineore")), "always");
        runs.put(s, r);
        jobs.startSeq(s, "always");
        hooks(jobs.job, r);
        begin(p, r);
        // started while a fight or a meal already holds the bot: the hold hook only fires on a new hold, so apply it now
        // (Baritone's mine stopped, breaking off); the held path in mineStep starts it again when the hold ends
        if (core.reflexes.hold() && jobs.job.onHold != null) jobs.job.onHold.run();
        return "started: " + status;
    }

    /** Baritone's mine from here, the first time. */
    private void begin(LocalPlayer p, MineRun r) {
        r.started = true;
        r.startTick = now();
        r.deadline = now() + MineRules.MAX_TICKS;
        r.before = Gui.inventory(p);
        mineSettings(r);
        if (r.need != null) holdPick(p, r);
        IBaritone b = Jobs.baritone();
        if (b != null) b.getMineProcess().mine(r.count, r.block);
    }

    /** Ends the mine: Baritone stops, breaking off, the leases go, and the job reports. */
    private String end(Seq s, MineRun j, String text) {
        IBaritone b = Jobs.baritone();
        if (b != null) Jobs.cancel(b);
        restoreSettings();
        releaseLeases(j);
        jobs.finish(text);
        return "wait";
    }

    private String mineEnd(Seq s, LocalPlayer p, MineRun j, String why) {
        return end(s, j, MineRules.shortText(j.target, mineCount(p, j), j.count, why, gains(p, j), j.toolNote));
    }

    /** The "mineore" step after a pickaxe trip: the mine starts here (the bridge's "minego"). */
    private String mineFirst(Seq s, Seq.Step st, LocalPlayer p, MineRun old) {
        Object o = prepareMine(p, st.text);
        if (o instanceof String e) return e.replaceFirst("^error: ", "");
        MineRun r = (MineRun) o;
        if (r.need != null && pickSlot(p, r) < 0) return "I have no pickaxe that can mine " + sid(r);
        int[] me = Jobs.here(p);
        r.toolNote = old.toolNote;
        r.anchor = me;
        r.trail.add(me.clone());
        String le = takeLeases(r, me);
        if (le != null) {
            releaseLeases(r);
            return le.replaceFirst("^error: ", "");
        }
        runs.put(s, r);
        hooks(s.job(), r);
        String status = MineRules.status(r.count, r.target, r.dig, 0);
        s.label = status;
        s.setStatus(status);
        begin(p, r);
        return "wait";
    }

    /** S1 (fail closed): a policy that can't be read mid-mine stops it (Baritone off, breaking off, leases gone). */
    private String mineStep(Seq s, Seq.Step st, LocalPlayer p) {
        try {
            return mineStepChecked(s, st, p);
        } catch (MineRules.BadPolicy e) {
            MineRun j = runs.get(s) instanceof MineRun x ? x : null;
            if (j == null) return MineRules.badPolicyText(e);
            return end(s, j, "stopped: " + MineRules.badPolicyText(e));
        }
    }

    /** Every 2 ticks; acts every 20 like the bridge's stepMineJob. */
    private String mineStepChecked(Seq s, Seq.Step st, LocalPlayer p) {
        MineRun j = runs.get(s) instanceof MineRun x ? x : null;
        if (j == null) return "the mine job is gone";
        if (!j.started) {
            j.held = false;
            return mineFirst(s, st, p, j);
        }
        long now = now();
        if (j.afterTool) {
            // back from the pickaxe trip (the steps spliced in front of this one); a fight on the way changes nothing
            j.afterTool = false;
            j.held = false;
            j.lastAct = now;
            mineReturn(s, p, j, "with a new pickaxe");
            return "wait";
        }
        if (j.held) {
            j.held = false;
            String r = holdEnd(s, p, j);
            if (r != null) return r;
            j.lastAct = now;
            return "wait";
        }
        if (now - j.lastAct < 20) return "wait";
        j.lastAct = now;
        core.guard.core.heartbeat(core.token);
        if (j.phase.equals("return") || j.phase.equals("seek")) return mineWalkStep(s, p, j);
        IBaritone b = Jobs.baritone();
        int[] me = Jobs.here(p);
        boolean inside = commands.inAreas(dim(), me[0], me[2]);
        if (b != null && (!inside || now > j.deadline)) {
            String why = !inside ? MineRules.leftAreasText(Jobs.fmt(me)) : MineRules.timeUpText();
            return end(s, j, "stopped: " + why + ", mining " + sid(j) + gains(p, j));
        }
        if (now - j.startTick < 60) return "wait";
        j.anchor = me;
        MineRules.trail(j.trail, me);
        if (j.ore && j.count > 0 && mineCount(p, j) >= j.count) return end(s, j, MineRules.doneText(j.target, gains(p, j), j.toolNote));
        String pc = pickCheck(s, p, j);
        if (pc != null) return pc;
        if (j.ore && stalled(p, j)) {
            j.stalls++;
            if (j.stalls >= 2) return end(s, j, MineRules.stalledText(j.target, Jobs.fmt(me), gains(p, j)));
            return seek(s, p, j, "no progress for a minute at " + Jobs.fmt(me));
        }
        if (b == null || Jobs.idle(b)) {
            if (j.ore && b != null) return seek(s, p, j, "ran out of ore in view");
            return end(s, j, MineRules.doneText(j.target, gains(p, j), null));
        }
        return "wait";
    }

    /** No move and nothing picked up for a minute while Baritone mines. */
    private boolean stalled(LocalPlayer p, MineRun j) {
        int c = 0;
        for (int i = 0; i < 36; i++) c += p.getInventory().getItem(i).getCount();
        String sig = Jobs.fmt(Jobs.here(p)) + " " + c;
        if (j.wdSig == null || !j.wdSig.equals(sig)) {
            j.wdSig = sig;
            j.wdSince = now();
            return false;
        }
        return now() - j.wdSince >= MineRules.STALL_TICKS;
    }

    /** (Again) Baritone's mine from where the bot is now: fresh leases here, the settings, the rest of the count; null or why not. */
    private String mineGo(Seq s, LocalPlayer p, MineRun j) {
        IBaritone b = Jobs.baritone();
        if (b == null) return "stopped: baritone not loaded";
        Jobs.cancel(b);                                   // whatever walk got it here is over
        int[] me = Jobs.here(p);
        MineRules.Near pn = MineRules.protectNear(boxes(policy(), "protect"), dim(), me[0], me[1], me[2], MineRules.PROTECT_MARGIN);
        if (pn != null) return "stopped: " + MineRules.protectNearText(pn);
        releaseLeases(j);
        String le = takeLeases(j, me);
        if (le != null) return le.replaceFirst("^error: ", "stopped: ");
        mineSettings(j);
        if (j.need != null) holdPick(p, j);
        b.getMineProcess().mine(Math.max(1, j.count - mineCount(p, j)), j.block);
        j.phase = "mining";
        j.anchor = me;
        j.startTick = now();
        j.wdSig = null;
        if (s.job() != null) s.job().reflex = false;
        s.setStatus(MineRules.status(j.count, j.target, j.dig, j.seeks));
        return null;
    }

    /** A reflex held the job: the mine starts again from here when close, else it walks back first (mineHoldEnd). */
    private String holdEnd(Seq s, LocalPlayer p, MineRun j) {
        String prev = j.heldBy != null ? j.heldBy : "a fight";
        IBaritone b = Jobs.baritone();
        if (j.phase.equals("return")) {
            mineReturn(s, p, j, "after " + prev);
            return null;
        }
        if (j.phase.equals("seek")) {
            if (b != null && j.walk != null) b.getCustomGoalProcess().setGoalAndPath(new GoalXZ(j.walk[0], j.walk[2]));
            if (j.walk != null) j.walkAt = now();
            return null;
        }
        if (j.anchor == null || Jobs.distSq(Jobs.here(p), j.anchor) > (long) MineRules.RETURN_R * MineRules.RETURN_R) {
            mineReturn(s, p, j, "after " + prev);
            return null;
        }
        String r = mineGo(s, p, j);
        if (r != null) return end(s, j, r + ", mining " + sid(j) + gains(p, j));
        return null;
    }

    /** One leg of the walk back (GoalNear 1), with its tries. */
    private void mineLeg(MineRun j, int tries) {
        IBaritone b = Jobs.baritone();
        if (b == null || j.legs == null || j.legs.isEmpty()) return;
        int[] q = j.legs.get(0);
        Jobs.safeSettings();
        b.getCustomGoalProcess().setGoalAndPath(new GoalNear(bp(q), 1));
        j.walk = q.clone();
        j.walkAt = now();
        j.walkTries = tries;
    }

    /** Walk back to where it was mining (after a fight, a tool trip); the fence doesn't stop it on the way (job.reflex). */
    private void mineReturn(Seq s, LocalPlayer p, MineRun j, String why) {
        IBaritone b = Jobs.baritone();
        if (b == null || j.anchor == null) return;
        Jobs.cancel(b);
        restoreSettings();
        j.phase = "return";
        j.legs = MineRules.legs(j.trail, j.anchor, Jobs.here(p));
        mineLeg(j, 1);
        if (s.job() != null) s.job().reflex = true;
        String status = "mining " + sid(j) + " - walking back to " + Jobs.fmt(j.anchor) + " (" + why + ")" + (j.legs.size() > 1 ? ", " + j.legs.size() + " legs" : "");
        s.setStatus(status);
        LOG.info("[entropybot] mine: walking back to {} ({}){}", Jobs.fmt(j.anchor), why, j.legs.size() > 1 ? " in " + j.legs.size() + " legs" : "");
    }

    /** The ore in view ran out (or no progress): walk to the nearest unexplored chunk of the areas, or end saying so. */
    private String seek(Seq s, LocalPlayer p, MineRun j, String why) {
        int[] me = Jobs.here(p);
        String d = dim();
        j.seeks++;
        if (j.seeks > MineRules.SEEKS) return mineEnd(s, p, j, why + ", also in " + MineRules.SEEKS + " more places I walked to");
        j.skip.addAll(ExploreRules.around(d, me[0], me[2]));
        List<Box> prot = boxes(policy(), "protect");
        ExploreRules.Target t = null;
        for (int n = 0; n < 12; n++) {
            t = ExploreRules.target(d, me[0], me[1], me[2], land(d), j.skip);
            // where "mine" may work (the areas) and away from the protect boxes (the base)
            if (t == null || (commands.inAreas(d, t.x(), t.z()) && MineRules.protectNear(prot, d, t.x(), me[1], t.z(), MineRules.PROTECT_MARGIN + 16) == null)) break;
            j.skip.add(ExploreRules.key(d, t.x() >> 4, t.z() >> 4));
            t = null;
        }
        if (t == null) return mineEnd(s, p, j, why + ", and no unexplored land is left in reach");
        IBaritone b = Jobs.baritone();
        if (b != null) {
            Jobs.cancel(b);
            restoreSettings();
            b.getCustomGoalProcess().setGoalAndPath(new GoalXZ(t.x(), t.z()));
        }
        j.phase = "seek";
        j.walk = new int[]{t.x(), me[1], t.z()};
        j.walkAt = now();
        j.seekWhy = why;
        String status = "mining " + sid(j) + ": " + mineCount(p, j) + "/" + j.count + ", " + why + " - walking to new land at " + t.x() + " " + t.z() + " (look " + (j.seeks + 1) + ")";
        s.setStatus(status);
        LOG.info("[entropybot] mine: {}", status);
        return "wait";
    }

    /** A walk of the mine job (back to its spot, or to new land), every 20 ticks. */
    private String mineWalkStep(Seq s, LocalPlayer p, MineRun j) {
        IBaritone b = Jobs.baritone();
        int[] me = Jobs.here(p), w = j.walk;
        boolean seek = j.phase.equals("seek");
        long limit = seek ? MineRules.SEEK_TICKS : MineRules.RETURN_TICKS;
        if (w == null) return mineEnd(s, p, j, "lost my way");
        if (now() - j.walkAt < 40) return "wait";
        if (b != null && !Jobs.idle(b) && now() - j.walkAt < limit) return "wait";
        boolean arrived = seek ? Math.abs(me[0] - w[0]) + Math.abs(me[2] - w[2]) <= 24 : Jobs.distSq(me, w) <= (j.legs != null && j.legs.size() > 1 ? 64 : 16);
        if (b != null) Jobs.cancel(b);
        if (!arrived) {
            if (seek) {
                j.skip.add(ExploreRules.key(dim(), w[0] >> 4, w[2] >> 4));          // couldn't get there: not again in this job
                return seek(s, p, j, j.seekWhy != null ? j.seekWhy : "ran out of ore in view");
            }
            if (j.walkTries < 2) {
                mineLeg(j, j.walkTries + 1);
                return "wait";
            }
            return end(s, j, MineRules.stuckBackText(j.target, Jobs.fmt(j.anchor), Jobs.fmt(me), Jobs.fmt(w), gains(p, j)));
        }
        if (!seek && j.legs != null && j.legs.size() > 1) {
            j.legs.remove(0);                      // the next leg
            mineLeg(j, 1);
            return "wait";
        }
        if (seek && MineRules.protectNear(boxes(policy(), "protect"), dim(), me[0], me[1], me[2], MineRules.PROTECT_MARGIN) != null) {
            j.skip.add(ExploreRules.key(dim(), me[0] >> 4, me[2] >> 4));
            return seek(s, p, j, j.seekWhy != null ? j.seekWhy : "ran out of ore in view");
        }
        String r = mineGo(s, p, j);
        if (r != null) return end(s, j, r + ", mining " + sid(j) + gains(p, j));
        return "wait";
    }

    /** The pickaxe while mining: in the hotbar, or (none left: it broke) a trip for another spliced in front, then back here. */
    private String pickCheck(Seq s, LocalPlayer p, MineRun j) {
        if (j.need == null) return null;
        int i = pickSlot(p, j);
        if (i >= 0) {
            if (i >= 9) holdPick(p, j);
            return null;
        }
        if (j.toolTrips >= MineRules.TOOL_TRIPS) return end(s, j, MineRules.noPickLeftText(j.target, j.toolTrips, gains(p, j)));
        j.toolTrips++;
        IBaritone b = Jobs.baritone();
        if (b != null) Jobs.cancel(b);
        restoreSettings();
        spliceHere(s, List.of(toolStep(j.need, "mining " + sid(j), j.target, false)));
        j.afterTool = true;
        s.setStatus("getting " + MineRules.anA(j.need) + " pickaxe (mining " + sid(j) + ")");
        LOG.info("[entropybot] mine: no pickaxe left for {} - getting one, then back to {}", sid(j), Jobs.fmt(j.anchor));
        return "wait";
    }

    // ---- the pickaxe trip (the bridge's toolget/toolcheck for the Baritone mine) ----

    record ToolGet(String need, String why, String mine, boolean crafted, String how) {}

    private static Seq.Step toolStep(String need, String why, String mine, boolean crafted) {
        Seq.Step t = new Seq.Step("mineoretool");
        t.state = new ToolGet(need, why, mine, crafted, null);
        return t;
    }

    private void setToolNote(Seq s, String note) {
        if (runs.get(s) instanceof MineRun r) r.toolNote = note;
    }

    /** A pickaxe good enough: a stone one made on the spot from cobblestone near a table, else from storage, else made. */
    private String toolGetStep(Seq s, Seq.Step st, LocalPlayer p) {
        ToolGet t = (ToolGet) st.state;
        int tier = MineRules.PICK_TIER.getOrDefault(t.need(), 4);
        if (bestPickTier(p) >= tier) {
            if (t.crafted()) setToolNote(s, "made " + MineRules.anA(t.need()) + " pickaxe for " + t.why());
            return "next";
        }
        if (t.mine() != null && t.need().equals("stone") && !t.crafted() && Gui.inventory(p).getOrDefault("minecraft:cobblestone", 0) >= 3) {
            int[] tb = crafting.findTable(p);
            if (tb != null && Jobs.distSq(tb, Jobs.here(p)) <= 48 * 48) {
                Seq.Step c = new Seq.Step("craftitem");
                c.text = "minecraft:stone_pickaxe 1";
                c.optional = true;
                s.splice(s.idx + 1, List.of(c, toolStep(t.need(), t.why(), t.mine(), true)));
                return "next";
            }
        }
        List<Crafting.Source> src = crafting.storageSources(p);
        // far from the base its chests count too: the walk there teleports home first
        if (farFromBase(p)) for (StorageRules.Chest c : storage.baseChests(p, true)) src.add(new Crafting.Source(false, c.pos(), c.items()));
        String found = null;
        for (Crafting.Source x : src) {
            for (String id : MineRules.pickIds(t.need())) {
                if (found == null && x.items().getOrDefault(id, 0) > 0) found = id;
            }
            if (found != null) break;
        }
        List<Seq.Step> add = new ArrayList<>();
        if (found != null) {
            Map<String, Integer> need = new LinkedHashMap<>();
            need.put(found, 1);
            add.addAll(Crafting.takeTrips(src, need).steps());
        } else {
            Seq.Step c = new Seq.Step("craftitem");
            c.text = "minecraft:" + t.need() + "_pickaxe 1";
            c.optional = true;
            add.add(c);
        }
        Seq.Step check = new Seq.Step("mineorecheck");
        check.state = new ToolGet(t.need(), t.why(), t.mine(), false, found != null ? "fetched" : "made");
        add.add(check);
        s.splice(s.idx + 1, add);
        s.setStatus(s.label + " - getting " + MineRules.anA(t.need()) + " pickaxe (" + t.why() + ")");
        return "next";
    }

    private String toolCheckStep(Seq s, Seq.Step st, LocalPlayer p) {
        ToolGet t = (ToolGet) st.state;
        if (bestPickTier(p) >= MineRules.PICK_TIER.getOrDefault(t.need(), 4)) {
            String note = t.how() + " " + MineRules.anA(t.need()) + " pickaxe for " + (t.mine() != null ? "" : "the ")
                    + t.why().replaceFirst("^blocked:tool ", "").replaceFirst(" needs \\w+$", "");
            setToolNote(s, note);
            LOG.info("[entropybot] mine: {}", note);
            return "next";
        }
        String msg = "I need " + MineRules.anA(t.need()) + " pickaxe for " + MineRules.shortId(t.mine()) + " and have none, none in my chests and I couldn't make one";
        // the pickaxe broke mid-mine (the bridge's resume message)
        if (runs.get(s) instanceof MineRun r && r.started) return end(s, r, "stopped: my pickaxe broke while mining " + sid(r) + " and " + msg + gains(p, r));
        return msg;
    }
}
