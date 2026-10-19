package io.github.mojolowjo.entropybot.commands;

import baritone.api.IBaritone;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.goals.GoalNear;
import com.mojang.logging.LogUtils;
import io.github.mojolowjo.entropybot.Core;
import io.github.mojolowjo.entropybot.cave.MineRules;
import io.github.mojolowjo.entropybot.chop.ChopLoop;
import io.github.mojolowjo.entropybot.chop.ChopRules;
import io.github.mojolowjo.entropybot.chop.TreeFinder;
import io.github.mojolowjo.entropybot.clear.ClearJob;
import io.github.mojolowjo.entropybot.clear.ClearRules;
import io.github.mojolowjo.entropybot.clear.Pos;
import io.github.mojolowjo.entropybot.guard.Box;
import io.github.mojolowjo.entropybot.gui.Gui;
import io.github.mojolowjo.entropybot.storage.StorageRules;
import io.github.mojolowjo.entropybot.surface.SurfaceColumns;
import io.github.mojolowjo.entropybot.surface.SurfaceExport;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.fml.ModList;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * P3 (SURVIVAL_PLAN P3): "chop &lt;logs&gt; [log type]" / "chop trees &lt;n&gt; [log type]" / "chop status". A {@link Seq} job
 * whose "chopstep" decides and splices the work in front of itself: a walk to the tree, a clear of its bottom log(s)
 * (the clear engine: reach, sight, the best-fitting axe, its own break lease, fights and meals hold it, a full bag goes
 * to the base chests), a 2 s wait (Falling Trees' fall), a re-scan of the tree's own logs and more clears until none
 * stands (TreeChop needs several chops; with neither mod each log is broken), a pickup of the drops, then the sapling
 * placed where the trunk stood ({@link Clearing#placeStep}, a place lease). Leaves are never broken (the owner: they
 * decay). The decisions are pure in {@code chop/} ({@link TreeFinder}, {@link ChopRules}).
 * <p>
 * Loader notes: candidates come from the surface export's column source ({@link SurfaceExport#liveSource}, vanilla
 * client level reads); the tree mods' presence from {@code net.neoforged.fml.ModList.get().isLoaded} (Fabric:
 * {@code FabricLoader.getInstance().isModLoaded}); everything else goes through the existing NeoForge-free paths
 * (ClearEngine breaks via the game mode, placing via {@code useItemOn}). No new hooks or mixins. The breaks are the
 * job's purpose, so the P1 restore ledger records none of them (no path context is set).
 */
final class Chopping {
    private static final Logger LOG = LogUtils.getLogger();
    private static Chopping instance;

    final Core core;
    final Commands commands;
    final Jobs jobs;
    final Storage storage;
    final Crafting crafting;
    private final Map<Seq, Run> runs = new WeakHashMap<>();
    /** For "chop status": the totals since the game started and the last end line. */
    private int treesTotal, logsTotal, replantedTotal;
    private String lastEnd = "none yet";

    private Chopping(Core core) {
        this.core = core;
        this.commands = core.commands;
        this.jobs = commands.jobs;
        this.storage = commands.storage;
        this.crafting = commands.crafting;
    }

    static synchronized Chopping get() {
        if (instance == null) instance = new Chopping(Core.INSTANCE);
        return instance;
    }

    /** "chop status" answers at once, even mid-job. */
    static boolean instant(String rest) { return ChopRules.parse(rest).status() || (rest != null && rest.trim().toLowerCase().startsWith("check ")); }

    /** "chop check x z": what the tree search sees in that column (for a "no trees" that looks wrong). */
    private String probe(LocalPlayer p, String rest) {
        String[] w = rest.trim().split("\\s+");
        if (w.length != 3) return "usage: chop check x z";
        int x, z;
        try {
            x = Integer.parseInt(w[1]);
            z = Integer.parseInt(w[2]);
        } catch (NumberFormatException e) {
            return "usage: chop check x z";
        }
        int[] me = Jobs.here(p);
        SurfaceColumns.Source src = SurfaceExport.liveSource(p.clientLevel);
        int top = src.top(x, z);
        StringBuilder sb = new StringBuilder("column " + x + " " + z + ": top " + top + ", kinds");
        for (int y = Math.min(top, me[1] + 40); y >= Math.min(top, me[1] + 40) - 8; y--) sb.append(" ").append(y).append("=").append(src.kind(x, y, z)).append("/").append(src.family(x, y, z));
        int y = ChopRules.logTop(src, x, z, me[1] + 40, me[1] - 24);
        sb.append("; log top ").append(y).append("; in areas ").append(commands.inAreas(Storage.dim(), x, z));
        if (y != ChopRules.NONE) {
            TreeFinder.Result r = TreeFinder.find(new Live(p.clientLevel), x, y, z);
            sb.append("; ").append(r.ok() ? "a tree of " + r.tree().logs().size() + " " + ChopRules.shortId(r.tree().logId()) + " at " + r.tree().at() : r.why());
        }
        return sb.toString();
    }

    private long now() { return core.tick(); }

    static final class Run {
        ChopRules.Args args;
        long deadline;
        int trees, replanted, failsInRow, rounds, lastLeft;
        final Map<String, Integer> got = new LinkedHashMap<>();
        final Set<String> tried = new HashSet<>();
        final List<String> notes = new ArrayList<>();
        int skippedBuilt;
        String firstBuilt, nearestOutside, axeNote, firstWhy;
        int notLogs;            // 0.32.1: wood/stripped column tops skipped
        int looked;
        TreeFinder.Tree tree;
        Map<String, Integer> before;
        String phase;
        List<int[]> planted = List.of();
        String sapling;
        int brokeAny;
        /** 0.26.4 TreeChop: this tree's loop, the trunk cell watched for chops, run totals. */
        ChopLoop.State loop = new ChopLoop.State();
        int[] trunk;
        String trunkId, trunkSig;
        int chopsTotal, reachFails;
        int farTrips;           // 0.34.0: walks to trees Baritone remembers
        int[] farAt;            // 0.34.1: the last trip's target
        String nearestReachable;
        boolean treeChop;
        int pickBag, pickGot;   // 0.35.5: the pickup's bag count and items gained
        long pickGainAt;        // 0.35.5: the pickup step's tick of the last gain
    }

    // ==== the verb ====

