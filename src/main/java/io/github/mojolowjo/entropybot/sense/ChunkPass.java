package io.github.mojolowjo.entropybot.sense;

import com.mojang.logging.LogUtils;
import io.github.mojolowjo.entropybot.Core;
import io.github.mojolowjo.entropybot.commands.SelfCheck;
import io.github.mojolowjo.entropybot.guard.Guard;
import io.github.mojolowjo.entropybot.memory.Limits;
import io.github.mojolowjo.entropybot.poi.PoiScanner;
import io.github.mojolowjo.entropybot.poi.Pois;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 0.28.0 the one chunk pass (docs/CHUNKPASS_PLAN.md): one scheduler for everything the mod learns from chunks. A chunk is
 * read once when it arrives (first load or resend; {@link McChunkReader}, palette-gated), kept as a {@link ChunkSummary}
 * in the {@link OreIndex}, and patched per block from the mod's existing block hook. A full read happens again only on a
 * resend or when a patch had to be dropped ({@link PassSchedule}'s missed rule); once a minute one loaded chunk is read
 * again and compared with its patched summary (the self-audit). The readers: points of interest ({@link PoiScanner#found},
 * {@link Pois#gone}), the terrain map (whole chunks on a read, columns on a patch), the surface export (columns), and the
 * ore index the jobs ask ("mine", gather, the brain, the cave job, "ores known"). Summaries are written to
 * {@code entropybot/sense/<dim>/<cx>.<cz>.json} ({@link SummaryFiles}) and loaded back stale at the next start.
 *
 * <p>Loader notes (docs/PLANNING.md):
 * <ul>
 *   <li>chunk arrival and resend: NeoForge {@code ChunkEvent.Load} on {@code NeoForge.EVENT_BUS} (client side; posted by
 *       {@code ClientChunkCache.replaceWithPacketData} for a new chunk and for a resend into the same LevelChunk);
 *       Fabric {@code ClientChunkEvents.CHUNK_LOAD}. Chunk gone: {@code ChunkEvent.Unload} / {@code CHUNK_UNLOAD}.
 *       Registered in {@code EntropyBot}.</li>
 *   <li>per-block changes with the old state: the existing mixin {@code RecorderMixinClientLevel} (no new mixin) through
 *       its listener slot {@code FlightRecorder.chunkPassListener}, called before the recorder's own range and rate
 *       filter; mixins are loader-neutral (Fabric needs the intermediary remap).</li>
 *   <li>the tick: {@code Core.onClientTick} (NeoForge {@code ClientTickEvent.Post}; Fabric {@code END_CLIENT_TICK}).</li>
 *   <li>reads: vanilla only (see {@link McChunkReader}); files, the worker thread, scheduling, caps: plain Java.</li>
 * </ul>
 * Errors: every listener call and the tick are wrapped (counted; the first 5 logged in full, then one a minute, with the
 * {@code [entropybot]} prefix); a read that throws leaves the chunk unknown (no half summary; patches skip it) and due
 * again; a file that can't be written is logged once a minute and stays due. Game (client) thread, except the file I/O.
 */
public final class ChunkPass {
    private static final Logger LOG = LogUtils.getLogger();
    public static final ChunkPass INSTANCE = new ChunkPass();
    /** Chunks a tick may take from the schedule (the time budget usually stops sooner). */
    static final int PER_TICK = 8;
    static final long AUDIT_EVERY_MS = 60_000, LOG_EVERY_MS = 60_000;

    private final PassSchedule schedule = new PassSchedule();
    private final OreIndex index = new OreIndex();
    private final McChunkReader reader = new McChunkReader();
    private final SummaryFiles.Dirty dirty = new SummaryFiles.Dirty();
    /** Chunks read since they (last) arrived: only these are patched. */
    private final Set<Long> live = new HashSet<>();
    private final Set<String> filesLoaded = new HashSet<>();
    private final ConcurrentLinkedQueue<SummaryFiles.Loaded> fromFiles = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<String> failedWrites = new ConcurrentLinkedQueue<>();
    private ExecutorService worker;
    private String dim;
    private long startMs, lastAuditMs, auditCursor;

    // counters (sense status)
    private long heard, patches, beforeRead, dropUnsure, dropError, readsLoad, readsResend, readsMissed, readsRetry, readErrors;
    private long audits, disagreements, errors, lastErrLogMs, lastAuditLogMs, filesBroken, filesLoadedCount;
    private final ArrayDeque<Long> disagreeAt = new ArrayDeque<>();
    private String lastError, lastDisagreement, filesNote = "not loaded yet";
    private final AtomicLong written = new AtomicLong(), writeErrors = new AtomicLong();
    private volatile String lastWriteError;
    private volatile long lastWriteErrLogMs;
    // tick time: last, max, the last minute by second
    private long lastNs, maxNs, joinMaxNs, dimStartMs;
    private final long[] secSum = new long[60], secCnt = new long[60], secAt = new long[60];

    private ChunkPass() {}

    public OreIndex index() { return index; }

    /** Has the pass read at least one chunk in this dimension (the index can answer)? */
    public boolean ready() { return dim != null && readsLoad + readsResend > 0; }

    public String dim() { return dim; }

    // ---- events (game thread) -----------------------------------------------------------------------------------------

    public void chunkLoaded(LevelAccessor level, ChunkAccess chunk) {
        try {
            if (level != Minecraft.getInstance().level || !(chunk instanceof LevelChunk)) return;
            schedule.loaded(chunk.getPos().x, chunk.getPos().z, System.currentTimeMillis());
        } catch (Throwable t) {
            error("chunk load", t);
        }
    }

    public void chunkUnloaded(LevelAccessor level, ChunkAccess chunk) {
        try {
            if (level != Minecraft.getInstance().level) return;
            int cx = chunk.getPos().x, cz = chunk.getPos().z;
            schedule.unloaded(cx, cz);
            live.remove(ChunkSummary.key(cx, cz));
            ChunkSummary s = dim == null ? null : index.get(dim, cx, cz);
            if (s != null) s.stale = true;
        } catch (Throwable t) {
            error("chunk unload", t);
        }
    }

    /** FlightRecorder.chunkPassListener: every real client block change, before the recorder's filters. Never throws. */
    public void blockChanged(ClientLevel level, BlockPos pos, BlockState from, BlockState to) {
        if (level != Minecraft.getInstance().level) return;
        int x = pos.getX(), y = pos.getY(), z = pos.getZ();
        try {
            // the surface export's column reader: every change in a column (the ground under leaves too)
            io.github.mojolowjo.entropybot.surface.SurfaceExport.INSTANCE.blockChanged(x, z);
        } catch (Throwable t) {
            error("surface forward", t);
        }
        try {
            heard++;
            if (dim == null) return;
            int cx = x >> 4, cz = z >> 4;
            long k = ChunkSummary.key(cx, cz);
            ChunkSummary s = index.get(dim, cx, cz);
            if (s == null || !live.contains(k)) {
                beforeRead++;              // its read is pending (or it is not loaded): the read covers this change
                if (!schedule.isPending(cx, cz)) schedule.missed(cx, cz, System.currentTimeMillis());
                return;
            }
            SummaryBuilder.Cls cf = reader.cls(from), ct = reader.cls(to);
            boolean nearTop = ColumnPatch.nearTop(s.tops, x, y, z);
            if (cf.none() && ct.none() && !nearTop) return;           // nothing the summary holds: O(1) and done
            LevelChunk chunk = level.getChunkSource().getChunk(cx, cz, false);
            if (chunk == null) return;
            List<ChunkDiff.Change> out = new ArrayList<>(2);
            long now = System.currentTimeMillis();
            boolean ok = SummaryBuilder.patch(s, x, y, z, cf, ct, McChunkReader.top(chunk, x, z), out, now);
            patches++;
            if (!ok) {
                dropUnsure++;
                schedule.missed(cx, cz, now);
            }
            if (nearTop) mapColumn(x, z);
            if (!out.isEmpty()) {
                apply(s, out, false);
                dirty.mark(SummaryFiles.Dirty.key(dim, cx, cz), now);
            }
        } catch (Throwable t) {
            dropError++;
            error("block change", t);
            try { schedule.missed(x >> 4, z >> 4, System.currentTimeMillis()); } catch (Throwable ignored) {}
        }
    }

    // ---- tick ---------------------------------------------------------------------------------------------------------

    /** Once a client tick (from Core). Never throws. */
    public void tick(long tick) {
        long t0 = System.nanoTime();
        try {
            Minecraft mc = Minecraft.getInstance();
            ClientLevel level = mc.level;
            if (level == null || mc.player == null) return;
            long now = System.currentTimeMillis();
            if (startMs == 0) startMs = now;
            String d = Guard.dimOf(level);
            if (!d.equals(dim)) start(level, d, mc, now);
            for (SummaryFiles.Loaded l; (l = fromFiles.poll()) != null; ) {
                filesLoadedCount += index.loadStale(l.list());
                filesBroken += l.broken();
                if (l.firstError() != null) LOG.warn("[entropybot] sense: {} broken summary file(s) skipped, first: {}", l.broken(), l.firstError());
            }
            for (String k; (k = failedWrites.poll()) != null; ) dirty.failed(k, now);
            BlockPos p = mc.player.blockPosition();
            List<PassSchedule.Item> next = schedule.take(p.getX() >> 4, p.getZ() >> 4, now, PER_TICK);
            for (int i = 0; i < next.size(); i++) {
                if (i > 0 && System.nanoTime() - t0 > Limits.SENSE_BUDGET_NS) {
                    for (int j = i; j < next.size(); j++) schedule.requeue(next.get(j), now);
                    break;
                }
                readOne(level, next.get(i), now);
            }
            if (now - lastAuditMs >= AUDIT_EVERY_MS && System.nanoTime() - t0 < Limits.SENSE_BUDGET_NS / 2) {
                lastAuditMs = now;
                audit(level, now);
            }
            if (tick % 20 == 3) writeDue(now, false);
            if (tick % 1200 == 5) saveStats();
        } catch (Throwable t) {
            error("tick", t);
        } finally {
            timeTick(System.nanoTime() - t0);
        }
    }

    /** A new world or dimension: the loaded chunks round the bot are due; the dimension's files load once (stale). */
    private void start(ClientLevel level, String d, Minecraft mc, long now) {
        writeDue(now, true);
        schedule.clear();
        live.clear();
        index.markAllStale();
        dim = d;
        dimStartMs = now;
        int rd = mc.options.renderDistance().get() + 2;
        BlockPos p = mc.player.blockPosition();
        int ecx = p.getX() >> 4, ecz = p.getZ() >> 4;
        for (int cx = ecx - rd; cx <= ecx + rd; cx++)
            for (int cz = ecz - rd; cz <= ecz + rd; cz++)
                if (level.getChunkSource().getChunk(cx, cz, false) != null) schedule.loaded(cx, cz, now);
        if (filesLoaded.add(d)) {
            Path root = root();
            if (root == null) {
                filesLoaded.remove(d);
                return;
            }
            Path dir = root.resolve(SummaryFiles.FOLDER).resolve(SummaryFiles.dimFolder(d));
            int g = gen.get();
            worker().execute(() -> {
                try {
                    SummaryFiles.Loaded l = SummaryFiles.loadDir(dir);
                    if (g != gen.get()) return;                  // 0.31.0 (c): the world changed meanwhile
                    filesNote = l.list().size() + " summaries from " + dir.getFileName() + (l.broken() > 0 ? ", " + l.broken() + " broken skipped" : "");
                    fromFiles.add(l);
                } catch (Throwable t) {
                    filesNote = "couldn't load " + dir + ": " + t;
                    LOG.warn("[entropybot] sense: {}", filesNote);
                }
            });
        }
    }

    /**
     * 0.31.0 (c): Core, after re-rooting the files to another world's folder (leftWorld already wrote the dirty
     * summaries to the old folder): the index forgets every summary and each dimension's files load again from the
     * new folder. Never throws.
     */
    public void worldChanged() {
        try {
            saveStats();                                  // leftWorld may have run already (then it is clean); the old root is still set
            stats = null;
            statsRoot = null;
            gen.incrementAndGet();
            schedule.clear();
            live.clear();
            index.clear();
            dirty.clear();
            failedWrites.clear();
            fromFiles.clear();
            filesLoaded.clear();
            dim = null;
            LOG.info("[entropybot] sense: world changed: ore index cleared, summaries load from {}", root());
        } catch (Throwable t) {
            error("world change", t);
        }
    }

    private final java.util.concurrent.atomic.AtomicInteger gen = new java.util.concurrent.atomic.AtomicInteger();

    /** Core: the bot left the world. Everything goes stale; dirty summaries are written. Never throws. */
    public void leftWorld() {
        try {
            writeDue(System.currentTimeMillis(), true);
            saveStats();
            schedule.clear();
            live.clear();
            index.markAllStale();
            dim = null;
        } catch (Throwable t) {
            error("leave", t);
        }
    }

    // ---- ore stats (B5): where each ore family turns up, per world (entropybot/.../ore-stats.json) ----

    private OreStats stats;
    private String statsRoot;

    /** The learned ore heights of the current world (loaded from its file on first use); null before the files exist. Never throws. */
    public OreStats stats() {
        try {
            Path root = root();
            if (root == null) return stats;
            String r = root.toString();
            if (stats != null && r.equals(statsRoot)) return stats;
            if (stats != null) saveStats();               // a different world root: keep the old one's counts
            Path f = root.resolve("ore-stats.json");
            int[] skipped = new int[1];
            if (java.nio.file.Files.isRegularFile(f)) {
                stats = OreStats.fromJson(java.nio.file.Files.readString(f), skipped);
                if (skipped[0] > 0) LOG.warn("[entropybot] sense: ore-stats.json had {} unreadable part(s), skipped", skipped[0]);
            } else {
                stats = new OreStats();
            }
            statsRoot = r;
            return stats;
        } catch (Throwable t) {
            error("ore stats load", t);
            if (stats == null) stats = new OreStats();    // count in memory at least; the file is not overwritten unseen (see saveStats)
            return stats;
        }
    }

    private void saveStats() {
        try {
            OreStats st = stats;
            io.github.mojolowjo.entropybot.io.BotFiles files = Core.INSTANCE.files();
            if (st == null || files == null || statsRoot == null || !st.dirty()) return;
            if (!statsRoot.equals(files.worldRoot().toString())) return;
            String json = st.toJson();
            st.saved();
            Path target = files.worldRoot().resolve("ore-stats.json");
            worker().execute(() -> {
                try {
                    files.writeAtomic(target, json);
                } catch (Throwable t) {
                    LOG.warn("[entropybot] sense: couldn't write {}: {}", target.getFileName(), t.toString());
                }
            });
        } catch (Throwable t) {
            error("ore stats save", t);
        }
    }

    private void readOne(ClientLevel level, PassSchedule.Item it, long now) {
        LevelChunk chunk = level.getChunkSource().getChunk(it.cx(), it.cz(), false);
        if (chunk == null) return;                    // gone meanwhile
        long k = ChunkSummary.key(it.cx(), it.cz());
        ChunkSummary fresh;
        try {
            fresh = reader.read(dim, chunk, now);
        } catch (Throwable t) {
            readErrors++;
            live.remove(k);                           // unknown: no patches on the old summary
            ChunkSummary old = index.get(dim, it.cx(), it.cz());
            if (old != null) old.stale = true;
            schedule.failed(it, now);
            error("read " + it.cx() + " " + it.cz(), t);
            return;
        }
        switch (it.why()) {
            case LOAD -> readsLoad++;
            case RESEND -> readsResend++;
            case MISSED -> readsMissed++;
            case RETRY -> readsRetry++;
        }
        ChunkSummary old = index.get(dim, it.cx(), it.cz());
        List<ChunkDiff.Change> changes = ChunkDiff.between(old, fresh);
        index.put(fresh);
        live.add(k);
        try {
            OreStats st = stats();
            if (st != null) st.addChunk(fresh);
        } catch (Throwable t) {
            error("ore stats", t);
        }
        apply(fresh, changes, true);
        mapChunk(it.cx(), it.cz());
        if (old == null || old.stale || !changes.isEmpty()) dirty.mark(SummaryFiles.Dirty.key(dim, it.cx(), it.cz()), now);
    }

    /** Self-audit: one loaded, patched chunk read again (round robin) and compared; a difference is counted, logged, replaced. */
    private void audit(ClientLevel level, long now) {
        List<Long> keys = new ArrayList<>(live);
        if (keys.isEmpty()) return;
        keys.sort(Long::compare);
        long k = keys.get((int) (auditCursor++ % keys.size()));
        int cx = ChunkSummary.cx(k), cz = ChunkSummary.cz(k);
        LevelChunk chunk = level.getChunkSource().getChunk(cx, cz, false);
        ChunkSummary s = index.get(dim, cx, cz);
        if (chunk == null || s == null || schedule.isPending(cx, cz)) return;
        ChunkSummary fresh = reader.read(dim, chunk, now);
        audits++;
        List<String> diff = ChunkDiff.disagreements(s, fresh);
        if (diff.isEmpty()) return;
        disagreements++;
        disagreeAt.add(now);
        while (!disagreeAt.isEmpty() && now - disagreeAt.peekFirst() > 3_600_000) disagreeAt.pollFirst();
        lastDisagreement = "chunk " + cx + " " + cz + ": " + String.join(", ", diff);
        if (now - lastAuditLogMs >= LOG_EVERY_MS) {
            lastAuditLogMs = now;
            LOG.warn("[entropybot] sense: self-audit disagreement #{} at {} - summary replaced", disagreements, lastDisagreement);
        }
        List<ChunkDiff.Change> changes = ChunkDiff.between(s, fresh);
        index.put(fresh);
        apply(fresh, changes, false);
        dirty.mark(SummaryFiles.Dirty.key(dim, cx, cz), now);
    }

    // ---- readers ------------------------------------------------------------------------------------------------------

    /** POI adds and removals to pois.json; a full read also refreshes every POI it holds (one per kind per section). */
    private void apply(ChunkSummary s, List<ChunkDiff.Change> changes, boolean fullRead) {
        Core core = Core.INSTANCE;
        long tick = core.tick();
        try {
            if (fullRead) {
                for (Map.Entry<String, ChunkSummary.Group> e : ChunkDiff.withLakes(s).entrySet()) {
                    Set<Integer> secs = new HashSet<>();
                    for (ChunkSummary.Spot sp : e.getValue().spots)
                        if (secs.add(sp.y() >> 4)) PoiScanner.found(core.pois, core.events, e.getKey(), sp.x(), sp.y(), sp.z(), s.dim, tick);
                }
            }
            for (ChunkDiff.Change c : changes) {
                if (c.type() == ChunkDiff.Type.POI_ADD && !fullRead) PoiScanner.found(core.pois, core.events, c.key(), c.x(), c.y(), c.z(), s.dim, tick);
                else if (c.type() == ChunkDiff.Type.POI_REMOVE) {
                    Pois.Poi g = core.pois.gone(c.key(), c.x(), c.y(), c.z(), s.dim, tick,
                            p -> index.poiNear(s.dim, p.kind(), p.x(), p.y(), p.z(), Pois.MERGE));
                    if (g != null) {
                        String line = "the " + g.kind() + " at " + g.x() + " " + g.y() + " " + g.z() + " is gone (poi " + g.id() + ")";
                        core.events.push("poi", line, null);
                        LOG.info("[entropybot] {}", line);
                    }
                }
            }
        } catch (Throwable t) {
            error("poi reader", t);
        }
    }

    private void mapChunk(int cx, int cz) {
        try {
            io.github.mojolowjo.entropybot.map.TerrainMap m = Core.INSTANCE.terrain();
            if (m != null) m.chunkRead(cx, cz);
        } catch (Throwable t) {
            error("map reader", t);
        }
    }

    private void mapColumn(int x, int z) {
        try {
            io.github.mojolowjo.entropybot.map.TerrainMap m = Core.INSTANCE.terrain();
            if (m != null) m.columnChanged(x, z);
        } catch (Throwable t) {
            error("map reader", t);
        }
    }

    // ---- queries (game thread) ----------------------------------------------------------------------------------------

    /** The nearest n listed ore positions whose id idOk takes, within r of x y z, that allow takes. Empty when not ready. */
    public List<OreIndex.Hit> nearest(String d, java.util.function.Predicate<String> idOk, int x, int y, int z, int r, int n, OreIndex.Allow allow) {
        try {
            return index.nearest(d, idOk, x, y, z, r, n, allow);
        } catch (Throwable t) {
            error("query", t);
            return List.of();
        }
    }

    public int chunksSeen(String d, int x, int z, int r, OreIndex.Allow allow) {
        try {
            return index.chunksSeen(d, x, z, r, allow);
        } catch (Throwable t) {
            error("query", t);
            return 0;
        }
    }

    // ---- files ---------------------------------------------------------------------------------------------------------

    private static Path root() {
        io.github.mojolowjo.entropybot.io.BotFiles f = Core.INSTANCE.files();
        return f == null ? null : f.worldRoot();
    }

    private void writeDue(long now, boolean all) {
        io.github.mojolowjo.entropybot.io.BotFiles files = Core.INSTANCE.files();
        if (files == null) return;
        List<String> keys = all ? dirty.all() : dirty.due(now, 64);
        for (String k : keys) {
            String[] p = k.split("\\|");
            ChunkSummary s;
            try {
                s = index.get(p[0], Integer.parseInt(p[1]), Integer.parseInt(p[2]));
            } catch (RuntimeException e) {
                continue;
            }
            if (s == null) continue;                  // left memory: its file has the last written state
            String json = SummaryFiles.toJson(s);
            Path target = SummaryFiles.file(files.worldRoot(), s);
            dirty.written(k, now);
            worker().execute(() -> {
                try {
                    files.writeAtomic(target, json);
                    written.incrementAndGet();
                } catch (Throwable t) {
                    writeErrors.incrementAndGet();
                    lastWriteError = target.getFileName() + ": " + t;
                    failedWrites.add(k);
                    long ms = System.currentTimeMillis();
                    if (ms - lastWriteErrLogMs >= LOG_EVERY_MS) {
                        lastWriteErrLogMs = ms;
                        LOG.warn("[entropybot] sense: couldn't write {} ({} write errors so far; it stays due)", lastWriteError, writeErrors.get());
                    }
                }
            });
        }
    }

    private synchronized ExecutorService worker() {
        if (worker == null) worker = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "entropybot-sense");
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
        });
        return worker;
    }

    // ---- errors, time, status, check ----------------------------------------------------------------------------------

    private void error(String where, Throwable t) {
        errors++;
        lastError = where + ": " + t;
        long now = System.currentTimeMillis();
        if (errors <= 5) LOG.warn("[entropybot] sense ({}) error {}:", where, errors, t);
        else if (now - lastErrLogMs >= LOG_EVERY_MS) {
            lastErrLogMs = now;
            LOG.warn("[entropybot] sense ({}) error {} (one line a minute): {}", where, errors, t.toString());
        }
    }

    private void timeTick(long ns) {
        lastNs = ns;
        // the first 10 s after a join or a dimension change (the burst of first reads) is kept apart from the steady max
        if (dimStartMs > 0 && System.currentTimeMillis() - dimStartMs < 10_000) {
            if (ns > joinMaxNs) joinMaxNs = ns;
        } else if (ns > maxNs) maxNs = ns;
        long sec = System.currentTimeMillis() / 1000;
        int i = (int) (sec % 60);
        if (secAt[i] != sec) {
            secAt[i] = sec;
            secSum[i] = 0;
            secCnt[i] = 0;
        }
        secSum[i] += ns;
        secCnt[i]++;
    }

    private double avgMs() {
        long sec = System.currentTimeMillis() / 1000, sum = 0, cnt = 0;
        for (int i = 0; i < 60; i++) if (sec - secAt[i] < 60) {
            sum += secSum[i];
            cnt += secCnt[i];
        }
        return cnt == 0 ? 0 : sum / 1e6 / cnt;
    }

    private int disagreementsLastHour() {
        long now = System.currentTimeMillis();
        int n = 0;
        for (long t : disagreeAt) if (now - t <= 3_600_000) n++;
        return n;
    }

    /** "sense" | "sense status". Never throws. */
    public String command(String rest) {
        try {
            String r = rest == null ? "" : rest.trim().toLowerCase(Locale.ROOT);
            if (r.isEmpty() || r.equals("status")) return status();
            return "usage: sense status";
        } catch (Throwable t) {
            return "error: sense: " + t;
        }
    }

    public String status() {
        StringBuilder sb = new StringBuilder("sense: ");
        boolean hook = io.github.mojolowjo.entropybot.recorder.FlightRecorder.chunkPassListener != null;
        sb.append("hook ").append(!hook ? "NOT SET" : heard > 0 ? "in (" + heard + " block changes heard)" : "set, nothing heard yet");
        sb.append(" | dim ").append(dim == null ? "(not in a world)" : dim);
        sb.append(" | reads: ").append(readsLoad).append(" load, ").append(readsResend).append(" resend, ").append(readsMissed).append(" missed-rescan, ")
                .append(readsRetry).append(" retry").append(readErrors > 0 ? ", " + readErrors + " failed" : "");
        sb.append(" | ").append(schedule.loadedCount()).append(" chunks loaded, ").append(live.size()).append(" read, ").append(schedule.pending()).append(" pending");
        sb.append(" | patches ").append(patches).append(" applied, dropped: ").append(dropUnsure).append(" unsure (rescanned), ")
                .append(dropError).append(" errors; ").append(beforeRead).append(" before the chunk's read (the read covers them)");
        sb.append(" | missed marks ").append(schedule.missedMarks).append(" (+").append(schedule.missedDeduped).append(" deduped)");
        sb.append(String.format(Locale.ROOT, " | tick %.3f ms last, %.3f max, %.3f avg (1 min); max (join) %.1f ms (the first 10 s: first reads, not a steady cost)",
                lastNs / 1e6, maxNs / 1e6, avgMs(), joinMaxNs / 1e6));
        int[] fp = index.familiesAndPositions();
        sb.append(" | summaries ").append(index.size()).append(" in memory (").append(index.staleCount()).append(" stale, ").append(index.evicted).append(" left memory at the cap ")
                .append(Limits.SENSE_SUMMARIES).append(")");
        // an estimate (~56 bytes a listed position, ~1.5 KB a summary): an ore-rich pack lists ~550 positions a chunk
        sb.append(" | ore families ").append(fp[0]).append(", positions ").append(fp[1]).append(String.format(Locale.ROOT, " (~%.0f MB in memory)",
                (fp[1] * 56.0 + index.size() * 1500.0) / 1e6));
        sb.append(" | files: ").append(written.get()).append(" written, ").append(dirty.pending()).append(" waiting, ").append(filesLoadedCount).append(" loaded (")
                .append(filesNote).append(")").append(filesBroken > 0 ? ", " + filesBroken + " broken skipped" : "");
        if (lastWriteError != null) sb.append(", last write error ").append(lastWriteError).append(" (").append(writeErrors.get()).append(")");
        sb.append(" | self-audit ").append(audits).append(" chunks, ").append(disagreements).append(" disagreements");
        if (lastDisagreement != null) sb.append(" (last: ").append(lastDisagreement).append(")");
        if (lastError != null) sb.append(" | errors ").append(errors).append(", last ").append(lastError);
        return sb.toString();
    }

    public List<SelfCheck.Finding> findings() {
        List<SelfCheck.Finding> out = new ArrayList<>();
        try {
            long now = System.currentTimeMillis();
            int dh = disagreementsLastHour();
            if (dh > 0) out.add(new SelfCheck.Finding("sense", "the chunk pass's self-audit found " + dh + " patched chunk(s) that disagreed with a fresh read in the last hour"
                    + (lastDisagreement != null ? " (last: " + lastDisagreement + ")" : ""), "sense status; the log has [entropybot] sense lines (the summaries were replaced)"));
            if (dim != null && startMs > 0 && now - startMs > 600_000 && heard == 0)
                out.add(new SelfCheck.Finding("sense", "the chunk pass heard no block change in 10 minutes in a world: the block hook may not be in",
                        "sense status; the log's ready line lists the mixins (restorehook); a restart reapplies them"));
            if (errors > 0 && lastError != null && readsLoad + readsResend == 0 && dim != null)
                out.add(new SelfCheck.Finding("sense", "the chunk pass has read no chunk and has errors: " + lastError, "sense status; the log has [entropybot] sense lines"));
        } catch (Throwable t) {
            error("check", t);
        }
        return out;
    }

    /** Test seam for status texts: per-family totals near a spot (game thread). */
    public Map<String, Integer> totals(String d, int x, int z, int r) {
        try {
            return index.totals(d, x, z, r);
        } catch (Throwable t) {
            error("query", t);
            return new HashMap<>();
        }
    }
}
