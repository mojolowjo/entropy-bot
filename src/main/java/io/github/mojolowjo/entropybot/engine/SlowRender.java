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
    /** Baritone's renderer usable (null = not tried yet); false after a linkage error: the batched fallback draws. */
    private static Boolean baritoneDraw;

    public static void onStage(RenderLevelStageEvent e) {
        if (e.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;
        List<int[]> pts = SlowRoute.points;
        if (pts.isEmpty()) return;
        if (!Boolean.FALSE.equals(baritoneDraw)) {
            try {
                drawLikeBaritone(e, pts);
                baritoneDraw = true;
                return;
            } catch (LinkageError le) {
                // Loader note: baritone.utils.IRenderer is Baritone's internal class (present in the unoptimized jar we
                // ship, not part of its API); a renamed or obfuscated build lands here once and the batched draw takes over
                baritoneDraw = false;
                LogUtils.getLogger().warn("[entropybot] aa: slow route: Baritone's renderer is missing ({}), drawing without see-through", le.toString());
            } catch (Throwable t) {
                if (errors++ < 5) LogUtils.getLogger().warn("[entropybot] aa: slow route draw failed: {}", t.toString());
                return;
            }
        }
        drawBatched(e, pts);
    }

    /**
     * Baritone's own technique (PathRenderer via IRenderer): an immediate line buffer with the depth test off for these
     * lines only, so the route shows through terrain like Baritone's path. emitAABB takes world coordinates (it subtracts
     * the camera itself); emitLine takes camera-relative ones.
     */
    private static void drawLikeBaritone(RenderLevelStageEvent e, List<int[]> pts) {
        if (Minecraft.getInstance().player == null) return;
        boolean wait = SlowRoute.waiting;
        java.awt.Color c = wait ? new java.awt.Color(255, 217, 26) : new java.awt.Color(89, 153, 89);
        Vec3 cam = e.getCamera().getPosition();
        PoseStack ps = e.getPoseStack();
        com.mojang.blaze3d.vertex.BufferBuilder bb = baritone.utils.IRenderer.startLines(c, 0.9f, 2f, true);
        for (int i = 0; i < pts.size(); i++) {
            int[] p = pts.get(i);
            baritone.utils.IRenderer.emitAABB(bb, ps, new AABB(p[0], p[1], p[2], p[0] + 1, p[1] + 1, p[2] + 1));
            if (i > 0) {
                int[] q = pts.get(i - 1);
                if (q[0] == p[0] && q[1] == p[1] && q[2] == p[2]) continue;
                baritone.utils.IRenderer.emitLine(bb, ps, q[0] + 0.5 - cam.x, q[1] + 1.0 - cam.y, q[2] + 0.5 - cam.z,
                        p[0] + 0.5 - cam.x, p[1] + 1.0 - cam.y, p[2] + 0.5 - cam.z);
            }
        }
        baritone.utils.IRenderer.endLines(bb, true);
    }

    /** The fallback without Baritone's renderer: the shared lines batch (not see-through). */
    private static void drawBatched(RenderLevelStageEvent e, List<int[]> pts) {
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
