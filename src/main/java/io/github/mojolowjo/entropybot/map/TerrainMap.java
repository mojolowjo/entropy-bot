package io.github.mojolowjo.entropybot.map;

import com.mojang.logging.LogUtils;
import io.github.mojolowjo.entropybot.guard.Guard;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.MapColor;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Paints a top-down terrain map of the ground the bot has had loaded (docs/BOT_PLAN.md B7b), in the paper-map
 * look, for the dashboard to draw under its marks. Shaped like {@link io.github.mojolowjo.entropybot.poi.PoiScanner}:
 * one loaded chunk every {@link #EVERY} ticks, spiralling out to {@link #RADIUS} chunks, a chunk painted again
 * after {@link #RESCAN} ticks. Pixels live in 512x512 regions (one int[] of ARGB each), loaded from their PNG the
 * first time a region is touched, so a restart keeps what was drawn. Dirty regions are written at most every
 * {@link #WRITE_EVERY} ticks: copied here on the client thread, encoded and written on one background thread.
 * Dimensions with a ceiling (the Nether) are skipped: their top is the bedrock roof.
 */
public final class TerrainMap {
    private static final Logger LOG = LogUtils.getLogger();
    static final int RADIUS = 5;
    static final long RESCAN = 6000;        // 5 minutes
    static final int EVERY = 2;             // ticks between two chunks
    static final long WRITE_EVERY = 600;    // 30 s between two writes of the dirty regions
    static final long FORGET_AFTER = 12000; // a clean region untouched for 10 minutes leaves memory
    static final int MAX_STEP = 8;          // blocks to step down through see-through tops (glass, ...)
    static final int NO_Y = Integer.MIN_VALUE;

    private final Path root;
    private final Map<String, Region> regions = new HashMap<>();
    private final Map<Long, Long> scanned = new HashMap<>();
    private final Set<String> dims = ConcurrentHashMap.newKeySet();
    private final Queue<Region> retry = new ConcurrentLinkedQueue<>();
    private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
    private int[][] spiral;
    private int cursor;
    private String dim;
    private long lastChunk = Long.MIN_VALUE / 2, lastWrite;
    private int colY;                       // the height column() found, NO_Y for nothing
    private long painted;
    private int errors;
    private ExecutorService io;
    private final AtomicInteger ioErrors = new AtomicInteger();
    private volatile long lastWriteMs;
    private volatile int onDisk = -1;

    static final class Region {
        final String dim;
        final int rx, rz;
        final Path file;
        volatile int[] px;                  // null while the background thread loads it
        boolean dirty;
        long touched;

        Region(String dim, int rx, int rz, Path file) {
            this.dim = dim;
            this.rx = rx;
            this.rz = rz;
            this.file = file;
        }
    }

    public TerrainMap(Path root) {
        this.root = root.toAbsolutePath().normalize();
        java.util.List<int[]> s = new java.util.ArrayList<>();
        for (int dx = -RADIUS; dx <= RADIUS; dx++) for (int dz = -RADIUS; dz <= RADIUS; dz++) s.add(new int[] { dx, dz });
        s.sort(java.util.Comparator.comparingInt(a -> a[0] * a[0] + a[1] * a[1]));
        spiral = s.toArray(new int[0][]);
    }

    /** Once per client tick while in a world. Never throws. */
    public void tick(long tick) {
        try {
            step(tick);
        } catch (Throwable t) {
            errors++;
            if (errors <= 5 || errors % 1200 == 0) LOG.error("[entropybot] terrain map error #{}: {}", errors, t.toString());
        }
    }

    /**
     * 0.23.1 (explore): is chunk cx cz painted on the map? TRUE / FALSE, or null while its region's tile is still being read
     * (asked again later). A region with no tile on disk is unpainted at once. Client thread. Never throws (null).
     */
    public Boolean painted(String d, int cx, int cz) {
        try {
            int rx = MapMath.region(cx << 4), rz = MapMath.region(cz << 4);
            Region r = regions.get(key(d, rx, rz));
            if (r == null) {
                Path f = root.resolve(MapMath.dimFolder(d)).resolve(MapMath.fileName(rx, rz));
                if (!java.nio.file.Files.exists(f)) return Boolean.FALSE;
                r = region(d, cx << 4, cz << 4, lastChunk);
            }
            int[] px = r.px;
            return px == null ? null : MapMath.chunkPainted(px, cx, cz);
        } catch (Throwable t) {
            return null;
        }
    }

    /** A short line for the log: regions in memory, dirty ones, tiles on disk, the last write. */
    public String status() {
        try {
            int dirty = 0;
            for (Region r : regions.values()) if (r.dirty) dirty++;
            long ms = lastWriteMs;
            String last = ms == 0 ? "nothing written yet" : "last write " + Math.max(0, (System.currentTimeMillis() - ms) / 1000) + "s ago";
            int errs = errors + ioErrors.get();
            return "terrain map: " + regions.size() + " regions in memory, " + dirty + " dirty, "
                + (onDisk < 0 ? "?" : String.valueOf(onDisk)) + " tiles on disk, " + painted + " chunks painted, " + last
                + (errs > 0 ? ", " + errs + " errors" : "");
        } catch (Throwable t) {
            return "terrain map: " + t;
        }
    }

    /**
     * Writes every dirty region now (waiting up to 3 s for the background thread), then forgets the regions
     * and the chunk times, so the next world starts clean. For leaving the world. Never throws.
     */
    public void flushAll() {
        try {
            writeDirty(Long.MAX_VALUE / 2, true);
            Future<?> done = io().submit(() -> {});
            try {
                done.get(3, TimeUnit.SECONDS);
            } catch (Exception e) {
                LOG.warn("[entropybot] terrain map: tiles still being written after 3 s");
            }
            regions.clear();
            scanned.clear();
            dim = null;
        } catch (Throwable t) {
            errors++;
            LOG.error("[entropybot] terrain map flush failed: {}", t.toString());
        }
    }

    private void step(long tick) {
        for (Region r; (r = retry.poll()) != null; ) if (regions.get(key(r.dim, r.rx, r.rz)) == r) r.dirty = true;
        if (tick - lastWrite >= WRITE_EVERY) {
            lastWrite = tick;
            writeDirty(tick, false);
        }
        if (tick - lastChunk < EVERY) return;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        ClientLevel level = mc.level;
        if (p == null || level == null || level.dimensionType().hasCeiling()) return;
        String d = Guard.dimOf(level);
        if (!d.equals(dim)) {
            dim = d;
            scanned.clear();
        }
        if (scanned.size() > 8192) scanned.values().removeIf(at -> tick - at >= RESCAN);
        int pcx = p.getBlockX() >> 4, pcz = p.getBlockZ() >> 4;
        // the next chunk in the spiral that is loaded and not painted lately
        for (int tries = 0; tries < spiral.length; tries++) {
            int[] o = spiral[cursor++ % spiral.length];
            int cx = pcx + o[0], cz = pcz + o[1];
            long k = ((long) cx << 32) ^ (cz & 0xffffffffL);
            Long at = scanned.get(k);
            if (at != null && tick - at < RESCAN) continue;
            LevelChunk chunk = level.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, false);
            if (chunk == null || chunk.isEmpty()) continue;
            Region r = region(d, cx << 4, cz << 4, tick);
            if (r.px == null) continue; // still loading: painted on a later pass
            scanned.put(k, tick);
            lastChunk = tick;
            paint(level, chunk, cx, cz, r);
            painted++;
            return;
        }
    }

    /** Paints one chunk's 16x16 columns into its region; marks the region dirty when a pixel changed. */
    private void paint(ClientLevel level, LevelChunk chunk, int cx, int cz, Region r) {
        int bx = cx << 4, bz = cz << 4;
        // the heights of the row to the north, for the shading of row 0 (unknown when that chunk isn't loaded)
        int[] north = new int[16];
        LevelChunk n = level.getChunkSource().getChunk(cx, cz - 1, ChunkStatus.FULL, false);
        for (int x = 0; x < 16; x++) {
            if (n != null && !n.isEmpty()) column(level, n, bx + x, bz - 1, NO_Y);
            else colY = NO_Y;
            north[x] = colY;
        }
        int[] px = r.px;
        boolean changed = false;
        for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
            int c = column(level, chunk, bx + x, bz + z, north[x]);
            north[x] = colY;
            if (c == 0) continue; // nothing there: keep what was drawn before
            int i = MapMath.index(bx + x, bz + z);
            if (px[i] != c) {
                px[i] = c;
                changed = true;
            }
        }
        if (changed) r.dirty = true;
    }

    /**
     * The ARGB colour of column x z (0 for nothing), and its height in {@link #colY}. Like vanilla MapItem:
     * the top block of WORLD_SURFACE, stepping down through blocks without a map colour; water shaded by its
     * depth, land by its height against the column to the north.
     */
    private int column(ClientLevel level, LevelChunk chunk, int x, int z, int yNorth) {
        colY = NO_Y;
        int minY = level.getMinBuildHeight();
        int y = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
        if (y < minY) return 0;
        BlockState st = null;
        MapColor mc = MapColor.NONE;
        for (int i = 0; i <= MAX_STEP && y >= minY; i++, y--) {
            pos.set(x, y, z);
            st = chunk.getBlockState(pos);
            mc = st.getMapColor(level, pos);
            if (mc != MapColor.NONE) break;
        }
        if (mc == MapColor.NONE || st == null) return 0;
        colY = y;
        if (st.getFluidState().is(FluidTags.WATER) && !st.isFaceSturdy(level, pos, Direction.UP)) {
            int depth = 1;
            for (int yy = y - 1; depth < MapMath.WATER_DEPTH_MAX && yy >= minY; yy--) {
                pos.setY(yy);
                if (chunk.getBlockState(pos).getFluidState().isEmpty()) break;
                depth++;
            }
            return MapMath.argb(MapColor.WATER.col, MapMath.water(depth, x, z));
        }
        return MapMath.argb(mc.col, MapMath.land(y, yNorth == NO_Y ? y : yNorth, x, z));
    }

    private static String key(String dim, int rx, int rz) {
        return dim + " " + rx + " " + rz;
    }

    /** The region holding block x z, loaded in the background the first time (px stays null until then). */
    private Region region(String d, int x, int z, long tick) {
        int rx = MapMath.region(x), rz = MapMath.region(z);
        String k = key(d, rx, rz);
        Region r = regions.get(k);
        if (r == null) {
            r = new Region(d, rx, rz, root.resolve(MapMath.dimFolder(d)).resolve(MapMath.fileName(rx, rz)));
            regions.put(k, r);
            dims.add(d);
            Region load = r;
            io().execute(() -> load(load));
        }
        r.touched = tick;
        return r;
    }

    // background thread
    private void load(Region r) {
        int[] px = null;
        try {
            px = TileFiles.readPng(r.file);
        } catch (Throwable t) {
            ioError("read " + r.file, t);
        } finally {
            r.px = px != null ? px : new int[MapMath.REGION * MapMath.REGION];
        }
    }

    /** Hands every dirty region's pixels (copied here) to the background thread; forgets regions long untouched. */
    private void writeDirty(long tick, boolean all) {
        boolean any = false;
        for (Iterator<Region> it = regions.values().iterator(); it.hasNext(); ) {
            Region r = it.next();
            int[] px = r.px;
            if (px == null) continue;
            if (r.dirty) {
                r.dirty = false;
                int[] copy = px.clone();
                io().execute(() -> {
                    try {
                        TileFiles.writePng(r.file, copy);
                        lastWriteMs = System.currentTimeMillis();
                    } catch (Throwable t) {
                        ioError("write " + r.file, t);
                        retry.add(r);
                    }
                });
                any = true;
            } else if (!all && tick - r.touched > FORGET_AFTER) {
                it.remove();
            }
        }
        if (any || onDisk < 0) io().execute(() -> {
            try {
                onDisk = TileFiles.writeIndex(root, dims);
            } catch (Throwable t) {
                ioError("write the index", t);
            }
        });
    }

    private void ioError(String what, Throwable t) {
        int n = ioErrors.incrementAndGet();
        if (n <= 5 || n % 100 == 0) LOG.error("[entropybot] terrain map: could not {} (#{}): {}", what, n, t.toString());
    }

    private ExecutorService io() {
        if (io == null) io = Executors.newSingleThreadExecutor(run -> {
            Thread t = new Thread(run, "entropybot-map");
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
        });
        return io;
    }
}
