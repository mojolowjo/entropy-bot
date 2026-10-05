package io.github.mojolowjo.entropybot.watchview;

import io.github.mojolowjo.entropybot.Core;
import io.github.mojolowjo.entropybot.clear.ClearEngine;
import io.github.mojolowjo.entropybot.commands.SelfCheck;
import io.github.mojolowjo.entropybot.engine.WatchCamera;
import io.github.mojolowjo.entropybot.guard.Guard;
import io.github.mojolowjo.entropybot.guard.MixinFlags;
import io.github.mojolowjo.entropybot.mixin.WatchMixinCameraAccess;
import io.github.mojolowjo.entropybot.recorder.Recorder;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Watch camera v2, the tunnel view (docs/CAMERA_PLAN.md; {@code watch tunnel}). The game side of the pure classes in
 * this package:
 * <ul>
 *   <li>known air ({@link KnownAir}): every block a clear broke ({@code ClearEngine.brokenSink}), the cells the bot
 *       stands in underground (sampled each tick, its own trail; the recorder's trail seeds it when the view starts),
 *       and the open cells it sees from there underground ({@link Sight}: radius 6 inside a cave's visited cells,
 *       2 elsewhere). Collected while the view is off too, so it has history; saved to {@code knownair.bin}.</li>
 *   <li>the camera (0.15.4: noclip): above and behind the bot along the view's yaw ({@link TunnelPose}), through rock
 *       and trees like the spectator camera; angles in {@code ComputeCameraAngles}, the position in {@code ComputeFov}
 *       through the {@link WatchMixinCameraAccess} invoker.</li>
 *   <li>the real world hidden ({@link WorldVeil}, {@link TunnelScene}): at {@code RenderLevelStageEvent.Stage.AFTER_LEVEL}
 *       the frame is cleared and only the view's own things are drawn: the bot and nearby mobs, the faces ({@link Shell}
 *       into {@link TunnelMesh}) and the bot's outline. A frame guard on {@code RenderFrameEvent.Pre/Post} clears any
 *       frame the veil missed and turns the view off after 3 in a row. While on, the terrain layers are skipped
 *       ({@code WatchMixinLevelRenderer}, a frame-time saving only).</li>
 * </ul>
 * Any exception is counted, logged rate-limited, and turns the view off; the frame loop never sees it.
 */
public final class TunnelView {
    public static final TunnelView INSTANCE = new TunnelView();
    public static final String FILE = "knownair.bin";
    static final int SIGHT_CAVE = 6, SIGHT_ELSEWHERE = 2, SIGHT_MAX = 400;
    static final double DEFAULT_HEIGHT = 4;
    static final long SAVE_EVERY_TICKS = 6000;

    private final KnownAir known = new KnownAir();
    private final TunnelMesh mesh = new TunnelMesh();
    private volatile boolean on;
    private volatile float yaw;
    private volatile double height = DEFAULT_HEIGHT;
    private long lastCell = Long.MIN_VALUE;
    private String lastDim;
    private Path file;
    private volatile boolean dirty;
    private long lastSaveTick;
    private volatile String fileNote = "not loaded yet";
    private final AtomicLong frames = new AtomicLong(), positions = new AtomicLong();
    private volatile long lastDrawMs, onSinceMs;
    private TunnelPose.Pose pose, pending;
    private volatile String how = "-";
    private int errors;
    private volatile String offByError, lastError;
    private boolean warnedNoDraw;
    private long statusFrames, statusPositions, statusMs, statusSkips;
    private final WorldVeil.Guard guard = new WorldVeil.Guard();
    private final AtomicLong skippedLayers = new AtomicLong();
    private volatile long lastFrameMs, skipAtStart;
    private volatile int lastEntities;
    private int missLogs;

    private TunnelView() {}

    public boolean on() { return on; }

    private double walkLastX, walkLastZ;
    private float walkLastPlayerYaw;
    private boolean walkHaveLast;

