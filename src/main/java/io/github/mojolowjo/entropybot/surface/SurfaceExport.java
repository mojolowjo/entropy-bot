package io.github.mojolowjo.entropybot.surface;

import io.github.mojolowjo.entropybot.commands.SelfCheck;
import io.github.mojolowjo.entropybot.guard.Guard;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 0.19.3: the surface export for the dashboard's RTS view. One JSON file per LOADED client chunk in
 * {@code entropybot\surface\<dim folder>\<cx>.<cz>.json} (format: {@link SurfaceColumns}; families: {@link SurfaceFamily}),
 * written on load, rewritten after block changes (at most every 2 s per chunk, {@link SurfaceSchedule}), deleted on
 * unload; the dimension's folder is wiped when the bot joins or leaves a world (or changes dimension). On whenever the
 * bot is in a world; {@code surface off|on} is kept in {@code entropybot\surface.json} (its own small file, the
 * {@code WatchSettings}/watch.json pattern: the camera's save rewrites watch.json whole, so a foreign key would be lost).
 *
 * <p>Threads: the columns are scanned on the client (game) thread in {@link #tick}, at most {@link SurfaceSchedule#PER_TICK}
 * chunks and about {@link #BUDGET_NS} per tick, never on the render path; the JSON is built and written (temp file +
 * atomic move), deleted and wiped on one worker thread, in order.
 *
 * <p>Loader notes (docs/PLANNING.md):
 * <ul>
 *   <li>chunk load/unload: NeoForge {@code net.neoforged.neoforge.event.level.ChunkEvent.Load/Unload} on
 *       {@code NeoForge.EVENT_BUS} (client side, registered in {@code EntropyBot}); Fabric:
 *       {@code ClientChunkEvents.CHUNK_LOAD / CHUNK_UNLOAD} (fabric-lifecycle-events-v1).</li>
 *   <li>the tick: NeoForge {@code ClientTickEvent.Post} (through {@code Core.onClientTick}); Fabric:
 *       {@code ClientTickEvents.END_CLIENT_TICK}.</li>
 *   <li>block changes: the mod's own ClientLevel mixin ({@code RecorderMixinClientLevel} -> {@code FlightRecorder.watchListener});
 *       mixins are loader-neutral (Fabric has no client block-change event either).</li>
 *   <li>block reads, tags, heightmaps: vanilla only ({@code ClientLevel.getBlockState}, {@code BlockState.is(TagKey)},
 *       {@code Heightmap.Types.WORLD_SURFACE}).</li>
 * </ul>
 * Errors: a column that throws is -1 (logged at most once a minute); a write failure is logged once (until a write
 * works again) and the chunk is written again on its next change; nothing here ever throws out of a tick or event.
 */
public final class SurfaceExport {
    public static final SurfaceExport INSTANCE = new SurfaceExport();
    public static final String SETTINGS_FILE = "surface.json";
    public static final long BUDGET_NS = 1_000_000;

    private static final org.slf4j.Logger LOG = com.mojang.logging.LogUtils.getLogger();
    private static final Map<String, TagKey<Block>> TAGS = Map.of("logs", BlockTags.LOGS, "leaves", BlockTags.LEAVES, "sand", BlockTags.SAND,
            "dirt", BlockTags.DIRT, "base_stone_overworld", BlockTags.BASE_STONE_OVERWORLD, "wool", BlockTags.WOOL, "planks", BlockTags.PLANKS,
            "crops", BlockTags.CROPS);

    private final SurfaceSchedule schedule = new SurfaceSchedule();
    private final Map<Block, Integer> familyCache = new HashMap<>();
    private ExecutorService worker;
    private volatile boolean on = true;
    private boolean loaded;
    private String settingsNote = "not loaded yet";
    private String dim;
    private Path dimDir;
    private boolean inWorld;
    private long activeSinceMs;
    private volatile long chunksWritten, deleted, lastWriteAtMs, lastWriteNanos, scanned;
    private volatile boolean writeErrorLogged;
    private volatile String lastWriteError;
    private long lastColumnErrorLogMs;
    private long columnErrors;
    private String lastTickError;
    private long tickErrors;

    private SurfaceExport() {}

    public boolean on() { return on; }

    private synchronized ExecutorService worker() {
        if (worker == null) worker = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "entropybot-surface");
            t.setDaemon(true);
            return t;
        });
        return worker;
    }

    private static Path root() {
        io.github.mojolowjo.entropybot.io.BotFiles f = io.github.mojolowjo.entropybot.Core.INSTANCE.files();
        return f == null ? null : f.root().resolve("surface");
    }

    // ---- events (game thread) ----------------------------------------------------------------------------------------

    public void chunkLoaded(int cx, int cz) {
        try {
            if (on && dim != null) schedule.loaded(cx, cz, System.currentTimeMillis());
        } catch (Throwable t) {
            tickError("chunk load", t);
        }
    }

    public void chunkUnloaded(int cx, int cz) {
        try {
            schedule.unloaded(cx, cz);
            Path d = dimDir;
            if (d == null) return;
            String name = SurfaceColumns.fileName(cx, cz);
            worker().execute(() -> {
                try {
                    if (Files.deleteIfExists(d.resolve(name))) deleted++;
                } catch (Throwable t) {
                    writeFailed("delete " + name, t);
                }
            });
        } catch (Throwable t) {
            tickError("chunk unload", t);
        }
    }

    public void blockChanged(int x, int z) {
        try {
            if (!on || dim == null) return;
            ClientLevel level = Minecraft.getInstance().level;
            if (level == null || level.getChunkSource().getChunk(x >> 4, z >> 4, false) == null) return;
            schedule.changed(x >> 4, z >> 4, System.currentTimeMillis());
        } catch (Throwable t) {
            tickError("block change", t);
        }
    }

    // ---- tick --------------------------------------------------------------------------------------------------------

    /** Once a client tick (from Core). Never throws. */
    public void tick() {
        try {
            ensureLoaded();
            Minecraft mc = Minecraft.getInstance();
            ClientLevel level = mc.level;
            if (level == null || mc.player == null) return;
            if (!inWorld) {
                inWorld = true;
                activeSinceMs = System.currentTimeMillis();
            }
            if (!on) {
                if (dim != null) stopAndWipe();
                return;
            }
            String d = Guard.dimOf(level);
            if (!d.equals(dim)) start(level, d, mc);
            long now = System.currentTimeMillis();
            BlockPos p = mc.player.blockPosition();
            List<long[]> next = schedule.take(p.getX() >> 4, p.getZ() >> 4, now, SurfaceSchedule.PER_TICK);
            long t0 = System.nanoTime();
            for (int i = 0; i < next.size(); i++) {
                int cx = (int) next.get(i)[0], cz = (int) next.get(i)[1];
                if (i > 0 && System.nanoTime() - t0 > BUDGET_NS) {
                    schedule.loaded(cx, cz, now);                  // over budget: next tick
                    continue;
                }
                if (level.getChunkSource().getChunk(cx, cz, false) == null) continue;
                SurfaceColumns cols = SurfaceColumns.scan(source(level), cx, cz, level.getMinBuildHeight(), this::columnError);
                scanned++;
                write(dimDir, dim, cx, cz, cols, now);
            }
        } catch (Throwable t) {
            tickError("tick", t);
        }
    }

    /** A new world or dimension: wipe its folder, start over with the chunks loaded now round the bot. */
    private void start(ClientLevel level, String d, Minecraft mc) {
        schedule.clear();
        familyCache.clear();
        Path root = root();
        if (root == null) return;
        dim = d;
        dimDir = root.resolve(SurfaceColumns.dimFolder(d));
        wipe(dimDir);
        long now = System.currentTimeMillis();
        int rd = mc.options.renderDistance().get() + 2;
        BlockPos p = mc.player.blockPosition();
        int ecx = p.getX() >> 4, ecz = p.getZ() >> 4;
        for (int cx = ecx - rd; cx <= ecx + rd; cx++)
            for (int cz = ecz - rd; cz <= ecz + rd; cz++)
                if (level.getChunkSource().getChunk(cx, cz, false) != null) schedule.loaded(cx, cz, now);
    }

    private void stopAndWipe() {
        schedule.clear();
        if (dimDir != null) wipe(dimDir);
        dim = null;
        dimDir = null;
    }

    /** Core: the bot left the world. */
    public void leftWorld() {
        try {
            inWorld = false;
            stopAndWipe();
        } catch (Throwable t) {
            tickError("leave", t);
        }
    }

    private void wipe(Path dir) {
        worker().execute(() -> {
            try {
                if (!Files.isDirectory(dir)) return;
                try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "*.json*")) {
                    for (Path f : ds) Files.deleteIfExists(f);
                }
            } catch (Throwable t) {
                writeFailed("wipe " + dir.getFileName(), t);
            }
        });
    }

    private void write(Path dir, String d, int cx, int cz, SurfaceColumns cols, long t) {
        worker().execute(() -> {
            long t0 = System.nanoTime();
            Path target = dir.resolve(SurfaceColumns.fileName(cx, cz));
            Path tmp = dir.resolve(SurfaceColumns.fileName(cx, cz) + ".tmp");
            try {
                Files.createDirectories(dir);
                Files.write(tmp, cols.toJson(d, cx, cz, t).getBytes(StandardCharsets.UTF_8));
                try {
                    Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
                }
                chunksWritten++;
                lastWriteAtMs = System.currentTimeMillis();
                lastWriteNanos = System.nanoTime() - t0;
                writeErrorLogged = false;
            } catch (Throwable e) {
                try { Files.deleteIfExists(tmp); } catch (IOException ignored) {}
                writeFailed("write " + target.getFileName(), e);
            }
        });
    }

    private void writeFailed(String what, Throwable t) {
        lastWriteError = what + ": " + t;
        if (!writeErrorLogged) {
            writeErrorLogged = true;
            LOG.warn("[entropybot] surface export: couldn't {} ({}); retried on the next change", what, t.toString());
        }
    }

    private void columnError(int x, int z, Throwable t) {
        columnErrors++;
        long now = System.currentTimeMillis();
        if (now - lastColumnErrorLogMs >= 60_000) {
            lastColumnErrorLogMs = now;
            LOG.warn("[entropybot] surface export: column {} {} failed ({} so far): {}", x, z, columnErrors, t.toString());
        }
    }

    private void tickError(String where, Throwable t) {
        tickErrors++;
        lastTickError = where + ": " + t;
        if (tickErrors <= 5 || tickErrors % 1000 == 0) LOG.warn("[entropybot] surface export ({}), error {}: {}", where, tickErrors, t.toString());
    }

    // ---- the world as the scan sees it ---------------------------------------------------------------------------------

    /** P3 (chop): the export's column source on the live level (client thread only), for tree candidates. */
    public static SurfaceColumns.Source liveSource(ClientLevel level) { return INSTANCE.source(level); }

    private SurfaceColumns.Source source(ClientLevel level) {
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        int max = level.getMaxBuildHeight() - 1;
        return new SurfaceColumns.Source() {
            @Override
            public int kind(int x, int y, int z) {
                return kindOf(level, m.set(x, y, z));
            }

            @Override
            public int family(int x, int y, int z) {
                return familyOf(level.getBlockState(m.set(x, y, z)));
            }

            @Override
            public int top(int x, int z) {
                return Math.min(level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z), max);
            }
        };
    }

    /** The column kind of one block (also the surface dig's rule, DigCommands). */
    public static int kindOf(ClientLevel level, BlockPos pos) {
        BlockState s = level.getBlockState(pos);
        if (s.isAir()) return SurfaceColumns.AIR;
        Block b = s.getBlock();
        if (b instanceof net.minecraft.world.level.block.LiquidBlock) return SurfaceColumns.GROUND;
        if (b instanceof net.minecraft.world.level.block.LeavesBlock || s.is(BlockTags.LEAVES)) return SurfaceColumns.LEAVES;
        if (b instanceof net.minecraft.world.level.block.SnowLayerBlock) return SurfaceColumns.PLANT;
        if (s.is(BlockTags.CROPS)) return SurfaceColumns.GROUND;
        boolean noCollision = s.getCollisionShape(level, pos).isEmpty();
        if (noCollision && !s.getFluidState().isEmpty()) return SurfaceColumns.GROUND;   // seagrass, kelp: the water's surface
        if (s.getRenderShape() == net.minecraft.world.level.block.RenderShape.INVISIBLE) return SurfaceColumns.AIR;
        return noCollision ? SurfaceColumns.PLANT : SurfaceColumns.GROUND;
    }

    /** P4: the family index of a block state (the export's cached rule; client thread). */
    public static int familyOfState(BlockState s) { return INSTANCE.familyOf(s); }

    private int familyOf(BlockState s) {
        Block b = s.getBlock();
        Integer f = familyCache.get(b);
        if (f == null) {
            f = SurfaceFamily.of(BuiltInRegistries.BLOCK.getKey(b).toString(), tag -> {
                TagKey<Block> k = TAGS.get(tag);
                return k != null && s.is(k);
            });
            familyCache.put(b, f);
        }
        return f;
    }

    // ---- settings, command, status, check -----------------------------------------------------------------------------

    private void ensureLoaded() {
        if (loaded) return;
        io.github.mojolowjo.entropybot.io.BotFiles files = io.github.mojolowjo.entropybot.Core.INSTANCE.files();
        if (files == null) return;
        loaded = true;
        try {
            String text = files.readJson(SETTINGS_FILE);
            Boolean v = SurfaceSchedule.parseOn(text);
            if (v != null) on = v;
            settingsNote = SETTINGS_FILE + ": " + (text == null ? "none yet (on)" : v == null ? "unreadable, using on" : "loaded");
        } catch (Throwable t) {
            settingsNote = SETTINGS_FILE + ": couldn't load (" + t + "), using on";
            LOG.warn("[entropybot] surface export: {}", settingsNote);
        }
    }

    /** "surface" | "surface status" | "surface on" | "surface off". Never throws. */
    public String command(String rest) {
        try {
            ensureLoaded();
            String r = rest == null ? "" : rest.trim().toLowerCase(java.util.Locale.ROOT);
            if (r.isEmpty() || r.equals("status")) return status();
            if (r.equals("on") || r.equals("off")) {
                on = r.equals("on");
                String saved = save();
                return "surface export " + (on ? "on: writing the loaded chunks' surface for the dashboard" : "off: files removed, nothing written") + saved;
            }
            return "surface | surface status | surface on|off";
        } catch (Throwable t) {
            return "error: surface: " + t;
        }
    }

    private String save() {
        io.github.mojolowjo.entropybot.io.BotFiles files = io.github.mojolowjo.entropybot.Core.INSTANCE.files();
        if (files == null) return " (not saved: not in a world yet)";
        String r = files.writeJson(SETTINGS_FILE, "{\"on\":" + on + "}");
        settingsNote = SETTINGS_FILE + (r.startsWith("ok") ? ": saved" : ": couldn't write (" + r + ")");
        return r.startsWith("ok") ? "" : " (not saved: " + r + ")";
    }

    public String status() {
        StringBuilder sb = new StringBuilder("surface export: ").append(on ? "on" : "off");
        sb.append(" | ").append(chunksWritten).append(" chunk files written, ").append(deleted).append(" deleted, ").append(schedule.pending()).append(" pending");
        long at = lastWriteAtMs;
        sb.append(" | last write ").append(at == 0 ? "never" : String.format(java.util.Locale.ROOT, "%.1f ms, %d s ago", lastWriteNanos / 1e6,
                (System.currentTimeMillis() - at) / 1000));
        Path d = dimDir;
        sb.append(" | folder ").append(d == null ? "(none: not in a world or off)" : d.toString());
        if (columnErrors > 0) sb.append(" | column errors ").append(columnErrors);
        if (lastWriteError != null) sb.append(" | last file error ").append(lastWriteError);
        if (lastTickError != null) sb.append(" | errors ").append(tickErrors).append(", last ").append(lastTickError);
        sb.append(" | ").append(settingsNote);
        return sb.toString();
    }

    public List<SelfCheck.Finding> findings() {
        List<SelfCheck.Finding> out = new ArrayList<>();
        long now = System.currentTimeMillis();
        if (SurfaceSchedule.quiet(on, inWorld && dim != null, now - activeSinceMs, lastWriteAtMs == 0 ? -1 : now - lastWriteAtMs, schedule.pending()))
            out.add(new SelfCheck.Finding("surface", "the surface export is on but wrote no chunk file for " + SurfaceSchedule.QUIET_MS / 1000 + " s",
                    "surface status; the log has [entropybot] surface export lines; surface off, then surface on, to start over"));
        return out;
    }

}
