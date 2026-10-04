package io.github.mojolowjo.entropybot.recorder;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import io.github.mojolowjo.entropybot.Core;
import io.github.mojolowjo.entropybot.events.EventRing;
import io.github.mojolowjo.entropybot.guard.Guard;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.slf4j.Logger;

import java.lang.ref.WeakReference;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The flight recorder (B7e E5, docs/B7E_PLAN.md 7a): a cheap 3D history around the bot. On the game thread it only
 * copies (section palettes, block ids, positions) and queues; a background thread encodes and writes ({@link RecStore}).
 * <ul>
 *   <li>baselines: each loaded chunk within the range, when it loads (a new chunk object) and again after
 *       {@link #REBASE_MS} while it stays loaded;</li>
 *   <li>changes: from {@code RecorderMixinClientLevel} (every block the client sets: the server's updates and the bot's
 *       own predicted breaks and places, which are the {@code byBot} ones);</li>
 *   <li>the trail: the bot's block position every {@code trailTicks} when it moved (and once a minute anyway), job starts
 *       (seen in {@code tick}) and ends ({@link #jobEnded}), marks and notes;</li>
 *   <li>incidents: a failed job end, a death, a guard refusal streak, or {@code recorder snapshot now}.</li>
 * </ul>
 * Installed in Core's ready block; the read side is thread-safe and never touches the level.
 */
public final class FlightRecorder implements Recorder, RecorderCommand.Controls {
    private static final Logger LOG = LogUtils.getLogger();
    static final long REBASE_MS = 30 * 60_000L, HEARTBEAT_MS = 60_000L, AUTO_INCIDENT_GAP_MS = 20_000L;
    static final long INCIDENT_LOOKBACK_MS = 5 * 60_000L;
    static final int MAX_TASKS = 32, SPIRAL_MAX = RecorderSettings.RANGE_MAX;

    /** The recorder the mixin reports to (the one installed last). */
    static volatile FlightRecorder active;

    private final RecStore store;
    private final EventRing events;
    private final ZoneId zone = ZoneId.systemDefault();
    private final RecorderSettings settings;
    private final ScheduledExecutorService io;
    private final AtomicInteger tasks = new AtomicInteger();
    private final int[][] spiral;
    private final Map<Long, Tracked> tracked = new HashMap<>();
    private final Map<Block, String> blockIds = new ConcurrentHashMap<>();
    private final Map<BlockState, String> stateIds = new ConcurrentHashMap<>();
    private final IncidentText.GuardStreak guardStreak = new IncidentText.GuardStreak(5, 60_000L, 5 * 60_000L);

    // game-thread state
    private volatile boolean on;
    private volatile boolean states;
    private volatile int effRange = 4;
    private volatile ClientLevel lastLevel;
    private volatile String lastDim = "minecraft:overworld";
    private int cursor;
    private Object lastJob;
    private int[] lastTrail;
    private String lastTrailDim;
    private long lastTrailMs, lastAutoIncident = Long.MIN_VALUE / 2, guardSeq = -1;
    private boolean wasDead;
    private int errors;
    private volatile int incidentCount;
    private volatile String lastIncident;

    private static final class Tracked {
        final WeakReference<LevelChunk> ref;
        final long takenMs, since;

        Tracked(LevelChunk c, long takenMs, long since) {
            this.ref = new WeakReference<>(c);
            this.takenMs = takenMs;
            this.since = since;
        }
    }

    public FlightRecorder(Path dir, EventRing events) {
        this.store = new RecStore(dir, System::currentTimeMillis, RecStore.DEFAULT_CAP);
        this.events = events;
        this.settings = RecorderSettings.fromJson(store.readSettings());
        String ended = settings.tick(System.currentTimeMillis());
        store.keepHours(settings.keepHours);
        applyLive();
        List<int[]> s = new ArrayList<>();
        for (int dx = -SPIRAL_MAX; dx <= SPIRAL_MAX; dx++) for (int dz = -SPIRAL_MAX; dz <= SPIRAL_MAX; dz++) s.add(new int[]{dx, dz});
        s.sort(Comparator.<int[]>comparingInt(a -> Math.max(Math.abs(a[0]), Math.abs(a[1]))).thenComparingInt(a -> a[0] * a[0] + a[1] * a[1]));
        spiral = s.toArray(new int[0][]);
        io = Executors.newSingleThreadScheduledExecutor(run -> {
            Thread t = new Thread(run, "entropybot-recorder");
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
        });
        io.scheduleWithFixedDelay(this::flushQuietly, 1, 1, TimeUnit.SECONDS);
        io.scheduleWithFixedDelay(this::maintainQuietly, 3, 60, TimeUnit.SECONDS);
        if (ended != null) saveSettings();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try { store.flush(); } catch (Throwable ignored) {}
        }, "entropybot-recorder-exit"));
        RecorderStatus.install(this::statusJson);
        active = this;
        LOG.info("[entropybot] recorder: {} in {}", settings.line(), dir);
    }

    private void applyLive() {
        on = settings.on();
        states = settings.states;
    }

    // ---- background ----

    private void flushQuietly() {
        try { store.flush(); } catch (Throwable t) { ioError("flush", t); }
    }

    private void maintainQuietly() {
        try {
            store.maintain();
            refreshIncidents();
        } catch (Throwable t) {
            ioError("maintain", t);
        }
    }

    private void refreshIncidents() {
        List<Incident> l = store.incidents(1);
        incidentCount = l.isEmpty() ? 0 : l.get(0).n();
        lastIncident = l.isEmpty() ? null : IncidentText.time(l.get(0).atMs(), zone, "HH:mm") + " " + cut(l.get(0).reason(), 80);
    }

    private int ioErrors;

    private void ioError(String what, Throwable t) {
        if (++ioErrors <= 5 || ioErrors % 100 == 0) LOG.warn("[entropybot] recorder: {} failed (#{}): {}", what, ioErrors, t.toString());
    }

    /** Runs on the background thread unless MAX_TASKS are waiting (then false: the caller tries again later). */
    private boolean submit(Runnable r) {
        if (tasks.get() >= MAX_TASKS) return false;
        tasks.incrementAndGet();
        try {
            io.execute(() -> {
                try { r.run(); } catch (Throwable t) { ioError("task", t); } finally { tasks.decrementAndGet(); }
            });
            return true;
        } catch (RuntimeException e) {
            tasks.decrementAndGet();
            return false;
        }
    }

    // ---- hooks (game thread) ----

    @Override
    public void tick(long tick) {
        try {
            step(tick);
        } catch (RuntimeException e) {
            if (++errors <= 5 || errors % 1200 == 0) LOG.warn("[entropybot] recorder tick error #{}: {}", errors, e.toString());
        }
    }

    private void step(long tick) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        ClientLevel level = mc.level;
        depth = 0;                                   // no setBlock is in progress here: a lost RETURN can't pile up
        if (p == null || level == null) return;
        long now = System.currentTimeMillis();
        if (level != lastLevel) {
            lastLevel = level;
            lastDim = Guard.dimOf(level);
            tracked.clear();
        }
        String dim = lastDim;
        if (tick % 20 == 0) {
            String ended;
            synchronized (settings) {
                ended = settings.tick(now);
                if (ended != null) applyLive();
                effRange = Math.max(1, Math.min(settings.range, mc.options.getEffectiveRenderDistance()));
            }
            if (ended != null) {
                saveSettings();
                note(p, dim, now, "recorder: " + ended);
            }
        }
        if (!on) return;
        int[] me = {p.getBlockX(), p.getBlockY(), p.getBlockZ()};
        // a death (with or without a job running)
        boolean dead = p.isDeadOrDying();
        if (dead && !wasDead) {
            note(p, dim, now, "died");
            autoIncident(now, "the bot died at " + me[0] + " " + me[1] + " " + me[2], jobText(), null);
        }
        wasDead = dead;
        // a job start
        Object j = currentJob();
        if (j != lastJob) {
            lastJob = j;
            String t = jobText();
            if (t != null) note(p, dim, now, "start " + t);
        }
        // the trail
        int every;
        synchronized (settings) { every = settings.trailTicks; }
        if (tick % every == 0) {
            boolean moved = lastTrail == null || lastTrail[0] != me[0] || lastTrail[1] != me[1] || lastTrail[2] != me[2] || !dim.equals(lastTrailDim);
            if (moved || now - lastTrailMs >= HEARTBEAT_MS) trailPoint(dim, me, now, null);
        }
        // the guard's refusals
        if (tick % 20 == 10) guardScan(now);
        // baselines
        if (tick % 2 == 0) baselineStep(level, dim, p, now);
        if (tick % 20 == 5) watchLoaded(level, dim, p, now);
    }

    private Object currentJob() {
        try {
            var jobs = Core.INSTANCE.commands.jobs;
            var j = jobs.job;
            return j != null && !j.done ? j : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private String jobText() {
        try {
            var j = Core.INSTANCE.commands.jobs.job;
            if (j == null || j.done) return null;
            return j.type + ": " + cut(j.label, 160);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private void trailPoint(String dim, int[] me, long now, String note) {
        store.addTrail(new TrailPoint(now, dim, me[0], me[1], me[2], note));
        if (note == null) {
            lastTrail = me;
            lastTrailDim = dim;
            lastTrailMs = now;
        }
    }

    private void note(LocalPlayer p, String dim, long now, String note) {
        trailPoint(dim, new int[]{p.getBlockX(), p.getBlockY(), p.getBlockZ()}, now, cut(note, 240));
    }

    @Override
    public void jobEnded(String type, String label, String msg) {
        try {
            Minecraft mc = Minecraft.getInstance();
            LocalPlayer p = mc.player;
            if (p == null || mc.level == null || !on) return;
            long now = System.currentTimeMillis();
            String job = type + ": " + cut(label, 160);
            note(p, lastDim, now, "end " + job + " -> " + cut(msg, 200));
            if (IncidentText.isFailure(msg)) {
                int[] target = IncidentText.coords(msg);
                if (target == null) target = IncidentText.coords(label);
                autoIncident(now, msg, job, target);
            }
        } catch (RuntimeException e) {
            if (++errors <= 5) LOG.warn("[entropybot] recorder job end: {}", e.toString());
        }
    }

    private void autoIncident(long now, String reason, String job, int[] target) {
        if (now - lastAutoIncident < AUTO_INCIDENT_GAP_MS) return;
        if (snapshot(reason, job, target) != null) lastAutoIncident = now;
    }

    private void guardScan(long now) {
        long last = events.lastSeq();
        if (guardSeq < 0) { guardSeq = last; return; }
        if (last <= guardSeq) return;
        String json = events.since(guardSeq, EventRing.CAPACITY);
        guardSeq = last;
        if (!json.contains("\"kind\":\"guard\"")) return;
        String lastText = null;
        boolean fire = false;
        for (JsonElement e : JsonParser.parseString(json).getAsJsonArray()) {
            JsonObject o = e.getAsJsonObject();
            if (!"guard".equals(o.get("kind").getAsString())) continue;
            JsonObject d = o.has("data") && o.get("data").isJsonObject() ? o.getAsJsonObject("data") : null;
            if (d == null || !d.has("enforced") || !d.get("enforced").getAsBoolean()) continue;
            lastText = o.get("text").getAsString();
            if (guardStreak.refused(now)) fire = true;
        }
        if (fire) {
            int[] target = IncidentText.coords(lastText);
            autoIncident(now, "guard refusal streak: 5 refusals within a minute, the last: " + cut(lastText, 160), jobText(), target);
        }
    }

    // ---- block changes (from the mixin, game thread) ----

    /** The old states of the setBlock calls in progress (they nest through neighbour updates); reset every tick. */
    private static final BlockState[] OLD = new BlockState[32];
    private static int depth;
    /** The mixin got called at least once (the hook is in). */
    public static volatile boolean hooked;

    /**
     * The mixin's HEAD: remembers the old state at pos (only while a recorder is installed and on; game thread only, as
     * every client block change is). Each call is matched by one {@link #popOld} at RETURN on the same thread.
     */
    public static void pushOld(net.minecraft.world.level.Level level, BlockPos pos) {
        if (!Minecraft.getInstance().isSameThread()) return;
        FlightRecorder r = active;
        if (depth < OLD.length) OLD[depth] = r != null && r.on ? level.getBlockState(pos) : null;
        depth++;
    }

    public static BlockState popOld() {
        if (depth <= 0 || !Minecraft.getInstance().isSameThread()) return null;
        depth--;
        if (depth >= OLD.length) return null;
        BlockState s = OLD[depth];
        OLD[depth] = null;
        if (s != null) hooked = true;
        return s;
    }

    /** The mixin's entry: a block the client set changed from {@code from} to {@code to}. Never throws. */
    public static void blockChanged(ClientLevel level, BlockPos pos, BlockState from, BlockState to, boolean byBot) {
        FlightRecorder r = active;
        if (r == null || !r.on || from == to) return;
        try {
            r.changed(level, pos, from, to, byBot);
        } catch (Throwable t) {
            if (++r.errors <= 5) LOG.warn("[entropybot] recorder change: {}", t.toString());
        }
    }

    private void changed(ClientLevel level, BlockPos pos, BlockState from, BlockState to, boolean byBot) {
        LocalPlayer p = Minecraft.getInstance().player;
        if (p == null || p.level() != level) return;
        boolean st = states;
        // a state-only flip (furnace lit, crop age, power) only with states on; only within the range
        if (!ChangeFilter.keep(from == to, from.getBlock() == to.getBlock(), st, p.getBlockX() >> 4, p.getBlockZ() >> 4,
                pos.getX() >> 4, pos.getZ() >> 4, effRange)) return;
        String dim = level == lastLevel ? lastDim : Guard.dimOf(level);
        store.addChange(new Change(System.currentTimeMillis(), dim, pos.getX(), pos.getY(), pos.getZ(),
                st ? stateId(from) : blockId(from), st ? stateId(to) : blockId(to), byBot));
    }

    String blockId(BlockState s) {
        Block b = s.getBlock();
        String id = blockIds.get(b);
        if (id == null) {
            id = BuiltInRegistries.BLOCK.getKey(b).toString();
            blockIds.put(b, id);
        }
        return id;
    }

    private String stateId(BlockState s) {
        String id = stateIds.get(s);
        if (id == null) {
            String t = s.toString();
            int i = t.indexOf('[');
            id = blockId(s) + (i >= 0 ? t.substring(i) : "");
            id = id.replace(' ', '_');
            if (stateIds.size() < 100_000) stateIds.put(s, id);
        }
        return id;
    }

    // ---- baselines (copies here, encoding in the background) ----

    private int spiralCount() {
        int r = effRange;
        return (2 * r + 1) * (2 * r + 1);
    }

    private static long chunkKey(int cx, int cz) { return ((long) cx << 32) ^ (cz & 0xffffffffL); }

    private void baselineStep(ClientLevel level, String dim, LocalPlayer p, long now) {
        if (tasks.get() >= MAX_TASKS / 2) return;
        int n = spiralCount(), pcx = p.getBlockX() >> 4, pcz = p.getBlockZ() >> 4;
        long rebase;
        synchronized (settings) { rebase = Math.min(REBASE_MS, settings.keepHours * 3_600_000L / 2); }
        for (int tries = 0; tries < n; tries++) {
            int[] o = spiral[cursor++ % n];
            if (cursor >= n * 1000) cursor = 0;
            int cx = pcx + o[0], cz = pcz + o[1];
            LevelChunk chunk = level.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, false);
            if (chunk == null || chunk.isEmpty()) continue;
            long k = chunkKey(cx, cz);
            Tracked t = tracked.get(k);
            boolean fresh = t == null || t.ref.get() != chunk;
            if (!fresh && now - t.takenMs < rebase) continue;
            long since = fresh ? now : t.since;
            Grab g = Grab.chunk(level, chunk, cx, cz);
            int minY = level.getMinBuildHeight();
            if (!submit(() -> {
                store.writeBase(g.toBase(this, dim, minY, now));
                store.covered(dim, cx, cz, since, System.currentTimeMillis());
            })) return;
            tracked.put(k, new Tracked(chunk, now, since));
            return;
        }
    }

    /** Once a second: chunks still loaded are "seen" (exact until now); unloaded ones are forgotten. */
    private void watchLoaded(ClientLevel level, String dim, LocalPlayer p, long now) {
        int pcx = p.getBlockX() >> 4, pcz = p.getBlockZ() >> 4, r = effRange + 2;
        for (Iterator<Map.Entry<Long, Tracked>> it = tracked.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Long, Tracked> e = it.next();
            int cx = (int) (e.getKey() >> 32), cz = (int) (long) e.getKey();
            LevelChunk now0 = level.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, false);
            if (now0 == null || now0 != e.getValue().ref.get() || Math.abs(cx - pcx) > r || Math.abs(cz - pcz) > r) {
                it.remove();
                continue;
            }
            store.seen(dim, cx, cz, now);
        }
    }

    /** Copies of block sections (taken on the game thread), turned into ids on the background thread. */
    static final class Grab {
        final int x1, y1, z1, x2, y2, z2;
        final int minSection;
        /** chunkKey -> sections (index from the world's lowest section; null = only air); absent = not loaded. */
        final Map<Long, PalettedContainer<BlockState>[]> chunks = new HashMap<>();

        private Grab(int x1, int y1, int z1, int x2, int y2, int z2, int minSection) {
            this.x1 = x1;
            this.y1 = y1;
            this.z1 = z1;
            this.x2 = x2;
            this.y2 = y2;
            this.z2 = z2;
            this.minSection = minSection;
        }

        @SuppressWarnings("unchecked")
        private static PalettedContainer<BlockState>[] copy(LevelChunk chunk) {
            LevelChunkSection[] secs = chunk.getSections();
            PalettedContainer<BlockState>[] out = new PalettedContainer[secs.length];
            for (int i = 0; i < secs.length; i++) {
                LevelChunkSection s = secs[i];
                out[i] = s == null || s.hasOnlyAir() ? null : s.getStates().copy();
            }
            return out;
        }

        static Grab chunk(ClientLevel level, LevelChunk chunk, int cx, int cz) {
            Grab g = new Grab(cx << 4, level.getMinBuildHeight(), cz << 4, (cx << 4) + 15, level.getMaxBuildHeight() - 1, (cz << 4) + 15, level.getMinSection());
            g.chunks.put(chunkKey(cx, cz), copy(chunk));
            return g;
        }

        static Grab box(ClientLevel level, int x1, int y1, int z1, int x2, int y2, int z2) {
            y1 = Math.max(y1, level.getMinBuildHeight());
            y2 = Math.min(y2, level.getMaxBuildHeight() - 1);
            Grab g = new Grab(x1, y1, z1, x2, Math.max(y1, y2), z2, level.getMinSection());
            for (int cx = x1 >> 4; cx <= x2 >> 4; cx++) {
                for (int cz = z1 >> 4; cz <= z2 >> 4; cz++) {
                    LevelChunk c = level.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, false);
                    if (c != null && !c.isEmpty()) g.chunks.put(chunkKey(cx, cz), copy(c));
                }
            }
            return g;
        }

        /** The id at x y z: null where not loaded. Background thread. */
        String id(FlightRecorder r, int x, int y, int z) {
            PalettedContainer<BlockState>[] secs = chunks.get(chunkKey(x >> 4, z >> 4));
            if (secs == null) return null;
            int si = (y >> 4) - minSection;
            if (si < 0 || si >= secs.length) return ChunkBase.AIR;
            PalettedContainer<BlockState> s = secs[si];
            return s == null ? ChunkBase.AIR : r.blockId(s.get(x & 15, y & 15, z & 15));
        }

        String[] ids(FlightRecorder r) {
            int dx = x2 - x1 + 1, dz = z2 - z1 + 1, dy = y2 - y1 + 1;
            String[] out = new String[dx * dy * dz];
            for (int y = y1; y <= y2; y++) for (int z = z1; z <= z2; z++) for (int x = x1; x <= x2; x++) {
                out[((y - y1) * dz + (z - z1)) * dx + (x - x1)] = id(r, x, y, z);
            }
            return out;
        }

        /** A one-chunk grab as a baseline. Background thread. */
        ChunkBase toBase(FlightRecorder r, String dim, int minY, long takenMs) {
            PalettedContainer<BlockState>[] secs = chunks.values().iterator().next();
            int n = secs.length;
            String[][] pal = new String[n][];
            short[][] idx = new short[n][];
            String[] cells = new String[4096];
            for (int s = 0; s < n; s++) {
                PalettedContainer<BlockState> c = secs[s];
                if (c == null) {
                    pal[s] = new String[]{ChunkBase.AIR};
                    continue;
                }
                for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                    cells[(y * 16 + z) * 16 + x] = r.blockId(c.get(x, y, z));
                }
                ChunkBase.section(cells, pal, idx, s);
            }
            return new ChunkBase(dim, x1 >> 4, z1 >> 4, minY, takenMs, pal, idx);
        }
    }

    // ---- incidents ----

    /** Takes an incident snapshot now (copies here, the file in the background). The bot's position, or null. */
    private int[] snapshot(String reason, String job, int[] target) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        ClientLevel level = mc.level;
        if (p == null || level == null) return null;
        long now = System.currentTimeMillis();
        String dim = Guard.dimOf(level);
        int half;
        synchronized (settings) { half = settings.snapshot; }
        int[] me = {p.getBlockX(), p.getBlockY(), p.getBlockZ()};
        LinkedHashMap<String, Grab> grabs = new LinkedHashMap<>();
        grabs.put("around the bot", Grab.box(level, me[0] - half, me[1] - half, me[2] - half, me[0] + half, me[1] + half, me[2] + half));
        if (target != null) {
            int d = Math.max(Math.abs(target[0] - me[0]), Math.max(Math.abs(target[1] - me[1]), Math.abs(target[2] - me[2])));
            if (d > half && d <= 64) grabs.put("around " + target[0] + " " + target[1] + " " + target[2],
                    Grab.box(level, target[0] - half, target[1] - half, target[2] - half, target[0] + half, target[1] + half, target[2] + half));
        }
        if (!submit(() -> writeIncident(now, reason, job, dim, me, target, half, grabs))) return null;
        return me;
    }

    private void writeIncident(long now, String reason, String job, String dim, int[] me, int[] target, int half, Map<String, Grab> grabs) {
        List<IncidentText.Box> boxes = new ArrayList<>();
        for (Map.Entry<String, Grab> e : grabs.entrySet()) {
            Grab g = e.getValue();
            boxes.add(new IncidentText.Box(e.getKey(), g.x1, g.y1, g.z1, g.x2, g.y2, g.z2, g.ids(this)));
        }
        long since = now - INCIDENT_LOOKBACK_MS;
        int r = half + 8;
        List<Change> cs = new ArrayList<>(store.changes(dim, me[0], me[1], me[2], r, since, 400));
        if (target != null && grabs.size() > 1) {
            for (Change c : store.changes(dim, target[0], target[1], target[2], r, since, 400)) if (!cs.contains(c)) cs.add(c);
            cs.sort(Comparator.comparingLong(Change::atMs).reversed());
        }
        String text = IncidentText.render(now, reason, job, dim, me, half, boxes, store.trail(since, 600), cs, zone);
        String file = store.writeIncident(now, reason, text);
        if (file != null) {
            store.maintain();
            refreshIncidents();
            LOG.info("[entropybot] recorder: incident {} ({})", file, cut(reason, 120));
        }
    }

    // ---- the verb ----

    @Override
    public String run(RecorderCommand.Cmd cmd) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        long now = System.currentTimeMillis();
        switch (cmd.kind()) {
            case SHOW -> { return summary(); }
            case MARK -> {
                if (p == null || mc.level == null) return "error: not in a world";
                note(p, lastDim, now, "mark: " + cmd.word());
                return "ok: marked \"" + cmd.word() + "\" at " + IncidentText.time(now, zone, "HH:mm:ss");
            }
            case SNAPSHOT_NOW -> {
                int[] at = snapshot("on demand (recorder snapshot now)", jobText(), null);
                if (at == null) return p == null || mc.level == null ? "error: not in a world" : "error: the recorder is busy writing, try again in a moment";
                int half;
                synchronized (settings) { half = settings.snapshot; }
                return "ok: snapshot of the " + (2 * half + 1) + "-block box around " + at[0] + " " + at[1] + " " + at[2] + " (debug incident shows it)";
            }
            default -> {
                String reply;
                synchronized (settings) {
                    reply = settings.apply(cmd, now);
                    store.keepHours(settings.keepHours);
                    applyLive();
                    if (mc.options != null) effRange = Math.max(1, Math.min(settings.range, mc.options.getEffectiveRenderDistance()));
                    if (reply != null && cmd.kind() == RecorderCommand.Kind.RANGE && settings.range > effRange)
                        reply += " (the render distance is " + effRange + ", so " + effRange + " for now)";
                }
                if (reply == null) return summary();
                saveSettings();
                if (p != null && mc.level != null) note(p, lastDim, now, "recorder: " + reply.replaceFirst("^ok: ", ""));
                return reply;
            }
        }
    }

    private void saveSettings() {
        String json;
        synchronized (settings) { json = settings.toJson().toString(); }
        submit(() -> store.writeSettings(json));
    }

    // ---- read side (any thread) ----

    @Override
    public boolean enabled() { return on; }

    @Override
    public String summary() {
        RecorderSettings s;
        synchronized (settings) { s = settings.copy(); }
        StringBuilder b = new StringBuilder("recorder: ").append(s.line());
        String changed = s.changedFromPreset();
        if (!changed.isEmpty()) b.append(" - changed from the preset: ").append(changed);
        if (s.boost != null) b.append("\nboost: ").append(s.boost.preset).append(" until ").append(RecorderSettings.hhmm(s.boost.untilMs))
                .append(", then ").append(s.boost.then == null ? "normal" : s.boost.then.preset);
        long disk = store.diskBytes();
        long[] mem = store.memCounts();
        b.append("\ndisk: ").append(disk < 0 ? "?" : mb(disk)).append(" of ").append(mb(store.cap()));
        int bases = store.baseCount();
        if (bases >= 0) b.append(", ").append(bases).append(" chunk baselines");
        b.append("; since the game started ").append(mem[0]).append(" changes, ").append(mem[1]).append(" trail points");
        if (!hooked) b.append(" (no block change heard yet: is the ClientLevel hook in?)");
        if (store.dropped() > 0) b.append("; ").append(store.dropped()).append(" dropped (queue full)");
        if (store.capped()) b.append("; over the disk cap: recording to memory only");
        if (store.lastError() != null) b.append("; last error: ").append(store.lastError());
        List<Incident> inc = store.incidents(3);
        if (inc.isEmpty()) b.append("\nno incidents");
        else {
            b.append("\nincidents (").append(inc.get(0).n()).append("):");
            for (Incident i : inc) b.append(" #").append(i.n()).append(' ').append(IncidentText.time(i.atMs(), zone, "MM-dd HH:mm")).append(' ').append(cut(i.reason(), 60)).append(';');
            b.setLength(b.length() - 1);
        }
        return b.toString();
    }

    static String mb(long bytes) {
        return String.format(java.util.Locale.ROOT, "%.1f MB", bytes / 1048576.0);
    }

    JsonObject statusJson() {
        RecorderSettings s;
        synchronized (settings) { s = settings.copy(); }
        return RecorderStatus.build(s, store.diskBytes(), incidentCount, lastIncident);
    }

    @Override
    public List<Change> changes(String dim, int x, int y, int z, int r, long sinceMs, int max) {
        return store.changes(dim, x, y, z, r, sinceMs, max);
    }

    @Override
    public List<TrailPoint> trail(long sinceMs, int max) { return store.trail(sinceMs, max); }

    @Override
    public List<Incident> incidents(int max) { return store.incidents(max); }

    @Override
    public String incident(int n) { return store.incident(n); }

    @Override
    public Slice blocksAt(String dim, int x1, int y1, int z1, int x2, int y2, int z2, long atMs) {
        return store.blocksAt(dim, x1, y1, z1, x2, y2, z2, atMs);
    }

    static String cut(String s, int n) {
        if (s == null) return "";
        s = s.replace('\n', ' ');
        return s.length() <= n ? s : s.substring(0, n - 3) + "...";
    }

}
