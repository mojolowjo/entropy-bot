package io.github.mojolowjo.entropybot.mixin;

import io.github.mojolowjo.entropybot.engine.Reconnect;
import net.minecraft.client.Minecraft;
import net.minecraft.client.quickplay.QuickPlay;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * T4 (2026-10-04): a game launched straight onto a server (Prism's {@code --quickPlayMultiplayer host:port}) whose
 * first join is refused never reached a world, so {@link Reconnect} had no server to go back to and never retried.
 * Minecraft keeps no copy of the launch address; QuickPlay.joinMultiplayerWorld gets it once, so note it there.
 */
@Mixin(QuickPlay.class)
public abstract class ReconnectMixinQuickPlay {

    @Inject(method = "joinMultiplayerWorld", at = @At("HEAD"))
    private static void entropybot$joinMultiplayer(Minecraft mc, String address, CallbackInfo ci) {
        try {
            if (address != null && !address.isBlank()) Reconnect.launchServer = address.trim();
        } catch (Throwable ignored) {}
    }
}
