package io.github.mojolowjo.entropybot.mixin;

import baritone.api.pathing.movement.ActionCosts;
import baritone.utils.BlockStateInterface;
import io.github.mojolowjo.entropybot.baritone.CostAdvisorHook;
import io.github.mojolowjo.entropybot.guard.Guard;
import net.minecraft.world.level.block.state.BlockState;
import io.github.mojolowjo.entropybot.guard.PolicyRuntime;
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
 * <p>Mining plan phase 0: the context's {@code bsi} goes along, so the policy can read the block facts on this thread
 * (shadow mode counts where it would differ).
 *
 * <p>Only applied when the readable class exists (the unoptimized jar); see GuardMixinPlugin.
 */
@Mixin(targets = "baritone.pathing.movement.CalculationContext", remap = false)
public abstract class GuardMixinCalculationContext {

    @Shadow(remap = false) @Final public Level world;
    @Shadow(remap = false) @Final public BlockStateInterface bsi;

    @Inject(method = "isPossiblyProtected(III)Z", at = @At("HEAD"), cancellable = true, remap = false)
    private void entropybot$isPossiblyProtected(int x, int y, int z, CallbackInfoReturnable<Boolean> cir) {
        try {
            if (world == null) return;
            BlockStateInterface b = bsi;
            if (Guard.INSTANCE.astarDenied(world, b == null ? null : PolicyRuntime.StateAt.of(b), x, y, z)) cir.setReturnValue(true);
        } catch (Throwable ignored) {}
    }

    /**
     * Mining plan phase 1, the stock-jar fallback for the fork's CostAdvisor: the policy's multiplier on top of
     * Baritone's answer (only when that answer is not already "impossible"). Does nothing when the fork hook is
     * installed (CostAdvisorHook.fork), so the policy is asked once per cell.
     */
    @Inject(method = "breakCostMultiplierAt(IIILnet/minecraft/world/level/block/state/BlockState;)D", at = @At("RETURN"), cancellable = true, remap = false)
    private void entropybot$breakCostMultiplierAt(int x, int y, int z, BlockState current, CallbackInfoReturnable<Double> cir) {
        try {
            if (CostAdvisorHook.fork() || world == null || bsi == null) return;
            double was = cir.getReturnValueD();
            if (was >= ActionCosts.COST_INF) return;
            double m = CostAdvisorHook.breakMultiplier(null, Guard.dimOf(world), x, y, z, current, PolicyRuntime.StateAt.of(bsi));
            if (!Double.isNaN(m)) cir.setReturnValue(was * m);
        } catch (Throwable ignored) {}
    }

    @Inject(method = "costOfPlacingAt(IIILnet/minecraft/world/level/block/state/BlockState;)D", at = @At("RETURN"), cancellable = true, remap = false)
    private void entropybot$costOfPlacingAt(int x, int y, int z, BlockState current, CallbackInfoReturnable<Double> cir) {
        try {
            if (CostAdvisorHook.fork() || world == null || bsi == null) return;
            double was = cir.getReturnValueD();
            if (was >= ActionCosts.COST_INF) return;
            double c = CostAdvisorHook.placeCost(null, Guard.dimOf(world), x, y, z, current, PolicyRuntime.StateAt.of(bsi), was);
            if (!Double.isNaN(c)) cir.setReturnValue(c);
        } catch (Throwable ignored) {}
    }
}
