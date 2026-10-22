package io.github.mojolowjo.entropybot.commands;

import io.github.mojolowjo.entropybot.aa.brain.Tuning;

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
import io.github.mojolowjo.entropybot.sense.BrainState;
import io.github.mojolowjo.entropybot.sense.Feasibility;
import io.github.mojolowjo.entropybot.engine.Reflexes;
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
 * from {@code SenseRuntime.sense()} (reused, not copied), implements {@link JobPort} over Chains/Jobs (AA's chain is
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
    static long holdMs() { return Tuning.l("aaRuntime.holdMs"); }
    static int errorsPerMinuteOff() { return Tuning.i("aaRuntime.errorsPerMinuteOff"); }

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
     * way that runs ("pillar" job / "climb": the way up's typed steps), the stuck end (the task's failure text if no
     * way out works), escapes in this task; the typed steps to go on with afterwards are {@code escapeResumeSteps}.
     */
    private boolean escapePending;
    private String escapeWay, escapeFail;
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
        var oldKeys = io.github.mojolowjo.entropybot.aa.AaSwitch.dropOldGlobal(d);      // live-facts: the switch moved into the world's part
        if (!oldKeys.isEmpty()) {
            LOG.info("[entropybot] aa: the old bot-wide switch keys {} dropped; on/off is per world now (starts off in each world)", oldKeys);
            c.saved();
        }
        worldLoaded();
        bb.fact(io.github.mojolowjo.entropybot.aa.RungOptions.SURFACE_FACTS, perTick(new io.github.mojolowjo.entropybot.aa.FactsMemo<>(0), timed("surface", this::surfaceFacts)));
        bb.fact(io.github.mojolowjo.entropybot.aa.NeedChain.WORLD_FACT, (java.util.function.Supplier<io.github.mojolowjo.entropybot.aa.NeedChain.World>) this::needWorld);
        bb.fact(io.github.mojolowjo.entropybot.aa.FoodOptions.FACTS, (java.util.function.Supplier<io.github.mojolowjo.entropybot.aa.FoodOptions.FoodFacts>) this::foodFacts);
        NeedFacts.install(bb, this);          // round 4A N wiring: the station, bed, base and bag facts (O's sets)
        bb.fact(io.github.mojolowjo.entropybot.aa.AaDesires.STATS_FACT, tree.brain.stats);
        // AA_ROUND3 2: the sequencer's horizon (aa horizon, saved) and the nearest known coal (torches' site, cached 10 s)
        applyFair(!d.has("fair") || d.get("fair").getAsBoolean());     // AA_ROUND3 3: default on
        if (d.has("horizon")) bb.fact(io.github.mojolowjo.entropybot.aa.brain.Brain.HORIZON_FACT, d.get("horizon").getAsInt());
        bb.fact(io.github.mojolowjo.entropybot.aa.AaDesires.THREAT_FACT, (java.util.function.Supplier<io.github.mojolowjo.entropybot.threat.ThreatFacts>) this::threatFacts);     // AA_CLEANUP 4
        bb.fact(io.github.mojolowjo.entropybot.aa.brain.Brain.SURFACE_Y_FACT, (java.util.function.IntBinaryOperator) AaRuntime::surfaceY);        // AA_ROUND4 B1
        loadTuning();                                                                                                                        // AA_ROUND4 B3
        c.chains.onEnd(this::chainEnded);
        slow = new AaSlow(c, this);                                  // AA_STEP3 B: slow mode + the prediction ledger
        c.chains.stepGate(slow::hold);
    }

    // ---- AA_STEP3 B: slow mode (AaSlow) ----

    final AaSlow slow;

    io.github.mojolowjo.entropybot.aa.Options.Option runningOption() { return runningOpt; }

    long taskSeq() { return current; }

    boolean ownChainRunning() { return ownRunning(); }

    /** rm-execution: AA's own task runs: its typed steps, or its chain (an option set not on typed steps yet). */
    private boolean ownRunning() { return typedRun ? c.stepRun.running() : chainRunning(); }

    /** Danger and the way out of a hole are never held by slow mode. */
    boolean slowSkip() { return "danger".equals(tree.driver()) || escapePending || escapeWay != null; }

    void poke() { progress++; }

    String desireId() {
        var rd = tree.brain.runningDesire();
        return rd == null ? "" : rd.id;
    }

    String objective() {
        String ch = bb.task() == null ? "" : bb.task().spec().chain();
        return desireId() + (ch == null || ch.isEmpty() ? "" : ": " + ch);
    }

    io.github.mojolowjo.entropybot.aa.Options.Choice lastChoice() {
        var op = runningOpt == null ? null : pickerOf(runningOpt);
        return op == null ? null : op.last();
    }

    static void background(Runnable r) { WRITER.submit(r); }

    // ---- AA_ROUND3 4: facts timing (the pick's cost lives in its facts supplier) ----

    private final Map<String, Long> slowLogAt = new java.util.HashMap<>();
    /** The last facts breakdown ("ores 1200 us, discovery 300 us"), set by the suppliers that measure their parts. */
    private String factsParts = "";

    /** The game tick (level time), -1 without a level: a facts supplier is read once per tick (the pick, the re-score, the sequencer). */
    private static long gameTick() {
        var lv = Minecraft.getInstance().level;
        return lv == null ? -1 : lv.getGameTime();
    }

    static <T> java.util.function.Supplier<T> perTick(io.github.mojolowjo.entropybot.aa.FactsMemo<T> m, java.util.function.Supplier<T> s) {
        return () -> m.get(gameTick(), System.currentTimeMillis(), null, s);
    }

    /** A facts supplier that logs a call over 2 ms (with its parts), at most every 10 s per supplier. */
    private <T> java.util.function.Supplier<T> timed(String name, java.util.function.Supplier<T> s) {
        return () -> {
            long t0 = System.nanoTime();
            factsParts = "";
            T v = s.get();
            long us = (System.nanoTime() - t0) / 1000;  // structural: milliseconds per second
            long now = System.currentTimeMillis();
            if (us > Tuning.i("aaRuntime.slowCallUs") && now - slowLogAt.getOrDefault(name, 0L) >= Tuning.i("aaRuntime.slowLogMs")) {
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
    static long foodFactsMs() { return Tuning.l("aaRuntime.foodFactsMs"); }
    static int foodR() { return Tuning.i("aaRuntime.foodR"); }
    static int foodDy() { return Tuning.i("aaRuntime.foodDy"); }

    /**
     * aa-food: the FOOD rung's facts (cached {@link #FOOD_FACTS_MS}): the bag, the stock target, a furnace, the animals
     * the hunt may take (Hunting.animalsNear) and the walk to the nearest of them, and one scan of the blocks within
     * {@link #FOOD_R} (y +-{@link #FOOD_DY}): oak/dark-oak logs, ripe berry bushes, mushrooms, water, kelp, a grass patch.
     * Null when nothing can be read; a throw is caught by the picker (logged, counted) and by the desire's park check.
     */
    /**
     * A field spot by the water source at (x, y, z): a neighbour at the water's level that is grass or dirt with air (or a
     * plant) above it, the policy allowing: the bot stands on it (y + 1) and "farm plant wheat here 4" has the water in reach.
     */
    private int[] fieldSpot(net.minecraft.world.level.Level lv, int x, int y, int z, String dim) {
        int[][] side = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] s : side) {
            int nx = x + s[0], nz = z + s[1];
            var g = lv.getBlockState(new net.minecraft.core.BlockPos(nx, y, nz));
            var up = lv.getBlockState(new net.minecraft.core.BlockPos(nx, y + 1, nz));
            boolean soil = g.is(net.minecraft.world.level.block.Blocks.GRASS_BLOCK) || g.is(net.minecraft.world.level.block.Blocks.DIRT);
            if (soil && (up.isAir() || up.canBeReplaced()) && up.getFluidState().isEmpty() && c.workAllowed(dim, nx, nz)) return new int[]{nx, y + 1, nz};
        }
        return null;
    }

    /** AA_ROUND3 5: the herds I have seen in this dimension (Sightings kind ANIMAL), confidence decayed to now (game time). */
    static List<io.github.mojolowjo.entropybot.aa.FoodOptions.Herd> herds(net.minecraft.world.level.Level lv, int[] me) {
        List<io.github.mojolowjo.entropybot.aa.FoodOptions.Herd> out = new ArrayList<>();
        var sg = io.github.mojolowjo.entropybot.sense.ChunkPass.INSTANCE.sightings();
        String dim = io.github.mojolowjo.entropybot.sense.ChunkPass.INSTANCE.dim();
        if (sg == null || dim == null || lv == null) return out;
        long game = lv.getGameTime() * 50;  // game fact: a game tick is 50 ms
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
        for (var e : lv.getEntitiesOfClass(net.minecraft.world.entity.animal.Animal.class, p.getBoundingBox().inflate(io.github.mojolowjo.entropybot.commands.StepRun.animalRange())))
            inView.add(net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString());
        int[] me = Jobs.here(p);
        Map<String, Double> score = new java.util.HashMap<>();
        for (var h : herds(lv, me)) {
            String id = h.type().indexOf(':') >= 0 ? h.type() : "minecraft:" + h.type();
            if (inView.contains(id) || h.conf() < io.github.mojolowjo.entropybot.aa.FoodOptions.herdMinConf() || h.dist() > io.github.mojolowjo.entropybot.aa.FoodOptions.herdMax()) continue;
            double s = (2 * h.dist() + Tuning.i("aaRuntime.herdSetupS")) / h.conf();
            if (s < score.getOrDefault(id, Double.MAX_VALUE)) {
                score.put(id, s);
                out.put(id, new int[]{h.x(), h.z(), (int) Math.round(h.dist()), (int) Math.round(h.conf() * 100)});  // structural: percent
            }
        }
        return out;
    }

    io.github.mojolowjo.entropybot.aa.FoodOptions.FoodFacts foodFacts() {
        long now = System.currentTimeMillis();
        // review: the cache holds only while the bag, the bar and the furnace are unchanged (a hunt/cook/eat refreshes it)
        if (foodCache != null && now - foodCacheAt < foodFactsMs() && sensed != null && sensed.feas != null
                && foodCache.bag().equals(sensed.feas.bag) && foodCache.food() == bb.in.food
                && foodCache.furnace() == (bb.in.furnaceKnown || bb.in.furnaceInBag)) return foodCache;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null || sensed == null || sensed.feas == null) return null;
        var lv = mc.level;
        int[] me = Jobs.here(p);
        Map<String, Integer> bag = sensed.feas.bag;
        Map<String, Integer> animals = c.stepRun.animalsNear(p);
        // owner's rule 2026-10-19: the walk to the animals is priced (FoodOptions.huntRow): the nearest one the hunt may take
        double huntDist = 0;
        double nearest = Double.MAX_VALUE;
        for (var e : lv.getEntitiesOfClass(net.minecraft.world.entity.animal.Animal.class, p.getBoundingBox().inflate(io.github.mojolowjo.entropybot.commands.StepRun.animalRange()))) {
            String type = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getPath();
            if (!animals.containsKey(type) || e.isBaby() || e.hasCustomName() || io.github.mojolowjo.entropybot.engine.Hostility.protectedMob(e)) continue;
            nearest = Math.min(nearest, p.distanceTo(e));
        }
        if (nearest < Double.MAX_VALUE) huntDist = nearest;
        int[] oak = null, berry = null, red = null, brown = null;
        String oakType = "oak";
        double oakD = Double.MAX_VALUE, berryD = Double.MAX_VALUE, redD = Double.MAX_VALUE, brownD = Double.MAX_VALUE;
        boolean water = false, kelp = false;
        int[] waterSpot = null;
        double waterSpotD = Double.MAX_VALUE;
        String wdim = io.github.mojolowjo.entropybot.guard.Guard.dimOf(lv);
        Map<Long, Integer> grassCells = new java.util.HashMap<>();
        var m = new net.minecraft.core.BlockPos.MutableBlockPos();
        for (int x = me[0] - foodR(); x <= me[0] + foodR(); x++)
            for (int z = me[2] - foodR(); z <= me[2] + foodR(); z++)
                for (int y = me[1] - foodDy(); y <= me[1] + foodDy(); y++) {
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
                        if (d <= Tuning.i("aaRuntime.waterNear")) water = true;
                        // aa383 (a field "no water within 4"): a dry cell beside the water to stand on and plant around
                        if (d < waterSpotD) {
                            int[] s = fieldSpot(lv, x, y, z, wdim);
                            if (s != null) { waterSpotD = d; waterSpot = s; }
                        }
                    }
                }
        // a grass patch: the 8x8 cell with the most grass (10 at least), the policy allowing
        int[] grass = null;
        int bestG = 9;
        String dim = io.github.mojolowjo.entropybot.guard.Guard.dimOf(lv);
        for (var e : grassCells.entrySet()) {
            if (e.getValue() <= bestG) continue;
            long k = e.getKey();
            int cx = ((int) (k >> 32)) << 3, cz = ((int) k) << 3;
            if (!c.workAllowed(dim, cx + 4, cz + 4)) continue;  // structural: centre of an 8x8 cell
            bestG = e.getValue();
            grass = new int[]{cx, cz, cx + 7, cz + 7};  // structural: corner of an 8x8 cell
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
                animals, c.stepRun.ownerOff() ? "hunting is off (the owner's hunting off; hunting on lets me)" : null,
                oak, oakType, oak == null ? -1 : oakD, berry, berry == null ? -1 : berryD, red, brown, grass, water, farm,
                Math.max(0, stored), kelp, me, p.getHealth(), huntDist, waterSpot, herds(lv, me));
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
                // one-planner: food has a source when the chain for the food need stands without an explore
                var fw = needWorld();
                var fch = fw == null ? null : io.github.mojolowjo.entropybot.aa.NeedChain.resolveAny("food", io.github.mojolowjo.entropybot.aa.brain.ItemWorth.plainFoods(),
                        Tuning.i("needs.foodItems"), fw, io.github.mojolowjo.entropybot.aa.brain.ItemWorth::energy);
                String park = fch != null && fch.ok() && !fch.explore() ? null : io.github.mojolowjo.entropybot.aa.FoodOptions.noSource(foodFacts());
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
    static long drainWindowMs() { return Tuning.l("aaRuntime.drainWindowMs"); }

    /**
     * aa-food: one drain sample per stretch of one task kind (FoodModel): closed when the kind changes, the bot ate
     * (the energy rose) or {@link #DRAIN_WINDOW_MS} passed; samples under a minute are dropped (BrainStats.recordDrain).
     */
    private void drainTick(long now) {
        if (!bb.in.hungerDrains) { drainE0 = -1; return; }
        double e = bb.in.food + bb.in.saturation;
        String kind = (bb.task() == null ? io.github.mojolowjo.entropybot.aa.core.TaskKind.NONE : bb.task().kind).name();
        if (drainE0 < 0) { drainE0 = e; drainT0 = now; drainKind = kind; return; }
        boolean ate = e > drainE0 + 0.01;  // structural: epsilon: a food reading rose
        if (ate || !kind.equals(drainKind) || now - drainT0 >= drainWindowMs()) {
            double minutes = (now - drainT0) / 60_000.0;  // structural: milliseconds per minute
            if (!ate && tree.brain.stats.recordDrain(drainKind, drainE0 - e, minutes))
                LOG.info("[entropybot] aa: food drain {}: {} points in {} min", drainKind, String.format(java.util.Locale.ROOT, "%.1f", drainE0 - e), String.format(java.util.Locale.ROOT, "%.1f", minutes));
            drainE0 = e;
            drainT0 = now;
            drainKind = kind;
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
            boolean own = pol != null && pol.typeAt(dim, me[0], me[1], me[2]) == io.github.mojolowjo.entropybot.guard.AreaType.BASE;
            var sg = io.github.mojolowjo.entropybot.sense.ChunkPass.INSTANCE.sightings();
            var cave = sg == null ? null : sg.nearestCave(dim, me[0], me[1], me[2], io.github.mojolowjo.entropybot.aa.brain.Discovery.seenCaveWithin());
            return new io.github.mojolowjo.entropybot.guard.Litter.Site(near, bb.in.pickTier, 3, 0, cave == null ? 0 : cave.conf(), Math.max(0, oreCount - 1), 0, own);
        } catch (RuntimeException e) {
            LOG.debug("[entropybot] aa: litter site: {}", e.toString());
            return null;
        }
    }

    /** AA_ROUND3 4: the staircase steps down whose cells are read per heading (the pick-time budget). */
    static int stairRead() { return Tuning.i("aaRuntime.stairRead"); }

    private List<io.github.mojolowjo.entropybot.aa.WayUp.Heading> downsCache;
    private final io.github.mojolowjo.entropybot.aa.FactsMemo<int[]> furnaceMemo = new io.github.mojolowjo.entropybot.aa.FactsMemo<>(10_000);
    private String downsKey;
    private long downsAt = -1, downsTtl = 10_000;

    /**
     * AA_ROUND3 4: the staircase down per heading (StairPlan.cells, the 3 cells a step digs): every column checked
     * against the policy (all {@code n} steps: no keep-out / no-break box on the way, place mine marks the bottom), the cells of the
     * first {@link #STAIR_READ} steps read for the policy and hazards (cellAt: about 20 us a cell, so 4 x 16 x 3 keeps the pick under ~8 ms; the rest is priced as plain stone by Discovery). Cached 10 s for the same spot and depth.
     */
    private List<io.github.mojolowjo.entropybot.aa.WayUp.Heading> downHeadings(net.minecraft.world.level.Level lv, int[] me, int n) {
        long now = System.currentTimeMillis();
        String key = io.github.mojolowjo.entropybot.guard.Guard.dimOf(lv) + " " + me[0] + " " + me[1] + " " + me[2] + " " + n;
        if (downsCache != null && key.equals(downsKey) && now - downsAt < downsTtl) return downsCache;
        List<io.github.mojolowjo.entropybot.aa.WayUp.Heading> out = new java.util.ArrayList<>();
        for (String d : io.github.mojolowjo.entropybot.aa.WayUp.DIRS) {
            int[] u = io.github.mojolowjo.entropybot.aa.WayUp.dir(d);
            int[] refused = null;
            for (int i = 1; i <= n && refused == null; i++) {
                int x = me[0] + u[0] * i, y = me[1] - i, z = me[2] + u[1] * i;
                if (!Clearing.boxAllowed(io.github.mojolowjo.entropybot.clear.ClearBox.of(x, y, z, x, y + 2, z))) refused = new int[]{x, y, z};
            }
            List<io.github.mojolowjo.entropybot.aa.Replan.Cell> cells = new java.util.ArrayList<>();
            if (refused == null)
                for (int[] c : StairPlan.cells(me[0], me[1], me[2], u[0], u[1], Math.min(n, stairRead()))) {
                    var cell = cellAt(lv, c[0], c[1], c[2]);
                    cells.add(cell);
                    if (Double.isInfinite(cell.mult())) {         // dropped anyway (WayUp.ground): no more reads, say where
                        LOG.debug("[entropybot] aa: stairs down {} stops at {} {} {} (may not be broken or not loaded)", d, c[0], c[1], c[2]);
                        break;
                    }
                }
            out.add(new io.github.mojolowjo.entropybot.aa.WayUp.Heading(d, n, cells, refused));
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
        if (caving && sn.caving && caveTickAt >= 0) sn.caveTime((now - caveTickAt) / 60_000.0);  // structural: milliseconds per minute
        sn.caving = caving;
        caveTickAt = now;
    }

    /**
     * chop B4: the owner's fair switch. Baritone's own legitMine (+ legitMineIncludeDiagonals) is the miner's half: its
     * mine and chop processes target only blocks in view (and their diagonal neighbours). The mod keeps only what legitMine
     * does not cover: the planner's half (sense.Seen: which indexed ore cells count as a known source before the bot goes
     * there). Nothing in the mod filtered Baritone's targets, so no mod rule was a copy of legitMine.
     */
    static void applyFair(boolean on) {
        io.github.mojolowjo.entropybot.sense.Seen.fair(on);
        try {
            var s = baritone.api.BaritoneAPI.getSettings();
            s.legitMine.value = on;
            s.legitMineIncludeDiagonals.value = on;
        } catch (RuntimeException | LinkageError e) {
            LOG.warn("[entropybot] aa: fair {}: Baritone's legitMine not set: {}", on ? "on" : "off", e.toString());
        }
    }

    /** aa fair on|off (saved): seen ore only (default) or today's behaviour (the whole chunk index). */
    String fair(String arg) {
        String a = arg == null ? "" : arg.trim().toLowerCase(java.util.Locale.ROOT);
        var sn = io.github.mojolowjo.entropybot.sense.ChunkPass.INSTANCE.seen();
        String state = "fair " + (io.github.mojolowjo.entropybot.sense.Seen.fair() ? "on" : "off") + " - " + io.github.mojolowjo.entropybot.sense.ChunkPass.INSTANCE.seenStatus()
                + (sn == null ? "" : "; " + sn.caveText()) + "; " + io.github.mojolowjo.entropybot.sense.ChunkPass.INSTANCE.sightingsText();
        if (a.isEmpty()) return state;
        if (!a.equals("on") && !a.equals("off")) return "usage: aa fair on|off (" + state + ")";
        applyFair(a.equals("on"));
        data().addProperty("fair", a.equals("on"));
        knownDue = true;                  // review: the goals' known counts and discovery refresh at once
        c.saved();
        LOG.info("[entropybot] aa: fair {}", a);
        return "ok: aa fair " + a + (a.equals("on") ? " - I go only for ore I have seen (24 blocks, an open face, in view); else I cave or strip mine"
                : " - every ore the chunk index knows counts again (today's behaviour)");
    }

    /**
     * S2 (MINING_PLAN 11): the SURFACE rung's facts, read once per pick: depth (or the hole's way out), the open shaft,
     * throwaway blocks, placing allowed, a pickaxe, the nearest
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
        long t0 = System.nanoTime();
        int[] sky = null;
        double best = Double.MAX_VALUE;
        net.minecraft.core.BlockPos.MutableBlockPos m = new net.minecraft.core.BlockPos.MutableBlockPos();
        for (int x = me[0] - skyR(); x <= me[0] + skyR(); x++)
            for (int z = me[2] - skyR(); z <= me[2] + skyR(); z++) {
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
        factsParts = "sky scan " + (System.nanoTime() - t0) / 1000 + " us";  // structural: milliseconds per second
        boolean tf = !(bb.in.tableKnown || bb.in.tableInBag) || !(bb.in.furnaceKnown || bb.in.furnaceInBag);
        return new io.github.mojolowjo.entropybot.aa.RungOptions.SurfaceFacts(depth, me, shaft, AaEscape.throwaway(bag),
                io.github.mojolowjo.entropybot.guard.PolicyRuntime.INSTANCE.travelMayPlace(), bb.in.pickaxes > 0,
                sky, sky == null ? -1 : best, tf, io.github.mojolowjo.entropybot.aa.brain.ToolWear.kit(bb.in),
                depth > 0 ? column(lv, me, depth) : null, depth > 0 ? headings(lv, me, depth) : null);
    }

    /** The open-sky cell search radius (columns), S2. */
    static int skyR() { return Tuning.i("aaRuntime.skyR"); }

    /**
     * one-planner: what the chain starts from: the bag, a table and a furnace known, the nearest known block of any item
     * ({@link #nearestSource}), the animals of a kind known ({@link #animalsKnown}), the loot data, and where the blocks
     * that drop an item generate ({@link #band}). Null while the senses are not ready.
     */
    io.github.mojolowjo.entropybot.aa.NeedChain.World needWorld() {
        if (sensed == null || sensed.feas == null) return null;
        var in = bb.in;
        return new io.github.mojolowjo.entropybot.aa.NeedChain.World(in.bag, in.tableKnown, in.furnaceKnown,
                item -> { var s = nearestSource(item); return s == null ? List.of() : List.of(s); },
                this::animalsKnown, currentLootBook(), this::band, 0, LivePathPrice.INSTANCE);
    }

    /** one-planner: the chain for an "I need N of X" step as it starts (the facts of that moment); no chain: why. */
    io.github.mojolowjo.entropybot.aa.moves.Steps.Routine obtainSteps(io.github.mojolowjo.entropybot.aa.moves.Step.Obtain o) {
        var w = needWorld();
        if (w == null) return new io.github.mojolowjo.entropybot.aa.moves.Steps.Routine(null, "no facts for the chain yet (the senses are not ready)");
        var ch = io.github.mojolowjo.entropybot.aa.NeedChain.of(o, w);
        LOG.info("[entropybot] aa: {}: {}", o.text(), ch.why());
        if (!ch.ok()) return new io.github.mojolowjo.entropybot.aa.moves.Steps.Routine(null, ch.why());
        return new io.github.mojolowjo.entropybot.aa.moves.Steps.Routine(ch.steps(), null);
    }

    /**
     * one-planner: the animals of a kind known now: those in view within the hunt's reach, then the herds seen earlier (the
     * sightings, confidence and distance within the food set's limits), nearest first. Never throws (empty on a failure, logged).
     */
    List<io.github.mojolowjo.entropybot.aa.NeedChain.Mob> animalsKnown(String type) {
        List<io.github.mojolowjo.entropybot.aa.NeedChain.Mob> out = new ArrayList<>();
        try {
            Minecraft mc = Minecraft.getInstance();
            LocalPlayer p = mc.player;
            if (p == null || mc.level == null) return out;
            int[] me = Jobs.here(p);
            for (var e : mc.level.getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class, p.getBoundingBox().inflate(io.github.mojolowjo.entropybot.commands.StepRun.animalRange()))) {
                if (!e.isAlive() || !net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString().equals(type)) continue;
                out.add(new io.github.mojolowjo.entropybot.aa.NeedChain.Mob(type, e.getBlockX(), e.getBlockY(), e.getBlockZ(), p.distanceTo(e)));
            }
            for (var h : herds(mc.level, me)) {
                String id = h.type().indexOf(':') >= 0 ? h.type() : "minecraft:" + h.type();
                if (!id.equals(type) || h.conf() < io.github.mojolowjo.entropybot.aa.FoodOptions.herdMinConf() || h.dist() > io.github.mojolowjo.entropybot.aa.FoodOptions.herdMax()) continue;
                out.add(new io.github.mojolowjo.entropybot.aa.NeedChain.Mob(type, h.x(), h.y(), h.z(), h.dist()));
            }
        } catch (RuntimeException e) {
            LOG.warn("[entropybot] aa: animals known of {}: {}", type, e.toString());
        }
        out.sort(java.util.Comparator.comparingDouble(io.github.mojolowjo.entropybot.aa.NeedChain.Mob::dist));
        return out;
    }

    /**
     * one-planner: {minY, maxY} where the blocks that drop the item generate (the loot data's blocks and the item's own
     * block, the worldgen read at load); null: none of them generates, or no worldgen was read (multiplayer).
     */
    int[] band(String item) {
        var cat = catalog;
        if (cat == null || cat.gen() == null) return null;
        java.util.Set<String> blocks = sourceBlocksOf(item);
        int lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE;
        for (String b : blocks) {
            var g = cat.gen().get(b);
            if (g == null || g.minY() == io.github.mojolowjo.entropybot.aa.brain.Thing.UNKNOWN_Y) continue;
            lo = Math.min(lo, g.minY());
            hi = Math.max(hi, g.maxY());
        }
        return lo == Integer.MAX_VALUE ? null : new int[]{lo, hi};
    }

    /** The item's own block id (the block it places), null when it places none. */
    private static String ownBlock(String item) {
        var it = net.minecraft.core.registries.BuiltInRegistries.ITEM.getOptional(net.minecraft.resources.ResourceLocation.tryParse(item)).orElse(null);
        if (it == null) return null;
        var b = net.minecraft.world.level.block.Block.byItem(it);
        return b == net.minecraft.world.level.block.Blocks.AIR ? null : net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(b).toString();
    }

    /**
     * live-facts: the nearest source of an item: the blocks the loot book says drop it plus its own block
     * (Nearby.sourceBlocks, the collect step's pairing), then the one Nearby fact (Baritone's scan; a log only as a tree, an ore
     * only under the fair-play rule, the policy allowing); the fallback is AA's sightings: the trees for a log, the ore index else.
     * Null: no block gives it, or none is known.
     */
    io.github.mojolowjo.entropybot.aa.NeedChain.Spot nearestSource(String item) {
        String word = io.github.mojolowjo.entropybot.aa.NeedChain.word(item);
        java.util.Set<String> blocks = sourceBlocksOf(word);
        if (blocks.isEmpty()) return null;
        boolean logs = false;
        for (String b : blocks)
            logs |= net.minecraft.core.registries.BuiltInRegistries.BLOCK.getOptional(net.minecraft.resources.ResourceLocation.tryParse(b))
                    .map(x -> x.defaultBlockState().is(net.minecraft.tags.BlockTags.LOGS)).orElse(false);
        final boolean tree = logs;
        var s = c.nearby.nearest(blocks, () -> tree ? treeSighting() : indexedSighting(blocks));
        // TAG_NEEDS: a tag need's spot says which member it gives (its loot drop in the tag, else its own item)
        if (s == null || io.github.mojolowjo.entropybot.aa.NeedChain.plain(word)) return s;
        String gives = io.github.mojolowjo.entropybot.aa.Nearby.gives(s.block(), word, currentLootBook(), AaRuntime::ownItem);
        return gives == null ? s : s.giving(gives);
    }

    /** TAG_NEEDS: the blocks that give any item of a need word (an item, a tag, several): the loot data, then the blocks whose own item it is. */
    java.util.Set<String> sourceBlocksOf(String word) {
        return io.github.mojolowjo.entropybot.aa.Nearby.sourceBlocksOf(word, currentLootBook(), AaRuntime::ownBlock, AaRuntime::tagBlocks);
    }

    /** TAG_NEEDS: the blocks whose own item is in the item tag (the game's tag index), for a tag need's source set. */
    private static java.util.Set<String> tagBlocks(String tag) {
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        for (var b : net.minecraft.core.registries.BuiltInRegistries.BLOCK) {
            var it = b.asItem();
            if (it == net.minecraft.world.item.Items.AIR) continue;
            if (io.github.mojolowjo.entropybot.aa.brain.ItemWorth.isIn(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(it).toString(), tag))
                out.add(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(b).toString());
        }
        return out;
    }

    /** The item a block places back as (its own item), null: none. */
    private static String ownItem(String block) {
        var b = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getOptional(net.minecraft.resources.ResourceLocation.tryParse(block)).orElse(null);
        if (b == null || b.asItem() == net.minecraft.world.item.Items.AIR) return null;
        return net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(b.asItem()).toString();
    }

    /**
     * one-planner: a known source of an item for the chain's explore to end on: a block that gives it (nearestSource), else an
     * animal of a kind whose loot drops it (animalsKnown, the loot data's mob drops). TAG_NEEDS: a tag word's ("#logs") source is
     * any member's. Null: none known.
     */
    io.github.mojolowjo.entropybot.aa.NeedChain.Spot knownSource(String item) {
        String id = io.github.mojolowjo.entropybot.aa.NeedChain.word(item);
        var s = nearestSource(id);
        if (s != null || id == null) return s;
        var book = currentLootBook();
        if (book == null) return null;
        var from = io.github.mojolowjo.entropybot.aa.NeedChain.plain(id) ? book.sources(id) : book.sourcesMatching(i -> io.github.mojolowjo.entropybot.aa.NeedChain.gives(id, i));
        for (var src : from) {
            if (src.kind() != io.github.mojolowjo.entropybot.aa.brain.LootBook.Kind.MOB) continue;
            var known = animalsKnown(src.from());
            if (!known.isEmpty()) {
                var a = known.get(0);
                return new io.github.mojolowjo.entropybot.aa.NeedChain.Spot(a.x(), a.y(), a.z(), a.dist(), src.from());
            }
        }
        return null;
    }

    /** The nearest tree AA's sightings know that I may cut (the tree fact's fallback). */
    private io.github.mojolowjo.entropybot.aa.NeedChain.Spot treeSighting() {
        var trees = woodFacts();
        if (trees != null) for (var t : trees.trees()) {
            if (t.deny() == null) return new io.github.mojolowjo.entropybot.aa.NeedChain.Spot(t.x(), t.y(), t.z(), t.dist(), null);
        }
        return null;
    }

    /** The nearest cell of the blocks the ore index knows (the policy and aa fair allowing). */
    private io.github.mojolowjo.entropybot.aa.NeedChain.Spot indexedSighting(java.util.Set<String> blocks) {
        var h = Mining.get().nearestIndexed(Minecraft.getInstance().player, blocks);
        return h == null ? null : new io.github.mojolowjo.entropybot.aa.NeedChain.Spot(h.x(), h.y(), h.z(), Math.sqrt(h.d2()), h.id());
    }

    /** simple-tools: the one "tree nearby" fact: Baritone's scan first, AA's sightings (the policy allowing) as the fallback. */
    io.github.mojolowjo.entropybot.aa.NeedChain.Spot nearbyLog() {
        return c.nearby.log(this::treeSighting);
    }

    /** The nearest known iron positions the scorer weighs. */
    static int ironOres() { return Tuning.i("aaRuntime.ironOres"); }

    /** The IRON rung's options line ("aa why"), or null. */
    String ironOptions() {
        for (var r : io.github.mojolowjo.entropybot.aa.Need.all(tree.ladder)) if (r.kind == TaskKind.IRON) return r.options();
        return null;
    }

    // ---- S0: options lines, counters and the owner's manual driving (AaManual) ----

    /** One "options <rung>: <line>" per rung with a picker (aa why). */
    List<String> optionLines() {
        List<String> out = new ArrayList<>();
        if (tree.brain.planText() != null) out.add(tree.brain.planText());          // AA_ROUND3 2: the sequencer's plan
        for (var r : io.github.mojolowjo.entropybot.aa.Need.all(tree.ladder)) out.add(r.id.substring(6) + " options: " + r.options() + priced(r));      // round 4A N: every need as rows with prices
        out.addAll(io.github.mojolowjo.entropybot.aa.brain.Costs.lines(bb));      // aa-costs: the cost terms
        out.addAll(tree.brain.heldLines(bb.clock.now()));                          // AA_ROUND2 3: the failure holds
        return out;
    }

    /** Round 4A N: the need's live price for aa why (" = 42 s"), "" while unknown. */
    private String priced(io.github.mojolowjo.entropybot.aa.Need r) {
        double c = r.liveCostS(bb);
        return Double.isNaN(c) ? "" : " = " + Math.round(c) + " s" + (r.wantsSurface() && bb.in.underDepth >= io.github.mojolowjo.entropybot.aa.AaLadder.surfaceDepth() ? " (the climb priced in)" : "");
    }

    /** Per-rung picker counters and the manual state (aa status); "!!" first when a facts supplier failed in the last minute. */
    String optionStatus() {
        long now = System.currentTimeMillis();
        StringBuilder b = new StringBuilder();
        boolean red = false;
        for (var r : io.github.mojolowjo.entropybot.aa.Need.all(tree.ladder)) {
            if (r.picker() == null) continue;
            red |= r.picker().factsFailedSince(now - 60_000);  // structural: per minute window
            String k = r.picker().counters(now);
            if (k != null) b.append('\n').append(r.id.substring(6)).append(": ").append(k);
        }
        return (red ? "!! a facts supplier failed in the last minute (see the log: aa: <RUNG> facts:)\n" : "") + io.github.mojolowjo.entropybot.aa.AaManual.of(bb).describe(now) + b
                + "\n" + replanLog.text(replanOn()) + (runningOpt == null ? "" : "; running " + runningOpt.id() + (runningOpt.ret().leg() == io.github.mojolowjo.entropybot.aa.Replan.Leg.NONE ? "" : ", " + runningOpt.ret().text()));
    }

    /** The rung AA works on now (the ladder's running rung), else the one that picked last; null none. */
    private String currentRung() {
        Node n = tree.slot.runningChild();
        if (n == tree.brain && tree.brain.runningChild() instanceof io.github.mojolowjo.entropybot.aa.Need rr) return rr.id;
        // review: the last picker only while it still runs (a stale one would preempt an unrelated task)
        Object o = bb.fact(io.github.mojolowjo.entropybot.aa.Need.CURRENT);
        for (var r : io.github.mojolowjo.entropybot.aa.Need.all(tree.ladder)) if (r.id.equals(o) && r.last() == Status.RUNNING) return r.id;
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
                return "ok: aa ask " + a + (man.ask ? " - before each task I show my options and wait " + io.github.mojolowjo.entropybot.aa.AaManual.askMs() / 1000 + " s for aa pick <n>, then take my first" : "");  // structural: milliseconds per second
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
                io.github.mojolowjo.entropybot.aa.Need r = null;
                for (var x : io.github.mojolowjo.entropybot.aa.Need.all(tree.ladder)) if (x.id.equals(rung)) r = x;
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

    // ---- the switch: the world's (AaSwitch: on, since, heldUntil, shelterBuilt, only); brainData "aa": the bot-wide settings ----

    JsonObject data() {
        JsonObject root = c.brainData();
        if (!root.has("aa") || !root.get("aa").isJsonObject()) root.add("aa", new JsonObject());
        return root.getAsJsonObject("aa");
    }

    /** live-facts: this world's switch (commands.json's world part). */
    private JsonObject sw() { return io.github.mojolowjo.entropybot.aa.AaSwitch.of(c.brainData()); }

    public boolean on() { return io.github.mojolowjo.entropybot.aa.AaSwitch.on(sw()); }

    /**
     * live-facts: the world's part was (re)loaded: the hold, the shelter latch and {@code aa only} come from this world's switch;
     * AA off here gives Baritone's look settings back. A line for the world log.
     */
    String worldLoaded() {
        JsonObject s = sw();
        heldUntil = io.github.mojolowjo.entropybot.aa.AaSwitch.heldUntil(s);
        bb.fact(AaLadder.SHELTER_FACT, io.github.mojolowjo.entropybot.aa.AaSwitch.shelterBuilt(s) ? Boolean.TRUE : null);
        io.github.mojolowjo.entropybot.aa.brain.Only.set(io.github.mojolowjo.entropybot.aa.AaSwitch.mode(s));     // orders-tier: no saved choice = orders only
        offReason = null;
        if (!on()) lookRestore();
        return "aa " + (on() ? "on" : "off") + " in this world, " + io.github.mojolowjo.entropybot.aa.brain.Only.modeText();
    }

    /** aa only <need>|off: the watched need, saved in this world's switch (off: the full set of needs). */
    String only(String arg) {
        if (arg == null || arg.isEmpty()) return io.github.mojolowjo.entropybot.aa.brain.Only.statusText() == null ? null : io.github.mojolowjo.entropybot.aa.brain.Only.need();
        String set = io.github.mojolowjo.entropybot.aa.brain.Only.set(arg);
        io.github.mojolowjo.entropybot.aa.AaSwitch.only(sw(), set == null ? io.github.mojolowjo.entropybot.aa.brain.Only.NEEDS : set);
        c.saved();
        return set;
    }

    /** orders-tier (0.39.z9): aa needs on|off: the full set of needs, wants and night desires, or orders only; saved per world. */
    String needs(String arg) {
        String a = arg == null ? "" : arg.trim().toLowerCase(java.util.Locale.ROOT);
        String v = switch (a) {
            case "on" -> io.github.mojolowjo.entropybot.aa.brain.Only.NEEDS;
            case "off" -> io.github.mojolowjo.entropybot.aa.brain.Only.ORDERS;
            default -> null;
        };
        if (v == null) return "aa needs: " + io.github.mojolowjo.entropybot.aa.brain.Only.modeText() + " (usage: aa needs on|off)";
        io.github.mojolowjo.entropybot.aa.brain.Only.set(v);
        io.github.mojolowjo.entropybot.aa.AaSwitch.only(sw(), v);
        c.saved();
        LOG.info("[entropybot] aa: needs {} ({})", a, io.github.mojolowjo.entropybot.aa.brain.Only.modeText());
        return a.equals("on") ? "ok: needs on - food, torches, tools, bed, base, stock-ups, wants and the night run again (saved for this world; aa needs off for orders only)"
                : "ok: orders only - every need, want and night desire is silent; danger, reflexes and your orders stay (saved for this world)";
    }

    String turnOn() {
        JsonObject s0 = sw();
        if (!io.github.mojolowjo.entropybot.aa.AaSwitch.hasMode(s0)) io.github.mojolowjo.entropybot.aa.AaSwitch.only(s0, io.github.mojolowjo.entropybot.aa.brain.Only.ORDERS);     // orders-tier: the default
        io.github.mojolowjo.entropybot.aa.brain.Only.set(io.github.mojolowjo.entropybot.aa.AaSwitch.mode(s0));
        io.github.mojolowjo.entropybot.aa.AaSwitch.turnOn(s0, System.currentTimeMillis());
        offReason = null;
        heldUntil = 0;
        c.saved();
        // 0.36.1 (review 1): assist ends now (its tick is off under AA; it could start and cancel jobs)
        String ended = "";
        LocalPlayer p = Minecraft.getInstance().player;
        if (c.assist.on() && p != null) {
            c.assist.end(p);
            ended += "; assist ended";
        }
        int queued = c.vocab.queued.size();
        if (queued > 0) ended += "; " + queued + " queued task(s) run as your orders when my slot is free";
        ended += "; rules and routines are suspended while aa runs (only aa, your orders and true reflexes start jobs)";     // AA_CLEANUP 2
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
        String mode = io.github.mojolowjo.entropybot.aa.brain.Only.modeText();
        return "ok: aa on, " + mode + (io.github.mojolowjo.entropybot.aa.brain.Only.orders()
                ? " - danger > your orders (aa want, aa go, aa do); with no order I stand still; aa needs on for the needs" + ended
                : " - danger > your orders > AI requests > basic needs > idle; one task at a time; aa status says who drives" + ended);
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
            int lt = data().has("lookTicks") ? data().get("lookTicks").getAsInt() : lookTicksDefault();
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
        slow.reset();                        // AA_STEP3 B
        io.github.mojolowjo.entropybot.clear.OreDetour.valuer = null;
        appendRun();
        io.github.mojolowjo.entropybot.aa.AaSwitch.turnOff(sw());
        c.saved();
        String stopped = null;
        if (bb.task() != null) {
            stopped = bb.task().spec().chain();
        }
        tree.root.halt("aa off: " + why);
        if (chainRunning()) c.chains.clear();
        if (typedRun) c.stepRun.stop("aa off");
        typedRun = false;
        // 0.36.5 (review 6): nothing of this run carries into the next aa on (queued AI requests, the last task's state)
        int dropped = bb.drop("ai");
        bb.fact(io.github.mojolowjo.entropybot.aa.AaManual.FACT, null);     // S0: ask/do/skip/use are per run
        clearEscape();
        current = -1;
        lastEnd = null;
        progress = 0;
        lastBag = -1;
        LOG.info("[entropybot] aa: off ({}){}", why, dropped > 0 ? "; " + dropped + " AI request(s) dropped" : "");
        return "ok: aa off" + (stopped != null ? " - stopped my task " + stopped : "") + "; your jobs run on";
    }

    /** The owner's "stop": AA's task ends (justified), the slot waits 2 min or until the next owner order. */
    String ownerStopped() {
        if (!on()) return "";
        if (bb.task() != null || tree.slot.runningChild() != null) tree.root.halt("the owner's stop");
        heldUntil = System.currentTimeMillis() + holdMs();
        io.github.mojolowjo.entropybot.aa.AaSwitch.hold(sw(), heldUntil);
        c.saved();
        return "; aa waits " + holdMs() / 60000 + " min (aa on, or your next order, to go on now)";  // structural: milliseconds per minute
    }

    /** AA's chain runs (the AI operator's request: the owner's vocabulary on the interpreter). */
    boolean chainRunning() { return c.chains.running() && CHAIN.equals(c.chains.name()); }
    /** The owner's order while AA's needs/idle task runs: the task is preempted (logged), the order runs (AA_PLAN 4). */
    void yieldToOwner(String verb) {
        if (!on() || !(chainRunning() || (typedRun && c.stepRun.running()))) return;
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
                slow.reset();               // AA_STEP3 B: slow mode, the drawn route and Baritone's render settings do not outlive the world
                return;
            }
            long now = System.currentTimeMillis();
            if (p.isDeadOrDying()) {
                if (!runDead) {
                    runDead = true;
                    runDeaths++;
                    io.github.mojolowjo.entropybot.aa.brain.Kit.died(bb, bb.clock.now());     // aa-kit: bb.in still holds the last live pos and bag
                }
                if (bb.task() != null) {
                    tree.root.halt("died");
                    c.chains.clear();
                    c.stepRun.stop("died");
                }
                return;
            }
            worldFacts(mc);                 // live-facts: the loot book is read once per world when AA starts ticking in it
            if (sensedTick < 0 || tick - sensedTick >= 10) {  // structural: periodic cadence in ticks (20 ticks = 1 s)
                sensed = c.senses.sense();      // AA_CLEANUP 3: was BrainRuntime.sense()
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
                if (bb.in.otherJob == null) {
                    tracer.beginTick();
                    snapshot(now, "held");
                    return;
                }
                heldUntil = 0;        // the owner's order lifts the hold
            }
            // 0.36.1 (review 1): the owner's queue ("queue <task>") is an owner order: the next queued line starts
            // when the slot is free and is adopted as the owner's job on the next tick
            if (bb.in.otherJob == null && bb.task() == null && !c.vocab.queued.isEmpty() && (tick % 20) == 2) {  // structural: periodic cadence in ticks (20 ticks = 1 s)
                c.vocab.tick();
                return;
            }
            Status s = tree.tick(bb);
            swimTick(p, now);
            replanTick(p, now);
            slow.tick(p, now);               // AA_STEP3 B: the ledger rows and the slow-mode hold
            // 0.36.2 (review 6): while danger drives, AA's chain starts no next step and no retry (the pause ended the
            // running one); held a little past AA's next tick, so it goes on by itself once the guard lets go
            if ("danger".equals(tree.driver()) && chainRunning()) c.chains.holdFor(10);
            if (tree.ladder.last() == Status.SUCCESS && !io.github.mojolowjo.entropybot.aa.AaSwitch.shelterBuilt(sw()) && bb.flag(AaLadder.SHELTER_FACT)) {
                io.github.mojolowjo.entropybot.aa.AaSwitch.shelterBuilt(sw(), true);
                c.saved();
            }
            if (s == Status.FAILURE && tracer.counts.ticks % 240 == 1) LOG.info("[entropybot] aa: nothing to do: {}", tree.root.reason());  // structural: periodic cadence in ticks (20 ticks = 1 s)
            snapshot(now, tree.driver());
        } catch (Throwable e) {
            tickFailed(e);
        } finally {
            tracer.counts.tickUs = (System.nanoTime() - t0) / 1000;  // structural: milliseconds per second
        }
    }

    private void tickFailed(Throwable e) {
        long now = System.currentTimeMillis();
        errors++;
        lastError = e.toString();
        if (now - minuteStart > 60_000) {  // structural: per minute window
            minuteStart = now;
            errorsThisMinute = 0;
        }
        errorsThisMinute++;
        if (errors <= Tuning.i("aaRuntime.errorsLogged") || now - lastErrorAt > Tuning.i("aaRuntime.errorWindowMs")) {
            lastErrorAt = now;
            LOG.warn("[entropybot] aa: tick failed: {}", errors <= Tuning.i("aaRuntime.errorsLogged") ? stack(e) : e.toString());
        }
        if (errorsThisMinute >= errorsPerMinuteOff()) {
            offReason = "turned itself off after " + errorsThisMinute + " tick errors in a minute: " + e;
            turnOff(offReason);
            c.whisper(c.owner(), "aa: " + offReason);
        }
    }

    private static String stack(Throwable e) {
        StringBuilder b = new StringBuilder(e.toString());
        int n = 0;
        for (StackTraceElement s : e.getStackTrace()) {
            if (n++ >= 6) break;  // structural: display: how many items a text lists
            b.append("\n    at ").append(s);
        }
        return b.toString();
    }

    /** The fixed inputs (AA_PLAN 7) from the sense snapshot plus a few direct reads. */
    private boolean sheepLatch;
    /** Reflex choosers: the last spent reflex's line and until when it holds the danger at 2 (bb clock ms). */
    private String reflexFailedLine;
    private io.github.mojolowjo.entropybot.aa.moves.ReflexLadder.Spent reflexFailedSpent;
    private long reflexFailedUntil;
    private final io.github.mojolowjo.entropybot.aa.brain.Costs.ToolSmoother toolSmoother = new io.github.mojolowjo.entropybot.aa.brain.Costs.ToolSmoother();

    private void fill(Inputs in, BrainState s, LocalPlayer p, Minecraft mc) {
        in.inWorld = s.inWorld;
        in.health = p.getHealth();
        in.maxHealth = p.getMaxHealth();
        in.food = p.getFoodData().getFoodLevel();
        in.saturation = p.getFoodData().getSaturationLevel();
        in.hurtRecently = p.hurtTime > 0;
        Reflexes.Reflex r = Core.INSTANCE.reflexes.reflex();
        // AA_CLEANUP 4: AA's own danger moves (RETREATING) and its own attack (a forced fight) are not a danger pause;
        // the true reflexes are (the hit response, the creeper flee, the fire reflex, eating)
        boolean dangerJob = tree.brain.runningDesire() != null && io.github.mojolowjo.entropybot.aa.AaDesires.DANGER_ID.equals(tree.brain.runningDesire().id);
        // 0.38.b9: a hit response while AA's own danger job runs is part of that job (no pause of the danger chain)
        boolean aaOwn = r == Reflexes.Reflex.RETREATING || (r == Reflexes.Reflex.FIGHTING && (Core.INSTANCE.reflexes.forcing() || dangerJob));
        bb.fact(io.github.mojolowjo.entropybot.aa.AaDesires.MOVE_FACT, Core.INSTANCE.reflexes.moves.active());
        // backbone step 2: a failed danger move fails the danger job's chain (taken once; dropped when no danger job runs)
        String moveFailed = Core.INSTANCE.reflexes.moves.takeFailure();
        if (moveFailed != null && dangerJob) bb.fact(io.github.mojolowjo.entropybot.aa.AaDesires.MOVE_FAILED_FACT, moveFailed);
        in.danger = !aaOwn && (r == Reflexes.Reflex.FIGHTING || r == Reflexes.Reflex.FLEEING) ? 2 : !aaOwn && Core.INSTANCE.reflexes.hold() ? 1 : 0;
        in.dangerWhy = in.danger > 0 ? r.name().toLowerCase() : "";
        // reflex choosers: a reflex whose every way is spent (its tries charged in the brain's Failures as
        // reflex.<kind> <cell>) goes to the danger desire: full danger for reflex.failedS with the line as the reason
        io.github.mojolowjo.entropybot.aa.moves.ReflexLadder.bind(tree.brain.failures);
        String reflexFailed = Core.INSTANCE.reflexes.takeReflexFailure();
        if (reflexFailed != null) {
            reflexFailedLine = reflexFailed;
            var sp = io.github.mojolowjo.entropybot.aa.moves.ReflexLadder.takeSpent();      // 0.39.s6: the spent ways as data
            reflexFailedSpent = sp != null ? sp : new io.github.mojolowjo.entropybot.aa.moves.ReflexLadder.Spent(reflexFailed, java.util.Set.of());
            reflexFailedUntil = bb.clock.now() + (long) (1000 * io.github.mojolowjo.entropybot.aa.brain.Tuning.get("reflex.failedS"));
            LOG.info("[entropybot] aa: {} - the danger desire takes it", reflexFailed);
            if (dangerJob) bb.fact(io.github.mojolowjo.entropybot.aa.AaDesires.MOVE_FAILED_FACT, reflexFailed);
        }
        if (reflexFailedLine != null && bb.clock.now() < reflexFailedUntil) {
            in.danger = 2;
            in.dangerWhy = reflexFailedLine;
        } else { reflexFailedLine = null; reflexFailedSpent = null; }
        // reflex probe 0.39.s5: the danger desire prices the spent reflex (its ways held) and runs a way that acts
        bb.fact(io.github.mojolowjo.entropybot.aa.AaDesires.REFLEX_FAILED_FACT, reflexFailedSpent);
        in.creeperClose = creeperClose();
        in.air = p.getAirSupply();
        in.inLavaOrFire = p.isInLava() || p.isOnFire();
        in.freeSlots = s.freeSlots;
        in.foodItems = s.foodItems;
        in.torches = s.torches;
        in.wood = s.woodItems;
        in.coal = s.coal;
        Map<String, Integer> bag = s.feas.bag;
        // one-planner: the counters are bag lookups by the game's tags, never names
        in.cobble = io.github.mojolowjo.entropybot.aa.core.Inputs.tagged(bag, "minecraft:stone_tool_materials");
        in.ironIngots = io.github.mojolowjo.entropybot.aa.core.Inputs.tagged(bag, "c:ingots/iron");
        in.wool = io.github.mojolowjo.entropybot.aa.core.Inputs.tagged(bag, "minecraft:wool");
        in.string = io.github.mojolowjo.entropybot.aa.core.Inputs.tagged(bag, "c:strings");
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
        in.digStoneS = s.digStoneS;          // aa-times: the game's seconds per stone block (ToolWear's kit)
        in.handStoneS = s.handStoneS;
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
        in.tableInBag = io.github.mojolowjo.entropybot.aa.core.Inputs.has(bag, io.github.mojolowjo.entropybot.aa.brain.ItemWorth.stationOf(io.github.mojolowjo.entropybot.aa.brain.ItemWorth.CRAFT));
        in.planks = io.github.mojolowjo.entropybot.aa.core.Inputs.tagged(bag, "minecraft:planks");
        in.furnaceInBag = io.github.mojolowjo.entropybot.aa.core.Inputs.has(bag, io.github.mojolowjo.entropybot.aa.brain.ItemWorth.stationOf(io.github.mojolowjo.entropybot.aa.brain.ItemWorth.SMELT));
        in.baseChests = s.feas.baseChests;
        in.baseKnown = s.basePos != null;
        in.basePos = s.basePos;
        in.campPos = s.campPos;
        in.shelterBuilt = bb.flag(AaLadder.SHELTER_FACT);
        in.basePlace = null;
        for (String n : c.areaNames()) {
            JsonObject a = c.policyArea(n);
            if (a == null) continue;
            var t = PolicyCommands.typeOf(a);
            if (t != io.github.mojolowjo.entropybot.guard.AreaType.BASE && t != io.github.mojolowjo.entropybot.guard.AreaType.WORK) continue;
            if (houseFits(a) && (in.basePlace == null || t == io.github.mojolowjo.entropybot.guard.AreaType.BASE)) in.basePlace = n;    // the base place first
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
        in.lit = litShare(mc.level, p.blockPosition());
        in.torchesNear = torchesNear(mc.level, p.blockPosition());      // AA_ROUND4 B2
        // aa-night3 (normal2: 4 of 7 deaths shot by skeletons): a ranged mob hits from range, so it counts twice in the danger
        in.hostilesNear = mc.level.getEntitiesOfClass(net.minecraft.world.entity.monster.Monster.class, p.getBoundingBox().inflate(24)).size()
                + mc.level.getEntitiesOfClass(net.minecraft.world.entity.monster.AbstractSkeleton.class, p.getBoundingBox().inflate(24)).size();
        in.sheltered = ShelterJob.waitingAt(mc.player == null ? null : Jobs.here(mc.player));
        in.armed = io.github.mojolowjo.entropybot.engine.Hostility.weaponHit(p) >= io.github.mojolowjo.entropybot.threat.ThreatFacts.armed();
        // aa-night2 P0 2: a zombie seen once stays seen (sticky while the game runs)
        if (!in.zombieSeen) in.zombieSeen = !mc.level.getEntitiesOfClass(net.minecraft.world.entity.monster.Zombie.class, p.getBoundingBox().inflate(24)).isEmpty();
        in.armorPoints = mc.player == null ? 0 : mc.player.getArmorValue();
        int[] me = Jobs.here(p);
        in.underDepth = Jobs.underDepth(mc.level, me[0], me[1], me[2]);
        in.pos = me;
        // AA_STEP3 A1: the world spawn for the death cost's walk back (client Level.getSharedSpawnPos, the level data)
        var sp = mc.level.getSharedSpawnPos();
        in.spawnPos = sp == null ? null : new int[]{sp.getX(), sp.getY(), sp.getZ()};
        areaFacts(in, me);                                      // AREAS: the labelled places as facts (the same tick as in.pos)
        in.ownerOnline = s.ownerOnline;
        in.hungerDrains = mc.level.getDifficulty() != net.minecraft.world.Difficulty.PEACEFUL;
        in.menuOpen = mc.screen != null && !chainRunning() && !c.stepRun.running() && !c.jobs.running();
        in.parked = s.parked;
        in.otherJob = otherJob();
        in.ownerOrderPending = in.otherJob != null;
        in.aiRequestPending = bb.peek("ai") != null;
        fillOwner(in, s, p, mc);
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
                in.ownerDark = mc.level.getMaxLocalRawBrightness(bp) < 8;  // game fact: hostile mobs spawn in light level 7 or less; the dark check is under 8
                in.ownerHostiles = mc.level.getEntitiesOfClass(net.minecraft.world.entity.monster.Monster.class,
                        new net.minecraft.world.phys.AABB(bp).inflate(12), net.minecraft.world.entity.LivingEntity::isAlive).size();
            }
            io.github.mojolowjo.entropybot.assist.AssistRules.Report r = c.assist.report();
            long now = System.currentTimeMillis();
            if (r != null && owner != null && r.name().equalsIgnoreCase(owner) && now - r.received() <= io.github.mojolowjo.entropybot.assist.AssistRules.freshMs()
                    && r.dim().equals(mc.level.dimension().location().toString())) {
                in.ownerAct = io.github.mojolowjo.entropybot.assist.AssistRules.guess(r, now).word();
                in.ownerPlaced = r.placed() == null || now - r.placed().at() > io.github.mojolowjo.entropybot.assist.AssistRules.recentMs() ? null : r.placed().id();
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
            LOG.info("[entropybot] aa: {} {}", key, now ? "noted (seen within " + AaLadder.campR() + " of the camp/base): it counts away from the camp too" : "dropped (no camp or base known)");
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
            return t != null && t.contains("creeper") && (!r.has("dist") || r.get("dist").getAsDouble() <= Tuning.i("aaRuntime.creeperNear"));
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * AREAS (0.39.2, AreaFacts): the meanings at the bot's cell, the nearest base area and its distance, the owner's
     * near-me circle (a soft preference in companion mode, never a permission), the owner's note - as inputs and as
     * blackboard facts (area.here, area.nearestBase, area.nearOwner, area.note) for the scorer and the operator model.
     */
    private void areaFacts(io.github.mojolowjo.entropybot.aa.core.Inputs in, int[] me) {
        String dim = Storage.dim();
        io.github.mojolowjo.entropybot.guard.Policy pol = Core.INSTANCE.guard == null ? null : Core.INSTANCE.guard.core.policy();
        in.areasHere = io.github.mojolowjo.entropybot.aa.core.AreaFacts.here(pol, dim, me[0], me[1], me[2]);
        in.nearestBase = io.github.mojolowjo.entropybot.aa.core.AreaFacts.nearestBase(pol, dim, me[0], me[2]);
        in.baseDist = io.github.mojolowjo.entropybot.aa.core.AreaFacts.baseDist(in.nearestBase, me[0], me[2]);
        in.areaNote = io.github.mojolowjo.entropybot.aa.core.AreaFacts.note(pol, dim, me[0], me[1], me[2]);
        io.github.mojolowjo.entropybot.guard.Box near = Core.INSTANCE.guard == null ? null : Core.INSTANCE.guard.core.near.box();
        in.nearOwner = near != null && near.contains(dim, me[0], me[1], me[2]);
        bb.fact(io.github.mojolowjo.entropybot.aa.core.AreaFacts.HERE, in.areasHere.isEmpty() ? null : in.areasHere);
        bb.fact(io.github.mojolowjo.entropybot.aa.core.AreaFacts.NEAREST_BASE, in.nearestBase);
        bb.fact(io.github.mojolowjo.entropybot.aa.core.AreaFacts.NEAR_OWNER, in.nearOwner ? Boolean.TRUE : null);
        bb.fact(io.github.mojolowjo.entropybot.aa.core.AreaFacts.NOTE, in.areaNote);
    }

    /** AA_ROUND4A O: the centre of area {@code name} at my feet's height (for the house row's site), null none or unreadable. */
    int[] areaCell(String name) {
        if (name == null || bb.in.pos == null) return null;
        try {
            JsonObject a = c.policyArea(name);
            if (a == null || !a.has("x1")) return null;
            return new int[]{(int) ((PolicyCommands.n(a, "x1") + PolicyCommands.n(a, "x2")) / 2), bb.in.pos[1], (int) ((PolicyCommands.n(a, "z1") + PolicyCommands.n(a, "z2")) / 2)};
        } catch (RuntimeException e) {
            LOG.debug("[entropybot] aa: area cell {}: {}", name, e.toString());
            return null;
        }
    }

    /** {@code house} takes a box of 7x7 up to 4096 blocks (CampCommands.house); a round area or a bigger box does not fit. */
    private static boolean houseFits(JsonObject a) {
        try {
            if (a.has("round")) return false;
            long w = Math.abs(PolicyCommands.n(a, "x2") - PolicyCommands.n(a, "x1")) + 1, d = Math.abs(PolicyCommands.n(a, "z2") - PolicyCommands.n(a, "z1")) + 1;
            return w >= Tuning.i("aaRuntime.minSide") && d >= Tuning.i("aaRuntime.minSide") && w * d <= Tuning.i("aaRuntime.maxArea");
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Something that is not AA's runs: the owner's chain, a job, a gather, a hunt, a carry, a request; else null. */
    private String otherJob() {
        if (escapePending || escapeWay != null) return null;       // 0.36.a6: AA's own way out (pillar job / climb chain)
        if (c.chains.running()) return chainRunning() ? null : "chain " + c.chains.name();
        if (c.stepRun.running()) return typedRun && current >= 0 ? null : "steps " + c.stepRun.status();
        if (restoreInSlot()) return null;          // AA_STEP2 3: putting back blocks is AA's own work, never the owner's job
        if (c.jobs.running()) return c.jobs.job.type + " (" + c.jobs.job.status + ")";
        if (c.requests.busy()) return "a request";
        return null;
    }

    /**
     * The activity counter (0.36.1, review 7: task-specific first): a chain step done, the job's status text changing
     * (it carries "N broken", "picking up"...), items gained, a walk's distance to its goal shrinking; the position only
     * as a fallback, and then only a move of 8+ blocks from the last counted spot (standing and shuffling is no progress).
     */
    private void progressTick(LocalPlayer p) {
        String step = typedRun ? c.stepRun.steps.stepText() : chainRunning() ? c.chains.stepText() : null;
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
        for (int i = 0; i < 36; i++) bagTotal += p.getInventory().getItem(i).getCount();  // game fact: the player inventory has 36 slots (hotbar is slots 0-8)
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
        if (lastPos == null || Math.abs(pos[0] - lastPos[0]) + Math.abs(pos[1] - lastPos[1]) + Math.abs(pos[2] - lastPos[2]) >= Tuning.i("aaRuntime.movedBlocks")) {
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
        if (c.stepRun.running()) { refusal = "busy: " + c.stepRun.status(); return -1; }
        if (io.github.mojolowjo.entropybot.aa.door.Door.aaSlot(false, c.jobs.running(), restoreInSlot()) == io.github.mojolowjo.entropybot.aa.door.Door.Slot.HALT_RESTORE) {
            // AA_STEP2 3: the restore is AA's own, below every desire: halted, its blocks wait in the ledger
            String was = c.jobs.job.status;
            log("restore halted for " + t.kind() + " \"" + t.chain() + "\" (" + was + "; " + RestoreLive.INSTANCE.pendingCount() + " wait in the ledger)");
            c.jobs.finish("stopped: aa needs the slot (the restore waits)");
            if (c.jobs.running()) { refusal = "busy: the restore did not stop (" + c.jobs.job.status + ")"; return -1; }
        }
        if (c.jobs.running()) { refusal = "busy: " + c.jobs.job.status; return -1; }      // 0.36.1: any job, as otherJob() sees it
        if (c.requests.busy()) { refusal = "busy: a request is open"; return -1; }
        lastEnd = null;
        typedRun = false;
        pendingReturnSteps = null;
        escapeResumeSteps = null;
        startedKind = t.kind();
        clearEscape();
        escapes = 0;
        runningOpt = null;
        returnDone = null;
        io.github.mojolowjo.entropybot.aa.Options.Option opt = optionFor(t.chain());
        // rm-execution2: every AA task runs on its typed steps (aa/moves/Steps over StepRun): Steps.run(option.steps)
        if (t.typed()) {
            List<io.github.mojolowjo.entropybot.aa.moves.Step> run = opt != null && opt.typed() ? stepsFrom(opt, 0) : t.steps();
            String r = runTyped(opt == null ? t.kind() + " " + t.source() : opt.memoryKey(), run, t.kind() + " " + (opt == null ? t.source() : opt.id()));
            if (r != null) {
                refusal = r;
                runningOpt = null;
                pendingReturnSteps = null;
                return -1;
            }
            current = ++seq;
            currentChain = t.chain();
            return current;
        }
        runningOpt = null;
        // the AI operator's request is the owner's typed vocabulary (it turns loose talk into the owner's verbs): the interpreter
        if (t.kind() != TaskKind.AI) {
            refusal = "error: no typed steps for " + t.kind() + " \"" + t.chain() + "\" (AA runs typed steps only)";
            return -1;
        }
        String r = c.chains.startChain(c.owner(), CHAIN, t.chain(), 1);
        if (r == null || !r.startsWith("started")) {
            refusal = r == null ? "error: no answer" : r;
            return -1;
        }
        current = ++seq;
        currentChain = t.chain();
        return current;
    }

    // ---- aa-replan: the stored return leg and the rolling re-score (aa/Replan) ----

    /** The option AA's task runs (null: not an option's chain), the step its chain started at, the stored way back. */
    private io.github.mojolowjo.entropybot.aa.Options.Option runningOpt;
    private int stepOffset;
    /** Where the option's chain started (the walk-back estimate at the ore step's end). */
    private int[] startPos;
    /** The ore step's end, held while the return leg runs; null: no return leg running. */
    private String returnDone;
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

    static int lookTicksDefault() { return Tuning.i("aaRuntime.lookTicksDefault"); }  // Baritone's own default is 5

    /** "aa look [smooth <ticks>]": shows / sets how many ticks a head turn takes (smoothLookTicks, 1..60), saved. */
    String look(String arg) {
        String a = arg == null ? "" : arg.trim();
        int now = data().has("lookTicks") ? data().get("lookTicks").getAsInt() : lookTicksDefault();
        String live;
        try {
            live = String.valueOf(baritone.api.BaritoneAPI.getSettings().smoothLookTicks.value);
        } catch (RuntimeException | LinkageError e) {
            live = "?";
        }
        if (a.isEmpty()) return "aa look: smooth " + now + " ticks per turn (Baritone now " + live + ", default " + lookTicksDefault() + "); set with: aa look smooth <ticks>";
        String[] w = a.split("\\s+");
        int s;
        try {
            if (w.length != 2 || !w[0].equals("smooth")) throw new NumberFormatException();
            s = Integer.parseInt(w[1]);
        } catch (NumberFormatException e) {
            return "usage: aa look [smooth <ticks>] (1..60, now " + now + ")";
        }
        if (s < 1 || s > 60) return "usage: aa look smooth <ticks> (1..60, now " + now + ")";  // structural: range of the aa look smooth command
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
        if (s < 30 || s > 7200) return "usage: aa horizon <seconds> (30..7200, now " + now + ")";  // structural: range of the aa horizon command (seconds)
        data().addProperty("horizon", s);
        c.saved();
        bb.fact(io.github.mojolowjo.entropybot.aa.brain.Brain.HORIZON_FACT, s);
        return "ok: aa horizon " + s + " s (was " + now + ")";
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
        for (var r : io.github.mojolowjo.entropybot.aa.Need.all(tree.ladder))
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
        for (var r : io.github.mojolowjo.entropybot.aa.Need.all(tree.ladder))
            if (r.picker() instanceof io.github.mojolowjo.entropybot.aa.Options.Picker<?> op && op.ran() == o) return op;
        return null;
    }

    // ---- rm-execution: AA's typed steps (StepRun) ----

    /** AA's task runs on typed steps (StepRun), not a chain. */
    private boolean typedRun;
    /** The typed run's end was read (typedEnded ran for it). */
    private boolean typedEndSeen;
    /** The typed steps after the ore step, held while the return leg runs; the typed steps to go on with after an escape. */
    private List<io.github.mojolowjo.entropybot.aa.moves.Step> pendingReturnSteps, escapeResumeSteps;
    private String typedKey;
    /** Rule 7: when the task's typed run started, the bag and the cell then (-1: none running). */
    private long typedStartMs = -1;
    private Map<String, Integer> typedStartBag = Map.of();
    private int[] typedStartAt;

    /** Starts AA's typed steps; null when they run, else why not. */
    private String runTyped(String key, List<io.github.mojolowjo.entropybot.aa.moves.Step> steps, String label) {
        typedKey = key;
        if (typedStartMs < 0) {         // rule 7: the task's first start (a replan or an escape goes on with the same clock)
            typedStartMs = System.currentTimeMillis();
            typedStartBag = bb.in.bag == null ? Map.of() : new java.util.HashMap<>(bb.in.bag);
            LocalPlayer sp = Minecraft.getInstance().player;
            typedStartAt = sp == null ? null : Jobs.here(sp);
        }
        String r = c.stepRun.start(key, steps, label);
        typedRun = true;
        typedEndSeen = r != null;
        if (r == null) slow.chainStarted();
        return r;
    }

    /** The option's typed steps from {@code from}; with a way back after its ore step, only up to that step (the rest waits). */
    private List<io.github.mojolowjo.entropybot.aa.moves.Step> stepsFrom(io.github.mojolowjo.entropybot.aa.Options.Option o, int from) {
        List<io.github.mojolowjo.entropybot.aa.moves.Step> steps = o.steps();
        stepOffset = from;
        pendingReturnSteps = null;
        int a = o.ret().afterStep();
        if (o.ret().leg() != io.github.mojolowjo.entropybot.aa.Replan.Leg.NONE && a >= from && a < steps.size()) {
            pendingReturnSteps = new ArrayList<>(steps.subList(a + 1, steps.size()));
            return new ArrayList<>(steps.subList(from, a + 1));
        }
        return new ArrayList<>(steps.subList(Math.min(from, steps.size()), steps.size()));
    }

    /**
     * The typed run ended (StepRun's Steps: DONE or FAILED with its stage and reason): as chainEnded does for a chain, a
     * stuck walk under ground starts the escape and goes on with the failed step afterwards; a done ore step with a way
     * back stored runs the return leg; else the end is the task's end.
     */
    private void typedEnded() {
        var st = c.stepRun.steps;
        String msg = st.why();
        boolean done = st.phase() == io.github.mojolowjo.entropybot.aa.moves.Steps.Phase.DONE;
        if (!done && on() && (AaEscape.stuck(msg) || holeStuckNow(msg)) && escapes < AaEscape.maxPerTask()) {
            int i = Math.max(0, AaEscape.failedStep(msg) - 1);
            List<io.github.mojolowjo.entropybot.aa.moves.Step> all = st.steps();
            if (i < all.size()) {
                stepOffset += i;
                escapes++;
                runStuckHalts++;
                escapePending = true;
                escapeResumeSteps = new ArrayList<>(all.subList(i, all.size()));
                escapeFail = msg;
                LOG.info("[entropybot] aa: the step ended stuck under ground - getting out myself (escape {} of this task), then: {}", escapes,
                        io.github.mojolowjo.entropybot.aa.moves.Step.label(escapeResumeSteps));
                return;
            }
        }
        if (done && pendingReturnSteps != null && on()) {
            returnDone = msg;
            escapeResumeSteps = pendingReturnSteps.isEmpty() ? null : pendingReturnSteps;
            pendingReturnSteps = null;
            escapeWay = "return";
            escapePending = true;
            return;
        }
        lastEnd = msg;
        lastEndStep = done ? null : st.failedStep();            // chop D: the source a failed step charges
        pendingReturnSteps = null;
    }

    /** chop D: the typed step the ended run failed at (read with its end in {@link #poll}). */
    private io.github.mojolowjo.entropybot.aa.moves.Step lastEndStep;

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
        boolean running = runningOpt != null && ownRunning();
        var ev = swimWatch.tick(p.isInWater(), running, progress, now);
        if (ev == io.github.mojolowjo.entropybot.aa.SwimWatch.Event.ENTERED) {
            wetReplan = true;
            LOG.info("[entropybot] aa: in the water during {} - re-scoring", runningOpt.id());
        } else if (ev == io.github.mojolowjo.entropybot.aa.SwimWatch.Event.STALLED && (typedRun || c.jobs.running())) {
            LOG.info("[entropybot] aa: {} during {} (air {}) - that option failed", io.github.mojolowjo.entropybot.aa.SwimWatch.REASON, runningOpt.id(), p.getAirSupply());
            if (typedRun) c.stepRun.steps.cancel(io.github.mojolowjo.entropybot.aa.SwimWatch.REASON);
            else c.jobs.finish(io.github.mojolowjo.entropybot.aa.SwimWatch.REASON);
        }
    }
    private void replanTick(LocalPlayer p, long now) {
        if (current < 0) { replanTimer.reset(); wetReplan = false; return; }
        if (!replanOn() || runningOpt == null || !ownRunning() || escapePending || escapeWay != null
                || "danger".equals(tree.driver())) return;
        if (!typedRun) return;
        String st = c.stepRun.steps.stepText();
        int[] me = Jobs.here(p);
        int at = c.stepRun.steps.index();
        io.github.mojolowjo.entropybot.aa.moves.Step step = at < 0 ? null : c.stepRun.steps.steps().get(at);
        String body = step == null ? null : step.text();
        // aa-replan 2 (aa381): the ore step went cave to cave far from the planned target: re-plan (every 5 s while so)
        boolean wandered = io.github.mojolowjo.entropybot.aa.Replan.oreStep(step)
                && io.github.mojolowjo.entropybot.aa.Replan.wandered(io.github.mojolowjo.entropybot.aa.Replan.targetOf(runningOpt.id()), me);
        boolean due = replanTimer.due(st, now);
        if (!due && wandered && now - lastWanderAt >= io.github.mojolowjo.entropybot.aa.Replan.everyMs()) due = true;
        if (wetReplan) { wetReplan = false; due = true; }       // aa-water: in the water now: the water ways price it
        if (!due) return;
        if (wandered) lastWanderAt = now;
        var op = pickerOf(runningOpt);
        if (op == null) return;
        var fresh = op.rescore(bb);
        if (fresh == null) return;
        replanLog.checks++;
        int idx = stepOffset + at;
        boolean mid = io.github.mojolowjo.entropybot.aa.Replan.digOrPillarStep(step, me)
                && (step instanceof io.github.mojolowjo.entropybot.aa.moves.Step.Walk || bb.in.underDepth > 0 || bb.in.underGround);
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
        c.stepRun.steps.clear();             // the switch is no failure of the running way: no end is read for it
        typedEndSeen = true;
        if (c.jobs.running()) c.jobs.finish("stopped: aa replan");
        String tr = runTyped(d.to().memoryKey(), stepsFrom(d.to(), d.from()), "replan " + d.to().id());
        op.adopt(fresh, d.to());
        runningOpt = d.to();
        lastSwitchAt = now;
        replanTimer.reset();
        replanLog.switches++;
        replanLog.add(new java.text.SimpleDateFormat("HH:mm:ss").format(new java.util.Date(now)) + " " + from + " " + Math.round(d.cur()) + " -> " + d.to().id() + " " + Math.round(d.alt()));
        if (tr != null) lastEnd = "error: the replan switch to " + d.to().id() + " did not start: " + tr;
        else currentChain = d.to().command();
    }

    @Override public String lastRefusal() { return refusal; }

    @Override public JobState poll(long taskId) {
        if (taskId != current) return JobState.gone("an older task");
        if (typedRun && !typedEndSeen && lastEnd == null && !escapePending && escapeWay == null && !c.stepRun.running()) {
            typedEndSeen = true;
            typedEnded();
        }
        if (lastEnd != null) {
            String e = lastEnd;
            lastEnd = null;
            current = -1;
            e = fastEnd(e);
            // aa-prereq (aa391: "done - mining coal: got 0 of 32 - no coal ore known yet" in 3 s counted done): a task
            // that gained nothing of what it was sent for is a failure (Failures holds it), never a success
            if (e.startsWith("done") && io.github.mojolowjo.entropybot.aa.AaLadder.zeroGain(e)) {
                LOG.info("[entropybot] aa: the task ended \"done\" with nothing gained - counted failed: {}", e);
                e = "failed: nothing gained - " + e.substring(4).replaceFirst("^\\s*-\\s*", "");
                return JobState.failed(e);
            }
            var failedAt = lastEndStep;
            lastEndStep = null;
            return e.startsWith("done") ? JobState.done(e) : JobState.failed(e, failedAt);
        }
        if (escapePending || escapeWay != null) return JobState.RUNNING;      // 0.36.a6: getting out, then the task goes on
        if (typedRun) return JobState.RUNNING;                                  // its end comes through typedEnded above
        if (!chainRunning()) {
            current = -1;
            String who = c.chains.running() ? "chain " + c.chains.name() : c.jobs.running() ? "job " + c.jobs.job.type : "someone (stop or a clear)";
            return JobState.gone(who);
        }
        return JobState.RUNNING;
    }

    /** Rule 7: a typed run that ends "done" within failures.fastEndMs having changed nothing is a failure with its stage (FastEnd). */
    private String fastEnd(String e) {
        long start = typedStartMs;
        typedStartMs = -1;
        if (!typedRun || start < 0) return e;
        double moved = 0;
        LocalPlayer p = Minecraft.getInstance().player;
        if (p != null && typedStartAt != null) {
            int[] now = Jobs.here(p);
            moved = Math.hypot(now[0] - typedStartAt[0], Math.hypot(now[1] - typedStartAt[1], now[2] - typedStartAt[2]));
        }
        String v = io.github.mojolowjo.entropybot.aa.brain.FastEnd.verdict(e, System.currentTimeMillis() - start, typedStartBag,
                bb.in.bag == null ? Map.of() : bb.in.bag, moved, c.stepRun.steps.stepText());
        if (v == null) return e;
        LOG.info("[entropybot] aa: the task ended \"done\" at once with nothing changed - counted failed: {}", v);
        return v;
    }

    @Override public void cancel(long taskId, String why) {
        if (taskId != current) return;
        current = -1;
        typedStartMs = -1;
        clearEscape();
        if (chainRunning()) c.chains.clear();
        if (typedRun) {
            typedEndSeen = true;
            c.stepRun.stop("aa " + why);
        }
        if (c.jobs.running()) c.jobs.finish("stopped: aa " + why);
    }

    @Override public long progress(long taskId) { return progress; }

    /**
     * 0.36.1 (review 6): a real pause: the chain's running step ends as "interrupted: low health ..." so Chains retries
     * that step (up to 3 times) once the reflexes are calm and health is back (Chains.stepChain's fight retry); the chain
     * itself stays, so the task is still RUNNING for AA. Resume needs nothing: the chain's own retry clock goes on.
     */
    @Override public void pause(long taskId, String why) {
        if (taskId != current) return;
        if (typedRun) {                  // rm-execution: the step's Baritone work stops, its clock stands still; resume starts it again
            c.stepRun.steps.pause(why);
            return;
        }
        if (!chainRunning()) return;
        if (c.jobs.running()) c.jobs.finish("interrupted: low health or danger - aa paused the step (" + why + ")");
    }

    @Override public void resume(long taskId) {
        if (taskId == current && typedRun) c.stepRun.steps.resume();
    }

    @Override public void log(String line) {
        if (line.startsWith("brain picked ")) runPicks++;
        LOG.info("[entropybot] aa: {}", line);
    }

    /** orders-tier: an order that stopped, whispered to the owner (the log line comes from the caller). */
    @Override public void tellOwner(String line) { c.whisper(c.owner(), "aa: " + line); }

    @Override public void incident(String what) {
        try {
            Core.INSTANCE.recorder.jobEnded("aa", what, "error: " + what);
        } catch (RuntimeException e) {
            LOG.warn("[entropybot] aa incident: {}", e.toString());
        }
    }

    /** Chains tells every listener how a chain ended; AA keeps its own. */
    void chainEnded(String name, String msg) {
        // rm-execution2: AA's own tasks run on typed steps; the only chain AA starts is the AI operator's request
        if (CHAIN.equals(name) && current >= 0 && !typedRun) lastEnd = msg;
    }

    /** rm-execution2: the escape's way up (its typed steps) ended: go on with the task, the next way up, or fail it. */
    private void wayUpEnded(boolean done, String msg) {
        escapeWay = null;
        if (done) {
            escapePending = true;
            escapeWay = "resume";
        } else if (escapeOpt != null && escapes < AaEscape.maxPerTask()) {
            // AA_ROUND3 1 finding 3: the failed way (a heading) is held in Failures with its stage and reason; the next priced way runs
            wayMem.failed(escapeOpt.memoryKey(), msg == null ? "the way up ended without a word" : msg, bb.clock.now());
            LOG.info("[entropybot] aa: wayup: {} failed ({}) - held; the next way up", escapeOpt.id(), msg);
            escapes++;
            escapeOpt = null;
            escapePending = true;
        } else {
            if (escapeOpt != null) wayMem.failed(escapeOpt.memoryKey(), msg == null ? "the way up ended without a word" : msg, bb.clock.now());
            escapeFailNow("climbing out failed too: " + msg);
        }
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
        return AaEscape.holeExit(AaEscape.holeMaxUp(), open, stepOut);
    }

    /** With ground overhead: the levels up to the first cell with open feet, head and sky (at most 2 x the tunnel cap). */
    private static int skyLevels(net.minecraft.world.level.Level lv, int[] me) {
        int maxY = Math.min(lv.getMaxBuildHeight() - 2, me[1] + 2 * io.github.mojolowjo.entropybot.aa.Replan.tunnelCap());
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
     * Review 2 item 5: block light on a 9 x 9 grid (step 4) within 16 of the bot at its feet level, as the lit share
     * (Costs.litShare): one torch at the feet lights about 5 of 81 points, a lit camp most of them.
     */
    // ---- AA_CLEANUP 4 / AA_ROUND4 B1-B3: the threat facts, the surface height, the torches near, the tuning file ----

    private io.github.mojolowjo.entropybot.threat.ThreatFacts threatMemo;
    private long threatAt = -1;

    /** The danger desire's facts (DangerMoves.facts), at most once a second. Never throws (calm facts, logged there). */
    private io.github.mojolowjo.entropybot.threat.ThreatFacts threatFacts() {
        long now = System.currentTimeMillis();
        if (threatMemo != null && now - threatAt < Tuning.i("aaRuntime.threatMemoMs")) return threatMemo;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return io.github.mojolowjo.entropybot.threat.ThreatFacts.CALM;
        threatAt = now;
        threatMemo = Core.INSTANCE.reflexes.moves.facts(mc, mc.player, bb.in.basePos, null, Core.INSTANCE.reflexes.recentlyHurt());
        return threatMemo;
    }

    /** The surface's y at x z (the MOTION_BLOCKING_NO_LEAVES heightmap), MIN_VALUE when the chunk is not loaded. */
    private static int surfaceY(int x, int z) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || !mc.level.hasChunk(x >> 4, z >> 4)) return Integer.MIN_VALUE;
        return mc.level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
    }

    private int torchMemo;
    private long torchAt = -1;

    /** Torches (and lanterns) within 16 across and 4 up or down, counted every 5 s. */
    private int torchesNear(net.minecraft.world.level.Level lv, net.minecraft.core.BlockPos at) {
        long now = System.currentTimeMillis();
        if (torchAt >= 0 && now - torchAt < Tuning.i("aaRuntime.torchMemoMs")) return torchMemo;
        torchAt = now;
        int n = 0;
        net.minecraft.core.BlockPos.MutableBlockPos m = new net.minecraft.core.BlockPos.MutableBlockPos();
        for (int dx = -Tuning.i("aaRuntime.torchR"); dx <= Tuning.i("aaRuntime.torchR"); dx++)
            for (int dz = -Tuning.i("aaRuntime.torchR"); dz <= Tuning.i("aaRuntime.torchR"); dz++)
                for (int dy = -Tuning.i("aaRuntime.torchDy"); dy <= Tuning.i("aaRuntime.torchDy"); dy++) {
                    m.set(at.getX() + dx, at.getY() + dy, at.getZ() + dz);
                    if (!lv.isLoaded(m)) continue;
                    var st = lv.getBlockState(m);
                    if (st.isAir() || st.getLightEmission() < Tuning.i("aaRuntime.torchLight")) continue;
                    String id = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(st.getBlock()).getPath();
                    if (id.contains("torch") || id.contains("lantern")) n++;
                }
        torchMemo = n;
        return n;
    }

    private static Path tuningFile() {
        try {
            return Core.INSTANCE.files() == null || Core.INSTANCE.files().worldRoot() == null ? null : Core.INSTANCE.files().worldRoot().resolve("aa-tuning.json");
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Loads the world's aa-tuning.json (absent: the defaults); errors are logged and the defaults kept for those. */
    /** 0.38.b9: why the world's tuning file could not be read or written (aa status), null = fine. */
    private String tuningProblem;

    private void loadTuning() {
        Path f = tuningFile();
        tuningProblem = null;
        Map<String, Double> m = new java.util.LinkedHashMap<>();
        if (f != null && java.nio.file.Files.exists(f)) {
            try {
                JsonObject o = com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(f)).getAsJsonObject();
                for (var e : o.entrySet()) {
                    try { m.put(e.getKey(), e.getValue().getAsDouble()); } catch (RuntimeException x) { m.put(e.getKey(), Double.NaN); }
                }
            } catch (Exception e) {
                tuningProblem = "aa-tuning.json unreadable (" + e.getMessage() + ") - defaults used; fix or delete it";
                LOG.warn("[entropybot] aa: tuning file {} unreadable ({}) - defaults used", f, e.toString());
            }
        }
        java.util.List<String> errors = io.github.mojolowjo.entropybot.aa.brain.Tuning.load(m);
        if (!errors.isEmpty()) {
            tuningProblem = "aa-tuning.json: " + String.join("; ", errors) + " - defaults used for those";
            LOG.warn("[entropybot] aa: tuning: {} - defaults used for those", String.join("; ", errors));
        }
        else if (!m.isEmpty()) LOG.info("[entropybot] aa: tuning loaded ({} tuned)", m.size());
    }

    /** Writes the tuned values; null = saved, else why not (logged). */
    private static String saveTuning() {
        Path f = tuningFile();
        if (f == null) {
            LOG.warn("[entropybot] aa: tuning not saved: no world folder");
            return "no world folder";
        }
        try {
            JsonObject o = new JsonObject();
            io.github.mojolowjo.entropybot.aa.brain.Tuning.changed().forEach(o::addProperty);
            java.nio.file.Files.createDirectories(f.getParent());
            Path tmp = f.resolveSibling("aa-tuning.json.tmp");
            java.nio.file.Files.writeString(tmp, new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(o));
            java.nio.file.Files.move(tmp, f, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            LOG.warn("[entropybot] aa: tuning write: {}", e.toString());
            return "the write failed: " + e.getMessage();
        }
        return null;
    }

    /** aa status: the tuning file's problem, or "". */
    String tuningLine() { return tuningProblem == null ? "" : "\n!! tuning: " + tuningProblem; }

    /** aa tune [name value]: lists the tuning, or sets one for this world and saves it. */
    String tune(String[] w) {
        if (w.length == 0) return "tuning: " + io.github.mojolowjo.entropybot.aa.brain.Tuning.names().size() + " numbers in groups (aa tune <group> lists one group, aa tune <name> lists one, aa tune <name> <n> sets one; saved per world in aa-tuning.json):\n"
                + String.join("\n", io.github.mojolowjo.entropybot.aa.brain.Tuning.groups()) + tuningLine();
        if (w.length == 1) {
            List<String> l = io.github.mojolowjo.entropybot.aa.brain.Tuning.lines(w[0]);
            return l.isEmpty() ? "error: no tuning group or name \"" + w[0] + "\" - aa tune lists the groups" : String.join("\n", l) + tuningLine();
        }
        if (w.length != 2) return "usage: aa tune [<group> | <name> [<n>]]";
        double v;
        try { v = Double.parseDouble(w[1]); } catch (NumberFormatException e) { return "error: " + w[1] + " is not a number"; }
        String r = io.github.mojolowjo.entropybot.aa.brain.Tuning.set(w[0], v);
        if (r.startsWith("ok")) {
            String why = saveTuning();
            LOG.info("[entropybot] aa: {}", r);
            return why == null ? r + " (saved for this world)" : r + " - NOT saved: " + why + " (it holds until the game closes or another world loads)";
        }
        return r;
    }

    private static double litShare(net.minecraft.world.level.Level lv, net.minecraft.core.BlockPos at) {
        int[] l = new int[81];
        int i = 0;
        for (int dx = -Tuning.i("aaRuntime.torchR"); dx <= Tuning.i("aaRuntime.torchR"); dx += Tuning.i("aaRuntime.lightStep"))
            for (int dz = -Tuning.i("aaRuntime.torchR"); dz <= Tuning.i("aaRuntime.torchR"); dz += Tuning.i("aaRuntime.lightStep"))
                l[i++] = lv.getBrightness(net.minecraft.world.level.LightLayer.BLOCK, at.offset(dx, 0, dz));
        return io.github.mojolowjo.entropybot.aa.brain.Costs.litShare(l);
    }

    /**
     * AA_ROUND3 1 finding 3: every stair heading's cells ({@code dig stairs up <dir> n}, n = levels, at most StairPlan.MAX)
     * read and checked against the policy (the dig refuses a keep-out / no-break box: the first such cell is kept).
     */
    private static List<io.github.mojolowjo.entropybot.aa.WayUp.Heading> headings(net.minecraft.world.level.Level lv, int[] me, int levels) {
        List<io.github.mojolowjo.entropybot.aa.WayUp.Heading> out = new java.util.ArrayList<>();
        int n = Math.min(Math.max(1, levels), StairPlan.maxV());
        for (String d : io.github.mojolowjo.entropybot.aa.WayUp.DIRS) {
            int[] u = io.github.mojolowjo.entropybot.aa.WayUp.dir(d);
            List<io.github.mojolowjo.entropybot.aa.Replan.Cell> cells = new java.util.ArrayList<>();
            int[] refused = null;
            for (int[] c : io.github.mojolowjo.entropybot.aa.WayUp.stairCells(me[0], me[1], me[2], u[0], u[1], n)) {
                if (refused == null && !Clearing.boxAllowed(io.github.mojolowjo.entropybot.clear.ClearBox.of(c[0], c[1], c[2], c[0], c[1], c[2]))) refused = c;
                cells.add(cellAt(lv, c[0], c[1], c[2]));
            }
            out.add(new io.github.mojolowjo.entropybot.aa.WayUp.Heading(d, n, cells, refused));
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

    private void clearEscape() {
        escapePending = false;
        escapeWay = null;
        escapeFail = null;
        escapeJob = null;
        escapeOpt = null;
        returnDone = null;
        escapeResumeSteps = null;
    }

    /** 0.36.a6: starts the pending escape once the stuck walk has settled; a pillar job's end -> go on, or climb. */
    private void escapeTick(LocalPlayer p, Minecraft mc) {
        if (current < 0) {
            if (escapePending || escapeWay != null) clearEscape();
            return;
        }
        if ("climb".equals(escapeWay)) {                 // rm-execution2: the way up runs on typed steps
            if (c.stepRun.running()) return;
            var st = c.stepRun.steps;
            boolean done = st.phase() == io.github.mojolowjo.entropybot.aa.moves.Steps.Phase.DONE;
            String msg = st.why();
            st.clear();
            wayUpEnded(done, msg);
            return;
        }
        if (escapePending) {
            if (c.chains.running() || c.jobs.running() || c.stepRun.running()) return;
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
                    f.sky(), f.skyDist(), false, f.kit(), null, f.headings());
            else f = new io.github.mojolowjo.entropybot.aa.RungOptions.SurfaceFacts(f.depth(), f.me(), f.shaftOpen(), f.throwaway(), f.placeAllowed(), f.pickaxe(),
                    f.sky(), f.skyDist(), false, f.kit(), f.column(), f.headings());
            // AA_ROUND3 1: the one way-up set, priced; a failed way is held in Failures under "wayup/<id>"
            wayMem.bind(tree.brain.failures, WAYUP);

            io.github.mojolowjo.entropybot.cave.MineRules.bind(tree.brain.failures);          // rm-mining: a failed per-ore cell holds in aa why
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
            // rm-execution2: the way up's typed steps (stairs, the climb, a walk) on the one step machine
            String cr = c.stepRun.start(WAYUP + " " + o.memoryKey(), o.steps(), "wayup " + o.id());
            if (cr == null) escapeWay = "climb";
            else {
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
        List<io.github.mojolowjo.entropybot.aa.moves.Step> resumeSteps = escapeResumeSteps;
        escapeResumeSteps = null;
        String fail = escapeFail;
        String back = returnDone;
        returnDone = null;
        if (back != null) {                  // aa-replan: the way back after the ore step is over
            if (runningOpt != null) stepOffset = runningOpt.ret().afterStep() + 1;
            if (resumeSteps == null) {
                escapeFail = null;
                lastEnd = back + " (back: " + how + ")";
                LOG.info("[entropybot] aa: back up ({}) - the task is done", how);
                return;
            }
        }
        if (resumeSteps != null) {             // rm-execution: the typed task goes on with its steps
            String tr = runTyped(typedKey, resumeSteps, "resume");
            if (tr == null) {
                escapeFail = null;
                LOG.info("[entropybot] aa: out from under ground ({}) - going on with: {}", how, io.github.mojolowjo.entropybot.aa.moves.Step.label(resumeSteps));
            } else {
                escapeFail = fail;
                escapeFailNow("out (" + how + "), but the task did not go on: " + tr);
            }
            return;
        }
        escapeFail = fail;
        escapeFailNow("out (" + how + "), but no step was left to go on with");
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
     * (the policy's keep-out / no-break, a protected build near; a tree that failed is held by its option's key). A seen tree whose chunk is
     * loaded and whose lowest log is gone is exhausted here. Null when nothing can be read; never throws.
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
            for (var r : sg.treesNear(gdim, me[0], me[2], io.github.mojolowjo.entropybot.aa.WoodOptions.farMax())) {
                net.minecraft.core.BlockPos q = new net.minecraft.core.BlockPos(r.x(), r.y(), r.z());
                if (mc.level.isLoaded(q) && !mc.level.getBlockState(q).is(net.minecraft.tags.BlockTags.LOGS)) {
                    sg.exhaustTree(gdim, r.x(), r.z(), now);
                    continue;
                }
                String deny = !c.workAllowed(adim, r.x(), r.y(), r.z()) ? c.policy.blockedText(adim, r.x(), r.z())
                        : io.github.mojolowjo.entropybot.cave.MineRules.protectNear(prot, adim, r.x(), r.y(), r.z(), io.github.mojolowjo.entropybot.cave.MineRules.protectMargin()) != null ? "a protected build is near" : null;
                out.add(new io.github.mojolowjo.entropybot.aa.WoodOptions.Tree(r.x(), r.y(), r.z(), Math.hypot(r.x() - me[0], r.z() - me[2]), deny));
            }
            return new io.github.mojolowjo.entropybot.aa.WoodOptions.WoodFacts(0, out);
        } catch (RuntimeException e) {
            LOG.warn("[entropybot] aa: wood facts: {}", e.toString());
            return null;
        }
    }

    // ---- status, files ----

    /** "AA: slot>needs>...RUNNING 34s by needs | danger: calm | last switch 2m ago: done TOOLS" (AA_PLAN 6). */
    public String statusLine() {
        long now = System.currentTimeMillis();
        if (!on()) return "AA: off" + (offReason != null ? " (" + offReason + ")" : "");
        StringBuilder b = new StringBuilder("AA: ").append(io.github.mojolowjo.entropybot.aa.brain.Only.modeText()).append(" | ");     // orders-tier: the mode first
        if (now < heldUntil) b.append("held ").append((heldUntil - now + 59_999) / 60_000).append(" min after a stop");  // structural: round up to whole minutes
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
                + ", AI requests " + aiRequests + " (" + bb.requests().size() + " waiting)"
                + ", " + io.github.mojolowjo.entropybot.aa.door.Door.text()     // AA_CLEANUP 2: the one door
                + ", " + restoreLine();
    }

    /** AA_STEP2 3: "restore pending: N blocks" (running now / waiting in the ledger). */
    private String restoreLine() {
        int n;
        try {
            n = RestoreLive.INSTANCE.pendingCount();
        } catch (RuntimeException e) {
            return "restore pending: ? (" + e + ")";
        }
        return "restore pending: " + n + (n == 1 ? " block" : " blocks") + (restoreInSlot() ? " (putting back now, AA's own: any desire halts it)" : "");
    }

    /** AA_STEP2 3: the job in the slot is only putting back blocks (a job's end restore or a restore job). */
    private boolean restoreInSlot() {
        return c.jobs.running() && c.jobs.job.restoreRun != null;
    }

    /** The ladder's reasons this tick ("needs.tools: TOOLS met; needs.torches: TORCHES planning ..."). */
    String ladderLine() {
        StringBuilder b = new StringBuilder();
        for (var r : io.github.mojolowjo.entropybot.aa.Need.all(tree.ladder)) {
            if (b.length() > 0) b.append("; ");
            b.append(r.id.substring(6)).append(": ").append(r.last() == null ? "not checked" : r.last() == Status.FAILURE ? r.reason() : r.last().name());
        }
        return b.toString();
    }

    // ---- BRAIN_PLAN B2: the recipe tech tree, what is had, measured costs ----

    private int treeRecipes = -1;
    private long treeCheckedAt = -Tuning.i("aaRuntime.treeEveryMs"), statsSavedAt, haveAt = -Tuning.i("aaRuntime.haveEveryMs");
    private boolean statsLoaded;
    private String treeError;

    /**
     * Once a minute: the tech tree from the client's RecipeManager (rebuilt when the recipe count changes; 2.8), the
     * set of items had (bag + known table/furnace), the stats file loaded once and saved when changed (2.9). Never
     * throws; a failure is logged once and shown by aa stats.
     */
    /** AA_ROUND4 B3: the world folder the tuning was loaded for (a new world loads its own). */
    private Path tuningFor;

    private void brainFacts(Minecraft mc, long now) {
        Path tf = tuningFile();
        if (tf != null && !tf.equals(tuningFor)) {
            tuningFor = tf;
            loadTuning();
        }
        if (!statsLoaded && statsFile() != null) {      // review: only once the files are known
            statsLoaded = true;
            loadStats();
            loadPersonality();
        }
        // review: the bag set at most every 10 s (the tech tree's caches are per bag; mining changes it all the time)
        if (now - haveAt >= Tuning.i("aaRuntime.haveEveryMs")) {
            haveAt = now;
            java.util.Set<String> have = new java.util.HashSet<>();
            if (sensed != null && sensed.feas != null) for (var e : sensed.feas.bag.entrySet()) if (e.getValue() > 0) have.add(e.getKey());
            if (bb.in.tableKnown) have.add("crafting_table");
            if (bb.in.furnaceKnown) have.add("furnace");
            if (!have.equals(bb.fact(io.github.mojolowjo.entropybot.aa.AaDesires.HAVE_FACT))) bb.fact(io.github.mojolowjo.entropybot.aa.AaDesires.HAVE_FACT, java.util.Set.copyOf(have));
        }
        if (now - treeCheckedAt >= Tuning.i("aaRuntime.treeEveryMs")) {
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
                    try {       // AA_ROUND4 C: the item data the worth is derived from (rebuilt with the recipes)
                        var src = ItemWorthSource.read(mc.level);
                        io.github.mojolowjo.entropybot.aa.brain.ItemWorth.use(src);
                        LOG.info("[entropybot] aa: item worth from data: {} items with facts, {} with recipes, {} unreadable", src.itemsWithFacts(), src.itemsWithRecipes(), src.failed);
                    } catch (RuntimeException e) {
                        LOG.warn("[entropybot] aa: item worth data not read - the tables stay in use: {}", e.toString());
                    }
                    io.github.mojolowjo.entropybot.aa.brain.MiningTiers.use(null);      // re-read with the recipes
                    miningTiersWarned = false;
                    treeError = null;
                    LOG.info("[entropybot] aa: tech tree from {} recipes ({} usable)", all.size(), t.size());
                }
            } catch (RuntimeException e) {
                if (treeError == null) LOG.warn("[entropybot] aa: tech tree: {}", e.toString());
                treeError = e.toString();
            }
            loadMiningTiers();
        }
        Path f = statsFile();
        if (f != null && now - statsSavedAt >= Tuning.i("aaRuntime.statsSaveMs") && tree.brain.stats.takeDirty()) {
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
            } catch (RuntimeException ex) {
                io.github.mojolowjo.entropybot.Quiet.warn("aa: the name of " + id + " could not be read (left out of the name list)", ex);
            }
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
        lootPassive = new java.util.HashSet<>();
        var book = lootBook(mc, lootPassive);
        bb.fact(io.github.mojolowjo.entropybot.aa.brain.LootBook.FACT, book);
        // one-planner: an ore is any block whose loot drops the item (the loot data both ways), no family words
        java.util.function.Function<String, List<String>> dropsOf = b -> {
            List<String> out = new ArrayList<>();
            for (var s : book.blockDrops(b)) if (!out.contains(s.item())) out.add(s.item());
            return out;
        };
        catalog = new io.github.mojolowjo.entropybot.aa.brain.Thing.Catalog(items, blocks, names, tiers, gen, dropsOf, book::blocksDropping);
        catalogWorld = world;
        if (book.origin.startsWith("read failed")) catalogWorld = null;      // review: try again on the next aa want
        LOG.info("[entropybot] aa: resolver catalog: {} items, {} blocks, worldgen {}", items.size(), blocks.size(),
                gen == null ? "none (multiplayer)" : gen.size() + " ore blocks");
        return catalog;
    }

    /** live-facts: the world the tick last read the catalog (and the loot book) for; a failed read is retried by the owner's aa want, not every tick. */
    private String catalogTried;

    /** live-facts: the first AA tick in a world (aa on, a world joined) reads the catalog and the loot book, so the collect step and NeedChain have it. */
    private void worldFacts(Minecraft mc) {
        String world = Core.INSTANCE.files() == null ? "" : Core.INSTANCE.files().worldRoot().toString();
        if (world.equals(catalogTried)) return;
        catalogTried = world;
        catalog(mc);
        var book = currentLootBook();
        LOG.info("[entropybot] aa: loot book for this world: {}", book == null ? "none" : book.counts());     // a failed read says "read failed" here and warns above
    }

    /** rm-execution: the loot book read for this world (the collect step's blocks), or null before the first read. */
    io.github.mojolowjo.entropybot.aa.brain.LootBook currentLootBook() {
        return bb.fact(io.github.mojolowjo.entropybot.aa.brain.LootBook.FACT) instanceof io.github.mojolowjo.entropybot.aa.brain.LootBook b ? b : null;
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
    /** A friendly-category mob that fights back (a wolf, a bee, a polar bear, a llama): the game gives it an attack-damage attribute or a ranged attack; never "passive" for a loot hunt. */
    static boolean fightsBack(String id) {
        var m = io.github.mojolowjo.entropybot.aa.brain.ItemWorth.mob(id);
        return m != null && (!Double.isNaN(m.damage()) || m.move() == io.github.mojolowjo.entropybot.aa.brain.ItemWorth.MobMove.RANGED);
    }
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
                if (type.getCategory().isFriendly() && !fightsBack(id)) passive.add(id);   // review: neutral ones fight back
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
        LOG.info("[entropybot] aa: loot data: {}; {} tables/trades skipped; {} ms", book.counts(), bad[0], (System.nanoTime() - t0) / 1_000_000);  // structural: nanoseconds to milliseconds
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
        boolean armed = false;       // a weapon in the bag that hits as hard as the danger model's armed bar (the game's attack-damage component)
        for (var e : bb.in.bag.entrySet()) if (e.getValue() > 0 && io.github.mojolowjo.entropybot.aa.brain.ItemWorth.facts(e.getKey()).attack() >= io.github.mojolowjo.entropybot.threat.ThreatFacts.armed()) armed = true;
        bb.fact(io.github.mojolowjo.entropybot.aa.brain.LootBook.CTX_FACT, new io.github.mojolowjo.entropybot.aa.brain.LootBook.Ctx(
                c.stepRun != null && c.stepRun.on(), lootPassive, java.util.Set.copyOf(c.vocab.expand("animals")),
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

    /** Every 10 s while goals exist: the loot context refreshed, reached goals said and dropped (the chain prices the rest). */
    private boolean knownDue;

    private void goalsTick(LocalPlayer p, long tick) {
        buildsTick(p, tick);
        var goals = io.github.mojolowjo.entropybot.aa.brain.Goals.of(bb);
        if (goals.isEmpty()) return;
        if (tick % 200 != 7 && !knownDue) return;  // structural: periodic cadence in ticks (20 ticks = 1 s)
        knownDue = false;
        lootCtx();
        for (var g : io.github.mojolowjo.entropybot.aa.brain.Goals.done(bb)) {
            tree.brain.remove(g.id(), "goal reached");
            String msg = "got " + io.github.mojolowjo.entropybot.aa.brain.Goals.have(bb.in.bag, g) + " " + g.r.key() + " (goal " + g.n + " done)";
            LOG.info("[entropybot] aa: {}", msg);
            c.whisper(c.owner(), "aa: " + msg);
        }
    }

    // ---- B7: schematic builds (2.12) ----

    private boolean buildsDue;

    /** Every 10 s while builds exist (at once after aa build): each build scanned in its dimension, a finished one said and dropped. */
    private void buildsTick(LocalPlayer p, long tick) {
        var builds = io.github.mojolowjo.entropybot.aa.brain.Blueprint.of(bb);
        if (builds.isEmpty() || (tick % 200 != 11 && !buildsDue)) return;  // structural: periodic cadence in ticks (20 ticks = 1 s)
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
            io.github.mojolowjo.entropybot.aa.brain.Wants.done(bb, "want." + wantKey, now - (long) (100 * io.github.mojolowjo.entropybot.aa.brain.Wants.DAY_MS));  // structural: percent
            return "ok: want." + wantKey + " is bored now (weight " + io.github.mojolowjo.entropybot.aa.brain.Personality.fmt(io.github.mojolowjo.entropybot.aa.brain.Personality.of(bb).get(wantKey))
                    + "; aa like " + wantKey + " <n> to raise it)"
                    + (io.github.mojolowjo.entropybot.aa.brain.Only.orders() ? " - but orders only is on: wants are silent (aa needs on to let it run)" : "");
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
        tree.brain.failures.forget(g.id());           // orders-tier: aa want again clears a parked order and its old holds
        lootCtx();
        // one-planner: the order is priced by the one chain now; the line says the chain or the explore it starts
        var ch = io.github.mojolowjo.entropybot.aa.brain.Goals.chain(bb, g);
        String lootLine = ch == null ? "; chain: no facts yet (the senses are not ready)" : "; chain: " + ch.why();
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

    private boolean miningTiersWarned;

    /** AA_ROUND4 C: the pickaxe tier each block needs; retried every minute until the tags are there (until then the old table, said in aa why). */
    private void loadMiningTiers() {
        if (io.github.mojolowjo.entropybot.aa.brain.MiningTiers.ready() || bb.fact(io.github.mojolowjo.entropybot.aa.AaDesires.TECH_FACT) == null) return;
        try {
            var mt = MiningTierSource.read(Minecraft.getInstance().getSingleplayerServer());
            io.github.mojolowjo.entropybot.aa.brain.MiningTiers.use(mt);
            List<io.github.mojolowjo.entropybot.aa.brain.ToolGroup.Tier> gameTiers = new ArrayList<>();       // the game's own tier enum: name, speed, attack bonus
            for (net.minecraft.world.item.Tiers t : net.minecraft.world.item.Tiers.values()) gameTiers.add(new io.github.mojolowjo.entropybot.aa.brain.ToolGroup.Tier(t.name(), t.getSpeed(), t.getUses()));
            io.github.mojolowjo.entropybot.aa.brain.ToolGroup.use(gameTiers);
            LOG.info("[entropybot] aa: mining tiers from data: {} pickaxes, {} blocks need a tool, drops {} ({} failed)", mt.pickaxes, mt.blocksNeedingTool, mt.dropsRead ? "read" : "not read (no integrated server)", mt.dropFailures);
        } catch (RuntimeException e) {
            if (!miningTiersWarned) LOG.warn("[entropybot] aa: mining tier data not read yet - the blocked share uses the old table, retrying every minute: {}", e.toString());
            miningTiersWarned = true;
        }
    }

    String ideasLine() { return io.github.mojolowjo.entropybot.aa.AaDesires.ideasLine(bb, 5); }

    /** The dashboard's live AA tree (fast channel GET /aa), built from the running tree. */
    com.google.gson.JsonObject live() {
        var o = io.github.mojolowjo.entropybot.aa.AaLive.json(tree, bb, on(), io.github.mojolowjo.entropybot.aa.AaLive.switchesV());
        o.add("slow", slow.json(System.currentTimeMillis()));          // AA_STEP3 B: the waiting plan, the Go button's state
        return o;
    }

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
        o.addProperty("door", io.github.mojolowjo.entropybot.aa.door.Door.text());     // AA_CLEANUP 2
        }
        return o;
    }

    /** check's findings. */
    List<String[]> findings() {
        List<String[]> out = new ArrayList<>();
        if (offReason != null) out.add(new String[]{"aa", "aa " + offReason, "aa on (after a look at the log)"});
        if (on() && tracer.counts.lost > 0) out.add(new String[]{"aalost", "aa lost " + tracer.counts.lost + " task(s) to someone else (a poster not gated)", "aa why"});
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
        if (now - lastSnapshotAt < Tuning.i("aaRuntime.snapshotMs")) return;
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
                    runStuckHalts, runDeaths, runMarks.foodEvents(), (System.currentTimeMillis() - start) / 60000.0);  // structural: milliseconds per minute
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
        if (now - lastWriteErrorAt > Tuning.i("aaRuntime.writeErrorMs")) {
            lastWriteErrorAt = now;
            LOG.warn("[entropybot] aa: {} write failed ({} so far): {}", what, writeErrors, e.toString());
        }
    }
}

