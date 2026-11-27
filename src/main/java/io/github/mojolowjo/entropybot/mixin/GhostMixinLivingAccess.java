package io.github.mojolowjo.entropybot.mixin;

import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Dry-run package 1 (the ghost body): reads the real player's {@code LivingEntity.noJumpDelay} (private int), so a ghost
 * copied mid-hop waits the same ticks before it can jump again. Read only; an accessor is all-or-nothing like
 * {@link GhostMixinEntityAccess}.
 */
@Mixin(LivingEntity.class)
public interface GhostMixinLivingAccess {
    @Accessor("noJumpDelay")
    int entropybot$noJumpDelay();
}
