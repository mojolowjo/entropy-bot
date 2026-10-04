package io.github.mojolowjo.entropybot.mixin;

import io.github.mojolowjo.entropybot.engine.WatchCamera;
import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Watch camera v1 (docs/CAMERA_PLAN.md): the drawn camera is set up with the smoothed walking direction instead of the
 * player's head angles, while {@code watch} is on. Only the render camera changes; the player is never touched, so
 * Baritone's steering and the mod's clicks are unaffected. In third person the camera still steps back from this angle
 * with vanilla's block check, so it never sits inside rock (v1 never sees through walls).
 * require = 0: if the hook can't go in the game still starts and {@code watch} simply shows the normal third person.
 */
@Mixin(Camera.class)
public abstract class WatchMixinCamera {
    @ModifyVariable(method = "setRotation", at = @At("HEAD"), argsOnly = true, ordinal = 0, require = 0)
    private float entropybot$yaw(float yRot) {
        return WatchCamera.INSTANCE.on() ? WatchCamera.INSTANCE.yaw() : yRot;
    }

    @ModifyVariable(method = "setRotation", at = @At("HEAD"), argsOnly = true, ordinal = 1, require = 0)
    private float entropybot$pitch(float xRot) {
        return WatchCamera.INSTANCE.on() ? WatchCamera.PITCH : xRot;
    }
}
