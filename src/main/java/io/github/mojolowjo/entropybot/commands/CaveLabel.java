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
        if (a.equals("list") || a.isEmpty())
            return pass.patterns().text() + "\nshapes the vote turned down this session: " + pass.shapesVotedOut();
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
        CavePatterns.Hole h = CavePatterns.walk(x, z, (cx, cz) -> level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE, cx, cz) - 1, SightingsRuntime.cells(level));
        int[] ws = SightingsRuntime.patch(level, x, z);
        int y = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE, x, z);
        pass.label(new CavePatterns.Patch(x, y, z, label, "owner", h.depth(), h.pocket(), h.darkSky(), ws, null));
        return "aa cave: " + x + " " + y + " " + z + " labelled " + label + " (" + h.numbers() + ")\n" + pass.patterns().rule().line();
    }
}
