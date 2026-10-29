package io.github.mojolowjo.entropybot.sense;

import com.mojang.logging.LogUtils;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * AA_ROUND3 5: the adapter of {@link Sightings} for trees and animals (ore stays in {@link SeenRuntime}, caves in {@link CaveRays}; the ray budget
 * is shared). Called from ChunkPass's tick.
 * <ul>
 * <li>0.39.z78 J6: no cave rays here any more (the dark-cell cave sightings are deleted): caves are known by the rays at
 *     the cave index, {@link CaveRays}.</li>
 * <li>Animals, every 20 ticks: the nearest {@link #ANIMAL_CANDIDATES} {@code Animal} entities (modded ones too) within
 *     32 in the view cone, one ray each to the eyes; an entity seen twice 2 s apart counts; the counted ones of a type
 *     are grouped within 16 into herds ({@link Sightings#sawHerd}). Log: "sense: seen cow x8 at x y z" (new or a
 *     changed count). Under a roof with no animal remembered: "sense: no animals seen yet (underground)" every 5 min.</li>
 * <li>Blocks walked under open sky (the explore chance's denominator), every 20 ticks.</li>
 * </ul>
 * Animal times are game time (ms = game ticks x 50: the decay is in-game). Loader notes: vanilla client API only
 * ({@code Level.clip}, {@code getBrightness}, {@code getHeight}, {@code getEntitiesOfClass}; Mojang names, the same on
 * both loaders). Errors: ChunkPass's wrapper counts and logs them.
 */
public final class SightingsRuntime {
    private static final Logger LOG = LogUtils.getLogger();
    static final int ANIMAL_CANDIDATES = 16; // structural
    static final int TREE_RAYS = 8; // structural
    static final long UNDERGROUND_LOG_MS = 5 * 60_000; // structural

    private long treeRays, trees, animalRays, herds, lastUnderLog = -UNDERGROUND_LOG_MS;
    private double lastX = Double.NaN, lastZ;
    private final Map<String, Integer> lastCount = new HashMap<>();
    /** Entity id -> last time it was seen after its confirmation. */
    private final Map<Integer, Long> confirmed = new HashMap<>();

    void tick(ClientLevel level, LocalPlayer p, Sightings sg, String dim, Seen.Budget budget, long tick, long now) {
        boolean roofed = roofed(level, p);
        if (tick % 10 == 3 && !roofed) trees(level, p, sg, dim, budget, now);
        if (tick % 20 == 11) {
            animals(level, p, sg, dim, budget, now, roofed);
            double x = p.getX(), z = p.getZ();
            if (!Double.isNaN(lastX) && !roofed) sg.walked(Math.hypot(x - lastX, z - lastZ));
            lastX = x;
            lastZ = z;
        }
    }

    /**
     * Under a roof: the bot's column's surface is 3+ above its eyes (in a cave, a mine or a house). 0.39.z76 surface: read
     * through the one surface answer (Jobs.surfaceNoLeaves; MOTION_BLOCKING_NO_LEAVES reads -64 on the client, so this was
     * never true); not known (rule 15): no roof is assumed - the rays run as they did.
     */
    static boolean roofed(ClientLevel level, LocalPlayer p) {
        BlockPos b = p.blockPosition();
        int s = io.github.mojolowjo.entropybot.commands.Jobs.surfaceNoLeaves(level, b.getX(), b.getZ());
        return io.github.mojolowjo.entropybot.world.SurfaceHeight.known(s) && s > p.getEyeY() + 3;
    }


    /**
     * aa-trees: up to {@link #TREE_RAYS} rays in the view cone (24 blocks, shared budget); a log hit is walked down to its
     * column's lowest log, the ground under it, the logs stacked and the leaves by the top are read, and
     * {@link Sightings#treeRule} + the confirm-twice rule decide. Log: "sense: seen tree at x y z (N logs)".
     */
    private void trees(ClientLevel level, LocalPlayer p, Sightings sg, String dim, Seen.Budget budget, long now) {
        Vec3 eye = p.getEyePosition();
        float yaw = p.getYRot(), pitch = p.getXRot();
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        for (int i = 0; i < TREE_RAYS; i++) {
            if (!budget.take(now)) return;
            treeRays++;
            float ry = yaw + (float) rnd.nextDouble(-50, 50), rp = Math.max(-60f, Math.min(60f, pitch + (float) rnd.nextDouble(-25, 15)));
            Vec3 to = eye.add(Vec3.directionFromRotation(rp, ry).scale(Seen.range()));
            BlockHitResult r = level.clip(new ClipContext(eye, to, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, p));
            if (r.getType() != HitResult.Type.BLOCK) continue;
            BlockPos hit = r.getBlockPos();
            if (!level.isLoaded(hit) || !level.getBlockState(hit).is(BlockTags.LOGS)) continue;
            BlockPos base = hit;
            for (int k = 0; k < 40 && level.getBlockState(base.below()).is(BlockTags.LOGS); k++) base = base.below();
            int logs = 0;
            BlockPos top = base;
            for (int k = 0; k < 40 && level.getBlockState(base.above(k)).is(BlockTags.LOGS); k++) { logs++; top = base.above(k); }
            boolean dirt = level.getBlockState(base.below()).is(BlockTags.DIRT);
            boolean leaves = false;
            for (int dx = -2; dx <= 2 && !leaves; dx++)
                for (int dz = -2; dz <= 2 && !leaves; dz++)
                    for (int dy = -1; dy <= 2 && !leaves; dy++)
                        leaves = level.getBlockState(top.offset(dx, dy, dz)).is(BlockTags.LEAVES);
            if (!Sightings.treeRule(dirt, logs, leaves, false)) continue;
            if (!sg.confirm(Sightings.key(Sightings.Kind.TREE, "tree", base.getX(), base.getY(), base.getZ()), now)) continue;
            // FV fix: the wood is read where it is seen (the lowest log's block), so the tree serves its own kind while its chunk is away
            String wood = BuiltInRegistries.BLOCK.getKey(level.getBlockState(base).getBlock()).toString();
            if (sg.sawTree(dim, base.getX(), base.getY(), base.getZ(), logs, now, wood) != null) {
                trees++;
                LOG.info("[entropybot] sense: seen tree at {} {} {} ({} logs, {})", base.getX(), base.getY(), base.getZ(), logs, wood);
            }
        }
    }

    private void animals(ClientLevel level, LocalPlayer p, Sightings sg, String dim, Seen.Budget budget, long now, boolean roofed) {
        Vec3 eye = p.getEyePosition(), look = p.getLookAngle();
        List<Animal> near = new ArrayList<>(level.getEntitiesOfClass(Animal.class, p.getBoundingBox().inflate(Sightings.animalRange())));
        near.sort((x, y) -> Double.compare(x.distanceToSqr(p), y.distanceToSqr(p)));
        long game = level.getGameTime() * 50;
        Map<String, List<int[]>> counted = new HashMap<>();
        if (confirmed.size() > 512) confirmed.values().removeIf(t -> now - t > Sightings.confirmMaxMs());
        int tried = 0;
        for (Animal e : near) {
            if (tried >= ANIMAL_CANDIDATES) break;
            if (!e.isAlive()) continue;
            Vec3 at = e.getEyePosition();
            if (!Sightings.inRange(Sightings.Kind.ANIMAL, eye.x, eye.y, eye.z, at.x, at.y, at.z)) continue;
            if (!Seen.inCone(eye.x, eye.y, eye.z, look.x, look.y, look.z, (int) Math.floor(at.x), (int) Math.floor(at.y), (int) Math.floor(at.z))) continue;
            tried++;
            if (!budget.take(now)) break;
            animalRays++;
            BlockHitResult r = level.clip(new ClipContext(eye, at, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, p));
            if (r.getType() == HitResult.Type.BLOCK) continue;           // something stands between
            String type = BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString();
            // confirm-twice per entity (the first glance waits; a sighting needs the same animal 2 s later); once
            // confirmed it counts on every pass it is in view, so a herd's count does not flicker
            Long was = confirmed.get(e.getId());
            if (was == null || now - was > Sightings.confirmMaxMs()) {
                if (!sg.confirm((3L << 60) ^ e.getId(), now)) continue;
            }
            confirmed.put(e.getId(), now);
            counted.computeIfAbsent(type, k -> new ArrayList<>()).add(new int[]{e.getBlockX(), e.getBlockY(), e.getBlockZ()});
        }
        for (Map.Entry<String, List<int[]>> t : counted.entrySet())
            for (int[] h : herdsOf(t.getValue())) {
                boolean[] fresh = new boolean[1];
                Sightings.Rec rec = sg.sawHerd(dim, t.getKey(), h[0], h[1], h[2], h[3], game, fresh);
                String k = t.getKey() + " " + (rec.x() >> 4) + " " + (rec.z() >> 4);
                Integer was = lastCount.put(k, h[3]);
                if (lastCount.size() > 256) lastCount.clear();
                if (fresh[0] || was == null || was != h[3]) {
                    herds++;
                    LOG.info("[entropybot] sense: seen {} x{} at {} {} {}", t.getKey().replaceFirst("^minecraft:", ""), h[3], h[0], h[1], h[2]);
                }
            }
        if (counted.isEmpty() && roofed && sg.animalCount(dim) == 0 && now - lastUnderLog >= UNDERGROUND_LOG_MS) {
            lastUnderLog = now;
            LOG.info("[entropybot] sense: no animals seen yet (underground: the food options cannot plan a hunt from memory)");
        }
    }

    /** Greedy grouping within {@link Sightings#MERGE}: {x, y, z of the first, count} per herd. */
    static List<int[]> herdsOf(List<int[]> cells) {
        List<int[]> out = new ArrayList<>();
        for (int[] c : cells) {
            int[] into = null;
            for (int[] h : out) {
                long dx = h[0] - c[0], dz = h[2] - c[2];
                if (dx * dx + dz * dz <= (long) Sightings.merge() * Sightings.merge()) { into = h; break; }
            }
            if (into != null) into[3]++;
            else out.add(new int[]{c[0], c[1], c[2], 1});
        }
        return out;
    }

    String status() { return treeRays + " tree rays, " + trees + " trees, " + animalRays + " animal rays, " + herds + " herd notes"; }
}
