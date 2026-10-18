package io.github.mojolowjo.entropybot.sense;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.Settings;
import baritone.api.cache.ICachedWorld;
import baritone.api.cache.IWorldData;
import com.mojang.logging.LogUtils;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * 0.34.0: block spots from Baritone - its live scanner (loaded chunks) and its chunk cache (our Baritone build also
 * remembers logs and ores, setting cacheLogsAndOres). Loader notes: Baritone API only (loader-neutral) plus vanilla
 * Level/registry reads. Every call catches its own errors and logs them (never a silent failure); a missing API or an
 * official Baritone build simply answers nothing from memory.
 */
public final class BaritoneMemory {
    private static final Logger LOG = LogUtils.getLogger();
    /** Regions (512 blocks) around the bot, squared: 4 = two regions out, ~1000 blocks. */
    public static final int REGIONS_SQ = 2;            // 0.34.1: the bot's region and its 8 neighbours
    /** 0.34.1: spots asked of the cache per block id. */
    public static final int PER_ID = 48;
    private static boolean warned;

    private BaritoneMemory() {}

    private static IBaritone baritone() {
        try {
            return BaritoneAPI.getProvider().getPrimaryBaritone();
        } catch (Throwable t) {
            return null;
        }
    }

    /** True when the installed Baritone build remembers logs and ores (ours, with the setting on). */
    public static boolean tracksLogsAndOres() {
        try {
            Settings.Setting<?> s = BaritoneAPI.getSettings().byLowerName.get("cachelogsandores");
            // 0.34.1: only the build with capped, non-creating cache reads (it has the per-chunk cap setting)
            if (s == null || BaritoneAPI.getSettings().byLowerName.get("cachelogsandoresperchunk") == null) {
                if (!warned) {
                    warned = true;
                    LOG.info("[entropybot] memory: this Baritone build doesn't remember logs or ores (no cacheLogsAndOres) - only the mod's index");
                }
                return false;
            }
            return Boolean.TRUE.equals(s.value);
        } catch (Throwable t) {
            LOG.warn("[entropybot] memory: can't read Baritone's settings: {}", t.toString());
            return false;
        }
    }

    private static List<Block> blocks(Collection<String> ids) {
        List<Block> out = new ArrayList<>();
        for (String id : ids) {
            ResourceLocation rl = ResourceLocation.tryParse(id.contains(":") ? id : "minecraft:" + id);
            if (rl == null) continue;
            BuiltInRegistries.BLOCK.getOptional(rl).ifPresent(out::add);
        }
        return out;
    }

    /** Baritone's live scanner over loaded chunks (radius in chunks). */
    public static List<MemoryPick.Spot> live(LocalPlayer p, Collection<String> ids, int max, int chunks) {
        List<MemoryPick.Spot> out = new ArrayList<>();
        IBaritone b = baritone();
        if (b == null || ids.isEmpty()) return out;
        try {
            List<Block> bl = blocks(ids);
            if (bl.isEmpty()) return out;
            for (BlockPos q : BaritoneAPI.getProvider().getWorldScanner().scanChunkRadius(b.getPlayerContext(), bl, max, 10, chunks)) {
                String id = BuiltInRegistries.BLOCK.getKey(p.level().getBlockState(q).getBlock()).toString();
                out.add(new MemoryPick.Spot(id, q.getX(), q.getY(), q.getZ(), MemoryPick.Source.LIVE));
            }
        } catch (Throwable t) {
            LOG.warn("[entropybot] memory: Baritone's scanner failed: {}", t.toString());
        }
        return out;
    }

    /** Spots Baritone's chunk cache remembers (only tracked blocks answer), unchecked. */
    public static List<MemoryPick.Spot> remembered(LocalPlayer p, Collection<String> ids, int maxEach) {
        List<MemoryPick.Spot> out = new ArrayList<>();
        IBaritone b = baritone();
        if (b == null) return out;
        try {
            IWorldData wd = b.getWorldProvider().getCurrentWorld();
            if (wd == null) return out;
            ICachedWorld cw = wd.getCachedWorld();
            int x = p.getBlockX(), z = p.getBlockZ();
            for (String id : ids) {
                String full = id.contains(":") ? id : "minecraft:" + id;
                for (BlockPos q : cw.getLocationsOf(MemoryPick.cacheName(full), maxEach, x, z, REGIONS_SQ))
                    out.add(new MemoryPick.Spot(full, q.getX(), q.getY(), q.getZ(), MemoryPick.Source.BARITONE));
            }
        } catch (Throwable t) {
            LOG.warn("[entropybot] memory: Baritone's cache failed: {}", t.toString());
        }
        return out;
    }

    /** The live check of a remembered spot: loaded and the same block / loaded and gone / not loaded. */
    public static MemoryPick.Check check(Level level) {
        return (id, x, y, z) -> {
            BlockPos q = new BlockPos(x, y, z);
            if (!level.isLoaded(q)) return MemoryPick.Live.UNLOADED;
            String now = BuiltInRegistries.BLOCK.getKey(level.getBlockState(q).getBlock()).toString();
            return now.equals(id) ? MemoryPick.Live.LOADED_SAME : MemoryPick.Live.LOADED_GONE;
        };
    }

    /** Remembered spots, nearest first, filtered, each loaded one checked; stale ones dropped with a log line. */
    public static List<MemoryPick.Spot> recall(LocalPlayer p, Collection<String> ids, MemoryPick.Filter filter, int max, String why) {
        return recall(p, ids, filter, max, why, Integer.MAX_VALUE);
    }

    /**
     * 0.34.1: as above, only spots within maxDist blocks (horizontal). The cache answers at most {@link #PER_ID} spots an
     * id, nearest chunks first (our Baritone build caps the read and never creates a region), so this stays cheap on the
     * game thread; only counts are logged, plus one line per stale spot (at most 3).
     */
    public static List<MemoryPick.Spot> recall(LocalPlayer p, Collection<String> ids, MemoryPick.Filter filter, int max, String why, int maxDist) {
        if (!tracksLogsAndOres()) return List.of();
        List<MemoryPick.Spot> all = remembered(p, ids, PER_ID);
        int[] me = {p.getBlockX(), p.getBlockY(), p.getBlockZ()};
        long cap = (long) maxDist * maxDist;
        MemoryPick.Filter f = (x, y, z) -> {
            long dx = x - me[0], dz = z - me[2];
            return dx * dx + dz * dz <= cap && (filter == null || filter.ok(x, y, z));
        };
        MemoryPick.Result r = MemoryPick.pick(all, me, f, check(p.level()), max);
        int shown = 0;
        for (MemoryPick.Spot s : r.dropped())
            if (shown++ < 3) LOG.info("[entropybot] memory: {}: Baritone remembered {} at {}, it's gone now (chunk loaded) - dropped", why, s.id(), s.at());
        LOG.info("[entropybot] memory: {}: {} spots remembered, {} kept, {} stale", why, all.size(), r.kept().size(), r.dropped().size());
        return r.kept();
    }
}
