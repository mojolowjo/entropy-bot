package io.github.mojolowjo.entropybot.sense;

import com.mojang.logging.LogUtils;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

import java.util.List;

/**
 * 0.39.z78 J6 (the owner's ruling 2026-10-27 late): the sight rays at the cave index, as {@link SeenRuntime}'s at ore.
 * Every other tick the index's cave cells within {@link Seen#range} of the eyes, in the view cone, not known and not
 * blocked lately, get one ray each from the eyes to the cell's centre while the shared budget lasts. The cells the ray
 * passed before the block that stopped it ({@link CaveIndex#path}) credit the volume whose air it went through
 * ({@link CaveIndex#creditRay}: the last cave cell on it); a ray stopped by rock in front of the air credits nothing (rule
 * 17); at {@link CaveIndex#KNOWN_RAYS} credits it is a known cave in Sightings ("sense: cave known
 * at ..."). Run EE: a ray that ends on a block beside a cave's opening cell is a RIM credit ({@link CaveIndex#rimCredit}:
 * the opening an explore target, "sense: cave opening seen"). A ray ending in a chunk the index has not read is said once per chunk (rule 7); a ray stopped by rock is
 * tried again after {@link SeenRuntime#RETRY_MS}. Loader notes: vanilla client only ({@code Level.clip},
 * {@code ClipContext}; Mojang names), called from ChunkPass's tick. Errors: the caller's wrapper counts and logs them.
 */
final class CaveRays {
    private static final Logger LOG = LogUtils.getLogger();
    /** Targets gathered per pass (the nearest first). */
    static final int CANDIDATES = 24; // structural: as SeenRuntime's per-pass list, half of it (one ray a run)

    private final java.util.Map<Long, Long> blockedUntil = new java.util.HashMap<>();
    private final java.util.Set<Long> edgeSaid = new java.util.HashSet<>();
    private long rays, credits, rims, known;

    void clear() {
        blockedUntil.clear();
        edgeSaid.clear();
    }

    void tick(ClientLevel level, LocalPlayer p, CaveIndex index, Sightings sg, String dim, Seen.Budget budget, long tick, long now) {
        if (tick % 2 != 1) return;                             // SeenRuntime takes the even ticks
        if (blockedUntil.size() > 4096) blockedUntil.values().removeIf(t -> t <= now);   // structural: as SeenRuntime
        Vec3 eye = p.getEyePosition();
        Vec3 look = p.getLookAngle();
        double ex = eye.x, ey = eye.y, ez = eye.z;
        List<int[]> cand = index.targets((int) Math.floor(ex), (int) Math.floor(ey), (int) Math.floor(ez), Seen.range(), CANDIDATES,
                (x, y, z) -> blockedUntil.getOrDefault(Seen.key(x, y, z), 0L) <= now && Seen.inRange(ex, ey, ez, x, y, z) && Seen.inCone(ex, ey, ez, look.x, look.y, look.z, x, y, z));
        for (int[] c : cand) {
            if (!level.isLoaded(new BlockPos(c[0], c[1], c[2]))) continue;
            if (!budget.take(now)) return;
            rays++;
            Vec3 to = new Vec3(c[0] + 0.5, c[1] + 0.5, c[2] + 0.5);
            BlockHitResult r = level.clip(new ClipContext(eye, to, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, p));
            int hx = c[0], hy = c[1], hz = c[2];
            List<int[]> path;
            if (r.getType() == HitResult.Type.BLOCK) {
                hx = r.getBlockPos().getX(); hy = r.getBlockPos().getY(); hz = r.getBlockPos().getZ();
                Vec3 at = r.getLocation();
                path = new java.util.ArrayList<>(CaveIndex.path(ex, ey, ez, at.x, at.y, at.z));
                final int bx = hx, by = hy, bz = hz;
                path.removeIf(q -> q[0] == bx && q[1] == by && q[2] == bz);   // the block that stopped it was not seen through
            } else path = CaveIndex.path(ex, ey, ez, to.x, to.y, to.z);
            CaveIndex.Result res = index.creditRay(path);
            if (r.getType() == HitResult.Type.BLOCK) {
                // run EE (the manager's ruling): the end block beside a cave's opening cell - the lip of the hole seen; an
                // explore target, never a known cave
                CaveIndex.Result rim = index.rimCredit(hx, hy, hz);
                if (rim.what() == CaveIndex.Credit.RIM) {
                    rims++;
                    LOG.info("[entropybot] sense: cave opening seen (its lip {} {} {}): a target for the explore, not known", hx, hy, hz);
                    if (res.what() == CaveIndex.Credit.NO_VOLUME || res.what() == CaveIndex.Credit.DIP) continue;
                }
            }
            switch (res.what()) {
                case NO_VOLUME -> {
                    if (!index.has(hx >> 4, hz >> 4) && edgeSaid.add(CaveIndex.chunkKey(hx >> 4, hz >> 4)))
                        LOG.info("[entropybot] sense: a cave ray ended at {} {} {} in chunk {} {}, which the cave index has not read: no volume to credit (said once a chunk)",
                                hx, hy, hz, hx >> 4, hz >> 4);
                    blockedUntil.put(Seen.key(c[0], c[1], c[2]), now + SeenRuntime.RETRY_MS);
                }
                case DIP -> blockedUntil.put(Seen.key(c[0], c[1], c[2]), now + SeenRuntime.RETRY_MS);   // run ED: only a dip's air on the way
                case CREDITED -> credits++;
                case ALREADY_KNOWN, RIM -> { }
                case KNOWN -> {
                    credits++;
                    CaveIndex.Known k = res.known();
                    if (sg.knowCave(dim, k, now) != null) {
                        known++;
                        LOG.info("[entropybot] sense: cave known at {} {} {} (size {}, depth {}; {} rays credited it)", k.x(), k.y(), k.z(), k.size(), k.depth(), k.hits().size());
                    }
                }
            }
        }
    }

    String status() { return rays + " cave rays, " + rims + " rim credits, " + credits + " air credits, " + known + " caves known"; }
}
