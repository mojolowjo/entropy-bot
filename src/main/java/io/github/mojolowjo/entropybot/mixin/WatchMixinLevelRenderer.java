package io.github.mojolowjo.entropybot.mixin;

import io.github.mojolowjo.entropybot.watchview.TunnelView;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderType;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Camera v2 (0.15.4, docs/CAMERA_PLAN.md "noclip"): while the tunnel view is on, the terrain's section layers are not
 * drawn at all. This is only a saving of frame time: the view hides the world by clearing the frame after the level
 * render ({@code WorldVeil}), whether or not this hook works. Target: {@code LevelRenderer.renderSectionLayer(
 * RenderType, double, double, double, Matrix4f, Matrix4f)V}, which Sodium 0.8.13 replaces with an {@code @Overwrite}
 * (verified with javap: its body calls {@code SodiumWorldRenderer.drawChunkLayer} and then NeoForge's chunk-layer stage
 * events). Priority 1500 so this mixin is applied after Sodium's (default 1000) and the HEAD inject lands in Sodium's
 * body instead of being thrown away by the overwrite. require = 0: if it can't go in, the game still starts and
 * {@code watch tunnel status} says "terrain drawn then cleared (skip hook not applied / not firing)"; the dev run fails
 * on a missed injection ({@code mixin.debug.countInjections}).
 */
@Mixin(value = LevelRenderer.class, priority = 1500)
public abstract class WatchMixinLevelRenderer {
    @Inject(method = "renderSectionLayer(Lnet/minecraft/client/renderer/RenderType;DDDLorg/joml/Matrix4f;Lorg/joml/Matrix4f;)V",
            at = @At("HEAD"), cancellable = true, require = 0)
    private void entropybot$skipTerrain(RenderType type, double x, double y, double z, Matrix4f frustum, Matrix4f projection, CallbackInfo ci) {
        if (TunnelView.INSTANCE.skipTerrain()) ci.cancel();
    }
}
