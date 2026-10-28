package io.github.mojolowjo.entropybot.commands;

import io.github.mojolowjo.entropybot.sense.CaveIndex;
import io.github.mojolowjo.entropybot.sense.CaveLabels;
import io.github.mojolowjo.entropybot.sense.ChunkPass;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * 0.39.z78 J6: {@code aa cave list} - the caves known by rays, the volumes the rays credited so far and the index's truth
 * near the bot (sense.CaveList). {@code aa cave yes|no} (the owner 2026-10-27 late: "I KNOW if it's a cave; get the
 * information of what caves start like and replicate"): labels the index volume whose air the crosshair's ray passed
 * last, or one beside the block it hit, or else the opening nearest the eyes within reach (CaveIndex.labelSpot) - with
 * its measurements at label time into cave-labels.json (CaveLabels); the floor is derived again from the labels.
 * {@code aa cave check}: the floor's classification against the labels, then the labelled caves' credits this session.
 * Loader notes: vanilla client API only ({@code Minecraft.player}, {@code Level.clip}, {@code ClipContext},
 * {@code Minecraft.getSingleplayerServer().getWorldData().worldGenOptions().seed()} for the seed - none on a server
 * world); the same on both loaders.
 */
final class CaveLabel {
    private CaveLabel() {}

    static final String USAGE = "usage: aa cave list | yes | no | check (list: the caves known by rays and the index near you; yes / no: label the volume at your crosshair or the opening nearest you; check: the floor against the labels)";

    /** The crosshair's ray for a label: as far as the sight rays look. */
    static double aimRange() { return io.github.mojolowjo.entropybot.sense.Seen.range(); }

    /** structural: the reach for "the opening nearest the eyes" - the survival block interaction range (a game fact). */
    static final double NEAR = 4.5;

    static String run(String arg) {
        String a = arg == null ? "" : arg.trim().toLowerCase(java.util.Locale.ROOT);
        ChunkPass pass = ChunkPass.INSTANCE;
        Minecraft mc = Minecraft.getInstance();
        var sg = pass.sightings();
        String dim = pass.dim();
        if (mc.player == null || mc.level == null || sg == null || dim == null) return "aa cave: no world (the caves and labels are this world's)";
        var p = mc.player;
        CaveIndex idx = pass.caveIndex();
        switch (a) {
            case "", "list" -> {
                int within = (int) Math.floor(io.github.mojolowjo.entropybot.aa.brain.Tuning.get("cave.exploreTowardM"));
                return io.github.mojolowjo.entropybot.sense.CaveList.text(sg.allKnownCaves(dim), idx.credited(), idx.truth(p.getBlockX(), p.getBlockY(), p.getBlockZ(), within),
                        within, idx.counts(), pass.caveRaysStatus(), p.getX(), p.getY(), p.getZ());   // run EE: the index's truth (a debug read)
            }
            case "check" -> {
                List<CaveLabels.Label> all = pass.labels().all();
                return CaveLabels.checkText(all, pass.floor(), l -> idx.creditsAt(l.x(), l.y(), l.z()));
            }
            case "yes", "no" -> {
                Vec3 eye = p.getEyePosition(), to = eye.add(p.getLookAngle().scale(aimRange()));
                BlockHitResult r = mc.level.clip(new ClipContext(eye, to, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, p));
                int[] hit = null;
                List<int[]> path;
                if (r.getType() == HitResult.Type.BLOCK) {
                    hit = new int[]{r.getBlockPos().getX(), r.getBlockPos().getY(), r.getBlockPos().getZ()};
                    Vec3 at = r.getLocation();
                    path = new java.util.ArrayList<>(CaveIndex.path(eye.x, eye.y, eye.z, at.x, at.y, at.z));
                    final int[] h = hit;
                    path.removeIf(q -> q[0] == h[0] && q[1] == h[1] && q[2] == h[2]);
                } else path = CaveIndex.path(eye.x, eye.y, eye.z, to.x, to.y, to.z);
                CaveIndex.Spot s = idx.labelSpot(path, hit, eye.x, eye.y, eye.z, NEAR);
                if (s == null) return "no air volume here (the crosshair passed no indexed air, the block it hit has none beside it, and no opening is within " + NEAR + " blocks)";
                Long seed = mc.getSingleplayerServer() == null ? null : mc.getSingleplayerServer().getWorldData().worldGenOptions().seed();
                var files = io.github.mojolowjo.entropybot.Core.INSTANCE.files();
                String world = files == null ? dim : files.worldRoot().getFileName() + " " + dim;
                boolean yes = a.equals("yes");
                pass.label(new CaveLabels.Label(s.x(), s.y(), s.z(), s.rim(), s.reach(), s.size().name().toLowerCase(java.util.Locale.ROOT),
                        s.depth().name().toLowerCase(java.util.Locale.ROOT), yes, seed, System.currentTimeMillis(), world));
                return "labelled " + a + ": volume at " + s.x() + " " + s.y() + " " + s.z() + ": rim " + s.rim() + ", reach " + (s.reach() < 0 ? "none (no opening)" : s.reach())
                        + ", size " + s.size().name().toLowerCase(java.util.Locale.ROOT) + ", depth " + s.depth().name().toLowerCase(java.util.Locale.ROOT)
                        + "; the index called it " + (s.cave() ? "a cave" : "no cave") + (s.cave() == yes ? " (agrees)" : " (disagrees)") + "\n" + pass.floor().line();
            }
            default -> {
                return USAGE;
            }
        }
    }
}
