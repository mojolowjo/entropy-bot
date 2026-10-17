package io.github.mojolowjo.entropybot.commands;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.pathing.goals.GoalGetToBlock;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import io.github.mojolowjo.entropybot.Core;
import io.github.mojolowjo.entropybot.commands.JobRequests.Request;
import io.github.mojolowjo.entropybot.engine.Reflexes;
import io.github.mojolowjo.entropybot.guard.Guard;
import io.github.mojolowjo.entropybot.move.SurfaceSeal;
import io.github.mojolowjo.entropybot.restore.EscapePlan;
import io.github.mojolowjo.entropybot.restore.RestoreRules;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

/**
 * The mod's own job slot (B7b, part 1): walks (goto, come, go, base, a teleport home, the walk to a bed), wait and
 * twerk, ported from the bridge's startTravel / stepWalkJob / startHome / startSetSpawn / startWait / startTwerk with
 * the same wording. A walk listens to Baritone's path events (no path after 3 failed calculations, a stuck watchdog
 * for goals it can't reach), holds still while a reflex runs, stops for a retreat, an urgent fight (not a walk) or
 * the area fence, and reports through the request it carries (a chain waits on it).
 */
public final class Jobs {
    private static final Logger LOG = LogUtils.getLogger();
    static final int TRAVEL_FAILS = 3;
    static final long TRAVEL_STUCK_TICKS = 600;

    public static final class Job {
        public String type, status, label, goal;
        public boolean done, reflex;
        /** 28b: a walk whose goal the fence allowed (inside an area); the position watch leaves it be, so it can walk back in. */
        boolean goalInside;
        Request req;
        long startTick, until, total, ticks;
        int[] dest;
        String bed;
        // the teleport home: where the bot was at the last check, when /home was sent
        int[] tpLast;
        String tpDim;
        long tpTick = -1;
        String tpNote;
        // path events since the goal
        long evSeq = -1;
        int evFails;
        boolean evAtGoal;
        double bestDist;
        long bestTick, lastStepTick = -1;
        // stepping off a block Baritone can't plan from (a modded altar, a pedestal): ticks left, tries made
        int unstickLeft, unstickTries;
        /** P1 fix: how often a walk that Baritone ended short of its goal tried again (WalkEnd). */
        int shortTries;
        /** 0.31.1: a goto into a house that ended short walks to the doorway's outside step once (doorFinal: the real goal). */
        boolean doorTried;
        int[] doorFinal;
        /** 0.32.2 (P2): stuck under ground: the walked trail back in hops (retraceFinal: the real goal after them). */
        boolean retraceTried;
        /** 0.33.0 (A1): the paper escape (walk plan, then digging plan) was started for this walk: no staircase or dig-out after it. */
        boolean paperEscape;
        java.util.ArrayDeque<int[]> retraceHops;
        int[] retraceFinal;
        int retraceSkips;
        double[] unstickTo;
        // B7b part 2: a job made of steps, the goal object of its walk (unsticking plans it again), and whether the
        // open menu is closed when it ends ("always", or "fail" = unless it ended ok)
        Seq seq;
        baritone.api.pathing.goals.Goal goalObj;
        String closeOnEnd;
        /**
         * B7d (D3): an urgent fight holds this job instead of ending it (the bridge's job.holdOnFight: explore, mine
         * cave, the Baritone mine); onHold runs when a reflex starts holding it (the Baritone mine stops and breaking
         * goes off), onEnd however it ends (finish, stop, a reflex, the fence): breaking off, leases released.
         * ownsBreaking: the job turned Baritone's breaking on (SafetyNet leaves it be while Baritone's mine runs).
         */
        public boolean holdOnFight, ownsBreaking;
        /** C5: junk thrown away while this job ran (its end line says "dropped N junk"). */
        public int junkDropped;
        public Runnable onHold, onEnd;
        /**
         * Routing stage 1 (R3): this walk's use of the router (null = a plain walk, as before), the goal the router
         * wraps (a travel's "goto x y z" as a GoalBlock, a Seq walk's own goal), and the current leg's end in legs mode
         * (the stuck watchdog measures progress to it).
         */
        io.github.mojolowjo.entropybot.routewalk.RouteWalk route;
        baritone.api.pathing.goals.Goal plainGoal;
        int[] legDest;
        /** P1: unique across restarts (the restore ledger's entries name their job by it). */
        public final long id = NEXT_ID.incrementAndGet();
        /** P1: the restore that runs before the job's end line (restoreMsg: that line), or mid-walk after an escape (restoreResume). */
        RestoreRun restoreRun;
        String restoreMsg;
        boolean restoreDone, restoreResume;
        /** P1: the escape dig-out: cells to break (highest first), the one at, the step out, its lease, the clock. */
        java.util.List<int[]> escapeBreaks;
        int escapeIdx, escapes, escapeDug, escapeRestored;
        int[] escapeTo, escapeAt, escapeHit;
        String escapeLease;
        /** 0.27.2: the surface dig-out's one-cell leases. */
        io.github.mojolowjo.entropybot.clear.LeaseSet escapeLeaseSet;
        long escapeTick;
        /** A travel dug out and has not put those blocks back yet (done once it is 3+ blocks away). */
        boolean escapeWaiting;
        /** 0.23.3: this walk runs through the long-route process (LongRouteProcess; its walk id is this job's id). */
        boolean longRoute;
    }

