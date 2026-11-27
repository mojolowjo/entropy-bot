package io.github.mojolowjo.entropybot.nav.world;

import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;

/**
 * nav step 3: the fork's FluidFlags (fork jar 1.11.3-entropy, run JFR: fluid tests were a hot spot of the search), the
 * fluid facts of a block state as one byte, computed once per state. The fork kept the byte in every BlockState through
 * a mixin ({@code MixinBlockStateBase}); here it is an array indexed by the state's id in
 * {@code Block.BLOCK_STATE_REGISTRY}, so no mixin is needed. The array grows when the registry does (a new state id);
 * a lost race only recomputes the same byte.
 *
 * <p>Pure (JUnit: FluidFlagsTest). Loader notes: vanilla Block.BLOCK_STATE_REGISTRY (the loader fills it at its
 * registry freeze; NeoForge and Fabric both).
 */
public final class FluidFlags {
    public static final byte FILLED = 1, FLUID = 2, WATER = 4, LAVA = 8, POSSIBLY_FLOWING = 16, WATERLOGGED = 32;
    private static final AtomicReference<byte[]> CACHE = new AtomicReference<>(new byte[0]);

    private FluidFlags() {}

    public static byte of(BlockState state) {
        int id = Block.BLOCK_STATE_REGISTRY.getId(state);
        if (id < 0) return compute(state);
        byte[] a = CACHE.get();
        if (id < a.length && a[id] != 0) return a[id];
        byte b = compute(state);
        while (true) {
            byte[] cur = CACHE.get();
            byte[] next = id < cur.length ? cur.clone() : java.util.Arrays.copyOf(cur, Math.max(id + 1, Block.BLOCK_STATE_REGISTRY.size()));
            next[id] = b;
            if (CACHE.compareAndSet(cur, next)) return b;
        }
    }

    public static byte compute(BlockState state) {
        FluidState fs = state.getFluidState();
        int b = FILLED;
        if (!fs.isEmpty()) b |= FLUID;
        Fluid f = fs.getType();
        if (f == Fluids.WATER || f == Fluids.FLOWING_WATER) b |= WATER;
        if (f == Fluids.LAVA || f == Fluids.FLOWING_LAVA) b |= LAVA;
        if (f instanceof FlowingFluid && f.getAmount(fs) != 8) b |= POSSIBLY_FLOWING;
        if (state.hasProperty(BlockStateProperties.WATERLOGGED) && state.getValue(BlockStateProperties.WATERLOGGED)) b |= WATERLOGGED;
        return (byte) b;
    }

    public static boolean isEmpty(BlockState state) { return (of(state) & FLUID) == 0; }
    public static boolean isWater(BlockState state) { return (of(state) & WATER) != 0; }
    public static boolean isLava(BlockState state) { return (of(state) & LAVA) != 0; }
    public static boolean possiblyFlowing(BlockState state) { return (of(state) & POSSIBLY_FLOWING) != 0; }
    public static boolean waterlogged(BlockState state) { return (of(state) & WATERLOGGED) != 0; }
}
