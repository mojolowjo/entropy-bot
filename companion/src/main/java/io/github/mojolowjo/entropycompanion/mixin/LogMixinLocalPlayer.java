package io.github.mojolowjo.entropycompanion.mixin;

import io.github.mojolowjo.entropycompanion.ActionLogMc;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Companion 0.4.0 (action log): Q / ctrl-Q drops the held item. require 0; loader-neutral. */
@Mixin(LocalPlayer.class)
public abstract class LogMixinLocalPlayer {
    @Inject(method = "drop", at = @At("HEAD"), require = 0)
    private void entropycompanion$drop(boolean fullStack, CallbackInfoReturnable<Boolean> cir) {
        ActionLogMc l = ActionLogMc.instance();
        if (l != null) l.dropHeld((LocalPlayer) (Object) this, fullStack);
    }
}
