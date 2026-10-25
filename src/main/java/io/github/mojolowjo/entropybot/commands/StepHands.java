package io.github.mojolowjo.entropybot.commands;

import baritone.api.IBaritone;
import baritone.api.pathing.goals.GoalNear;
import baritone.api.utils.BlockOptionalMetaLookup;
import com.mojang.logging.LogUtils;
import io.github.mojolowjo.entropybot.Core;
import io.github.mojolowjo.entropybot.aa.moves.StepWait;
import io.github.mojolowjo.entropybot.aa.brain.Tuning;
import io.github.mojolowjo.entropybot.aa.moves.Car;
import io.github.mojolowjo.entropybot.aa.moves.FirstAnswerWait;
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
        // jobs (owner 2026-10-22): a block job / placement / use holds the brain's goal and replan checks until it ends
        String r = start0(s);
        if (r == null) io.github.mojolowjo.entropybot.aa.brain.JobBoundary.enter(s);
        return r;
    }

    @Override public Car.Status status(Step s) {
        Car.Status st = status0(s);
        StepProbe.status(st.why());              // 0.39.z56 item 1: one field (the probe reads it when on)
        if (st.state() != Car.State.RUNNING) io.github.mojolowjo.entropybot.aa.brain.JobBoundary.leave();
        return st;
    }

    /**
     * 0.39.z56 item 2a (run O 22:15:20-22:17:43: "probe walk:" lines went on after the iron order stopped, 790 -> 3197 - the
     * walk step FAILED, the run ended without a cancel, and only start / cancel cleared the pointer): Steps says the step is
     * over on every path; the probes' step pointers go. Nothing else changes (the step's own state is reset at the next
     * start or cancel, as before).
     */
    @Override public void ended(String how) {
        WalkProbe.step(null);
        StepProbe.end(how);
    }

    /**
     * 0.39.z34 (S39): the step's needs from its own data ({@link io.github.mojolowjo.entropybot.aa.moves.StepNeeds#of}); a
     * mine at one ore cell asks the tier of the block that is there now (the game's needs_*_tool tags).
     */
    @Override public io.github.mojolowjo.entropybot.aa.moves.StepNeeds needs(Step s) {
        LocalPlayer p = Minecraft.getInstance().player;
        if (s instanceof Step.MineAt m && p != null) {
            BlockPos q = new BlockPos(m.x(), m.y(), m.z());
            if (!p.level().isLoaded(q)) return io.github.mojolowjo.entropybot.aa.moves.StepNeeds.NONE;   // its start says "not loaded"
            return io.github.mojolowjo.entropybot.aa.moves.StepNeeds.forBlocks(List.of(BuiltInRegistries.BLOCK.getKey(p.level().getBlockState(q).getBlock()).toString()));
        }
        return io.github.mojolowjo.entropybot.aa.moves.StepNeeds.of(s, bag());   // 0.39.z61: the bag picks among several recipes
    }

    /**
     * 0.39.z61 (D1 J2, replaces z38's keep list): the run's counted reserves to the fork's lease (Baritone places a reserved
     * item only while the bag holds more than the reserves need).
     */
    @Override public void reserve(List<baritone.api.process.Reserves.Reserve<String>> reserves) {
        io.github.mojolowjo.entropybot.engine.RunThrowaway.reserve(reserves);
        io.github.mojolowjo.entropybot.engine.RunThrowaway.walkPenalty();     // 0.39.z39: the walk lease's placement price, fresh per step
        StepProbe.keep(reserves.size());                                      // 0.39.z56 item 1: one field (now the reserves' count)
    }

    /** 0.39.z61 (owner's ruling 1, option c): the running order's target and the finished orders' items. */
    @Override public List<baritone.api.process.Reserves.Reserve<String>> orderReserves() {
        return c.aaRuntime == null ? List.of() : c.aaRuntime.orderReserves();
    }

    /** 0.39.z34: the bag as the step checks see it: the player's main inventory (one pass of its slots per look). */
    @Override public io.github.mojolowjo.entropybot.aa.moves.StepNeeds.Bag bag() {
        if (Minecraft.getInstance().player == null) return null;
        return new io.github.mojolowjo.entropybot.aa.moves.StepNeeds.Bag() {
            private String best(String kind, int[] tier) {
                LocalPlayer p = Minecraft.getInstance().player;
                String id = null;
                if (p == null) return null;
                for (net.minecraft.world.item.ItemStack st : p.getInventory().items) {
                    if (st.isEmpty()) continue;
                    String iid = BuiltInRegistries.ITEM.getKey(st.getItem()).toString();
                    int t = io.github.mojolowjo.entropybot.aa.brain.MiningTiers.toolTier(iid, kind);
                    if (t > tier[0]) { tier[0] = t; id = iid; }
                }
                return id;
            }

            @Override public int bestTier(String kind) { int[] t = {0}; best(kind, t); return t[0]; }

            @Override public String bestTool(String kind) { return best(kind, new int[]{0}); }

            @Override public int usesLeft(String id) { Integer u = AaRuntime.usesLeft(id); return u == null ? io.github.mojolowjo.entropybot.aa.brain.ToolUses.UNKNOWN : u; }

            @Override public int count(String id) {
                LocalPlayer p = Minecraft.getInstance().player;
                return p == null ? 0 : Gui.inventory(p).getOrDefault(id, 0);
            }

            /** 0.39.z61: the dry run's bag (the main inventory's stacks summed by item id). */
            @Override public java.util.Map<String, Integer> items() {
                LocalPlayer p = Minecraft.getInstance().player;
                return p == null ? null : Gui.inventory(p);
            }
        };
    }

    private String start0(Step s) {
        step = s;
        // 0.39.z54 B: the walk probe's step (one field; the probe itself reads Tuning debug.walkProbe)
        WalkProbe.step(s instanceof Step.ToStrip || s instanceof Step.ToBand || s instanceof Step.Walk ? s.text() : null);
        StepProbe.begin(s);                      // 0.39.z56 item 1: every step kind (one field; the probe reads Tuning debug.stepProbe)
        LocalPlayer p = Minecraft.getInstance().player;
        if (p == null) return "error: not in a world";
        stationsLine(s);
        try {
            // station-race (0.39.z17, rule 15): a craft / smelt whose station fact is not loaded yet waits for it (status:
            // running with the reason), exactly like the loot-book wait; it never plans, places or fails on "none" meanwhile
            if (StepWait.kindOf(s) != null) return dataWait.start(s, c.nearby::stations, () -> direct(s));
            return switch (s) {
                case Step.Walk w -> walk(w);
                case Step.ChopTree t -> chop(p, t);
                case Step.Collect k -> collect(p, k);
                case Step.TakeStation t -> takeStation(p, t);
                case Step.HuntAt h -> hunt(p, h);
                case Step.Build b -> build(b);
                case Step.PlaceNear n -> placeNear(p, n);
                case Step.ToBand t -> toBand(p, t);
                case Step.ToStrip t -> toStrip(p, t);
                case Step.Climb k -> climb(p);
                case Step.Fish fs -> fish(p, fs);
                case Step.KelpCut k -> kelpCut(p, k);
                case Step.MineAt m -> mineAt(p, m);
                case Step.Strip st -> StripStep.start(st);              // the owner's strip order: the fork's StripProcess
                case Step.Mine m when m.how() == Step.Mine.How.STRIP -> mineStrip(p, m);   // an AA strip leg: the same fork process (the old verb is gone)
                case Step.Deposit d when d.order() != null -> depositLeg(p, d);           // the strip order's deposit leg (DepositLeg)
                case Step.StripBack k -> stripBack(p, k);
                case Step.Deposit d -> depositLeg(p, d);                                  // DepositLeg (the order's leg, or a plain deposit)
                case Step.Explore k when k.whilePaths() && k.waitS() > 0 -> exploreFirstWait(k);   // 0.39.z58: wait in place first
                default -> direct(s);   // the item actions and the mine steps: the method, its own job
            };
        } catch (RuntimeException e) {
            LOG.warn("[entropybot] steps: {} did not start: {}", s.text(), e.toString());
            return "error: " + s.text() + ": " + e;
        }
    }

    /** station-race (0.39.z17): the craft / smelt step waits here while its station fact is not loaded (rule 15). */
    private final StepWait dataWait = new StepWait();

    /** station-note (0.39.z15): a craft / smelt step starts: one line of what the station memory knows (the station fact). */
    private void stationsLine(Step s) {
        String kind = StepWait.kindOf(s);
        if (kind == null) return;
        try {
            LOG.info("[entropybot] {} ({})", c.nearby.stationsLine(kind), s.text());
        } catch (RuntimeException e) {
            LOG.warn("[entropybot] stations: the lookup line for {} failed: {}", s.text(), e.toString());
        }
    }

    private Car.Status status0(Step s) {
        if (s != step) return Car.Status.failed("not the running step");
        LocalPlayer p = Minecraft.getInstance().player;
        if (p == null) return Car.Status.failed("not in a world");
        try {
            if (dataWait.waiting()) return dataWait.tick(c.nearby::stations, () -> { stationsLine(s); return direct(s); }, this::requestStatus);
            return switch (s) {
                case Step.ChopTree t -> mineStatus(p);
                case Step.Collect k -> mineStatus(p);
                case Step.TakeStation t -> mineStatus(p);
                case Step.KelpCut k -> mineStatus(p);
                case Step.MineAt m -> veinStatus(p, m);
                case Step.Strip st -> StripStep.status(st, Core.INSTANCE.reflexes.hold());
                case Step.Mine m when m.how() == Step.Mine.How.STRIP && legStrip != null -> StripStep.status(legStrip, Core.INSTANCE.reflexes.hold());
                case Step.Fish fs -> fishStatus(p);
                case Step.Deposit d when deposit != null -> depositStatus(p);
                case Step.HuntAt h -> huntStatus(p, h);
                case Step.Build b -> buildStatus(b);
                case Step.PlaceNear n -> placeNearStatus(n);
                case Step.ToBand t -> toBandStatus(p, t);
                case Step.ToStrip t -> toStripStatus(p, t);
                case Step.Climb k -> climbStatus();
                case Step.Explore k when firstWait.waiting() -> firstWaitStatus(k);
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
            if (deposit != null) {
                LocalPlayer dp = Minecraft.getInstance().player;
                if (dp != null && Gui.open(dp)) Gui.close(dp);
                io.github.mojolowjo.entropybot.aa.moves.JobHands.get().stop(why);
            }
            if (step instanceof Step.Strip || legStrip != null) StripStep.cancel();
            legStrip = null;
            if (step instanceof Step.HuntAt) {
                Core.INSTANCE.reflexes.stopAttack();
                IBaritone b = Jobs.baritone();
                if (b != null) Jobs.cancel(b);
            }
            if (step instanceof Step.Build || step instanceof Step.PlaceNear) io.github.mojolowjo.entropybot.aa.moves.JobHands.get().stop(why);
            if (step instanceof Step.ToBand || step instanceof Step.ToStrip) {
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
        io.github.mojolowjo.entropybot.aa.brain.JobBoundary.leave();
        step = null;
        WalkProbe.step(null);                    // 0.39.z54 B
        fishing = null;
        deposit = null;
        vein = null;
        req = null;
        instant = null;
        walkJob = null;
        if (mining) endMining();
        chopping = false;
        miningWhat = null;
        mineSource = null;
        mineFrom = null;
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
        dataWait.reset();
        firstWait.end();
    }

    // ---- 0.39.z58: the explore while paths compute first waits in place while first answers compute ----

    /**
     * The first-answer wait's state (0.39.z60 review 2: FirstAnswerWait.Tracker, pure - its JUnit drives the same object the
     * hands keep): when it began, the landed answers and fork segments at the last look, the last progress.
     */
    private final FirstAnswerWait.Tracker firstWait = new FirstAnswerWait.Tracker();

    /** 0.39.z58 review 7: the level's game time (stops while the game is paused); 0 with no level (the status says not in a world first). */
    private static long gameTime() {
        var lv = Minecraft.getInstance().level;
        return lv == null ? 0 : lv.getGameTime();
    }

    /**
     * 0.39.z58 (Opus Design ruling on run Q 02:29:32: rule 18 stays exact; the explore moved the bot off every crafting
     * walk's first answer): while first answers are computing or queued in the fork, the step waits in place (Baritone idle,
     * no walk) up to its waitS, so they land from this cell; each landing re-prices the order (Steps: a priced way ends the
     * step). None pending / the wait over -> the explore as before (FirstAnswerWait).
     */
    private String exploreFirstWait(Step.Explore k) {
        FirstAnswerWait.Decision d = FirstAnswerWait.of(0, k.waitS(), LivePathPrice.INSTANCE.firstAnswersPending(), false);
        LOG.info("[entropybot] steps: {}: {}", k.text(), d.why());
        if (d.next() == FirstAnswerWait.Next.EXPLORE) return direct(k);
        IBaritone b = Jobs.baritone();
        if (b != null) Jobs.cancel(b);                   // Baritone idle: the first answers are priced from the cell it stands on
        firstWait.begin(gameTime(), LivePathPrice.INSTANCE.landed(), LivePathPrice.INSTANCE.segmentsLanded());
        return null;
    }

    /**
     * 0.39.z60 item 1 (run R 03:34:56: the wait ended at 4 s with 2 searches still progressing; both landed at 6 s): the wait
     * runs to its 30 s cap while the first answers progress - a fork segment or an answer landed within
     * pathCost.firstAnswerStallS; review 2: the segment count not known (-1) ends it at once, as before.
     */
    private Car.Status firstWaitStatus(Step.Explore k) {
        FirstAnswerWait.Decision d = firstWait.look(gameTime(), LivePathPrice.INSTANCE.landed(), LivePathPrice.INSTANCE.segmentsLanded(),
                LivePathPrice.INSTANCE.firstAnswersPending(), k.waitS(), Tuning.get("pathCost.firstAnswerStallS"));
        if (d.next() == FirstAnswerWait.Next.WAIT) return Car.Status.running(k.text() + ": " + d.why());
        firstWait.end();
        LOG.info("[entropybot] steps: {}: {}", k.text(), d.why());
        String r = direct(k);
        return r != null ? Car.Status.failed(r) : requestStatus();
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

    // ---- the strip order's deposit leg and walk back ----

    private DepositLeg deposit;

    private String depositLeg(LocalPlayer p, Step.Deposit d) {
        deposit = new DepositLeg(c, d, new DepositLeg.Hands() {
            @Override public String craft(String item, int n) {
                req = null; instant = null;
                return follow("craft " + item, () -> c.stepJob("craft " + item + " " + n, () -> c.crafting.craft(p, item + " " + n, null)));
            }
            @Override public String walk(int x, int y, int z) {
                req = null; instant = null;
                return follow("walk", () -> c.stepJob("walk " + x + " " + y + " " + z, () -> c.walkTo(p, x, y, z)));
            }
        }, this::requestStatus);
        String[] inst = new String[1];
        String r = deposit.start(p, inst);
        if (inst[0] != null) { instant = inst[0]; deposit = null; }
        return r;
    }

    private Car.Status depositStatus(LocalPlayer p) {
        Car.Status st = deposit.tick(p);
        if (st.state() != Car.State.RUNNING) deposit = null;
        return st;
    }

    /** The walk back to where the bag filled (the saved deposits' back cell): the travel job's one Baritone goal. */
    private String stripBack(LocalPlayer p, Step.StripBack k) {
        if (StripStep.met(k.order())) { instant = "ok: the strip order is met, no walk back"; return null; }
        int[] back = StripStep.deposits(k.order()).back();
        if (back == null) { instant = "ok: no tunnel end saved (the strip resumes from its saved step)"; return null; }
        return walk(new Step.Walk(back[0], back[1], back[2], 0));
    }

    // ---- mine at: one ore cell and its vein over Baritone's MineProcess ----

    private VeinMine vein;

    /** An AA strip leg (Step.Mine STRIP) as the fork's strip: the family's ore blocks at the feet's Y, the facing heading. */
    private Step.Strip legStrip;

    private String mineStrip(LocalPlayer p, Step.Mine m) {
        String[] err = new String[1];
        legStrip = StripStep.resolve("strip " + m.family(), p, null, err);
        if (legStrip == null) return "error: " + err[0];
        return StripStep.start(legStrip);
    }

    private String mineAt(LocalPlayer p, Step.MineAt m) {
        var level = p.level();
        BlockPos q = new BlockPos(m.x(), m.y(), m.z());
        String fam = m.family();
        if (!level.isLoaded(q)) return "error: the ore cell " + m.x() + " " + m.y() + " " + m.z() + " is not loaded";
        if (!io.github.mojolowjo.entropybot.sense.KnownOre.family(BuiltInRegistries.BLOCK.getKey(level.getBlockState(q).getBlock()).toString(), fam)) {
            io.github.mojolowjo.entropybot.aa.moves.FactChange.source(fam + " ore", m.x() + " " + m.y() + " " + m.z());   // 0.39.z30: the step's changed fact
            return "error: no " + fam + " ore there any more (" + m.x() + " " + m.y() + " " + m.z() + ")";
        }
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

    // ---- the item actions and the mine steps: the typed step calls its method (never a verb line), its job's request ----

    /** An instant answer (no job). */
    private static Chains.Reply now(String r) { return Chains.Reply.now(r); }

    /** The typed step's method; a job's answer carries the job's request. */
    private Chains.Reply call(Step s, LocalPlayer p) {
        String me = c.owner();
        return switch (s) {
            case Step.Craft k -> c.stepJob(s.text(), () -> c.crafting.craft(p, k.item() + " " + k.n(), null));
            case Step.Smelt k -> c.stepJob(s.text(), () -> c.crafting.smelt(p, k.item() + " " + k.n() + (k.here() ? " here" : "")));
            case Step.Cook k -> {
                String t = io.github.mojolowjo.entropybot.farm.Cooking.smeltText(k.item() + " " + k.n());
                yield t == null ? now("error: " + s.text() + ": " + io.github.mojolowjo.entropybot.farm.Cooking.USAGE) : c.stepJob(s.text(), () -> c.crafting.smelt(p, t));
            }
            case Step.Fetch k -> c.stepJob(s.text(), () -> c.crafting.get(p, k.item() + " " + k.n()));
            case Step.SmeltCollect k -> c.stepJob(s.text(), () -> c.crafting.smelt(p, "collect all"));
            case Step.Scan k -> c.stepJob(s.text(), () -> c.storage.scan(p, k.base() ? "base" : ""));
            case Step.Mine m -> c.stepJob(s.text(), () -> Mining.get().mine(p, switch (m.how()) {
                case KNOWN -> m.family() + " " + m.n();
                case DIG -> m.family() + " " + m.n() + " dig";
                case CAVE -> "cave " + m.family() + " " + m.n() + (m.minutes() > 0 ? " " + m.minutes() + "m" : "");
                case STRIP -> throw new IllegalStateException("a strip leg runs on the fork's strip (mineStrip)");
            }, x -> null));
            case Step.FindCave k -> c.stepJob(s.text(), () -> Mining.get().find(p, io.github.mojolowjo.entropybot.vocab.ExploreWords.find("cave " + k.minutes(), Commands.POI_KINDS, Commands::isBiome)));
            case Step.Explore k -> c.stepJob(s.text(), () -> k.whilePaths() ? Mining.get().exploreWhilePaths(p, k.minutes())
                    : Mining.get().explore(p, k.minutes() + (k.until() == null ? "" : " until " + k.until())));
            case Step.Dig d -> c.stepJob(s.text(), () -> DigCommands.dig(c, p, d.from()[0] + " " + d.from()[1] + " " + d.from()[2] + " " + d.to()[0] + " " + d.to()[1] + " " + d.to()[2]
                    + (d.ores() ? " ores" : "") + (d.viaEngine() ? " via engine" : ""), me));
            case Step.DigArea k -> c.stepJob(s.text(), () -> DigCommands.dig(c, p, k.box()[0] + " " + k.box()[1] + " " + k.box()[2] + " " + k.box()[3] + " +" + k.up(), me));
            case Step.BuildSchematic k -> c.stepJob(s.text(), () -> DigCommands.build(c, p, "schematic " + k.name() + " " + k.x() + " " + k.y() + " " + k.z(), me));
            case Step.MarkMine k -> mark(p, "mine " + (k.at() == null ? "" : k.at()[0] + " " + k.at()[1] + " " + k.at()[2] + " ") + k.dir());
            case Step.MarkBase k -> mark(p, "base " + k.x() + " " + k.y() + " " + k.z());
            case Step.MarkFarm k -> mark(p, "farm" + (k.at() == null ? "" : " " + k.at()[0] + " " + k.at()[1] + " " + k.at()[2]));
            case Step.Restore k -> {
                String t = k.keep() ? "keep pillar" : "placed " + k.radius();
                yield io.github.mojolowjo.entropybot.restore.RestoreArgs.isJob(t) ? c.stepJob(s.text(), () -> RestoreLive.INSTANCE.startNow(p, t)) : now(RestoreLive.INSTANCE.command(p, t));
            }
            case Step.PutBack k -> c.stepJob(s.text(), () -> RestoreLive.INSTANCE.startNow(p, "now " + k.radius()));
            case Step.DropJunk k -> now(Storage.drop(p, k.item() + " " + k.n()));
            case Step.FarmPlant k -> c.stepJob(s.text(), () -> c.crafting.farm(p, "plant " + k.crop() + " here " + k.n(), me));
            case Step.Shelter k -> c.stepJob(s.text(), () -> ShelterJob.start(c, p, "", true, me));
            case Step.Sleep k -> c.stepJob(s.text(), () -> SleepJob.start(c, p, ""));
            case Step.Wait k -> c.stepJob(s.text(), () -> c.jobs.startWait(String.valueOf(k.seconds())));
            case Step.GoBase k -> c.stepJob(s.text(), () -> c.goBase(p));
            case Step.Come k -> c.stepJob(s.text(), () -> c.comeTo(me, p));
            case Step.Give k -> c.stepJob(s.text(), () -> {
                PolicyCommands.Pos h = c.hereOf(Minecraft.getInstance(), me);
                return c.crafting.give(p, "me " + k.item() + " " + k.n(), new int[]{h.x(), h.y(), h.z()});
            });
            case Step.Light k -> c.camp.light(p, "here " + k.radius(), me, true, s.text(), null);
            case Step.House k -> now(c.camp.house(p, me, true, k.area()));
            case Step.Burrow k -> now(Core.INSTANCE.reflexes.moves.burrow());
            case Step.Flee k -> now(Core.INSTANCE.reflexes.moves.flee());
            case Step.Pillar k -> now(Core.INSTANCE.reflexes.moves.pillar());
            case Step.Attack k -> now(c.vocab.attack(me, "nearest", p));
            case Step.Eat k -> now(Core.INSTANCE.reflexes.eat());
            default -> throw new IllegalStateException(s.text() + " has hands of its own (StepHands.start0), no method here");
        };
    }

    /** A place mark (place base|mine|farm ...): the instant place memory, else the place job. */
    private Chains.Reply mark(LocalPlayer p, String rest) {
        String pl = c.vocab.placeInstant(c.owner(), rest, p);
        return pl != null ? now(pl) : c.stepJob("place " + rest, () -> DigCommands.place(c, p, rest, c.owner()));
    }

    /** The typed step's method runs in the driver's door scope; its job's request is followed (or its answer is the end). */
    private String direct(Step s) {
        LocalPlayer p = Minecraft.getInstance().player;
        if (p == null) return "error: not in a world";
        return follow(s.text(), () -> call(s, p));
    }

    private String follow(String text, java.util.function.Supplier<Chains.Reply> run) {
        Chains.Reply r;
        var d = io.github.mojolowjo.entropybot.aa.door.Door.current() == null ? io.github.mojolowjo.entropybot.aa.door.Driver.AA : io.github.mojolowjo.entropybot.aa.door.Door.current();
        try (var sc = io.github.mojolowjo.entropybot.aa.door.Door.scope(d)) {
            r = run.get();
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
        LocalPlayer p = Minecraft.getInstance().player;
        if (p == null) return "error: not in a world";
        String r = follow(w.text(), () -> c.stepJob(w.text(), () -> c.walkTo(p, w.x(), w.y(), w.z())));
        if (r != null) return r;
        if (c.jobs.running() && "travel".equals(c.jobs.job.type)) {
            walkJob = c.jobs.job;
            if (w.near() > 0) {
                GoalNear g = new GoalNear(new BlockPos(w.x(), w.y(), w.z()), w.near());
                IBaritone b = Jobs.baritone();
                if (walkJob.finalGoal != null) walkJob.finalGoal = g;           // the far walk's last leg
                else if (b != null) Jobs.walkGoal(b, walkJob, g);
            } else if (walkJob.finalGoal == null) {
                // 0.39.z47 (item 4): the chain's walk starts Baritone on the path its price kept (LivePathPrice.walk: the
                // bot still at its start and its cells unchanged), else Baritone searches fresh - as the strip leg (z46)
                IBaritone b = Jobs.baritone();
                if (b != null) walkJob.goalObj = LivePathPrice.walkGoal(b, new BlockPos(w.x(), w.y(), w.z()));
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
        // 0.39.z36 (S42): the cut's source (its log or the logs kind, at its tree): an end with none got is its changed fact
        mineSource = t.log() != null ? t.log() : "logs";
        if (t.at() != null) mineFrom = "at " + t.at()[0] + " " + t.at()[1] + " " + t.at()[2];
        List<String> ids = new ArrayList<>();
        for (Block b : blocks) ids.add(BuiltInRegistries.ITEM.getKey(b.asItem()).toString());
        return mine(p, blocks, ids, t.logs(), "cut " + t.logs() + " " + (t.log() == null ? "logs" : t.log().replaceFirst("^minecraft:", "")));
    }

    private String collect(LocalPlayer p, Step.Collect k) {
        mineSource = k.item();
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
     * 0.39.z48 (run J 12:05:35, S63): the known station block at the step's cell, taken from beside it (the walk before it
     * went there on the priced path): the cell is checked first (not loaded: not known, rule 15; another block: the source
     * fact "gone", the order re-plans), then the fork's component break (mineComponent) on that block kind from here, whose
     * nearest component is this station - never the stock mine process, which searches the cache for any such block.
     */
    private String takeStation(LocalPlayer p, Step.TakeStation t) {
        mineSource = null;
        String id = t.item().contains(":") ? t.item() : "minecraft:" + t.item();
        Block own = BuiltInRegistries.ITEM.getOptional(ResourceLocation.tryParse(id)).map(Block::byItem).orElse(net.minecraft.world.level.block.Blocks.AIR);
        if (own == net.minecraft.world.level.block.Blocks.AIR) return "error: " + t.text() + ": " + t.item() + " is no block";
        BlockPos at = new BlockPos(t.x(), t.y(), t.z());
        var level = Minecraft.getInstance().level;
        if (level == null || !level.isLoaded(at)) return "error: " + t.text() + ": " + io.github.mojolowjo.entropybot.aa.PathPrice.NOT_KNOWN + " (its chunk is not loaded)";
        Block there = level.getBlockState(at).getBlock();
        if (there != own) {
            io.github.mojolowjo.entropybot.aa.moves.FactChange.source(t.item(), t.x() + " " + t.y() + " " + t.z());
            return "error: " + t.text() + ": the " + t.item() + " is gone (" + BuiltInRegistries.BLOCK.getKey(there) + " there now)";
        }
        int[] here = Jobs.here(p);
        double d = Math.sqrt(Jobs.distSq(here, new int[]{t.x(), t.y(), t.z()}));
        if (d > Tuning.get("station.takeReachM")) return "error: " + t.text() + ": " + Math.round(d) + " m from it, the walk before did not get there";
        mineFrom = "at " + t.x() + " " + t.y() + " " + t.z();
        component = true;
        try {
            return mine(p, new ArrayList<>(List.of(own)), List.of(id), 1, t.text());
        } finally {
            component = false;
        }
    }

    /** 0.39.z48: the next mine() runs the fork's component break whatever the block kind (a station take). */
    private boolean component;

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

    /** chop B1: blocks the game tags as logs go to the fork's chop process (the whole tree); ores too (S26, {@link #allOres}); others to the mine process. */
    static boolean allLogs(List<Block> blocks) {
        for (Block b : blocks) if (!b.defaultBlockState().is(BlockTags.LOGS)) return false;
        return !blocks.isEmpty();
    }

    /** S26: blocks the game tags as ores (c:ores): a cluster, mined whole by the fork's component rule. */
    static boolean allOres(List<Block> blocks) {
        for (Block b : blocks) if (!b.defaultBlockState().is(net.neoforged.neoforge.common.Tags.Blocks.ORES)) return false;
        return !blocks.isEmpty();
    }

    private String mine(LocalPlayer p, List<Block> blocks, List<String> ids, int n, String what) {
        IBaritone b = Jobs.baritone();
        if (b == null) return "error: baritone not loaded";
        // S26 (owner 2026-10-22): an ore cluster is one job like a tree: the fork's component rule (mineComponent) for logs and
        // ores alike; any other block set (stone, sand...) stays on the stock mine process (a stone "component" is the hillside)
        chopping = component || allLogs(blocks) || allOres(blocks);
        if (!allLogs(blocks)) {
            // the tier guard (owner-watched 2026-10-22): never start a dig the bag's pickaxe cannot do; the step fails at once
            // and the chain re-prices with the pickaxe first. Blocks it cannot dig leave the filter.
            int have = 0;
            for (net.minecraft.world.item.ItemStack st : p.getInventory().items)
                if (!st.isEmpty()) have = Math.max(have, io.github.mojolowjo.entropybot.aa.brain.MiningTiers.toolTier(BuiltInRegistries.ITEM.getKey(st.getItem()).toString(), "pickaxe"));
            List<String> blockItems = new ArrayList<>();
            for (Block bl : blocks) blockItems.add(BuiltInRegistries.ITEM.getKey(bl.asItem()).toString());
            String refusal = io.github.mojolowjo.entropybot.aa.brain.MiningTiers.digRefusal(blockItems, have);
            if (refusal != null) return "error: " + what + ": " + refusal;
            if (!io.github.mojolowjo.entropybot.aa.brain.MiningTiers.ready()) LOG.warn("[entropybot] steps: {}: no mining-tier data loaded, the pickaxe tier is not checked", what);
            List<String> ok = io.github.mojolowjo.entropybot.aa.brain.MiningTiers.diggable(blockItems, have);
            List<Block> kept = new ArrayList<>();
            for (int i = 0; i < blocks.size(); i++) if (ok.contains(blockItems.get(i))) kept.add(blocks.get(i));
            if (kept.size() < blocks.size()) LOG.info("[entropybot] steps: {}: {} of {} block kinds need a better pickaxe than tier {}, left out", what, blocks.size() - kept.size(), blocks.size(), have);
            blocks = kept;
        }
        countIds = ids;
        startCount = count(p);
        want = Math.max(1, n);
        BlockOptionalMetaLookup filter = new BlockOptionalMetaLookup(blocks.toArray(new Block[0]));
        // the count goes to Baritone (not 0): it counts the bag's items its filter matches (the blocks' drops), so its
        // target is what is there now + want
        int target = itemsOf(p, filter) + want;
        // 0.39.z8 (owner 2026-10-22, "remove the old job habit"): no Baritone setting here; the fork's chop / mine run
        // owns breaking and placing for its run (and the chop the bag's placeable blocks as throwaway)
        io.github.mojolowjo.entropybot.guard.PolicyRuntime.INSTANCE.setIntent(io.github.mojolowjo.entropybot.guard.BreakPolicy.Intent.MINE, "steps");
        mining = true;
        miningWhat = what;
        mineLines0 = io.github.mojolowjo.entropybot.baritone.BaritoneHook.lines();
        int[] here = Jobs.here(p);
        if (mineFrom == null) mineFrom = "searched from " + here[0] + " " + here[1] + " " + here[2];   // a tree step names its own spot
        try {
            if (chopping) {
                b.getChopProcess().mineComponent(target, filter);
                if (!b.getChopProcess().isActive()) {          // it ended at its start (none in range): its own reason
                    endMining();
                    String end = endOf(b.getChopProcess().lastEnd());
                    ChopEnd.zeroGot(0, mineSource, mineFrom, end);   // 0.39.z36 (S42): none got: the source fact, the order re-plans
                    return "error: " + what + ": " + end;
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

    /** 0.39.z33: Baritone's chat-line count at the mine's start, the collect's item (null: not a collect) and where it started. */
    private long mineLines0;
    private String mineSource, mineFrom;

    /**
     * 0.39.z33 (S36, rule 7): the mine process's end in Baritone's own words: its last chat line since the start (the stock
     * mine process says its end there, its API has none); no line since the start is said so, never guessed.
     */
    static String mineSaid(long linesAtStart, long linesNow, String lastLine) {
        return io.github.mojolowjo.entropybot.aa.moves.FactChange.baritoneSaid(linesAtStart, linesNow, lastLine);
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
            return ChopEnd.of(miningWhat, got, want, endOf(b.getChopProcess().lastEnd()), mineSource, mineFrom);
        }
        if (got >= want) {
            if (b != null) b.getMineProcess().cancel();
            endMining();
            return Car.Status.done("ok: " + miningWhat + ": got " + got);
        }
        if (b == null) return Car.Status.failed("baritone not loaded");
        if (!b.getMineProcess().isActive() && !Core.INSTANCE.reflexes.hold()) {
            endMining();
            String said = mineSaid(mineLines0, io.github.mojolowjo.entropybot.baritone.BaritoneHook.lines(), io.github.mojolowjo.entropybot.baritone.BaritoneHook.lastLine());
            // 0.39.z33 (S36): none of it got: the source is a changed fact (unreachable) and the order re-plans without it
            ChopEnd.zeroGot(got, mineSource, mineFrom, said);
            return Car.Status.failed(miningWhat + ": Baritone's mine process ended with " + got + " of " + want + ": " + said);
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
        if (!r.failed()) return null;
        // 0.39.z60 item 3 (run R 03:52:52: the order stopped on "no cell within 3 blocks" with no re-plan): a typed fact, so
        // the order is planned again on the facts of now (rule 7) instead of parked
        io.github.mojolowjo.entropybot.aa.moves.FactChange.placeFailed(n.item(), r.why());
        return "error: " + r.why();
    }

    private Car.Status placeNearStatus(Step.PlaceNear n) {
        var r = io.github.mojolowjo.entropybot.aa.moves.JobHands.get().placeNear(n.item(), Tuning.i("steps.placeNearR"), (int) (20 * Tuning.need("hands.buildS")), "steps");
        if (r.done()) {
            // station-note (0.39.z15): a station the chain placed is mine in the ledger ("built: station"), as the place verb notes it
            String line = io.github.mojolowjo.entropybot.clear.OwnStations.afterPlaceNear(n.item(), r.cell(), RestoreLive.INSTANCE::noteStation);
            if (line != null) {
                LOG.info("[entropybot] {}", line);
                c.nearby.forget();          // the cached station answers go: the new one is known now
            }
            return Car.Status.done("ok: " + n.text());
        }
        if (r.failed()) {
            io.github.mojolowjo.entropybot.aa.moves.FactChange.placeFailed(n.item(), r.why());   // 0.39.z60 item 3: re-plan, never park
            return Car.Status.failed(r.why());
        }
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
        if (!b.getCustomGoalProcess().isActive() && !Core.INSTANCE.reflexes.hold()) {
            String why = "Baritone's goal ended at y " + y + " (no path into the band)";
            io.github.mojolowjo.entropybot.aa.moves.FactChange.band(t.minY(), t.maxY(), why);     // 0.39.z42: a fact change, the order re-plans
            return Car.Status.failed(t.text() + ": " + why);
        }
        return Car.Status.running(t.text() + ": at y " + y);
    }

    // ---- 0.39.z46: to the strip's band: the priced path (one search), done when the feet stand by the cell ----

    private String toStrip(LocalPlayer p, Step.ToStrip t) {
        BlockPos at = new BlockPos(t.x(), t.y(), t.z());
        if (LivePathPrice.goal(at).isInGoal(p.blockPosition())) { instant = "ok: already by " + t.x() + " " + t.y() + " " + t.z(); return null; }
        IBaritone b = Jobs.baritone();
        if (b == null) return "error: baritone not loaded";
        io.github.mojolowjo.entropybot.guard.PolicyRuntime.INSTANCE.setIntent(io.github.mojolowjo.entropybot.guard.BreakPolicy.Intent.TRAVEL, "to strip");
        LivePathPrice.walk(b, at);
        return null;
    }

    private Car.Status toStripStatus(LocalPlayer p, Step.ToStrip t) {
        if (instant != null) return Car.Status.done(instant);
        IBaritone b = Jobs.baritone();
        if (b == null) return Car.Status.failed("baritone not loaded");
        if (LivePathPrice.goal(new BlockPos(t.x(), t.y(), t.z())).isInGoal(p.blockPosition())) {
            Jobs.cancel(b);
            return Car.Status.done("ok: at y " + p.blockPosition().getY() + " for the strip");
        }
        if (!b.getCustomGoalProcess().isActive() && !Core.INSTANCE.reflexes.hold()) {
            String why = "Baritone's walk ended at y " + p.blockPosition().getY() + " short of y " + t.y() + " (no path down to the band)";
            io.github.mojolowjo.entropybot.aa.moves.FactChange.strip(t.strip(), why);    // the strip way dropped for the order, it re-plans
            return Car.Status.failed(t.text() + ": " + why);
        }
        return Car.Status.running(t.text() + ": at y " + p.blockPosition().getY());
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
