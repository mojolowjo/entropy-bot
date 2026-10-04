package io.github.mojolowjo.entropybot.mixin;

import io.github.mojolowjo.entropybot.engine.WatchCamera;
import net.minecraft.client.Camera;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Watch camera v1 (docs/CAMERA_PLAN.md): while {@code watch} is on, the drawn camera is set up again at the end of
 * {@code Camera.setup} with the smoothed walking direction instead of the player's head angles: aim, sit on the
 * entity's eye, step back {@code watch distance} blocks with vanilla's own block check (so it never sits inside
 * rock). Only the render camera changes; the player is never touched, so Baritone's steering and the mod's clicks are
 * unaffected. (0.14.0 and 0.14.1 tried to change the arguments of setRotation instead: the setup hook ran, that one
 * never did, so this version re-does the whole placement itself.)
 * require = 0: if the hook can't go in the game still starts; {@code watch status} says so.
 */
@Mixin(Camera.class)
public abstract class WatchMixinCamera {
    @Shadow protected abstract void setRotation(float yRot, float xRot);
    @Shadow protected abstract void setPosition(Vec3 pos);
    @Shadow protected abstract void move(float forwards, float up, float right);
    @Shadow protected abstract float getMaxZoom(float zoom);

    /** Counts every camera setup, so `watch status` can tell a hook that is in from one that is not. */
    @Inject(method = "setup", at = @At("HEAD"), require = 0)
    private void entropybot$seen(BlockGetter level, Entity entity, boolean detached, boolean mirrored, float partial, CallbackInfo ci) {
        WatchCamera.INSTANCE.hookCalled();
    }

    @Inject(method = "setup", at = @At("TAIL"), require = 0)
    private void entropybot$watch(BlockGetter level, Entity entity, boolean detached, boolean mirrored, float partial, CallbackInfo ci) {
        WatchCamera w = WatchCamera.INSTANCE;
        if (!w.on() || !detached || entity == null) return;
        w.angleEdited();
        setRotation(w.yaw(), WatchCamera.PITCH);
        setPosition(entity.getEyePosition(partial));
        move(-getMaxZoom(w.distance()), 0.0f, 0.0f);
    }
}
