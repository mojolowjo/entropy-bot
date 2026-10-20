package io.github.mojolowjo.entropybot.commands;

import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import io.github.mojolowjo.entropybot.Core;
import io.github.mojolowjo.entropybot.aa.AaEscape;
import io.github.mojolowjo.entropybot.aa.AaScorer;
import io.github.mojolowjo.entropybot.aa.AaTree;
import io.github.mojolowjo.entropybot.aa.AaLadder;
import io.github.mojolowjo.entropybot.aa.IronOptions;
import io.github.mojolowjo.entropybot.aa.core.Blackboard;
import io.github.mojolowjo.entropybot.aa.core.Inputs;
import io.github.mojolowjo.entropybot.aa.core.JobPort;
import io.github.mojolowjo.entropybot.aa.core.Node;
import io.github.mojolowjo.entropybot.aa.core.Request;
import io.github.mojolowjo.entropybot.aa.core.Status;
import io.github.mojolowjo.entropybot.aa.core.TaskKind;
import io.github.mojolowjo.entropybot.aa.core.Tracer;
import io.github.mojolowjo.entropybot.brain.BrainState;
import io.github.mojolowjo.entropybot.brain.Feasibility;
import io.github.mojolowjo.entropybot.engine.Reflexes;
import io.github.mojolowjo.entropybot.plan.RoutineRule;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 0.36.0 AA v0 (AA_PLAN 2-6, 8, 11; package P3): the thin game-side adapter of the new brain. Fills the Blackboard
 * from {@code BrainRuntime.sense()} (reused, not copied), implements {@link JobPort} over Chains/Jobs (AA's chain is
 * named {@value #CHAIN}; only AA starts and cancels it), is ticked from {@code Commands.tick} every 5 client ticks
 * (4 Hz; the sense snapshot every 10), writes the snapshot and decision files under {@code entropybot/aa/}, and turns
 * itself off after 10 tick errors in a minute (saying so in {@code aa status} and a whisper).
 *
 * <p>Loader notes: nothing of its own: the client tick through Commands (NeoForge ClientTickEvent.Post via Core;
 * Fabric END_CLIENT_TICK), LocalPlayer getters, our Chains/Jobs. No events, mixins, reflection or Baritone.
 */
public final class AaRuntime implements JobPort {
    private static final Logger LOG = LogUtils.getLogger();
    public static final String CHAIN = "aa";
    static final long HOLD_MS = 2 * 60_000;
    static final int ERRORS_PER_MINUTE_OFF = 10;

    private final Commands c;
    final AaTree tree;
    final Blackboard bb;
    private final Tracer tracer = new Tracer();
    // AA round 2 C: the run ledger (runs.csv): one row per run, written when aa turns off or the world unloads
    private long runStartMs = -1, runPicks, runStuckHalts, runDeaths;
    private boolean runDead;
    private io.github.mojolowjo.entropybot.aa.brain.RunLedger.Marks runMarks = new io.github.mojolowjo.entropybot.aa.brain.RunLedger.Marks();
    private long runSwitches0, runPointless0, runPreempts0;
    private BrainState sensed;
    private long sensedTick = -1;
    private long seq, current = -1;
    private String currentChain, lastEnd;
    private long progress;
    /** AA_ROUND3 1: the escape's way-up option that runs (its failure is charged to it), and the Failures view. */
    private io.github.mojolowjo.entropybot.aa.Options.Option escapeOpt;
    private final io.github.mojolowjo.entropybot.aa.Options.OptionMemory wayMem = new io.github.mojolowjo.entropybot.aa.Options.OptionMemory();
    static final String WAYUP = "wayup";
    private int lastRestoreWork = -1;
    private String lastStep, lastJobStatus;
    private int lastBag = -1;
    private int[] lastPos;
    /**
     * 0.36.a6: the escape from under ground inside AA's task (AaEscape): pending (the stuck walk's end is settling), the
     * way that runs ("pillar" job / "climb" chain), the chain to go on with afterwards, the stuck end (the task's
     * failure text if no way out works), escapes in this task.
     */
    private boolean escapePending;
    private String escapeWay, escapeResume, escapeFail;
    private Object escapeJob;
    private int escapes;
    private long heldUntil, lastSnapshotAt, lastErrorAt, errorsThisMinute, minuteStart, errors;
    private String lastError, offReason;
    private int decisionLines;
    private long aiRequests;

    AaRuntime(Commands c) {
        this.c = c;
        this.tree = AaTree.build(new AaScorer());
        this.bb = new Blackboard(System::currentTimeMillis, this, tracer);
        tracer.sink(this::appendDecision);
        JsonObject d = data();
        if (d.has(AaLadder.SHELTER_FACT) && d.get(AaLadder.SHELTER_FACT).getAsBoolean()) AaLadder.noteShelter(bb);
        bb.fact(IronOptions.FACTS, perTick(new io.github.mojolowjo.entropybot.aa.FactsMemo<>(0), timed("iron", this::ironFacts)));
        bb.fact(io.github.mojolowjo.entropybot.aa.RungOptions.SURFACE_FACTS, perTick(new io.github.mojolowjo.entropybot.aa.FactsMemo<>(0), timed("surface", this::surfaceFacts)));
        bb.fact(io.github.mojolowjo.entropybot.aa.WoodOptions.FACTS, (java.util.function.Supplier<io.github.mojolowjo.entropybot.aa.WoodOptions.WoodFacts>) this::woodFacts);
        bb.fact(io.github.mojolowjo.entropybot.aa.RungOptions.TOOLS_FACTS, (java.util.function.Supplier<io.github.mojolowjo.entropybot.aa.RungOptions.ToolsFacts>) this::toolsFacts);
        bb.fact(io.github.mojolowjo.entropybot.aa.FoodOptions.FACTS, (java.util.function.Supplier<io.github.mojolowjo.entropybot.aa.FoodOptions.FoodFacts>) this::foodFacts);
        bb.fact(io.github.mojolowjo.entropybot.aa.AaDesires.STATS_FACT, tree.brain.stats);
        // AA_ROUND3 2: the sequencer's horizon (aa horizon, saved) and the nearest known coal (torches' site, cached 10 s)
        io.github.mojolowjo.entropybot.sense.Seen.fair(!d.has("fair") || d.get("fair").getAsBoolean());     // AA_ROUND3 3: default on
        if (d.has("horizon")) bb.fact(io.github.mojolowjo.entropybot.aa.brain.Brain.HORIZON_FACT, d.get("horizon").getAsInt());
        bb.fact(io.github.mojolowjo.entropybot.aa.AaDesires.COAL_SITE_FACT, (java.util.function.Supplier<int[]>) this::coalSite);
        c.chains.onEnd(this::chainEnded);
    }

    // ---- AA_ROUND3 4: facts timing (the pick's cost lives in its facts supplier) ----

    private final Map<String, Long> slowLogAt = new java.util.HashMap<>();
    /** The last facts breakdown ("ores 1200 us, discovery 300 us"), set by the suppliers that measure their parts. */
    private String factsParts = "";

    /** The game tick (level time), -1 without a level: a facts supplier is read once per tick (the pick, the re-score, the sequencer). */
    private static long gameTick() {
        var lv = Minecraft.getInstance().level;
        return lv == null ? -1 : lv.getGameTime();
    }

    private static <T> java.util.function.Supplier<T> perTick(io.github.mojolowjo.entropybot.aa.FactsMemo<T> m, java.util.function.Supplier<T> s) {
        return () -> m.get(gameTick(), System.currentTimeMillis(), null, s);
    }

    /** A facts supplier that logs a call over 2 ms (with its parts), at most every 10 s per supplier. */
    private <T> java.util.function.Supplier<T> timed(String name, java.util.function.Supplier<T> s) {
        return () -> {
            long t0 = System.nanoTime();
            factsParts = "";
            T v = s.get();
            long us = (System.nanoTime() - t0) / 1000;
            long now = System.currentTimeMillis();
            if (us > 2000 && now - slowLogAt.getOrDefault(name, 0L) >= 10_000) {
                slowLogAt.put(name, now);
                LOG.info("[entropybot] aa: facts {} took {} us{}", name, us, factsParts.isEmpty() ? "" : " (" + factsParts + ")");
            }
            return v;
        };
    }

    // ---- aa-food: the FOOD rung's facts and the drain sampler ----

    private io.github.mojolowjo.entropybot.aa.FoodOptions.FoodFacts foodCache;
    private long foodCacheAt = -1;
    /** The food facts are read at most this often (the desire's park check asks every tick). */
    static final long FOOD_FACTS_MS = 10_000;
    static final int FOOD_R = 24, FOOD_DY = 6;

    /**
     * aa-food: the FOOD rung's facts (cached {@link #FOOD_FACTS_MS}): the bag, the stock target, a furnace, the animals
     * the hunt may take (Hunting.animalsNear) and those outside the areas, and one scan of the blocks within
     * {@link #FOOD_R} (y +-{@link #FOOD_DY}): oak/dark-oak logs, ripe berry bushes, mushrooms, water, kelp, a grass patch.
     * Null when nothing can be read; a throw is caught by the picker (logged, counted) and by the desire's park check.
     */
    /**
     * A field spot by the water source at (x, y, z): a neighbour at the water's level that is grass or dirt with air (or a
     * plant) above it, inside my areas: the bot stands on it (y + 1) and "farm plant wheat here 4" has the water in reach.
     */
    private int[] fieldSpot(net.minecraft.world.level.Level lv, int x, int y, int z, String dim) {
        int[][] side = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] s : side) {
            int nx = x + s[0], nz = z + s[1];
            var g = lv.getBlockState(new net.minecraft.core.BlockPos(nx, y, nz));
            var up = lv.getBlockState(new net.minecraft.core.BlockPos(nx, y + 1, nz));
            boolean soil = g.is(net.minecraft.world.level.block.Blocks.GRASS_BLOCK) || g.is(net.minecraft.world.level.block.Blocks.DIRT);
            if (soil && (up.isAir() || up.canBeReplaced()) && up.getFluidState().isEmpty() && c.inAreas(dim, nx, nz)) return new int[]{nx, y + 1, nz};
        }
        return null;
    }

    /** AA_ROUND3 5: the herds I have seen in this dimension (Sightings kind ANIMAL), confidence decayed to now (game time). */
    static List<io.github.mojolowjo.entropybot.aa.FoodOptions.Herd> herds(net.minecraft.world.level.Level lv, int[] me) {
        List<io.github.mojolowjo.entropybot.aa.FoodOptions.Herd> out = new ArrayList<>();
        var sg = io.github.mojolowjo.entropybot.sense.ChunkPass.INSTANCE.sightings();
        String dim = io.github.mojolowjo.entropybot.sense.ChunkPass.INSTANCE.dim();
        if (sg == null || dim == null || lv == null) return out;
        long game = lv.getGameTime() * 50;
        for (var r : sg.animals(dim, null)) {
            String type = r.id().startsWith("minecraft:") ? r.id().substring(10) : r.id();
            out.add(new io.github.mojolowjo.entropybot.aa.FoodOptions.Herd(type, r.x(), r.y(), r.z(), r.count(),
                    io.github.mojolowjo.entropybot.sense.Sightings.confidence(r, game), Math.sqrt(Jobs.distSq(me, new int[]{r.x(), r.y(), r.z()}))));
        }
        return out;
    }

    /**
     * AA_ROUND3 5: mob id -> the best herd seen ({x, z, distance, confidence x 100}) for the loot plan's "at" hunt, the
     * types in view now left out (the plain hunt takes them).
     */
    static Map<String, int[]> herdsSeen(net.minecraft.world.level.Level lv, LocalPlayer p) {
        Map<String, int[]> out = new java.util.HashMap<>();
        if (lv == null || p == null) return out;
        java.util.Set<String> inView = new java.util.HashSet<>();
        for (var e : lv.getEntitiesOfClass(net.minecraft.world.entity.animal.Animal.class, p.getBoundingBox().inflate(io.github.mojolowjo.entropybot.vocab.HuntRules.RANGE)))
            inView.add(net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString());
        int[] me = Jobs.here(p);
        Map<String, Double> score = new java.util.HashMap<>();
        for (var h : herds(lv, me)) {
            String id = h.type().indexOf(':') >= 0 ? h.type() : "minecraft:" + h.type();
            if (inView.contains(id) || h.conf() < io.github.mojolowjo.entropybot.aa.FoodOptions.HERD_MIN_CONF || h.dist() > io.github.mojolowjo.entropybot.aa.FoodOptions.HERD_MAX) continue;
            double s = (2 * h.dist() + 60) / h.conf();
            if (s < score.getOrDefault(id, Double.MAX_VALUE)) {
                score.put(id, s);
                out.put(id, new int[]{h.x(), h.z(), (int) Math.round(h.dist()), (int) Math.round(h.conf() * 100)});
            }
        }
        return out;
    }

    io.github.mojolowjo.entropybot.aa.FoodOptions.FoodFacts foodFacts() {
        long now = System.currentTimeMillis();
        // review: the cache holds only while the bag, the bar and the furnace are unchanged (a hunt/cook/eat refreshes it)
        if (foodCache != null && now - foodCacheAt < FOOD_FACTS_MS && sensed != null && sensed.feas != null
                && foodCache.bag().equals(sensed.feas.bag) && foodCache.food() == bb.in.food
                && foodCache.furnace() == (bb.in.furnaceKnown || bb.in.furnaceInBag)) return foodCache;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null || sensed == null || sensed.feas == null) return null;
        var lv = mc.level;
        int[] me = Jobs.here(p);
        Map<String, Integer> bag = sensed.feas.bag;
        Map<String, Integer> animals = c.hunting.animalsNear(p);
        int outside = 0;
        for (var e : lv.getEntitiesOfClass(net.minecraft.world.entity.animal.Animal.class, p.getBoundingBox().inflate(32))) {
            String type = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getPath();
            if (io.github.mojolowjo.entropybot.aa.FoodOptions.MEAT_ANIMALS.contains(type)) outside++;
        }
        // the director (2026-10-19): allowed animals outside my areas within 48, for an urgent "hunt ... out"
        Map<String, Integer> animalsOut = new java.util.HashMap<>();
        // owner's rule 2026-10-19: any hunger may walk out (costed); seen up to twice the hunt's reach, the nearest's place kept
        double outDist = 2 * io.github.mojolowjo.entropybot.vocab.HuntRules.OUT_R;
        int[] outAt = null;
        String hdim = io.github.mojolowjo.entropybot.guard.Guard.dimOf(lv);
        for (var e : lv.getEntitiesOfClass(net.minecraft.world.entity.animal.Animal.class, p.getBoundingBox().inflate(2 * io.github.mojolowjo.entropybot.vocab.HuntRules.OUT_R))) {
            String type = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getPath();
            if (!io.github.mojolowjo.entropybot.aa.FoodOptions.MEAT_ANIMALS.contains(type) || e.isBaby() || e.hasCustomName()
                    || io.github.mojolowjo.entropybot.engine.Hostility.protectedMob(e) || c.inAreas(hdim, e.getBlockX(), e.getBlockZ())
                    || p.distanceTo(e) > 2 * io.github.mojolowjo.entropybot.vocab.HuntRules.OUT_R) continue;
            animalsOut.merge(type, 1, Integer::sum);
            if (p.distanceTo(e) < outDist) { outDist = p.distanceTo(e); outAt = new int[]{e.getBlockX(), e.getBlockY(), e.getBlockZ()}; }
        }
        int inside = 0;
        for (int n : animals.values()) inside += n;
        outside = Math.max(0, outside - inside);
        int[] oak = null, berry = null, red = null, brown = null;
        String oakType = "oak";
        double oakD = Double.MAX_VALUE, berryD = Double.MAX_VALUE, redD = Double.MAX_VALUE, brownD = Double.MAX_VALUE;
        boolean water = false, kelp = false;
        int[] waterSpot = null;
        double waterSpotD = Double.MAX_VALUE;
        String wdim = io.github.mojolowjo.entropybot.guard.Guard.dimOf(lv);
        Map<Long, Integer> grassCells = new java.util.HashMap<>();
        var m = new net.minecraft.core.BlockPos.MutableBlockPos();
        for (int x = me[0] - FOOD_R; x <= me[0] + FOOD_R; x++)
            for (int z = me[2] - FOOD_R; z <= me[2] + FOOD_R; z++)
                for (int y = me[1] - FOOD_DY; y <= me[1] + FOOD_DY; y++) {
                    m.set(x, y, z);
                    if (!lv.isLoaded(m)) continue;
                    var st = lv.getBlockState(m);
                    if (st.isAir()) continue;
                    var b = st.getBlock();
                    double d = Math.sqrt(Jobs.distSq(me, new int[]{x, y, z}));
                    if (b == net.minecraft.world.level.block.Blocks.OAK_LOG || b == net.minecraft.world.level.block.Blocks.DARK_OAK_LOG) {
                        if (d < oakD) { oakD = d; oak = new int[]{x, y, z}; oakType = b == net.minecraft.world.level.block.Blocks.OAK_LOG ? "oak" : "dark_oak"; }
                    } else if (b == net.minecraft.world.level.block.Blocks.SWEET_BERRY_BUSH) {
                        if (st.getValue(net.minecraft.world.level.block.SweetBerryBushBlock.AGE) >= 2 && d < berryD) { berryD = d; berry = new int[]{x, y, z}; }
                    } else if (b == net.minecraft.world.level.block.Blocks.RED_MUSHROOM) {
                        if (d < redD) { redD = d; red = new int[]{x, y, z}; }
                    } else if (b == net.minecraft.world.level.block.Blocks.BROWN_MUSHROOM) {
                        if (d < brownD) { brownD = d; brown = new int[]{x, y, z}; }
                    } else if (b == net.minecraft.world.level.block.Blocks.KELP || b == net.minecraft.world.level.block.Blocks.KELP_PLANT) {
                        kelp = true;
                    } else if (b == net.minecraft.world.level.block.Blocks.SHORT_GRASS || b == net.minecraft.world.level.block.Blocks.TALL_GRASS) {
                        grassCells.merge(((long) (x >> 3) << 32) | ((z >> 3) & 0xffffffffL), 1, Integer::sum);
                    }
                    if (st.getFluidState().is(net.minecraft.tags.FluidTags.WATER) && st.getFluidState().isSource()) {
                        if (d <= 12) water = true;
                        // aa383 (a field "no water within 4"): a dry cell beside the water to stand on and plant around
                        if (d < waterSpotD) {
                            int[] s = fieldSpot(lv, x, y, z, wdim);
                            if (s != null) { waterSpotD = d; waterSpot = s; }
                        }
                    }
                }
        // a grass patch: the 8x8 cell with the most grass (10 at least), inside my areas
        int[] grass = null;
        int bestG = 9;
        String dim = io.github.mojolowjo.entropybot.guard.Guard.dimOf(lv);
        for (var e : grassCells.entrySet()) {
            if (e.getValue() <= bestG) continue;
            long k = e.getKey();
            int cx = ((int) (k >> 32)) << 3, cz = ((int) k) << 3;
            if (!c.inAreas(dim, cx + 4, cz + 4)) continue;
            bestG = e.getValue();
            grass = new int[]{cx, cz, cx + 7, cz + 7};
        }
        boolean farm;
        try { farm = Core.INSTANCE.knowledge.places().get("farm") != null; } catch (RuntimeException e) { farm = false; }
        int stored = 0;
        try {
            Map<String, Integer> stock = c.storage.stock(p).totals();        // the bag included (GoalPlanning subtracts it too)
            for (var e : stock.entrySet()) {
                String path = e.getKey().startsWith("minecraft:") ? e.getKey().substring(10) : e.getKey();
                if (io.github.mojolowjo.entropybot.aa.brain.FoodModel.energyOf(path) > 0) stored += e.getValue() - bag.getOrDefault(path, 0);
            }
        } catch (RuntimeException e) {
            LOG.debug("[entropybot] aa: food facts: no stock view: {}", e.toString());
        }
        foodCache = new io.github.mojolowjo.entropybot.aa.FoodOptions.FoodFacts(bb.in.food, bb.in.saturation, bag,
                io.github.mojolowjo.entropybot.aa.AaDesires.foodTarget(bb), bb.in.furnaceKnown || bb.in.furnaceInBag,
                animals, outside, c.hunting.ownerOff() ? "hunting is off (the owner's hunting off; hunting on lets me)" : null,
                oak, oakType, oak == null ? -1 : oakD, berry, berry == null ? -1 : berryD, red, brown, grass, water, farm,
                Math.max(0, stored), kelp, me, animalsOut, p.getHealth(), outDist, outAt, waterSpot, herds(lv, me));
        foodCacheAt = now;
        return foodCache;
    }

    private boolean conserving;
    private String conserveWhy;

    /**
     * aa-sprint: conserve hunger while no food source is known and the bag holds less than the predicted need
     * (HungerConserve). Renews the flag the movement package reads; one log line per start and end. Never throws.
     */
    private void conserveTick(long now) {
        boolean on = false;
        String why = null;
        try {
            double bagE = io.github.mojolowjo.entropybot.aa.brain.FoodModel.energy(bb.in.bag);
            double target = io.github.mojolowjo.entropybot.aa.AaDesires.foodTarget(bb);
            if (bb.in.hungerDrains && !io.github.mojolowjo.entropybot.aa.brain.HungerConserve.covered(bagE, target)) {
                String park = io.github.mojolowjo.entropybot.aa.FoodOptions.noSource(foodFacts());
                // aa-costs: sprint's measured hunger cost vs the time it saves, weighted by scarcity
                double hand = io.github.mojolowjo.entropybot.aa.brain.FoodModel.handRate(io.github.mojolowjo.entropybot.aa.core.TaskKind.EXPLORE);
                double scale = tree.brain.stats.drain(io.github.mojolowjo.entropybot.aa.core.TaskKind.EXPLORE.name(), hand) / hand;
                var w = io.github.mojolowjo.entropybot.aa.brain.HungerConserve.weigh(true, park != null, bagE, target, scale);
                on = w.conserve();
                io.github.mojolowjo.entropybot.aa.brain.Costs.note(bb, "sprint", w.terms());
                if (on) why = String.format(java.util.Locale.ROOT, "bag %.0f of %.0f food points, %s; %s", bagE, target, park == null ? "a food source known" : park, w.terms());
            } else io.github.mojolowjo.entropybot.aa.brain.Costs.note(bb, "sprint", "the bag covers the need -> sprint");
        } catch (RuntimeException e) {
            LOG.debug("[entropybot] aa: conserve check: {}", e.toString());
            on = conserving;                      // keep the last answer on a read error
            why = conserveWhy;
        }
        io.github.mojolowjo.entropybot.aa.brain.HungerConserve.set(on, now);
        if (on != conserving) {
            LOG.info("[entropybot] aa: conserving hunger {} ({})", on ? "starts: no sprint, no parkour" : "ends: normal movement",
                    on ? why : "a food source is known or the bag covers the need");
            conserving = on;
        }
        conserveWhy = why;
    }

    /** aa-food drain sampler: energy (food + saturation) at the window's start, its start time and kind. */
    private double drainE0 = -1;
    private long drainT0;
    private String drainKind;
    /** A sample closes after this long at most (a long task gives several). */
    static final long DRAIN_WINDOW_MS = 5 * 60_000;

    /**
     * aa-food: one drain sample per stretch of one task kind (FoodModel): closed when the kind changes, the bot ate
     * (the energy rose) or {@link #DRAIN_WINDOW_MS} passed; samples under a minute are dropped (BrainStats.recordDrain).
     */
    private void drainTick(long now) {
        if (!bb.in.hungerDrains) { drainE0 = -1; return; }
        double e = bb.in.food + bb.in.saturation;
        String kind = (bb.task() == null ? io.github.mojolowjo.entropybot.aa.core.TaskKind.NONE : bb.task().kind).name();
        if (drainE0 < 0) { drainE0 = e; drainT0 = now; drainKind = kind; return; }
        boolean ate = e > drainE0 + 0.01;
        if (ate || !kind.equals(drainKind) || now - drainT0 >= DRAIN_WINDOW_MS) {
            double minutes = (now - drainT0) / 60_000.0;
            if (!ate && tree.brain.stats.recordDrain(drainKind, drainE0 - e, minutes))
                LOG.info("[entropybot] aa: food drain {}: {} points in {} min", drainKind, String.format(java.util.Locale.ROOT, "%.1f", drainE0 - e), String.format(java.util.Locale.ROOT, "%.1f", minutes));
            drainE0 = e;
            drainT0 = now;
            drainKind = kind;
        }
    }

    /**
     * 0.36.a8 (MINING_PLAN 3.6): the facts the IRON rung's option scorer reads, read once per pick (not per tick): the
     * nearest known iron with its faces, depth, water and policy verdict (Mining.aaOres), the nearest known cave, strip
     * preconditions, the camp furnace's distance and the bag. Null (the planner's chain runs as it is) when nothing can
     * be read; never throws.
     */
    IronOptions.Facts ironFacts() {
        try {
            Minecraft mc = Minecraft.getInstance();
            LocalPlayer p = mc.player;
            if (p == null || mc.level == null || sensed == null) return null;
            int[] me = Jobs.here(p);
            String dim = io.github.mojolowjo.entropybot.guard.Guard.dimOf(mc.level);
            boolean fair = io.github.mojolowjo.entropybot.sense.Seen.fair();
            long t0 = System.nanoTime();
            List<IronOptions.Ore> ores = Mining.get().aaOres(p, IronOptions.FAMILY, IRON_ORES, fair ? 2 : 0);
            long t1 = System.nanoTime();
            var cave = Core.INSTANCE.caves.nearest(dim, me[0], me[1], me[2]);
            int caveDist = cave == null ? -1 : (int) Math.round(Math.sqrt(Jobs.distSq(me, new int[]{cave.ex, cave.ey, cave.ez})));
            if (caveDist > io.github.mojolowjo.entropybot.cave.Caves.PICK_WITHIN) caveDist = -1;      // review: "mine cave" picks only within 64, else it starts a new cave here
            Map<String, Integer> bag = sensed.feas != null ? sensed.feas.bag : Map.of();
            boolean mineMarked = sensed.feas != null && sensed.feas.mineMarked;
            String stripWhy = !mineMarked ? "no mine marked (place mine)" : !sensed.areasHere ? "outside my areas" : null;
            IronOptions.Fair fairFacts = null;
            if (fair) {         // AA_ROUND3 3: seen ores only, discovery priced
                java.util.Set<String> seenKeys = new java.util.HashSet<>();
                var sn = io.github.mojolowjo.entropybot.sense.ChunkPass.INSTANCE.seen();
                for (IronOptions.Ore o : ores) if (sn != null && sn.has(dim, o.x(), o.y(), o.z())) seenKeys.add(o.key());
                List<String> dd = new ArrayList<>();
                var opts = io.github.mojolowjo.entropybot.aa.brain.Discovery.rank(discoveryFacts(dim, me, IronOptions.FAMILY, caveDist, mineMarked, stripWhy, false), IronOptions.FAMILY, 3, dd);
                fairFacts = new IronOptions.Fair(seenKeys, opts, dd);
            }
            long t2 = System.nanoTime();
            int[] furnace = furnaceMemo.get(gameTick(), System.currentTimeMillis(), dim + " " + me[0] + " " + me[1] + " " + me[2] + " " + bb.in.furnaceKnown + " " + bb.in.furnaceInBag + " " + bag.getOrDefault("furnace", 0), () -> c.crafting.findFurnace(p));     // AA_ROUND3 4: 1-3 ms a read
            factsParts = "ores " + (t1 - t0) / 1000 + " us [" + Mining.aaOresParts + "], cave+discovery " + (t2 - t1) / 1000 + " us, furnace " + (System.nanoTime() - t2) / 1000 + " us";
            int furnaceDist = furnace == null ? -1 : (int) Math.round(Math.sqrt(Jobs.distSq(me, furnace)));
            return new IronOptions.Facts(ores, me[1], p.isInWater(), AaEscape.throwaway(bag),
                    io.github.mojolowjo.entropybot.guard.PolicyRuntime.INSTANCE.travelMayPlace(), caveDist, stripWhy == null, stripWhy,
                    furnaceDist, bag.getOrDefault("furnace", 0) > 0, bag.getOrDefault("crafting_table", 0) > 0,
                    bag.getOrDefault("cobblestone", 0) + bag.getOrDefault("cobbled_deepslate", 0), io.github.mojolowjo.entropybot.aa.brain.ToolWear.kit(bb.in), fairFacts,
                    litterSite(dim, me, ores.size()), bag.getOrDefault("raw_iron", 0));
        } catch (RuntimeException e) {
            LOG.warn("[entropybot] aa: iron facts: {}", e.toString());
            return null;
        }
    }

    /**
     * aa-litter: what the price of a scaffold needs about this spot, read with the iron facts: the distance to the nearest
     * area edge or protect box (the known builds and places), my pickaxe tier, the return leg and the cave sighting's
     * confidence x the ore still known (the revisits), and whether I stand in my own camp (MAIN) box. Never throws.
     */
    io.github.mojolowjo.entropybot.guard.Litter.Site litterSite(String dim, int[] me, int oreCount) {
        try {
            var pol = Core.INSTANCE.guard.core.basePolicy();
            List<io.github.mojolowjo.entropybot.guard.Box> boxes = new ArrayList<>();
            if (pol != null) {
                boxes.addAll(pol.areas);
                boxes.addAll(pol.protect);
            }
            int near = io.github.mojolowjo.entropybot.guard.Litter.nearestBoxGap(boxes, dim, me[0], me[2]);
            boolean own = pol != null && pol.typeAt(dim, me[0], me[1], me[2]) == io.github.mojolowjo.entropybot.guard.AreaType.MAIN;
            var sg = io.github.mojolowjo.entropybot.sense.ChunkPass.INSTANCE.sightings();
            var cave = sg == null ? null : sg.nearestCave(dim, me[0], me[1], me[2], io.github.mojolowjo.entropybot.aa.brain.Discovery.SEEN_CAVE_WITHIN);
            return new io.github.mojolowjo.entropybot.guard.Litter.Site(near, bb.in.pickTier, 3, 0, cave == null ? 0 : cave.conf(), Math.max(0, oreCount - 1), 0, own);
        } catch (RuntimeException e) {
            LOG.debug("[entropybot] aa: litter site: {}", e.toString());
            return null;
        }
    }

    /**
     * AA_ROUND3 3: the discovery facts for a family: the known cave, the cave yield (Seen), the mine or my areas, the best
     * band and its density (OreStats, this dimension), my pickaxe's seconds per stone block.
     */
    private io.github.mojolowjo.entropybot.aa.brain.Discovery.Facts discoveryFacts(String dim, int[] me, String fam, int caveDist, boolean mineMarked, String stripWhy, boolean ownerGoal) {
        var pass = io.github.mojolowjo.entropybot.sense.ChunkPass.INSTANCE;
        var st = pass.stats();
        var sg = pass.sightings();
        var sn = sg == null ? null : sg.ore();
        // AA_ROUND3 5: the nearest cave opening I have seen (not exhausted) and the learned chance to see one per 100 blocks
        var seenCave = sg == null ? null : sg.nearestCave(dim, me[0], me[1], me[2], io.github.mojolowjo.entropybot.aa.brain.Discovery.SEEN_CAVE_WITHIN);
        var r = st == null ? null : st.range(dim, fam);
        int bestY = r == null ? Integer.MIN_VALUE : r.peakY();
        double density = r == null ? -1 : st.density(dim, fam, io.github.mojolowjo.entropybot.aa.brain.Discovery.stripY(
                new io.github.mojolowjo.entropybot.aa.brain.Discovery.Facts(caveDist, 0, mineMarked, sensed.areasHere, stripWhy, me[0], me[1], me[2], bestY, -1, 1, ownerGoal)));
        var kit = io.github.mojolowjo.entropybot.aa.brain.ToolWear.kit(bb.in);
        double dig = io.github.mojolowjo.entropybot.aa.brain.ToolWear.withTool(kit == null ? io.github.mojolowjo.entropybot.aa.brain.ToolWear.STONE_SPEED : kit.speed());
        int down = me[1] - io.github.mojolowjo.entropybot.aa.brain.Discovery.stripY(
                new io.github.mojolowjo.entropybot.aa.brain.Discovery.Facts(caveDist, 0, mineMarked, sensed.areasHere, stripWhy, me[0], me[1], me[2], bestY, -1, 1, ownerGoal));
        var lv = Minecraft.getInstance().level;
        var downs = mineMarked || !sensed.areasHere || down <= 0 || lv == null ? null : downHeadings(lv, me, down);
        return new io.github.mojolowjo.entropybot.aa.brain.Discovery.Facts(caveDist, sn == null ? io.github.mojolowjo.entropybot.sense.Seen.PRIOR_RATE : sn.caveRate(fam),
                mineMarked, sensed.areasHere, stripWhy, me[0], me[1], me[2], bestY, density, dig, ownerGoal, downs,
                seenCave == null ? null : new int[]{seenCave.x(), seenCave.y(), seenCave.z()}, sg == null ? io.github.mojolowjo.entropybot.sense.Sightings.PRIOR_CAVES : sg.cavesPer100(),
                bb.in.basePos == null ? -1 : Math.sqrt(Jobs.distSq(me, bb.in.basePos)),
                io.github.mojolowjo.entropybot.aa.brain.Unit.blocks(io.github.mojolowjo.entropybot.aa.brain.Brain.horizon(bb)), bb.in.dayTime);
    }

    /** AA_ROUND3 4: the staircase steps down whose cells are read per heading (the pick-time budget). */
    static final int STAIR_READ = 16;

    private List<io.github.mojolowjo.entropybot.aa.WayUp.Heading> downsCache;
    private final io.github.mojolowjo.entropybot.aa.FactsMemo<int[]> furnaceMemo = new io.github.mojolowjo.entropybot.aa.FactsMemo<>(10_000);
    private String downsKey;
    private long downsAt = -1, downsTtl = 10_000;

    /**
     * AA_ROUND3 4: the staircase down per heading (StairPlan.cells, the 3 cells a step digs): every column checked
     * against my areas (all {@code n} steps: the bottom must stay in them, place mine marks it there), the cells of the
     * first {@link #STAIR_READ} steps read for the policy and hazards (cellAt: about 20 us a cell, so 4 x 16 x 3 keeps the pick under ~8 ms; the rest is priced as plain stone by Discovery). Cached 10 s for the same spot and depth.
     */
    private List<io.github.mojolowjo.entropybot.aa.WayUp.Heading> downHeadings(net.minecraft.world.level.Level lv, int[] me, int n) {
        long now = System.currentTimeMillis();
        String key = io.github.mojolowjo.entropybot.guard.Guard.dimOf(lv) + " " + me[0] + " " + me[1] + " " + me[2] + " " + n;
        if (downsCache != null && key.equals(downsKey) && now - downsAt < downsTtl) return downsCache;
        List<io.github.mojolowjo.entropybot.aa.WayUp.Heading> out = new java.util.ArrayList<>();
        for (String d : io.github.mojolowjo.entropybot.aa.WayUp.DIRS) {
            int[] u = io.github.mojolowjo.entropybot.aa.WayUp.dir(d);
            int[] outside = null;
            for (int i = 1; i <= n && outside == null; i++) {
                int x = me[0] + u[0] * i, y = me[1] - i, z = me[2] + u[1] * i;
                if (!Clearing.boxInAreas(io.github.mojolowjo.entropybot.clear.ClearBox.of(x, y, z, x, y + 2, z))) outside = new int[]{x, y, z};
            }
            List<io.github.mojolowjo.entropybot.aa.Replan.Cell> cells = new java.util.ArrayList<>();
            if (outside == null)
                for (int[] c : StairPlan.cells(me[0], me[1], me[2], u[0], u[1], Math.min(n, STAIR_READ))) {
                    var cell = cellAt(lv, c[0], c[1], c[2]);
                    cells.add(cell);
                    if (Double.isInfinite(cell.mult())) {         // dropped anyway (WayUp.ground): no more reads, say where
                        LOG.debug("[entropybot] aa: stairs down {} stops at {} {} {} (may not be broken or not loaded)", d, c[0], c[1], c[2]);
                        break;
                    }
                }
            out.add(new io.github.mojolowjo.entropybot.aa.WayUp.Heading(d, n, cells, outside));
        }
        // review: a heading stopped by an unbreakable or unloaded cell is kept 2 s only (chunks load, policy edits)
        boolean stopped = false;
        for (var h : out) for (var cl : h.cells()) if (Double.isInfinite(cl.mult())) stopped = true;
        downsCache = out;
        downsTtl = stopped ? 2_000 : 10_000;
        downsKey = key;
        downsAt = now;
        return out;
    }

    private long caveTickAt = -1;

    /** AA_ROUND3 3: minutes of caving (a "mine cave" chain runs) feed Seen's cave yield; new sightings meanwhile count. */
    private void caveTick(long now) {
        var sn = io.github.mojolowjo.entropybot.sense.ChunkPass.INSTANCE.seen();
        if (sn == null) return;
        boolean caving = currentChain != null && current >= 0 && currentChain.contains("mine cave");
        if (caving && sn.caving && caveTickAt >= 0) sn.caveTime((now - caveTickAt) / 60_000.0);
        sn.caving = caving;
        caveTickAt = now;
    }

    /** aa fair on|off (saved): seen ore only (default) or today's behaviour (the whole chunk index). */
    String fair(String arg) {
        String a = arg == null ? "" : arg.trim().toLowerCase(java.util.Locale.ROOT);
        var sn = io.github.mojolowjo.entropybot.sense.ChunkPass.INSTANCE.seen();
        String state = "fair " + (io.github.mojolowjo.entropybot.sense.Seen.fair() ? "on" : "off") + " - " + io.github.mojolowjo.entropybot.sense.ChunkPass.INSTANCE.seenStatus()
                + (sn == null ? "" : "; " + sn.caveText()) + "; " + io.github.mojolowjo.entropybot.sense.ChunkPass.INSTANCE.sightingsText();
        if (a.isEmpty()) return state;
        if (!a.equals("on") && !a.equals("off")) return "usage: aa fair on|off (" + state + ")";
        io.github.mojolowjo.entropybot.sense.Seen.fair(a.equals("on"));
        data().addProperty("fair", a.equals("on"));
        knownDue = true;                  // review: the goals' known counts and discovery refresh at once
        c.saved();
        LOG.info("[entropybot] aa: fair {}", a);
        return "ok: aa fair " + a + (a.equals("on") ? " - I go only for ore I have seen (24 blocks, an open face, in view); else I cave or strip mine"
                : " - every ore the chunk index knows counts again (today's behaviour)");
    }

    /**
     * S2 (MINING_PLAN 11): the SURFACE rung's facts, read once per pick: depth (or the hole's way out), the open shaft,
     * throwaway blocks, placing allowed, a pickaxe, the trail back to open sky (TrailRuntime.retrace), the nearest
     * standable open-sky cell within {@link #SKY_R} (heightmap scan of loaded columns), and whether the next rung is the
     * table/furnace one. Null when nothing can be read; a throw is caught by the picker (logged, counted).
     */
    io.github.mojolowjo.entropybot.aa.RungOptions.SurfaceFacts surfaceFacts() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null || sensed == null) return null;
        net.minecraft.world.level.Level lv = mc.level;
        int[] me = Jobs.here(p);
        int depth = Jobs.underDepth(lv, me[0], me[1], me[2]);
        if (depth == 0) depth = Math.max(0, holeUp(lv, me));
        boolean shaft = depth > 0;
        for (int y = me[1] + 2; shaft && y <= me[1] + depth; y++) if (!openCell(lv, me[0], y, me[2])) shaft = false;
        Map<String, Integer> bag = sensed.feas != null ? sensed.feas.bag : Map.of();
        String dim = io.github.mojolowjo.entropybot.guard.Guard.dimOf(lv);
        int[] trailEnd = null;
        double trailLen = 0;
        List<int[]> hops = io.github.mojolowjo.entropybot.trail.TrailRuntime.INSTANCE.trail().retrace(dim, me[0], me[1], me[2], 8, 8, 400);
        if (hops != null && !hops.isEmpty()) {
            int[] prev = me;
            for (int[] h : hops) {
                trailLen += Math.sqrt(Jobs.distSq(prev, h));
                prev = h;
            }
            trailEnd = hops.get(hops.size() - 1);
        }
        long t0 = System.nanoTime();
        int[] sky = null;
        double best = Double.MAX_VALUE;
        net.minecraft.core.BlockPos.MutableBlockPos m = new net.minecraft.core.BlockPos.MutableBlockPos();
        for (int x = me[0] - SKY_R; x <= me[0] + SKY_R; x++)
            for (int z = me[2] - SKY_R; z <= me[2] + SKY_R; z++) {
                m.set(x, 0, z);
                if (!lv.isLoaded(m)) continue;
                int y = lv.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                double d = Math.sqrt(Jobs.distSq(me, new int[]{x, y, z}));
                m.set(x, y, z);
                if (d >= best || !lv.canSeeSky(m) || !openCell(lv, x, y, z) || !openCell(lv, x, y + 1, z)) continue;     // review: no roof (the Nether)
                m.set(x, y - 1, z);
                var below = lv.getBlockState(m);
                if (below.getCollisionShape(lv, m).isEmpty() || !below.getFluidState().isEmpty()) continue;
                best = d;
                sky = new int[]{x, y, z};
            }
        factsParts = "sky scan " + (System.nanoTime() - t0) / 1000 + " us";
        boolean tf = !(bb.in.tableKnown || bb.in.tableInBag) || !(bb.in.furnaceKnown || bb.in.furnaceInBag);
        return new io.github.mojolowjo.entropybot.aa.RungOptions.SurfaceFacts(depth, me, shaft, AaEscape.throwaway(bag),
                io.github.mojolowjo.entropybot.guard.PolicyRuntime.INSTANCE.travelMayPlace(), bb.in.pickaxes > 0,
                trailEnd, trailLen, sky, sky == null ? -1 : best, tf, io.github.mojolowjo.entropybot.aa.brain.ToolWear.kit(bb.in),
                depth > 0 ? column(lv, me, depth) : null, depth > 0 ? headings(lv, me, depth) : null);
    }

    /** The open-sky cell search radius (columns), S2. */
    static final int SKY_R = 24;

    /** S2: the TOOLS rung's facts (the pickaxe tier, trees within 16 as the planner sees them). */
    io.github.mojolowjo.entropybot.aa.RungOptions.ToolsFacts toolsFacts() {
        if (sensed == null || sensed.feas == null) return null;
        return new io.github.mojolowjo.entropybot.aa.RungOptions.ToolsFacts(bb.in.pickTier, sensed.feas.trees);
    }

    /** The nearest known iron positions the scorer weighs. */
    static final int IRON_ORES = 8;

    /** The IRON rung's options line ("aa why"), or null. */
    String ironOptions() {
        for (var r : AaLadder.rungs(tree.ladder)) if (r.kind == TaskKind.IRON) return r.options();
        return null;
    }

    // ---- S0: options lines, counters and the owner's manual driving (AaManual) ----

    /** One "options <rung>: <line>" per rung with a picker (aa why). */
    List<String> optionLines() {
        List<String> out = new ArrayList<>();
        if (tree.brain.planText() != null) out.add(tree.brain.planText());          // AA_ROUND3 2: the sequencer's plan
        for (var r : AaLadder.rungs(tree.ladder)) if (r.picker() != null) out.add(r.id.substring(6) + " options: " + r.options());
        out.addAll(io.github.mojolowjo.entropybot.aa.brain.Costs.lines(bb));      // aa-costs: the cost terms
        out.addAll(tree.brain.heldLines(bb.clock.now()));                          // AA_ROUND2 3: the failure holds
        return out;
    }

    /** Per-rung picker counters and the manual state (aa status); "!!" first when a facts supplier failed in the last minute. */
    String optionStatus() {
        long now = System.currentTimeMillis();
        StringBuilder b = new StringBuilder();
        boolean red = false;
        for (var r : AaLadder.rungs(tree.ladder)) {
            if (r.picker() == null) continue;
            red |= r.picker().factsFailedSince(now - 60_000);
            String k = r.picker().counters(now);
            if (k != null) b.append('\n').append(r.id.substring(6)).append(": ").append(k);
        }
        return (red ? "!! a facts supplier failed in the last minute (see the log: aa: <RUNG> facts:)\n" : "") + io.github.mojolowjo.entropybot.aa.AaManual.of(bb).describe(now) + b
                + "\n" + replanLog.text(replanOn()) + (runningOpt == null ? "" : "; running " + runningOpt.id() + (runningOpt.ret().leg() == io.github.mojolowjo.entropybot.aa.Replan.Leg.NONE ? "" : ", " + runningOpt.ret().text()));
    }

    /** The rung AA works on now (the ladder's running rung), else the one that picked last; null none. */
    private String currentRung() {
        Node n = tree.slot.runningChild();
        if (n == tree.brain && tree.brain.runningChild() instanceof io.github.mojolowjo.entropybot.aa.Rung rr) return rr.id;
        // review: the last picker only while it still runs (a stale one would preempt an unrelated task)
        Object o = bb.fact(io.github.mojolowjo.entropybot.aa.Rung.CURRENT_RUNG);
        for (var r : AaLadder.rungs(tree.ladder)) if (r.id.equals(o) && r.last() == Status.RUNNING) return r.id;
        return null;
    }

    /** AA's own needs/idle task (not the owner's, not the AI's) is halted so the ladder chooses again now. */
    private boolean preemptOwnTask(String why) {
        Node n = tree.slot.runningChild();
        if (n == null || n.id.equals("owner") || n.id.equals("ai")) return false;
        tree.slot.preempt(why);
        LOG.info("[entropybot] aa: {}", why);
        return true;
    }

    /** aa ask on|off | pick n | do need | skip need|none | use option. */
    String manual(String verb, String arg) {
        var man = io.github.mojolowjo.entropybot.aa.AaManual.of(bb);
        String a = arg == null ? "" : arg.trim();
        switch (verb) {
            case "ask" -> {
                if (!a.equals("on") && !a.equals("off")) return "usage: aa ask on|off (now " + (man.ask ? "on" : "off") + ")";
                man.ask = a.equals("on");
                if (!man.ask) man.pending = null;
                LOG.info("[entropybot] aa: ask {}", a);
                return "ok: aa ask " + a + (man.ask ? " - before each task I show my options and wait " + io.github.mojolowjo.entropybot.aa.AaManual.ASK_MS / 1000 + " s for aa pick <n>, then take my first" : "");
            }
            case "pick" -> {
                if (man.pending == null) return "nothing to pick: no rung is asking now (aa ask on makes them ask)";
                int n;
                try { n = Integer.parseInt(a); } catch (NumberFormatException e) { n = -1; }
                if (n < 1 || n > man.pending.rows().size()) return "usage: aa pick 1.." + man.pending.rows().size() + " - " + io.github.mojolowjo.entropybot.aa.AaManual.rowsText(man.pending.rows());
                man.pick = n;
                return "ok: " + man.pending.rung() + " runs " + n + ") " + man.pending.rows().get(n - 1).label();
            }
            case "do" -> {
                if (a.equals("none")) { man.doRung = null; return "ok: aa do cleared"; }
                String id = io.github.mojolowjo.entropybot.aa.AaManual.rungId(a);
                if (id == null) return "usage: aa do <need>|none - needs: " + String.join(", ", io.github.mojolowjo.entropybot.aa.AaManual.NEEDS);
                man.doRung = id;
                man.doSince = bb.clock.now();
                man.skipped.remove(id);
                boolean pre = preemptOwnTask("aa do " + a + ": my task stops, " + id + " runs now");
                return "ok: " + id + " runs now" + (pre ? " (my task stopped)" : "") + "; the other rungs wait until it ends";
            }
            case "skip" -> {
                if (a.equals("none") || a.isEmpty()) {
                    String was = String.join(", ", man.skipped);
                    man.skipped.clear();
                    return a.isEmpty() && !was.isEmpty() ? "skipped this run: " + was + " (aa skip none clears)" : "ok: nothing skipped" + (was.isEmpty() ? "" : " (was: " + was + ")");
                }
                String id = io.github.mojolowjo.entropybot.aa.AaManual.rungId(a);
                if (id == null) return "usage: aa skip <need>|none - needs: " + String.join(", ", io.github.mojolowjo.entropybot.aa.AaManual.NEEDS);
                man.skipped.add(id);
                if (id.equals(man.doRung)) man.doRung = null;
                boolean pre = id.equals(currentRung()) && preemptOwnTask("aa skip " + a + ": its task stops");
                return "ok: " + id + " left out this run" + (pre ? " (its task stopped)" : "") + "; aa skip none brings it back";
            }
            case "use" -> {
                if (a.isEmpty()) return "usage: aa use <option> (an option of the current rung, as aa why names it, e.g. pillar, smelt here)";
                String rung = currentRung();
                man.use = a.toLowerCase(java.util.Locale.ROOT);
                man.useRung = rung;
                io.github.mojolowjo.entropybot.aa.Rung r = null;
                for (var x : AaLadder.rungs(tree.ladder)) if (x.id.equals(rung)) r = x;
                if (r != null && r.picker() == null) {
                    man.use = null;
                    man.useRung = null;
                    return rung + " has one option (its plan): nothing to choose";
                }
                boolean pre = rung != null && preemptOwnTask("aa use " + a + ": " + rung + " picks again");
                return "ok: " + (rung == null ? "the next rung that picks" : rung) + " runs \"" + a + "\" if it is a ranked option" + (pre ? " (its task stopped, picking again)" : "")
                        + (r != null ? "; options now: " + r.options() : "");
            }
            default -> { return AaCommands.USAGE; }
        }
    }

    // ---- the switch (brainData "aa": {on, since, heldUntil, shelterBuilt}) ----

    JsonObject data() {
        JsonObject root = c.brainData();
        if (!root.has("aa") || !root.get("aa").isJsonObject()) root.add("aa", new JsonObject());
        return root.getAsJsonObject("aa");
    }

    public boolean on() {
        JsonObject d = data();
        return d.has("on") && d.get("on").getAsBoolean();
    }

    String turnOn() {
        if (c.brainRuntime != null && c.brainRuntime.brain.on()) return "brain is on - brain off first (aa and brain exclude each other)";
        JsonObject d = data();
        d.addProperty("on", true);
        d.addProperty("since", System.currentTimeMillis());
        d.remove("heldUntil");
        offReason = null;
        heldUntil = 0;
        c.saved();
        // 0.36.1 (review 1): assist and escort end now (their ticks are off under AA; they could start and cancel jobs)
        String ended = "";
        LocalPlayer p = Minecraft.getInstance().player;
        if (c.assist.on() && p != null) {
            c.assist.end(p);
            ended += "; assist ended";
        }
        if (Core.INSTANCE.reflexes.escort.active()) {
            Core.INSTANCE.reflexes.escort.stop("aa on");
            c.endEscortFollow();
            ended += "; escort ended";
        }
        int queued = c.vocab.queued.size();
        if (queued > 0) ended += "; " + queued + " queued task(s) run as your orders when my slot is free";
        runStartMs = System.currentTimeMillis();
        runPicks = runStuckHalts = runDeaths = 0;
        runDead = false;
        runMarks = new io.github.mojolowjo.entropybot.aa.brain.RunLedger.Marks();
        runSwitches0 = tracer.counts.switches;
        runPointless0 = tracer.counts.pointless;
        runPreempts0 = tracer.counts.preempts;
        io.github.mojolowjo.entropybot.clear.OreDetour.valuer = this::oreBlockValue;     // small-3: the ore detour is valued while AA runs
        LOG.info("[entropybot] aa: on{}", ended);
        LOG.info("[entropybot] aa: ready ({} desires)", tree.brain.lastScored().size());
        return "ok: aa on - danger > your orders > AI requests > basic needs > idle; one task at a time; aa status says who drives" + ended;
    }

    /** small-3: seconds of value in one mined block of this ore (OreValue), from the tech tree and the open goals. */
    private double oreBlockValue(String oreId) {
        java.util.Map<String, Integer> goals = new java.util.HashMap<>();
        for (var g : io.github.mojolowjo.entropybot.aa.brain.Goals.of(bb)) for (String k : g.r.counts()) goals.merge(k, g.n, Math::max);
        var tree = bb.fact(io.github.mojolowjo.entropybot.aa.AaDesires.TECH_FACT) instanceof io.github.mojolowjo.entropybot.aa.brain.TechTree tt ? tt : null;
        var have = bb.fact(io.github.mojolowjo.entropybot.aa.AaDesires.HAVE_FACT) instanceof java.util.Set<?> s ? (java.util.Set<String>) s : java.util.Set.<String>of();
        return io.github.mojolowjo.entropybot.aa.brain.OreValue.perBlock(oreId, tree, have, goals, bb.in.bag == null ? java.util.Map.of() : bb.in.bag);
    }

    // aa-look: Baritone's freeLook (default true) turns the head server-side only, so the camera looked away from the walk
    private Boolean savedFreeLook;
    private Double savedRandomLooking, savedRandomLooking113;
    private Boolean savedSmoothLook;
    private Integer savedSmoothTicks;

    /** While AA drives the camera faces where the bot walks and mines (freeLook false); the old value is kept for lookRestore. */
    void lookApply() {
        try {
            var fl = baritone.api.BaritoneAPI.getSettings().freeLook;
            if (savedFreeLook == null) {
                savedFreeLook = fl.value;
                LOG.info("[entropybot] aa: camera follows the walk (freeLook {} -> false)", savedFreeLook);
            }
            if (fl.value) fl.value = false;
            // smooth: no per-tick aim jitter, head eases toward the target instead of snapping (smoothLookTicks stays default)
            var st = baritone.api.BaritoneAPI.getSettings();
            if (savedRandomLooking == null) {
                savedRandomLooking = st.randomLooking.value;
                savedRandomLooking113 = st.randomLooking113.value;
                savedSmoothLook = st.smoothLook.value;
                LOG.info("[entropybot] aa: smooth camera (randomLooking {} -> 0, randomLooking113 {} -> 0, smoothLook {} -> true)",
                        savedRandomLooking, savedRandomLooking113, savedSmoothLook);
            }
            if (st.randomLooking.value != 0d) st.randomLooking.value = 0d;
            if (st.randomLooking113.value != 0d) st.randomLooking113.value = 0d;
            if (!st.smoothLook.value) st.smoothLook.value = true;
            if (savedSmoothTicks == null) savedSmoothTicks = st.smoothLookTicks.value;
            int lt = data().has("lookTicks") ? data().get("lookTicks").getAsInt() : LOOK_TICKS_DEFAULT;
            if (st.smoothLookTicks.value != lt) st.smoothLookTicks.value = lt;
        } catch (RuntimeException | LinkageError e) {
            LOG.warn("[entropybot] aa: freeLook not set: {}", e.toString());
        }
    }

    /** Puts freeLook back to the value from before AA (aa off, world left). */
    void lookRestore() {
        if (savedFreeLook == null) return;
        try {
            var st = baritone.api.BaritoneAPI.getSettings();
            st.freeLook.value = savedFreeLook;
            if (savedRandomLooking != null) {
                st.randomLooking.value = savedRandomLooking;
                st.randomLooking113.value = savedRandomLooking113;
                st.smoothLook.value = savedSmoothLook;
                if (savedSmoothTicks != null) st.smoothLookTicks.value = savedSmoothTicks;
            }
            LOG.info("[entropybot] aa: freeLook restored to {}, randomLooking {}/{} smoothLook {}", savedFreeLook, savedRandomLooking, savedRandomLooking113, savedSmoothLook);
        } catch (RuntimeException | LinkageError e) {
            LOG.warn("[entropybot] aa: freeLook not restored: {}", e.toString());
        }
        savedFreeLook = null;
        savedRandomLooking = null;
        savedRandomLooking113 = null;
        savedSmoothLook = null;
        savedSmoothTicks = null;
    }

    String turnOff(String why) {
        lookRestore();
        io.github.mojolowjo.entropybot.clear.OreDetour.valuer = null;
        appendRun();
        JsonObject d = data();
        d.addProperty("on", false);
        c.saved();
        String stopped = null;
        if (bb.task() != null) {
            stopped = bb.task().spec().chain();
        }
        tree.root.halt("aa off: " + why);
        releaseSwim(Minecraft.getInstance());
        if (chainRunning()) c.chains.clear();
        // 0.36.5 (review 6): nothing of this run carries into the next aa on (queued AI requests, the last task's state)
        int dropped = bb.drop("ai");
        bb.fact(io.github.mojolowjo.entropybot.aa.AaManual.FACT, null);     // S0: ask/do/skip/use are per run
        clearEscape();
        current = -1;
        lastEnd = null;
        myChain = CHAIN;
        progress = 0;
        lastBag = -1;
        LOG.info("[entropybot] aa: off ({}){}", why, dropped > 0 ? "; " + dropped + " AI request(s) dropped" : "");
        return "ok: aa off" + (stopped != null ? " - stopped my task " + stopped : "") + "; your jobs run on";
    }

    /** The owner's "stop": AA's task ends (justified), the slot waits 2 min or until the next owner order. */
    String ownerStopped() {
        if (!on()) return "";
        if (bb.task() != null || tree.slot.runningChild() != null) tree.root.halt("the owner's stop");
        heldUntil = System.currentTimeMillis() + HOLD_MS;
        data().addProperty("heldUntil", heldUntil);
        c.saved();
        return "; aa waits " + HOLD_MS / 60000 + " min (aa on, or your next order, to go on now)";
    }

    /** AA's chain runs (its task). */
    boolean chainRunning() { return c.chains.running() && (CHAIN.equals(c.chains.name()) || myChain.equals(c.chains.name())); }

    /** 0.36.3: the chain name of AA's task: "aa", or "climb" (climb runs as a chain of its own, never inside one). */
    private String myChain = CHAIN;
    private boolean swimming;

    /** Lets go of jump if AA holds it (aa off, death, hold, task change); leaves it when the air reflex holds it. */
    private void releaseSwim(Minecraft mc) {
        if (!swimming) return;
        swimming = false;
        if (mc != null && !Core.INSTANCE.reflexes.airSwimming()) mc.options.keyJump.setDown(false);
        LOG.info("[entropybot] aa: swim-up released (aa stopped or paused)");
    }

    /** The owner's order while AA's needs/idle task runs: the task is preempted (logged), the order runs (AA_PLAN 4). */
    void yieldToOwner(String verb) {
        if (!on() || !chainRunning()) return;
        if (tree.preemptForOwner(bb, verb)) {
            heldUntil = 0;
            LOG.info("[entropybot] aa: steps aside for the owner's {}", verb);
        }
    }

    /** The AI operator's job-starting command while AA's task runs: queued as a request (AA_PLAN 4). */
    String postAi(String cmd) {
        if (!on()) return null;
        aiRequests++;
        bb.post(new Request("ai", TaskKind.AI, cmd, 1, System.currentTimeMillis(), 0, "the AI asked: " + cmd));
        LOG.info("[entropybot] aa: AI request #{} queued: {}", aiRequests, cmd);
        return "queued as AI request #" + aiRequests + " (runs when my task ends; " + bb.requests().size() + " waiting)";
    }

    /** The AI's stop: its own queued requests go, and its running task (never the owner's or a need). */
    String cancelAi() {
        int n = bb.drop("ai");
        boolean running = bb.task() != null && "ai".equals(bb.task().source());
        if (running) tree.slot.preempt("the AI's stop");
        return "ok: " + n + " AI request(s) dropped" + (running ? ", the AI's task stopped" : "");
    }

    // ---- the tick ----

    /** Every 5 client ticks from Commands.tick, in a world. Never throws. */
    void tick(long tick) {
        if (!on()) return;
        lookApply();
        long t0 = System.nanoTime();
        try {
            Minecraft mc = Minecraft.getInstance();
            LocalPlayer p = mc.player;
            if (p == null || mc.level == null) {
                appendRun();                // the world unloaded: the run ends here
                return;
            }
            long now = System.currentTimeMillis();
            if (c.brainRuntime != null && c.brainRuntime.brain.on()) {     // 0.36.5 (review 1): both saved on (an old save): AA wins
                c.brainRuntime.brain.command("off");
                LOG.info("[entropybot] aa: the brain was on next to aa - brain off");
            }
            if (p.isDeadOrDying()) {
                if (!runDead) {
                    runDead = true;
                    runDeaths++;
                    io.github.mojolowjo.entropybot.aa.brain.Kit.died(bb, bb.clock.now());     // aa-kit: bb.in still holds the last live pos and bag
                }
                releaseSwim(mc);
                if (bb.task() != null) {
                    tree.root.halt("died");
                    c.chains.clear();
                }
                return;
            }
            if (sensedTick < 0 || tick - sensedTick >= 10) {
                sensed = c.brainRuntime.sense();
                sensedTick = tick;
            }
            escapeTick(p, mc);
            fill(bb.in, sensed, p, mc);
            runDead = false;
            if (runStartMs >= 0) runMarks.tick(now - runStartMs, bb.in.pickTier, bb.in.tableKnown || bb.in.tableInBag, bb.in.furnaceKnown || bb.in.furnaceInBag, bb.in.food);
            drainTick(now);
            conserveTick(now);
            progressTick(p);
            brainFacts(mc, now);
            goalsTick(p, tick);
            caveTick(now);
            if (now < heldUntil) {
                releaseSwim(mc);
                if (bb.in.otherJob == null) {
                    tracer.beginTick();
                    snapshot(now, "held");
                    return;
                }
                heldUntil = 0;        // the owner's order lifts the hold
            }
            // 0.36.1 (review 1): the owner's queue ("queue <task>") is an owner order: the next queued line starts
            // when the slot is free and is adopted as the owner's job on the next tick
            if (bb.in.otherJob == null && bb.task() == null && !c.vocab.queued.isEmpty() && (tick % 20) == 2) {
                c.vocab.tick();
                return;
            }
            io.github.mojolowjo.entropybot.chop.Unreachable.world(Core.INSTANCE.files() == null ? "" : Core.INSTANCE.files().worldRoot().toString());
            Status s = tree.tick(bb);
            swimTick(p, now);
            replanTick(p, now);
            for (var r : AaLadder.rungs(tree.ladder)) if (r.id.equals("needs.woodArea") && r.last() == Status.SUCCESS) bb.fact(AaLadder.TREE_AREA_FACT, null);
            // 0.36.2 (review 6): while danger drives, AA's chain starts no next step and no retry (the pause ended the
            // running one); held a little past AA's next tick, so it goes on by itself once the guard lets go
            if ("danger".equals(tree.driver()) && chainRunning()) c.chains.holdFor(10);
            // 0.36.4 (aa40d: AA paused the iron job for "drowning" 10+ min while nothing moved the bot): under water with
            // air short and danger driving, AA holds jump (swim up) until the air is full again or the danger ends
            // 0.36.a1: not while the air reflex holds jump itself (one owner of the key; it also pauses Baritone)
            // 0.36.a2: only for the air danger; a hostile/lava/creeper danger leaves jump to Baritone and the fight reflex
            boolean otherDanger = bb.in.danger >= 2 || bb.in.inLavaOrFire || bb.in.creeperClose;
            boolean swim = io.github.mojolowjo.entropybot.aa.AaSwim.hold("danger".equals(tree.driver()), p.isInWater(),
                    p.getAirSupply(), p.getMaxAirSupply(), Core.INSTANCE.reflexes.airSwimming(), otherDanger, swimming);
            if (swim != swimming) {
                if (swim || !Core.INSTANCE.reflexes.airSwimming()) mc.options.keyJump.setDown(swim);
                swimming = swim;
                LOG.info(swim ? "[entropybot] aa: under water with air {} - swimming up"
                        : "[entropybot] aa: swim-up released (air {}, driver {})", p.getAirSupply(), tree.driver());
            }
            if (tree.ladder.last() == Status.SUCCESS && !data().has(AaLadder.SHELTER_FACT) && bb.flag(AaLadder.SHELTER_FACT)) {
                data().addProperty(AaLadder.SHELTER_FACT, true);
                c.saved();
            }
            if (s == Status.FAILURE && tracer.counts.ticks % 240 == 1) LOG.info("[entropybot] aa: nothing to do: {}", tree.root.reason());
            snapshot(now, tree.driver());
        } catch (Throwable e) {
            tickFailed(e);
        } finally {
            tracer.counts.tickUs = (System.nanoTime() - t0) / 1000;
        }
    }

    private void tickFailed(Throwable e) {
        long now = System.currentTimeMillis();
        errors++;
        lastError = e.toString();
        if (now - minuteStart > 60_000) {
            minuteStart = now;
            errorsThisMinute = 0;
        }
        errorsThisMinute++;
        if (errors <= 3 || now - lastErrorAt > 60_000) {
            lastErrorAt = now;
            LOG.warn("[entropybot] aa: tick failed: {}", errors <= 3 ? stack(e) : e.toString());
        }
        if (errorsThisMinute >= ERRORS_PER_MINUTE_OFF) {
            offReason = "turned itself off after " + errorsThisMinute + " tick errors in a minute: " + e;
            turnOff(offReason);
            c.whisper(c.owner(), "aa: " + offReason);
        }
    }

    private static String stack(Throwable e) {
        StringBuilder b = new StringBuilder(e.toString());
        int n = 0;
        for (StackTraceElement s : e.getStackTrace()) {
            if (n++ >= 6) break;
            b.append("\n    at ").append(s);
        }
        return b.toString();
    }

    /** The fixed inputs (AA_PLAN 7) from the sense snapshot plus a few direct reads. */
    private boolean sheepLatch;
    private final io.github.mojolowjo.entropybot.aa.brain.Costs.ToolSmoother toolSmoother = new io.github.mojolowjo.entropybot.aa.brain.Costs.ToolSmoother();

    private void fill(Inputs in, BrainState s, LocalPlayer p, Minecraft mc) {
        in.inWorld = s.inWorld;
        in.health = p.getHealth();
        in.maxHealth = p.getMaxHealth();
        in.food = p.getFoodData().getFoodLevel();
        in.saturation = p.getFoodData().getSaturationLevel();
        in.hurtRecently = p.hurtTime > 0;
        Reflexes.Reflex r = Core.INSTANCE.reflexes.reflex();
        in.danger = r == Reflexes.Reflex.FIGHTING || r == Reflexes.Reflex.FLEEING || r == Reflexes.Reflex.RETREATING ? 2 : Core.INSTANCE.reflexes.hold() ? 1 : 0;
        in.dangerWhy = in.danger > 0 ? r.name().toLowerCase() : "";
        in.creeperClose = creeperClose();
        in.air = p.getAirSupply();
        in.inLavaOrFire = p.isInLava() || p.isOnFire();
        in.freeSlots = s.freeSlots;
        in.foodItems = s.foodItems;
        in.torches = s.torches;
        in.wood = s.woodItems;
        in.coal = s.coal;
        Map<String, Integer> bag = s.feas.bag;
        in.cobble = bag.getOrDefault("cobblestone", 0) + bag.getOrDefault("cobbled_deepslate", 0);
        in.ironIngots = bag.getOrDefault("iron_ingot", 0);
        in.wool = 0;
        for (Map.Entry<String, Integer> e : bag.entrySet()) if (e.getKey().endsWith("_wool")) in.wool += e.getValue();
        in.string = bag.getOrDefault("string", 0);
        in.bag = bag;
        if (mc.level != null) in.dimension = mc.level.dimension().location().toString();
        if (!sheepLatch && mc.level != null && !mc.level.getEntitiesOfClass(net.minecraft.world.entity.animal.Sheep.class, p.getBoundingBox().inflate(48)).isEmpty()) sheepLatch = true;
        if (!sheepLatch && mc.level != null) {           // AA_ROUND3 5: a sheep herd remembered (Sightings) is a wool source too
            var sg = io.github.mojolowjo.entropybot.sense.ChunkPass.INSTANCE.sightings();
            String sd = io.github.mojolowjo.entropybot.sense.ChunkPass.INSTANCE.dim();
            if (sg != null && sd != null && !sg.animals(sd, "minecraft:sheep").isEmpty()) sheepLatch = true;
        }
        in.sheepSeen = sheepLatch;
        // aa-costs: the tier smoothed (a drop counts after 4 s held) in place of the tools score's old cap at 74
        in.pickTier = toolSmoother.update(s.feas.known ? s.feas.pickTier : (s.pickaxes > 0 ? 1 : 0), System.currentTimeMillis());
        in.pickPct = s.pickaxes == 0 ? 0 : s.pickPct;
        in.pickaxes = s.pickaxes;
        in.pickSpeed = s.pickSpeed;
        in.pickLeft = s.pickDurability;
        in.bedKnown = s.bedNear;
        in.bedInBag = s.bedInBag;
        // 0.36.a6: a table/furnace seen at the camp or base counts wherever the bot is (AaLadder.campLatch, saved)
        double camp = -1;
        for (int[] m : new int[][]{s.campPos, s.basePos}) {
            if (m == null) continue;
            double d = Math.sqrt(Jobs.distSq(s.botPos, m));
            camp = camp < 0 ? d : Math.min(camp, d);
        }
        boolean tLatch = latch("tableAtCamp", s.feas.table || io.github.mojolowjo.entropybot.craft.OwnTable.takeCampTable(), camp), fLatch = latch("furnaceAtCamp", s.feas.furnace, camp);
        in.tableKnown = s.feas.table || tLatch;
        in.furnaceKnown = s.feas.furnace || fLatch;
        in.tableInBag = bag.getOrDefault("crafting_table", 0) > 0;
        in.planks = 0;
        for (Map.Entry<String, Integer> e : bag.entrySet()) if (e.getKey().endsWith("_planks")) in.planks += e.getValue();
        in.furnaceInBag = bag.getOrDefault("furnace", 0) > 0;
        in.baseChests = s.feas.baseChests;
        in.baseKnown = s.basePos != null;
        in.basePos = s.basePos;
        in.campPos = s.campPos;
        in.shelterBuilt = bb.flag(AaLadder.SHELTER_FACT);
        in.areaKnown = false;
        in.areaName = null;
        for (String n : c.areaNames()) {
            JsonObject a = c.policyArea(n);
            if (a == null || PolicyCommands.typeOf(a) == io.github.mojolowjo.entropybot.guard.AreaType.SAFE) continue;
            in.areaKnown = true;
            if (in.areaName == null && houseFits(a)) in.areaName = n;
        }
        in.dayTime = s.dayTime;
        in.night = s.night;
        in.thunder = s.thundering;
        in.underGround = !s.skyAbove;
        double[] cg = io.github.mojolowjo.entropybot.aa.brain.Goals.coalGoal(bb);
        in.coalGoalScore = cg[0];
        in.coalGoalN = (int) cg[1];
        if (in.coalGoalN > 0) io.github.mojolowjo.entropybot.aa.brain.Costs.note(bb, "coal", in.torchCoalTerms());
        in.difficulty = mc.level.getDifficulty().getId();
        in.light = mc.level.getBrightness(net.minecraft.world.level.LightLayer.BLOCK, p.blockPosition());
        // aa-night3 (normal2: 4 of 7 deaths shot by skeletons): a ranged mob hits from range, so it counts twice in the danger
        in.hostilesNear = mc.level.getEntitiesOfClass(net.minecraft.world.entity.monster.Monster.class, p.getBoundingBox().inflate(24)).size()
                + mc.level.getEntitiesOfClass(net.minecraft.world.entity.monster.AbstractSkeleton.class, p.getBoundingBox().inflate(24)).size();
        in.sheltered = ShelterJob.waitingAt(mc.player == null ? null : Jobs.here(mc.player));
        in.armed = io.github.mojolowjo.entropybot.engine.Hostility.weaponHit(p) >= io.github.mojolowjo.entropybot.threat.FightOrFlee.ARMED;
        // aa-night2 P0 2: a zombie seen once stays seen (sticky while the game runs)
        if (!in.zombieSeen) in.zombieSeen = !mc.level.getEntitiesOfClass(net.minecraft.world.entity.monster.Zombie.class, p.getBoundingBox().inflate(24)).isEmpty();
        in.armorPoints = mc.player == null ? 0 : mc.player.getArmorValue();
        int[] me = Jobs.here(p);
        in.underDepth = Jobs.underDepth(mc.level, me[0], me[1], me[2]);
        in.pos = me;
        fillChestCell(in, mc, me);
        in.inArea = s.areasHere;
        io.github.mojolowjo.entropybot.aa.core.WayBack.observe(bb);
        in.ownerOnline = s.ownerOnline;
        in.hungerDrains = mc.level.getDifficulty() != net.minecraft.world.Difficulty.PEACEFUL;
        in.menuOpen = mc.screen != null && !chainRunning() && !c.jobs.running();
        in.parked = s.parked;
        in.otherJob = otherJob();
        in.ownerOrderPending = in.otherJob != null;
        in.aiRequestPending = bb.peek("ai") != null;
        fillOwner(in, s, p, mc);
    }

    /**
     * aa-r1fix: the camp chest's cell, only while the camp needs one (table and furnace stand, no base chest yet): the free cells
     * around the base (else me), inside my areas, ranked by walk and distance from the table (ChestSpot); refused cells are skipped.
     */
    private void fillChestCell(Inputs in, Minecraft mc, int[] me) {
        if (in.baseChests) {
            in.chestCell = null;
            in.chestWhy = null;
            in.chestFailed.clear();
            return;
        }
        if (mc.level == null || !(in.tableKnown || in.tableInBag) || !(in.furnaceKnown || in.furnaceInBag)) return;
        try {
            var lv = mc.level;
            String dim = io.github.mojolowjo.entropybot.guard.Guard.dimOf(lv);
            int[] anchor = in.basePos != null ? in.basePos : me;
            int[] table = Crafting.findBlockAround(anchor, "crafting_table", 8, 3);
            var ranked = io.github.mojolowjo.entropybot.aa.ChestSpot.rank(anchor, table, me,
                    cc -> {
                        var b = new net.minecraft.core.BlockPos(cc[0], cc[1], cc[2]);
                        return lv.isLoaded(b) && CampCommands.placeable(lv, b) && lv.getBlockState(b.above()).canBeReplaced();
                    },
                    cc -> !c.fenceOn() || c.inAreas(dim, cc[0], cc[2]));
            var pick = io.github.mojolowjo.entropybot.aa.ChestSpot.pick(ranked, in.chestFailed);
            in.chestCell = pick == null ? null : pick.cell();
            in.chestWhy = pick == null ? "no free cell within " + io.github.mojolowjo.entropybot.aa.ChestSpot.RADIUS + " of the "
                    + (in.basePos != null ? "base" : "bot") + " (" + in.chestFailed.size() + " refused)"
                    : "chest cell " + pick.key() + ": " + pick.why() + " (" + ranked.size() + " free, " + in.chestFailed.size() + " refused)";
        } catch (RuntimeException e) {
            in.chestCell = null;
            in.chestWhy = "chest cell: " + e;
        }
    }

    /**
     * B6 (2.11): the owner as the help desire sees them. Position: their player entity when loaded (same dimension), else
     * the companion's position; activity and last placed block from the companion report (fresh, same name and dimension).
     * Loader notes: vanilla client classes only (level players/entities, raw brightness).
     */
    private void fillOwner(Inputs in, BrainState s, LocalPlayer p, Minecraft mc) {
        in.ownerDist = -1;
        in.ownerHostiles = 0;
        in.ownerAct = null;
        in.ownerPlaced = null;
        in.ownerDark = false;
        in.assistOn = c.assist.on();
        if (mc.level == null) return;
        try {
            String owner = c.owner();
            net.minecraft.world.phys.Vec3 at = null;
            for (net.minecraft.world.entity.player.Player pl : mc.level.players())
                if (pl != p && owner != null && pl.getGameProfile().getName().equalsIgnoreCase(owner)) at = pl.position();
            if (at == null && s.ownerKnown()) at = new net.minecraft.world.phys.Vec3(s.ownerPos[0] + 0.5, s.ownerPos[1], s.ownerPos[2] + 0.5);
            if (at == null) return;
            in.ownerDist = (int) Math.round(Math.hypot(at.x - p.getX(), at.z - p.getZ()));
            net.minecraft.core.BlockPos bp = net.minecraft.core.BlockPos.containing(at);
            if (mc.level.isLoaded(bp)) {
                in.ownerDark = mc.level.getMaxLocalRawBrightness(bp) < 8;
                in.ownerHostiles = mc.level.getEntitiesOfClass(net.minecraft.world.entity.monster.Monster.class,
                        new net.minecraft.world.phys.AABB(bp).inflate(12), net.minecraft.world.entity.LivingEntity::isAlive).size();
            }
            io.github.mojolowjo.entropybot.assist.AssistRules.Report r = c.assist.report();
            long now = System.currentTimeMillis();
            if (r != null && owner != null && r.name().equalsIgnoreCase(owner) && now - r.received() <= io.github.mojolowjo.entropybot.assist.AssistRules.FRESH_MS
                    && r.dim().equals(mc.level.dimension().location().toString())) {
                in.ownerAct = io.github.mojolowjo.entropybot.assist.AssistRules.guess(r, now).word();
                in.ownerPlaced = r.placed() == null || now - r.placed().at() > io.github.mojolowjo.entropybot.assist.AssistRules.RECENT_MS ? null : r.placed().id();
            }
        } catch (RuntimeException e) {
            if (!ownerReadWarned) LOG.warn("[entropybot] aa help: owner read failed (help stays off; said once): {}", e.toString());
            ownerReadWarned = true;
        }
    }

    private boolean ownerReadWarned;

    /** A camp latch from brainData "aa" (AaLadder.campLatch); saved and logged once when it changes. */
    private boolean latch(String key, boolean seen, double distToCamp) {
        JsonObject d = data();
        boolean was = d.has(key) && d.get(key).getAsBoolean();
        boolean now = AaLadder.campLatch(was, seen, distToCamp);
        if (now != was) {
            d.addProperty(key, now);
            c.saved();
            LOG.info("[entropybot] aa: {} {}", key, now ? "noted (seen within " + AaLadder.CAMP_R + " of the camp/base): it counts away from the camp too" : "dropped (no camp or base known)");
        }
        return now;
    }

    /** 0.36.1 (review 6): the reflexes' target is a creeper within 10 (Reflexes.CREEPER_OVERRIDE), or a creeper duel runs. */
    private boolean creeperClose() {
        try {
            JsonObject r = Core.INSTANCE.reflexes.status();
            if (r.has("duel")) return true;
            if (!r.has("target")) return false;
            String t = r.get("target").getAsString();
            return t != null && t.contains("creeper") && (!r.has("dist") || r.get("dist").getAsDouble() <= 10);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** {@code house} takes a box of 7x7 up to 4096 blocks (CampCommands.house); a round area or a bigger box does not fit. */
    private static boolean houseFits(JsonObject a) {
        try {
            if (a.has("round")) return false;
            long w = Math.abs(PolicyCommands.n(a, "x2") - PolicyCommands.n(a, "x1")) + 1, d = Math.abs(PolicyCommands.n(a, "z2") - PolicyCommands.n(a, "z1")) + 1;
            return w >= 7 && d >= 7 && w * d <= 4096;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Something that is not AA's runs: the owner's chain, a job, a gather, a hunt, a carry, a request; else null. */
    private String otherJob() {
        if (escapePending || escapeWay != null) return null;       // 0.36.a6: AA's own way out (pillar job / climb chain)
        if (c.chains.running()) return chainRunning() ? null : "chain " + c.chains.name();
        if (c.jobs.running()) return c.jobs.job.type + " (" + c.jobs.job.status + ")";
        if (c.gathering.running()) return "gather";
        if (c.hunting.running()) return "hunt";
        if (c.mule.running()) return "carry";
        if (c.requests.busy()) return "a request";
        return null;
    }

    /**
     * The activity counter (0.36.1, review 7: task-specific first): a chain step done, the job's status text changing
     * (it carries "N broken", "picking up"...), items gained, a walk's distance to its goal shrinking; the position only
     * as a fallback, and then only a move of 8+ blocks from the last counted spot (standing and shuffling is no progress).
     */
    private void progressTick(LocalPlayer p) {
        String step = chainRunning() ? c.chains.stepText() : null;
        if (step != null && !step.equals(lastStep)) progress++;
        lastStep = step;
        String js = c.jobs.running() ? c.jobs.job.status : null;
        if (js != null && !js.equals(lastJobStatus)) progress++;
        lastJobStatus = js;
        // AA_ROUND3 1 finding 4: putting back blocks stands still; each placement (or try) counts as progress
        RestoreRun rr = c.jobs.running() ? c.jobs.job.restoreRun : null;
        int rw = rr == null ? -1 : rr.placed;      // review: only placements (a retried block is no progress)
        progress = io.github.mojolowjo.entropybot.aa.core.RestoreProgress.tick(progress, lastRestoreWork, rw);
        lastRestoreWork = rw;
        int bagTotal = 0;
        for (int i = 0; i < 36; i++) bagTotal += p.getInventory().getItem(i).getCount();
        if (lastBag >= 0 && bagTotal > lastBag) progress++;          // gained (a deposit's drop is no progress by itself)
        // aa-night2 P0 3 (normal run: BASE_BED "stuck 40 s, no move, no bag change" while placing): a build's placed
        // blocks leave the bag, and that is its progress
        else if (lastBag >= 0 && bagTotal < lastBag && io.github.mojolowjo.entropybot.aa.core.BuildProgress.counts(startedKind)) progress++;
        lastBag = bagTotal;
        int[] pos = Jobs.here(p);
        int[] dest = c.jobs.running() ? Jobs.travelDest(c.jobs.job) : null;
        if (dest != null) {
            double d = Math.abs(dest[0] - pos[0]) + Math.abs(dest[1] - pos[1]) + Math.abs(dest[2] - pos[2]);
            if (lastGoalDist >= 0 && d < lastGoalDist - 1) progress++;
            lastGoalDist = d;
        } else lastGoalDist = -1;
        if (lastPos == null || Math.abs(pos[0] - lastPos[0]) + Math.abs(pos[1] - lastPos[1]) + Math.abs(pos[2] - lastPos[2]) >= 8) {
            if (lastPos != null) progress++;
            lastPos = pos;
        }
    }

    private double lastGoalDist = -1;
    /** The kind of the task AA started last (its progress rules differ: a build spends blocks). */
    private TaskKind startedKind;

    // ---- JobPort ----

    private String refusal;

    @Override public long start(TaskSpec t) {
        refusal = null;
        if (c.chains.running()) { refusal = "busy: " + c.chains.chainStatus(); return -1; }
        if (c.jobs.running()) { refusal = "busy: " + c.jobs.job.status; return -1; }      // 0.36.1: any job, as otherJob() sees it
        if (c.gathering.running()) { refusal = "busy: " + c.gathering.statusText(); return -1; }
        if (c.hunting.running()) { refusal = "busy: a hunt runs"; return -1; }
        if (c.mule.running()) { refusal = "busy: " + c.mule.statusText(); return -1; }
        if (c.requests.busy()) { refusal = "busy: a request is open"; return -1; }
        lastEnd = null;
        myChain = CHAIN;
        startedKind = t.kind();
        clearEscape();
        escapes = 0;
        runningOpt = null;
        pendingReturn = null;
        returnDone = null;
        if (AaLadder.CLIMB.equals(t.chain())) {
            // 0.36.3: climb plans its own staircase and runs it as its own chain "climb"; "ok: I'm not in a hole" = done
            LocalPlayer p = Minecraft.getInstance().player;
            String cr = p == null ? "error: no player" : DigCommands.climb(c, p, "", c.owner());
            if (cr != null && cr.startsWith("ok")) {
                current = ++seq;
                currentChain = t.chain();
                lastEnd = "done - " + cr;
                return current;
            }
            if (cr == null || !cr.startsWith("started")) {
                refusal = cr == null ? "error: no answer" : cr;
                return -1;
            }
            myChain = "climb";
            current = ++seq;
            currentChain = t.chain();
            return current;
        }
        io.github.mojolowjo.entropybot.aa.Options.Option opt = optionFor(t.chain());
        String head = opt == null ? t.chain() : chainFrom(opt, 0);
        String r = c.chains.startChain(c.owner(), CHAIN, head, 1);
        if (r == null || !r.startsWith("started")) {
            refusal = r == null ? "error: no answer" : r;
            runningOpt = null;
            pendingReturn = null;
            return -1;
        }
        current = ++seq;
        currentChain = head;
        return current;
    }

    // ---- aa-replan: the stored return leg and the rolling re-score (aa/Replan) ----

    /** The option AA's task runs (null: not an option's chain), the step its chain started at, the stored way back. */
    private io.github.mojolowjo.entropybot.aa.Options.Option runningOpt;
    private int stepOffset;
    /** Where the option's chain started (the walk-back estimate at the ore step's end). */
    private int[] startPos;
    /** The steps after the ore step, held while the return leg runs ("" none); null: no return leg pending. */
    private String pendingReturn, returnDone;
    private final io.github.mojolowjo.entropybot.aa.Replan.Timer replanTimer = new io.github.mojolowjo.entropybot.aa.Replan.Timer();
    final io.github.mojolowjo.entropybot.aa.Replan.Log replanLog = new io.github.mojolowjo.entropybot.aa.Replan.Log();
    private long lastSwitchAt = -1, lastWanderAt = -1;
    /** aa-water (1f): the swim watch; a re-score forced on entering the water. */
    private final io.github.mojolowjo.entropybot.aa.SwimWatch swimWatch = new io.github.mojolowjo.entropybot.aa.SwimWatch();
    private boolean wetReplan;

    /** aa replan on|off (saved; default on on this branch). */
    boolean replanOn() {
        JsonObject d = data();
        return !d.has("replan") || d.get("replan").getAsBoolean();
    }

    static final int LOOK_TICKS_DEFAULT = 10;      // Baritone's own default is 5

    /** "aa look [smooth <ticks>]": shows / sets how many ticks a head turn takes (smoothLookTicks, 1..60), saved. */
    String look(String arg) {
        String a = arg == null ? "" : arg.trim();
        int now = data().has("lookTicks") ? data().get("lookTicks").getAsInt() : LOOK_TICKS_DEFAULT;
        String live;
        try {
            live = String.valueOf(baritone.api.BaritoneAPI.getSettings().smoothLookTicks.value);
        } catch (RuntimeException | LinkageError e) {
            live = "?";
        }
        if (a.isEmpty()) return "aa look: smooth " + now + " ticks per turn (Baritone now " + live + ", default " + LOOK_TICKS_DEFAULT + "); set with: aa look smooth <ticks>";
        String[] w = a.split("\\s+");
        int s;
        try {
            if (w.length != 2 || !w[0].equals("smooth")) throw new NumberFormatException();
            s = Integer.parseInt(w[1]);
        } catch (NumberFormatException e) {
            return "usage: aa look [smooth <ticks>] (1..60, now " + now + ")";
        }
        if (s < 1 || s > 60) return "usage: aa look smooth <ticks> (1..60, now " + now + ")";
        data().addProperty("lookTicks", s);
        c.saved();
        if (on()) lookApply();
        return "ok: aa look smooth " + s + " ticks (was " + now + ")";
    }

    /** "aa horizon [seconds]": the sequencer's horizon (default 600; 30..7200), saved. */
    String horizon(String arg) {
        String a = arg == null ? "" : arg.trim();
        int now = io.github.mojolowjo.entropybot.aa.brain.Brain.horizon(bb);
        if (a.isEmpty()) return "aa horizon " + now + " s (plans of up to 3 desires that start within it); " + (tree.brain.planText() == null ? "no plan yet" : tree.brain.planText());
        int s;
        try {
            s = Integer.parseInt(a.replaceAll("(?i)s$", ""));
        } catch (NumberFormatException e) {
            return "usage: aa horizon <seconds> (30..7200, now " + now + ")";
        }
        if (s < 30 || s > 7200) return "usage: aa horizon <seconds> (30..7200, now " + now + ")";
        data().addProperty("horizon", s);
        c.saved();
        bb.fact(io.github.mojolowjo.entropybot.aa.brain.Brain.HORIZON_FACT, s);
        return "ok: aa horizon " + s + " s (was " + now + ")";
    }

    private int[] coalAt;
    private long coalAtMs = -1;

    /** The nearest known coal ore (Mining.aaOres, family coal), cached 10 s; null none or unreadable (logged). */
    int[] coalSite() {
        long now = System.currentTimeMillis();
        if (coalAtMs >= 0 && now - coalAtMs < 10_000) return coalAt;
        coalAtMs = now;
        try {
            LocalPlayer p = Minecraft.getInstance().player;
            List<IronOptions.Ore> ores = p == null ? List.of() : Mining.get().aaOres(p, "coal", 1);
            coalAt = ores.isEmpty() ? null : new int[]{ores.get(0).x(), ores.get(0).y(), ores.get(0).z()};
        } catch (RuntimeException e) {
            LOG.warn("[entropybot] aa: coal site: {}", e.toString());
            coalAt = null;
        }
        return coalAt;
    }

    String replan(String arg) {
        String a = arg == null ? "" : arg.trim();
        if (!a.equals("on") && !a.equals("off")) return "usage: aa replan on|off (now " + (replanOn() ? "on" : "off") + "); " + replanLog.text(replanOn());
        data().addProperty("replan", a.equals("on"));
        c.saved();
        return "ok: replan " + a + (a.equals("on") ? " (re-score at each step and every 5 s on a long step; switch at 30% and 3 s cheaper)" : " (the chosen option runs to its end)");
    }

    /** The picked option whose command is this chain (a rung's picker chose it just now), or null. */
    private io.github.mojolowjo.entropybot.aa.Options.Option optionFor(String chain) {
        for (var r : AaLadder.rungs(tree.ladder))
            if (r.picker() instanceof io.github.mojolowjo.entropybot.aa.Options.Picker<?> op && op.ran() != null && op.ran().command().equals(chain)) {
                runningOpt = op.ran();
                LocalPlayer pl = Minecraft.getInstance().player;
                startPos = pl == null ? null : Jobs.here(pl);
                stepOffset = 0;
                replanTimer.reset();
                return runningOpt;
            }
        runningOpt = null;
        return null;
    }

    private io.github.mojolowjo.entropybot.aa.Options.Picker<?> pickerOf(io.github.mojolowjo.entropybot.aa.Options.Option o) {
        for (var r : AaLadder.rungs(tree.ladder))
            if (r.picker() instanceof io.github.mojolowjo.entropybot.aa.Options.Picker<?> op && op.ran() == o) return op;
        return null;
    }

    /**
     * The option's steps from {@code from}: with a pillar way back after its ore step, only up to that step (the rest
     * waits in {@link #pendingReturn} and runs after the return leg).
     */
    private String chainFrom(io.github.mojolowjo.entropybot.aa.Options.Option o, int from) {
        List<String> steps = io.github.mojolowjo.entropybot.aa.Options.Chains.split(o.command());
        stepOffset = from;
        pendingReturn = null;
        var ret = o.ret();
        int a = ret.afterStep();
        // aa-replan 2: any stored leg is only the scoring estimate; the chain stops after the ore step and the real way
        // back is priced where that step ends (startReturn)
        if (ret.leg() != io.github.mojolowjo.entropybot.aa.Replan.Leg.NONE && a >= from && a < steps.size()) {
            pendingReturn = String.join(" then ", steps.subList(a + 1, steps.size()));
            return String.join(" then ", steps.subList(from, a + 1));
        }
        return String.join(" then ", steps.subList(Math.min(from, steps.size()), steps.size()));
    }

    /** The ore step is done and a pillar way back is stored: check the shaft and the blocks again, pillar or walk. */
    private void startReturn(LocalPlayer p, Minecraft mc) {
        escapeOpt = null;
        try {
            int[] me = Jobs.here(p);
            net.minecraft.world.level.Level lv = mc.level;
            int under = Jobs.underDepth(lv, me[0], me[1], me[2]), hole = holeUp(lv, me);
            boolean out = under == 0 && hole == 0;
            // AA_ROUND3 1 (aa388): the levels are the hole's step-out level (holeExit), never "the first cell that sees the
            // sky" (every cell of a 1-wide shaft does); with ground overhead the scan to the first open sky cell
            int levels = out ? 0 : io.github.mojolowjo.entropybot.aa.WayUp.returnLevels(under, hole, skyLevels(lv, me));
            int top = me[1] + levels;
            List<io.github.mojolowjo.entropybot.aa.Replan.Cell> col = column(lv, me, levels);
            double back = startPos == null ? 0 : Math.sqrt(Math.pow(startPos[0] - me[0], 2) + Math.pow(startPos[2] - me[2], 2));
            double walk = io.github.mojolowjo.entropybot.aa.Replan.walkCost(levels, back);
            Map<String, Integer> bag = sensed != null && sensed.feas != null ? sensed.feas.bag : Map.of();
            int tw = AaEscape.throwaway(bag);
            var way = io.github.mojolowjo.entropybot.aa.Replan.chooseNow(col, levels, tw, walk, io.github.mojolowjo.entropybot.aa.brain.ToolWear.kit(bb.in));
            String why = way.why() + (runningOpt == null ? "" : "; planned " + runningOpt.ret().text());
            LOG.info("[entropybot] aa: way back at {}: {} - {}", Jobs.fmt(me), way.leg().name().toLowerCase(), why);
            var leg = way.leg();
            if (leg == io.github.mojolowjo.entropybot.aa.Replan.Leg.PILLAR || leg == io.github.mojolowjo.entropybot.aa.Replan.Leg.TUNNEL) {
                int[] e = {me[0], top, me[2]};
                String r = leg == io.github.mojolowjo.entropybot.aa.Replan.Leg.PILLAR ? PaperPlan.INSTANCE.pillar(c, e, c.owner()) : PaperPlan.INSTANCE.tunnelUp(c, e, c.owner());
                if (r != null && !r.startsWith("error") && !r.startsWith("busy") && c.jobs.running()) {
                    escapeWay = "pillar";
                    escapeJob = c.jobs.job;
                    return;
                }
                LOG.info("[entropybot] aa: the {} back did not start ({}) - walking", leg.name().toLowerCase(), r);
            }
            escapeResumeNow("way back: " + why);
        } catch (RuntimeException e) {
            LOG.warn("[entropybot] aa: way back failed: {}", e.toString());
            escapeResumeNow("way back error, walking: " + e);
        }
    }

    /**
     * aa-replan: at each step boundary, and every 5 s on a step older than 15 s, the running option's rung re-scores
     * its options from here; a switch (30% and 3 s cheaper, not within 20 s of the last, not mid dig-down or pillar)
     * stops the running step and starts the other option from the step where they differ. Logged with both scores.
     */
    /**
     * aa-water (to-do 1f): on entering the water the running option is re-scored at once; in the water with no progress
     * for 20 s the running step ends "no progress while swimming 20 s", which the option's rung records against that
     * option (Failures) and answers with the next one.
     */
    private void swimTick(LocalPlayer p, long now) {
        boolean running = runningOpt != null && chainRunning() && CHAIN.equals(myChain);
        var ev = swimWatch.tick(p.isInWater(), running, progress, now);
        if (ev == io.github.mojolowjo.entropybot.aa.SwimWatch.Event.ENTERED) {
            wetReplan = true;
            LOG.info("[entropybot] aa: in the water during {} - re-scoring", runningOpt.id());
        } else if (ev == io.github.mojolowjo.entropybot.aa.SwimWatch.Event.STALLED && c.jobs.running()) {
            LOG.info("[entropybot] aa: {} during {} (air {}) - that option failed", io.github.mojolowjo.entropybot.aa.SwimWatch.REASON, runningOpt.id(), p.getAirSupply());
            c.jobs.finish(io.github.mojolowjo.entropybot.aa.SwimWatch.REASON);
        }
    }
    private void replanTick(LocalPlayer p, long now) {
        if (current < 0) { replanTimer.reset(); wetReplan = false; return; }
        if (!replanOn() || runningOpt == null || !chainRunning() || !CHAIN.equals(myChain) || escapePending || escapeWay != null
                || "danger".equals(tree.driver())) return;
        String st = c.chains.stepText();
        int[] me = Jobs.here(p);
        String body = io.github.mojolowjo.entropybot.aa.Replan.stepBody(st);
        // aa-replan 2 (aa381): the ore step went cave to cave far from the planned target: re-plan (every 5 s while so)
        boolean wandered = body != null && body.trim().toLowerCase().startsWith("mine ")
                && io.github.mojolowjo.entropybot.aa.Replan.wandered(io.github.mojolowjo.entropybot.aa.Replan.targetOf(runningOpt.id()), me);
        boolean due = replanTimer.due(st, now);
        if (!due && wandered && now - lastWanderAt >= io.github.mojolowjo.entropybot.aa.Replan.EVERY_MS) due = true;
        if (wetReplan) { wetReplan = false; due = true; }       // aa-water: in the water now: the water ways price it
        if (!due) return;
        if (wandered) lastWanderAt = now;
        var op = pickerOf(runningOpt);
        if (op == null) return;
        var fresh = op.rescore(bb);
        if (fresh == null) return;
        replanLog.checks++;
        int idx = stepOffset + io.github.mojolowjo.entropybot.aa.Replan.stepIndex(st);
        boolean mid = io.github.mojolowjo.entropybot.aa.Replan.digOrPillarStep(body, me)
                && (body.trim().toLowerCase().startsWith("goto") || bb.in.underDepth > 0 || bb.in.underGround);
        var d = io.github.mojolowjo.entropybot.aa.Replan.decide(runningOpt, fresh.ranked(), idx, now, lastSwitchAt, mid, wandered);
        if (wandered) LOG.warn("[entropybot] aa: bug signal: replan check, wandered from {} to {}: {}", runningOpt.id(), Jobs.fmt(me), d.why());
        if (!d.switches()) {
            if (d.why().contains("locked") || d.why().contains("mid dig")) replanLog.held++;
            return;
        }
        String from = runningOpt.id();
        String line = "replan: " + d.why() + " at step " + (idx + 1) + " (" + body + ") -> " + d.to().id();
        LOG.info("[entropybot] aa: {}", line);
        tracer.decision(now, "replan", bb.task() == null ? TaskKind.NONE : bb.task().kind, d.to().command(), line);
        c.chains.clear();                    // first: a step's end then reaches no chain (clear fires no end)
        if (c.jobs.running()) c.jobs.finish("stopped: aa replan");
        if (c.gathering.running()) c.gathering.stop("stopped: aa replan");
        if (c.hunting.running()) c.hunting.stop("stopped: aa replan");
        String head = chainFrom(d.to(), d.from());
        String r = c.chains.startChain(c.owner(), CHAIN, head, 1);
        op.adopt(fresh, d.to());
        runningOpt = d.to();
        lastSwitchAt = now;
        replanTimer.reset();
        replanLog.switches++;
        replanLog.add(new java.text.SimpleDateFormat("HH:mm:ss").format(new java.util.Date(now)) + " " + from + " " + Math.round(d.cur()) + " -> " + d.to().id() + " " + Math.round(d.alt()));
        if (r == null || !r.startsWith("started")) {
            lastEnd = "error: the replan switch to " + d.to().id() + " did not start: " + r;
            return;
        }
        currentChain = head;
    }

    @Override public String lastRefusal() { return refusal; }

    @Override public JobState poll(long taskId) {
        if (taskId != current) return JobState.gone("an older task");
        if (lastEnd != null) {
            String e = lastEnd;
            lastEnd = null;
            current = -1;
            // aa-prereq (aa391: "done - mining coal: got 0 of 32 - no coal ore known yet" in 3 s counted done): a task
            // that gained nothing of what it was sent for is a failure (Failures holds it), never a success
            if (e.startsWith("done") && io.github.mojolowjo.entropybot.aa.AaLadder.zeroGain(e)) {
                LOG.info("[entropybot] aa: the task ended \"done\" with nothing gained - counted failed: {}", e);
                return JobState.failed("failed: nothing gained - " + e.substring(4).replaceFirst("^\\s*-\\s*", ""));
            }
            return e.startsWith("done") ? JobState.done(e) : JobState.failed(e);
        }
        if (escapePending || escapeWay != null) return JobState.RUNNING;      // 0.36.a6: getting out, then the task goes on
        if (!chainRunning()) {
            current = -1;
            String who = c.chains.running() ? "chain " + c.chains.name() : c.jobs.running() ? "job " + c.jobs.job.type : "someone (stop or a clear)";
            return JobState.gone(who);
        }
        return JobState.RUNNING;
    }

    @Override public void cancel(long taskId, String why) {
        if (taskId != current) return;
        current = -1;
        clearEscape();
        if (chainRunning()) c.chains.clear();
        if (c.jobs.running()) c.jobs.finish("stopped: aa " + why);
        if (c.gathering.running()) c.gathering.stop("stopped: aa " + why);
        if (c.hunting.running()) c.hunting.stop("stopped: aa " + why);
        if (c.mule.running()) c.mule.stop("stopped: aa " + why);
    }

    @Override public long progress(long taskId) { return progress; }

    /**
     * 0.36.1 (review 6): a real pause: the chain's running step ends as "interrupted: low health ..." so Chains retries
     * that step (up to 3 times) once the reflexes are calm and health is back (Chains.stepChain's fight retry); the chain
     * itself stays, so the task is still RUNNING for AA. Resume needs nothing: the chain's own retry clock goes on.
     */
    @Override public void pause(long taskId, String why) {
        if (taskId != current || !chainRunning()) return;
        if (c.jobs.running()) c.jobs.finish("interrupted: low health or danger - aa paused the step (" + why + ")");
        if (c.gathering.running()) c.gathering.stop("interrupted: low health or danger - aa paused (" + why + ")");
    }

    @Override public String resolve(String chain) {
        if (chain == null || !chain.startsWith("goal ")) return chain;
        return c.planning.chainFor(chain.substring(5));
    }

    /** S0 (MINING_PLAN 10.1): the planner's tri-state; a text that is not a goal is READY as it is. */
    @Override public Resolved resolveState(String chain) {
        if (chain == null || !chain.startsWith("goal ")) return chain == null ? Resolved.none("nothing to run") : Resolved.ready(chain, 0);
        return c.planning.chainState(chain.substring(5));
    }

    /** 0.36.a8: a routine name (the planner's goal_...) or chain text as its plain steps, for the option scorer. */
    @Override public String expand(String chain) {
        try {
            return String.join(" then ", c.chains.expandSteps(Texts.splitChain(chain), 0));
        } catch (RuntimeException e) {
            return chain;
        }
    }

    @Override public String infeasible(String chain) {
        if (sensed == null || !sensed.feas.known) return null;
        try {
            Feasibility.Verdict v = Feasibility.check(routineSteps(chain), sensed);
            if (!v.infeasible()) return null;
            // 0.36.1: a saved goal routine the facts no longer support is deleted, so the planner plans the goal afresh
            // next time (GoalPlanning keeps a saved routine otherwise; the hook cache forgets it within a minute)
            // 0.36.2: only the planner's own text (RoutineRule OVERWRITE); a routine the owner edited is theirs, kept
            if (chain.startsWith("goal_") && c.chains.isRoutine(chain)
                    && RoutineRule.decide(c.planning.routineText(chain), c.planning.plannedText(chain)) == RoutineRule.Decision.OVERWRITE) {
                String r = c.chains.routineCommand("delete " + chain);
                LOG.info("[entropybot] aa: routine {} dropped as infeasible ({}): {}", chain, v.missing(), r);
            }
            return "infeasible: " + v.missing() + (v.stepText() == null ? "" : " for " + v.stepText());
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** A routine name -> its steps joined with "then" (Feasibility reads the steps). */
    private String routineSteps(String chain) {
        if (c.chains.isRoutine(chain)) return c.chains.routines().get(chain.toLowerCase()).getAsString();
        return chain;
    }

    @Override public void log(String line) {
        if (line.startsWith("brain picked ")) runPicks++;
        LOG.info("[entropybot] aa: {}", line);
    }

    @Override public void incident(String what) {
        try {
            Core.INSTANCE.recorder.jobEnded("aa", what, "error: " + what);
        } catch (RuntimeException e) {
            LOG.warn("[entropybot] aa incident: {}", e.toString());
        }
    }

    /** Chains tells every listener how a chain ended; AA keeps its own. */
    void chainEnded(String name, String msg) {
        boolean mine = CHAIN.equals(name) || (current >= 0 && myChain.equals(name));
        if (mine && current >= 0 && "climb".equals(escapeWay)) {         // 0.36.a6: the escape's climb ended
            escapeWay = null;
            myChain = CHAIN;
            if (msg != null && msg.startsWith("done")) {
                escapePending = true;           // review: the chain starts from escapeTick, not inside the end callback
                escapeWay = "resume";
            }
            else if (escapeOpt != null && escapes < AaEscape.MAX_PER_TASK) {
                // AA_ROUND3 1 finding 3: the failed way (a heading) is held in Failures; the next priced way runs
                wayMem.failed(escapeOpt.memoryKey(), msg == null ? "failed" : msg, bb.clock.now());
                LOG.info("[entropybot] aa: wayup: {} failed ({}) - held; the next way up", escapeOpt.id(), msg);
                escapes++;
                escapeOpt = null;
                escapePending = true;           // escapeTick starts it once the chain has settled
            } else {
                if (escapeOpt != null) wayMem.failed(escapeOpt.memoryKey(), msg == null ? "failed" : msg, bb.clock.now());
                escapeFailNow("climbing out failed too: " + msg);
            }
            return;
        }
        if (mine && current >= 0 && escapeWay == null && !escapePending && on() && (AaEscape.stuck(msg) || holeStuckNow(msg)) && escapes < AaEscape.MAX_PER_TASK) {
            String resume = null;
            try {
                resume = AaEscape.resume(c.chains.expandSteps(Texts.splitChain(currentChain), 0), AaEscape.failedStep(msg));
            } catch (RuntimeException e) {
                LOG.warn("[entropybot] aa: the stuck step's chain could not be read: {}", e.toString());
            }
            if (resume != null) {
                stepOffset += Math.max(0, AaEscape.failedStep(msg) - 1);
                escapes++;
                runStuckHalts++;
                escapePending = true;
                escapeResume = resume;
                escapeFail = msg;
                LOG.info("[entropybot] aa: the walk ended stuck under ground - getting out myself (escape {} of this task), then: {}", escapes, resume);
                return;
            }
        }
        // aa-replan: the ore step is done and a pillar way back is stored: the return leg runs, then the rest
        if (mine && current >= 0 && escapeWay == null && !escapePending && pendingReturn != null && msg != null && msg.startsWith("done") && on()) {
            returnDone = msg;
            escapeResume = pendingReturn.isEmpty() ? null : pendingReturn;
            pendingReturn = null;
            escapeWay = "return";
            escapePending = true;
            return;
        }
        if (mine) { lastEnd = msg; pendingReturn = null; }
        // 0.36.3: no tree I can get at in my areas: the woodArea rung makes an area around the nearest reachable one
        if (mine && msg != null && (msg.contains("no trees in my areas") || msg.contains("couldn't get at its trunk"))) treeAreaFact();
    }

    /**
     * aa372 gap: the walk failed at the bottom of a hole open to the sky (a 1x1 shaft the bot dug: "not under ground" by
     * the shared rule, so the under-ground end never came). The trigger is the owner's: the walk failed and the goal is
     * above me, or I stand in a cell I dug myself; and there is a way out above (AaEscape.holeStuck). Never throws.
     */
    private boolean holeStuckNow(String msg) {
        try {
            if (!AaEscape.walkFailed(msg)) return false;
            Minecraft mc = Minecraft.getInstance();
            LocalPlayer p = mc.player;
            if (p == null || mc.level == null) return false;
            int[] me = Jobs.here(p);
            String dim = io.github.mojolowjo.entropybot.guard.Guard.dimOf(mc.level);
            boolean dug = io.github.mojolowjo.entropybot.guard.PolicyRuntime.INSTANCE.dug(dim, me[0], me[1], me[2]);
            return AaEscape.holeStuck(msg, me[1], AaEscape.destOf(msg), dug, holeUp(mc.level, me));
        } catch (RuntimeException e) {
            LOG.warn("[entropybot] aa: hole check failed: {}", e.toString());
            return false;
        }
    }

    /** The levels up from the feet to where the bot can step out sideways (AaEscape.holeExit): 0 = here, -1 = none. */
    private static int holeUp(net.minecraft.world.level.Level lv, int[] me) {
        java.util.function.IntPredicate open = dy -> openCell(lv, me[0], me[1] + dy, me[2]);
        java.util.function.IntPredicate stepOut = dy -> {
            for (int[] s : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}})
                if (openCell(lv, me[0] + s[0], me[1] + dy, me[2] + s[1]) && openCell(lv, me[0] + s[0], me[1] + dy + 1, me[2] + s[1])) return true;
            return false;
        };
        return AaEscape.holeExit(AaEscape.HOLE_MAX_UP, open, stepOut);
    }

    /** With ground overhead: the levels up to the first cell with open feet, head and sky (at most 2 x the tunnel cap). */
    private static int skyLevels(net.minecraft.world.level.Level lv, int[] me) {
        int maxY = Math.min(lv.getMaxBuildHeight() - 2, me[1] + 2 * io.github.mojolowjo.entropybot.aa.Replan.TUNNEL_CAP);
        for (int y = me[1] + 2; y <= maxY; y++)
            if (openCell(lv, me[0], y - 1, me[2]) && openCell(lv, me[0], y, me[2]) && lv.canSeeSky(new net.minecraft.core.BlockPos(me[0], y, me[2]))) return y - 1 - me[1];
        return maxY - me[1];
    }

    /** The column over the head for {@code levels} up (the head cells on the way: feet+2 .. feet+levels+1), priced cell by cell. */
    private static List<io.github.mojolowjo.entropybot.aa.Replan.Cell> column(net.minecraft.world.level.Level lv, int[] me, int levels) {
        List<io.github.mojolowjo.entropybot.aa.Replan.Cell> col = new java.util.ArrayList<>();
        for (int y = me[1] + 2; y <= me[1] + levels + 1; y++) col.add(cellAt(lv, me[0], y, me[2]));
        return col;
    }

    /** One cell for WayUp: open, mine, the BreakPolicy multiplier (denied = infinite), fluids, a falling block. */
    private static io.github.mojolowjo.entropybot.aa.Replan.Cell cellAt(net.minecraft.world.level.Level lv, int x, int y, int z) {
        var pr = io.github.mojolowjo.entropybot.guard.PolicyRuntime.INSTANCE;
        String dim = lv.dimension().location().toString();
        net.minecraft.core.BlockPos q = new net.minecraft.core.BlockPos(x, y, z);
        if (!lv.isLoaded(q)) return new io.github.mojolowjo.entropybot.aa.Replan.Cell(false, false, Double.POSITIVE_INFINITY, false, false, false);
        var st = lv.getBlockState(q);
        var fl = io.github.mojolowjo.entropybot.guard.PolicyRuntime.fluidOf(st);
        boolean open = openCell(lv, x, y, z);
        double mult = 1;
        boolean own = pr.dug(dim, x, y, z);
        if (!open && fl == io.github.mojolowjo.entropybot.guard.BreakPolicy.Fluid.NONE) {
            if (st.getDestroySpeed(lv, q) < 0) mult = Double.POSITIVE_INFINITY;            // bedrock and the like
            else {
                var f = pr.facts(lv, q, false).withDug(own);
                var v = pr.judge("aa-wayup", io.github.mojolowjo.entropybot.guard.BreakPolicy.Action.BREAK, io.github.mojolowjo.entropybot.guard.BreakPolicy.Intent.DIG_OUT, dim, x, y, z, f, pr.where());
                mult = v.allowed() ? v.multiplier() : Double.POSITIVE_INFINITY;
            }
        }
        return new io.github.mojolowjo.entropybot.aa.Replan.Cell(open, own, mult,
                fl == io.github.mojolowjo.entropybot.guard.BreakPolicy.Fluid.LAVA, fl == io.github.mojolowjo.entropybot.guard.BreakPolicy.Fluid.WATER,
                st.getBlock() instanceof net.minecraft.world.level.block.FallingBlock);
    }

    /**
     * AA_ROUND3 1 finding 3: every stair heading's cells ({@code dig stairs up <dir> n}, n = levels, at most StairPlan.MAX)
     * read and checked against my areas (the dig refuses a box outside them: the first such cell is kept).
     */
    private static List<io.github.mojolowjo.entropybot.aa.WayUp.Heading> headings(net.minecraft.world.level.Level lv, int[] me, int levels) {
        List<io.github.mojolowjo.entropybot.aa.WayUp.Heading> out = new java.util.ArrayList<>();
        int n = Math.min(Math.max(1, levels), StairPlan.MAX);
        for (String d : io.github.mojolowjo.entropybot.aa.WayUp.DIRS) {
            int[] u = io.github.mojolowjo.entropybot.aa.WayUp.dir(d);
            List<io.github.mojolowjo.entropybot.aa.Replan.Cell> cells = new java.util.ArrayList<>();
            int[] outside = null;
            for (int[] c : io.github.mojolowjo.entropybot.aa.WayUp.stairCells(me[0], me[1], me[2], u[0], u[1], n)) {
                if (outside == null && !Clearing.boxInAreas(io.github.mojolowjo.entropybot.clear.ClearBox.of(c[0], c[1], c[2], c[0], c[1], c[2]))) outside = c;
                cells.add(cellAt(lv, c[0], c[1], c[2]));
            }
            out.add(new io.github.mojolowjo.entropybot.aa.WayUp.Heading(d, n, cells, outside));
        }
        return out;
    }

    private static boolean openCell(net.minecraft.world.level.Level lv, int x, int y, int z) {
        net.minecraft.core.BlockPos q = new net.minecraft.core.BlockPos(x, y, z);
        if (!lv.isLoaded(q)) return false;
        var st = lv.getBlockState(q);
        return st.getCollisionShape(lv, q).isEmpty() && st.getFluidState().isEmpty();
    }

    /** Out of the ground and out of any hole (the under-ground rule and the hole scan both say so). */
    private static boolean outNow(net.minecraft.world.level.Level lv, int[] me) {
        return Jobs.underDepth(lv, me[0], me[1], me[2]) == 0 && holeUp(lv, me) == 0;     // review: -1 (walled in) is not out
    }

    /** AA's escape from under ground runs or waits to start (Jobs: then no paper plan, no whisper). */
    boolean ownsEscape() {
        return on() && current >= 0 && (escapePending || escapeWay != null
                || (chainRunning() && CHAIN.equals(myChain) && escapes < AaEscape.MAX_PER_TASK));
    }

    private void clearEscape() {
        escapePending = false;
        escapeWay = null;
        escapeResume = null;
        escapeFail = null;
        escapeJob = null;
        escapeOpt = null;
        myChain = CHAIN;
        returnDone = null;
    }

    /** 0.36.a6: starts the pending escape once the stuck walk has settled; a pillar job's end -> go on, or climb. */
    private void escapeTick(LocalPlayer p, Minecraft mc) {
        if (current < 0) {
            if (escapePending || escapeWay != null) clearEscape();
            return;
        }
        if (escapePending) {
            if (c.chains.running() || c.jobs.running()) return;
            escapePending = false;
            if ("return".equals(escapeWay)) {
                escapeWay = null;
                startReturn(p, mc);
            } else if ("resume".equals(escapeWay)) {
                escapeWay = null;
                escapeResumeNow("climbed out");
            } else startEscape(p, mc, false);
        } else if ("pillar".equals(escapeWay) && !(c.jobs.running() && c.jobs.job == escapeJob) && !PaperPlan.owned()) {
            escapeWay = null;
            escapeJob = null;
            int[] me = Jobs.here(p);
            if (outNow(mc.level, me)) escapeResumeNow("pillared up");
            else {
                LOG.info("[entropybot] aa: the pillar ended still in the hole at {} - the next way up", Jobs.fmt(me));
                if (escapeOpt != null) wayMem.failed(escapeOpt.memoryKey(), "ended still in the hole", bb.clock.now());
                startEscape(p, mc, true);
            }
        }
    }

    private void startEscape(LocalPlayer p, Minecraft mc, boolean noPillar) {
        try {
            int[] me = Jobs.here(p);
            net.minecraft.world.level.Level lv = mc.level;
            boolean hole = Jobs.underDepth(lv, me[0], me[1], me[2]) == 0;     // aa372 gap: open to the sky, but at the bottom of a hole
            var f = surfaceFacts();
            if (f == null || f.depth() <= 0) { escapeResumeNow("already out"); return; }
            if (noPillar) f = new io.github.mojolowjo.entropybot.aa.RungOptions.SurfaceFacts(f.depth(), f.me(), false, f.throwaway(), false, f.pickaxe(),
                    f.trailEnd(), f.trailLen(), f.sky(), f.skyDist(), false, f.kit(), null, f.headings());
            else f = new io.github.mojolowjo.entropybot.aa.RungOptions.SurfaceFacts(f.depth(), f.me(), f.shaftOpen(), f.throwaway(), f.placeAllowed(), f.pickaxe(),
                    f.trailEnd(), f.trailLen(), f.sky(), f.skyDist(), false, f.kit(), f.column(), f.headings());
            // AA_ROUND3 1: the one way-up set, priced; a failed way is held in Failures under "wayup/<id>"
            wayMem.bind(tree.brain.failures, WAYUP);
            var ch = io.github.mojolowjo.entropybot.aa.AaEscape.chooseAll(f, wayMem, bb.clock.now());
            var o = ch.best();
            LOG.info("[entropybot] aa: stuck {} at {} ({} {} up): wayup: {} - {}", hole ? "in a hole" : "under ground", Jobs.fmt(me), hole ? "the way out" : "surface", f.depth(),
                    o == null ? "wait" : o.id(), ch.why());
            escapeOpt = o;
            if (o == null) { escapeFailNow("no way up: " + ch.why()); return; }
            if ("pillar".equals(o.id()) || "tunnel".equals(o.id())) {
                int[] e = {me[0], me[1] + f.depth(), me[2]};
                String r = "pillar".equals(o.id()) ? PaperPlan.INSTANCE.pillar(c, e, c.owner()) : PaperPlan.INSTANCE.tunnelUp(c, e, c.owner());
                if (r != null && !r.startsWith("error") && !r.startsWith("busy") && c.jobs.running()) {
                    escapeWay = "pillar";
                    escapeJob = c.jobs.job;
                } else {
                    LOG.info("[entropybot] aa: the {} did not start ({}) - the next way up", o.id(), r);
                    wayMem.failed(o.memoryKey(), "did not start: " + r, bb.clock.now());
                    startEscape(p, mc, true);
                }
                return;
            }
            String cr = io.github.mojolowjo.entropybot.aa.AaLadder.CLIMB.equals(o.command()) ? DigCommands.climb(c, p, "", c.owner()) : c.chainsRef().startChain(c.owner(), "climb", o.command(), 1);
            if (cr != null && cr.startsWith("ok")) escapeResumeNow(o.id() + ": " + cr);
            else if (cr != null && cr.startsWith("started")) {
                escapeWay = "climb";
                myChain = "climb";
            } else {
                wayMem.failed(o.memoryKey(), "did not start: " + cr, bb.clock.now());
                escapeFailNow("the way up (" + o.id() + ") did not start: " + cr);
            }
        } catch (RuntimeException e) {
            LOG.warn("[entropybot] aa: escape failed: {}", e.toString());
            escapeFailNow("escape error: " + e);
        }
    }

    /** Out: the task goes on with the step that ended stuck (same task, no failure, no cooldown). */
    private void escapeResumeNow(String how) {
        String resume = escapeResume;
        String fail = escapeFail;
        escapeResume = null;
        myChain = CHAIN;
        String back = returnDone;
        returnDone = null;
        if (back != null) {                  // aa-replan: the way back after the ore step is over
            if (runningOpt != null) stepOffset = runningOpt.ret().afterStep() + 1;
            if (resume == null) {
                escapeFail = null;
                lastEnd = back + " (back: " + how + ")";
                LOG.info("[entropybot] aa: back up ({}) - the task is done", how);
                return;
            }
        }
        String r = resume == null ? null : c.chains.startChain(c.owner(), CHAIN, resume, 1);
        if (r != null && r.startsWith("started")) {
            escapeFail = null;
            LOG.info("[entropybot] aa: out from under ground ({}) - going on with: {}", how, resume);
        } else {
            escapeFail = fail;
            escapeFailNow("out (" + how + "), but the task did not go on: " + r);
        }
    }

    /** No way out worked: the task fails with the stuck walk's end and why. */
    private void escapeFailNow(String why) {
        String fail = escapeFail;
        clearEscape();
        lastEnd = (fail == null ? "error: stuck under ground" : fail) + " (getting out: " + why + ")";
        LOG.info("[entropybot] aa: no way out from under ground: {}", why);
    }

    /**
     * aa-trees: the trees the bot has SEEN (sense/Sightings kind TREE) as wood options facts: distance, and a deny reason
     * (inside my areas already = the chop could not get at it, remembered unreachable, a protected build near). A seen tree whose
     * chunk is loaded and whose lowest log is gone is exhausted here. Null when nothing can be read; never throws.
     */
    io.github.mojolowjo.entropybot.aa.WoodOptions.WoodFacts woodFacts() {
        try {
            Minecraft mc = Minecraft.getInstance();
            LocalPlayer p = mc.player;
            var sg = io.github.mojolowjo.entropybot.sense.ChunkPass.INSTANCE.sightings();
            if (p == null || mc.level == null || sg == null) return null;
            String gdim = io.github.mojolowjo.entropybot.guard.Guard.dimOf(mc.level), adim = Storage.dim();
            var prot = io.github.mojolowjo.entropybot.cave.MineRules.policyBoxes(io.github.mojolowjo.entropybot.cave.MineRules.parsePolicy(c.policyJson()), "protect", PolicyCommands.DEFAULT_DIM);
            int[] me = Jobs.here(p);
            long now = System.currentTimeMillis();
            List<io.github.mojolowjo.entropybot.aa.WoodOptions.Tree> out = new ArrayList<>();
            for (var r : sg.treesNear(gdim, me[0], me[2], io.github.mojolowjo.entropybot.aa.WoodOptions.FAR_MAX)) {
                net.minecraft.core.BlockPos q = new net.minecraft.core.BlockPos(r.x(), r.y(), r.z());
                if (mc.level.isLoaded(q) && !mc.level.getBlockState(q).is(net.minecraft.tags.BlockTags.LOGS)) {
                    sg.exhaustTree(gdim, r.x(), r.z(), now);
                    continue;
                }
                String deny = c.inAreas(adim, r.x(), r.z()) ? "inside my areas already (the chop could not get at it)"
                        : io.github.mojolowjo.entropybot.chop.Unreachable.blocked(r.x(), r.z(), now) ? "remembered as unreachable"
                        : io.github.mojolowjo.entropybot.cave.MineRules.protectNear(prot, adim, r.x(), r.y(), r.z(), io.github.mojolowjo.entropybot.aa.WoodOptions.TREE_AREA_R + 8) != null ? "a protected build is near" : null;
                out.add(new io.github.mojolowjo.entropybot.aa.WoodOptions.Tree(r.x(), r.y(), r.z(), Math.hypot(r.x() - me[0], r.z() - me[2]), deny));
            }
            int n = 0;
            for (String a : c.areaNames()) if (a.startsWith("trees")) n++;
            return new io.github.mojolowjo.entropybot.aa.WoodOptions.WoodFacts(0, out, n, mc.getSingleplayerServer() != null);
        } catch (RuntimeException e) {
            LOG.warn("[entropybot] aa: wood facts: {}", e.toString());
            return null;
        }
    }

    /** At most this many tree areas AA adds itself ("trees1".."trees3"), each a box of 2*TREE_AREA_R+1. */
    static final int TREE_AREAS = 3, TREE_AREA_R = 20, TREE_SEARCH_R = 96;

    /**
     * 0.36.3: the nearest log column outside my areas (loaded, within 96, not a remembered unreachable trunk, no protect
     * box within 8 of the box) -> "area x z x2 z2 treesN work neutral" in the blackboard fact; none -> a log line only.
     * The owner's rule (2026-10-11): the bot works inside its own areas; it never boxes in a protected build.
     */
    void treeAreaFact() {
        try {
            if (bb.fact(AaLadder.TREE_AREA_FACT) != null) return;
            int n = 0;
            for (String a : c.areaNames()) if (a.startsWith("trees")) n++;
            if (n >= TREE_AREAS) {
                LOG.info("[entropybot] aa: no tree I can get at in my areas, and {} tree areas made already - not growing further", n);
                return;
            }
            Minecraft mc = Minecraft.getInstance();
            LocalPlayer p = mc.player;
            if (p == null || mc.level == null) return;
            String dim = Storage.dim();
            var prot = io.github.mojolowjo.entropybot.cave.MineRules.policyBoxes(io.github.mojolowjo.entropybot.cave.MineRules.parsePolicy(c.policyJson()), "protect", PolicyCommands.DEFAULT_DIM);
            int[] me = Jobs.here(p);
            long now = System.currentTimeMillis(), best = Long.MAX_VALUE;
            int[] at = null;
            net.minecraft.core.BlockPos.MutableBlockPos m = new net.minecraft.core.BlockPos.MutableBlockPos();
            for (int x = me[0] - TREE_SEARCH_R; x <= me[0] + TREE_SEARCH_R; x += 2)
                for (int z = me[2] - TREE_SEARCH_R; z <= me[2] + TREE_SEARCH_R; z += 2) {
                    long d = (long) (x - me[0]) * (x - me[0]) + (long) (z - me[2]) * (z - me[2]);
                    if (d >= best || c.inAreas(dim, x, z)) continue;
                    m.set(x, 0, z);
                    if (!mc.level.isLoaded(m)) continue;
                    int y = mc.level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
                    m.set(x, y, z);
                    if (!mc.level.getBlockState(m).is(net.minecraft.tags.BlockTags.LOGS)) continue;
                    if (io.github.mojolowjo.entropybot.chop.Unreachable.blocked(x, z, now)) continue;
                    if (io.github.mojolowjo.entropybot.cave.MineRules.protectNear(prot, dim, x, y, z, TREE_AREA_R + 8) != null) continue;
                    best = d;
                    at = new int[]{x, y, z};
                }
            if (at == null) {
                LOG.info("[entropybot] aa: no tree I can get at in my areas, and none loaded within {} outside them", TREE_SEARCH_R);
                return;
            }
            String cmd = "area " + (at[0] - TREE_AREA_R) + " " + (at[2] - TREE_AREA_R) + " " + (at[0] + TREE_AREA_R) + " " + (at[2] + TREE_AREA_R) + " trees" + (n + 1) + " work neutral";
            bb.fact(AaLadder.TREE_AREA_FACT, cmd);
            LOG.info("[entropybot] aa: no tree I can get at in my areas: the nearest reachable one is at {} {} {} ({} away) - {}", at[0], at[1], at[2], Math.round(Math.sqrt(best)), cmd);
        } catch (RuntimeException e) {
            LOG.warn("[entropybot] aa: tree area: {}", e.toString());
        }
    }

    // ---- status, files ----

    /** "AA: slot>needs>...RUNNING 34s by needs | danger: calm | last switch 2m ago: done TOOLS" (AA_PLAN 6). */
    public String statusLine() {
        long now = System.currentTimeMillis();
        if (!on()) return "AA: off" + (offReason != null ? " (" + offReason + ")" : "");
        StringBuilder b = new StringBuilder("AA: ");
        if (now < heldUntil) b.append("held ").append((heldUntil - now + 59_999) / 60_000).append(" min after a stop");
        else b.append(tree.driver()).append('>').append(bb.taskText(now));
        b.append(" | danger: ").append(tree.dangerWhy());
        if (conserving) b.append(" | conserving hunger");
        b.append(" | last switch ").append(tracer.lastSwitchText(now));
        return b.toString();
    }

    /** The short part for status/the HUD, or null when off. */
    String statusPart() {
        if (!on()) return null;
        long now = System.currentTimeMillis();
        return now < heldUntil ? "aa held" : "aa " + tree.driver() + (bb.task() != null ? " " + bb.task().spec().chain() : "");
    }

    String counts() {
        Tracer.Counts k = tracer.counts;
        return "switches " + k.switches + " (pointless " + k.pointless + "), preempts " + k.preempts + ", fails " + k.fails + ", lost " + k.lost
                + ", ticks " + k.ticks + ", last tick " + k.tickUs + " us, errors " + errors + (lastError != null ? " (last: " + lastError + ")" : "")
                + (writeErrors > 0 ? ", file write errors " + writeErrors : "")
                + ", AI requests " + aiRequests + " (" + bb.requests().size() + " waiting)";
    }

    /** The ladder's reasons this tick ("needs.tools: TOOLS met; needs.torches: TORCHES planning ..."). */
    String ladderLine() {
        StringBuilder b = new StringBuilder();
        for (var r : AaLadder.rungs(tree.ladder)) {
            if (b.length() > 0) b.append("; ");
            b.append(r.id.substring(6)).append(": ").append(r.last() == null ? "not checked" : r.last() == Status.FAILURE ? r.reason() : r.last().name());
        }
        return b.toString();
    }

    // ---- BRAIN_PLAN B2: the recipe tech tree, what is had, measured costs ----

    private int treeRecipes = -1;
    private long treeCheckedAt = -60_000, statsSavedAt, haveAt = -10_000;
    private boolean statsLoaded;
    private String treeError;

    /**
     * Once a minute: the tech tree from the client's RecipeManager (rebuilt when the recipe count changes; 2.8), the
     * set of items had (bag + known table/furnace), the stats file loaded once and saved when changed (2.9). Never
     * throws; a failure is logged once and shown by aa stats.
     */
    private void brainFacts(Minecraft mc, long now) {
        if (!statsLoaded && statsFile() != null) {      // review: only once the files are known
            statsLoaded = true;
            loadStats();
            loadPersonality();
        }
        // review: the bag set at most every 10 s (the tech tree's caches are per bag; mining changes it all the time)
        if (now - haveAt >= 10_000) {
            haveAt = now;
            java.util.Set<String> have = new java.util.HashSet<>();
            if (sensed != null && sensed.feas != null) for (var e : sensed.feas.bag.entrySet()) if (e.getValue() > 0) have.add(e.getKey());
            if (bb.in.tableKnown) have.add("crafting_table");
            if (bb.in.furnaceKnown) have.add("furnace");
            if (!have.equals(bb.fact(io.github.mojolowjo.entropybot.aa.AaDesires.HAVE_FACT))) bb.fact(io.github.mojolowjo.entropybot.aa.AaDesires.HAVE_FACT, java.util.Set.copyOf(have));
        }
        if (now - treeCheckedAt >= 60_000) {
            treeCheckedAt = now;
            try {
                var rm = mc.level.getRecipeManager();
                var all = rm.getRecipes();
                if (all.size() != treeRecipes) {
                    List<io.github.mojolowjo.entropybot.aa.brain.TechTree.Recipe> rs = new ArrayList<>();
                    for (var h : all) {
                        var r = h.value();
                        var out = r.getResultItem(mc.level.registryAccess());
                        if (out == null || out.isEmpty()) continue;
                        List<java.util.Set<String>> slots = new ArrayList<>();
                        for (var ing : r.getIngredients()) {
                            java.util.Set<String> s = new java.util.HashSet<>();
                            for (var st : ing.getItems()) s.add(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(st.getItem()).toString());
                            if (!s.isEmpty()) slots.add(s);
                        }
                        rs.add(new io.github.mojolowjo.entropybot.aa.brain.TechTree.Recipe(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(out.getItem()).toString(), slots));
                    }
                    var t = new io.github.mojolowjo.entropybot.aa.brain.TechTree(rs);
                    bb.fact(io.github.mojolowjo.entropybot.aa.AaDesires.TECH_FACT, t);
                    treeRecipes = all.size();
                    treeError = null;
                    LOG.info("[entropybot] aa: tech tree from {} recipes ({} usable)", all.size(), t.size());
                }
            } catch (RuntimeException e) {
                if (treeError == null) LOG.warn("[entropybot] aa: tech tree: {}", e.toString());
                treeError = e.toString();
            }
        }
        Path f = statsFile();
        if (f != null && now - statsSavedAt >= 60_000 && tree.brain.stats.takeDirty()) {
            statsSavedAt = now;
            String json = tree.brain.stats.toJson();
            WRITER.submit(() -> {
                try {
                    Files.createDirectories(f.getParent());
                    Path tmp = f.resolveSibling("aa-stats.json.tmp");
                    Files.writeString(tmp, json, StandardCharsets.UTF_8);
                    Files.move(tmp, f, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException e) {
                    LOG.warn("[entropybot] aa: stats write: {}", e.toString());
                }
            });
        }
    }

    /** entropybot/aa-stats.json (all worlds: measured costs carry over to a fresh test world). */
    private static Path statsFile() {
        return Core.INSTANCE.files() == null ? null : Core.INSTANCE.files().root().resolve("aa-stats.json");
    }

    private void loadStats() {
        Path f = statsFile();
        if (f == null || !Files.exists(f)) return;
        try {
            var o = com.google.gson.JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8)).getAsJsonObject();
            for (var e : o.entrySet()) {
                var s = e.getValue().getAsJsonObject();
                if (e.getKey().startsWith(io.github.mojolowjo.entropybot.aa.brain.BrainStats.DRAIN_PREFIX)) {      // aa-food
                    if (s.has("perMin")) tree.brain.stats.putDrain(e.getKey().substring(io.github.mojolowjo.entropybot.aa.brain.BrainStats.DRAIN_PREFIX.length()),
                            s.get("n").getAsInt(), s.get("perMin").getAsDouble());
                    continue;
                }
                tree.brain.stats.put(e.getKey(), s.get("n").getAsInt(), s.get("ok").getAsInt(), s.get("avgS").getAsDouble());
            }
            LOG.info("[entropybot] aa: measured costs loaded for {} desires", o.size());
        } catch (Exception e) {
            LOG.warn("[entropybot] aa: {} unreadable, hand costs used: {}", f, e.toString());
            treeError = "stats file unreadable: " + e;
        }
    }

    // ---- B3 (BRAIN_PLAN 2.4): the personality file ----

    private static Path personalityFile() {
        return Core.INSTANCE.files() == null ? null : Core.INSTANCE.files().root().resolve("aa-personality.json");
    }

    /** Loads entropybot/aa-personality.json (written with the defaults when missing); errors: defaults, said in aa status. */
    private void loadPersonality() {
        Path f = personalityFile();
        io.github.mojolowjo.entropybot.aa.brain.Personality p;
        if (f == null || !Files.exists(f)) {
            p = new io.github.mojolowjo.entropybot.aa.brain.Personality();
            savePersonality(p);
        } else {
            try {
                var o = com.google.gson.JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8)).getAsJsonObject();
                Map<String, Object> m = new java.util.LinkedHashMap<>();
                for (var e : o.entrySet()) {
                    var v = e.getValue();
                    m.put(e.getKey(), v.isJsonPrimitive() && v.getAsJsonPrimitive().isNumber() ? (Object) v.getAsDouble() : v.toString());
                }
                p = io.github.mojolowjo.entropybot.aa.brain.Personality.of(m);
            } catch (Exception e) {
                p = new io.github.mojolowjo.entropybot.aa.brain.Personality();
                p.errors.add(f.getFileName() + " unreadable: " + e.getMessage());
            }
        }
        bb.fact(io.github.mojolowjo.entropybot.aa.brain.Personality.FACT, p);
        if (p.errors.isEmpty()) LOG.info("[entropybot] aa: personality loaded ({})", String.join(", ", p.lines().subList(0, 3)).replaceAll(" - [^,]*", ""));
        else LOG.warn("[entropybot] aa: personality: {} - defaults used for those", String.join("; ", p.errors));
    }

    private static void savePersonality(io.github.mojolowjo.entropybot.aa.brain.Personality p) {
        Path f = personalityFile();
        if (f == null) return;
        String json = p.toJson();
        WRITER.submit(() -> {
            try {
                Files.createDirectories(f.getParent());
                Path tmp = f.resolveSibling("aa-personality.json.tmp");
                Files.writeString(tmp, json, StandardCharsets.UTF_8);
                Files.move(tmp, f, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                LOG.warn("[entropybot] aa: personality write: {}", e.toString());
            }
        });
    }

    /** aa like [key value]: lists the personality, or sets one value and saves the file. */
    String like(String arg) {
        var p = io.github.mojolowjo.entropybot.aa.brain.Personality.of(bb);
        if (bb.fact(io.github.mojolowjo.entropybot.aa.brain.Personality.FACT) == null) {      // not loaded yet: a fresh one
            p = new io.github.mojolowjo.entropybot.aa.brain.Personality();
            bb.fact(io.github.mojolowjo.entropybot.aa.brain.Personality.FACT, p);
        }
        String[] w = arg.isBlank() ? new String[0] : arg.trim().split("\\s+");
        if (w.length == 0) return "personality (aa like <key> <n>; entropybot/aa-personality.json):\n" + String.join("\n", p.lines())
                + (p.errors.isEmpty() ? "" : "\n!! " + String.join("; ", p.errors) + " (defaults used)");
        if (w.length != 2) return "usage: aa like | aa like <key> <number>";
        double v;
        try {
            v = Double.parseDouble(w[1]);
        } catch (NumberFormatException e) {
            return "error: \"" + w[1] + "\" is not a number";
        }
        if (!p.errors.isEmpty()) return "error: the personality file has errors (" + String.join("; ", p.errors) + ") - fix entropybot/aa-personality.json first; saving now would reset those entries";
        String err = p.set(w[0], v);
        if (err != null) return "error: " + err;
        savePersonality(p);
        return "ok: " + w[0] + " " + io.github.mojolowjo.entropybot.aa.brain.Personality.fmt(v) + " (saved)";
    }

    // ---- B4 (BRAIN_PLAN 2.5): aa want / drop / goals ----

    private io.github.mojolowjo.entropybot.aa.brain.Thing.Catalog catalog;
    private String catalogWorld;

    /** The registries for the resolver, read once per world: ids, names (client language), pickaxe tiers, worldgen. */
    private io.github.mojolowjo.entropybot.aa.brain.Thing.Catalog catalog(Minecraft mc) {
        String world = Core.INSTANCE.files() == null ? "" : Core.INSTANCE.files().worldRoot().toString();
        if (catalog != null && world.equals(catalogWorld)) return catalog;
        List<String> items = new ArrayList<>(), blocks = new ArrayList<>();
        Map<String, String> names = new java.util.HashMap<>();
        Map<String, Integer> tiers = new java.util.HashMap<>();
        for (var e : net.minecraft.core.registries.BuiltInRegistries.ITEM.entrySet()) {
            String id = e.getKey().location().toString();
            items.add(id);
            try {
                names.put(id, e.getValue().getDescription().getString().toLowerCase(java.util.Locale.ROOT));
            } catch (RuntimeException ignored) {}
        }
        for (var e : net.minecraft.core.registries.BuiltInRegistries.BLOCK.entrySet()) {
            String id = e.getKey().location().toString();
            blocks.add(id);
            var st = e.getValue().defaultBlockState();
            tiers.put(id, io.github.mojolowjo.entropybot.aa.brain.Thing.tier(st.is(net.minecraft.tags.BlockTags.MINEABLE_WITH_PICKAXE),
                    st.is(net.minecraft.tags.BlockTags.NEEDS_STONE_TOOL), st.is(net.minecraft.tags.BlockTags.NEEDS_IRON_TOOL),
                    st.is(net.minecraft.tags.BlockTags.NEEDS_DIAMOND_TOOL)));
            names.putIfAbsent(id, e.getValue().getName().getString().toLowerCase(java.util.Locale.ROOT));
        }
        Map<String, io.github.mojolowjo.entropybot.aa.brain.Thing.Gen> gen = worldgen(mc);
        catalog = new io.github.mojolowjo.entropybot.aa.brain.Thing.Catalog(items, blocks, names, tiers, gen, AaRuntime::learned);
        catalogWorld = world;
        lootPassive = new java.util.HashSet<>();
        var book = lootBook(mc, lootPassive);
        bb.fact(io.github.mojolowjo.entropybot.aa.brain.LootBook.FACT, book);
        if (book.origin.startsWith("read failed")) catalogWorld = null;      // review: try again on the next aa want
        LOG.info("[entropybot] aa: resolver catalog: {} items, {} blocks, worldgen {}", items.size(), blocks.size(),
                gen == null ? "none (multiplayer)" : gen.size() + " ore blocks");
        return catalog;
    }

    /**
     * Singleplayer only: every ore feature (OreConfiguration targets) in each level's possible biomes (NeoForge biome
     * modifiers included), with the Y range from its height_range placement (encoded to JSON: the anchors). Null when
     * there is no integrated server; a failure is logged and gives null (the vanilla table / guess take over).
     */
    private static Map<String, io.github.mojolowjo.entropybot.aa.brain.Thing.Gen> worldgen(Minecraft mc) {
        var server = mc.getSingleplayerServer();
        if (server == null) return null;
        Map<String, java.util.Set<String>> dims = new java.util.HashMap<>();
        Map<String, int[]> ys = new java.util.HashMap<>();
        try {
            for (var lvl : server.getAllLevels()) {
                String dim = lvl.dimension().location().toString();
                int bottom = lvl.getMinBuildHeight(), top = lvl.getMaxBuildHeight();
                java.util.Set<Object> seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
                for (var biome : lvl.getChunkSource().getGenerator().getBiomeSource().possibleBiomes())
                    for (var set : biome.value().getGenerationSettings().features())
                        for (var pf : set) {
                            var placed = pf.value();
                            if (!seen.add(placed)) continue;
                            if (!(placed.feature().value().config() instanceof net.minecraft.world.level.levelgen.feature.configurations.OreConfiguration oc)) continue;
                            int[] y = heights(placed.placement(), bottom, top);
                            for (var t : oc.targetStates) {
                                String id = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(t.state.getBlock()).toString();
                                dims.computeIfAbsent(id, k -> new java.util.TreeSet<>()).add(dim);
                                if (y != null) ys.merge(id, y, (a, b) -> new int[]{Math.min(a[0], b[0]), Math.max(a[1], b[1])});
                            }
                        }
            }
        } catch (RuntimeException e) {
            LOG.warn("[entropybot] aa: worldgen read failed ({}) - the vanilla table and name guesses are used", e.toString());
            return null;
        }
        Map<String, io.github.mojolowjo.entropybot.aa.brain.Thing.Gen> out = new java.util.HashMap<>();
        for (var e : dims.entrySet()) {
            int[] y = ys.get(e.getKey());
            int u = io.github.mojolowjo.entropybot.aa.brain.Thing.UNKNOWN_Y;
            out.put(e.getKey(), new io.github.mojolowjo.entropybot.aa.brain.Thing.Gen(e.getValue(), y == null ? u : y[0], y == null ? u : y[1]));
        }
        return out;
    }

    private java.util.Set<String> lootPassive = java.util.Set.of();
    /** Friendly-category mobs that fight back: never "passive" for a loot hunt (the animals kind may still allow them). */
    static final java.util.Set<String> NEUTRAL = java.util.Set.of("minecraft:wolf", "minecraft:llama", "minecraft:trader_llama", "minecraft:polar_bear",
            "minecraft:bee", "minecraft:panda", "minecraft:goat", "minecraft:iron_golem", "minecraft:dolphin", "minecraft:fox");

    /**
     * Loot data (BRAIN_PLAN "knowledge from the game at load"), singleplayer only: every mob's and block's loot table and
     * the chests/ tables, encoded to JSON with LootTable.DIRECT_CODEC (registry ops) and parsed by the pure LootBook;
     * the villager and wandering trader trade lists (VillagerTrades after NeoForge's trade events). A multiplayer
     * client has no loot tables (server data): an empty book, said in the log and in aa want. One bad table or
     * trade is skipped and counted, never fatal. Loader notes: vanilla API only (ReloadableServerRegistries,
     * LootTable codec, VillagerTrades); Fabric has the same vanilla calls (its LootTableEvents only modify tables).
     */
    private static io.github.mojolowjo.entropybot.aa.brain.LootBook lootBook(Minecraft mc, java.util.Set<String> passive) {
        var server = mc.getSingleplayerServer();
        if (server == null) {
            LOG.info("[entropybot] aa: loot data: the vanilla farm-animal table only (multiplayer client: loot tables stay on the server; modded drops unknown)");
            return io.github.mojolowjo.entropybot.aa.brain.LootBook.vanilla();
        }
        long t0 = System.nanoTime();
        List<io.github.mojolowjo.entropybot.aa.brain.LootBook.Source> all = new ArrayList<>();
        int[] bad = {0};
        try {
            var ops = net.minecraft.resources.RegistryOps.create(com.mojang.serialization.JsonOps.INSTANCE, server.registryAccess());
            var rr = server.reloadableRegistries();
            Map<String, com.google.gson.JsonElement> cache = new java.util.HashMap<>();
            java.util.function.Function<String, com.google.gson.JsonElement> lookup = id -> {
                if (cache.containsKey(id)) return cache.get(id);
                com.google.gson.JsonElement j = null;
                try {
                    var key = net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.LOOT_TABLE, net.minecraft.resources.ResourceLocation.parse(id));
                    var t = rr.getLootTable(key);
                    if (t != net.minecraft.world.level.storage.loot.LootTable.EMPTY)
                        j = net.minecraft.world.level.storage.loot.LootTable.DIRECT_CODEC.encodeStart(ops, t).result().orElse(null);
                    if (t != net.minecraft.world.level.storage.loot.LootTable.EMPTY && j == null) bad[0]++;
                } catch (RuntimeException e) {
                    bad[0]++;
                }
                cache.put(id, j);
                return j;
            };
            for (var e : net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.entrySet()) {
                var type = e.getValue();
                if (type.getCategory() == net.minecraft.world.entity.MobCategory.MISC) continue;
                String id = e.getKey().location().toString();
                if (type.getCategory().isFriendly() && !NEUTRAL.contains(id)) passive.add(id);   // review: neutral ones fight back
                String table = type.getDefaultLootTable().location().toString();
                var parsed = io.github.mojolowjo.entropybot.aa.brain.LootBook.parse(lookup.apply(table), lookup);
                all.addAll(io.github.mojolowjo.entropybot.aa.brain.LootBook.sources(io.github.mojolowjo.entropybot.aa.brain.LootBook.Kind.MOB, id, parsed));
            }
            for (var e : net.minecraft.core.registries.BuiltInRegistries.BLOCK.entrySet()) {
                var key = e.getValue().getLootTable();
                if (key == null || key == net.minecraft.world.level.storage.loot.BuiltInLootTables.EMPTY) continue;
                String id = e.getKey().location().toString();
                var parsed = io.github.mojolowjo.entropybot.aa.brain.LootBook.parse(lookup.apply(key.location().toString()), lookup);
                parsed.remove(id);                     // the block itself: gather/mine know it
                all.addAll(io.github.mojolowjo.entropybot.aa.brain.LootBook.sources(io.github.mojolowjo.entropybot.aa.brain.LootBook.Kind.BLOCK, id, parsed));
            }
            for (var loc : rr.getKeys(net.minecraft.core.registries.Registries.LOOT_TABLE)) {
                if (!loc.getPath().startsWith("chests/")) continue;
                var parsed = io.github.mojolowjo.entropybot.aa.brain.LootBook.parse(lookup.apply(loc.toString()), lookup);
                all.addAll(io.github.mojolowjo.entropybot.aa.brain.LootBook.sources(io.github.mojolowjo.entropybot.aa.brain.LootBook.Kind.CHEST, loc.toString(), parsed));
            }
            for (var pe : net.minecraft.world.entity.npc.VillagerTrades.TRADES.entrySet()) {
                String prof = String.valueOf(net.minecraft.core.registries.BuiltInRegistries.VILLAGER_PROFESSION.getKey(pe.getKey()));
                for (var lv : pe.getValue().values()) trades(all, prof, lv, bad);
            }
            for (var lv : net.minecraft.world.entity.npc.VillagerTrades.WANDERING_TRADER_TRADES.values()) trades(all, "minecraft:wandering_trader", lv, bad);
        } catch (RuntimeException e) {
            LOG.warn("[entropybot] aa: loot data read failed ({}) - aa want uses recipes, ores and gather sources only", e.toString());
            return io.github.mojolowjo.entropybot.aa.brain.LootBook.empty("read failed: " + e.getClass().getSimpleName());
        }
        var book = new io.github.mojolowjo.entropybot.aa.brain.LootBook(all, "read from the game at load");
        LOG.info("[entropybot] aa: loot data: {}; {} tables/trades skipped; {} ms", book.counts(), bad[0], (System.nanoTime() - t0) / 1_000_000);
        return book;
    }

    /** One level's trade listings: each offer once (null trader: the ones that need a villager or a map throw and are skipped). */
    private static void trades(List<io.github.mojolowjo.entropybot.aa.brain.LootBook.Source> all, String prof,
                               net.minecraft.world.entity.npc.VillagerTrades.ItemListing[] lv, int[] bad) {
        if (lv == null) return;
        for (var l : lv) {
            if (l == null || l instanceof net.minecraft.world.entity.npc.VillagerTrades.TreasureMapForEmeralds) continue;   // would search for a structure
            try {
                var o = l.getOffer(null, net.minecraft.util.RandomSource.create(7));
                if (o == null || o.getResult().isEmpty()) continue;
                var a = o.getCostA();
                var b = o.getCostB();
                String cost = a.getCount() + " " + net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(a.getItem()).getPath()
                        + (b.isEmpty() ? "" : " + " + b.getCount() + " " + net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(b.getItem()).getPath());
                all.add(new io.github.mojolowjo.entropybot.aa.brain.LootBook.Source(io.github.mojolowjo.entropybot.aa.brain.LootBook.Kind.TRADE, prof,
                        net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(o.getResult().getItem()).toString(), o.getResult().getCount(), 1, false, cost));
            } catch (RuntimeException e) {
                bad[0]++;
            }
        }
    }

    /** The loot plan's context: hunting on, passive mobs, the owner's animals kind, the mine verb's blocks, armed. */
    private void lootCtx() {
        java.util.Set<String> armedSet = java.util.Set.of("stone_sword", "iron_sword", "diamond_sword", "netherite_sword", "stone_axe", "iron_axe", "diamond_axe", "netherite_axe");
        boolean armed = false;
        for (var e : bb.in.bag.entrySet()) if (e.getValue() > 0 && armedSet.contains(e.getKey().replaceFirst("^minecraft:", ""))) armed = true;
        bb.fact(io.github.mojolowjo.entropybot.aa.brain.LootBook.CTX_FACT, new io.github.mojolowjo.entropybot.aa.brain.LootBook.Ctx(
                c.hunting != null && c.hunting.on(), lootPassive, java.util.Set.copyOf(c.vocab.expand("animals")),
                b -> io.github.mojolowjo.entropybot.cave.MineRules.EXTRA.matcher(b).find(), armed));
        try {         // AA_ROUND3 5: herds seen earlier, for the loot plan's "hunt ... at x z"
            Minecraft mc = Minecraft.getInstance();
            bb.fact(io.github.mojolowjo.entropybot.aa.brain.LootBook.HERDS_FACT, herdsSeen(mc.level, mc.player));
        } catch (RuntimeException e) {
            LOG.debug("[entropybot] aa: herds seen: {}", e.toString());
        }
    }

    /** The min/max Y of the anchors in a placement list's height_range (null when none). */
    private static int[] heights(List<net.minecraft.world.level.levelgen.placement.PlacementModifier> mods, int bottom, int top) {
        int[] r = null;
        for (var m : mods) {
            if (!(m instanceof net.minecraft.world.level.levelgen.placement.HeightRangePlacement)) continue;
            var json = net.minecraft.world.level.levelgen.placement.PlacementModifier.CODEC
                    .encodeStart(com.mojang.serialization.JsonOps.INSTANCE, m).result().orElse(null);
            if (json == null) continue;
            List<Integer> vals = new ArrayList<>();
            anchors(json, bottom, top, vals);
            for (int v : vals) r = r == null ? new int[]{v, v} : new int[]{Math.min(r[0], v), Math.max(r[1], v)};
        }
        return r;
    }

    private static void anchors(com.google.gson.JsonElement j, int bottom, int top, List<Integer> out) {
        if (j.isJsonArray()) for (var x : j.getAsJsonArray()) anchors(x, bottom, top, out);
        if (!j.isJsonObject()) return;
        for (var e : j.getAsJsonObject().entrySet()) {
            String k = e.getKey();
            if ((k.equals("absolute") || k.equals("above_bottom") || k.equals("below_top")) && e.getValue().isJsonPrimitive())
                out.add(io.github.mojolowjo.entropybot.aa.brain.Thing.anchorY(k, e.getValue().getAsInt(), bottom, top));
            else anchors(e.getValue(), bottom, top, out);
        }
    }

    /** B5: what the chunk pass has learned about a family (read live, so a cached catalog stays current). */
    private static io.github.mojolowjo.entropybot.aa.brain.Thing.Learned learned(String family) {
        var st = io.github.mojolowjo.entropybot.sense.ChunkPass.INSTANCE.stats();
        var r = st == null ? null : st.best(family);
        return r == null ? null : new io.github.mojolowjo.entropybot.aa.brain.Thing.Learned(r.dim(), r.lowY(), r.highY(), r.peakY(), r.n());
    }

    /** aa ore [<thing>]: the heights learned from the chunks seen (plus what the resolver says for a word). */
    String oreInfo(String arg) {
        var st = io.github.mojolowjo.entropybot.sense.ChunkPass.INSTANCE.stats();
        if (st == null) return "ore info: no world files yet";
        String w = arg == null ? "" : arg.trim().replaceFirst("^(?i)info\\s*", "");
        String learned = st.info(w);
        Minecraft mc = Minecraft.getInstance();
        if (w.isEmpty() || mc.level == null) return learned;
        var r = io.github.mojolowjo.entropybot.aa.brain.Thing.resolve(w, catalog(mc));
        return (r.err() != null ? r.err() : r.text()) + "\nlearned: " + learned;
    }

    /** Every 10 s while goals exist: known positions per family (OreIndex, 256 around), reached goals said and dropped. */
    private boolean knownDue;

    private final Map<String, String> discLines = new java.util.HashMap<>();

    private void goalsTick(LocalPlayer p, long tick) {
        buildsTick(p, tick);
        var goals = io.github.mojolowjo.entropybot.aa.brain.Goals.of(bb);
        if (goals.isEmpty()) { bb.fact(io.github.mojolowjo.entropybot.aa.brain.Discovery.FACT, Map.of()); return; }
        if (tick % 200 != 7 && !knownDue) return;
        knownDue = false;
        lootCtx();
        for (var g : io.github.mojolowjo.entropybot.aa.brain.Goals.done(bb)) {
            tree.brain.remove(g.id(), "goal reached");
            String msg = "got " + io.github.mojolowjo.entropybot.aa.brain.Goals.have(bb.in.bag, g) + " " + g.r.key() + " (goal " + g.n + " done)";
            LOG.info("[entropybot] aa: {}", msg);
            c.whisper(c.owner(), "aa: " + msg);
        }
        Map<String, Integer> known = new java.util.HashMap<>();
        var pass = io.github.mojolowjo.entropybot.sense.ChunkPass.INSTANCE;
        int[] me = Jobs.here(p);
        Map<String, io.github.mojolowjo.entropybot.sense.OreIndex.Count> within = pass.index().within(bb.in.dimension, me[0], me[1], me[2], 256);
        var seenNow = io.github.mojolowjo.entropybot.sense.Seen.fair() ? pass.seen() : null;
        for (var g : goals) {
            if (!g.r.ore()) continue;
            if (io.github.mojolowjo.entropybot.sense.Seen.fair()) {       // AA_ROUND3 3: only the cells I have seen count as known
                String sf = io.github.mojolowjo.entropybot.sense.KnownOre.familyOf(g.r.key());
                known.put(g.r.key(), seenNow == null || sf == null || sf.equals("?") ? 0 : seenNow.near(bb.in.dimension, io.github.mojolowjo.entropybot.sense.KnownOre.familyOf(g.r.key()), me[0], me[1], me[2], 256).size());
                continue;
            }
            int n = 0;
            // aa-water (live water1): the index is keyed by family ("iron"), the goal by ids ("iron_ore"): both count
            String fam = io.github.mojolowjo.entropybot.sense.KnownOre.familyOf(g.r.key());
            for (var e : within.entrySet()) if (g.r.ids().contains(e.getKey()) || e.getKey().equals(fam)) n += e.getValue().n();
            known.put(g.r.key(), n);
        }
        bb.fact(io.github.mojolowjo.entropybot.aa.brain.Goals.KNOWN_FACT, known);
        // round 2 item 5: each ore goal's nearest takeable ore, for its "mine <ore> 1 at x y z" step
        Map<String, int[]> nearest = new java.util.HashMap<>();
        Map<String, String> waterCmd = new java.util.HashMap<>();
        Map<String, Integer> bagNow = sensed != null && sensed.feas != null ? sensed.feas.bag : Map.of();
        for (var g : goals) {
            if (!g.r.ore() || known.getOrDefault(g.r.key(), 0) == 0) continue;
            // aa-water (1f): priced by the iron scorer (the water ways, a floating dig, the other ores)
            var pick = io.github.mojolowjo.entropybot.aa.brain.Goals.pickPriced(Mining.get().aaOres(p,
                    io.github.mojolowjo.entropybot.sense.KnownOre.familyOf(g.r.key()), IRON_ORES), me[1], p.isInWater(), AaEscape.throwaway(bagNow),
                    io.github.mojolowjo.entropybot.guard.PolicyRuntime.INSTANCE.travelMayPlace(), IronOptions.DIG_MAX);
            if (pick == null) continue;
            nearest.put(g.r.key(), pick.at());
            if (pick.waterCmd() != null) {
                waterCmd.put(g.r.key(), pick.waterCmd());
                LOG.info("[entropybot] aa: goal {}: water way {}", g.r.key(), pick.waterCmd());
            }
        }
        bb.fact(io.github.mojolowjo.entropybot.aa.brain.Goals.NEAREST_FACT, nearest);
        bb.fact(io.github.mojolowjo.entropybot.aa.brain.Goals.WATER_CMD_FACT, waterCmd);
        if (sensed != null && Minecraft.getInstance().level != null) {
            String dim = io.github.mojolowjo.entropybot.guard.Guard.dimOf(Minecraft.getInstance().level);
            var cave = Core.INSTANCE.caves.nearest(dim, me[0], me[1], me[2]);
            int caveDist = cave == null ? -1 : (int) Math.round(Math.sqrt(Jobs.distSq(me, new int[]{cave.ex, cave.ey, cave.ez})));
            if (caveDist > io.github.mojolowjo.entropybot.cave.Caves.PICK_WITHIN) caveDist = -1;
            bb.fact(io.github.mojolowjo.entropybot.aa.brain.Goals.WAYS_FACT, new io.github.mojolowjo.entropybot.aa.brain.Goals.Ways(caveDist,
                    sensed.feas != null && sensed.feas.mineMarked, sensed.areasHere));
            // AA_ROUND3 3: each ore goal's discovery options (fair on), cheapest first; the choice is logged when it changes
            Map<String, List<io.github.mojolowjo.entropybot.aa.brain.Discovery.Opt>> disc = new java.util.HashMap<>();
            if (io.github.mojolowjo.entropybot.sense.Seen.fair()) {
                boolean marked = sensed.feas != null && sensed.feas.mineMarked;
                String stripWhy = !sensed.areasHere ? "outside my areas" : null;
                for (var g : goals) {
                    if (!g.r.ore()) continue;
                    String fam = io.github.mojolowjo.entropybot.sense.KnownOre.familyOf(g.r.key());
                    List<String> dd = new ArrayList<>();
                    var opts = io.github.mojolowjo.entropybot.aa.brain.Discovery.rank(discoveryFacts(dim, me, fam, caveDist, marked, stripWhy, true),
                            g.r.key().replaceFirst("^.*:", ""), Math.max(1, g.n - io.github.mojolowjo.entropybot.aa.brain.Goals.have(bb.in.bag, g)), dd);
                    disc.put(g.r.key(), opts);
                    String line = opts.isEmpty() ? "none (" + String.join("; ", dd) + ")" : opts.get(0).text() + (opts.size() > 1 ? "; then " + opts.get(1).text() : "");
                    String head = opts.isEmpty() ? "none" : opts.get(0).label();       // logged when the chosen way changes, not its price
                    if (!head.equals(discLines.get(g.r.key()))) {
                        discLines.put(g.r.key(), head);
                        if (known.getOrDefault(g.r.key(), 0) == 0) LOG.info("[entropybot] aa: goal {}: none seen; discovery: {}", g.r.key(), line);
                    }
                }
            }
            bb.fact(io.github.mojolowjo.entropybot.aa.brain.Discovery.FACT, disc);
        }
    }

    // ---- B7: schematic builds (2.12) ----

    private boolean buildsDue;

    /** Every 10 s while builds exist (at once after aa build): each build scanned in its dimension, a finished one said and dropped. */
    private void buildsTick(LocalPlayer p, long tick) {
        var builds = io.github.mojolowjo.entropybot.aa.brain.Blueprint.of(bb);
        if (builds.isEmpty() || (tick % 200 != 11 && !buildsDue)) return;
        buildsDue = false;
        String dim = p.level().dimension().location().toString();
        for (var b : new ArrayList<>(builds)) {
            b.lastStop = SchematicBuild.LAST_STOP.get(b.name);
            if (b.dim != null && !b.dim.equals(dim)) continue;
            var l = SchematicBuild.LOADED.get(b.name);
            if (l == null) continue;
            b.fails = SchematicBuild.FAILS.getOrDefault(b.name, 0);
            io.github.mojolowjo.entropybot.aa.brain.Blueprint.Scan sc;
            try {
                sc = SchematicBuild.scan(l, b.origin, p.level());
            } catch (RuntimeException e) {
                LOG.warn("[entropybot] aa: build {} scan failed: {}", b.name, e.toString());
                continue;
            }
            if (sc == null) {                         // review 2: part of it not loaded: the last scan stays; none yet = go there and build
                if (b.scan == null) b.scan = new io.github.mojolowjo.entropybot.aa.brain.Blueprint.Scan(Map.of(), Map.of(), 0, 1, 0, 0, 1);
                continue;
            }
            b.scan = sc;
            if (b.scan.done()) {
                builds.remove(b);
                tree.brain.remove(b.id(), "build done");
                SchematicBuild.LAST_STOP.remove(b.name);
                String msg = "built " + b.name + " (" + b.scan.placed + "/" + b.scan.total + " placed" + (b.scan.occupied > 0 ? ", " + b.scan.occupied + " cells held by other blocks" : "") + ")";
                LOG.info("[entropybot] aa: {}", msg);
                c.whisper(c.owner(), "aa: " + msg);
            }
        }
    }

    /** aa build <name> here|at x y z [high|low]: the file read, its material list said, a build goal made. */
    String build(String arg) {
        var a = io.github.mojolowjo.entropybot.aa.brain.Blueprint.parse(arg);
        if (a.err() != null) return a.err();
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return "error: no world";
        String[] err = new String[1];
        var l = SchematicBuild.load(a.name(), err);              // read again each time (the owner may have changed the file)
        if (l == null) return "aa build: " + err[0].replaceFirst("^error: ", "");
        int[] o = a.at() != null ? a.at() : Jobs.here(mc.player);
        var b = new io.github.mojolowjo.entropybot.aa.brain.Blueprint.Build(a.name(), l.file().getName(), o, a.prio(), System.currentTimeMillis(), l.need(), l.skipped());
        b.dim = mc.level.dimension().location().toString();
        io.github.mojolowjo.entropybot.aa.brain.Blueprint.add(bb, b);
        tree.brain.remove(b.id(), "aa build again");
        tree.brain.add(io.github.mojolowjo.entropybot.aa.AaDesires.ownerBuild(b));
        SchematicBuild.LAST_STOP.remove(b.name);
        SchematicBuild.FAILS.remove(b.name);
        buildsDue = true;
        var s = l.s();
        LOG.info("[entropybot] aa: build goal {} at {} {} {}", b.name, o[0], o[1], o[2]);
        return "ok: build " + b.name + " (" + l.file().getName() + ", " + s.widthX() + "x" + s.heightY() + "x" + s.lengthZ() + ", " + s.heightY() + " layers) at "
                + o[0] + " " + o[1] + " " + o[2] + (b.prio > 0 ? " high" : b.prio < 0 ? " low" : "") + ": "
                + io.github.mojolowjo.entropybot.aa.brain.Blueprint.materials(l.need())
                + (l.skipped().isEmpty() ? "" : "; left out: " + String.join(", ", l.skipped()))
                + "; I only place (blocks in the way stay); aa goals shows the layer" + (on() ? "" : " (aa is off: aa on to work on it)");
    }

    /** aa want <thing> [n] [high|low] (also the wants by name: house/build, explore, tidy). */
    String want(String arg) {
        var a = io.github.mojolowjo.entropybot.aa.brain.Goals.parse(arg);
        if (a.err() != null) return a.err();
        String w = a.word();
        String wantKey = switch (w) {
            case "house", "build" -> "build";
            case "explore", "tidy" -> w;
            default -> null;
        };
        long now = System.currentTimeMillis();
        if (wantKey != null) {         // a want by name: bored now (it wins by its weight when nothing more urgent runs)
            io.github.mojolowjo.entropybot.aa.brain.Wants.done(bb, "want." + wantKey, now - (long) (100 * io.github.mojolowjo.entropybot.aa.brain.Wants.DAY_MS));
            return "ok: want." + wantKey + " is bored now (weight " + io.github.mojolowjo.entropybot.aa.brain.Personality.fmt(io.github.mojolowjo.entropybot.aa.brain.Personality.of(bb).get(wantKey))
                    + "; aa like " + wantKey + " <n> to raise it)";
        }
        for (var d : tree.brain.desires())
            if (d.id.equals("needs." + w)) return w + " is one of my needs: aa do " + w + " runs it now";
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return "error: no world";
        var r = io.github.mojolowjo.entropybot.aa.brain.Thing.resolve(w, catalog(mc));
        if (r.err() != null) {
            for (var d : tree.brain.desires())       // review: an ore/item word wins; a need name only when nothing resolves
                if (d.id.equals("needs." + w)) return w + " is one of my needs: aa do " + w + " runs it now";
            return "aa want: " + r.err();
        }
        var g = io.github.mojolowjo.entropybot.aa.brain.Goals.add(bb, r, a.n(), a.prio(), now);
        lootCtx();
        String lootLine = "";
        if (!r.ore() && bb.fact(io.github.mojolowjo.entropybot.aa.brain.LootBook.FACT) instanceof io.github.mojolowjo.entropybot.aa.brain.LootBook book) {
            String from = book.sourcesText(r.ids().get(0), 3);
            var lp = io.github.mojolowjo.entropybot.aa.brain.Goals.loot(bb, g, g.n);
            lootLine = from != null ? "; " + from + (lp != null && lp.text() != null ? " - " + lp.text() : "")
                    : book.isEmpty() ? "; loot data: " + book.origin : "";
        }
        tree.brain.add(io.github.mojolowjo.entropybot.aa.AaDesires.ownerGoal(g));
        knownDue = true;                              // review: known positions now, not in up to 10 s
        String blocked = io.github.mojolowjo.entropybot.aa.brain.Goals.blocked(bb, g);
        LOG.info("[entropybot] aa: owner goal {} x{} prio {}: {}{}", r.key(), g.n, g.prio, r.text(), blocked == null ? "" : " - parked: " + blocked);
        return "ok: goal " + r.key() + " x" + g.n + (g.prio > 0 ? " high" : g.prio < 0 ? " low" : "") + " (score "
                + Math.round(io.github.mojolowjo.entropybot.aa.brain.Goals.score(bb, g)) + "): " + r.text()
                + lootLine + (blocked != null ? " - parked: " + blocked : "") + (on() ? "" : " (aa is off: aa on to work on it)");
    }

    /** aa drop <thing|all>. */
    String drop(String arg) {
        var goals = io.github.mojolowjo.entropybot.aa.brain.Goals.of(bb);
        String w = arg == null ? "" : arg.trim().toLowerCase(java.util.Locale.ROOT).replaceAll("\\s+", "_");
        if (w.isEmpty()) return "usage: aa drop <thing>|all";
        var builds = io.github.mojolowjo.entropybot.aa.brain.Blueprint.of(bb);
        if (goals.isEmpty() && builds.isEmpty()) return "no owner goals";
        List<String> gone = new ArrayList<>();
        for (var b : new ArrayList<>(builds)) {              // B7: builds by name
            if (!w.equals("all") && !b.name.equals(w)) continue;
            builds.remove(b);
            tree.brain.remove(b.id(), "aa drop");        // halts its running task too
            SchematicBuild.LOADED.remove(b.name);
            SchematicBuild.LAST_STOP.remove(b.name);
            SchematicBuild.FAILS.remove(b.name);
            gone.add("build " + b.name);
        }
        for (var g : new ArrayList<>(goals)) {
            boolean hit = w.equals("all") || g.r.key().equals(w) || g.r.key().endsWith(":" + w) || g.r.word().trim().toLowerCase(java.util.Locale.ROOT).replaceAll("\\s+", "_").equals(w)
                    || g.r.ids().contains(w.contains(":") ? w : "minecraft:" + w);
            if (!hit) continue;
            goals.remove(g);
            tree.brain.remove(g.id(), "aa drop");
            gone.add(g.r.key());
        }
        if (gone.isEmpty()) {
            List<String> keys = new ArrayList<>();
            for (var g : goals) keys.add(g.r.key());
            for (var b : builds) keys.add(b.name);
            return "no goal " + w + " (goals: " + String.join(", ", keys) + ")";
        }
        LOG.info("[entropybot] aa: dropped goals {}", gone);
        return "ok: dropped " + String.join(", ", gone);
    }

    /** aa goals: each goal with its count, score and next step, then the top ideas. */
    String goalsText() {
        var goals = io.github.mojolowjo.entropybot.aa.brain.Goals.of(bb);
        List<String> out = new ArrayList<>();
        for (var g : goals) out.add(io.github.mojolowjo.entropybot.aa.brain.Goals.line(bb, g, scoreOf(g.id())));
        for (var b : io.github.mojolowjo.entropybot.aa.brain.Blueprint.of(bb)) out.add("build " + io.github.mojolowjo.entropybot.aa.brain.Blueprint.line(bb, b, scoreOf(b.id())));
        return "owner goals" + (out.isEmpty() ? ": none (aa want <thing> [n] [high|low] | aa build <name> here)" : " (" + out.size() + "):\n" + String.join("\n", out))
                + "\n" + ideasLine();
    }

    /** "goals: iron_ore 0/5 70" for aa status (empty when none). */
    String goalsLine() {
        var goals = io.github.mojolowjo.entropybot.aa.brain.Goals.of(bb);
        var builds = io.github.mojolowjo.entropybot.aa.brain.Blueprint.of(bb);
        if (goals.isEmpty() && builds.isEmpty()) return "goals: none";
        List<String> out = new ArrayList<>();
        for (var g : goals) out.add(g.r.key() + " " + io.github.mojolowjo.entropybot.aa.brain.Goals.have(bb.in.bag, g) + "/" + g.n + " " + scoreOf(g.id()));
        for (var b : builds) out.add("build " + b.name + (b.scan == null ? " (not scanned)" : " layer " + Math.min(b.scan.layer + 1, b.scan.layers) + "/" + b.scan.layers + " " + b.scan.placed + "/" + b.scan.total) + " " + scoreOf(b.id()));
        return "goals: " + String.join(", ", out);
    }

    private String scoreOf(String id) {
        for (var s : tree.brain.lastScored())
            if (s.d().id.equals(id)) return "score " + Math.round(s.score()) + (s.skip() != null ? " (" + s.skip() + ")" : "")
                    + (tree.brain.runningDesire() == s.d() ? " running" : "");
        return "score - (not scored yet)";
    }

    String wantsLine() { return io.github.mojolowjo.entropybot.aa.AaDesires.wantsLine(bb, tree.brain.lastScored()); }

    /** aa stats: measured runs, the tech tree and its ideas. */
    String statsText() {
        return runsText() + "\n" + tree.brain.stats.text() + "\n" + io.github.mojolowjo.entropybot.aa.AaDesires.ideasLine(bb, 8) + (treeError != null ? "\n!! " + treeError : "");
    }

    String ideasLine() { return io.github.mojolowjo.entropybot.aa.AaDesires.ideasLine(bb, 5); }

    /** The dashboard's live AA tree (fast channel GET /aa), built from the running tree. */
    com.google.gson.JsonObject live() { return io.github.mojolowjo.entropybot.aa.AaLive.json(tree, bb, on(), io.github.mojolowjo.entropybot.aa.AaLive.SWITCHES); }

    /** BRAIN_PLAN B1: the top desires with their scores (aa why / aa status). */
    List<String> brainLines(int n) { return tree.brain.whyLines(n); }

    String brainCounters() { return tree.brain.counters(System.currentTimeMillis()); }

    List<String> decisions() { return tracer.decisions(); }

    /** state.json's "aa": {on, driver, task, danger}. */
    JsonObject stateBlock() {
        JsonObject o = new JsonObject();
        o.addProperty("on", on());
        if (on()) {
            o.addProperty("driver", tree.driver());
            if (bb.task() != null) o.addProperty("task", bb.task().spec().chain());
            o.addProperty("danger", tree.dangerWhy());
            o.addProperty("status", statusLine());
        }
        return o;
    }

    /** check's findings. */
    List<String[]> findings() {
        List<String[]> out = new ArrayList<>();
        if (offReason != null) out.add(new String[]{"aa", "aa " + offReason, "aa on (after a look at the log)"});
        if (on() && tracer.counts.lost > 0) out.add(new String[]{"aalost", "aa lost " + tracer.counts.lost + " task(s) to someone else (a poster not gated)", "aa why"});
        if (on() && c.brainRuntime != null && c.brainRuntime.brain.on()) out.add(new String[]{"aabrain", "aa and the brain are both on", "brain off"});
        return out;
    }

    /** entropybot/worlds/&lt;world&gt;/aa (per world, like the map: a fresh test copy starts with empty files). */
    private Path dir() {
        return Core.INSTANCE.files() == null ? null : Core.INSTANCE.files().worldRoot().resolve("aa");
    }

    /** 0.36.1 (review 8): the file writes run on one background thread (the JSON text is built on the game thread). */
    private static final java.util.concurrent.ExecutorService WRITER = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "entropybot-aa-writer");
        t.setDaemon(true);
        return t;
    });
    /** 0.36.2 (review 8): on quit the queued writes still land (the writer is a daemon; the JVM would drop them). */
    static {
        try {
            Runtime.getRuntime().addShutdownHook(new Thread(AaRuntime::flushWrites, "entropybot-aa-flush"));
        } catch (RuntimeException e) {
            LOG.warn("[entropybot] aa: no shutdown flush: {}", e.toString());
        }
    }

    /** Lets the queued writes finish (at most 3 s; each write carries its own path, so a world change is safe). */
    static void flushWrites() {
        try {
            WRITER.submit(() -> { }).get(3, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            LOG.warn("[entropybot] aa: flush of the aa files: {}", e.toString());
        }
    }
    private volatile long writeErrors, lastWriteErrorAt;

    private void snapshot(long now, String driver) {
        if (now - lastSnapshotAt < 1000) return;
        lastSnapshotAt = now;
        Path d = dir();
        if (d == null) return;
        String json = tracer.snapshot(now, on(), driver, bb.taskJson(now), bb.in);
        WRITER.submit(() -> {
            try {
                Files.createDirectories(d);
                Path tmp = d.resolve("snapshot.json.tmp"), f = d.resolve("snapshot.json");
                Files.writeString(tmp, json, StandardCharsets.UTF_8);
                Files.move(tmp, f, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException | RuntimeException e) {
                writeFailed("snapshot", e);
            }
        });
    }

    private void appendDecision(String line) {
        Path d = dir();
        if (d == null) return;
        boolean rewrite = ++decisionLines > 2 * Tracer.KEEP_DECISIONS;
        String text = rewrite ? String.join("\n", tracer.decisions()) + "\n" : line + "\n";
        if (rewrite) decisionLines = tracer.decisions().size();
        WRITER.submit(() -> {
            try {
                Files.createDirectories(d);
                Path f = d.resolve("decisions.jsonl");
                if (rewrite) Files.writeString(f, text, StandardCharsets.UTF_8);      // keep the file short: the last 200 lines
                else Files.writeString(f, text, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException | RuntimeException e) {
                writeFailed("decisions", e);
            }
        });
    }

    /** The last 5 rows of runs.csv (aa stats). */
    private String runsText() {
        try {
            Path f = runsFile();
            return io.github.mojolowjo.entropybot.aa.brain.RunLedger.statsText(f != null && Files.exists(f) ? Files.readString(f, StandardCharsets.UTF_8) : "", 5);
        } catch (IOException | RuntimeException e) {
            return "runs: could not read runs.csv: " + e;
        }
    }

    private Path runsFile() { return Core.INSTANCE.files() == null ? null : Core.INSTANCE.files().root().resolve("runs.csv"); }

    /** AA round 2 C: appends this run's row to entropybot/runs.csv (header once). A no-op when no run is open. Never throws. */
    void appendRun() {
        if (runStartMs < 0) return;
        long start = runStartMs;
        runStartMs = -1;
        try {
            Path f = runsFile();
            if (f == null) return;
            Minecraft mc = Minecraft.getInstance();
            String diff = mc.level == null ? "?" : mc.level.getDifficulty().getKey();
            String world = Core.INSTANCE.files().worldRoot().getFileName().toString();
            var r = new io.github.mojolowjo.entropybot.aa.brain.RunLedger.Row(Core.INSTANCE.version(), world, diff,
                    runMarks.stoneMin(), runMarks.tableFurnaceMin(), runMarks.ironMin(), runPicks,
                    tracer.counts.switches - runSwitches0, tracer.counts.pointless - runPointless0, tracer.counts.preempts - runPreempts0,
                    runStuckHalts, runDeaths, runMarks.foodEvents(), (System.currentTimeMillis() - start) / 60000.0);
            boolean has = Files.exists(f) && Files.size(f) > 0;
            String text = io.github.mojolowjo.entropybot.aa.brain.RunLedger.append(has, r);
            WRITER.submit(() -> {
                try {
                    Files.createDirectories(f.getParent());
                    Files.writeString(f, text, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                } catch (IOException | RuntimeException e) {
                    writeFailed("runs.csv", e);
                }
            });
            LOG.info("[entropybot] aa: run row -> runs.csv ({} min)", String.format(java.util.Locale.ROOT, "%.1f", r.totalMin()));
        } catch (IOException | RuntimeException e) {
            writeFailed("runs.csv", e);
        }
    }

    private void writeFailed(String what, Exception e) {
        writeErrors++;
        long now = System.currentTimeMillis();
        if (now - lastWriteErrorAt > 60_000) {
            lastWriteErrorAt = now;
            LOG.warn("[entropybot] aa: {} write failed ({} so far): {}", what, writeErrors, e.toString());
        }
    }
}