    String command(LocalPlayer p, String rest) {
        if (rest != null && rest.trim().toLowerCase().startsWith("check ")) return probe(p, rest);
        ChopRules.Args a = ChopRules.parse(rest);
        if (a.error() != null) return a.error();
        if (a.status()) return status();
        if (jobs.running() && !jobs.walking()) return "error: busy with \"" + jobs.job.status + "\" - send stop first";
        try {
            if (MineRules.policyBoxes(MineRules.parsePolicy(commands.policyJson()), "areas", PolicyCommands.DEFAULT_DIM).isEmpty())
                return "error: I chop only inside my areas, and there are none - " + PolicyCommands.AREA_HINT;
        } catch (MineRules.BadPolicy e) {
            return "error: " + MineRules.badPolicyText(e).replace("I won't mine", "I won't chop");
        }
        int[] me = Jobs.here(p);
        if (!commands.inAreas(Storage.dim(), me[0], me[2])) return "error: I'm outside my areas - walk me into one first (" + PolicyCommands.AREA_HINT.trim() + ")";
        if (Jobs.baritone() == null) return "error: baritone not loaded";
        Run r = new Run();
        r.args = a;
        r.treeChop = treeChopLoaded();
        r.deadline = now() + ChopRules.MAX_TICKS;
        String what = a.trees() ? a.n() + (a.n() == 1 ? " tree" : " trees") : a.n() + " logs";
        String label = "chopping " + what + (a.type() != null ? " (" + a.type() + ")" : "");
        List<Seq.Step> steps = new ArrayList<>();
        if (axeSlot(p) < 0) steps.add(new Seq.Step("chopaxe"));
        steps.add(new Seq.Step("chopstep"));
        Seq s = new Seq(jobs, storage, label, steps, "always");
        runs.put(s, r);
        String res = jobs.startSeq(s, "always");
        jobs.job.holdOnFight = true;              // a fight or a meal holds the chopping, it doesn't end it
        RestoreLive.INSTANCE.scanBuilds(p, me);   // P1: someone's build near here? (a whisper, never a box)
        LOG.info("[entropybot] chop: {} ({})", label, modsLine());
        return res;
    }

    /** 0.26.4: TreeChop present (each break of a trunk is a chop; the tree falls after enough). */
    static boolean treeChopLoaded() {
        try {
            return ModList.get().isLoaded("treechop");
        } catch (RuntimeException e) {
            LOG.warn("[entropybot] chop: can't read the mod list: {}", e.toString());
            return false;
        }
    }

    /** 0.26.4 brain settings chop.maxChops / chop.maxTrees (defaults until the brain loaded them). */
    static int setting(String key, int dflt) {
        try { return io.github.mojolowjo.entropybot.brain.BrainConfig.current().i(key); } catch (RuntimeException e) { return dflt; }
    }

    private static String modsLine() {
        boolean ft = false, tc = false;
        try {
            ft = ModList.get().isLoaded("fallingtrees");
            tc = ModList.get().isLoaded("treechop");
        } catch (RuntimeException e) {
            LOG.warn("[entropybot] chop: can't read the mod list: {}", e.toString());
        }
        return ChopRules.modsLine(ft, tc);
    }

    private String status() {
        return "chop: " + modsLine() + "; since the game started " + treesTotal + " trees, " + logsTotal + " logs, " + replantedTotal
                + " replanted; last: " + lastEnd + (jobs.running() && jobs.job.seq != null && runs.containsKey(jobs.job.seq) ? "; running: " + jobs.job.status : "");
    }

    // ==== the steps ====

    String step(Seq s, Seq.Step st, LocalPlayer p, long elapsed) {
        Run r = runs.get(s);
        if (r == null) return "the chop run is gone";
        try {
            switch (st.type) {
                case "chopstep": return main(s, r, p);
                case "chopwait": return elapsed >= st.ticks ? "next" : "wait";
                case "choppickup": return pickup(s, st, r, p, elapsed);
                case "chopplant": return plant(s, r, p);
                case "chopaxe": return axe(s, r, p);
                case "chopaxecheck": return axeCheck(s, st, r, p);
                case "chopgather": return gatherAfter(s, r, p);
                default: return "unknown step " + st.type;
            }
        } catch (MineRules.BadPolicy e) {
            return end(s, r, p, MineRules.badPolicyText(e).replace("I won't mine", "I won't chop"));
        }
    }

    private void spliceHere(Seq s, List<Seq.Step> add) {
        s.splice(s.idx, add);
        s.stepStart = now();
        s.stage = null;
    }

    private String main(Seq s, Run r, LocalPlayer p) {
        Live w = new Live(p.clientLevel);
        if (r.tree != null && "fell".equals(r.phase)) return afterBreak(s, r, p, w);
        if (r.tree != null && "planted".equals(r.phase)) treeDone(r, p, w);
        String why = ChopRules.stopReason(r.args, ChopRules.total(r.got), r.trees, now(), r.deadline, r.failsInRow, r.reachFails,
                setting("chop.maxTrees", ChopLoop.DEFAULT_MAX_TREES), r.nearestReachable);
        if (why != null) return end(s, r, p, why);
        if (Mining.get().bagRoom(p) <= 2) {
            List<Seq.Step> dep = depositSteps(p);
            if (dep == null) return end(s, r, p, "my bag is full and I can't put things away (\"scan base\" once)");
            int[] me = Jobs.here(p);
            dep.add(Seq.Step.walk(me, true));
            spliceHere(s, dep);
            s.setStatus(s.label + " - bag full, putting things away");
            return "wait";
        }
        TreeFinder.Tree t = nextTree(r, p, w);
        if (t == null) {
            String far = farTree(s, r, p);
            if (far != null) return far;
            String none = r.trees == 0 ? "no trees in my areas" + (r.looked > 0 ? " (" + r.looked + " log columns looked at" + (r.firstWhy != null ? "; " + r.firstWhy : "") + ")"
                    : r.nearestOutside != null ? " (nearest log at " + r.nearestOutside + " is outside; " + coverOutside(r.nearestOutside, p) + ")" : "")
                    : "no more trees in my areas";
            return end(s, r, p, none);
        }
        r.tree = t;
        r.phase = "fell";
        r.rounds = 0;
        r.lastLeft = t.logs().size();
        r.brokeAny = 0;
        r.loop = new ChopLoop.State();
        r.trunk = TreeFinder.lowest(t.logs()).get(0).clone();
        r.trunkId = w.id(r.trunk[0], r.trunk[1], r.trunk[2]);
        r.trunkSig = w.sig(r.trunk[0], r.trunk[1], r.trunk[2]);
        r.before = Gui.inventory(p);
        r.tried.add(TreeFinder.key(t.base()[0], t.base()[1], t.base()[2]));
        LOG.info("[entropybot] chop: tree at {} ({} logs of {})", t.at(), t.logs().size(), t.logId());
        s.setStatus(s.label + " - the " + ChopRules.shortId(t.logId()).replace("_log", "") + " tree at " + t.at() + " (" + ChopRules.total(r.got) + " logs so far)");
        List<Seq.Step> add = new ArrayList<>();
        add.add(Seq.Step.walk(t.base().clone(), true));
        add.addAll(breakSteps(r, TreeFinder.lowest(t.logs())));
        spliceHere(s, add);
        return "wait";
    }