    /** Once a client tick (from WatchCamera.tick): the view turns slowly to match the way the bot is moving. Never throws. */
    public void tickYaw() {
        if (!on) { walkHaveLast = false; return; }
        try {
            net.minecraft.client.player.LocalPlayer p = Minecraft.getInstance().player;
            if (p == null) return;
            double x = p.getX(), z = p.getZ();
            float py = p.getYRot();       // watch steer: the human drives = hold the yaw, turning only with the player's own turn
            if (walkHaveLast) yaw = io.github.mojolowjo.entropybot.engine.SteerRules.nextYaw(io.github.mojolowjo.entropybot.engine.WatchSteer.INSTANCE.humanDriving(),
                    yaw, TunnelPose.followWalk(yaw, x - walkLastX, z - walkLastZ), WatchCamera.turn(walkLastPlayerYaw, py));
            walkLastPlayerYaw = py;
            walkLastX = x;
            walkLastZ = z;
            walkHaveLast = true;
        } catch (Throwable t) {
            fail("tunnel yaw", t, false);
        }
    }

    public float yaw() { return yaw; }

    public void setYaw(float y) {
        yaw = y;
        pose = null;
    }

    public double height() { return height; }

    /** 0.16.0 {@code watch tunnel dollhouse}: back-face culling and the depth test for the faces (see TunnelMesh.draw). The default since TLL 32; kept in watch.json (WatchSettings). */
    private volatile boolean dollhouse = true;

    /** The yaw the camera was last drawn with (the eased look-at yaw of the pose), else the view's yaw. For watch steer. */
    public float renderedYaw() {
        TunnelPose.Pose p = pose;
        return p != null ? p.yaw() : yaw;
    }

    public boolean dollhouse() { return dollhouse; }

    public void setDollhouse(boolean on) { dollhouse = on; }

    /**
     * 0.16.1 {@code watch tunnel cut}: hide the faces between the camera and the bot (a cylinder of {@link #cutRadius()}
     * along that line, {@link Cutaway}; drawn by the {@link CutShaders}). On by default: the owner asked for it
     * (2026-10-04). Per session, like the dollhouse switch.
     */
    private volatile boolean cut = true;
    private volatile double cutRadius = Cutaway.DEFAULT_RADIUS;
    private final AtomicLong cutFrames = new AtomicLong();
    private int cutMissLogs;

    public boolean cut() { return cut; }

    public void setCut(boolean on) {
        cut = on;
        cutMissLogs = 0;
    }

    public double cutRadius() { return cutRadius; }

    public void setCutRadius(double r) { cutRadius = r; }

    /** The status words for the cutaway. */
    public String cutReport() {
        if (!cut) return "off (watch tunnel cut on)";
        String problem = CutShaders.problem();
        if (problem != null) return "on but NOT working: " + problem + " - the faces are drawn without it (see check)";
        return "on (radius " + cutRadius + ": faces that cover the bot on the screen and are nearer than it are not drawn, "
                + "with a margin of " + cutRadius + " blocks round its box and a dithered rim; " + cutFrames.get() + " frames cut)";
    }

    /** The graphics setting the faces were last drawn for ("fast" / "fancy"), for status. */
    private volatile String graphicsWord = "-";

    /** The status words for the graphics setting (0.17.1). */
    static String graphicsReport(String word) {
        return switch (word) {
            case "fast" -> "fast (leaves drawn as solid cubes, faces between leaves skipped; other textures cut out)";
            case "fancy" -> "fancy or fabulous (leaves cut out: the canopy shows gaps, faces between leaves drawn)";
            default -> "not drawn yet";
        };
    }

    /** The status words for the face drawing mode. */
    public static String drawMode(boolean dollhouse) {
        return dollhouse ? "dollhouse (back-face culling and depth test on, opaque: only floors and far walls facing the camera)"
                : "see-through (every face blended at alpha " + TunnelMesh.ALPHA + "/255, depth test and culling off)";
    }

