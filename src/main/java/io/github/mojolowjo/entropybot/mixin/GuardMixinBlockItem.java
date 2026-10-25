package io.github.mojolowjo.entropybot.mixin;

import io.github.mojolowjo.entropybot.Quiet;

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
            else {
                io.github.mojolowjo.entropybot.commands.RestoreLive.INSTANCE.notePlaced(context.getClickedPos(), path);      // aa-litter + rm-mining: my placements are noted as mine
                io.github.mojolowjo.entropybot.commands.WalkProbe.placing(context.getItemInHand());   // 0.39.z55 item 1 probe (debug.walkProbe); z56 2b: the line at RETURN
            }
        } catch (Throwable caught) { Quiet.warn("GuardMixinBlockItem.? failed, carrying on with the fallback", caught); }
    }

    /**
     * 0.39.z56 item 2b (run O 22:11:20-21: five "probe keep: placed" lines for one crafting table): the probe's line once per
     * block really placed - BlockItem.place's result consumed the action (HEAD runs for every attempt, refused ones too).
     * Read only; client side only.
     */
    @Inject(method = "place", at = @At("RETURN"))
    private void entropybot$placed(BlockPlaceContext context, CallbackInfoReturnable<InteractionResult> cir) {
        try {
            if (!context.getLevel().isClientSide()) return;
            io.github.mojolowjo.entropybot.commands.WalkProbe.placed(cir.getReturnValue(), context.getClickedPos());
        } catch (Throwable caught) { Quiet.warn("GuardMixinBlockItem.placed failed (probe line only)", caught); }
    }
}
