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
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Camera v2's GPU side. Two kinds of cached vertex buffers, each a textured one (the block's real sprite) and a
 * flat-colour one (blocks whose sprite could not be looked up):
 * <ul>
 *   <li>the main mesh: the dug-tunnel shell and the faces the bot's view saw (underground, plus the rays' surface faces
 *       where the scan has not been yet), rebuilt only when {@link MeshRule} says so;</li>
 *   <li>0.17.1, one mesh per scanned chunk ({@link SkyStore}): the surface, built once per scan result (a few ms each,
 *       under a per-frame budget, nearest first) and dropped with its chunk, like vanilla's section meshes.</li>
 * </ul>
 * Every face gets vanilla-like corner shading (0.17.1, {@link SkyScan#cornerShade}): without it a one-block step in the
 * ground had no visual cue and its walls read as loose triangles of grass side. Leaves follow the graphics setting:
 * with Fast they are drawn as solid textures (an odd vertex alpha tells the shader), otherwise cut out like every other
 * texture (texels with alpha below 0.1 are not drawn). Render thread only. NeoForge/vanilla render API: VertexBuffer,
 * BufferBuilder, the block atlas, our core shaders ({@link CutShaders}) with vanilla's as the fallback.
 */
final class TunnelMesh {
    static final int ALPHA = 140;              // of 255: half transparent in the see-through mode (even = cut-out texture)
    static final int ALPHA_SOLID = ALPHA + 1;  // odd = the texture is drawn solid (leaves with Fast graphics)
    static final double INSET = 0.004;
    /** Time a frame may spend building chunk meshes (at least one is built when any is due). */
    static final long CHUNK_BUILD_BUDGET_NS = 4_000_000;

    private VertexBuffer tex, flat;
    int texFaces, flatFaces, ox, oy, oz, seenDrawn, surfaceDrawn, shapedDrawn;
    boolean built, capped, builtTint, builtFancy;
    long builtVersion, builtSeenVersion, builtMs, buildMs, maxBuildMs, builds;
    int spriteFallbacks;
    // chunk meshes (0.17.1)
    private final Map<Long, ChunkMesh> chunkMeshes = new HashMap<>();
    long chunkBuilds, chunkBuildNanos, chunkMaxNanos;
    int chunkFacesDrawn, chunkMeshesDrawn, chunkWaiting;

    private static final class ChunkMesh {
        VertexBuffer tex, flat;
        long version;
        int ox, oz, faces;
        boolean fancy;
    }

    /** Per block state: the sprite and the tint index of each side, looked up once. */
    private static final class Look {
        final TextureAtlasSprite[] sprite = new TextureAtlasSprite[6];
        final int[] tint = {UNKNOWN, UNKNOWN, UNKNOWN, UNKNOWN, UNKNOWN, UNKNOWN};
    }

    private static final int UNKNOWN = -2;
    private final Map<BlockState, Look> looks = new HashMap<>();
    private final RandomSource random = RandomSource.create(42L);

    int faces() { return texFaces + flatFaces; }

    /**
     * 0.16.1: the box a face is drawn on. Full opaque blocks: the whole cell. Water and lava: the cell up to the fluid's
     * height. Other blocks: the bounds of their outline shape (a bottom slab's top face at half height, a path's at 15/16,
     * a fence post's sides at the post): exact for boxes, the bounding box for stairs, fences with arms and other
     * composite shapes (cheap: no per-part quads).
     */
    static double[] box(BlockState state, ClientLevel level, BlockPos pos) {
        if (state.isSolidRender(level, pos)) return FaceGeometry.UNIT;
        if (state.getBlock() instanceof net.minecraft.world.level.block.LiquidBlock) {
            float h = state.getFluidState().getHeight(level, pos);
            return h > 0.05f && h < 1f ? new double[]{0, 0, 0, 1, h, 1} : FaceGeometry.UNIT;
        }
        net.minecraft.world.phys.shapes.VoxelShape sh = state.getShape(level, pos);
        if (sh.isEmpty()) return FaceGeometry.UNIT;
        AABB b = sh.bounds();
        return new double[]{b.minX, b.minY, b.minZ, b.maxX, b.maxY, b.maxZ};
    }

    // ---- writing one face ---------------------------------------------------------------------------------------

    /** Where faces are written, with the scratch it needs. */
    private final class Writer {
        final BufferBuilder tbuf, fbuf;
        final Minecraft mc = Minecraft.getInstance();
        final ClientLevel level;
        final int ox, oy, oz;
        final boolean fancy;
        final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(), tmp = new BlockPos.MutableBlockPos();
        int texN, flatN, shapedN;

        Writer(BufferBuilder tbuf, BufferBuilder fbuf, ClientLevel level, int ox, int oy, int oz, boolean fancy) {
            this.tbuf = tbuf;
            this.fbuf = fbuf;
            this.level = level;
            this.ox = ox;
            this.oy = oy;
            this.oz = oz;
            this.fancy = fancy;
        }

        /** Writes the face side of block x y z with the given brightness (side shade x light dim), tinted cyan if asked. */
        void face(int x, int y, int z, int side, float shade, boolean cyan) {
            pos.set(x, y, z);
            BlockState state = level.getBlockState(pos);
            double[] bx;
            try {
                bx = box(state, level, pos);
            } catch (RuntimeException e) {
                bx = FaceGeometry.UNIT;
            }
            if (bx != FaceGeometry.UNIT) shapedN++;
            float[][] c = FaceGeometry.corners(x, y, z, side, INSET, ox, oy, oz, bx);
            float[] ao = {1f, 1f, 1f, 1f};
            try {
                double[] centre = {x - ox + (bx[0] + bx[3]) / 2, y - oy + (bx[1] + bx[4]) / 2, z - oz + (bx[2] + bx[5]) / 2};
                int[] lv = SkyScan.cornerShade(x - ox, y - oy, z - oz, side, c, centre,
                        (ax, ay, az) -> SkyScan.shades(SkyScanner.kind(level, tmp.set(ax + ox, ay + oy, az + oz))));
                for (int i = 0; i < 4; i++) ao[i] = SkyScan.shadeFactor(lv[i]);
            } catch (RuntimeException ignored) {
                // no corner shading for this face
            }
            TextureAtlasSprite sprite = null;
            int tint = -1;
            try {
                Direction d = Direction.from3DDataValue(side);
                Look look = looks.computeIfAbsent(state, st -> new Look());
                sprite = look.sprite[side];
                if (sprite == null) {
                    sprite = lookup(mc, state, d);
                    look.sprite[side] = sprite;
                }
                int ti = look.tint[side];
                if (ti == UNKNOWN) {
                    ti = tintIndex(mc, state, d);
                    look.tint[side] = ti;
                }
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
            if (cyan) {
                int[] t = SeenRule.tint(r, g, b);
                r = t[0];
                g = t[1];
                b = t[2];
            }
            boolean solidTex = !fancy && state.getBlock() instanceof net.minecraft.world.level.block.LeavesBlock;
            int alpha = solidTex ? ALPHA_SOLID : ALPHA;
            // the quad is drawn as triangles 0-1-2 and 2-3-0; when the corners on that diagonal are the brighter pair,
            // start one vertex later (same winding) so the shading is split along the other diagonal (no dark streak)
            int start = FaceGeometry.aoStart(ao);
            if (sprite != null) {
                float u0 = sprite.getU0(), u1 = sprite.getU1(), v0 = sprite.getV0(), v1 = sprite.getV1();
                for (int k = 0; k < 4; k++) {
                    int i = (start + k) & 3;
                    float[] v = c[i];
                    float s = shade * ao[i];
                    tbuf.addVertex(v[0], v[1], v[2]).setUv(u0 + (u1 - u0) * v[3], v0 + (v1 - v0) * v[4])
                            .setColor((int) (r * s), (int) (g * s), (int) (b * s), alpha);
                }
                texN++;
            } else {
                spriteFallbacks++;
                int col;
                try {
                    col = state.getMapColor(level, pos).col;
                } catch (RuntimeException e) {
                    col = 0x808080;
                }
                for (int k = 0; k < 4; k++) {
                    int i = (start + k) & 3;
                    float[] v = c[i];
                    float s = shade * ao[i];
                    fbuf.addVertex(v[0], v[1], v[2]).setColor((int) ((col >> 16 & 255) * s), (int) ((col >> 8 & 255) * s), (int) ((col & 255) * s), ALPHA);
                }
                flatN++;
            }
        }
    }

    // ---- the main mesh -------------------------------------------------------------------------------------------

    /**
     * Rebuilds the main buffers from the faces, around origin ox oy oz (keeps the floats small). Seen faces (0.16.0) are
     * dimmed by the light they were seen at ({@link SeenRule#dim}) and tinted cyan when {@code tint} (watch seen on).
     */
    void rebuild(List<SeenMesh.MeshFace> faces, ClientLevel level, int ox, int oy, int oz, long version, long seenVersion, boolean tintSeen, boolean capped, boolean fancy) {
        long t0 = System.nanoTime();
        this.ox = ox;
        this.oy = oy;
        this.oz = oz;
        int seenN = 0, surfN = 0;
        try (ByteBufferBuilder tb = new ByteBufferBuilder(Math.max(256, faces.size() * 4 * 24));
             ByteBufferBuilder fb = new ByteBufferBuilder(256)) {
            Writer w = new Writer(new BufferBuilder(tb, VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR),
                    new BufferBuilder(fb, VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR), level, ox, oy, oz, fancy);
            for (SeenMesh.MeshFace f : faces) {
                float shade = FaceGeometry.SHADE[f.side()];
                if (f.seen()) {
                    shade *= SeenRule.dim(f.light());
                    if (f.surface()) surfN++;
                    else seenN++;
                }
                w.face(f.x(), f.y(), f.z(), f.side(), shade, f.seen() && tintSeen);
            }
            tex = upload(tex, w.tbuf.build());
            flat = upload(flat, w.fbuf.build());
            texFaces = w.texN;
            flatFaces = w.flatN;
            shapedDrawn = w.shapedN;
        }
        seenDrawn = seenN;
        surfaceDrawn = surfN;
        built = true;
        this.capped = capped;
        builtVersion = version;
        builtSeenVersion = seenVersion;
        builtTint = tintSeen;
        builtFancy = fancy;
        builtMs = System.currentTimeMillis();
        buildMs = (System.nanoTime() - t0) / 1_000_000;
        builds++;
    }

    /** The rebuild's whole time (gathering the faces plus building the buffers), measured by the caller; kept as the max too. */
    void noteRebuild(long totalMs) {
        buildMs = totalMs;
        if (totalMs > maxBuildMs) maxBuildMs = totalMs;
    }

    // ---- chunk meshes (0.17.1) -----------------------------------------------------------------------------------

    /**
     * Brings the chunk meshes in line with the scan: drops meshes of chunks no longer scanned, builds missing or outdated
     * ones (or ones built for the other graphics setting), nearest to the bot first, until the frame's budget is used
     * (at least one). Render thread.
     */
    void syncChunks(SkyStore store, ClientLevel level, int botX, int botZ, boolean fancy) {
        List<SkyStore.Chunk> chunks = store.chunks();
        Set<Long> live = new HashSet<>();
        List<SkyStore.Chunk> due = new ArrayList<>();
        for (SkyStore.Chunk c : chunks) {
            long k = SkyStore.chunkKey(c.cx(), c.cz());
            live.add(k);
            ChunkMesh m = chunkMeshes.get(k);
            if (m == null || m.version != c.version() || m.fancy != fancy) due.add(c);
        }
        Iterator<Map.Entry<Long, ChunkMesh>> it = chunkMeshes.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Long, ChunkMesh> e = it.next();
            if (live.contains(e.getKey())) continue;
            closeChunk(e.getValue());
            it.remove();
        }
        int bcx = botX >> 4, bcz = botZ >> 4;
        due.sort((a, b) -> Long.compare(d2(a, bcx, bcz), d2(b, bcx, bcz)));
        long end = System.nanoTime() + CHUNK_BUILD_BUDGET_NS;
        int built = 0;
        for (SkyStore.Chunk c : due) {
            if (built > 0 && System.nanoTime() > end) break;
            buildChunk(c, level, fancy);
            built++;
        }
        chunkWaiting = due.size() - built;
    }

    private static long d2(SkyStore.Chunk c, int bcx, int bcz) {
        long dx = c.cx() - bcx, dz = c.cz() - bcz;
        return dx * dx + dz * dz;
    }

    private void buildChunk(SkyStore.Chunk c, ClientLevel level, boolean fancy) {
        long t0 = System.nanoTime();
        long k = SkyStore.chunkKey(c.cx(), c.cz());
        ChunkMesh m = chunkMeshes.computeIfAbsent(k, kk -> new ChunkMesh());
        m.ox = c.cx() << 4;
        m.oz = c.cz() << 4;
        long[] faces = c.faces();
        try (ByteBufferBuilder tb = new ByteBufferBuilder(Math.max(256, faces.length * 4 * 24));
             ByteBufferBuilder fb = new ByteBufferBuilder(256)) {
            Writer w = new Writer(new BufferBuilder(tb, VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR),
                    new BufferBuilder(fb, VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR), level, m.ox, 0, m.oz, fancy);
            for (int i = 0; i < faces.length; i++) {
                long fk = faces[i];
                int side = (int) (fk & 7);
                long cell = fk >> 3;
                int x = CellKey.x(cell), y = CellKey.y(cell), z = CellKey.z(cell);
                w.face(x, y, z, side, FaceGeometry.SHADE[side] * SeenRule.dim(c.light()[i]), false);
            }
            m.tex = upload(m.tex, w.tbuf.build());
            m.flat = upload(m.flat, w.fbuf.build());
            m.faces = w.texN + w.flatN;
        }
        m.version = c.version();
        m.fancy = fancy;
        long ns = System.nanoTime() - t0;
        chunkBuilds++;
        chunkBuildNanos += ns;
        if (ns > chunkMaxNanos) chunkMaxNanos = ns;
    }

    private static void closeChunk(ChunkMesh m) {
        if (m.tex != null) m.tex.close();
        if (m.flat != null) m.flat.close();
        m.tex = null;
        m.flat = null;
    }

    int chunkMeshes() { return chunkMeshes.size(); }

    // ---- sprites -------------------------------------------------------------------------------------------------

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

    /**
     * The tint index of the side's first quad (grass, leaves...), or -1. Water has no quads (a fluid renderer draws it):
     * index 0, which BlockColors maps to the biome's water colour. Looked up once per state and side (see {@link Look}).
     */
    private int tintIndex(Minecraft mc, BlockState state, Direction side) {
        random.setSeed(42L);
        BakedModel model = mc.getBlockRenderer().getBlockModel(state);
        List<BakedQuad> quads = model.getQuads(state, side, random, net.neoforged.neoforge.client.model.data.ModelData.EMPTY, null);
        if (!quads.isEmpty()) return quads.get(0).isTinted() ? quads.get(0).getTintIndex() : -1;
        random.setSeed(42L);
        for (BakedQuad q : model.getQuads(state, null, random, net.neoforged.neoforge.client.model.data.ModelData.EMPTY, null))
            if (q.getDirection() == side) return q.isTinted() ? q.getTintIndex() : -1;
        if (state.getBlock() instanceof net.minecraft.world.level.block.LiquidBlock && state.getFluidState().is(net.minecraft.tags.FluidTags.WATER)) return 0;
        return -1;
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

    // ---- drawing -------------------------------------------------------------------------------------------------

    /**
     * Draws the main mesh and the chunk meshes for this frame's camera. Normal: half transparent, blended, depth test and
     * culling off. Dollhouse ({@code watch tunnel dollhouse}, the default): opaque, back-face culling and the depth test
     * with depth writes, so each face shows only from its air side and nearer faces hide farther ones. Our shaders
     * ({@link CutShaders}) are used whenever they are loaded: they apply the cutaway when {@code cut} is given (the bot's
     * box min x y z, max x y z, the outer margin; world coordinates) and the cut-out/solid texture rule always. When they
     * are not loaded, or a draw with them throws, vanilla's shaders draw (no cut; vanilla's alpha-0 discard) and the
     * caller reports it. Returns true when the cut was applied. The render state is restored in {@code finally}.
     */
    boolean draw(Matrix4f modelView, Matrix4f projection, Vec3 cam, boolean dollhouse, double[] cut) {
        if (CutShaders.ready()) {
            try {
                drawWith(modelView, projection, cam, dollhouse, CutShaders.tex(), CutShaders.flat(), cut, true);
                return cut != null;
            } catch (RuntimeException e) {
                CutShaders.broken(e.toString());             // fall through: this frame and the next draw without our shaders
            }
        }
        drawWith(modelView, projection, cam, dollhouse, GameRenderer.getPositionTexColorShader(), GameRenderer.getPositionColorShader(), null, false);
        return false;
    }

    private void setCut(ShaderInstance s, Vec3 cam, double[] cut, int mx, int my, int mz) {
        s.safeGetUniform("CutCam").set((float) (cam.x - mx), (float) (cam.y - my), (float) (cam.z - mz));
        if (cut == null) {
            s.safeGetUniform("CutMargin").set(-1f, -1f);
            return;
        }
        s.safeGetUniform("CutMin").set((float) (cut[0] - mx), (float) (cut[1] - my), (float) (cut[2] - mz));
        s.safeGetUniform("CutMax").set((float) (cut[3] - mx), (float) (cut[4] - my), (float) (cut[5] - mz));
        s.safeGetUniform("CutMargin").set((float) (cut[6] * Cutaway.INNER_SHARE), (float) cut[6]);
        s.safeGetUniform("CutCone").set(cut.length > 7 ? (float) cut[7] : 0f);   // 0.19.0: > 0 = cone mode with that radius
    }

    private void drawWith(Matrix4f modelView, Matrix4f projection, Vec3 cam, boolean dollhouse, ShaderInstance texShader, ShaderInstance flatShader,
                          double[] cut, boolean ours) {
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
        int drawnFaces = 0, drawnMeshes = 0;
        try {
            RenderSystem.setShaderTexture(0, TextureAtlas.LOCATION_BLOCKS);
            drawPair(modelView, projection, cam, texShader, flatShader, cut, ours, tex, flat, ox, oy, oz);
            for (ChunkMesh m : chunkMeshes.values()) {
                if (m.tex == null && m.flat == null) continue;
                drawPair(modelView, projection, cam, texShader, flatShader, cut, ours, m.tex, m.flat, m.ox, 0, m.oz);
                drawnFaces += m.faces;
                drawnMeshes++;
            }
        } finally {
            VertexBuffer.unbind();
            RenderSystem.depthMask(true);
            RenderSystem.enableDepthTest();
            RenderSystem.enableCull();
            RenderSystem.disableBlend();
        }
        chunkFacesDrawn = drawnFaces;
        chunkMeshesDrawn = drawnMeshes;
    }

    private void drawPair(Matrix4f modelView, Matrix4f projection, Vec3 cam, ShaderInstance texShader, ShaderInstance flatShader, double[] cut, boolean ours,
                          VertexBuffer t, VertexBuffer f, int mx, int my, int mz) {
        if (t == null && f == null) return;
        Matrix4f mv = new Matrix4f(modelView).translate((float) (mx - cam.x), (float) (my - cam.y), (float) (mz - cam.z));
        if (t != null) {
            if (ours) setCut(texShader, cam, cut, mx, my, mz);
            t.bind();
            t.drawWithShader(mv, projection, texShader);
        }
        if (f != null) {
            if (ours) setCut(flatShader, cam, cut, mx, my, mz);
            f.bind();
            f.drawWithShader(mv, projection, flatShader);
        }
    }

    /**
     * The bot's outline through the rock (yellow lines round its box, depth test off), so the viewer sees where it is in
     * the tunnel; the bot's own model is hidden by the ground from a camera in the sky. Rebuilt every frame (24 vertices).
     */
    void drawMarker(Matrix4f modelView, Matrix4f projection, Vec3 cam, AABB box) {
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
        for (ChunkMesh m : chunkMeshes.values()) closeChunk(m);
        chunkMeshes.clear();
        marker = null;
        tex = null;
        flat = null;
        built = false;
        texFaces = 0;
        flatFaces = 0;
        seenDrawn = 0;
        surfaceDrawn = 0;
        shapedDrawn = 0;
        chunkFacesDrawn = 0;
        chunkMeshesDrawn = 0;
        looks.clear();
    }
}