    public void setHeight(double h) {
        height = h;
        pose = null;
    }

    /** At mod start: every block a clear breaks becomes known air. */
    public void init() {
        ClearEngine.brokenSink = p -> {
            try {
                ClientLevel level = Minecraft.getInstance().level;
                if (level != null && known.add(Guard.dimOf(level), p.x(), p.y(), p.z())) dirty = true;
            } catch (RuntimeException e) {
                fail("broken cell", e, false);
            }
        };
    }

    /** The view's camera position hook is in (the invoker mixin applied). */
    public boolean positionHookIn() {
        if (MixinFlags.cameraPosApplied) return true;
        try {
            return (Object) Minecraft.getInstance().gameRenderer.getMainCamera() instanceof WatchMixinCameraAccess;
        } catch (Throwable t) {
            return false;
        }
    }

    public void start(float startYaw) {
        yaw = startYaw;
        pose = null;
        pending = null;
        offByError = null;
        warnedNoDraw = false;
        onSinceMs = System.currentTimeMillis();
        lastDrawMs = 0;
        statusMs = 0;
        guard.resetRun();
        skipAtStart = skippedLayers.get();
        SkyScanner.INSTANCE.reset();                       // 0.17.1: a scan stopped by errors gets another try
        on = true;
        Minecraft.getInstance().execute(this::seedFromTrail);
    }

    public void stop() {
        if (!on) return;
        on = false;
        pending = null;
        Minecraft.getInstance().execute(mesh::close);
    }

    // ---- known air ------------------------------------------------------------------------------------------------

    /** Loads knownair.bin from the mod's folder (once, at the first tick in a world). A broken file is set aside. */
    public void load(Path root) {
        file = root.resolve(FILE);
        if (!Files.exists(file)) {
            fileNote = FILE + ": none yet";
            return;
        }
        try (InputStream in = new java.io.BufferedInputStream(Files.newInputStream(file))) {
            int n = known.read(in);
            fileNote = FILE + ": " + n + " cells";
        } catch (Exception e) {
            known.clear();
            try {
                Files.move(file, root.resolve("knownair.broken.bin"), StandardCopyOption.REPLACE_EXISTING);
            } catch (Exception ignored) {
            }
            fileNote = FILE + ": broken (" + e.getMessage() + "), set aside, starting empty";
        }
    }

    public String fileNote() { return fileNote; }

    /** Writes knownair.bin when something changed (a snapshot here, the disk write on a small thread). */
    public void save() { save(false); }

    /** sync: write on this thread (leaving the world, the game quitting). */
    public void save(boolean sync) {
        if (!dirty || file == null) return;
        dirty = false;
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            known.write(bytes);
            byte[] data = bytes.toByteArray();
            Path target = file;
            Runnable write = () -> {
                try {
                    Path tmp = target.resolveSibling(FILE + ".tmp");
                    Files.write(tmp, data);
                    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (Exception e) {
                    fileNote = FILE + ": couldn't write (" + e.getMessage() + ")";
                    com.mojang.logging.LogUtils.getLogger().warn("[entropybot] watch tunnel: couldn't write {}: {}", FILE, e.toString());
                }
            };
            if (sync) {
                write.run();
                return;
            }
            Thread t = new Thread(write, "entropybot-knownair");
            t.setDaemon(true);
            t.start();
        } catch (Exception e) {
            fail("save", e, false);
        }
    }