    /** 0.34.0: at most this many walks to remembered trees per run. */
    static final int FAR_TRIPS = 3;
    /** 0.34.1: remembered trees at most this far (blocks, horizontal); brain setting chop.farMax. */
    static final int FAR_MAX = 256;

    /**
     * 0.34.0: no tree in the live scan - ask Baritone's chunk cache (our build remembers logs) for the nearest log in my
     * areas beyond what the scan saw, walk there and look again. A remembered log whose chunk is loaded and gone is
     * dropped (BaritoneMemory logs it). Null: nothing remembered (the run ends as before).
     */
    private String farTree(Seq s, Run r, LocalPlayer p) {
        int[] me = Jobs.here(p);
        Live lw = new Live(p.clientLevel);
        if (r.farAt != null) {
            // 0.34.1: the last trip's tree (its chunk was unloaded when picked): say so when it turned out gone
            int[] a = r.farAt;
            r.farAt = null;
            if (p.level().isLoaded(new BlockPos(a[0], a[1], a[2])) && !lw.log(a[0], a[1], a[2]))
                LOG.info("[entropybot] chop: the remembered tree at {} {} {} is gone (its chunk is loaded now) - dropped", a[0], a[1], a[2]);
        }
        if (r.farTrips >= FAR_TRIPS) return null;
        String dim = Storage.dim();
        List<Box> prot = MineRules.policyBoxes(MineRules.parsePolicy(commands.policyJson()), "protect", PolicyCommands.DEFAULT_DIM);
        int farMax = setting("chop.farMax", FAR_MAX);
        List<String> ids = new ArrayList<>();
        for (net.minecraft.resources.ResourceLocation k : net.minecraft.core.registries.BuiltInRegistries.BLOCK.keySet()) {
            String id = k.toString(), path = k.getPath();
            if (path.endsWith("_log") && !path.startsWith("stripped_") && ChopRules.matches(r.args.type(), id)) ids.add(id);
        }
        int near = ChopRules.RADIUS - 4;
        List<io.github.mojolowjo.entropybot.sense.MemoryPick.Spot> got = io.github.mojolowjo.entropybot.sense.BaritoneMemory.recall(p, ids, (x, y, z) -> {
            if (r.tried.contains("far " + x + " " + z)) return false;
            if (io.github.mojolowjo.entropybot.chop.Unreachable.blocked(x, z, System.currentTimeMillis())) return false;      // 0.36.3
            if (!commands.inAreas(dim, x, z) || jobs.goalAllowed(x, y, z) != null) return false;
            // 0.34.1 (review): never toward a protect box (margin as the mine's), nor a loaded log the tree finder
            // judges built (a log house) or not a tree; unloaded ones are judged by the scan on arrival
            if (MineRules.protectNear(prot, dim, x, y, z, MineRules.PROTECT_MARGIN) != null) return false;
            BlockPos q = new BlockPos(x, y, z);
            boolean loaded = p.level().isLoaded(q);
            long dx = x - me[0], dz = z - me[2];
            if (loaded && dx * dx + dz * dz <= (long) near * near) return false;     // 0.34.2 (review): the cheap check first
            return !loaded || TreeFinder.find(lw, x, y, z).ok();
        }, 64, "chop", farMax);
        if (got.isEmpty()) return null;
        // the nearest remembered log may be high up the trunk: walk to the lowest one of that column
        io.github.mojolowjo.entropybot.sense.MemoryPick.Spot t = got.get(0);
        for (io.github.mojolowjo.entropybot.sense.MemoryPick.Spot o : got)
            if (o.x() == t.x() && o.z() == t.z() && o.y() < t.y()) t = o;
        // the trip spends the column (any log of that tree is the same trip)
        for (int dx = -3; dx <= 3; dx++) for (int dz = -3; dz <= 3; dz++) r.tried.add("far " + (t.x() + dx) + " " + (t.z() + dz));
        r.farTrips++;
        r.farAt = new int[]{t.x(), t.y(), t.z()};
        String text = io.github.mojolowjo.entropybot.sense.MemoryPick.text(t, me);
        if (r.notes.stream().noneMatch(n -> n.startsWith("remembered "))) r.notes.add(text);
        LOG.info("[entropybot] chop: no tree in view - {} (Baritone's memory), walking there ({}/{})", text, r.farTrips, FAR_TRIPS);
        s.setStatus(s.label + " - " + text + ", walking there");
        // the walk is a GoalNear(2) around the trunk's lowest remembered log: Baritone picks a standable cell beside it
        Seq.Step w = Seq.Step.walk(new int[]{t.x(), t.y(), t.z()}, true);
        w.key = "chopfar";              // a failed walk goes on to the next scan (caught), not the end of the run
        spliceHere(s, new ArrayList<>(List.of(w)));
        return "wait";
    }

    /** One break round: a clear of these cells, then 40 ticks for a fall (Falling Trees' animation is 1.5 s). */
    private List<Seq.Step> breakSteps(Run r, List<int[]> cells) {
        List<Pos> only = new ArrayList<>();
        for (int[] c : cells) only.add(new Pos(c[0], c[1], c[2]));
        Seq.Step clear = Clearing.clearStep(new ClearJob.Options().only(only).soft(true)
                .label("chopping the tree at " + r.tree.at()));
        Seq.Step wait = new Seq.Step("chopwait");
        wait.ticks = r.treeChop ? 12 : 40;     // TreeChop: a chop shows at once; Falling Trees' fall takes 1.5 s
        return new ArrayList<>(List.of(clear, wait));
    }

