package io.github.mojolowjo.entropycompanion.mixin;

import io.github.mojolowjo.entropycompanion.ActionLogMc;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundTakeItemEntityPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Companion 0.4.0 (action log): an item picked up. The handler runs once on the network thread (which hands it to the
 * client thread) and again on the client thread: only the second counts, before the item entity is removed. require 0;
 * loader-neutral.
 */
@Mixin(ClientPacketListener.class)
public abstract class LogMixinPacketListener {
    @Inject(method = "handleTakeItemEntity", at = @At("HEAD"), require = 0)
    private void entropycompanion$take(ClientboundTakeItemEntityPacket p, CallbackInfo ci) {
        Minecraft mc = Minecraft.getInstance();
        ActionLogMc l = ActionLogMc.instance();
        if (l != null && mc.isSameThread()) l.pickup(mc, p.getItemId(), p.getPlayerId(), p.getAmount());
    }
}
