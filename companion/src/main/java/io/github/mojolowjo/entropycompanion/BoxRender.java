package io.github.mojolowjo.entropycompanion;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

import java.util.List;

/**
 * C3's box view: the bot's areas by type (neutral white, destroy red, main blue, safe green) and the owner's pending corners (yellow) as line boxes
 * in the owner's world, within {@link BoxSet#RANGE} blocks. Drawn at AFTER_PARTICLES: in 1.21.1 the model-view stack
 * holds the camera rotation there (LevelRenderer.renderLevel pushes it before the entities and pops it after the
 * weather), so camera-relative coordinates with an identity pose are right. Stale boxes (dashboard down) are grey.
 */
final class BoxRender {
    private BoxRender() {}

    static void onRender(RenderLevelStageEvent e) {
        if (e.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) return;
        Companion c = Companion.INSTANCE;
        if (c == null) return;
        boolean corners = c.corners.get(1) != null || c.corners.get(2) != null;
        if (!c.boxView && !corners) return;
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null || mc.level == null) return;
            String dim = mc.level.dimension().location().toString();
            Vec3 cam = e.getCamera().getPosition();
            MultiBufferSource.BufferSource buf = mc.renderBuffers().bufferSource();
            VertexConsumer vc = buf.getBuffer(RenderType.lines());
            PoseStack ps = new PoseStack();
            int drawn = 0;
            if (c.boxView) {
                List<BoxSet.Box> near = BoxSet.near(c.boxes, dim, cam.x, cam.z, BoxSet.RANGE);
                boolean stale = c.boxesStale;
                for (BoxSet.Box b : near) {
                    float[] col = BoxSet.colour(b.type());          // 0.2.1: by area type
                    float r = stale ? 0.6f : col[0], g = stale ? 0.6f : col[1], bl = stale ? 0.6f : col[2];
                    // whole-height areas are drawn 48 blocks around the camera's height, not to the build limit
                    double y1 = b.y1() <= BoxSet.WORLD_MIN_Y ? Math.max(b.y1(), cam.y - 48) : b.y1();
                    double y2 = b.y2() >= BoxSet.WORLD_MAX_Y ? Math.min(b.y2() + 1, cam.y + 48) : b.y2() + 1;
                    LevelRenderer.renderLineBox(ps, vc, b.x1() - cam.x, y1 - cam.y, b.z1() - cam.z,
                            b.x2() + 1 - cam.x, y2 - cam.y, b.z2() + 1 - cam.z, r, g, bl, 1f);
                    drawn++;
                }
            }
            for (int i = 1; i <= 2; i++) {
                Corners.Pos p = c.corners.get(i);
                if (p == null || !p.dim().equals(dim)) continue;
                LevelRenderer.renderLineBox(ps, vc, p.x() - cam.x - 0.02, p.y() - cam.y - 0.02, p.z() - cam.z - 0.02,
                        p.x() + 1.02 - cam.x, p.y() + 1.02 - cam.y, p.z() + 1.02 - cam.z, 1f, 1f, 0.2f, 1f);
                drawn++;
            }
            buf.endBatch(RenderType.lines());
            c.framesDrawn++;
            c.boxesDrawn = drawn;
        } catch (RuntimeException ex) {
            c.error("render", ex);
        }
    }
}
