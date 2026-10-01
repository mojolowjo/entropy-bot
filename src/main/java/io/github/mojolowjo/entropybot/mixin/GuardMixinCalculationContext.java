package io.github.mojolowjo.entropybot.mixin;

import io.github.mojolowjo.entropybot.guard.Guard;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Baritone's own hook for "someone else's block" (upstream TODO #220): a stub that returns false,
 * consulted by costOfPlacingAt and breakCostMultiplierAt, which make the move impossible when it is
 * true. Fills it with the guard's box rules, so A* never plans a break or a placement the click veto
 * would refuse. Runs on the pathfinder thread; the guard reads immutable snapshots there.
 *
 * <p>Only applied when the readable class exists (the unoptimized jar); see GuardMixinPlugin.
 */
@Mixin(targets = "baritone.pathing.movement.CalculationContext", remap = false)
public abstract class GuardMixinCalculationContext {

    @Shadow(remap = false) @Final public Level world;

    @Inject(method = "isPossiblyProtected(III)Z", at = @At("HEAD"), cancellable = true, remap = false)
    private void entropybot$isPossiblyProtected(int x, int y, int z, CallbackInfoReturnable<Boolean> cir) {
        try {
            if (world != null && Guard.INSTANCE.astarDenied(world, x, y, z)) cir.setReturnValue(true);
        } catch (Throwable ignored) {}
    }
}
