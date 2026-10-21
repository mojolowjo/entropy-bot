package io.github.mojolowjo.entropybot.engine;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.logging.LogUtils;
import io.github.mojolowjo.entropybot.aa.SlowRoute;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;

import java.util.List;

/**
 * AA_STEP3 B: draws slow mode's route in the world: a line through the points the chosen way names (the bot, then each
 * goto and the way's site) and a box on each, bright while the bot waits for go, dim while the way runs. The mod's own
 * renderer: Baritone's PathRenderer only draws the path it is walking, which does not exist while the plan waits.
 *
 * <p>Loader notes: a NeoForge client event, {@code RenderLevelStageEvent} at {@code AFTER_TRANSLUCENT_BLOCKS} (posted by
 * LevelRenderer with the level's PoseStack and the camera), registered from {@link WatchEvents#register} on the NeoForge
 * event bus; a failure is counted and logged once, never let into the frame loop. Fabric: WorldRenderEvents.AFTER_TRANSLUCENT.
 */
public final class SlowRender {
    private SlowRender() {}

    private static int errors;
    // Cosmetic, not done: an AABB per point per frame, and RenderType.lines() keeps its own depth state at endBatch, so the
    // lines may not show through terrain; a see-through line needs a custom RenderType (left out on purpose).

    public static void onStage(RenderLevelStageEvent e) {
        if (e.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;
        List<int[]> pts = SlowRoute.points;
        if (pts.isEmpty()) return;
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null || mc.level == null) return;
            Vec3 cam = e.getCamera().getPosition();
            PoseStack ps = e.getPoseStack();
            ps.pushPose();
            try {
                ps.translate(-cam.x, -cam.y, -cam.z);
                MultiBufferSource.BufferSource buf = mc.renderBuffers().bufferSource();
                VertexConsumer vc = buf.getBuffer(RenderType.lines());
                boolean wait = SlowRoute.waiting;
                float r = wait ? 1f : 0.35f, g = wait ? 0.85f : 0.6f, b = wait ? 0.1f : 0.35f;
                Matrix4f m = ps.last().pose();
                for (int i = 0; i < pts.size(); i++) {
                    int[] p = pts.get(i);
                    LevelRenderer.renderLineBox(ps, vc, new AABB(p[0], p[1], p[2], p[0] + 1, p[1] + 1, p[2] + 1), r, g, b, 1f);
                    if (i > 0) {
                        int[] q = pts.get(i - 1);
                        float dx = p[0] - q[0], dy = p[1] - q[1], dz = p[2] - q[2];
                        float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
                        if (len < 1e-3f) continue;
                        dx /= len;
                        dy /= len;
                        dz /= len;
                        vc.addVertex(m, q[0] + 0.5f, q[1] + 1.0f, q[2] + 0.5f).setColor(r, g, b, 1f).setNormal(ps.last(), dx, dy, dz);
                        vc.addVertex(m, p[0] + 0.5f, p[1] + 1.0f, p[2] + 0.5f).setColor(r, g, b, 1f).setNormal(ps.last(), dx, dy, dz);
                    }
                }
                RenderSystem.disableDepthTest();
                try {
                    buf.endBatch(RenderType.lines());
                } finally {
                    RenderSystem.enableDepthTest();
                }
            } finally {
                ps.popPose();
            }
        } catch (Throwable t) {
            if (errors++ < 5) LogUtils.getLogger().warn("[entropybot] aa: slow route draw failed: {}", t.toString());
        }
    }
}
