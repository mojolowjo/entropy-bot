package io.github.mojolowjo.entropybot.move;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.Settings;
import baritone.api.pathing.calc.IPath;
import baritone.api.pathing.goals.GoalNear;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.PathCalculationResult;
import com.mojang.logging.LogUtils;
import io.github.mojolowjo.entropybot.baritone.LongRouteProcess;
import io.github.mojolowjo.entropybot.route.Cell;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import org.slf4j.Logger;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * 0.23.3 movement package, the game side: the instant start (raw walk + sprint inputs the tick a walk is asked for,
 * released once Baritone executes its first path: {@code IPathingBehavior.getCurrent() != null}), the long-route
 * process's keep-moving input after a failed search ({@link NoDeadStop}), the settings profiles ({@link Profiles}),
 * the ready paths ({@link ReadyPaths}: planned on a worker thread with Baritone's own A*, see {@link #planReady}),
 * the edge penalties' file, and the counters for {@code path status}.
 *
 * <p>What the Baritone API allowed (1.11.3): a custom process (yes, {@code IBaritoneProcess}); planning without
 * executing is not in the API ({@code IPathingBehavior} has no "plan only"), so a ready leg is planned by constructing
 * Baritone's internal {@code baritone.pathing.calc.AStarPathFinder} with a {@code CalculationContext(baritone, true)}
 * (the "safe for threaded use" copy its own planning thread uses) and {@code calculate(primary, failure)} on our thread.
 * A finished path cannot be handed to Baritone to execute either ({@code PathingBehavior.current} is private): a ready
 * leg gives the instant start its direction along a real path (its first cells) and tells a walk the leg is open.
 * Loader API: Minecraft's options keys ({@code KeyMapping.setDown}), {@code LocalPlayer}; no NeoForge API.
 */
public final class MovePackage {
    private static final Logger LOG = LogUtils.getLogger();
    public static final MovePackage INSTANCE = new MovePackage();

    /** The instant start lets go after this many ticks even if Baritone has no path yet. */
    static final int INSTANT_MAX_TICKS = 40;

    private boolean assist = true;
    private final Profiles profiles = new Profiles();
    public final ReadyPaths ready = new ReadyPaths();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "entropybot-ready-paths");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });
    private Future<?> readyJob;

    // the instant start
    private double[] rawTo;
    private long rawUntil;
    private boolean rawDown;
    // command-to-first-move
    private long moveAskedTick = -1;
    private double moveFromX, moveFromZ;
    private long lastWalkEndTick = -1;
    private Path penaltiesFile;
    private long penaltiesSavedAt;
    private int errors;

    private MovePackage() {}

    public MoveStats stats() { return LongRouteProcess.INSTANCE.stats; }

    public boolean assist() { return assist; }

    public void setAssist(boolean on) { assist = on; }

    public Profiles.Profile profile() { return profiles.active(); }

    /** A long walk: path assist on and the goal far (LegPlan.needsAssist). */
    public boolean wantsLong(int[] me, int[] dest) {
        return assist && LongRouteProcess.INSTANCE.registered() && LegPlan.needsAssist(me, dest);
    }

    /**
     * Any walk the mod starts: the instant start (raw walk + sprint toward the ready leg's first cells, the route
     * map's first waypoint, or straight at the goal) and the first-move clock.
     */
    public void walkStarted(LocalPlayer p, int[] dest, long tick) {
        try {
            stats().walks++;
            int[] me = {(int) Math.floor(p.getX()), (int) Math.floor(p.getY()), (int) Math.floor(p.getZ())};
            Cell goal = new Cell(dest[0], dest[1], dest[2]);
            ready.rememberGoal(goal);
            double[] to = null;
            ReadyPaths.Entry e = ready.lookup(new Cell(me[0], me[1], me[2]), goal, System.currentTimeMillis());
            if (e != null) {
                stats().readyHits++;
                Cell c = e.cells().get(Math.min(6, e.cells().size() - 1));
                to = new double[]{c.x() + 0.5, c.z() + 0.5};
            }
            if (to == null && LongRouteProcess.INSTANCE.active()) {
                Cell t = LongRouteProcess.INSTANCE.currentTarget();
                if (t != null) to = new double[]{t.x() + 0.5, t.z() + 0.5};
            }
            if (to == null) to = new double[]{dest[0] + 0.5, dest[2] + 0.5};
            moveAskedTick = tick;
            moveFromX = p.getX();
            moveFromZ = p.getZ();
            if (!assist) return;                  // path assist off: plain Baritone, no instant start (the baseline)
            rawTo = to;
            rawUntil = tick + INSTANT_MAX_TICKS;
            applyRaw(p, rawTo);         // this very tick
        } catch (Throwable t) {
            error("walk start", t);
        }
    }

    /** 0.23.4: the first-move clock runs this long (a sealed start digs out first: the move after it counts, standing included). */
    static final long FIRST_MOVE_WINDOW = 1200;

    /** 0.23.4: a walk's dig-out began (sealed in): its start time goes to path status; the first-move clock keeps running. */
    public void digOutStarted(long tick) {
        try {
            if (moveAskedTick >= 0) {
                stats().digOutStart(tick - moveAskedTick);
                LOG.info("[entropybot] path: dig-out began {} ticks after the command", tick - moveAskedTick);
            }
        } catch (Throwable t) {
            error("dig-out start", t);
        }
    }

    /** A walk job ended (for the chain-gap clock). */
    public void walkEnded(long tick) {
        lastWalkEndTick = tick;
        release();
    }

    /** Once a tick in a world (after the jobs). walking: a walk job runs; breaking: Baritone may break; fleeing: a flight. */
    public void tick(LocalPlayer p, long tick, boolean walking, boolean breaking, boolean fleeing, boolean idle,
                     Map<String, Cell> targets, Cell chainNext) {
        try {
            IBaritone b = BaritoneAPI.getProvider().getPrimaryBaritone();
            // first move
            if (moveAskedTick >= 0) {
                double dx = p.getX() - moveFromX, dz = p.getZ() - moveFromZ;
                if (dx * dx + dz * dz > 0.09) {
                    long t = tick - moveAskedTick;
                    stats().firstMove(t);
                    if (lastWalkEndTick >= 0 && moveAskedTick - lastWalkEndTick <= 100) {
                        stats().gap(tick - lastWalkEndTick);
                        LOG.info("[entropybot] path: chain gap {} ticks (walk end to next move)", tick - lastWalkEndTick);
                    }
                    lastWalkEndTick = -1;
                    LOG.info("[entropybot] path: first move after {} ticks", t);
                    moveAskedTick = -1;
                } else if (tick - moveAskedTick > FIRST_MOVE_WINDOW) {
                    stats().noMove++;                 // 0.23.4: counted, not silently dropped
                    moveAskedTick = -1;
                }
            }
            // the instant start, then the no-dead-stop keep-moving input
            boolean executing = b != null && b.getPathingBehavior().getCurrent() != null;
            double[] keep = LongRouteProcess.INSTANCE.keepTo(Minecraft.getInstance().level.getGameTime());
            if (rawTo != null && (executing || tick > rawUntil || !walking)) rawTo = null;
            double[] to = rawTo != null ? rawTo : (keep != null && !executing && walking ? keep : null);
            if (to != null && safeAhead(p, to)) applyRaw(p, to);
            else release();
            // profiles
            Profiles.Profile want = Profiles.choose(walking, breaking, fleeing);
            if (profiles.apply(want, SETTINGS)) LOG.info("[entropybot] path: profile {}", want.name().toLowerCase());
            // ready paths: when idle, or for a chain's next walk while the current step finishes
            ready.setPriority(chainNext);
            if ((idle || chainNext != null) && b != null && tick % 10 == 3) {
                ready.setTargets(targets);
                if (readyJob == null || readyJob.isDone()) {
                    int[] me = {(int) Math.floor(p.getX()), (int) Math.floor(p.getY()), (int) Math.floor(p.getZ())};
                    ReadyPaths.Entry e = ready.due(new Cell(me[0], me[1], me[2]), System.currentTimeMillis());
                    if (e != null) planReady(b, e, me);
                }
            }
            if (EdgePenalties.GLOBAL.dirty() && System.currentTimeMillis() - penaltiesSavedAt > 30_000) savePenalties();
        } catch (Throwable t) {
            error("tick", t);
        }
    }

    /** A block changed (Baritone's onBlockChange): ready legs near it and the long walk's leg are planned again. */
    public void blockChanged(int x, int y, int z) {
        try {
            ready.blockChanged(x, y, z);
            LongRouteProcess.INSTANCE.blockChanged(x, y, z);
        } catch (Throwable t) {
            error("block change", t);
        }
    }

    /**
     * Plans e's first leg (to the target, or 36 blocks toward it) on the worker thread with Baritone's own A*
     * ({@code AStarPathFinder}, internal), the context made here on the game thread.
     */
    private void planReady(IBaritone b, ReadyPaths.Entry e, int[] me) {
        Cell t = e.target;
        double dx = t.x() - me[0], dz = t.z() - me[2], flat = Math.sqrt(dx * dx + dz * dz);
        double f = flat > LegPlan.MAX_LEG ? LegPlan.MAX_LEG / flat : 1;
        int gx = (int) Math.round(me[0] + dx * f), gz = (int) Math.round(me[2] + dz * f);
        baritone.api.pathing.goals.Goal g = f < 1 ? new baritone.api.pathing.goals.GoalXZ(gx, gz) : new GoalNear(new BlockPos(t.x(), t.y(), t.z()), 2);
        baritone.pathing.movement.CalculationContext ctx = new baritone.pathing.movement.CalculationContext(b, true);
        BetterBlockPos start = new BetterBlockPos(me[0], me[1], me[2]);
        Cell from = new Cell(me[0], me[1], me[2]);
        stats().readyPlanned++;
        if ("next".equals(e.name)) stats().chainPrePlans++;
        readyJob = worker.submit(() -> {
            try {
                baritone.pathing.calc.AStarPathFinder pf = new baritone.pathing.calc.AStarPathFinder(start, me[0], me[1], me[2], g,
                        new baritone.utils.pathing.Favoring(null, ctx), ctx);
                PathCalculationResult r = pf.calculate(500, 2000);
                List<Cell> cells = new ArrayList<>();
                IPath path = r.getPath().orElse(null);
                if (path != null) for (BetterBlockPos pos : path.positions()) cells.add(new Cell(pos.x, pos.y, pos.z));
                boolean failed = path == null || r.getType() == PathCalculationResult.Type.FAILURE || r.getType() == PathCalculationResult.Type.EXCEPTION;
                Minecraft.getInstance().execute(() -> {
                    ready.store(e, from, cells, failed, System.currentTimeMillis());
                    if (failed) stats().readyFailed++;
                });
            } catch (Throwable x) {
                Minecraft.getInstance().execute(() -> {
                    ready.store(e, from, List.of(), true, System.currentTimeMillis());
                    stats().readyFailed++;
                });
                error("ready plan", x);
            }
        });
    }

    // ---- raw input ----

    private void applyRaw(LocalPlayer p, double[] to) {
        Minecraft mc = Minecraft.getInstance();
        double dx = to[0] - p.getX(), dz = to[1] - p.getZ();
        if (dx * dx + dz * dz < 1) {
            release();
            return;
        }
        // 0.24.0: the run guard (drop over 3, lava, water, fire ahead) turns or stops the raw run
        io.github.mojolowjo.entropybot.threat.RunGuard.Verdict g =
                io.github.mojolowjo.entropybot.threat.ThreatRuntime.INSTANCE.runGuard(p, dx, dz);
        if (!g.go()) {
            String said = g.act() + " " + g.why();
            if (!said.equals(lastGuard)) LOG.info("[entropybot] path: run guard {} on the instant start", said);
            lastGuard = said;
            if (g.stop()) {
                release();
                return;
            }
            dx = g.dirX();
            dz = g.dirZ();
        } else lastGuard = null;
        p.setYRot((float) (Math.toDegrees(Math.atan2(-dx, dz))));
        mc.options.keyUp.setDown(true);
        boolean sprint = p.getFoodData().getFoodLevel() > 6;
        mc.options.keySprint.setDown(sprint);
        if (sprint && !p.isSprinting()) p.setSprinting(true);
        rawDown = true;
    }

    private String lastGuard;

    private void release() {
        lastGuard = null;
        if (!rawDown) return;
        Minecraft mc = Minecraft.getInstance();
        mc.options.keyUp.setDown(false);
        mc.options.keySprint.setDown(false);
        rawDown = false;
    }

    /** No raw step into a hole, fluid or a wall: the cell one ahead has a floor within 2 below and no fluid. */
    static boolean safeAhead(LocalPlayer p, double[] to) {
        Minecraft mc = Minecraft.getInstance();
        double dx = to[0] - p.getX(), dz = to[1] - p.getZ(), len = Math.sqrt(dx * dx + dz * dz);
        if (len < 1e-3) return false;
        BlockPos ahead = BlockPos.containing(p.getX() + dx / len * 1.2, p.getY(), p.getZ() + dz / len * 1.2);
        var lvl = mc.level;
        if (!lvl.getFluidState(ahead).isEmpty() || !lvl.getFluidState(ahead.below()).isEmpty()) return false;
        if (!lvl.getBlockState(ahead.above()).getCollisionShape(lvl, ahead.above()).isEmpty()) return false;
        for (int d = 1; d <= 2; d++) {
            BlockPos f = ahead.below(d);
            if (!lvl.getFluidState(f).isEmpty()) return false;
            if (!lvl.getBlockState(f).getCollisionShape(lvl, f).isEmpty()) return true;
        }
        return !lvl.getBlockState(ahead).getCollisionShape(lvl, ahead).isEmpty();      // a step up
    }

    // ---- settings ----

    @SuppressWarnings({"unchecked", "rawtypes"})
    static final Profiles.Access SETTINGS = new Profiles.Access() {
        @Override
        public Object get(String name) {
            Settings.Setting<?> s = BaritoneAPI.getSettings().byLowerName.get(name);
            return s == null ? null : s.value;
        }

        @Override
        public void set(String name, Object value) {
            Settings.Setting s = BaritoneAPI.getSettings().byLowerName.get(name);
            if (s != null && value != null && s.value != null && s.value.getClass() == value.getClass()) s.value = value;
        }
    };

    // ---- penalties file ----

    public void loadPenalties(Path root) {
        try {
            penaltiesFile = root.resolve("route").resolve("penalties.json");
            if (Files.exists(penaltiesFile)) EdgePenalties.GLOBAL.loadJson(Files.readString(penaltiesFile, StandardCharsets.UTF_8));
            LOG.info("[entropybot] path: {} penalised route edges", EdgePenalties.GLOBAL.count(System.currentTimeMillis()));
        } catch (Throwable t) {
            error("load penalties", t);
        }
    }

    public void savePenalties() {
        penaltiesSavedAt = System.currentTimeMillis();
        if (penaltiesFile == null) return;
        try {
            Files.createDirectories(penaltiesFile.getParent());
            Path tmp = penaltiesFile.resolveSibling("penalties.json.tmp");
            Files.writeString(tmp, EdgePenalties.GLOBAL.toJson(System.currentTimeMillis()), StandardCharsets.UTF_8);
            Files.move(tmp, penaltiesFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (Throwable t) {
            error("save penalties", t);
        }
    }

    // ---- status ----

    public String status(LocalPlayer p) {
        MoveStats s = stats();
        long now = System.currentTimeMillis();
        Cell me = p == null ? new Cell(0, 0, 0) : new Cell((int) Math.floor(p.getX()), (int) Math.floor(p.getY()), (int) Math.floor(p.getZ()));
        return "path assist " + (assist ? "on" : "off") + ", profile " + profiles.active().name().toLowerCase()
                + "; " + s.firstMoveText() + "; " + s.gapText()
                + "; walks " + s.walks + " (long " + s.longWalks + "), legs " + s.legs + " (ran long " + s.legsLong + "), route plans " + s.replans
                + ", resumed " + s.resumes + " (re-planned after a pause " + s.replansAfterPause + "), search fails " + s.fails
                + " (kept moving " + s.keepMoving + ", dig-outs " + s.digOuts + ", reports " + s.reports + ")"
                + "; penalised edges " + EdgePenalties.GLOBAL.count(now) + " (+" + s.penalised + " this session)"
                + "; ready paths " + ready.ready(me, now) + "/" + ready.size() + " (planned " + s.readyPlanned + ", failed " + s.readyFailed
                + ", used " + s.readyHits + ", chain pre-plans " + s.chainPrePlans + ")"
                + "; " + LongRouteProcess.INSTANCE.statusLine();
    }

    private void error(String where, Throwable t) {
        if (errors++ < 10 || errors % 1000 == 0) LOG.warn("[entropybot] path ({}): {}", where, t.toString());
    }
}
