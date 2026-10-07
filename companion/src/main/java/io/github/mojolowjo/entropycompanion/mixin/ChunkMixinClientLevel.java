package io.github.mojolowjo.entropycompanion.mixin;

import io.github.mojolowjo.entropycompanion.ChunkShare;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Companion 0.3.0 (chunks-0.23.5): the chunk scanner's ears. Every block the client sets passes one of these (the
 * server's updates through setServerVerifiedBlockState, the owner's own through setBlock); the chunk is scanned again
 * 2 s later. Only the position is passed on, nothing else. require = 0: if a hook can't go in, the game still starts
 * and only chunk loads are scanned. Loader notes: mixins are loader-neutral (the same on Fabric).
 */
@Mixin(ClientLevel.class)
public abstract class ChunkMixinClientLevel {
    @Inject(method = "setServerVerifiedBlockState", at = @At("RETURN"), require = 0)
    private void entropycompanion$verified(BlockPos pos, BlockState state, int flags, CallbackInfo ci) {
        ChunkShare.blockChanged((ClientLevel) (Object) this, pos);
    }

    @Inject(method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z", at = @At("RETURN"), require = 0)
    private void entropycompanion$set(BlockPos pos, BlockState state, int flags, int recursionLeft, CallbackInfoReturnable<Boolean> cir) {
        if (Boolean.TRUE.equals(cir.getReturnValue())) ChunkShare.blockChanged((ClientLevel) (Object) this, pos);
    }
}