    /** After a break round: more rounds while its logs stand (and progress is made), then the pickup and the replant. */
    private String afterBreak(Seq s, Run r, LocalPlayer p, Live w) {
        List<int[]> left = TreeFinder.left(w, r.tree);
        String idNow = w.id(r.trunk[0], r.trunk[1], r.trunk[2]), sigNow = w.sig(r.trunk[0], r.trunk[1], r.trunk[2]);
        int gained = ChopRules.total(ChopRules.gained(r.before, Gui.inventory(p), Chopping::isLogItem));
        ChopLoop.Round rd = new ChopLoop.Round(r.lastLeft, left.size(), r.trunkId, idNow, r.trunkSig, sigNow, lastBroken(s), gained);
        int chopsBefore = r.loop.chops;
        ChopLoop.Next next = ChopLoop.step(r.loop, rd, r.treeChop, setting("chop.maxChops", ChopLoop.DEFAULT_MAX_CHOPS), ChopRules.ROUNDS);
        r.chopsTotal += r.loop.chops - chopsBefore;
        r.rounds = r.loop.rounds;
        if (left.size() < r.lastLeft) r.brokeAny += r.lastLeft - left.size();
        if (r.loop.chops > 0 || gained > 0) r.brokeAny = Math.max(r.brokeAny, 1);
        r.lastLeft = left.size();
        r.trunkId = idNow;
        r.trunkSig = sigNow;
        if (next == ChopLoop.Next.AGAIN) {
            LOG.debug("[entropybot] chop: round {} at {}: {} logs left, {} chops", r.rounds, r.tree.at(), left.size(), r.loop.chops);
            // TreeChop: keep chopping the same trunk block while it stands (the tree falls as a whole)
            List<int[]> cells = r.treeChop && w.log(r.trunk[0], r.trunk[1], r.trunk[2]) ? List.of(r.trunk) : left;
            spliceHere(s, breakSteps(r, cells));
            return "wait";
        }
        if (next == ChopLoop.Next.UNREACHABLE) {
            // not one log came down and no chop: no spot to stand within reach (low leaves all around a short trunk), a guard refusal...
            String clr = lastClear(s);
            r.notes.add(ChopLoop.skipNote(r.tree.at(), false, clr));      // 0.27.1: "couldn't get at" only for a reach failure
            // 0.36.3: remembered 30 min per world: the next pick, the far pick and the planner's trees fact leave it out
            io.github.mojolowjo.entropybot.chop.Unreachable.mark(r.tree.base()[0], r.tree.base()[2], System.currentTimeMillis());
            LOG.info("[entropybot] chop: nothing came down at {} - skipped", r.tree.at());
            r.failsInRow++;
            r.reachFails++;
            r.tree = null;
            r.phase = null;
            return main(s, r, p);
        }
        if (next == ChopLoop.Next.PARTIAL && !left.isEmpty()) {
            int[] c = left.get(0);
            r.notes.add("tree at " + r.tree.at() + ": " + left.size() + " logs left (" + (r.treeChop && r.loop.chops > 0
                    ? r.loop.chops + " chops and it didn't fall" : "out of reach at " + c[0] + " " + c[1] + " " + c[2]) + ")");
            LOG.info("[entropybot] chop: giving up on {} logs at {} ({} chops)", left.size(), r.tree.at(), r.loop.chops);
        } else if (r.treeChop) {
            LOG.info("[entropybot] chop: the tree at {} fell after {} chops", r.tree.at(), r.loop.chops);
        }
        r.phase = "planted";
        Seq.Step pick = new Seq.Step("choppickup");
        spliceHere(s, new ArrayList<>(List.of(pick, new Seq.Step("chopplant"))));
        return "wait";
    }

    /** The blocks the last clear step of this job broke (with TreeChop each is a chop), 0 when none ran. */
    private static int lastBroken(Seq s) {
        for (int i = s.idx - 1; i >= 0; i--) {
            Seq.Step st = s.steps.get(i);
            if (st.cleared != null) return st.cleared.broken();
        }
        return 0;
    }

    /** Why the last clear step of this job left blocks (its message's tail), or null. */
    private static String lastClear(Seq s) {
        for (int i = s.idx - 1; i >= 0; i--) {
            Seq.Step st = s.steps.get(i);
            if (st.cleared != null && st.cleared.message() != null) {
                String m = st.cleared.message();
                // 0.31.0: the walk ended where the trunk was not in sight ("couldn't reach it"): a reach failure too
                if (m.contains(io.github.mojolowjo.entropybot.clear.ClearEngine.COULD_NOT_REACH)) return "not in sight from where I stood";
                int k = m.indexOf("out of reach");
                return k >= 0 ? m.substring(k).replaceFirst("^out of reach \\(", "").replaceFirst("\\)$", "") : null;
            }
        }
        return null;
    }

    /** The tree is done: what it gave, what was planted. */
    private void treeDone(Run r, LocalPlayer p, Live w) {
        Map<String, Integer> g = ChopRules.gained(r.before, Gui.inventory(p), Chopping::isLogItem);
        g.forEach((k, v) -> r.got.merge(k, v, Integer::sum));
        int planted = 0;
        for (int[] c : r.planted) if (r.sapling != null && r.sapling.equals(w.id(c[0], c[1], c[2]))) planted++;
        r.replanted += planted;
        if (r.brokeAny > 0 || ChopRules.total(g) > 0) {
            r.trees++;
            r.failsInRow = 0;
            r.reachFails = 0;
        } else {
            r.failsInRow++;
        }
        LOG.info("[entropybot] chop: tree at {} done: {}, replanted {}", r.tree.at(), ChopRules.logs(g), planted);
        r.tree = null;
        r.phase = null;
        r.planted = List.of();
    }

