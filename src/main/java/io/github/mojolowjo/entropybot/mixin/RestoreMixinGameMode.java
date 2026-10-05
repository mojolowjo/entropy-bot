package io.github.mojolowjo.entropybot.mixin;

import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * P1 (survival plan): the restore ledger's ears. Every block this client breaks ends in
 * {@code MultiPlayerGameMode.destroyBlock(BlockPos)} (from startDestroyBlock in creative / instant breaks and from
 * continueDestroyBlock); at HEAD the block is still there, so its state is read before the break. No NeoForge client
 * event exists for the local player's break (BlockEvent.BreakEvent is server-side). require = 1: the hook is part of
 * the no-grief promise, so a game where it can't go in fails loudly; MixinFlags.restoreApplied feeds "check".
 */
@Mixin(MultiPlayerGameMode.class)
public abstract class RestoreMixinGameMode {

    @Inject(method = "destroyBlock(Lnet/minecraft/core/BlockPos;)Z", at = @At("HEAD"), require = 1)
    private void entropybot$destroyBlock(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        try {
            io.github.mojolowjo.entropybot.commands.RestoreLive.INSTANCE.onBreak(pos);
        } catch (Throwable t) {
            io.github.mojolowjo.entropybot.commands.RestoreLive.INSTANCE.hookError(t);
        }
    }
}
