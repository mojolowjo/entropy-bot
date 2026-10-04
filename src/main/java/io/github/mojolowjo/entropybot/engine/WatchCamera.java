package io.github.mojolowjo.entropybot.engine;

/**
 * Watch camera v1 (docs/CAMERA_PLAN.md): a third-person view behind the bot that follows its walking direction instead
 * of its head, so the picture never "runs backwards". Render-only: the player's yaw, pitch and position are never
 * written (Baritone steers by them); the mixin {@code WatchMixinCamera} only changes the angles the drawn camera is set
 * up with. While it is on the frame-rate cap is raised, and the old cap goes back on {@code watch off} and when the
 * bot leaves the world. {@link #follow} is pure (JUnit); the rest reads and acts on the game.
 */
public final class WatchCamera {
    public static final WatchCamera INSTANCE = new WatchCamera();

    /** Camera pitch (degrees, positive = looking down) and the cap while watching. */
    public static final float PITCH = 25f;
    public static final int WATCH_FPS = 60;
    /** Horizontal speed (blocks per tick) below which the bot counts as standing: the yaw stays. */
    static final double MOVING = 0.03;
    /** Share of the way to the walking direction covered per client tick (about 0.5 s to settle at 20 ticks/s). */
    static final float EASE = 0.12f;

    private volatile boolean on;
    private volatile float yaw;
    private boolean haveYaw;
    private double lastX, lastZ;
    private boolean haveLast;
    private Object savedCamera;      // the camera type before (net.minecraft.client.CameraType)
    private int savedFps = -1;
    private int errors;
    /** Camera distance in blocks (watch distance 4-8) and the hook's own call count, so a hook that never attached shows. */
    private volatile float distance = 5f;
    private final java.util.concurrent.atomic.AtomicLong hookCalls = new java.util.concurrent.atomic.AtomicLong();
    private volatile long lastHookMs, onSinceMs;
    private long statusCalls, statusMs;

    private WatchCamera() {}

    public boolean on() { return on; }

    public float yaw() { return yaw; }

    public float distance() { return distance; }

    /** The mixin calls this on every camera setup (also while off, so "the hook is in" can be told apart from "it is not"). */
    public void hookCalled() {
        hookCalls.incrementAndGet();
        lastHookMs = System.currentTimeMillis();
    }

    /** Pure: the hook report. calls/ms-since-last/seconds-watched. */
    public static String hookReport(boolean applied, boolean on, long callsPerSec, long sinceLastMs, long onForMs) {
        if (!applied) return "hook: NOT in (the mixin did not attach: plain third person only)";
        if (on && onForMs > 1500 && (sinceLastMs < 0 || sinceLastMs > 1000)) return "hook: NOT running (attached, but no camera setup seen in the last second)";
        return "hook: active" + (callsPerSec >= 0 ? " (" + callsPerSec + " calls/s)" : "");
    }

    /** Pure: the clamped distance asked for, or -1 when the text is not a number in 4-8. */
    public static float parseDistance(String s) {
        try {
            float d = Float.parseFloat(s.trim());
            return d >= 4f && d <= 8f ? d : -1f;
        } catch (RuntimeException e) {
            return -1f;
        }
    }

    private String hookLine() {
        long now = System.currentTimeMillis();
        long calls = hookCalls.get();
        long rate = statusMs > 0 && now > statusMs ? (calls - statusCalls) * 1000 / (now - statusMs) : -1;
        statusCalls = calls;
        statusMs = now;
        long since = lastHookMs == 0 ? -1 : now - lastHookMs;
        return hookReport(io.github.mojolowjo.entropybot.guard.MixinFlags.watchApplied, on, rate, since, on ? now - onSinceMs : 0);
    }

    /** The walking direction as a Minecraft yaw (0 = south, 90 = west) for a step of dx, dz. */
    public static float walkYaw(double dx, double dz) {
        return (float) Math.toDegrees(Math.atan2(-dx, dz));
    }

    /** The shortest signed turn from a to b, in (-180, 180]. */
    public static float turn(float a, float b) {
        float d = (b - a) % 360f;
        if (d > 180f) d -= 360f;
        if (d <= -180f) d += 360f;
        return d;
    }

    /** The next camera yaw: it eases towards the walking direction while the bot moves, and stays while it stands. Pure. */
    public static float follow(float yaw, double dx, double dz) {
        if (Math.hypot(dx, dz) < MOVING) return yaw;
        return yaw + EASE * turn(yaw, walkYaw(dx, dz));
    }

    /** "watch" | "watch off" | "watch status" (PM, owner): the answer. */
    public String command(String text) {
        String t = text == null ? "" : text.trim().toLowerCase();
        if (t.startsWith("watch")) t = t.substring(5).trim();
        if (t.equals("off") || t.equals("stop")) return off("ok: the normal view is back");
        if (t.equals("status")) {
            String h = hookLine();
            try { Thread.sleep(0); } catch (InterruptedException ignored) {}
            return "watch camera: " + (on ? "on (behind the bot, " + distance + " blocks, following its walking direction)" : "off") + " | " + h;
        }
        if (t.startsWith("distance")) {
            float d = parseDistance(t.substring(8));
            if (d < 0) return "error: watch distance 4-8 (now " + distance + ")";
            distance = d;
            return "ok: the camera sits " + d + " blocks behind";
        }
        if (!t.isEmpty() && !t.equals("on")) return "error: watch | watch off | watch status | watch distance 4-8";
        try {
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
            if (mc.player == null) return "error: not in a world";
            if (!on) {
                savedCamera = mc.options.getCameraType();
                savedFps = mc.options.framerateLimit().get();
                mc.options.setCameraType(net.minecraft.client.CameraType.THIRD_PERSON_BACK);
                mc.options.framerateLimit().set(WATCH_FPS);
                haveYaw = false;
                haveLast = false;
                onSinceMs = System.currentTimeMillis();
                statusMs = 0;
                on = true;
            }
            String warn = io.github.mojolowjo.entropybot.guard.MixinFlags.watchApplied ? "" : " WARNING: the camera hook is not in (see watch status): plain third person only.";
            return "ok: watching from behind (" + WATCH_FPS + " FPS while it is on); watch off puts the normal view back." + warn;
        } catch (Throwable e) {
            return "error: " + e;
        }
    }

    private String off(String ok) {
        if (!on) return "ok: the watch camera was off";
        on = false;
        try {
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
            if (savedCamera instanceof net.minecraft.client.CameraType c) mc.options.setCameraType(c);
            if (savedFps > 0) mc.options.framerateLimit().set(savedFps);
        } catch (Throwable e) {
            return "error: " + e;
        }
        savedCamera = null;
        savedFps = -1;
        return ok;
    }

    /** The bot left the world (or the game is going): put the old view and cap back. Never throws. */
    public void leftWorld() {
        if (on) off("");
    }

    /** Once a client tick, in a world: the yaw eases towards the walking direction. Never throws. */
    public void tick() {
        if (!on) return;
        try {
            net.minecraft.client.player.LocalPlayer p = net.minecraft.client.Minecraft.getInstance().player;
            if (p == null) return;
            double x = p.getX(), z = p.getZ();
            if (!haveYaw) {
                yaw = p.getYRot();
                haveYaw = true;
            }
            if (haveLast) yaw = follow(yaw, x - lastX, z - lastZ);
            lastX = x;
            lastZ = z;
            haveLast = true;
        } catch (Throwable e) {
            if (errors++ < 5) com.mojang.logging.LogUtils.getLogger().warn("[entropybot] watch camera: {}", e.toString());
        }
    }
}