    /** The nearest tree in my areas that passes the finder (and the type), or null. */
    private TreeFinder.Tree nextTree(Run r, LocalPlayer p, Live w) {
        String dim = Storage.dim();
        List<Box> prot = MineRules.policyBoxes(MineRules.parsePolicy(commands.policyJson()), "protect", PolicyCommands.DEFAULT_DIM);
        int[] me = Jobs.here(p);
        SurfaceColumns.Source src = SurfaceExport.liveSource(p.clientLevel);
        List<int[]> inside = new ArrayList<>();
        long bestOut = Long.MAX_VALUE;
        for (int x = me[0] - ChopRules.RADIUS; x <= me[0] + ChopRules.RADIUS; x++)
            for (int z = me[2] - ChopRules.RADIUS; z <= me[2] + ChopRules.RADIUS; z++) {
                int y = ChopRules.logTop(src, x, z, me[1] + 40, me[1] - 24);
                if (y == ChopRules.NONE) continue;
                // 0.32.1 (P1, Haiku run: "no log at -249 71 123" three runs in a row): the column source counts stripped
                // logs and 6-sided wood as LOG, the tree finder doesn't: only a column whose top is a real log is a candidate
                if (!w.log(x, y, z)) { r.notLogs++; continue; }
                if (commands.inAreas(dim, x, z)) inside.add(new int[]{x, y, z});
                else {
                    long d = (long) (x - me[0]) * (x - me[0]) + (long) (z - me[2]) * (z - me[2]);
                    if (d < bestOut) {
                        bestOut = d;
                        r.nearestOutside = x + " " + y + " " + z;
                    }
                }
            }
        r.looked = inside.size();
        LOG.info("[entropybot] chop: {} log columns in my areas within {} (nearest outside: {}; {} wood/stripped tops skipped)", inside.size(), ChopRules.RADIUS, r.nearestOutside, r.notLogs);
        int checked = 0;
        List<ChopLoop.Cand> cands = new ArrayList<>();
        Map<String, TreeFinder.Tree> found = new java.util.HashMap<>();
        int limit = Math.max(24, setting("chop.maxTrees", ChopLoop.DEFAULT_MAX_TREES) * 2);
        for (int[] c : ChopRules.order(inside, me[0], me[1], me[2])) {
            if (checked >= limit) break;
            TreeFinder.Result res = TreeFinder.find(w, c[0], c[1], c[2]);
            if (!res.ok()) {
                if (r.firstWhy == null) r.firstWhy = res.why();
                if (res.why().contains("built blocks")) {
                    String k = "built " + res.why();
                    if (r.tried.add(k)) {
                        r.skippedBuilt++;
                        if (r.firstBuilt == null) r.firstBuilt = res.why();
                        LOG.info("[entropybot] chop: skipped: {}", res.why());
                    }
                }
                continue;
            }
            TreeFinder.Tree t = res.tree();
            int[] b = t.base();
            if (io.github.mojolowjo.entropybot.chop.Unreachable.blocked(b[0], b[2], System.currentTimeMillis())) {
                if (r.firstWhy == null) r.firstWhy = "the tree at " + t.at() + " is one I couldn't get at (remembered 30 min)";
                continue;
            }
            if (r.tried.contains(TreeFinder.key(b[0], b[1], b[2]))) {
                // 0.31.0 (trial 11: "1 log columns looked at; null"): the only tree was tried already this run
                if (r.firstWhy == null) r.firstWhy = "the tree at " + t.at() + " was tried already";
                continue;
            }
            checked++;
            if (!ChopRules.matches(r.args.type(), t.logId())) {
                if (r.firstWhy == null) r.firstWhy = "the tree at " + t.at() + " is " + ChopRules.shortId(t.logId());
                continue;
            }
            String fence = jobs.goalAllowed(b[0], b[1], b[2]);
            if (fence != null) {
                if (r.firstWhy == null) r.firstWhy = "the tree at " + t.at() + ": " + fence;
                continue;
            }
            boolean off = false;
            for (int[] l : t.logs()) {
                if (!commands.inAreas(dim, l[0], l[2]) || MineRules.protectNear(prot, dim, l[0], l[1], l[2], 0) != null) {
                    off = true;
                    break;
                }
            }
            if (off) {
                if (r.firstWhy == null) r.firstWhy = "the tree at " + t.at() + " reaches out of my areas or into a protect box";
                r.tried.add(TreeFinder.key(b[0], b[1], b[2]));
                continue;
            }
            // 0.27.1: a one-log column without leaves above it is a stump or a lone log: tried only when nothing else is left
            // 0.27.2: any one-log column (whatever its leaves) counts as a stump: after every real tree, skipped near one
            boolean stump = ChopLoop.isStump(t.logs().size(), TreeFinder.stump(w, t));
            if (stump && r.tried.add("stump " + t.at())) LOG.info("[entropybot] chop: stump at {} (1 log, no leaves above) - only if no tree is left", t.at());
            // 0.26.4: trunks with a free standing cell next to their base first (after a reach failure: the next nearest)
            boolean reach = !stump && trunkReachable(p, b);
            long cost = (long) (b[0] - me[0]) * (b[0] - me[0]) + (long) (b[2] - me[2]) * (b[2] - me[2]) + 4L * (b[1] - me[1]) * (b[1] - me[1]);
            cands.add(new ChopLoop.Cand(b, cost, reach, stump));
            found.put(TreeFinder.key(b[0], b[1], b[2]), t);
            if (reach && r.reachFails == 0) break;      // the usual case: the nearest tree is fine
        }
        if (cands.isEmpty()) return null;
        List<ChopLoop.Cand> ord = ChopLoop.order(cands, me);
        if (ord.isEmpty()) return null;
        if (ChopLoop.onlyStumps(cands)) LOG.info("[entropybot] chop: only stumps or lone logs left - trying the nearest one");
        r.nearestReachable = null;
        for (ChopLoop.Cand c : ord) if (c.reachable()) { r.nearestReachable = c.base()[0] + " " + c.base()[1] + " " + c.base()[2]; break; }
        int[] b = ord.get(0).base();
        return found.get(TreeFinder.key(b[0], b[1], b[2]));
    }

    /**
     * A free standing cell (feet at the trunk's base level, one up or down) near the trunk base: 0.27.1 the 8 cells around
     * it, and 2 out when a cell next to the base is leaves or grass (ChopLoop.standOffsets).
     */
    private static boolean trunkReachable(LocalPlayer p, int[] b) {
        try {
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
            boolean wide = false;
            if (mc.level != null) for (int[] o : ChopLoop.standOffsets(false)) {
                BlockState st = mc.level.getBlockState(new BlockPos(b[0] + o[0], b[1], b[2] + o[1]));
                if (st.is(BlockTags.LEAVES) || st.getBlock() instanceof LeavesBlock || st.is(BlockTags.REPLACEABLE)) { wide = true; break; }
            }
            for (int[] o : ChopLoop.standOffsets(wide))
                for (int dy = -1; dy <= 1; dy++)
                    if (Jobs.standable(mc, new BlockPos(b[0] + o[0], b[1] + dy, b[2] + o[1]))) return true;
        } catch (RuntimeException e) {
            LOG.debug("[entropybot] chop: reach check failed: {}", e.toString());
            return true;
        }
        return false;
    }

    static final int PICK_RADIUS = 16, PICK_NEAR_LOG = 4;
    static final long PICK_NO_GAIN = 100, PICK_MAX = 600;     // 5 s without a gain ends it; 30 s at most

    /**
     * 0.35.5 (to-do 12c, like Baritone's mine pickup): walks next to the drops within 16 of the trunk or near a broken
     * log (anticipated drops), its own drops (the log, saplings, sticks, apples) first; goes on while the bag grows,
     * ends 5 s after the last gain (30 s at most) and logs what it got and left.
     */
    private String pickup(Seq s, Seq.Step st, Run r, LocalPlayer p, long elapsed) {
        IBaritone b = Jobs.baritone();
        if (r.tree == null || b == null) return "next";
        int[] base = r.tree.base();
        int bag = bagCount(p);
        if (elapsed == 0) {
            pickupProbe(p, base, "start");
            r.pickBag = bag;
            r.pickGot = 0;
            r.pickGainAt = 0;
        } else if (bag > r.pickBag) {
            r.pickGot += bag - r.pickBag;
            r.pickGainAt = elapsed;
        }
        r.pickBag = bag;
        ItemEntity best = null;
        double bd = Double.MAX_VALUE;
        int left = 0;
        for (Entity e : p.clientLevel.entitiesForRendering()) {
            if (!(e instanceof ItemEntity ie) || !e.isAlive()) continue;
            if (!nearTree(r.tree, e.getX(), e.getY(), e.getZ())) continue;
            int x = (int) Math.floor(e.getX()), y = (int) Math.floor(e.getY() + 0.01), z = (int) Math.floor(e.getZ());
            if (jobs.goalAllowed(x, y, z) != null) continue;
            if (p.getInventory().getFreeSlot() < 0 && p.getInventory().getSlotWithRemainingSpace(ie.getItem()) < 0) continue;
            left++;
            double d = e.distanceTo(p) - (ownDrop(r.tree, BuiltInRegistries.ITEM.getKey(ie.getItem().getItem()).toString()) ? 8 : 0);
            if (d < bd) {
                bd = d;
                best = ie;
            }
        }
        String end = null;
        if (best == null && elapsed >= 30) end = "no drops left";          // the drops of a fall land a moment later
        else if (best != null && elapsed - r.pickGainAt > PICK_NO_GAIN) end = "no gain for " + PICK_NO_GAIN / 20 + " s";
        else if (elapsed > PICK_MAX) end = "the " + PICK_MAX / 20 + " s cap";
        if (end != null) {
            Jobs.cancel(b);
            LOG.info("[entropybot] chop: pickup done at {}: {} item(s) picked up, {} drop(s) left near the tree, after {} s ({})",
                    r.tree.at(), r.pickGot, best == null ? 0 : left, elapsed / 20, end);
            if (best != null) pickupProbe(p, base, "left");
            return "next";
        }
        if (best == null) return "wait";
        boolean same = st.pos != null && st.pos[0] == (int) Math.floor(best.getX()) && st.pos[2] == (int) Math.floor(best.getZ());
        if (!same || Jobs.idle(b)) {
            if (!same) r.pickGainAt = Math.max(r.pickGainAt, elapsed - PICK_NO_GAIN / 2);     // a new drop gets its walk (review: a long first walk)
            st.pos = new int[]{(int) Math.floor(best.getX()), (int) Math.floor(best.getY() + 0.01), (int) Math.floor(best.getZ())};
            Jobs.safeSettings();
            // next to the drop, not onto it (a drop in leaves or a hole no longer stalls the path); arrived and still there:
            // onto it (GoalNear's 1 is 3D, the pickup reach ~1.3 from the centre, so a corner drop can stay out of reach)
            BlockPos at = new BlockPos(st.pos[0], st.pos[1], st.pos[2]);
            b.getCustomGoalProcess().setGoalAndPath(same ? new GoalBlock(at) : new GoalNear(at, 1));
            s.setStatus(s.label + " - picking up the drops");
        }
        return "wait";
    }

