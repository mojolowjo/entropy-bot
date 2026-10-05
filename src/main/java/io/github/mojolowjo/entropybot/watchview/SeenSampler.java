package io.github.mojolowjo.entropybot.watchview;

import io.github.mojolowjo.entropybot.commands.SelfCheck;
import io.github.mojolowjo.entropybot.guard.Guard;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * {@code watch seen} (0.16.0, docs/CAMERA_PLAN.md "Visible-faces idea"): records the block faces the bot's own
 * first-person view sees, so the tunnel view can draw them (cyan while this is on) next to the dug-tunnel shell.
 * Once a client tick while on (the tunnel view may be off: it fills the store while the bot works):
 * <ul>
 *   <li>from the player's real eye, yaw and pitch, the FOV option and the window's aspect (never the watch camera), a
 *       jittered grid of {@link #RAYS} rays, each walked through the voxel grid ({@link SeenRays#cast}) up to the render
 *       distance (the {@code renderDistance} option x 16 blocks, read every tick; never further);</li>
 *   <li>every 4 ticks (or when the eye's block changed) the exact near-field pass within {@link #NEAR_RADIUS} blocks;</li>
 *   <li>a face is kept by {@link SeenRule#record}: never when its air side sees the sky, light-0 faces only within 8
 *       blocks; the brightest light seen is stored ({@link SeenFaces}, {@code seenfaces.bin}).</li>
 * </ul>
 * A time budget of {@link #BUDGET_NS} per tick stops the rays early (counted as an overrun). Vanilla client API only
 * ({@code ClientLevel.getBlockState/isLoaded/canSeeSky/getRawBrightness}, {@code LocalPlayer} angles, {@code Options.fov},
 * {@code Options.renderDistance}, {@code Window} size); the tick comes from the mod's {@code ClientTickEvent.Post}
 * listener through {@code Core}. No mixin. Every error is caught: the first 5 logged in full, then every 100th; 20 in
 * a minute turn the sampler off (status and {@code check} say so).
 */
public final class SeenSampler {
    public static final SeenSampler INSTANCE = new SeenSampler();
    public static final String FILE = "seenfaces.bin";
    public static final int RAYS = 480, NEAR_RADIUS = 5, NEAR_EVERY_TICKS = 4;
    public static final long BUDGET_NS = 1_000_000;
    static final long SAVE_EVERY_TICKS = 6000;
    /** A view unchanged this long (same eye block, angles within a degree) is sampled only every IDLE_EVERY ticks. */
    static final int IDLE_AFTER_TICKS = 40, IDLE_EVERY = 10;

    private final SeenFaces store = new SeenFaces();
    private final SeenRule.Errors errs = new SeenRule.Errors();
    private final Random random = new Random();
    private volatile boolean on;
    private volatile String offByError, lastError;
    private Path file;
    private volatile boolean dirty;
    private long lastSaveTick;
    private volatile String fileNote = "not loaded yet";
    // counters (client thread writes, status reads)
    private volatile long rays, newFaces, samples, overruns, nanosTotal, lastSampleMs, onSinceMs, maxNanos;
    private volatile int lastRange, lastRenderChunks;
    private long statusRays, statusFaces, statusSamples, statusNanos, statusMs;
    private long lastEyeKey = Long.MIN_VALUE;
    private int lastYawDeg, lastPitchDeg, sameTicks;
    private volatile boolean idle;

    private SeenSampler() {}

    public boolean on() { return on; }

    public SeenFaces store() { return store; }

    public String setOn(boolean want) {
        if (want) {
            offByError = null;
            errs.reset();
            onSinceMs = System.currentTimeMillis();
            lastSampleMs = 0;
            statusMs = 0;
            sameTicks = 0;
            on = true;
            return "ok: watch seen on: recording the faces the bot's own view sees (rays up to the render distance, also with the tunnel view off); "
                    + "watch tunnel draws them cyan next to the dug-tunnel shell. watch seen status, watch seen off.";
        }
        boolean was = on;
        on = false;
        save();
        return was ? "ok: watch seen off: no more recording; the faces seen so far stay and are still drawn (no tint) in watch tunnel"
                : "ok: watch seen was off";
    }

    // ---- file ------------------------------------------------------------------------------------------------------

    /** Loads seenfaces.bin (once, at the first tick in a world). A broken file is set aside, the store starts empty. */
    public void load(Path root) {
        file = root.resolve(FILE);
        if (!Files.exists(file)) {
            fileNote = FILE + ": none yet";
            return;
        }
        try (InputStream in = new java.io.BufferedInputStream(Files.newInputStream(file))) {
            int n = store.read(in);
            fileNote = FILE + ": " + n + " blocks";
        } catch (Exception e) {
            store.clear();
            try {
                Files.move(file, root.resolve("seenfaces.broken.bin"), StandardCopyOption.REPLACE_EXISTING);
            } catch (Exception ignored) {
            }
            fileNote = FILE + ": broken (" + e.getMessage() + "), set aside as seenfaces.broken.bin, starting empty";
        }
    }

    public String fileNote() { return fileNote; }

    public void save() { save(false); }

    /** Writes seenfaces.bin when changed (snapshot here, disk on a small thread; sync when leaving or quitting). */
    public void save(boolean sync) {
        if (!dirty || file == null) return;
        dirty = false;
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            store.write(bytes);
            byte[] data = bytes.toByteArray();
            Path target = file;
            Runnable write = () -> {
                try {
                    Path tmp = target.resolveSibling(FILE + ".tmp");
                    Files.write(tmp, data);
                    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (Exception e) {
                    fileNote = FILE + ": couldn't write (" + e.getMessage() + ")";
                    com.mojang.logging.LogUtils.getLogger().warn("[entropybot] watch seen: couldn't write {}: {}", FILE, e.toString());
                }
            };
            if (sync) {
                write.run();
                return;
            }
            Thread t = new Thread(write, "entropybot-seenfaces");
            t.setDaemon(true);
            t.start();
        } catch (Throwable e) {
            fail("save", e);
        }
    }

    public void leftWorld() {
        save(true);
        lastEyeKey = Long.MIN_VALUE;
    }

    // ---- sampling --------------------------------------------------------------------------------------------------

    /** What a block does to a ray (see {@link SeenRays}). */
    static int cell(ClientLevel level, BlockPos pos) {
        if (!level.isLoaded(pos)) return SeenRays.STOP;
        BlockState s = level.getBlockState(pos);
        if (s.isAir()) return SeenRays.PASS;
        if (s.isSolidRender(level, pos)) return SeenRays.HIT;
        FluidState f = s.getFluidState();
        if (!f.isEmpty()) {
            if (f.is(FluidTags.WATER) && s.getCollisionShape(level, pos).isEmpty()) return SeenRays.WATER;
            return SeenRays.STOP;                                       // lava, waterlogged slabs and the like
        }
        return s.getCollisionShape(level, pos).isEmpty() ? SeenRays.PASS : SeenRays.STOP;   // torches pass; glass, leaves, ice stop
    }

    /** Once a client tick (from Core, after the tunnel view's tick). Never throws. */
    public void tick(long tick) {
        try {
            if (tick - lastSaveTick >= SAVE_EVERY_TICKS) {
                lastSaveTick = tick;
                save();
            }
            if (!on) return;
            Minecraft mc = Minecraft.getInstance();
            LocalPlayer p = mc.player;
            ClientLevel level = mc.level;
            if (p == null || level == null) return;
            Vec3 eye = p.getEyePosition();
            float yaw = p.getYRot(), pitch = p.getXRot();
            long eyeKey = BlockPos.containing(eye).asLong();
            int yd = Math.round(yaw), pd = Math.round(pitch);
            boolean same = eyeKey == lastEyeKey && yd == lastYawDeg && pd == lastPitchDeg;
            boolean moved = eyeKey != lastEyeKey;
            sameTicks = same ? sameTicks + 1 : 0;
            lastEyeKey = eyeKey;
            lastYawDeg = yd;
            lastPitchDeg = pd;
            idle = sameTicks >= IDLE_AFTER_TICKS;
            if (idle && tick % IDLE_EVERY != 0) {
                lastSampleMs = System.currentTimeMillis();         // idle on purpose: the view has not changed
                return;
            }
            sample(mc, level, p, eye, yaw, pitch, moved || tick % NEAR_EVERY_TICKS == 0);
        } catch (Throwable t) {
            fail("tick", t);
        }
    }

    private void sample(Minecraft mc, ClientLevel level, LocalPlayer p, Vec3 eye, float yaw, float pitch, boolean nearPass) {
        long t0 = System.nanoTime();
        String dim = Guard.dimOf(level);
        int chunks = mc.options.renderDistance().get();
        double range = Math.max(16, chunks * 16);
        lastRenderChunks = chunks;
        lastRange = (int) range;
        double fov = mc.options.fov().get();
        int w = mc.getWindow().getWidth(), h = mc.getWindow().getHeight();
        double aspect = w > 0 && h > 0 ? (double) w / h : 16.0 / 9;
        double[][] basis = SeenRays.basis(yaw, pitch);
        double tanV = SeenRays.tanHalf(fov);
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        SeenRays.Cells cells = (x, y, z) -> cell(level, m.set(x, y, z));
        BlockPos.MutableBlockPos air = new BlockPos.MutableBlockPos();
        long added = 0;
        if (nearPass) {
            List<long[]> found = new ArrayList<>();
            SeenRays.near(eye.x, eye.y, eye.z, basis, tanV, aspect, NEAR_RADIUS, range, cells,
                    (x, y, z, s, d) -> found.add(new long[]{x, y, z, s, Double.doubleToLongBits(d)}));
            for (long[] f : found) if (keep(level, dim, air, (int) f[0], (int) f[1], (int) f[2], (int) f[3], Double.longBitsToDouble(f[4]))) added++;
        }
        int[] g = SeenRays.grid(RAYS, aspect);
        int n = g[0] * g[1];
        int start = random.nextInt(n), stride = SeenRays.stride(n);
        int cast = 0;
        boolean over = false;
        for (int i = 0; i < n; i++) {
            if ((cast & 15) == 0 && System.nanoTime() - t0 > BUDGET_NS) {
                over = true;                         // the cells left out are spread over the screen, not the bottom rows
                break;
            }
            int idx = (int) ((start + (long) i * stride) % n);
            double[] sp = SeenRays.jittered(idx % g[0], idx / g[0], g[0], g[1], random.nextDouble(), random.nextDouble());
            double[] d = SeenRays.direction(basis, tanV, aspect, sp[0], sp[1]);
            SeenRays.Hit hit = SeenRays.cast(eye.x, eye.y, eye.z, d[0], d[1], d[2], range, cells, SeenRays.MAX_WATER);
            cast++;
            if (hit != null && keep(level, dim, air, hit.x(), hit.y(), hit.z(), hit.side(), hit.dist())) added++;
        }
        long ns = System.nanoTime() - t0;
        rays += cast;
        newFaces += added;
        samples++;
        nanosTotal += ns;
        if (ns > maxNanos) maxNanos = ns;
        if (over) overruns++;
        if (added > 0) dirty = true;
        lastSampleMs = System.currentTimeMillis();
    }

    /** The sky and light rule, then the store. True when the store changed. */
    private boolean keep(ClientLevel level, String dim, BlockPos.MutableBlockPos air, int x, int y, int z, int side, double dist) {
        int[] o = Shell.OFF[side];
        air.set(x + o[0], y + o[1], z + o[2]);
        boolean sky = level.canSeeSky(air);
        int light = level.getRawBrightness(air, 0);
        if (!SeenRule.record(sky, light, dist)) return false;
        return store.add(dim, x, y, z, side, light);
    }

    // ---- errors, status, check -------------------------------------------------------------------------------------

    private void fail(String where, Throwable t) {
        lastError = where + ": " + t;
        boolean stop = errs.add(System.currentTimeMillis());
        if (errs.logIt()) com.mojang.logging.LogUtils.getLogger().warn("[entropybot] watch seen ({}), error {}: ", where, errs.total(), t);
        if (stop && on) {
            on = false;
            offByError = SeenRule.ERRORS_TO_STOP + " errors within a minute, last " + lastError;
            com.mojang.logging.LogUtils.getLogger().warn("[entropybot] watch seen: turned itself off ({})", offByError);
        }
    }

    public String status() {
        long now = System.currentTimeMillis();
        long r = rays, f = newFaces, s = samples, ns = nanosTotal;
        StringBuilder sb = new StringBuilder("seen faces: ");
        if (on) sb.append("on, recording what the bot's own view sees (with or without the tunnel view)").append(idle ? " - view unchanged, sampling every " + IDLE_EVERY + " ticks" : "");
        else sb.append("off");
        if (offByError != null) sb.append(" (turned itself off: ").append(offByError).append(')');
        if (statusMs > 0 && now > statusMs) {
            long dt = now - statusMs, ds = s - statusSamples;
            sb.append(" | ").append((r - statusRays) * 1000 / dt).append(" rays/s, ").append((f - statusFaces) * 1000 / dt).append(" new faces/s, ")
                    .append(ds > 0 ? String.format(java.util.Locale.ROOT, "%.2f", (ns - statusNanos) / 1e6 / ds) : "-").append(" ms per tick");
        } else if (s > 0) {
            sb.append(" | ").append(String.format(java.util.Locale.ROOT, "%.2f", ns / 1e6 / s)).append(" ms per tick on average (ask again for the rates)");
        }
        statusRays = r;
        statusFaces = f;
        statusSamples = s;
        statusNanos = ns;
        statusMs = now;
        sb.append(" | max ").append(String.format(java.util.Locale.ROOT, "%.2f", maxNanos / 1e6)).append(" ms, ").append(overruns)
                .append(" budget overruns (").append(BUDGET_NS / 1_000_000).append(" ms a tick, ").append(RAYS).append(" rays)");
        sb.append(" | range ").append(lastRange > 0 ? lastRange + " blocks (render distance " + lastRenderChunks + " chunks)" : "the render distance (not sampled yet)");
        sb.append(" | stored ").append(store.size()).append(" blocks, ").append(store.faces()).append(" faces");
        if (store.evicted() > 0) sb.append(" (").append(store.evicted()).append(" oldest dropped)");
        sb.append(" | ").append(fileNote);
        if (errs.total() > 0) sb.append(" | errors ").append(errs.total()).append(", last ").append(lastError);
        return sb.toString();
    }

    /** For check: turned off by errors; on in a world but nothing sampled for 2 s. */
    public List<SelfCheck.Finding> findings() {
        List<SelfCheck.Finding> out = new ArrayList<>();
        if (offByError != null) out.add(new SelfCheck.Finding("seensampler",
                "watch seen turned itself off: " + offByError, "watch seen status; the log has [entropybot] watch seen lines; watch seen on to try again"));
        long now = System.currentTimeMillis();
        boolean inWorld = Minecraft.getInstance().player != null;
        if (WatchChecks.seenStalled(on, inWorld, now - onSinceMs, lastSampleMs == 0 ? -1 : now - lastSampleMs)) out.add(new SelfCheck.Finding("seensampler",
                "watch seen is on and the bot is in a world, but nothing was sampled for 2 s", "watch seen status; the log has [entropybot] watch seen lines"));
        return out;
    }
}
