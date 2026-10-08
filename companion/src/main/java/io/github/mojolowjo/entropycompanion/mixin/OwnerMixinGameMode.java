package io.github.mojolowjo.entropycompanion.mixin;

import io.github.mojolowjo.entropycompanion.OwnerEvents;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Companion 0.3.1 (assist): the owner's own actions on their client. destroyBlock = a block they broke, useItemOn =
 * a block they placed, attack = an entity they hit. Each hook only hands the facts to {@link OwnerEvents}, which never
 * throws. require = 0: a hook that can't go in leaves that field out of the report, the game still starts. Loader
 * notes: mixins are loader-neutral (the same on Fabric).
 */
@Mixin(MultiPlayerGameMode.class)
public abstract class OwnerMixinGameMode {
    @Inject(method = "destroyBlock", at = @At("HEAD"), require = 0)
    private void entropycompanion$destroy(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        OwnerEvents.beforeDestroy(pos);
    }

    @Inject(method = "useItemOn", at = @At("HEAD"), require = 0)
    private void entropycompanion$useHead(LocalPlayer player, InteractionHand hand, BlockHitResult hit, CallbackInfoReturnable<InteractionResult> cir) {
        OwnerEvents.beforeUse(player, hand);
    }

    @Inject(method = "useItemOn", at = @At("RETURN"), require = 0)
    private void entropycompanion$useReturn(LocalPlayer player, InteractionHand hand, BlockHitResult hit, CallbackInfoReturnable<InteractionResult> cir) {
        OwnerEvents.afterUse(hit, cir.getReturnValue());
    }

    @Inject(method = "attack", at = @At("HEAD"), require = 0)
    private void entropycompanion$attack(Player player, Entity target, CallbackInfo ci) {
        OwnerEvents.attacked(target);
    }

    /** 0.4.0 action log: crafts, furnace output and throws from an open menu. */
    @Inject(method = "handleInventoryMouseClick", at = @At("HEAD"), require = 0)
    private void entropycompanion$click(int containerId, int slotId, int button, net.minecraft.world.inventory.ClickType type, Player player, CallbackInfo ci) {
        io.github.mojolowjo.entropycompanion.ActionLogMc l = io.github.mojolowjo.entropycompanion.ActionLogMc.instance();
        if (l != null) l.click(slotId, button, type, player);
    }
}
