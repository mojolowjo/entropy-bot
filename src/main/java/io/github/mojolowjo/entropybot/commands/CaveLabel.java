package io.github.mojolowjo.entropybot.commands;

import io.github.mojolowjo.entropybot.sense.CavePatterns;
import io.github.mojolowjo.entropybot.sense.ChunkPass;
import io.github.mojolowjo.entropybot.sense.SightingsRuntime;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

/**
 * 0.39.z50 (S71): {@code aa cave yes|no|hill|cliff|list}. The owner labels the spot under the crosshair (or the bot's own
 * position when the crosshair is on no block): its 9 x 9 surface patch, the hole walk's depth and pocket, the label, into
 * cave-patterns.json (the world's and the global one). "no" is stored as "cliff" (the not-a-cave side). Loader notes:
 * vanilla client API only ({@code Minecraft.hitResult}, {@code Level.getHeight}); the same on both loaders.
 */
final class CaveLabel {
    private CaveLabel() {}

    static final String USAGE = "usage: aa cave yes|no|hill|cliff (label the spot under the crosshair) | aa cave list";

    static String run(String arg) {
        String a = arg == null ? "" : arg.trim().toLowerCase(java.util.Locale.ROOT);
        ChunkPass pass = ChunkPass.INSTANCE;
        if (a.equals("list") || a.isEmpty()) return list(pass);
        String label = switch (a) {
            case "yes" -> "cave";
            case "no", "cliff" -> "cliff";
            case "hill" -> "hill";
            default -> null;
        };
        if (label == null) return USAGE;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return "aa cave: no world";
        BlockPos at = mc.hitResult instanceof BlockHitResult b && b.getType() == HitResult.Type.BLOCK ? b.getBlockPos() : mc.player.blockPosition();
        int x = at.getX(), z = at.getZ();
        var level = mc.level;
        // 0.39.z54 A3: the label's patch, walk and spot are read over GROUND heights (leaves, logs and air stripped), as the
        // shape test and the bot's own walk read them
        int g = SightingsRuntime.top(level, x, z);
        CavePatterns.Hole h = SightingsRuntime.walk(level, x, z);
        int[] ws = SightingsRuntime.patch(level, x, z);
        if (g == io.github.mojolowjo.entropybot.sense.CaveMouths.NOT_LOADED || h == null || ws == null)
            return "aa cave: the chunk at " + x + " " + z + " is not loaded: nothing labelled (stand nearer)";
        int y = g + 1;
        pass.label(new CavePatterns.Patch(x, y, z, label, "owner", h.depth(), h.pocket(), h.darkSky(), ws, null));
        return "aa cave: " + x + " " + y + " " + z + " labelled " + label + " (" + h.numbers() + ")\n" + pass.patterns().rule().line();
    }

    /**
     * 0.39.z52 (the owner's labelling walk): the rule line, then every candidate of the loaded chunks and every stored
     * mouth, nearest to the owner first (a candidate within cave.mergeM of a mouth shows as that mouth).
     */
    private static String list(ChunkPass pass) {
        Minecraft mc = Minecraft.getInstance();
        CavePatterns pat = pass.patterns();
        String rule = pat.rule().line() + "\nshapes the vote turned down this session: " + pass.shapesVotedOut()
                + (pass.canopyCapped() > 0 ? "; columns past cave.canopyMaxDepth (heightmap top kept): " + pass.canopyCapped() : "");
        if (mc.player == null) return rule + "\naa cave list: no world (candidates and mouths are known in a world)";
        var sg = pass.sightings();
        String dim = pass.dim();
        java.util.List<io.github.mojolowjo.entropybot.sense.CaveList.Entry> out = new java.util.ArrayList<>();
        java.util.List<io.github.mojolowjo.entropybot.sense.Sightings.MouthFact> mouths = sg == null || dim == null ? java.util.List.of() : sg.allMouths(dim);
        for (var f : mouths)
            out.add(io.github.mojolowjo.entropybot.sense.CaveList.mouth(f.x(), f.y(), f.z(), f.pocket().size(), f.fx(), f.fy(), f.fz(), f.walked() != 0));
        String note = sg == null || dim == null ? "\n(sightings not loaded: mouths unknown)" : "";
        long m = (long) io.github.mojolowjo.entropybot.sense.Sightings.factMerge() * io.github.mojolowjo.entropybot.sense.Sightings.factMerge();
        for (var c : pass.mouths().all()) {
            boolean isMouth = false;
            for (var f : mouths) { long dx = f.x() - c.x(), dz = f.z() - c.z(); if (dx * dx + dz * dz <= m) { isMouth = true; break; } }
            if (isMouth) continue;
            String why = SightingsRuntime.dropReason(c.x(), c.y(), c.z());
            if (why == null && pass.mouths().refused(c.x(), c.y(), c.z())) why = "refused earlier (reason not kept this session)";
            String nums = c.shape() + (c.numbers().isEmpty() ? "" : " " + c.numbers());
            String wait = why == null ? SightingsRuntime.holdReason(c.x(), c.y(), c.z()) : null;     // z54 A4
            out.add(wait != null ? io.github.mojolowjo.entropybot.sense.CaveList.held(c.x(), c.y(), c.z(), nums, wait)
                    : io.github.mojolowjo.entropybot.sense.CaveList.candidate(c.x(), c.y(), c.z(), nums, why));
        }
        var p = mc.player;
        return io.github.mojolowjo.entropybot.sense.CaveList.text(rule, out, pat.all(), p.getX(), p.getY(), p.getZ()) + note;
    }
}
