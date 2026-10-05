package io.github.mojolowjo.entropybot.watchview;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Camera v2's GPU side: the shell faces as two cached vertex buffers (textured with the neighbour block's real sprite,
 * and a flat-colour one for blocks whose sprite could not be looked up), rebuilt only when {@link MeshRule} says so,
 * and drawn every frame half transparent with the depth test off (so they show through the rock between the camera
 * and the tunnel; only these faces are drawn, so nothing else is revealed). Render thread only. NeoForge/vanilla
 * render API: VertexBuffer, BufferBuilder, the block atlas, GameRenderer's position-tex-colour shader.
 */
final class TunnelMesh {
    static final int ALPHA = 140;              // of 255: half transparent
    static final double INSET = 0.004;

    private VertexBuffer tex, flat;
    int texFaces, flatFaces, ox, oy, oz, seenDrawn;
    boolean built, capped, builtTint;
    long builtVersion, builtSeenVersion, builtMs, buildMs;
    int spriteFallbacks;
    private final Map<BlockState, TextureAtlasSprite[]> sprites = new HashMap<>();
    private final RandomSource random = RandomSource.create(42L);

    int faces() { return texFaces + flatFaces; }

    /**
     * Rebuilds both buffers from the faces, around origin ox oy oz (keeps the floats small). Seen faces (0.16.0) are
     * dimmed by the light they were seen at ({@link SeenRule#dim}) and tinted cyan when {@code tint} (watch seen on).
     */
    void rebuild(List<SeenMesh.MeshFace> faces, ClientLevel level, int ox, int oy, int oz, long version, long seenVersion, boolean tintSeen, boolean capped) {
        long t0 = System.nanoTime();
        this.ox = ox;
        this.oy = oy;
        this.oz = oz;
        Minecraft mc = Minecraft.getInstance();
        int texN = 0, flatN = 0, seenN = 0;
        try (ByteBufferBuilder tb = new ByteBufferBuilder(Math.max(256, faces.size() * 4 * 24));
             ByteBufferBuilder fb = new ByteBufferBuilder(256)) {
            BufferBuilder tbuf = new BufferBuilder(tb, VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
            BufferBuilder fbuf = new BufferBuilder(fb, VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
            BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
            for (SeenMesh.MeshFace f : faces) {
                pos.set(f.x(), f.y(), f.z());
                BlockState state = level.getBlockState(pos);
                float shade = FaceGeometry.SHADE[f.side()];
                if (f.seen()) {
                    shade *= SeenRule.dim(f.light());
                    seenN++;
                }
                float[][] c = FaceGeometry.corners(f.x(), f.y(), f.z(), f.side(), INSET, ox, oy, oz);
                TextureAtlasSprite sprite = null;
                int tint = -1;
                try {
                    Direction side = Direction.from3DDataValue(f.side());
                    TextureAtlasSprite[] s = sprites.computeIfAbsent(state, st -> new TextureAtlasSprite[6]);
                    sprite = s[f.side()];
                    if (sprite == null) {
                        sprite = lookup(mc, state, side);
                        s[f.side()] = sprite;
                    }
                    int ti = tintIndex(mc, state, side);
                    if (ti >= 0) tint = mc.getBlockColors().getColor(state, level, pos, ti);
                } catch (RuntimeException e) {
                    sprite = null;
                }
                int r = 255, g = 255, b = 255;
                if (tint != -1) {
                    r = tint >> 16 & 255;
                    g = tint >> 8 & 255;
                    b = tint & 255;
                }
                if (f.seen() && tintSeen) {
                    int[] t = SeenRule.tint(r, g, b);
                    r = t[0];
                    g = t[1];
                    b = t[2];
                }
                if (sprite != null) {
                    float u0 = sprite.getU0(), u1 = sprite.getU1(), v0 = sprite.getV0(), v1 = sprite.getV1();
                    for (float[] v : c)
                        tbuf.addVertex(v[0], v[1], v[2]).setUv(u0 + (u1 - u0) * v[3], v0 + (v1 - v0) * v[4])
                                .setColor((int) (r * shade), (int) (g * shade), (int) (b * shade), ALPHA);
                    texN++;
                } else {
                    spriteFallbacks++;
                    int col;
                    try {
                        col = state.getMapColor(level, pos).col;
                    } catch (RuntimeException e) {
                        col = 0x808080;
                    }
                    for (float[] v : c)
                        fbuf.addVertex(v[0], v[1], v[2]).setColor((int) ((col >> 16 & 255) * shade), (int) ((col >> 8 & 255) * shade),
                                (int) ((col & 255) * shade), ALPHA);
                    flatN++;
                }
            }
            tex = upload(tex, tbuf.build());
            flat = upload(flat, fbuf.build());
        }
        texFaces = texN;
        flatFaces = flatN;
        seenDrawn = seenN;
        built = true;
        this.capped = capped;
        builtVersion = version;
        builtSeenVersion = seenVersion;
        builtTint = tintSeen;
        builtMs = System.currentTimeMillis();
        buildMs = (System.nanoTime() - t0) / 1_000_000;
    }

    /**
     * The face's sprite: the model's quad for that side, else any quad facing that way, else the particle icon. NeoForge's
     * getQuads with ModelData (modded models may only implement that one); no world data, so connected textures show plain.
     */
    private TextureAtlasSprite lookup(Minecraft mc, BlockState state, Direction side) {
        BakedModel model = mc.getBlockRenderer().getBlockModel(state);
        random.setSeed(42L);
        List<BakedQuad> quads = model.getQuads(state, side, random, net.neoforged.neoforge.client.model.data.ModelData.EMPTY, null);
        if (!quads.isEmpty()) return quads.get(0).getSprite();
        random.setSeed(42L);
        for (BakedQuad q : model.getQuads(state, null, random, net.neoforged.neoforge.client.model.data.ModelData.EMPTY, null))
            if (q.getDirection() == side) return q.getSprite();
        return model.getParticleIcon(net.neoforged.neoforge.client.model.data.ModelData.EMPTY);
    }

    /** The tint index of the side's first quad (grass, leaves...), or -1. */
    private int tintIndex(Minecraft mc, BlockState state, Direction side) {
        random.setSeed(42L);
        List<BakedQuad> quads = mc.getBlockRenderer().getBlockModel(state).getQuads(state, side, random,
                net.neoforged.neoforge.client.model.data.ModelData.EMPTY, null);
        return !quads.isEmpty() && quads.get(0).isTinted() ? quads.get(0).getTintIndex() : -1;
    }

    private static VertexBuffer upload(VertexBuffer vb, MeshData data) {
        if (data == null) {                         // nothing of this kind: drop the old buffer
            if (vb != null) vb.close();
            return null;
        }
        if (vb == null) vb = new VertexBuffer(VertexBuffer.Usage.STATIC);
        vb.bind();
        vb.upload(data);
        VertexBuffer.unbind();
        return vb;
    }

    /**
     * Draws the cached faces for this frame's camera. Normal: half transparent, blended, depth test and culling off (every
     * layer shows through every other). Dollhouse (0.16.0, {@code watch tunnel dollhouse}): opaque (no blending), back-face
     * culling and the depth test on with depth writes, so each face shows only from its air side (its winding's front,
     * checked by FaceGeometry.frontNormal in JUnit): from a camera outside the rock the floor and the far walls show and the
     * ceiling and near walls drop out, and nearer faces hide farther ones. The veil cleared the depth buffer before (0.15.4),
     * so only the view's own entities and faces take part. The state is restored in {@code finally} either way.
     */
    void draw(Matrix4f modelView, Matrix4f projection, Vec3 cam, boolean dollhouse) {
        if (tex == null && flat == null) return;
        Matrix4f mv = new Matrix4f(modelView).translate((float) (ox - cam.x), (float) (oy - cam.y), (float) (oz - cam.z));
        if (dollhouse) {
            RenderSystem.disableBlend();
            RenderSystem.enableCull();
            RenderSystem.enableDepthTest();
            RenderSystem.depthFunc(org.lwjgl.opengl.GL11.GL_LEQUAL);
            RenderSystem.depthMask(true);
        } else {
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            RenderSystem.disableCull();
            RenderSystem.disableDepthTest();
            RenderSystem.depthMask(false);
        }
        try {
            if (tex != null) {
                RenderSystem.setShaderTexture(0, TextureAtlas.LOCATION_BLOCKS);
                tex.bind();
                tex.drawWithShader(mv, projection, GameRenderer.getPositionTexColorShader());
            }
            if (flat != null) {
                flat.bind();
                flat.drawWithShader(mv, projection, GameRenderer.getPositionColorShader());
            }
        } finally {
            VertexBuffer.unbind();
            RenderSystem.depthMask(true);
            RenderSystem.enableDepthTest();
            RenderSystem.enableCull();
            RenderSystem.disableBlend();
        }
    }

    /**
     * The bot's outline through the rock (yellow lines round its box, depth test off), so the viewer sees where it is in
     * the tunnel; the bot's own model is hidden by the ground from a camera in the sky. Rebuilt every frame (24 vertices).
     */
    void drawMarker(Matrix4f modelView, Matrix4f projection, Vec3 cam, net.minecraft.world.phys.AABB box) {
        float x0 = (float) (box.minX - cam.x), y0 = (float) (box.minY - cam.y), z0 = (float) (box.minZ - cam.z);
        float x1 = (float) (box.maxX - cam.x), y1 = (float) (box.maxY - cam.y), z1 = (float) (box.maxZ - cam.z);
        float[][] e = {
                {x0, y0, z0, x1, y0, z0}, {x0, y0, z1, x1, y0, z1}, {x0, y1, z0, x1, y1, z0}, {x0, y1, z1, x1, y1, z1},
                {x0, y0, z0, x0, y1, z0}, {x1, y0, z0, x1, y1, z0}, {x0, y0, z1, x0, y1, z1}, {x1, y0, z1, x1, y1, z1},
                {x0, y0, z0, x0, y0, z1}, {x1, y0, z0, x1, y0, z1}, {x0, y1, z0, x0, y1, z1}, {x1, y1, z0, x1, y1, z1}};
        try (ByteBufferBuilder bb = new ByteBufferBuilder(24 * 16)) {
            BufferBuilder b = new BufferBuilder(bb, VertexFormat.Mode.DEBUG_LINES, DefaultVertexFormat.POSITION_COLOR);
            for (float[] l : e) {
                b.addVertex(l[0], l[1], l[2]).setColor(255, 220, 40, 255);
                b.addVertex(l[3], l[4], l[5]).setColor(255, 220, 40, 255);
            }
            MeshData md = b.build();
            if (md == null) return;
            if (marker == null) marker = new VertexBuffer(VertexBuffer.Usage.DYNAMIC);
            marker.bind();
            marker.upload(md);
            RenderSystem.disableDepthTest();
            RenderSystem.depthMask(false);
            RenderSystem.lineWidth(2f);
            try {
                marker.drawWithShader(new Matrix4f(modelView), projection, GameRenderer.getPositionColorShader());
            } finally {
                VertexBuffer.unbind();
                RenderSystem.lineWidth(1f);
                RenderSystem.depthMask(true);
                RenderSystem.enableDepthTest();
            }
        }
    }

    private VertexBuffer marker;

    /** Frees the GPU buffers (tunnel view off). */
    void close() {
        if (tex != null) tex.close();
        if (flat != null) flat.close();
        if (marker != null) marker.close();
        marker = null;
        tex = null;
        flat = null;
        built = false;
        texFaces = 0;
        flatFaces = 0;
        seenDrawn = 0;
        sprites.clear();
    }
}
