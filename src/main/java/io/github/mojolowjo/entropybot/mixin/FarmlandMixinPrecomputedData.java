package io.github.mojolowjo.entropybot.mixin;

import baritone.pathing.movement.MovementHelper;
import baritone.pathing.precompute.Ternary;
import net.minecraft.world.level.block.FarmBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Baritone walks on vanilla farmland only (an identity check, {@code block == Blocks.FARMLAND}: farmland is 15/16 of a
 * block, so it isn't a full cube), so the crop rows of a farm on modded farmland (Mystical Agriculture's
 * inferium_farmland...) are unreachable: no path into the field, seen live 2026-10-03. Baritone caches walkability per
 * block state in PrecomputedData; this answers YES there for every {@link FarmBlock}, as it does for vanilla's.
 * Walking never tramples (only falling onto farmland does).
 *
 * <p>Only applied when the readable class exists (the unoptimized jar); see GuardMixinPlugin.
 */
@Mixin(targets = "baritone.pathing.precompute.PrecomputedData", remap = false)
public abstract class FarmlandMixinPrecomputedData {

    @Redirect(method = "fillData(ILnet/minecraft/world/level/block/state/BlockState;)I",
            at = @At(value = "INVOKE", remap = false,
                    target = "Lbaritone/pathing/movement/MovementHelper;canWalkOnBlockState(Lnet/minecraft/world/level/block/state/BlockState;)Lbaritone/pathing/precompute/Ternary;"),
            remap = false)
    private Ternary entropybot$canWalkOn(BlockState state) {
        try {
            if (state.getBlock() instanceof FarmBlock) return Ternary.YES;
        } catch (Throwable ignored) {}
        return MovementHelper.canWalkOnBlockState(state);
    }
}
