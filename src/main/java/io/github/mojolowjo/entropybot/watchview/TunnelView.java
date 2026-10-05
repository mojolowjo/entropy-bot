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
 *   <li>the camera: above and behind the bot at a fixed yaw ({@link TunnelPose}), never in rock and never in air the
 *       bot doesn't know; angles in {@code ComputeCameraAngles}, the position in {@code ComputeFov} through the
 *       {@link WatchMixinCameraAccess} invoker.</li>
 *   <li>the faces ({@link Shell} into {@link TunnelMesh}) at {@code RenderLevelStageEvent.Stage.AFTER_PARTICLES}:
 *       posted by vanilla {@code LevelRenderer.renderLevel} itself (not from the section renderer Sodium replaces),
 *       after the translucent blocks, so water never paints over the faces.</li>
 * </ul>
 * Any exception is counted, logged rate-limited, and turns the view off; the frame loop never sees it.
 */
public final class TunnelView {
    public static final TunnelView INSTANCE = new TunnelView();
    public static final String FILE = "knownair.bin";
    static final int SIGHT_CAVE = 6, SIGHT_ELSEWHERE = 2, SIGHT_MAX = 400, MAX_CLIMB = 48;
    static final double DEFAULT_HEIGHT = 12;
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
    private long statusFrames, statusPositions, statusMs;

    private TunnelView() {}

    public boolean on() { return on; }

    public float yaw() { return yaw; }

    public void setYaw(float y) {
        yaw = y;
        pose = null;
    }

    public double height() { return height; }

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
                if (now - onSinceMs > 1500 && now - lastDrawMs > 1000) {
                    if (!warnedNoDraw) {
                        warnedNoDraw = true;
                        com.mojang.logging.LogUtils.getLogger().warn("[entropybot] watch tunnel: on, but no frame drew its faces in the last second");
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

    /** The camera may sit in this cell: open, no fluid, and lit by the sky or known air. */
    private boolean cameraOk(ClientLevel level, String dim, BlockPos.MutableBlockPos m, int x, int y, int z) {
        m.set(x, y, z);
        if (!level.isLoaded(m)) return false;
        BlockState s = level.getBlockState(m);
        if (s.isSolidRender(level, m) || !s.getFluidState().isEmpty()) return false;
        return level.canSeeSky(m) || known.contains(dim, x, y, z);
    }

    // ---- camera ---------------------------------------------------------------------------------------------------

    /** ComputeCameraAngles: this frame's pose; the angles now, the position in {@link #onFov}. True when it set them. */
    public boolean onAngles(ViewportEvent.ComputeCameraAngles e) {
        if (!on) return false;
        try {
            Camera cam = e.getCamera();
            Entity ent = cam.getEntity();
            ClientLevel level = Minecraft.getInstance().level;
            if (ent == null || level == null) return false;
            String dim = Guard.dimOf(level);
            Vec3 eye = ent.getEyePosition((float) e.getPartialTick());
            BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
            TunnelPose.CellOk ok = (x, y, z) -> cameraOk(level, dim, m, x, y, z);
            TunnelPose.Pose want = TunnelPose.place(eye.x, eye.y, eye.z, yaw, height, WatchCamera.INSTANCE.distance(), MAX_CLIMB, ok);
            pose = TunnelPose.follow(pose, want, 0.15, eye.x, eye.y, eye.z, ok);
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
        if (!on || p == null || !e.usedConfiguredFov()) return;
        pending = null;
        try {
            if ((Object) e.getCamera() instanceof WatchMixinCameraAccess a) {
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

    /** RenderLevelStageEvent: at AFTER_PARTICLES, rebuild the mesh if due and draw it. */
    public void onStage(RenderLevelStageEvent e) {
        if (!on || e.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) return;
        try {
            Minecraft mc = Minecraft.getInstance();
            ClientLevel level = mc.level;
            LocalPlayer p = mc.player;
            if (level == null || p == null) return;
            BlockPos c = p.blockPosition();
            long now = System.currentTimeMillis();
            long version = known.version();
            double mx = c.getX() - mesh.ox, my = c.getY() - mesh.oy, mz = c.getZ() - mesh.oz;
            if (MeshRule.due(mesh.built, version, mesh.builtVersion, now, mesh.builtMs, mx * mx + my * my + mz * mz)) {
                String dim = Guard.dimOf(level);
                int radius = MeshRule.radius(mc.options.getEffectiveRenderDistance());
                long[] cells = known.near(dim, c.getX(), c.getY(), c.getZ(), radius);
                BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
                Shell.Result r = Shell.build(cells, (x, y, z) -> kind(level, m.set(x, y, z)), c.getX(), c.getY(), c.getZ(), MeshRule.MAX_FACES);
                mesh.rebuild(r.faces(), level, c.getX(), c.getY(), c.getZ(), version, r.capped());
            }
            Vec3 cam = e.getCamera().getPosition();
            mesh.draw(e.getModelViewMatrix(), e.getProjectionMatrix(), cam);
            float partial = e.getPartialTick().getGameTimeDeltaPartialTick(false);
            Vec3 at = p.getPosition(partial);
            mesh.drawMarker(e.getModelViewMatrix(), e.getProjectionMatrix(), cam, p.getBoundingBox().move(at.subtract(p.position())));
            frames.incrementAndGet();
            lastDrawMs = now;
        } catch (Throwable t) {
            fail("draw", t, true);
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
        long f = frames.get(), ps = positions.get();
        long fps = -1, pps = -1;
        if (statusMs > 0 && now > statusMs) {
            fps = (f - statusFrames) * 1000 / (now - statusMs);
            pps = (ps - statusPositions) * 1000 / (now - statusMs);
        }
        statusFrames = f;
        statusPositions = ps;
        statusMs = now;
        StringBuilder sb = new StringBuilder("tunnel view: ");
        if (on) {
            sb.append("on (camera ").append(how).append(", ").append(height).append(" up, ").append(WatchCamera.INSTANCE.distance())
                    .append(" back, looking ").append(WatchCamera.compass(yaw)).append(')');
            boolean drawing = now - onSinceMs <= 1500 || now - lastDrawMs <= 1000;
            if (!drawing) sb.append(" WARNING: no frame drew its faces in the last second");
        } else sb.append("off");
        if (offByError != null) sb.append(" (turned itself off after an error: ").append(offByError).append(')');
        sb.append(" | faces drawn ").append(mesh.faces());
        if (mesh.flatFaces > 0) sb.append(" (").append(mesh.flatFaces).append(" flat-coloured: no sprite)");
        if (mesh.built) sb.append(", last rebuild ").append((now - mesh.builtMs) / 1000).append(" s ago in ").append(mesh.buildMs).append(" ms")
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
        return WatchChecks.findings(MixinFlags.watchApplied, positionHookIn(), on, lastDrawMs == 0 ? -1 : now - lastDrawMs,
                on ? now - onSinceMs : 0, offByError);
    }

    /** The bot left the world: save, and forget the sampling spot. */
    public void leftWorld() {
        save(true);
        lastCell = Long.MIN_VALUE;
        lastDim = null;
    }
}