    /** Once a client tick in a world (also while the view is off): sample the bot's cell and what it sees. Never throws. */
    public void tick(long tick) {
        try {
            Minecraft mc = Minecraft.getInstance();
            LocalPlayer p = mc.player;
            ClientLevel level = mc.level;
            if (p == null || level == null) return;
            String dim = Guard.dimOf(level);
            BlockPos feet = p.blockPosition();
            long key = feet.asLong();
            if (key != lastCell || !dim.equals(lastDim)) {
                lastCell = key;
                lastDim = dim;
                sample(level, dim, feet, p.getEyePosition());
            }
            if (tick - lastSaveTick >= SAVE_EVERY_TICKS) {
                lastSaveTick = tick;
                save();
            }
            if (on) {
                long now = System.currentTimeMillis();
                // only when frames ARE being rendered: a game that stalls for a second (a hitch) is not the view's fault
                if (WatchChecks.notDrawing(true, now - onSinceMs, lastDrawMs == 0 ? -1 : now - lastDrawMs, lastFrameMs == 0 ? -1 : now - lastFrameMs)) {
                    if (!warnedNoDraw) {
                        warnedNoDraw = true;
                        com.mojang.logging.LogUtils.getLogger().warn("[entropybot] watch tunnel: on, frames are rendered, but none ran the tunnel view's drawing in the last second");
                    }
                } else warnedNoDraw = false;
            }
        } catch (Throwable t) {
            fail("tick", t, false);
        }
    }

    private void sample(ClientLevel level, String dim, BlockPos feet, Vec3 eye) {
        BlockPos head = feet.above();
        if (level.canSeeSky(head)) return;                        // the surface is no tunnel: nothing to remember
        boolean changed = false;
        if (kind(level, feet) == Shell.OPEN) changed |= known.add(dim, feet.getX(), feet.getY(), feet.getZ());
        if (kind(level, head) == Shell.OPEN) changed |= known.add(dim, head.getX(), head.getY(), head.getZ());
        int radius = Core.INSTANCE.caves.visitedAt(dim, feet.getX(), feet.getY(), feet.getZ()) ? SIGHT_CAVE : SIGHT_ELSEWHERE;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        List<Long> seen = Sight.seen(eye.x, eye.y, eye.z, radius, SIGHT_MAX, (x, y, z) -> kind(level, m.set(x, y, z)),
                (x, y, z) -> !level.canSeeSky(m.set(x, y, z)));
        for (long k : seen) changed |= known.add(dim, CellKey.x(k), CellKey.y(k), CellKey.z(k));
        if (changed) dirty = true;
    }

    /** At the view's start: the recorder's trail of the last day (where it stood underground) as known air. */
    private void seedFromTrail() {
        try {
            Minecraft mc = Minecraft.getInstance();
            ClientLevel level = mc.level;
            if (level == null) return;
            String dim = Guard.dimOf(level);
            Recorder r = Core.INSTANCE.recorder;
            List<Recorder.TrailPoint> trail = r.trail(System.currentTimeMillis() - 24L * 3600_000L, 20_000);
            BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
            boolean changed = false;
            for (Recorder.TrailPoint t : trail) {
                if (!dim.equals(t.dim())) continue;
                for (int dy = 0; dy <= 1; dy++) {
                    m.set(t.x(), t.y() + dy, t.z());
                    if (!level.isLoaded(m) || level.canSeeSky(m)) continue;
                    if (kind(level, m) == Shell.OPEN) changed |= known.add(dim, t.x(), t.y() + dy, t.z());
                }
            }
            if (changed) dirty = true;
        } catch (Throwable t) {
            fail("trail", t, false);
        }
    }

    /** OPEN, SOLID (a full opaque block: stone, ores, dirt...) or UNKNOWN (not loaded). */
    static int kind(ClientLevel level, BlockPos pos) {
        if (!level.isLoaded(pos)) return Shell.UNKNOWN;
        BlockState s = level.getBlockState(pos);
        return s.isSolidRender(level, pos) ? Shell.SOLID : Shell.OPEN;
    }

    // ---- camera ---------------------------------------------------------------------------------------------------

