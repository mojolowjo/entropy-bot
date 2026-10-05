package io.github.mojolowjo.entropybot.routing;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.Settings;
import baritone.api.cache.ICachedWorld;
import baritone.api.cache.IWorldData;
import baritone.api.event.events.BlockChangeEvent;
import baritone.api.event.listener.AbstractGameEventListener;
import baritone.api.utils.Pair;
import baritone.pathing.movement.MovementHelper;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import io.github.mojolowjo.entropybot.Core;
import io.github.mojolowjo.entropybot.baritone.SafetyNet;
import io.github.mojolowjo.entropybot.guard.Box;
import io.github.mojolowjo.entropybot.guard.Guard;
import io.github.mojolowjo.entropybot.guard.MixinFlags;
import io.github.mojolowjo.entropybot.recorder.FlightRecorder;
import io.github.mojolowjo.entropybot.route.RoutePlannerHolder;
import io.github.mojolowjo.entropybot.route.Cell;
import io.github.mojolowjo.entropybot.route.RouteCounters;
import io.github.mojolowjo.entropybot.route.RouteFileHeader;
import io.github.mojolowjo.entropybot.route.RouteHashes;
import io.github.mojolowjo.entropybot.route.RouteLog;
import io.github.mojolowjo.entropybot.route.RoutePlan;
import io.github.mojolowjo.entropybot.route.RoutePlanner;
import io.github.mojolowjo.entropybot.route.RouteRequest;
import io.github.mojolowjo.entropybot.route.RouteStats;
import io.github.mojolowjo.entropybot.route.SectionKey;
import io.github.mojolowjo.entropybot.route.SectionRecord;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.ChunkEvent;
import org.slf4j.Logger;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The route map in the running game (routing stage 1, package R2): the {@link RoutePlanner} R3's walks use, the
 * builder's game-thread side, the staleness hooks and the map files. A facade over one {@link RouteEngine} per world;
 * all decisions sit in the plain-Java classes of this package.
 *
 * <p>Lifecycle: {@link #tick} once a client tick while in a world (Core); it starts the engine in the overworld once
 * Baritone is there (loading the tiles on the io thread first), and stops it (saving) when the world or dimension
 * changes; {@link #leftWorld} on leaving. Never throws to the caller.
 *
 * <p>Error catching (docs/PLANNING.md section 3): {@link #statusLine} and {@link #stats} carry the counters (review R10);
 * worker and planner exceptions are counted and logged through {@link RouteLog} (5 in full, then one line a minute,
 * {@code [entropybot]} prefix); {@link #available} says false with {@link #unavailableReason} when anything is missing.
 */
public final class RouteRuntime implements RoutePlanner {
    private static final Logger LOG = LogUtils.getLogger();
    public static final RouteRuntime INSTANCE = new RouteRuntime();
    public static final int OVERWORLD = 0;
    public static final String OVERWORLD_ID = "minecraft:overworld";
    static final long SAVE_EVERY_MS = 60_000;
    static final long LAG_MS = 150;

    /**
     * The Baritone settings that change move costs or which moves exist (RouteHashes.settings). Names Baritone doesn't
     * have are hashed as "absent". allowBreak/allowPlace are not here: building is skipped while they are on (R5).
     */
    static final String[] COST_SETTINGS = {
            "allowSprint", "allowParkour", "allowParkourPlace", "allowParkourAscend", "allowDiagonalDescend",
            "allowDiagonalAscend", "allowDownward", "allowJumpAt256", "allowJumpAtBuildLimit", "allowVines",
            "allowWalkOnBottomSlab", "allowWalkOnMagmaBlocks", "assumeWalkOnWater", "assumeWalkOnLava", "assumeStep",
            "assumeSafeWalk", "walkOnWaterOnePenalty", "jumpPenalty", "maxFallHeightNoWater", "maxFallHeightBucket",
            "allowWaterBucketFall", "sprintInWater", "considerPotionEffects",
            "avoidUpdatingFallingBlocks", "blocksToAvoid", "pathThroughCachedOnly", "allowOvershootDiagonalDescend"};

    private final RouteLog log = RouteLog.of(s -> {
        if (s.startsWith("route error")) LOG.warn("[entropybot] {}", s);
        else LOG.info("[entropybot] {}", s);
    });
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "entropybot-route-io");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });

    private volatile boolean enabled = true;
    private volatile RouteEngine engine;
    private volatile RouteTiles tiles;
    private volatile AreaBoxes areas = new AreaBoxes(List.of(), -64, 319);
    private volatile RouteFileHeader header;
    private volatile boolean loading;
    private volatile String unavailable = "not started";
    private volatile Level level;
    private long settingsHash, areasHash;
    private boolean refillDue;
    private long lastTickNanos, lastGapMs, lastSaveMs;
    private Future<?> saveFuture;
    private boolean chunkListener, shutdownHook;
    private IBaritone listenedBaritone;
    private RouteStats lastStats;
    private final Env env = new Env();
    final AtomicLong levelCalls = new AtomicLong(), chunkLoads = new AtomicLong(), baritoneChanges = new AtomicLong(),
            baritoneFallbackMarks = new AtomicLong();

    private RouteRuntime() {
    }

    // ---------------------------------------------------------------- RoutePlanner

    @Override
    public boolean available() {
        try {
            return unavailableReason() == null;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Null when {@link #available()}, else why not (one line). */
    public String unavailableReason() {
        if (!enabled) return "the route map is off";
        if (level == null) return "not in a world";
        RouteEngine e = engine;
        if (e == null) return unavailable == null ? "not started" : unavailable;
        if (breakingOn()) return "breaking or placing is on";
        return null;
    }

    @Override
    public CompletableFuture<RoutePlan> plan(int dim, Cell start, Cell goal, int goalRadius) {
        try {
            String why = unavailableReason();
            if (why == null && dim != OVERWORLD) why = "only the overworld is mapped (stage 1)";
            RouteEngine e = engine;
            if (e == null) return CompletableFuture.completedFuture(RouteEngine.refused("route map not available: " + (why == null ? "not started" : why)));
            BaritoneCellMoves moves = null;
            double costHeuristic = 3.563;
            if (why == null) {
                try {
                    costHeuristic = BaritoneAPI.getSettings().costHeuristic.value;
                } catch (Throwable ignored) {
                    // keep Baritone's default
                }
                moves = planMoves(e);
            }
            CompletableFuture<RoutePlan> f = e.plan(new RouteRequest(dim, start, goal, goalRadius, moves, costHeuristic), why);
            if (why != null) return f;
            return f.thenApply(p -> {
                if (p.status() == RoutePlan.Status.NO_GOAL_BOX || p.status() == RoutePlan.Status.NO_START_BOX)
                    e.queueMissingAlong(dim, start, goal, areas);
                return p;
            });
        } catch (Throwable t) {
            RouteEngine e = engine;
            if (e != null) e.counters.workerException("plan", t, log);
            return CompletableFuture.completedFuture(RouteEngine.refused("error: " + t));
        }
    }

    /** A context for the plan's start and goal boxes when on the game thread and within the budget, else null. */
    private BaritoneCellMoves planMoves(RouteEngine e) {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (!mc.isSameThread()) return null;
            IBaritone b = SafetyNet.primary();
            if (b == null || !e.scheduler.takeContext(System.currentTimeMillis())) return null;
            BaritoneCellMoves m = BaritoneCellMoves.create(b);
            return m.walkingOnly() ? m : null;
        } catch (Throwable t) {
            e.counters.workerException("plan context", t, log);
            return null;
        }
    }

    @Override
    public RouteStats stats() {
        try {
            RouteEngine e = engine;
            if (e != null) return lastStats = e.stats();
            if (lastStats != null) return lastStats;
            return new RouteCounters().snapshot(System.currentTimeMillis(), 0, 0);
        } catch (Throwable t) {
            return new RouteCounters().snapshot(System.currentTimeMillis(), 0, 0);
        }
    }

    @Override
    public void requestBuildAlong(int dim, Cell a, Cell b) {
        try {
            RouteEngine e = engine;
            if (e == null || dim != OVERWORLD || a == null || b == null) return;
            e.rebuildAlong(dim, a, b, areas);
        } catch (Throwable t) {
            LOG.warn("[entropybot] route build-along: {}", t.toString());
        }
    }

    // ---------------------------------------------------------------- switches and status

    /** {@code route on|off} (R3 wires the command). Off stops the engine (saving) at the next tick. */
    public void setEnabled(boolean on) {
        enabled = on;
    }

    public boolean enabled() {
        return enabled;
    }

    /** The planner R3 uses (this). */
    public static RoutePlanner planner() {
        return INSTANCE;
    }

    /** For {@code route status} and {@code check}: availability, the core counters, the adapter's own counters. */
    public String statusLine() {
        try {
            String why = unavailableReason();
            StringBuilder sb = new StringBuilder();
            sb.append(why == null ? "route map: on" : "route map: not available (" + why + ")");
            RouteEngine e = engine;
            sb.append("; ").append(stats().line());
            if (e != null) sb.append("; ").append(e.line());
            RouteTiles t = tiles;
            if (t != null) sb.append("; ").append(t.line());
            sb.append("; hooks: block hook ").append(MixinFlags.levelHookApplied ? "in" : "NOT in (staleness from Baritone's events only)")
                    .append(" (").append(levelCalls.get()).append(" calls), chunk loads ").append(chunkLoads.get())
                    .append(", Baritone block events ").append(baritoneChanges.get());
            if (baritoneFallbackMarks.get() > 0) sb.append(" (used as fallback ").append(baritoneFallbackMarks.get()).append(")");
            return sb.toString();
        } catch (Throwable t) {
            return "route map: status failed: " + t;
        }
    }

    // ---------------------------------------------------------------- lifecycle (game thread)

    /** Once a client tick in a world, after Core's ready block. Never throws. */
    public void tick(long tick) {
        try {
            tick0(tick);
        } catch (Throwable t) {
            RouteEngine e = engine;
            if (e != null) e.counters.workerException("route runtime tick", t, log);
            else LOG.warn("[entropybot] route runtime tick: {}", t.toString());
        }
    }

    private void tick0(long tick) {
        long nanos = System.nanoTime();
        lastGapMs = lastTickNanos == 0 ? 0 : (nanos - lastTickNanos) / 1_000_000;
        lastTickNanos = nanos;
        Minecraft mc = Minecraft.getInstance();
        Level lvl = mc.level;
        if (lvl != level) {
            if (engine != null) stop("the world changed");
            level = lvl;
        }
        if (lvl == null) return;
        if (!enabled) {
            if (engine != null) stop("turned off");
            unavailable = "the route map is off";
            return;
        }
        if (!OVERWORLD_ID.equals(Guard.dimOf(lvl))) {
            if (engine != null) stop("left the overworld");
            unavailable = "only the overworld is mapped (stage 1)";
            return;
        }
        if (!MixinFlags.astarTargetPresent && !internalsPresent()) {
            unavailable = "Baritone's internals are missing (not the unoptimized jar)";
            return;
        }
        IBaritone b = SafetyNet.primary();
        if (b == null) {
            unavailable = "Baritone is not ready";
            return;
        }
        if (engine == null) {
            if (tick % 100 != 0 && unavailable != null && unavailable.startsWith("route core")) return; // retried every 5 s
            start(mc, b);
            if (engine == null) return;
        }
        listenToBaritone(b);
        RouteEngine e = engine;
        if (tick % 100 == 0) checkHashes(e);
        if (refillDue && !loading) {
            refillDue = false;
            int n = e.scheduler.refillIdle(areas, OVERWORLD, places());
            log.info("idle queue filled: " + n + " boxes");
        }
        e.scheduler.tick(env);
        long now = System.currentTimeMillis();
        if (!loading && now - lastSaveMs >= SAVE_EVERY_MS && (saveFuture == null || saveFuture.isDone())) {
            lastSaveMs = now;
            RouteTiles t = tiles;
            RouteFileHeader h = header;
            saveFuture = io.submit(() -> t.saveDirty(e.store, h));
        }
    }

    private void start(Minecraft mc, IBaritone b) {
        RouteEngine e;
        try {
            e = RouteEngine.create(log);
        } catch (Throwable t) {
            // R1's core still a stub (stage B), or broken: say so once per minute, retry every 5 s
            String why = "route core not built: " + t;
            if (!why.equals(unavailable)) LOG.warn("[entropybot] route map can't start: {}", why);
            unavailable = why;
            return;
        }
        Path root = Core.INSTANCE.files() == null ? mc.gameDirectory.toPath().resolve(Core.MODID) : Core.INSTANCE.files().root();
        RouteTiles t = new RouteTiles(root.resolve("routes"), d -> d == OVERWORLD ? "overworld" : null,
                RouteTiles.TileIo.files(), e.counters, log);
        Level lvl = mc.level;
        areas = readAreas(lvl);
        settingsHash = settingsHash();
        areasHash = RouteHashes.areas(areas.hashInput());
        header = RouteFileHeader.current(Core.INSTANCE.version(), baritoneVersion(), settingsHash, areasHash);
        engine = e;
        tiles = t;
        loading = true;
        refillDue = true;
        lastSaveMs = System.currentTimeMillis();
        RouteFileHeader h = header;
        io.submit(() -> {
            try {
                RouteTiles.LoadSummary s = t.loadAll(e.store, OVERWORLD, h);
                log.info("map loaded: " + s.line());
            } catch (Throwable x) {
                e.counters.workerException("route load", x, log);
            } finally {
                loading = false;
            }
        });
        FlightRecorder.routeListener = this::onLevelBlock;
        RoutePlannerHolder.set(this);
        RoutePlannerHolder.setDumper(this::dump);
        // stop() clears the planner (RoutePlannerHolder.set(null)); the dumper stays set: dump works without the engine
        if (!chunkListener) {
            NeoForge.EVENT_BUS.addListener(RouteRuntime::onChunkLoad);
            chunkListener = true;
        }
        if (!shutdownHook) {
            Runtime.getRuntime().addShutdownHook(new Thread(this::saveOnQuit, "entropybot-route-quit"));
            shutdownHook = true;
        }
        unavailable = null;
        log.info("route map started: " + areas.areas().size() + " areas, " + RouteScheduler.CONTEXTS_PER_SECOND
                + " contexts/s, " + e.pool.size() + " workers; block hook " + (MixinFlags.levelHookApplied ? "in" : "NOT in"));
    }

    /** Leaving the world (Core, the level became null): stop and save. */
    public void leftWorld() {
        try {
            if (engine != null) stop("left the world");
            level = null;
        } catch (Throwable t) {
            LOG.warn("[entropybot] route stop: {}", t.toString());
        }
    }

    /** Stops the engine: workers joined (2 s), a last save on the io thread (waited up to 3 s). */
    private void stop(String why) {
        RouteEngine e = engine;
        RouteTiles t = tiles;
        RouteFileHeader h = header;
        engine = null;
        FlightRecorder.routeListener = null;
        RoutePlannerHolder.set(null);
        unavailable = why;
        if (e == null) return;
        boolean clean = e.stop(2000);
        lastStats = e.stats();
        if (t != null && h != null) {
            try {
                io.submit(() -> t.saveDirty(e.store, h)).get(3, TimeUnit.SECONDS);
            } catch (Throwable x) {
                e.counters.workerException("route save on stop", x, log);
            }
        }
        log.info("route map stopped (" + why + ")" + (clean ? "" : ", a worker did not end in 2 s") + ": " + lastStats.line());
    }

    private void saveOnQuit() {
        RouteEngine e = engine;
        RouteTiles t = tiles;
        RouteFileHeader h = header;
        if (e == null || t == null || h == null) return;
        try {
            t.saveDirty(e.store, h);
        } catch (Throwable ignored) {
            // the game is quitting; nothing left to report to
        }
    }

    /** Areas or Baritone settings changed: new header; settings = every box stale; both refill the idle queue. */
    private void checkHashes(RouteEngine e) {
        AreaBoxes a = readAreas(level);
        long ah = RouteHashes.areas(a.hashInput());
        long sh = settingsHash();
        if (ah == areasHash && sh == settingsHash) return;
        if (sh != settingsHash) {
            e.store.markAllStale();
            log.info("Baritone's cost settings changed: every box is stale");
        }
        if (ah != areasHash) log.info("the areas changed: " + a.areas().size() + " areas");
        areas = a;
        areasHash = ah;
        settingsHash = sh;
        header = RouteFileHeader.current(Core.INSTANCE.version(), baritoneVersion(), sh, ah);
        refillDue = true;
    }

    // ---------------------------------------------------------------- staleness hooks

    /** The ClientLevel hook (through FlightRecorder, game thread). */
    private void onLevelBlock(ClientLevel lvl, BlockPos pos, BlockState from, BlockState to) {
        RouteEngine e = engine;
        if (e == null || lvl != level) return;
        levelCalls.incrementAndGet();
        e.blockChanged(OVERWORLD, pos.getX(), pos.getY(), pos.getZ(), walkClass(from), walkClass(to), from == to);
    }

    /** NeoForge's ChunkEvent.Load (posted by ClientChunkCache.replaceWithPacketData on the client). */
    private static void onChunkLoad(ChunkEvent.Load event) {
        try {
            if (!event.getLevel().isClientSide()) return;
            RouteRuntime r = INSTANCE;
            RouteEngine e = r.engine;
            if (e == null || event.getLevel() != r.level) return;
            r.chunkLoads.incrementAndGet();
            e.scheduler.chunkLoaded(OVERWORLD, event.getChunk().getPos().x, event.getChunk().getPos().z);
        } catch (Throwable t) {
            LOG.warn("[entropybot] route chunk load: {}", t.toString());
        }
    }

    /**
     * Baritone's onBlockChange (fed by its packet mixins) as the second source: counted always; used to mark boxes
     * stale (without a walkability filter, the old state is unknown) only while the ClientLevel hook has heard nothing.
     */
    private void listenToBaritone(IBaritone b) {
        if (b == listenedBaritone) return;
        listenedBaritone = b;
        b.getGameEventHandler().registerEventListener(new AbstractGameEventListener() {
            @Override
            public void onBlockChange(BlockChangeEvent event) {
                try {
                    RouteRuntime r = INSTANCE;
                    RouteEngine e = r.engine;
                    if (e == null) return;
                    r.baritoneChanges.incrementAndGet();
                    if (r.levelCalls.get() > 0) return;
                    for (Pair<BlockPos, BlockState> p : event.getBlocks()) {
                        BlockPos pos = p.first();
                        r.baritoneFallbackMarks.incrementAndGet();
                        e.markStaleAround(OVERWORLD, pos.getX(), pos.getY(), pos.getZ());
                    }
                } catch (Throwable t) {
                    LOG.warn("[entropybot] route Baritone block event: {}", t.toString());
                }
            }
        });
    }

    /** The walkability class of a block state ({@link RouteRules}). Game thread. */
    static int walkClass(BlockState s) {
        try {
            FluidState f = s.getFluidState();
            if (!f.isEmpty()) return f.isSource() ? RouteRules.LIQUID_SOURCE : RouteRules.LIQUID_FLOW;
            if (MovementHelper.avoidWalkingInto(s)) return RouteRules.AVOID;
            VoxelShape sh = s.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
            if (sh.isEmpty()) return RouteRules.PASSABLE;
            return Block.isShapeFullBlock(sh) ? RouteRules.SOLID : RouteRules.PARTIAL;
        } catch (Throwable t) {
            return RouteRules.PARTIAL;
        }
    }

    // ---------------------------------------------------------------- route dump

    /**
     * {@code route dump <x y z>} (R3 wires the command): writes the box holding (x, y, z), plus a 2-block margin, as a
     * JUnit fixture under {@code entropybot\routes\dumps\}. Call on the game thread (the context is made there); the
     * writing runs on the io thread. Works without the route core (only Baritone is needed). The future answers
     * "ok: wrote ..." or "error: ...".
     */
    public CompletableFuture<String> dump(int x, int y, int z) {
        return dumpTo(x, y, z, null);
    }

    /**
     * R3's {@code RouteDumper} shape ({@code RoutePlannerHolder.setDumper(RouteRuntime.INSTANCE::dump)} once merged):
     * true when the dump was started (the result line goes to the log), false when it can't be done.
     */
    public boolean dump(int dim, int x, int y, int z, Path outDir) {
        if (dim != OVERWORLD) return false;
        CompletableFuture<String> f = dumpTo(x, y, z, outDir);
        f.thenAccept(s -> {
            if (s.startsWith("ok")) LOG.info("[entropybot] route dump: {}", s);
            else LOG.warn("[entropybot] route dump: {}", s);
        });
        return !f.isDone() || f.getNow("error").startsWith("ok");
    }

    /** {@link #dump(int, int, int)} into {@code outDir} (null = {@code entropybot\routes\dumps}). */
    public CompletableFuture<String> dumpTo(int x, int y, int z, Path outDir) {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (!mc.isSameThread()) return CompletableFuture.completedFuture("error: route dump must start on the game thread");
            if (mc.level == null) return CompletableFuture.completedFuture("error: not in a world");
            if (!OVERWORLD_ID.equals(Guard.dimOf(mc.level)))
                return CompletableFuture.completedFuture("error: only the overworld is mapped (stage 1)");
            if (!MixinFlags.astarTargetPresent && !internalsPresent())
                return CompletableFuture.completedFuture("error: Baritone's internals are missing");
            IBaritone b = SafetyNet.primary();
            if (b == null) return CompletableFuture.completedFuture("error: Baritone is not ready");
            SectionKey k = SectionKey.of(OVERWORLD, x, y, z);
            SectionRecord.Quality q = env.terrain(k);
            BaritoneCellMoves m = BaritoneCellMoves.create(b);
            long sh = settingsHash();
            String note = "made " + LocalDateTime.now().withNano(0) + " at " + x + " " + y + " " + z
                    + (m.walkingOnly() ? "" : "; NOT walking-only: breaking or placing was on")
                    + (m.sprintAsSettings(setting("allowSprint", true)) ? "" : "; the bot could not sprint (hungry)");
            Path root = Core.INSTANCE.files() == null ? mc.gameDirectory.toPath().resolve(Core.MODID) : Core.INSTANCE.files().root();
            Path file = (outDir != null ? outDir : root.resolve("routes").resolve("dumps"))
                    .resolve(DumpFixture.fileName(k, LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))));
            return CompletableFuture.supplyAsync(() -> {
                try {
                    StringBuilder sb = new StringBuilder();
                    DumpFixture.Counts c = DumpFixture.write(sb, k, q, sh, note, m);
                    Files.createDirectories(file.getParent());
                    Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
                    return "ok: wrote " + file + " (" + c.cells() + " cells, " + c.moves() + " moves, "
                            + (q == null ? "no terrain known" : q.name().toLowerCase()) + ")";
                } catch (Throwable t) {
                    return "error: route dump failed: " + t;
                }
            }, io);
        } catch (Throwable t) {
            return CompletableFuture.completedFuture("error: route dump failed: " + t);
        }
    }

    // ---------------------------------------------------------------- the game side of the builder

    private final class Env implements RouteScheduler.BuildEnv {
        @Override
        public String pauseReason() {
            if (loading) return "loading the map files";
            Minecraft mc = Minecraft.getInstance();
            if (mc.level == null || mc.player == null) return "not in a world";
            if (breakingOn()) return "breaking or placing is on";
            if (!Core.INSTANCE.commands.ready()) return "commands not ready";
            if (mc.player.isDeadOrDying()) return "the bot is dead";
            if (setting("allowSprint", true) && mc.player.getFoodData().getFoodLevel() <= 6)
                return "too hungry to sprint (costs would be the walking ones)";
            return null;
        }

        @Override
        public boolean idle() {
            try {
                if (!Core.INSTANCE.commands.idleForRoutes() || Core.INSTANCE.reflexes.hold()) return false;
                IBaritone b = SafetyNet.primary();
                return b != null && !b.getPathingBehavior().isPathing()
                        && b.getPathingControlManager().mostRecentInControl().isEmpty();
            } catch (Throwable t) {
                return false;
            }
        }

        @Override
        public boolean lagging() {
            return lastGapMs > LAG_MS;
        }

        @Override
        public AreaBoxes areas() {
            return areas;
        }

        @Override
        public SectionRecord.Quality terrain(SectionKey k) {
            Minecraft mc = Minecraft.getInstance();
            ClientLevel lvl = mc.level;
            if (lvl == null) return null;
            boolean[] loaded = new boolean[9];
            int i = 0;
            for (int dz = -1; dz <= 1; dz++)
                for (int dx = -1; dx <= 1; dx++)
                    loaded[i++] = lvl.getChunkSource().hasChunk(k.sx() + dx, k.sz() + dz);
            boolean cached = false;
            if (!loaded[4]) {
                try {
                    IBaritone b = SafetyNet.primary();
                    IWorldData wd = b == null ? null : b.getWorldProvider().getCurrentWorld();
                    ICachedWorld cw = wd == null ? null : wd.getCachedWorld();
                    cached = cw != null && cw.isCached(k.minX() + 8, k.minZ() + 8);
                } catch (Throwable ignored) {
                    // no cache: the box is unknown
                }
            }
            return RouteRules.quality(loaded, cached);
        }

        @Override
        public RouteScheduler.WorkerMoves newMoves() {
            IBaritone b = SafetyNet.primary();
            if (b == null) return null;
            BaritoneCellMoves m = BaritoneCellMoves.create(b);
            if (!m.sprintAsSettings(setting("allowSprint", true))) return null;
            return new RouteScheduler.WorkerMoves(m, m.walkingOnly());
        }
    }

    // ---------------------------------------------------------------- small readers

    /** Baritone's breaking or placing is on (a mine or build job): no building, no plans (review R5). */
    static boolean breakingOn() {
        try {
            Settings s = BaritoneAPI.getSettings();
            return s.allowBreak.value || s.allowPlace.value;
        } catch (Throwable t) {
            return true;
        }
    }

    static boolean setting(String name, boolean dflt) {
        try {
            Settings.Setting<?> st = BaritoneAPI.getSettings().byLowerName.get(name.toLowerCase());
            return st != null && st.value instanceof Boolean v ? v : dflt;
        } catch (Throwable t) {
            return dflt;
        }
    }

    /** RouteHashes.settings over {@link #COST_SETTINGS}. */
    static long settingsHash() {
        Map<String, String> m = new TreeMap<>();
        try {
            Settings s = BaritoneAPI.getSettings();
            for (String n : COST_SETTINGS) {
                Settings.Setting<?> st = s.byLowerName.get(n.toLowerCase());
                m.put(n, st == null ? "absent" : String.valueOf(st.value));
            }
        } catch (Throwable t) {
            m.put("error", t.toString());
        }
        return RouteHashes.settings(m);
    }

    static String baritoneVersion() {
        try {
            return ModList.get().getModContainerById("baritone").map(c -> c.getModInfo().getVersion().toString()).orElse("unknown");
        } catch (Throwable t) {
            return "unknown";
        }
    }

    /** The owner's overworld areas from the guard's policy, as AreaBoxes. */
    static AreaBoxes readAreas(Level lvl) {
        List<int[]> out = new ArrayList<>();
        try {
            for (Box a : Guard.INSTANCE.core.policy().areas) {
                if (!OVERWORLD_ID.equals(a.dim)) continue;
                out.add(new int[]{OVERWORLD, a.x1, a.z1, a.x2, a.z2, a.y1, a.y2});
            }
        } catch (Throwable t) {
            LOG.warn("[entropybot] route areas: {}", t.toString());
        }
        int minY = lvl == null ? -64 : lvl.getMinBuildHeight();
        int maxY = lvl == null ? 319 : lvl.getMaxBuildHeight() - 1;
        return new AreaBoxes(out, minY, maxY);
    }

    /** The marked places in the overworld, {x, y, z}. */
    static List<int[]> places() {
        List<int[]> out = new ArrayList<>();
        try {
            for (JsonObject p : Core.INSTANCE.knowledge.places().values()) {
                if (!p.has("x") || !p.has("y") || !p.has("z")) continue;
                String d = p.has("dim") && !p.get("dim").isJsonNull() ? p.get("dim").getAsString() : OVERWORLD_ID;
                if (!OVERWORLD_ID.equals(d) && !"overworld".equals(d)) continue;
                out.add(new int[]{p.get("x").getAsInt(), p.get("y").getAsInt(), p.get("z").getAsInt()});
            }
        } catch (Throwable t) {
            LOG.warn("[entropybot] route places: {}", t.toString());
        }
        return out;
    }

    /** The unoptimized Baritone's internals are on the classpath (resource lookup, no class loading). */
    static boolean internalsPresent() {
        ClassLoader cl = RouteRuntime.class.getClassLoader();
        return cl != null && cl.getResource("baritone/pathing/movement/Moves.class") != null
                && cl.getResource("baritone/pathing/movement/CalculationContext.class") != null;
    }
}
