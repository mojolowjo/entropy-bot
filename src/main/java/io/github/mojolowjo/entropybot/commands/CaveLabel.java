package io.github.mojolowjo.entropybot.commands;

import io.github.mojolowjo.entropybot.sense.ChunkPass;
import net.minecraft.client.Minecraft;

/**
 * 0.39.z78 J6 (the owner's ruling 2026-10-27 late): {@code aa cave list} - the caves known by rays and the volumes the
 * rays credited so far (sense.CaveList). {@code aa cave yes|no|hill|cliff} (the label patterns) is deleted with the
 * heightmap rule. Loader notes: vanilla client API only ({@code Minecraft.player}); the same on both loaders.
 */
final class CaveLabel {
    private CaveLabel() {}

    static final String USAGE = "usage: aa cave list (the caves known by rays and the volumes credited so far)";

    static String run(String arg) {
        String a = arg == null ? "" : arg.trim().toLowerCase(java.util.Locale.ROOT);
        if (!a.equals("list") && !a.isEmpty()) return USAGE;
        ChunkPass pass = ChunkPass.INSTANCE;
        Minecraft mc = Minecraft.getInstance();
        var sg = pass.sightings();
        String dim = pass.dim();
        if (mc.player == null || sg == null || dim == null) return "aa cave list: no world (the known caves are this world's)";
        var p = mc.player;
        return io.github.mojolowjo.entropybot.sense.CaveList.text(sg.allKnownCaves(dim), pass.caveIndex().credited(),
                pass.caveIndex().truth(p.getBlockX(), p.getBlockY(), p.getBlockZ(), (int) Math.floor(io.github.mojolowjo.entropybot.aa.brain.Tuning.get("cave.exploreTowardM"))),
                (int) Math.floor(io.github.mojolowjo.entropybot.aa.brain.Tuning.get("cave.exploreTowardM")), pass.caveIndex().counts(), pass.caveRaysStatus(), p.getX(), p.getY(), p.getZ());   // run EE: the index's truth (a debug read)
    }
}