    /**
     * ComputeCameraAngles: this frame's pose; the angles now, the position in {@link #onFov}. True when it set them.
     * Only in a frame the guard armed (the view was on at RenderFrameEvent.Pre), so every frame with a moved camera is
     * checked for the veil at RenderFrameEvent.Post.
     */
    public boolean onAngles(ViewportEvent.ComputeCameraAngles e) {
        if (!on || !guard.armed()) return false;
        try {
            Camera cam = e.getCamera();
            Entity ent = cam.getEntity();
            ClientLevel level = Minecraft.getInstance().level;
            if (ent == null || level == null) return false;
            Vec3 eye = ent.getEyePosition((float) e.getPartialTick());
            TunnelPose.Pose want = TunnelPose.place(eye.x, eye.y, eye.z, yaw, height, WatchCamera.INSTANCE.distance());
            pose = TunnelPose.follow(pose, want, 0.15, eye.x, eye.y, eye.z);
            e.setYaw(pose.yaw());
            e.setPitch(pose.pitch());
            e.setRoll(0f);
            pending = pose;
            how = pose.how();
            return true;
        } catch (Throwable t) {
            fail("camera angles", t, true);
            return false;
        }
    }

    /** ComputeFov (the level render's call, after Camera.setup): move the camera to this frame's pose. */
    public void onFov(ViewportEvent.ComputeFov e) {
        TunnelPose.Pose p = pending;
        if (!on || p == null || !e.usedConfiguredFov() || !guard.armed()) return;
        pending = null;
        try {
            if ((Object) e.getCamera() instanceof WatchMixinCameraAccess a) {
                guard.placed();                       // before the move: a frame with a moved camera is always checked
                a.entropybot$setPosition(new Vec3(p.x(), p.y(), p.z()));
                positions.incrementAndGet();
            } else {
                fail("camera position", new IllegalStateException("the camera position hook (WatchMixinCameraAccess) is not in"), true);
            }
        } catch (Throwable t) {
            fail("camera position", t, true);
        }
    }

    // ---- faces ----------------------------------------------------------------------------------------------------

    /**
     * The terrain-skip hook (WatchMixinLevelRenderer, render thread) asks: skip this section layer? Yes while the view is
     * on (the veil clears the frame anyway); counted, so status can tell a hook that fires from one that doesn't.
     */
    public boolean skipTerrain() {
        if (!on) return false;
        skippedLayers.incrementAndGet();
        return true;
    }

    /** RenderFrameEvent.Pre (render thread): arm the guard for this frame when the view is on. */
    public void onFramePre() {
        try {
            guard.pre(on && Minecraft.getInstance().level != null);
        } catch (Throwable t) {
            fail("frame start", t, true);
        }
    }

    /**
     * RenderFrameEvent.Post (render thread), before the frame is shown: a frame that was rendered with the view on (or
     * with the camera moved) but without the veil is cleared now, so it never shows the world; 3 in a row turn the view
     * off (the camera goes back to the bot).
     */
    public void onFramePost() {
        WorldVeil.Verdict v;
        try {
            lastFrameMs = System.currentTimeMillis();
            v = guard.post(Minecraft.getInstance().level != null, on);
        } catch (Throwable t) {
            v = WorldVeil.Verdict.CLEAR_AND_STOP;
        }
        if (v == WorldVeil.Verdict.NOTHING) return;
        try {
            TunnelScene.clearWorld();
        } catch (Throwable t) {
            fail("emergency clear", t, true);
        } finally {
            TunnelScene.restoreState();
        }
        if (on && missLogs++ < 5) com.mojang.logging.LogUtils.getLogger().warn("[entropybot] watch tunnel: a frame was rendered without the world-hiding step; cleared it ({} so far)", guard.misses());
        if (v == WorldVeil.Verdict.CLEAR_AND_STOP)
            fail("world hiding", new IllegalStateException(WorldVeil.MISSES_TO_STOP + " frames in a row rendered without the world-hiding step (AFTER_LEVEL); turned the view off"), true);
    }

