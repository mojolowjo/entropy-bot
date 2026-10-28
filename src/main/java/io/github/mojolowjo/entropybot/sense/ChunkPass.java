package io.github.mojolowjo.entropybot.sense;

import io.github.mojolowjo.entropybot.Quiet;

import com.mojang.logging.LogUtils;
import io.github.mojolowjo.entropybot.Core;
import io.github.mojolowjo.entropybot.commands.SelfCheck;
import io.github.mojolowjo.entropybot.guard.Guard;
import io.github.mojolowjo.entropybot.memory.Limits;
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
 * again and compared with its patched summary (the self-audit). The readers: points of interest (on demand only,
 * {@link #refreshPois}), the terrain map (whole chunks on a read, columns on a patch), the surface export (columns), and the
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
    static final int PER_TICK = 8; // structural
    static final long AUDIT_EVERY_MS = 60_000; // structural
    static final long LOG_EVERY_MS = 60_000; // structural

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
    private long startMs, lastAuditMs, auditCursor, nearCursor;

    // counters (sense status)
    private long heard, patches, beforeRead, dropUnsure, dropError, readsLoad, readsResend, readsMissed, readsRetry, readErrors;
    private long nearAudits, nearFixes, oreHeard;
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

    private final SeenRuntime seenRt = new SeenRuntime();

    private final SightingsRuntime sightRt = new SightingsRuntime();

    /** 0.39.z78 J6: the cave index of this dimension (the chunks read this session) and the rays at it. */
    private final CaveIndex caveIndex = new CaveIndex();
    { caveIndex.measureEvery(1_000_000_000L); }       // structural: the openings measured again at most once a second while chunks load (run EF)
    private final CaveRays caveRays = new CaveRays();

    public CaveIndex caveIndex() { return caveIndex; }

    /** 0.39.z78 J6: the cave rays' counts (sense status, aa cave list). */
    public String caveRaysStatus() { return caveRays.status() + ", " + caveIndex.chunks() + " chunks indexed"; }

    /** AA_ROUND3 3/5: the seen ore cells of this world (Sightings kind ORE, sightings.json); null before the world files are known. */
    public Seen seen() {
        Sightings s = sightings();
        return s == null ? null : s.ore();
    }

    /** sense status: the seen-ore line. */
    public String seenStatus() {
        Seen s = seen();
        return "seen ore: " + (s == null || dim == null ? "no world" : s.size(dim) + " cells here") + ", " + seenRt.status() + ", fair " + (Seen.fair() ? "on" : "off");
    }

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
            caveIndex.remove(cx, cz);                     // 0.39.z78 J6: the index holds the loaded chunks only (its unknown volumes go; known caves stay in Sightings)
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
            SummaryBuilder.Cls cf = reader.cls(from), ct = reader.cls(to);
            if (ct.family() != null) oreHeard++;       // an ore block appeared (sense status counts them: the hook is alive)
            if (cf.family() != null && ct.family() == null) {           // AA_ROUND3 3: a mined ore leaves the seen cells
                Seen sn = seen();
                if (sn != null) sn.mined(dim, x, y, z);
            }
            // 0.39.z77 seen: with fair play off an ore is a source once a face is open - a block opened next to indexed ore cells
            // is their sighting for the seen trigger (fair on: the ray's Seen.saw says it, SeenRuntime)
            if (!Seen.fair() && open(to) && !open(from)) exposedNext(x, y, z);
            if (s == null || !live.contains(k)) {
                beforeRead++;              // its read is pending (or it is not loaded): the read covers this change
                if (!schedule.isPending(cx, cz)) schedule.missed(cx, cz, System.currentTimeMillis());
                return;
            }
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
                dirty.mark(SummaryFiles.Dirty.key(dim, cx, cz), now);
            }
        } catch (Throwable t) {
            dropError++;
            error("block change", t);
            try { schedule.missed(x >> 4, z >> 4, System.currentTimeMillis()); } catch (Throwable caught) { Quiet.warn("ChunkPass.blockChanged failed, carrying on with the fallback", caught); }
        }
    }

    /** 0.39.z77 seen: a cell a player sees ore through (air or water; as SeenRuntime's open face). */
    private static boolean open(BlockState s) { return s != null && (s.isAir() || s.getFluidState().is(net.minecraft.tags.FluidTags.WATER)); }

    /** 0.39.z77 seen: the indexed ore cells touching x y z by a face, queued as new sources (Seen.exposed). */
    private void exposedNext(int x, int y, int z) {
        Seen sn = seen();
        if (sn == null || dim == null) return;
        long now = System.currentTimeMillis();
        for (OreIndex.Hit h : index.nearest(dim, id -> true, x, y, z, 1, Seen.FACES.length, (a, b, c) -> Math.abs(a - x) + Math.abs(b - y) + Math.abs(c - z) == 1))
            sn.exposed(dim, h.x(), h.y(), h.z(), h.family(), now);
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
            if (tick % NearAudit.EVERY_TICKS == 5 && System.nanoTime() - t0 < Limits.SENSE_BUDGET_NS / 2) nearAudit(level, p, now);
            if (tick % 20 == 3) writeDue(now, false);
            if (tick % 1200 == 5) saveStats();
            try {
                Sightings sg = sightings();
                if (sg != null && dim != null) seenRt.tick(level, mc.player, index, sg.ore(), dim, tick, now);
                if (sg != null && dim != null) sightRt.tick(level, mc.player, sg, dim, seenRt.budget, tick, now);
                if (sg != null && dim != null) { labels(); if (tick % 20 == 0) remeasureLabels(); if (tick % 100 == 0) refreshCaveRecords(sg); caveRays.tick(level, mc.player, caveIndex, sg, dim, seenRt.budget, tick, now); }   // structural: a second in ticks   // 0.39.z78 J6 (the labels' floor first)
            } catch (Throwable t) {
                error("seen", t);
            }
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
        caveIndex.clear();                                // 0.39.z78 J6: the index is this dimension's
        caveRays.clear();
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
            sightings = null;
            sightRoot = null;
            gen.incrementAndGet();
            schedule.clear();
            live.clear();
            index.clear();
            dirty.clear();
            failedWrites.clear();
            fromFiles.clear();
            filesLoaded.clear();
            caveIndex.clear();                            // review c921e903: the cave index and its kept credits are the old world's (start() clears it again)
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

    // ---- 0.39.z78 J6: the owner's cave labels (cave-labels.json, the global one and the world's) and the floor from them ----

    private CaveLabels labels = new CaveLabels(), worldLabels = new CaveLabels();
    private String labelRoot;
    private CaveLabels.Floor floor = CaveLabels.derive(List.of());

    /** The floor in force (aa cave check / aa cave yes|no). */
    public CaveLabels.Floor floor() { labels(); return floor; }

    /**
     * The global labels (every world's: the floor reads them); loaded once per world, the floor derived and set on the
     * cave index, its line said. A missing file is said once a world (rule 15: empty by absence). Never throws.
     */
    public CaveLabels labels() {
        try {
            io.github.mojolowjo.entropybot.io.BotFiles files = Core.INSTANCE.files();
            if (files == null) return labels;
            String r = files.worldRoot().toString();
            if (r.equals(labelRoot)) return labels;
            int[] skipped = new int[1];
            Path gf = files.root().resolve("cave-labels.json"), wf = files.worldRoot().resolve("cave-labels.json");
            CaveLabels g = java.nio.file.Files.isRegularFile(gf) ? CaveLabels.read(java.nio.file.Files.readString(gf), skipped) : new CaveLabels();
            CaveLabels w = java.nio.file.Files.isRegularFile(wf) ? CaveLabels.read(java.nio.file.Files.readString(wf), skipped) : new CaveLabels();
            if (skipped[0] > 0) LOG.warn("[entropybot] sense: cave-labels.json had {} unreadable part(s), skipped", skipped[0]);
            if (!java.nio.file.Files.isRegularFile(gf)) LOG.info("[entropybot] sense: no cave labels yet ({} not found): the default floor until the owner labels (aa cave yes|no)", gf);
            labels = g;
            worldLabels = w;
            labelRoot = r;
            refloor();
        } catch (Throwable t) {
            error("cave labels load", t);
            labelRoot = null;
        }
        return labels;
    }

    private void refloor() {
        floor = CaveLabels.derive(labels.all());
        int[] was = caveIndex.floor();
        caveIndex.setFloor(floor.rim(), floor.reach());
        if (was[0] != floor.rim() || was[1] != floor.reach() || labelRoot != null) LOG.info("[entropybot] sense: {}", floor.line());
    }

    private long recordsVersion = -1;

    /**
     * 0.39.z78 J6b (D12): the known caves' records take their volume's cells from the cave index when it changed (chunks
     * loaded or left; every 5 s at most), so the cave way prices the unseen cells before the first visit. Never throws.
     */
    private void refreshCaveRecords(Sightings sg) {
        try {
            long v = caveIndex.version();
            if (v == recordsVersion || dim == null) return;
            recordsVersion = v;
            List<Sightings.KnownCave> known = sg.allKnownCaves(dim);
            List<int[]> at = new ArrayList<>();
            for (Sightings.KnownCave k : known) at.add(new int[]{k.x(), k.y(), k.z()});
            List<List<Long>> cells = caveIndex.volumesCells(at);
            for (int i = 0; i < known.size(); i++)
                if (!cells.get(i).isEmpty()) sg.caveCells(dim, known.get(i).x(), known.get(i).y(), known.get(i).z(), cells.get(i));
        } catch (Throwable t) {
            error("cave records refresh", t);
        }
    }

    private int labelsRemeasured;

    /** The old-measure labels promoted this session (aa cave check). */
    public int labelsRemeasured() { return labelsRemeasured; }

    /**
     * The owner's ruling: his old-measure labels are never relabelled by hand - each one of this world whose volume is in
     * the index is measured again per opening and promoted (CaveLabels.remeasure; his verdict kept), the files written and
     * the floor derived again; one whose volume is not loaded stays old until it is. Said per label. Never throws.
     */
    private void remeasureLabels() {
        try {
            io.github.mojolowjo.entropybot.io.BotFiles files = Core.INSTANCE.files();
            if (files == null || dim == null) return;
            String world = files.worldRoot().getFileName() + " " + dim;
            boolean any = false;
            for (CaveLabels.Label l : labels().all()) {
                if (!l.old() || !world.equals(l.world())) continue;
                CaveLabels.Label now = CaveLabels.remeasure(l, caveIndex);
                if (now == null) continue;
                labels.replace(l, now);
                for (CaveLabels.Label w : worldLabels.all()) if (w.equals(l)) worldLabels.replace(w, now);
                labelsRemeasured++;
                any = true;
                LOG.info("[entropybot] sense: cave label at {} re-measured per opening: rim {} -> {}, reach {} -> {} (verdict {} kept){}", l.where(), l.rim(), now.rim(),
                        l.reach(), now.reach(), l.cave() ? "yes" : "no", now.where().equals(l.where()) ? "" : "; its entry now " + now.where());
            }
            if (any) { refloor(); saveLabels(); }
        } catch (Throwable t) {
            error("cave labels re-measure", t);
        }
    }

    private void saveLabels() {
        io.github.mojolowjo.entropybot.io.BotFiles files = Core.INSTANCE.files();
        if (files == null) return;
        String gj = labels.toJson(), wj = worldLabels.toJson();
        labels.saved();
        worldLabels.saved();
        Path gf = files.root().resolve("cave-labels.json"), wf = files.worldRoot().resolve("cave-labels.json");
        worker().execute(() -> {
            try {
                files.writeAtomic(gf, gj);
                files.writeAtomic(wf, wj);
            } catch (Throwable t) {
                LOG.warn("[entropybot] sense: couldn't write cave-labels.json: {}", t.toString());
            }
        });
    }

    /** A label (aa cave yes|no): into the global and the world's file, the floor derived again. Never throws. */
    public void label(CaveLabels.Label l) {
        try {
            labels().add(l);
            worldLabels.add(l);
            refloor();
            io.github.mojolowjo.entropybot.io.BotFiles files = Core.INSTANCE.files();
            if (files == null) return;
            String gj = labels.toJson(), wj = worldLabels.toJson();
            labels.saved();
            worldLabels.saved();
            Path gf = files.root().resolve("cave-labels.json"), wf = files.worldRoot().resolve("cave-labels.json");
            worker().execute(() -> {
                try {
                    files.writeAtomic(gf, gj);
                    files.writeAtomic(wf, wj);
                } catch (Throwable t) {
                    LOG.warn("[entropybot] sense: couldn't write cave-labels.json: {}", t.toString());
                }
            });
        } catch (Throwable t) {
            error("cave label", t);
        }
    }

    // ---- sightings (AA_ROUND3 5): ore cells, caves and animals the bot has seen, per world (sightings.json) ----

    private Sightings sightings;
    private String sightRoot;

    /** The current world's sightings (loaded on first use; an older ore-stats.json "seen" part is carried over once). Never throws. */
    public Sightings sightings() {
        try {
            Path root = root();
            if (root == null) return sightings;
            String r = root.toString();
            if (sightings != null && r.equals(sightRoot)) return sightings;
            if (sightings != null) saveSightings();
            Path f = root.resolve("sightings.json");
            int[] skipped = new int[1];
            if (java.nio.file.Files.isRegularFile(f)) {
                sightings = Sightings.fromJson(java.nio.file.Files.readString(f), skipped);
                if (skipped[0] > 0) LOG.warn("[entropybot] sense: sightings.json had {} unreadable part(s), skipped", skipped[0]);
            } else {
                OreStats st = stats();
                Seen old = st == null ? null : st.legacySeen();
                sightings = new Sightings(old);
                if (old != null) LOG.info("[entropybot] sense: sightings.json: the seen ore cells carried over from ore-stats.json");
            }
            sightRoot = r;
            return sightings;
        } catch (Throwable t) {
            error("sightings load", t);
            if (sightings == null) sightings = new Sightings();
            return sightings;
        }
    }

    /** aa fair / aa sightings: the sightings line. */
    public String sightingsText() {
        Sightings s = sightings();
        var mc = Minecraft.getInstance();
        return s == null || dim == null ? "sightings: no world" : s.text(dim, mc.level == null ? 0 : mc.level.getGameTime() * 50) + " | " + sightRt.status();
    }

    private void saveSightings() {
        try {
            Sightings sg = sightings;
            io.github.mojolowjo.entropybot.io.BotFiles files = Core.INSTANCE.files();
            if (sg == null || files == null || sightRoot == null || !sg.dirty()) return;
            if (!sightRoot.equals(files.worldRoot().toString())) return;
            String json = sg.snapshot();
            Path target = files.worldRoot().resolve("sightings.json");
            worker().execute(() -> {
                try {
                    files.writeAtomic(target, json);
                } catch (Throwable t) {
                    LOG.warn("[entropybot] sense: couldn't write {}: {}", target.getFileName(), t.toString());
                }
            });
        } catch (Throwable t) {
            error("sightings save", t);
        }
    }

    private void saveStats() {
        saveSightings();
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
        try {
            caveRuns(chunk, it.cx(), it.cz());
        } catch (Throwable t) {
            error("cave index", t);
        }
        mapChunk(it.cx(), it.cz());
        if (old == null || old.stale || !changes.isEmpty()) dirty.mark(SummaryFiles.Dirty.key(dim, it.cx(), it.cz()), now);
    }

    /**
     * 0.39.z78 J6: the chunk's air runs below its WORLD_SURFACE heights into the cave index (once while the chunk stays loaded: a
     * chunk read again is kept as it was), then the known caves of Sightings that lie in it mark their volumes known (a
     * reload keeps them known). The reads go by section (an all-air section answers at once). Bounded by the pass's time
     * budget per tick, as the chunk reads. Run ED: the runs lie under the ground (CaveIndex.ground: down from WORLD_SURFACE
     * past air and leaves). Loader notes: vanilla LevelChunk#getSections / #getMinSection, LevelChunkSection#hasOnlyAir /
     * #getBlockState, BlockState#isAir / #is(BlockTags.LEAVES) (modded leaves join the vanilla tag); the heights from
     * SurfaceHeightLive.worldSurface.
     */
    private void caveRuns(LevelChunk chunk, int cx, int cz) {
        if (caveIndex.has(cx, cz)) return;
        var secs = chunk.getSections();
        int minSec = chunk.getMinSection();
        int[] surface = new int[256];
        // run ED: the ground under the crowns (leaves are no cave roof: CaveIndex.ground)
        CaveIndex.CellTest open = (x, y, z) -> {
            int si = (y >> 4) - minSec;
            if (si < 0 || si >= secs.length) return true;
            var s = secs[si];
            if (s.hasOnlyAir()) return true;
            var st = s.getBlockState(x & 15, y & 15, z & 15);
            return st.isAir() || st.is(net.minecraft.tags.BlockTags.LEAVES);
        };
        for (int lz = 0; lz < 16; lz++)
            for (int lx = 0; lx < 16; lx++)
                surface[lx + 16 * lz] = CaveIndex.ground((cx << 4) + lx, (cz << 4) + lz, io.github.mojolowjo.entropybot.world.SurfaceHeightLive.worldSurface(chunk, lx, lz), chunk.getMinBuildHeight(), open);
        caveIndex.put(CaveIndex.runs(cx, cz, surface, chunk.getMinBuildHeight(), (x, y, z) -> {
            int si = (y >> 4) - minSec;
            if (si < 0 || si >= secs.length) return false;
            var s = secs[si];
            return s.hasOnlyAir() || s.getBlockState(x & 15, y & 15, z & 15).isAir();
        }));
        Sightings sg = sightings();
        if (sg == null || dim == null) return;
        for (Sightings.KnownCave k : sg.allKnownCaves(dim)) {
            if (k.x() >> 4 == cx && k.z() >> 4 == cz) caveIndex.markKnown(k.x(), k.y(), k.z());
            for (long h : k.hits()) {
                int[] c = Sightings.cellOf(h);
                if (c[0] >> 4 == cx && c[2] >> 4 == cz) caveIndex.markKnown(c[0], c[1], c[2]);
            }
        }
    }

    /** Self-audit: one loaded, patched chunk read again (round robin) and compared; a difference is counted, logged, replaced. */
    private void audit(ClientLevel level, long now) {
        List<Long> keys = new ArrayList<>(live);
        if (keys.isEmpty()) return;
        keys.sort(Long::compare);
        long k = keys.get((int) (auditCursor++ % keys.size()));
        auditChunk(level, ChunkSummary.cx(k), ChunkSummary.cz(k), now, false);
    }

    /**
     * 0.36.a9 (to-do vanilla 1b): the 3 x 3 chunks round the bot are read again in turn, one every
     * {@link NearAudit#EVERY_TICKS} ticks, and compared with their patched summaries: a new or uncovered ore the patch path
     * missed reaches the ore index within seconds (the minute-long round over every loaded chunk took far longer). A
     * disagreement here means a patch did not take; it is counted and logged ("sense status": near fixes).
     */
    private void nearAudit(ClientLevel level, BlockPos p, long now) {
        int[] c = NearAudit.pick(p.getX() >> 4, p.getZ() >> 4, nearCursor++);
        auditChunk(level, c[0], c[1], now, true);
    }

    private void auditChunk(ClientLevel level, int cx, int cz, long now, boolean near) {
        LevelChunk chunk = level.getChunkSource().getChunk(cx, cz, false);
        ChunkSummary s = index.get(dim, cx, cz);
        if (chunk == null || s == null || !live.contains(ChunkSummary.key(cx, cz)) || schedule.isPending(cx, cz)) return;
        ChunkSummary fresh = reader.read(dim, chunk, now);
        if (near) nearAudits++;
        else audits++;
        List<String> diff = ChunkDiff.disagreements(s, fresh);
        if (diff.isEmpty()) return;
        if (near) nearFixes++;
        else disagreements++;
        disagreeAt.add(now);
        while (!disagreeAt.isEmpty() && now - disagreeAt.peekFirst() > 3_600_000) disagreeAt.pollFirst();
        lastDisagreement = (near ? "near " : "") + "chunk " + cx + " " + cz + ": " + String.join(", ", diff);
        if (now - lastAuditLogMs >= LOG_EVERY_MS) {
            lastAuditLogMs = now;
            LOG.warn("[entropybot] sense: {} disagreement #{} at {} - summary replaced",
                    near ? "near-bot check" : "self-audit", near ? nearFixes : disagreements, lastDisagreement);
        }
        List<ChunkDiff.Change> changes = ChunkDiff.between(s, fresh);
        index.put(fresh);
        dirty.mark(SummaryFiles.Dirty.key(dim, cx, cz), now);
    }

    // ---- readers ------------------------------------------------------------------------------------------------------

    /**
     * rm-mining (sense noise: 309 "found a ..." lines in 15 min, one per POI block as chunks arrived): the pass no longer
     * writes points of interest on every chunk read. pois.json is brought up to date on demand, when a reader asks (the
     * "poi" verb, "go poi"): the index's POI spots within r of x z are noted (one per kind per section, merged as before)
     * and the noted points of this dimension within r whose kind the index no longer has near them are dropped. One
     * summary line. Client thread.
     */
    public String refreshPois(Pois pois, String d, int x, int z, int r) {
        Core core = Core.INSTANCE;
        long tick = core.tick(), nowMs = System.currentTimeMillis();
        int added = 0, gone = 0;
        try {
            for (ChunkSummary s : index.all(d)) {
                if (Math.abs((s.cx << 4) + 8 - x) > r + 8 || Math.abs((s.cz << 4) + 8 - z) > r + 8) continue;
                for (Map.Entry<String, ChunkSummary.Group> e : ChunkDiff.withLakes(s).entrySet()) {
                    Set<Integer> secs = new HashSet<>();
                    for (ChunkSummary.Spot sp : e.getValue().spots)
                        if (secs.add(sp.y() >> 4) && pois.saw(e.getKey(), sp.x(), sp.y(), sp.z(), d, nowMs, tick, null) != null) added++;
                }
            }
            for (com.google.gson.JsonElement el : pois.toJson().getAsJsonArray("pois")) {
                com.google.gson.JsonObject p = el.getAsJsonObject();
                if (!d.equals(p.get("dim").getAsString())) continue;
                int px = p.get("x").getAsInt(), py = p.get("y").getAsInt(), pz = p.get("z").getAsInt();
                if (Math.abs(px - x) > r || Math.abs(pz - z) > r || index.get(d, px >> 4, pz >> 4) == null) continue;     // unknown chunk: kept
                String kind = p.get("kind").getAsString();
                if (!index.poiNear(d, kind, px, py, pz, Pois.MERGE) && pois.gone(kind, px, py, pz, d, tick, null) != null) gone++;
            }
        } catch (RuntimeException t) {
            error("poi refresh", t);
            return "the points of interest could not be refreshed: " + t;
        }
        String line = "points of interest within " + r + ": " + added + " new, " + gone + " gone (from the chunk index)";
        if (added + gone > 0) LOG.info("[entropybot] {}", line);
        return line;
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
            Quiet.warn("ChunkPass.writeDue failed, carrying on with the fallback", e);
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
        sb.append(" | ").append(seenStatus());
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
        sb.append(" | ore blocks heard appear ").append(oreHeard);
        sb.append(" | near-bot check ").append(nearAudits).append(" reads, ").append(nearFixes).append(" fixes (a fix = a patch that did not take)");
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
