package io.github.mojolowjo.entropybot.watchview;

import io.github.mojolowjo.entropybot.commands.SelfCheck;
import io.github.mojolowjo.entropybot.guard.Guard;
import net.minecraft.client.GraphicsStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.FluidState;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 0.17.1: the game side of the surface scan ({@link SkyScan}, {@link SkyStore}). While the tunnel view is on, every
 * loaded chunk within the render distance (the {@code renderDistance} option, read live) is scanned, nearest first, a
 * column at a time under a hard time budget per client tick ({@link #BUDGET_NS}; a chunk takes several ticks and goes on
 * where it stopped), and its sky faces stored for the view's per-chunk meshes. A chunk is scanned again when
 * <ul>
 *   <li>it (or a neighbour, whose edge faces it owns) is loaded: NeoForge {@code ChunkEvent.Load} on the client
 *       (posted by {@code ClientChunkCache.replaceWithPacketData});</li>
 *   <li>a block changes at or above the lowest floor of its columns (or of a neighbour chunk's, within one block): the
 *       mod's existing ClientLevel hook ({@code RecorderMixinClientLevel} through {@code FlightRecorder.watchListener};
 *       NeoForge has no client block-change event, so no new mixin);</li>
 *   <li>the graphics setting changes between Fast and Fancy/Fabulous (the leaves rule differs).</li>
 * </ul>
 * A chunk being unloaded ({@code ChunkEvent.Unload}) or leaving the render distance is dropped. Errors: caught, the
 * first 5 logged in full then every 100th, 20 in a minute turn the scan off (status and {@code check}: skyscan).
 */
public final class SkyScanner {
    public static final SkyScanner INSTANCE = new SkyScanner();
    /** The scan's share of a client tick (owner, 2026-10-05: performance is not a worry now; still never a stall). */
    public static final long BUDGET_NS = 2_000_000;
    static final int REFILL_EVERY_TICKS = 10;
    /** check: on, chunks waiting, and no column scanned for this long. */
    static final long STALL_MS = 5000;

    private final SkyStore store = new SkyStore();
    private final SeenRule.Errors errs = new SeenRule.Errors();
    private volatile String offByError, lastError;
    private String dim;
    private Boolean fancyNow;
    private long lastEyeChunk = Long.MIN_VALUE;
    private long lastRefillTick;
    // the chunk being scanned
    private int jobX, jobZ, jobCol = -1;
    private boolean jobDirty;
    private long[] jobFaces = new long[1024];
    private byte[] jobLight = new byte[1024];
    private int jobN;
    private final int[] jobFloors = new int[256];
    // counters
    private volatile long chunksScanned, columnsScanned, facesAdded, overruns, nanosTotal, ticksScanned, maxNanos, rescans, blockMarks, loads, unloads;
    private volatile long lastColumnMs, activeSinceMs;
    private volatile int lastRenderChunks;
    private boolean wasActive;

    private SkyScanner() {}

    public SkyStore store() { return store; }

    /** Runs while the tunnel view is on (it is the only user), unless errors turned it off. */
    public boolean active() { return TunnelView.INSTANCE.on() && offByError == null; }

    /** The graphics setting the scan used (true = Fancy or Fabulous), or null before the first scan. */
    public Boolean fancy() { return fancyNow; }

    public static boolean fancyNow(Minecraft mc) {
        try {
            return mc.options.graphicsMode().get() != GraphicsStatus.FAST;
        } catch (Throwable t) {
            return false;
        }
    }

    // ---- cell kinds (the scan's view of a block) -------------------------------------------------------------------

    /**
     * The scan's cell kind: air and invisible blocks AIR; no collision shape (grass, flowers, torches, rails) PLANT, snow
     * layers excepted; liquid water and seagrass WATER; lava SHAPE; glass, panes, ice, slime, honey GLASS; leaves (any
     * {@code LeavesBlock}) LEAVES; opaque full blocks SOLID; any other full outline SHAPE (spawners); the rest PARTIAL
     * (slabs, stairs, paths, farmland, fences, chests, waterlogged blocks). The same classes as the rays' (SeenSampler.cell).
     */
    static int kind(ClientLevel level, BlockPos pos) {
        BlockState s = level.getBlockState(pos);
        if (s.isAir()) return SkyScan.AIR;
        if (s.isSolidRender(level, pos)) return SkyScan.SOLID;
        Block b = s.getBlock();
        FluidState f = s.getFluidState();
        if (b instanceof net.minecraft.world.level.block.LiquidBlock) return f.is(FluidTags.WATER) ? SkyScan.WATER : SkyScan.SHAPE;
        if (!f.isEmpty() && f.is(FluidTags.WATER) && s.getCollisionShape(level, pos).isEmpty()) return SkyScan.WATER;
        if (s.getRenderShape() == net.minecraft.world.level.block.RenderShape.INVISIBLE) return SkyScan.AIR;
        if (b instanceof net.minecraft.world.level.block.LeavesBlock) return SkyScan.LEAVES;
        if (b instanceof net.minecraft.world.level.block.HalfTransparentBlock || b instanceof net.minecraft.world.level.block.IronBarsBlock) return SkyScan.GLASS;
        if (!(b instanceof net.minecraft.world.level.block.SnowLayerBlock) && s.getCollisionShape(level, pos).isEmpty()) return SkyScan.PLANT;
        net.minecraft.world.phys.shapes.VoxelShape sh = s.getShape(level, pos);
        if (sh.isEmpty()) return SkyScan.PLANT;
        return sh == net.minecraft.world.phys.shapes.Shapes.block() ? SkyScan.SHAPE : SkyScan.PARTIAL;
    }

    /** Vanilla's neighbour test for the face {@code side} of the block at pos ({@code Block.shouldRenderFace}); true on any error. */
    static boolean faceShows(ClientLevel level, BlockPos.MutableBlockPos pos, BlockPos.MutableBlockPos other, int side) {
        try {
            Direction d = Direction.from3DDataValue(side);
            other.setWithOffset(pos, d);
            return Block.shouldRenderFace(level.getBlockState(pos), level, pos, d, other);
        } catch (Throwable t) {
            return true;
        }
    }

    // ---- events ------------------------------------------------------------------------------------------------------

    /** NeoForge ChunkEvent.Load on the client: scan it, and its scanned neighbours again (their edge faces). */
    public void chunkLoaded(int cx, int cz) {
        if (!active()) return;
        loads++;
        store.enqueue(cx, cz);
        int[][] n = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] o : n) if (store.has(cx + o[0], cz + o[1])) store.enqueue(cx + o[0], cz + o[1]);
    }

    /** NeoForge ChunkEvent.Unload: forget it. */
    public void chunkUnloaded(int cx, int cz) {
        unloads++;
        store.remove(cx, cz);
        if (jobCol >= 0 && jobX == cx && jobZ == cz) jobCol = -1;
    }

    /**
     * A block changed (game thread, the ClientLevel hook): the chunks whose faces or corner shading can change are
     * scanned again, unless the change lies more than a block below every floor there (deep underground: no sky face
     * can change; the rays and the dug-tunnel shell handle it).
     */
    public void blockChanged(int x, int y, int z) {
        if (!active()) return;
        for (int cx = (x - 1) >> 4; cx <= (x + 1) >> 4; cx++)
            for (int cz = (z - 1) >> 4; cz <= (z + 1) >> 4; cz++) {
                SkyStore.Chunk c = store.get(cx, cz);
                boolean inJob = jobCol >= 0 && jobX == cx && jobZ == cz;
                if (c == null && !inJob) continue;
                if (c != null && y < c.minFloor() - 1 && !inJob) continue;
                blockMarks++;
                if (inJob) jobDirty = true;
                else store.enqueue(cx, cz);
            }
    }

    // ---- tick ------------------------------------------------------------------------------------------------------

    /** Once a client tick (from Core, after the sampler). Never throws. */
    public void tick(long tick) {
        try {
            boolean active = active();
            if (active && !wasActive) {
                activeSinceMs = System.currentTimeMillis();
                lastColumnMs = 0;
            }
            if (!active && wasActive) clear();
            wasActive = active;
            if (!active) return;
            Minecraft mc = Minecraft.getInstance();
            ClientLevel level = mc.level;
            if (mc.player == null || level == null) return;
            String d = Guard.dimOf(level);
            boolean fancy = fancyNow(mc);
            if (!d.equals(dim) || fancyNow == null || fancy != fancyNow) {
                clear();
                dim = d;
                fancyNow = fancy;
                lastRefillTick = Long.MIN_VALUE / 2;
            }
            BlockPos eye = BlockPos.containing(mc.player.getEyePosition());
            int ecx = eye.getX() >> 4, ecz = eye.getZ() >> 4;
            long ek = SkyStore.chunkKey(ecx, ecz);
            if (ek != lastEyeChunk) {
                lastEyeChunk = ek;
                store.eyeMoved();
                lastRefillTick = Long.MIN_VALUE / 2;
            }
            int rd = mc.options.renderDistance().get();
            lastRenderChunks = rd;
            if (tick - lastRefillTick >= REFILL_EVERY_TICKS) {
                lastRefillTick = tick;
                store.pruneOutside(ecx, ecz, rd);
                for (int cx = ecx - rd - 1; cx <= ecx + rd + 1; cx++)
                    for (int cz = ecz - rd - 1; cz <= ecz + rd + 1; cz++) {
                        if (!SeenFaces.chunkWithin(cx, cz, ecx, ecz, rd) || store.has(cx, cz) || store.queued(cx, cz)) continue;
                        if (jobCol >= 0 && jobX == cx && jobZ == cz) continue;
                        if (level.getChunkSource().getChunk(cx, cz, false) != null) store.enqueue(cx, cz);
                    }
            }
            scan(level, ecx, ecz, rd, fancy);
        } catch (Throwable t) {
            fail("tick", t);
        }
    }

    private void scan(ClientLevel level, int ecx, int ecz, int rd, boolean fancy) {
        long t0 = System.nanoTime(), end = t0 + BUDGET_NS;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos(), m2 = new BlockPos.MutableBlockPos(), m3 = new BlockPos.MutableBlockPos();
        SkyScan.World w = new SkyScan.World() {
            @Override
            public int kind(int x, int y, int z) {
                return SkyScanner.kind(level, m.set(x, y, z));
            }

            @Override
            public boolean faceShows(int x, int y, int z, int side) {
                return SkyScanner.faceShows(level, m2.set(x, y, z), m3, side);
            }
        };
        int minY = level.getMinBuildHeight();
        boolean worked = false;
        while (System.nanoTime() < end) {
            if (jobCol < 0) {
                long[] next = store.next(ecx, ecz);
                if (next == null) break;
                int cx = (int) next[0], cz = (int) next[1];
                if (!SeenFaces.chunkWithin(cx, cz, ecx, ecz, rd) || level.getChunkSource().getChunk(cx, cz, false) == null) continue;
                jobX = cx;
                jobZ = cz;
                jobCol = 0;
                jobN = 0;
                jobDirty = false;
                if (store.has(cx, cz)) rescans++;
            }
            if (jobDirty) {                                    // changed while we scanned it: start it over
                jobCol = 0;
                jobN = 0;
                jobDirty = false;
            }
            int lx = jobCol & 15, lz = jobCol >> 4;
            int x = (jobX << 4) + lx, z = (jobZ << 4) + lz;
            int top = topOf(level, x, z);
            final int bx = x, bz = z;
            jobFloors[jobCol] = SkyScan.column(w, x, z, top, minY, fancy, (fx, fy, fz, side) -> {
                int[] o = Shell.OFF[side];
                int light = 15;
                try {
                    light = level.getRawBrightness(m3.set(fx + o[0], fy + o[1], fz + o[2]), 0);
                } catch (RuntimeException ignored) {
                }
                addFace(SkyStore.faceKey(fx, fy, fz, side), light);
            });
            columnsScanned++;
            worked = true;
            if (++jobCol >= 256) finishJob(ecx, ecz);
        }
        long ns = System.nanoTime() - t0;
        if (worked) {
            lastColumnMs = System.currentTimeMillis();
            ticksScanned++;
            nanosTotal += ns;
            if (ns > maxNanos) maxNanos = ns;
            if (ns > BUDGET_NS * 2) overruns++;              // one column ran far past the budget (counted, not hidden)
        } else if (store.queueSize() == 0 && jobCol < 0) {
            lastColumnMs = System.currentTimeMillis();        // nothing to do is not a stall
        }
    }

    /** The first all-air y above the highest block of column x z and its four neighbours (client heightmap WORLD_SURFACE). */
    private static int topOf(ClientLevel level, int x, int z) {
        int top = level.getMinBuildHeight();
        int[][] cols = {{0, 0}, {1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] c : cols) {
            int h;
            try {
                h = level.getHeight(Heightmap.Types.WORLD_SURFACE, x + c[0], z + c[1]);
            } catch (RuntimeException e) {
                h = level.getMaxBuildHeight();
            }
            top = Math.max(top, h);
        }
        return Math.min(top, level.getMaxBuildHeight() - 1);
    }

    private void addFace(long key, int light) {
        if (jobN == jobFaces.length) {
            jobFaces = Arrays.copyOf(jobFaces, jobN * 2);
            jobLight = Arrays.copyOf(jobLight, jobN * 2);
        }
        jobFaces[jobN] = key;
        jobLight[jobN] = (byte) Math.max(0, Math.min(15, light));
        jobN++;
    }

    private void finishJob(int ecx, int ecz) {
        long[][] pairs = new long[jobN][];
        for (int i = 0; i < jobN; i++) pairs[i] = new long[]{jobFaces[i], jobLight[i]};
        Arrays.sort(pairs, (a, b) -> Long.compare(a[0], b[0]));
        long[] faces = new long[jobN];
        byte[] light = new byte[jobN];
        for (int i = 0; i < jobN; i++) {
            faces[i] = pairs[i][0];
            light[i] = (byte) pairs[i][1];
        }
        store.put(new SkyStore.Chunk(jobX, jobZ, faces, light, jobFloors.clone(), store.newChunkVersion()), ecx, ecz);
        chunksScanned++;
        facesAdded += jobN;
        jobCol = -1;
        jobN = 0;
    }

    /** Forget everything (view off, dimension or graphics change, leaving the world). */
    public void clear() {
        store.clear();
        jobCol = -1;
        jobN = 0;
        lastEyeChunk = Long.MIN_VALUE;
    }

    public void leftWorld() {
        clear();
        dim = null;
        fancyNow = null;
    }

    private void fail(String where, Throwable t) {
        lastError = where + ": " + t;
        boolean stop = errs.add(System.currentTimeMillis());
        if (errs.logIt()) com.mojang.logging.LogUtils.getLogger().warn("[entropybot] watch tunnel surface scan ({}), error {}: ", where, errs.total(), t);
        jobCol = -1;
        if (stop && offByError == null) {
            offByError = SeenRule.ERRORS_TO_STOP + " errors within a minute, last " + lastError;
            com.mojang.logging.LogUtils.getLogger().warn("[entropybot] watch tunnel surface scan: turned itself off ({})", offByError);
            clear();
        }
    }

    /** {@code watch tunnel on} after an error stop: try again. */
    public void reset() {
        offByError = null;
        errs.reset();
    }

    // ---- status, check ---------------------------------------------------------------------------------------------

    public String status() {
        StringBuilder sb = new StringBuilder("surface scan: ");
        if (offByError != null) sb.append("OFF after errors (").append(offByError).append(")");
        else if (!active()) sb.append("idle (runs while watch tunnel is on)");
        else sb.append("on (graphics ").append(Boolean.TRUE.equals(fancyNow) ? "fancy: leaves cut out, faces between leaves drawn" : "fast: leaves solid, faces between leaves skipped")
                .append(", render distance ").append(lastRenderChunks).append(" chunks)");
        sb.append(" | ").append(store.chunkCount()).append(" chunks, ").append(store.faces()).append(" faces (cap ").append(SkyStore.MAX_FACES).append(')');
        if (store.cappedNow() > 0) sb.append(", ").append(store.cappedNow()).append(" farthest chunks left out at the cap");
        sb.append(" | queue ").append(store.queueSize()).append(jobCol >= 0 ? " + 1 under way (column " + jobCol + "/256)" : "");
        sb.append(" | scanned ").append(chunksScanned).append(" chunks (").append(rescans).append(" again), ").append(columnsScanned).append(" columns, ")
                .append(facesAdded).append(" faces added");
        long tk = ticksScanned;
        sb.append(" | ").append(tk > 0 ? String.format(java.util.Locale.ROOT, "%.2f", nanosTotal / 1e6 / tk) : "-").append(" ms per scanning tick (max ")
                .append(String.format(java.util.Locale.ROOT, "%.2f", maxNanos / 1e6)).append(", budget ").append(BUDGET_NS / 1_000_000).append(" ms, ")
                .append(overruns).append(" overruns)");
        sb.append(" | rescans asked by block changes ").append(blockMarks).append(", chunk loads ").append(loads).append(", unloads ").append(unloads)
                .append(", dropped out of range ").append(store.pruned());
        sb.append(" | block hook ").append(io.github.mojolowjo.entropybot.recorder.FlightRecorder.hooked ? "in" : "not heard yet");
        if (errs.total() > 0) sb.append(" | errors ").append(errs.total()).append(", last ").append(lastError);
        return sb.toString();
    }

    public List<SelfCheck.Finding> findings() {
        List<SelfCheck.Finding> out = new ArrayList<>();
        if (offByError != null) out.add(new SelfCheck.Finding("skyscan", "the tunnel view's surface scan turned itself off: " + offByError,
                "watch tunnel status; the log has [entropybot] watch tunnel surface scan lines; watch tunnel off, then watch tunnel, to try again"));
        long now = System.currentTimeMillis();
        boolean waiting = store.queueSize() > 0 || jobCol >= 0;
        if (WatchChecks.scanStalled(active(), waiting, now - activeSinceMs, lastColumnMs == 0 ? -1 : now - lastColumnMs, STALL_MS))
            out.add(new SelfCheck.Finding("skyscan", "the tunnel view's surface scan has chunks waiting but scanned nothing for " + STALL_MS / 1000 + " s",
                    "watch tunnel status (surface scan: queue, ms per tick); the log has [entropybot] watch tunnel surface scan lines"));
        return out;
    }
}
