package io.github.mojolowjo.entropybot.commands;

import baritone.api.IBaritone;
import baritone.api.pathing.goals.GoalNear;
import baritone.api.utils.BlockOptionalMetaLookup;
import com.mojang.logging.LogUtils;
import io.github.mojolowjo.entropybot.Core;
import io.github.mojolowjo.entropybot.aa.brain.Tuning;
import io.github.mojolowjo.entropybot.aa.moves.Car;
import io.github.mojolowjo.entropybot.aa.moves.Step;
import io.github.mojolowjo.entropybot.aa.moves.Steps;
import io.github.mojolowjo.entropybot.gui.Gui;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Block;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * rm-execution: {@link Steps.Hands} in the game. Baritone underneath: a walk is the travel job's one Baritone goal
 * (GoalNear when the step asks for it); a tree (blocks the game tags as logs) is the fork's ChopProcess (the whole tree,
 * its own end reason), any other block to collect Baritone's MineProcess, on the blocks the game data names (logs: the
 * block tag; a collected item: the blocks whose loot gives it), with the count and placing on, counted in the bag; a hunt
 * walks with GoalNear to the animal and hands it to the strike input (the reflexes' fight loop); a build is the builder
 * leg ({@code JobHands.build}); the item actions (craft, smelt, cook, scan) and the mine verb's per-ore and discovery
 * steps start their own job with one command and are followed through its request. Nothing here picks a stand spot, a
 * face or a click target. Loader notes: Baritone API (MineProcess, GoalNear, GoalXZ, BlockOptionalMetaLookup), vanilla
 * registries, block tags; the loot book read on the integrated server. Client thread only. Never throws (a failure is a
 * FAILED status with its reason).
 */
public final class StepHands implements Steps.Hands {
    private static final Logger LOG = LogUtils.getLogger();

    private final Commands c;
    /** The running step's own state. */
    private Step step;
    private JobRequests.Request req;
    private String instant;
    private Jobs.Job walkJob;
    private int startCount, want;
    private List<String> countIds = List.of();
    private boolean mining, chopping;
    private String miningWhat;
    // the hunt
    private int huntTarget = -1, huntTicks;
    private int[] huntDrop;
    private boolean huntArrived, huntVisited;
    private long huntWalkAt = -1;
    private String huntWhy;

    StepHands(Commands c) {
        this.c = c;
    }

    @Override public String start(Step s) {
        reset();
        step = s;
        LocalPlayer p = Minecraft.getInstance().player;
        if (p == null) return "error: not in a world";
        try {
            return switch (s) {
                case Step.Walk w -> walk(w);
                case Step.ChopTree t -> chop(p, t);
                case Step.Collect k -> collect(p, k);
                case Step.HuntAt h -> hunt(p, h);
                case Step.Build b -> build(b);
                case Step.PlaceNear n -> placeNear(p, n);
                case Step.ToBand t -> toBand(p, t);
                case Step.Climb k -> climb(p);
                case Step.Fish fs -> fish(p, fs);
                case Step.KelpCut k -> kelpCut(p, k);
                case Step.MineAt m -> mineAt(p, m);
                default -> verb(StepCommands.command(s));   // the item actions and the mine verb's steps: one command, its own job
            };
        } catch (RuntimeException e) {
            LOG.warn("[entropybot] steps: {} did not start: {}", s.text(), e.toString());
            return "error: " + s.text() + ": " + e;
        }
    }

    @Override public Car.Status status(Step s) {
        if (s != step) return Car.Status.failed("not the running step");
        LocalPlayer p = Minecraft.getInstance().player;
        if (p == null) return Car.Status.failed("not in a world");
        try {
            return switch (s) {
                case Step.ChopTree t -> mineStatus(p);
                case Step.Collect k -> mineStatus(p);
                case Step.KelpCut k -> mineStatus(p);
                case Step.MineAt m -> veinStatus(p, m);
                case Step.Fish fs -> fishStatus(p);
                case Step.HuntAt h -> huntStatus(p, h);
                case Step.Build b -> buildStatus(b);
                case Step.PlaceNear n -> placeNearStatus(n);
                case Step.ToBand t -> toBandStatus(p, t);
                case Step.Climb k -> climbStatus();
                default -> requestStatus();
            };
        } catch (RuntimeException e) {
            LOG.warn("[entropybot] steps: {} status: {}", s.text(), e.toString());
            return Car.Status.failed("status: " + e);
        }
    }

    @Override public void cancel(String why) {
        try {
            if (mining) {
                IBaritone b = Jobs.baritone();
                if (b != null && chopping) b.getChopProcess().cancel();
                else if (b != null) b.getMineProcess().cancel();
            }
            if (fishing != null) fishing.end();
            if (vein != null) vein.stop();
            if (step instanceof Step.HuntAt) {
                Core.INSTANCE.reflexes.stopAttack();
                IBaritone b = Jobs.baritone();
                if (b != null) Jobs.cancel(b);
            }
            if (step instanceof Step.Build || step instanceof Step.PlaceNear) io.github.mojolowjo.entropybot.aa.moves.JobHands.get().stop(why);
            if (step instanceof Step.ToBand) {
                IBaritone b = Jobs.baritone();
                if (b != null) Jobs.cancel(b);
            }
            if (step instanceof Step.Climb && climbing && c.chains.running() && "climb".equals(c.chains.name())) c.chains.clear();
            if ((req != null && !req.finished) || walkJob != null) {
                if (c.jobs.running()) c.jobs.finish("stopped: " + why);
            }
        } catch (RuntimeException e) {
            LOG.warn("[entropybot] steps: cancel of {} ({}) failed: {}", step == null ? "-" : step.text(), why, e.toString());
        }
        reset();
    }

    /** A collect / chop ended: the policy intent back to travel (the fork process put its own settings back). */
    private void endMining() {
        mining = false;
        io.github.mojolowjo.entropybot.guard.PolicyRuntime.INSTANCE.setIntent(io.github.mojolowjo.entropybot.guard.BreakPolicy.Intent.TRAVEL, "steps end");
    }

    private void reset() {
        step = null;
        fishing = null;
        vein = null;
        req = null;
        instant = null;
        walkJob = null;
        if (mining) endMining();
        chopping = false;
        miningWhat = null;
        countIds = List.of();
        huntTarget = -1;
        huntTicks = 0;
        huntDrop = null;
        huntArrived = false;
        huntVisited = false;
        huntWalkAt = -1;
        huntWhy = null;
        climbing = false;
        climbEnd = null;
    }

    /** one-planner: the chain for an Obtain step, on the facts as it starts (AaRuntime.obtainSteps: NeedChain). */
    @Override public Steps.Routine routine(Step.Obtain o) {
        if (c.aaRuntime == null) return new Steps.Routine(null, "no chain (aa not loaded)");
        return c.aaRuntime.obtainSteps(o);
    }

    // ---- fish: cast and reel as item inputs on Baritone's look, in the one Steps machine ----

    private FishStep fishing;

    private String fish(LocalPlayer p, Step.Fish f) {
        String no = FishStep.canStart(p);
        if (no != null) return "error: " + no;
        fishing = new FishStep(c, f, p);
        return null;
    }

    private Car.Status fishStatus(LocalPlayer p) {
        if (fishing == null) return Car.Status.failed("the fishing step has no state");
        Car.Status st = fishing.tick(p);
        if (st.state() != Car.State.RUNNING) fishing = null;
        return st;
    }

    // ---- mine at: one ore cell and its vein over Baritone's MineProcess ----

    private VeinMine vein;

    private String mineAt(LocalPlayer p, Step.MineAt m) {
        var level = p.level();
        BlockPos q = new BlockPos(m.x(), m.y(), m.z());
        String fam = m.family();
        if (!level.isLoaded(q)) return "error: the ore cell " + m.x() + " " + m.y() + " " + m.z() + " is not loaded";
        if (!io.github.mojolowjo.entropybot.sense.KnownOre.family(BuiltInRegistries.BLOCK.getKey(level.getBlockState(q).getBlock()).toString(), fam))
            return "error: no " + fam + " ore there any more (" + m.x() + " " + m.y() + " " + m.z() + ")";
        if (!m.dig() && !Mining.exposed(level, q)) return "error: the " + fam + " ore is buried, and this is the walk form (the dig form digs to it)";
        List<io.github.mojolowjo.entropybot.clear.Pos> cells = new ArrayList<>();
        for (int[] cell : io.github.mojolowjo.entropybot.cave.MineRules.vein(new int[]{m.x(), m.y(), m.z()}, n -> {
            BlockPos b = new BlockPos(n[0], n[1], n[2]);
            return level.isLoaded(b) && io.github.mojolowjo.entropybot.sense.KnownOre.family(BuiltInRegistries.BLOCK.getKey(level.getBlockState(b).getBlock()).toString(), fam);
        }, io.github.mojolowjo.entropybot.cave.MineRules.atVein())) cells.add(new io.github.mojolowjo.entropybot.clear.Pos(cell[0], cell[1], cell[2]));
        vein = new VeinMine(cells, "mine " + fam + " at " + m.x() + " " + m.y() + " " + m.z());
        String bad = vein.begin(level);
        if (bad != null) { vein = null; return "error: " + bad; }
        return null;
    }

    private Car.Status veinStatus(LocalPlayer p, Step.MineAt m) {
        if (vein == null) return Car.Status.failed("the vein step has no state");
        Car.Status st = vein.poll(p.level());
        if (st.state() != Car.State.RUNNING) vein = null;
        return st;
    }

    // ---- kelp: Baritone's MineProcess on the game's kelp blocks, counted in the bag ----

    private String kelpCut(LocalPlayer p, Step.KelpCut k) {
        List<Block> blocks = new ArrayList<>();
        for (Block b : BuiltInRegistries.BLOCK) if (b instanceof net.minecraft.world.level.block.KelpBlock || b instanceof net.minecraft.world.level.block.KelpPlantBlock) blocks.add(b);
        if (blocks.isEmpty()) return "error: the game has no kelp block";
        List<String> ids = new ArrayList<>();
        for (Block b : blocks) {
            String id = BuiltInRegistries.ITEM.getKey(b.asItem()).toString();
            if (!id.endsWith(":air") && !ids.contains(id)) ids.add(id);        // a block with no item (the kelp plant) names none
        }
        return mine(p, blocks, ids, k.n(), "cut " + k.n() + " kelp");
    }

    // ---- the climb: the climb verb plans its own staircase and runs it as a chain of its own ----

    private boolean climbing, climbHooked;
    private String climbEnd;

    private String climb(LocalPlayer p) {
        if (!climbHooked) {
            climbHooked = true;
            c.chains.onEnd((name, msg) -> {
                if (climbing && "climb".equals(name)) climbEnd = msg == null ? "ended without a word" : msg;
            });
        }
        String r = DigCommands.climb(c, p, "", c.owner());
        if (r != null && r.startsWith("ok")) {
            instant = r;
            return null;
        }
        if (r == null || !r.startsWith("started")) return "error: climb: " + (r == null ? "no answer" : r);
        climbing = true;
        return null;
    }

    private Car.Status climbStatus() {
        if (instant != null) return Car.Status.done(instant);
        if (climbEnd != null) {
            String m = climbEnd;
            climbing = false;
            return m.startsWith("done") ? Car.Status.done(m) : Car.Status.failed("climb: " + m);
        }
        if (c.chains.running() && "climb".equals(c.chains.name())) return Car.Status.running("climbing: " + c.chains.stepText());
        climbing = false;
        return Car.Status.failed("climb: its chain went without an end (stopped by someone)");
    }

    // ---- the item actions and the mine verb's steps: one command, its job's request ----

    private String verb(String text) {
        Chains.Reply r;
        var d = io.github.mojolowjo.entropybot.aa.door.Door.current() == null ? io.github.mojolowjo.entropybot.aa.door.Driver.AA : io.github.mojolowjo.entropybot.aa.door.Door.current();
        try (var sc = io.github.mojolowjo.entropybot.aa.door.Door.scope(d)) {
            r = c.handle(c.owner(), text, true, null);
        }
        if (r == null) return "error: " + text + ": no answer";
        if (r.pending() != null) {
            req = r.pending();
            if (Texts.stepFailed(req.reply)) return "error: " + req.reply;
            return null;
        }
        String t = r.text() == null ? "" : r.text();
        if (Texts.stepFailed(t)) return t.startsWith("error") ? t : "error: " + t;
        instant = t;
        return null;
    }

    private Car.Status requestStatus() {
        if (instant != null) return Car.Status.done(instant);
        if (req == null) return Car.Status.failed("no job");
        if (!req.finished) return Car.Status.running(c.jobs.running() ? c.jobs.job.status : step.text());
        String m = req.doneMsg == null ? "" : req.doneMsg;
        if (req.failed || Texts.stepFailed(m) || !(m.startsWith("ok") || m.startsWith("done"))) return Car.Status.failed(m.isEmpty() ? "ended without a word" : m);
        return Car.Status.done(m);
    }

    // ---- walk: the travel job's one Baritone goal ----

    private String walk(Step.Walk w) {
        String r = verb("goto " + w.x() + " " + w.y() + " " + w.z());
        if (r != null) return r;
        if (c.jobs.running() && "travel".equals(c.jobs.job.type)) {
            walkJob = c.jobs.job;
            if (w.near() > 0) {
                GoalNear g = new GoalNear(new BlockPos(w.x(), w.y(), w.z()), w.near());
                IBaritone b = Jobs.baritone();
                if (walkJob.finalGoal != null) walkJob.finalGoal = g;           // the far walk's last leg
                else if (b != null) Jobs.walkGoal(b, walkJob, g);
            }
        }
        return null;
    }

    // ---- Baritone's MineProcess: a tree, a block to collect ----

    private String chop(LocalPlayer p, Step.ChopTree t) {
        List<Block> blocks = new ArrayList<>();
        if (t.log() != null) {
            Block b = BuiltInRegistries.BLOCK.getOptional(ResourceLocation.tryParse(t.log())).orElse(null);
            if (b == null) return "error: no block " + t.log();
            blocks.add(b);
        } else {
            // the owner's logs kind (the game's log tag, less what "kinds logs exclude" left out)
            for (String id : c.vocab.expand("logs")) {
                Block b = BuiltInRegistries.ITEM.getOptional(ResourceLocation.tryParse(id)).map(Block::byItem).orElse(net.minecraft.world.level.block.Blocks.AIR);
                if (b != net.minecraft.world.level.block.Blocks.AIR && b.defaultBlockState().is(BlockTags.LOGS) && !blocks.contains(b)) blocks.add(b);
            }
        }
        if (blocks.isEmpty()) return "error: no log block left in the logs kind (kinds logs)";
        List<String> ids = new ArrayList<>();
        for (Block b : blocks) ids.add(BuiltInRegistries.ITEM.getKey(b.asItem()).toString());
        return mine(p, blocks, ids, t.logs(), "cut " + t.logs() + " " + (t.log() == null ? "logs" : t.log().replaceFirst("^minecraft:", "")));
    }

    private String collect(LocalPlayer p, Step.Collect k) {
        if (!io.github.mojolowjo.entropybot.aa.NeedChain.plain(k.item())) return collectAny(p, k);
        String id = k.item().contains(":") ? k.item() : "minecraft:" + k.item();
        // the blocks that give it: the game's loot data (LootBook), and the block of the item itself
        List<Block> blocks = new ArrayList<>();
        var book = c.aaRuntime == null ? null : c.aaRuntime.currentLootBook();
        if (book != null) for (String bid : book.blocksDropping(id))
            BuiltInRegistries.BLOCK.getOptional(ResourceLocation.tryParse(bid)).ifPresent(blocks::add);
        BuiltInRegistries.ITEM.getOptional(ResourceLocation.tryParse(id)).ifPresent(it -> {
            Block own = Block.byItem(it);
            if (own != net.minecraft.world.level.block.Blocks.AIR && !blocks.contains(own)) blocks.add(own);
        });
        if (blocks.isEmpty()) return "error: no block gives " + k.item() + (book == null || book.isEmpty() ? " (no loot data in this world)" : " (the game's loot data)");
        return mine(p, blocks, List.of(id), k.n(), "collect " + k.n() + " " + k.item());
    }

    /**
     * TAG_NEEDS: a collect of a tag need whose member no lookup named ("collect #coals 4"): Baritone's MineProcess on every block
     * whose loot gives a member or whose own item is one (AaRuntime.sourceBlocksOf), counted as any member in the bag (the
     * game's tag index).
     */
    private String collectAny(LocalPlayer p, Step.Collect k) {
        String word = io.github.mojolowjo.entropybot.aa.NeedChain.word(k.item());
        if (c.aaRuntime == null) return "error: collect " + k.item() + ": aa is not loaded (no source lookup)";
        List<Block> blocks = new ArrayList<>();
        for (String bid : c.aaRuntime.sourceBlocksOf(word)) BuiltInRegistries.BLOCK.getOptional(ResourceLocation.tryParse(bid)).ifPresent(blocks::add);
        if (blocks.isEmpty()) return "error: no block gives any of " + k.item() + " (the game's loot data and tags)";
        List<String> ids = new ArrayList<>();
        for (var it : BuiltInRegistries.ITEM) {
            String iid = BuiltInRegistries.ITEM.getKey(it).toString();
            if (io.github.mojolowjo.entropybot.aa.NeedChain.gives(word, iid)) ids.add(iid);
        }
        return mine(p, blocks, ids, k.n(), "collect " + k.n() + " " + k.item());
    }

    /** chop B1: blocks the game tags as logs go to the fork's chop process (the whole tree); others to the mine process. */
    static boolean allLogs(List<Block> blocks) {
        for (Block b : blocks) if (!b.defaultBlockState().is(BlockTags.LOGS)) return false;
        return !blocks.isEmpty();
    }

    private String mine(LocalPlayer p, List<Block> blocks, List<String> ids, int n, String what) {
        IBaritone b = Jobs.baritone();
        if (b == null) return "error: baritone not loaded";
        countIds = ids;
        startCount = count(p);
        want = Math.max(1, n);
        chopping = allLogs(blocks);
        BlockOptionalMetaLookup filter = new BlockOptionalMetaLookup(blocks.toArray(new Block[0]));
        // the count goes to Baritone (not 0): it counts the bag's items its filter matches (the blocks' drops), so its
        // target is what is there now + want
        int target = itemsOf(p, filter) + want;
        // 0.39.z8 (owner 2026-10-22, "remove the old job habit"): no Baritone setting here; the fork's chop / mine run
        // owns breaking and placing for its run (and the chop the bag's placeable blocks as throwaway)
        io.github.mojolowjo.entropybot.guard.PolicyRuntime.INSTANCE.setIntent(io.github.mojolowjo.entropybot.guard.BreakPolicy.Intent.MINE, "steps");
        mining = true;
        miningWhat = what;
        try {
            if (chopping) {
                b.getChopProcess().chop(target, filter);
                if (!b.getChopProcess().isActive()) {          // it ended at its start (none in range): its own reason
                    endMining();
                    return "error: " + what + ": " + endOf(b.getChopProcess().lastEnd());
                }
            } else {
                b.getMineProcess().mineWith(target, filter, true);
            }
        } catch (LinkageError e) {
            endMining();
            return "error: this Baritone has no mineWith (the stock jar? install the entropy fork): " + e;
        }
        LOG.info("[entropybot] steps: {} - Baritone's {} process on {} block kind(s), until {} in the bag", what, chopping ? "chop" : "mine", blocks.size(), target);
        return null;
    }

    /** The bag's items the filter matches now (what Baritone's quantity counts: its main inventory, as MineProcess reads it). */
    private static int itemsOf(LocalPlayer p, BlockOptionalMetaLookup filter) {
        int n = 0;
        for (net.minecraft.world.item.ItemStack st : p.getInventory().items) if (!st.isEmpty() && filter.has(st)) n += st.getCount();
        return n;
    }

    /** The chop's end reason as the step's failure text (an empty one is said so, never guessed). */
    static String endOf(String lastEnd) {
        return lastEnd == null || lastEnd.isEmpty() ? "the chop process ended without a reason" : lastEnd;
    }

    private int count(LocalPlayer p) {
        Map<String, Integer> bag = Gui.inventory(p);
        int n = 0;
        for (String id : countIds) n += bag.getOrDefault(id, 0);
        return n;
    }

    private Car.Status mineStatus(LocalPlayer p) {
        int got = count(p) - startCount;
        IBaritone b = Jobs.baritone();
        if (chopping) {
            // chop: the whole tree is the job; the process ends itself (the count is checked between trees) with its reason
            if (b == null) return Car.Status.failed("baritone not loaded");
            if (b.getChopProcess().isActive() || Core.INSTANCE.reflexes.hold()) return Car.Status.running(miningWhat + ": " + got + " of " + want);
            endMining();
            String end = endOf(b.getChopProcess().lastEnd());
            if (got >= want) return Car.Status.done("ok: " + miningWhat + ": got " + got + " (" + end + ")");
            return Car.Status.failed(miningWhat + ": " + end + " (got " + got + " of " + want + ")");
        }
        if (got >= want) {
            if (b != null) b.getMineProcess().cancel();
            endMining();
            return Car.Status.done("ok: " + miningWhat + ": got " + got);
        }
        if (b == null) return Car.Status.failed("baritone not loaded");
        if (!b.getMineProcess().isActive() && !Core.INSTANCE.reflexes.hold()) {
            endMining();
            return Car.Status.failed(miningWhat + ": Baritone's mine process ended with " + got + " of " + want + " (the mine process gives no reason)");
        }
        return Car.Status.running(miningWhat + ": " + got + " of " + want);
    }

    // ---- build: the builder leg ----

    private String build(Step.Build b) {
        var r = io.github.mojolowjo.entropybot.aa.moves.JobHands.get().build(b.cells(), b.items(), (int) (20 * Tuning.need("hands.buildS")), "steps", null, false);
        return r.failed() ? "error: " + r.why() : null;
    }

    private Car.Status buildStatus(Step.Build b) {
        var r = io.github.mojolowjo.entropybot.aa.moves.JobHands.get().build(b.cells(), b.items(), (int) (20 * Tuning.need("hands.buildS")), "steps", null, false);
        if (r.done()) return Car.Status.done("ok: " + b.text());
        if (r.failed()) return Car.Status.failed(r.why());
        return Car.Status.running(r.why());
    }

    // ---- place near me: the fork's placeNear picks the cell (free, reachable, the guard allowing), the stand and the face; the mod names none ----

    private String placeNear(LocalPlayer p, Step.PlaceNear n) {
        var r = io.github.mojolowjo.entropybot.aa.moves.JobHands.get().placeNear(n.item(), Tuning.i("steps.placeNearR"), (int) (20 * Tuning.need("hands.buildS")), "steps");
        return r.failed() ? "error: " + r.why() : null;
    }

    private Car.Status placeNearStatus(Step.PlaceNear n) {
        var r = io.github.mojolowjo.entropybot.aa.moves.JobHands.get().placeNear(n.item(), Tuning.i("steps.placeNearR"), (int) (20 * Tuning.need("hands.buildS")), "steps");
        if (r.done()) return Car.Status.done("ok: " + n.text());
        if (r.failed()) return Car.Status.failed(r.why());
        return Car.Status.running(r.why());
    }

    // ---- to a height band: one Baritone goal (GoalYLevel to the nearest Y of the band); done once the feet are in it ----

    private String toBand(LocalPlayer p, Step.ToBand t) {
        int y = Jobs.here(p)[1];
        if (y >= t.minY() && y <= t.maxY()) { instant = "ok: already at y " + y + " (band " + t.minY() + ".." + t.maxY() + ")"; return null; }
        IBaritone b = Jobs.baritone();
        if (b == null) return "error: baritone not loaded";
        int target = Math.max(t.minY(), Math.min(t.maxY(), y));
        b.getCustomGoalProcess().setGoalAndPath(new baritone.api.pathing.goals.GoalYLevel(target));
        LOG.info("[entropybot] steps: {} - Baritone GoalYLevel {}", t.text(), target);
        return null;
    }

    private Car.Status toBandStatus(LocalPlayer p, Step.ToBand t) {
        if (instant != null) return Car.Status.done(instant);
        int y = Jobs.here(p)[1];
        IBaritone b = Jobs.baritone();
        if (y >= t.minY() && y <= t.maxY()) {
            if (b != null) Jobs.cancel(b);
            return Car.Status.done("ok: at y " + y + " (band " + t.minY() + ".." + t.maxY() + ")");
        }
        if (b == null) return Car.Status.failed("baritone not loaded");
        if (!b.getCustomGoalProcess().isActive() && !Core.INSTANCE.reflexes.hold()) return Car.Status.failed(t.text() + ": Baritone's goal ended at y " + y + " (no path into the band)");
        return Car.Status.running(t.text() + ": at y " + y);
    }

    // ---- hunt: GoalNear on the animal, the strike stays the reflexes' input ----

    private String hunt(LocalPlayer p, Step.HuntAt h) {
        String item = h.forItem() != null ? (h.forItem().contains(":") ? h.forItem() : "minecraft:" + h.forItem())
                : io.github.mojolowjo.entropybot.aa.brain.ItemWorth.mobFood().get(h.type());
        if (item == null) return "error: the " + h.type() + "'s loot gives no food (name what to hunt it for)";
        if (!c.stepRun.on()) return "error: hunting is off (the owner's hunting off; hunting on lets me)";
        if (!c.vocab.expand("animals").contains(h.type())) return "error: " + h.type().replaceFirst("^minecraft:", "") + " is not in the animals kind (kinds animals add ...)";
        countIds = List.of(item);
        startCount = count(p);
        want = Math.max(1, h.forItem() != null ? h.forCount() : h.n());
        return null;
    }

    private Car.Status huntStatus(LocalPlayer p, Step.HuntAt h) {
        int got = count(p) - startCount;
        IBaritone b = Jobs.baritone();
        if (got >= want) {
            if (b != null) Jobs.cancel(b);
            return Car.Status.done("ok: hunted " + got + " " + countIds.get(0).replaceFirst("^minecraft:", ""));
        }
        if (++huntTicks % 10 != 0) return Car.Status.running(huntWhy == null ? step.text() : huntWhy);  // structural: periodic cadence in ticks (20 ticks = 1 s)
        var refl = Core.INSTANCE.reflexes;
        Minecraft mc = Minecraft.getInstance();
        if (refl.forcing()) {                                        // the strike runs on the animal: follow its spot
            Entity tg = huntTarget >= 0 ? mc.level.getEntity(huntTarget) : null;
            if (tg != null) huntDrop = new int[]{tg.getBlockX(), tg.getBlockY(), tg.getBlockZ()};
            return Car.Status.running("striking the " + h.type().replaceFirst("^minecraft:", ""));
        }
        if (refl.hold()) return Car.Status.running("a reflex holds me");
        if (huntTarget >= 0) {
            // the last one died or got away: walk over where it was for the drops (one goal)
            Entity old = mc.level.getEntity(huntTarget);
            huntTarget = -1;
            if ((old == null || !old.isAlive()) && huntDrop != null && b != null) {
                b.getCustomGoalProcess().setGoalAndPath(new GoalNear(new BlockPos(huntDrop[0], huntDrop[1], huntDrop[2]), 0));
                huntDrop = null;
                return Car.Status.running(huntWhy = "picking up the drops");
            }
        }
        if (b != null && b.getCustomGoalProcess().isActive() && huntWhy != null && huntWhy.startsWith("picking")) return Car.Status.running(huntWhy);
        double atR = Tuning.get("steps.huntAtR");
        Entity best = null;
        double bestD = Double.MAX_VALUE;
        for (Entity e : mc.level.entitiesForRendering()) {
            if (e == p || !e.isAlive()) continue;
            if (!BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString().equals(h.type())) continue;
            if (h.at() != null && Math.hypot(e.getX() - h.at()[0], e.getZ() - h.at()[1]) > atR) continue;
            if (io.github.mojolowjo.entropybot.engine.Hostility.protectedMob(e) || e.hasCustomName() || (e instanceof AgeableMob am && am.isBaby())) continue;
            double d = p.distanceTo(e);
            if (d < bestD) { bestD = d; best = e; }
        }
        String name = h.type().replaceFirst("^minecraft:", "");
        if (best == null) {
            if (h.at() != null && !huntArrived) {
                if (Math.hypot(p.getX() - h.at()[0], p.getZ() - h.at()[1]) <= atR) huntArrived = true;
                else {
                    if (b != null && (huntWalkAt < 0 || !b.getCustomGoalProcess().isActive())) {
                        huntWalkAt = huntTicks;
                        b.getCustomGoalProcess().setGoalAndPath(new baritone.api.pathing.goals.GoalXZ(h.at()[0], h.at()[1]));
                    }
                    return Car.Status.running(huntWhy = "walking to " + h.at()[0] + " " + h.at()[1] + " where I saw " + name);
                }
            }
            if (h.at() != null) visit(h, false, 0);
            return Car.Status.failed("hunt: " + got + " of " + want + " - no " + name + " I may take in view" + (h.at() == null ? "" : " (none at the place I saw them)"));
        }
        if (h.at() != null) visit(h, true, 1);
        if (bestD > Tuning.get("steps.huntReach")) {
            if (b != null) b.getCustomGoalProcess().setGoalAndPath(new GoalNear(best.blockPosition(), (int) Tuning.get("steps.walkNear")));
            return Car.Status.running(huntWhy = "walking to a " + name + " " + Math.round(bestD) + " away");
        }
        if (b != null && b.getCustomGoalProcess().isActive()) Jobs.cancel(b);
        String r = refl.forceAttack(best, name);
        huntTarget = best.getId();
        huntDrop = new int[]{best.getBlockX(), best.getBlockY(), best.getBlockZ()};
        LOG.info("[entropybot] steps: hunt: {}", r);
        if (r.startsWith("error")) return Car.Status.failed("hunt: " + r.replaceFirst("^error: ", ""));
        return Car.Status.running(huntWhy = "striking the " + name);
    }

    /** AA_ROUND3 5: the visit's outcome for the herd's sighting at the step's place (Sightings), once. Never throws. */
    private void visit(Step.HuntAt h, boolean found, int count) {
        if (huntVisited) return;
        huntVisited = true;
        try {
            var sg = io.github.mojolowjo.entropybot.sense.ChunkPass.INSTANCE.sightings();
            var lv = Minecraft.getInstance().level;
            String dim = io.github.mojolowjo.entropybot.sense.ChunkPass.INSTANCE.dim();
            if (sg == null || lv == null || dim == null) return;
            sg.visit(dim, h.type(), h.at()[0], h.at()[1], found, count, lv.getGameTime() * 50);  // game fact: a game tick is 50 ms
        } catch (RuntimeException e) {
            LOG.warn("[entropybot] steps: hunt sighting visit: {}", e.toString());
        }
    }
}