    /** Within 16 of the trunk's base, or within 4 of one of its logs (where a broken log drops). */
    static boolean nearTree(TreeFinder.Tree t, double x, double y, double z) {
        int[] base = t.base();
        if (Math.abs(x - base[0]) <= PICK_RADIUS && Math.abs(z - base[2]) <= PICK_RADIUS && Math.abs(y - base[1]) <= PICK_RADIUS) return true;
        for (int[] l : t.logs()) {
            if (Math.abs(x - l[0]) <= PICK_NEAR_LOG && Math.abs(z - l[2]) <= PICK_NEAR_LOG && Math.abs(y - l[1]) <= PICK_NEAR_LOG) return true;
        }
        return false;
    }

    /** The tree's own drops: its log, saplings, sticks, apples. */
    static boolean ownDrop(TreeFinder.Tree t, String itemId) {
        return itemId.equals(t.logId()) || itemId.endsWith("sapling") || itemId.equals("minecraft:stick") || itemId.equals("minecraft:apple");
    }

    private static int bagCount(LocalPlayer p) {
        int n = 0;
        for (var st : p.getInventory().items) n += st.getCount();
        return n;
    }

    /** 0.34.1 probe (live: 1 of 3 broken logs came home): every dropped item within 16 of the trunk, offset from the base. */
    private static void pickupProbe(LocalPlayer p, int[] base, String when) {
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (Entity e : p.clientLevel.entitiesForRendering()) {
            if (!(e instanceof ItemEntity ie) || !e.isAlive()) continue;
            double dx = e.getX() - base[0], dy = e.getY() - base[1], dz = e.getZ() - base[2];
            if (Math.abs(dx) > 16 || Math.abs(dz) > 16 || Math.abs(dy) > 16) continue;
            if (n++ < 8) sb.append(String.format(java.util.Locale.ROOT, " %s %.0f/%.0f/%.0f", ie.getItem().getItem(), dx, dy, dz));
        }
        LOG.info("[entropybot] chop: pickup {}: {} drops within 16 of the base {} {} {} (dx/dy/dz):{}", when, n, base[0], base[1], base[2], sb);
    }

    /** The saplings where the trunk stood (from the bag, after the pickup). */
    private String plant(Seq s, Run r, LocalPlayer p) {
        if (r.tree == null) return "next";
        Live w = new Live(p.clientLevel);
        r.sapling = ChopRules.sapling(r.tree.logId());
        List<int[]> free = new ArrayList<>();
        for (int[] c : r.tree.bases()) {
            BlockState st = p.clientLevel.getBlockState(new BlockPos(c[0], c[1], c[2]));
            if (st.canBeReplaced() && w.soil(c[0], c[1] - 1, c[2])) free.add(c);
        }
        int have = r.sapling == null ? 0 : Gui.inventory(p).getOrDefault(r.sapling, 0);
        ChopRules.Replant rp = ChopRules.replant(free.isEmpty() ? List.of() : free, r.sapling, free.isEmpty() ? 0 : have);
        if (free.isEmpty()) {
            r.notes.add("tree at " + r.tree.at() + ": the trunk's spot isn't free - not replanted");
            return "next";
        }
        if (rp.note() != null) r.notes.add("tree at " + r.tree.at() + ": " + rp.note());
        r.planted = rp.cells();
        List<Seq.Step> add = new ArrayList<>();
        for (int[] c : rp.cells()) add.add(Clearing.placeStep(c.clone(), r.sapling, null, true));
        s.splice(s.idx + 1, add);
        return "next";
    }

    // ---- 0.23.1: one tree while exploring outside the areas (VOCABULARY 6b) ----

    /**
     * The steps that fell the nearest tree within 12 blocks whose every log the predicate allows (the roaming permission:
     * outside the areas, near the bot, off any base), for the explore job's own Seq; null when there is none. No replant
     * (roaming places nothing but torches). The pickup follows; the explore job's tally step counts what came in.
     */
    List<Seq.Step> gatherTree(Seq s, LocalPlayer p, java.util.function.Predicate<int[]> allowed) {
        Live w = new Live(p.clientLevel);
        int[] me = Jobs.here(p);
        SurfaceColumns.Source src = SurfaceExport.liveSource(p.clientLevel);
        List<int[]> cols = new ArrayList<>();
        int rad = 12;
        for (int x = me[0] - rad; x <= me[0] + rad; x++)
            for (int z = me[2] - rad; z <= me[2] + rad; z++) {
                int y = ChopRules.logTop(src, x, z, me[1] + 24, me[1] - 12);
                if (y != ChopRules.NONE) cols.add(new int[]{x, y, z});
            }
        Run r = runs.get(s);
        Set<String> tried = r != null ? r.tried : new HashSet<>();
        int checked = 0;
        for (int[] c : ChopRules.order(cols, me[0], me[1], me[2])) {
            if (checked++ >= 16) break;
            TreeFinder.Result res = TreeFinder.find(w, c[0], c[1], c[2]);
            if (!res.ok()) continue;
            TreeFinder.Tree t = res.tree();
            int[] b = t.base();
            if (!tried.add(TreeFinder.key(b[0], b[1], b[2]))) continue;
            if (io.github.mojolowjo.entropybot.chop.Unreachable.blocked(b[0], b[2], System.currentTimeMillis())) continue;      // 0.36.3
            boolean ok = true;
            for (int[] l : t.logs()) if (!allowed.test(l)) { ok = false; break; }
            if (!ok) continue;
            if (r == null) {
                r = new Run();
                r.args = ChopRules.parse("trees 1");
                r.deadline = now() + ChopRules.MAX_TICKS;
                r.tried.addAll(tried);
                runs.put(s, r);
            }
            r.tree = t;
            r.phase = "gather";
            r.rounds = 0;
            r.lastLeft = t.logs().size();
            r.brokeAny = 0;
            LOG.info("[entropybot] explore: cutting the tree at {} ({} logs of {}) outside my areas", t.at(), t.logs().size(), t.logId());
            List<Seq.Step> add = new ArrayList<>();
            add.add(Seq.Step.walk(b.clone(), true));
            add.addAll(breakSteps(r, TreeFinder.lowest(t.logs())));
            add.add(new Seq.Step("chopgather"));
            return add;
        }
        return null;
    }

