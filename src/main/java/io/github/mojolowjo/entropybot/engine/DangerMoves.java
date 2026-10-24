package io.github.mojolowjo.entropybot.engine;

import io.github.mojolowjo.entropybot.Quiet;

import com.mojang.logging.LogUtils;
import io.github.mojolowjo.entropybot.aa.moves.BaritoneCar;
import io.github.mojolowjo.entropybot.aa.moves.Moves;
import io.github.mojolowjo.entropybot.events.EventRing;
import io.github.mojolowjo.entropybot.guard.Guard;
import io.github.mojolowjo.entropybot.aa.brain.Tuning;
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
 * AA_CLEANUP 4: the danger desire's moves, out of the reflexes. The verbs {@code flee}, {@code pillar} and
 * {@code burrow} (dig down, else into a wall, and seal) start one; AA's danger desire runs them as chain steps (or the
 * owner types them). Nothing here decides: AA prices the ways on {@link #facts}. While a move runs the reflexes hold the
 * job (Reflexes.hold); it ends when nothing has chased the bot for 5 s, after 90 s, or when it cannot go on (always with
 * a "done ...: why" event). Never throws from a tick.
 *
 * <p>Backbone step 2 ("AA is the driver, Baritone is the car"): a thin caller of {@link Moves}. rm-threat: the burrow
 * and the dig-in say only "burrow here, d deep" / "dig into the nearest wall"; whether that is possible here (soft
 * ground, closed sides, a wall) is the fork's BurrowProcess answer ({@code canBurrowHere}, {@code canDigInHere}), and
 * its no (stage, block, reason) goes into the facts AA prices. This class follows the way; every physical action (walk, run away, pillar, dig, seal, look) goes
 * through Moves onto Baritone (goals through {@link EngineProcess}, the fork's BurrowProcess, LookBehavior). Only the
 * strike in the hold stays here ({@code MultiPlayerGameMode.attack}: Baritone has no combat), its look through Moves.
 * A way that fails ends the move as a failure ({@link #takeFailure}); AA's danger job then fails its chain.
 *
 * <p>Loader notes: vanilla client reads plus {@code MultiPlayerGameMode.attack}; Baritone API goals and the fork's
 * {@code IBurrowProcess} through {@link io.github.mojolowjo.entropybot.aa.moves.BaritoneCar}; the guard mixin vetoes
 * breaks as always and the fork's break rules ask the CostAdvisor hook. The pure parts are {@link FleeHeading},
 * {@link ThreatFacts} and {@link Moves}.
 */
public final class DangerMoves {
    private static final Logger LOG = LogUtils.getLogger();

    public enum Move { NONE, FLEE, PILLAR, NICHE, BURROW }

    static double fleeRun() { return io.github.mojolowjo.entropybot.aa.brain.Tuning.get("danger.fleeRun"); }
    static long calmTicks() { return Tuning.l("dangerMoves.calmTicks"); }
    static long maxTicks() { return Tuning.l("dangerMoves.maxTicks"); }

    private final EventRing events;
    private final Moves moves;
    private final BaritoneCar car;
    /** The cell a burrow started in (its dig-time hold check). */
    private BlockPos burrowFrom;
    private Move move = Move.NONE;
    private long now, start, calmSince;
    private boolean holding;
    private BlockPos anchor;              // the cell the way holds in (pillar top, burrow bottom, niche foot)
    private String failure;

    DangerMoves(EventRing events, EngineProcess engine) {
        this.events = events;
        this.car = new BaritoneCar(engine);
        this.moves = new Moves(car);
    }

    /** Backbone step 2: the last way that failed ("burrow: gave up at ..."), once; null none. AaRuntime feeds it to AA. */
    public String takeFailure() {
        String f = failure;
        failure = null;
        return f;
    }

    /** 0.38.b9 (review: a verb started a move with a stale clock and the first tick ended it): the client tick, every tick. */
    void time(long tick) {
        now = tick;
        moves.clock(tick);      // 0.39.s7: the legs' clock too (a burrow 'timed out after 40 s' on its first tick)
    }

    public boolean active() { return move != Move.NONE; }

    public Move move() { return move; }

    public String doing() {
        if (move == Move.NONE) return "none";
        String m = move.name().toLowerCase();
        return holding ? m + " done, holding" : m + ": " + moves.why();
    }

    // ---- the verbs ----

    /** "flee": the least-threat heading over every hostile near, 16 blocks. */
    public String flee() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null) return "error: not in a world";
        List<FleeHeading.Mob> mobs = mobs(mc, p);
        if (mobs.isEmpty()) return "error: nothing to flee from";
        // 0.39.s3 review: FleeHeading gates nothing; its least-threat heading is only the run's heuristic term
        // (PreferredRunAway, Baritone may refuse it); none clear = plain GoalRunAway from the mobs' own cells
        int[] to = FleeHeading.choose(p.getX(), p.getZ(), mobs, fleeRun());
        double[] prefer = to == null ? null : new double[]{to[0] + 0.5 - p.getX(), to[1] + 0.5 - p.getZ()};
        List<int[]> from = mobCells(mc, p);
        begin(Move.FLEE, "running " + (int) fleeRun() + " from " + from.size() + " mob(s)" + (prefer == null ? "" : " leaning on the flee heading"));
        moves.flee((int) fleeRun(), from, prefer);
        return "ok: running " + (int) fleeRun() + " from " + from.size() + " mob(s)";
    }

    /** "pillar": Tuning danger.pillarUp on throwaway blocks, then hold and hit what comes in reach. */
    public String pillar() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null) return "error: not in a world";
        if (p.isInLava()) return "error: in lava";
        // backbone step 2 (owner: water is parked): no pillar in or beside water, out of water included
        if (waterNear(mc, p.blockPosition())) return "error: water in or beside me: no pillar here (water ways parked)";
        int[] tw = throwaway(p);
        if (!p.onGround()) return "error: not standing on ground";
        int head = headRoom(mc, p);
        int up = Math.min((int) Tuning.get("danger.pillarUp"), Math.min(head, tw[0]));
        if (up < (int) Tuning.get("danger.pillarMinUp")) return "error: no pillar here (" + tw[0] + " blocks, head room " + head + ")";
        BlockPos feet = p.blockPosition();
        for (int k = 0; k < up; k++)
            if (Guard.INSTANCE.placeRefused(mc.level, feet.above(k))) return "error: no placing at " + c(feet.above(k)) + " (an area rule): no pillar here";
        begin(Move.PILLAR, "pillaring " + up + " up");
        anchor = feet.above(up);
        moves.pillar(new int[]{feet.getX(), feet.getY(), feet.getZ()}, up);
        return "ok: pillaring " + up + " up";
    }

    private static String c(BlockPos q) {
        return q.getX() + " " + q.getY() + " " + q.getZ();
    }

    /** Backbone step 2: water in the cell, at the head, or beside either (the danger ways are not possible there). */
    static boolean waterNear(Minecraft mc, BlockPos feet) {
        for (BlockPos q : new BlockPos[]{feet, feet.above()}) {
            if (wet(mc, q)) return true;
            for (Direction d : Direction.Plane.HORIZONTAL) if (wet(mc, q.relative(d))) return true;
        }
        return wet(mc, feet.below());
    }

    /** How far down the water facts look for a floor. */
    static int waterFloorScan() { return Tuning.i("dangerMoves.waterFloorScan"); }

    /**
     * {column, headDepth, floorY, surfaceY}: water cells from the floor (the first solid block under the feet, within
     * {@link #WATER_FLOOR_SCAN}) up to the surface (the first cell over the feet without water); column 0 = not in water
     * or no floor in reach. headDepth = water cells over the head cell.
     */
    static int[] water(Minecraft mc, LocalPlayer p) {
        BlockPos feet = p.blockPosition();
        if (!wet(mc, feet)) return new int[]{0, 0, 0, 0};
        int up = feet.getY();
        while (up - feet.getY() < Tuning.i("dangerMoves.wetUpMax") && wet(mc, new BlockPos(feet.getX(), up, feet.getZ()))) up++;
        int floor = feet.getY() - 1;
        while (feet.getY() - floor <= waterFloorScan() && !solid(mc, new BlockPos(feet.getX(), floor, feet.getZ()))) {
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
    static long shoreCacheMs() { return Tuning.l("dangerMoves.shoreCacheMs"); }

    /**
     * Review 0.39.a4 2/3: as {@link #shoreScan}, cached {@link #SHORE_CACHE_MS} per feet cell. A cell counts only where
     * the areas let the box be built and the floor be dug (Guard place veto, the BreakPolicy DIG_OUT ask) and where it
     * touches the water (reachable by swimming); else the next candidate.
     */
    static int[] shore(Minecraft mc, LocalPlayer p, int surfaceY) {
        long ms = System.currentTimeMillis();
        BlockPos feet = p.blockPosition();
        if (shoreMs >= 0 && ms - shoreMs < shoreCacheMs() && feet.equals(shoreAt)) return shoreMemo;
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
        for (int r = 1; r <= ThreatFacts.shoreReach() && best == null; r++)
            for (int dx = -r; dx <= r; dx++)
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                    int x = feet.getX() + dx, z = feet.getZ() + dz;
                    if (!mc.level.hasChunkAt(new BlockPos(x, surfaceY, z))) continue;
                    for (int y = surfaceY - 2; y <= surfaceY + Tuning.i("dangerMoves.surfaceUp"); y++) {
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
        BlockPos top = p.blockPosition();
        // backbone step 2 (owner: water is parked): no burrow or dig-in in or beside water
        if (waterNear(mc, top)) return "error: water in or beside me: no burrow or dig-in here (water ways parked)";
        // rm-threat: "burrow here, d deep" / "dig into the nearest wall"; the fork's BurrowProcess says whether it can
        int d = (int) Tuning.get("danger.burrowDepth");
        String noBurrow = car.burrowVerdict(d);
        if (noBurrow == null) {
            begin(Move.BURROW, "digging down " + d + " and sealing");
            anchor = null;          // the hold cell is where the fork's burrow leaves the bot (set when it arrives)
            burrowFrom = top;
            moves.burrowHere("burrow", d, false);
            return "ok: digging down " + d + " and sealing";
        }
        String noWall = car.digInVerdict();
        if (noWall == null) {
            begin(Move.NICHE, "digging into the nearest wall and sealing");
            anchor = null;          // the hold cell is the niche the fork picked (set when it arrives)
            moves.digInHere();
            return "ok: digging into the nearest wall";
        }
        return "error: no burrow here (" + noBurrow + ") and no wall to dig into (" + noWall + ")";
    }

    private void begin(Move m, String line) {
        if (move != Move.NONE) moves.cancel("replaced by " + m.name().toLowerCase());
        move = m;
        start = calmSince = now;
        holding = false;
        anchor = null;
        events.push("reflex", line, null);
        LOG.info("[entropybot] danger move: {}", line);
    }

    /** Ends the move as done (got away, calm, held): Baritone back to the job. */
    public void stop(String why) {
        end(why, false);
    }

    /**
     * Backbone step 2 (step3-watch: "burrow gave up", then "wait 120" in the open): ends the move as failed; the line is
     * kept for {@link #takeFailure} so AA's danger job fails its chain (and holds that way) instead of going on.
     */
    void fail(String why) {
        end(why, true);
    }

    private void end(String why, boolean failed) {
        if (move == Move.NONE) return;
        String m = move.name().toLowerCase();
        events.push("reflex", (failed ? "failed " : "done ") + m + ": " + why, null);
        LOG.info("[entropybot] danger move {} {}: {}", m, failed ? "failed" : "done", why);
        if (failed) failure = m + ": " + why;
        move = Move.NONE;
        holding = false;
        anchor = null;
        moves.release();
    }

    // ---- the tick ----

    /** One tick while a move runs (Reflexes calls it before the fight code). */
    void tick(Minecraft mc, LocalPlayer p, long tick) {
        now = tick;
        if (move == Move.NONE) return;
        try {
            Entity t = nearest(mc, p);
            if (t != null) calmSince = now;
            if (now - start >= maxTicks()) { fail("gave up at " + move.name().toLowerCase() + " after " + maxTicks() / 20 + " s at " + c(p.blockPosition()) + " (" + moves.why() + ")" + verdict(mc)); return; }  // game fact: 20 game ticks per second
            Moves.Phase ph = moves.tick(now);
            if (ph == Moves.Phase.FAILED) { fail(moves.why() + verdict(mc)); return; }
            boolean arrived = ph == Moves.Phase.ARRIVED;
            switch (move) {
                case FLEE -> {
                    if (arrived) { stop("got away (" + moves.why() + ")"); return; }
                    if (now - calmSince >= calmTicks()) stop("nothing chasing me");
                }
                case PILLAR, NICHE, BURROW -> {
                    // 0.39.s3 review: the burrow's hold-cell check runs while it digs too (from the cell it started in;
                    // the fork digs straight down there)
                    if (!arrived && move == Move.BURROW && burrowFrom != null && (p.isInWater() || p.isInLava()
                            || Math.abs(p.getX() - (burrowFrom.getX() + 0.5)) > 1 || Math.abs(p.getZ() - (burrowFrom.getZ() + 0.5)) > 1)) {
                        fail("knocked out of the burrow while digging (started at " + c(burrowFrom) + ", now " + c(p.blockPosition()) + ")");
                        return;
                    }
                    if (!arrived) return;
                    String what = move == Move.PILLAR ? "pillar" : move == Move.NICHE ? "niche" : "burrow";
                    if (!holding) {
                        holding = true;
                        if (anchor == null) anchor = p.blockPosition();     // the fork's burrow left the bot here
                        calmSince = now;            // the hold starts its own calm wait
                        events.push("reflex", (move == Move.PILLAR ? "pillared up" : move == Move.NICHE ? "dug in and sealed" : "dug down and sealed") + ", holding", null);
                    }
                    if (anchor != null && (p.isInWater() || p.isInLava() || Math.abs(p.getX() - (anchor.getX() + 0.5)) > 1 || Math.abs(p.getZ() - (anchor.getZ() + 0.5)) > 1)) {
                        fail("knocked out of the " + what);
                        return;
                    }
                    holdOn(mc, p, t, what);
                }
                default -> { }
            }
        } catch (RuntimeException e) {
            LOG.warn("[entropybot] danger move {} failed: {}", move, e.toString());
            fail("error: " + e);
        }
    }

    /** The break policy's verdict on the first dig cell still standing (the "gave up" line names it). */
    private String verdict(Minecraft mc) {
        try {
            List<BlockPos> cells = car.burrowCells();      // a burrow's and a dig-in's cells are the fork's
            for (BlockPos q : cells) {
                if (!solid(mc, q)) continue;
                var rt = io.github.mojolowjo.entropybot.guard.PolicyRuntime.INSTANCE;
                var v = rt.judge("aa-corner", io.github.mojolowjo.entropybot.guard.BreakPolicy.Action.BREAK,
                        io.github.mojolowjo.entropybot.guard.BreakPolicy.Intent.DIG_OUT, Guard.dimOf(mc.level),
                        q.getX(), q.getY(), q.getZ(), rt.facts(mc.level, q, false), rt.where());
                String block = BuiltInRegistries.BLOCK.getKey(mc.level.getBlockState(q).getBlock()).getPath();
                return "; guard on " + block + " at " + c(q) + ": " + (v.allowed() ? "allowed x" + v.multiplier() + " (" + v.rule() + ")" : "refused: " + v.reason());
            }
            return cells.isEmpty() ? "" : "; every dig cell is open";
        } catch (RuntimeException e) {
            return "; guard verdict unknown (" + e + ")";
        }
    }

    private void holdOn(Minecraft mc, LocalPlayer p, Entity t, String what) {
        hitInReach(mc, p, t);
        if (now - calmSince >= calmTicks()) stop("nothing near the " + what);
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
            int armor = p.getArmorValue();
            Hostility h = Hostility.INSTANCE;
            for (Entity e : mc.level.entitiesForRendering()) {
                if (e == p || !(e instanceof LivingEntity le) || !le.isAlive() || e instanceof Creeper) continue;
                double d = p.distanceTo(e);
                if (d > ReflexRules.lookRadius(hurt) || !h.kind(e, hurt).counts()) continue;
                boolean seen = hurt || d <= Tuning.get("dangerMoves.seenDist") || p.hasLineOfSight(e);
                if (!ReflexRules.counts(d, hurt, seen) || !ThreatRuntime.INSTANCE.counts(e, d, h.hitMe(e))) continue;
                ThreatFacts.Foe f = ThreatFacts.foe(BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString(), d, armor,
                        io.github.mojolowjo.entropybot.threat.MobDamage.INSTANCE);
                foes.add(f);
                mobs.add(new FleeHeading.Mob(e.getX(), e.getZ(), 1));
            }
            ThreatFacts.Me me = new ThreatFacts.Me(p.getHealth() + p.getAbsorptionAmount(), p.getMaxHealth(), armor, Hostility.weaponHit(p),
                    p.getFoodData().getFoodLevel());
            // AA_STEP2 1: the ways are filled with no foe near too (the night ways: box, burrow, dig in, home)
            int flee = FleeHeading.choose(p.getX(), p.getZ(), mobs, fleeRun()) == null ? 0 : (int) fleeRun();
            double baseDist = -1;
            if (base != null && (baseDim == null || baseDim.equals(Guard.dimOf(mc.level)))) {
                double dx = p.getX() - base[0] - 0.5, dy = p.getY() - base[1], dz = p.getZ() - base[2] - 0.5, d = Math.sqrt(dx * dx + dy * dy + dz * dz);
                if (d > Tuning.i("dangerMoves.baseFar") && d <= io.github.mojolowjo.entropybot.move.ServerCmds.maxWalk()) baseDist = d;
            }
            int[] tw = throwaway(p);
            // AA_STEP3 A3/A4: the water facts (the column to fill, the swim up, the nearest shore)
            boolean wetNow = p.isInWater();
            boolean wn = waterNear(mc, p.blockPosition());
            // rm-threat: the fork answers "burrow here" / "dig in here" (its no names the stage, the block, the reason);
            // in or beside water neither is asked (the ways are parked there, DangerOptions says why)
            String noBurrow = wetNow || wn ? "water in or beside me" : car.burrowVerdict((int) Tuning.get("danger.burrowDepth"));
            String noNiche = wetNow || wn ? "water in or beside me" : car.digInVerdict();
            boolean burrow = noBurrow == null, niche = noNiche == null;
            int[] wf = wetNow ? water(mc, p) : new int[]{0, 0, 0, 0};
            int[] shore = wetNow ? shore(mc, p, wf[3] != 0 ? wf[3] : p.blockPosition().getY()) : null;
            double shoreDist = shore == null ? -1 : Math.sqrt(Math.pow(shore[0] + 0.5 - p.getX(), 2) + Math.pow(shore[2] + 0.5 - p.getZ(), 2));
            // 0.39.z42 (rule 18): the swim to the shore is priced by Baritone's complete path (the chain's background search);
            // the straight distance above only names it in aa why
            double shoreS = Double.NaN;
            String shoreNo = null;
            if (shore != null) {
                io.github.mojolowjo.entropybot.aa.PathPrice.Answer a = io.github.mojolowjo.entropybot.commands.LivePathPrice.INSTANCE.price(shore[0], shore[1], shore[2], shoreDist);
                if (a.kind() == io.github.mojolowjo.entropybot.aa.PathPrice.Kind.PRICED && a.walk() != null) shoreS = a.walk().seconds();
                else shoreNo = io.github.mojolowjo.entropybot.aa.PathPrice.NOT_KNOWN + " (" + a.kind().name().toLowerCase(java.util.Locale.ROOT) + (a.reason() == null ? "" : ": " + a.reason()) + ")";
            }
            return new ThreatFacts(me, foes, new ThreatFacts.Ways(flee, baseDist, burrow || niche, tw[0], headRoom(mc, p),
                    p.onGround() && !wetNow, burrow, niche, wetNow, wf[0], wf[1], shore, shoreDist, wn, noBurrow, noNiche, shoreS, shoreNo));
        } catch (RuntimeException e) {
            LOG.warn("[entropybot] danger facts failed (calm facts used): {}", e.toString());
            return ThreatFacts.CALM;
        }
    }

    // ---- helpers ----

    /** The cells of the hostiles {@link #mobs} counts (what the flee runs from). */
    private List<int[]> mobCells(Minecraft mc, LocalPlayer p) {
        List<int[]> out = new ArrayList<>();
        for (Entity e : mc.level.entitiesForRendering())
            if (e != p && e instanceof LivingEntity le && le.isAlive() && p.distanceTo(e) <= ReflexRules.lookRadius(true) && Hostility.INSTANCE.kind(e, true).counts())
                out.add(new int[]{e.getBlockX(), e.getBlockY(), e.getBlockZ()});
        return out;
    }

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
        double bd = ReflexRules.look();
        for (Entity e : mc.level.entitiesForRendering()) {
            if (e == p || !(e instanceof LivingEntity le) || !le.isAlive() || !Hostility.INSTANCE.kind(e, true).counts()) continue;
            double d = p.distanceTo(e);
            if (d < bd) { bd = d; best = e; }
        }
        return best;
    }

    /** The strike in the hold (Baritone has no combat): the look through Moves (LookBehavior), the hit by the game mode. */
    private void hitInReach(Minecraft mc, LocalPlayer p, Entity t) {
        if (t == null || p.distanceTo(t) > ReflexRules.reach() || !Hostility.mayAttack(t)) return;
        Vec3 eye = t.getEyePosition();
        moves.lookAt(eye.x, eye.y, eye.z);
        if (p.getAttackStrengthScale(0f) < 0.9f) return;  // game fact: a swing at 90% attack strength or more is a full hit
        mc.gameMode.attack(p, t);
        p.swing(InteractionHand.MAIN_HAND);
    }

    private static int headRoom(Minecraft mc, LocalPlayer p) {
        BlockPos feet = p.blockPosition();
        int head = 0;
        int up = (int) Tuning.get("danger.pillarUp");
        while (head < up &&mc.level.getBlockState(feet.above(2 + head)).getCollisionShape(mc.level, feet.above(2 + head)).isEmpty()) head++;
        return head;
    }

    /** The blocks the danger moves place, in the bag ({@link io.github.mojolowjo.entropybot.aa.AaEscape#pillarBlock}: throwaway and planks), and the slot of the first. */
    static int[] throwaway(LocalPlayer p) {
        // review 0.39.a4 4: the one count (AaEscape.placeable: planks over the reserve); a plain block's slot first, planks last
        int slot = -1, plankSlot = -1;
        List<java.util.Map.Entry<String, Integer>> items = new ArrayList<>();
        var inv = p.getInventory();
        for (int i = 0; i < 36; i++) {  // game fact: the player inventory has 36 slots (hotbar is slots 0-8)
            ItemStack s = inv.getItem(i);
            if (s.isEmpty() || !(s.getItem() instanceof net.minecraft.world.item.BlockItem)) continue;
            String id = BuiltInRegistries.ITEM.getKey(s.getItem()).getPath();
            if (!io.github.mojolowjo.entropybot.aa.AaEscape.pillarBlock(id)) continue;
            items.add(java.util.Map.entry(id, s.getCount()));
            boolean plank = id.endsWith("_planks");
            if (plank) { if (plankSlot < 0 || (plankSlot >= 9 && i < 9)) plankSlot = i; }  // game fact: the hotbar is inventory slots 0-8
            else if (slot < 0 || (slot >= 9 && i < 9)) slot = i;  // game fact: the hotbar is inventory slots 0-8
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
            Quiet.warn("DangerMoves.policyBreaks failed, carrying on with the fallback", e);
            return false;
        }
    }

    /** The cells at feet and head closed on 4 sides, the floor under it solid (the "sealed in here already" fact). */
    private static boolean burrowClosed(Minecraft mc, BlockPos feet) {
        BlockPos head = feet.above();
        for (Direction d : Direction.Plane.HORIZONTAL) if (!solid(mc, feet.relative(d)) || !solid(mc, head.relative(d))) return false;
        return solid(mc, feet.below());
    }

    /** Feet and head closed on 4 sides and a solid block over the head. */
    private static boolean sealedHere(Minecraft mc, LocalPlayer p) {
        BlockPos f = p.blockPosition();
        return burrowClosed(mc, f) && solid(mc, f.above(2));
    }
}