    /**
     * RenderLevelStageEvent at AFTER_LEVEL (GameRenderer.renderLevel, after the whole level render): the veil. Clear the
     * frame, then draw the bot and mobs, the faces (rebuilt if due) and the bot's outline; restore the render state.
     */
    public void onStage(RenderLevelStageEvent e) {
        if (!on || !guard.armed() || e.getStage() != RenderLevelStageEvent.Stage.AFTER_LEVEL) return;
        try {
            Minecraft mc = Minecraft.getInstance();
            ClientLevel level = mc.level;
            LocalPlayer p = mc.player;
            if (level == null || p == null) return;
            TunnelScene.clearWorld();
            guard.veiled();                                   // the world is gone from this frame
            lastEntities = TunnelScene.drawEntities(e.getModelViewMatrix(), e.getCamera(), e.getPartialTick(), p, level);
            BlockPos c = p.blockPosition();
            long now = System.currentTimeMillis();
            long version = known.version();
            SeenFaces seen = SeenSampler.INSTANCE.store(), surface = SeenSampler.INSTANCE.surface();
            SkyStore sky = SkyScanner.INSTANCE.store();
            // both stores only grow and the scan's version moves with every chunk: the sum moves when any does
            long seenVersion = seen.version() + surface.version() + sky.version();
            boolean tint = SeenSampler.INSTANCE.on();
            boolean fancy = SkyScanner.fancyNow(mc);
            graphicsWord = fancy ? "fancy" : "fast";
            double mx = c.getX() - mesh.ox, my = c.getY() - mesh.oy, mz = c.getZ() - mesh.oz;
            if ((mesh.built && mesh.builtFancy != fancy) || MeshRule.due(mesh.built, version, mesh.builtVersion, seenVersion, mesh.builtSeenVersion, tint, mesh.builtTint, now, mesh.builtMs,
                    mx * mx + my * my + mz * mz)) {
                long t0 = System.nanoTime();
                String dim = Guard.dimOf(level);
                int radius = MeshRule.radius(mc.options.renderDistance().get());   // 0.16.1: the option, as the rays' range
                long[] cells = known.near(dim, c.getX(), c.getY(), c.getZ(), radius);
                BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
                Shell.World w = (x, y, z) -> kind(level, m.set(x, y, z));
                Shell.Result r = Shell.build(cells, w, c.getX(), c.getY(), c.getZ(), MeshRule.MAX_FACES);
                // 0.16.0: the faces the bot's own view saw (watch seen), with the dug-tunnel shell, under the same cap;
                // 0.16.1: and the surface store (faces under open sky, within the render distance)
                BlockPos.MutableBlockPos m2 = new BlockPos.MutableBlockPos();
                SeenRays.Cells rc = (x, y, z) -> SeenSampler.cell(level, m2.set(x, y, z));
                // 0.17.1: faces the surface scan draws (its per-chunk meshes) are left out here
                SeenMesh.Result u = SeenMesh.union(r.faces(), r.capped(), seen.near(dim, c.getX(), c.getY(), c.getZ(), radius),
                        surface.near(dim, c.getX(), c.getY(), c.getZ(), radius), rc, c.getX(), c.getY(), c.getZ(), MeshRule.MAX_FACES, sky::drawsInstead);
                mesh.rebuild(u.faces(), level, c.getX(), c.getY(), c.getZ(), version, seenVersion, tint, u.capped(), fancy);
                mesh.noteRebuild((System.nanoTime() - t0) / 1_000_000);
            }
            mesh.syncChunks(sky, level, c.getX(), c.getZ(), fancy);   // 0.17.1: the surface, one mesh per scanned chunk
            Vec3 cam = e.getCamera().getPosition();
            float partial = e.getPartialTick().getGameTimeDeltaPartialTick(false);
            Vec3 at = p.getPosition(partial);
            net.minecraft.world.phys.AABB box = p.getBoundingBox().move(at.subtract(p.position()));
            // 0.17.1: the bot's box and the margin; the shader cuts only what covers the bot on the screen and is nearer
            double[] cutArgs = cut ? new double[]{box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ, cutRadius} : null;
            boolean cutDone = mesh.draw(e.getModelViewMatrix(), e.getProjectionMatrix(), cam, dollhouse, cutArgs);
            if (cut) {
                if (cutDone) cutFrames.incrementAndGet();
                else if (cutMissLogs++ < 1) com.mojang.logging.LogUtils.getLogger().warn("[entropybot] watch tunnel: the cutaway is on but its shader is not available ({}); drawing without it", CutShaders.problem());
            }
            mesh.drawMarker(e.getModelViewMatrix(), e.getProjectionMatrix(), cam, box);
            frames.incrementAndGet();
            lastDrawMs = now;
        } catch (Throwable t) {
            fail("draw", t, true);
        } finally {
            TunnelScene.restoreState();                       // whatever happened above: the rest of the frame gets vanilla's state
        }
    }

