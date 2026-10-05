package io.github.mojolowjo.entropybot.watchview;

import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;

/**
 * Camera v2's veil, the render side of {@link WorldVeil} (0.15.4): wipes what the world render drew, and draws the bot
 * and nearby mobs again on the clean picture. Render thread only. Vanilla render API: RenderSystem (clear colour, masks,
 * scissor, model-view stack), {@code Lighting}, {@code EntityRenderDispatcher.render} with the shared buffer source (the
 * same calls {@code LevelRenderer.renderEntity} makes). The entities are drawn by us, not by the level render, because
 * with the camera inside rock Sodium's occlusion culling (on unless the player is a spectator) hides the sections
 * around them, and the clear wipes them anyway.
 */
final class TunnelScene {
    private TunnelScene() {}

    /** Clears the bound main target's colour and depth to the veil's background. Leaves masks on, scissor off. */
    static void clearWorld() {
        Minecraft.getInstance().getMainRenderTarget().bindWrite(false);
        RenderSystem.disableScissor();
        RenderSystem.colorMask(true, true, true, true);
        RenderSystem.depthMask(true);
        RenderSystem.clearColor(WorldVeil.BG_R, WorldVeil.BG_G, WorldVeil.BG_B, 1f);
        RenderSystem.clearDepth(1.0);
        RenderSystem.clear(16640, Minecraft.ON_OSX);              // GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT
    }

    /** The render state the rest of the frame expects (vanilla's after LevelRenderer.renderLevel). Never throws. */
    static void restoreState() {
        try {
            RenderSystem.colorMask(true, true, true, true);
            RenderSystem.depthMask(true);
            RenderSystem.enableDepthTest();
            RenderSystem.depthFunc(515);                           // GL_LEQUAL, vanilla's default
            RenderSystem.enableCull();
            RenderSystem.disableBlend();
            RenderSystem.defaultBlendFunc();
            RenderSystem.lineWidth(1f);
        } catch (Throwable ignored) {
        }
    }

    /**
     * The bot and the mobs and players near it ({@link WorldVeil#shows}), as {@code LevelRenderer.renderEntity} draws
     * them, without shadows (a shadow lies on the ground's real blocks). Returns how many were drawn.
     */
    static int drawEntities(Matrix4f modelView, Camera camera, DeltaTracker dt, Entity bot, ClientLevel level) {
        Minecraft mc = Minecraft.getInstance();
        EntityRenderDispatcher d = mc.getEntityRenderDispatcher();
        MultiBufferSource.BufferSource buf = mc.renderBuffers().bufferSource();
        Vec3 cam = camera.getPosition();
        Matrix4fStack stack = RenderSystem.getModelViewStack();
        stack.pushMatrix();
        stack.mul(modelView);
        RenderSystem.applyModelViewMatrix();
        if (level.effects().constantAmbientLight()) Lighting.setupNetherLevel();
        else Lighting.setupLevel();
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(true);
        boolean shadows = mc.options.entityShadows().get();
        d.setRenderShadow(false);
        PoseStack ps = new PoseStack();
        int n = 0;
        try {
            for (Entity e : level.entitiesForRendering()) {
                boolean isBot = e == bot;
                if (!WorldVeil.shows(isBot, e instanceof Mob || e instanceof Player, e.isInvisible(), e.distanceToSqr(bot))) continue;
                float pt = dt.getGameTimeDeltaPartialTick(!level.tickRateManager().isEntityFrozen(e));
                double x = Mth.lerp(pt, e.xOld, e.getX()), y = Mth.lerp(pt, e.yOld, e.getY()), z = Mth.lerp(pt, e.zOld, e.getZ());
                float yRot = Mth.lerp(pt, e.yRotO, e.getYRot());
                d.render(e, x - cam.x, y - cam.y, z - cam.z, yRot, pt, ps, buf, d.getPackedLightCoords(e, pt));
                n++;
            }
        } finally {
            try {
                buf.endBatch();
            } finally {
                d.setRenderShadow(shadows);
                stack.popMatrix();
                RenderSystem.applyModelViewMatrix();
            }
        }
        return n;
    }
}
