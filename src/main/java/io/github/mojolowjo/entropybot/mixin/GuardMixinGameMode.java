package io.github.mojolowjo.entropybot.mixin;

import io.github.mojolowjo.entropybot.guard.Guard;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The last line of the guard: every block this client breaks, and every liquid, fire, egg or entity it
 * puts down, passes here (Baritone, the KubeJS bridge, the mod's own engine, a person in the window).
 * Block placements are caught in {@link GuardMixinBlockItem}, after interactions had their turn.
 */
@Mixin(MultiPlayerGameMode.class)
public abstract class GuardMixinGameMode {

    @Inject(method = "startDestroyBlock", at = @At("HEAD"), cancellable = true)
    private void entropybot$startDestroy(BlockPos loc, Direction face, CallbackInfoReturnable<Boolean> cir) {
        try {
            if (Guard.INSTANCE.vetoBreak(loc)) cir.setReturnValue(false);
        } catch (Throwable ignored) {}
    }

    @Inject(method = "continueDestroyBlock", at = @At("HEAD"), cancellable = true)
    private void entropybot$continueDestroy(BlockPos posBlock, Direction directionFacing, CallbackInfoReturnable<Boolean> cir) {
        try {
            if (Guard.INSTANCE.vetoBreak(posBlock)) cir.setReturnValue(false);
        } catch (Throwable ignored) {}
    }

    @Inject(method = "useItemOn", at = @At("HEAD"), cancellable = true)
    private void entropybot$useItemOn(LocalPlayer player, InteractionHand hand, BlockHitResult result, CallbackInfoReturnable<InteractionResult> cir) {
        try {
            if (Guard.INSTANCE.vetoUseOn(player, hand, result)) cir.setReturnValue(InteractionResult.FAIL);
        } catch (Throwable ignored) {}
    }

    @Inject(method = "useItem", at = @At("HEAD"), cancellable = true)
    private void entropybot$useItem(Player player, InteractionHand hand, CallbackInfoReturnable<InteractionResult> cir) {
        try {
            if (Guard.INSTANCE.vetoUse(player, hand)) cir.setReturnValue(InteractionResult.FAIL);
        } catch (Throwable ignored) {}
    }
}
