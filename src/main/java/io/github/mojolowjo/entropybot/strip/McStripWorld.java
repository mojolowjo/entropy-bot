package io.github.mojolowjo.entropybot.strip;

import io.github.mojolowjo.entropybot.clear.McClearWorld;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;
import java.util.function.Predicate;

/** {@link StripWorld} over the client's level: the clear engine's {@link McClearWorld} plus the strip mine's few extras. Client thread. */
public final class McStripWorld implements StripWorld {
    private final Level level;
    private final McClearWorld w;
    private final BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();

    public McStripWorld(Level level, Entity viewer) {
        this.level = level;
        this.w = new McClearWorld(level, viewer, avoidList());
    }

    /** Baritone's blocksToAvoidBreaking (the clear leaves those too); null when Baritone can't be asked. */
    static Predicate<Block> avoidList() {
        try {
            List<Block> l = baritone.api.BaritoneAPI.getSettings().blocksToAvoidBreaking.value;
            return l == null || l.isEmpty() ? null : l::contains;
        } catch (Throwable t) {
            return null;
        }
    }

    private BlockState at(int x, int y, int z) { return level.getBlockState(m.set(x, y, z)); }

    @Override public boolean untrusted(int x, int y, int z) {
        com.google.gson.JsonObject c = io.github.mojolowjo.entropybot.Core.INSTANCE.knowledge.chests().get(x + " " + y + " " + z);
        return c != null && c.has("trusted") && !c.get("trusted").getAsBoolean();
    }

    @Override public String id(int x, int y, int z) { return w.id(x, y, z); }
    @Override public String name(int x, int y, int z) { return w.name(x, y, z); }
    @Override public boolean air(int x, int y, int z) { return w.air(x, y, z); }
    @Override public boolean fluid(int x, int y, int z) { return w.fluid(x, y, z); }
    @Override public boolean blockEntity(int x, int y, int z) { return w.blockEntity(x, y, z); }
    @Override public boolean unbreakable(int x, int y, int z) { return w.unbreakable(x, y, z); }
    @Override public boolean builtBlock(int x, int y, int z) { return w.builtBlock(x, y, z); }
    @Override public boolean avoided(int x, int y, int z) { return w.avoided(x, y, z); }
    @Override public boolean ore(int x, int y, int z) { return w.ore(x, y, z); }
    @Override public boolean noCollision(int x, int y, int z) { return w.noCollision(x, y, z); }
    @Override public boolean fullBlock(int x, int y, int z) { return w.fullBlock(x, y, z); }
    @Override public double collisionHeight(int x, int y, int z) { return w.collisionHeight(x, y, z); }
    @Override public int blockLight(int x, int y, int z) { return w.blockLight(x, y, z); }
    @Override public Hit clip(double fx, double fy, double fz, double tx, double ty, double tz) { return w.clip(fx, fy, fz, tx, ty, tz); }

    @Override public String toolNeed(int x, int y, int z) {
        BlockState st = at(x, y, z);
        if (st.is(BlockTags.NEEDS_DIAMOND_TOOL)) return "diamond";
        if (st.is(BlockTags.NEEDS_IRON_TOOL)) return "iron";
        return "stone";          // needs_stone_tool, or in no needs_*_tool tag: any pickaxe will do
    }

    @Override public boolean needsCorrectTool(int x, int y, int z) { return w.needsCorrectTool(x, y, z); }

    @Override public boolean replaceable(int x, int y, int z) { return at(x, y, z).canBeReplaced(); }

    @Override public boolean loaded(int x, int y, int z) { return level.isLoaded(m.set(x, y, z)); }
}
