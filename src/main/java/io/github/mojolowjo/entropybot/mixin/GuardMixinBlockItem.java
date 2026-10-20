package io.github.mojolowjo.entropybot.mixin;

import io.github.mojolowjo.entropybot.guard.Guard;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Every block placement by this client goes through BlockItem.place, after doors, buttons and menus
 * had their chance to react to the click, so this is where a placement is refused without breaking
 * ordinary interactions. The context's clicked position is already the spot the block would go.
 */
@Mixin(BlockItem.class)
public abstract class GuardMixinBlockItem {

    @Inject(method = "place", at = @At("HEAD"), cancellable = true)
    private void entropybot$place(BlockPlaceContext context, CallbackInfoReturnable<InteractionResult> cir) {
        try {
            Level level = context.getLevel();
            if (!level.isClientSide()) return;
            String path = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(context.getItemInHand().getItem()).getPath();
            if (Guard.INSTANCE.vetoPlace(level, context.getClickedPos(), path.endsWith("torch"))) cir.setReturnValue(InteractionResult.FAIL);
            else io.github.mojolowjo.entropybot.commands.RestoreLive.INSTANCE.noteLitter(context.getClickedPos(), path);      // aa-litter: a walk's scaffold is noted
        } catch (Throwable ignored) {}
    }
}