    /** After a break round of a gathered tree: more rounds while its logs stand and come down, then the pickup. */
    private String gatherAfter(Seq s, Run r, LocalPlayer p) {
        if (r.tree == null) return "next";
        List<int[]> left = TreeFinder.left(new Live(p.clientLevel), r.tree);
        r.rounds++;
        boolean progress = left.size() < r.lastLeft;
        r.lastLeft = left.size();
        if (!left.isEmpty() && r.rounds < ChopRules.ROUNDS && (progress || r.rounds < 3)) {
            List<Seq.Step> add = breakSteps(r, left);
            add.add(new Seq.Step("chopgather"));
            s.splice(s.idx + 1, add);
            return "next";
        }
        s.splice(s.idx + 1, new ArrayList<>(List.of(new Seq.Step("choppickup"))));
        return "next";
    }

    // ---- the axe ----

    private static final List<String> AXES = List.of("minecraft:wooden_axe", "minecraft:stone_axe", "minecraft:iron_axe", "minecraft:golden_axe",
            "minecraft:diamond_axe", "minecraft:netherite_axe");

    static boolean isAxe(String id) { return id != null && id.endsWith("_axe") && !id.endsWith("_pickaxe"); }

    private static int axeSlot(LocalPlayer p) {
        for (int i = 0; i < 36; i++) if (isAxe(Gui.itemId(p.getInventory().getItem(i)))) return i;
        return -1;
    }

    /** No axe: one from storage, else a stone or a wooden one made; else on by hand with an actionable note. */
    private String axe(Seq s, Run r, LocalPlayer p) {
        if (axeSlot(p) >= 0) return "next";
        List<Crafting.Source> src = crafting.storageSources(p);
        for (Crafting.Source x : src) {
            for (String id : AXES) {
                if (x.items().getOrDefault(id, 0) <= 0) continue;
                Map<String, Integer> need = new LinkedHashMap<>();
                need.put(id, 1);
                List<Seq.Step> add = new ArrayList<>(Crafting.takeTrips(src, need).steps());
                Seq.Step check = new Seq.Step("chopaxecheck");
                check.text = "fetched";
                add.add(check);
                s.splice(s.idx + 1, add);
                s.setStatus(s.label + " - fetching an axe");
                return "next";
            }
        }
        String lastErr = null;
        int[] table = crafting.findTable(p);
        if (table == null || Jobs.distSq(table, Jobs.here(p)) > 48 * 48) {
            // an axe is a 3x3 recipe: without a table near, a craft would only turn the logs into planks and fail
            r.axeNote = "no axe, and no crafting table near me to make one - chopped by hand; next: give me an axe, or put a crafting table near me";
            LOG.info("[entropybot] chop: {}", r.axeNote);
            return "next";
        }
        for (String text : List.of("minecraft:stone_axe 1", "minecraft:wooden_axe 1")) {
            Crafting.Prepared pr = crafting.prepareCraft(p, text, null);
            if (pr.err() != null) {
                lastErr = pr.err().replaceFirst("^error: ", "");
                continue;
            }
            List<Seq.Step> add = new ArrayList<>(pr.steps());
            for (Seq.Step a : add) a.direct = false;
            Seq.Step check = new Seq.Step("chopaxecheck");
            check.text = "made";
            add.add(check);
            s.splice(s.idx + 1, add);
            s.setStatus(s.label + " - making " + (text.contains("stone") ? "a stone" : "a wooden") + " axe");
            return "next";
        }
        r.axeNote = "no axe and nothing to make one from" + (lastErr != null ? " (" + lastErr + ")" : "")
                + " - chopped by hand; next: give me an axe or 3 logs and a crafting table nearby";
        LOG.info("[entropybot] chop: {}", r.axeNote);
        return "next";
    }

    /**
     * P2: a step of the axe phase (the fetch or craft spliced between "chopaxe" and its "chopaxecheck") failed: clean up
     * (close a menu, put the craft's grid back) and go on by hand from the check step, with the reason in the end note.
     */
    static boolean caught(Seq s, String why, LocalPlayer p) {
        Chopping c = instance;
        if (c == null || !c.runs.containsKey(s)) return false;
        if (s.idx < s.steps.size() && "chopfar".equals(s.steps.get(s.idx).key)) {
            // 0.34.0 live: the walk to a remembered tree ended short (7 blocks off, on the slope): look from here
            LOG.info("[entropybot] chop: the walk to the remembered tree ended short ({}) - looking from {}", why, p == null ? "?" : Jobs.fmt(Jobs.here(p)));
            s.idx++;
            s.stepStart = c.now();
            s.stage = null;
            return true;
        }
        int j = ChopRules.axeCheckAfter(s.steps.stream().map(x -> x.type).toList(), s.idx);
        if (j < 0) return false;
        Seq.Step check = s.steps.get(j);
        check.why = why;
        try {
            if (p != null && Gui.open(p)) Gui.close(p);
        } catch (RuntimeException e) {
            LOG.warn("[entropybot] chop: closing the menu after the axe failed: {}", e.toString());
        }
        LOG.info("[entropybot] chop: the axe step failed ({}) - going on by hand", why);
        s.idx = j;
        s.stepStart = c.now();
        s.stage = null;
        return true;
    }

    private String axeCheck(Seq s, Seq.Step st, Run r, LocalPlayer p) {
        if (st.why != null) {
            r.axeNote = ChopRules.axeFailedNote(st.text, st.why);
            if (axeSlot(p) < 0) return "next";
        }
        int i = axeSlot(p);
        if (i >= 0) {
            r.axeNote = st.text + " a " + ChopRules.shortId(Gui.itemId(p.getInventory().getItem(i)));
            return "next";
        }
        r.axeNote = "no axe (" + st.text + " none) - chopped by hand; next: give me an axe";
        return "next";
    }

