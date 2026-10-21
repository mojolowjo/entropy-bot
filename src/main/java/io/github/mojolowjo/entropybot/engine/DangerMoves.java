package io.github.mojolowjo.entropybot.engine;

import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalNear;
import baritone.api.pathing.goals.GoalXZ;
import com.mojang.logging.LogUtils;
import io.github.mojolowjo.entropybot.events.EventRing;
import io.github.mojolowjo.entropybot.guard.Guard;
import io.github.mojolowjo.entropybot.threat.CornerEscape;
import io.github.mojolowjo.entropybot.threat.FleeHeading;
import io.github.mojolowjo.entropybot.threat.ThreatFacts;
import io.github.mojolowjo.entropybot.threat.ThreatRuntime;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * AA_CLEANUP 4: the danger desire's moves, out of the reflexes. The verbs {@code flee}, {@code cover}, {@code pillar} and
 * {@code burrow} (dig down, else into a wall, and seal) start one; AA's danger desire runs them as chain steps (or the
 * owner types them). Nothing here decides: AA prices the ways on {@link #facts}. While a move runs the reflexes hold the
 * job (Reflexes.hold); it ends when nothing has chased the bot for 5 s, after 90 s, or when it cannot go on (always with
 * a "done ...: why" event). Never throws from a tick.
 *
 * <p>Loader notes: vanilla client calls only (MultiPlayerGameMode.continueDestroyBlock/useItemOn/attack, KeyMapping.setDown,
 * Entity.lookAt); Baritone goals through {@link EngineProcess}; the guard mixin vetoes breaks as always and the dig asks
 * {@code PolicyRuntime} (DIG_OUT) first. The pure parts are {@link CornerEscape}, {@link FleeHeading} and {@link ThreatFacts}.
 */
public final class DangerMoves {
    private static final Logger LOG = LogUtils.getLogger();

    public enum Move { NONE, FLEE, COVER, PILLAR, NICHE, BURROW }

    static final double FLEE_RUN = 16;
    static final long CALM_TICKS = 100, MAX_TICKS = 1800;

    private final EventRing events;
    private final EngineProcess engine;
    private Move move = Move.NONE;
    private long now, start, calmSince;
    private Goal goal;
    private int[] goalXZ;
    // pillar
    private int pillarCell, pillarBase, pillarTop, pillarX, pillarZ;
    private boolean pillarUp, pillarWater;
    // niche
    private int digStage = -1;
    private Direction digDir;
    private BlockPos digEntrance;
    // burrow
    private int burrowStage = -1, burrowDepth, burrowWait;
    private BlockPos burrowTop;
    // cover
    private int coverMob = -1;
    private long coverUntil;

    DangerMoves(EventRing events, EngineProcess engine) {
        this.events = events;
        this.engine = engine;
    }

    /** 0.38.b9 (review: a verb started a move with a stale clock and the first tick ended it): the client tick, every tick. */
    void time(long tick) { now = tick; }

    public boolean active() { return move != Move.NONE; }

    public Move move() { return move; }

    public String doing() {
        return switch (move) {
            case NONE -> "none";
            case FLEE -> "fleeing to " + (goalXZ == null ? "?" : goalXZ[0] + " " + goalXZ[1]);
            case COVER -> "in cover";
            case PILLAR -> pillarUp ? "pillared up, holding" : "pillaring up";
            case NICHE -> digStage >= 5 ? "dug in, holding" : "digging into the wall";
            case BURROW -> burrowStage > burrowDepth ? "dug down, holding" : "digging down";
        };
    }

    // ---- the verbs ----

    /** "flee": the least-threat heading over every hostile near, 16 blocks. */
    public String flee() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null) return "error: not in a world";
        List<FleeHeading.Mob> mobs = mobs(mc, p);
        if (mobs.isEmpty()) return "error: nothing to flee from";
        int[] to = FleeHeading.choose(p.getX(), p.getZ(), mobs, FLEE_RUN);
        if (to == null) return "error: cornered - no heading clear of mobs (burrow or pillar)";
        begin(Move.FLEE, "fleeing to " + to[0] + " " + to[1]);
        goalXZ = to;
        goal = new GoalXZ(to[0], to[1]);
        engine.override(goal);
        return "ok: fleeing to " + to[0] + " " + to[1];
    }

    /** "cover": a cell out of the nearest ranged mob's sight (ThreatRuntime.cover), held 15 s or until it closes in. */
    public String cover() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null) return "error: not in a world";
        Entity r = nearestRanged(mc, p);
        if (r == null) return "error: nothing shoots at me";
        var c = ThreatRuntime.INSTANCE.cover(p, r);
        if (c == null) return "error: no cell out of the " + kind(r) + "'s sight near (or no threat grid)";
        begin(Move.COVER, "taking cover from " + kind(r) + " at " + c.x() + " " + c.y() + " " + c.z());
        coverMob = r.getId();
        coverUntil = now + (long) (20 * io.github.mojolowjo.entropybot.aa.brain.Tuning.get("danger.coverS"));
        goal = new GoalNear(new BlockPos(c.x(), c.y(), c.z()), 0);
        engine.override(goal);
        return "ok: cover at " + c.x() + " " + c.y() + " " + c.z();
    }

    /** "pillar": 3 up on throwaway blocks, then hold and hit what comes in reach. */
    public String pillar() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null) return "error: not in a world";
        if (p.isInLava()) return "error: in lava";
        int[] tw = throwaway(p);
        if (p.isInWater()) return pillarOutOfWater(mc, p, tw);
        if (!p.onGround()) return "error: not standing on ground";
        int head = headRoom(mc, p);
        int up = Math.min(CornerEscape.UP, Math.min(head, tw[0]));
        if (up < CornerEscape.MIN_UP) return "error: no pillar here (" + tw[0] + " blocks, head room " + head + ")";
        if (!Hotbar.toHand(mc, p, tw[1])) return "error: could not take the blocks in hand";
        begin(Move.PILLAR, "pillaring " + up + " up");
        engine.hold();
        stopMoving(mc);
        BlockPos feet = p.blockPosition();
        pillarCell = pillarBase = feet.getY();
        pillarTop = feet.getY() + up;
        pillarX = feet.getX();
        pillarZ = feet.getZ();
        pillarUp = false;
        pillarWater = false;
        return "ok: pillaring " + up + " up";
    }

    /**
     * AA_STEP3 A3: pillar out of water. The column under me is filled from the floor up to the water surface (one block a
     * cell, placed under the feet while swimming up: the game allows a block into a water cell against the one below),
     * so the bot ends standing dry on top. Needs a floor within {@link #WATER_FLOOR_SCAN} and a block per cell.
     */
    private String pillarOutOfWater(Minecraft mc, LocalPlayer p, int[] tw) {
        int[] wf = water(mc, p);
        if (wf[0] <= 0) return "error: in water with no floor within " + WATER_FLOOR_SCAN;
        if (tw[0] < wf[0]) return "error: pillar out of water needs " + wf[0] + " blocks, " + tw[0] + " in the bag";
        for (int y = wf[2] + 1; y < wf[3]; y++) {
            BlockPos c = new BlockPos(p.getBlockX(), y, p.getBlockZ());
            if (Guard.INSTANCE.placeRefused(mc.level, c)) return "error: no placing at " + c.getX() + " " + c.getY() + " " + c.getZ() + " (an area rule): no pillar out here";
        }
        if (!Hotbar.toHand(mc, p, tw[1])) return "error: could not take the blocks in hand";
        begin(Move.PILLAR, "pillaring out of water " + wf[0] + " up");
        engine.hold();
        stopMoving(mc);
        BlockPos feet = p.blockPosition();
        pillarCell = pillarBase = wf[2] + 1;
        pillarTop = wf[3];
        pillarX = feet.getX();
        pillarZ = feet.getZ();
        pillarUp = false;
        pillarWater = true;
        return "ok: pillaring out of water " + wf[0] + " up (floor " + wf[2] + ", surface " + wf[3] + ")";
    }

    /** How far down the water facts look for a floor. */
    static final int WATER_FLOOR_SCAN = 24;

    /**
     * {column, headDepth, floorY, surfaceY}: water cells from the floor (the first solid block under the feet, within
     * {@link #WATER_FLOOR_SCAN}) up to the surface (the first cell over the feet without water); column 0 = not in water
     * or no floor in reach. headDepth = water cells over the head cell.
     */
    static int[] water(Minecraft mc, LocalPlayer p) {
        BlockPos feet = p.blockPosition();
        if (!wet(mc, feet)) return new int[]{0, 0, 0, 0};
        int up = feet.getY();
        while (up - feet.getY() < 64 && wet(mc, new BlockPos(feet.getX(), up, feet.getZ()))) up++;
        int floor = feet.getY() - 1;
        while (feet.getY() - floor <= WATER_FLOOR_SCAN && !solid(mc, new BlockPos(feet.getX(), floor, feet.getZ()))) {
            if (!wet(mc, new BlockPos(feet.getX(), floor, feet.getZ()))) break;      // air under water: no floor to build on
            floor--;
        }
        boolean found = solid(mc, new BlockPos(feet.getX(), floor, feet.getZ()));
        int column = found ? up - floor - 1 : 0;
        return new int[]{column, Math.max(0, up - feet.getY() - 2), floor, up};
    }

    private static boolean wet(Minecraft mc, BlockPos q) {
        return mc.level.getFluidState(q).is(net.minecraft.tags.FluidTags.WATER);
    }

    /**
     * AA_STEP3 A4: the nearest dry standing cell (solid under, feet and head free of blocks and fluid) within
     * {@link ThreatFacts#SHORE_REACH} across, near the water surface; ring by ring, the first ring with one wins
     * (nearest in it). Null none.
     */
    private static int[] shoreMemo;
    private static BlockPos shoreAt;
    private static long shoreMs = -1;
    /** Review 0.39.a4 3: the shore scan is kept this long (and for the same cell). */
    static final long SHORE_CACHE_MS = 2_000;

    /**
     * Review 0.39.a4 2/3: as {@link #shoreScan}, cached {@link #SHORE_CACHE_MS} per feet cell. A cell counts only where
     * the areas let the box be built and the floor be dug (Guard place veto, the BreakPolicy DIG_OUT ask) and where it
     * touches the water (reachable by swimming); else the next candidate.
     */
    static int[] shore(Minecraft mc, LocalPlayer p, int surfaceY) {
        long ms = System.currentTimeMillis();
        BlockPos feet = p.blockPosition();
        if (shoreMs >= 0 && ms - shoreMs < SHORE_CACHE_MS && feet.equals(shoreAt)) return shoreMemo;
        shoreMemo = shoreScan(mc, p, surfaceY);
        shoreAt = feet;
        shoreMs = ms;
        return shoreMemo;
    }

    /** The cell is allowed (areas) and touches water beside it at the feet or one under (a swim reaches it). */
    private static boolean shoreOk(Minecraft mc, BlockPos c) {
        if (Guard.INSTANCE.placeRefused(mc.level, c) || Guard.INSTANCE.placeRefused(mc.level, c.above()) || !policyBreaks(mc, c.below())) return false;
        for (Direction d : Direction.Plane.HORIZONTAL)
            if (wet(mc, c.relative(d)) || wet(mc, c.relative(d).below())) return true;
        return false;
    }

    static int[] shoreScan(Minecraft mc, LocalPlayer p, int surfaceY) {
        BlockPos feet = p.blockPosition();
        int[] best = null;
        double bestD = Double.MAX_VALUE;
        for (int r = 1; r <= ThreatFacts.SHORE_REACH && best == null; r++)
            for (int dx = -r; dx <= r; dx++)
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                    int x = feet.getX() + dx, z = feet.getZ() + dz;
                    if (!mc.level.hasChunkAt(new BlockPos(x, surfaceY, z))) continue;
                    for (int y = surfaceY - 2; y <= surfaceY + 3; y++) {
                        BlockPos c = new BlockPos(x, y, z);
                        if (!solid(mc, c.below()) || !free(mc, c) || !free(mc, c.above()) || !shoreOk(mc, c)) continue;
                        double d = Math.sqrt(dx * dx + dz * dz + (y - feet.getY()) * (y - feet.getY()));
                        if (d < bestD) { bestD = d; best = new int[]{x, y, z}; }
                        break;
                    }
                }
        return best;
    }

    private static boolean free(Minecraft mc, BlockPos q) {
        return mc.level.getBlockState(q).getCollisionShape(mc.level, q).isEmpty() && mc.level.getFluidState(q).isEmpty();
    }

    /** "burrow": dig down here and seal over the head (no blocks needed), else dig into a wall and seal. */
    public String burrow() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null) return "error: no player";
        if (move == Move.BURROW || move == Move.NICHE) return "ok: digging in already (" + doing() + ")";
        if (sealedHere(mc, p)) return "ok: sealed in here already";
        int d = findBurrow(mc, p);
        if (d > 0) {
            begin(Move.BURROW, "digging down " + d + " and sealing");
            engine.hold();
            stopMoving(mc);
            burrowTop = p.blockPosition();
            burrowDepth = d;
            burrowStage = 0;
            burrowWait = 0;
            return "ok: digging down " + d + " and sealing";
        }
        Direction n = findNiche(mc, p);
        if (n != null && throwaway(p)[0] >= CornerEscape.SEAL_BLOCKS) {
            begin(Move.NICHE, "digging into the wall " + n.getName() + " and sealing");
            engine.hold();
            stopMoving(mc);
            digDir = n;
            digEntrance = p.blockPosition();
            digStage = 0;
            return "ok: digging into the wall " + n.getName();
        }
        return "error: no ground to dig down into and no wall to dig into here (soft ground, closed sides, no water near)";
    }

    private void begin(Move m, String line) {
        clear(Minecraft.getInstance());
        move = m;
        start = calmSince = now;
        events.push("reflex", line, null);
        LOG.info("[entropybot] danger move: {}", line);
    }

    /** Ends the move: keys up, Baritone back to the job. */
    public void stop(String why) {
        if (move == Move.NONE) return;
        Minecraft mc = Minecraft.getInstance();
        events.push("reflex", "done " + move.name().toLowerCase() + ": " + why, null);
        LOG.info("[entropybot] danger move {} done: {}", move.name().toLowerCase(), why);
        clear(mc);
        stopMoving(mc);
        engine.release();
    }

    private void clear(Minecraft mc) {
        if (mc != null && (move == Move.PILLAR || move == Move.NICHE)) {
            mc.options.keyJump.setDown(false);
            mc.options.keyUse.setDown(false);
            mc.options.keyUp.setDown(false);
            mc.options.keyShift.setDown(false);
        }
        move = Move.NONE;
        goal = null;
        goalXZ = null;
        digStage = -1;
        burrowStage = -1;
        coverMob = -1;
    }

    // ---- the tick ----

    /** One tick while a move runs (Reflexes calls it before the fight code). */
    void tick(Minecraft mc, LocalPlayer p, long tick) {
        now = tick;
        if (move == Move.NONE) return;
        try {
            Entity t = nearest(mc, p);
            if (t != null) calmSince = now;
            if (now - start >= MAX_TICKS) { stop("gave up after 90 s"); return; }
            switch (move) {
                case FLEE -> {
                    if (goalXZ != null && Math.abs(p.getX() - goalXZ[0]) + Math.abs(p.getZ() - goalXZ[1]) <= 3) { stop("got away"); return; }
                    if (engine.mode() == EngineProcess.Mode.NONE && goal != null) engine.override(goal);    // a cancel dropped it
                    if (ThreatFacts.sprint(p.getDeltaMovement().horizontalDistance() > 0.05, p.getFoodData().getFoodLevel()) && !p.isSprinting()) p.setSprinting(true);
                    if (now - calmSince >= CALM_TICKS) stop("nothing chasing me");
                }
                case COVER -> {
                    Entity m = mc.level.getEntity(coverMob);
                    if (m == null || !m.isAlive()) { stop("the shooter is gone"); return; }
                    if (p.distanceTo(m) <= 4) { stop("it closed in (the hit response fights it)"); return; }
                    if (now >= coverUntil) { stop("held the cover " + (int) io.github.mojolowjo.entropybot.aa.brain.Tuning.get("danger.coverS") + " s"); return; }
                    if (engine.mode() == EngineProcess.Mode.NONE && goal != null) engine.override(goal);
                }
                case PILLAR -> pillarTick(mc, p, t);
                case NICHE -> digTick(mc, p, t);
                case BURROW -> burrowTick(mc, p, t);
                default -> { }
            }
        } catch (RuntimeException e) {
            LOG.warn("[entropybot] danger move {} failed: {}", move, e.toString());
            stop("error: " + e);
        }
    }

    private void holdOn(Minecraft mc, LocalPlayer p, Entity t, String what) {
        hitInReach(mc, p, t);
        if (now - calmSince >= CALM_TICKS) stop("nothing near the " + what);
    }

    private void pillarTick(Minecraft mc, LocalPlayer p, Entity t) {
        if (p.getBlockX() != pillarX || p.getBlockZ() != pillarZ || (p.isInWater() && !pillarWater) || p.isInLava()) { stop("knocked off the pillar"); return; }
        if (pillarUp) { holdOn(mc, p, t, "pillar"); return; }
        long limit = pillarWater ? CornerEscape.TIMEOUT + 40L * (pillarTop - pillarBase) : CornerEscape.TIMEOUT;
        if (now - start > limit) { stop("pillar gave up at " + (pillarCell - pillarBase) + " up" + (pillarWater ? " (out of water, " + (pillarTop - pillarBase) + " to go)" : "")); return; }
        if (pillarWater) { pillarWaterTick(mc, p); return; }
        p.setXRot(90f);
        p.setDeltaMovement(0, p.getDeltaMovement().y, 0);      // straight up: no drift off the column
        BlockPos cell = new BlockPos(pillarX, pillarCell, pillarZ);
        if (p.getY() >= pillarCell + 1.0 - 1e-3 && p.onGround() && !mc.level.getBlockState(cell).isAir()) pillarCell++;   // landed on the last one
        if (pillarCell >= pillarTop) {
            pillarUp = true;
            mc.options.keyJump.setDown(false);
            events.push("reflex", "pillared up to " + pillarTop + ", holding", null);
            return;
        }
        int[] tw = throwaway(p);
        if (tw[0] == 0 || !Hotbar.toHand(mc, p, tw[1])) { stop("pillar: out of blocks"); return; }
        mc.options.keyJump.setDown(p.onGround());
        if (Guard.INSTANCE.placeRefused(mc.level, cell)) { stop("pillar: no placing at " + cell.getX() + " " + cell.getY() + " " + cell.getZ() + " (an area rule)"); return; }
        if (CornerEscape.placeNow(p.getY(), pillarCell) && mc.level.getBlockState(cell).canBeReplaced()) {
            BlockPos below = cell.below();
            mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND, new BlockHitResult(Vec3.atCenterOf(below).add(0, 0.5, 0), Direction.UP, below, false));
            p.swing(InteractionHand.MAIN_HAND);
        }
    }

    /** AA_STEP3 A3: one tick of the pillar out of water: sink to the next cell, swim up over it, place it, until dry on top. */
    private void pillarWaterTick(Minecraft mc, LocalPlayer p) {
        p.setXRot(90f);
        p.setDeltaMovement(0, p.getDeltaMovement().y, 0);
        BlockPos cell = new BlockPos(pillarX, pillarCell, pillarZ);
        if (!mc.level.getBlockState(cell).canBeReplaced() && p.getY() >= pillarCell + 1.0 - 1e-3) pillarCell++;     // placed and over it
        if (pillarCell >= pillarTop) {
            pillarUp = true;
            mc.options.keyJump.setDown(false);
            mc.options.keyShift.setDown(false);
            events.push("reflex", "pillared out of the water to " + pillarTop + ", holding", null);
            return;
        }
        int[] tw = throwaway(p);
        if (tw[0] == 0 || !Hotbar.toHand(mc, p, tw[1])) { stop("pillar: out of blocks in the water"); return; }
        boolean high = p.getY() > pillarCell + 1.6;                 // floating over the next cell: sink to it
        mc.options.keyShift.setDown(high);
        mc.options.keyJump.setDown(!high);
        cell = new BlockPos(pillarX, pillarCell, pillarZ);
        if (Guard.INSTANCE.placeRefused(mc.level, cell)) { stop("pillar: no placing at " + cell.getX() + " " + cell.getY() + " " + cell.getZ() + " (an area rule)"); return; }
        if (CornerEscape.placeNow(p.getY(), pillarCell) && mc.level.getBlockState(cell).canBeReplaced()) {
            BlockPos below = cell.below();
            mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND, new BlockHitResult(Vec3.atCenterOf(below).add(0, 0.5, 0), Direction.UP, below, false));
            p.swing(InteractionHand.MAIN_HAND);
        }
    }

    private void digTick(Minecraft mc, LocalPlayer p, Entity t) {
        BlockPos e = digEntrance, f = e.relative(digDir), h = f.above();
        BlockPos at = p.blockPosition();
        if (digStage < 5 && now - start > CornerEscape.DIG_TIMEOUT) { stop("dig-in gave up at stage " + digStage); return; }
        if ((!at.equals(e) && !at.equals(f)) || p.isInWater() || p.isInLava()) { stop("knocked out of the niche"); return; }
        int prev = digStage;
        digStage = CornerEscape.nextStage(digStage, !solid(mc, f), !solid(mc, h), at.equals(f), solid(mc, e), solid(mc, e.above()));
        if (digStage != prev && digStage == 5) events.push("reflex", "dug in and sealed, holding", null);
        mc.options.keyUp.setDown(digStage == 2);
        switch (digStage) {
            case 0, 1 -> breakAt(mc, p, digStage == 0 ? f : h, digDir.getOpposite());
            case 2 -> look(p, Vec3.atCenterOf(f));
            case 3 -> placeAt(mc, p, e.below(), Direction.UP, e);
            case 4 -> placeAt(mc, p, e, Direction.UP, e.above());
            default -> holdOn(mc, p, t, "niche");
        }
    }

    private void burrowTick(Minecraft mc, LocalPlayer p, Entity t) {
        if (burrowStage <= burrowDepth && now - start > CornerEscape.DIG_TIMEOUT) { stop("burrow gave up"); return; }
        if (p.isInWater() || p.isInLava() || Math.abs(p.getX() - (burrowTop.getX() + 0.5)) > 1 || Math.abs(p.getZ() - (burrowTop.getZ() + 0.5)) > 1) {
            stop("knocked out of the burrow");
            return;
        }
        BlockPos seal = burrowTop.below(burrowDepth - 2);
        BlockPos cell = burrowStage < burrowDepth ? burrowTop.below(burrowStage + 1) : null;
        int prev = burrowStage;
        burrowStage = CornerEscape.burrowStage(burrowStage, burrowDepth, cell != null && !solid(mc, cell),
                cell != null && p.blockPosition().getY() <= cell.getY() && p.onGround(), solid(mc, seal));
        if (burrowStage < burrowDepth) {
            BlockPos c = burrowTop.below(burrowStage + 1);
            if (solid(mc, c)) breakAt(mc, p, c, Direction.UP);
            return;
        }
        if (burrowStage == burrowDepth) {
            for (Direction d : Direction.Plane.HORIZONTAL) {
                BlockPos side = seal.relative(d);
                if (!solid(mc, side)) continue;
                int[] tw = throwaway(p);
                if (tw[0] == 0 || !Hotbar.toHand(mc, p, tw[1])) {
                    if (++burrowWait < 30) return;          // a drop is picked up ~10 ticks after it falls
                    stop("burrow: no dug block came into the bag to seal with");
                    return;
                }
                look(p, Vec3.atCenterOf(seal));
                mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND, new BlockHitResult(Vec3.atCenterOf(side).relative(d.getOpposite(), 0.5), d.getOpposite(), side, false));
                p.swing(InteractionHand.MAIN_HAND);
                return;
            }
            stop("burrow: nothing beside the hole to seal against");
            return;
        }
        if (prev == burrowDepth) {
            calmSince = now;            // the hold starts its own calm wait
            events.push("reflex", "dug down and sealed, holding", null);
        }
        holdOn(mc, p, t, "burrow");
    }

    // ---- the facts AA prices (ThreatFacts) ----

    /**
     * The threat facts now: the foes that count (no creepers: the reflex), the bot, and which ways exist here.
     * {@code base}: the base cell or null. Never throws (an error: calm facts, logged).
     */
    public ThreatFacts facts(Minecraft mc, LocalPlayer p, int[] base, String baseDim, boolean hurt) {
        try {
            List<ThreatFacts.Foe> foes = new ArrayList<>();
            List<FleeHeading.Mob> mobs = new ArrayList<>();
            Entity ranged = null;
            int armor = p.getArmorValue();
            Hostility h = Hostility.INSTANCE;
            for (Entity e : mc.level.entitiesForRendering()) {
                if (e == p || !(e instanceof LivingEntity le) || !le.isAlive() || e instanceof Creeper) continue;
                double d = p.distanceTo(e);
                if (d > ReflexRules.lookRadius(hurt) || !h.kind(e, hurt).counts()) continue;
                boolean seen = hurt || d <= 2.5 || p.hasLineOfSight(e);
                if (!ReflexRules.counts(d, hurt, seen) || !ThreatRuntime.INSTANCE.counts(e, d, h.hitMe(e))) continue;
                ThreatFacts.Foe f = ThreatFacts.foe(BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString(), d, armor,
                        io.github.mojolowjo.entropybot.threat.MobDamage.INSTANCE);
                foes.add(f);
                mobs.add(new FleeHeading.Mob(e.getX(), e.getZ(), 1));
                if (f.ranged() && (ranged == null || d < p.distanceTo(ranged))) ranged = e;
            }
            ThreatFacts.Me me = new ThreatFacts.Me(p.getHealth() + p.getAbsorptionAmount(), p.getMaxHealth(), armor, Hostility.weaponHit(p),
                    p.getFoodData().getFoodLevel());
            // AA_STEP2 1: the ways are filled with no foe near too (the night ways: box, burrow, dig in, home)
            int flee = FleeHeading.choose(p.getX(), p.getZ(), mobs, FLEE_RUN) == null ? 0 : (int) FLEE_RUN;
            double baseDist = -1;
            if (base != null && (baseDim == null || baseDim.equals(Guard.dimOf(mc.level)))) {
                double dx = p.getX() - base[0] - 0.5, dy = p.getY() - base[1], dz = p.getZ() - base[2] - 0.5, d = Math.sqrt(dx * dx + dy * dy + dz * dz);
                if (d > 8 && d <= io.github.mojolowjo.entropybot.move.ServerCmds.maxWalk()) baseDist = d;
            }
            int cover = -1;
            if (ranged != null) {
                var c = ThreatRuntime.INSTANCE.cover(p, ranged);
                if (c != null) cover = c.moves();
            }
            int[] tw = throwaway(p);
            boolean burrow = findBurrow(mc, p) > 0, niche = tw[0] >= CornerEscape.SEAL_BLOCKS && findNiche(mc, p) != null;
            // AA_STEP3 A3/A4: the water facts (the column to fill, the swim up, the nearest shore)
            boolean wetNow = p.isInWater();
            int[] wf = wetNow ? water(mc, p) : new int[]{0, 0, 0, 0};
            int[] shore = wetNow ? shore(mc, p, wf[3] != 0 ? wf[3] : p.blockPosition().getY()) : null;
            double shoreDist = shore == null ? -1 : Math.sqrt(Math.pow(shore[0] + 0.5 - p.getX(), 2) + Math.pow(shore[2] + 0.5 - p.getZ(), 2));
            return new ThreatFacts(me, foes, new ThreatFacts.Ways(flee, baseDist, cover, burrow || niche, tw[0], headRoom(mc, p),
                    p.onGround() && !wetNow, burrow, niche, wetNow, wf[0], wf[1], shore, shoreDist));
        } catch (RuntimeException e) {
            LOG.warn("[entropybot] danger facts failed (calm facts used): {}", e.toString());
            return ThreatFacts.CALM;
        }
    }

    // ---- helpers ----

    private static String kind(Entity e) { return BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getPath(); }

    private List<FleeHeading.Mob> mobs(Minecraft mc, LocalPlayer p) {
        List<FleeHeading.Mob> out = new ArrayList<>();
        for (Entity e : mc.level.entitiesForRendering())
            if (e != p && e instanceof LivingEntity le && le.isAlive() && p.distanceTo(e) <= ReflexRules.lookRadius(true) && Hostility.INSTANCE.kind(e, true).counts())
                out.add(new FleeHeading.Mob(e.getX(), e.getZ(), e instanceof Creeper ? 2 : 1));
        return out;
    }

    /** The nearest counted hostile within LOOK (the calm clock and the hits), or null. */
    private static Entity nearest(Minecraft mc, LocalPlayer p) {
        Entity best = null;
        double bd = ReflexRules.LOOK;
        for (Entity e : mc.level.entitiesForRendering()) {
            if (e == p || !(e instanceof LivingEntity le) || !le.isAlive() || !Hostility.INSTANCE.kind(e, true).counts()) continue;
            double d = p.distanceTo(e);
            if (d < bd) { bd = d; best = e; }
        }
        return best;
    }

    private static Entity nearestRanged(Minecraft mc, LocalPlayer p) {
        Entity best = null;
        double bd = io.github.mojolowjo.entropybot.threat.ThreatFacts.RANGED_RANGE + 4;
        for (Entity e : mc.level.entitiesForRendering()) {
            if (e == p || !(e instanceof LivingEntity le) || !le.isAlive() || !Hostility.INSTANCE.kind(e, true).counts()) continue;
            if (io.github.mojolowjo.entropybot.threat.ThreatRules.moveOf(BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString())
                    != io.github.mojolowjo.entropybot.threat.ThreatRules.Move.RANGED) continue;
            double d = p.distanceTo(e);
            if (d < bd) { bd = d; best = e; }
        }
        return best;
    }

    private static void hitInReach(Minecraft mc, LocalPlayer p, Entity t) {
        if (t == null || p.distanceTo(t) > ReflexRules.REACH || p.getAttackStrengthScale(0f) < 0.9f || !Hostility.mayAttack(t)) return;
        look(p, t.getEyePosition());
        mc.gameMode.attack(p, t);
        p.swing(InteractionHand.MAIN_HAND);
    }

    private static void look(LocalPlayer p, Vec3 at) {
        try { p.lookAt(EntityAnchorArgument.Anchor.EYES, at); } catch (RuntimeException ignored) {}
    }

    static void stopMoving(Minecraft mc) {
        mc.options.keyUp.setDown(false);
        mc.options.keySprint.setDown(false);
        mc.options.keyJump.setDown(false);
        if (mc.player != null && mc.player.isSprinting()) mc.player.setSprinting(false);
    }

    private static int headRoom(Minecraft mc, LocalPlayer p) {
        BlockPos feet = p.blockPosition();
        int head = 0;
        while (head < CornerEscape.UP && mc.level.getBlockState(feet.above(2 + head)).getCollisionShape(mc.level, feet.above(2 + head)).isEmpty()) head++;
        return head;
    }

    /** The blocks the danger moves place, in the bag ({@link io.github.mojolowjo.entropybot.aa.AaEscape#pillarBlock}: throwaway and planks), and the slot of the first. */
    static int[] throwaway(LocalPlayer p) {
        // review 0.39.a4 4: the one count (AaEscape.placeable: planks over the reserve); a plain block's slot first, planks last
        int slot = -1, plankSlot = -1;
        List<java.util.Map.Entry<String, Integer>> items = new ArrayList<>();
        var inv = p.getInventory();
        for (int i = 0; i < 36; i++) {
            ItemStack s = inv.getItem(i);
            if (s.isEmpty() || !(s.getItem() instanceof net.minecraft.world.item.BlockItem)) continue;
            String id = BuiltInRegistries.ITEM.getKey(s.getItem()).getPath();
            if (!io.github.mojolowjo.entropybot.aa.AaEscape.pillarBlock(id)) continue;
            items.add(java.util.Map.entry(id, s.getCount()));
            boolean plank = id.endsWith("_planks");
            if (plank) { if (plankSlot < 0 || (plankSlot >= 9 && i < 9)) plankSlot = i; }
            else if (slot < 0 || (slot >= 9 && i < 9)) slot = i;
        }
        int n = io.github.mojolowjo.entropybot.aa.AaEscape.placeable(items);
        return new int[]{n, n <= 0 ? -1 : slot >= 0 ? slot : plankSlot};
    }

    private static boolean solid(Minecraft mc, BlockPos q) {
        BlockState s = mc.level.getBlockState(q);
        return !s.getCollisionShape(mc.level, q).isEmpty() && s.getFluidState().isEmpty();
    }

    /** The BreakPolicy ask for one cell (DIG_OUT intent). Never throws; false = refused or error. */
    private static boolean policyBreaks(Minecraft mc, BlockPos q) {
        try {
            var rt = io.github.mojolowjo.entropybot.guard.PolicyRuntime.INSTANCE;
            return rt.judge("aa-corner", io.github.mojolowjo.entropybot.guard.BreakPolicy.Action.BREAK,
                    io.github.mojolowjo.entropybot.guard.BreakPolicy.Intent.DIG_OUT, Guard.dimOf(mc.level),
                    q.getX(), q.getY(), q.getZ(), rt.facts(mc.level, q, false), rt.where()).allowed();
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static void breakAt(Minecraft mc, LocalPlayer p, BlockPos c, Direction face) {
        toolFor(mc, p, mc.level.getBlockState(c));
        look(p, Vec3.atCenterOf(c));
        mc.gameMode.continueDestroyBlock(c, face);       // the guard's break check runs inside
        p.swing(InteractionHand.MAIN_HAND);
    }

    /** The hotbar tool that breaks this block fastest goes in hand. */
    private static void toolFor(Minecraft mc, LocalPlayer p, BlockState s) {
        int best = -1;
        float bestSpeed = p.getInventory().getItem(p.getInventory().selected).getDestroySpeed(s);
        for (int i = 0; i < 9; i++) {
            float sp = p.getInventory().getItem(i).getDestroySpeed(s);
            if (sp > bestSpeed) { bestSpeed = sp; best = i; }
        }
        if (best >= 0) Hotbar.toHand(mc, p, best);
    }

    private void placeAt(Minecraft mc, LocalPlayer p, BlockPos against, Direction face, BlockPos lookAtCell) {
        int[] tw = throwaway(p);
        if (tw[0] == 0 || !Hotbar.toHand(mc, p, tw[1])) { stop("dig-in: out of blocks"); return; }
        look(p, Vec3.atCenterOf(lookAtCell));
        mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND, new BlockHitResult(Vec3.atCenterOf(against).add(0, 0.5, 0), face, against, false));
        p.swing(InteractionHand.MAIN_HAND);
    }

    /** A wall direction whose two cells can be dug out and sealed behind; null = none. Never throws. */
    private static Direction findNiche(Minecraft mc, LocalPlayer p) {
        try {
            BlockPos feet = p.blockPosition();
            for (Direction d : Direction.Plane.HORIZONTAL) {
                BlockPos f = feet.relative(d), h = f.above();
                BlockState fs = mc.level.getBlockState(f), hs = mc.level.getBlockState(h);
                boolean breakable = solid(mc, f) && solid(mc, h)
                        && fs.getDestroySpeed(mc.level, f) >= 0 && hs.getDestroySpeed(mc.level, h) >= 0
                        && !fs.hasBlockEntity() && !hs.hasBlockEntity()
                        && !(fs.getBlock() instanceof net.minecraft.world.level.block.FallingBlock)
                        && !(hs.getBlock() instanceof net.minecraft.world.level.block.FallingBlock)
                        && policyBreaks(mc, f) && policyBreaks(mc, h);
                if (!breakable) continue;
                int enclosed = 0;
                for (Direction s : new Direction[]{d, d.getClockWise(), d.getCounterClockWise()}) {
                    BlockPos side = f.relative(s);
                    if (solid(mc, side) && solid(mc, side.above())) enclosed++;
                }
                BlockPos ceil = h.above();
                boolean fluid = !mc.level.getFluidState(ceil).isEmpty() || !mc.level.getFluidState(f.below()).isEmpty();
                if (CornerEscape.nicheOk(breakable, enclosed, solid(mc, ceil), solid(mc, f.below()), fluid)) return d;
            }
        } catch (RuntimeException e) {
            LOG.warn("[entropybot] danger: niche search failed: {}", e.toString());
        }
        return null;
    }

    /** A cell this burrow may dig: solid, breakable soon (soft, or the right tool in the bag), not falling, no block entity, no fluid near, the policy allows. */
    private static boolean burrowDiggable(Minecraft mc, LocalPlayer p, BlockPos q) {
        BlockState s = mc.level.getBlockState(q);
        if (!solid(mc, q) || s.hasBlockEntity() || s.getBlock() instanceof net.minecraft.world.level.block.FallingBlock) return false;
        float hard = s.getDestroySpeed(mc.level, q);
        if (hard < 0) return false;
        boolean tool = false;
        for (int i = 0; i < 9 && !tool; i++) tool = p.getInventory().getItem(i).isCorrectToolForDrops(s) && hard <= 3.5f;
        if (hard > 0.8f && !tool) return false;           // stone by hand: 7.5 s a block, and it drops nothing to seal with
        for (Direction d : Direction.values()) if (!mc.level.getFluidState(q.relative(d)).isEmpty()) return false;
        return policyBreaks(mc, q);
    }

    /** The cells at feet and head of a final depth closed on 4 sides, the floor under it solid. */
    private static boolean burrowClosed(Minecraft mc, BlockPos feet) {
        BlockPos head = feet.above();
        for (Direction d : Direction.Plane.HORIZONTAL) if (!solid(mc, feet.relative(d)) || !solid(mc, head.relative(d))) return false;
        return solid(mc, feet.below());
    }

    /** The burrow depth here (2 or 3), 0 = none. Never throws. */
    private static int findBurrow(Minecraft mc, LocalPlayer p) {
        try {
            if (!p.onGround() || p.isInWater() || p.isInLava()) return 0;
            BlockPos top = p.blockPosition();
            boolean[] dig = new boolean[3];
            for (int k = 0; k < 3; k++) dig[k] = burrowDiggable(mc, p, top.below(k + 1));
            boolean sealSide = false;          // depth 3 seals in the first dug cell; it needs a side to place against
            for (Direction d : Direction.Plane.HORIZONTAL) sealSide |= solid(mc, top.below().relative(d));
            boolean[] sides = {burrowClosed(mc, top.below(2)), sealSide && burrowClosed(mc, top.below(3))};
            boolean feetSide = false;
            for (Direction d : Direction.Plane.HORIZONTAL) feetSide |= solid(mc, top.relative(d));
            return CornerEscape.burrowDepth(dig, sides, feetSide);
        } catch (RuntimeException e) {
            LOG.warn("[entropybot] danger: burrow check failed: {}", e.toString());
            return 0;
        }
    }

    /** Feet and head closed on 4 sides and a solid block over the head. */
    private static boolean sealedHere(Minecraft mc, LocalPlayer p) {
        BlockPos f = p.blockPosition();
        return burrowClosed(mc, f) && solid(mc, f.above(2));
    }
}
