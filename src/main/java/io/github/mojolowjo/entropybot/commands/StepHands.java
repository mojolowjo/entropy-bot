package io.github.mojolowjo.entropybot.commands;

import baritone.api.IBaritone;
import baritone.api.pathing.goals.GoalNear;
import baritone.api.utils.BlockOptionalMetaLookup;
import com.mojang.logging.LogUtils;
import io.github.mojolowjo.entropybot.Core;
import io.github.mojolowjo.entropybot.aa.moves.StepWait;
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
    /** 0.39.z78 vein: the collect runs the fork's vein (ore); {@code veinWalking}: still on its walk to the priced spot. */
    private boolean veining, veinWalking;
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

    /** 0.39.z75: the feet at the last status call and the tick they last changed (the stall facts' "since the last move"). */
    private int[] lastFeet;
    private long lastMoveTick = -1;

    /**
     * 0.39.z75 (run AI 20:11:05-20:14:16): a frozen walk's or explore's facts for the probe line. Loader notes: Baritone's
     * IPathingControlManager#mostRecentInControl, IBaritoneProcess#displayName, IPathingBehavior#getCurrent / getGoal /
     * isPathing, IPathExecutor#getPath / getPosition; vanilla Entity#isInWater / isInLava.
     */
    @Override public String stallFacts(Step s) {
        LocalPlayer p = Minecraft.getInstance().player;
        IBaritone b = Jobs.baritone();
        if (p == null || b == null) return "no player or no Baritone";
        int[] me = Jobs.here(p);
        var ctl = b.getPathingControlManager().mostRecentInControl();
        String proc = ctl.map(x -> x.displayName() + (x.isActive() ? " (active)" : " (inactive)")).orElse("none");
        var pb = b.getPathingBehavior();
        var cur = pb.getCurrent();
        String path = cur == null ? "no path executing" : "path " + cur.getPath().length() + " moves, at " + cur.getPosition();
        long now = Core.INSTANCE.tick();
        // 0.39.z75 probe (run AK 20:50:41 / 20:51:44: a 400 m GoalXZ from y -28, "a search running", nothing executing; the
        // hypothesis: the pricing searches starve Baritone's movement search on the shared executor): its search's age and
        // timeouts, the pricing searches running and queued now, and the explore target with its chooser's reason
        var set = baritone.api.BaritoneAPI.getSettings();
        long age = StepProbe.calcAgeTicks();
        String search = "Baritone's search " + (pb.getInProgress().isPresent() ? "running" + (age < 0 ? " (start not seen)" : " " + age * 50 + " ms") : "none")   // structural: 50 ms a tick
                + " (primaryTimeoutMS " + set.primaryTimeoutMS.value + ", failureTimeoutMS " + set.failureTimeoutMS.value
                + ", planAheadPrimaryTimeoutMS " + set.planAheadPrimaryTimeoutMS.value + ", planAheadFailureTimeoutMS " + set.planAheadFailureTimeoutMS.value + ")";
        // 0.39.z76 J3-mod item 0 (fork J3: run AL's "73 running" was started - landed, with 70 searches cancelled for priority
        // that never land): the fork's true running count, IPricedPath.pending() (= started - landed - ended)
        String pricing = "pricing searches: the mod's in flight " + LivePathPrice.INSTANCE.inFlight()
                + ", the fork's segment searches running " + segmentsRunning(b)
                + ", first answers pending " + b.getPricedPath().firstAnswersPending()
                + ", the mod's asks withdrawn so far " + LivePathPrice.INSTANCE.withdrawn();
        // 0.39.z76 J3-mod item 0: the goal's owner process (its class: an inactive one shows only "INACTIVE" by name), the goal
        // last set while this step ran and who held it, Baritone's last search result, the explore target (or that it holds none)
        String owner = "goal owner " + ctl.map(StepHands::processName).orElse("none") + " (last goal this step " + (carGoal == null ? "none seen" : carGoal + " by " + carOwner) + ")";
        String calc = "Baritone's last search result " + (io.github.mojolowjo.entropybot.baritone.BaritoneHook.lastCalc() == null ? "none seen" : io.github.mojolowjo.entropybot.baritone.BaritoneHook.lastCalc());
        String target = s instanceof Step.Explore ? Mining.exploreTargetText(me) : null;
        if (s instanceof Step.Explore && target == null) target = "explore target none (the explore holds no target now)";
        return "process " + proc + " | " + owner + " | pathing " + pb.isPathing() + ", " + path + " | goal " + pb.getGoal() + " | feet " + me[0] + " " + me[1] + " " + me[2]
                + " | in fluid " + (p.isInWater() || p.isInLava() ? (p.isInLava() ? "lava" : "water") : "no")
                + " | " + (lastMoveTick < 0 ? "no move seen yet" : (now - lastMoveTick) + " ticks since the last move")
                + " | " + search + " | " + calc + " | " + pricing + (target == null ? "" : " | " + target);
    }

    /**
     * 0.39.z76 J3-mod item 0: the fork's priced segment searches running now - {@code IPricedPath.pending()} (fork J3); an older
     * fork: started - landed - ended from searchCounts ([3] when it has one), said as such. Loader notes: the fork's IPricedPath.
     */
    private static String segmentsRunning(IBaritone b) {
        try {
            return String.valueOf(b.getPricedPath().pending());
        } catch (LinkageError e) {
            long[] seg = b.getPricedPath().searchCounts();
            if (seg == null || seg.length < 2 || seg[0] < 0) return "not known (this fork has no IPricedPath.pending: " + e + ")";
            return (seg[0] - seg[1] - (seg.length > 3 ? seg[3] : 0)) + " (started - landed" + (seg.length > 3 ? " - ended" : ", an older fork: over-counts the cancelled") + ")";   // structural: [started, landed, nodes, ended]
        }
    }

    /** 0.39.z76 J3-mod item 0: a Baritone process by its class (its display name is "INACTIVE" once it is not active). */
    private static String processName(baritone.api.process.IBaritoneProcess p) {
        return p.getClass().getSimpleName() + (p.isActive() ? " (active)" : " (inactive)");
    }

    // ---- 0.39.z76 J3-mod item 0: the car has no goal ----

    /** The goal last seen set while this step ran ("GoalXZ{...}") and the process that held it; null: none seen yet. */
    private String carGoal, carOwner;
    /** Baritone's CALC_FAILED count when that goal was last seen (or when the step started). */
    private long carFailsAt;

    /**
     * 0.39.z76 J3-mod item 0 (run AL 21:06:51, run AK 20:49:42-20:50:10): the car is idle now under a step that drives it -
     * Baritone has no goal, no process in control is active, no path executes and no search runs; Baritone's own reason
     * ({@link Steps#carIdleReason}). Null while the car drives, while the step does not use it now ({@link #drivesCar}: its
     * own waits, a hunt's strike, the item jobs), while a reflex holds the bot, or with no Baritone (the status says so).
     * Loader notes: Baritone API (IPathingBehavior#getGoal / getCurrent / getInProgress, IPathingControlManager#mostRecentInControl).
     */
    @Override public String carIdle(Step s) {
        if (s != step || instant != null || dataWait.waiting() || !drivesCar(s) || Core.INSTANCE.reflexes.hold()) return null;
        IBaritone b = Jobs.baritone();
        if (b == null) return null;
        var pb = b.getPathingBehavior();
        var ctl = b.getPathingControlManager().mostRecentInControl();
        long fails = io.github.mojolowjo.entropybot.baritone.BaritoneHook.calcFailed();
        baritone.api.pathing.goals.Goal g = pb.getGoal();
        if (g != null) {
            carGoal = String.valueOf(g);
            carOwner = ctl.map(StepHands::processName).orElse("no process in control");
            carFailsAt = fails;
            return null;
        }
        if (ctl.isPresent() && ctl.get().isActive()) return null;        // a process drives with no goal (the chop's break in reach)
        if (pb.getCurrent() != null || pb.getInProgress().isPresent()) return null;
        return Steps.carIdleReason(carGoal, carOwner, fails - carFailsAt, io.github.mojolowjo.entropybot.baritone.BaritoneHook.lastCalc());
    }

    /**
     * 0.39.z76 J3-mod item 0: the running step drives Baritone now - a walk whose travel job runs, an explore walking to its
     * held target (Mining.exploreWalking), a collect / cut while its mine process is on, the band and strip walks once
     * walking. Not: a hunt (its strike and pick-up have their own cadence), placements, strips, mines at a cell, item jobs.
     */
    private boolean drivesCar(Step s) {
        boolean travel = walkJob != null && c.jobs.running() && c.jobs.job == walkJob;
        return switch (s) {
            case Step.Walk w -> travel;
            case Step.ToStation t -> travel;
            case Step.ToCave t -> travel;
            case Step.ToSurface t -> travel;
            case Step.StripBack k -> travel;
            case Step.Explore k -> Mining.exploreWalking();
            case Step.Collect k -> mining;
            case Step.ChopTree t -> mining;
            case Step.KelpCut k -> mining;
            case Step.ToBand t -> bandWalking;
            case Step.ToStrip t -> stripWalking;             // review: once its walk started (as ToBand)
            default -> false;
        };
    }

    /**
     * 0.39.z75 (run AI 20:11:05-20:14:16; review 2: only for an explore, called by the run for that step): the monitor ended
     * the running explore for no progress - its target is held by position (Mining.frozen), so the next explore from here
     * picks another target.
     */
    @Override public void exploreFrozen(Step s, String why) {
        LocalPlayer fp = Minecraft.getInstance().player;
        if (fp != null && s instanceof Step.Explore) Mining.frozen(Jobs.here(fp), why);
    }

    @Override public Car.Status status(Step s) {
        LocalPlayer pl = Minecraft.getInstance().player;
        if (pl != null) {
            int[] f = Jobs.here(pl);
            if (lastFeet == null || f[0] != lastFeet[0] || f[1] != lastFeet[1] || f[2] != lastFeet[2]) { lastFeet = f; lastMoveTick = Core.INSTANCE.tick(); }
        }
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

    /** 0.39.z70 (D2 3.1): the feet cell when a step starts (the Attempt's start cell); null: no player (rule 15). */
    @Override public int[] feet() {
        LocalPlayer p = Minecraft.getInstance().player;
        return p == null ? null : Jobs.here(p);
    }

    @Override public int[] standStart() {
        LocalPlayer p = Minecraft.getInstance().player;
        return p == null ? null : searchStart(Jobs.baritone(), p).cell();
    }

    /** J6b item 14: the cell Baritone's searches start from and whether it has a floor under it. */
    record Start(int[] cell, boolean standable) {}

    /**
     * 0.39.z78 J6b queue item 14 (run ER 07:37:48: the band leg's cell was the column of the floorless edge cell -10 61 69,
     * the priced search stopped on its budget): the cell Baritone's searches start from - the fork's API,
     * IPathingBehavior#searchStart (job-vein a2d4e1c8: its pathStart, the supporting block when the feet stand off an edge;
     * rule 23 (2): one reader with the search itself) - and whether it is standable (a block with a collision shape under
     * it). Not given (no Baritone, an older fork, no player): the feet. Loader notes: the fork's API; vanilla
     * LocalPlayer#blockPosition, BlockState#getCollisionShape.
     */
    static Start searchStart(IBaritone b, LocalPlayer p) {
        int[] cell = Jobs.here(p);
        baritone.api.utils.BetterBlockPos s = null;
        try {
            s = b == null ? null : b.getPathingBehavior().searchStart();
        } catch (LinkageError e) {
            s = null;                                    // an older fork (no searchStart): the feet, said by the step's own line
        }
        if (s != null) cell = new int[]{s.x, s.y, s.z};
        BlockPos under = new BlockPos(cell[0], cell[1] - 1, cell[2]);
        return new Start(cell, p.level().isLoaded(under) && !p.level().getBlockState(under).getCollisionShape(p.level(), under).isEmpty());
    }
    /** 0.39.z70: the step machine's decision lines (the re-plan's plan diff) at info level. */
    @Override public void log(String line) { LOG.info("[entropybot] steps: {}", line); }

    // ---- 0.39.z71 (design D2 3.2): the step monitor's signals ----

    private final StepSignalsLive live = new StepSignalsLive();
    /** When (System ms) the running step started: a furnace job it started is its own wait. */
    private long stepStartMs;

    /** The running step's signals now (StepSignalsLive); the hands' own waits said as such. */
    @Override public io.github.mojolowjo.entropybot.aa.moves.StepSignals signals(Step s) {
        if (s != step) return io.github.mojolowjo.entropybot.aa.moves.StepSignals.notLoaded("not the running step");
        String nl = dataWait.waiting() ? "the station fact is not loaded yet (the step waits for it)" : null;
        return live.read(nl, ownWait(s), caveSeen(s));
    }

    /**
     * Run HF (round 17, 2026-11-02; the owner: the step clears the cave, it never leaves early on a poor count): the caving
     * step's progress for the monitor is its record's cells seen (the one feeder, Sightings.caveStood), read as an explore's
     * chunks are - a step that still sees new cells is working, a step that sees none for monitor.stallS is not. -1: not the
     * caving step, or no record (rule 15: the monitor then reads the other signals as before).
     */
    private int caveSeen(Step s) {
        Caving cv = caving;
        if (!(s instanceof Step.Cave k) || cv == null) return -1;
        var sg = io.github.mojolowjo.entropybot.sense.ChunkPass.INSTANCE.sightings();
        var f = sg == null ? null : sg.caveFacts(cv.dim, k.x(), k.y(), k.z());
        return f == null ? -1 : f.cellCount() - f.unseen();
    }

    /**
     * The step waits on a clock of its own (the monitor's stall window does not run meanwhile), its words; null: none. The
     * standing step (bounded by pathCost.firstAnswerWaitS, z76 rule 22), the band walk's priced path still computing
     * (bounded by steps.stepMaxS), a smelt / cook whose furnace job (started by this step) is cooking - the furnace job's
     * own due time and its stall watch (furnaceJobs.stallMs). The game exposes no furnace progress to the client outside
     * its open menu, so the furnace job's clock is the smelt's signal (D2 risk 8), never a guess.
     */
    private String ownWait(Step s) {
        if (s instanceof Step.AwaitPaths a && stand.standing()) return "standing while the walks compute (its own bound pathCost.firstAnswerWaitS " + a.waitS() + " s; rule 22)";
        if (s instanceof Step.ToBand && !bandWalking && bandCell != null) return "the priced path to " + Jobs.fmt(bandCell) + " is not known yet (its own bound steps.stepMaxS)";
        if (s instanceof Step.Smelt || s instanceof Step.Cook) {
            long now = System.currentTimeMillis();
            for (var j : c.crafting.furnaces().all())
                if (!j.stalled && j.remaining() > 0 && j.startedAt >= stepStartMs && now < j.dueAt + io.github.mojolowjo.entropybot.craft.FurnaceJobs.slackMs())
                    return io.github.mojolowjo.entropybot.craft.FurnaceJobs.waitStatus(j, now) + " (its own watch furnaceJobs.stallMs)";
        }
        return null;
    }

    /**
     * job-mon (run JAI): Baritone's process in control (its displayName), its last chat line and line count (BaritoneHook's
     * logger), for the monitor's silent rule. Null: not the running step, or Baritone not loaded / not hooked (rule 15: the
     * silent rule is then off). Loader notes: Baritone API IPathingControlManager#mostRecentInControl,
     * IBaritoneProcess#displayName.
     */
    @Override public io.github.mojolowjo.entropybot.aa.moves.StepMonitor.Said said(Step s) {
        if (s != step || !Core.INSTANCE.baritone.hooked()) return null;
        var b = Jobs.baritone();
        if (b == null) return null;
        var in = b.getPathingControlManager().mostRecentInControl();
        String proc = in.isPresent() ? in.get().displayName() : null;
        return new io.github.mojolowjo.entropybot.aa.moves.StepMonitor.Said(proc,
                io.github.mojolowjo.entropybot.baritone.BaritoneHook.lastLine(), io.github.mojolowjo.entropybot.baritone.BaritoneHook.lines());
    }

    /** 0.39.z71: the monitor's probe lines (debug.monitorProbe 1). */
    @Override public void probe(String line) { LOG.info("[entropybot] probe monitor: {}", line); }

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
        WalkProbe.step(s instanceof Step.ToStrip || s instanceof Step.ToBand || s instanceof Step.Walk || s instanceof Step.ToStation || s instanceof Step.ToSurface
                || s instanceof Step.ToCave || s instanceof Step.Cave || s instanceof Step.PlaceNear ? s.text() : null,     // run EN: the lip and the caving walks too
                s instanceof Step.PlaceNear);            // run EQ: the place step's leg, with the fork's face check
        StepProbe.begin(s);                      // 0.39.z56 item 1: every step kind (one field; the probe reads Tuning debug.stepProbe)
        LocalPlayer p = Minecraft.getInstance().player;
        if (p == null) return "error: not in a world";
        live.begin(s, p);                        // 0.39.z71: the monitor's made baseline (before the step takes or makes anything)
        stepStartMs = System.currentTimeMillis();
        // 0.39.z66 item 1 (run X 08:38:24, 08:40:39): AA's own put-back holding the slot is halted for every step kind - a
        // job step would answer "busy" and end the order's run, a Baritone step (collect, chop) would lose control to it
        String halt = c.haltRestoreFor(s.text());
        if (halt != null) return halt;
        stationsLine(s);
        try {
            // station-race (0.39.z17, rule 15): a craft / smelt whose station fact is not loaded yet waits for it (status:
            // running with the reason), exactly like the loot-book wait; it never plans, places or fails on "none" meanwhile
            if (StepWait.kindOf(s) != null) return dataWait.start(s, c.nearby::stations, () -> direct(s));
            return switch (s) {
                case Step.Walk w -> walk(w);
                case Step.ToStation t -> toStation(t);     // 0.39.z65 item 1: the chain's priced walk
                case Step.ToCave t -> toCave(p, t);         // J6b run EF: to the cave's lip (a reach goal)
                case Step.ToSurface t -> toSurface(p, t);  // 0.39.z68: the explore's priced walk up
                case Step.ChopTree t -> chop(p, t);
                case Step.Collect k -> collect(p, k);
                case Step.TakeStation t -> takeStation(p, t);
                case Step.TakeNear t -> takeNear(p, t);
                case Step.HuntAt h -> hunt(p, h);
                case Step.Build b -> build(b);
                case Step.PlaceNear n -> placeNear(p, n);
                case Step.ToBand t -> toBand(p, t);
                case Step.ToStrip t -> toStrip(p, t);
                case Step.Climb k -> climb(p);
                case Step.Fish fs -> fish(p, fs);
                case Step.KelpCut k -> kelpCut(p, k);
                case Step.MineAt m -> mineAt(p, m);
                case Step.Cave k -> caveStart(p, k);                   // 0.39.z78 J6b: the caving step (D12)
                case Step.Strip st -> StripStep.start(st);              // the owner's strip order: the fork's StripProcess
                case Step.Mine m when m.how() == Step.Mine.How.STRIP -> mineStrip(p, m);   // an AA strip leg: the same fork process (the old verb is gone)
                case Step.Deposit d when d.order() != null -> depositLeg(p, d);           // the strip order's deposit leg (DepositLeg)
                case Step.StripBack k -> stripBack(p, k);
                case Step.Deposit d -> depositLeg(p, d);                                  // DepositLeg (the order's leg, or a plain deposit)
                case Step.AwaitPaths a -> standForPaths(a);   // 0.39.z76 send-back 3 (rule 22): stand while the walks compute
                default -> direct(s);   // the item actions and the mine steps: the method, its own job
            };
        } catch (RuntimeException e) {
            LOG.warn("[entropybot] steps: {} did not start: {} at {}", s.text(), e.toString(), io.github.mojolowjo.entropybot.Quiet.where(e));
            io.github.mojolowjo.entropybot.aa.moves.FactChange.threw(e);     // 0.39.z76 send-back 2 (rule 7): the step's fact, the order re-plans
            return "error: " + s.text() + ": " + e + " at " + io.github.mojolowjo.entropybot.Quiet.where(e);
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
                case Step.TakeStation t -> takeStatus(p, t);
                case Step.TakeNear t -> takeNearStatus(p, t);
                case Step.KelpCut k -> mineStatus(p);
                case Step.MineAt m -> veinStatus(p, m);
                case Step.Cave k -> caveStatus(p);
                case Step.Strip st -> StripStep.status(st, Core.INSTANCE.reflexes.hold());
                case Step.Mine m when m.how() == Step.Mine.How.STRIP && legStrip != null -> StripStep.status(legStrip, Core.INSTANCE.reflexes.hold());
                case Step.Fish fs -> fishStatus(p);
                case Step.Deposit d when deposit != null -> depositStatus(p);
                case Step.HuntAt h -> huntStatus(p, h);
                case Step.Build b -> buildStatus(b);
                case Step.PlaceNear n -> placeNearStatus(n);
                case Step.ToBand t -> toBandStatus(p, t);
                case Step.ToStrip t -> toStripStatus(p, t);
                case Step.Climb k -> climbStatus(p);
                case Step.AwaitPaths a -> standStatus(a);     // 0.39.z76 send-back 3 (rule 22)
                case Step.ToStation t -> toStationStatus(t);
                case Step.ToCave t -> toCaveStatus(p, t);
                case Step.ToSurface t -> toSurfaceStatus(p, t);
                case Step.Walk w -> walkStatus(w);
                default -> requestStatus();
            };
        } catch (RuntimeException e) {
            LOG.warn("[entropybot] steps: {} status: {} at {}", s.text(), e.toString(), io.github.mojolowjo.entropybot.Quiet.where(e));
            io.github.mojolowjo.entropybot.aa.moves.FactChange.threw(e);     // 0.39.z76 send-back 2 (rule 7): the step's fact, the order re-plans
            return Car.Status.failed("status: " + e + " at " + io.github.mojolowjo.entropybot.Quiet.where(e));
        }
    }

    @Override public void cancel(String why) {
        try {
            if (mining) {
                IBaritone b = Jobs.baritone();
                if (b != null && chopping) b.getChopProcess().cancel();
                else if (b != null && veining && veinWalking) Jobs.cancel(b);     // 0.39.z78 vein: its walk to the priced spot
                else if (b != null && veining) b.getVeinProcess().cancel();
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
            if (step instanceof Step.Cave && caving != null) {
                if (caving.vein != null) caving.vein.stop();
                IBaritone b = Jobs.baritone();
                if (b != null) Jobs.cancel(b);
                caveEnd(io.github.mojolowjo.entropybot.cave.CaveWalk.End.STOPPED, why);   // review: an owner stop or a re-plan, its own end
            }
            if (step instanceof Step.ToCave && lip != null) {
                lip = null;
                IBaritone b = Jobs.baritone();
                if (b != null) Jobs.cancel(b);
            }
            if (step instanceof Step.Climb && climbing) {
                climbing = false;
                IBaritone b = Jobs.baritone();
                if (b != null) Jobs.cancel(b);
            }
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
        veining = false;
        veinWalking = false;
        miningWhat = null;
        mineSource = null;
        mineFrom = null;
        mineFilter = null;
        countIds = List.of();
        huntTarget = -1;
        huntTicks = 0;
        huntDrop = null;
        huntArrived = false;
        huntVisited = false;
        huntWalkAt = -1;
        huntWhy = null;
        climbing = false;
        bandCell = null;                        // 0.39.z65 item 3
        bandWalking = false;
        stripWalking = false;                    // 0.39.z76 review
        collectSpot = null;                      // 0.39.z76 send-back 2
        collectStartDist = Double.NaN;
        carGoal = null;                         // 0.39.z76 J3-mod item 0: the next step's car starts unseen
        carOwner = null;
        carFailsAt = io.github.mojolowjo.entropybot.baritone.BaritoneHook.calcFailed();
        dataWait.reset();
        stand.end();
    }

    // ---- 0.39.z76 send-back 3 (rule 22): the standing step - the bot stands while its walks' paths compute ----

    /** The standing step's clock (StandWait, pure - its JUnit drives the same object the hands keep). */
    private final io.github.mojolowjo.entropybot.aa.moves.StandWait stand = new io.github.mojolowjo.entropybot.aa.moves.StandWait();

    /** 0.39.z58 review 7: the level's game time (stops while the game is paused); 0 with no level (the status says not in a world first). */
    private static long gameTime() {
        var lv = Minecraft.getInstance().level;
        return lv == null ? 0 : lv.getGameTime();
    }

    /**
     * 0.39.z76 send-back 3 (rule 22; run AU 01:57:53: "no first answer is computing: exploring at once", then 5 min of walking
     * while every price was searched again from each new cell): the bot stands - Baritone idle, nothing started - so the
     * walks are priced from the cell it stands on; each landing prices the order again (Steps). Said once at the start.
     */
    private String standForPaths(Step.AwaitPaths a) {
        return stand.start(a, gameTime(), LivePathPrice.INSTANCE.firstAnswersPending(), () -> {
            IBaritone b = Jobs.baritone();
            if (b != null) Jobs.cancel(b);
        }, line -> LOG.info("[entropybot] steps: {}", line));
    }

    /**
     * Running while it stands; past its waitS (pathCost.firstAnswerWaitS) it says so once and ends with a price fact, so the
     * order is priced again where the bot stands - the walks still not known then taken by Baritone's estimate (rule 18 as
     * amended, PathPrice.Kind.ESTIMATED).
     */
    private Car.Status standStatus(Step.AwaitPaths a) {
        return stand.status(a, gameTime(), LivePathPrice.INSTANCE.firstAnswersPending(), line -> LOG.info("[entropybot] steps: {}", line));
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
        // 0.39.z78 strip-off review: the old strip leg (an option row's strip mine) is a strip way too
        if (!io.github.mojolowjo.entropybot.aa.brain.StripWays.on()) return "error: " + io.github.mojolowjo.entropybot.aa.brain.StripWays.OFF;
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
        Car.Status st = vein.poll(p);
        if (st.state() != Car.State.RUNNING) vein = null;
        return st;
    }

    // ---- 0.39.z78 J6b run EF: the walk to a known cave's lip - its entry within reach, never a goto into the opening ----

    /** The running lip walk's hold (null: none). */
    private io.github.mojolowjo.entropybot.cave.ExploreRules.TargetHold.Held lip;
    /** Run EL: the lip step's goal - the fork's reach goal on the entry (null: its cube's chunks not all loaded yet). */
    private baritone.api.pathing.goals.GoalReachBlock lipGoal;
    /** Run EL: the lip step's named fact found at its start (said by the first status). */
    private String lipFail;
    private long lipGoalAt;

    /**
     * The lip walk's start. Run EL (rules 2, 23 (2)): the step's ONE "arrived" reader is its goal - the fork's
     * GoalReachBlock on the entry cell (a standable cell, not the entry nor the cell over it, the entry's centre within
     * reach of the eye, a face of it seen), built on the game thread from the loaded world; the step is done exactly when
     * the goal holds the feet ({@link io.github.mojolowjo.entropybot.cave.ExploreRules#lip}). The reach goal's cube not
     * loaded yet: an approach toward the entry's column (GoalXZ), never done; loaded and empty: the named fact
     * ("no standable cell within reach and sight of the entry") as the opening's source fact for the order and the world
     * (run EY, FactChange.lipFailed), so the plan prices the cave's next opening or the next cave. The same lip test
     * ({@link LipGoals}) is asked by the cave-way snapshot before any walk (run EY: the price had asked a stand-by goal).
     * The cave way's lease (breaking and placing as priced). Loader notes: the fork's GoalReachBlock / GoalXZ (api),
     * Baritone's custom goal process; vanilla Level#clip, Level#isLoaded, BlockState#getCollisionShape.
     */
    private String toCave(LocalPlayer p, Step.ToCave t) {
        int[] me = Jobs.here(p), at = {t.x(), t.y(), t.z()};
        IBaritone b = Jobs.baritone();
        if (b == null) return "error: " + t.text() + ": baritone not loaded";
        lipFail = null;
        lipGoal = reachGoal(p, at);
        lipGoalAt = Core.INSTANCE.tick();
        switch (io.github.mojolowjo.entropybot.cave.ExploreRules.lip(lipGoalOf(lipGoal), me)) {
            case ARRIVED -> { instant = "ok: at the cave's lip at " + Jobs.fmt(me) + " (in the reach goal: its entry " + Jobs.fmt(at) + " in reach and in sight)"; return null; }
            case NO_CELL -> { lipFail = io.github.mojolowjo.entropybot.cave.ExploreRules.noLipCell(at); return null; }
            default -> { }
        }
        b.getCustomGoalProcess().setGoalAndPath(lipGoal != null ? lipGoal : new baritone.api.pathing.goals.GoalXZ(at[0], at[2]));
        lip = io.github.mojolowjo.entropybot.cave.ExploreRules.TargetHold.start(new io.github.mojolowjo.entropybot.cave.ExploreRules.Target(t.x(), t.z()), Core.INSTANCE.tick(), me,
                io.github.mojolowjo.entropybot.baritone.BaritoneHook.calcFailed());
        LOG.info("[entropybot] cave: to the lip of the known cave at {} ({})", Jobs.fmt(at), lipGoal != null
                ? "the reach goal: " + lipGoal.size() + " standable cells its entry is in reach and in sight from"
                : "toward its column until the reach goal's cells are loaded (an approach: never done)");
        return null;
    }

    /** Run EL / run EY: the fork's reach goal on the entry, the ONE lip test ({@link LipGoals}); null while its cube is not loaded. */
    private static baritone.api.pathing.goals.GoalReachBlock reachGoal(LocalPlayer p, int[] at) { return LipGoals.reachGoal(p, at); }

    /** The reach goal as the pure lip rule reads it (null: none yet). */
    private static io.github.mojolowjo.entropybot.cave.ExploreRules.LipGoal lipGoalOf(baritone.api.pathing.goals.GoalReachBlock g) { return LipGoals.lipGoalOf(g); }

    /** A cell a player can stand in (the one test, {@link LipGoals#standable}; the branch gotos' reach goals use it too). */
    private static boolean standable(net.minecraft.world.level.Level lv, int x, int y, int z) { return LipGoals.standable(lv, x, y, z); }

    /**
     * Run EL: done exactly when the reach goal holds the feet (its one arrived reader); its cells loaded and none: the
     * named fact; Baritone's no progress: the walk's fact - both FactChange.walkShort on the ENTRY, so the planner tries
     * the cave's next opening. The goal is built again once a second while Baritone idles (the world may have changed).
     */
    private Car.Status toCaveStatus(LocalPlayer p, Step.ToCave t) {
        if (instant != null) return Car.Status.done(instant);
        int[] me = Jobs.here(p), at = {t.x(), t.y(), t.z()};
        if (lipFail != null) return lipNoCell(at, me, Jobs.baritone());
        IBaritone b = Jobs.baritone();
        if (b == null || lip == null) return Car.Status.failed(t.text() + ": no walk state");
        boolean due = Core.INSTANCE.tick() - lipGoalAt >= 20;                   // structural: once a second in ticks
        if (due && (lipGoal == null || Jobs.idle(b))) {
            lipGoal = reachGoal(p, at);
            lipGoalAt = Core.INSTANCE.tick();
            if (lipGoal != null && !lipGoal.isEmpty() && !lipGoal.isInGoal(me[0], me[1], me[2])) b.getCustomGoalProcess().setGoalAndPath(lipGoal);
            else if (lipGoal == null && Jobs.idle(b)) b.getCustomGoalProcess().setGoalAndPath(new baritone.api.pathing.goals.GoalXZ(at[0], at[2]));
        }
        switch (io.github.mojolowjo.entropybot.cave.ExploreRules.lip(lipGoalOf(lipGoal), me)) {
            case ARRIVED -> {
                Jobs.cancel(b);
                lip = null;
                LOG.info("[entropybot] cave: at the lip of the known cave at {} (feet {}, in the reach goal: its entry in reach and in sight)", Jobs.fmt(at), Jobs.fmt(me));
                return Car.Status.done("ok: at the cave's lip at " + Jobs.fmt(me) + ", its entry " + Jobs.fmt(at) + " in reach and in sight");
            }
            case NO_CELL -> { return lipNoCell(at, me, b); }
            default -> { }
        }
        lip = io.github.mojolowjo.entropybot.cave.ExploreRules.TargetHold.look(lip, Core.INSTANCE.tick(), me, io.github.mojolowjo.entropybot.baritone.BaritoneHook.calcFailed(),
                b.getPathingBehavior().getCurrent() != null, at, StepSignalsLive.noWay(b));   // J6c run ET / run HB site1: an exhausted search, or a budget stop whose best stayed at the feet
        if (lip.state() == io.github.mojolowjo.entropybot.cave.ExploreRules.TargetHold.State.FAILED) {
            String why = "the walk to the cave's lip at " + Jobs.fmt(at) + ": " + lip.why();
            lip = null;
            Jobs.cancel(b);
            // run EY (rule 23 (1)): the opening's own source fact - held for the order and, as a world fact, for every order
            // (was walkShort: the running order's walk-short fact under a key the cave way never read, run EF (3)'s claim false)
            io.github.mojolowjo.entropybot.aa.moves.FactChange.lipFailed(at, me, why);
            return Car.Status.failed(why);
        }
        return Car.Status.running("to the cave's lip at " + Jobs.fmt(at) + (lipGoal == null ? " (approaching: the reach goal's cells not loaded yet)" : ""));
    }

    /**
     * Run EL: the reach goal has no cell - the named fact. Run EY (rule 23 (1)): the opening's own source fact
     * (FactChange.lipFailed), held for the order and for every order as a world fact, so the planner prices the cave's next
     * opening or the next cave (was: a walk-short fact of the running order alone, and this line's "the next opening is tried"
     * was false - five orders ran the same lip twice each).
     */
    private Car.Status lipNoCell(int[] at, int[] me, IBaritone b) {
        String why = io.github.mojolowjo.entropybot.cave.ExploreRules.noLipCell(at) + " (the fork's reach goal, its cells loaded)";
        lipFail = null;
        lip = null;
        lipGoal = null;
        if (b != null) Jobs.cancel(b);
        LOG.info("[entropybot] cave: {} - held for every order; the next opening or the next cave is priced", why);
        io.github.mojolowjo.entropybot.aa.moves.FactChange.lipFailed(at, me, why);
        return Car.Status.failed(why);
    }

    // ---- 0.39.z78 J6b (D12): the caving step - a KNOWN cave walked on its record (the one store), Baritone the car ----

    /** The running caving step's state (null: none). */
    private static final class Caving {
        Step.Cave step;
        String dim;
        long startMs, version = -1;
        int mined, oreSeen, seenAtStart;
        int[] goal;
        String goalWhy;
        io.github.mojolowjo.entropybot.cave.ExploreRules.TargetHold.Held hold;
        /** Run EN: the walk's reach goal (its one arrived reader), the cells it is over, its drop key, when last set. */
        baritone.api.pathing.goals.Goal reach;
        List<int[]> reachCells = List.of();
        /** d0682da0 review: each part of the goal (its own reach goal), the same index as reachCells. */
        List<io.github.mojolowjo.entropybot.cave.ExploreRules.LipGoal> reachParts = List.of();
        long goalKey;
        long goalAt;
        /** The stall site probe (runs GZ / HB): the tick this goto was set (goalAt moves with each re-set), the last site dump. */
        long holdSince, siteAt = -1;
        /** Run EO: the record's seen cells when the goal was set (an arrival that adds none drops the goto). */
        int seenAtGoal;
        /** Run EN: the gotos dropped in this step (an empty reach goal, no path) and their reasons, said at the end. */
        final java.util.Set<Long> dropped = new java.util.HashSet<>();
        final List<String> drops = new ArrayList<>();
        /** J6d (run EV): the goal walks to a seen ore (its arrival mines it; never dropped "arrived, nothing new"). */
        boolean goalOre;
        /** J6d: the seen ore whose vein FAILED with the ore still there - left out of the next moves, said at the end (rule 7). */
        final java.util.Set<Long> veinFailed = new java.util.HashSet<>();
        /** Run GT probe: the ore hits said once as rejected by the record's feed (debug.stepProbe). */
        final java.util.Set<Long> oreRejected = new java.util.HashSet<>();
        VeinMine vein;
        List<int[]> veinCells;
        int[] veinStart;
        String fam;
        /** D15: the passage walked to (its cells seen on arrival), null: the goal is not a passage. */
        io.github.mojolowjo.entropybot.cave.Passages.Passage goalPassage;
        /** D15: the open passages at the last pick (-1: not measured), for the end line and the budget fact. */
        int passagesLeft = -1;
        /** D15 pass two: the lip line said once. */
        boolean lipSaid;
        /** D15 pass three: the capped passage ends stood at (the flood went on from there), and the passage cells looked at / walked past. */
        final java.util.Set<Long> reflooded = new java.util.HashSet<>();
        int looked, walkedPast;
    }

    private Caving caving;

    /**
     * D15 (run JAD's flip loop): the running caving step's current walk target distance from the feet, NaN: none (no
     * caving step, or it mines / picks). Read by the adapter's seen ask (AaRuntime.seenTick, CaveWalk#seenRepricesCave).
     */
    private static volatile double caveTargetDist = Double.NaN;

    /** D15: see {@link #caveTargetDist}. */
    public static double caveTargetDist() { return caveTargetDist; }

    /**
     * Starts the caving step at the known cave's entry (the chain's walk brought the bot there): its record's cells from
     * the cave index, the "cave: enters" line. Rule 15: sightings not loaded, or no known cave there, is a named error.
     * Loader notes: Baritone's custom goal process (GoalNear), VeinMine (Baritone's MineProcess), vanilla Level reads.
     */
    private String caveStart(LocalPlayer p, Step.Cave k) {
        var pass = io.github.mojolowjo.entropybot.sense.ChunkPass.INSTANCE;
        var sg = pass.sightings();
        String dim = pass.dim();
        if (sg == null || dim == null) return "error: " + k.text() + ": the sightings are not loaded yet (rule 15)";
        var f = sg.caveFacts(dim, k.x(), k.y(), k.z());
        if (f == null) return "error: " + k.text() + ": no known cave there (aa cave list)";
        if (Jobs.baritone() == null) return "error: " + k.text() + ": baritone not loaded";
        caving = new Caving();
        caving.step = k;
        caving.dim = dim;
        caving.startMs = System.currentTimeMillis();
        caving.fam = io.github.mojolowjo.entropybot.sense.KnownOre.familyOf(k.family());
        f = sg.caveFacts(dim, k.x(), k.y(), k.z());
        caving.seenAtStart = f.cellCount() - f.unseen();
        // D15 pass two: while the step runs the planner's cave price holds (the record's passage list as the step finds it
        // first); the step's end fact re-opens it (caveEnd)
        sg.caveHold(dim, k.x(), k.y(), k.z(), true);
        LOG.info("[entropybot] cave: the caving step enters the known cave {} for {} {} ore ({} min at most): {}", f.x() + " " + f.y() + " " + f.z(), k.n(), caving.fam,
                k.minutes(), f.line());
        return null;
    }

    private Car.Status caveStatus(LocalPlayer p) {
        Caving cv = caving;
        if (cv == null) return Car.Status.failed("the caving step has no state");
        var pass = io.github.mojolowjo.entropybot.sense.ChunkPass.INSTANCE;
        var sg = pass.sightings();
        var idx = pass.caveIndex();
        Step.Cave k = cv.step;
        int[] me = Jobs.here(p);
        long nowMs = System.currentTimeMillis();
        // run EK: the record's cells come from ChunkPass's one budgeted feeder (Sightings.caveFeed); the step only reads it
        // D15 pass three (run JAK: 234 cells "seen" by a 3 s walk): seen is looked at - the stand's cells in the eyes' cone
        // and the ore rays' range (seen.coneDeg, seen.range), as the rays cover them; a cell walked past behind the back is not
        var eye = p.getEyePosition();
        var lookV = p.getLookAngle();
        sg.caveStood(cv.dim, k.x(), k.y(), k.z(), me[0], me[1], me[2], nowMs, (x, y, z) -> io.github.mojolowjo.entropybot.sense.Seen.inRange(eye.x, eye.y, eye.z, x, y, z)
                && io.github.mojolowjo.entropybot.sense.Seen.inCone(eye.x, eye.y, eye.z, lookV.x, lookV.y, lookV.z, x, y, z));
        // the ore seen round the feet (by sight: the ore index under aa fair) beside the cave's air goes into the record
        // run GT fix (2026-11-01): "beside the cave" is the record's own cells (Sightings.caveBeside) - never CaveIndex.volumeAt(entry),
        // which is -1 once the entry's chunk unloads (the index keeps loaded chunks only): 115 blocks in, every ore was rejected
        boolean probe = StepProbe.on();
        // run GT (2026-11-01: the record held iron 0 left while the sightings saw 24 iron veins, several within 5 blocks of
        // the feet for minutes): with the probe on, the page is read without the fair filter and every rejected hit is said
        // once per cell with its reason - not fair, no family, no root, not beside the volume (the six neighbours' volumes)
        for (var h : pass.nearest(cv.dim, id -> true, me[0], me[1], me[2], io.github.mojolowjo.entropybot.sense.Seen.range(), 32,   // D15 pass three: the rays' own range (every sighting in the cave is a face); structural: a page of hits
                probe ? null : (x, y, z) -> Mining.fairOk(cv.dim, x, y, z))) {
            String why = null;
            if (probe && !Mining.fairOk(cv.dim, h.x(), h.y(), h.z())) why = "not fair (not seen by sight)";
            else if (h.family() == null) why = "no ore family for " + h.id();
            else if (!sg.caveBeside(cv.dim, k.x(), k.y(), k.z(), h.x(), h.y(), h.z())) why = "not beside the cave's cells (the index's neighbour volumes " + neighbourVolumes(idx, h.x(), h.y(), h.z()) + ", the entry's " + idx.volumeAt(k.x(), k.y(), k.z()) + ")";
            if (why != null) {
                if (probe && cv.oreRejected.add(io.github.mojolowjo.entropybot.sense.Sightings.cell(h.x(), h.y(), h.z())))
                    LOG.info("[entropybot] probe cave ore: t={} feet {} | {} {} at {} {} {} ({} away) rejected: {}", Core.INSTANCE.tick(), Jobs.fmt(me), h.family(), h.id(), h.x(), h.y(), h.z(),
                            String.format(java.util.Locale.ROOT, "%.1f", Math.sqrt(h.d2())), why);
                continue;
            }
            if (sg.caveOre(cv.dim, k.x(), k.y(), k.z(), io.github.mojolowjo.entropybot.sense.Sightings.cell(h.x(), h.y(), h.z()), h.family())) cv.oreSeen++;
        }
        IBaritone b = Jobs.baritone();
        if (b == null) return caveEnd(io.github.mojolowjo.entropybot.cave.CaveWalk.End.NO_PROGRESS, "baritone not loaded");
        if (cv.vein != null) {
            Car.Status st = cv.vein.poll(p);                                   // run HD: the vein's own no-progress clock ends a mine on an ore Baritone cannot reach
            if (st.state() == Car.State.RUNNING) return Car.Status.running("caving: mining the seen ore (" + cv.mined + " of " + k.n() + ")");
            // the step's own count of its vein; the record hears every broken ore from ChunkPass.blockChanged (run ER: the
            // one reader, rule 23 (2) - a capped list never decides what the record knows)
            for (int[] c : cv.veinCells) {
                BlockPos q = new BlockPos(c[0], c[1], c[2]);
                if (p.level().isLoaded(q) && !io.github.mojolowjo.entropybot.sense.KnownOre.family(BuiltInRegistries.BLOCK.getKey(p.level().getBlockState(q).getBlock()).toString(), cv.fam))
                    cv.mined++;
            }
            if (st.state() == Car.State.FAILED) {
                LOG.info("[entropybot] cave: the vein at {} ended: {}", Jobs.fmt(cv.veinCells.get(0)), st.why());
                // J6d: a seen ore in reach is mined, never skipped - unless its own vein failed with the ore still there
                // (else the next move mines it again, forever): left out, said (rule 7)
                int[] s0 = cv.veinStart;
                BlockPos q0 = s0 == null ? null : new BlockPos(s0[0], s0[1], s0[2]);
                if (q0 != null && p.level().isLoaded(q0) && io.github.mojolowjo.entropybot.sense.KnownOre.family(BuiltInRegistries.BLOCK.getKey(p.level().getBlockState(q0).getBlock()).toString(), cv.fam)) {
                    cv.veinFailed.add(io.github.mojolowjo.entropybot.sense.Sightings.cell(s0[0], s0[1], s0[2]));
                    cv.drops.add("the seen ore at " + Jobs.fmt(s0) + ": its vein failed (" + st.why() + ")");
                    LOG.info("[entropybot] cave: the seen {} ore at {} is left out of this step: its vein failed ({})", cv.fam, Jobs.fmt(s0), st.why());
                }
            }
            cv.vein = null;
            cv.veinCells = null;
            cv.veinStart = null;
        }
        if (cv.reach != null && oreInReach(sg, cv, k, me)) {
            // D15: a seen ore of the family came within the mine's reach on the walk: the walk pauses (not dropped), the
            // next move mines it; the passage is picked again after, by its price. Pass three: the mine's sight
            // (Step.Collect.MINE_SIGHT; the fork's mineWith walks to it), not the arm's reach
            LOG.info("[entropybot] cave: a seen {} ore is in reach on the walk to {} - mined first", cv.fam, cv.goalWhy);
            Jobs.cancel(b);
            clearGoal(cv);
        }
        caveTargetDist = cv.reach != null && cv.goal != null ? Math.sqrt(sq(cv.goal[0] - me[0]) + sq(cv.goal[1] - me[1]) + sq(cv.goal[2] - me[2])) : Double.NaN;
        if (cv.reach != null) {
            // run EN (rules 2, 23 (2)): the walk's one arrived reader is its reach goal; the cells it is over are then in
            // reach and in sight - seen (the record's caveSaw), so the frontier moves on
            if (cv.reach.isInGoal(me[0], me[1], me[2])) {
                arrived(sg, cv, k, me);
            } else {
                cv.hold = io.github.mojolowjo.entropybot.cave.ExploreRules.TargetHold.look(cv.hold, Core.INSTANCE.tick(), me, io.github.mojolowjo.entropybot.baritone.BaritoneHook.calcFailed(),
                        b.getPathingBehavior().getCurrent() != null, io.github.mojolowjo.entropybot.cave.ExploreRules.NEVER_REACHED, StepSignalsLive.noWay(b));   // J6c run ET / run HB site1: an exhausted search, or a budget stop whose best stayed at the feet
                if (cv.hold.state() == io.github.mojolowjo.entropybot.cave.ExploreRules.TargetHold.State.FAILED) {
                    dropGoto(cv, cv.goalKey, cv.goalWhy, "the walk to it: " + cv.hold.why());
                    Jobs.cancel(b);
                } else {
                    if (Jobs.idle(b) && Core.INSTANCE.tick() - cv.goalAt >= 20) {           // structural: once a second in ticks
                        if (StepProbe.on()) {
                            LOG.info("[entropybot] probe cave: {}", caveHoldLine(b, cv, me));   // the stall probe (to-do/cave-walk-probe.md): Baritone's state before each re-set
                            long t = Core.INSTANCE.tick();
                            if (t - cv.holdSince >= SITE_AFTER_TICKS && (cv.siteAt < 0 || t - cv.siteAt >= SITE_EVERY_TICKS)) {   // runs GZ / HB: the site, 5 s into a hold
                                for (String line : caveSiteLines(p, b, cv, me)) LOG.info("[entropybot] probe cave site: {}", line);
                                cv.siteAt = t;
                            }
                        }
                        b.getCustomGoalProcess().setGoalAndPath(cv.reach);
                        cv.goalAt = Core.INSTANCE.tick();
                    }
                    return Car.Status.running("caving: walking to " + cv.goalWhy + " (" + cv.mined + " of " + k.n() + " ore)");
                }
            }
        }
        // J6d (run EV: "in sight from here" set no goal): a goal already in reach and sight is seen at once and the next move
        // is picked in the same tick, a few passes at most
        for (int move = 0; move < IN_SIGHT_PASSES; move++) {
            Car.Status st = caveMove(p, cv, sg, k, me, b, nowMs);
            if (st != null) return st;
        }
        return Car.Status.running("caving: " + IN_SIGHT_PASSES + " goals were in sight from here this tick; the next is picked next tick");
    }

    /**
     * J6d: each pass builds at most CaveWalk.REACH_CELLS reach goals (~10,000 cell tests with a sight ray each, on the game
     * thread), so a tick takes a few.
     */
    private static final int IN_SIGHT_PASSES = 4; // structural: the passes one tick holds (the reach goals' cost on the game thread)

    /**
     * One move of the caving step: the next move (CaveWalk.next) acted on - its status, or null when the move was over at
     * once (a goal in reach and sight already, a goto dropped, a seen ore gone): the caller picks the next one.
     */
    private Car.Status caveMove(LocalPlayer p, Caving cv, io.github.mojolowjo.entropybot.sense.Sightings sg, Step.Cave k, int[] me, IBaritone b, long nowMs) {
        var f = sg.caveFacts(cv.dim, k.x(), k.y(), k.z());
        double minutes = (nowMs - cv.startMs) / 60000.0;                       // structural: ms a minute
        List<int[]> left = new ArrayList<>(sg.caveLeft(cv.dim, k.x(), k.y(), k.z(), cv.fam));
        left.removeIf(c -> cv.veinFailed.contains(io.github.mojolowjo.entropybot.sense.Sightings.cell(c[0], c[1], c[2])));
        // D15: the cave read as passages from the feet (the record's own cells), their ore faces from the seen ore left
        java.util.Set<Long> oreCells = new java.util.HashSet<>();
        for (int[] c : left) oreCells.add(io.github.mojolowjo.entropybot.sense.Sightings.cell(c[0], c[1], c[2]));
        var found = sg.cavePassages(cv.dim, k.x(), k.y(), k.z(), me, oreCells, Tuning.i("cave.passageDrop"), Tuning.i("cave.passageForkLayers"), Tuning.i("cave.passageCells"),
                Tuning.i("cave.passageRoomWidth"), Tuning.i("cave.passageLipNear"));   // pass two: rooms, and the lip floods from cave air near the feet
        cv.passagesLeft = io.github.mojolowjo.entropybot.cave.CaveWalk.openPassages(found.passages());
        if (found.why() != null && cv.drops.stream().noneMatch(d -> d.startsWith("passages: "))) {
            cv.drops.add("passages: " + found.why());                          // rule 7: said once at the end
            LOG.info("[entropybot] cave: passages: {} - the step walks to the cave's mouth {} {} {}", found.why(), k.x(), k.y(), k.z());
        }
        if (found.lip() && !cv.lipSaid) {
            cv.lipSaid = true;
            LOG.info("[entropybot] cave: passages: the feet {} are outside the cave's air; the flood starts at the cave cell {} (cave.passageLipNear {})", Jobs.fmt(me), Jobs.fmt(found.start()), Tuning.i("cave.passageLipNear"));
        }
        var next = io.github.mojolowjo.entropybot.cave.CaveWalk.next(cv.mined, k.n(), minutes, k.minutes(), f, found.passages(),
                left, me, io.github.mojolowjo.entropybot.aa.moves.Step.Collect.MINE_SIGHT,
                new int[]{k.x(), k.y(), k.z()}, cv.dropped, found.capped(), cv.reflooded);   // pass three: the capped flood goes on from its farthest ends     // pass two: the mouth, when no passage is found from the feet; run EN: the dropped passed over
        switch (next) {
            case io.github.mojolowjo.entropybot.cave.CaveWalk.Stop s -> {
                return caveEnd(s.end(), s.why() + (cv.drops.isEmpty() ? "" : "; dropped: " + String.join("; ", cv.drops)));
            }
            case io.github.mojolowjo.entropybot.cave.CaveWalk.Wait w -> { return Car.Status.running("caving: " + w.why()); }
            case io.github.mojolowjo.entropybot.cave.CaveWalk.Mine m -> {
                var level = p.level();
                List<io.github.mojolowjo.entropybot.clear.Pos> cells = new ArrayList<>();
                cv.veinCells = new ArrayList<>();
                for (int[] cell : io.github.mojolowjo.entropybot.cave.MineRules.vein(m.cell(), n -> {
                    BlockPos bq = new BlockPos(n[0], n[1], n[2]);
                    return level.isLoaded(bq) && io.github.mojolowjo.entropybot.sense.KnownOre.family(BuiltInRegistries.BLOCK.getKey(level.getBlockState(bq).getBlock()).toString(), cv.fam);
                }, io.github.mojolowjo.entropybot.cave.MineRules.atVein())) { cells.add(new io.github.mojolowjo.entropybot.clear.Pos(cell[0], cell[1], cell[2])); cv.veinCells.add(cell); }
                if (cells.isEmpty()) {
                    // the seen ore is gone (mined by someone, or never there): the record says so, the next move is picked.
                    // run ER: MineRules.vein tests its start cell now, so this fires; the same marking as a heard break
                    // (Sightings.oreBroken: a change not heard - the chunk unloaded then - is the world read now)
                    sg.oreBroken(cv.dim, m.cell()[0], m.cell()[1], m.cell()[2]);
                    LOG.info("[entropybot] cave: the seen {} ore at {} is no longer there", cv.fam, Jobs.fmt(m.cell()));
                    return null;                                                         // the next move, this tick
                }
                cv.veinStart = m.cell().clone();
                cv.vein = new VeinMine(cells, "cave: mine " + cv.fam + " at " + Jobs.fmt(m.cell()));
                String bad = cv.vein.begin(level);
                if (bad != null) { cv.vein = null; return caveEnd(io.github.mojolowjo.entropybot.cave.CaveWalk.End.NO_PROGRESS, "the vein at " + Jobs.fmt(m.cell()) + ": " + bad); }
                return Car.Status.running("caving: mining the seen ore at " + Jobs.fmt(m.cell()));
            }
            case io.github.mojolowjo.entropybot.cave.CaveWalk.Goto g when g.isPassage() -> {
                // D15 (rule 2): a passage's far end is a walkable cell of the flood; the goal is a GoalNear round it
                // (cave.passageNear) - Baritone picks the stand and the path; the arrival sees the passage's cells
                cv.goal = g.cell().clone();
                cv.goalWhy = g.why();
                cv.goalKey = g.key();
                cv.goalOre = false;
                cv.goalPassage = g.passage();
                cv.reachCells = List.of();
                cv.reachParts = List.of();
                cv.seenAtGoal = f == null ? 0 : f.cellCount() - f.unseen();
                cv.reach = new GoalNear(new BlockPos(g.cell()[0], g.cell()[1], g.cell()[2]), Tuning.i("cave.passageNear"));
                if (cv.reach.isInGoal(me[0], me[1], me[2])) {
                    LOG.info("[entropybot] cave: {} - at its far end already, the next move is picked now", g.why());
                    arrived(sg, cv, k, me);
                    return null;
                }
                cv.hold = io.github.mojolowjo.entropybot.cave.ExploreRules.TargetHold.start(new io.github.mojolowjo.entropybot.cave.ExploreRules.Target(g.cell()[0], g.cell()[2]),
                        Core.INSTANCE.tick(), me, io.github.mojolowjo.entropybot.baritone.BaritoneHook.calcFailed());
                b.getCustomGoalProcess().setGoalAndPath(cv.reach);
                cv.goalAt = Core.INSTANCE.tick();
                cv.holdSince = cv.goalAt;
                cv.siteAt = -1;
                LOG.info("[entropybot] cave: to {} - GoalNear {} round its far end ({} of {} ore mined, {} open passage(s))", g.why(), Tuning.i("cave.passageNear"), cv.mined, k.n(), cv.passagesLeft);
                return Car.Status.running("caving: walking " + g.why());
            }
            case io.github.mojolowjo.entropybot.cave.CaveWalk.Goto g -> {
                // run EN (rule 2): the goal is a stand Baritone picks - a reach goal (the fork's GoalReachBlock) over each
                // of the goto's cells, one goal for all (GoalComposite); never the cell itself (EN: a mid-air frontier cell)
                List<baritone.api.pathing.goals.Goal> parts = new ArrayList<>();
                List<int[]> partCells = new ArrayList<>();
                List<io.github.mojolowjo.entropybot.cave.ExploreRules.LipGoal> partGoals = new ArrayList<>();
                int stands = 0, tried = 0;
                List<int[]> candidates = g.cells();
                int carNo0 = LipGoals.CAR_SAID_NO.get();                            // run HB site3 probe: the car's stand refusals this build
                for (int[] c : candidates) {
                    if (parts.size() >= io.github.mojolowjo.entropybot.cave.CaveWalk.REACH_CELLS || tried >= io.github.mojolowjo.entropybot.cave.CaveWalk.REACH_TRIES) break;
                    tried++;
                    var r = new baritone.api.pathing.goals.GoalReachBlock(new BlockPos(c[0], c[1], c[2]), io.github.mojolowjo.entropybot.cave.ExploreRules.OPENING_REACH, 1.62,   // game fact: eye height 1.62
                            (x, y, z) -> standable(p.level(), x, y, z), (x, y, z) -> seesCell(p, x, y, z, c));
                    if (r.isEmpty()) continue;
                    parts.add(r);
                    partCells.add(c);
                    partGoals.add(lipGoalOf(r));
                    stands += r.size();
                }
                String carNo = StepProbe.on() ? " (the car refused " + (LipGoals.CAR_SAID_NO.get() - carNo0) + " stand(s) the mod's test took)" : "";   // run HB site3 probe
                if (parts.isEmpty()) {
                    dropGoto(cv, g.key(), g.why(), "no standable cell within reach and sight of its " + g.cells().size() + " cell(s)" + carNo);
                    return null;                                                         // the next one, this tick
                }
                cv.goal = g.cell().clone();
                cv.goalWhy = g.why();
                cv.goalKey = g.key();
                cv.goalOre = g.ore();
                cv.goalPassage = null;
                cv.reachCells = partCells;
                cv.reachParts = partGoals;
                cv.seenAtGoal = f == null ? 0 : f.cellCount() - f.unseen();
                cv.reach = parts.size() == 1 ? parts.get(0) : new baritone.api.pathing.goals.GoalComposite(parts.toArray(new baritone.api.pathing.goals.Goal[0]));
                if (cv.reach.isInGoal(me[0], me[1], me[2])) {                            // in reach and sight already: seen, the next move this tick (J6d)
                    LOG.info("[entropybot] cave: {} is in reach and sight from here - the next move is picked now", g.why());
                    arrived(sg, cv, k, me);
                    return null;
                }
                cv.hold = io.github.mojolowjo.entropybot.cave.ExploreRules.TargetHold.start(new io.github.mojolowjo.entropybot.cave.ExploreRules.Target(g.cell()[0], g.cell()[2]),
                        Core.INSTANCE.tick(), me, io.github.mojolowjo.entropybot.baritone.BaritoneHook.calcFailed());
                if (StepProbe.on()) {
                    // the stall probe (to-do/cave-walk-probe.md, the peer's line): the goto's first cell, how many cells it has, the
                    // parts kept with the stands each, the feet, Baritone's state before this goal is set - so a drop or a stall
                    // reads as geometry (few stands) or as pathing (stands, yet no calc)
                    StringBuilder per = new StringBuilder();
                    for (int i = 0; i < parts.size(); i++) per.append(i == 0 ? "" : ", ").append(Jobs.fmt(partCells.get(i))).append(" x").append(((baritone.api.pathing.goals.GoalReachBlock) parts.get(i)).size());
                    LOG.info("[entropybot] probe cave: t={} feet {} | goto {} at {} ({} cells) | parts {} of {} tried kept: {} | before the goal: {}",
                            Core.INSTANCE.tick(), Jobs.fmt(me), g.why(), Jobs.fmt(g.cell()), g.cells().size(), parts.size(), tried, per, caveHoldLine(b, cv, me));
                }
                b.getCustomGoalProcess().setGoalAndPath(cv.reach);
                cv.goalAt = Core.INSTANCE.tick();
                cv.holdSince = cv.goalAt;
                cv.siteAt = -1;
                LOG.info("[entropybot] cave: to {} - the reach goal over {} cell(s) of {} tried: {} standable cells in reach and sight ({} of {} ore mined){}", g.why(), parts.size(), tried, stands, cv.mined, k.n(), carNo);
                return Car.Status.running("caving: walking to " + g.why());
            }
        }
    }

    /**
     * Run EO (rule 23 (1); run EO 05:13:14-44: "at the branch at -51 51 81" 611 times, the feet still, nothing new seen -
     * its frontier cell sat at the stand's SEE_STEPS edge): an arrival that adds no seen cell to the record (since the goal
     * was set) drops that goto "arrived, nothing new" - the same list as the no-path drop - and the next one is walked.
     * J6d (run EV): branch and unseen-cell gotos only; a walk to a seen ore arrives in its reach, and the next move mines it.
     */
    /**
     * The stall probe (to-do/cave-walk-probe.md; runs GD/GJ: the feet still for 30-40 s with a reach goal 5 blocks away set and
     * "calc none"): Baritone's state as the caving step sees it - who is in control, the custom goal process active or not, a
     * path executing or not, a calc in progress or not, the fork's last calc (ICustomGoalProcess.lastCalc), the last CALC_FAILED
     * tick, the hold's state, the ticks since the goal was last set. Read-only; behind debug.stepProbe. Rule 15: a part this fork
     * cannot give is said so, not guessed.
     */
    private static String caveHoldLine(IBaritone b, Caving cv, int[] me) {
        var pb = b.getPathingBehavior();
        String control = b.getPathingControlManager().mostRecentInControl().map(pr -> pr.displayName()).orElse("none");
        String calc;
        try {
            var wc = b.getCustomGoalProcess().lastCalc();
            calc = wc == null ? "none" : wc.line();
        } catch (LinkageError e) {
            calc = "not readable from this fork: " + e;
        }
        String event = io.github.mojolowjo.entropybot.baritone.BaritoneHook.lastCalc();
        long t = Core.INSTANCE.tick();
        return "t=" + t + " feet " + Jobs.fmt(me) + " | goal " + cv.goalWhy + " set " + (t - cv.goalAt) + " ticks ago | in control " + control
                + " | custom goal active " + b.getCustomGoalProcess().isActive() + " | goal in process " + pb.getGoal()
                + " | path " + (pb.getCurrent() != null ? "executing" : "none") + " | calc " + (pb.getInProgress().isPresent() ? "in progress" : "none")
                + " | fork last calc " + calc + " | last path event " + (event == null ? "none since the hook was wired" : event)
                + " | CALC_FAILED count " + io.github.mojolowjo.entropybot.baritone.BaritoneHook.calcFailed()
                + " | hold " + (cv.hold == null ? "-" : cv.hold.state() + " " + cv.hold.why());
    }

    /** structural: the site lines 5 s into a hold (ticks), then every 10 s; the stands said per part; the deepest drop counted */
    private static final int SITE_AFTER_TICKS = 100, SITE_EVERY_TICKS = 200, SITE_STANDS = 4, SITE_DROP_MAX = 8;

    /**
     * The stall site probe (runs GZ / HB, 2026-11-01: the feet still 30-60 s, Baritone's search "stop TIME, best = the
     * feet" every 2 s while the mod's goal had 7-20 standable cells 8-10 blocks away): what surrounds the feet, Baritone's
     * break setting and tool strengths as its own CalculationContext reads them, and per goal part the cell, its stands
     * (the same tests the goal was built from) with the block under each and lava or water beside, and the blocks on the
     * eye's line to the cell. Read-only; behind debug.stepProbe. Rule 15: a part this fork cannot give is said so.
     * Loader notes: Baritone's CalculationContext and ToolSet (the main jar, read-only), vanilla Level reads.
     */
    private static List<String> caveSiteLines(LocalPlayer p, IBaritone b, Caving cv, int[] me) {
        List<String> out = new ArrayList<>();
        var lv = p.level();
        long t = Core.INSTANCE.tick();
        StringBuilder around = new StringBuilder();
        for (int dy = -1; dy <= 2; dy++) for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
            String n = blockAt(lv, me[0] + dx, me[1] + dy, me[2] + dz);
            if (n.equals("air")) continue;
            if (around.length() > 0) around.append(", ");
            around.append(dx).append(' ').append(dy).append(' ').append(dz).append(' ').append(n);
        }
        String lease;
        try {
            var cx = new baritone.pathing.movement.CalculationContext(b);
            lease = "Baritone's context: allowBreak " + cx.allowBreak + ", breakBlockAdditionalCost " + cx.breakBlockAdditionalCost + " | fork lease " + baritone.process.RunLease.describe();
        } catch (RuntimeException | LinkageError e) {
            lease = "Baritone's context not readable from this fork: " + e;
        }
        StringBuilder drops = new StringBuilder();                                  // run HB site1: the feet stood at an edge each time - the drop at each side
        for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
            int k = 0;
            while (k < SITE_DROP_MAX && blockAt(lv, me[0] + d[0], me[1] - 1 - k, me[2] + d[1]).equals("air")) k++;
            if (drops.length() > 0) drops.append(' ');
            drops.append(k >= SITE_DROP_MAX ? SITE_DROP_MAX + "+" : String.valueOf(k));
        }
        out.add("t=" + t + " feet " + Jobs.fmt(me) + " | held " + (t - cv.holdSince) / 20 + " s | around the feet (dx dy dz, not air): "   // game fact: 20 ticks a second
                + (around.length() == 0 ? "all air" : around) + " | drop beside the feet (+x -x +z -z): " + drops + " | " + lease);
        List<baritone.api.pathing.goals.Goal> parts = cv.reach instanceof baritone.api.pathing.goals.GoalComposite gc ? List.of(gc.goals()) : cv.reach == null ? List.of() : List.of(cv.reach);
        java.util.Set<String> kinds = new java.util.TreeSet<>();
        int i = 0;
        for (var g : parts) {
            if (!(g instanceof baritone.api.pathing.goals.GoalReachBlock r)) continue;
            int[] c = {r.x, r.y, r.z};
            double d = Math.sqrt(sq(c[0] - me[0]) + sq(c[1] - me[1]) + sq(c[2] - me[2]));
            List<String> ray = new ArrayList<>();                                   // the eye's line to the cell: the blocks it crosses
            double fx = me[0] + 0.5, fy = me[1] + 1.62, fz = me[2] + 0.5, tx = c[0] + 0.5, ty = c[1] + 0.5, tz = c[2] + 0.5;   // game fact: eye height 1.62
            int steps = (int) Math.ceil(d * 4) + 1;                                 // structural: 4 samples a block along the line
            int lx = Integer.MIN_VALUE, ly = 0, lz = 0;
            for (int s = 0; s <= steps; s++) {
                double f = (double) s / steps;
                int bx = (int) Math.floor(fx + (tx - fx) * f), by = (int) Math.floor(fy + (ty - fy) * f), bz = (int) Math.floor(fz + (tz - fz) * f);
                if (bx == lx && by == ly && bz == lz) continue;
                lx = bx; ly = by; lz = bz;
                String n = blockAt(lv, bx, by, bz);
                ray.add(n);
                kinds.add(n);
            }
            StringBuilder st = new StringBuilder();
            int stands = 0;
            int rr = (int) Math.ceil(r.reach) + 1;
            for (int dx = -rr; dx <= rr; dx++) for (int dz = -rr; dz <= rr; dz++) for (int dy = -rr - 3; dy <= rr; dy++) {   // structural: the goal's own cell box (eye height 1.62 rounds to 3 more below)
                int sx = c[0] + dx, sy = c[1] + dy, sz = c[2] + dz;
                if (!r.isInGoal(sx, sy, sz)) continue;
                stands++;
                if (stands <= SITE_STANDS) {
                    if (st.length() > 0) st.append("; ");
                    st.append(sx).append(' ').append(sy).append(' ').append(sz).append(" on ").append(blockAt(lv, sx, sy - 1, sz));
                    String beside = fluidBeside(lv, sx, sy, sz);
                    if (beside != null) st.append(" (").append(beside).append(")");
                }
            }
            out.add("part " + i + ": cell " + Jobs.fmt(c) + " (" + Math.round(d) + " blocks away, chunk " + (lv.isLoaded(new BlockPos(c[0], c[1], c[2])) ? "loaded" : "NOT loaded")
                    + ") | stands " + stands + (stands > SITE_STANDS ? " (first " + SITE_STANDS + ")" : "") + ": " + st + " | the eye's line to it: " + ProbeLines.runs(ray));
            i++;
        }
        StringBuilder tools = new StringBuilder();                                 // Baritone's own tool strength per block kind on the lines
        try {
            var cx = new baritone.pathing.movement.CalculationContext(b);
            for (String k : kinds) {
                if (k.equals("air") || k.equals("unloaded") || k.contains(" (")) continue;
                var block = BuiltInRegistries.BLOCK.get(net.minecraft.resources.ResourceLocation.withDefaultNamespace(k));
                var state = block.defaultBlockState();
                int slot = cx.toolSet.getBestSlot(block, false);
                var item = p.getInventory().getItem(slot);
                if (tools.length() > 0) tools.append(", ");
                tools.append(k).append(": hardness ").append(state.getDestroySpeed(lv, BlockPos.ZERO)).append(", strength ").append(String.format(java.util.Locale.ROOT, "%.2f", cx.toolSet.getStrVsBlock(state)))
                        .append(" with ").append(item.isEmpty() ? "the hand" : BuiltInRegistries.ITEM.getKey(item.getItem()).getPath()).append(" (hotbar ").append(slot + 1).append(")");
            }
        } catch (RuntimeException | LinkageError e) {
            tools.append("not readable from this fork: ").append(e);
        }
        out.add("tools as Baritone's ToolSet reads them: " + (tools.length() == 0 ? "none (no block kind on the lines)" : tools));
        return out;
    }

    private static double sq(double v) { return v * v; }

    /** The block's short name at a cell, " (lava)" / " (water)" added for a fluid in a non-fluid block; "unloaded" when the chunk is not here. */
    private static String blockAt(net.minecraft.world.level.Level lv, int x, int y, int z) {
        BlockPos q = new BlockPos(x, y, z);
        if (!lv.isLoaded(q)) return "unloaded";
        String n = BuiltInRegistries.BLOCK.getKey(lv.getBlockState(q).getBlock()).getPath();
        var fl = lv.getFluidState(q);
        if (!fl.isEmpty() && !n.equals("water") && !n.equals("lava")) {
            n = n + (BuiltInRegistries.FLUID.getKey(fl.getType()).getPath().contains("lava") ? " (lava)" : " (water)");
        }
        return n;
    }

    /** "lava beside" / "water beside": a fluid in one of the four horizontal neighbours, or under them, or under the cell; null when none. */
    private static String fluidBeside(net.minecraft.world.level.Level lv, int x, int y, int z) {
        String found = null;
        for (int[] d : new int[][]{{1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}, {0, -1, 0}, {1, -1, 0}, {-1, -1, 0}, {0, -1, 1}, {0, -1, -1}}) {
            BlockPos q = new BlockPos(x + d[0], y + d[1], z + d[2]);
            if (!lv.isLoaded(q)) continue;
            var fl = lv.getFluidState(q);
            if (fl.isEmpty()) continue;
            if (BuiltInRegistries.FLUID.getKey(fl.getType()).getPath().contains("lava")) return "lava beside";
            found = "water beside";
        }
        return found;
    }

    private static void arrived(io.github.mojolowjo.entropybot.sense.Sightings sg, Caving cv, Step.Cave k, int[] me) {
        long key = cv.goalKey;
        String what = cv.goalWhy;
        boolean ore = cv.goalOre;
        arrivedAt(sg, cv, k, me);
        var after = sg.caveFacts(cv.dim, k.x(), k.y(), k.z());
        int seenNow = after == null ? cv.seenAtGoal : after.cellCount() - after.unseen();
        if (io.github.mojolowjo.entropybot.cave.CaveWalk.dropsOnNothingNew(ore, cv.seenAtGoal, seenNow))
            dropGoto(cv, key, what, "arrived, nothing new (its stand sees no cell not seen before)");
    }

    /**
     * Run EN: the walk arrived (in its reach goal): the goal is over. d0682da0 review (rule 17): seen are only the cells
     * whose OWN reach goal holds the feet (CaveWalk.seenFrom) - the composite holds when any one part does.
     */
    private static void arrivedAt(io.github.mojolowjo.entropybot.sense.Sightings sg, Caving cv, Step.Cave k, int[] me) {
        List<Long> seen = new ArrayList<>();
        if (cv.goalPassage != null) {
            // D15 pass three (run JAK): arriving is not seeing - the passage's cells count as seen only as the eyes looked
            // at them on the walk (caveStood's cone); the rest are walked past, said, and the passage stays open (walked again)
            var ps = cv.goalPassage;
            int looked = sg.caveSeenOf(cv.dim, k.x(), k.y(), k.z(), ps.cells());
            int past = ps.cells().size() - looked;
            cv.looked += looked;
            cv.walkedPast += past;
            if (ps.endKind() == io.github.mojolowjo.entropybot.cave.Passages.EndKind.CAPPED) cv.reflooded.add(ps.key());   // the flood goes on from here
            LOG.info("[entropybot] cave: at the far end of {} (feet {}): of its {} cell(s) {} looked at, {} walked past unseen{}", cv.goalWhy, Jobs.fmt(me), ps.cells().size(), looked, past,
                    ps.endKind() == io.github.mojolowjo.entropybot.cave.Passages.EndKind.CAPPED ? "; its end was the flood's cap - flooded again from here" : "");
        } else {
            for (int[] c : io.github.mojolowjo.entropybot.cave.CaveWalk.seenFrom(cv.reachParts, cv.reachCells, me))
                seen.add(io.github.mojolowjo.entropybot.sense.Sightings.cell(c[0], c[1], c[2]));
            sg.caveSaw(cv.dim, k.x(), k.y(), k.z(), seen);
            LOG.info("[entropybot] cave: at {} (feet {}, in its reach goal: {} of its {} cell(s) in reach and in sight)", cv.goalWhy, Jobs.fmt(me), seen.size(), cv.reachCells.size());
        }
        clearGoal(cv);
    }

    /** The walk's goal over (arrived, dropped, or paused for an ore in reach). */
    private static void clearGoal(Caving cv) {
        cv.reach = null;
        cv.goal = null;
        cv.hold = null;
        cv.goalPassage = null;
        cv.reachCells = List.of();
        cv.reachParts = List.of();
        caveTargetDist = Double.NaN;
    }

    /** D15: a seen ore of the step's family, not left out, within the mine's reach of the feet. */
    private static boolean oreInReach(io.github.mojolowjo.entropybot.sense.Sightings sg, Caving cv, Step.Cave k, int[] me) {
        List<int[]> left = new ArrayList<>(sg.caveLeft(cv.dim, k.x(), k.y(), k.z(), cv.fam));
        left.removeIf(o -> cv.veinFailed.contains(io.github.mojolowjo.entropybot.sense.Sightings.cell(o[0], o[1], o[2])));
        return io.github.mojolowjo.entropybot.cave.CaveWalk.oreToMine(left, me, io.github.mojolowjo.entropybot.aa.moves.Step.Collect.MINE_SIGHT) != null;
    }

    /** Run EN (rule 23 (1)): a goto dropped for the step with its reason (logged; said at the step's end); the next is picked. */
    private static void dropGoto(Caving cv, long key, String what, String why) {
        cv.dropped.add(key);
        cv.drops.add(what + ": " + why);
        LOG.info("[entropybot] cave: {} dropped: {} - the next one is picked", what, why);
        clearGoal(cv);
    }

    /** Run EN: the eyes at feet cell x y z see the cell - nothing collides between, or the first hit is the cell itself (an ore's face). */
    static boolean seesCell(LocalPlayer p, int x, int y, int z, int[] c) {
        var lv = p.level();
        var from = new net.minecraft.world.phys.Vec3(x + 0.5, y + 1.62, z + 0.5);   // game fact: eye height 1.62
        var to = new net.minecraft.world.phys.Vec3(c[0] + 0.5, c[1] + 0.5, c[2] + 0.5);
        var r = lv.clip(new net.minecraft.world.level.ClipContext(from, to, net.minecraft.world.level.ClipContext.Block.COLLIDER, net.minecraft.world.level.ClipContext.Fluid.NONE, p));
        if (r.getType() == net.minecraft.world.phys.HitResult.Type.MISS) return true;
        return r instanceof net.minecraft.world.phys.BlockHitResult bh && bh.getBlockPos().getX() == c[0] && bh.getBlockPos().getY() == c[1] && bh.getBlockPos().getZ() == c[2];
    }

    /** x y z (an ore block) is beside an air cell of the cave's volume. */
    /** Run GT probe: the six neighbours' volume ids (-1: none), for the "not beside the volume" line. */
    private static String neighbourVolumes(io.github.mojolowjo.entropybot.sense.CaveIndex idx, int x, int y, int z) {
        return idx.volumeAt(x + 1, y, z) + "," + idx.volumeAt(x - 1, y, z) + "," + idx.volumeAt(x, y + 1, z) + "," + idx.volumeAt(x, y - 1, z) + "," + idx.volumeAt(x, y, z + 1) + "," + idx.volumeAt(x, y, z - 1);
    }

    /** The step's end: the walk's minutes and ore into the record, the D12 end line (rule 7); the need met is done, else failed. */
    private Car.Status caveEnd(io.github.mojolowjo.entropybot.cave.CaveWalk.End end, String why) {
        Caving cv = caving;
        caving = null;
        if (cv == null) return Car.Status.failed("the caving step has no state");
        IBaritone b = Jobs.baritone();
        if (b != null) Jobs.cancel(b);
        var sg = io.github.mojolowjo.entropybot.sense.ChunkPass.INSTANCE.sightings();
        Step.Cave k = cv.step;
        double minutes = (System.currentTimeMillis() - cv.startMs) / 60000.0;   // structural: ms a minute
        io.github.mojolowjo.entropybot.sense.CaveRecord.Facts after = null;
        if (sg != null) {
            // D15 pass two: the step's end re-opens the planner's cave price - the step's last passage measure is stored
            sg.caveHold(cv.dim, k.x(), k.y(), k.z(), false);
            var before = sg.caveFacts(cv.dim, k.x(), k.y(), k.z());
            int seenNow = before == null ? cv.seenAtStart : before.cellCount() - before.unseen();
            // review 4: a walk that saw no cell and mined no ore holds the cave until something new is known (no loop); an owner's
            // stop judges nothing (57db13b7 review (b)); FK fix: a stop for a stall is the walk's own verdict (CaveWalk.judged)
            Boolean gained = io.github.mojolowjo.entropybot.cave.CaveWalk.judged(end, why, seenNow > cv.seenAtStart || cv.mined > 0);
            sg.caveWalked(cv.dim, k.x(), k.y(), k.z(), minutes, cv.oreSeen, System.currentTimeMillis(), gained);
            // D15 pass three (run JAK): a DONE cave is held done in its record (not entered again until more of it is known)
            if (end == io.github.mojolowjo.entropybot.cave.CaveWalk.End.DONE) sg.caveDone(cv.dim, k.x(), k.y(), k.z(), why);
            after = sg.caveFacts(cv.dim, k.x(), k.y(), k.z());
        }
        caveTargetDist = Double.NaN;
        String line = io.github.mojolowjo.entropybot.cave.CaveWalk.endLine(k.x() + " " + k.y() + " " + k.z(), end, why, minutes, cv.seenAtStart, after, cv.oreSeen, cv.mined, cv.passagesLeft,
                cv.looked, cv.walkedPast);
        LOG.info("[entropybot] {}", line);
        if (end == io.github.mojolowjo.entropybot.cave.CaveWalk.End.NEED_MET) return Car.Status.done("ok: " + line);
        // GA fix (runs FN, FZ, GA: a cave's end stopped the order - no fact, the run ended FAILED): the end is the order's fact -
        // the cave explored to its end, no reachable branch, no progress: the cave's own source fact (held for the order, the next
        // cave / strip / explore priced); its minutes run out: a price fact (priced again on the record). An owner's stop or a
        // record not loaded stays a plain end (said; rule 15 for the latter).
        int[] entry = {k.x(), k.y(), k.z()};
        switch (end) {
            case DONE, NO_BRANCH, NO_PROGRESS -> io.github.mojolowjo.entropybot.aa.moves.FactChange.caveEnded(entry, end.name(), why);
            case MINUTES -> io.github.mojolowjo.entropybot.aa.moves.FactChange.caveMinutes(entry, cv.mined, cv.passagesLeft, why);
            default -> { }
        }
        return Car.Status.failed(line);
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

    // ---- the climb: one Baritone goal - the feet under open sky (ReflexGoals.UnderSky); Baritone digs, pillars and walks ----

    /**
     * 0.39.z77 round fix (run BI 15:51 / 22:41: "climb: stopped at step 1 (goto -131 14 19): "goto" was deleted" - the climb
     * ran the old climb verb's staircase as a chain of deleted owner verbs; rule 2, Baritone is the car): the climb is ONE
     * Baritone goal, the feet and head open under open sky ({@link io.github.mojolowjo.entropybot.aa.moves.ReflexGoals.UnderSky},
     * its heuristic pulling up toward the column's surface); Baritone picks every cell, dig, pillar and step. Done once the
     * feet stand under open sky; Baritone's goal ending with the feet still under ground is the climb's fact (FactChange.climb,
     * held for the order). Bounded by the step watch and the no-progress watch as every walk. Loader notes: vanilla
     * Level#canSeeSky, heightmaps (Jobs.surfaceNoLeaves / underDepth); Baritone's custom goal process.
     */
    private boolean climbing;

    private String climb(LocalPlayer p) {
        int[] me = Jobs.here(p);
        // EH review: done is ON THE SURFACE (the one reader: a sky-open shaft is not), only the goal is "open sky"
        int depth = io.github.mojolowjo.entropybot.aa.moves.ReflexGoals.climbDepth(Jobs.rockOverHeadKnown(p.level(), me[0], me[1], me[2]), Jobs.belowSurface(p.level(), me[0], me[1], me[2]));
        if (depth == 0) { instant = "ok: already on the surface at " + Jobs.fmt(me); return null; }
        IBaritone b = Jobs.baritone();
        if (b == null) return "error: climb: baritone not loaded";
        int top = Jobs.surfaceNoLeaves(p.level(), me[0], me[2]);
        // shape review 1 (rule 15): the surface not known (depth -1) - the goal still pulls up; "out" is only a known surface
        int upTo = io.github.mojolowjo.entropybot.world.SurfaceHeight.known(top) && top > me[1] ? top : me[1] + Math.max(1, depth);
        io.github.mojolowjo.entropybot.guard.PolicyRuntime.INSTANCE.setIntent(io.github.mojolowjo.entropybot.guard.BreakPolicy.Intent.TRAVEL, "climb");
        // 0.39.z77 climb (run CM 17:36-17:43): each level of the goal's heuristic is the column's own dearest dug level, so
        // Baritone's search counts a level dug up as progress (ReflexGoals.UnderSky)
        String[] dearest = new String[1];
        double cell = dearestClimbCell(p, me, upTo, dearest);
        var goal = new io.github.mojolowjo.entropybot.aa.moves.ReflexGoals.UnderSky(SKY_CELLS, upTo, io.github.mojolowjo.entropybot.aa.moves.ReflexGoals.digLevelTicks(cell));
        b.getCustomGoalProcess().setGoalAndPath(goal);
        climbing = true;
        LOG.info("[entropybot] steps: climb - Baritone's goal: the feet under open sky (pull up to y {}; {} over the feet at {}; a dug level priced {} ticks: {})",
                upTo, depth < 0 ? "the surface not known" : depth + " blocks", Jobs.fmt(me), Math.round(goal.levelTicks()), dearest[0]);
        return null;
    }

    /**
     * 0.39.z77 climb: Baritone's price of the dearest cell to break in the column over the head (feet + 2 .. upTo + 1), as its
     * search prices a break - the mining ticks with the best hotbar item (Baritone's ToolSet reads the hotbar) plus its
     * blockBreakAdditionalPenalty, times the cost hook's TRAVEL multiplier for that cell (BreakPolicy, the climb's intent);
     * open, fluid, unbreakable and refused cells are left out (Baritone goes round them). {@code said[0]}: the words for the
     * log line. 0: no such cell. Loader notes: vanilla BlockState#getCollisionShape / getDestroySpeed, Inventory hotbar
     * slots (getSelectionSize), GameTimesLive (ItemStack#getDestroySpeed); Baritone's Settings.
     */
    private static double dearestClimbCell(LocalPlayer p, int[] me, int upTo, String[] said) {
        var lv = p.level();
        var pr = io.github.mojolowjo.entropybot.guard.PolicyRuntime.INSTANCE;
        String dim = io.github.mojolowjo.entropybot.guard.Guard.dimOf(lv);
        double penalty = baritone.api.BaritoneAPI.getSettings().blockBreakAdditionalPenalty.value;
        java.util.Map<net.minecraft.world.level.block.state.BlockState, Double> ticksOf = new java.util.HashMap<>();
        double dearest = 0;
        said[0] = "no solid cell over the feet";
        int top = Math.min(upTo + 1, lv.getMaxBuildHeight() - 1);
        for (int y = me[1] + 2; y <= top; y++) {
            BlockPos q = new BlockPos(me[0], y, me[2]);
            if (!lv.isLoaded(q)) { said[0] += " (the column is loaded up to y " + (y - 1) + ")"; break; }
            var st = lv.getBlockState(q);
            if (st.getCollisionShape(lv, q).isEmpty() || !st.getFluidState().isEmpty() || st.getDestroySpeed(lv, q) < 0) continue;
            double ticks = ticksOf.computeIfAbsent(st, s -> {
                double best = io.github.mojolowjo.entropybot.commands.GameTimesLive.breakSeconds(p, s, net.minecraft.world.item.ItemStack.EMPTY);
                for (int i = 0; i < net.minecraft.world.entity.player.Inventory.getSelectionSize(); i++) {
                    var it = p.getInventory().getItem(i);
                    if (!it.isEmpty()) best = Math.min(best, io.github.mojolowjo.entropybot.commands.GameTimesLive.breakSeconds(p, s, it));
                }
                return best * io.github.mojolowjo.entropybot.aa.brain.GameTimes.TPS;
            });
            if (Double.isInfinite(ticks)) continue;
            var v = pr.judge("climb", io.github.mojolowjo.entropybot.guard.BreakPolicy.Action.BREAK, io.github.mojolowjo.entropybot.guard.BreakPolicy.Intent.TRAVEL, dim, me[0], y, me[2],
                    pr.facts(lv, q, false), pr.where());
            if (!v.allowed()) continue;
            double cost = (ticks + penalty) * v.multiplier();
            if (cost > dearest) {
                dearest = cost;
                said[0] = "dearest cell " + BuiltInRegistries.BLOCK.getKey(st.getBlock()).getPath() + " at y " + y + ": " + Math.round(ticks) + " ticks + " + Math.round(penalty)
                        + " x" + String.format(java.util.Locale.ROOT, "%.2f", v.multiplier()) + " (" + v.rule() + ")";
            }
        }
        return dearest;
    }

    private Car.Status climbStatus(LocalPlayer p) {
        if (instant != null) return Car.Status.done(instant);
        int[] me = Jobs.here(p);
        int depth = io.github.mojolowjo.entropybot.aa.moves.ReflexGoals.climbDepth(Jobs.rockOverHeadKnown(p.level(), me[0], me[1], me[2]), Jobs.belowSurface(p.level(), me[0], me[1], me[2]));
        IBaritone b = Jobs.baritone();
        if (b == null) { climbing = false; return Car.Status.failed("climb: baritone not loaded"); }
        String end = io.github.mojolowjo.entropybot.aa.moves.ReflexGoals.climbEnd(depth, b.getCustomGoalProcess().isActive() || Core.INSTANCE.reflexes.hold(), Jobs.fmt(me));
        if (end == null) return Car.Status.running("climbing: y " + me[1] + ", " + (depth < 0 ? "the surface not known" : depth + " blocks over the feet"));
        climbing = false;
        if (end.startsWith("ok")) {
            Jobs.cancel(b);
            return Car.Status.done(end);
        }
        io.github.mojolowjo.entropybot.aa.moves.FactChange.climb(end);     // 0.39.z77 answers (2): the climb's own fact
        return Car.Status.failed("climb: " + end);
    }

    /** The client level as the climb's goal reads it (Baritone calls it from its path thread, as the reflex goals' cells). */
    private static final io.github.mojolowjo.entropybot.aa.moves.ReflexGoals.SkyCells SKY_CELLS = new io.github.mojolowjo.entropybot.aa.moves.ReflexGoals.SkyCells() {
        @Override public boolean open(int x, int y, int z) {
            var lv = net.minecraft.client.Minecraft.getInstance().level;
            if (lv == null) return false;
            BlockPos q = new BlockPos(x, y, z);
            return lv.getBlockState(q).getCollisionShape(lv, q).isEmpty() && lv.getFluidState(q).isEmpty();
        }

        @Override public boolean sky(int x, int y, int z) {
            var lv = net.minecraft.client.Minecraft.getInstance().level;
            return lv != null && lv.canSeeSky(new BlockPos(x, y, z));
        }
    };

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
            // 0.39.z76 send-back 3 (rule 22): a chain's explore is a priced way for one source - no gathering on the way (run AU:
            // the gathers' mine process placed blocks and ran GoalRunAwayFromMaintainY), no placing (Mining's explore lease)
            case Step.Explore k -> c.stepJob(s.text(), () -> Mining.get().explore(p, k.minutes() + (k.until() == null ? "" : " until " + k.until()) + " gather off", k.leased()));   // fc cause 2: the leased first walk
            case Step.Dig d -> c.stepJob(s.text(), () -> digRefusedMark(d, DigCommands.dig(c, p, d.from()[0] + " " + d.from()[1] + " " + d.from()[2] + " " + d.to()[0] + " " + d.to()[1] + " " + d.to()[2]
                    + (d.ores() ? " ores" : "") + (d.viaEngine() ? " via engine" : ""), me)));
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
        // run JAF (2026-11-04): a "near" walk (the chain's walk to within the mine's sight of a spot) goes to the cell itself,
        // on Baritone's goal below - never walkTo's free-spot test for a cell inside a block (it ended the walk at its start)
        String r = follow(w.text(), () -> c.stepJob(w.text(), () -> w.near() > 0 ? c.walkNearTo(p, w.x(), w.y(), w.z()) : c.walkTo(p, w.x(), w.y(), w.z())));
        if (r != null) return r;
        if (c.jobs.running() && "travel".equals(c.jobs.job.type)) {
            walkJob = c.jobs.job;
            if (w.near() > 0 && walkJob.finalGoal != null) {
                walkJob.finalGoal = new GoalNear(new BlockPos(w.x(), w.y(), w.z()), w.near());   // the far walk's last leg
            } else if (walkJob.finalGoal == null) {
                // 0.39.z47 (item 4): the chain's walk starts Baritone on the path its price kept (LivePathPrice.walk: the
                // bot still at its start and its cells unchanged), else Baritone searches fresh - as the strip leg (z46).
                // 0.39.z65 item 1 (run V 07:15:22: "goto -261 75 21 near 2 ... Baritone stopped short" 59 below the table):
                // a walk "near" a block walks the same priced goal too (GoalGetToBlock: within one block, inside "near"),
                // never a plain GoalNear search of its own - the chain priced exactly this goal (NeedChain.walk), and the
                // fork steers a priced goal's searches (DigDownGoal below, z63 ClimbGoal above)
                IBaritone b = Jobs.baritone();
                if (b != null) walkJob.goalObj = LivePathPrice.walkGoal(b, new BlockPos(w.x(), w.y(), w.z()));
            }
        }
        return null;
    }

    /**
     * 0.39.z77 walk (run BT 13:14:32: "stopped at step 2 (goto 20 21 -10): couldn't get there (22 blocks away, stopped at
     * 28 21 11) (walk: Baritone stopped short)" ended the leggings order - a walk has no count and its failure said no fact):
     * the walk's travel job ended short (its typed end, Jobs.Job#shortEnd) - the fact "walk to x y z ended short at feet:
     * Baritone's reason" keyed by the target (FactChange.walkShort); the order re-plans on it (Steps.failOrReplan holds the
     * target when the same target ends short again at the same feet).
     */
    private Car.Status walkStatus(Step.Walk w) {
        Car.Status st = requestStatus();
        if (st.state() == Car.State.FAILED && walkJob != null && walkJob.shortEnd != null)
            io.github.mojolowjo.entropybot.aa.moves.FactChange.walkShort(new int[]{w.x(), w.y(), w.z()}, walkJob.shortEnd, st.why());
        return st;
    }

    /**
     * 0.39.z65 item 1: the walk to a known station (the travel job on the chain's priced goal); ended failed, it is the
     * station's fact for this order (review 1): the order re-plans and the chain prices placing a new one here
     * (NeedChain.World.withoutSources drops it). Never a park.
     */
    private Car.Status toStationStatus(Step.ToStation t) {
        if (instant != null) return Car.Status.done(instant);
        Car.Status st = requestStatus();
        if (st.state() != Car.State.FAILED) return st;
        // 0.39.z78 J5 slice 1 (audit D2; run DU 16:29:01.344 "stopped 1 blocks away", run DT 16:19:41.301 two blocks under the
        // furnace): the walk ended short but the station is in reach from where the bot stands - done, nothing held (a walk
        // not needed is no fact about the station)
        String reach = inReachOf(t);
        if (reach != null) {
            LOG.info("[entropybot] stations: the {} at {} {} {}: the walk ended short ({}) but it is in reach from here - done, not held", t.name(), t.x(), t.y(), t.z(), st.why());
            return Car.Status.done(reach);
        }
        return Car.Status.failed(stationRefused(t, "the walk to it ended short: " + (st.why() == null ? "no reason" : st.why())));
    }

    /**
     * 0.39.z78 J5 slice 1: "ok: the &lt;station&gt; at x y z is in reach from here" when the station's block is within the
     * player's block interaction range from the eye (StationReach, vanilla's own test), else null. Loader notes: vanilla
     * Player#getEyePosition, Player#blockInteractionRange.
     */
    private static String inReachOf(Step.ToStation t) {
        LocalPlayer p = Minecraft.getInstance().player;
        if (p == null) return null;
        var eye = p.getEyePosition();
        boolean in = io.github.mojolowjo.entropybot.aa.moves.StationReach.inReach(new double[]{eye.x, eye.y, eye.z}, new int[]{t.x(), t.y(), t.z()}, p.blockInteractionRange());
        return in ? "ok: the " + t.name() + " at " + t.x() + " " + t.y() + " " + t.z() + " is in reach from here" : null;
    }

    /**
     * 0.39.z68: the explore's walk up to the surface (the travel job on the chain's priced goal, as the station walk).
     * Already on the surface (0.39.z78 EH: Jobs.belowSurface, the one reader - a sky-open shaft is not the surface): done at once.
     * Loader notes: vanilla Level#canSeeSky / heightmaps through Jobs.belowSurface.
     */
    private String toSurface(LocalPlayer p, Step.ToSurface t) {
        int[] me = Jobs.here(p);
        instant = null;
        surfaceTicks = 0;
        if (me[0] == t.x() && me[1] == t.y() && me[2] == t.z()) { instant = "ok: already at the surface cell " + Jobs.fmt(me); return null; }   // run EI: the target reached, never a fact re-read
        IBaritone b = Jobs.baritone();
        if (b == null) return "error: " + surfaceRefused(t, "the walk up did not start: baritone not loaded");
        // FN fix (run FN 20:23-20:44: "up to the surface at 55 53 3 ... arrived near 56 53 3" 686 times - the travel job's
        // GoalGetToBlock is met BESIDE the cell, the bot stayed under a 9-block overhang and the explore said "under ground" each
        // time): the walk up is an INTO walk - the feet end IN the cell (LivePathPrice.walkInto, the band walk's; NeedChain
        // priced that very goal, rule 18); Baritone walks and picks the path (rule 2)
        io.github.mojolowjo.entropybot.guard.PolicyRuntime.INSTANCE.setIntent(io.github.mojolowjo.entropybot.guard.BreakPolicy.Intent.TRAVEL, "to the surface");
        LivePathPrice.walkInto(b, new BlockPos(t.x(), t.y(), t.z()));
        LOG.info("[entropybot] steps: {} - Baritone's walk into the cell (the feet end on it, never beside it)", t.text());
        return null;
    }

    /** FN fix: the walk up's ticks since its start (Baritone's process is active from its next tick; structural). */
    private int surfaceTicks;

    /**
     * 0.39.z68: done with the feet ON the surface cell; ended failed, it is the walk up's fact (a re-plan). FN fix: Baritone's
     * walk ending with the feet elsewhere is "ended short" - the cell is out for the order (its last calc's words), never done.
     */
    private Car.Status toSurfaceStatus(LocalPlayer p, Step.ToSurface t) {
        if (instant != null) return Car.Status.done(instant);
        // 0.39.z78 J6b run EI (the manager's fix; 01:07:44.919 "on the surface at 9 64 4" mid-path, the walk's own cut at 9 65 3
        // fooling the re-read): the walk up ends ONLY when the feet stand on its target cell, or Baritone's walk ends short (the
        // walk up's fact, held for the order); never on a "below the surface" fact re-read mid-path
        int[] me = Jobs.here(p);
        IBaritone b = Jobs.baritone();
        if (me[0] == t.x() && me[1] == t.y() && me[2] == t.z()) {
            if (b != null) Jobs.cancel(b);
            LOG.info("[entropybot] steps: {} - at the surface cell (feet {})", t.text(), Jobs.fmt(me));
            return Car.Status.done("ok: at the surface cell " + Jobs.fmt(me));
        }
        if (b == null) return Car.Status.failed(surfaceRefused(t, "baritone not loaded"));
        surfaceTicks++;
        if (b.getCustomGoalProcess().isActive() || Core.INSTANCE.reflexes.hold() || surfaceTicks <= 1) {   // structural: the process is active from the tick after its start
            return Car.Status.running(t.text() + ": walking into " + t.x() + " " + t.y() + " " + t.z() + " (feet " + Jobs.fmt(me) + ")");
        }
        // J6b item 14 (rule 15): only an exhausted graph is "no path"; a budget or other stop is "not known" - either way this
        // cell is out for the order and the next price takes another cell or the climb
        String calc;
        try {
            var wc = b.getCustomGoalProcess().lastCalc();
            calc = wc == null ? "no calc kept" : wc.exhausted() ? "no path (the graph exhausted)" : wc.budget() ? "not known (stop " + wc.stop() + ": a budget)" : "stop " + wc.stop();
        } catch (LinkageError e) {
            calc = "the stop is not known (an older fork)";
        }
        return Car.Status.failed(surfaceRefused(t, "the walk up ended short at " + Jobs.fmt(me) + ": Baritone's walk into the cell ended (" + calc + ")"));
    }
    private String surfaceRefused(Step.ToSurface t, String why0) {
        String why = why0.replace(": error: ", ": ");
        io.github.mojolowjo.entropybot.aa.moves.FactChange.surface(t.x(), t.y(), t.z(), why);
        LOG.info("[entropybot] explore: the walk up to the surface at {} {} {} out for this order: {} - the explore runs from where I am", t.x(), t.y(), t.z(), why);
        return "the walk up to the surface at " + t.x() + " " + t.y() + " " + t.z() + " out for this order: " + why;
    }

    /** 0.39.z65 item 1: the station walk's start; a walk refused at its start is the station's fact as well. */
    private String toStation(Step.ToStation t) {
        // 0.39.z78 J5 slice 1 (audit D2): already in reach - no walk, done
        String reach = inReachOf(t);
        if (reach != null) { instant = reach; return null; }
        String r = walk(new Step.Walk(t.x(), t.y(), t.z(), 0));
        return r == null ? null : "error: " + stationRefused(t, "the walk to it did not start: " + r);
    }

    /**
     * The station out for this order and its fact said (the order re-plans); the step's failure words. 0.39.z65 review 1:
     * a walk that failed from here says nothing about the station from elsewhere. 0.39.z78 J5: the fact
     * (FactChange.stationWalk) lands in the one station store (Goals.replan -> StationHolds.walkFailed, lifted by distance).
     */
    private String stationRefused(Step.ToStation t, String why0) {
        int[] at = {t.x(), t.y(), t.z()};
        String why = why0.replace(": error: ", ": ");
        io.github.mojolowjo.entropybot.aa.moves.FactChange.stationWalk(t.name(), t.x(), t.y(), t.z(), why);
        LOG.info("[entropybot] stations: the {} at {} out for this order: {}", t.name(), Jobs.fmt(at), why);
        return t.name() + " at " + Jobs.fmt(at) + " out for this order: " + why;
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

    /** 0.39.z76 send-back 2 (run AP): the running collect's priced spot (null: none named) and the feet's metres from it at the start. */
    private Step.Collect collectSpot;
    private double collectStartDist = Double.NaN;

    private String collect(LocalPlayer p, Step.Collect k) {
        mineSource = k.item();
        collectSpot = k.at() == null ? null : k;
        collectStartDist = k.dist(Jobs.here(p));
        if (!io.github.mojolowjo.entropybot.aa.NeedChain.plain(k.item())) return collectAny(p, k);
        String id = k.item().contains(":") ? k.item() : "minecraft:" + k.item();
        // the blocks that give it: the game's loot data (LootBook), and the block of the item itself when it gives it
        // (0.39.z66 item 2: Nearby.sourceBlocks, the chain's own pairing - an iron_ore block drops raw_iron, not iron_ore)
        List<Block> blocks = new ArrayList<>();
        var book = c.aaRuntime == null ? null : c.aaRuntime.currentLootBook();
        for (String bid : io.github.mojolowjo.entropybot.aa.Nearby.sourceBlocks(id, book, AaRuntime::ownBlock))
            BuiltInRegistries.BLOCK.getOptional(ResourceLocation.tryParse(bid)).ifPresent(b -> { if (!blocks.contains(b)) blocks.add(b); });
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
        takePickupUntil = -1;
        takeStartTick = Core.INSTANCE.tick();
        takeOnGround = p.onGround();                // take 2: the fork's in-reach break asks for the ground (said if short)
        component = true;
        try {
            return mine(p, new ArrayList<>(List.of(own)), List.of(id), 1, t.text());
        } finally {
            component = false;
        }
    }

    /** Owner 2026-10-29 (the carried table): the station of mine the running "take my X here" step resolved to (null: none found). */
    private Step.TakeStation takeNearAs;

    /**
     * Owner 2026-10-29 ("the crafting table keeps on itself"): the nearest station block of mine of the step's kind within
     * station.takeReachM of the feet (the ledger's placements, a refused one skipped: OwnStations.whyNot), then the take of
     * that cell ({@link #takeStation}). None: the step fails saying so (the chain re-plans on the bag it has).
     */
    private String takeNear(LocalPlayer p, Step.TakeNear t) {
        takeNearAs = null;
        String id = t.item().contains(":") ? t.item() : "minecraft:" + t.item();
        int[] here = Jobs.here(p);
        double reach = Tuning.get("station.takeReachM");
        io.github.mojolowjo.entropybot.clear.OwnStations.Placed best = null;
        double bd = Double.MAX_VALUE;
        List<String> refused = new ArrayList<>();
        for (var q : io.github.mojolowjo.entropybot.clear.OwnStations.placed()) {
            if (q == null || q.block() == null) continue;
            String b = q.block().contains(":") ? q.block() : "minecraft:" + q.block();
            if (!b.equals(id)) continue;
            double d = Math.sqrt(Jobs.distSq(here, new int[]{q.x(), q.y(), q.z()}));
            if (d > reach) continue;
            String why = io.github.mojolowjo.entropybot.clear.OwnStations.whyNot(q.x(), q.y(), q.z());
            if (why != null) { refused.add(q.x() + " " + q.y() + " " + q.z() + ": " + why); continue; }
            if (d < bd) { bd = d; best = q; }
        }
        if (best == null) return "error: " + t.text() + ": no " + t.item() + " of mine within " + Math.round(reach) + " m of " + here[0] + " " + here[1] + " " + here[2]
                + (refused.isEmpty() ? "" : " (refused: " + String.join("; ", refused) + ")");
        takeNearAs = new Step.TakeStation(t.item(), best.x(), best.y(), best.z());
        LOG.info("[entropybot] steps: {}: my {} at {} {} {} ({} m)", t.text(), t.item(), best.x(), best.y(), best.z(), Math.round(bd));
        return takeStation(p, takeNearAs);
    }

    /**
     * The take's status; done: the ledger forgets the cell (run GG 04:22:54 / 04:30:15: a taken table's ledger entry stayed
     * "built: station" with no block there, the next take near that cell found it "refused: no station any more" and nothing
     * else within reach - the take failed and the order re-planned).
     */
    private Car.Status takeNearStatus(LocalPlayer p, Step.TakeNear t) {
        if (takeNearAs == null) return Car.Status.failed(t.text() + ": no station of mine was found at its start");
        Car.Status st = takeStatus(p, takeNearAs);
        if (st.state() == Car.State.DONE) {
            String line = io.github.mojolowjo.entropybot.clear.OwnStations.forget(takeNearAs.x(), takeNearAs.y(), takeNearAs.z(), "taken along (" + t.text() + ")");
            if (line != null) LOG.info("[entropybot] steps: {}", line);
            takeNearAs = null;
        }
        return st;
    }

    /** 0.39.z48: the next mine() runs the fork's component break whatever the block kind (a station take). */
    private boolean component;

    /** 0.39.z75 take: the tick the take's pickup wait ends (-1: none started); the tick the take started (-1: none). */
    private long takePickupUntil = -1, takeStartTick = -1;
    /** take 2: on the ground when the take started. */
    private boolean takeOnGround;

    /**
     * take 2 (run AJ 20:23:41 / 20:24:55): what the fork's in-reach break needs, as the take saw it at its end - on the ground
     * at the start, the eyes' distance to the block's centre, a liquid or a falling block above or beside it (Baritone's
     * avoidBreaking adjacency), and Baritone's last chat line. Loader notes: vanilla Entity#getEyePosition, Vec3#atCenterOf,
     * Level#getFluidState, FallingBlock.
     */
    private String takeFacts(LocalPlayer p, Step.TakeStation t) {
        var level = Minecraft.getInstance().level;
        BlockPos at = new BlockPos(t.x(), t.y(), t.z());
        List<String> near = new ArrayList<>();
        if (level != null) {
            for (BlockPos q : List.of(at.above(), at.north(), at.south(), at.east(), at.west())) {
                var st = level.getBlockState(q);
                if (!level.getFluidState(q).isEmpty()) near.add("liquid at " + q.getX() + " " + q.getY() + " " + q.getZ());
                else if (st.getBlock() instanceof net.minecraft.world.level.block.FallingBlock) near.add(BuiltInRegistries.BLOCK.getKey(st.getBlock()).getPath() + " (falls) at " + q.getX() + " " + q.getY() + " " + q.getZ());
            }
        }
        double eye = p.getEyePosition().distanceTo(net.minecraft.world.phys.Vec3.atCenterOf(at));
        String last = io.github.mojolowjo.entropybot.baritone.BaritoneHook.lastLine();
        return "on the ground at the start " + (takeOnGround ? "yes" : "no") + ", eyes " + String.format(java.util.Locale.ROOT, "%.1f", eye) + " m from it"
                + ", beside/above it " + (near.isEmpty() ? "no liquid or falling block" : String.join(", ", near))
                + ", Baritone's last words: " + (last == null || last.isEmpty() ? "none" : last);
    }

    /**
     * 0.39.z75 take (run AI 20:06:10): the take's status on the observed state (TakeEnd): the station in the bag - done;
     * the break running - running; the block gone, not in the bag - Baritone's pickup (the stock follow process) walks to
     * its drop while the step waits station.takePickupS for the bag; else short by 1 with the take's typed fact
     * (FactChange.take), so the order re-plans. Loader notes: vanilla Level#getBlockState, ItemStack#is; Baritone's
     * IFollowProcess#pickup.
     */
    private Car.Status takeStatus(LocalPlayer p, Step.TakeStation t) {
        IBaritone b = Jobs.baritone();
        if (b == null) return Car.Status.failed("baritone not loaded");
        int got = count(p) - startCount;
        String id = t.item().contains(":") ? t.item() : "minecraft:" + t.item();
        net.minecraft.world.item.Item item = BuiltInRegistries.ITEM.get(ResourceLocation.tryParse(id));
        Block own = Block.byItem(item);
        var level = Minecraft.getInstance().level;
        boolean stands = level != null && level.getBlockState(new BlockPos(t.x(), t.y(), t.z())).getBlock() == own;
        boolean breaking = b.getChopProcess().isActive() || Core.INSTANCE.reflexes.hold();
        long now = Core.INSTANCE.tick();
        var v = io.github.mojolowjo.entropybot.aa.moves.TakeEnd.judge(got, breaking, stands, now, takePickupUntil, takeStartTick < 0 ? Long.MAX_VALUE : now - takeStartTick);
        switch (v) {
            case DONE -> {
                if (takePickupUntil >= 0) b.getFollowProcess().cancel();
                takePickupUntil = -1;
                endMining();
                io.github.mojolowjo.entropybot.aa.moves.TakeHolds.INSTANCE.clear(t.x(), t.y(), t.z());     // take 2: done here, no hold
                return Car.Status.done("ok: " + miningWhat + ": got " + got + " (in the bag)");
            }
            case RUNNING -> {
                return Car.Status.running(takePickupUntil >= 0 ? miningWhat + ": waiting for the " + t.item() + " drop to reach the bag (Baritone's pickup)" : miningWhat + ": " + got + " of 1");
            }
            case PICKUP -> {
                b.getChopProcess().cancel();
                b.getFollowProcess().pickup(st -> st.is(item));
                takePickupUntil = now + Math.round(20 * Tuning.get("station.takePickupS"));     // structural: 20 ticks a second
                LOG.info("[entropybot] steps: {}: the {} is broken, not in the bag yet: Baritone's pickup walks to its drop (station.takePickupS {} s)",
                        t.text(), t.item(), Math.round(Tuning.get("station.takePickupS")));
                return Car.Status.running(miningWhat + ": waiting for the " + t.item() + " drop to reach the bag (Baritone's pickup)");
            }
            default -> {
                boolean waited = takePickupUntil >= 0;
                if (waited) b.getFollowProcess().cancel();
                takePickupUntil = -1;
                endMining();
                String end = endOf(b.getChopProcess().lastEnd());
                String why = io.github.mojolowjo.entropybot.aa.moves.TakeEnd.shortText(t.text(), stands, end, waited, takeFacts(p, t));
                io.github.mojolowjo.entropybot.aa.moves.FactChange.take(t.x(), t.y(), t.z(), why);
                // take 2: the cell's short takes count for every order (two: held for all of them, TakeHolds)
                io.github.mojolowjo.entropybot.aa.moves.TakeHolds.INSTANCE.shortAt(t.x(), t.y(), t.z(), id, Jobs.here(p), why);
                return Car.Status.failed(why);
            }
        }
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
        // S26 (owner 2026-10-22): an ore cluster is one job like a tree; any other block set (stone, sand...) stays on the
        // stock mine process (a stone "component" is the hillside). 0.39.z78 vein (owner 2026-10-27, "a vein is not a chop";
        // RULES 17): a tree and a station take run the fork's chop, ore its vein (only blocks in view, CollectHand)
        var hand = io.github.mojolowjo.entropybot.aa.moves.CollectHand.of(component, allLogs(blocks), allOres(blocks));
        chopping = hand == io.github.mojolowjo.entropybot.aa.moves.CollectHand.Hand.CHOP;
        veining = hand == io.github.mojolowjo.entropybot.aa.moves.CollectHand.Hand.VEIN;
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
        mineFilter = filter;                     // 0.39.z66 item 2: the drops' gain at the end, as Baritone counts it
        mineDrops0 = target - want;
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
                if (component) b.getChopProcess().mineComponent(target, filter);     // a station take: the component at hand
                else b.getChopProcess().chop(target, filter);                         // 0.39.z78 vein: a tree is the chop's
                if (!b.getChopProcess().isActive()) {          // it ended at its start (none in range): its own reason
                    endMining();
                    String end = endOf(b.getChopProcess().lastEnd());
                    ChopEnd.zeroGot(0, mineSource, mineFrom, end);   // 0.39.z36 (S42): none got: the source fact, the order re-plans
                    return "error: " + what + ": " + end;
                }
            } else if (veining) {
                // 0.39.z78 vein: the vein takes no spot and targets only blocks in view - the priced spot's walk first, on the
                // goal the chain priced (LivePathPrice.walkGoal: the kept priced path when the bot still stands at its start)
                BlockPos spot = collectSpot == null || collectSpot.at() == null ? null : new BlockPos(collectSpot.at()[0], collectSpot.at()[1], collectSpot.at()[2]);
                if (spot != null && !LivePathPrice.goal(spot).isInGoal(p.blockPosition())) {
                    veinWalking = true;
                    LivePathPrice.walkGoal(b, spot);
                    LOG.info("[entropybot] steps: {} - the walk to the priced spot {} first (Baritone's vein takes only blocks in view)", what, collectSpot.spot());
                } else {
                    String end = startVein(b, filter, target, here, what);
                    if (end != null) return "error: " + end;
                }
            } else {
                b.getMineProcess().mineWith(target, filter, true);
            }
        } catch (LinkageError e) {
            endMining();
            return "error: this Baritone has no " + (veining ? "IVeinProcess (getVeinProcess: the fork's job-vein jar)" : "mineWith (the stock jar? install the entropy fork)") + ": " + e;
        }
        LOG.info("[entropybot] steps: {} - Baritone's {} process on {} block kind(s), until {} in the bag", what, hand.name, blocks.size(), target);
        return null;
    }

    /**
     * 0.39.z78 vein: the fork's vein on {@code filter} until {@code target} in the bag, from where the bot stands now (at the
     * priced spot). Null: it runs; else the step's failure text from its own end at the start (rule 7).
     */
    private String startVein(IBaritone b, BlockOptionalMetaLookup filter, int target, int[] here, String what) {
        b.getVeinProcess().vein(target, filter);
        if (b.getVeinProcess().isActive()) return null;
        endMining();
        String end = veinEndOf(b.getVeinProcess().lastEnd());
        String none = io.github.mojolowjo.entropybot.aa.moves.CollectHand.veinNoneInView(collectSpot, end, here, what, 0, want);
        if (none != null) return none;
        ChopEnd.zeroGot(0, mineSource, mineFrom, end);
        return what + ": " + end;
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
    /** 0.39.z66 item 2: the mine's filter and the bag's count of its items at the start (null: no mine running). */
    private BlockOptionalMetaLookup mineFilter;
    private int mineDrops0;

    /**
     * 0.39.z77 J2 E4: the cells of a Baritone goal - a goal with a position (IGoalRenderPos: GoalBlock, GoalTwoBlocks,
     * GoalGetToBlock, GoalNear) as itself, a GoalComposite as its parts that have one; empty: none read. Loader notes:
     * Baritone's goal API (IGoalRenderPos#getGoalPos, GoalComposite#goals), vanilla BlockPos.
     */
    static List<int[]> goalCells(baritone.api.pathing.goals.Goal g) {
        List<int[]> out = new java.util.ArrayList<>();
        if (g instanceof baritone.api.pathing.goals.GoalComposite c) {
            for (var x : c.goals()) if (x instanceof baritone.api.utils.interfaces.IGoalRenderPos r) { var bp = r.getGoalPos(); out.add(new int[]{bp.getX(), bp.getY(), bp.getZ()}); }
        } else if (g instanceof baritone.api.utils.interfaces.IGoalRenderPos r) {
            var bp = r.getGoalPos();
            out.add(new int[]{bp.getX(), bp.getY(), bp.getZ()});
        }
        return out;
    }

    /** 0.39.z66 item 2: the bag's gain of the mine filter's items since the start (Baritone's count); -1: not known. */
    private int dropsGot(LocalPlayer p) {
        return mineFilter == null ? -1 : itemsOf(p, mineFilter) - mineDrops0;
    }

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

    /** 0.39.z78 vein: the vein's end reason as the step's failure text (an empty one is said so, never guessed). */
    static String veinEndOf(String lastEnd) {
        return lastEnd == null || lastEnd.isEmpty() ? "the vein process ended without a reason" : lastEnd;
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
            if (b.getChopProcess().isActive() || Core.INSTANCE.reflexes.hold()) return Car.Status.running(miningWhat + ": " + got + " of " + want + " (Baritone's chop)");
            endMining();
            return ChopEnd.of(miningWhat, got, want, endOf(b.getChopProcess().lastEnd()), mineSource, mineFrom, dropsGot(p));
        }
        if (veining) return veinStatus(p, b, got);
        if (got >= want) {
            if (b != null) b.getMineProcess().cancel();
            endMining();
            return Car.Status.done("ok: " + miningWhat + ": got " + got);
        }
        // 0.39.z76 send-back 2 (run AP 00:07:24-00:17:24: "collect oak_planks 11" priced at a spot 75 m away walked ~1000 blocks
        // to other planks Baritone knew): the collect is the priced spot's - a mine walking away from it ends with that spot's fact
        int[] here = Jobs.here(p);
        if (collectSpot != null && !Core.INSTANCE.reflexes.hold() && collectSpot.leftSpot(here, collectStartDist, io.github.mojolowjo.entropybot.aa.brain.Failures.movedV())) {
            if (b != null) b.getMineProcess().cancel();
            endMining();
            String why = miningWhat + ": Baritone's mine went away from the priced spot " + collectSpot.spot() + " (" + Math.round(collectSpot.dist(here)) + " m from it, "
                    + Math.round(collectStartDist) + " m at the start; failures.moved " + Math.round(io.github.mojolowjo.entropybot.aa.brain.Failures.movedV()) + "), got " + got + " of " + want;
            io.github.mojolowjo.entropybot.aa.moves.FactChange.nearCollect(collectSpot.item(), collectSpot.at(), why);
            return Car.Status.failed(why);
        }
        // 0.39.z77 J2 E4 (run AP 00:07:24): the mine's own goal - every block it goes for - more than failures.moved from the
        // priced spot: the same spot's fact before the feet show it (no new comparison, rule 14)
        if (collectSpot != null && b != null && !Core.INSTANCE.reflexes.hold()) {
            String why = io.github.mojolowjo.entropybot.aa.moves.CollectHand.targetAway(collectSpot, goalCells(b.getPathingBehavior().getGoal()),
                    io.github.mojolowjo.entropybot.aa.brain.Failures.movedV(), io.github.mojolowjo.entropybot.aa.moves.CollectHand.Hand.MINE, miningWhat, got, want);
            if (why != null) {
                b.getMineProcess().cancel();
                endMining();
                return Car.Status.failed(why);
            }
        }
        if (b == null) return Car.Status.failed("baritone not loaded");
        if (!b.getMineProcess().isActive() && !Core.INSTANCE.reflexes.hold()) {
            endMining();
            // 0.39.z78 vein (fork job-vein, second item): under the run lease (mineWith) the mine ENDS when it knows none of
            // the block - "none in sight (N blocks of the feet)" / "none in range" (IMineProcess.lastEnd), never its explore
            // toward legitMineYLevel; at a priced spot that is the spot's fact, in the explore watch's words it replaces
            String end = mineEnd(b);
            var sg = io.github.mojolowjo.entropybot.sense.ChunkPass.INSTANCE.sightings();
            String none = io.github.mojolowjo.entropybot.aa.moves.CollectHand.mineNoneKnown(collectSpot, end, here, miningWhat, got, want,
                    sg == null ? null : (x, y, z, w) -> sg.markCovered(Storage.dim(), x, y, z, w));
            if (none != null) return Car.Status.failed(none);
            String said = end != null && !end.isEmpty() ? end
                    : mineSaid(mineLines0, io.github.mojolowjo.entropybot.baritone.BaritoneHook.lines(), io.github.mojolowjo.entropybot.baritone.BaritoneHook.lastLine());
            int drops = dropsGot(p);
            if (drops > got) return ChopEnd.of(miningWhat, got, want, said, mineSource, mineFrom, drops);   // 0.39.z66 item 2: other drops, its own words
            // 0.39.z33 (S36): none of it got: the source is a changed fact (unreachable) and the order re-plans without it
            ChopEnd.zeroGot(got, mineSource, mineFrom, said);
            return Car.Status.failed(miningWhat + ": Baritone's mine process ended with " + got + " of " + want + ": " + said);
        }
        return Car.Status.running(miningWhat + ": " + got + " of " + want + " (Baritone's mine)");
    }

    /**
     * job-sight: a dig refused at its start (keep-out, no-break, a safe area: DigCommands' "error: ...") whose box is a
     * cover column (one column, the "dig to" of a covered spot) marks the spot under it in the sightings store with the
     * reason - the planner reads it as no source there (Nearby.withCover, said). The reply goes on unchanged.
     */
    private static String digRefusedMark(Step.Dig d, String reply) {
        if (reply == null || !reply.startsWith("error") || d.from()[0] != d.to()[0] || d.from()[2] != d.to()[2]) return reply;
        var sg = io.github.mojolowjo.entropybot.sense.ChunkPass.INSTANCE.sightings();
        int y = Math.min(d.from()[1], d.to()[1]) - 1;
        if (sg != null && sg.markCovered(Storage.dim(), d.from()[0], y, d.from()[2], io.github.mojolowjo.entropybot.sense.Sightings.DIG_REFUSED + reply))
            LOG.info("[entropybot] steps: {} - the spot {} {} {} marked covered, dig refused: {}", d.text(), d.from()[0], y, d.from()[2], reply);
        return reply;
    }

    /** 0.39.z78 vein: the mine's own end (the fork's IMineProcess.lastEnd), null on a fork without it (the chat line then). */
    private static String mineEnd(IBaritone b) {
        try {
            return b.getMineProcess().lastEnd();
        } catch (LinkageError e) {
            io.github.mojolowjo.entropybot.Quiet.warn("steps: this Baritone has no IMineProcess.lastEnd (the fork's job-vein jar): the mine's chat line says its end", e);
            return null;
        }
    }

    /**
     * 0.39.z78 vein: an ore collect on the fork's vein - first its walk to the priced spot (the vein takes no spot and only
     * blocks in view), then the vein; its end is the step's (CollectHand: "none in view" at the spot is the spot's fact; the
     * chop's end rules else, ChopEnd).
     */
    private Car.Status veinStatus(LocalPlayer p, IBaritone b, int got) {
        int[] here = Jobs.here(p);
        if (veinWalking) {
            if (b.getCustomGoalProcess().isActive() || b.getPathingBehavior().getInProgress().isPresent() || Core.INSTANCE.reflexes.hold())
                return Car.Status.running(miningWhat + ": walking to the priced spot " + collectSpot.spot() + " (" + Math.round(collectSpot.dist(here)) + " m) for Baritone's vein");
            veinWalking = false;
            BlockPos spot = new BlockPos(collectSpot.at()[0], collectSpot.at()[1], collectSpot.at()[2]);
            if (!LivePathPrice.goal(spot).isInGoal(p.blockPosition())) {
                endMining();
                return Car.Status.failed(io.github.mojolowjo.entropybot.aa.moves.CollectHand.veinWalkShort(collectSpot, here, miningWhat,
                        io.github.mojolowjo.entropybot.baritone.BaritoneHook.lastCalc()));
            }
            String end = startVein(b, mineFilter, mineDrops0 + want, here, miningWhat);
            if (end != null) return Car.Status.failed(end);
            LOG.info("[entropybot] steps: {} - at the priced spot {}: Baritone's vein on what is in view", miningWhat, collectSpot.spot());
            return Car.Status.running(miningWhat + ": " + got + " of " + want + " (Baritone's vein)");
        }
        // review of 4e5e57e1: the mine's E4 guard for the vein too (after its walk) - its goal cells all away from the priced
        // spot: the spot's near-collect fact, the vein stopped
        if (!Core.INSTANCE.reflexes.hold()) {
            String away = io.github.mojolowjo.entropybot.aa.moves.CollectHand.targetAway(collectSpot, goalCells(b.getPathingBehavior().getGoal()),
                    io.github.mojolowjo.entropybot.aa.brain.Failures.movedV(), io.github.mojolowjo.entropybot.aa.moves.CollectHand.Hand.VEIN, miningWhat, got, want);
            if (away != null) {
                b.getVeinProcess().cancel();
                endMining();
                return Car.Status.failed(away);
            }
        }
        if (b.getVeinProcess().isActive() || Core.INSTANCE.reflexes.hold()) return Car.Status.running(miningWhat + ": " + got + " of " + want + " (Baritone's vein)");
        endMining();
        String end = veinEndOf(b.getVeinProcess().lastEnd());
        String none = io.github.mojolowjo.entropybot.aa.moves.CollectHand.veinNoneInView(collectSpot, end, here, miningWhat, got, want);
        if (none != null) return Car.Status.failed(none);
        return ChopEnd.of(miningWhat, got, want, end, mineSource, mineFrom, dropsGot(p));
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
        // 0.39.z62 item 1a (run S 04:57:21): the cells the chain's next walk or strip uses are kept free - a constraint for
        // Baritone's pick (KeepClear), never a cell named to place on
        List<int[]> keep = keepClear(p);
        if (!keep.isEmpty()) LOG.info("[entropybot] steps: {}: keeping {} cell(s) free for the next step ({})", n.text(), keep.size(), keepText(keep));
        var r = io.github.mojolowjo.entropybot.aa.moves.JobHands.get().placeNear(n.item(), Tuning.i("steps.placeNearR"), (int) (20 * Tuning.need("hands.buildS")), "steps", keep);
        if (!r.failed()) return null;
        // 0.39.z60 item 3 (run R 03:52:52: the order stopped on "no cell within 3 blocks" with no re-plan): a typed fact, so
        // the order is planned again on the facts of now (rule 7) instead of parked
        io.github.mojolowjo.entropybot.aa.moves.FactChange.placeFailed(n.item(), r.why(), Jobs.here(p));   // 0.39.z67 item 1: where it failed
        placeSiteProbe(p, n.item(), r.why());
        return "error: " + r.why();
    }

    /**
     * Round 17 (2026-11-02; runs HF 01:10:09 and 01:14:56, HG 01:37:02, HH 01:56:03 and 02:13:13: two to four table placements
     * failed per run, a minute of re-plan and walk each; the fork's ends "cannot reach: no stand spot beside any of the 1 free
     * cells", "no cell within 3 blocks; none to clear either", "no cell with room" - at y 62 on the surface as under ground):
     * what the site is at a place-near failure, behind debug.stepProbe, from the one world read the fork's own pick makes
     * (LegRules: empty = replaceable and dry, a solid top = face sturdy up). Said, not fixed: the fix waits for the site
     * (to-do/cave-walk-probe.md item 3).
     */
    private static void placeSiteProbe(LocalPlayer p, String item, String why) {
        if (!StepProbe.on()) return;
        var level = p.level();
        int[] me = Jobs.here(p);
        int r = Tuning.i("steps.placeNearR");
        int cells = 0, free = 0, empty = 0, wet = 0, solid = 0, soft = 0;
        for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) {
            if (dx * dx + dz * dz > r * r) continue;
            for (int dy = -1; dy <= 1; dy++) {                                           // structural: the fork's pick scans dy -1..1
                BlockPos q = new BlockPos(me[0] + dx, me[1] + dy, me[2] + dz);
                cells++;
                var s = level.getBlockState(q);
                boolean e = s.canBeReplaced() && s.getFluidState().isEmpty();
                if (!s.getFluidState().isEmpty()) wet++;
                else if (e) empty++;
                else if (s.isFaceSturdy(level, q, net.minecraft.core.Direction.UP)) solid++;
                else soft++;
                var a = level.getBlockState(q.above());
                if (e && a.canBeReplaced() && a.getFluidState().isEmpty() && level.getBlockState(q.below()).isFaceSturdy(level, q.below(), net.minecraft.core.Direction.UP)) free++;
            }
        }
        BlockPos feet = new BlockPos(me[0], me[1], me[2]);
        String in = BuiltInRegistries.BLOCK.getKey(level.getBlockState(feet).getBlock()).getPath();
        String under = BuiltInRegistries.BLOCK.getKey(level.getBlockState(feet.below()).getBlock()).getPath();
        LOG.info("[entropybot] probe place site: t={} feet {} in {} on {} sky {} | within {} (dy -1..1): {} cells: {} empty, {} wet, {} solid-topped, {} soft (leaves, slabs, plants); {} free (empty, empty above, solid under) | place {}: {}",
                Core.INSTANCE.tick(), Jobs.fmt(me), in, under, level.canSeeSky(feet) ? "open" : "covered", r, cells, empty, wet, solid, soft, free, item.replaceFirst("^minecraft:", ""), why);
    }

    /**
     * 0.39.z62 item 1a: the cells to keep free for the steps after the running one (KeepClear over the running run's steps;
     * a walk's cells from the fork's kept price for its goal; 0.39.z77 craft: my stations a later craft / smelt uses). Empty
     * when no typed run runs here (an owner verb).
     */
    private List<int[]> keepClear(LocalPlayer p) {
        var run = c.stepRun.steps;
        int idx = run.index();
        if (idx < 0) return List.of();
        IBaritone b = Jobs.baritone();
        int[] feet = Jobs.here(p);
        int r = Tuning.i("steps.placeNearR");
        // 0.39.z77 craft (run DA 00:35:53): my station a later craft / smelt uses is never the cell the fork clears
        List<int[]> keep = new ArrayList<>(io.github.mojolowjo.entropybot.aa.moves.KeepClear.stations(run.steps(), idx + 1, feet, r,
                c.nearby::blocksOfKind, io.github.mojolowjo.entropybot.clear.OwnStations.placed()));
        List<int[]> walk = io.github.mojolowjo.entropybot.aa.moves.KeepClear.cells(run.steps(), idx + 1, feet, r, goal -> {
            if (b == null) return null;
            List<int[]> out = new ArrayList<>();
            try {
                for (baritone.api.pathing.calc.IPath seg : b.getPricedPath().keptSegments(LivePathPrice.goal(new BlockPos(goal[0], goal[1], goal[2]))))
                    for (var q : seg.positions()) out.add(new int[]{q.getX(), q.getY(), q.getZ()});
            } catch (LinkageError e) {
                return null;                     // a fork without kept segments: no walk cells known (said by the probe's install line)
            }
            return out;
        });
        for (int[] w : walk) {
            boolean dup = false;
            for (int[] o : keep) if (o[0] == w[0] && o[1] == w[1] && o[2] == w[2]) { dup = true; break; }
            if (!dup) keep.add(w);
        }
        return keep;
    }

    private static String keepText(List<int[]> cells) {
        List<String> s = new ArrayList<>();
        for (int i = 0; i < cells.size() && i < 6; i++) s.add(cells.get(i)[0] + " " + cells.get(i)[1] + " " + cells.get(i)[2]);   // structural: a log line names at most 6
        return String.join(", ", s) + (cells.size() > 6 ? ", ..." : "");   // structural: as above
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
            LocalPlayer pl = Minecraft.getInstance().player;
            // 0.39.z60 item 3: re-plan, never park; 0.39.z67 item 1: the fact names where it failed (null player: the plain fact)
            io.github.mojolowjo.entropybot.aa.moves.FactChange.placeFailed(n.item(), r.why(), pl == null ? null : Jobs.here(pl));
            if (pl != null) placeSiteProbe(pl, n.item(), r.why());
            return Car.Status.failed(r.why());
        }
        return Car.Status.running(r.why());
    }

    // ---- to a height band: one Baritone goal (GoalYLevel to the nearest Y of the band); done once the feet are in it ----

    /**
     * 0.39.z65 item 3 (run V 07:05:35: "to y 5..31: Baritone's goal ended at y 62 (no path into the band)"): the band is
     * reached by Baritone's priced path to the cell of the feet's column at the band's nearest Y (as the strip's first leg,
     * ToStrip) - the fork steers a priced goal's searches (DigDownGoal below, z63 ClimbGoal above), which a plain
     * GoalYLevel never got (it has no one goal position). Asked when the step starts, walked once its complete path is known
     * (rule 18); computing / chunks not loaded: the step waits - bounded by the step watch (Steps: steps.stepMaxS, past it
     * the ToBand step's timeout is its band's source fact and the order re-plans; and the no-progress watch,
     * jobPort.noProgressMs) - never an open wait; no path: the band fact as before.
     */
    private int[] bandCell;
    private boolean bandWalking;

    private String toBand(LocalPlayer p, Step.ToBand t) {
        int[] me = Jobs.here(p);
        int y = me[1];
        bandCell = null;
        bandWalking = false;
        digFrom = me.clone();
        if (t.until() != null) {
            String seen = baseUnderFeet(p, t);
            if (seen != null) { instant = "ok: " + seen; return null; }
        } else if (y >= t.minY() && y <= t.maxY()) { instant = "ok: already at y " + y + " (band " + t.minY() + ".." + t.maxY() + ")"; return null; }
        IBaritone b = Jobs.baritone();
        if (b == null) return "error: baritone not loaded";
        // J6b queue item 14 (run ER: the floorless edge cell -10 61 69): the leg's column is the cell Baritone's searches start
        // from (its pathStart: the supporting block off an edge), standable or said
        Start st = searchStart(b, p);
        bandStart = st;
        bandCell = new int[]{st.cell()[0], t.target(st.cell()[1]), st.cell()[2]};
        if (t.until() != null) digFrom = st.cell().clone();
        LOG.info("[entropybot] steps: {} - Baritone's priced path to {} (the band's nearest Y under the search's start {}{}; walked once its path is known)", t.text(), Jobs.fmt(bandCell),
                Jobs.fmt(st.cell()), st.standable() ? "" : " - it has no floor: a no-path from it is not known, never the band's fact");
        return null;
    }

    /** J6b item 14: the band leg's search start (its cell and floor). */
    private Start bandStart;

    /** Run EP: the dig's start cell (the named fact's "under <cell>"). */
    private int[] digFrom;

    /**
     * Run EP (rule 17): the block under the feet - the one the bot stands on, observed - is one of the step's {@code until}
     * blocks: the done words; null: not (or not loaded). Only the observed state; no block under the top is read.
     */
    private static String baseUnderFeet(LocalPlayer p, Step.ToBand t) {
        int[] me = Jobs.here(p);
        BlockPos under = new BlockPos(me[0], me[1] - 1, me[2]);
        if (!p.level().isLoaded(under)) return null;
        String id = BuiltInRegistries.BLOCK.getKey(p.level().getBlockState(under).getBlock()).toString();
        return t.doneOn(id) ? id.replace("minecraft:", "") + " seen under the feet at y " + under.getY() : null;
    }

    private Car.Status toBandStatus(LocalPlayer p, Step.ToBand t) {
        if (instant != null) return Car.Status.done(instant);
        int y = Jobs.here(p)[1];
        IBaritone b = Jobs.baritone();
        if (t.until() != null) {
            // run EP: done on the OBSERVED base block under the feet; the cap reached without it: the named fact
            String seen = baseUnderFeet(p, t);
            if (seen != null) {
                if (b != null) Jobs.cancel(b);
                return Car.Status.done("ok: " + seen);
            }
            if (y <= t.minY()) {
                int[] from = digFrom == null ? Jobs.here(p) : digFrom;
                String why = t.capFact(from);
                if (b != null) Jobs.cancel(b);
                io.github.mojolowjo.entropybot.aa.moves.FactChange.band(t.minY(), t.maxY(), why);     // the order re-plans without this dig
                return Car.Status.failed(t.text() + ": " + why);
            }
        } else if (y >= t.minY() && y <= t.maxY()) {
            if (b != null) Jobs.cancel(b);
            return Car.Status.done("ok: at y " + y + " (band " + t.minY() + ".." + t.maxY() + ")");
        }
        if (b == null) return Car.Status.failed("baritone not loaded");
        if (!bandWalking && bandCell != null) {
            io.github.mojolowjo.entropybot.aa.PathPrice.Answer a = LivePathPrice.INSTANCE.priceInto(bandCell[0], bandCell[1], bandCell[2], Math.abs(y - bandCell[1]));   // z76 send-back 2: the feet IN the band
            // J6b queue item 14 (the manager's ER ruling, rule 15): a budget stop / an estimate is NOT KNOWN, never "no path";
            // only a complete search's no-path from a standable start is the band's fact (BandLeg)
            Start st = bandStart != null ? bandStart : searchStart(b, p);
            var next = io.github.mojolowjo.entropybot.aa.moves.BandLeg.of(a, st.cell(), st.standable());
            switch (next.act()) {
                case WALK -> {
                    io.github.mojolowjo.entropybot.guard.PolicyRuntime.INSTANCE.setIntent(io.github.mojolowjo.entropybot.guard.BreakPolicy.Intent.TRAVEL, "to band");
                    LivePathPrice.walkInto(b, new BlockPos(bandCell[0], bandCell[1], bandCell[2]));   // z76 send-back 2 (run AN: ended at y 57 over 56..56)
                    bandWalking = true;
                    return Car.Status.running(t.text() + ": " + next.why() + " to " + Jobs.fmt(bandCell));
                }
                case WAIT -> {
                    return Car.Status.running(t.text() + ": the path to " + Jobs.fmt(bandCell) + " is " + next.why());
                }
                case FACT -> {
                    io.github.mojolowjo.entropybot.aa.moves.FactChange.band(t.minY(), t.maxY(), next.why());     // 0.39.z42: a fact change, the order re-plans
                    return Car.Status.failed(t.text() + ": " + next.why());
                }
                default -> {
                    io.github.mojolowjo.entropybot.aa.moves.FactChange.bandNotKnown(t.minY(), t.maxY(), next.why());   // re-plans, nothing held
                    return Car.Status.failed(t.text() + ": " + next.why());
                }
            }
        }
        if (!b.getCustomGoalProcess().isActive() && !Core.INSTANCE.reflexes.hold()) {
            // J6b item 14: the walk's own end is the band's fact only from a standable start; else not known (rule 15)
            Start st = bandStart != null ? bandStart : searchStart(b, p);
            // J6b (job-vein a2d4e1c8): the walk's own last calc decides - only an exhausted graph is "no path"
            baritone.api.process.ICustomGoalProcess.WalkCalc wc;
            try {
                wc = b.getCustomGoalProcess().lastCalc();
            } catch (LinkageError e) {
                wc = null;                               // an older fork: the stop is not known
            }
            String nk = io.github.mojolowjo.entropybot.aa.moves.BandLeg.walkEnd(wc == null ? null : wc.exhausted(), st.cell(), st.standable(), y);
            if (nk != null) {
                io.github.mojolowjo.entropybot.aa.moves.FactChange.bandNotKnown(t.minY(), t.maxY(), nk);
                return Car.Status.failed(t.text() + ": " + nk);
            }
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
        stripWalking = true;                     // 0.39.z76 review: the car drives this step from here (drivesCar)
        return null;
    }

    /** 0.39.z76 review: the strip leg's walk started (the car-idle check judges it only then). */
    private boolean stripWalking;

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
            // strip re-review 1: the walk down names no cause along the heading - a walk that ended short (its own rule: the same
            // target short again at the same feet parks the try; no heading held)
            io.github.mojolowjo.entropybot.aa.moves.FactChange.walkShort(new int[]{t.x(), t.y(), t.z()}, Jobs.here(p), why);
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
