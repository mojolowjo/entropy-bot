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
import net.minecraft.world.level.levelgen.Heightmap;
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
 * AA_ROUND3 5: the adapter of {@link Sightings} for caves and animals (ore stays in {@link SeenRuntime}; the ray budget
 * is shared). Called from ChunkPass's tick.
 * <ul>
 * <li>Caves, every 10 ticks while the bot is not under a roof: up to {@link #CAVE_RAYS} rays in the view cone (24
 *     blocks, outline shapes); the air cell before each hit is tested by {@link Sightings#caveRule} (sky/block light,
 *     the surface heightmap, the ground beside it, and PolicyRuntime's own-dug cells) and must be seen twice 2 s apart
 *     ({@link Sightings#confirm}). Log: "sense: seen cave at x y z (dark|ravine)".</li>
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
    /** The pitfall cap (AA_ROUND3 5 (1)): at most this many cave rays a pass. */
    static final int CAVE_RAYS = 16; // structural
    static final int ANIMAL_CANDIDATES = 16; // structural
    static final int TREE_RAYS = 8; // structural
    static final long UNDERGROUND_LOG_MS = 5 * 60_000; // structural

    private long treeRays, trees, caveRays, caves, animalRays, herds, lastUnderLog = -UNDERGROUND_LOG_MS;
    private double lastX = Double.NaN, lastZ;
    private final Map<String, Integer> lastCount = new HashMap<>();
    /** Entity id -> last time it was seen after its confirmation. */
    private final Map<Integer, Long> confirmed = new HashMap<>();

    void tick(ClientLevel level, LocalPlayer p, Sightings sg, String dim, Seen.Budget budget, CaveMouths cand, long tick, long now) {
        boolean roofed = roofed(level, p);
        if (tick % 10 == 7 && !roofed) mouths(level, p, sg, dim, budget, cand, now);
        if (tick % 10 == 7 && !roofed) caves(level, p, sg, dim, budget, now);
        if (tick % 20 == 13) mouthGone(level, p, sg, dim);
        if (tick % 10 == 3 && !roofed) trees(level, p, sg, dim, budget, now);
        if (tick % 20 == 11) {
            animals(level, p, sg, dim, budget, now, roofed);
            double x = p.getX(), z = p.getZ();
            if (!Double.isNaN(lastX) && !roofed) sg.walked(Math.hypot(x - lastX, z - lastZ));
            lastX = x;
            lastZ = z;
        }
    }

    /** Under a roof: the bot's column's surface is 3+ above its eyes (in a cave, a mine or a house). */
    static boolean roofed(ClientLevel level, LocalPlayer p) {
        BlockPos b = p.blockPosition();
        return level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, b.getX(), b.getZ()) > p.getEyeY() + 3;
    }

    private long mouthRays, mouthsSeen, mouthsGone;

    /** 0.39.z46: the last verdict per cave-mouth candidate (confirmed / rejected and why), newest last; aa debug lists them. */
    private static final Map<Long, String> VERDICTS = new java.util.LinkedHashMap<>(16, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<Long, String> e) { return size() > Seen.range(); }   // bounded: one line per candidate in range at most
    };

    private static synchronized void verdict(long key, String line) { VERDICTS.put(key, line); }

    /** 0.39.z52 (aa cave list): why each dropped candidate was dropped, by its key (capped like the dropped set). */
    private static final Map<Long, String> DROP_REASONS = new java.util.HashMap<>();

    private static synchronized void dropReason(long key, String why) {
        if (DROP_REASONS.size() > 4096) DROP_REASONS.clear();     // structural: memory cap, the same as the dropped set
        DROP_REASONS.put(key, why);
    }

    /** The reason the candidate at x y z was dropped this session; null = not dropped. */
    public static synchronized String dropReason(int x, int y, int z) { return DROP_REASONS.get(Seen.key(x, y, z)); }

    /** aa debug: one line per candidate the rays looked at (its last verdict). */
    public static synchronized List<String> verdicts() { return new java.util.ArrayList<>(VERDICTS.values()); }
    /** 0.39.z46: candidates a ray showed are not caves (dropped, said once). */
    private final java.util.Set<Long> dropped = new java.util.HashSet<>();

    /**
     * 0.39.z46 (owner 2026-10-24): the cave candidates (ChunkPass: the heightmap's shape, {@link CaveMouths#candidates})
     * within {@code cave.confirmM} and the view cone get up to {@code cave.mouthRays} rays a pass from the shared Seen
     * budget; none at farther ones. A ray that reaches the low point reads the air below and beside it: one under its
     * column's surface with sky light at most {@code cave.maxSky} = a cave mouth ("sense: seen cave mouth ..."); none = not
     * a cave, the candidate is dropped and said once. A ray stopped short tries again on a later pass.
     */
    private void mouths(ClientLevel level, LocalPlayer p, Sightings sg, String dim, Seen.Budget budget, CaveMouths cand, long now) {
        if (cand == null) return;
        Vec3 eye = p.getEyePosition(), look = p.getLookAngle();
        if (dropped.size() > 4096) dropped.clear();                  // structural: memory cap
        int shots = 0, max = io.github.mojolowjo.entropybot.aa.brain.Tuning.i("cave.mouthRays");
        List<Sightings.Rec> known = sg.mouths(dim, (int) eye.x, (int) eye.y, (int) eye.z);
        for (CaveMouths.Candidate c : cand.toConfirm(eye.x, eye.y, eye.z)) {   // z46: no rays at far candidates
            if (shots >= max) return;
            long k = Seen.key(c.x(), c.y(), c.z());
            if (dropped.contains(k)) continue;
            if (!Seen.inCone(eye.x, eye.y, eye.z, look.x, look.y, look.z, c.x(), c.y(), c.z())) continue;
            boolean have = false;
            for (Sightings.Rec r : known) {
                long dx = r.x() - c.x(), dz = r.z() - c.z();
                if (dx * dx + dz * dz <= (long) Sightings.merge() * Sightings.merge()) { have = true; break; }
            }
            if (have) continue;
            // 0.39.z51 item 2: a mouth confirms once - its stored confirmation (merged within cave.mergeM) is read, never
            // re-run (run L: 101 "confirmed" lines for one mouth once its sighting was marked exhausted)
            if (sg.mouthFact(dim, c.x(), c.y(), c.z()) != null) continue;
            if (!budget.take(now)) return;
            shots++;
            mouthRays++;
            // 0.39.z46 (owner 2026-10-24): one ray at the low point; then sky light 0 in the air beside / below it = a mouth
            BlockPos target = new BlockPos(c.x(), c.y(), c.z());
            Vec3 tc = target.getCenter(), dir = tc.subtract(eye);
            if (dir.lengthSqr() < 1e-6) continue;
            Vec3 to = eye.add(dir.normalize().scale(CaveMouths.confirmM()));
            BlockHitResult r = level.clip(new ClipContext(eye, to, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, p));
            boolean reached = r.getType() != HitResult.Type.BLOCK || eye.distanceTo(r.getLocation()) >= eye.distanceTo(tc) - 0.87;   // structural: half a cell's diagonal: the hit lies on the target cell or beyond
            String where = CaveMouths.where(c);
            if (!reached) { verdict(k, where + ": candidate, not yet - the ray stopped at " + r.getBlockPos().toShortString() + " before the low point"); continue; }
            // 0.39.z50 (S69): the hole walk DESCENDS: the lowest top in the cliff ring is the floor column, walked down from
            // the rim through air to its first non-air cell (the hole's own air column, rule 17's exception); then the
            // near-only pocket fill over the hole's own air from that floor cell (owner: "require a pocket, not a cell")
            CavePatterns pat = ChunkPass.INSTANCE.patterns();
            CavePatterns.Hole h = CavePatterns.walk(c.x(), c.z(), (x, z) -> top(level, x, z), cells(level));
            int[] ws = patch(level, c.x(), c.z());
            CavePatterns.Vote v = pat.vote(ws, h.depth(), h.pocket(), h.darkSky(), true, null);
            String no = CavePatterns.verdict(h, pat.rule(), v);
            String hole = h.numbers() + "; " + v.line();
            if (no != null) {
                dropped.add(k);
                cand.refuse(c.x(), c.y(), c.z());
                String line = where + ": dropped - not a cave (" + no + "; " + hole + ")";
                verdict(k, line);
                LOG.info("[entropybot] sense: cave candidate {}", line);   // said once: the candidate is dropped
                // z50 (S71): the bot's own drop is a labelled patch (weighted cave.botLabelWeight)
                ChunkPass.INSTANCE.label(new CavePatterns.Patch(c.x(), c.y(), c.z(), h.water() ? "water" : "cliff", "bot", h.depth(), h.pocket(), h.darkSky(), ws, null));
                continue;
            }
            int d = (int) Math.round(Math.sqrt(eye.distanceToSqr(new Vec3(c.x() + 0.5, c.y() + 0.5, c.z() + 0.5))));
            // 0.39.z51 items 1-2: the confirmation is a stored fact on the mouth (tick, floor cell, the dark pocket's cells)
            List<Long> pocket = CavePatterns.pocketCells(h.fx(), h.floorY() + 1, h.fz(), h.rim(), cells(level));
            if (sg.confirmMouth(dim, c.x(), c.y(), c.z(), level.getGameTime(), h.fx(), h.floorY() + 1, h.fz(), pocket) == null) continue;   // merged into a stored one
            String ok = where + ": confirmed - a dark pocket under the surface at " + h.fx() + " " + (h.floorY() + 1) + " " + h.fz() + ", from " + d + " m ("
                    + hole + "; " + pocket.size() + " pocket cells stored)";
            verdict(k, ok);
            LOG.info("[entropybot] sense: cave candidate {}", ok);
            ChunkPass.INSTANCE.label(new CavePatterns.Patch(c.x(), c.y(), c.z(), "cave", "bot", h.depth(), h.pocket(), h.darkSky(), ws, null));
            if (sg.sawMouth(dim, c.x(), c.y(), c.z(), d, now) != null) {
                mouthsSeen++;
                known = sg.mouths(dim, (int) eye.x, (int) eye.y, (int) eye.z);
                LOG.info("[entropybot] sense: seen cave mouth at {} {} {} from {} m ({})", c.x(), c.y(), c.z(), d, c.shape());
            }
        }
    }

    /** 0.39.z50: the WORLD_SURFACE top block Y of a column (Level.getHeight gives the first free Y). */
    static int top(ClientLevel level, int x, int z) { return level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1; }

    /**
     * 0.39.z50: the 9 x 9 WORLD_SURFACE tops round x z, relative to the centre (the heightmap only, rule 17). A column of a
     * chunk not loaded reads as the centre's height (no difference; the client has no height there).
     */
    public static int[] patch(ClientLevel level, int x, int z) {
        int r = CavePatterns.R, n = CavePatterns.SIDE, c = top(level, x, z);
        int[] out = new int[n * n];
        for (int dz = -r; dz <= r; dz++)
            for (int dx = -r; dx <= r; dx++)
                out[(dz + r) * n + dx + r] = level.hasChunk((x + dx) >> 4, (z + dz) >> 4) ? top(level, x + dx, z + dz) - c : 0;
        return out;
    }

    /** 0.39.z50: the hole walk's and the pocket fill's cells from the level (an unloaded cell is not air: the walk stops). */
    public static CavePatterns.Cells cells(ClientLevel level) {
        return new CavePatterns.Cells() {
            public boolean air(int x, int y, int z) {
                BlockPos q = new BlockPos(x, y, z);
                return level.isLoaded(q) && level.getBlockState(q).isAir();
            }
            public boolean water(int x, int y, int z) {
                BlockPos q = new BlockPos(x, y, z);
                return level.isLoaded(q) && level.getBlockState(q).getFluidState().is(net.minecraft.tags.FluidTags.WATER);
            }
            public int sky(int x, int y, int z) { return level.getBrightness(LightLayer.SKY, new BlockPos(x, y, z)); }
        };
    }
    /** 0.39.z44 (rule 15, gone): standing by a known mouth whose cell is not air (filled, built over): it is forgotten. */
    private void mouthGone(ClientLevel level, LocalPlayer p, Sightings sg, String dim) {
        BlockPos b = p.blockPosition();
        for (Sightings.Rec r : sg.mouths(dim, b.getX(), b.getY(), b.getZ())) {
            long dx = r.x() - b.getX(), dy = r.y() - b.getY(), dz = r.z() - b.getZ();
            double g = io.github.mojolowjo.entropybot.aa.brain.Tuning.get("cave.goneReach");
            if (dx * dx + dy * dy + dz * dz > g * g) break;          // nearest first: the rest are farther
            BlockPos at = new BlockPos(r.x(), r.y(), r.z());
            if (!level.isLoaded(at)) continue;
            if (CaveMouths.state(true, true, level.getBlockState(at).isAir()) == CaveMouths.State.GONE && sg.forgetMouth(dim, r.x(), r.y(), r.z())) {
                mouthsGone++;
                LOG.info("[entropybot] sense: cave mouth at {} {} {} gone (the cell is {} now)", r.x(), r.y(), r.z(), level.getBlockState(at).getBlock().getName().getString());
            }
        }
    }

    private void caves(ClientLevel level, LocalPlayer p, Sightings sg, String dim, Seen.Budget budget, long now) {
        Vec3 eye = p.getEyePosition();
        float yaw = p.getYRot(), pitch = p.getXRot();
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        String pdim = level.dimension().location().toString();
        var pr = io.github.mojolowjo.entropybot.guard.PolicyRuntime.INSTANCE;
        for (int i = 0; i < CAVE_RAYS; i++) {
            if (!budget.take(now)) return;
            caveRays++;
            // a direction in the cone: yaw +-50, pitch from the look down to 60 below it (holes lie below the eyes)
            float ry = yaw + (float) rnd.nextDouble(-50, 50), rp = Math.max(-30f, Math.min(89f, pitch + (float) rnd.nextDouble(-20, 60)));
            Vec3 dir = Vec3.directionFromRotation(rp, ry);
            Vec3 to = eye.add(dir.scale(Seen.range()));
            BlockHitResult r = level.clip(new ClipContext(eye, to, ClipContext.Block.OUTLINE, ClipContext.Fluid.ANY, p));
            if (r.getType() != HitResult.Type.BLOCK) continue;
            BlockPos a = r.getBlockPos().relative(r.getDirection());
            if (!level.isLoaded(a) || eye.distanceToSqr(a.getCenter()) < 16) continue;
            boolean air2 = level.getBlockState(a).isAir() && level.getBlockState(a.above()).isAir();
            if (!air2) continue;
            int surf = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, a.getX(), a.getZ());
            int[] side = null;
            if (a.getY() >= surf - 1) {
                side = new int[]{Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
                for (int d = 1; d <= 5; d++) {
                    side[0] = Math.max(side[0], level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, a.getX() - d, a.getZ()));
                    side[1] = Math.max(side[1], level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, a.getX() + d, a.getZ()));
                    side[2] = Math.max(side[2], level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, a.getX(), a.getZ() - d));
                    side[3] = Math.max(side[3], level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, a.getX(), a.getZ() + d));
                }
            }
            boolean own = pr.dug(pdim, a.getX(), a.getY(), a.getZ()) || pr.dug(pdim, a.getX(), a.getY() + 1, a.getZ())
                    || pr.dug(pdim, a.getX(), a.getY() - 1, a.getZ());
            String kind = Sightings.caveRule(true, level.getBrightness(LightLayer.SKY, a), level.getBrightness(LightLayer.BLOCK, a), a.getY(), surf, side, own);
            if (kind == null) continue;
            if (!sg.confirm(Sightings.key(Sightings.Kind.CAVE, kind, a.getX(), a.getY(), a.getZ()), now)) continue;
            Sightings.Rec c = sg.sawCave(dim, a.getX(), a.getY(), a.getZ(), now);
            if (c != null) {
                caves++;
                LOG.info("[entropybot] sense: seen cave at {} {} {} ({})", a.getX(), a.getY(), a.getZ(), kind);
            }
        }
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
            if (sg.sawTree(dim, base.getX(), base.getY(), base.getZ(), logs, now) != null) {
                trees++;
                LOG.info("[entropybot] sense: seen tree at {} {} {} ({} logs)", base.getX(), base.getY(), base.getZ(), logs);
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

    String status() { return mouthRays + " mouth rays, " + mouthsSeen + " mouths seen, " + mouthsGone + " gone, " + caveRays + " cave rays, " + caves + " caves, " + treeRays + " tree rays, " + trees + " trees, " + animalRays + " animal rays, " + herds + " herd notes"; }
}
