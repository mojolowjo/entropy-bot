package io.github.mojolowjo.entropybot.commands;

import io.github.mojolowjo.entropybot.vocab.NaturalStructures;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import java.util.ArrayList;
import java.util.List;

/**
 * The world's structures from the integrated server's generator data (rm-final: no block palette guessed): every structure
 * start in the chunks round the spot whose bounding box meets the asked cube, by its registry name, vanilla or modded.
 * A client on a server has none (there {@link NaturalStructures#known()} stays false). Loader notes: vanilla
 * {@code ServerLevel.structureManager().startsForStructure(ChunkPos, predicate)}, {@code Registries.STRUCTURE}; NeoForge and
 * Fabric alike.
 */
final class StructureSource implements NaturalStructures.Source {
    /** Installed once a singleplayer world is up; null on a server. */
    static void install() {
        NaturalStructures.use(Minecraft.getInstance().getSingleplayerServer() == null ? null : new StructureSource());
    }

    @Override public List<String> near(String dim, int x, int y, int z, int r) {
        var server = Minecraft.getInstance().getSingleplayerServer();
        if (server == null) return List.of();
        ServerLevel level = server.getLevel(ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(dim)));
        if (level == null) throw new IllegalStateException("no server level " + dim);
        var reg = level.registryAccess().registryOrThrow(Registries.STRUCTURE);
        BoundingBox box = new BoundingBox(x - r, y - r, z - r, x + r, y + r, z + r);
        List<String> out = new ArrayList<>();
        for (int cx = (x - r) >> 4; cx <= (x + r) >> 4; cx++) for (int cz = (z - r) >> 4; cz <= (z + r) >> 4; cz++) {
            for (var start : level.structureManager().startsForStructure(new ChunkPos(cx, cz), s -> true)) {
                if (!start.getBoundingBox().intersects(box)) continue;
                var key = reg.getKey(start.getStructure());
                String name = key == null ? "structure" : key.getPath();
                if (!out.contains(name)) out.add(name);
            }
        }
        return out;
    }
}