    // ---- errors, status -------------------------------------------------------------------------------------------

    /** Counts and logs (the first 5 in full, then every 100th); a render-side failure turns the view off. */
    private void fail(String where, Throwable t, boolean turnOff) {
        errors++;
        lastError = where + ": " + t;
        if (errors <= 5) com.mojang.logging.LogUtils.getLogger().warn("[entropybot] watch tunnel ({}): ", where, t);
        else if (errors % 100 == 0) com.mojang.logging.LogUtils.getLogger().warn("[entropybot] watch tunnel: {} errors so far, last {}", errors, lastError);
        if (turnOff && on) {
            offByError = lastError;
            on = false;
            pending = null;
            try {
                Minecraft.getInstance().execute(mesh::close);
                WatchCamera.INSTANCE.tunnelStopped();
            } catch (Throwable ignored) {
            }
        }
    }

    public String status() {
        long now = System.currentTimeMillis();
        long f = frames.get(), ps = positions.get(), sk = skippedLayers.get();
        long fps = -1, pps = -1, sps = -1;
        if (statusMs > 0 && now > statusMs) {
            fps = (f - statusFrames) * 1000 / (now - statusMs);
            pps = (ps - statusPositions) * 1000 / (now - statusMs);
            sps = (sk - statusSkips) * 1000 / (now - statusMs);
        }
        statusFrames = f;
        statusPositions = ps;
        statusSkips = sk;
        statusMs = now;
        StringBuilder sb = new StringBuilder("tunnel view: ");
        if (on) {
            sb.append("on (camera ").append(how).append(" through blocks, ").append(height).append(" up, ").append(WatchCamera.INSTANCE.distance())
                    .append(" back, looking ").append(WatchCamera.compass(yaw)).append(')');
            if (WatchChecks.notDrawing(true, now - onSinceMs, lastDrawMs == 0 ? -1 : now - lastDrawMs, lastFrameMs == 0 ? -1 : now - lastFrameMs))
                sb.append(" WARNING: frames are rendered but none ran the tunnel view's drawing in the last second");
        } else sb.append("off");
        if (offByError != null) sb.append(" (turned itself off after an error: ").append(offByError).append(')');
        sb.append(" | world hidden: ").append(on ? "yes" : "n/a (off)").append(" (frame cleared after the level render, ")
                .append(guard.veiledFrames()).append(" frames hidden, ").append(guard.misses()).append(" missed and cleared late")
                .append(guard.misses() > 0 ? " - see check" : "").append(')');
        sb.append(" | terrain draw: ").append(WatchChecks.skipReport(MixinFlags.terrainSkipApplied, on, sps, sk));
        sb.append(" | entities drawn ").append(lastEntities).append(" (the bot, mobs and players within ").append((int) WorldVeil.ENTITY_RADIUS).append(')');
        sb.append(" | faces: ").append(drawMode(dollhouse));
        sb.append(" | cutaway: ").append(cutReport());
        sb.append(" | graphics: ").append(graphicsReport(graphicsWord));
        sb.append(" | surface meshes: ").append(mesh.chunkMeshes()).append(" chunks, ").append(mesh.chunkFacesDrawn).append(" faces drawn");
        if (mesh.chunkWaiting > 0) sb.append(", ").append(mesh.chunkWaiting).append(" waiting to be built");
        if (mesh.chunkBuilds > 0) sb.append(String.format(java.util.Locale.ROOT, ", %.1f ms per chunk mesh (max %.1f, %d built)",
                mesh.chunkBuildNanos / 1e6 / mesh.chunkBuilds, mesh.chunkMaxNanos / 1e6, mesh.chunkBuilds));
        sb.append(" | ").append(SkyScanner.INSTANCE.status());
        sb.append(" | other faces drawn ").append(mesh.faces());
        sb.append(" (").append(mesh.seenDrawn).append(" seen underground, ").append(mesh.surfaceDrawn).append(" seen under open sky")
                .append(SeenSampler.INSTANCE.on() ? ", both cyan" : "").append(", ").append(mesh.shapedDrawn).append(" on non-opaque blocks)");
        if (mesh.flatFaces > 0) sb.append(" (").append(mesh.flatFaces).append(" flat-coloured: no sprite)");
        if (mesh.built) sb.append(", last rebuild ").append((now - mesh.builtMs) / 1000).append(" s ago in ").append(mesh.buildMs).append(" ms (max ")
                .append(mesh.maxBuildMs).append(" ms over ").append(mesh.builds).append(" rebuilds)")
                .append(mesh.capped ? " (capped at " + MeshRule.MAX_FACES + " faces, nearest first)" : "");
        if (fps >= 0) sb.append(" | ").append(fps).append(" frames/s drawn, camera placed ").append(pps).append("/s");
        sb.append(" | known air ").append(known.size()).append(" cells");
        if (known.evicted() > 0) sb.append(" (").append(known.evicted()).append(" oldest dropped)");
        sb.append(" | position hook ").append(positionHookIn() ? "in" : "NOT in");
        sb.append(" | ").append(fileNote);
        if (errors > 0) sb.append(" | errors ").append(errors).append(", last ").append(lastError);
        return sb.toString();
    }