    // ---- the end ----

    private String end(Seq s, Run r, LocalPlayer p, String why) {
        List<String> notes = new ArrayList<>();
        if (r.treeChop && r.chopsTotal > 0) notes.add(ChopLoop.felledNote(r.trees, r.chopsTotal));
        if (r.axeNote != null) notes.add(r.axeNote);
        if (r.skippedBuilt > 0) notes.add(r.skippedBuilt + " skipped: " + r.firstBuilt.replaceFirst("^the tree at \\S+ \\S+ \\S+ is ", "")
                + (r.skippedBuilt > 1 ? " and more" : ""));
        notes.addAll(r.notes.size() > 4 ? r.notes.subList(0, 4) : r.notes);
        String next = r.trees == 0 ? (r.skippedBuilt > 0 ? "make that build a safe area (area here 16 build safe), or an area with trees away from builds"
                : r.nearestOutside != null ? null : "\"area here 48 woods neutral\" where trees grow") : null;
        String text = ChopRules.endText(r.trees, r.got, r.replanted, why, notes, next);
        treesTotal += r.trees;
        logsTotal += ChopRules.total(r.got);
        replantedTotal += r.replanted;
        lastEnd = text;
        LOG.info("[entropybot] chop: {}", text);
        jobs.finish(text);
        return "wait";
    }

    /** 0.33.0: the exact area command that takes in the nearest log outside ("x y z"), from where I stand. */
    private String coverOutside(String at, LocalPlayer p) {
        String[] a = at.split(" ");
        int[] me = Jobs.here(p);
        return ChopRules.areaCover(me[0], me[2], Integer.parseInt(a[0]), Integer.parseInt(a[2]), 6, "trees", commands.areaNames())
                + " takes it in, then ask again";
    }

    /** The base chests trip (like the clear's), or null when there is nothing to put away / no chests known. */
    private List<Seq.Step> depositSteps(LocalPlayer p) {
        Map<String, Integer> items = StorageRules.depositables(Storage.held(p), "", false, null, storage.keeps());
        if (items.isEmpty()) return null;
        boolean atBase = StorageRules.depositAtBase(core.knowledge.places().get("base"), Jobs.here(p), Storage.dim());
        StorageRules.Plan plan = StorageRules.depositPlan(items, storage.baseChests(p, atBase), Jobs.here(p), "");
        if (plan.err() != null) {
            LOG.info("[entropybot] chop: can't put things away ({})", plan.err());
            return null;
        }
        List<Seq.Step> steps = new ArrayList<>();
        for (StorageRules.Stop stop : plan.stops()) {
            steps.add(Seq.Step.walk(stop.chest().pos(), false));
            steps.add(Seq.Step.open(stop.chest().pos(), "no"));
            Seq.Step put = new Seq.Step("put");
            put.items = stop.items();
            put.fallback = stop.fallback();
            steps.add(put);
            steps.add(Seq.Step.close());
        }
        return steps;
    }

    // ---- the world ----

    static boolean isLogItem(String id) {
        if (id == null) return false;
        String path = id.substring(id.indexOf(':') + 1);
        return (path.endsWith("_log") || path.endsWith("_stem")) && !path.startsWith("stripped_");
    }

    /** The client level as the tree finder sees it. */
    static final class Live implements TreeFinder.World {
        private final ClientLevel level;
        private final BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();

        Live(ClientLevel level) { this.level = level; }

        private BlockState st(int x, int y, int z) { return level.getBlockState(m.set(x, y, z)); }

        @Override public String id(int x, int y, int z) {
            if (!level.isLoaded(m.set(x, y, z))) return null;
            return BuiltInRegistries.BLOCK.getKey(st(x, y, z).getBlock()).toString();
        }

        @Override public boolean log(int x, int y, int z) {
            BlockState s = st(x, y, z);
            if (s.isAir()) return false;
            String id = BuiltInRegistries.BLOCK.getKey(s.getBlock()).toString();
            if (ClearRules.choppedLog(id)) return true;                     // TreeChop's partly chopped log
            return s.is(BlockTags.LOGS) && isLogItem(id);                    // never stripped logs or 6-sided wood (built)
        }

        /** 0.26.4: the block's id plus its block entity data (TreeChop's chop count lives there), for "did that break chop?". */
        String sig(int x, int y, int z) {
            try {
                if (!level.isLoaded(m.set(x, y, z))) return null;
                String id = BuiltInRegistries.BLOCK.getKey(st(x, y, z).getBlock()).toString();
                net.minecraft.world.level.block.entity.BlockEntity be = level.getBlockEntity(m.set(x, y, z));
                return be == null ? id : id + be.saveWithoutMetadata(level.registryAccess());
            } catch (RuntimeException e) {
                LOG.debug("[entropybot] chop: block data at {} {} {}: {}", x, y, z, e.toString());
                return null;
            }
        }

        @Override public boolean leaves(int x, int y, int z) {
            BlockState s = st(x, y, z);
            return s.getBlock() instanceof LeavesBlock || s.is(BlockTags.LEAVES);
        }

        @Override public boolean persistentLeaves(int x, int y, int z) {
            BlockState s = st(x, y, z);
            return s.hasProperty(LeavesBlock.PERSISTENT) && s.getValue(LeavesBlock.PERSISTENT);
        }

        @Override public boolean soil(int x, int y, int z) { return st(x, y, z).is(BlockTags.DIRT); }

        @Override public boolean built(int x, int y, int z) {
            BlockState s = st(x, y, z);
            if (s.isAir()) return false;
            String id = BuiltInRegistries.BLOCK.getKey(s.getBlock()).toString();
            if (ClearRules.choppedLog(id)) return false;                       // TreeChop's partly chopped log
            if (s.hasBlockEntity()) return true;
            if (id.endsWith("_sapling") || id.endsWith("torch") || id.endsWith("_propagule")) return false;   // its own replants and torches
            return ClearRules.builtId(id);
        }

        private java.util.Set<String> shelters;

        /** 0.27.1: the restore ledger's "built: ..." cells and its shelters' blocks are the bot's own (never someone's build). */
        @Override public boolean ownBuilt(int x, int y, int z) {
            try {
                if (RestoreLive.INSTANCE.builtByMe(x, y, z)) return true;
                if (shelters == null) {
                    Commands c = RestoreLive.INSTANCE.commands();
                    shelters = c == null ? java.util.Set.of() : ShelterJob.ownBlocks(c);
                }
                return shelters.contains(x + " " + y + " " + z);
            } catch (RuntimeException e) {
                LOG.warn("[entropybot] chop: own-block check at {} {} {} failed: {} - counted as a build", x, y, z, e.toString());
                return false;
            }
        }
    }

}
