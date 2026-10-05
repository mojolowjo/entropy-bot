package io.github.mojolowjo.entropybot.engine;

import io.github.mojolowjo.entropybot.watchview.TunnelPose;
import io.github.mojolowjo.entropybot.watchview.TunnelView;

/**
 * Watch camera (docs/CAMERA_PLAN.md). v1 ({@code watch}): a third-person view behind the bot that follows its walking
 * direction instead of its head, so the picture never "runs backwards". v2 ({@code watch tunnel}, {@link TunnelView}):
 * a camera fixed above and behind the bot with the walls of its tunnels and caves drawn through the rock.
 * Render-only: the player's yaw, pitch and position are never written (Baritone steers by them); only the drawn camera
 * moves, through NeoForge's {@code ViewportEvent.ComputeCameraAngles} and {@code CalculateDetachedCameraDistanceEvent}
 * (0.15.2, review C3: the Camera.setup mixin is gone; the listeners are in {@link WatchEvents}). While a view is on the
 * window's frame cap is 60 (set on the window, review C4: the video option is never written, so options.txt keeps the
 * owner's value); {@code watch off} and leaving the world put the option's value back on the window. {@link #follow}
 * and the other statics are pure (JUnit); the rest reads and acts on the game.
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
    private volatile float prevYaw;
    private float tickEndYaw;
    private double lastX, lastZ;
    private float lastPlayerYaw;
    private boolean haveLast;
    private Object savedCamera;      // the camera type before (net.minecraft.client.CameraType)
    private volatile boolean viewSaved;
    private int errors;
    /** Camera distance in blocks (watch distance 1-8) and the hook's own call count, so a hook that never attached shows. */
    private volatile float distance = 4f;
    private final java.util.concurrent.atomic.AtomicLong hookCalls = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong distanceCalls = new java.util.concurrent.atomic.AtomicLong();
    private volatile long lastHookMs, onSinceMs;
    private long statusCalls, statusMs;

    private WatchCamera() {}

    /** v1 (the follow camera) is on. */
    public boolean on() { return on; }

    public float yaw() { return yaw; }

    public float distance() { return distance; }

    /** From the settings file (WatchSettings); the value is checked there. */
    public void setDistance(float d) { distance = d; }

    private final java.util.concurrent.atomic.AtomicLong angleEdits = new java.util.concurrent.atomic.AtomicLong();

    /** The angle listener changed an angle (only while watching). */
    public void angleEdited() { angleEdits.incrementAndGet(); }

    /** The angle listener calls this on every camera setup (also while off, so "the hook is in" can be told apart from "it is not"). */
    public void hookCalled() {
        hookCalls.incrementAndGet();
        lastHookMs = System.currentTimeMillis();
    }

    public void distanceCalled() { distanceCalls.incrementAndGet(); }

    /** Pure: the hook report. registered = the event listeners are in; calls/ms-since-last/seconds-watched. */
    public static String hookReport(boolean applied, boolean on, long callsPerSec, long sinceLastMs, long onForMs) {
        if (!applied) return "hook: NOT in (the camera event listeners are not registered: plain third person only)";
        if (on && onForMs > 1500 && (sinceLastMs < 0 || sinceLastMs > 1000)) return "hook: NOT running (registered, but no camera setup seen in the last second)";
        return "hook: active" + (callsPerSec >= 0 ? " (" + callsPerSec + " calls/s)" : "");
    }

    /** Pure: the clamped distance asked for, or -1 when the text is not a number in 1-8. */
    public static float parseDistance(String s) {
        try {
            float d = Float.parseFloat(s.trim());
            return d >= 1f && d <= 8f ? d : -1f;
        } catch (RuntimeException e) {
            return -1f;
        }
    }

    /** Pure: the tunnel camera's height asked for, or -1 when the text is not a number in 1-40. */
    public static double parseHeight(String s) {
        try {
            double d = Double.parseDouble(s.trim());
            return d >= 1 && d <= 40 ? d : -1;
        } catch (RuntimeException e) {
            return -1;
        }
    }

    private String hookLine() {
        long now = System.currentTimeMillis();
        long calls = hookCalls.get();
        long rate = statusMs > 0 && now > statusMs ? (calls - statusCalls) * 1000 / (now - statusMs) : -1;
        statusCalls = calls;
        statusMs = now;
        long since = lastHookMs == 0 ? -1 : now - lastHookMs;
        return hookReport(io.github.mojolowjo.entropybot.guard.MixinFlags.watchApplied, on || TunnelView.INSTANCE.on(), rate, since,
                on ? now - onSinceMs : 0);
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

    /** v1's angles for this frame, or null to leave the camera alone (off, or first person). */
    public float[] v1Angles(boolean detached) {
        return v1Angles(detached, 1f);
    }

    /**
     * v1's angles for a frame partial ticks after the last tick: the yaw drawn between the last two ticks' yaws, the
     * same way the bot itself is drawn between its last two positions (watch turn, 2026-10-05: a 45 deg/s turn moves
     * 2.25 degrees a tick, a visible step at 60 FPS otherwise).
     */
    public float[] v1Angles(boolean detached, float partial) {
        if (!on || !detached) return null;
        return new float[]{haveYaw ? drawnYaw(prevYaw, yaw, partial) : yaw, PITCH, 0f};
    }

    /** Pure: the yaw between the last tick's (from) and this tick's (to), partial 0..1 of the way, by the shorter way round. */
    public static float drawnYaw(float from, float to, float partial) {
        float t = partial < 0f || Float.isNaN(partial) ? 0f : Math.min(partial, 1f);
        return from + turn(from, to) * t;
    }

    /** watch turn: the input listener turns v1's yaw (before tick() in the same client tick). */
    public void turnBy(float degrees) {
        if (haveYaw) yaw = SteerRules.wrap(yaw + degrees);
    }

    /** v1's camera distance, or -1 to leave it alone. */
    public float v1Distance() {
        return on && !TunnelView.INSTANCE.on() ? distance : -1f;
    }

    static final String USAGE = "watch | watch off | watch status | watch distance 1-8 | watch tunnel [off|status|height 1-40|turn left|right|dollhouse [on|off]|cut [on|off|status|mode cone|outline|radius N]] | watch dollhouse [on|off] | watch cut [on|off|status|mode cone|outline|radius N] | watch steer [on|off|status] | watch turn [on|off|status|rate 5-180] | watch seen [on|off|status] | watch shot";

    /** Pure: "dollhouse", "dollhouse on|off" -> true/false for the new state given the old one, or null when not understood. */
    public static Boolean parseDollhouse(String a, boolean now) {
        String t = a == null ? "" : a.trim();
        if (t.equals("dollhouse")) return !now;          // a bare word toggles
        if (t.equals("dollhouse on")) return true;
        if (t.equals("dollhouse off")) return false;
        return null;
    }

    /** "watch" | "watch off" | "watch status" | "watch tunnel ..." (PM, owner): the answer. */
    public String command(String text) {
        String t = text == null ? "" : text.trim().toLowerCase();
        if (t.startsWith("watch")) t = t.substring(5).trim();
        WatchSettings.INSTANCE.ensureLoaded();          // TLL 32: the saved settings first, so a change below is never overwritten by them
        if (t.equals("off") || t.equals("stop")) return allOff();
        if (t.equals("steer") || t.startsWith("steer ")) return WatchSteer.INSTANCE.command(t.substring(5));
        if (t.equals("turn") || t.startsWith("turn ")) return WatchSteer.INSTANCE.turnCommand(t.substring(4));   // watch turn (2026-10-05)
        if (t.equals("status")) {
            String h = hookLine();
            return "watch camera: " + (on ? "on (behind the bot, " + distance + " blocks, following its walking direction)" : "off") + " | " + h
                    + " | angle edits so far: " + angleEdits.get() + (on && angleEdits.get() == 0 ? " (NONE: the angle listener is not working)" : "")
                    + " | yaw " + Math.round(yaw) + " | " + WatchSteer.INSTANCE.status() + " | " + WatchSettings.INSTANCE.status()
                    + " | " + TunnelView.INSTANCE.status() + " | " + io.github.mojolowjo.entropybot.watchview.SeenSampler.INSTANCE.status();
        }
        if (t.equals("tunnel") || t.startsWith("tunnel ")) return tunnel(t.substring(6).trim());
        if (t.equals("dollhouse") || t.startsWith("dollhouse ")) return tunnel(t);          // alias of watch tunnel dollhouse
        if (t.equals("cut") || t.startsWith("cut ")) return tunnel(t);                      // alias of watch tunnel cut (0.16.1)
        if (t.equals("seen") || t.startsWith("seen ")) return seen(t.substring(4).trim());
        if (t.equals("probe") || t.equals("probe on")) {
            WatchProbe.INSTANCE.setOn(true);
            return "ok: render-stage probe on (red/green/blue boxes around the bot at three stages); watch probe status | watch probe off | watch shot";
        }
        if (t.equals("probe off")) {
            WatchProbe.INSTANCE.setOn(false);
            return "ok: render-stage probe off";
        }
        if (t.equals("probe status")) return "probe: " + (WatchProbe.INSTANCE.on() ? "on" : "off") + " | stages seen: " + WatchProbe.INSTANCE.report();
        if (t.equals("shot")) return shot();
        if (t.startsWith("distance")) {
            float d = parseDistance(t.substring(8));
            if (d < 0) return "error: watch distance 1-8 (now " + distance + ")";
            distance = d;
            return "ok: the camera sits " + d + " blocks behind" + WatchSettings.INSTANCE.save();
        }
        if (!t.isEmpty() && !t.equals("on")) return "error: " + USAGE;
        try {
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
            if (mc.player == null) return "error: not in a world";
            if (!on) {
                startView(mc);
                haveYaw = false;
                haveLast = false;
                onSinceMs = System.currentTimeMillis();
                statusMs = 0;
                on = true;
            }
            String warn = io.github.mojolowjo.entropybot.guard.MixinFlags.watchApplied ? "" : " WARNING: the camera listeners are not in (see watch status): plain third person only.";
            String tun = TunnelView.INSTANCE.on() ? " (the tunnel view is on and shows first; watch tunnel off to see this one)" : "";
            return "ok: watching from behind (" + WATCH_FPS + " FPS while it is on); watch off puts the normal view back." + tun + warn;
        } catch (Throwable e) {
            return "error: " + e;
        }
    }

    /** {@code watch tunnel [off|status|height N|turn left|right]}. */
    private String tunnel(String a) {
        TunnelView tv = TunnelView.INSTANCE;
        try {
            if (a.equals("off") || a.equals("stop")) {
                if (!tv.on()) return "ok: the tunnel view was off";
                tv.stop();
                endViewIfIdle();
                return "ok: tunnel view off" + (on ? " (the follow camera is still on: watch off for the normal view)" : "; the normal view is back");
            }
            if (a.equals("status")) return tv.status();
            if (a.startsWith("height")) {
                double h = parseHeight(a.substring(6));
                if (h < 0) return "error: watch tunnel height 1-40 (now " + tv.height() + ")";
                tv.setHeight(h);
                return "ok: the tunnel camera sits " + h + " blocks above the bot (through rock: only what the bot opened is drawn)" + WatchSettings.INSTANCE.save();
            }
            Boolean dh = parseDollhouse(a, tv.dollhouse());
            if (dh != null) {
                tv.setDollhouse(dh);
                return "ok: tunnel view faces: " + TunnelView.drawMode(dh) + (tv.on() ? "" : " (applies when watch tunnel is on)")
                        + "; kept across restarts" + WatchSettings.INSTANCE.save();
            }
            if (a.startsWith("dollhouse")) return "error: watch tunnel dollhouse [on|off] (now " + (tv.dollhouse() ? "on" : "off") + ")";
            if (a.equals("cut status")) return "cutaway: " + tv.cutReport();
            if (a.startsWith("cut radius")) {
                boolean cone = tv.cutCone();
                double r = io.github.mojolowjo.entropybot.watchview.Cutaway.parseRadius(a.substring(10), cone);
                if (r < 0) return "error: watch cut radius " + (cone ? "1-6 in cone mode (now " + tv.coneRadius() : "0-3 in outline mode (now " + tv.cutRadius()) + ")";
                if (cone) tv.setConeRadius(r); else tv.setCutRadius(r);
                return "ok: cutaway " + tv.cutReport() + (tv.cut() ? "" : " (the cut is off: watch tunnel cut on)") + "; kept across restarts" + WatchSettings.INSTANCE.save();
            }
            if (a.startsWith("cut mode")) {
                Boolean m = io.github.mojolowjo.entropybot.watchview.Cutaway.parseMode(a);
                if (m == null) return "error: watch cut mode cone|outline (now " + (tv.cutCone() ? "cone" : "outline") + ")";
                tv.setCutCone(m);
                return "ok: cutaway " + tv.cutReport() + (tv.cut() ? "" : " (the cut is off: watch tunnel cut on)") + "; kept across restarts" + WatchSettings.INSTANCE.save();
            }
            Boolean ct = io.github.mojolowjo.entropybot.watchview.Cutaway.parseCut(a, tv.cut());
            if (ct != null) {
                tv.setCut(ct);
                return "ok: cutaway " + tv.cutReport() + (tv.on() ? "" : " (applies when watch tunnel is on)") + "; kept until the game restarts";
            }
            if (a.startsWith("cut")) return "error: watch tunnel cut [on|off|status|mode cone|outline|radius N] (now " + (tv.cut() ? "on, " + tv.cutModeWords() : "off") + ")";
            if (a.equals("turn left") || a.equals("turn right")) {
                tv.setYaw(TunnelPose.quarter(tv.yaw(), a.endsWith("left")));
                return "ok: the tunnel camera looks " + compass(tv.yaw()) + " now";
            }
            if (!a.isEmpty() && !a.equals("on")) return "error: watch tunnel | watch tunnel off | watch tunnel status | watch tunnel height 1-40 | watch tunnel turn left|right | watch tunnel dollhouse [on|off] | watch tunnel cut [on|off|status|mode cone|outline|radius N]";
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
            if (mc.player == null) return "error: not in a world";
            if (!io.github.mojolowjo.entropybot.guard.MixinFlags.watchApplied)
                return "error: the watch camera's render listeners are not registered (see check): the tunnel view can't hide the world, so it stays off";
            if (!tv.positionHookIn()) return "error: the camera position hook is not in (WatchMixinCameraAccess; see check and the game log): the tunnel view can't place its camera";
            if (!tv.on()) {
                startView(mc);
                float start = on ? yaw : mc.player.getYRot();
                tv.start(TunnelPose.snap45(start));
            }
            return "ok: tunnel view on: the camera floats above and behind the bot looking " + compass(tv.yaw())
                    + " and passes through blocks; the real world is hidden, only what the bot's own view saw (the tunnels and caves it opened, the ground and trees round it out to the render distance), the bot and mobs are drawn; cutaway "
                    + (tv.cut() ? "on (nothing that covers the bot is drawn)" : "off") + "; "
                    + WATCH_FPS + " FPS while on. watch tunnel turn left|right, watch tunnel height 1-40, watch tunnel cut on|off, watch tunnel off.";
        } catch (Throwable e) {
            return "error: " + e;
        }
    }

    /** {@code watch seen [on|off|status]}: the visible-faces sampler (0.16.0). */
    private String seen(String a) {
        io.github.mojolowjo.entropybot.watchview.SeenSampler s = io.github.mojolowjo.entropybot.watchview.SeenSampler.INSTANCE;
        try {
            if (a.equals("status")) return s.status();
            if (a.equals("off") || a.equals("stop")) return s.setOn(false);
            if (a.isEmpty() || a.equals("on")) {
                if (net.minecraft.client.Minecraft.getInstance().player == null) return "error: not in a world";
                return s.setOn(true);
            }
            return "error: watch seen [on|off|status]";
        } catch (Throwable e) {
            return "error: " + e;
        }
    }

    /** Pure: the compass word for a Minecraft yaw (0 = south). */
    public static String compass(float yaw) {
        String[] w = {"south", "south-west", "west", "north-west", "north", "north-east", "east", "south-east"};
        int i = Math.floorMod(Math.round(yaw / 45f), 8);
        return w[i];
    }

    /** First view on: remember the camera type, go third person, cap 60 on the window (never in the options). */
    private void startView(net.minecraft.client.Minecraft mc) {
        if (viewSaved) return;
        savedCamera = mc.options.getCameraType();
        viewSaved = true;
        mc.options.setCameraType(net.minecraft.client.CameraType.THIRD_PERSON_BACK);
        mc.getWindow().setFramerateLimit(WATCH_FPS);
    }

    /** No view left on: the camera type back, and the option's frame cap back on the window. Never throws. */
    private void endViewIfIdle() {
        if (on || TunnelView.INSTANCE.on() || !viewSaved) return;
        viewSaved = false;
        try {
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
            if (savedCamera instanceof net.minecraft.client.CameraType c) mc.options.setCameraType(c);
            mc.getWindow().setFramerateLimit(mc.options.framerateLimit().get());
        } catch (Throwable e) {
            if (errors++ < 5) com.mojang.logging.LogUtils.getLogger().warn("[entropybot] watch camera: couldn't restore the view: {}", e.toString());
        }
        savedCamera = null;
    }

    /** The tunnel view turned itself off (an error): restore the view if nothing else is on. */
    public void tunnelStopped() {
        net.minecraft.client.Minecraft.getInstance().execute(this::endViewIfIdle);
    }

    private volatile String shotName;
    private volatile long shotAskedMs;

    /**
     * {@code watch shot}: saves a screenshot of the game window to screenshots\ (so a session can look at the picture).
     * Since 0.15.4 it is taken at the end of the next rendered frame ({@code RenderFrameEvent.Post}, after the tunnel
     * view's drawing and its frame guard, before the frame goes to the screen), so it always shows a finished frame;
     * if no frame is rendered within 2 s the tick takes it from the last finished one, as before.
     */
    private String shot() {
        try {
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
            if (mc.player == null) return "error: not in a world";
            String name = "watch-" + System.currentTimeMillis() + ".png";
            shotAskedMs = System.currentTimeMillis();
            shotName = name;
            return "ok: screenshot saved as screenshots\\" + name + " in a moment";
        } catch (Throwable e) {
            return "error: " + e;
        }
    }

    /** RenderFrameEvent.Post (render thread): take a pending {@code watch shot}. */
    public void onFramePost() {
        String name = shotName;
        if (name == null) return;
        shotName = null;
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        net.minecraft.client.Screenshot.grab(mc.gameDirectory, name, mc.getMainRenderTarget(), msg -> {});
    }

    /** No frame rendered since the shot was asked for (a stalled or minimised window): take it from the last frame. */
    private void shotFallback() {
        String name = shotName;
        if (name == null || System.currentTimeMillis() - shotAskedMs < 2000) return;
        shotName = null;
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        mc.execute(() -> net.minecraft.client.Screenshot.grab(mc.gameDirectory, name, mc.getMainRenderTarget(), msg -> {}));
        com.mojang.logging.LogUtils.getLogger().warn("[entropybot] watch shot: no frame was rendered for 2 s; took {} from the last finished frame", name);
    }

    /** {@code watch off}: both views off, the normal view and frame cap back. */
    private String allOff() {
        boolean was = on || TunnelView.INSTANCE.on();
        on = false;
        TunnelView.INSTANCE.stop();
        endViewIfIdle();
        String seen = io.github.mojolowjo.entropybot.watchview.SeenSampler.INSTANCE.on() ? " (watch seen is still recording: watch seen off stops it)" : "";
        return (was ? "ok: the normal view is back" : "ok: the watch camera was off") + seen;
    }

    /** The bot left the world (or the game is going): put the old view and cap back. Never throws. */
    public void leftWorld() {
        try {
            on = false;
            TunnelView.INSTANCE.stop();
            endViewIfIdle();
        } catch (Throwable e) {
            if (errors++ < 5) com.mojang.logging.LogUtils.getLogger().warn("[entropybot] watch camera: {}", e.toString());
        }
    }

    /** Once a client tick, in a world: the yaw eases towards the walking direction. Never throws. */
    public void tick() {
        WatchSettings.INSTANCE.ensureLoaded();      // TLL 32: once, at the first tick in a world (never throws)
        TunnelView.INSTANCE.tickYaw();     // v2: the view turns slowly with the bot's walking direction
        try {
            shotFallback();
        } catch (Throwable e) {
            if (errors++ < 5) com.mojang.logging.LogUtils.getLogger().warn("[entropybot] watch shot: {}", e.toString());
        }
        if (!on) return;
        try {
            net.minecraft.client.player.LocalPlayer p = net.minecraft.client.Minecraft.getInstance().player;
            if (p == null) return;
            double x = p.getX(), z = p.getZ();
            if (!haveYaw) {
                yaw = p.getYRot();
                tickEndYaw = yaw;
                haveYaw = true;
            }
            prevYaw = tickEndYaw;       // frames are drawn from the last tick's yaw to this tick's (v1Angles)
            float py = p.getYRot();
            // watch steer: while the human drives, hold the yaw (turning only with the player's own turn), else follow the walk
            if (haveLast) yaw = SteerRules.nextYaw(WatchSteer.INSTANCE.humanDriving(), yaw, follow(yaw, x - lastX, z - lastZ), turn(lastPlayerYaw, py));
            lastPlayerYaw = py;
            lastX = x;
            lastZ = z;
            haveLast = true;
            tickEndYaw = yaw;
        } catch (Throwable e) {
            if (errors++ < 5) com.mojang.logging.LogUtils.getLogger().warn("[entropybot] watch camera: {}", e.toString());
        }
    }
}
