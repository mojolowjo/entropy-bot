package io.github.mojolowjo.entropybot.mixin;

import net.minecraft.client.Camera;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Camera v2 (tunnel view, docs/CAMERA_PLAN.md): lets the mod call {@code Camera.setPosition(Vec3)V} (protected), so the
 * render camera can sit above the bot. No NeoForge event places the camera: {@code ComputeCameraAngles} only turns it,
 * and {@code CalculateDetachedCameraDistanceEvent}'s distance is clipped against blocks by {@code getMaxZoom}, which
 * pulls the camera back to the bot underground. The call is made from {@code ViewportEvent.ComputeFov} (posted after
 * {@code Camera.setup}, before the frustum and the level render use the position). An invoker is all-or-nothing: it
 * either applies or the game log has the mixin error; {@code check} and {@code watch status} say which
 * ({@code MixinFlags.cameraPosApplied}, and an instanceof test on the real camera).
 */
@Mixin(Camera.class)
public interface WatchMixinCameraAccess {
    @Invoker("setPosition")
    void entropybot$setPosition(Vec3 pos);
}
