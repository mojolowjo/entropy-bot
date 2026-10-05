package io.github.mojolowjo.entropybot.engine;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Camera v2 probe (docs/CAMERA_PLAN.md, review C2): which {@link RenderLevelStageEvent} stages fire with Sodium on, and
 * can a line box drawn there be seen through rock. {@code watch probe} counts every stage and draws a line box around
 * the bot with the depth test off at three stages (red = after translucent blocks, green = after particles, blue = after
 * the level), each a different size, so a screenshot ({@code watch shot}) tells which stages actually draw.
 * {@code watch probe off} stops it. Client render thread only; never throws.
 */
public final class WatchProbe {
    public static final WatchProbe INSTANCE = new WatchProbe();

    private volatile boolean on;
    private final Map<String, AtomicLong> counts = new ConcurrentHashMap<>();
    private int errors;

    private WatchProbe() {}

    public boolean on() { return on; }

    public void setOn(boolean v) {
        on = v;
        if (v) counts.clear();
    }

    /** The per-stage counts, e.g. "AFTER_PARTICLES=120, AFTER_LEVEL=120" (sorted), or "none". Pure formatting. */
    public String report() {
        if (counts.isEmpty()) return "no stage seen yet";
        Map<String, Long> sorted = new TreeMap<>();
        counts.forEach((k, v) -> sorted.put(k, v.get()));
        StringBuilder sb = new StringBuilder();
        sorted.forEach((k, v) -> sb.append(sb.length() == 0 ? "" : ", ").append(k).append('=').append(v));
        return sb.toString();
    }

    /** NeoForge event listener: counts the stage and draws the probe box at the three chosen stages. */
    public void onStage(RenderLevelStageEvent event) {
        if (!on) return;
        try {
            String name = String.valueOf(event.getStage());
            counts.computeIfAbsent(name, k -> new AtomicLong()).incrementAndGet();
            float r = 0, g = 0, b = 0, size = 0;
            // compare the Stage objects: the names are lower case ("minecraft:after_particles"), so 0.15.1's upper-case test never drew
            RenderLevelStageEvent.Stage st = event.getStage();
            if (st == RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) { r = 1; size = 0.9f; }
            else if (st == RenderLevelStageEvent.Stage.AFTER_PARTICLES) { g = 1; size = 1.3f; }
            else if (st == RenderLevelStageEvent.Stage.AFTER_LEVEL) { b = 1; size = 1.7f; }
            else return;
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null) return;
            Vec3 cam = event.getCamera().getPosition();
            Vec3 p = mc.player.position();
            PoseStack ps = event.getPoseStack();
            ps.pushPose();
            ps.translate(-cam.x, -cam.y, -cam.z);
            MultiBufferSource.BufferSource buf = mc.renderBuffers().bufferSource();
            VertexConsumer vc = buf.getBuffer(RenderType.lines());
            LevelRenderer.renderLineBox(ps, vc, new AABB(p.x - size / 2, p.y, p.z - size / 2, p.x + size / 2, p.y + 1.8 + size / 4, p.z + size / 2), r, g, b, 1f);
            RenderSystem.disableDepthTest();
            buf.endBatch(RenderType.lines());
            RenderSystem.enableDepthTest();
            ps.popPose();
        } catch (Throwable e) {
            if (errors++ < 5) com.mojang.logging.LogUtils.getLogger().warn("[entropybot] watch probe: {}", e.toString());
        }
    }
}
