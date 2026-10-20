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
import io.github.mojolowjo.entropybot.scout.ScoutRules;
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
            io.github.mojolowjo.entropybot.guard.PolicyRuntime.INSTANCE.setIntent(io.github.mojolowjo.entropybot.guard.BreakPolicy.Intent.TRAVEL, "mine ended");
        } catch (Throwable ignored) {}
    }

    // ==== the verbs ====

    /**
     * "mine ..." (the bridge's mineCommand): the grammar, "mine cave", the Baritone mine; "mine strip" goes to the strip
     * mine (D2) through {@code strip} (null: not in the mod yet).
     */
    String mine(LocalPlayer p, String rest, Function<MineGrammar.Parsed, String> strip) {
        // AA round 2 item 5: "mine <ore> 1 at x y z dig|walk": one named target and its vein, then the job ends
        MineRules.At at = MineRules.parseAt(rest);
        if (at != null) {
            if (at.error() != null) return at.error();
            String busy = busyText();
            if (busy != null) return busy;
            try {
                return startMine(p, rest.trim());
            } catch (MineRules.BadPolicy e) {
                return "error: " + MineRules.badPolicyText(e);
            }
        }
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

    /** "ores [name]" / "ores clear" ("ores prefer ..." is StripMine's). */
    String ores(LocalPlayer p, String rest) {
        String t = rest == null ? "" : rest.trim().toLowerCase();
        if (t.matches("^known\\b.*")) return oresKnown(p, t);
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

    // ==== 0.28.0: the ore index (sense/ChunkPass) ====

    private static io.github.mojolowjo.entropybot.sense.ChunkPass pass() { return io.github.mojolowjo.entropybot.sense.ChunkPass.INSTANCE; }

    /** The index can answer for this dimension (the pass has read chunks here). */
    static boolean indexReady() { return pass().ready() && dim().equals(pass().dim()); }

    /** "ores known [family] [r]": from the index (every chunk seen, loaded or not), nearest first. */
    String oresKnown(LocalPlayer p, String t) {
        if (!indexReady()) return "the chunk pass has read no chunk here yet - sense status says why";
        Object[] a = io.github.mojolowjo.entropybot.sense.KnownOre.args(t);
        String fam = (String) a[0];
        int r = (Integer) a[1];
        int[] me = Jobs.here(p);
        String d = dim();
        int chunks = pass().chunksSeen(d, me[0], me[2], r, null);
        // 0.28.0 fix: counts from the listed positions within r (never whole-chunk sums); "+" where a chunk's list is capped
        Map<String, io.github.mojolowjo.entropybot.sense.OreIndex.Count> within = pass().index().within(d, me[0], me[1], me[2], r);
        if (fam != null) {
            String f = fam;
            List<io.github.mojolowjo.entropybot.sense.OreIndex.Hit> hits = pass().nearest(d, id -> io.github.mojolowjo.entropybot.sense.KnownOre.family(id, f), me[0], me[1], me[2], r, 8, null);
            int count = 0;
            boolean capped = false;
            for (Map.Entry<String, io.github.mojolowjo.entropybot.sense.OreIndex.Count> e : within.entrySet())
                if (io.github.mojolowjo.entropybot.sense.KnownOre.family((e.getKey().contains(":") ? "" : "minecraft:") + e.getKey() + "_ore", f)) {
                    count += e.getValue().n();
                    capped |= e.getValue().capped();
                }
            return io.github.mojolowjo.entropybot.sense.KnownOre.family(io.github.mojolowjo.entropybot.sense.KnownOre.familyOf(fam), r,
                    new io.github.mojolowjo.entropybot.sense.OreIndex.Count(count, capped), hits, chunks);
        }
        Map<String, io.github.mojolowjo.entropybot.sense.OreIndex.Hit> nearest = new HashMap<>();
        for (String f : within.keySet()) {
            List<io.github.mojolowjo.entropybot.sense.OreIndex.Hit> h = pass().nearest(d, id -> io.github.mojolowjo.entropybot.strip.OreSpec.family(id).equals(f), me[0], me[1], me[2], r, 1, null);
            if (!h.isEmpty()) nearest.put(f, h.get(0));
        }
        int stale = 0;
        for (io.github.mojolowjo.entropybot.sense.ChunkSummary s : pass().index().all(d)) {
            if (s.stale && Math.abs((s.cx << 4) + 8 - me[0]) <= r + 8 && Math.abs((s.cz << 4) + 8 - me[2]) <= r + 8) stale++;
        }
        return io.github.mojolowjo.entropybot.sense.KnownOre.summary(r, within, nearest, chunks, stale);
    }

    /** Is a family ("iron", "nickel") known within SENSE_KNOWN_R inside the areas (gather's ore source, the brain's feasibility)? */
    boolean oreKnownNear(LocalPlayer p, String family) {
        if (!indexReady() || p == null) return false;
        int[] me = Jobs.here(p);
        String d = dim();
        return !pass().nearest(d, id -> io.github.mojolowjo.entropybot.sense.KnownOre.family(id, family), me[0], me[1], me[2],
                io.github.mojolowjo.entropybot.memory.Limits.SENSE_KNOWN_R, 1, (x, y, z) -> commands.inAreas(d, x, z) && fairOk(d, x, y, z)).isEmpty();
    }

    /** AA_ROUND3 3: under {@code aa fair} (default on) only an ore cell I have seen counts (sense.Seen); off: every indexed one. */
    static boolean fairOk(String d, int x, int y, int z) {
        if (!io.github.mojolowjo.entropybot.sense.Seen.fair()) return true;
        io.github.mojolowjo.entropybot.sense.Seen s = pass().seen();
        return s != null && s.has(d, x, y, z);
    }

    /** Chunks seen within the mine reach inside the areas ("I have seen N chunks here"). */
    private int chunksHere(LocalPlayer p) {
        int[] me = Jobs.here(p);
        String d = dim();
        return pass().chunksSeen(d, me[0], me[2], io.github.mojolowjo.entropybot.memory.Limits.SENSE_MINE_R, (x, y, z) -> commands.inAreas(d, x, z));
    }

    /** One face of the block at q is open (air, water, a plant): Baritone's mine without "dig" can reach it. */
    private static boolean exposed(Level level, BlockPos q) {
        for (net.minecraft.core.Direction dir : net.minecraft.core.Direction.values()) {
            BlockPos n = q.relative(dir);
            if (level.getBlockState(n).getCollisionShape(level, n).isEmpty()) return true;
        }
        return false;
    }

    /** The mine's known ore: its block, in the areas, off the protect margin, the guard allows, not tried; loaded: still there (and open without dig). */
    private List<io.github.mojolowjo.entropybot.sense.OreIndex.Hit> knownHits(LocalPlayer p, MineRun j, int n) {
        if (!indexReady() && j.at == null) return List.of();      // the at form names its cell: no index needed
        String d = dim();
        int[] me = Jobs.here(p);
        List<Box> prot = boxes(policy(), "protect");
        Level level = p.level();
        long tick = now();
        // the whole family (iron_ore and deepslate_iron_ore drop the same raw_iron; live: the deepslate ones were skipped)
        // (the owner: "mine iron_ore" / "mine iron" take both; "mine deepslate_iron_ore" stays exact)
        String fam = io.github.mojolowjo.entropybot.sense.KnownOre.familyOf(j.target);
        io.github.mojolowjo.entropybot.clear.ClearJob.StandCheck stand = Clearing.standCheck(commands);
        boolean exact = !j.familyWord;      // the owner: "mine iron" = the family, deepslate or not; "mine iron_ore" = exactly that block
        java.util.function.Predicate<String> idOk = id -> exact ? id.equals(j.target) : io.github.mojolowjo.entropybot.sense.KnownOre.family(id, fam);
        io.github.mojolowjo.entropybot.sense.OreIndex.Allow ok = (x, y, z) -> {
            if (j.knownTried.contains(BlockPos.asLong(x, y, z))) return false;
            if (!fairOk(d, x, y, z)) return false;          // AA_ROUND3 3: the at form, the index and the live scan: seen cells only
            if (MineRules.skipHolds(j.kWaterSkip.get(BlockPos.asLong(x, y, z)), tick)) return false;   // 0.36.a3: timed, not for good
            if (!commands.inAreas(d, x, z) || MineRules.protectNear(prot, d, x, y, z, MineRules.PROTECT_MARGIN) != null || jobs.goalAllowed(x, y, z) != null) return false;
            // 0.28.0 live (LAN flat world): the clear engine stands only inside an area in 3D (Clearing.standCheck), so an
            // area whose floor is above the cell under/beside the ore left "nowhere to stand": that ore is no candidate
            if (stand != null && !stand.ok(x, y - 1, z) && !stand.ok(x, y, z)) return false;
            BlockPos q = new BlockPos(x, y, z);
            if (!level.isLoaded(q)) return true;              // known from before: go and look
            return idOk.test(BuiltInRegistries.BLOCK.getKey(level.getBlockState(q).getBlock()).toString())
                    && (j.dig || exposed(level, q));
        };
        if (j.at != null) {
            // AA round 2 item 5: only the named cell (the same fence); not loaded: walk there and look
            int[] t = j.at;
            if (!ok.test(t[0], t[1], t[2])) return List.of();
            BlockPos q = new BlockPos(t[0], t[1], t[2]);
            boolean loaded = level.isLoaded(q);
            long dx = t[0] - me[0], dy = t[1] - me[1], dz = t[2] - me[2];
            return List.of(new io.github.mojolowjo.entropybot.sense.OreIndex.Hit(t[0], t[1], t[2],
                    loaded ? BuiltInRegistries.BLOCK.getKey(level.getBlockState(q).getBlock()).toString() : j.target, fam, dx * dx + dy * dy + dz * dz, !loaded));
        }
        List<io.github.mojolowjo.entropybot.sense.OreIndex.Hit> idx = pass().nearest(d, idOk, me[0], me[1], me[2], io.github.mojolowjo.entropybot.memory.Limits.SENSE_MINE_R, n, ok);
        // 0.36.a5 (owner test: iron placed 2 blocks away was ignored): Baritone's live scan of the loaded chunks joins the index
        List<io.github.mojolowjo.entropybot.sense.OreIndex.Hit> live = new ArrayList<>();
        for (io.github.mojolowjo.entropybot.sense.MemoryPick.Spot s : liveOres(p, j, tick)) {
            if (!ok.test(s.x(), s.y(), s.z())) continue;
            long dx = s.x() - me[0], dy = s.y() - me[1], dz = s.z() - me[2];
            live.add(new io.github.mojolowjo.entropybot.sense.OreIndex.Hit(s.x(), s.y(), s.z(), s.id(), io.github.mojolowjo.entropybot.sense.KnownOre.familyOf(s.id()), dx * dx + dy * dy + dz * dz, false));
        }
        io.github.mojolowjo.entropybot.sense.OreMerge.Merged m = io.github.mojolowjo.entropybot.sense.OreMerge.merge(idx, live, n);
        j.kIdxKeys = new java.util.HashSet<>();
        for (io.github.mojolowjo.entropybot.sense.OreIndex.Hit h : idx) j.kIdxKeys.add(io.github.mojolowjo.entropybot.sense.OreMerge.key(h.x(), h.y(), h.z()));
        j.kMerged = m;
        return m.hits();
    }

    /**
     * aa-water (to-do 1f): the stand cells by a wet ore for WaterWays: the nearest dry cell, floor cell under water and
     * water cell over water (a block placed under it: placed into a cell with a solid neighbour) whose eyes reach the
     * ore (4.5). Feet and head passable, no lava. Client thread.
     */
    private static io.github.mojolowjo.entropybot.aa.WaterWays.Wet wetFacts(LocalPlayer p, Level level, int[] me, int ox, int oy, int oz, int oreDepth) {
        int[] dry = null, fl = null, pl = null;
        double dryD = -1, flD = -1, plD = -1;
        int flDepth = 0, plDepth = 0;
        for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) for (int dy = -2; dy <= 1; dy++) {
            int x = ox + dx, y = oy + dy, z = oz + dz;
            double ex = x + 0.5 - (ox + 0.5), ey = y + 1.6 - (oy + 0.5), ez = z + 0.5 - (oz + 0.5);
            if (ex * ex + ey * ey + ez * ez > 4.5 * 4.5 || (dx == 0 && dz == 0 && (dy == 0 || dy == -1))) continue;
            BlockPos feet = new BlockPos(x, y, z);
            if (!passable(level, feet) || !passable(level, feet.above())) continue;
            boolean wFeet = !level.getFluidState(feet).isEmpty(), wHead = !level.getFluidState(feet.above()).isEmpty();
            BlockPos below = feet.below();
            boolean solidBelow = solid(level, below);
            double d = Math.sqrt(Jobs.distSq(me, new int[]{x, y, z}));
            if (!wFeet && !wHead && solidBelow) { if (dryD < 0 || d < dryD) { dry = new int[]{x, y, z}; dryD = d; } }
            else if (wFeet && solidBelow) { if (flD < 0 || d < flD) { fl = new int[]{x, y, z}; flD = d; flDepth = io.github.mojolowjo.entropybot.guard.PolicyRuntime.waterAbove(level, feet); } }
            else if (wFeet && !level.getFluidState(below).isEmpty() && passable(level, below) && hasSolidSide(level, below)) {
                if (plD < 0 || d < plD) { pl = new int[]{x, y - 1, z}; plD = d; plDepth = io.github.mojolowjo.entropybot.guard.PolicyRuntime.waterAbove(level, feet); }
            }
        }
        int cobble = 0;
        for (int i = 0; i < 36; i++) {
            var st = p.getInventory().getItem(i);
            if (st.is(net.minecraft.world.item.Items.COBBLESTONE)) cobble += st.getCount();
        }
        return new io.github.mojolowjo.entropybot.aa.WaterWays.Wet(p.isInWater(), oreDepth, p.getAirSupply(), io.github.mojolowjo.entropybot.guard.PolicyRuntime.pickTier(p),
                fl, flD, flDepth, pl, plD, plDepth, dry, dryD, cobble);
    }

    private static boolean passable(Level level, BlockPos q) {
        var st = level.getBlockState(q);
        return st.getCollisionShape(level, q).isEmpty() && !st.getFluidState().is(net.minecraft.tags.FluidTags.LAVA);
    }

    private static boolean solid(Level level, BlockPos q) {
        var st = level.getBlockState(q);
        return st.getFluidState().isEmpty() && st.isCollisionShapeFullBlock(level, q);
    }

    private static boolean hasSolidSide(Level level, BlockPos q) {
        for (net.minecraft.core.Direction dir : net.minecraft.core.Direction.values()) if (dir != net.minecraft.core.Direction.UP && solid(level, q.relative(dir))) return true;
        return false;
    }

    /**
     * 0.36.a8 (AA's iron option scorer, MINING_PLAN 3.6): the nearest known ore of a family with the facts the scorer
     * reads: loaded, a dry open face, sky-open, the dig depth from the surface, the water rule (a dig down from in the
     * water), and the break policy's MINE verdict at the cell. Same fence as the mine's own pick (my areas, the protect
     * margin, the guard); no tried-set (the scorer keeps its own memory). Never throws; empty when the index is not ready.
     */
    List<io.github.mojolowjo.entropybot.aa.IronOptions.Ore> aaOres(LocalPlayer p, String fam, int n) { return aaOres(p, fam, n, 0); }

    /**
     * AA_ROUND3 3: under {@code aa fair} the n nearest seen ores, plus up to {@code unseen} nearest ones I never saw
     * (bare rows, so the iron scorer drops them with the reason "not seen"); fair off: the n nearest as before.
     */
    /** AA_ROUND3 4: the last aaOres' parts (index query, the live cell reads) for the facts timing line. */
    static String aaOresParts = "";

    List<io.github.mojolowjo.entropybot.aa.IronOptions.Ore> aaOres(LocalPlayer p, String fam, int n, int unseen) {
        List<io.github.mojolowjo.entropybot.aa.IronOptions.Ore> out = new ArrayList<>();
        aaOresParts = "";
        try {
            if (!indexReady()) return out;
            String d = dim();
            int[] me = Jobs.here(p);
            Level level = p.level();
            List<Box> prot = boxes(policy(), "protect");
            java.util.function.Predicate<String> idOk = id -> io.github.mojolowjo.entropybot.sense.KnownOre.family(id, fam);
            io.github.mojolowjo.entropybot.sense.OreIndex.Allow fence = (x, y, z) -> commands.inAreas(d, x, z)
                    && MineRules.protectNear(prot, d, x, y, z, MineRules.PROTECT_MARGIN) == null && jobs.goalAllowed(x, y, z) == null;
            io.github.mojolowjo.entropybot.sense.OreIndex.Allow ok = (x, y, z) -> fairOk(d, x, y, z) && fence.test(x, y, z);     // AA_ROUND3 4: the seen lookup first (cheap, mostly no)
            if (unseen > 0 && io.github.mojolowjo.entropybot.sense.Seen.fair())
                for (io.github.mojolowjo.entropybot.sense.OreIndex.Hit h : pass().nearest(d, idOk, me[0], me[1], me[2], io.github.mojolowjo.entropybot.memory.Limits.SENSE_KNOWN_R, unseen,
                        (x, y, z) -> fence.test(x, y, z) && !fairOk(d, x, y, z)))
                    out.add(new io.github.mojolowjo.entropybot.aa.IronOptions.Ore(h.x(), h.y(), h.z(), Math.sqrt(h.d2()), false, false, false, -1, false, null, false));
            var rt = io.github.mojolowjo.entropybot.guard.PolicyRuntime.INSTANCE;
            long tq = System.nanoTime();
            var near = pass().nearest(d, idOk, me[0], me[1], me[2], io.github.mojolowjo.entropybot.memory.Limits.SENSE_KNOWN_R, n, ok);
            long tc = System.nanoTime();
            aaOresParts = "index " + (tc - tq) / 1000 + " us";
            for (io.github.mojolowjo.entropybot.sense.OreIndex.Hit h : near) {
                BlockPos q = new BlockPos(h.x(), h.y(), h.z());
                boolean loaded = level.isLoaded(q);
                double dist = Math.sqrt(h.d2());
                if (!loaded) {
                    out.add(new io.github.mojolowjo.entropybot.aa.IronOptions.Ore(h.x(), h.y(), h.z(), dist, false, true, false, -1, false, null, false));
                    continue;
                }
                if (!idOk.test(BuiltInRegistries.BLOCK.getKey(level.getBlockState(q).getBlock()).toString())) continue;     // gone
                boolean open = airFace(level, h.x(), h.y(), h.z());
                boolean sky = open && skyOpen(level, q);
                int top = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE, h.x(), h.z()) - 1;
                boolean touchesWater = io.github.mojolowjo.entropybot.guard.Guard.liquidNextTo(level, h.x(), h.y(), h.z()) != null || !level.getFluidState(q.below()).isEmpty();
                // aa-costs: the drowning risk of the dig (DrownRisk); fatal = dropped, else a cost the scorer weighs
                boolean wetDown = !open && MineRules.waterDownSkip(p.isInWater(), me[1], h.y(), touchesWater);
                int wAbove = wetDown ? io.github.mojolowjo.entropybot.guard.PolicyRuntime.waterAbove(level, p.blockPosition()) : 0;
                double risk = wetDown ? io.github.mojolowjo.entropybot.guard.DrownRisk.risk(me[1] - h.y(), wAbove, p.getAirSupply(), io.github.mojolowjo.entropybot.guard.PolicyRuntime.pickTier(p)) : 0;
                boolean wet = io.github.mojolowjo.entropybot.guard.DrownRisk.fatal(risk);
                String drownWhy = wetDown ? io.github.mojolowjo.entropybot.guard.DrownRisk.terms(me[1] - h.y(), wAbove, p.getAirSupply(), io.github.mojolowjo.entropybot.guard.PolicyRuntime.pickTier(p)) : null;
                String deny = null;
                boolean floor = false;
                try {
                    var v = rt.judge("aa-iron", io.github.mojolowjo.entropybot.guard.BreakPolicy.Action.BREAK, io.github.mojolowjo.entropybot.guard.BreakPolicy.Intent.MINE,
                            d, h.x(), h.y(), h.z(), rt.facts(level, q, false), rt.where());
                    if (!v.allowed()) {
                        deny = v.reason();
                        floor = v.floor();
                    }
                } catch (RuntimeException e) {
                    deny = "policy error: " + e;
                }
                int oreDepth = io.github.mojolowjo.entropybot.guard.PolicyRuntime.waterAbove(level, q.above());
                io.github.mojolowjo.entropybot.aa.WaterWays.Wet wetFacts = touchesWater || oreDepth > 0 ? wetFacts(p, level, me, h.x(), h.y(), h.z(), oreDepth) : null;
                out.add(new io.github.mojolowjo.entropybot.aa.IronOptions.Ore(h.x(), h.y(), h.z(), dist, true, open, sky, Math.max(1, top - h.y() + 1), wet, deny, floor,
                        wet ? 0 : io.github.mojolowjo.entropybot.guard.DrownRisk.cost(risk), drownWhy, wetFacts));
            }
            aaOresParts += ", " + near.size() + " cells " + (System.nanoTime() - tc) / 1000 + " us";
        } catch (RuntimeException e) {
            LOG.warn("[entropybot] aa iron: the known-ore facts could not be read: {}", e.toString());
        }
        return out;
    }

    /** 0.36.a5: Baritone's live scan for the job's block(s) near me, kept a few seconds (one scan per pick, cheap on the tick). */
    private List<io.github.mojolowjo.entropybot.sense.MemoryPick.Spot> liveOres(LocalPlayer p, MineRun j, long tick) {
        if (j.kLive != null && tick - j.kLiveAt < 60) return j.kLive;
        String fam = io.github.mojolowjo.entropybot.sense.KnownOre.familyOf(j.target);
        List<String> ids = new ArrayList<>();
        for (net.minecraft.resources.ResourceLocation k : BuiltInRegistries.BLOCK.keySet()) {
            String id = k.toString();
            if (!k.getPath().endsWith("_ore") && !k.getPath().equals("ancient_debris")) continue;
            if (!j.familyWord ? id.equals(j.target) : io.github.mojolowjo.entropybot.sense.KnownOre.family(id, fam)) ids.add(id);
        }
        j.kLive = io.github.mojolowjo.entropybot.sense.BaritoneMemory.live(p, ids, 64, 6);
        j.kLiveAt = tick;
        return j.kLive;
    }

    /** The mine starts (or goes on) from the index: phase "known". False: the index has none near (today's path runs). */
    private boolean knownStart(LocalPlayer p, MineRun j) {
        if (!j.ore || (!indexReady() && j.at == null)) return false;
        // the at form always runs here: knownStep ends it when its cell can't be had (never Baritone's own search)
        if (j.at == null && knownHits(p, j, 1).isEmpty() && farOres(p, j).isEmpty()) {
            j.knownWhy = io.github.mojolowjo.entropybot.sense.KnownOre.noneWhy(io.github.mojolowjo.entropybot.sense.KnownOre.familyOf(j.target));
            return false;
        }
        IBaritone b = Jobs.baritone();
        if (b != null) Jobs.cancel(b);
        restoreSettings();                                // the clear engine breaks; Baritone only walks
        j.phase = "known";
        // the known-ore digs are the job's purpose, like the strip mine's corridor: never in the restore ledger
        // (live: each try put a cobblestone back over the owner's ore). Baritone's own path breaks get it again in mineGo.
        if (jobs.job != null) RestoreLive.INSTANCE.endPathContext(jobs.job.id);
        j.kTarget = null;
        if (j.at != null) {
            LOG.info("[entropybot] mine: {} 1 at {} {} - that cell and its vein (at most {}), then the job ends", sid(j), Jobs.fmt(j.at), j.dig ? "dig" : "walk", MineRules.AT_VEIN);
            return true;
        }
        LOG.info("[entropybot] mine: {} {} - walking to it (no Baritone search; {})", sid(j),
                knownHits(p, j, 1).isEmpty() ? "remembered by Baritone (not in the index)" : "known in the index",
                j.familyWord ? "any " + io.github.mojolowjo.entropybot.sense.KnownOre.familyOf(j.target) + " ore block, deepslate or not" : "exactly " + sid(j));
        return true;
    }

    /** 0.34.0: at most this many walks to ore Baritone remembers per mine. */
    static final int FAR_TRIPS = 3;

    /** 0.34.0: ore of the mine's block (or family) Baritone's cache remembers, in my areas, not tried, live-checked when loaded. */
    private List<io.github.mojolowjo.entropybot.sense.MemoryPick.Spot> farOres(LocalPlayer p, MineRun j) {
        if (j.kFarTrips >= FAR_TRIPS || j.at != null) return List.of();
        // 0.34.1 (review): one recall per job; later asks reuse its list (tried and now-gone spots taken out)
        if (j.kFarCache != null) {
            io.github.mojolowjo.entropybot.sense.MemoryPick.Check live = io.github.mojolowjo.entropybot.sense.BaritoneMemory.check(p.level());
            long tk = now();
            j.kFarCache.removeIf(o -> j.knownTried.contains(BlockPos.asLong(o.x(), o.y(), o.z()))
                    || MineRules.skipHolds(j.kWaterSkip.get(BlockPos.asLong(o.x(), o.y(), o.z())), tk)
                    || live.at(o.id(), o.x(), o.y(), o.z()) == io.github.mojolowjo.entropybot.sense.MemoryPick.Live.LOADED_GONE);
            // 0.34.2 (review): nearest from where I stand now; an empty list recalls once more
            int[] here = Jobs.here(p);
            j.kFarCache.sort(java.util.Comparator.comparingLong(o -> o.d2(here)));
            if (!j.kFarCache.isEmpty()) return List.of(j.kFarCache.get(0));
            if (j.kFarRecalls >= 2) return List.of();
        }
        j.kFarRecalls++;
        String d = dim();
        String fam = io.github.mojolowjo.entropybot.sense.KnownOre.familyOf(j.target);
        boolean exact = !j.familyWord;
        List<String> ids = new ArrayList<>();
        for (net.minecraft.resources.ResourceLocation k : BuiltInRegistries.BLOCK.keySet()) {
            String id = k.toString();
            if (!k.getPath().endsWith("_ore") && !k.getPath().equals("ancient_debris")) continue;
            if (exact ? id.equals(j.target) : io.github.mojolowjo.entropybot.sense.KnownOre.family(id, fam)) ids.add(id);
        }
        if (ids.isEmpty()) return List.of();
        List<Box> prot = boxes(policy(), "protect");
        j.kFarCache = new ArrayList<>(io.github.mojolowjo.entropybot.sense.BaritoneMemory.recall(p, ids, (x, y, z) ->
                !j.knownTried.contains(BlockPos.asLong(x, y, z)) && !MineRules.skipHolds(j.kWaterSkip.get(BlockPos.asLong(x, y, z)), now())
                        && commands.inAreas(d, x, z) && fairOk(d, x, y, z)
                        && MineRules.protectNear(prot, d, x, y, z, MineRules.PROTECT_MARGIN) == null && jobs.goalAllowed(x, y, z) == null
                        && (j.dig || io.github.mojolowjo.entropybot.sense.BaritoneMemory.exposedInCache(x, y, z)), 8, "mine"));   // 0.34.1: walkable faces only
        return j.kFarCache.isEmpty() ? List.of() : List.of(j.kFarCache.get(0));
    }

    /** 0.34.0: walk to the nearest remembered ore (Baritone's memory); false when none. */
    private boolean farOreWalk(Seq s, LocalPlayer p, MineRun j) {
        List<io.github.mojolowjo.entropybot.sense.MemoryPick.Spot> far = farOres(p, j);
        if (far.isEmpty()) return false;
        io.github.mojolowjo.entropybot.sense.MemoryPick.Spot o = far.get(0);
        int[] me = Jobs.here(p);
        int[] t = {o.x(), o.y(), o.z()};
        j.kFarTrips++;
        j.kFarId = o.id();
        j.kFarText = io.github.mojolowjo.entropybot.sense.MemoryPick.text(o, me);
        j.kWaterDown = false;
        j.knownTried.add(BlockPos.asLong(t[0], t[1], t[2]));
        j.kTarget = t;
        j.kVein = new ArrayList<>(List.of(new Pos(t[0], t[1], t[2])));   // the rest of the vein shows in the index once loaded
        Seq.Step w = Seq.Step.walk(t.clone(), true);
        w.optional = true;
        // 0.34.2 (lead's live check: the walk went on at a removed spot after its chunk loaded): checked every half second
        // of the walk; gone ends the walk, knownStep drops it (no trip) and picks the next
        w.watch = walkWatch(j, o.id(), t);
        LOG.info("[entropybot] mine: none in view or in the index - {} (Baritone's memory), walking there ({}/{})", j.kFarText, j.kFarTrips, FAR_TRIPS);
        s.setStatus("mining " + sid(j) + ": " + mineCount(p, j) + "/" + j.count + " - " + j.kFarText + ", walking there");
        spliceHere(s, List.of(w));
        return true;
    }

    /**
     * 0.36.a3: the walk watch of every known-ore walk: the remembered target gone (farWatch), or the walk digging down
     * from in the water (owner's rule; aa370 ran out of air that way) - that sets kWaterDown and ends the walk.
     */
    private static java.util.function.BooleanSupplier walkWatch(MineRun j, String farId, int[] t) {
        java.util.function.BooleanSupplier far = farWatch(farId, t);
        return () -> {
            if (far != null && far.getAsBoolean()) return true;
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
            LocalPlayer pl = mc.player;
            if (pl == null || mc.gameMode == null) return false;
            int breakY = Integer.MAX_VALUE;
            if (mc.hitResult instanceof net.minecraft.world.phys.BlockHitResult bh && bh.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK)
                breakY = bh.getBlockPos().getY();
            // aa-costs: stopped only when the dig still ahead leaves no air to come back (DrownRisk)
            int feet = pl.blockPosition().getY(), ty = t == null ? feet - 1 : t[1];
            if (MineRules.waterDigDown(pl.isInWater(), mc.gameMode.isDestroying(), breakY, feet, ty, pl.getAirSupply(),
                    io.github.mojolowjo.entropybot.guard.PolicyRuntime.waterAbove(pl.level(), pl.blockPosition()), io.github.mojolowjo.entropybot.guard.PolicyRuntime.pickTier(pl))) {
                LOG.info("[entropybot] mine: walk stopped, {}", io.github.mojolowjo.entropybot.guard.DrownRisk.terms(Math.max(1, feet - ty),
                        io.github.mojolowjo.entropybot.guard.PolicyRuntime.waterAbove(pl.level(), pl.blockPosition()), pl.getAirSupply(), io.github.mojolowjo.entropybot.guard.PolicyRuntime.pickTier(pl)));
                j.kWaterDown = true;
                return true;
            }
            return false;
        };
    }

    /** 0.34.2: the walk watch for a remembered target (null id: none). */
    private static java.util.function.BooleanSupplier farWatch(String id, int[] t) {
        if (id == null) return null;
        return () -> {
            net.minecraft.client.multiplayer.ClientLevel lv = net.minecraft.client.Minecraft.getInstance().level;
            return lv != null && io.github.mojolowjo.entropybot.sense.BaritoneMemory.check(lv).at(id, t[0], t[1], t[2])
                    == io.github.mojolowjo.entropybot.sense.MemoryPick.Live.LOADED_GONE;
        };
    }

    /**
     * 0.34.2 (lead's live check: every walk ended in a hollow 6 below the ores; walks never place): a paper plan with
     * placing to the cell above the ore, reported (log + whisper) as the next step; the mine goes on to the next target.
     */
    private void farPlan(LocalPlayer p, int[] t, int[] me) {
        try {
            String to = t[0] + " " + (t[1] + 1) + " " + t[2];
            String s = PaperPlan.INSTANCE.start(commands, p, to, "place", r -> {
                LOG.info("[entropybot] mine: the remembered ore at {} was out of reach from {}: {}", Jobs.fmt(t), Jobs.fmt(me), r);
                commands.whisper(commands.owner(), "ore at " + Jobs.fmt(t) + " out of reach on foot: " + r);
            });
            if (s != null && (s.startsWith("busy") || s.startsWith("error"))) {
                // 0.34.3 (review): no plan started, so no whisper will follow - say so now and go on
                LOG.info("[entropybot] mine: ended {} below the remembered ore at {} - no placing plan ({}), going on", t[1] - me[1], Jobs.fmt(t), s);
                commands.whisper(commands.owner(), "ore at " + Jobs.fmt(t) + " out of reach on foot; no placing plan (" + s + ") - next: plan path " + to + " place");
                return;
            }
            LOG.info("[entropybot] mine: ended {} below the remembered ore at {} - plan path {} place: {}", t[1] - me[1], Jobs.fmt(t), to, s);
        } catch (RuntimeException e) {
            LOG.warn("[entropybot] mine: the placing plan to {} failed to start: {}", Jobs.fmt(t), e.toString());
        }
    }

    /** The end line of a mine whose index had nothing (never "ran out of ore in view" then). */
    private String knownEndText(LocalPlayer p, MineRun j) {
        return io.github.mojolowjo.entropybot.sense.KnownOre.noneText(io.github.mojolowjo.entropybot.sense.KnownOre.familyOf(j.target), chunksHere(p));
    }

    /**
     * Every 20 ticks in phase "known" (0.28.0, the conservative form after three live rounds in run1): only known ores the
     * bot can walk to are tried - loaded, with an open face (the ones whose open air sees the sky first, then ores in
     * caves); the mod's walk step goes there and the vein is cleared with the clear engine when the bot ends within 4.5
     * blocks. Buried ores are skipped (a dug route down a staircase did not walk reliably). Every failure - the walk failed,
     * it ended too far, the vein clear mined nothing - costs a try with a "mine: couldn't get to ... try n/5" line; after
     * {@link io.github.mojolowjo.entropybot.sense.KnownOre#MAX_TARGETS} the job ends saying the ore is known but unreachable.
     */
    private String knownStep(Seq s, LocalPlayer p, MineRun j) {
        long now = now();
        int[] me = Jobs.here(p);
        String d = dim();
        String fam = io.github.mojolowjo.entropybot.sense.KnownOre.familyOf(j.target);
        if (j.kClear != null) {
            // the vein clear is over: it counts only when it mined something
            Clearing.Outcome o = j.kClear.cleared;
            int mined = 0;
            if (o != null && o.mined() != null) for (Map.Entry<String, Integer> e : o.mined().entrySet()) {
                mined += e.getValue();
                j.kBlocks.merge(MineRules.shortId(e.getKey()), e.getValue(), Integer::sum);
            }
            int[] t = j.kLast;
            String failed = j.kFailed;
            j.kClear = null;
            j.kFailed = null;
            boolean dug = j.kDigging;
            j.kDigging = false;
            if (j.at != null) {
                // the column dig is over: now the vein around the cell; after the vein clear the job ends
                if (failed == null && !j.atVeinDone) return knownClear(s, j, t != null ? t : j.at);
                return atEnd(s, p, j, failed != null ? failed : "the clear mined none of it from " + Jobs.fmt(me));
            }
            if (failed == null && mined > 0) {
                j.knownVeins++;
                if (dug) j.kDug++;
            } else if (t != null) {
                String r = knownMiss(s, p, j, t, failed != null ? failed : "the vein clear mined nothing there (out of reach from " + Jobs.fmt(me) + ")");
                if (!r.equals("wait") || j.kTries >= io.github.mojolowjo.entropybot.sense.KnownOre.MAX_TARGETS) return r;      // the job ended: no "try 6/5"
            }
        }
        boolean inside = commands.inAreas(d, me[0], me[2]);
        if (!inside || now > j.deadline) return end(s, j, "stopped: " + (!inside ? MineRules.leftAreasText(Jobs.fmt(me)) : MineRules.timeUpText()) + ", mining " + sid(j) + gains(p, j));
        if (j.at == null && j.count > 0 && mineCount(p, j) >= j.count)
            return end(s, j, MineRules.shortText(j.target, mineCount(p, j), j.count, (j.kBlocks.isEmpty() ? "" : "blocks " + blocksText(j) + " ") + "from known ore (" + j.knownVeins + " vein" + (j.knownVeins == 1 ? "" : "s") + (j.kDug > 0 ? ", dug to " + j.kDug + " of them" : "") + ", " + j.kTries + " missed)", gains(p, j), j.toolNote));
        String pc = pickCheck(s, p, j);
        if (pc != null) return pc;
        if (j.kTarget != null) {
            // the walk spliced for it is over (or failed)
            int[] t = j.kTarget;
            String failed = j.kFailed;
            j.kTarget = null;
            j.kFailed = null;
            if (j.kWaterDown && j.at != null) {
                j.kWaterDown = false;
                return atEnd(s, p, j, "the walk started digging down from in the water at " + Jobs.fmt(me));
            }
            if (j.kWaterDown) {
                // 0.36.a3 (owner's rule): the walk began digging down from in the water - stopped; this ore is out of
                // the pick for a minute (not for good: from dry ground it is fine), no try spent, the next one is picked
                j.kWaterDown = false;
                j.kWalks = 0;
                if (j.kFarId != null) {          // review: a remembered target went into knownTried at its walk start - timed only
                    j.knownTried.remove(BlockPos.asLong(t[0], t[1], t[2]));
                    if (j.kFarTrips > 0) j.kFarTrips--;
                }
                j.kFarId = null;
                j.kWaterSkip.put(BlockPos.asLong(t[0], t[1], t[2]), now + MineRules.WATER_SKIP_TICKS);
                LOG.info("[entropybot] mine: target {} at {} skipped for {} s: the walk started digging down from in the water at {} (no try spent)",
                        sid(j), Jobs.fmt(t), MineRules.WATER_SKIP_TICKS / 20, Jobs.fmt(me));
                return "wait";
            }
            if (j.kFarId != null && io.github.mojolowjo.entropybot.sense.BaritoneMemory.check(p.level()).at(j.kFarId, t[0], t[1], t[2])
                    == io.github.mojolowjo.entropybot.sense.MemoryPick.Live.LOADED_GONE) {
                // 0.34.0: the remembered ore is gone now that its chunk is loaded: dropped, no try spent
                LOG.info("[entropybot] mine: Baritone remembered {} at {}, it's gone now (chunk loaded) - dropped", j.kFarId, Jobs.fmt(t));
                j.kFarId = null;
                j.kWalks = 0;
                if (j.kFarTrips > 0) j.kFarTrips--;          // 0.34.1: a spot found gone costs no trip
                return "wait";
            }
            if (failed == null && Jobs.distSq(me, t) <= 20) {
                j.kFarId = null;
                j.kWalks = 0;
                return knownClear(s, j, t);
            }
            // walk ended short (live: standing on leaves, 2 s): walk again from here, twice, before it counts as a try
            if (j.kWalks < 2) {
                j.kWalks++;
                j.kTarget = t;
                Seq.Step w = Seq.Step.walk(t.clone(), true);
                w.optional = true;
                w.watch = walkWatch(j, j.kFarId, t);          // 0.34.2: every leg of a remembered target is watched (0.36.a3: and the water dig-down)
                LOG.info("[entropybot] mine: the walk to the known {} at {} ended short at {}{} - walking again ({}/2)", sid(j), Jobs.fmt(t), Jobs.fmt(me),
                        failed != null ? " (" + failed + ")" : "", j.kWalks);
                spliceHere(s, List.of(w));
                return "wait";
            }
            j.kWalks = 0;
            if (j.kFarId != null && me[1] < t[1] - 2) farPlan(p, t, me);   // 0.34.2: below a remembered ore: what placing would do
            j.kFarId = null;
            return knownMiss(s, p, j, t, failed != null ? failed : "the walk ended at " + Jobs.fmt(me) + ", out of reach");
        }
        List<io.github.mojolowjo.entropybot.sense.OreIndex.Hit> hits = knownHits(p, j, 64);
        if (hits.isEmpty() && j.at != null) {
            BlockPos q = new BlockPos(j.at[0], j.at[1], j.at[2]);
            String why = !commands.inAreas(d, j.at[0], j.at[2]) ? "it is outside my areas"
                    : p.level().isLoaded(q) && !io.github.mojolowjo.entropybot.sense.KnownOre.family(BuiltInRegistries.BLOCK.getKey(p.level().getBlockState(q).getBlock()).toString(), fam)
                    ? "no " + fam + " ore there any more" : !j.dig && p.level().isLoaded(q) ? "buried, and this is the walk form (dig digs to it)" : "off limits (protect margin, guard or no place to stand)";
            return atEnd(s, p, j, why);
        }
        if (hits.isEmpty()) {
            if (farOreWalk(s, p, j)) return "wait";          // 0.34.0: Baritone remembers one further out
            if (j.kTries > 0 || j.kKnown > 0) {
                int left = knownCount(p, fam);
                String veins = j.knownVeins + " vein" + (j.knownVeins == 1 ? "" : "s") + " mined" + (j.kDug > 0 ? " (dug to " + j.kDug + " of them)" : "") + (j.kBlocks.isEmpty() ? "" : " (" + blocksText(j) + ")");
                String why = j.kTries == 0 ? veins + ", no more " + fam + " ore I can walk to known within " + io.github.mojolowjo.entropybot.memory.Limits.SENSE_MINE_R
                        + (left > 0 ? " (" + left + " more known, buried or tried)" : "") + " - next: mine strip " + fam + " or mine cave " + fam
                        : veins + ", " + io.github.mojolowjo.entropybot.sense.KnownOre.unreachableText(fam, left, j.kTries);
                return mineEnd(s, p, j, why);
            }
            j.knownWhy = io.github.mojolowjo.entropybot.sense.KnownOre.noneWhy(fam);
            j.phase = "mining";
            return seek(s, p, j, j.knownWhy);
        }
        if (j.kKnown == 0) j.kKnown = Math.max(hits.size(), knownCount(p, fam));
        Level level = p.level();
        io.github.mojolowjo.entropybot.sense.OreIndex.Hit best = null;
        double bestCost = Double.MAX_VALUE;
        int buried = 0, tooDeep = 0, deepest = 0, underWater = 0;
        List<String> wetAt = new ArrayList<>();
        int wAbove = p.isInWater() ? io.github.mojolowjo.entropybot.guard.PolicyRuntime.waterAbove(level, p.blockPosition()) : 0;
        int bestTop = Integer.MIN_VALUE;          // with dig: the surface block over a buried ore (the dig box runs from the ore up to it)
        int[] bestTun = null;                     // with dig: the side tunnel (KnownOre.sideTunnel) to the best one
        for (io.github.mojolowjo.entropybot.sense.OreIndex.Hit h : hits) {
            BlockPos q = new BlockPos(h.x(), h.y(), h.z());
            double cost;
            int top = Integer.MIN_VALUE;
            // 0.36.8 (owner's rule): in water, never dig down through solid blocks to an ore (no air down there); an ore
            // the water touches is fine (sink to it). Skipped here, no try spent; the next known ore is picked.
            // aa-costs: skipped only when the drowning risk of the dig is fatal (DrownRisk: air, depth, water above)
            if (level.isLoaded(q) && MineRules.waterDownSkip(p.isInWater(), me[1], h.y(),
                    io.github.mojolowjo.entropybot.guard.Guard.liquidNextTo(level, h.x(), h.y(), h.z()) != null
                            || !level.getFluidState(q.below()).isEmpty(), p.getAirSupply(), wAbove, io.github.mojolowjo.entropybot.guard.PolicyRuntime.pickTier(p))
                    && !airFace(level, h.x(), h.y(), h.z())) {         // 0.36.9: an ore with a dry open face is walked to (a cave beside it)
                underWater++;
                if (wetAt.size() < 5) wetAt.add(h.x() + " " + h.y() + " " + h.z());
                continue;
            }
            // 0.28.0 live (the owner's shells one over the floor): with dig, a 2-high side tunnel at the ore's level first
            int[] tun = j.dig && level.isLoaded(q) ? io.github.mojolowjo.entropybot.sense.KnownOre.sideTunnel(me[0], me[2], h.x(), h.y(), h.z(), (x, y, z) -> {
                BlockPos c = new BlockPos(x, y, z);
                return level.getBlockState(c).getCollisionShape(level, c).isEmpty();
            }, 16) : null;
            if (tun != null) {
                cost = Math.sqrt(h.d2()) + (tun[0] <= tun[3] ? (Math.abs(tun[3] - tun[0]) + Math.abs(tun[5] - tun[2]) + 1) * 2 : 0);
                if (cost < bestCost) {
                    bestCost = cost;
                    best = h;
                    bestTop = Integer.MIN_VALUE;
                    bestTun = tun;
                }
                continue;
            }
            if (level.isLoaded(q) && exposed(level, q)) cost = Math.sqrt(h.d2()) * (skyOpen(level, q) ? 1 : 2);      // open to the sky first, then caves
            else if (j.dig && level.isLoaded(q)) {
                // 0.28.0 (the owner): "mine ... dig" digs down to a covered ore, as "dig x1 y1 z1 x2 y2 z2 ores" would: the column over it
                top = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE, h.x(), h.z()) - 1;
                int depth = top - h.y() + 1;
                // AA round 2 wave 2: the at form (AA picked this ore and costed its depth) digs to IronOptions.DIG_HARD
                if (depth > (j.at != null ? io.github.mojolowjo.entropybot.aa.IronOptions.DIG_HARD : 16)) {
                    tooDeep++;
                    deepest = Math.max(deepest, depth);
                    continue;
                }
                cost = Math.sqrt(h.d2()) + depth * 2;
            } else {
                buried++;
                continue;
            }
            if (cost < bestCost) {
                bestCost = cost;
                best = h;
                bestTop = top;
                bestTun = null;
            }
        }
        if (underWater > 0) LOG.info("[entropybot] mine: skipped {} known {} below me that I'd have to dig down to from in the water (no air down there; ore the water touches or with a dry face is fine): {}", underWater, sid(j), String.join("; ", wetAt));
        if (best == null && tooDeep > 0) LOG.info("[entropybot] mine: {} known {} too deep for a dig ({} blocks), use mine strip", tooDeep, sid(j), deepest);
        if (best == null && j.at != null)
            return atEnd(s, p, j, underWater > 0 ? "it is below me and I'm in the water" : tooDeep > 0 ? "too deep for a dig (" + deepest + " blocks)" : "buried, and this is the walk form (dig digs to it)");
        if (best == null) {
            for (io.github.mojolowjo.entropybot.sense.OreIndex.Hit h : hits) j.knownTried.add(BlockPos.asLong(h.x(), h.y(), h.z()));
            if (underWater == hits.size()) {          // 0.36.9: every one skipped by the water rule - say so, not "buried"
                LOG.info("[entropybot] mine: all {} nearest known {} are below me and I'm in the water - out of the water first", hits.size(), sid(j));
                return mineEnd(s, p, j, "all " + hits.size() + " nearest known " + sid(j) + " are below me and I'm in the water (I don't dig down from in the water) - out of the water first");
            }
            LOG.info("[entropybot] mine: the {} nearest known {} are all buried or not loaded (I walk only to ore with an open face)", hits.size(), sid(j));
            return mineEnd(s, p, j, io.github.mojolowjo.entropybot.sense.KnownOre.unreachableText(fam, j.kKnown, j.kTries) + (tooDeep > 0 ? " (" + tooDeep + " too deep for a dig (" + deepest + " blocks), use mine strip)" : " (" + buried + " nearest are buried; mine ... dig digs to them)"));
        }
        j.kVein = new ArrayList<>();
        if (j.at != null) j.kVein = atVein(level, j);
        else for (io.github.mojolowjo.entropybot.sense.OreIndex.Hit v : io.github.mojolowjo.entropybot.sense.KnownOre.vein(hits, best, io.github.mojolowjo.entropybot.sense.KnownOre.VEIN_MAX))
            j.kVein.add(new Pos(v.x(), v.y(), v.z()));
        j.anchor = me;
        MineRules.trail(j.trail, me);
        int[] t = {best.x(), best.y(), best.z()};
        if (j.kMerged != null) {
            int mem = j.kFarCache == null ? 0 : j.kFarCache.size();
            LOG.info("[entropybot] mine: pick sources for {}: {} from the index, {} from the live scan only, {} remembered by Baritone; chosen {} from {}", sid(j),
                    j.kMerged.fromIndex(), j.kMerged.liveOnly(), mem, Jobs.fmt(t), j.kMerged.sourceOf(t[0], t[1], t[2], j.kIdxKeys));
        }
        LOG.info("[entropybot] mine: next known {} at {} ({},{} blocks; try {}/{}; {} buried ones skipped)", sid(j), Jobs.fmt(t),
                skyOpen(level, new BlockPos(t[0], t[1], t[2])) ? "open to the sky" : "in a cave", (int) Math.round(Math.sqrt(best.d2())),
                j.kTries + 1, io.github.mojolowjo.entropybot.sense.KnownOre.MAX_TARGETS, buried);
        if (bestTun != null) {
            // walk to the stand cell (the walk step goes round the shell), dig the 2-high side cells, then the vein clear from there
            int[] stand = {bestTun[6], bestTun[7], bestTun[8]};
            List<Seq.Step> steps = new ArrayList<>();
            if (Jobs.distSq(me, stand) > 2) {
                Seq.Step w = Seq.Step.walk(stand, true);
                w.optional = true;
                steps.add(w);
            }
            if (bestTun[0] <= bestTun[3]) {
                Seq.Step box = Clearing.clearStep(new ClearJob.Options().box(io.github.mojolowjo.entropybot.clear.ClearBox.of(bestTun[0], bestTun[1], bestTun[2], bestTun[3], bestTun[4], bestTun[5]))
                        .collect(true).label("digging beside the known " + sid(j) + " at " + Jobs.fmt(t)));
                box.optional = true;
                steps.add(box);
            }
            Seq.Step vein = Clearing.clearStep(new ClearJob.Options().only(new ArrayList<>(j.kVein)).collect(true).soft(true).explainSpots(true).waterOk(true)
                    .label("mining " + j.kVein.size() + " known " + sid(j) + " blocks"));
            vein.optional = true;
            steps.add(vein);
            j.kClear = vein;
            if (j.at != null) j.atVeinDone = true;
            j.kLast = t;
            j.kDigging = bestTun[0] <= bestTun[3];
            LOG.info("[entropybot] mine: side tunnel to the known {} at {}: stand {}{}", sid(j), Jobs.fmt(t), Jobs.fmt(stand),
                    bestTun[0] <= bestTun[3] ? ", dig " + bestTun[0] + " " + bestTun[1] + " " + bestTun[2] + " to " + bestTun[3] + " " + bestTun[4] + " " + bestTun[5] : ", nothing to dig");
            s.setStatus("mining " + sid(j) + ": " + mineCount(p, j) + "/" + j.count + " - to known ore at " + Jobs.fmt(t) + " from the side");
            spliceHere(s, steps);
            return "wait";
        }
        if (bestTop != Integer.MIN_VALUE) {
            // walk to the surface over it, then the box clear of the column (ores included), like the dig verb; its outcome is the try
            int[] stand = {t[0], bestTop + 1, t[2]};
            Seq.Step w = Seq.Step.walk(stand, true);
            w.optional = true;
            Seq.Step box = Clearing.clearStep(new ClearJob.Options().box(io.github.mojolowjo.entropybot.clear.ClearBox.of(t[0], t[1], t[2], t[0], bestTop, t[2]))
                    .collect(true).label("digging to the known " + sid(j) + " at " + Jobs.fmt(t) + " (" + (bestTop - t[1] + 1) + " blocks down)"));
            box.optional = true;
            j.kClear = box;
            j.kLast = t;
            j.kDigging = true;
            s.setStatus("mining " + sid(j) + ": " + mineCount(p, j) + "/" + j.count + " - digging down to known ore at " + Jobs.fmt(t));
            spliceHere(s, Jobs.distSq(me, stand) <= 16 ? List.of(box) : List.of(w, box));
            return "wait";
        }
        // 0.36.a3 (aa370: 1 block short of -42 59 23 it swam 25 s at the surface): within the clear's reach (the same 20 as
        // the walk's end check) the vein clear starts from here
        if (Jobs.distSq(me, t) <= 20) return knownClear(s, j, t);
        j.kTarget = t;
        j.kFarId = null;
        j.kWaterDown = false;
        Seq.Step w = Seq.Step.walk(t.clone(), true);
        w.optional = true;
        w.watch = walkWatch(j, null, t);
        s.setStatus("mining " + sid(j) + ": " + mineCount(p, j) + "/" + j.count + " - walking to known ore at " + Jobs.fmt(t) + " (" + (int) Math.round(Math.sqrt(best.d2())) + " blocks)");
        spliceHere(s, List.of(w));
        return "wait";
    }

    /** "2 iron_ore, 1 deepslate_iron_ore". */
    private static String blocksText(MineRun j) {
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, Integer> e : j.kBlocks.entrySet()) parts.add(e.getValue() + " " + e.getKey());
        return String.join(", ", parts);
    }

    /** Listed positions of the family within the mine's reach (for the end line). */
    private int knownCount(LocalPlayer p, String fam) {
        int[] me = Jobs.here(p);
        io.github.mojolowjo.entropybot.sense.OreIndex.Count n = pass().index().within(dim(), me[0], me[1], me[2], io.github.mojolowjo.entropybot.memory.Limits.SENSE_MINE_R).get(fam);
        return n == null ? 0 : n.n();
    }
    /** A target the bot couldn't get to: a try is spent; past the cap the job ends (known, but unreachable). */
    private String knownMiss(Seq s, LocalPlayer p, MineRun j, int[] t, String why) {
        if (j.at != null) return atEnd(s, p, j, why);
        j.knownTried.add(BlockPos.asLong(t[0], t[1], t[2]));
        // 0.28.0 live: only the target is spent; its vein neighbours stay candidates (the clear's patches drop what it mined)
        j.kTries++;
        LOG.info("[entropybot] mine: couldn't get to the known {} at {} ({}; try {}/{})", sid(j), Jobs.fmt(t), why, j.kTries, io.github.mojolowjo.entropybot.sense.KnownOre.MAX_TARGETS);
        if (j.kTries >= io.github.mojolowjo.entropybot.sense.KnownOre.MAX_TARGETS)
            return mineEnd(s, p, j, io.github.mojolowjo.entropybot.sense.KnownOre.unreachableText(io.github.mojolowjo.entropybot.sense.KnownOre.familyOf(j.target), j.kKnown, j.kTries));
        return "wait";
    }

    /** An open face of q whose air sees the sky (a surface hollow, a ravine): reachable by walking. */
    private static boolean skyOpen(Level level, BlockPos q) {
        for (net.minecraft.core.Direction dir : net.minecraft.core.Direction.values()) {
            BlockPos n = q.relative(dir);
            if (level.getBlockState(n).getCollisionShape(level, n).isEmpty() && level.getBrightness(LightLayer.SKY, n) > 0) return true;
        }
        return false;
    }

    /** Seq: a spliced known-ore walk or dig (optional) failed: drop the rest of it, the mine tries the next target. */
    boolean caught(Seq s, String r) {
        if (!(runs.get(s) instanceof MineRun j) || !j.phase.equals("known") || (j.kTarget == null && j.kClear == null)) return false;
        int k = s.idx;
        while (k < s.steps.size() && !s.steps.get(k).type.equals("mineore")) k++;
        if (k >= s.steps.size()) return false;
        s.steps.subList(s.idx, k).clear();
        s.stage = null;
        s.stepStart = now();
        j.kFailed = r == null ? "failed" : r.length() > 160 ? r.substring(0, 160) : r;
        return true;
    }

    /** The at form's vein: the named cell and the loaded same-family cells touching it (MineRules.vein, at most AT_VEIN), in my areas. */
    private List<Pos> atVein(Level level, MineRun j) {
        String fam = io.github.mojolowjo.entropybot.sense.KnownOre.familyOf(j.target);
        String d = dim();
        List<Pos> out = new ArrayList<>();
        for (int[] c : MineRules.vein(j.at, n -> {
            BlockPos q = new BlockPos(n[0], n[1], n[2]);
            return level.isLoaded(q) && commands.inAreas(d, n[0], n[2]) && jobs.goalAllowed(n[0], n[1], n[2]) == null
                    && io.github.mojolowjo.entropybot.sense.KnownOre.family(BuiltInRegistries.BLOCK.getKey(level.getBlockState(q).getBlock()).toString(), fam);
        }, MineRules.AT_VEIN)) out.add(new Pos(c[0], c[1], c[2]));
        return out;
    }

    /** The at form's end: done with what it mined (raw ore or block in the bag), else why it couldn't. */
    private String atEnd(Seq s, LocalPlayer p, MineRun j, String why) {
        int n = mineCount(p, j);
        String cell = Jobs.fmt(j.at);
        if (n > 0) return end(s, j, MineRules.atDoneText(n, io.github.mojolowjo.entropybot.sense.KnownOre.familyOf(j.target), cell, gains(p, j)));
        return end(s, j, MineRules.atFailText(j.target, cell, why, gains(p, j)));
    }

    /** The vein's cells to the clear engine (the strip mine's and the cave's vein clear), spliced in front of this step. */
    private String knownClear(Seq s, MineRun j, int[] target) {
        j.kLast = target;
        if (j.at != null) {
            // the at form: the vein as the world shows it now (the target's chunk may have loaded on the walk)
            Level lv = Minecraft.getInstance().level;
            if (lv != null) j.kVein = atVein(lv, j);
            j.atVeinDone = true;
        }
        // (nothing marked tried here: a vein clear that mines nothing costs a try on its target only)
        Seq.Step clear = Clearing.clearStep(new ClearJob.Options().only(new ArrayList<>(j.kVein)).collect(true).soft(true).explainSpots(true).waterOk(true)
                .label("mining " + j.kVein.size() + " known " + sid(j) + " blocks"));
        clear.optional = true;
        j.kClear = clear;
        spliceHere(s, List.of(clear));
        return "wait";
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
            case "explorescout": return scoutStep(s, p);
            case "exploretally": return tallyStep(s, p);
            case "mineore": return mineStep(s, st, p);
            case "mineoretool": return toolGetStep(s, st, p);
            case "mineorecheck": return toolCheckStep(s, st, p);
            default: return "unknown step " + st.type;
        }
    }

    // ==== explore ====

    static final class ExRun {
        long until, start, targetAt, scanAt, findAt = -1;
        int chunks, sx, sz;
        /** 0.33.0: where the walk started (the report says how far it went). */
        int[] from;
        String dir;
        /** V1b find: what to look for (null: a plain explore). */
        io.github.mojolowjo.entropybot.vocab.ExploreWords.Find find;
        String found;
        final Set<Integer> poiIds = new HashSet<>();
        final Set<String> bases = new HashSet<>();
        ExploreRules.Target target;
        /** 0.23.1: gathering outside the areas (explore ... gather off = false); the bot's spot for the roam permission; the bases seen. */
        boolean gather = true;
        final int[] at = new int[3];
        final List<int[]> baseSpots = new java.util.concurrent.CopyOnWriteArrayList<>();
        long gatherAt;
        int gathers, gatherMisses;
        /** 0.33.1: ticks spent gathering, the gather in progress's start (0: none), the chunk count at the last gather. */
        long gatherTicks, gatherStart;
        int chunksAtGather;
        Map<String, Integer> before;
        final Map<String, Integer> took = new LinkedHashMap<>();
    }

    /** 0.23.1: at most this many gathers (a tree, a surface vein) per explore/find run; a look round every 2 s while out there. */
    static final int GATHER_MAX = 8, GATHER_EVERY = 40, GATHER_LOGS_BELOW = 32;

    /**
     * V1b: "explore [north|south|east|west] [minutes]": never-seen land (explored.json), inside or outside the areas (a
     * walk lease: explore may leave them, never into a safe area), then home; someone's base out there is kept off and
     * noted. The report names the new points of interest.
     */
    String explore(LocalPlayer p, String rest) {
        io.github.mojolowjo.entropybot.vocab.ExploreWords.Args a = io.github.mojolowjo.entropybot.vocab.ExploreWords.parse(rest, io.github.mojolowjo.entropybot.vocab.ExploreWords.DEFAULT_MINUTES);
        if (a.error() != null) return a.error();
        return startExplore(p, a.dir(), a.minutes(), null, a.gather());
    }

    /** V1b: "find cave|<poi kind>|<biome> [minutes]": a known one answers at once; else explore until one turns up. */
    String find(LocalPlayer p, io.github.mojolowjo.entropybot.vocab.ExploreWords.Find f) {
        if (f.kind() == io.github.mojolowjo.entropybot.vocab.ExploreWords.FindKind.POI || f.kind() == io.github.mojolowjo.entropybot.vocab.ExploreWords.FindKind.CAVE) {
            int[] me = Jobs.here(p);
            ExploreRules.Poi best = null;
            long bd = Long.MAX_VALUE;
            for (ExploreRules.Poi q : pois()) {
                if (!q.kind().equals(f.what())) continue;
                long dx = q.x() - me[0], dz = q.z() - me[2], d = dx * dx + dz * dz;
                if (d < bd) { bd = d; best = q; }
            }
            if (best != null) return "ok: I know a " + f.what() + ": #" + best.id() + " at " + best.x() + " " + best.y() + " " + best.z() + " (" + Math.round(Math.sqrt(bd)) + "m) - go poi " + best.id();
        }
        return startExplore(p, null, f.minutes(), f, f.gather());
    }

    private String startExplore(LocalPlayer p, String dir, int minutes, io.github.mojolowjo.entropybot.vocab.ExploreWords.Find find, boolean gather) {
        String busy = busyText();
        if (busy != null) return busy;
        try {
            policy();
        } catch (MineRules.BadPolicy e) {
            return "error: " + MineRules.badPolicyText(e).replace("I won't mine", "I won't explore");
        }
        ExRun ex = new ExRun();
        ex.until = now() + minutes * 1200L;
        ex.start = now();
        ex.dir = dir;
        ex.find = find;
        int[] me = Jobs.here(p);
        ex.sx = me[0] >> 4;
        ex.sz = me[2] >> 4;
        ex.from = me.clone();
        for (ExploreRules.Poi q : pois()) ex.poiIds.add(q.id());
        String label = find != null ? "looking for a " + find.what().replaceFirst("^minecraft:", "") + " (" + minutes + " min at most)"
                : "exploring " + (dir != null ? dir + " " : "") + "for " + minutes + " min";
        Seq s = new Seq(jobs, storage, label, List.of(new Seq.Step("explore")), "always");
        runs.put(s, ex);
        String r = jobs.startSeq(s, "always");
        jobs.job.holdOnFight = true;              // a fight holds the walk (the mod fights), it doesn't end it
        jobs.job.goalInside = true;               // V1b: explore and find may walk outside the areas (the position watch leaves it be)
        ex.gather = gather;
        System.arraycopy(me, 0, ex.at, 0, 3);
        return r + (gather ? "" : " (no gathering while exploring)");
    }

    /** 0.23.1: a gather while exploring: a tree (when short of logs) or an exposed ore vein; null = nothing. AREAS: anywhere not protected, keeping off bases seen. */
    private List<Seq.Step> gatherSteps(Seq s, ExRun ex, LocalPlayer p) {
        String d = dim();
        if (!ex.gather) return null;
        final List<int[]> bases = ex.baseSpots;
        java.util.function.Predicate<int[]> ok = c -> {
            if (!commands.inAreas(d, c[0], c[2])) return false;
            for (int[] b : bases) if (Math.abs(c[0] - b[0]) <= 16 && Math.abs(c[2] - b[2]) <= 16) return false;
            return true;
        };
        int logs = 0;
        for (Map.Entry<String, Integer> e : Gui.inventory(p).entrySet()) if (Chopping.isLogItem(e.getKey())) logs += e.getValue();
        if (logs < GATHER_LOGS_BELOW) {
            List<Seq.Step> t = Chopping.get().gatherTree(s, p, ok);
            if (t != null) return t;
        }
        List<Pos> vein = surfaceVein(p, ok);
        if (vein.isEmpty()) {
            if (ex.gatherMisses++ < 3) LOG.info("[entropybot] explore: nothing to take near {} {} (logs on me {})", Jobs.here(p)[0], Jobs.here(p)[2], logs);
            return null;
        }
        Pos f = vein.get(0);
        LOG.info("[entropybot] explore: mining {} exposed ore blocks at {} {} {} outside my areas", vein.size(), f.x(), f.y(), f.z());
        List<Seq.Step> add = new ArrayList<>();
        add.add(Seq.Step.walk(new int[]{f.x(), f.y(), f.z()}, true));
        add.add(Clearing.clearStep(new ClearJob.Options().only(vein).soft(true).label("mining the ore at " + f.x() + " " + f.y() + " " + f.z())));
        return add;
    }

    /** An ore within 8 blocks that touches open sky-lit air (a surface ore) and its vein (same block, joined, 8 at most). */
    private List<Pos> surfaceVein(LocalPlayer p, java.util.function.Predicate<int[]> ok) {
        Level level = p.level();
        int[] me = Jobs.here(p);
        BlockPos.MutableBlockPos q = new BlockPos.MutableBlockPos();
        for (int r = 1; r <= 8; r++) for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) {
            if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
            for (int dy = -4; dy <= 4; dy++) {
                q.set(me[0] + dx, me[1] + dy, me[2] + dz);
                if (!level.isLoaded(q)) continue;
                BlockState st = level.getBlockState(q);
                if (!st.is(Tags.Blocks.ORES) || !ok.test(new int[]{q.getX(), q.getY(), q.getZ()}) || !skyTouching(level, q)) continue;
                List<Pos> vein = new ArrayList<>();
                ArrayDequeHolder.fill(level, q.immutable(), st.getBlock(), ok, vein);
                return vein;
            }
        }
        return List.of();
    }

    private static boolean skyTouching(Level level, BlockPos at) {
        for (net.minecraft.core.Direction dir : net.minecraft.core.Direction.values()) {
            BlockPos n = at.relative(dir);
            if (level.getBlockState(n).isAir() && level.getBrightness(LightLayer.SKY, n) > 0) return true;
        }
        return false;
    }

    /** The vein's flood fill (joined blocks of one kind, at most 8). */
    private static final class ArrayDequeHolder {
        static void fill(Level level, BlockPos start, Block kind, java.util.function.Predicate<int[]> ok, List<Pos> out) {
            java.util.ArrayDeque<BlockPos> todo = new java.util.ArrayDeque<>(List.of(start));
            Set<BlockPos> seen = new HashSet<>(List.of(start));
            while (!todo.isEmpty() && out.size() < 8) {
                BlockPos c = todo.poll();
                out.add(new Pos(c.getX(), c.getY(), c.getZ()));
                for (net.minecraft.core.Direction dir : net.minecraft.core.Direction.values()) {
                    BlockPos n = c.relative(dir);
                    if (seen.add(n) && level.isLoaded(n) && level.getBlockState(n).is(kind) && ok.test(new int[]{n.getX(), n.getY(), n.getZ()})) todo.add(n);
                }
            }
        }
    }

    static String tookText(Map<String, Integer> took) { return io.github.mojolowjo.entropybot.vocab.ExploreWords.took(took); }

    /** 0.23.1: after a gather, what came into the bag goes into the run's tally (the report's "took ..."). */
    private String tallyStep(Seq s, LocalPlayer p) {
        ExRun ex = runs.get(s) instanceof ExRun x ? x : null;
        if (ex != null && ex.gatherStart > 0) {
            ex.gatherTicks += now() - ex.gatherStart;
            ex.gatherStart = 0;
        }
        if (ex == null || ex.before == null) return "next";
        Map<String, Integer> now = Gui.inventory(p);
        for (Map.Entry<String, Integer> e : now.entrySet()) {
            int gained = e.getValue() - ex.before.getOrDefault(e.getKey(), 0);
            if (gained > 0) ex.took.merge(e.getKey(), gained, Integer::sum);
        }
        ex.before = null;
        return "next";
    }

    /** V1b: a scan of 16 blocks round the bot (y -6..+6) for someone's base; only outside the areas. */
    private io.github.mojolowjo.entropybot.vocab.ExploreWords.Scan baseScan(LocalPlayer p) {
        Level level = p.level();
        int[] me = Jobs.here(p);
        int built = 0, be = 0, named = 0, villagers = 0, wet = 0, open = 0, r = io.github.mojolowjo.entropybot.vocab.ExploreWords.BASE_R;
        java.util.Map<String, Integer> kinds = new java.util.TreeMap<>();
        BlockPos.MutableBlockPos q = new BlockPos.MutableBlockPos();
        io.github.mojolowjo.entropybot.guard.Guard g = io.github.mojolowjo.entropybot.guard.Guard.INSTANCE;
        for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) for (int dy = -6; dy <= 6; dy++) {
            q.set(me[0] + dx, me[1] + dy, me[2] + dz);
            if (!level.isLoaded(q)) continue;
            BlockState st = level.getBlockState(q);
            if (st.isAir()) continue;
            String id = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(st.getBlock()).getPath();
            boolean prot = g.isProtectedBlock(st.getBlock());
            if (level.getBlockEntity(q) != null) { if (!io.github.mojolowjo.entropybot.vocab.NaturalStructures.wildBlockEntity(id)) be++; }
            else if (prot) built++;
            if (prot || level.getBlockEntity(q) != null || io.github.mojolowjo.entropybot.vocab.NaturalStructures.clue(id)) {
                kinds.merge(id, 1, Integer::sum);
                if (prot && !st.getFluidState().isEmpty()) wet++;
                if (prot && level.canSeeSky(q.above())) open++;
            }
        }
        for (net.minecraft.world.entity.Entity e : level.getEntities(p, p.getBoundingBox().inflate(r))) {
            if (e instanceof net.minecraft.world.entity.npc.AbstractVillager || e instanceof net.minecraft.world.entity.animal.IronGolem) villagers++;
            if (!(e instanceof net.minecraft.world.entity.player.Player) && e.hasCustomName() && e.getCustomName() != null
                    && io.github.mojolowjo.entropybot.vocab.AttackRules.isName(e.getCustomName().getString())) named++;
        }
        // 0.33.0: the world's own structures (a village's loot chests, beds, hay) are not someone's base
        String natural = null;
        try {
            natural = io.github.mojolowjo.entropybot.vocab.NaturalStructures.explains(new io.github.mojolowjo.entropybot.vocab.NaturalStructures.Seen(kinds, villagers, wet, open));
        } catch (RuntimeException ex) {
            LOG.warn("[entropybot] natural structure check: {}", ex.toString());
        }
        if (natural != null && (built >= io.github.mojolowjo.entropybot.vocab.ExploreWords.BASE_BUILT || be > 0))
            LOG.info("[entropybot] base scan: {} built, {} block entities near {} - {} (the world's own), not a base; kinds {}", built, be, Jobs.fmt(me), natural, kinds);
        else if (built >= io.github.mojolowjo.entropybot.vocab.ExploreWords.BASE_BUILT || be > 0)
            LOG.info("[entropybot] base scan: {} built, {} block entities near {} - no world structure explains it; kinds {}", built, be, Jobs.fmt(me), kinds);
        return new io.github.mojolowjo.entropybot.vocab.ExploreWords.Scan(built, be, named, natural);
    }

    /** V1b find: dark air below the surface within 8 blocks (a cave under the bot). */
    private int darkAirBelow(LocalPlayer p) {
        Level level = p.level();
        int[] me = Jobs.here(p);
        int n = 0;
        BlockPos.MutableBlockPos q = new BlockPos.MutableBlockPos();
        for (int dx = -8; dx <= 8; dx += 2) for (int dz = -8; dz <= 8; dz += 2) {
            int top = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE, me[0] + dx, me[2] + dz);
            for (int y = top - 4; y >= Math.max(level.getMinBuildHeight(), top - 40); y--) {
                q.set(me[0] + dx, y, me[2] + dz);
                if (level.isLoaded(q) && level.getBlockState(q).isAir() && level.getBrightness(LightLayer.SKY, q) == 0) n++;
            }
        }
        return n;
    }

    /** V1b find: did it turn up (a new POI of the kind, the biome under the bot, a cave)? The found text, or null. */
    private String findCheck(ExRun ex, LocalPlayer p) {
        io.github.mojolowjo.entropybot.vocab.ExploreWords.Find f = ex.find;
        int[] me = Jobs.here(p);
        switch (f.kind()) {
            case POI -> {
                for (ExploreRules.Poi q : pois()) if (q.kind().equals(f.what()) && !ex.poiIds.contains(q.id()))
                    return "found a " + f.what() + ": #" + q.id() + " at " + q.x() + " " + q.y() + " " + q.z() + " (go poi " + q.id() + ")";
            }
            case BIOME -> {
                String b = p.level().getBiome(new BlockPos(me[0], me[1], me[2])).unwrapKey().map(k -> k.location().toString()).orElse("");
                if (b.equals(f.what())) {
                    var poi = core.pois.saw("biome " + f.what().replaceFirst("^minecraft:", ""), me[0], me[1], me[2], dim(), System.currentTimeMillis(), now(), null);
                    return "found " + f.what().replaceFirst("^minecraft:", "") + " at " + me[0] + " " + me[1] + " " + me[2] + (poi != null ? " (poi #" + poi.id() + ")" : "");
                }
            }
            case CAVE -> {
                // live aa-seen2: this under-the-feet test never ran before (the step's ticks missed "% 20 == 5"); once it ran
                // it called the spawn's plain ground a cave. Kept on its old schedule; the sightings below are the finder.
                if (now() % 20 == 5 && io.github.mojolowjo.entropybot.vocab.ExploreWords.isCave(darkAirBelow(p))) {
                    var poi = core.pois.saw("cave", me[0], me[1], me[2], dim(), System.currentTimeMillis(), now(), null);
                    return "found a cave under " + me[0] + " " + me[1] + " " + me[2] + (poi != null ? " (poi #" + poi.id() + "; mine cave <ores> there)" : "");
                }
                // AA_ROUND3 5: a cave opening seen (Sightings: in view, line of sight, two glances) within 64
                var sg = pass().sightings();
                var open = sg == null ? null : sg.nearestCave(dim(), me[0], me[1], me[2], io.github.mojolowjo.entropybot.aa.brain.Discovery.SEEN_CAVE_WITHIN);
                if (open != null) return "saw a cave opening at " + open.where() + " (" + Math.round(Math.sqrt(Jobs.distSq(me, new int[]{open.x(), open.y(), open.z()}))) + " away; mine cave <ores> starts there)";
            }
            default -> { }
        }
        return null;
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
        // review 0.33.2: a gather that ended without its tally step (failed, skipped) is closed here, never left open
        if (ex.gatherStart > 0) {
            LOG.info("[entropybot] explore: gather {} ended without its tally ({} ticks)", ex.gathers, now() - ex.gatherStart);
            ex.gatherTicks += now() - ex.gatherStart;
            ex.gatherStart = 0;
            ex.before = null;
        }
        IBaritone b = Jobs.baritone();
        int[] me = Jobs.here(p);
        String d = dim();
        ex.at[0] = me[0];
        ex.at[1] = me[1];
        ex.at[2] = me[2];
        if (now() % 20 == 0 && notes().markExplored(ExploreRules.around(d, me[0], me[2])) > 0) ex.chunks++;
        String done = now() > ex.until ? "the time is up" : null;
        // aa385: the leash - never farther than LEASH blocks from my areas (live: explore went 363 blocks out); the
        // brain's return leg (aa.core.WayBack) brings it back
        // owner 2026-10-20: no leash any more - exploring may leave the areas freely; the way home is priced instead
        // (Discovery's explore row: the return trip to my areas, night-weighted, within the horizon)
        // V1b find: stop when it turned up
        // AA_ROUND3 5 (live aa-seen2: the step runs on even ticks, so "% 20 == 5" never came): once a second by the clock
        if (ex.find != null && done == null && (ex.findAt < 0 || now() - ex.findAt >= 20)) {
            ex.findAt = now();
            try {
                ex.found = findCheck(ex, p);
            } catch (RuntimeException e) {
                LOG.warn("[entropybot] find: {}", e.toString());
            }
            if (ex.found != null) {
                s.note = ex.found + tookText(ex.took);
                if (b != null) Jobs.cancel(b);
                notes().flush();
                return "next";
            }
        }
        // V1b: outside the areas, someone's base within 16: keep off (mark its chunks seen), note it once, walk elsewhere
        if (done == null && now() - ex.scanAt >= 100 && !commands.inAreas(d, me[0], me[2])) {
            ex.scanAt = now();
            try {
                io.github.mojolowjo.entropybot.vocab.ExploreWords.Scan sc = baseScan(p);
                if (io.github.mojolowjo.entropybot.vocab.ExploreWords.isBase(sc)) {
                    String key = (me[0] >> 5) + " " + (me[2] >> 5);
                    List<String> around = new ArrayList<>();
                    for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) around.add(ExploreRules.key(d, (me[0] >> 4) + dx, (me[2] >> 4) + dz));
                    notes().markExplored(around);
                    boolean known = false;
                    for (int[] bs : ex.baseSpots) if (Math.abs(bs[0] - me[0]) <= 16 && Math.abs(bs[2] - me[2]) <= 16) { known = true; break; }
                    if (!known) ex.baseSpots.add(me.clone());      // 0.23.1: roaming keeps 16 off it
                    if (ex.bases.add(key)) {
                        String note = io.github.mojolowjo.entropybot.vocab.ExploreWords.baseNote(sc, me[0], me[1], me[2]);
                        LOG.info("[entropybot] explore: {}", note);
                        commands.noteSafeCandidate(d, me, sc);
                        commands.whisper(commands.owner(), note);
                    }
                    ex.target = null;
                    if (b != null) Jobs.cancel(b);
                }
            } catch (RuntimeException e) {
                LOG.warn("[entropybot] explore base scan: {}", e.toString());
            }
        }
        // 0.23.1 (VOCABULARY 6b): outside the areas, off any base, take what keeps it going: a tree, an exposed ore vein
        // 0.33.1: only as a side effect of the walk (ExploreRules.gatherAllowed), so a short explore still walks
        if (done == null && ex.gather && ex.gathers < GATHER_MAX && now() - ex.gatherAt >= GATHER_EVERY && !commands.inAreas(d, me[0], me[2])
                && ExploreRules.gatherAllowed(now() - ex.start, ex.until - now(), ex.gatherTicks, ex.chunks, ex.chunksAtGather)) {
            ex.gatherAt = now();
            try {
                if (!io.github.mojolowjo.entropybot.vocab.ExploreWords.isBase(baseScan(p))) {
                    List<Seq.Step> add = gatherSteps(s, ex, p);
                    if (add != null) {
                        if (b != null) Jobs.cancel(b);
                        ex.target = null;
                        ex.gathers++;
                        ex.chunksAtGather = ex.chunks;
                        ex.gatherStart = now();
                        LOG.info("[entropybot] explore: gather {} after {} chunks ({} of {} ticks spent gathering)", ex.gathers, ex.chunks, ex.gatherTicks, now() - ex.start);
                        ex.before = Gui.inventory(p);
                        add = new ArrayList<>(add);
                        add.add(new Seq.Step("exploretally"));
                        s.splice(s.idx, add);
                        s.stepStart = now();
                        s.stage = null;
                        s.setStatus(s.label + " - gathering on the way (" + ex.gathers + ")");
                        return "wait";
                    }
                }
            } catch (RuntimeException e) {
                LOG.warn("[entropybot] explore gather: {}", e.toString());
            }
        }
        if (done == null && ex.target != null && (now() - ex.targetAt < 20 || (b != null && !Jobs.idle(b) && now() - ex.targetAt < ExploreRules.WALK_TICKS))) {
            s.setStatus(s.label + " - walking to " + ex.target.x() + " " + ex.target.z() + " (" + ex.chunks + " chunks so far)");
            return "wait";
        }
        // didn't get there (water, cliffs): give that chunk up
        if (ex.target != null && !ExploreRules.arrived(me[0], me[2], ex.target)) notes().markExplored(List.of(ExploreRules.key(d, ex.target.x() >> 4, ex.target.z() >> 4)));
        ex.target = null;
        if (done == null) {
            // V1b: the nearest never-seen chunk (that way, with a direction), inside or outside the areas; never a refused spot
            final int y = me[1];
            int[] c = io.github.mojolowjo.entropybot.vocab.ExploreWords.next(me[0] >> 4, me[2] >> 4, ex.sx, ex.sz, ex.dir,
                    // 0.23.1: unvisited = not in explored.json AND no painted map tile (a tile still loading counts as seen this round)
                    (cx, cz) -> io.github.mojolowjo.entropybot.vocab.ExploreWords.seen(notes().explored(ExploreRules.key(d, cx, cz)),
                            core.terrain() == null ? Boolean.FALSE : core.terrain().painted(d, cx, cz)),
                    (cx, cz) -> {
                        String why = io.github.mojolowjo.entropybot.commands.FenceRules.goalAllowed(io.github.mojolowjo.entropybot.api.BotAPI.check(d, (cx << 4) + 8, y, (cz << 4) + 8, "go"),
                                commands.fenceOn(), io.github.mojolowjo.entropybot.guard.AreaTypeRules.Walker.EXPLORE);
                        if (why != null) notes().markExplored(List.of(ExploreRules.key(d, cx, cz)));
                        return why != null;
                    });
            if (c != null) ex.target = new ExploreRules.Target((c[0] << 4) + 8, (c[1] << 4) + 8);
        }
        if (ex.target == null) {
            List<ExploreRules.Poi> fresh = new ArrayList<>();
            for (ExploreRules.Poi q : pois()) if (!ex.poiIds.contains(q.id())) fresh.add(q);
            s.note = ExploreRules.note(ex.chunks, now() - ex.start, done != null ? done : "nothing left to explore in reach", fresh, ex.from, Jobs.here(p));
            if (ex.find != null) s.note = "no " + ex.find.what().replaceFirst("^minecraft:", "") + " found (" + (done != null ? done : "nothing left in reach") + "); " + s.note;
            s.note += tookText(ex.took);
            // owner 2026-10-20 ("done exploring for 5 min" after 1 min): the label says the real time and why it ended
            long realMin = Math.max(0, Math.round((now() - ex.start) / 1200.0));
            String why = done != null ? done : "nothing left to explore in reach";
            s.label = (ex.find != null ? "looking for a " + ex.find.what().replaceFirst("^minecraft:", "") : "exploring")
                    + " - ended after " + realMin + " min (" + why + ")";
            if (s.job() != null) s.job().label = s.label;
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

    // ==== C8 scout ====

    static final class ScRun {
        ScoutRules.Plan plan;
        int[] start, reached;
        int leg = -1;
        long until, legAt;
        String blocked;
        final Map<Integer, String> mobs = new HashMap<>();
    }

    private String lastScout = "no scouting yet";

    /** "scout <dir|x z> [n] [<min>m] [from me]" / "scout status": walk out in legs inside the areas, note, come back. */
    String scout(LocalPlayer p, String rest, int[] ownerAt) {
        ScoutRules.Parsed m = ScoutRules.parse(rest);
        if (m.kind() == ScoutRules.Kind.ERROR) return m.error();
        if (m.kind() == ScoutRules.Kind.STATUS) {
            for (Map.Entry<Seq, Object> e : runs.entrySet()) if (e.getValue() instanceof ScRun && jobs.running()) return "scouting: " + jobs.job.status;
            return "last scout: " + lastScout;
        }
        String busy = busyText();
        if (busy != null) return busy;
        String d = dim();
        try {
            boxes(policy(), "areas");
        } catch (MineRules.BadPolicy e) {
            return "error: " + MineRules.badPolicyText(e).replace("I won't mine", "I won't scout");
        }
        int[] start = Jobs.here(p);
        if (m.fromMe()) {
            if (ownerAt == null) return "error: I can't see you (and no companion position) - say it without \"from me\"";
            start = ownerAt;
        }
        if (!commands.inAreas(d, start[0], start[2])) return "error: " + start[0] + " " + start[2] + " is " + commands.policy.blockedText(d, start[0], start[2]);
        final int y = start[1];
        ScoutRules.Plan plan = ScoutRules.legs(start[0], start[2], m, (x, z) -> commands.inAreas(d, x, z) && jobs.goalAllowed(x, y, z) == null);
        if (plan.legs().isEmpty()) {
            if (plan.cutAt() != null) return "error: " + plan.dirText() + " runs into a keep-out or no-break area at once (" + plan.cutAt()[0] + " " + plan.cutAt()[1] + ")";
            return "error: that is where I start - name a direction or a farther point";
        }
        ScRun sc = new ScRun();
        sc.plan = plan;
        sc.start = start;
        sc.reached = new int[]{start[0], start[2]};
        sc.until = now() + m.minutes() * 1200L;
        List<Seq.Step> steps = new ArrayList<>();
        if (m.fromMe()) steps.add(Seq.Step.walk(start, true));
        steps.add(new Seq.Step("explorescout"));
        Seq s = new Seq(jobs, storage, "scouting " + plan.blocks() + " " + plan.dirText(), steps, "always");
        runs.put(s, sc);
        String r = jobs.startSeq(s, "always");
        jobs.job.holdOnFight = true;              // a fight (and a meal) holds the walk, it doesn't end it
        return r;
    }

    private String scoutStep(Seq s, LocalPlayer p) {
        ScRun sc = runs.get(s) instanceof ScRun x ? x : null;
        if (sc == null) return "the scout run is gone";
        IBaritone b = Jobs.baritone();
        int[] me = Jobs.here(p);
        if (now() % 10 == 0) noteMobs(p, sc);
        if (now() % 20 == 0) notes().markExplored(ExploreRules.around(dim(), me[0], me[2]));
        String why = now() > sc.until ? "the time is up" : null;
        if (why == null && sc.leg >= 0) {
            int[] t = sc.plan.legs().get(sc.leg);
            boolean there = Math.abs(me[0] - t[0]) + Math.abs(me[2] - t[1]) <= 4;
            if (there) {
                sc.reached = new int[]{me[0], me[2]};
            } else if (now() - sc.legAt < 20 || (b != null && !Jobs.idle(b) && now() - sc.legAt < ExploreRules.WALK_TICKS)) {
                s.setStatus(s.label + " - leg " + (sc.leg + 1) + "/" + sc.plan.legs().size() + " to " + t[0] + " " + t[1]);
                return "wait";
            } else {
                sc.reached = new int[]{me[0], me[2]};
                sc.blocked = t[0] + " " + t[1] + " (" + (p.isInWater() ? "water" : p.isInLava() ? "lava" : "no way found") + ")";
            }
        }
        if (why == null && sc.blocked == null && sc.leg + 1 < sc.plan.legs().size()) {
            sc.leg++;
            int[] t = sc.plan.legs().get(sc.leg);
            if (b != null) {
                Jobs.safeSettings();
                b.getCustomGoalProcess().setGoalAndPath(new GoalXZ(t[0], t[1]));
            }
            sc.legAt = now();
            return "wait";
        }
        Map<String, Integer> mobs = new java.util.TreeMap<>();
        for (String k : sc.mobs.values()) mobs.merge(k, 1, Integer::sum);
        String text = ScoutRules.report(sc.plan, sc.start, sc.reached, spotsPois(), spotsOres(), mobs, sc.blocked, why) + "; back to the start";
        lastScout = text;
        s.note = text;
        s.splice(s.idx + 1, List.of(Seq.Step.walk(sc.start, true)));
        notes().flush();
        return "next";
    }

    /** Hostile mobs within 32 (as the defence judges them), by entity id, so one mob counts once. */
    private void noteMobs(LocalPlayer p, ScRun sc) {
        try {
            var h = io.github.mojolowjo.entropybot.engine.Hostility.INSTANCE;
            for (net.minecraft.world.entity.Mob mob : p.level().getEntitiesOfClass(net.minecraft.world.entity.Mob.class, p.getBoundingBox().inflate(32),
                    mob -> mob.isAlive() && h.kind(mob, false).counts())) {
                if (sc.mobs.size() < 500) sc.mobs.putIfAbsent(mob.getId(), BuiltInRegistries.ENTITY_TYPE.getKey(mob.getType()).getPath());
            }
        } catch (RuntimeException e) {
            LOG.warn("[entropybot] scout: mob scan failed: {}", e.toString());
        }
    }

    private List<ScoutRules.Spot> spotsPois() {
        List<ScoutRules.Spot> out = new ArrayList<>();
        for (ExploreRules.Poi q : pois()) out.add(new ScoutRules.Spot(q.kind(), q.x(), q.y(), q.z(), q.id()));
        return out;
    }

    private List<ScoutRules.Spot> spotsOres() {
        List<ScoutRules.Spot> out = new ArrayList<>();
        String d = dim();
        for (Map.Entry<String, JsonObject> e : notes().ores().entrySet()) {
            try {
                JsonObject o = e.getValue();
                if (!(o.has("dim") ? o.get("dim").getAsString() : "minecraft:overworld").equals(d)) continue;
                String[] k = e.getKey().trim().split("\\s+");
                String id = o.get("id").getAsString();
                out.add(new ScoutRules.Spot(id.startsWith("minecraft:") ? id.substring(10) : id, Integer.parseInt(k[0]), Integer.parseInt(k[1]), Integer.parseInt(k[2]), -1));
            } catch (RuntimeException ignored) {}
        }
        return out;
    }

    /** "find nearest <poi kind|ore>". */
    String findNearest(LocalPlayer p, String what) {
        return ScoutRules.nearest(what, spotsPois(), spotsOres(), Jobs.here(p));
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
        /** 0.28.0: known ore spots (the index) this run walked to already. */
        final Set<Long> knownTried = new HashSet<>();

        int got() { return spec.count(tally); }
    }

    String startCave(LocalPlayer p, MineGrammar.Parsed m) {
        if (bagRoom(p) <= CaveRules.FREE) return "error: my bag is nearly full - \"deposit\" first";
        protectBoxes();                           // S1: fail closed - a policy it can't read refuses here (BadPolicy, caught in mine())
        int[] me = Jobs.here(p);
        // AA_ROUND3 5: no known cave within reach but a cave opening I have seen within 64: the cave starts there (walk first)
        int[] at = me, walkTo = null;
        if (m.at() == null || m.at().isEmpty()) {
            Caves.Cave k = core.caves.nearest(dim(), me[0], me[1], me[2]);
            boolean knownNear = k != null && Jobs.distSq(me, new int[]{k.ex, k.ey, k.ez}) <= (long) Caves.PICK_WITHIN * Caves.PICK_WITHIN;
            var sg = knownNear ? null : pass().sightings();
            var open = sg == null ? null : sg.nearestCave(dim(), me[0], me[1], me[2], io.github.mojolowjo.entropybot.aa.brain.Discovery.SEEN_CAVE_WITHIN);
            if (open != null) {
                at = new int[]{open.x(), open.y(), open.z()};
                if (Jobs.distSq(me, at) > 36) walkTo = at;
                LOG.info("[entropybot] cave: no known cave near; the cave opening I saw at {} ({} away)", open.where(), Math.round(Math.sqrt(Jobs.distSq(me, at))));
            }
        }
        Caves.Cave c = core.caves.pick(m.at() == null ? "" : m.at(), dim(), at[0], at[1], at[2], System.currentTimeMillis(), now());
        if (c == null) return "error: I know no cave called " + m.at() + " (\"debug caves\" lists them)";
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
        Seq s = new Seq(jobs, storage, CaveRules.label(c.name, m.spec(), m.n(), minutes),
                walkTo == null ? List.of(new Seq.Step("cavestep")) : List.of(Seq.Step.walk(walkTo.clone(), true), new Seq.Step("cavestep")), "always");
        runs.put(s, cv);
        String r = jobs.startSeq(s, "always");
        jobs.job.holdOnFight = true;              // monsters live in caves: a fight holds the caving, it doesn't end it
        RestoreLive.INSTANCE.scanBuilds(p, Jobs.here(p));     // P1: someone's build near here? (a whisper, never a box)
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
        // 0.27.3: a dark spot or ore in a keep-out / no-break area is no candidate (AREAS: the world is open otherwise)
        CaveSearch.OffLimits off = (x, y, z) -> CaveRules.offLimits(prot, d, x, y, z) != null || !commands.inAreas(d, x, z);
        CaveSearch.Result r = CaveSearch.search(new LevelWorld(level), me[0], me[1], me[2], c.visited, cv.ids::contains, c.ex, c.ey, c.ez, CaveRules.FROM_ENTRANCE, off);
        cv.steps++;
        // ores first: one vein at a time
        List<CaveSearch.Ore> vein = CaveRules.pickVein(r.ores(), cv.tried,
                o -> jobs.goalAllowed(o.x(), o.y(), o.z()) != null || off.test(o.x(), o.y(), o.z()),
                o -> canMineAt(p, o.x(), o.y(), o.z()), cv.tooHard);
        if (!vein.isEmpty()) {
            List<Pos> cells = new ArrayList<>();
            for (CaveSearch.Ore o : vein) cells.add(new Pos(o.x(), o.y(), o.z()));
            Seq.Step clear = Clearing.clearStep(new ClearJob.Options().only(cells).collect(true).soft(true).explainSpots(true)
                    .label("mining " + cells.size() + " ore blocks in " + cv.name));
            cv.pendingClear = clear;
            spliceHere(s, List.of(clear));
            return "wait";
        }
        // 0.28.0: no vein in reach: a known ore of the list (the chunk pass's index) near the cave goes before a dark corner
        int[] ko = knownCaveOre(cv, p, c, off);
        if (ko != null) {
            cv.walk = ko;
            cv.walkAt = now();
            if (b != null) {
                Jobs.safeSettings();
                b.getCustomGoalProcess().setGoalAndPath(new GoalNear(bp(ko), 2));
            }
            s.setStatus(s.label + " - walking to known ore at " + Jobs.fmt(ko) + " (" + cv.got() + " ores so far)");
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

    /**
     * 0.28.0: the nearest known ore of the cave's list (the index) within the cave's reach from its entrance, open to air
     * (it is next to a dark spot the bot can stand in), the bot can mine, the fence and the guard allow; each spot once.
     */
    private int[] knownCaveOre(CaveRun cv, LocalPlayer p, Caves.Cave c, CaveSearch.OffLimits off) {
        if (!indexReady()) return null;
        int[] me = Jobs.here(p);
        Level level = p.level();
        long reach2 = (long) CaveRules.FROM_ENTRANCE * CaveRules.FROM_ENTRANCE;
        List<io.github.mojolowjo.entropybot.sense.OreIndex.Hit> h = pass().nearest(dim(), cv.ids::contains, me[0], me[1], me[2], CaveRules.FROM_ENTRANCE, 1, (x, y, z) -> {
            if (cv.knownTried.contains(BlockPos.asLong(x, y, z)) || off.test(x, y, z) || jobs.goalAllowed(x, y, z) != null || !fairOk(dim(), x, y, z)) return false;
            long dx = x - c.ex, dy = y - c.ey, dz = z - c.ez;
            if (dx * dx + dy * dy + dz * dz > reach2) return false;
            BlockPos q = new BlockPos(x, y, z);
            return level.isLoaded(q) && exposed(level, q) && canMineAt(p, x, y, z);
        });
        if (h.isEmpty()) return null;
        io.github.mojolowjo.entropybot.sense.OreIndex.Hit k = h.get(0);
        cv.knownTried.add(BlockPos.asLong(k.x(), k.y(), k.z()));
        return new int[]{k.x(), k.y(), k.z()};
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
    /** 0.27.3, AREAS: x z lies in a keep-out or no-break column. */
    private boolean outsideFenced(String d, int x, int z) {
        return !commands.inAreas(d, x, z);
    }

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
                k -> jobs.goalAllowed(k.x(), k.y(), k.z()) != null || CaveRules.offLimits(prot, d, k.x(), k.y(), k.z()) != null || outsideFenced(d, k.x(), k.z()));
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
                if (t == null || (CaveRules.offLimits(prot, d, t.x(), me[1], t.z()) == null && !outsideFenced(d, t.x(), t.z()))) break;      // 0.27.3: and inside the areas
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
        if (c == null) return "I know no cave called " + name + " (\"debug caves\" lists them)";
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
        // AA_ROUND3 5: a cave entered that gave no ore: its seen openings are exhausted (no more "explore cave" there)
        if (cv.got() == 0 && cv.entrance != null) {
            try {
                var sg = pass().sightings();
                int n = sg == null ? 0 : sg.exhaust(dim(), cv.entrance[0], cv.entrance[1], cv.entrance[2], System.currentTimeMillis());
                if (n > 0) LOG.info("[entropybot] cave: no ore in {} - the cave opening{} seen at {} marked exhausted", cv.name, n == 1 ? "" : "s", Jobs.fmt(cv.entrance));
            } catch (RuntimeException e) {
                LOG.warn("[entropybot] cave: couldn't mark the sighting exhausted: {}", e.toString());
            }
        }
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
        /** 0.28.0 phase "known": positions tried, the walk to the next vein, its cells, the clear spliced, veins done, the index's none-reason. */
        final Set<Long> knownTried = new HashSet<>();
        /** 0.36.a3: known ores dropped because the walk dug down from in the water: position -> tick the skip ends (not for good). */
        final Map<Long, Long> kWaterSkip = new HashMap<>();
        /** 0.36.a3: set by the walk watch when the walk started digging down from in the water. */
        volatile boolean kWaterDown;
        int[] kTarget;
        /** 0.34.0: kTarget came from Baritone's memory (this block id); trips made. */
        String kFarId;
        int kFarTrips;
        String kFarText;
        /** 0.34.1: this job's one recall of Baritone's memory (nearest first), or null before it. */
        List<io.github.mojolowjo.entropybot.sense.MemoryPick.Spot> kFarCache;
        int kFarRecalls;
        /** 0.36.a5: the live scan's last answer and when; the last merged pick list and the index's positions in it. */
        List<io.github.mojolowjo.entropybot.sense.MemoryPick.Spot> kLive;
        long kLiveAt;
        io.github.mojolowjo.entropybot.sense.OreMerge.Merged kMerged;
        java.util.Set<Long> kIdxKeys;
        String kFailed;
        int kTries, kKnown, kWalks;
        boolean familyWord, kDigging;
        int kDug;
        /** the ore blocks the vein clears mined, by id (the end line names them) */
        final Map<String, Integer> kBlocks = new LinkedHashMap<>();
        int[] kLast;
        List<Pos> kVein = new ArrayList<>();
        Seq.Step kClear;
        int knownVeins;
        String knownWhy;
        /** AA round 2 item 5: "mine <ore> 1 at x y z": the one named target (null: the usual mine); its vein clear ran. */
        int[] at;
        boolean atVeinDone;
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
                {
                    Object fo = prepareMine(p, text.trim().replaceFirst("^(\\S+)", "$1_ore"));
                    if (fo instanceof MineRun mr) mr.familyWord = true;      // 0.28.0 (the owner): "mine iron" = any block of the family
                    return fo;
                }
            return "error: unknown block " + a.id();
        }
        Block block = BuiltInRegistries.BLOCK.get(rl);
        BlockState st = block.defaultBlockState();
        boolean ore = st.is(Tags.Blocks.ORES);
        if (!ore && !MineRules.EXTRA.matcher(a.id()).matches()) return MineRules.notAnOre(a.id());
        JsonObject pol = policy();
        String d = dim();
        int[] me = Jobs.here(p);
        if (!commands.inAreas(d, me[0], me[2])) return "error: I am " + commands.policy.blockedText(d, me[0], me[2]) + " - I mine nothing here";
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
        MineRules.At at = MineRules.parseAt(text);
        if (at != null && at.error() == null) {
            if (!ore) return MineRules.notAnOre(a.id());
            r.at = new int[]{at.x(), at.y(), at.z()};
        }
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
            String why = "the whole box lies in a keep-out or no-break area";
            if (strict) return "error: the guard refused: " + why;
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

    /** 0.36.9: a dry open cell (air, no fluid) beside x y z: the ore has a face a walk can reach without digging. */
    private static boolean airFace(Level level, int x, int y, int z) {
        int[][] sides = {{0, 1, 0}, {0, -1, 0}, {1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}};
        for (int[] d : sides) {
            BlockPos c = new BlockPos(x + d[0], y + d[1], z + d[2]);
            if (!level.isLoaded(c)) continue;
            var st = level.getBlockState(c);
            if (st.getFluidState().isEmpty() && st.getCollisionShape(level, c).isEmpty()) return true;
        }
        return false;
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
            // mining plan phase 1: Baritone's mine digs under the MINE intent (its own rules, plus: never down from in the water)
            io.github.mojolowjo.entropybot.guard.PolicyRuntime.INSTANCE.setIntent(io.github.mojolowjo.entropybot.guard.BreakPolicy.Intent.MINE, "mine");
        } catch (Throwable ignored) {}
    }

    /** The job's hooks: a fight holds it (Baritone's mine stops, breaking off), and however it ends, breaking goes off. */
    private void hooks(Jobs.Job job, MineRun j) {
        job.holdOnFight = true;
        job.ownsBreaking = true;
        // P1: with dig, every block but the target that Baritone breaks is a path break the restore ledger puts back
        // 0.36.8: the known-ore walk may dig now too, so the path context is set for every mine job (without dig and
        // outside that walk Baritone breaks only the target, which is never a path break)
        RestoreLive.INSTANCE.pathContext(job.id, BuiltInRegistries.BLOCK.getKey(j.block).toString());
        // 0.36.8: while this job runs, breaking next to water is allowed; its known-ore walk may dig inside my areas
        MineWater.start(job, () -> {
            if (j.kTarget == null) return false;
            var pl = net.minecraft.client.Minecraft.getInstance().player;
            return pl != null && core.commands.inAreas(Storage.dim(), (int) Math.floor(pl.getX()), (int) Math.floor(pl.getZ()));
        });
        job.onHold = () -> {
            j.held = true;
            j.heldBy = core.reflexes.reflex().name().toLowerCase();
            IBaritone b = Jobs.baritone();
            if (b != null) Jobs.cancel(b);
            restoreSettings();
        };
        job.onEnd = () -> {
            MineWater.end(job);
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
        RestoreLive.INSTANCE.scanBuilds(p, me);          // P1: someone's build near here? (a whisper, never a box)
        begin(p, r);
        // started while a fight or a meal already holds the bot: the hold hook only fires on a new hold, so apply it now
        // (Baritone's mine stopped, breaking off); the held path in mineStep starts it again when the hold ends
        if (core.reflexes.hold() && jobs.job.onHold != null) jobs.job.onHold.run();
        return "started: " + status + (r.at != null ? " at " + Jobs.fmt(r.at) + " and its vein" : "") + (r.familyWord ? " (any " + io.github.mojolowjo.entropybot.sense.KnownOre.familyOf(r.target) + " ore block, deepslate or not)" : "");
    }

    /** Baritone's mine from here, the first time. */
    private void begin(LocalPlayer p, MineRun r) {
        r.started = true;
        r.startTick = now();
        r.deadline = now() + MineRules.MAX_TICKS;
        r.before = Gui.inventory(p);
        mineSettings(r);
        if (r.need != null) holdPick(p, r);
        if (knownStart(p, r)) return;                     // 0.28.0: known ore from the index first (walk + clear engine)
        // 0.30.7 (item 5): the index is up and knows none: Baritone's mine would only wander; the first step ends the job
        if (r.ore && r.knownWhy != null && indexReady()) {
            LOG.info("[entropybot] mine: {} - not starting Baritone's mine (no walk on an empty ore index)", r.knownWhy);
            return;
        }
        IBaritone b = Jobs.baritone();
        if (b != null) b.getMineProcess().mine(r.count, r.block);
    }

    /** Ends the mine: Baritone stops, breaking off, the leases go, and the job reports. */
    private String end(Seq s, MineRun j, String text) {
        LOG.info("[entropybot] mine: the job ends: {}", text);      // 0.28.0: every mine end has its reason in the log
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
        if (r.need != null && pickSlot(p, r) < 0) return Hints.next("I have no pickaxe that can mine " + sid(r), Hints.pickaxeFix(r.need));
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
        if (j.phase.equals("known")) return knownStep(s, p, j);       // 0.28.0
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
            // 0.28.0: with the index up, its words (it knows every ore seen), never "ran out of ore in view"
            if (j.ore && b != null && indexReady()) {
                j.knownWhy = io.github.mojolowjo.entropybot.sense.KnownOre.noneWhy(io.github.mojolowjo.entropybot.sense.KnownOre.familyOf(j.target));
                return seek(s, p, j, j.knownWhy);
            }
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
        // 0.28.0: new land (or back after a fight): known ore here first
        if (knownStart(p, j)) {
            j.anchor = me;
            if (s.job() != null) s.job().reflex = false;
            s.setStatus(MineRules.status(j.count, j.target, j.dig, j.seeks));
            return null;
        }
        mineSettings(j);
        if (j.need != null) holdPick(p, j);
        // Baritone mines again: its path breaks with dig go back in the restore ledger (knownStart took the context off)
        if (j.dig && s.job() != null) RestoreLive.INSTANCE.pathContext(s.job().id, BuiltInRegistries.BLOCK.getKey(j.block).toString());
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
        if (j.phase.equals("known")) {                    // 0.28.0: pick the nearest known ore again from where the fight left it
            return null;      // the spliced walk/dig goes on; knownStep looks where it got to
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
        // 0.30.7 (package 8 item 5): the ore index knows none of this family: no walk to random new land (it found
        // nothing in trial runs); end now, saying what was walked
        if (j.knownWhy != null && why.equals(j.knownWhy) && indexReady()) {
            int[] from = j.trail.isEmpty() ? me : j.trail.get(0);
            int walked = (int) Math.round(Math.sqrt(Math.pow(me[0] - from[0], 2) + Math.pow(me[2] - from[2], 2)));
            if (walked > io.github.mojolowjo.entropybot.sense.KnownOre.NONE_WALK_CAP)
                LOG.warn("[entropybot] mine: {} blocks from the start on an empty ore index (cap {})", walked, io.github.mojolowjo.entropybot.sense.KnownOre.NONE_WALK_CAP);
            String fam = io.github.mojolowjo.entropybot.sense.KnownOre.familyOf(j.target);
            return end(s, j, io.github.mojolowjo.entropybot.sense.KnownOre.noneYetText(fam, mineCount(p, j), j.count, walked, gains(p, j)));
        }
        j.seeks++;
        if (j.seeks > MineRules.SEEKS) return mineEnd(s, p, j, j.knownWhy != null && why.equals(j.knownWhy) ? knownEndText(p, j)
                : why + ", also in " + MineRules.SEEKS + " more places I walked to");
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
        if (t == null) return mineEnd(s, p, j, j.knownWhy != null && why.equals(j.knownWhy) ? knownEndText(p, j) : why + ", and no unexplored land is left in reach");
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