    /** For check. */
    public List<SelfCheck.Finding> findings() {
        long now = System.currentTimeMillis();
        List<SelfCheck.Finding> out = new java.util.ArrayList<>(WatchChecks.findings(MixinFlags.watchApplied, positionHookIn(), on,
                lastDrawMs == 0 ? -1 : now - lastDrawMs, on ? now - onSinceMs : 0, lastFrameMs == 0 ? -1 : now - lastFrameMs, offByError));
        out.addAll(WatchChecks.veilFindings(guard.misses(), MixinFlags.terrainSkipApplied, on, on ? now - onSinceMs : 0, skippedLayers.get() - skipAtStart));
        out.addAll(SeenSampler.INSTANCE.findings());                // 0.16.0: watch seen (seensampler)
        out.addAll(SkyScanner.INSTANCE.findings());                 // 0.17.1: the surface scan (skyscan)
        String cutProblem = WatchChecks.cutProblem(cut, CutShaders.problem());   // 0.16.1: the cutaway shader (tunnelcut)
        if (cutProblem != null) out.add(new SelfCheck.Finding("tunnelcut", cutProblem,
                "the game log has [entropybot] watch tunnel cutaway shader lines; F3+T reloads the shaders; watch tunnel cut off hides this"));
        return out;
    }

    /** The bot left the world: save, and forget the sampling spot. */
    public void leftWorld() {
        save(true);
        lastCell = Long.MIN_VALUE;
        lastDim = null;
    }
}
