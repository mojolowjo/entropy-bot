package io.github.mojolowjo.entropybot.cave;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.state.BlockState;

/** {@link CaveSearch.World} over the client's level. Client thread. */
public final class LevelWorld implements CaveSearch.World {
    private final Level level;
    private final BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();

    public LevelWorld(Level level) {
        this.level = level;
    }

    private BlockState at(int x, int y, int z) {
        return level.getBlockState(m.set(x, y, z));
    }

    @Override public boolean loaded(int x, int y, int z) { return level.isLoaded(m.set(x, y, z)); }

    @Override public boolean solid(int x, int y, int z) {
        BlockState st = at(x, y, z);
        return !st.getCollisionShape(level, m).isEmpty();
    }

    @Override public boolean open(int x, int y, int z) {
        BlockState st = at(x, y, z);
        return st.getCollisionShape(level, m).isEmpty() && st.getFluidState().isEmpty();
    }

    @Override public boolean liquid(int x, int y, int z) { return !at(x, y, z).getFluidState().isEmpty(); }

    @Override public boolean lava(int x, int y, int z) { return at(x, y, z).getFluidState().is(FluidTags.LAVA); }

    @Override public int blockLight(int x, int y, int z) { return level.getBrightness(LightLayer.BLOCK, m.set(x, y, z)); }

    @Override public int skyLight(int x, int y, int z) { return level.getBrightness(LightLayer.SKY, m.set(x, y, z)); }

    @Override public String block(int x, int y, int z) { return BuiltInRegistries.BLOCK.getKey(at(x, y, z).getBlock()).toString(); }
}
