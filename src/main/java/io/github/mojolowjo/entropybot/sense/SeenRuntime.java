package io.github.mojolowjo.entropybot.sense;

import com.mojang.logging.LogUtils;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

import java.util.List;

/**
 * AA_ROUND3 3: the adapter of {@link Seen}. Every other tick (from {@link ChunkPass#tick}) the indexed ore cells within
 * {@link Seen#RANGE} of the eyes that are not seen yet, in the view cone and with an open face (air or water) turned to
 * the eyes get one ray each from the eyes to that face ({@code level.clip}, outline shapes, fluids ignored) while the
 * budget ({@link Seen#RAYS_PER_SEC}) lasts; a ray that ends on the cell marks it seen ("sense: seen iron at x y z").
 * Loader notes: vanilla client only ({@code Level.clip}, {@code ClipContext}; Mojang names, the same on both loaders),
 * called from ChunkPass's tick. Errors: the caller's wrapper counts and logs them (never throws out of the tick).
 */
final class SeenRuntime {
    private static final Logger LOG = LogUtils.getLogger();
    /** Candidates gathered per pass (the nearest first). */
    static final int CANDIDATES = 48;

    /** The ray budget, shared with {@link SightingsRuntime} (AA_ROUND3 5: one budget for every kind). */
    final Seen.Budget budget = new Seen.Budget();
    private long rays, hits, logAt, logged;
    /** review: a cell whose ray was blocked waits {@link #RETRY_MS} (else the nearest blocked cells take every ray). */
    static final long RETRY_MS = 2000;
    private final java.util.Map<Long, Long> blockedUntil = new java.util.HashMap<>();

    void tick(ClientLevel level, LocalPlayer p, OreIndex index, Seen seen, String dim, long tick, long now) {
        if (tick % 2 != 0) return;
        if (blockedUntil.size() > 4096) blockedUntil.values().removeIf(t -> t <= now);
        Vec3 eye = p.getEyePosition();
        Vec3 look = p.getLookAngle();
        double ex = eye.x, ey = eye.y, ez = eye.z;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        List<OreIndex.Hit> cand = index.nearest(dim, id -> true, (int) Math.floor(ex), (int) Math.floor(ey), (int) Math.floor(ez), Seen.RANGE, CANDIDATES,
                (x, y, z) -> blockedUntil.getOrDefault(Seen.key(x, y, z), 0L) <= now && !seen.has(dim, x, y, z) && Seen.inRange(ex, ey, ez, x, y, z) && Seen.inCone(ex, ey, ez, look.x, look.y, look.z, x, y, z));
        for (OreIndex.Hit h : cand) {
            m.set(h.x(), h.y(), h.z());
            if (!level.isLoaded(m)) continue;
            boolean[] open = new boolean[6];
            for (int i = 0; i < 6; i++) {
                int[] f = Seen.FACES[i];
                BlockPos n = new BlockPos(h.x() + f[0], h.y() + f[1], h.z() + f[2]);
                var st = level.getBlockState(n);
                open[i] = st.isAir() || st.getFluidState().is(FluidTags.WATER);
            }
            int face = Seen.faceToward(ex, ey, ez, h.x(), h.y(), h.z(), open);
            if (face < 0) continue;
            if (!budget.take(now)) return;
            rays++;
            int[] f = Seen.FACES[face];
            // aim just inside the open face's centre: the ray ends on this cell when nothing stands between
            Vec3 to = new Vec3(h.x() + 0.5 + f[0] * 0.45, h.y() + 0.5 + f[1] * 0.45, h.z() + 0.5 + f[2] * 0.45);
            BlockHitResult r = level.clip(new ClipContext(eye, to, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, p));
            if (r.getType() != HitResult.Type.BLOCK || !r.getBlockPos().equals(new BlockPos(h.x(), h.y(), h.z()))) {
                blockedUntil.put(Seen.key(h.x(), h.y(), h.z()), now + RETRY_MS);
                continue;
            }
            hits++;
            if (seen.saw(dim, h.x(), h.y(), h.z(), h.family(), now)) {
                if (now - logAt > 1000) { logAt = now; logged = 0; }
                if (logged++ < 4) LOG.info("[entropybot] sense: seen {} at {} {} {}", h.family(), h.x(), h.y(), h.z());
            }
        }
    }

    String status() { return rays + " rays, " + hits + " reached"; }
}
