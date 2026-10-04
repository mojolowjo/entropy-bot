package io.github.mojolowjo.entropybot.mixin;

import io.github.mojolowjo.entropybot.engine.WatchCamera;
import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Watch camera v1 (docs/CAMERA_PLAN.md): the drawn camera is set up with the smoothed walking direction instead of the
 * player's head angles, while {@code watch} is on. Only the render camera changes; the player is never touched, so
 * Baritone's steering and the mod's clicks are unaffected. In third person the camera still steps back from this angle
 * with vanilla's block check, so it never sits inside rock (v1 never sees through walls).
 * require = 0: if the hook can't go in the game still starts and {@code watch} simply shows the normal third person.
 */
@Mixin(Camera.class)
public abstract class WatchMixinCamera {
    /** Counts every camera setup, so `watch status` can tell a hook that is in from one that is not. */
    @Inject(method = "setup", at = @At("HEAD"), require = 0)
    private void entropybot$seen(net.minecraft.world.level.BlockGetter level, net.minecraft.world.entity.Entity entity, boolean detached, boolean mirrored, float partial, CallbackInfo ci) {
        WatchCamera.INSTANCE.hookCalled();
    }

    /** `watch distance N`: vanilla steps back 4 blocks (getMaxZoom(4.0)); while watching it is N. */
    @ModifyArg(method = "setup", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Camera;getMaxZoom(F)F"), index = 0, require = 0)
    private float entropybot$distance(float zoom) {
        return WatchCamera.INSTANCE.on() ? WatchCamera.INSTANCE.distance() : zoom;
    }

    @ModifyVariable(method = "setRotation(FF)V", at = @At("HEAD"), argsOnly = true, index = 1, require = 0)
    private float entropybot$yaw(float yRot) {
        if (!WatchCamera.INSTANCE.on()) return yRot;
        WatchCamera.INSTANCE.angleEdited();
        return WatchCamera.INSTANCE.yaw();
    }

    @ModifyVariable(method = "setRotation(FF)V", at = @At("HEAD"), argsOnly = true, index = 2, require = 0)
    private float entropybot$pitch(float xRot) {
        return WatchCamera.INSTANCE.on() ? WatchCamera.PITCH : xRot;
    }
}
