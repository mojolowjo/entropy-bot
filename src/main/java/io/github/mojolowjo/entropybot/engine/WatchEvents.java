package io.github.mojolowjo.entropybot.engine;

import io.github.mojolowjo.entropybot.guard.MixinFlags;
import io.github.mojolowjo.entropybot.watchview.TunnelView;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.client.event.CalculateDetachedCameraDistanceEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;
import net.neoforged.neoforge.common.NeoForge;

/**
 * The watch camera's NeoForge listeners (0.15.2; docs/CAMERA_PLAN.md "Loader API used"), all at
 * {@link EventPriority#LOWEST} so ours run last and win over another camera mod (review C3):
 * <ul>
 *   <li>{@code ViewportEvent.ComputeCameraAngles} (posted first in {@code Camera.setup}, before {@code setRotation}):
 *       v1's walking yaw and fixed pitch, v2's look-at-the-bot angles. Counted for {@code watch status}.</li>
 *   <li>{@code CalculateDetachedCameraDistanceEvent} (posted from {@code Camera.setup} via
 *       {@code ClientHooks.getDetachedCameraDistance}): v1's {@code watch distance}; vanilla's block check still
 *       shortens it, so v1 never sits in rock.</li>
 *   <li>{@code ViewportEvent.ComputeFov} (posted in {@code GameRenderer.renderLevel} after {@code Camera.setup}, before
 *       the frustum and the level render read the camera position): v2 places the camera through the
 *       {@code WatchMixinCameraAccess} invoker.</li>
 *   <li>{@code RenderLevelStageEvent}: the probe, and v2's faces at {@code AFTER_PARTICLES}.</li>
 * </ul>
 * Every listener catches everything: a failure is counted and logged, never let into the frame loop.
 */
public final class WatchEvents {
    private static int errors;

    private WatchEvents() {}

    public static void register() {
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, false, ViewportEvent.ComputeCameraAngles.class, WatchEvents::angles);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, false, CalculateDetachedCameraDistanceEvent.class, WatchEvents::distance);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, false, ViewportEvent.ComputeFov.class, WatchEvents::fov);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, false, RenderLevelStageEvent.class, WatchEvents::stage);
        MixinFlags.watchApplied = true;          // "the watch hook is in": the listeners, since 0.15.2
    }

    private static void angles(ViewportEvent.ComputeCameraAngles e) {
        WatchCamera w = WatchCamera.INSTANCE;
        w.hookCalled();
        try {
            if (TunnelView.INSTANCE.on()) {
                if (TunnelView.INSTANCE.onAngles(e)) w.angleEdited();
                return;
            }
            float[] a = w.v1Angles(e.getCamera().isDetached());
            if (a == null) return;
            e.setYaw(a[0]);
            e.setPitch(a[1]);
            e.setRoll(a[2]);
            w.angleEdited();
        } catch (Throwable t) {
            fail("angles", t);
        }
    }

    private static void distance(CalculateDetachedCameraDistanceEvent e) {
        try {
            WatchCamera.INSTANCE.distanceCalled();
            float d = WatchCamera.INSTANCE.v1Distance();
            if (d > 0) e.setDistance(d);
        } catch (Throwable t) {
            fail("distance", t);
        }
    }

    private static void fov(ViewportEvent.ComputeFov e) {
        try {
            if (TunnelView.INSTANCE.on()) TunnelView.INSTANCE.onFov(e);
        } catch (Throwable t) {
            fail("position", t);
        }
    }

    private static void stage(RenderLevelStageEvent e) {
        try {
            WatchProbe.INSTANCE.onStage(e);
        } catch (Throwable t) {
            fail("probe", t);
        }
        if (TunnelView.INSTANCE.on()) TunnelView.INSTANCE.onStage(e);   // catches its own errors (and turns itself off)
    }

    private static void fail(String where, Throwable t) {
        errors++;
        if (errors <= 5) com.mojang.logging.LogUtils.getLogger().warn("[entropybot] watch camera ({}): ", where, t);
        else if (errors % 1000 == 0) com.mojang.logging.LogUtils.getLogger().warn("[entropybot] watch camera ({}): {} errors so far, last {}", where, errors, t.toString());
    }
}