    static final java.util.concurrent.atomic.AtomicLong NEXT_ID = new java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis());
    /** P1: escapes per job (each one step out). */
    static final int ESCAPE_MAX = 5;
    static final long ESCAPE_BLOCK_TICKS = 200;

    static final int UNSTICK_TRIES = 2, UNSTICK_TICKS = 12;
    static final long UNSTICK_EARLY = 100;

    private final Core core;
    private final Commands commands;
    public Job job;
    private long tpFailedAt = -100000, holdStart = -1;
    private Reflexes.Reflex reflexWas = Reflexes.Reflex.NONE;
    /** A follow the fence keeps an eye on: {name, from}. */
    String[] followWatch;
    /**
     * A follow of a player the bot cannot see, led by the position their companion mod sends (OwnerFix): {name, from}.
     * The walk is a plain "goto" to that spot, issued again when they move; Baritone's own follow takes over when they
     * come into view. Any other job or a stop clears it.
     */
    String[] followFix;
    private int[] followFixGoal;
    private long followFixAt;

    Jobs(Core core, Commands commands) {
        this.core = core;
        this.commands = commands;
        io.github.mojolowjo.entropybot.baritone.LongRouteProcess.INSTANCE.setHost(new io.github.mojolowjo.entropybot.baritone.LongRouteProcess.Host() {
            @Override
            public boolean alive(long walkId) { return job != null && !job.done && job.id == walkId && job.longRoute; }

            @Override
            public boolean canDigOut(int[] me) { return digOutAllowed(me); }

            @Override
            public boolean boxedIn(int[] me) {
                LocalPlayer p = Minecraft.getInstance().player;
                return p != null && bestFreeStep(p, null) == null;
            }

            @Override
            public boolean insideAreas(int x, int z) {         // 0.27.3: with no area in this dimension: everywhere
                String dim = Storage.dim();
                return commands.areaRects(dim).isEmpty() || commands.inAreas(dim, x, z);
            }

            @Override
            public int[] insideFor(int[] xz) {
                String dim = Storage.dim();
                if (commands.inAreas(dim, xz[0], xz[1])) return null;
                var t = io.github.mojolowjo.entropybot.brain.AreaReturn.nearest(commands.areaRects(dim), xz[0], xz[1]);
                return t == null ? null : new int[]{t.x(), t.z()};
            }

            @Override
            public String pauseCause() {
                String c = walkReflex;
                walkReflex = null;
                return c;
            }
        });
    }

    /** 0.23.4: the last reflex (fighting, eating...) seen while a long walk ran, for its "after fight" note. */
    private String walkReflex;
    /** 0.23.4: the walk build hint's clock (one whisper a minute at most). */
    private long walkHintAt;

    /**
     * 0.23.3: the movement package's tick (MovePackage): instant start / keep-moving inputs, the settings profile, the
     * ready paths to home, base, mine, farm, the owner and a chain's next walk. Never throws.
     */
    public void movementTick(LocalPlayer p) {
        try {
            IBaritone b = baritone();
            boolean walk = running() && (walking() || (job.seq != null && job.status != null && job.status.contains("walking")));
            boolean breaking = false;
            try { breaking = BaritoneAPI.getSettings().allowBreak.value; } catch (Throwable ignored) {}
            boolean fleeing = core.reflexes.reflex() == Reflexes.Reflex.FLEEING || core.reflexes.reflex() == Reflexes.Reflex.RETREATING;
            boolean idle = !commands.busyForQueue() && b != null && idle(b) && !core.reflexes.hold();
            if (running() && job.longRoute && core.reflexes.reflex() != Reflexes.Reflex.NONE) walkReflex = core.reflexes.reflex().name();
            if (walk && !fleeing && core.tick() % 40 == 21) walkBuildHint(p);
            java.util.Map<String, io.github.mojolowjo.entropybot.route.Cell> targets = new java.util.LinkedHashMap<>();
            if (core.tick() % 10 == 3) {
                JsonObject h = commands.home();
                String dim = Guard.dimOf(p.level());
                if (h != null && dimOf(h).equals(dim)) targets.put("home", cell(pos(h)));
                for (String name : new String[]{"base", "mine", "farm"}) {
                    JsonObject pl = core.knowledge.places().get(name);
                    if (pl != null && pl.has("x") && dimOf(pl).equals(dim)) targets.put(name, cell(pos(pl)));
                }
                net.minecraft.world.entity.player.Player o = null;
                for (net.minecraft.world.entity.player.Player q : p.level().players()) if (q.getGameProfile().getName().equalsIgnoreCase(commands.owner())) o = q;
                if (o != null && o != p) targets.put("owner", cell(here(o)));
            }
            int[] next = null;
            Chains ch = commands.chainsRef();
            String step = ch != null && ch.running() ? ch.nextStep() : null;
            if (step != null) {
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("^goto (-?\\d+) (-?\\d+) (-?\\d+)$").matcher(step.trim());
                if (m.find()) next = new int[]{Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3))};
            }
            if (next == null && running() && job.seq != null) next = job.seq.nextWalkGoal();
            io.github.mojolowjo.entropybot.move.MovePackage.INSTANCE.tick(p, core.tick(), walk, breaking, fleeing, idle, targets,
                    next == null ? null : cell(next));
        } catch (RuntimeException e) {
            LOG.warn("[entropybot] movement tick: {}", e.toString());
        }
    }

    private static io.github.mojolowjo.entropybot.route.Cell cell(int[] a) { return new io.github.mojolowjo.entropybot.route.Cell(a[0], a[1], a[2]); }

    /**
     * 0.23.3: the surface over x z with leaves skipped. The client level keeps only the MOTION_BLOCKING and
     * WORLD_SURFACE heightmaps (MOTION_BLOCKING_NO_LEAVES read -64 live, so the dig-out never counted as underground):
     * walk down from MOTION_BLOCKING past leaves. Vanilla API only (Heightmap, BlockTags.LEAVES).
     */
    static int surfaceNoLeaves(net.minecraft.world.level.Level level, int x, int z) {
        int y = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, x, z) - 1;
        int min = level.getMinBuildHeight();
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos(x, y, z);
        while (y > min) {
            m.setY(y);
            var st = level.getBlockState(m);
            if (!st.is(net.minecraft.tags.BlockTags.LEAVES) && st.blocksMotion()) break;
            y--;
        }
        return y + 1;
    }

    /** 0.28.1 (trial fix A): one cell for SurfaceRule, from the level (vanilla BlockState/BlockTags only). */
    static io.github.mojolowjo.entropybot.world.SurfaceRule.Cell surfaceCell(net.minecraft.world.level.Level level, int x, int y, int z) {
        BlockPos bp = new BlockPos(x, y, z);
        if (!level.isLoaded(bp)) return io.github.mojolowjo.entropybot.world.SurfaceRule.Cell.SOLID;      // unknown: stop there
        var st = level.getBlockState(bp);
        if (!st.getFluidState().isEmpty() && st.getCollisionShape(level, bp).isEmpty()) return io.github.mojolowjo.entropybot.world.SurfaceRule.Cell.FLUID;
        if (st.is(net.minecraft.tags.BlockTags.LEAVES)) return io.github.mojolowjo.entropybot.world.SurfaceRule.Cell.LEAVES;
        if (st.is(net.minecraft.tags.BlockTags.LOGS)) return io.github.mojolowjo.entropybot.world.SurfaceRule.Cell.LOG;
        if (st.is(net.minecraft.world.level.block.Blocks.SNOW)) return io.github.mojolowjo.entropybot.world.SurfaceRule.Cell.SNOW_LAYER;
        if (st.isAir()) return io.github.mojolowjo.entropybot.world.SurfaceRule.Cell.AIR;
        if (st.getCollisionShape(level, bp).isEmpty()) return io.github.mojolowjo.entropybot.world.SurfaceRule.Cell.PLANT;
        return io.github.mojolowjo.entropybot.world.SurfaceRule.Cell.SOLID;
    }

    /** 0.28.1: the ground under x y z (SurfaceRule.groundFeet): {x, feet y, z}; the spot itself when the level is missing. */
    public static int[] groundUnder(net.minecraft.world.level.Level level, int x, int y, int z) {
        if (level == null) return new int[]{x, y, z};
        try {
            int g = io.github.mojolowjo.entropybot.world.SurfaceRule.groundFeet(yy -> surfaceCell(level, x, yy, z), y, level.getMinBuildHeight());
            return new int[]{x, g, z};
        } catch (RuntimeException e) {
            LOG.warn("[entropybot] ground under {} {} {}: {}", x, y, z, e.toString());
            return new int[]{x, y, z};
        }
    }

    /** 0.28.1: a walk target whose cell and the one under it are open (a mark left in the air). */
    /** 0.31.0 (5): the short id of the block filling x y z (it has a collision shape), null when free or not loaded. */
    static String solidAt(net.minecraft.world.level.Level level, int x, int y, int z) {
        if (level == null) return null;
        BlockPos g = new BlockPos(x, y, z);
        if (!level.isLoaded(g)) return null;
        var st = level.getBlockState(g);
        if (st.getCollisionShape(level, g).isEmpty()) return null;
        return net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(st.getBlock()).getPath();
    }

    /** 0.31.0 (5): {@link WalkEnd#freeSpotFor} on the level (feet and head free, a full block below, no fluid). */
    static int[] freeSpotFor(net.minecraft.world.level.Level level, int x, int y, int z) {
        return WalkEnd.freeSpotFor(x, y, z, c -> {
            BlockPos f = new BlockPos(c[0], c[1], c[2]);
            return level.isLoaded(f)
                    && level.getBlockState(f).getCollisionShape(level, f).isEmpty()
                    && level.getBlockState(f.above()).getCollisionShape(level, f.above()).isEmpty()
                    && level.getBlockState(f.below()).isCollisionShapeFullBlock(level, f.below())
                    && level.getFluidState(f).isEmpty();
        });
    }

    static boolean targetInAir(net.minecraft.world.level.Level level, int x, int y, int z) {
        if (level == null || !level.isLoaded(new BlockPos(x, y - 1, z))) return false;
        return io.github.mojolowjo.entropybot.world.SurfaceRule.inAir(yy -> surfaceCell(level, x, yy, z), y);
    }

    /** 0.23.3: the long walk's dig-out rule (the escape's own): underground and inside the areas. */
    boolean digOutAllowed(int[] me) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return false;
        int surface = surfaceNoLeaves(mc.level, me[0], me[2]);
        boolean sky = mc.level.canSeeSky(new BlockPos(me[0], me[1] + 1, me[2])) || RestoreRules.openAbove(surface, me[1]);
        boolean under = RestoreRules.underground(sky, surface, me[1]), in = commands.inAreas(Guard.dimOf(mc.level), me[0], me[2]);
        LOG.info("[entropybot] long walk: dig-out check at {}: underground {} (surface {}), in my areas {}", fmt(me), under, surface, in);
        if (under && in) return true;
        // 0.27.2: the surface dig-out (leaves, plants, own shelter blocks) wherever the bot stands
        try {
            return surfacePlan(mc.level, Guard.dimOf(mc.level), me, null, false) != null;
        } catch (RuntimeException e) {
            LOG.warn("[entropybot] surface dig-out check: {}", e.toString());
            return false;
        }
    }

    // ---- 0.27.2: the surface dig-out ----

    /**
     * 0.30.6 (live, 0.30.4: a failed walk's dig-out opened the house's cobblestone wall): the cell belongs to a house in
     * a main area (Policy.houseCell: the area box's shell, or my own block in its columns). Every dig-out and the
     * shelter's own-wall exit leave it alone; the door is the way out. Never throws (false on any error).
     */
    static boolean houseCell(String dim, int x, int y, int z) {
        try {
            return io.github.mojolowjo.entropybot.Core.INSTANCE.guard.core.policy().houseCell(dim, x, y, z, RestoreLive.INSTANCE.builtByMe(x, y, z));
        } catch (RuntimeException e) {
            LOG.warn("[entropybot] house cell check at {} {} {}: {}", x, y, z, e.toString());
            return false;
        }
    }

    /**
     * 0.30.7 (owner's rule 2026-10-11; live: stuck twice in the respawn pit at 0 136 9 inside the neutral area "arena"):
     * a dig-out may break this cell whatever it is (placed blocks, persistent leaves): Policy.digOutAny (own neutral or
     * destroy area, no safe area, no house cell), loaded, no fluid in or next to it, no block entity, breakable, no
     * falling block on top. Vanilla Level/BlockState calls only. Never throws (false on any error, logged).
     */
    static boolean digOutAny(net.minecraft.world.level.Level level, io.github.mojolowjo.entropybot.guard.Policy pol, String dim, int x, int y, int z) {
        try {
            BlockPos bp = new BlockPos(x, y, z);
            if (!level.isLoaded(bp)) return false;
            var st = level.getBlockState(bp);
            if (!st.getFluidState().isEmpty() || st.hasBlockEntity() || st.getDestroySpeed(level, bp) < 0) return false;
            if (Guard.liquidNextTo(level, x, y, z) != null) return false;
            if (level.getBlockState(bp.above()).getBlock() instanceof net.minecraft.world.level.block.FallingBlock) return false;
            return pol.digOutAny(dim, x, y, z, RestoreLive.INSTANCE.builtByMe(x, y, z));
        } catch (RuntimeException e) {
            LOG.warn("[entropybot] dig-out any-block check at {} {} {}: {}", x, y, z, e.toString());
            return false;
        }
    }

    /** The surface kind of one cell (SurfaceSeal), from the level. */
    SurfaceSeal.Kind surfaceKind(net.minecraft.world.level.Level level, int x, int y, int z) {
        BlockPos bp = new BlockPos(x, y, z);
        if (!level.isLoaded(bp)) return SurfaceSeal.Kind.NO;
        var st = level.getBlockState(bp);
        String id = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(st.getBlock()).toString();
        boolean leaves = st.is(net.minecraft.tags.BlockTags.LEAVES);
        boolean persistent = leaves && st.hasProperty(net.minecraft.world.level.block.LeavesBlock.PERSISTENT)
                && st.getValue(net.minecraft.world.level.block.LeavesBlock.PERSISTENT);
        // 0.31.0 (trial 10 snow pit): snow blocks go like snow layers (natural, a shovel's or a hand's work; still needs the lease)
        boolean plant = st.is(net.minecraft.tags.BlockTags.REPLACEABLE_BY_TREES) || st.is(net.minecraft.world.level.block.Blocks.SNOW)
                || st.is(net.minecraft.world.level.block.Blocks.SNOW_BLOCK);
        boolean own = (ShelterJob.ownBlocks(commands).contains(x + " " + y + " " + z) || RestoreLive.INSTANCE.builtByMe(x, y, z))
                && !houseCell(Guard.dimOf(level), x, y, z);         // 0.30.6: a house wall is not a shelter wall
        return SurfaceSeal.kind(id, leaves, persistent && !own, plant, own, st.hasBlockEntity(), Guard.liquidNextTo(level, x, y, z) != null);
    }

    /** The surface escape plan: EscapePlan over the surface kinds (breakable: leaves, plants, own blocks), or null. */
    EscapePlan.Escape surfacePlan(net.minecraft.world.level.Level level, String dim, int[] me, int[] dest, boolean towardGoal) {
        io.github.mojolowjo.entropybot.guard.Policy pol = core.guard.core.policy();
        return EscapePlan.plan((x, y, z) -> {
            BlockPos bp = new BlockPos(x, y, z);
            if (!level.isLoaded(bp)) return EscapePlan.Kind.BLOCKED;
            var st = level.getBlockState(bp);
            if (!st.getFluidState().isEmpty()) return EscapePlan.Kind.BLOCKED;
            if (st.getCollisionShape(level, bp).isEmpty() || DigCommands.lowSnow(level, bp, st)) return EscapePlan.Kind.OPEN;   // 0.31.0: low snow is walked through
            SurfaceSeal.Kind k = surfaceKind(level, x, y, z);
            // leaves and plants need the guard's lease like any dig; own blocks get the one-cell force lease (shelter leave's way)
            boolean ok = k == SurfaceSeal.Kind.OWN || (k != SurfaceSeal.Kind.NO && io.github.mojolowjo.entropybot.guard.GuardCore.cellLeasable(pol, dim, x, y, z));
            // 0.30.7: the owner's rule: in my own neutral/destroy areas any block (placed, persistent leaves) may go
            if (ok || digOutAny(level, pol, dim, x, y, z)) return EscapePlan.Kind.BREAKABLE;
            return st.isCollisionShapeFullBlock(level, bp) ? EscapePlan.Kind.FLOOR_ONLY : EscapePlan.Kind.BLOCKED;
        }, me, dest, towardGoal);
    }

    /** 0.27.3 (d1): sealed in my own shelter: its way out (ShelterPlan.exit) as the dig-out, head cell first; else null. */
    EscapePlan.Escape ownShelterExit(net.minecraft.world.level.Level level, int[] me) {
        try {
            java.util.Set<String> own = ShelterJob.knownOwn(commands, me);
            java.util.function.Predicate<int[]> closed = c -> !level.getBlockState(new BlockPos(c[0], c[1], c[2])).canBeReplaced();
            // 0.27.4: a natural cell the dig rules allow (underground inside my areas) may be the other half of the way out
            java.util.function.Predicate<int[]> mayDig = c -> level instanceof net.minecraft.client.multiplayer.ClientLevel cl
                    && closed.test(c) && ShelterJob.mayDig(commands, cl, me, c);
            java.util.List<int[]> r = SurfaceSeal.ownExit(me, own, closed, mayDig);
            if (r == null) return null;
            LOG.info("[entropybot] in my own shelter at {}: opening my own wall at {}", fmt(me), fmt(r.get(0)));
            return new EscapePlan.Escape(r.get(0), new java.util.ArrayList<>(r.subList(1, r.size())));
        } catch (RuntimeException e) {
            LOG.warn("[entropybot] own shelter exit: {}", e.toString());
            return null;
        }
    }

    /** 0.27.3 (d2): the surface dig-out's refusal, side by side ("east: oak_leaves (persistent...) at x y z; ..."). */
    String surfaceWhy(net.minecraft.world.level.Level level, String dim, int[] me) {
        try {
            io.github.mojolowjo.entropybot.guard.Policy pol = core.guard.core.policy();
            return EscapePlan.explain((x, y, z) -> {
                BlockPos bp = new BlockPos(x, y, z);
                if (!level.isLoaded(bp)) return EscapePlan.Kind.BLOCKED;
                var st = level.getBlockState(bp);
                if (!st.getFluidState().isEmpty()) return EscapePlan.Kind.BLOCKED;
                if (st.getCollisionShape(level, bp).isEmpty() || DigCommands.lowSnow(level, bp, st)) return EscapePlan.Kind.OPEN;   // 0.31.0: low snow is walked through
                SurfaceSeal.Kind k = surfaceKind(level, x, y, z);
                boolean ok = k == SurfaceSeal.Kind.OWN || (k != SurfaceSeal.Kind.NO && io.github.mojolowjo.entropybot.guard.GuardCore.cellLeasable(pol, dim, x, y, z));
                if (ok || digOutAny(level, pol, dim, x, y, z)) return EscapePlan.Kind.BREAKABLE;
                return st.isCollisionShapeFullBlock(level, bp) ? EscapePlan.Kind.FLOOR_ONLY : EscapePlan.Kind.BLOCKED;
            }, c -> surfaceWhat(level, dim, c), me);
        } catch (RuntimeException e) {
            return "(explaining failed: " + e + ")";
        }
    }

    /** 0.27.3 (d2): what a cell is for the surface dig-out's refusal line (why it may not be broken). */
    String surfaceWhat(net.minecraft.world.level.Level level, String dim, int[] c) {
        try {
            BlockPos bp = new BlockPos(c[0], c[1], c[2]);
            if (!level.isLoaded(bp)) return "not loaded";
            var st = level.getBlockState(bp);
            String id = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(st.getBlock()).getPath();
            if (!st.getFluidState().isEmpty()) return id + " (a fluid)";
            if (st.getCollisionShape(level, bp).isEmpty()) return id + " (open)";
            if (houseCell(dim, c[0], c[1], c[2])) return id + " (my house: the door is the way out)";
            if (digOutAny(level, core.guard.core.policy(), dim, c[0], c[1], c[2])) return id + " (in my own area: may go)";
            SurfaceSeal.Kind k = surfaceKind(level, c[0], c[1], c[2]);
            if (k == SurfaceSeal.Kind.NO) {
                if (st.is(net.minecraft.tags.BlockTags.LEAVES)) return id + " (persistent: placed by a player, never broken)";
                if (st.hasBlockEntity()) return id + " (a container or machine)";
                if (Guard.liquidNextTo(level, c[0], c[1], c[2]) != null) return id + " (next to water or lava)";
                return id + " (not leaves, a plant or my own block)";
            }
            if (k != SurfaceSeal.Kind.OWN && !io.github.mojolowjo.entropybot.guard.GuardCore.cellLeasable(core.guard.core.policy(), dim, c[0], c[1], c[2]))
                return id + " (not leasable: outside my areas' box or in a protect box)";
            return id;
        } catch (RuntimeException e) {
            return "unknown (" + e + ")";
        }
    }

    /** 0.27.2: starts the surface dig-out: one lease per cell, own blocks noted as the shelter's door. False when none. */
    private boolean startSurfaceEscape(LocalPlayer p, Job j, int[] me, String dim, boolean towardGoal) {
        Minecraft mc = Minecraft.getInstance();
        net.minecraft.world.level.Level level = mc.level;
        EscapePlan.Escape e = ownShelterExit(level, me);          // 0.27.3 (d1): in my own shelter: open my own wall first
        if (e == null) e = surfacePlan(level, dim, me, travelDest(j), towardGoal);
        if (e == null) return false;
        IBaritone b = baritone();
        if (b != null) cancel(b);
        j.escapes++;
        if (e.breaks().isEmpty()) {
            j.unstickLeft = UNSTICK_TICKS;
            j.unstickTo = new double[]{e.feet()[0] + 0.5, e.feet()[1], e.feet()[2] + 0.5};
            return true;
        }
        io.github.mojolowjo.entropybot.clear.LeaseSet ls = Clearing.newLeases();
        int leaves = 0, plants = 0, own = 0, natural = 0;
        java.util.List<int[]> doors = new java.util.ArrayList<>();
        for (int[] c : e.breaks()) {
            SurfaceSeal.Kind k = surfaceKind(level, c[0], c[1], c[2]);
            // 0.30.7: a placed block in my own area (the owner's rule) needs the one-cell force lease like my own blocks
            boolean force = k == SurfaceSeal.Kind.OWN || core.guard.isProtectedBlock(level.getBlockState(new BlockPos(c[0], c[1], c[2])).getBlock())
                    && digOutAny(level, core.guard.core.policy(), dim, c[0], c[1], c[2]);
            String le = ls.take("surface dig-out at " + fmt(c), new io.github.mojolowjo.entropybot.clear.ClearBox(c[0], c[1], c[2], c[0], c[1], c[2]), false, force);
            if (le != null && core.guard.core.mode() == io.github.mojolowjo.entropybot.guard.GuardCore.Mode.STRICT) {
                ls.releaseAll();
                LOG.info("[entropybot] stuck at {}: the guard refused the surface dig-out at {} ({})", fmt(me), fmt(c), le);
                return false;
            }
            if (k == SurfaceSeal.Kind.OWN) { own++; doors.add(c); } else if (k == SurfaceSeal.Kind.LEAVES) leaves++;
            else if (k == SurfaceSeal.Kind.NO) natural++;          // 0.27.4: natural stone of a pit's way out (mayDig)
            else plants++;
        }
        j.escapeLeaseSet = ls;
        j.escapeLease = null;
        j.escapeBreaks = e.breaks();
        j.escapeIdx = 0;
        j.escapeHit = null;
        j.escapeTo = e.feet();
        j.escapeTick = core.tick();
        if (j.escapeAt == null) j.escapeAt = me;
        // never put back: leaves and plants are not restored, own shelter blocks become the shelter's door
        if (!doors.isEmpty()) ShelterJob.noteDoor(commands, doors);
        if (own > 0 && natural == 0) holdAxe(mc, p); else holdPickaxe(mc, p);          // 0.27.4: stone needs the pickaxe
        io.github.mojolowjo.entropybot.move.MovePackage.INSTANCE.surfaceDigOut(leaves, plants + natural, own);
        LOG.info("[entropybot] stuck at {} on the surface with no free step: dig-out (surface) of {} block(s) (leaves {}, own blocks {}, plants {}) to {}",
                fmt(me), e.breaks().size(), leaves, own, plants, fmt(e.feet()));
        core.events.push("job", "escape: surface dig-out of " + e.breaks().size() + " block(s) at " + fmt(me), null);
        return true;
    }

    private static void holdAxe(Minecraft mc, LocalPlayer p) {
        String[] tiers = {"netherite_axe", "diamond_axe", "iron_axe", "stone_axe", "golden_axe", "wooden_axe"};
        for (String t : tiers)
            for (int i = 0; i < 36; i++) {
                net.minecraft.world.item.ItemStack s = p.getInventory().getItem(i);
                if (!s.isEmpty() && io.github.mojolowjo.entropybot.gui.Gui.itemId(s).endsWith(t) && !io.github.mojolowjo.entropybot.gui.Gui.itemId(s).endsWith("pickaxe")) {
                    io.github.mojolowjo.entropybot.engine.Hotbar.toHand(mc, p, i);
                    return;
                }
            }
    }

    /**
     * 0.23.3: starts j's walk to d through the long-route process when path assist is on and d is far (LegPlan); false:
     * walk as before. finalGoal: the walk's own goal (GoalBlock for a goto, a Seq step's goal).
     */
    boolean startLong(LocalPlayer p, Job j, int[] d, baritone.api.pathing.goals.Goal finalGoal) {
        var mp = io.github.mojolowjo.entropybot.move.MovePackage.INSTANCE;
        int[] me = here(p);
        if (d == null || !mp.wantsLong(me, d)) return false;
        IBaritone b = baritone();
        if (b == null) return false;
        safeSettings();
        j.longRoute = true;
        j.route = null;
        j.legDest = null;
        j.goalObj = null;
        io.github.mojolowjo.entropybot.baritone.LongRouteProcess.INSTANCE.start(j.id, me, d, finalGoal, RouteWalker.radiusOf(finalGoal));
        mp.walkStarted(p, d, core.tick());
        return true;
    }

    /**
     * 0.23.3: one tick of a long walk: "wait" while it runs, "ok" arrived, else the failure line. A dig-out it asks for
     * starts here (the job's escape); a walk Baritone cancelled (an unstick, a stray cancelEverything) resumes.
     */
    String longTick(LocalPlayer p, Job j) {
        var lr = io.github.mojolowjo.entropybot.baritone.LongRouteProcess.INSTANCE;
        if (lr.walkId() != j.id) return "error: the long walk was replaced";
        if (lr.takeDigOut()) {
            // 0.33.0 (A1, live: a sealed start under ground dug out before the trail retrace): the retrace first, and
            // after it (or with no trail) the paper plans; the dig-out only where neither applies
            if (!j.retraceTried && underGround(p)) {
                lr.stop();
                j.longRoute = false;
                if (startRetrace(p, j, baritone(), "sealed at the start")) return "wait";
                j.longRoute = true;
                if (j.paperEscape) return "error: " + paperEscapeEnd(p).replaceFirst("^error: ", "");
            }
            if (j.paperEscape) return "error: " + paperEscapeEnd(p).replaceFirst("^error: ", "");
            if (startEscape(p, j, true)) {
                io.github.mojolowjo.entropybot.move.MovePackage.INSTANCE.digOutStarted(core.tick());     // 0.23.4: counted in path status
                return "wait";
            }
            lr.digOutFailed(here(p));
        }
        if (lr.arrived()) return "ok";
        if (lr.failure() != null) {
            int[] d = travelDest(j) != null ? travelDest(j) : j.dest;
            if (d != null && WalkEnd.arrived(here(p), d)) return "ok";       // the goal block itself is unreachable but I'm there (goalVerdict judges it)
            // 0.33.0 (A1, live: the staircase fired before the retrace inside an area): the trail retrace and the paper
            // escape come first; the staircase only for a walk whose retrace was tried and no paper escape runs
            String st = j.retraceTried && !j.paperEscape ? sealedStairs(p, here(p), d) : null;
            if (st != null) return "ok: " + st;                               // 0.27.4: the job ends with the whisper; the staircase runs next
            return lr.failure();
        }
        if (lr.active()) return "wait";
        lr.resume(here(p), lr.cancelled() ? "after a cancel" : "the process stopped");
        return "wait";
    }

    public boolean running() { return job != null && !job.done; }

    Core core() { return core; }

    Commands commands() { return commands; }

    /** A job made of steps (open, scan, deposit, corpse, rs, pots...): "started: <label>". */
    String startSeq(Seq s, String closeOnEnd) {
        followWatch = null;
        followFix = null;
        Job j = new Job();
        j.type = "seq";
        j.startTick = core.tick();
        j.label = s.label;
        j.status = s.label;
        j.seq = s;
        j.closeOnEnd = closeOnEnd;
        s.stepStart = core.tick();
        job = j;
        return "started: " + s.label;
    }

    public boolean walking() { return running() && (job.type.equals("travel") || job.type.equals("spawn")); }

    static IBaritone baritone() {
        try { return BaritoneAPI.getProvider().getPrimaryBaritone(); } catch (Throwable t) { return null; }
    }

    static boolean idle(IBaritone b) {
        return !b.getPathingBehavior().isPathing() && b.getPathingControlManager().mostRecentInControl().isEmpty();
    }

    static void cancel(IBaritone b) {
        try { b.getPathingBehavior().cancelEverything(); } catch (Throwable ignored) {}
    }

    /** A walk never breaks or places (the bridge's restoreSafeSettings, for Baritone's part). */
    static void safeSettings() {
        try {
            // 0.32.3: a plan-go job keeps the breaking/placing its plan needs (PaperPlan owns them for that job only)
            BaritoneAPI.getSettings().allowBreak.value = PaperPlan.ownsBreak();
            BaritoneAPI.getSettings().allowPlace.value = PaperPlan.ownsPlace();
        } catch (Throwable ignored) {}
    }

    static int[] here(Player p) {
        return new int[]{(int) Math.floor(p.getX()), (int) Math.floor(p.getY()), (int) Math.floor(p.getZ())};
    }

    static String fmt(int[] p) { return p[0] + " " + p[1] + " " + p[2]; }

    static long distSq(int[] a, int[] b) {
        long dx = a[0] - b[0], dy = a[1] - b[1], dz = a[2] - b[2];
        return dx * dx + dy * dy + dz * dz;
    }

    // ---- the request a started job carries ----

    /** After a start: a job that runs gets a request (the caller hands it on), else null. */
    Request attach(String kind, String from, String text, String reply, JobRequests.Listener l) {
        if (!running() || job.req != null) return null;
        job.req = commands.requests.local(kind, from, text, reply, l);
        return job.req;
    }

    /** Ends the job: its request hears msg (the requester's whisper, a chain's next step). */
    /**
     * 0.28.1 (trial fix C): a walk or dig that ends short ("couldn't get there", "stuck") with the bot 3+ under the
     * surface and no sky over it says so, with "next: climb" (trial 1: both bots ended in their own pit, no hint).
     */
    private String holeNote(Job j, String msg) {
        try {
            if (msg == null || msg.startsWith("ok") || msg.contains("next: climb")) return msg;
            String low = msg.toLowerCase();
            if (!(j.type.equals("travel") || low.contains("couldn't get there") || low.contains("stuck"))) return msg;
            LocalPlayer p = Minecraft.getInstance().player;
            if (p == null) return msg;
            int x = p.getBlockX(), y = p.getBlockY(), z = p.getBlockZ();
            int surface = surfaceNoLeaves(p.level(), x, z);
            boolean sky = p.level().canSeeSky(new BlockPos(x, y + 1, z));
            if (!ClimbPlan.inHole(y, surface, sky)) {
                // 0.28.2 (D): at the surface, walled in on all four sides (feet or head): climb digs one side open
                net.minecraft.world.level.Level lv = p.level();
                boolean walled = ClimbPlan.depth(y, surface) < ClimbPlan.HOLE;
                for (String n : new String[]{"north", "south", "east", "west"}) {
                    int[] d = StairPlan.dir(n);
                    if (DigCommands.climbCell(lv, x + d[0], y, z + d[1]) == ClimbPlan.Kind.OPEN && DigCommands.climbCell(lv, x + d[0], y + 1, z + d[1]) == ClimbPlan.Kind.OPEN) walled = false;
                }
                if (walled) {
                    // 0.31.0: say why each side counts as closed (the trial 10 snow pit had an open side the log never named)
                    StringBuilder why = new StringBuilder();
                    for (String n : new String[]{"north", "south", "east", "west"}) {
                        int[] d = StairPlan.dir(n);
                        why.append(n).append(": feet ").append(DigCommands.climbCell(lv, x + d[0], y, z + d[1])).append(" ")
                                .append(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(lv.getBlockState(new BlockPos(x + d[0], y, z + d[1])).getBlock()).getPath())
                                .append(", head ").append(DigCommands.climbCell(lv, x + d[0], y + 1, z + d[1])).append("; ");
                    }
                    LOG.info("[entropybot] walled in at {} {} {}: {}", x, y, z, why);
                }
                return walled ? msg + " - I'm walled in on every side - next: climb" : msg;
            }
            if (ClimbPlan.depth(y, surface) > ClimbPlan.HINT_MAX) return msg;     // a deep mine is no hole
            Clearing.LiveWorld lw = new Clearing.LiveWorld();
            lw.set(p);
            if (ClimbPlan.indoors(lw, x, y, z, ClimbPlan.depth(y, surface))) return msg;   // 0.30.2: under my own roof
            return msg + " - " + ClimbPlan.holeHint(ClimbPlan.depth(y, surface));
        } catch (RuntimeException e) {
            LOG.warn("[entropybot] hole note: {}", e.toString());
            return msg;
        }
    }

    public void finish(String msg) {
        Job j = job;
        if (j == null || j.done) return;
        msg = restoreGate(j, msg);
        if (msg == null) return;                          // P1: the job's blocks are put back first; finish comes again then
        if (j.junkDropped > 0) msg = msg + "; dropped " + j.junkDropped + " junk";        // C5
        msg = holeNote(j, msg);                                                           // 0.28.1 (trial fix C)
        j.done = true;
        j.status = msg;
        try {
            // 0.23.3: the long walk ends with its job; the chain-gap clock starts
            if (j.longRoute && io.github.mojolowjo.entropybot.baritone.LongRouteProcess.INSTANCE.walkId() == j.id)
                io.github.mojolowjo.entropybot.baritone.LongRouteProcess.INSTANCE.stop();
            if (j.type.equals("travel") || j.seq != null) io.github.mojolowjo.entropybot.move.MovePackage.INSTANCE.walkEnded(core.tick());
        } catch (RuntimeException e) { LOG.warn("[entropybot] path end: {}", e.toString()); }
        if (j.onEnd != null) {
            try { j.onEnd.run(); } catch (RuntimeException e) { LOG.warn("[entropybot] job end hook: {}", e.toString()); }
        }
        Minecraft mc = Minecraft.getInstance();
        if (j.type.equals("twerk") || j.seq != null) mc.options.keyShift.setDown(false);      // twerk, a farm round's crouch
        if (j.unstickLeft > 0) endUnstick(j);
        if (j.seq != null) { try { String m = Clearing.ended(j.seq, msg); if (m != null && !m.equals(msg)) { msg = m; j.status = m; } } catch (RuntimeException e) { LOG.warn("[entropybot] clear end hook: {}", e.toString()); } }           // B7d D1: a clear's leases end with the job, however it ends (a table left is named); never keeps the request open
        if (j.seq != null) {
            LocalPlayer p = mc.player;
            boolean close = "always".equals(j.closeOnEnd) || ("fail".equals(j.closeOnEnd) && !msg.startsWith("ok"));
            if (close && p != null && (io.github.mojolowjo.entropybot.gui.Gui.open(p) || mc.screen != null)) io.github.mojolowjo.entropybot.gui.Gui.close(p);
            IBaritone b = baritone();
            if (b != null && !msg.startsWith("ok")) cancel(b);
        }
        LOG.info("[entropybot] job finished: {}", msg);
        try { core.recorder.jobEnded(j.type, j.label, msg); } catch (RuntimeException e) { LOG.warn("[entropybot] recorder job end: {}", e.toString()); }
        if (j.req != null) commands.requests.done(j.req.id, msg);
    }

    // ---- P1: the restore before the end line ----

    /** End messages after which nothing is put back now (the owner said stop, the bot is fleeing or gone): the entries wait. */
    static boolean noRestoreAfter(String msg) {
        return msg.equals("stopped") || msg.startsWith("stopped: low health") || msg.startsWith("stopped: I was disconnected")
                || msg.startsWith("stopped: I am in ");
    }

    /**
     * The job is about to end with msg. Null when a restore of the blocks it broke on the way starts now instead (the
     * job's own end hook runs first: breaking off, leases released); finish is called again with the full line when it
     * is done. Otherwise the line to end with (the escape report and the restore summary or "N blocks to put back" added).
     */
    private String restoreGate(Job j, String msg) {
        try {
            if (j.escapeBreaks != null) endEscape(j);
            if (j.restoreRun != null) {
                // the restore itself was cut short (a stop, a fight, the fence), or a walk ended while putting back its escape
                RestoreRun r = j.restoreRun;
                j.restoreRun = null;
                j.restoreDone = true;
                boolean resume = j.restoreResume;
                j.restoreResume = false;
                r.stopRest(msg.startsWith("stopped") ? "stopped" : "the job ended");
                r.end();
                if (resume) {
                    j.escapeRestored += r.placedEscape;
                    return RestoreLive.INSTANCE.endText(msg, j, r);
                }
                String base = j.restoreMsg != null ? j.restoreMsg : msg;
                String text = RestoreLive.INSTANCE.endText(base, j, r);
                return j.restoreMsg != null ? text + " (restore " + msg.replaceFirst("^error: ", "") + ")" : text;
            }
            if (j.restoreDone || JobRequests.quiet(msg)) return msg;
            j.restoreDone = true;
            LocalPlayer p = Minecraft.getInstance().player;
            RestoreRun r = noRestoreAfter(msg) || p == null ? null : RestoreLive.INSTANCE.afterJob(j, here(p), Guard.dimOf(p.level()));
            if (r == null || r.empty()) {
                if (r != null) r.end();
                String text = RestoreLive.INSTANCE.endText(msg, j, r);
                if (r == null) {
                    String w = RestoreLive.INSTANCE.waitingNote(j);
                    if (w != null) text += "; " + w;
                }
                return text;
            }
            if (j.onEnd != null) {
                try { j.onEnd.run(); } catch (RuntimeException e) { LOG.warn("[entropybot] job end hook: {}", e.toString()); }
                j.onEnd = null;
            }
            if (j.unstickLeft > 0) endUnstick(j);
            IBaritone b = baritone();
            if (b != null) cancel(b);
            safeSettings();
            j.restoreMsg = msg;
            j.restoreRun = r;
            j.status = "putting back " + r.total() + (r.total() == 1 ? " block" : " blocks") + " I broke on the way";
            LOG.info("[entropybot] {} (then: {})", j.status, msg);
            return null;
        } catch (RuntimeException e) {
            LOG.warn("[entropybot] restore at the job's end: {}", e.toString());
            return msg + "; couldn't put back what I broke on the way (" + e + ") - restore status";
        }
    }

    /** "restore now": a job that only puts blocks back. */
    String startRestore(RestoreRun run, String label) {
        followWatch = null;
        followFix = null;
        safeSettings();
        Job j = new Job();
        j.type = "restore";
        j.startTick = core.tick();
        j.label = label;
        j.status = label;
        j.restoreRun = run;
        j.restoreDone = true;
        job = j;
        return "started: " + label;
    }

    private void restoreTick(LocalPlayer p, Job j) {
        RestoreRun r = j.restoreRun;
        boolean done;
        try {
            done = r.step(p, core.tick());
        } catch (RuntimeException e) {
            LOG.warn("[entropybot] restore: {}", e.toString());
            r.stopRest("error: " + e);
            done = true;
        }
        if (!done) {
            if (!j.restoreResume) j.status = "putting back blocks I broke on the way (" + r.placed + " of " + r.total() + ")";
            return;
        }
        r.end();
        j.restoreRun = null;
        if (j.restoreResume) {
            // the escape's blocks are back: walk on
            j.restoreResume = false;
            j.escapeRestored += r.placedEscape;
            if (!r.left.isEmpty()) LOG.info("[entropybot] escape: {}", r.summary());
            IBaritone b = baritone();
            if (j.longRoute) io.github.mojolowjo.entropybot.baritone.LongRouteProcess.INSTANCE.resume(here(Minecraft.getInstance().player), "after stepping off");      // 0.23.3
            else if (b != null && j.goalObj != null) b.getCustomGoalProcess().setGoalAndPath(j.goalObj);
            else if (b != null && j.goal != null) b.getCommandManager().execute(j.goal);
            j.status = j.label;
            j.startTick = core.tick();
            j.lastStepTick = -1;
            watch(j);
            return;
        }
        String text;
        if (j.restoreMsg != null) text = RestoreLive.INSTANCE.endText(j.restoreMsg, j, r);
        else {
            String sum = r.summary();
            text = (r.placed == 0 && !r.left.isEmpty() ? "error: " : "ok: ") + (sum == null ? "nothing to put back" : sum);
        }
        finish(text);
    }

    /** A new command replaces a walk: it ends quietly (the bridge's "job.done = true"). */
    public void replaceWalk() {
        if (walking()) finish(JobRequests.REPLACED);
    }

    // ---- the fence ----

    /** Null when the bot may walk to x y z, else the guard's reason (FenceRules.goalAllowed). */
    String goalAllowed(int x, int y, int z) {
        Minecraft mc = Minecraft.getInstance();
        return FenceRules.goalAllowed(io.github.mojolowjo.entropybot.api.BotAPI.check(Guard.dimOf(mc.level), x, y, z, "go"), commands.fenceOn());
    }

    static String withAreaHint(String reason) { return FenceRules.withAreaHint(reason); }

    static String fenceError(String reason) { return FenceRules.gotoRefusal(reason); }

    // ---- walks ----

    /**
     * goal: a Baritone command ("goto x y z", "goto x z", "follow player NAME"); dest: where it ends up (a /home
     * first when that is near home and far off), or null; reflex: no fence check.
     */
    /** Package D: when the last errand walk (come, goto, follow, go, base) started (ms; 0 = none yet). */
    long lastTravelAt;

    public String startTravel(String goal, String label, int[] dest, String destDim, boolean reflex) {
        return startTravel(goal, label, dest, destDim, reflex, io.github.mojolowjo.entropybot.guard.AreaTypeRules.Walker.OTHER);
    }

    /** V1a: walker OWNER_ORDER (the owner's own goto/go) may walk outside every area; digging rules are unchanged. */
    public String startTravel(String goal, String label, int[] dest, String destDim, boolean reflex, io.github.mojolowjo.entropybot.guard.AreaTypeRules.Walker walker) {
        IBaritone b = baritone();
        if (b == null) return "error: baritone not loaded";
        LocalPlayer p = Minecraft.getInstance().player;
        // 0.24.1: absurd walks are refused before any planning (path maxWalk)
        {
            int[] far = dest != null ? dest : FenceRules.goalSpot(goal, (int) Math.floor(p.getY()));
            String tooFar = far == null ? null : io.github.mojolowjo.entropybot.move.ServerCmds.tooFar(here(p), far, io.github.mojolowjo.entropybot.move.ServerCmds.maxWalk());
            if (tooFar != null) return tooFar;
        }
        if (!reflex) {
            int[] spot = dest != null ? dest : FenceRules.goalSpot(goal, (int) Math.floor(p.getY()));
            String why = spot == null ? null : FenceRules.goalAllowed(io.github.mojolowjo.entropybot.api.BotAPI.check(Guard.dimOf(Minecraft.getInstance().level), spot[0], spot[1], spot[2], "go"), commands.fenceOn(), walker);
            if (why != null) return FenceRules.gotoRefusal(why);
            lastTravelAt = System.currentTimeMillis();     // package D: an errand (come, goto, go...) holds the furnace pickups
        }
        followWatch = null;
        followFix = null;
        safeSettings();
        Job j = new Job();
        j.type = "travel";
        j.startTick = core.tick();
        j.label = label;
        j.goal = goal;
        j.dest = dest;
        j.reflex = reflex;
        j.goalInside = !reflex && (dest != null || FenceRules.goalSpot(goal, 0) != null);
        if (dest != null && tpWorth(p, dest, destDim)) {
            // stand still for /home (in case the server has a warm-up), then walk the rest
            cancel(b);
            j.status = label + " (teleporting home first)";
            sendHome(p, j);
            job = j;
            return "ok: " + j.status;
        }
        job = j;
        launchTravel(p, j, b);
        return "ok: " + label;
    }

    /**
     * Starts a travel's walk: through the router when it decides so (routing stage 1; the job then waits for the plan
     * at most 300 ms in {@link #tick}, status "(planning)"), else exactly as before (the Baritone command). Reflex walks
     * (a retreat) and walks to a moving player (follow, the companion's follow) are never routed.
     */
    private void launchTravel(LocalPlayer p, Job j, IBaritone b) {
        j.route = null;
        j.legDest = null;
        int[] d = travelDest(j);
        boolean fixed = !j.reflex && d != null && j.goal != null && j.goal.startsWith("goto ")
                && (j.label == null || !j.label.startsWith("following"));
        if (fixed && startLong(p, j, d, new baritone.api.pathing.goals.GoalBlock(d[0], d[1], d[2]))) {
            j.status = j.label + " (long route)";
            return;
        }
        if (fixed) {
            j.plainGoal = new baritone.api.pathing.goals.GoalBlock(d[0], d[1], d[2]);
            j.route = RouteWalker.begin(commands, p, d, null, 0, true, null);
        }
        if (j.route != null) {
            j.status = j.label + " (planning)";
            io.github.mojolowjo.entropybot.move.MovePackage.INSTANCE.walkStarted(p, d, core.tick());     // 0.23.3: moving while it plans
            return;
        }
        b.getCommandManager().execute(j.goal);
        j.status = j.label;
        watch(j);
        if (d != null && !j.reflex) io.github.mojolowjo.entropybot.move.MovePackage.INSTANCE.walkStarted(p, d, core.tick());      // 0.23.3: instant start
    }

    /** A travel waiting for its plan: once it is there (or 300 ms passed), the walk starts with the goal it gives. */
    private void routeTravelPoll(Job j) {
        if (RouteWalker.poll(j.route) == io.github.mojolowjo.entropybot.routewalk.RouteWalk.Phase.PLANNING) return;
        IBaritone b = baritone();
        if (b == null) {
            finish("error: baritone not loaded");
            return;
        }
        j.status = j.label;
        j.startTick = core.tick();
        j.lastStepTick = -1;
        if (!j.route.routed()) {
            j.route = null;
            b.getCommandManager().execute(j.goal);
        } else {
            walkGoal(b, j, RouteWalker.goalFor(j.route, j.plainGoal));
            j.legDest = RouteWalker.legDest(j.route);
        }
        watch(j);
    }

    /** Sets a routed goal (or a leg) and remembers it, so unsticking plans it again. */
    static void walkGoal(IBaritone b, Job j, baritone.api.pathing.goals.Goal g) {
        safeSettings();
        b.getCustomGoalProcess().setGoalAndPath(g);
        j.goalObj = g;
    }

    /**
     * A routed travel got "no path" or the stuck watchdog fired: the plain walk from here, once (the stretch is
     * rebuilt by the planner). False when the walk wasn't routed (do today's thing).
     */
    private boolean routeFallback(LocalPlayer p, Job j, IBaritone b, String why) {
        if (j.route == null || b == null || !RouteWalker.fallBack(j.route, why, p)) return false;
        cancel(b);
        j.legDest = null;
        j.goalObj = null;
        safeSettings();
        b.getCommandManager().execute(j.goal);
        j.startTick = core.tick();
        j.lastStepTick = -1;
        watch(j);
        return true;
    }

    /** "home": /home right away. */
    public String startHome(LocalPlayer p) {
        JsonObject h = commands.home();
        int[] me = here(p);
        if (h != null && dimOf(h).equals(Guard.dimOf(p.level())) && distSq(pos(h), me) <= 64) return "ok: already home (" + fmt(pos(h)) + ")";
        // 0.24.1: server commands off: walk home (the long-route process), never /home
        if (!io.github.mojolowjo.entropybot.move.ServerCmds.allowed("home")) {
            if (h == null) return "error: server commands are off and I know no home - place base, or server commands on";
            if (!dimOf(h).equals(Guard.dimOf(p.level()))) return "error: server commands are off and my home is in " + dimOf(h) + " - I can't walk there";
            int[] hp = pos(h);
            return startTravel("goto " + hp[0] + " " + hp[1] + " " + hp[2], "walking home (server commands are off)", hp, dimOf(h), false);
        }
        IBaritone b = baritone();
        if (b != null) cancel(b);
        Job j = new Job();
        j.type = "travel";
        j.startTick = core.tick();
        j.status = "teleporting home";
        j.label = j.status;
        sendHome(p, j);
        job = j;
        return "ok: teleporting home";
    }

    /** "spawn": walk to the nearest bed and click it. */
    public String startSetSpawn(LocalPlayer p) {
        String f = findBlock(p, "_bed 24");
        if (!f.startsWith("found")) return Hints.next("error: no bed within 24 blocks", "go base then spawn (or goto x y z next to a bed, then spawn)");
        String[] w = f.split(" ");
        String[] parts = w[4].split("\\.");          // block.minecraft.white_bed
        int x = Integer.parseInt(w[1]), y = Integer.parseInt(w[2]), z = Integer.parseInt(w[3]);
        IBaritone b = baritone();
        if (b == null) return "error: baritone not loaded";
        String why = goalAllowed(x, y, z);
        if (why != null) return fenceError(why);
        b.getCustomGoalProcess().setGoalAndPath(new GoalGetToBlock(new BlockPos(x, y, z)));
        Job j = new Job();
        j.type = "spawn";
        j.bed = x + " " + y + " " + z;
        j.startTick = core.tick();
        j.status = "walking to the " + String.join(".", java.util.Arrays.copyOfRange(parts, 2, parts.length)) + " at " + j.bed;
        job = j;
        return "started: " + j.status;
    }

    /** "wait <seconds>": a step that does nothing for a while. */
    public String startWait(String text) {
        int s = (int) Math.min(Math.max(Chains.leadingInt(text) == 0 ? 10 : Chains.leadingInt(text), 1), 3600);
        Job j = new Job();
        j.type = "wait";
        j.until = core.tick() + s * 20L;
        j.status = "waiting " + s + "s";
        job = j;
        return "started: waiting " + s + "s";
    }

    /** "twerk" is a toggle: crouch on/off every 4 ticks until "twerk" again; "twerk <s>" for that long. */
    public String startTwerk(String text) {
        String t = text == null ? "" : text.trim().toLowerCase();
        long seconds = Chains.leadingInt(t);
        if (running() && job.type.equals("twerk")) {
            if (t.equals("on")) return "ok: already twerking";
            Request r = job.req;
            job.req = null;                          // one reply, not a whisper as well
            finish("ok: stopped twerking");
            if (r != null) commands.requests.done(r.id, JobRequests.REPLACED);
            return "ok: stopped twerking";
        }
        if (t.equals("off")) return "ok: not twerking";
        Job j = new Job();
        j.type = "twerk";
        if (seconds > 0) {
            j.total = Math.min(seconds, 60) * 20;
            j.status = "twerking";
            job = j;
            return "started: twerking for " + Math.min(seconds, 60) + "s";
        }
        j.total = Long.MAX_VALUE;
        j.status = "twerking (PM \"twerk\" again to stop)";
        job = j;
        return "started: twerking - PM \"twerk\" again to stop";
    }

    // ---- the teleport home ----

    /** Worth a /home on the way? Only if dest is near home and the bot is far from it (another dimension, 40+ across, 10+ up or down). */
    boolean tpWorth(Player p, int[] dest, String destDim) {
        JsonObject h = commands.home();
        boolean worth = h != null && TpRules.worth(pos(h), dimOf(h), dest, destDim, here(p), Guard.dimOf(p.level()), core.tick(), tpFailedAt);
        return worth && io.github.mojolowjo.entropybot.move.ServerCmds.allowed("a far trip's teleport home");
    }

    void sendHome(LocalPlayer p, Job j) {
        j.tpLast = here(p);
        j.tpDim = Guard.dimOf(p.level());
        j.tpTick = core.tick();
        p.connection.sendCommand("home");
    }

    /** null while waiting; "ok" once the bot jumped (8+ blocks since the last check); "failed" after TpRules.WAIT_TICKS. */
    String tpResult(LocalPlayer p, Job j) {
        int[] me = here(p);
        String dim = Guard.dimOf(p.level());
        String r = TpRules.result(j.tpLast, j.tpDim, me, dim, j.tpTick, core.tick());
        if ("ok".equals(r)) {
            commands.setHome(me, dim);
            return "ok";
        }
        j.tpLast = me;
        if (r == null) return null;
        tpFailedAt = core.tick();
        return "failed";
    }

    // ---- path events ----

    void watch(Job j) {
        j.evSeq = core.events.lastSeq();
        j.evFails = 0;
        j.evAtGoal = false;
    }

    /** "nopath", "goal" or null (undecided). */
    String travelEvents(Job j) {
        if (j.evSeq < 0) return null;
        try {
            JsonArray list = JsonParser.parseString(core.events.since(j.evSeq, 100)).getAsJsonArray();
            for (JsonElement e : list) {
                JsonObject o = e.getAsJsonObject();
                j.evSeq = o.get("seq").getAsLong();
                if (!"path".equals(o.get("kind").getAsString())) continue;
                String what = o.get("text").getAsString();
                if (what.equals("CALC_STARTED")) j.evFails = 0;
                else if (what.equals("CALC_FAILED") || what.equals("NEXT_CALC_FAILED")) j.evFails++;
                else if (what.equals("AT_GOAL")) j.evAtGoal = true;
            }
            if (j.evAtGoal) return "goal";
            if (j.evFails >= TRAVEL_FAILS) return "nopath";
        } catch (RuntimeException e) {
            j.evSeq = -1;
        }
        return null;
    }

    static int[] travelDest(Job j) {
        if (j.dest != null) return j.dest;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^goto (-?\\d+) (-?\\d+) (-?\\d+)$").matcher(j.goal == null ? "" : j.goal);
        return m.find() ? new int[]{Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3))} : null;
    }

    static String noPathResult(Player p, Job j) {
        int[] d = travelDest(j), me = here(p);
        String text = "error: no path to " + (d != null ? fmt(d) : String.valueOf(j.goal).replaceFirst("^goto ", "")) + " - stuck at " + fmt(me);
        if (d != null) text += ", " + Math.round(Math.sqrt(distSq(me, d))) + " blocks short";
        return text;
    }

    /** No block of progress toward the destination for 30 s: Baritone re-plans a goal it can't reach forever. */
    boolean travelStuck(Player p, Job j) {
        if (j.evSeq < 0) return false;
        int[] d = j.legDest != null ? j.legDest : travelDest(j);       // legs mode: progress to the current leg's end
        if (d == null) return false;
        double dist = Math.sqrt(distSq(here(p), d));
        long now = core.tick();
        if (j.lastStepTick < 0 || now - j.lastStepTick > 40) {
            j.bestDist = dist;
            j.bestTick = now;
        }
        j.lastStepTick = now;
        if (dist < j.bestDist - 1) {
            j.bestDist = dist;
            j.bestTick = now;
        }
        // review S1: a routed goal-mode walk may detour away from the goal in a straight line: a 90 s window
        boolean routedGoal = j.legDest == null && j.route != null && j.route.routed()
                && j.route.mode() == io.github.mojolowjo.entropybot.routewalk.RouteWalk.Mode.GOAL;
        return now - j.bestTick > io.github.mojolowjo.entropybot.routewalk.RouteDecision.stuckTicks(routedGoal, TRAVEL_STUCK_TICKS);
    }

    // ---- the tick ----

    /** Once a tick in a world. */
    void tick(LocalPlayer p) {
        long now = core.tick();
        reflexWatch(p);
        if (now % 20 == 10) fenceWatch(p);
        if (now % 20 == 15) followFixTick(p);
        if (!running()) return;
        if (core.reflexes.hold()) {                      // a reflex holds the job still
            if (job.unstickLeft > 0) endUnstick(job);
            return;
        }
        Job j = job;
        if (j.restoreRun != null) {                     // P1: putting back what it broke on the way
            restoreTick(p, j);
            return;
        }
        if (j.escapeBreaks != null) {                   // P1: digging out of a boxed-in spot
            escapeStep(p, j);
            return;
        }
        if (j.unstickLeft > 0) {
            unstickStep(p, j);
            return;
        }
        if (j.escapeWaiting && j.type.equals("travel") && now % 20 == 7 && RestoreLive.INSTANCE.outOfEscape(j, here(p))) {
            // P1: out of the spot it dug itself out of (3+ blocks away): those blocks go back, then the walk goes on
            j.escapeWaiting = false;
            RestoreRun r = RestoreLive.INSTANCE.escapeRun(j, here(p), Guard.dimOf(p.level()));
            if (r != null && !r.empty()) {
                IBaritone b = baritone();
                if (b != null) cancel(b);
                j.restoreRun = r;
                j.restoreResume = true;
                return;
            }
        }
        switch (j.type) {
            case "twerk" -> {
                j.ticks++;
                Minecraft.getInstance().options.keyShift.setDown(j.ticks < j.total && (j.ticks / 4) % 2 == 0);
                if (j.ticks >= j.total) finish("ok: twerked");
            }
            case "wait" -> { if (now >= j.until) finish("ok: waited"); }
            case "travel", "spawn" -> {
                if (j.longRoute && j.tpTick < 0) {                // 0.23.3: the long-route process walks it; checked every tick
                    String r = longTick(p, j);
                    if (r.equals("wait")) return;
                    if (r.equals("ok")) {
                        String v = goalVerdict(p, j);
                        finish(v != null ? v : "ok: arrived near " + fmt(here(p)) + (j.tpNote != null ? " (" + j.tpNote + ")" : ""));
                    } else if (r.startsWith("ok: ")) finish(r);                    // 0.27.4: sealed underground, digging a staircase up
                    else {
                        // 0.32.2 (P2): a long walk that gave up under ground walks the trail back first (plain hops)
                        if (!j.retraceTried && io.github.mojolowjo.entropybot.baritone.LongRouteProcess.INSTANCE.walkId() == j.id) {
                            io.github.mojolowjo.entropybot.baritone.LongRouteProcess.INSTANCE.stop();
                            j.longRoute = false;
                            if (startRetrace(p, j, baritone(), "long walk: " + r)) return;
                            j.longRoute = true;
                        }
                        if (j.paperEscape || paperAfterRetrace(p, j)) { finish(paperEscapeEnd(p)); return; }
                        if (j.retraceTried) {                                     // 0.33.0 (A1): retrace first, then the staircase
                            int[] d2 = travelDest(j) != null ? travelDest(j) : j.dest;
                            String st = sealedStairs(p, here(p), d2);
                            if (st != null) { finish("ok: " + st); return; }
                        }
                        finish(r.startsWith("error") ? r : "error: " + r);
                    }
                    return;
                }
                if (j.route != null && j.route.phase() == io.github.mojolowjo.entropybot.routewalk.RouteWalk.Phase.PLANNING) routeTravelPoll(j);
                else if (now % 20 == 0) stepWalk(p, j);
            }
            case "seq" -> { if (now % 2 == 0 || j.seq.everyTick()) j.seq.tick(p); }
            default -> {}
        }
    }

    private void stepWalk(LocalPlayer p, Job j) {
        IBaritone b = baritone();
        if (j.tpTick >= 0) {
            String t = tpResult(p, j);
            if (t == null) return;
            j.tpTick = -1;
            j.tpNote = t.equals("ok") ? "teleported home" : "/home didn't move me, so I walked";
            if (t.equals("ok") && j.goal == null) {
                if (b != null) cancel(b);
                finish("ok: teleported home (" + fmt(pos(commands.home())) + ")");
                return;
            }
            if (j.goal == null) {
                finish("error: /home didn't move me - is my home set? Stand me at the base and PM sethome");
                return;
            }
            j.startTick = core.tick();
            if (b != null) launchTravel(p, j, b);
            else {
                j.status = j.label;
                watch(j);
            }
            return;
        }
        String ev = travelEvents(j);
        boolean stuck = j.type.equals("travel") && ev == null && travelStuck(p, j);
        // routing stage 1: a routed walk that found no path or got stuck walks the plain goal once before anything else
        if ((stuck || "nopath".equals(ev)) && routeFallback(p, j, b, stuck ? "stuck" : "no path")) return;
        // Baritone plans nothing from a block it doesn't understand (a modded altar or pedestal, seen live
        // 2026-10-02): step off it by hand first, sooner when the bot isn't standing on a full block
        boolean early = j.type.equals("travel") && ev == null && travelDest(j) != null && j.lastStepTick >= 0
                && !onFullBlock(p) && core.tick() - j.bestTick > UNSTICK_EARLY;
        if ((stuck || early || "nopath".equals(ev)) && j.unstickTries < UNSTICK_TRIES && startUnstick(p, j)) return;
        // 0.27.3 (d1): a short walk with no path and no free step (its tries spent) still gets the dig-out
        if ((stuck || "nopath".equals(ev)) && j.retraceFinal != null && retraceNext(p, j, b, stuck ? "stuck" : "no path")) return;   // 0.32.2 live: a hop it can't reach is skipped
        if ("nopath".equals(ev) && startRetrace(p, j, b, "no path")) return;          // 0.32.2: the trail back first
        if ((stuck || "nopath".equals(ev)) && (j.paperEscape || paperAfterRetrace(p, j))) {    // 0.33.0 (A1): the plans decide, no dig-out
            if (b != null) cancel(b);
            finish(paperEscapeEnd(p));
            return;
        }
        if ("nopath".equals(ev) && bestFreeStep(p, null) == null && startEscape(p, j, false)) {
            io.github.mojolowjo.entropybot.move.MovePackage.INSTANCE.digOutStarted(core.tick());
            return;
        }
        if (stuck) {
            if (b != null) cancel(b);
            finish(noPathResult(p, j));
            return;
        }
        if (ev == null && core.tick() - j.startTick < 40) return;
        if (b != null && !idle(b)) return;
        if (!"nopath".equals(ev) && b != null && j.route != null && j.route.hasNextLeg()) {
            // legs mode: this leg is done, on to the next (the last one is the real goal)
            j.route.nextLeg();
            walkGoal(b, j, RouteWalker.goalFor(j.route, j.plainGoal));
            j.legDest = RouteWalker.legDest(j.route);
            j.startTick = core.tick();
            j.lastStepTick = -1;
            watch(j);
            return;
        }
        if ("nopath".equals(ev)) {
            finish(noPathResult(p, j));
        } else if (j.type.equals("travel")) {
            // P1 fix: Baritone goes idle without a no-path event when the goal can't be reached (it walks to the
            // closest spot, or plans nothing from a sealed pit): the end position decides
            int[] d = travelDest(j), me = here(p);
            boolean moving = j.label != null && j.label.startsWith("following");
            // 0.31.1 (live: "arrived near" 3 blocks off, a house wall between): a goal inside a house box counts only from inside
            boolean wallBetween = d != null && !moving && !j.reflex && outsideHouseOf(me, d);
            if (!j.reflex && !moving && d != null && (!WalkEnd.arrived(me, d) || wallBetween)) {
                if (j.retraceFinal != null && retraceNext(p, j, b, "ended short")) return;
                if (!wallBetween && startRetrace(p, j, b, "ended short")) return;      // 0.32.2: the trail back first
                if (j.paperEscape || paperAfterRetrace(p, j)) { finish(paperEscapeEnd(p)); return; }   // 0.33.0 (A1): no dig-out after the plans started
                if (!wallBetween && WalkEnd.retryShort(j.shortTries)) {          // 0.31.1: never dig out through a house wall
                    j.shortTries++;
                    LOG.info("[entropybot] walk ended short at {} ({} from {}): stepping off or digging out, then again", fmt(me),
                            Math.round(Math.sqrt(distSq(me, d))), fmt(d));
                    // a free step: step there and plan again; none: the escape dig-out (underground, inside the areas)
                    if (recoverShort(p, j)) return;
                }
                if (!j.doorTried && j.doorFinal == null && b != null) {
                    j.doorTried = true;
                    String[] house = new String[1];
                    int[] step = houseDoorStep(p, d, house);
                    if (step != null) {
                        LOG.info("[entropybot] walk ended short of {} in house {}: once more through the door, from {}", fmt(d), house[0], fmt(step));
                        j.doorFinal = d;
                        j.dest = step;
                        j.shortTries = 0;
                        j.startTick = core.tick();
                        j.lastStepTick = -1;
                        walkGoal(b, j, new baritone.api.pathing.goals.GoalBlock(step[0], step[1], step[2]));
                        watch(j);
                        return;
                    }
                    if (house[0] != null) {
                        finish(WalkEnd.shortResult(me, d) + " - " + fmt(d) + " is in house " + house[0] + ", which has no open doorway I can reach - next: house "
                                + house[0] + " (it digs the door), or let me in");
                        return;
                    }
                }
                finish(WalkEnd.shortResult(me, d) + (j.doorFinal != null ? " (via the door of the house too)" : "") + " - next: goto a spot next to it, or come get me");
                return;
            }
            if (j.retraceFinal != null) {                 // 0.32.2: at a trail hop: the next one, else the real goal
                int[] nx = j.retraceHops != null && !j.retraceHops.isEmpty() ? j.retraceHops.pollFirst() : null;
                if (nx == null) {
                    nx = j.retraceFinal;
                    j.retraceFinal = null;
                    j.retraceHops = null;
                    LOG.info("[entropybot] retrace: back at {} along my trail: on to {}", fmt(here(p)), fmt(nx));
                }
                j.dest = nx;
                j.shortTries = 0;
                j.startTick = core.tick();
                j.lastStepTick = -1;
                walkGoal(b, j, j.retraceFinal != null ? new baritone.api.pathing.goals.GoalNear(new BlockPos(nx[0], nx[1], nx[2]), 2) : new baritone.api.pathing.goals.GoalBlock(nx[0], nx[1], nx[2]));
                watch(j);
                return;
            }
            if (j.doorFinal != null) {                    // 0.31.1: at the doorway's outside step: on to the real goal
                int[] fin = j.doorFinal;
                j.doorFinal = null;
                j.dest = fin;
                j.shortTries = 0;
                j.startTick = core.tick();
                j.lastStepTick = -1;
                LOG.info("[entropybot] at the door step {}: on to {}", fmt(here(p)), fmt(fin));
                walkGoal(b, j, new baritone.api.pathing.goals.GoalBlock(fin[0], fin[1], fin[2]));
                watch(j);
                return;
            }
            String v = moving ? null : goalVerdict(p, j);
            finish(v != null ? v : "ok: arrived near " + fmt(here(p)) + (j.tpNote != null ? " (" + j.tpNote + ")" : ""));
        } else {
            String r = useBlock(p, j.bed);
            finish(r.startsWith("ok") ? "ok: clicked the bed at " + j.bed + " (spawn set if it was night or the server allows it)" : r);
        }
    }

    // ---- unsticking ----

    /** Standing on a whole block (or the ground of a full-block top)? An altar, a pedestal, a slab-like machine is not. */
    static boolean onFullBlock(LocalPlayer p) {
        Minecraft mc = Minecraft.getInstance();
        double frac = p.getY() - Math.floor(p.getY());
        if (frac > 0.01 && frac < 0.99) return false;
        BlockPos below = BlockPos.containing(p.getX(), p.getY() - 0.01, p.getZ());
        return mc.level.getBlockState(below).isCollisionShapeFullBlock(mc.level, below);
    }

    /** A cell the bot can stand in: nothing to bump into at the feet and head, a full block under it. */
    static boolean standable(Minecraft mc, BlockPos feet) {
        return mc.level.getBlockState(feet).getCollisionShape(mc.level, feet).isEmpty()
                && mc.level.getBlockState(feet.above()).getCollisionShape(mc.level, feet.above()).isEmpty()
                && mc.level.getBlockState(feet.below()).isCollisionShapeFullBlock(mc.level, feet.below())
                && mc.level.getFluidState(feet).isEmpty();
    }

    /**
     * 0.23.4 (run 9a): an owner's goto x y z ending within the 3-block tolerance: is the goal cell itself inside a block
     * or walled in? Then WalkEnd.goalVerdict's answer, else null (arrived near, as before). Unloaded goal: null.
     */
    String goalVerdict(LocalPlayer p, Job j) {
        try {
            if (j.reflex || j.goal == null || !j.goal.matches("^goto -?\\d+ -?\\d+ -?\\d+$")) return null;
            int[] d = travelDest(j);
            Minecraft mc = Minecraft.getInstance();
            if (d == null || mc.level == null) return null;
            BlockPos g = new BlockPos(d[0], d[1], d[2]);
            if (!mc.level.isLoaded(g)) return null;
            WalkEnd.GoalCell cell = goalCell(mc, g, here(p));
            String v = WalkEnd.goalVerdict(here(p), d, cell);
            if (v != null) LOG.info("[entropybot] walk end at {}: goal {} {}", fmt(here(p)), fmt(d), v);
            return v;
        } catch (RuntimeException e) {
            LOG.warn("[entropybot] goal check: {}", e.toString());
            return null;
        }
    }

    private static boolean open(Minecraft mc, BlockPos c) {
        return mc.level.getBlockState(c).getCollisionShape(mc.level, c).isEmpty();
    }

    static WalkEnd.GoalCell goalCell(Minecraft mc, BlockPos g, int[] me) {
        var st = mc.level.getBlockState(g);
        if (!st.getCollisionShape(mc.level, g).isEmpty()) {
            String id = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(st.getBlock()).getPath();
            int[] best = null;
            double bd = Double.MAX_VALUE;
            for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) {
                BlockPos c = g.offset(dx, dy, dz);
                if ((dx | dy | dz) == 0 || !standable(mc, c)) continue;
                double s = distSq(new int[]{c.getX(), c.getY(), c.getZ()}, me);
                if (s < bd) { bd = s; best = new int[]{c.getX(), c.getY(), c.getZ()}; }
            }
            return new WalkEnd.GoalCell(id, false, best);
        }
        // walled in: no room for the head, or closed on all four sides at feet and head height (only reached
        // from above, and the walk ended more than a block off, so it wasn't)
        boolean walled = !open(mc, g.above());
        if (!walled) {
            walled = true;
            for (net.minecraft.core.Direction dir : net.minecraft.core.Direction.Plane.HORIZONTAL) {
                BlockPos s = g.relative(dir);
                if (open(mc, s) && open(mc, s.above())) walled = false;
            }
        }
        return new WalkEnd.GoalCell(null, walled, null);
    }

    /**
     * 0.23.4 (run 8): a walk passing a built cluster no safe or main area covers whispers once per spot, at most once a
     * minute, never in the near-me zone around the owner ("looks like a build at x y z - area here 8 name safe?").
     */
    private void walkBuildHint(LocalPlayer p) {
        long now = System.currentTimeMillis();
        if (!io.github.mojolowjo.entropybot.restore.BuildSpotter.walkHintDue(walkHintAt, now)) return;
        if (RestoreLive.INSTANCE.walkHint(p, here(p))) walkHintAt = now;
    }

    /** Picks the free spot next to the bot nearest the destination and starts walking onto it; false when there is none. */
    boolean startUnstick(LocalPlayer p, Job j) {
        BlockPos best = bestFreeStep(p, travelDest(j));
        if (best == null) {                                   // P1: boxed in: dig out (underground, inside the areas only)
            boolean r = startEscape(p, j);
            if (r) io.github.mojolowjo.entropybot.move.MovePackage.INSTANCE.digOutStarted(core.tick());      // 0.27.3: short walks count in path status too
            return r;
        }
        return stepTo(p, j, best);
    }

    /**
     * P1 fix (0.19.7): a walk that Baritone ended short of its goal. A free step that brings the bot closer: step there
     * and plan again; none (a sealed pit, or a pocket whose only free cell leads back): the escape dig-out.
     */
    /**
     * 0.31.1: d inside a house area (a box area with heights): the walkable cell just outside an open doorway (a 2-high
     * gap at floor + 1 in the wall ring), the one nearest the bot; house[0] names the area (null: d is in no such area).
     */
    /** 0.31.1: the box (x1 y1 z1 x2 y2 z2, sorted) of a house-like area (a box with heights) whose walls d lies strictly inside; null: none. */
    int[] houseBoxOf(int[] d, String[] house) {
        for (com.google.gson.JsonElement e : commands.effectiveAreas()) {
            if (!e.isJsonObject() || !e.getAsJsonObject().has("name")) continue;
            String name = e.getAsJsonObject().get("name").getAsString();
            String[] err = new String[1];
            int[] box = DigCommands.areaBox(commands, name, err);
            if (box == null) continue;
            int x1 = Math.min(box[0], box[3]), x2 = Math.max(box[0], box[3]), y1 = Math.min(box[1], box[4]), y2 = Math.max(box[1], box[4]);
            int z1 = Math.min(box[2], box[5]), z2 = Math.max(box[2], box[5]);
            if (x2 - x1 > 32 || z2 - z1 > 32 || y2 - y1 > 16) continue;                 // a house, not a base-sized area
            if (d[0] <= x1 || d[0] >= x2 || d[2] <= z1 || d[2] >= z2 || d[1] <= y1 || d[1] >= y2) continue;   // strictly inside the walls
            if (!wallsStand(x1, y1 + 1, z1, x2, z2)) continue;          // review 2: a house stands there, not a dig box
            if (house != null) house[0] = name;
            return new int[]{x1, y1, z1, x2, y2, z2};
        }
        return null;
    }

    /** review 2: at least 3/4 of the wall ring at y (corners included) holds a block with a collision shape. */
    static boolean wallsStand(int x1, int y, int z1, int x2, int z2) {
        net.minecraft.world.level.Level lv = Minecraft.getInstance().level;
        if (lv == null) return false;
        int ring = 0, solid = 0;
        for (int x = x1; x <= x2; x++) for (int z = z1; z <= z2; z++) {
            if (!(x == x1 || x == x2 || z == z1 || z == z2)) continue;
            BlockPos q = new BlockPos(x, y, z);
            if (!lv.isLoaded(q)) return false;
            ring++;
            if (!lv.getBlockState(q).getCollisionShape(lv, q).isEmpty()) solid++;
        }
        return ring > 0 && solid * 4 >= ring * 3;
    }

    /** 0.31.1: d is inside a house box and me is not (a wall between them, whatever the distance). */
    boolean outsideHouseOf(int[] me, int[] d) {
        try {
            int[] b = houseBoxOf(d, null);
            // review 2: the wall line counts as inside for me (only a doorway puts me there)
            return b != null && !(me[0] >= b[0] && me[0] <= b[3] && me[2] >= b[2] && me[2] <= b[5] && me[1] > b[1] && me[1] < b[4]);
        } catch (RuntimeException ex) {
            return false;
        }
    }

    int[] houseDoorStep(LocalPlayer p, int[] d, String[] house) {
        try {
            net.minecraft.world.level.Level lv = p.level();
            {
                int[] hb = houseBoxOf(d, house);
                if (hb == null) return null;
                int x1 = hb[0], y1 = hb[1], z1 = hb[2], x2 = hb[3], y2 = hb[4], z2 = hb[5];
                int iy = y1 + 1;
                int[] best = null;
                double bd = Double.MAX_VALUE;
                int[] me = here(p);
                for (int x = x1; x <= x2; x++) for (int z = z1; z <= z2; z++) {
                    boolean edge = x == x1 || x == x2 || z == z1 || z == z2;
                    if (!edge) continue;
                    BlockPos a = new BlockPos(x, iy, z);
                    if (!lv.getBlockState(a).getCollisionShape(lv, a).isEmpty() || !lv.getBlockState(a.above()).getCollisionShape(lv, a.above()).isEmpty()) continue;
                    int ox = x == x1 ? x - 1 : x == x2 ? x + 1 : x, oz = z == z1 ? z - 1 : z == z2 ? z + 1 : z;
                    if ((x == x1 || x == x2) && (z == z1 || z == z2)) continue;          // corners are no door
                    BlockPos o = new BlockPos(ox, iy, oz);
                    if (!standable(Minecraft.getInstance(), o)) continue;
                    double s = distSq(new int[]{ox, iy, oz}, me);
                    if (s < bd) { bd = s; best = new int[]{ox, iy, oz}; }
                }
                return best;
            }
        } catch (RuntimeException ex) {
            LOG.warn("[entropybot] house door step for {}: {}", fmt(d), ex.toString());
        }
        return null;
    }

    /**
     * 0.32.2 (PATHS_PLAN P2): under ground with no way on, walk the trail back (hops of 8, up to where the sky was open,
     * at most 400 trail points), once per walk; then the real goal again. False when not under ground, no trail near,
     * or tried already (the old escape runs). Never throws.
     */
    /**
     * 0.32.2 (live: "no path to -156 -5 155 - stuck 1 blocks short" ended the retrace at its first hop): a hop it is
     * within 4 of counts as reached; one it can't reach is skipped (at most 4 skips); the hops done: the real goal.
     */
    private boolean retraceNext(LocalPlayer p, Job j, IBaritone b, String why) {
        if (b == null) return false;
        int[] me = here(p), d = travelDest(j);
        boolean near = d != null && distSq(me, d) <= 16;
        if (!near && ++j.retraceSkips > 4) {
            LOG.info("[entropybot] retrace: gave up after {} unreachable hops ({}) at {}", j.retraceSkips - 1, why, fmt(me));
            // review 5: the retrace is over: the real goal back as the walk's goal (its end text), no second escape
            j.dest = j.retraceFinal;
            j.retraceFinal = null;
            j.retraceHops = null;
            j.paperEscape = true;
            PaperPlan.INSTANCE.escape(commands, p, commands.owner());        // P3.4: plan from the highest point reached
            return false;
        }
        int[] nx = j.retraceHops != null && !j.retraceHops.isEmpty() ? j.retraceHops.pollFirst() : null;
        if (nx == null) {
            nx = j.retraceFinal;
            j.retraceFinal = null;
            j.retraceHops = null;
        }
        LOG.info("[entropybot] retrace: hop {} ({}{}): on to {}", d == null ? "?" : fmt(d), why, near ? ", near enough" : ", skipped", fmt(nx));
        if (b != null) cancel(b);
        j.dest = nx;
        j.shortTries = 0;
        j.startTick = core.tick();
        j.lastStepTick = -1;
        j.bestTick = core.tick();
        walkGoal(b, j, j.retraceFinal != null ? new baritone.api.pathing.goals.GoalNear(new BlockPos(nx[0], nx[1], nx[2]), 2) : new baritone.api.pathing.goals.GoalBlock(nx[0], nx[1], nx[2]));
        watch(j);
        return true;
    }

    /** The retrace's under-ground test: more than 3 below the top and no sky over the head. */
    static boolean underGround(LocalPlayer p) {
        int[] me = here(p);
        net.minecraft.world.level.Level lv = p.level();
        int top = lv.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, me[0], me[2]);
        return top - me[1] > 3 && !lv.canSeeSky(p.blockPosition().above());
    }

    /**
     * 0.33.0 (A1/A2, live: a 1-hop retrace skipped its hop, then the dig-out climbed blindly): the retrace was tried and
     * the walk still ends under ground: the paper escape once (walk plan, then digging plan, whispered). True: started.
     */
    private boolean paperAfterRetrace(LocalPlayer p, Job j) {
        try {
            if (!j.retraceTried || j.paperEscape || j.retraceFinal != null || !j.type.equals("travel") || !underGround(p)) return false;
            j.paperEscape = true;
            LOG.info("[entropybot] escape: the trail back didn't get me out from {}: walk plan, then a digging plan", fmt(here(p)));
            PaperPlan.INSTANCE.escape(commands, p, commands.owner());
            return true;
        } catch (RuntimeException e) {
            LOG.warn("[entropybot] escape: paper plan failed to start: {}", e.toString());
            return false;
        }
    }

    /** 0.33.0 (A1): the walk's end once the paper escape runs: no staircase, no dig-out; the plans' answer is whispered. */
    String paperEscapeEnd(LocalPlayer p) {
        String s = "error: stuck under ground at " + fmt(here(p)) + " - my trail back didn't get me out; planning a way out (on foot, then with digging): the answer comes as a whisper - next: plan last";
        LOG.info("[entropybot] escape: {}", s);
        return s;
    }

    private boolean startRetrace(LocalPlayer p, Job j, IBaritone b, String why) {
        try {
            if (j.retraceTried || j.retraceFinal != null || j.doorFinal != null || b == null || !j.type.equals("travel")) return false;
            int[] me = here(p), d = travelDest(j);
            if (d == null) return false;
            net.minecraft.world.level.Level lv = p.level();
            int top = lv.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, me[0], me[2]);
            if (top - me[1] <= 3 || lv.canSeeSky(p.blockPosition().above())) return false;
            j.retraceTried = true;
            java.util.List<int[]> hops = io.github.mojolowjo.entropybot.trail.TrailRuntime.INSTANCE.trail().retrace(Guard.dimOf(lv), me[0], me[1], me[2], 8, 8, 400);
            if (hops.isEmpty()) {
                LOG.info("[entropybot] retrace ({}): no trail back to open sky from {}", why, fmt(me));
                j.paperEscape = true;
                PaperPlan.INSTANCE.escape(commands, p, commands.owner());        // P3.4: a long search, then a plan with digging
                return false;
            }
            j.retraceHops = new java.util.ArrayDeque<>(hops);
            j.retraceFinal = d;
            int[] h = j.retraceHops.pollFirst();
            LOG.info("[entropybot] retrace ({}): {} hops back along my trail to {} (open sky), then {}", why, hops.size(), fmt(hops.get(hops.size() - 1)), fmt(d));
            core.events.push("job", "retrace: " + hops.size() + " hops back along my trail", null);
            j.dest = h;
            j.shortTries = 0;
            j.startTick = core.tick();
            j.lastStepTick = -1;
            walkGoal(b, j, new baritone.api.pathing.goals.GoalNear(new BlockPos(h[0], h[1], h[2]), 2));
            watch(j);
            return true;
        } catch (RuntimeException e) {
            LOG.warn("[entropybot] retrace failed (the old escape runs): {}", e.toString());
            return false;
        }
    }

    /** 0.32.3: "plan go": a plain walk (never the long route) to the plan's end; onJob gets the job id before the goal is set. */
    String startPlanGo(int[] e, boolean brk, boolean plc, String from, java.util.function.LongConsumer onJob) {
        LocalPlayer p = Minecraft.getInstance().player;
        if (p == null) return "error: not in a world";
        String r = startTravel("goto " + fmt(e), "plan go to " + fmt(e), e, Guard.dimOf(p.level()), false,
                io.github.mojolowjo.entropybot.guard.AreaTypeRules.Walker.OWNER_ORDER);
        Job j = job;
        if (r == null || r.startsWith("error") || r.startsWith("busy") || j == null || j.done) return r;
        onJob.accept(j.id);
        if (j.longRoute && io.github.mojolowjo.entropybot.baritone.LongRouteProcess.INSTANCE.walkId() == j.id) {
            io.github.mojolowjo.entropybot.baritone.LongRouteProcess.INSTANCE.stop();
            j.longRoute = false;
        }
        IBaritone b = baritone();
        j.retraceTried = true;                       // the plan is the way: no trail retrace inside it
        if (b != null) walkGoal(b, j, new baritone.api.pathing.goals.GoalBlock(e[0], e[1], e[2]));
        return r + (brk || plc ? " (breaking " + (brk ? "on" : "off") + ", placing " + (plc ? "on" : "off") + " for this walk only)" : "");
    }

    boolean recoverShort(LocalPlayer p, Job j) {
        int[] me = here(p), d = travelDest(j);
        BlockPos best = bestFreeStep(p, d);
        double hereDist = d == null ? 0 : Math.sqrt(distSq(me, d));
        double stepDist = best == null || d == null ? Double.MAX_VALUE : Math.sqrt(distSq(new int[]{best.getX(), best.getY(), best.getZ()}, d));
        if (WalkEnd.stepHelps(stepDist, hereDist)) return j.unstickTries < UNSTICK_TRIES && stepTo(p, j, best);
        return startEscape(p, j, true);
    }

    /** The free spot next to the bot nearest the destination, or null. */
    static BlockPos bestFreeStep(LocalPlayer p, int[] d) {
        Minecraft mc = Minecraft.getInstance();
        int[] me = here(p);
        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;
        for (int dy = 1; dy >= -1; dy--) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dz == 0) continue;
                    BlockPos c = new BlockPos(me[0] + dx, me[1] + dy, me[2] + dz);
                    if (!standable(mc, c)) continue;
                    // 0.27.3 (d1, live: "stepping to -10 70 31" out of a sealed shelter): a diagonal cell counts only when
                    // one of the two side cells lets the body through (the shell's open corners are no way out)
                    if (dx != 0 && dz != 0 && !SurfaceSeal.diagonalPass((x, y, z) -> open(mc, new BlockPos(x, y, z)), me, dx, dy, dz)) continue;
                    double score = d == null ? Math.abs(dx) + Math.abs(dz) : Math.sqrt(distSq(new int[]{c.getX(), c.getY(), c.getZ()}, d));
                    if (score < bestScore) {
                        bestScore = score;
                        best = c;
                    }
                }
            }
        }
        return best;
    }

    private boolean stepTo(LocalPlayer p, Job j, BlockPos best) {
        Minecraft mc = Minecraft.getInstance();
        int[] me = here(p);
        IBaritone b = baritone();
        if (b != null) cancel(b);
        j.unstickTries++;
        j.unstickLeft = UNSTICK_TICKS;
        j.unstickTo = new double[]{best.getX() + 0.5, best.getY(), best.getZ() + 0.5};
        LOG.info("[entropybot] stuck at {} (on {}): stepping to {} {} {}", fmt(me),
                mc.level.getBlockState(BlockPos.containing(p.getX(), p.getY() - 0.01, p.getZ())).getBlock().getDescriptionId(), best.getX(), best.getY(), best.getZ());
        core.events.push("job", "unstick: stepping off at " + fmt(me), null);
        return true;
    }

    private void unstickStep(LocalPlayer p, Job j) {
        Minecraft mc = Minecraft.getInstance();
        p.lookAt(EntityAnchorArgument.Anchor.FEET, new Vec3(j.unstickTo[0], p.getY(), j.unstickTo[2]));
        mc.options.keyUp.setDown(true);
        mc.options.keyJump.setDown(j.unstickTo[1] >= Math.floor(p.getY()));
        if (--j.unstickLeft > 0) return;
        endUnstick(j);
        // and plan again from the new spot (a fresh stuck clock and fresh path events)
        IBaritone b = baritone();
        if (j.longRoute) io.github.mojolowjo.entropybot.baritone.LongRouteProcess.INSTANCE.resume(here(Minecraft.getInstance().player), "after stepping off");      // 0.23.3
            else if (b != null && j.goalObj != null) b.getCustomGoalProcess().setGoalAndPath(j.goalObj);
        else if (b != null && j.goal != null) b.getCommandManager().execute(j.goal);
        j.startTick = core.tick();
        j.lastStepTick = -1;
        watch(j);
    }

    // ---- P1: the escape dig-out ----

    /** What the escape planner sees at x y z (loaded chunks only; anything doubtful is never broken). */
    private EscapePlan.Kind escapeKind(net.minecraft.world.level.Level level, io.github.mojolowjo.entropybot.guard.Policy pol, String dim, int x, int y, int z) {
        BlockPos bp = new BlockPos(x, y, z);
        if (!level.isLoaded(bp)) return EscapePlan.Kind.BLOCKED;
        net.minecraft.world.level.block.state.BlockState st = level.getBlockState(bp);
        if (!st.getFluidState().isEmpty()) return EscapePlan.Kind.BLOCKED;
        if (st.getCollisionShape(level, bp).isEmpty()) return EscapePlan.Kind.OPEN;
        if (!st.isCollisionShapeFullBlock(level, bp)) return EscapePlan.Kind.BLOCKED;
        if (houseCell(dim, x, y, z)) return EscapePlan.Kind.FLOOR_ONLY;      // 0.30.6: never through a house's wall, floor or roof
        // 0.27.3 fix: my own shelter blocks (shelterBlocks / the ledger) are mine to open, not a stranger's build
        if ((ShelterJob.ownBlocks(commands).contains(x + " " + y + " " + z) || RestoreLive.INSTANCE.builtByMe(x, y, z))
                && !st.hasBlockEntity() && Guard.liquidNextTo(level, x, y, z) == null) return EscapePlan.Kind.BREAKABLE;
        // 0.30.7: the owner's rule (2026-10-11): in my own neutral/destroy areas any block may go (also for the stair side)
        if (digOutAny(level, pol, dim, x, y, z)) return EscapePlan.Kind.BREAKABLE;
        String id = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(st.getBlock()).toString();
        boolean ok = RestoreRules.natural(id) && !st.hasBlockEntity() && !core.guard.isProtectedBlock(st.getBlock())
                && !st.is(net.neoforged.neoforge.common.Tags.Blocks.ORES) && st.getDestroySpeed(level, bp) >= 0
                && Guard.liquidNextTo(level, x, y, z) == null
                && io.github.mojolowjo.entropybot.guard.GuardCore.cellLeasable(pol, dim, x, y, z)
                && !(level.getBlockState(bp.above()).getBlock() instanceof net.minecraft.world.level.block.FallingBlock);
        return ok ? EscapePlan.Kind.BREAKABLE : EscapePlan.Kind.FLOOR_ONLY;
    }

    /**
     * No free step next to the bot: when it is underground (no sky, 3+ under the surface) and inside the areas, break
     * the fewest natural blocks that open one step out (EscapePlan), each recorded in the restore ledger as "escape", then
     * step there. False (and a log line saying why) when it may not or can't.
     */
    boolean startEscape(LocalPlayer p, Job j) {
        return startEscape(p, j, false);
    }

    /** towardGoal: a walk that ended short (only steps closer to the goal; EscapePlan). */
    boolean startEscape(LocalPlayer p, Job j, boolean towardGoal) {
        if (j.escapes >= ESCAPE_MAX || j.reflex) return false;
        Minecraft mc = Minecraft.getInstance();
        net.minecraft.world.level.Level level = mc.level;
        int[] me = here(p);
        String dim = Guard.dimOf(level);
        // 0.19.7: a tree crown over an open pit is no roof (live: canSeeSky said no under birch leaves 10 up), so the
        // surface ignores leaves and "sky" is also true when nothing but leaves is over the head
        // 0.27.3 fix (live d1): in my own shelter its roof makes the spot look underground: open my own wall first,
        // before the underground/surface split (force lease per own cell, the shelter leave's way)
        if (ownShelterExit(level, me) != null && startSurfaceEscape(p, j, me, dim, towardGoal)) return true;
        int surface = surfaceNoLeaves(level, me[0], me[2]);
        boolean sky = level.canSeeSky(new BlockPos(me[0], me[1] + 1, me[2])) || RestoreRules.openAbove(surface, me[1]);
        // 0.19.7: a climb out this job's own dig-out started may go on near the surface (live: the first step out of a
        // sealed pit left the bot in a 2-deep hole of its own, open to the sky, that it can't jump out of)
        boolean ownClimb = towardGoal && j.escapeAt != null && Math.abs(me[1] - j.escapeAt[1]) <= 4;
        if (!RestoreRules.underground(sky, surface, me[1]) && !ownClimb) {
            // 0.27.2: on the surface only leaves, plants, snow layers and its own shelter blocks (run1: a shelter in leaves)
            if (startSurfaceEscape(p, j, me, dim, towardGoal)) return true;
            LOG.info("[entropybot] stuck at {} with no free step, on the surface: no way out by leaves, plants or own blocks (owner's rule): {}", fmt(me), surfaceWhy(level, dim, me));
            return false;
        }
        if (!commands.inAreas(dim, me[0], me[2])) {
            if (startSurfaceEscape(p, j, me, dim, towardGoal)) return true;
            LOG.info("[entropybot] stuck at {} with no free step, outside my areas: no dig-out (surface: {})", fmt(me), surfaceWhy(level, dim, me));
            return false;
        }
        io.github.mojolowjo.entropybot.guard.Policy pol = core.guard.core.policy();
        EscapePlan.Escape e = EscapePlan.plan((x, y, z) -> escapeKind(level, pol, dim, x, y, z), me, travelDest(j), towardGoal);
        if (e == null) {
            LOG.info("[entropybot] stuck at {} underground: no way out without breaking a built block, a container, a block by water or lava, or more than {}: {}", fmt(me), EscapePlan.MAX_BREAKS,
                    EscapePlan.explain((x, y, z) -> escapeKind(level, pol, dim, x, y, z), c -> surfaceWhat(level, dim, c), me));
            core.events.push("job", "escape: no safe dig-out at " + fmt(me), null);
            // 0.27.3 (d3): underground inside the areas: a staircase up toward the surface instead (after this job ends)
            if (!io.github.mojolowjo.entropybot.camp.ShelterPlan.anyOwn(me, ShelterJob.knownOwn(commands, me)))      // never from inside my own shell
                planStairsUp(me, surface, travelDest(j), (x, y, z) -> escapeKind(level, pol, dim, x, y, z));
            return false;
        }
        IBaritone b = baritone();
        if (b != null) cancel(b);
        j.escapes++;
        if (e.breaks().isEmpty()) {
            j.unstickLeft = UNSTICK_TICKS;
            j.unstickTo = new double[]{e.feet()[0] + 0.5, e.feet()[1], e.feet()[2] + 0.5};
            return true;
        }
        int x1 = Integer.MAX_VALUE, y1 = Integer.MAX_VALUE, z1 = Integer.MAX_VALUE, x2 = Integer.MIN_VALUE, y2 = Integer.MIN_VALUE, z2 = Integer.MIN_VALUE;
        for (int[] c : e.breaks()) {
            x1 = Math.min(x1, c[0]); y1 = Math.min(y1, c[1]); z1 = Math.min(z1, c[2]);
            x2 = Math.max(x2, c[0]); y2 = Math.max(y2, c[1]); z2 = Math.max(z2, c[2]);
        }
        // 0.30.7 (review): a placed block on the way out that the owner's rule allows (digOutAny) gets a one-cell force
        // lease, as the surface dig-out does; the box lease stays a plain one (no force over cells the rule doesn't cover)
        io.github.mojolowjo.entropybot.clear.LeaseSet forced = null;
        for (int[] c : e.breaks()) {
            if (!core.guard.isProtectedBlock(level.getBlockState(new BlockPos(c[0], c[1], c[2])).getBlock()) || !digOutAny(level, pol, dim, c[0], c[1], c[2])) continue;
            if (forced == null) forced = Clearing.newLeases();
            String fl = forced.take("escape dig-out at " + fmt(c), new io.github.mojolowjo.entropybot.clear.ClearBox(c[0], c[1], c[2], c[0], c[1], c[2]), false, true);
            LOG.info("[entropybot] stuck at {}: the dig-out breaks the placed block at {} inside my own area (owner's rule): one-cell force lease{}", fmt(me), fmt(c), fl == null ? "" : " refused (" + fl + ")");
            if (fl != null && core.guard.core.mode() == io.github.mojolowjo.entropybot.guard.GuardCore.Mode.STRICT) {
                forced.releaseAll();
                return false;
            }
        }
        String le = core.guard.core.lease(core.token, "escape dig-out", new io.github.mojolowjo.entropybot.guard.Box("escape", dim, x1, y1, z1, x2, y2, z2), false, false);
        if (le.startsWith("error") && core.guard.core.mode() == io.github.mojolowjo.entropybot.guard.GuardCore.Mode.STRICT && forced != null) forced.releaseAll();
        if (le.startsWith("error")) {
            if (core.guard.core.mode() == io.github.mojolowjo.entropybot.guard.GuardCore.Mode.STRICT) {
                LOG.info("[entropybot] stuck at {}: the guard refused the dig-out ({})", fmt(me), le);
                return false;
            }
            le = null;
        }
        j.escapeLease = le;
        j.escapeLeaseSet = forced;
        j.escapeBreaks = e.breaks();
        j.escapeIdx = 0;
        j.escapeHit = null;
        j.escapeTo = e.feet();
        j.escapeTick = core.tick();
        if (j.escapeAt == null) j.escapeAt = me;
        RestoreLive.INSTANCE.expectEscape(j.id, e.breaks(), dim);
        holdPickaxe(mc, p);
        StringBuilder cells = new StringBuilder();
        for (int[] c : e.breaks()) cells.append(fmt(c)).append("; ");
        LOG.info("[entropybot] stuck at {} underground with no free step: digging out {} block(s) ({}) to {}", fmt(me), e.breaks().size(), cells.toString().replaceAll("; $", ""), fmt(e.feet()));
        core.events.push("job", "escape: digging out " + e.breaks().size() + " block(s) at " + fmt(me), null);
        return true;
    }

    /**
     * 0.30.7 (review): the stair side may run through placed blocks the owner's rule allows (digOutAny): each dig step
     * whose box holds such a protected block gets " force" (StairPlan.withForce). Never throws (the steps unchanged).
     */
    private java.util.List<String> placedForce(java.util.List<String> steps) {
        try {
            net.minecraft.world.level.Level level = Minecraft.getInstance().level;
            if (level == null) return steps;
            io.github.mojolowjo.entropybot.guard.Policy pol = core.guard.core.policy();
            String dim = Guard.dimOf(level);
            return StairPlan.withForce(steps, (x, y, z) -> core.guard.isProtectedBlock(level.getBlockState(new BlockPos(x, y, z)).getBlock())
                    && digOutAny(level, pol, dim, x, y, z));
        } catch (RuntimeException e) {
            LOG.warn("[entropybot] stairs: the placed-block force check failed: {}", e.toString());
            return steps;
        }
    }

    /** 0.27.3 (d3): the staircase up a sealed underground spot gets (a chain started once this job has ended), or null. */
    volatile String pendingStairs;
    private long stairsWhisperedAt = -1_000_000;

    /** Picks the side (toward dest first) whose first step's cells are all natural and breakable, and notes the staircase. */
    private void planStairsUp(int[] me, int surface, int[] dest, EscapePlan.Grid g) {
        int n = StairPlan.upCount(me[1], surface);
        if (n <= 0) return;
        int[] side = EscapePlan.stairSide(g, me, dest);
        if (side == null) {
            LOG.info("[entropybot] stuck at {} underground: no side to dig a staircase up from (built blocks, fluid or a protect box next to me)", fmt(me));
            return;
        }
        java.util.List<String> steps = new java.util.ArrayList<>(StairPlan.upSteps(me[0], me[1], me[2], side[0], side[1], n));
        // an own shelter roof over the head (the pit's plank): the first dig may break my own block (force, never a chest)
        String roof = me[0] + " " + (me[1] + 2) + " " + me[2];
        if (ShelterJob.ownBlocks(commands).contains(roof) || RestoreLive.INSTANCE.builtByMe(me[0], me[1] + 2, me[2])) steps.set(0, steps.get(0) + " force");
        steps = new java.util.ArrayList<>(placedForce(steps));          // 0.30.7 (review): placed blocks the owner's rule allows
        pendingStairs = String.join(" then ", steps);
        String msg = "I am sealed in underground at " + fmt(me) + " - digging a staircase up (" + n + " steps " + StairPlan.dirName(side[0], side[1]) + ")";
        LOG.info("[entropybot] {}", msg);
        if (core.tick() - stairsWhisperedAt > 20 * 60 * 10) {      // once per 10 minutes
            stairsWhisperedAt = core.tick();
            commands.whisper(commands.owner(), msg);
        }
    }

    /** 0.27.4: the goal of the last sealed-walk staircase, and how many were dug for it (at most SealedWalk.MAX_STAIRS). */
    private int[] stairDest;
    private int stairCount;

    /**
     * 0.27.4: a failed walk underground inside the areas toward a goal above: the staircase up to the goal's height, then
     * a goto back to the goal (a chain, started by stairsTick once nothing runs). Returns the whisper line, or null (the
     * caller reports the walk's failure) with the reason logged.
     */
    private String sealedStairs(LocalPlayer p, int[] me, int[] d) {
        try {
            Minecraft mc = Minecraft.getInstance();
            net.minecraft.world.level.Level level = mc.level;
            if (level == null || d == null || me == null) return null;
            String dim = Guard.dimOf(level);
            if (!java.util.Arrays.equals(stairDest, d)) { stairDest = d.clone(); stairCount = 0; }
            int surface = surfaceNoLeaves(level, me[0], me[2]);
            boolean sky = level.canSeeSky(new BlockPos(me[0], me[1] + 1, me[2])) || RestoreRules.openAbove(surface, me[1]);
            boolean under = RestoreRules.underground(sky, surface, me[1]);
            boolean inAreas = commands.inAreas(dim, me[0], me[2]);
            if (!SealedWalk.want(under, inAreas, d, me, true, stairCount)) {
                if (under && inAreas && d[1] > me[1] + 2) LOG.info("[entropybot] sealed walk at {}: no more staircases for {} ({} dug)", fmt(me), fmt(d), stairCount);
                return null;
            }
            io.github.mojolowjo.entropybot.guard.Policy pol = core.guard.core.policy();
            EscapePlan.Grid g = (x, y, z) -> escapeKind(level, pol, dim, x, y, z);
            int[] side = EscapePlan.stairSide(g, me, d);
            if (side == null) {
                LOG.info("[entropybot] sealed walk at {}: no side to dig a staircase up from (built blocks, fluid or a protect box next to me)", fmt(me));
                return null;
            }
            int n = SealedWalk.steps(d, me);
            java.util.List<String> steps = new java.util.ArrayList<>(StairPlan.upSteps(me[0], me[1], me[2], side[0], side[1], n));
            String roof = me[0] + " " + (me[1] + 2) + " " + me[2];
            if (ShelterJob.ownBlocks(commands).contains(roof) || RestoreLive.INSTANCE.builtByMe(me[0], me[1] + 2, me[2])) steps.set(0, steps.get(0) + " force");
            steps = new java.util.ArrayList<>(placedForce(steps));          // 0.30.7 (review): placed blocks the owner's rule allows
            steps.add("goto " + d[0] + " " + d[1] + " " + d[2]);
            pendingStairs = String.join(" then ", steps);
            stairCount++;
            String msg = "I am sealed in underground at " + fmt(me) + " - digging a staircase up (" + n + " steps " + StairPlan.dirName(side[0], side[1]) + ")";
            LOG.info("[entropybot] sealed walk: {} (then goto {})", msg, fmt(d));
            return msg;
        } catch (RuntimeException e) {
            LOG.warn("[entropybot] sealed walk staircase failed to plan: {}", e.toString());
            return null;
        }
    }

    /** Called every tick by Commands: the noted staircase starts as a chain once nothing runs. */
    void stairsTick() {
        String s = pendingStairs;
        if (s == null || running()) return;
        pendingStairs = null;
        try {
            Chains ch = commands.chainsRef();
            if (ch == null || ch.running()) {
                LOG.info("[entropybot] escape staircase not started: a chain runs");
                return;
            }
            String r = ch.startChain(commands.owner(), "escape stairs", s, 1);
            LOG.info("[entropybot] escape staircase: {}", r);
        } catch (RuntimeException e) {
            LOG.warn("[entropybot] escape staircase failed to start: {}", e.toString());
        }
    }

    private static void holdPickaxe(Minecraft mc, LocalPlayer p) {
        String[] tiers = {"netherite_pickaxe", "diamond_pickaxe", "iron_pickaxe", "stone_pickaxe", "golden_pickaxe", "wooden_pickaxe"};
        for (String t : tiers) {
            for (int i = 0; i < 36; i++) {
                net.minecraft.world.item.ItemStack s = p.getInventory().getItem(i);
                if (!s.isEmpty() && io.github.mojolowjo.entropybot.gui.Gui.itemId(s).endsWith(t)) {
                    io.github.mojolowjo.entropybot.engine.Hotbar.toHand(mc, p, i);
                    return;
                }
            }
        }
    }

    /** One tick of the dig-out: the current cell hit until it is open, then the next; all open: step out. */
    private void escapeStep(LocalPlayer p, Job j) {
        Minecraft mc = Minecraft.getInstance();
        long now = core.tick();
        if (now % 20 == 0) core.guard.core.heartbeat(core.token);
        int[] c = j.escapeBreaks.get(j.escapeIdx);
        BlockPos bp = new BlockPos(c[0], c[1], c[2]);
        var st = mc.level.getBlockState(bp);
        if (st.getCollisionShape(mc.level, bp).isEmpty()) {
            j.escapeIdx++;
            j.escapeHit = null;
            j.escapeTick = now;
            if (j.escapeIdx < j.escapeBreaks.size()) return;
            j.escapeDug += j.escapeBreaks.size();
            endEscape(j);
            j.escapeWaiting = true;
            j.unstickLeft = UNSTICK_TICKS;
            j.unstickTo = new double[]{j.escapeTo[0] + 0.5, j.escapeTo[1], j.escapeTo[2] + 0.5};
            LOG.info("[entropybot] escape: dug out, stepping to {}", fmt(j.escapeTo));
            return;
        }
        if (now - j.escapeTick > ESCAPE_BLOCK_TICKS) {
            String what = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(st.getBlock()).getPath();
            endEscape(j);
            mc.options.keyAttack.setDown(false);
            finish("error: stuck at " + fmt(here(p)) + " and couldn't dig out: the " + what + " at " + fmt(c) + " didn't break in 10 s");
            return;
        }
        Vec3 center = Vec3.atCenterOf(bp);
        p.lookAt(EntityAnchorArgument.Anchor.EYES, center);
        Vec3 eye = p.getEyePosition();
        Direction face = Direction.getNearest((float) (eye.x - center.x), (float) (eye.y - center.y), (float) (eye.z - center.z));
        if (j.escapeHit == null || j.escapeHit[0] != c[0] || j.escapeHit[1] != c[1] || j.escapeHit[2] != c[2]) {
            mc.gameMode.startDestroyBlock(bp, face);
            j.escapeHit = c;
        } else {
            mc.gameMode.continueDestroyBlock(bp, face);
        }
        p.swing(InteractionHand.MAIN_HAND);
    }

    /** The dig-out's lease goes, its expected cells are forgotten. */
    private void endEscape(Job j) {
        if (j.escapeLease != null) core.guard.core.release(j.escapeLease);
        j.escapeLease = null;
        if (j.escapeLeaseSet != null) try { j.escapeLeaseSet.releaseAll(); } catch (RuntimeException ignored) {}
        j.escapeLeaseSet = null;
        j.escapeBreaks = null;
        j.escapeHit = null;
        RestoreLive.INSTANCE.expectEscape(j.id, java.util.List.of(), "");
        try { Minecraft.getInstance().gameMode.stopDestroyBlock(); } catch (RuntimeException ignored) {}
    }

    static void endUnstick(Job j) {
        Minecraft mc = Minecraft.getInstance();
        mc.options.keyUp.setDown(false);
        mc.options.keyJump.setDown(false);
        j.unstickLeft = 0;
    }

    /**
     * The reflexes against the job (the bridge's syncReflexes for its own jobs): a held job's clocks move on by the
     * hold and the reflex's own path events are not the job's; a retreat stops any job, an urgent fight stops what
     * isn't a walk, a denied dimension stops everything.
     */
    private void reflexWatch(LocalPlayer p) {
        Reflexes.Reflex r = core.reflexes.reflex(), prev = reflexWas;
        reflexWas = r;
        long now = core.tick();
        reflexNotes(p, r, now);
        if (r != Reflexes.Reflex.NONE && prev == Reflexes.Reflex.NONE) {
            holdStart = now;
            if (running() && job.onHold != null) {
                try { job.onHold.run(); } catch (RuntimeException e) { LOG.warn("[entropybot] job hold hook: {}", e.toString()); }
            }
        }
        if (r == Reflexes.Reflex.NONE && prev != Reflexes.Reflex.NONE && holdStart >= 0) {
            long held = now - holdStart;
            holdStart = -1;
            if (running()) {
                job.startTick += held;
                if (job.seq != null) job.seq.afterHold(held, p);
                if (job.evSeq >= 0) {
                    job.evSeq = core.events.lastSeq();
                    job.evFails = 0;
                    job.lastStepTick = -1;
                }
            }
        }
        if (!running()) return;
        JsonObject st = core.reflexes.status();
        String target = st.has("target") ? st.get("target").getAsString() : "a monster";
        if (r == Reflexes.Reflex.RETREATING && prev != Reflexes.Reflex.RETREATING && !core.reflexes.sheltering()) {   // 0.24.1: a shelter keeps the job
            stopForReflex("stopped: low health, getting away from " + target);
        } else if ((r == Reflexes.Reflex.FIGHTING || r == Reflexes.Reflex.FLEEING) && st.has("urgent") && st.get("urgent").getAsBoolean()
                && !job.type.equals("travel") && !job.holdOnFight) {
            stopForReflex("stopped: attacked by " + target);
        } else if (st.has("deniedDim")) {
            stopForReflex("stopped: I am in " + st.get("deniedDim").getAsString() + ", which is off limits");
        }
    }

    /** B7e (E1): the reflex whispers (the bridge's syncReflexes texts), once per transition. */
    private final io.github.mojolowjo.entropybot.engine.ReflexNotes notes = new io.github.mojolowjo.entropybot.engine.ReflexNotes();

    private void reflexNotes(LocalPlayer p, Reflexes.Reflex r, long now) {
        try {
            JsonObject st = core.reflexes.status();
            java.util.List<String> say = notes.step(r.name(), st.has("target") ? st.get("target").getAsString() : null, p.getHealth(),
                    st.has("deniedDim") ? st.get("deniedDim").getAsString() : null,
                    st.has("noFood") && st.get("noFood").getAsBoolean(), p.getFoodData().getFoodLevel());
            say = new java.util.ArrayList<>(say);
            say.addAll(core.reflexes.takeWhispers());       // B7e C: a creeper explosion near the bot
            for (String w : say) commands.whisper(commands.owner(), w);
        } catch (RuntimeException e) {
            LOG.warn("[entropybot] reflex notes: {}", e.toString());
        }
    }

    private void stopForReflex(String msg) {
        finish(msg);
        IBaritone b = baritone();
        if (b != null) cancel(b);
    }

    /** T3: the tick the bot first stood 2+ blocks outside the areas during a dig (-1: it doesn't). */
    private long fenceOutSince = -1;
    /** 28a: the last spot of this job that was inside an area, and when the walk back was last ordered. */
    private int[] fenceLastInside;
    private long fenceBackTick = -1000;

    /** T3: the mod's own break leases (a clear holds them while it digs; a walk or a craft trip holds none). */
    private boolean holdsDigLeases() {
        for (io.github.mojolowjo.entropybot.guard.Lease l : core.guard.core.leases().values()) {
            if (!l.place && core.token.equals(l.owner)) return true;
        }
        return false;
    }

    /** Every 20 ticks with the fence on: a job outside every area stops; a follow ends when its player leaves the areas. */
    private void fenceWatch(LocalPlayer p) {
        if (!commands.fenceOn()) return;
        int[] me = here(p);
        String dim = Guard.dimOf(p.level());
        if (FenceRules.watches(true, running(), running() && (job.reflex || job.goalInside))) {
            // T3: a clear pulled 2-4 blocks out (a drop, a cave) gets 15 s to come back; everything else as before
            int gap = commands.areaGap(me[0], me[1], me[2], dim);
            long now = core.tick();
            FenceGrace.Verdict v = FenceGrace.verdict(gap, gap > 1 && holdsDigLeases(), fenceOutSince, now);
            if (v == FenceGrace.Verdict.OK) {
                fenceOutSince = -1;
                if (gap == 0) fenceLastInside = me;
            } else if (v == FenceGrace.Verdict.GRACE) {
                if (fenceOutSince < 0) {
                    fenceOutSince = now;
                    LOG.info("[entropybot] {} block(s) outside my areas at {} - 15 s to get back in", gap, fmt(me));
                }
                // 28a: Baritone's own path took it out (a cave beside the tunnel): when it stands idle out there, walk back
                // to the last spot that was inside; the dig then goes on from there
                IBaritone gb = baritone();
                if (gb != null && fenceLastInside != null && idle(gb) && now - fenceOutSince >= 20 && now - fenceBackTick >= 100) {
                    fenceBackTick = now;
                    LOG.info("[entropybot] walking back inside to {}", fmt(fenceLastInside));
                    safeSettings();
                    gb.getCustomGoalProcess().setGoalAndPath(new baritone.api.pathing.goals.GoalBlock(new BlockPos(fenceLastInside[0], fenceLastInside[1], fenceLastInside[2])));
                }
            } else {
                boolean afterGrace = fenceOutSince >= 0;
                fenceOutSince = -1;
                IBaritone b = baritone();
                if (b != null) cancel(b);
                finish(FenceGrace.stopMessage(fmt(me), afterGrace));
                return;
            }
        } else {
            fenceOutSince = -1;
        }
        if (followWatch != null) {
            Player t = findPlayer(followWatch[0]);
            if (t == null) return;
            int[] tp = here(t);
            String why = goalAllowed(tp[0], tp[1], tp[2]);
            if (why != null) {
                IBaritone b = baritone();
                if (b != null) cancel(b);
                commands.whisper(followWatch[1], FenceRules.followLeft(followWatch[0], tp));
                LOG.info("[entropybot] follow stopped: {} left the areas at {} ({})", followWatch[0], fmt(tp), why);
                followWatch = null;
            }
        }
    }

    // ---- following a player the bot cannot see (the owner's companion mod) ----

    /** "follow <name>" with the player out of view but a fresh position t: walk there and keep walking as it changes. */
    String startFollowFix(String name, String from, int[] t) {
        String r = followFixGo(name, from, t);
        return r.startsWith("ok") ? r + " (by the position your game sends, until you're in view)" : r;
    }

    /** One walk to t, then the follow state again (startTravel clears it); like a follow, the job itself never "arrives". */
    private String followFixGo(String name, String from, int[] t) {
        String r = startTravel("goto " + fmt(t), "following " + name, null, null, false);
        if (!r.startsWith("ok")) return r;
        if (job != null) job.done = true;
        followFix = new String[]{name, from};
        followFixGoal = t;
        followFixAt = System.currentTimeMillis();
        return r;
    }

    private void stopFollowFix(String why) {
        String from = followFix[1];
        followFix = null;
        IBaritone b = baritone();
        if (b != null) cancel(b);
        commands.whisper(from, why);
        LOG.info("[entropybot] follow (companion position) ended: {}", why);
    }

    /** Once a second: hand over when the player is in view, stop when the position is stale, walk again when they moved 5+ blocks. */
    private void followFixTick(LocalPlayer p) {
        if (followFix == null) return;
        if (running()) {                         // another job took over
            followFix = null;
            return;
        }
        if (core.reflexes.hold()) return;        // a fight or a meal: not now
        String name = followFix[0], from = followFix[1];
        if (findPlayer(name) != null) {          // in view: Baritone's own follow does it better
            followFix = null;
            commands.modJob("follow", name, from, p);
            return;
        }
        int[] t = commands.ownerFixPos(name);
        if (t == null) {
            stopFollowFix("stopped: I lost track of you (no fresh position from your game, or you're in another dimension)");
            return;
        }
        // re-aim only when the player moved a fair share of the way (5 blocks, or 1/8 of the distance when far): every
        // new goal is a whole new Baritone search, and far away a few blocks change nothing about the way there
        double far = Math.sqrt(distSq(t, here(p))) / 8;
        long moved = (long) Math.max(5, far);
        if (System.currentTimeMillis() - followFixAt < 1500 || distSq(t, followFixGoal) <= moved * moved) return;
        String r = followFixGo(name, from, t);
        if (!r.startsWith("ok")) stopFollowFix("stopped: " + r.replaceFirst("^error: ", ""));
    }

    // ---- blocks ----

    static Player findPlayer(String name) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || name == null) return null;
        for (Player p : mc.level.players()) if (p.getGameProfile().getName().equalsIgnoreCase(name)) return p;
        return null;
    }

    /** "find <text> [radius]": the nearest block whose id contains the text. */
    static String findBlock(LocalPlayer p, String text) {
        Minecraft mc = Minecraft.getInstance();
        String[] parts = text.trim().split("\\s+");
        String needle = parts[0].toLowerCase();
        int radius = parts.length > 1 ? (int) Math.min(Chains.leadingInt(parts[1]), 32) : 16;
        int[] me = here(p);
        String best = null;
        long bestDist = Long.MAX_VALUE;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    long d = (long) dx * dx + (long) dy * dy + (long) dz * dz;
                    if (d >= bestDist) continue;
                    pos.set(me[0] + dx, me[1] + dy, me[2] + dz);
                    String id = mc.level.getBlockState(pos).getBlock().getDescriptionId();
                    if (!id.contains(needle)) continue;
                    bestDist = d;
                    best = (me[0] + dx) + " " + (me[1] + dy) + " " + (me[2] + dz) + " " + id;
                }
            }
        }
        return best != null ? "found " + best + " dist " + Math.round(Math.sqrt(bestDist)) : "none within " + radius;
    }

    /** Right-clicks the block at "x y z" (within reach). */
    static String useBlock(LocalPlayer p, String text) {
        String[] c = text.trim().split("\\s+");
        int x = Integer.parseInt(c[0]), y = Integer.parseInt(c[1]), z = Integer.parseInt(c[2]);
        Vec3 center = new Vec3(x + 0.5, y + 0.5, z + 0.5);
        double dist = p.getEyePosition().distanceTo(center);
        if (dist > 4.5) return "error: too far (" + Math.round(dist * 10) / 10.0 + " blocks), walk closer first";
        p.lookAt(EntityAnchorArgument.Anchor.EYES, center);
        Minecraft mc = Minecraft.getInstance();
        InteractionResult r = mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND, new BlockHitResult(center, Direction.UP, new BlockPos(x, y, z), false));
        p.swing(InteractionHand.MAIN_HAND);
        return "ok: " + r;
    }

    /** The job as state.json shows it. */
    JsonObject stateJson() {
        if (job == null) return null;
        JsonObject o = new JsonObject();
        o.addProperty("type", job.type);
        o.addProperty("status", job.status);
        o.addProperty("done", job.done);
        if (job.seq != null && !job.done) o.addProperty("step", (job.seq.idx + 1) + "/" + job.seq.steps.size());
        o.add("requester", com.google.gson.JsonNull.INSTANCE);
        o.addProperty("mod", true);
        return o;
    }

    static int[] pos(JsonObject o) {
        return new int[]{o.get("x").getAsInt(), o.get("y").getAsInt(), o.get("z").getAsInt()};
    }

    static String dimOf(JsonObject o) {
        return o.has("dim") && !o.get("dim").isJsonNull() ? o.get("dim").getAsString() : "minecraft:overworld";
    }
}
