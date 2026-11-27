package io.github.mojolowjo.entropybot.mixin;

import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Dry-run package 1 (the ghost body): lets the ghost run {@code Entity.updateFluidOnEyes()V} (private), the one piece of
 * {@code Entity.baseTick} it cannot call itself. The ghost copies baseTick piece by piece instead of calling it whole,
 * because the whole tick also runs portals, sprint particles and (in {@code LivingEntity.baseTick}) NeoForge's
 * {@code LivingBreatheEvent}. An invoker is all-or-nothing: it applies, or the game log has the mixin error and
 * {@code debug ghost status} says the ghost is off ({@code GhostPlayer.ready()}).
 */
@Mixin(Entity.class)
public interface GhostMixinEntityAccess {
    @Invoker("updateFluidOnEyes")
    void entropybot$updateFluidOnEyes();
}
