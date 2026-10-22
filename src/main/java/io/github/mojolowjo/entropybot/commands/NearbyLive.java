package io.github.mojolowjo.entropybot.commands;

import baritone.api.IBaritone;
import baritone.api.BaritoneAPI;
import baritone.api.utils.BlockOptionalMetaLookup;
import io.github.mojolowjo.entropybot.aa.Nearby;
import io.github.mojolowjo.entropybot.aa.NeedChain.Spot;
import io.github.mojolowjo.entropybot.aa.brain.ItemWorth;
import io.github.mojolowjo.entropybot.aa.brain.MiningTiers;
import io.github.mojolowjo.entropybot.aa.brain.Tuning;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Block;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * simple-tools (0.39.w1): the live side of {@link Nearby}: Baritone's cached world scan (the one its mine process uses) for the
 * nearest tree log and the nearest diggable stone, the level for the leaves and the block id, the policy for what I may touch.
 * The fallbacks (AA's sightings, the loaded-block stone scan) are passed in by the caller. Results are reused for
 * {@code nearby.cacheMs} while the bot stays within {@code nearby.cacheMove} blocks. Client thread only.
 */
final class NearbyLive {
    private static final Logger LOG = LoggerFactory.getLogger("entropybot");

    private final Commands c;
    private List<Block> logBlocks, stoneBlocks;
    private long logAt = -1, stoneAt = -1;
    private int[] logMe, stoneMe;
    private Spot logSpot, stoneSpot;
    private String lastFrom = "none";

    NearbyLive(Commands c) { this.c = c; }

    /** Where the last answer came from: "baritone", "sightings" or "none" (for aa status / aa why). */
    String lastFrom() { return lastFrom; }

    /** The nearest tree log, Baritone first, else {@code fallback}. */
    Spot log(Supplier<Spot> fallback) {
        long now = System.currentTimeMillis();
        int[] me = here();
        if (me == null) return fallback == null ? null : fallback.get();
        if (logAt >= 0 && now - logAt < Tuning.l("nearby.cacheMs") && close(me, logMe)) return logSpot;
        logAt = now;
        logMe = me;
        boolean[] fell = {false};
        logSpot = Nearby.log(scan(), me, () -> { fell[0] = true; return fallback == null ? null : fallback.get(); });
        lastFrom = logSpot == null ? "none" : fell[0] ? "sightings" : "baritone";
        return logSpot;
    }

    /** The nearest diggable stone, Baritone first, else {@code fallback}. */
    Spot stone(Supplier<Spot> fallback) {
        long now = System.currentTimeMillis();
        int[] me = here();
        if (me == null) return fallback == null ? null : fallback.get();
        if (stoneAt >= 0 && now - stoneAt < Tuning.l("nearby.cacheMs") && close(me, stoneMe)) return stoneSpot;
        stoneAt = now;
        stoneMe = me;
        stoneSpot = Nearby.stone(scan(), me, fallback);
        return stoneSpot;
    }

    private static boolean close(int[] a, int[] b) {
        if (b == null) return false;
        double dx = a[0] - b[0], dy = a[1] - b[1], dz = a[2] - b[2];
        return dx * dx + dy * dy + dz * dz <= Tuning.get("nearby.cacheMove") * Tuning.get("nearby.cacheMove");
    }

    private static int[] here() {
        LocalPlayer p = Minecraft.getInstance().player;
        return p == null || Minecraft.getInstance().level == null ? null : Jobs.here(p);
    }

    private List<Block> logBlocks() {
        if (logBlocks == null) {
            List<Block> l = new ArrayList<>();
            for (Block b : BuiltInRegistries.BLOCK) if (b.defaultBlockState().is(BlockTags.LOGS)) l.add(b);
            logBlocks = l;
        }
        return logBlocks;
    }

    private List<Block> stoneBlocks() {
        if (stoneBlocks == null) {
            List<Block> l = new ArrayList<>();
            for (Block b : BuiltInRegistries.BLOCK) {
                String id = BuiltInRegistries.BLOCK.getKey(b).toString();
                if (ItemWorth.stoneLike(id) && MiningTiers.blockTierOf(id) >= 1) l.add(b);
            }
            stoneBlocks = l;
        }
        return stoneBlocks;
    }

    private List<int[]> scanFor(List<Block> blocks, int radius) {
        List<int[]> out = new ArrayList<>();
        try {
            IBaritone b = Jobs.baritone();
            if (b == null || blocks.isEmpty()) return out;
            var found = BaritoneAPI.getProvider().getWorldScanner().scanChunkRadius(b.getPlayerContext(), new BlockOptionalMetaLookup(blocks),
                    Tuning.i("nearby.scanMax"), Tuning.i("nearby.scanY"), radius);
            for (BlockPos q : found) out.add(new int[]{q.getX(), q.getY(), q.getZ()});
        } catch (RuntimeException | LinkageError e) {
            io.github.mojolowjo.entropybot.Quiet.warn("nearby: Baritone's world scan failed, the sightings answer instead", e);
        }
        return out;
    }

    private Nearby.Scan scan() {
        Minecraft mc = Minecraft.getInstance();
        String adim = Storage.dim();
        return new Nearby.Scan() {
            @Override public List<int[]> logs(int radius) { return scanFor(logBlocks(), radius); }
            @Override public List<int[]> stones(int radius) { return scanFor(stoneBlocks(), radius); }
            @Override public boolean leavesNear(int x, int y, int z, int reach) {
                BlockPos.MutableBlockPos q = new BlockPos.MutableBlockPos();
                for (int dx = -reach; dx <= reach; dx++) for (int dz = -reach; dz <= reach; dz++) for (int dy = 0; dy <= reach; dy++) {
                    q.set(x + dx, y + dy, z + dz);
                    if (mc.level.isLoaded(q) && mc.level.getBlockState(q).is(BlockTags.LEAVES)) return true;
                }
                return false;
            }
            @Override public boolean allowed(int x, int y, int z) { return c.workAllowed(adim, x, y, z); }
            @Override public String blockAt(int x, int y, int z) {
                BlockPos q = new BlockPos(x, y, z);
                return mc.level.isLoaded(q) ? BuiltInRegistries.BLOCK.getKey(mc.level.getBlockState(q).getBlock()).toString() : null;
            }
        };
    }
}
