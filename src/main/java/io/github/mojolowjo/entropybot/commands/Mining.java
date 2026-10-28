package io.github.mojolowjo.entropybot.commands;

import io.github.mojolowjo.entropybot.aa.brain.Tuning;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.pathing.goals.GoalNear;
import baritone.api.pathing.goals.GoalXZ;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import io.github.mojolowjo.entropybot.Core;
import io.github.mojolowjo.entropybot.cave.ExploreRules;
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
 * B7d (D3): the mining verbs in the mod - "mine &lt;block&gt; [n] [dig]" (rm-mining: with the ore index up, the per-ore
 * step "mine &lt;ore&gt; 1 at x y z dig|walk" (one cell and its vein of at most 8) and the owner's plain "mine &lt;ore&gt; N" as
 * a loop of per-ore steps whose cells the iron scorer prices, a failed cell charged in Failures and never retried in
 * place; without the index or for sand/gravel Baritone's own mine; the pickaxe in hand, the protect-box distance, the
 * time limit and the stall stop from Tuning, a fight holds it), "mine cave &lt;ores&gt; [n] [&lt;min&gt;m] [at &lt;cave&gt;]" (the cave search, the lighter, one vein
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

    /** 0.39.z71 (D2 3.2): the explore started last (its new chunks are the step monitor's explored signal). */
    private volatile ExRun lastEx;

    /** 0.39.z71: the new chunks of the explore started last, -1: none started this session. */
    int exploredChunks() {
        ExRun e = lastEx;
        return e == null ? -1 : e.chunks;
    }
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

    private static boolean strict(JsonObject pol) {
        try { return pol.has("strict") && pol.get("strict").getAsBoolean(); } catch (RuntimeException e) { return false; }
    }

    private ExploreRules.Land land(String dim) {
        return new ExploreRules.Land() {
            @Override public boolean explored(String key) { return notes().explored(key); }
            @Override public boolean allowed(int x, int z) { return commands.workAllowed(dim, x, z); }
            @Override public boolean refused(int x, int y, int z) { return jobs.walkVerdict(x, y, z) != null; }
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
        // 0.39.z8 (owner 2026-10-22, "remove the old job habit"): Baritone's breaking / placing are not touched here;
        // the fork's mine run owns them and puts them back at its end. The policy intent goes back to travel.
        try {
            io.github.mojolowjo.entropybot.guard.PolicyRuntime.INSTANCE.setIntent(io.github.mojolowjo.entropybot.guard.BreakPolicy.Intent.TRAVEL, "mine ended");
        } catch (Throwable t) {
            io.github.mojolowjo.entropybot.Quiet.warn("mine: Baritone's settings could not be put back after the mine", t);
        }
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
            return m.kind() == MineGrammar.Kind.CAVE ? "error: the old cave verb is deleted (0.39.z78 J6b): a known cave is walked by the caving step (Step.Cave: the cave way of aa want, on its record)" : startMine(p, m.text());
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
        // rm-mining (fair play): under aa fair only seen cells are named or counted (no x-ray count in any line)
        io.github.mojolowjo.entropybot.sense.OreIndex.Allow fair = fairAllow(d);
        // 0.28.0 fix: counts from the listed positions within r (never whole-chunk sums); "+" where a chunk's list is capped
        Map<String, io.github.mojolowjo.entropybot.sense.OreIndex.Count> within = pass().index().within(d, me[0], me[1], me[2], r, fair);
        if (fam != null) {
            String f = fam;
            List<io.github.mojolowjo.entropybot.sense.OreIndex.Hit> hits = pass().nearest(d, id -> io.github.mojolowjo.entropybot.sense.KnownOre.family(id, f), me[0], me[1], me[2], r, 8, fair);
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
            List<io.github.mojolowjo.entropybot.sense.OreIndex.Hit> h = pass().nearest(d, id -> io.github.mojolowjo.entropybot.cave.OreSpec.family(id).equals(f), me[0], me[1], me[2], r, 1, fair);
            if (!h.isEmpty()) nearest.put(f, h.get(0));
        }
        int stale = 0;
        for (io.github.mojolowjo.entropybot.sense.ChunkSummary s : pass().index().all(d)) {
            if (s.stale && Math.abs((s.cx << 4) + 8 - me[0]) <= r + 8 && Math.abs((s.cz << 4) + 8 - me[2]) <= r + 8) stale++;
        }
        return io.github.mojolowjo.entropybot.sense.KnownOre.summary(r, within, nearest, chunks, stale);
    }

    /** Is a family ("iron", "nickel") known within SENSE_KNOWN_R where the policy allows (gather's ore source, the brain's feasibility)? */
    boolean oreKnownNear(LocalPlayer p, String family) {
        if (!indexReady() || p == null) return false;
        int[] me = Jobs.here(p);
        String d = dim();
        return !pass().nearest(d, id -> io.github.mojolowjo.entropybot.sense.KnownOre.family(id, family), me[0], me[1], me[2],
                io.github.mojolowjo.entropybot.memory.Limits.SENSE_KNOWN_R, 1, (x, y, z) -> commands.workAllowed(d, x, y, z) && fairOk(d, x, y, z)).isEmpty();
    }

    /** live-facts: the nearest indexed cell of any of the block ids, the policy and aa fair allowing (NeedChain's sightings fallback); null: none or no index here. */
    io.github.mojolowjo.entropybot.sense.OreIndex.Hit nearestIndexed(LocalPlayer p, java.util.Set<String> blocks) {
        if (!indexReady() || p == null || blocks.isEmpty()) return null;
        int[] me = Jobs.here(p);
        String d = dim();
        var hits = pass().nearest(d, blocks::contains, me[0], me[1], me[2], io.github.mojolowjo.entropybot.memory.Limits.SENSE_KNOWN_R, 1,
                (x, y, z) -> commands.workAllowed(d, x, y, z) && fairOk(d, x, y, z));
        return hits.isEmpty() ? null : hits.get(0);
    }

    /** rm-mining: the fair-play filter for index reads (null: aa fair off, every indexed cell). */
    static io.github.mojolowjo.entropybot.sense.OreIndex.Allow fairAllow(String d) {
        io.github.mojolowjo.entropybot.sense.Seen s = pass().seen();
        return io.github.mojolowjo.entropybot.sense.OreIndex.fairPlay(io.github.mojolowjo.entropybot.sense.Seen.fair(), s == null ? null : (x, y, z) -> s.has(d, x, y, z));
    }

    /** AA_ROUND3 3: under {@code aa fair} (default on) only an ore cell I have seen counts (sense.Seen); off: every indexed one. */
    static boolean fairOk(String d, int x, int y, int z) {
        if (!io.github.mojolowjo.entropybot.sense.Seen.fair()) return true;
        io.github.mojolowjo.entropybot.sense.Seen s = pass().seen();
        return s != null && s.has(d, x, y, z);
    }

    /** Chunks seen within the mine reach the policy allows ("I have seen N chunks here"). */
    private int chunksHere(LocalPlayer p) {
        int[] me = Jobs.here(p);
        String d = dim();
        return pass().chunksSeen(d, me[0], me[2], io.github.mojolowjo.entropybot.memory.Limits.SENSE_MINE_R, (x, y, z) -> commands.workAllowed(d, x, y, z));
    }

    /** One face of the block at q is open (air, water, a plant): Baritone's mine without "dig" can reach it. */
    static boolean exposed(Level level, BlockPos q) {
        for (net.minecraft.core.Direction dir : net.minecraft.core.Direction.values()) {
            BlockPos n = q.relative(dir);
            if (level.getBlockState(n).getCollisionShape(level, n).isEmpty()) return true;
        }
        return false;
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
     * water), and the break policy's MINE verdict at the cell. Same fence as the mine's own pick (the policy, the protect
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
            io.github.mojolowjo.entropybot.sense.OreIndex.Allow fence = (x, y, z) -> commands.workAllowed(d, x, y, z)
                    && MineRules.protectNear(prot, d, x, y, z, MineRules.protectMargin()) == null && jobs.walkVerdict(x, y, z) == null;
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
                int top = io.github.mojolowjo.entropybot.world.SurfaceHeightLive.worldSurface(level, h.x(), h.z()) - 1;
                boolean touchesWater = io.github.mojolowjo.entropybot.guard.Guard.liquidNextTo(level, h.x(), h.y(), h.z()) != null || !level.getFluidState(q.below()).isEmpty();
                // aa-costs: the drowning risk of a dig down from in the water (DrownRisk prices the depth; no fixed "2 below")
                boolean wetDown = !open && p.isInWater() && h.y() < me[1] && !touchesWater;
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

    // ==== rm-mining: the per-ore step ("mine <ore> 1 at x y z dig|walk") and the owner's plain mine as a loop of them ====

    /**
     * The mine starts its per-ore phase ("known"): the at form names its cell; the owner's plain "mine &lt;ore&gt; N" with
     * the index up is a loop of per-ore steps, each cell chosen by the iron scorer's pricing ({@link #pickNext}). False:
     * not an ore, or no index (Baritone's own mine runs).
     */
    private boolean knownStart(LocalPlayer p, MineRun j) {
        if (!j.ore || (!indexReady() && j.at == null)) return false;
        IBaritone b = Jobs.baritone();
        if (b != null) Jobs.cancel(b);
        restoreSettings();                                // the clear engine breaks; Baritone only walks
        j.phase = "known";
        j.loop = j.at == null;
        // the per-ore digs are the job's purpose: never in the restore ledger
        if (jobs.job != null) RestoreLive.INSTANCE.endPathContext(jobs.job.id);
        if (j.at != null) LOG.info("[entropybot] mine: per-ore step {} - that cell and its vein (at most {})", MineRules.perOre(j.target, j.at, j.dig), MineRules.atVein());
        else LOG.info("[entropybot] mine: {} {} as per-ore steps, each cell priced by the iron scorer ({})", j.count, sid(j),
                io.github.mojolowjo.entropybot.sense.Seen.fair() ? "seen ore only: aa fair" : "every indexed ore: aa fair off");
        return true;
    }

    /**
     * The next per-ore step's cell for the plain mine: the family's known cells (seen only under aa fair; the fence and
     * the guard's verdict as facts), the cells Failures holds left out, priced by {@link io.github.mojolowjo.entropybot.aa.brain.Goals#pickPriced}
     * (the iron scorer: walk, dig, the drowning risk, the floating dig). {x, y, z, 0 walk | 1 dig}, or null with
     * {@code why[0]} saying why none.
     */
    private int[] pickNext(LocalPlayer p, MineRun j, String[] why) {
        String fam = io.github.mojolowjo.entropybot.sense.KnownOre.familyOf(j.target);
        long nowMs = System.currentTimeMillis();
        List<io.github.mojolowjo.entropybot.aa.IronOptions.Ore> ores = new ArrayList<>(aaOres(p, fam, AaRuntime.ironOres()));
        int known = ores.size(), held = 0, other = 0, buried = 0;
        Level level = p.level();
        for (java.util.Iterator<io.github.mojolowjo.entropybot.aa.IronOptions.Ore> it = ores.iterator(); it.hasNext(); ) {
            var o = it.next();
            if (MineRules.held(fam, o.x(), o.y(), o.z(), nowMs)) { held++; it.remove(); continue; }
            BlockPos q = new BlockPos(o.x(), o.y(), o.z());
            if (!j.familyWord && o.loaded() && !BuiltInRegistries.BLOCK.getKey(level.getBlockState(q).getBlock()).toString().equals(j.target)) { other++; it.remove(); continue; }
            if (!j.dig && o.loaded() && !o.openFace()) { buried++; it.remove(); }
        }
        if (ores.isEmpty()) {
            why[0] = known == 0 ? io.github.mojolowjo.entropybot.sense.KnownOre.noneText(fam, chunksHere(p))
                    : "no " + fam + " ore left I can take: " + (held > 0 ? held + " held after failing (Failures)" : "")
                    + (buried > 0 ? (held > 0 ? ", " : "") + buried + " buried (the walk form; \"dig\" digs to them)" : "")
                    + (other > 0 ? (held + buried > 0 ? ", " : "") + other + " of another block of the family" : "");
            return null;
        }
        var pick = io.github.mojolowjo.entropybot.aa.brain.Goals.pickPriced(fam, ores, Jobs.here(p)[1], p.isInWater(),
                io.github.mojolowjo.entropybot.aa.AaEscape.throwaway(Gui.inventory(p)),
                io.github.mojolowjo.entropybot.guard.PolicyRuntime.INSTANCE.travelMayPlace(), io.github.mojolowjo.entropybot.aa.IronOptions.digMax());
        if (pick == null) {
            why[0] = "the iron scorer takes none of the " + ores.size() + " known " + fam + " ore (denied, a fatal wet dig, or too deep)";
            return null;
        }
        int[] at = pick.at();
        return new int[]{at[0], at[1], at[2], j.dig && at.length > 3 && at[3] == 1 ? 1 : 0};
    }

    /**
     * Every 20 ticks in phase "known": one per-ore step at a time. The step: the cell checked (still that ore, the
     * policy, the walk form's open face), the walk there (a Baritone goal; with dig Baritone digs its own way), then the
     * vein clear (the cell and its loaded same-family neighbours, at most {@link MineRules#AT_VEIN}). A failed step is
     * charged in Failures under its cell key with its stage and reason (never retried in place): the at form ends; the
     * plain mine prices its next cell without it.
     */
    private String knownStep(Seq s, LocalPlayer p, MineRun j) {
        long now = now();
        int[] me = Jobs.here(p);
        String fam = io.github.mojolowjo.entropybot.sense.KnownOre.familyOf(j.target);
        if (j.kClear != null) {
            // the vein clear is over: it counts only when it mined something
            Clearing.Outcome o = j.kClear.cleared;
            int mined = 0;
            if (o != null && o.mined() != null) for (Map.Entry<String, Integer> e : o.mined().entrySet()) {
                mined += e.getValue();
                j.kBlocks.merge(MineRules.shortId(e.getKey()), e.getValue(), Integer::sum);
            }
            String failed = j.kFailed;
            j.kClear = null;
            j.kFailed = null;
            if (failed == null && mined > 0) return stepDone(s, p, j);
            return stepFailed(s, p, j, "vein", failed != null ? failed : "the vein clear mined none of it from " + Jobs.fmt(me));
        }
        if (now > j.deadline) return end(s, j, "stopped: " + MineRules.timeUpText() + ", mining " + sid(j) + gains(p, j));
        if (j.loop && j.count > 0 && mineCount(p, j) >= j.count)
            return end(s, j, MineRules.shortText(j.target, mineCount(p, j), j.count, (j.kBlocks.isEmpty() ? "" : "blocks " + blocksText(j) + " ")
                    + "in " + j.veins + " per-ore step" + (j.veins == 1 ? "" : "s"), gains(p, j), j.toolNote));
        String pc = pickCheck(s, p, j);
        if (pc != null) return pc;
        if (j.kTarget != null) {
            // the walk spliced for this step is over (or failed)
            int[] t = j.kTarget;
            String failed = j.kFailed;
            j.kTarget = null;
            j.kFailed = null;
            if (failed == null && Jobs.distSq(me, t) <= 20) return knownClear(s, j);
            return stepFailed(s, p, j, "walk", failed != null ? failed : "the walk ended at " + Jobs.fmt(me) + ", out of reach");
        }
        if (j.at == null) {
            String[] why = new String[1];
            int[] c = pickNext(p, j, why);
            if (c == null) return mineEnd(s, p, j, why[0]);
            j.at = new int[]{c[0], c[1], c[2]};
            j.atDig = c[3] == 1;
            LOG.info("[entropybot] mine: per-ore step {} (priced by the iron scorer)", MineRules.perOre(j.target, j.at, j.atDig));
        }
        int[] t = j.at;
        String d = dim();
        Level level = p.level();
        BlockPos q = new BlockPos(t[0], t[1], t[2]);
        boolean loaded = level.isLoaded(q);
        if (!commands.workAllowed(d, t[0], t[1], t[2])) return stepFailed(s, p, j, "check", "it is " + commands.policy.blockedText(d, t[0], t[2]));
        if (jobs.walkVerdict(t[0], t[1], t[2]) != null) return stepFailed(s, p, j, "check", jobs.walkVerdict(t[0], t[1], t[2]));
        if (loaded && !io.github.mojolowjo.entropybot.sense.KnownOre.family(BuiltInRegistries.BLOCK.getKey(level.getBlockState(q).getBlock()).toString(), fam))
            return stepFailed(s, p, j, "check", "no " + fam + " ore there any more");
        if (loaded && !j.atDig && !exposed(level, q)) return stepFailed(s, p, j, "check", "buried, and this is the walk form (dig digs to it)");
        j.kVein = loaded ? atVein(level, j) : new ArrayList<>(List.of(new Pos(t[0], t[1], t[2])));
        j.anchor = me;
        // within the clear's reach the vein clear starts from here
        if (Jobs.distSq(me, t) <= 20) return knownClear(s, j);
        j.kTarget = t.clone();
        Seq.Step w = Seq.Step.walk(t.clone(), true);
        w.optional = true;
        s.setStatus("mining " + sid(j) + ": " + mineCount(p, j) + "/" + j.count + " - walking to the ore at " + Jobs.fmt(t) + (j.atDig ? " (digging allowed)" : ""));
        spliceHere(s, List.of(w));
        return "wait";
    }

    /** A per-ore step mined its vein: the at form ends done; the plain mine prices its next cell. */
    private String stepDone(Seq s, LocalPlayer p, MineRun j) {
        j.veins++;
        if (!j.loop) {
            int n = mineCount(p, j);
            return end(s, j, MineRules.atDoneText(n, io.github.mojolowjo.entropybot.sense.KnownOre.familyOf(j.target), Jobs.fmt(j.at), gains(p, j)));
        }
        j.at = null;
        return "wait";
    }

    /** A per-ore step failed at {@code stage}: charged in Failures under its cell key; the at form ends, the loop goes on without it. */
    private String stepFailed(Seq s, LocalPlayer p, MineRun j, String stage, String why) {
        String fam = io.github.mojolowjo.entropybot.sense.KnownOre.familyOf(j.target);
        var rec = MineRules.charge(fam, j.at, stage, why, System.currentTimeMillis());
        LOG.info("[entropybot] mine: per-ore step {} failed at {}: {} (charged in Failures as {} {}, held {} s)", MineRules.perOre(j.target, j.at, j.atDig), stage, why,
                MineRules.CELL_DESIRE, MineRules.cellKey(fam, j.at[0], j.at[1], j.at[2]), Math.max(0, (rec.until() - System.currentTimeMillis()) / 1000));
        if (!j.loop) return end(s, j, MineRules.atFailText(j.target, Jobs.fmt(j.at), stage + ": " + why, gains(p, j)));
        j.at = null;
        return "wait";
    }

    /** "2 iron_ore, 1 deepslate_iron_ore". */
    private static String blocksText(MineRun j) {
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, Integer> e : j.kBlocks.entrySet()) parts.add(e.getValue() + " " + e.getKey());
        return String.join(", ", parts);
    }

    /** An open face of q whose air sees the sky (a surface hollow, a ravine): a fact for the iron scorer. */
    private static boolean skyOpen(Level level, BlockPos q) {
        for (net.minecraft.core.Direction dir : net.minecraft.core.Direction.values()) {
            BlockPos n = q.relative(dir);
            if (level.getBlockState(n).getCollisionShape(level, n).isEmpty() && level.getBrightness(LightLayer.SKY, n) > 0) return true;
        }
        return false;
    }

    /** Seq: the per-ore step's spliced walk or vein clear (optional) failed: the rest of it dropped, knownStep charges the cell. */
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

    /** The step's vein: its cell and the loaded same-family cells touching it (MineRules.vein, at most AT_VEIN), the policy allowing. */
    private List<Pos> atVein(Level level, MineRun j) {
        String fam = io.github.mojolowjo.entropybot.sense.KnownOre.familyOf(j.target);
        String d = dim();
        List<Pos> out = new ArrayList<>();
        for (int[] c : MineRules.vein(j.at, n -> {
            BlockPos q = new BlockPos(n[0], n[1], n[2]);
            return level.isLoaded(q) && commands.workAllowed(d, n[0], n[1], n[2]) && jobs.walkVerdict(n[0], n[1], n[2]) == null
                    && io.github.mojolowjo.entropybot.sense.KnownOre.family(BuiltInRegistries.BLOCK.getKey(level.getBlockState(q).getBlock()).toString(), fam);
        }, MineRules.atVein())) out.add(new Pos(c[0], c[1], c[2]));
        return out;
    }

    /** The step's vein to the clear engine, spliced in front of this step (the vein as the world shows it now). */
    private String knownClear(Seq s, MineRun j) {
        Level lv = Minecraft.getInstance().level;
        if (lv != null && lv.isLoaded(new BlockPos(j.at[0], j.at[1], j.at[2]))) j.kVein = atVein(lv, j);
        Seq.Step clear = VeinMine.seqStep(new ArrayList<>(j.kVein), "mining " + j.kVein.size() + " " + sid(j) + " blocks at " + Jobs.fmt(j.at));       // rm-final: Baritone's MineProcess, not the clear engine
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
            case "explore": return exploreStep(s, p);
            case "exploretally": return tallyStep(s, p);
            case "mineore": return mineStep(s, st, p);
            case "mineoretool": return toolGetStep(s, st, p);
            case "mineorecheck": return toolCheckStep(s, st, p);
            default: return "unknown step " + st.type;
        }
    }

    // ==== explore ====

    static final class ExRun {
        long until, start, targetAt, findAt = -1, untilAt = -1;
        /** live-facts: the item whose source, once known, ends the walk (a chain's explore); null: none. */
        String untilSource;
        /** 0.39.z51 item 3: the until-source known at the first check (null: none); untilChecked: that check ran. */
        String untilAtStart;
        /** 0.39.z75 metric fix B: the found source the order was last priced again on (one re-price per source). */
        String foundAsked;
        /** 0.39.z75 log: the found sources whose hold was said (once each); the end line lists them. */
        final java.util.Set<String> heldSaid = new java.util.LinkedHashSet<>();
        boolean untilChecked;
        /** 0.39.z47: the until words whose known source was dropped for the order (said once each). */
        final java.util.Set<String> saidDropped = new java.util.HashSet<>();
        int chunks, sx, sz;
        /** 0.33.0: where the walk started (the report says how far it went). */
        int[] from;
        String dir;
        /** V1b find: what to look for (null: a plain explore). */
        io.github.mojolowjo.entropybot.vocab.ExploreWords.Find find;
        String found;
        final Set<Integer> poiIds = new HashSet<>();
        ExploreRules.Target target;
        /** 0.39.z75 probe: why the target was picked (the chooser's reason, for the stall line). */
        String targetWhy;
        /** 0.39.z72 (D2 3.3): the current target as a held fact (null: none chosen yet); the run's failed targets. */
        ExploreRules.TargetHold.Held hold;
        final List<ExploreRules.TargetHold.Failed> failed = new ArrayList<>();
        /** 0.39.z68: where the until target generates (GenWhere), read once per run (whereRead). */
        io.github.mojolowjo.entropybot.aa.brain.GenWhere.Fact where;
        boolean whereRead;
        /** 0.39.z48: the cave candidate the current target walks toward ("x y z"), null: a chunk target. */
        String aimed;
        /** Run ED: the cave opening cell the target walks toward (x y z), null: a chunk target - reached only within reach of it. */
        int[] aimCell;

        /**
         * 0.39.z77 J2 E2: the Y of an under-ground target (a cave cell in the band: a cell goal, GoalNear), null: a GoalXZ
         * target on the surface. E1: {@code landFirst} - a part of the joined until word generates on the surface (the water
         * term on), {@code landRead} once every part's fact was loaded.
         */
        Integer aimY;
        boolean landFirst, landRead;
        /** J6b item 5 (a): the chunk target's goal is its surface cell (false: the column goal, its column not loaded yet). */
        boolean surfaceGoal;
        /** Run EH (2): the feet the last no-break walks found no path from, and how many in a row from there. */
        int[] noBreakFrom;
        int noBreakFails;

        /**
         * 0.39.z76 send-back 1 (run AN 23:29:43-23:30:07: a gather on the way to cave candidate 117 13 213 dropped the target
         * but kept {@code aimed}; back from the vein, the "walk toward cave candidate ... ended" line read the null target: a
         * NullPointerException that stopped the chestplate order): a gather drops the target, its hold and the candidate it
         * walked toward together - none of them failed or was visited (the candidate may be aimed at again after it).
         */
        void dropTargetForGather() {
            target = null;
            hold = null;
            aimed = null;
            aimCell = null;
            aimY = null;
        }
        /** 0.23.1: gathering on the way (explore ... gather off = false); the bot's spot. */
        boolean gather = true;
        final int[] at = new int[3];
        long gatherAt;
        int gathers, gatherMisses;
        /** 0.33.1: ticks spent gathering, the gather in progress's start (0: none), the chunk count at the last gather. */
        long gatherTicks, gatherStart;
        int chunksAtGather;
        Map<String, Integer> before;
        final Map<String, Integer> took = new LinkedHashMap<>();
    }

    /** 0.39.z48 (S65): cave candidates an explore walked toward this session ("x y z"): never aimed at again. */
    static final Set<String> CAVE_VISITED = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * 0.39.z68 item (c): the client knows chunk cx cz is water: it is loaded and its centre column's top (the MOTION_BLOCKING
     * heightmap, which counts water) holds water by the game's fluid tag. Not loaded: not known, false (rule 15: no extra).
     * Loader notes: vanilla Level#hasChunk, Level#getHeight, FluidState#is(FluidTags.WATER).
     */
    static boolean waterChunk(Level lv, int cx, int cz) {
        if (lv == null || !lv.hasChunk(cx, cz)) return false;
        int x = (cx << 4) + 8, z = (cz << 4) + 8;           // game fact: the centre of a chunk is 8 blocks in
        int top = io.github.mojolowjo.entropybot.world.SurfaceHeightLive.motionBlocking(lv, x, z);
        return lv.getFluidState(new BlockPos(x, top - 1, z)).is(net.minecraft.tags.FluidTags.WATER);
    }

    /**
     * 0.39.z77 J2 E2: the explore target's Baritone goal - a cell goal near the cave cell (its own Y, Baritone's GoalNear) for
     * an under-ground target, else the column (GoalXZ) as before. Loader notes: Baritone's GoalNear / GoalXZ, vanilla BlockPos.
     */
    private static baritone.api.pathing.goals.Goal exploreGoal(ExRun ex) {
        if (ex.aimY != null) return new baritone.api.pathing.goals.GoalNear(new BlockPos(ex.target.x(), ex.aimY, ex.target.z()), 2);   // structural: within 2 blocks of the cell
        // 0.39.z78 J6b item 5 (a) (the owner): a chunk target is a SURFACE goal - its column's leaf-free surface cell (the
        // index's ground), so the walk does not dig down for it; Baritone still picks the path (rule 2). The column not
        // loaded yet: the column goal (GoalXZ) until it loads (the held walk sets the surface goal then)
        var lv = Minecraft.getInstance().level;
        int[] cell = ExploreRules.surfaceCell(ex.target.x(), ex.target.z(), lv == null ? io.github.mojolowjo.entropybot.world.SurfaceHeight.NOT_KNOWN : Jobs.surfaceNoLeaves(lv, ex.target.x(), ex.target.z()));
        ex.surfaceGoal = cell != null;
        if (cell != null) return new baritone.api.pathing.goals.GoalNear(new BlockPos(cell[0], cell[1], cell[2]), 2);   // structural: within 2 blocks of the cell
        return new GoalXZ(ex.target.x(), ex.target.z());
    }

    /** J6b item 5: the explore aims at cave openings (the same test as the pick's: a band target, or an ore need not on the surface). */
    private boolean caveAim(ExRun ex) {
        if (ex.untilSource == null) return false;
        boolean band = ex.where != null && ex.where.band(), surface = ex.where != null && ex.where.surface();
        return band || (!surface && oreNeed(ex.untilSource));
    }

    /** 0.39.z48: one of the explore's until words is an ore need (a source block the game tags c:ores). */
    private boolean oreNeed(String untilSource) {
        if (commands.aaRuntime == null) return false;
        for (String part : untilSource.split("\\|")) {
            if (part.isEmpty()) continue;
            for (String b : commands.aaRuntime.sourceBlocksOf(io.github.mojolowjo.entropybot.aa.NeedChain.word(part)))
                if (io.github.mojolowjo.entropybot.aa.brain.ItemWorth.hasTag(b, "c:ores")) return true;
        }
        return false;
    }

    /** 0.23.1: at most this many gathers (a tree, a surface vein) per explore/find run; a look round every 2 s while out there. */
    static int gatherMax() { return Tuning.i("mining.gatherMax"); }
    static int gatherEvery() { return Tuning.i("mining.gatherEvery"); }

    /**
     * V1b: "explore [north|south|east|west] [minutes]": never-seen land (explored.json; never into a keep-out area),
     * then home. The report names the new points of interest.
     */
    String explore(LocalPlayer p, String rest) {
        io.github.mojolowjo.entropybot.vocab.ExploreWords.Args a = io.github.mojolowjo.entropybot.vocab.ExploreWords.parse(rest, io.github.mojolowjo.entropybot.vocab.ExploreWords.defaultMinutes());
        if (a.error() != null) return a.error();
        return startExplore(p, a.dir(), a.minutes(), null, a.gather(), a.until());
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
        return startExplore(p, null, f.minutes(), f, f.gather(), null);
    }

    private String startExplore(LocalPlayer p, String dir, int minutes, io.github.mojolowjo.entropybot.vocab.ExploreWords.Find find, boolean gather, String untilSource) {
        String busy = busyText();
        if (busy != null) return busy;
        try {
            policy();
        } catch (MineRules.BadPolicy e) {
            return "error: " + MineRules.badPolicyText(e).replace("I won't mine", "I won't explore");
        }
        ExRun ex = new ExRun();
        lastEx = ex;                             // 0.39.z71: the step monitor reads its chunks
        ex.until = now() + minutes * 1200L;
        ex.start = now();
        ex.dir = dir;
        ex.find = find;
        // 0.39.z77 seen (run CC 16:42:08 "explore: for iron_ingot: ... no block gives it": the iron ore it passed never ended
        // it): the until word is read through the furnace's recipes - "iron_ingot" is also what smelts into it (raw_iron, whose
        // block the loot data names) - so a source of those ends the explore (the order is priced again on it, as for any find)
        ex.untilSource = io.github.mojolowjo.entropybot.cave.ExploreRules.throughSmelts(untilSource, io.github.mojolowjo.entropybot.cave.ExploreRules::smeltInputs);
        if (untilSource != null && !untilSource.equals(ex.untilSource)) LOG.info("[entropybot] explore: until {} - read as {} (what smelts into it too)", untilSource, ex.untilSource);
        // 0.39.z76 send-back 3 (rule 22: no placing inside an explore; run AU's placements came from the gathers' mine runs,
        // VeinMine mineWith allowPlace): the explore's walks price a placement out (the fork's walk lease, COST_INF) - the
        // next step's start puts walk.placePenalty back (StepHands.reserve -> RunThrowaway.walkPenalty)
        io.github.mojolowjo.entropybot.engine.RunThrowaway.noPlacing();
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
        ex.gather = gather;
        System.arraycopy(me, 0, ex.at, 0, 3);
        return r + (gather ? "" : " (no gathering while exploring)");
    }

    /** live-facts: the first surface ore the last vein search passed for want of a pickaxe tier (said in the miss line), null none. */
    private String veinPassed;

    /** 0.23.1: a gather while exploring: a tree (when short of logs) or an exposed ore vein; null = nothing. AREAS: anywhere not protected. */
    private List<Seq.Step> gatherSteps(Seq s, ExRun ex, LocalPlayer p) {
        String d = dim();
        if (!ex.gather) return null;
        java.util.function.Predicate<int[]> ok = c -> {
            if (!commands.workAllowed(d, c[0], c[1], c[2])) return false;
            return true;
        };
        veinPassed = null;
        List<Pos> vein = surfaceVein(p, ok);
        if (vein.isEmpty()) {
            if (ex.gatherMisses++ < 3) LOG.info("[entropybot] explore: nothing to take near {} {}{}", Jobs.here(p)[0], Jobs.here(p)[2],
                    veinPassed == null ? "" : " (passed ore I can't take with what I carry: " + veinPassed + ")");
            return null;
        }
        Pos f = vein.get(0);
        LOG.info("[entropybot] explore: mining {} exposed ore blocks at {} {} {}", vein.size(), f.x(), f.y(), f.z());
        List<Seq.Step> add = new ArrayList<>();
        add.add(Seq.Step.walk(new int[]{f.x(), f.y(), f.z()}, true));
        add.add(VeinMine.seqStep(vein, "mining the ore at " + f.x() + " " + f.y() + " " + f.z(), false));     // z76 send-back 3 (rule 22): no placing inside an explore       // rm-final: Baritone's MineProcess, not the clear engine
        return add;
    }

    /**
     * An ore within 8 blocks that touches open sky-lit air (a surface ore) and its vein (same block, joined, 8 at most).
     * live-facts: only an ore the pickaxe I carry meets (the mining-tier data; an unknown tier is not met): no bare-handed ore.
     */
    private List<Pos> surfaceVein(LocalPlayer p, java.util.function.Predicate<int[]> ok) {
        Level level = p.level();
        int[] me = Jobs.here(p);
        int have = io.github.mojolowjo.entropybot.guard.PolicyRuntime.pickTier(p);
        String passed = null;
        BlockPos.MutableBlockPos q = new BlockPos.MutableBlockPos();
        for (int r = 1; r <= 8; r++) for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) {
            if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
            for (int dy = -4; dy <= 4; dy++) {
                q.set(me[0] + dx, me[1] + dy, me[2] + dz);
                if (!level.isLoaded(q)) continue;
                BlockState st = level.getBlockState(q);
                if (!st.is(Tags.Blocks.ORES) || !ok.test(new int[]{q.getX(), q.getY(), q.getZ()}) || !skyTouching(level, q)) continue;
                String id = BuiltInRegistries.BLOCK.getKey(st.getBlock()).toString();
                int need = io.github.mojolowjo.entropybot.aa.brain.MiningTiers.blockTierOf(id);
                if (!io.github.mojolowjo.entropybot.cave.ExploreRules.toolMeets(need, have)) {
                    if (passed == null) passed = id + " (tier " + need + ", my pickaxe tier " + have + ")";
                    continue;
                }
                List<Pos> vein = new ArrayList<>();
                ArrayDequeHolder.fill(level, q.immutable(), st.getBlock(), ok, vein);
                return vein;
            }
        }
        veinPassed = passed;
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

    /** V1b find: dark air below the surface within 8 blocks (a cave under the bot). */
    private int darkAirBelow(LocalPlayer p) {
        Level level = p.level();
        int[] me = Jobs.here(p);
        int n = 0;
        BlockPos.MutableBlockPos q = new BlockPos.MutableBlockPos();
        for (int dx = -8; dx <= 8; dx += 2) for (int dz = -8; dz <= 8; dz += 2) {
            int top = io.github.mojolowjo.entropybot.world.SurfaceHeightLive.worldSurface(level, me[0] + dx, me[2] + dz);
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
                var open = sg == null ? null : sg.nearestCave(dim(), me[0], me[1], me[2], io.github.mojolowjo.entropybot.aa.brain.Discovery.seenCaveWithin());
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
        } catch (RuntimeException e) {
            io.github.mojolowjo.entropybot.Quiet.warn("explore: the points of interest could not be read (none used)", e);
        }
        return out;
    }

    /**
     * 0.39.z75 (run AI 20:11:05-20:14:16: helmet, leggings and boots each parked on "explore 5 until raw_iron: monitor: no
     * progress 30 s ... farthest 0 from the start", the re-plan picking the same explore toward the same target from the
     * same spot): the explore targets frozen under the step monitor, each with the feet it froze from - a fact by position;
     * every later explore passes over that target until the bot stands failures.moved from there (TargetHold.skip).
     */
    static final List<ExploreRules.TargetHold.Failed> FROZEN = java.util.Collections.synchronizedList(new ArrayList<>());

    /** 0.39.z75: a chunk whose explore target froze from near here (FROZEN). */
    private static boolean frozenSkip(int cx, int cz, int[] me) {
        synchronized (FROZEN) {
            pruneFrozen(me);
            return ExploreRules.TargetHold.skip(FROZEN, cx, cz, me, io.github.mojolowjo.entropybot.aa.brain.Failures.movedV());
        }
    }

    /** Run ED: a cave opening whose walk froze from near here (FROZEN, by its cell). */
    private static boolean frozenCell(int[] cell, int[] me) {
        synchronized (FROZEN) {
            pruneFrozen(me);
            return ExploreRules.TargetHold.skipCell(FROZEN, cell[0], cell[2], me, io.github.mojolowjo.entropybot.aa.brain.Failures.movedV());
        }
    }

    /** z75 review 2: the held targets the bot now stands failures.moved from are released (they would not apply any more). */
    private static void pruneFrozen(int[] me) {
        if (me != null) FROZEN.removeIf(f -> ExploreRules.TargetHold.released(f, me, io.github.mojolowjo.entropybot.aa.brain.Failures.movedV()));
    }

    /** 0.39.z75 probe: the running explore's target, its distance from {@code me} and why it was picked; null: no running explore. */
    static String exploreTargetText(int[] me) {
        ExRun ex = lastExplore;
        if (ex == null || ex.target == null || me == null || Core.INSTANCE.tick() - lastExploreTick > 20) return null;   // structural: one second in ticks
        double d = Math.hypot(me[0] - ex.target.x(), me[2] - ex.target.z());
        return "explore target " + ex.target.x() + " " + ex.target.z() + " " + Math.round(d) + " m from the feet (" + (ex.targetWhy == null ? "why not said" : ex.targetWhy) + ")";
    }

    /**
     * 0.39.z76 J3-mod item 0: the running explore walks toward a held target now (it gives Baritone that goal again once a
     * second while Baritone is idle) - the step's car should have a goal; false: no running explore, a gather, between
     * targets, or the explore did not step within the last second.
     */
    static boolean exploreWalking() {
        ExRun ex = lastExplore;
        return ex != null && ex.target != null && ex.hold != null && Core.INSTANCE.tick() - lastExploreTick <= 20;   // structural: one second in ticks
    }

    /** The explore run that stepped last (its target is the one a monitor's cancel froze). */
    private static volatile ExRun lastExplore;

    /** 0.39.z75 review 2: the tick {@link #lastExplore} last stepped (a frozen() older than a second is no running explore's). */
    private static volatile long lastExploreTick = -1;

    /** z75 review 2: entries FROZEN keeps at most (the oldest go first). */
    static final int FROZEN_MAX = 32; // structural: a memory bound, far more than an order's explores

    /** 0.39.z75: the step monitor ended the running explore for no progress at {@code feet}: its target is held by position. */
    static void frozen(int[] feet, String why) {
        ExRun ex = lastExplore;
        lastExplore = null;
        if (ex == null || ex.target == null || feet == null || Core.INSTANCE.tick() - lastExploreTick > 20) {   // structural: one second in ticks
            LOG.info("[entropybot] explore: the monitor ended an explore ({}) with no running target to hold", why);
            return;
        }
        synchronized (FROZEN) {
            pruneFrozen(feet);
            FROZEN.add(new ExploreRules.TargetHold.Failed(ex.target.x(), ex.target.z(), feet, "frozen: " + why));
            while (FROZEN.size() > FROZEN_MAX) FROZEN.remove(0);
        }
        // run ED: a frozen walk toward an opening never stood at it - not visited (FROZEN skips it from near here)
        LOG.info("[entropybot] explore: target {} {} held from {} {} {} (the monitor ended the explore: {}); the next explore from here picks another",
                ex.target.x(), ex.target.z(), feet[0], feet[1], feet[2], why);
    }

    private String exploreStep(Seq s, LocalPlayer p) {
        String r = exploreStep0(s, p);
        if (!"wait".equals(r)) lastExplore = null;          // z75 review 2: an explore that ended (or is gone) is no running one
        return r;
    }

    private String exploreStep0(Seq s, LocalPlayer p) {
        ExRun ex = runs.get(s) instanceof ExRun x ? x : null;
        if (ex == null) return "the explore run is gone";
        lastExplore = ex;
        lastExploreTick = Core.INSTANCE.tick();
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
        // owner 2026-10-20: no leash - the way home is priced instead (Discovery's explore row: the return trip to
        // the base place, night-weighted, within the horizon)
        // V1b find: stop when it turned up
        // AA_ROUND3 5 (live aa-seen2: the step runs on even ticks, so "% 20 == 5" never came): once a second by the clock
        // live-facts: a chain's explore ends the moment the missing source is known (the one Nearby fact), checked once a second
        if (ex.untilSource != null && done == null && (ex.untilAt < 0 || now() - ex.untilAt >= 20) && commands.aaRuntime != null) {  // structural: once a second in ticks
            ex.untilAt = now();
            String known = ExploreRules.untilFound(ex.untilSource, commands.aaRuntime::knownSource, (part, sp) -> {
                String why = commands.aaRuntime.droppedByOrder(part, sp);     // 0.39.z47: a dropped source never ends it
                if (why != null && ex.saidDropped.add(part))
                    LOG.info("[entropybot] explore: {} at {} {} {} was dropped for the order ({}); exploring on", part, sp.x(), sp.y(), sp.z(), why);
                return why;
            }, commands.aaRuntime::knownCave);       // 0.39.z77 cave: a confirmed cave the planner offers ends it too (run BW 14:24:10)
            // 0.39.z51 item 3: a source already known at the explore's start is no change: it holds replan.unchangedHoldS
            if (!ex.untilChecked) {
                ex.untilChecked = true;
                ex.untilAtStart = known;
                if (known != null) LOG.info("[entropybot] explore: {} - known when the explore started (nothing changed): exploring on for replan.unchangedHoldS {} s unless the order's pick changes",
                        ExploreRules.sourceOf(known), Math.round(Tuning.get("replan.unchangedHoldS")));
            }
            boolean ends = ExploreRules.untilEnds(known, ex.untilAtStart, (now() - ex.start) / 20.0);   // structural: 20 ticks a second
            // 0.39.z75 metric fix B (run AH 19:46:05-33): the order behind the explore is priced again where it runs, once per
            // source found; the explore ends only when that price picks other steps (Steps.reprice(true)) - the planner's test
            if (ends && ExploreRules.askAgain(known, ex.foundAsked) && commands.aaRuntime.repriceOnFound(known)) {
                ex.foundAsked = known;
                LOG.info("[entropybot] explore: {} - the order is priced again here; the explore ends only if the new price uses it", ExploreRules.sourceOf(known));
                ends = false;
            } else if (ends && ex.foundAsked != null) {
                // asked already: the price said "explore on" - held, said once per source (rule 7: never a silent hold)
                ends = false;
                String src = ExploreRules.sourceOf(known);
                if (ex.heldSaid.add(src)) {
                    boolean same = !ExploreRules.askAgain(known, ex.foundAsked);
                    LOG.info("[entropybot] explore: {} seen, not ended: {}", src, same
                            ? "the order was priced on this source already and kept this explore (it waits for a source the plan uses, or its time limit)"
                            : "the re-price was not asked (no typed run of an order behind the explore now); it runs to a source the plan uses or its time limit");
                }
            }
            if (ends) {
                LOG.info("[entropybot] explore: {}", known);
                s.note = known + tookText(ex.took);
                if (b != null) Jobs.cancel(b);
                notes().flush();
                return "next";
            }
        }
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
        // 0.23.1 (VOCABULARY 6b): off any base, take what keeps it going: a tree, an exposed ore vein
        // 0.33.1: only as a side effect of the walk (ExploreRules.gatherAllowed), so a short explore still walks
        if (done == null && ex.gather && ex.gathers < gatherMax() && now() - ex.gatherAt >= gatherEvery()
                && ExploreRules.gatherAllowed(now() - ex.start, ex.until - now(), ex.gatherTicks, ex.chunks, ex.chunksAtGather)) {
            ex.gatherAt = now();
            try {
                {     // owner 2026-10-23: no base detection; gather anywhere not marked
                    List<Seq.Step> add = gatherSteps(s, ex, p);
                    if (add != null) {
                        if (b != null) Jobs.cancel(b);
                        ex.dropTargetForGather();        // 0.39.z72: a gather is no failure of the target (chosen again after it); z76: the candidate too
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
        // 0.39.z72 (D2 3.3, run P P6): the target is a held fact - held until reached or failed (no path monitor.noPathFails
        // times or no new best for monitor.stallS); Baritone idle alone is no failure: the same goal is set again (once a
        // second). Was (z56, Mining ~1079): given up 1 s after Baritone went idle and its chunk marked explored, never reached.
        // 0.39.z78 J6b item 5 (b) (the owner): a rim credit landing mid-walk switches the target at once - the held chunk
        // target yields to a credited opening this tick (the pick below takes it)
        if (done == null && ex.target != null && ex.hold != null && ex.aimed == null && caveAim(ex)) {
            int within = (int) Math.floor(Tuning.get("cave.exploreTowardM"));
            final double moved = io.github.mojolowjo.entropybot.aa.brain.Failures.movedV();
            int[] seen = ExploreRules.yieldTo(true, io.github.mojolowjo.entropybot.sense.ChunkPass.INSTANCE.caveIndex().openings(me[0], me[1], me[2], within),
                    cell -> CAVE_VISITED.contains(Jobs.fmt(cell)) || ExploreRules.TargetHold.skipCell(ex.failed, cell[0], cell[2], me, moved) || frozenCell(cell, me));
            if (seen != null) {
                LOG.info("[entropybot] explore: a cave opening was seen at {} mid-walk: the walk to {} {} yields to it", Jobs.fmt(seen), ex.target.x(), ex.target.z());
                if (b != null) Jobs.cancel(b);
                ex.hold = null;
            }
        }
        if (done == null && ex.target != null && ex.hold != null) {
            ex.hold = ExploreRules.TargetHold.look(ex.hold, now(), me, io.github.mojolowjo.entropybot.baritone.BaritoneHook.calcFailed(), b != null && b.getPathingBehavior().getCurrent() != null,
                    ex.aimed != null ? ex.aimCell : null);   // run ED: an opening is reached within reach of its cell
            // J6b item 5 (the owner: "when exploring it should not break blocks"): the walk is the fork's no-break walk; its
            // "no path (no-break walk)" fails the target at once - the explore picks the next one (said once, the failed line)
            if (ex.hold.state() == ExploreRules.TargetHold.State.HELD && b != null && ExploreRules.noBreakNoPath(b.getCustomGoalProcess().lastEnd())) {
                ex.hold = ExploreRules.TargetHold.failNow(ex.hold, "no path without breaking or placing (the no-break walk) from " + Jobs.fmt(me));
                // run EI (2): the probe line of the failed no-break walk (debug.walkProbe), with the fork's calc detail
                // (ICustomGoalProcess.lastNoBreakCalc, job-vein 5ca5424c: null before the first no-break "no path" end)
                if (Tuning.get("debug.walkProbe") >= 1) {
                    var calc = b.getCustomGoalProcess().lastNoBreakCalc();
                    LOG.info("[entropybot] {}", ExploreRules.noBreakLine(me, ex.aimY != null ? "near " + ex.target.x() + " " + ex.aimY + " " + ex.target.z() + " (opening)"
                            : (ex.surfaceGoal ? "the surface cell of " : "the column ") + ex.target.x() + " " + ex.target.z(), b.getCustomGoalProcess().lastEnd(),
                            calc == null ? null : calc.type(), calc == null ? null : calc.nodesOpened(),
                            calc == null || calc.best() == null ? null : calc.best().getX() + " " + calc.best().getY() + " " + calc.best().getZ()));
                    if (calc != null) LOG.info("[entropybot] probe nobreak: fork says {}", calc.line());
                }
                // run EH (2): several no-break walks in a row find no path from these same feet - the bot is boxed in here: the
                // explore ends at once with that fact (the next plan prices the way out first), not 20 tries and a freeze
                ex.noBreakFails = java.util.Arrays.equals(ex.noBreakFrom, me) ? ex.noBreakFails + 1 : 1;
                ex.noBreakFrom = me.clone();
                if (ExploreRules.boxedIn(ex.noBreakFails)) {
                    String why = "boxed in at " + Jobs.fmt(me) + ": " + ex.noBreakFails + " no-break walks in a row found no path from here (no way out without breaking or placing)";
                    io.github.mojolowjo.entropybot.aa.moves.FactChange.boxedIn(me, why);
                    LOG.info("[entropybot] explore: {} - the explore ends; the next plan prices the way out first", why);
                    Jobs.cancel(b);
                    notes().flush();
                    return why;
                }
            }
            if (ex.hold.state() == ExploreRules.TargetHold.State.HELD) {
                boolean surfaceNow = ex.aimY == null && !ex.surfaceGoal && io.github.mojolowjo.entropybot.world.SurfaceHeight.known(Jobs.surfaceNoLeaves(p.level(), ex.target.x(), ex.target.z()));
                if (b != null && (Jobs.idle(b) || surfaceNow) && now() - ex.targetAt >= 20) {     // structural: once a second in ticks
                    b.getCustomGoalProcess().setGoalAndPathNoBreak(exploreGoal(ex));   // J6b item 5: never breaking, never placing
                    if (surfaceNow) LOG.info("[entropybot] explore: the target column {} {} loaded: the walk's goal is its surface cell now", ex.target.x(), ex.target.z());
                    ex.targetAt = now();
                }
                s.setStatus(s.label + " - walking to " + ex.target.x() + " " + ex.target.z() + " (" + ex.chunks + " chunks so far)");
                return "wait";
            }
            if (ex.hold.marksExplored(ex.aimed != null)) notes().markExplored(List.of(ExploreRules.key(d, ex.target.x() >> 4, ex.target.z() >> 4)));
            else if (ex.hold.state() == ExploreRules.TargetHold.State.FAILED) {
                // rule 15: never reached is never "explored"; the run passes over it until the bot moved failures.moved from here
                ex.failed.add(new ExploreRules.TargetHold.Failed(ex.target.x(), ex.target.z(), me, ex.hold.why()));
                LOG.info("[entropybot] {}", ExploreRules.TargetHold.failedLine(ex.hold));
                if (b != null) Jobs.cancel(b);
            }
        }
        boolean stood = ex.hold != null && ex.hold.state() == ExploreRules.TargetHold.State.REACHED;
        ex.hold = null;
        if (ex.aimed != null && ex.target != null) {     // z76 send-back 1: never a candidate without its target (run AN NPE)
            // run ED: visited = the bot stood within reach of the opening (REACHED); a walk that failed is in ex.failed (passed
            // over from near here, TargetHold.skipCell); one cut by the explore's own end is neither
            long m = me == null || ex.aimCell == null ? -1 : Math.round(Math.sqrt(Jobs.distSq(me, ex.aimCell)));
            if (stood) {
                CAVE_VISITED.add(ex.aimed);
                LOG.info("[entropybot] explore: reached cave opening {} (stood {} m from it): visited", ex.aimed, m);
            } else LOG.info("[entropybot] explore: walk toward cave opening {} ended {} m from it, not reached (not visited)", ex.aimed, m);
        }
        ex.aimed = null;
        ex.aimCell = null;
        ex.aimY = null;
        ex.target = null;
        // 0.39.z68 (owner 2026-10-25): where the target generates (GenWhere from the jars' world-gen data), read once per run
        if (ex.untilSource != null && !ex.whereRead && commands.aaRuntime != null) {
            boolean first = ex.where == null;
            ex.where = commands.aaRuntime.whereOf(ex.untilSource);
            ex.whereRead = ex.where.kind() != io.github.mojolowjo.entropybot.aa.brain.GenWhere.Kind.NOT_LOADED;   // rule 15: asked again until loaded
            if (first || ex.whereRead) LOG.info("[entropybot] explore: for {}: {}", ex.untilSource, ex.where.surface() ? "on the surface - " + ex.where.text() + " (known water chunks count exploreWords.waterRings " + io.github.mojolowjo.entropybot.vocab.ExploreWords.waterRings() + " rings farther)"
                    : ex.where.band() ? "toward caves in or near the band, ranked by the density at their Y (exposed ore: less the air-exposure discard) - " + ex.where.text()
                    : "as before - " + ex.where.text());
        }
        // 0.39.z77 J2 E1 (run AP 00:02:40): a joined until word reads UNKNOWN as a whole (placed both ways); any part that
        // generates on the surface turns the land-first water term on (read per part, asked again until every part is loaded)
        if (ex.untilSource != null && !ex.landRead && commands.aaRuntime != null) {
            boolean loaded = true;
            for (String part : ex.untilSource.split("\\|"))
                if (!part.isEmpty() && commands.aaRuntime.whereOf(part).kind() == io.github.mojolowjo.entropybot.aa.brain.GenWhere.Kind.NOT_LOADED) loaded = false;
            ex.landFirst = ExploreRules.anySurfacePart(ex.untilSource, commands.aaRuntime::whereOf);
            ex.landRead = loaded;
            if (ex.landFirst && (ex.where == null || !ex.where.surface()))
                LOG.info("[entropybot] explore: for {}: a part generates on the surface - known water chunks count exploreWords.waterRings {} rings farther (land first)",
                        ex.untilSource, io.github.mojolowjo.entropybot.vocab.ExploreWords.waterRings());
        }
        boolean surfaceTarget = ex.where != null && ex.where.surface();
        boolean bandTarget = ex.where != null && ex.where.band();
        // 0.39.z77 J2 E2 (runs AX 14:23, BC 06:24:02: "no path 3 times from -181 16 27" - GoalXZ targets through the rock over
        // the bot): a chain explore under aaLadder.surfaceDepth of ground aims only at cave cells IN the target's band within
        // cave.exploreTowardM, as a cell goal (Baritone may dig to it); never a never-seen chunk from down there (rule 22)
        int depthNow = ex.untilSource == null ? 0 : Jobs.belowSurface(p.level(), me[0], me[1], me[2]);   // run EH (rule 23): the one reader
        boolean under = ex.untilSource != null && depthNow >= io.github.mojolowjo.entropybot.aa.AaLadder.surfaceDepth();
        // 0.39.z48 (owner 2026-10-24, item 6): an ore need's explore aims at the nearest unvisited cave candidate within
        // cave.exploreTowardM (dropped ones are out of the list) before the next unexplored chunk. 0.39.z68: a band target
        // (GenWhere BAND) does so with the candidates and the seen mouths (Sightings) in its band first; a surface target
        // never; an unknown one as before (an ore by the c:ores tag)
        if (done == null && ex.untilSource != null && (bandTarget || (!surfaceTarget && oreNeed(ex.untilSource)))) {
            int within = (int) Math.floor(Tuning.get("cave.exploreTowardM"));
            // 0.39.z78 J6: the cave index's openings a sight ray has credited (CaveIndex.TARGET_RAYS: a hole the bot has seen;
            // the manager's ruling - one no ray reached is never a target, rule 17) in place of the heightmap's candidates, and
            // for a band target the known caves' entries; none: the explore explores plainly
            List<int[]> cells = new ArrayList<>(io.github.mojolowjo.entropybot.sense.ChunkPass.INSTANCE.caveIndex().openings(me[0], me[1], me[2], within));
            var sg = pass().sightings();
            if (bandTarget && sg != null) for (var m : sg.allKnownCaves(d))
                if (Jobs.distSq(me, new int[]{m.x(), m.y(), m.z()}) <= (long) within * within) cells.add(new int[]{m.x(), m.y(), m.z()});
            cells.sort(java.util.Comparator.comparingDouble(c2 -> Jobs.distSq(me, c2)));
            if (bandTarget) {
                // 0.39.z69 item 2 (owner: "does the data tell where the ore is denser?"): the caves ranked by the target's
                // density curve at their Y as a cave wall shows it (each placer's air-exposure discard taken off), densest
                // first, ties nearest first; a fact with no curve read: z68's placer band nearest the feet first
                var where = ex.where;
                if (!where.curves().isEmpty()) {
                    cells = ExploreRules.byCurve(cells, y -> where.curve(y, true), me, within);
                    if (!cells.isEmpty()) LOG.debug("[entropybot] explore: caves ranked by {}'s density at their Y (first at y {}: {})", ex.untilSource, cells.get(0)[1],
                            String.format(java.util.Locale.ROOT, "%.2f", where.curve(cells.get(0)[1], true)));
                } else {
                    var pb = where.preferred(me[1]);
                    cells = pb == null ? ExploreRules.bandFirst(cells, where.lo(), where.hi()) : ExploreRules.bandFirst(cells, pb.lo(), pb.hi());
                    if (pb != null && !cells.isEmpty()) LOG.debug("[entropybot] explore: caves in {}'s band {}..{} first (feet y {})", pb.feature(), pb.lo(), pb.hi(), me[1]);
                }
            }
            // 0.39.z77 J2 E2: from under ground only the cells in the target's band (its placer band nearest the feet)
            var inBand = under ? (bandTarget ? ex.where.preferred(me[1]) : null) : null;
            if (under) cells = inBand == null ? new ArrayList<>() : ExploreRules.inBand(cells, inBand.lo(), inBand.hi());
            final double moved = io.github.mojolowjo.entropybot.aa.brain.Failures.movedV();
            int[] aim = ExploreRules.toward(cells, cell -> CAVE_VISITED.contains(Jobs.fmt(cell))
                    || ExploreRules.TargetHold.skipCell(ex.failed, cell[0], cell[2], me, moved) || frozenCell(cell, me));   // run ED: failed from here: passed over
            if (aim != null) {
                ex.aimed = Jobs.fmt(aim);
                ex.target = new ExploreRules.Target(aim[0], aim[2]);
                ex.aimCell = aim.clone();
                ex.aimY = aim[1];                                // run ED: a cell goal (GoalNear) at the opening, on the surface too
                ex.targetWhy = "toward cave opening " + ex.aimed + " (the nearest unvisited within cave.exploreTowardM" + (bandTarget ? ", ranked by the band" : "")
                        + (under ? "; under ground: a cell in the band, its own Y" : "") + ")";
                LOG.info("[entropybot] explore: toward cave opening {} ({} m; cave.exploreTowardM {}){}", ex.aimed,
                        Math.round(Math.sqrt(Jobs.distSq(me, aim))), Math.round(Tuning.get("cave.exploreTowardM")), under ? " - a cell goal in the band (under ground)" : "");
            }
        }
        if (done == null && ex.target == null && under) {
            // 0.39.z77 J2 E2 (rule 7, rule 22): no cave cell in the band from down here - no GoalXZ through the rock either: the
            // explore ends with a fact the order re-plans on (a band target: its band is held - the next explore runs from the
            // surface, the walk up first; any other: this explore is held where it stands, NeedChain.frozenHere)
            String why = "under ground (" + depthNow + " blocks over the feet at " + me[0] + " " + me[1] + " " + me[2] + "): no cave cell in the band within cave.exploreTowardM "
                    + Math.round(Tuning.get("cave.exploreTowardM")) + " and no target a player would walk to from here (a never-seen chunk is a goal through rock; rule 22)";
            if (bandTarget) io.github.mojolowjo.entropybot.aa.moves.FactChange.band(ex.where.lo(), ex.where.hi(), why);
            else io.github.mojolowjo.entropybot.aa.moves.FactChange.exploreHere(me, why);
            LOG.info("[entropybot] explore: {}", why);
            if (b != null) Jobs.cancel(b);
            notes().flush();
            return why;
        }
        if (done == null && ex.target == null) {
            // V1b: the nearest never-seen chunk (that way, with a direction); never a spot the policy refuses
            final int y = me[1];
            int[] c = io.github.mojolowjo.entropybot.vocab.ExploreWords.next(me[0] >> 4, me[2] >> 4, ex.sx, ex.sz, ex.dir,
                    // 0.23.1: unvisited = not in explored.json AND no painted map tile (a tile still loading counts as seen this round)
                    (cx, cz) -> io.github.mojolowjo.entropybot.vocab.ExploreWords.seen(notes().explored(ExploreRules.key(d, cx, cz)),
                            core.terrain() == null ? Boolean.FALSE : core.terrain().painted(d, cx, cz))
                            // 0.39.z72: a target that failed from near here is passed over (a fact about where it failed)
                            || ExploreRules.TargetHold.skip(ex.failed, cx, cz, me, io.github.mojolowjo.entropybot.aa.brain.Failures.movedV())
                            || frozenSkip(cx, cz, me),
                    (cx, cz) -> {
                        String why = io.github.mojolowjo.entropybot.commands.FenceRules.walkVerdict(io.github.mojolowjo.entropybot.api.BotAPI.check(d, (cx << 4) + 8, y, (cz << 4) + 8, "go"));
                        if (why != null) notes().markExplored(List.of(ExploreRules.key(d, cx, cz)));
                        return why != null;
                    },
                    // 0.39.z68 item (c): a surface target prefers land - a chunk the client has loaded whose centre column's top is water
                    // 0.39.z77 J2 E1: also when a part of a joined until word generates on the surface
                    surfaceTarget || ex.landFirst ? (cx, cz) -> waterChunk(p.level(), cx, cz) : null, io.github.mojolowjo.entropybot.vocab.ExploreWords.waterRings());
            if (c != null) {
                ex.target = new ExploreRules.Target((c[0] << 4) + 8, (c[1] << 4) + 8);
                ex.targetWhy = "the nearest never-seen chunk " + c[0] + "," + c[1] + (surfaceTarget ? " (a surface target: land first)" : "");
            }
        }
        if (ex.target == null) {
            List<ExploreRules.Poi> fresh = new ArrayList<>();
            for (ExploreRules.Poi q : pois()) if (!ex.poiIds.contains(q.id())) fresh.add(q);
            s.note = ExploreRules.note(ex.chunks, now() - ex.start, done != null ? done : "nothing left to explore in reach", fresh, ex.from, Jobs.here(p));
            if (ex.find != null) s.note = "no " + ex.find.what().replaceFirst("^minecraft:", "") + " found (" + (done != null ? done : "nothing left in reach") + "); " + s.note;
            s.note += tookText(ex.took);
            // 0.39.z47 (run H): a chain's explore that ends with no new source is not a find: the order's next explore waits
            // explore.retryS (Goals.blocked), said once here
            if (ex.untilSource != null) {
                io.github.mojolowjo.entropybot.aa.brain.Goals.exploreEndedBare(System.currentTimeMillis());
                String wait = "no new source; next explore in " + io.github.mojolowjo.entropybot.aa.brain.Goals.exploreRetryS() + " s";
                LOG.info("[entropybot] explore: {}", wait);
                s.note = wait + "; " + s.note;
            }
            // owner 2026-10-20 ("done exploring for 5 min" after 1 min): the label says the real time and why it ended
            long realMin = Math.max(0, Math.round((now() - ex.start) / 1200.0));
            String why = done != null ? done : "nothing left to explore in reach";
            // 0.39.z75 log (rule 7): every end of an explore says why, and the found sources it held for the plan
            LOG.info("[entropybot] explore: ended after {} min ({}){}", realMin, why, ex.heldSaid.isEmpty() ? ""
                    : "; seen but not used by the order's plan: " + String.join(", ", ex.heldSaid) + (ex.foundAsked == null ? "" : " (priced again on " + ExploreRules.sourceOf(ex.foundAsked) + ")"));
            s.label = (ex.find != null ? "looking for a " + ex.find.what().replaceFirst("^minecraft:", "") : "exploring")
                    + " - ended after " + realMin + " min (" + why + ")";
            if (s.job() != null) s.job().label = s.label;
            int[] base = basePos();
            if (base != null) s.splice(s.idx + 1, List.of(Seq.Step.walk(base, true)));
            notes().flush();
            return "next";
        }
        if (b != null) {
            b.getCustomGoalProcess().setGoalAndPathNoBreak(exploreGoal(ex));      // J6b item 5: never breaking, never placing
        }
        ex.targetAt = now();
        ex.hold = ExploreRules.TargetHold.start(ex.target, now(), me, io.github.mojolowjo.entropybot.baritone.BaritoneHook.calcFailed());   // 0.39.z72
        return "wait";
    }

    // ==== mine <block> [n] [dig]: the per-ore steps, or Baritone's mine (no index, or not an ore) ====

    static final class MineRun {
        String target, text, need, phase = "mining", wdSig, toolNote, heldBy;
        Block block;
        int count;
        boolean dig, ore, req, started, afterTool, held, leaseWarned;
        Map<String, Integer> before = new LinkedHashMap<>();
        long deadline, startTick, lastAct = Long.MIN_VALUE / 2, wdSince;
        int[] anchor;
        final List<String> leases = new ArrayList<>();
        /** "mine iron" = any block of the family (deepslate or not); "mine iron_ore" = exactly that block. */
        boolean familyWord;
        /** rm-mining: the per-ore step's cell (the at form's, or the loop's current pick; null between picks) and its dig|walk. */
        int[] at;
        boolean atDig;
        /** The owner's plain "mine <ore> N" with the index up: a loop of per-ore steps. */
        boolean loop;
        /** The step's spliced walk (its target) or vein clear, and the failure Seq caught for them. */
        int[] kTarget;
        Seq.Step kClear;
        String kFailed;
        List<Pos> kVein = new ArrayList<>();
        /** Per-ore steps done; the ore blocks the vein clears mined, by id (the end line names them). */
        int veins;
        final Map<String, Integer> kBlocks = new LinkedHashMap<>();
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
        if (!commands.workAllowed(d, me[0], me[2])) return "error: I am " + commands.policy.blockedText(d, me[0], me[2]) + " - I mine nothing here";
        if (Jobs.baritone() == null) return "error: baritone not loaded";
        // wave 1, item 8: Baritone's mine digs where it likes, so never inside or near a protect box (the base)
        MineRules.Near pn = MineRules.protectNear(boxes(pol, "protect"), d, me[0], me[1], me[2], MineRules.protectMargin());
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
            r.atDig = at.dig();
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

    private int mineCount(LocalPlayer p, MineRun j) { return MineRules.count(j.target, gained(p, j), commands.aaRuntime == null ? null : commands.aaRuntime.currentLootBook()); }

    private String gains(LocalPlayer p, MineRun j) { return MineRules.gains(gained(p, j)); }

    private static String sid(MineRun j) { return MineRules.shortId(j.target); }

    /** The break leases around me (the mine box minus the protect boxes, sliced): null, or the refusal (strict mode). */
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

    /**
     * The mine's policy intent. 0.39.z8 (owner 2026-10-22): no Baritone setting here; the fork's mine run
     * ({@code mineWith}) owns breaking for its run and the policy prices which blocks (the protected blocks never).
     */
    private static void mineSettings(MineRun j) {
        try {
            // mining plan phase 1: Baritone's mine digs under the MINE intent
            io.github.mojolowjo.entropybot.guard.PolicyRuntime.INSTANCE.setIntent(io.github.mojolowjo.entropybot.guard.BreakPolicy.Intent.MINE, "mine");
        } catch (Throwable t) {
            LOG.warn("[entropybot] mine: Baritone's mine settings could not be set: {}", t.toString());
        }
    }

    /** The job's hooks: a fight holds it (Baritone's mine stops, breaking off), and however it ends, breaking goes off. */
    private void hooks(Jobs.Job job, MineRun j) {
        job.holdOnFight = true;
        // P1: with dig, every block but the target that Baritone breaks is a path break the restore ledger puts back
        RestoreLive.INSTANCE.pathContext(job.id, BuiltInRegistries.BLOCK.getKey(j.block).toString());
        // 0.36.8: while this job runs, breaking next to water is allowed
        MineWater.start(job);
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
            Seq.Step go = new Seq.Step("mineore");
            go.text = text;
            Seq s = new Seq(jobs, storage, "getting " + MineRules.anA(r.need) + " pickaxe, then mining " + sid(r),
                    List.of(toolStep(r.need, "mining " + sid(r), r.target, false), go), "always");
            runs.put(s, r);
            String res = jobs.startSeq(s, "always");
            hooks(jobs.job, r);
            return res;
        }
        r.anchor = me;
        String le = takeLeases(r, me);
        if (le != null) {
            releaseLeases(r);
            return le;
        }
        String status = MineRules.status(r.count, r.target, r.dig);
        Seq s = new Seq(jobs, storage, status, List.of(new Seq.Step("mineore")), "always");
        runs.put(s, r);
        jobs.startSeq(s, "always");
        hooks(jobs.job, r);
        RestoreLive.INSTANCE.scanBuilds(p, me);          // P1: someone's build near here? (a whisper, never a box)
        begin(p, r);
        // started while a fight or a meal already holds the bot: the hold hook only fires on a new hold, so apply it now
        if (core.reflexes.hold() && jobs.job.onHold != null) jobs.job.onHold.run();
        return "started: " + status + (r.at != null ? " at " + Jobs.fmt(r.at) + " and its vein" : "") + (r.familyWord ? " (any " + io.github.mojolowjo.entropybot.sense.KnownOre.familyOf(r.target) + " ore block, deepslate or not)" : "");
    }

    /** The per-ore steps (known ore), else Baritone's mine from here. */
    private void begin(LocalPlayer p, MineRun r) {
        r.started = true;
        r.startTick = now();
        r.deadline = now() + MineRules.maxTicks();
        r.before = Gui.inventory(p);
        mineSettings(r);
        if (r.need != null) holdPick(p, r);
        if (knownStart(p, r)) return;
        IBaritone b = Jobs.baritone();
        if (b != null) b.getMineProcess().mineWith(r.count, new baritone.api.utils.BlockOptionalMetaLookup(r.block), false);
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
        String le = takeLeases(r, me);
        if (le != null) {
            releaseLeases(r);
            return le.replaceFirst("^error: ", "");
        }
        runs.put(s, r);
        hooks(s.job(), r);
        String status = MineRules.status(r.count, r.target, r.dig);
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
        if (j.afterTool || j.held) {
            // back from a pickaxe trip or a fight: the per-ore step looks where it got to; Baritone's mine starts again from here
            j.afterTool = false;
            j.held = false;
            j.lastAct = now;
            String r = j.phase.equals("known") ? null : mineGo(s, p, j);
            if (r != null) return end(s, j, r + ", mining " + sid(j) + gains(p, j));
            return "wait";
        }
        if (now - j.lastAct < 20) return "wait";
        j.lastAct = now;
        core.guard.core.heartbeat(core.token);
        if (j.phase.equals("known")) return knownStep(s, p, j);
        IBaritone b = Jobs.baritone();
        int[] me = Jobs.here(p);
        if (b != null && now > j.deadline) return end(s, j, "stopped: " + MineRules.timeUpText() + ", mining " + sid(j) + gains(p, j));
        if (now - j.startTick < 60) return "wait";
        j.anchor = me;
        if (j.ore && j.count > 0 && mineCount(p, j) >= j.count) return end(s, j, MineRules.doneText(j.target, gains(p, j), j.toolNote));
        String pc = pickCheck(s, p, j);
        if (pc != null) return pc;
        if (j.ore && stalled(p, j)) return end(s, j, MineRules.stalledText(j.target, Jobs.fmt(me), gains(p, j)));
        if (b == null || Jobs.idle(b)) {
            if (j.ore && b != null) return mineEnd(s, p, j, "Baritone's mine found no more " + sid(j) + " in reach");
            return end(s, j, MineRules.doneText(j.target, gains(p, j), null));
        }
        return "wait";
    }

    /** No move and nothing picked up for mine.stallS while Baritone mines. */
    private boolean stalled(LocalPlayer p, MineRun j) {
        int c = 0;
        for (int i = 0; i < 36; i++) c += p.getInventory().getItem(i).getCount();
        String sig = Jobs.fmt(Jobs.here(p)) + " " + c;
        if (j.wdSig == null || !j.wdSig.equals(sig)) {
            j.wdSig = sig;
            j.wdSince = now();
            return false;
        }
        return now() - j.wdSince >= MineRules.stallTicks();
    }

    /** Baritone's mine again from where the bot is now: fresh leases here, the settings, the rest of the count; null or why not. */
    private String mineGo(Seq s, LocalPlayer p, MineRun j) {
        IBaritone b = Jobs.baritone();
        if (b == null) return "stopped: baritone not loaded";
        Jobs.cancel(b);
        int[] me = Jobs.here(p);
        MineRules.Near pn = MineRules.protectNear(boxes(policy(), "protect"), dim(), me[0], me[1], me[2], MineRules.protectMargin());
        if (pn != null) return "stopped: " + MineRules.protectNearText(pn);
        releaseLeases(j);
        String le = takeLeases(j, me);
        if (le != null) return le.replaceFirst("^error: ", "stopped: ");
        mineSettings(j);
        if (j.need != null) holdPick(p, j);
        if (j.dig && s.job() != null) RestoreLive.INSTANCE.pathContext(s.job().id, BuiltInRegistries.BLOCK.getKey(j.block).toString());
        b.getMineProcess().mineWith(Math.max(1, j.count - mineCount(p, j)), new baritone.api.utils.BlockOptionalMetaLookup(j.block), false);
        j.phase = "mining";
        j.anchor = me;
        j.startTick = now();
        j.wdSig = null;
        if (s.job() != null) s.job().reflex = false;
        s.setStatus(MineRules.status(j.count, j.target, j.dig));
        return null;
    }

    /** The pickaxe while mining: in the hotbar, or none left (it broke): the job ends with the tool stage (AA re-prices). */
    private String pickCheck(Seq s, LocalPlayer p, MineRun j) {
        if (j.need == null) return null;
        int i = pickSlot(p, j);
        if (i >= 0) {
            if (i >= 9) holdPick(p, j);
            return null;
        }
        return end(s, j, MineRules.noPickText(j.target, gains(p, j)));
    }

    // ---- the pickaxe trip before the mine starts (the bridge's toolget/toolcheck) ----

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
            io.github.mojolowjo.entropybot.aa.StationFact tf = crafting.findTable(p);
            if (tf instanceof io.github.mojolowjo.entropybot.aa.StationFact.NotLoaded n) {      // rule 15: wait with the reason
                s.setStatus(s.label + " - " + Crafting.notLoadedText(n));
                return "wait";
            }
            int[] tb = tf instanceof io.github.mojolowjo.entropybot.aa.StationFact.Known k ? Crafting.cell(k) : null;
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
