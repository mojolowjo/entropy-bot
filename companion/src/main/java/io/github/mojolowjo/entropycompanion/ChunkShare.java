package io.github.mojolowjo.entropycompanion;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.neoforge.event.level.ChunkEvent;
import org.slf4j.Logger;

import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * Companion 0.3.0 (chunks-0.23.5): shares the surface of the chunks the owner's client has loaded with the bot, through
 * the dashboard's {@code POST /api/chunks} (the dashboard writes them into the bot's surface folder; the bot's route map
 * and the RTS page read them there). Pure parts: {@link ChunkScanQueue}, {@link ChunkScanner}, {@link ChunkBatcher},
 * {@link ChunkColumns} (a copy of the bot's surface rule).
 *
 * <p>Threads: the scan runs on the client thread in {@link #tick} (1 ms a tick at most); the post (gzip, at most 64
 * chunks, at most every 10 s, backoff on failures) on its own daemon thread. On while the config's {@code shareChunks}
 * is true and the url and key are set; {@code /bot companion chunks on|off|status}.
 *
 * <p>Loader notes (docs/PLANNING.md): chunk load/unload: NeoForge {@code ChunkEvent.Load/Unload} on
 * {@code NeoForge.EVENT_BUS} (Fabric: {@code ClientChunkEvents.CHUNK_LOAD/UNLOAD}); block changes: the companion's own
 * ClientLevel mixin ({@code mixin.ChunkMixinClientLevel}, loader-neutral; require 0: without it only loads are scanned);
 * the tick: {@code ClientTickEvent.Post} (Fabric {@code END_CLIENT_TICK}); block reads, tags and heightmaps: vanilla.
 * Errors: a failing column is -1 (counted, logged once a minute); a failed post is retried with backoff and shown in
 * {@code /bot status}; nothing throws out of an event or tick.
 */
public final class ChunkShare {
    static ChunkShare INSTANCE;
    private static final Logger LOG = LogUtils.getLogger();
    private static final Map<String, TagKey<Block>> TAGS = Map.of("logs", BlockTags.LOGS, "leaves", BlockTags.LEAVES, "sand", BlockTags.SAND,
            "dirt", BlockTags.DIRT, "base_stone_overworld", BlockTags.BASE_STONE_OVERWORLD, "wool", BlockTags.WOOL, "planks", BlockTags.PLANKS,
            "crops", BlockTags.CROPS);

    private final Supplier<CompanionConfig> config;
    private final HttpSender http;
    final ChunkScanQueue queue = new ChunkScanQueue();
    final ChunkBatcher batcher = new ChunkBatcher();
    private final Map<Block, Integer> familyCache = new HashMap<>();
    private final ExecutorService poster = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "entropy-companion-chunks");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean posting = new AtomicBoolean();
    private ClientLevel level;
    private String dim;
    volatile long scanned, columnErrors, blockChanges;
    private long lastColumnLogAt;
    private volatile String lastError = "none";

    ChunkShare(Supplier<CompanionConfig> config, HttpSender http) {
        this.config = config;
        this.http = http;
    }

    private boolean on() {
        CompanionConfig c = config.get();
        return c.shareChunks && c.active();
    }

    // ---- events ----

    void onLoad(ChunkEvent.Load e) {
        try {
            if (!e.getLevel().isClientSide() || e.getLevel() != Minecraft.getInstance().level || !on()) return;
            queue.loaded(e.getChunk().getPos().x, e.getChunk().getPos().z, System.currentTimeMillis());
        } catch (RuntimeException ex) {
            error("chunk load", ex);
        }
    }

    void onUnload(ChunkEvent.Unload e) {
        try {
            if (!e.getLevel().isClientSide()) return;
            queue.unloaded(e.getChunk().getPos().x, e.getChunk().getPos().z);
        } catch (RuntimeException ex) {
            error("chunk unload", ex);
        }
    }

    /** From the mixin: a block the client set (server update or the owner's own). Client thread. Never throws. */
    public static void blockChanged(ClientLevel lvl, BlockPos pos) {
        ChunkShare s = INSTANCE;
        if (s == null) return;
        try {
            if (lvl != s.level) return;
            s.blockChanges++;
            s.queue.changed(pos.getX() >> 4, pos.getZ() >> 4, System.currentTimeMillis());
        } catch (RuntimeException ex) {
            s.error("block change", ex);
        }
    }

    // ---- tick ----

    void tick() {
        try {
            Minecraft mc = Minecraft.getInstance();
            ClientLevel lvl = mc.level;
            if (lvl == null || mc.player == null || !on()) {
                if (level != null && lvl == null) {           // left the world
                    level = null;
                    dim = null;
                    queue.clear();
                }
                return;
            }
            if (lvl != level) start(mc, lvl);
            long now = System.currentTimeMillis();
            ClientLevel l = lvl;
            scanned += ChunkScanner.step(queue, new ChunkScanner.World() {
                public String dim() { return dim; }
                public boolean loaded(int cx, int cz) { return l.getChunkSource().getChunk(cx, cz, false) != null; }
                public int minY() { return l.getMinBuildHeight(); }
                public ChunkColumns.Source source() { return ChunkShare.this.source(l); }
            }, batcher, now, System::nanoTime, this::columnError);
            if (batcher.due(now) && posting.compareAndSet(false, true)) post(now);
        } catch (RuntimeException e) {
            error("tick", e);
        }
    }

    /** A new world or dimension: forget what was sent, queue the chunks loaded now round the owner. */
    private void start(Minecraft mc, ClientLevel lvl) {
        level = lvl;
        dim = lvl.dimension().location().toString();
        queue.clear();
        familyCache.clear();
        batcher.reset();
        long now = System.currentTimeMillis();
        int rd = mc.options.renderDistance().get() + 2;
        BlockPos p = mc.player.blockPosition();
        for (int cx = (p.getX() >> 4) - rd; cx <= (p.getX() >> 4) + rd; cx++)
            for (int cz = (p.getZ() >> 4) - rd; cz <= (p.getZ() >> 4) + rd; cz++)
                if (lvl.getChunkSource().getChunk(cx, cz, false) != null) queue.loaded(cx, cz, now);
    }

    private void post(long now) {
        CompanionConfig c = config.get();
        List<ChunkBatcher.Item> batch = batcher.take(now);
        if (batch.isEmpty()) {
            posting.set(false);
            return;
        }
        poster.execute(() -> {
            String err;
            try {
                URI uri = c.apiUri("/api/chunks");
                if (uri == null) err = "no dashboard url";
                else err = ChunkScanner.verdict(http.postBytes(uri, c.key, ChunkScanner.gzip(ChunkScanner.body(batch)), true, 5000));
            } catch (Exception e) {
                err = "the dashboard is unreachable (" + e.getClass().getSimpleName() + ")";
            }
            try {
                batcher.done(batch, err, System.currentTimeMillis());
                if (err != null) {
                    lastError = err;
                    if (batcher.failures() <= 3 || batcher.failures() % 20 == 0) LOG.warn("[entropycompanion] chunk post: {} (retrying)", err);
                }
            } finally {
                posting.set(false);
            }
        });
    }

    // ---- the world as the scan sees it (the bot's SurfaceExport rule, copied) ----

    private ChunkColumns.Source source(ClientLevel level) {
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        int max = level.getMaxBuildHeight() - 1;
        return new ChunkColumns.Source() {
            public int kind(int x, int y, int z) { return kindOf(level, m.set(x, y, z)); }
            public int family(int x, int y, int z) { return familyOf(level.getBlockState(m.set(x, y, z))); }
            public int top(int x, int z) { return Math.min(level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z), max); }
        };
    }

    static int kindOf(ClientLevel level, BlockPos pos) {
        BlockState s = level.getBlockState(pos);
        if (s.isAir()) return ChunkColumns.AIR;
        Block b = s.getBlock();
        if (b instanceof net.minecraft.world.level.block.LiquidBlock) return ChunkColumns.GROUND;
        if (b instanceof net.minecraft.world.level.block.LeavesBlock || s.is(BlockTags.LEAVES)) return ChunkColumns.LEAVES;
        if (b instanceof net.minecraft.world.level.block.SnowLayerBlock) return ChunkColumns.PLANT;
        if (s.is(BlockTags.CROPS)) return ChunkColumns.GROUND;
        boolean noCollision = s.getCollisionShape(level, pos).isEmpty();
        if (noCollision && !s.getFluidState().isEmpty()) return ChunkColumns.GROUND;
        if (s.getRenderShape() == net.minecraft.world.level.block.RenderShape.INVISIBLE) return ChunkColumns.AIR;
        return noCollision ? ChunkColumns.PLANT : ChunkColumns.GROUND;
    }

    private int familyOf(BlockState s) {
        Block b = s.getBlock();
        Integer f = familyCache.get(b);
        if (f == null) {
            f = ChunkFamily.of(BuiltInRegistries.BLOCK.getKey(b).toString(), tag -> {
                TagKey<Block> k = TAGS.get(tag);
                return k != null && s.is(k);
            });
            familyCache.put(b, f);
        }
        return f;
    }

    private void columnError(int x, int z, Throwable t) {
        columnErrors++;
        long now = System.currentTimeMillis();
        if (now - lastColumnLogAt >= 60_000) {
            lastColumnLogAt = now;
            LOG.warn("[entropycompanion] chunk scan: column {} {} failed ({} so far): {}", x, z, columnErrors, t.toString());
        }
    }

    private void error(String where, Throwable t) {
        lastError = where + ": " + t;
        Companion c = Companion.INSTANCE;
        if (c != null) c.error("chunks " + where, t);
    }

    // ---- status ----

    String line() {
        CompanionConfig c = config.get();
        long now = System.currentTimeMillis();
        String state = !c.shareChunks ? "off" : !c.active() ? "on, but the url or key is missing" : "on";
        return "chunk sharing " + state + ": scanned " + scanned + " (" + queue.size() + " due, block changes " + blockChanges
                + (columnErrors > 0 ? ", column errors " + columnErrors : "") + "), " + batcher.line(now)
                + ("none".equals(lastError) ? "" : "; last error " + lastError);
    }
}
